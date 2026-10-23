package com.mtxgdn.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 死连接必须能被服务端自己发现并清理。
 *
 * <p><b>缺陷形态</b>：{@code network.read_timeout_ms} 只对「请求事务」生效，
 * 而 WebSocket 升级成功后就没有事务了。客户端网络静默断开
 * （手机切网、笔记本合盖、代理超时）时，服务端<b>永远收不到 close 帧</b>：</p>
 * <ul>
 *   <li>连接对象一直留在 {@code sessionUsers} / {@code userSessions} 里；</li>
 *   <li>玩家表现为"永远在线"，世界广播、私聊都往一个死连接发；</li>
 *   <li>内存与文件句柄只增不减，长期运行后表现为"在线人数虚高"。</li>
 * </ul>
 *
 * <p>应用层虽然有 {@code heartbeat} 动作，但它是<b>客户端驱动</b>的（服务端只回 pong）——
 * 客户端不发或发不出去时，服务端依然察觉不到。所以必须有服务端侧的空闲判定。</p>
 *
 * <p>修复：记录每条连接的最后活跃时间，启动一个 daemon 巡检线程，
 * 超过 {@code network.ws_idle_timeout_ms}（默认 5 分钟）没有任何消息就断开并清理本地状态。</p>
 */
class WebSocketIdleSweepTest {

    private static final Path APP = Paths.get("src/main/java/com/mtxgdn/websocket/GameWebSocketApp.java");
    private static final Path YML = Paths.get("src/main/resources/application.yml");

    @Test
    @DisplayName("每条消息都刷新活跃时间，连接时也要登记")
    void activityIsTracked() throws IOException {
        String code = stripComments(read(APP));

        assertTrue(code.contains("lastActivity"), "必须有 lastActivity 记录");
        assertTrue(code.contains("network.ws_idle_timeout_ms"),
                "空闲超时必须可配置（network.ws_idle_timeout_ms）");

        int connectIdx = code.indexOf("public void onConnect");
        int connectEnd = code.indexOf('}', code.indexOf("lastActivity.put", connectIdx));
        assertTrue(connectIdx > 0 && connectEnd > connectIdx, "onConnect 里应登记活跃时间");
        assertTrue(code.substring(connectIdx, connectEnd).contains("lastActivity.put"),
                "onConnect 必须登记初始活跃时间，否则刚连上就被判空闲");

        int msgIdx = code.indexOf("public void onMessage(WebSocket socket, String text)");
        assertTrue(msgIdx > 0, "找不到 onMessage");
        String msgBody = code.substring(msgIdx, msgIdx + 400);
        assertTrue(msgBody.contains("lastActivity.put"),
                "onMessage 一开始就要刷新活跃时间（任何入站消息都算活着）");

        int closeIdx = code.indexOf("public void onClose(WebSocket socket, DataFrame frame)");
        assertTrue(closeIdx > 0, "找不到 onClose");
        String closeBody = code.substring(closeIdx, closeIdx + 400);
        assertTrue(closeBody.contains("lastActivity.remove"),
                "onClose 必须清理活跃时间，否则 map 只增不减");
    }

    @Test
    @DisplayName("巡检线程必须是 daemon，且超时后即使 close 没触发 onClose 也要清状态")
    void sweeperIsDaemonAndCleansState() throws IOException {
        String code = stripComments(read(APP));

        int sweepIdx = code.indexOf("void closeIdleSockets()");
        assertTrue(sweepIdx > 0, "必须有 closeIdleSockets()");
        int sweepEnd = code.indexOf("MAX_MESSAGE_CHARS", sweepIdx);
        assertTrue(sweepEnd > sweepIdx);
        String body = code.substring(sweepIdx, sweepEnd);

        assertTrue(body.contains("ws.close"), "超时后应尝试关闭连接");
        assertTrue(body.contains("onClose("),
                "即使 close() 没触发 onClose（半开连接正是如此），也必须主动清理本地状态：\n" + body);

        assertTrue(code.contains("setDaemon(true)"),
                "巡检线程必须是 daemon，否则会阻止 JVM 退出");
        assertTrue(code.contains("new Thread("),
                "巡检线程应命名，便于在线程转储里辨认");
    }

    @Test
    @DisplayName("配置文件里必须有这个键，并且说明了 read_timeout_ms 对 WS 无效")
    void configDocumentsTheDifference() throws IOException {
        String yml = read(YML);
        assertTrue(yml.contains("ws_idle_timeout_ms"),
                "application.yml 必须提供 network.ws_idle_timeout_ms");
        assertTrue(yml.contains("对 WebSocket 无效") || yml.contains("升级成功后"),
                "配置注释要写清楚 read_timeout_ms 对 WebSocket 不生效，"
                        + "否则运维会以为调它就能解决死连接");
    }

    @Test
    @DisplayName("巡检不能在类加载时就起线程（没有连接的进程不该多线程）")
    void sweeperStartsLazily() throws IOException {
        String code = stripComments(read(APP));
        assertTrue(code.contains("startIdleSweeperOnce"),
                "应在首次连接时才启动巡检");
        int connectIdx = code.indexOf("public void onConnect");
        String connectBody = code.substring(connectIdx, connectIdx + 400);
        assertTrue(connectBody.contains("startIdleSweeperOnce()"),
                "onConnect 里应触发懒启动");
        assertFalse(code.contains("static { ") && code.contains("scheduleAtFixedRate"),
                "不要在静态初始化块里启动巡检线程");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "找不到文件: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
