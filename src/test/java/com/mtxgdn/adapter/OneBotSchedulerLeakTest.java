package com.mtxgdn.adapter;

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
 * 热重载 OneBot 适配器时不能泄漏调度线程。
 *
 * <p><b>缺陷形态</b>：{@code OneBotWebSocketServer} 构造时会创建一个
 * 每 12 小时跑一次「黑名单续期禁言」的 {@code ScheduledExecutorService}，
 * 并提供 {@code shutdown()} 来停它。但 {@code AdapterManager.cleanupOneBotServer()}
 * 只做了两件事：{@code HttpServer.shutdownNow()} 和
 * {@code WebSocketEngine.unregister(...)}，<b>从不调用 shutdown()</b>。</p>
 *
 * <p>后果：</p>
 * <ul>
 *   <li>每次热重载泄漏一个调度线程（且旧实例还在对已下线的机器人做禁言续期）；</li>
 *   <li>线程还是非 daemon 的，泄漏多了还会让 JVM 关不掉，只能强杀。</li>
 * </ul>
 *
 * <p>修复：清理时显式调用 {@code wsServer.shutdown()}；
 * 调度线程改为 daemon 且命名，即使漏调也不会阻止 JVM 退出。</p>
 */
class OneBotSchedulerLeakTest {

    private static final Path ADAPTER_MANAGER =
            Paths.get("src/main/java/com/mtxgdn/adapter/AdapterManager.java");
    private static final Path ONE_BOT_SERVER =
            Paths.get("src/main/java/com/mtxgdn/onebot/OneBotWebSocketServer.java");

    @Test
    @DisplayName("cleanupOneBotServer 必须调用 wsServer.shutdown()")
    void cleanupStopsScheduler() throws IOException {
        String code = stripComments(read(ADAPTER_MANAGER));
        int start = code.indexOf("private static void cleanupOneBotServer");
        assertTrue(start >= 0, "找不到 cleanupOneBotServer");
        int end = code.indexOf("private static void startQqOfficial", start);
        assertTrue(end > start, "找不到 cleanupOneBotServer 的结尾");
        String body = code.substring(start, end);

        assertTrue(body.contains("unregister"), "仍应先从 WebSocketEngine 反注册");
        assertTrue(body.contains("shutdown()"),
                "cleanupOneBotServer 必须调用 OneBotWebSocketServer.shutdown()，"
                        + "否则每次热重载泄漏一个调度线程：\n" + body);
    }

    @Test
    @DisplayName("续期调度线程必须是 daemon 且命名")
    void schedulerThreadIsDaemonAndNamed() throws IOException {
        String code = stripComments(read(ONE_BOT_SERVER));
        int idx = code.indexOf("muteRenewalScheduler");
        assertTrue(idx >= 0, "找不到 muteRenewalScheduler");
        String window = code.substring(idx, Math.min(code.length(), idx + 600));

        assertTrue(window.contains("setDaemon(true)"),
                "续期调度线程必须是 daemon，否则漏调 shutdown 时 JVM 关不掉：\n" + window);
        assertTrue(window.contains("new Thread("),
                "应通过线程工厂给调度线程命名，便于在线程转储里辨认");
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

    @Test
    @DisplayName("OneBotWebSocketServer.shutdown() 必须存在且停掉调度器")
    void shutdownStopsScheduler() throws IOException {
        String code = stripComments(read(ONE_BOT_SERVER));
        int start = code.indexOf("public void shutdown()");
        assertTrue(start >= 0, "OneBotWebSocketServer 必须有 shutdown()");
        String window = code.substring(start, Math.min(code.length(), start + 300));
        assertFalse(window.contains("muteRenewalScheduler = null"),
                "不该把字段置空（其他线程可能还在用），应调用 shutdown()");
        assertTrue(window.contains("muteRenewalScheduler.shutdown"),
                "shutdown() 里必须停掉 muteRenewalScheduler：\n" + window);
    }
}
