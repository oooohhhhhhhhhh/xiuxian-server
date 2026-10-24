package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MC 绑定模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code MinecraftPlayerBindingService} 原来 108 行里 4 处
 * {@code DatabaseManager.getConnection()}，而它被 {@code MinecraftAuthService}
 * 的注册/绑定/解绑链路共用 —— {@code doRegisterComplete} 的失败回滚是
 * "先 {@code unbindByMcUuid} 再 {@code deleteUser}"两步。DAO 若自己另开连接，
 * 外层事务会被劈成多次独立提交，半截账号会留在库里。</p>
 */
class MinecraftPlayerBindingDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return MinecraftPlayerBindingDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/minecraft/adapter/MinecraftPlayerBindingService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/MinecraftPlayerBindingDAO.java";
    }

    @Override
    protected java.util.List<String> serviceAnchors() {
        return java.util.List.of(
                // 一个 MC 账号对一个游戏账号的互斥约束留在服务层（先查后插）
                "checkDuplicateBinding",
                "该MC玩家已绑定用户: ",
                "该用户已绑定MC玩家: ",
                // 删不动就抛的业务契约
                "该MC玩家未绑定任何用户");
    }

    @Test
    @DisplayName("四个意图各一对重载，自取连接版整体委托")
    void everyIntentHasBothOverloads() throws IOException {
        String code = stripComments(read(daoPath()));
        // 一对重载 = 2 次（自取连接版签名 + Connection 版签名）。
        // 3 次 = 两个签名 + 自取连接版里那一行委托调用。
        for (String intent : new String[]{"findByMcUuid", "findByMcName", "findByUserId"}) {
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        for (String intent : new String[]{"insertBinding", "deleteByMcUuid", "updateMcName"}) {
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        // 自取连接版必须整体委托，不在自己体内重复 SQL。
        // 按方法签名切片而不是按缩进匹配，重新格式化不断言。
        for (String sig : new String[]{
                "public MinecraftPlayerBinding findByMcUuid(String mcUuid)",
                "public MinecraftPlayerBinding findByMcName(String mcName)",
                "public MinecraftPlayerBinding findByUserId(Long userId)",
                "public void insertBinding(String mcUuid, String mcName, Long userId)",
                "public int deleteByMcUuid(String mcUuid)",
                "public void updateMcName(String mcUuid, String newName)"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"),
                    sig + " 没有自己开连接");
            assertFalse(body.contains("SELECT ") || body.contains("INSERT INTO") || body.contains("DELETE "),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
    }

    @Test
    @DisplayName("列清单与 SQL 逐字保留，没有新增 LIMIT/IGNORE")
    void sqlKeptVerbatim() throws IOException {
        String code = stripComments(read(daoPath()));
        // 委托之后每条 SQL 只写在 Connection 版里，所以恰好 1 次。
        assertEquals(1, countOccurrences(code, "SELECT id, mc_uuid, mc_name, user_id, created_at FROM mc_bindings WHERE mc_uuid = ?"),
                "按 UUID 查询的列清单逐字保留");
        assertEquals(1, countOccurrences(code, "SELECT id, mc_uuid, mc_name, user_id, created_at FROM mc_bindings WHERE mc_name = ?"),
                "按昵称查询的列清单逐字保留");
        assertEquals(1, countOccurrences(code, "SELECT id, mc_uuid, mc_name, user_id, created_at FROM mc_bindings WHERE user_id = ?"),
                "按用户查询的列清单逐字保留");
        assertEquals(1, countOccurrences(code, "INSERT INTO mc_bindings (mc_uuid, mc_name, user_id) VALUES (?, ?, ?)"),
                "插入语句逐字保留");
        assertEquals(1, countOccurrences(code, "DELETE FROM mc_bindings WHERE mc_uuid = ?"),
                "解绑语句逐字保留");
        assertEquals(1, countOccurrences(code, "UPDATE mc_bindings SET mc_name = ? WHERE mc_uuid = ?"),
                "改昵称语句逐字保留");
        // 原实现没有 LIMIT 1（靠 rs.next() 取首行），不要"顺手优化"
        assertFalse(code.contains("LIMIT 1"),
                "原实现没有 LIMIT 1，取首行靠 rs.next()；加 LIMIT 会改变语义");
        // 互斥是服务层先查后插的职责，DAO 层去重会把拒绝变成静默成功
        assertFalse(code.contains("INSERT OR IGNORE"), "加 IGNORE 会把重复绑定变成静默成功");
        assertFalse(code.contains("INSERT IGNORE"), "加 IGNORE 会把重复绑定变成静默成功");
        assertFalse(code.contains("ON CONFLICT"), "加 ON CONFLICT 同样抹掉拒绝语义");
        assertFalse(code.contains("ON DUPLICATE KEY"), "加 ON DUPLICATE KEY 同样抹掉拒绝语义");
    }

    @Test
    @DisplayName("互斥约束：DAO 的 INSERT 是裸插入，去重在服务层")
    void mutualExclusionStaysInService() throws IOException {
        String dao = stripComments(read(daoPath()));
        // DAO 不做任何查重，所以 insertBinding 方法体里只能有一个 prepareStatement
        String insertBody = bodyOf(dao, "public void insertBinding(Connection conn, String mcUuid, String mcName, Long userId)");
        assertFalse(insertBody.contains("SELECT "),
                "DAO 的插入不该自己查重：先查后插的互斥是服务层职责");
        assertFalse(insertBody.contains("COUNT("),
                "DAO 的插入不该自己统计重复行");

        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("checkDuplicateBinding(mcUuid, userId);"),
                "bind 必须先做互斥检查再插入，顺序不能反");
        assertTrue(service.contains("bindingDao.findByMcUuid(mcUuid)"),
                "按 MC 账号查重必须保留");
        assertTrue(service.contains("bindingDao.findByUserId(userId)"),
                "按游戏账号查重必须保留");
        // 两个方向的拒绝文案是业务契约
        assertTrue(service.contains("该MC玩家已绑定用户: " + "existingByUuid.getUserId()")
                || service.contains("\"该MC玩家已绑定用户: \""),
                "MC 账号已被绑定时必须拒绝");
        assertTrue(service.contains("\"该用户已绑定MC玩家: \""),
                "游戏账号已被绑定时必须拒绝");
    }

    @Test
    @DisplayName("解绑的异常契约：0 行抛异常，文案逐字保留")
    void unbindZeroRowsThrows() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("throw new RuntimeException(\"该MC玩家未绑定任何用户\")"),
                "自取连接版在影响 0 行时必须抛异常，文案逐字保留");
        assertTrue(code.contains("throw new RuntimeException(\"解绑MC失败\", e)"),
                "SQL 异常的包装文案逐字保留");
        assertTrue(code.contains("throw new RuntimeException(\"绑定MC失败\", e)"),
                "绑定失败文案逐字保留");
        assertTrue(code.contains("throw new RuntimeException(\"更新MC昵称失败\", e)"),
                "改昵称失败文案逐字保留");
        assertTrue(code.contains("throw new RuntimeException(\"查询MC绑定失败\", e)"),
                "查询失败文案逐字保留");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("throw new RuntimeException(\"该MC玩家未绑定任何用户\")"),
                "Connection 版返回 0 行时由服务层抛同一句异常，"
                        + "调用方 handleUnbind 靠它给玩家回「解绑失败: ...」");
    }

    @Test
    @DisplayName("ResultSet 映射按列名取五列")
    void mappingByColumnName() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String column : new String[]{"id", "mc_uuid", "mc_name", "user_id", "created_at"}) {
            assertTrue(code.contains("rs.getLong(\"" + column + "\")")
                            || code.contains("rs.getString(\"" + column + "\")"),
                    "列 " + column + " 必须按列名取，不能用列序号");
        }
        assertTrue(code.contains("rs.getString(\"created_at\")"),
                "created_at 原实现就是按字符串取的，不能改成 getTimestamp");
        assertEquals(1, countOccurrences(code, "private MinecraftPlayerBinding mapBinding(ResultSet rs)"),
                "映射方法只应有一份，三个查询共用");
    }
}
