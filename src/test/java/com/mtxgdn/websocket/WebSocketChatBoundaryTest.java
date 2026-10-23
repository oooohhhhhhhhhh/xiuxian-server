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
 */
class WebSocketChatBoundaryTest {

    private static final Path WS_SOURCE =
            Paths.get("src/main/java/com/mtxgdn/websocket/GameWebSocketApp.java");

    @Test
    @DisplayName("世界聊天必须检查 game.chat.world 权限")
    void worldChatRequiresPermission() throws IOException {
        String code = stripComments(readSource());
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
        String code = stripComments(readSource());
        int start = code.indexOf("private void handleChat(");
        assertTrue(start >= 0);
        int end = code.indexOf("private void handlePrivateChat(", start);
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
        String code = stripComments(readSource());
        int start = code.indexOf("private void handlePrivateChat(");
        int end = code.indexOf("private void handleChatHistory(", start);
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
        assertEquals(20, GameWebSocketApp.clampLimit(20, 20, 100), "合法值原样返回");
        assertEquals(100, GameWebSocketApp.clampLimit(2_000_000_000, 20, 100), "超大值夹到上限");
        assertEquals(20, GameWebSocketApp.clampLimit(-1, 20, 100), "负数回落默认（SQLite 的 LIMIT -1 = 不限）");
        assertEquals(20, GameWebSocketApp.clampLimit(0, 20, 100), "0 回落默认");
        assertEquals(100, GameWebSocketApp.clampLimit(100, 20, 100), "正好等于上限时保留");
    }

    @Test
    @DisplayName("handleRanking 必须夹过 limit 再进 SQL")
    void rankingClampsLimit() throws IOException {
        String code = stripComments(readSource());
        int start = code.indexOf("private void handleRanking(");
        assertTrue(start >= 0);
        String window = code.substring(start, Math.min(code.length(), start + 700));
        assertTrue(window.contains("clampLimit("),
                "handleRanking 的 limit 没有经过 clampLimit，会直接进 LIMIT ?：\n" + window);
        Matcher m = Pattern.compile("MAX_RANKING_LIMIT\\s*=\\s*(\\d+)").matcher(code);
        assertTrue(m.find(), "缺少 MAX_RANKING_LIMIT 常量");
        assertTrue(Integer.parseInt(m.group(1)) <= 1000,
                "排行榜上限不该超过 1000，实际: " + m.group(1));
    }

    private static String readSource() throws IOException {
        assertTrue(Files.exists(WS_SOURCE), "找不到 GameWebSocketApp.java: " + WS_SOURCE.toAbsolutePath());
        return Files.readString(WS_SOURCE, StandardCharsets.UTF_8);
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
