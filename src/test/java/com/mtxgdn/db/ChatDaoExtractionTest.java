package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 聊天模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code ChatService} 原来 175 行里 4 处
 * {@code DatabaseManager.getConnection()}，两条 INSERT 把 {@code channel}
 * 写成字面量，两条查询各带 {@code LIMIT ?} 但列数不同（世界 5 列、私聊 6 列）。
 * 限流与正文净化是安全相关的业务，却和 {@code PreparedStatement} 样板
 * 挤在同一个方法里。</p>
 */
class ChatDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return ChatDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/ChatService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/ChatDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of("RateLimiter.allow", "TextSanitizer.sanitizeChatContent",
                "MAX_CONTENT_LENGTH", "MAX_WORLD_HISTORY", "MAX_PRIVATE_HISTORY",
                "setSenderNames", "chat_world:", "chat_private:");
    }

    @Test
    @DisplayName("限流与正文净化必须留在服务层（安全相关的业务）")
    void rateLimitAndSanitizeStayInService() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("com.mtxgdn.util.RateLimiter.allow(\"chat_world:\" + senderPlayerId, 5, 30)"),
                "世界频道每玩家 30 秒 5 条的限流不能丢");
        assertTrue(service.contains("com.mtxgdn.util.RateLimiter.allow(\"chat_private:\" + senderPlayerId, 10, 30)"),
                "私聊每玩家 30 秒 10 条的限流不能丢");
        assertTrue(service.contains("TextSanitizer.sanitizeChatContent(content.trim())"),
                "正文净化必须在写库之前，DAO 不该收到未净化的内容");
        assertTrue(service.contains("content.length() > MAX_CONTENT_LENGTH"),
                "500 字截断必须留在服务层");
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("RateLimiter"), "DAO 不该管限流");
        assertFalse(dao.contains("TextSanitizer"), "DAO 不该管正文净化");
    }

    @Test
    @DisplayName("两条 INSERT 的 channel 仍是字面量，created_at 仍由库生成")
    void insertShapeKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("VALUES ('world', ?, ?)"), "世界频道的 channel 字面量不能改成绑定参数");
        assertTrue(code.contains("VALUES ('private', ?, ?, ?)"), "私聊的 channel 字面量不能改成绑定参数");
        assertTrue(code.contains("INSERT INTO chat_messages (channel, sender_player_id, content)"),
                "世界消息的列清单不能丢");
        assertTrue(code.contains("INSERT INTO chat_messages (channel, sender_player_id, receiver_player_id, content)"),
                "私聊消息的列清单不能丢");
        // created_at 由数据库默认值生成：插入语句里不出现它
        assertFalse(code.contains("created_at) VALUES"), "插入时不该显式写 created_at");
    }

    @Test
    @DisplayName("世界查询 5 列、私聊查询 6 列的差异不能丢")
    void columnCountDifferenceKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("SELECT id, channel, sender_player_id, content, created_at FROM chat_messages WHERE channel = 'world'"),
                "世界频道只查 5 列（没有 receiver_player_id）");
        assertTrue(code.contains("SELECT id, channel, sender_player_id, receiver_player_id, content, created_at FROM chat_messages"),
                "私聊要查 6 列（含 receiver_player_id）");
        assertTrue(code.contains("((sender_player_id = ? AND receiver_player_id = ?) OR (sender_player_id = ? AND receiver_player_id = ?))"),
                "私聊的 OR 对称条件不能丢：A→B 和 B→A 都要查到");
        assertEquals(4, countOccurrences(code, "LIMIT ?"),
                "两条查询各有一对重载（自取连接 + Connection），都要带 LIMIT ?，条数由调用方决定");
    }

    @Test
    @DisplayName("世界消息读出来后 receiverPlayerId 保持 null")
    void worldMessagesLeaveReceiverNull() throws IOException {
        String code = stripComments(read(daoPath()));
        int worldIdx = code.indexOf("String sql = \"SELECT id, channel, sender_player_id, content, created_at FROM chat_messages WHERE channel = 'world'");
        int privateIdx = code.indexOf("String sql = \"SELECT id, channel, sender_player_id, receiver_player_id, content, created_at FROM chat_messages");
        assertTrue(worldIdx >= 0 && privateIdx > worldIdx, "找不到两个查询的 SQL");
        String worldBody = code.substring(worldIdx, privateIdx);
        assertFalse(worldBody.contains("setReceiverPlayerId"),
                "世界频道查询不该填 receiverPlayerId：下游 setSenderNames 正是靠它判空决定要不要补接收方名字");
    }

    @Test
    @DisplayName("limit 的兜底规则留在服务层")
    void limitFallbackStaysInService() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("if (limit <= 0 || limit > MAX_WORLD_HISTORY)"),
                "世界消息的 limit 兜底（超范围回落 50）必须留在服务层");
        assertTrue(service.contains("if (limit <= 0 || limit > MAX_PRIVATE_HISTORY)"),
                "私聊的 limit 兜底（超范围回落 50）必须留在服务层");
        assertTrue(service.contains("limit = 50;"), "兜底值是 50");
    }

}
