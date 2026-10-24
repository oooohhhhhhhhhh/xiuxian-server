package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minecraft 认证链路的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code MinecraftAuthService} 原来 225 行里 4 处
 * {@code DatabaseManager.getConnection()}，而它扛着"MC 注册 → 建角色"和
 * "MC 绑定 → 校验密码"两条账号链路。{@code doRegisterComplete} 的失败回滚是
 * "先 {@code unbindByMcUuid} 再 {@code deleteUser}"两步，DAO 若自己另开连接，
 * 外层事务会被劈成多次独立提交 —— 用户插了、角色没建的半截账号会留在库里，
 * 而 MC 侧已经显示"注册成功"。</p>
 */
class MinecraftAuthDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return MinecraftAuthDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/minecraft/adapter/MinecraftAuthService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/MinecraftAuthDAO.java";
    }

    @Override
    protected java.util.List<String> serviceAnchors() {
        return java.util.List.of(
                // 密码哈希与比对属于安全语义，必须留在服务层
                "BCrypt.hashpw(",
                "BCrypt.checkpw(",
                // 注册/绑定/解绑的对话流程与对玩家可见的文案
                "doRegisterComplete",
                "doBindComplete",
                "handlePendingFlow",
                "PermissionService.assignDefaultRole",
                "bindingService.bind(",
                "bindingService.unbindByMcUuid(",
                "用户名或密码错误，请重新绑定。",
                "注册失败: ",
                "注册失败，请稍后重试。",
                "创建角色失败: ",
                "你已注册角色，无需重复注册。");
    }

    @Test
    @DisplayName("四个意图各一对重载，自取连接版整体委托")
    void everyIntentHasBothOverloads() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String intent : new String[]{
                "findCredentialByUsername", "isUsernameExists", "insertUser", "deleteUser"}) {
            // 3 次 = 自取连接版签名 + Connection 版签名 + 自取连接版里那一行委托调用。
            // 委托调用与签名同名，所以每个意图恰好 3 次。
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        // 自取连接版必须整体委托，不在自己体内重复 SQL。
        // 按方法签名切片而不是按缩进匹配，重新格式化不断言。
        for (String sig : new String[]{
                "public UserCredential findCredentialByUsername(String username)",
                "public boolean isUsernameExists(String username)",
                "public Long insertUser(String username, String hashedPassword)",
                "public void deleteUser(long userId)"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"),
                    sig + " 没有自己开连接");
            assertFalse(body.contains("SELECT ") || body.contains("INSERT INTO") || body.contains("DELETE "),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
        // Connection 版必须 throws，让事务决定回滚。
        // throws 在方法体的左括号之前，所以直接对整份源码做签名子串匹配。
        for (String sig : new String[]{
                "public UserCredential findCredentialByUsername(Connection conn, String username) throws SQLException",
                "public boolean isUsernameExists(Connection conn, String username) throws SQLException",
                "public Long insertUser(Connection conn, String username, String hashedPassword) throws SQLException",
                "public void deleteUser(Connection conn, long userId) throws SQLException"}) {
            assertTrue(stripComments(read(daoPath())).contains(sig),
                    sig + " 必须 throws SQLException，把失败交给调用方的事务");
        }
    }

    @Test
    @DisplayName("SQL 逐字保留：列清单、自增主键、表名都没动")
    void sqlKeptVerbatim() throws IOException {
        String code = stripComments(read(daoPath()));
        // 自取连接版整体委托之后，每条 SQL 只在 Connection 版里写一次。
        assertEquals(1, countOccurrences(code, "SELECT id, password FROM users WHERE username = ?"),
                "凭据查询的列清单逐字保留（原实现就是 id, password 两列）");
        assertEquals(1, countOccurrences(code, "SELECT COUNT(*) FROM users WHERE username = ?"),
                "用户名占用查询逐字保留");
        assertEquals(1, countOccurrences(code, "INSERT INTO users (username, password) VALUES (?, ?)"),
                "插入用户逐字保留");
        assertEquals(1, countOccurrences(code, "DELETE FROM users WHERE id = ?"),
                "删除用户逐字保留");
        // 注册成功必须拿到自增主键才能建角色，这个 flag 不能丢
        assertEquals(1, countOccurrences(code, "Statement.RETURN_GENERATED_KEYS"),
                "插入用户必须取自增主键");
    }

    @Test
    @DisplayName("失败语义逐字保留：查询降级、写入抛异常")
    void failureSemanticsKept() throws IOException {
        String code = stripComments(read(daoPath()));
        // 凭据查询失败只告警后返回 null：调用方据此回"用户名或密码错误"。
        // 改成抛 RuntimeException 会把"数据库挂了"显示成"密码错误"。
        assertTrue(code.contains("log.error(\"验证用户凭据失败\", e);"),
                "凭据查询失败的告警文案必须逐字保留");
        assertTrue(code.contains("log.error(\"查询用户名失败\", e);"),
                "用户名占用查询失败的告警文案必须逐字保留");
        // 写路径失败必须抛异常（注册回滚依赖它触发）
        assertTrue(code.contains("throw new RuntimeException(\"用户插入失败\", e);"),
                "插入失败的异常文案逐字保留");
        assertTrue(code.contains("throw new RuntimeException(\"删除用户失败\", e);"),
                "删除失败的异常文案逐字保留");
        // 两个查询的降级：Connection 版直接抛 SQLException，由调用方决定
        assertTrue(code.contains("public UserCredential findCredentialByUsername(Connection conn, String username) throws SQLException"),
                "Connection 版要声明 throws SQLException");
        assertTrue(code.contains("public boolean isUsernameExists(Connection conn, String username) throws SQLException"),
                "Connection 版要声明 throws SQLException");
    }

    @Test
    @DisplayName("BCrypt 比对不在 DAO 里，DAO 不掺安全语义")
    void bcryptStaysOutOfDao() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("BCrypt"),
                "密码哈希与比对属于安全语义，必须留在服务层；DAO 只负责 SQL");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("BCrypt.checkpw(password, credential.passwordHash())"),
                "服务层仍要自己做 BCrypt 比对，且必须用 DAO 取回的哈希");
        assertTrue(service.contains("BCrypt.hashpw(password, BCrypt.gensalt())"),
                "注册时的哈希不能搬进 DAO");
    }

    @Test
    @DisplayName("凭据用 record 承载，两列一起取回")
    void credentialCarriesIdAndHash() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("public record UserCredential(long id, String passwordHash)"),
                "id 与 password 两列必须在同一次查询里取回，分两次查会让"
                        + "改密前后的哈希与 id 对不上");
        assertEquals(1, countOccurrences(code, "new UserCredential(rs.getLong(\"id\"), rs.getString(\"password\"))"),
                "按列名取 id 与 password（只在 Connection 版里写一次）");
    }
}
