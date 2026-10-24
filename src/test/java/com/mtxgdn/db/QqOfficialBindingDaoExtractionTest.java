package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QQ 官方绑定模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code QqOfficialBindingService} 原来 242 行里 7 处
 * {@code DatabaseManager.getConnection()}，其中限定 AppID 与不限 AppID 是
 * <b>同一意图的两种写法</b>（前者是后者的退化形态），散在四个 public 方法里；
 * 解绑还有"先删本机器人、0 行时退回全量删"的两级退化，fallback 语句靠复用
 * 外层 {@code conn} 才和前一条删除落在同一条连接上。</p>
 */
class QqOfficialBindingDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return QqOfficialBindingDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/onebot/QqOfficialBindingService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/QqOfficialBindingDAO.java";
    }

    @Override
    protected java.util.List<String> serviceAnchors() {
        return java.util.List.of(
                // AppID 维度的业务规则必须留在服务层
                "private boolean scoped()",
                "该 QQ 官方适配器未配置 app_id，无法绑定",
                "legacyHint(",
                "isOtherBotLegacyRow(",
                "该 openid 已绑定用户: ",
                "该用户已绑定QQ官方 openid: ",
                // 删不动就报错是业务契约
                "该 openid 未绑定任何用户",
                "该用户未绑定任何QQ官方 openid");
    }

    @Test
    @DisplayName("自取连接的方法数与 Connection 重载数相等")
    void everyPlainMethodHasExactlyOneConnOverload() {
        assertTrue(daoClass().getMethods().length > 0, "DAO 应有 public 方法");
        // DaoExtractionAssertions 已用反射校验"存在重载"；这里补一条数目相等的 sanity check
        int connOverloads = (int) java.util.Arrays.stream(daoClass().getMethods())
                .filter(m -> m.getDeclaringClass() == QqOfficialBindingDAO.class)
                .filter(m -> m.getParameterCount() > 0
                        && m.getParameterTypes()[0] == java.sql.Connection.class)
                .count();
        int plainOverloads = (int) java.util.Arrays.stream(daoClass().getMethods())
                .filter(m -> m.getDeclaringClass() == QqOfficialBindingDAO.class)
                .filter(m -> m.getParameterCount() > 0
                        && m.getParameterTypes()[0] != java.sql.Connection.class)
                .count();
        assertEquals(plainOverloads, connOverloads,
                "每个自取连接的方法都要有一条 Connection 首参重载，否则事务内只能另开连接");
    }

    @Test
    @DisplayName("AppID 隔离与历史兜底的 SQL 原样保留")
    void appIdIsolationAndLegacyFallbackSqlKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("WHERE openid = ? AND (app_id = ? OR app_id = '')"),
                "限定 AppID 的 openid 查询：必须带上历史行（app_id 为空）兜底");
        assertTrue(code.contains("WHERE user_id = ? AND (app_id = ? OR app_id = '')"),
                "限定 AppID 的 user_id 查询：同样要能命中历史行");
        assertEquals(2, countOccurrences(code, "ORDER BY CASE WHEN app_id = ? THEN 0 ELSE 1 END LIMIT 1"),
                "两个限定 AppID 的查询都要让本机器人的绑定排在历史行之前");
        assertTrue(code.contains("WHERE openid = ? LIMIT 1"),
                "不限 AppID 时是另一条退化 SQL（没有 app_id 条件），不能合并");
        assertTrue(code.contains("WHERE user_id = ? LIMIT 1"),
                "不限 AppID 时是另一条退化 SQL，不能合并");
        // 限定 AppID 的 WHERE 出现两次：一次在 SELECT，一次在 DELETE。
        // 这两处必须同步改，所以这里钉住数量，改一处漏一处就会红。
        assertEquals(2, countOccurrences(code, "WHERE openid = ? AND (app_id = ? OR app_id = '')"),
                "SELECT 与 DELETE 的 AppID 限定条件必须同时在，否则解绑删不干净");
        assertEquals(1, countOccurrences(code, "WHERE user_id = ? AND (app_id = ? OR app_id = '')"),
                "限定 AppID 的 user_id 查询只能有一条");
        assertEquals(1, countOccurrences(code, "WHERE openid = ? LIMIT 1"),
                "不限 AppID 的 openid 退化查询只能有一条");
        assertEquals(1, countOccurrences(code, "WHERE user_id = ? LIMIT 1"),
                "不限 AppID 的 user_id 退化查询只能有一条");
    }

    @Test
    @DisplayName("解绑的两级退化必须共用同一条连接")
    void unbindFallbackSharesOneConnection() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("DELETE FROM qq_official_bindings WHERE openid = ? AND (app_id = ? OR app_id = '')"),
                "第一级：只删本机器人（含历史兼容行）");
        assertTrue(code.contains("DELETE FROM qq_official_bindings WHERE openid = ?"),
                "第二级：按 openid 全量删除的兜底语句不能丢");
        // 关键：fallback 必须用 conn.prepareStatement，而不是 DatabaseManager.getConnection()
        assertTrue(code.contains("conn.prepareStatement("),
                "fallback 必须复用调用方连接：另开连接时第一级删除尚未提交，"
                        + "SQLite 下会 SQLITE_BUSY，表现是「明明删掉了」却继续走兜底");
        assertFalse(code.contains("getConnection();\n            return deleteByQq("),
                "自取连接版必须整体委托 Connection 版，不能在自己体内重复 SQL");
    }

    @Test
    @DisplayName("插入不带 IGNORE：去重是服务层先查后插的职责")
    void insertHasNoIgnoreOrConflict() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("INSERT INTO qq_official_bindings (app_id, openid, user_id) VALUES (?, ?, ?)"),
                "插入语句逐字保留");
        assertFalse(code.contains("INSERT OR IGNORE INTO qq_official_bindings"),
                "加 IGNORE 会把「重复绑定」变成静默成功，业务契约就没了");
        assertFalse(code.contains("ON CONFLICT"),
                "加 ON CONFLICT 同样会抹掉「openid 已绑定」的拒绝语义");
        assertFalse(code.contains("ON DUPLICATE KEY"),
                "加 ON DUPLICATE KEY 同样会抹掉「用户已绑定」的拒绝语义");
    }

    @Test
    @DisplayName("ResultSet → 实体仍复用 QqBinding 承载 openid")
    void mappingReusesQqBindingForOpenid() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("binding.setQqNumber(rs.getString(\"openid\"))"),
                "qqNumber 字段承载 openid 字符串，这是原实现的约定，换成新实体要改动所有调用点");
        assertTrue(code.contains("binding.setAppId(rs.getString(\"app_id\"))"),
                "app_id 必须读出来：legacyHint 与 isOtherBotLegacyRow 都靠它判断历史行");
        assertTrue(code.contains("SELECT id, app_id, openid, user_id, created_at FROM qq_official_bindings"),
                "查询列清单逐字保留");
    }

    @Test
    @DisplayName("服务层仍按 openid/userId 判空后分派到两条查询")
    void serviceStillDispatchesOnScope() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("if (!scoped()) {"), "不限 AppID 的分派逻辑留在服务层");
        assertTrue(service.contains("BINDING_DAO.findByQqAnyApp(openid)"),
                "不限 AppID 走退化查询");
        assertTrue(service.contains("BINDING_DAO.findByQqForApp(openid, appId)"),
                "限定 AppID 走带 app_id 维度的查询");
        assertTrue(service.contains("if (openid == null || openid.isEmpty())"),
                "openid 判空是业务规则，必须留在服务层");
        assertTrue(service.contains("if (userId == null)"),
                "userId 判空是业务规则，必须留在服务层");
    }
}
