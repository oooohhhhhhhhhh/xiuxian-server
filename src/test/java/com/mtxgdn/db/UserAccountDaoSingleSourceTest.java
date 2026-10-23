package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号读写只允许有一份实现。
 *
 * <p><b>缺陷形态</b>（P1-18）：{@code isUsernameExists} / {@code insertUser} /
 * {@code deleteUser} / {@code isValidEmail} / {@code getUsernameByUserId} /
 * {@code updatePassword} 这六个方法在 {@code OneBotWebSocketServer} 与
 * {@code QqOfficialAdapter} 里各存了一份，逐字节相同。</p>
 *
 * <p>复制两份的代价不是"多写了 130 行"，而是改一处忘另一处：
 * 例如给 {@code users} 表加列或改列名时只改了一个通道，另一个通道在运行时才报
 * "no such column"；两份各自演化后，同一个用户名在两个通道上的判断还可能不一致。</p>
 *
 * <p>修复：收敛到 {@link UserAccountDAO}，两个适配器都只调用它。</p>
 */
class UserAccountDaoSingleSourceTest extends TestBase {

    private static final String[] SHARED_METHODS = {
            "isUsernameExists", "insertUser", "deleteUser",
            "isValidEmail", "getUsernameByUserId", "updatePassword"
    };

    @Test
    @DisplayName("两个适配器都不再自己声明这些方法")
    void adaptersDelegateInsteadOfDuplicating() throws IOException {
        for (String file : new String[]{
                "src/main/java/com/mtxgdn/onebot/OneBotWebSocketServer.java",
                "src/main/java/com/mtxgdn/qq/QqOfficialAdapter.java"}) {
            String code = stripComments(read(file));
            for (String method : SHARED_METHODS) {
                assertFalse(code.contains("private boolean " + method + "(")
                                || code.contains("private Long " + method + "(")
                                || code.contains("private void " + method + "(")
                                || code.contains("private String " + method + "("),
                        file + " 里又出现了 " + method + " 的私有实现 —— 账号读写必须只有一份，"
                                + "请改为调用 UserAccountDAO");
            }
            assertTrue(code.contains("UserAccountDAO."),
                    file + " 应该通过 UserAccountDAO 访问 users 表");
        }
    }

    @Test
    @DisplayName("DAO 的基本读写行为")
    void daoRoundTrip() throws Exception {
        String username = "dao_test_" + UUID.randomUUID().toString().substring(0, 8);
        assertFalse(UserAccountDAO.isUsernameExists(username), "新用户名不该存在");

        Long userId = UserAccountDAO.insertUser(username, "hash-v1", username + "@example.com");
        assertNotNull(userId, "插入应返回自增主键");
        assertTrue(UserAccountDAO.isUsernameExists(username), "插入后应存在");
        assertEquals(username, UserAccountDAO.getUsernameByUserId(userId), "按 ID 取回用户名");

        UserAccountDAO.updatePassword(userId, "hash-v2");
        assertEquals("hash-v2", readPassword(userId), "密码应被更新");

        UserAccountDAO.deleteUser(userId);
        assertFalse(UserAccountDAO.isUsernameExists(username), "删除后不该存在");
        assertNull(UserAccountDAO.getUsernameByUserId(userId), "删除后按 ID 应查不到");
    }

    @Test
    @DisplayName("邮箱校验：只做形状校验")
    void emailValidation() {
        assertTrue(UserAccountDAO.isValidEmail("a@b.com"));
        assertTrue(UserAccountDAO.isValidEmail("first.last+tag@sub.example.org"));
        assertFalse(UserAccountDAO.isValidEmail("no-at-sign"));
        assertFalse(UserAccountDAO.isValidEmail(""));
        assertFalse(UserAccountDAO.isValidEmail(null), "null 不该抛 NPE");
        // 注意：原正则只校验"形状"，单段域名（a@b）也算通过。
        // 这里如实锁定现状 —— 本次提交只是把两份复制粘贴收敛成一份，
        // 不夹带校验规则的行为变更。真要收紧，应当单独提一次并评估对存量数据的影响。
        assertTrue(UserAccountDAO.isValidEmail("a@b"), "原正则接受单段域名（保持行为不变）");
    }

    // ==================== 夹具 ====================

    private static String readPassword(long userId) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT password FROM users WHERE id = ?")) {
            stmt.setLong(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String read(String relative) throws IOException {
        Path path = Paths.get(relative);
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
