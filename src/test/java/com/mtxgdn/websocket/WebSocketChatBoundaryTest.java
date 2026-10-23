package com.mtxgdn.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 入口的权限与边界。
 *
 * <p><b>缺陷形态</b>：</p>
 * <ol>
 *   <li>世界聊天 {@code case "chat"} 是唯一没有 {@code checkWsPermission} 的聊天动作
 *       —— {@code chat_private} / {@code chat_history} / {@code rank} 都有，
 *       只有它没有。被禁用 {@code game.chat.world} 的角色照样能往世界频道发言。</li>
 *   <li>{@code handleChat} 广播时用的是客户端<b>原始</b> content，而不是
 *       {@code ChatService} 清洗+截断后的那份：
 *       清洗白做了（客户端收到未清洗原文），长度也没上限 ——
 *       一条 10MB 的发言会被复制给每个在线连接，一次请求放大成 N 份。</li>
 *   <li>{@code handleRanking} 的 {@code limit} 不校验就进 {@code LIMIT ?}：
 *       传 2,000,000,000 会把整张表查出来；负数在 SQLite 里等于"不限条数"。</li>
 * </ol>
 *
 * <p><b>拆分后的扫描范围</b>：{@code GameWebSocketApp} 已经按 action 域拆成
 * {@code WsChatHandler} / {@code WsSocialHandler} 等处理器类，处理函数不再全部住在
 * 入口类里。因此本测试的扫描范围从"一个文件"扩展到"处理器所在的那个文件"：</p>
 * <ul>
 *   <li>权限检查仍在分发入口 {@code GameWebSocketApp}（拆分时特意没有下移到处理器，
 *       否则 53 个 action 的权限门会散到 11 个类里，一眼看不出哪个漏了）；</li>
 *   <li>{@code handleChat} / {@code handlePrivateChat} 在 {@code WsChatHandler}，
 *       {@code handleRanking} 在 {@code WsSocialHandler}，
 *       {@code clampLimit} 与排行榜上限常量在 {@code WsSupport}。</li>
 * </ul>
 * <p>断言本身没有放宽：仍然逐条检查"广播内容必须来自 ChatService"、
 * "limit 必须夹过再进 SQL"、"世界聊天必须检查 game.chat.world"。
 * 变的只是每个断言去哪个文件里找证据。</p>
 */
class WebSocketChatBoundaryTest {

    private static final Path WS_DIR = Paths.get("src/main/java/com/mtxgdn/websocket");
    private static final Path WS_SOURCE = WS_DIR.resolve("GameWebSocketApp.java");
    private static final Path CHAT_SOURCE = WS_DIR.resolve("WsChatHandler.java");
    private static final Path SOCIAL_SOURCE = WS_DIR.resolve("WsSocialHandler.java");
    private static final Path SUPPORT_SOURCE = WS_DIR.resolve("WsSupport.java");

    @Test
    @DisplayName("世界聊天必须检查 game.chat.world 权限")
    void worldChatRequiresPermission() throws IOException {
        String code = stripComments(readSource(WS_SOURCE));
        // 文件里有多处 case "chat"（另有一处是 消息类型→功能模块 的映射表），
        // 这里只看真正调用 handleChat 的那一处。
        int from = 0;
        String dispatchWindow = null;
        while (true) {
            int idx = code.indexOf("case \"chat\":", from);
            if (idx < 0) {
                break;
            }
            String window = code.substring(idx, Math.min(code.length(), idx + 400));
            if (window.contains("handleChat(socket")) {
                dispatchWindow = window;
                break;
            }
            from = idx + 1;
        }
        assertTrue(dispatchWindow != null, "找不到调用 handleChat 的 case \"chat\" 分支");
        assertTrue(dispatchWindow.contains("checkWsPermission")
                        && dispatchWindow.contains("game.chat.world"),
                "世界聊天分支没有检查 game.chat.world 权限（chat_private/chat_history/rank 都有，"
                        + "只有它没有）：\n" + dispatchWindow);
    }

    @Test
    @DisplayName("世界聊天的广播内容必须取自 ChatService 处理后的结果")
    void worldChatBroadcastsSanitizedContent() throws IOException {
        String code = stripComments(readSource(CHAT_SOURCE));
        int start = code.indexOf("void handleChat(");
        assertTrue(start >= 0, "WsChatHandler 里找不到 handleChat");
        int end = code.indexOf("void handlePrivateChat(", start);
        assertTrue(end > start);
        String body = code.substring(start, end);

        assertTrue(body.contains("msg.getContent()"),
                "handleChat 必须用 ChatService 返回的 msg.getContent()（已清洗+截断），"
                        + "不能把客户端原始 content 广播出去");
        // 广播用的 chatData 里不能直接塞原始 content 变量
        assertFalse(body.contains("chatData.addProperty(\"content\", content)"),
                "chatData 里仍然是客户端原始 content —— 清洗被绕过、长度不受限");
    }

    @Test
    @DisplayName("私聊转发内容同样必须取自 ChatService")
    void privateChatForwardsSanitizedContent() throws IOException {
        String code = stripComments(readSource(CHAT_SOURCE));
        int start = code.indexOf("void handlePrivateChat(");
        int end = code.indexOf("void handleChatHistory(", start);
        assertTrue(start >= 0 && end > start);
        String body = code.substring(start, end);

        assertFalse(body.contains("chatData.addProperty(\"content\", content)"),
                "私聊 chatData 里仍然是客户端原始 content");
        assertTrue(body.contains("getContent()"),
                "私聊必须使用 ChatService 返回对象的 getContent()");
    }

    @Test
    @DisplayName("clampLimit：越界值被夹住，非法值回落默认")
    void clampLimitBehaves() {
        assertEquals(20, WsSupport.clampLimit(20, 20, 100), "合法值原样返回");
        assertEquals(100, WsSupport.clampLimit(2_000_000_000, 20, 100), "超大值夹到上限");
        assertEquals(20, WsSupport.clampLimit(-1, 20, 100), "负数回落默认（SQLite 的 LIMIT -1 = 不限）");
        assertEquals(20, WsSupport.clampLimit(0, 20, 100), "0 回落默认");
        assertEquals(100, WsSupport.clampLimit(100, 20, 100), "正好等于上限时保留");
    }

    @Test
    @DisplayName("handleRanking 必须夹过 limit 再进 SQL")
    void rankingClampsLimit() throws IOException {
        String code = stripComments(readSource(SOCIAL_SOURCE));
        int start = code.indexOf("void handleRanking(");
        assertTrue(start >= 0, "WsSocialHandler 里找不到 handleRanking");
        String window = code.substring(start, Math.min(code.length(), start + 700));
        assertTrue(window.contains("clampLimit("),
                "handleRanking 的 limit 没有经过 clampLimit，会直接进 LIMIT ?：\n" + window);
        // 上限常量随 clampLimit 一起搬到了 WsSupport：取值仍然要钉住
        String support = stripComments(readSource(SUPPORT_SOURCE));
        Matcher m = Pattern.compile("MAX_RANKING_LIMIT\\s*=\\s*(\\d+)").matcher(support);
        assertTrue(m.find(), "缺少 MAX_RANKING_LIMIT 常量");
        assertTrue(Integer.parseInt(m.group(1)) <= 1000,
                "排行榜上限不该超过 1000，实际: " + m.group(1));
    }

    private static String readSource(Path path) throws IOException {
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
