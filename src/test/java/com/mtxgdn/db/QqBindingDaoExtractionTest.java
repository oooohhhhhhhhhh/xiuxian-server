package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OneBot QQ 绑定模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code QqBindingService} 原来 113 行里 5 处
 * {@code DatabaseManager.getConnection()}，而它被 QQ 适配器的整条账号流程
 * （注册 / 绑定 / 改密 / 注销）共用 —— 注销是"删角色 → 删用户 → 解绑"三步，
 * 失败回滚是"先解绑再删用户"两步。DAO 若自己另开连接，外层事务会被劈成
 * 多次独立提交，半截账号就留在了库里。</p>
 */
class QqBindingDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return QqBindingDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/onebot/QqBindingService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/QqBindingDAO.java";
    }

    @Override
    protected java.util.List<String> serviceAnchors() {
        return java.util.List.of(
                // 先查后插的去重是业务，异常文案是对玩家的契约
                "该QQ号已绑定用户: ",
                "该用户已绑定QQ号: ",
                "该QQ号未绑定任何用户",
                "该用户未绑定任何QQ",
                // 跨平台展示查询的策略留在服务层
                "findAnyBindingByUserId");
    }

    @Test
    @DisplayName("五个意图各一对重载，自取连接版直接委托")
    void everyIntentHasBothOverloads() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String intent : new String[]{"findByQq", "findByUserId", "insertBinding", "deleteByQq", "deleteByUserId"}) {
            // 3 次 = 两个签名 + 自取连接版里那一行委托调用。
            // 委托调用与签名同名，所以每个意图都恰好 3 次。
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        // 每个自取连接版都必须整体委托，不在自己体内重复 SQL。
        // 按方法签名切片而不是按缩进匹配，重新格式化不断言。
        for (String sig : new String[]{
                "public QqBinding findByQq(String qqNumber)",
                "public QqBinding findByUserId(Long userId)",
                "public void insertBinding(String qqNumber, Long userId)",
                "public int deleteByQq(String qqNumber)",
                "public int deleteByUserId(Long userId)"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"),
                    sig + " 没有自己开连接");
            assertFalse(body.contains("SELECT ") || body.contains("INSERT INTO") || body.contains("DELETE "),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
        assertTrue(bodyOf(code, "public QqBinding findByQq(String qqNumber)")
                        .contains("return findByQq(conn, qqNumber);"),
                "findByQq 的自取连接版应直接委托 Connection 版");
        assertTrue(bodyOf(code, "public int deleteByQq(String qqNumber)")
                        .contains("return deleteByQq(conn, qqNumber);"),
                "deleteByQq 的自取连接版应直接委托 Connection 版");
    }

    @Test
    @DisplayName("列清单与 SQL 逐字保留，没有新增 LIMIT/IGNORE")
    void sqlKeptVerbatim() throws IOException {
        String code = stripComments(read(daoPath()));
        // 自取连接版委托之后，每条 SQL 在 DAO 里只剩 Connection 版一处。
        // 这正是委托要达到的效果：同一条语句只写一次，改漏了不存在。
        assertEquals(1, countOccurrences(code, "SELECT id, qq_number, user_id, created_at FROM qq_bindings WHERE qq_number = ?"),
                "按 QQ 号查询的语句逐字保留");
        assertEquals(1, countOccurrences(code, "SELECT id, qq_number, user_id, created_at FROM qq_bindings WHERE user_id = ?"),
                "按用户查询的语句逐字保留");
        assertEquals(1, countOccurrences(code, "INSERT INTO qq_bindings (qq_number, user_id) VALUES (?, ?)"),
                "插入语句逐字保留");
        assertEquals(1, countOccurrences(code, "DELETE FROM qq_bindings WHERE qq_number = ?"),
                "按 QQ 号删除逐字保留");
        assertEquals(1, countOccurrences(code, "DELETE FROM qq_bindings WHERE user_id = ?"),
                "按用户删除逐字保留");
        // 原实现没有 LIMIT 1（靠 rs.next() 取首行），不要"顺手优化"
        assertFalse(code.contains("LIMIT 1"),
                "原实现没有 LIMIT 1，取首行靠 rs.next()；加 LIMIT 会改变语义");
        assertFalse(code.contains("INSERT OR IGNORE"), "加 IGNORE 会把重复绑定变成静默成功");
        assertFalse(code.contains("ON CONFLICT"), "加 ON CONFLICT 同样抹掉拒绝语义");
        assertFalse(code.contains("ON DUPLICATE KEY"), "加 ON DUPLICATE KEY 同样抹掉拒绝语义");
    }

    @Test
    @DisplayName("与官方绑定 DAO 不共用代码：没有 app_id 维度")
    void noAppIdDimensionInOneBotDao() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("app_id"),
                "qq_bindings 没有 app_id 列，OneBot 渠道不掺 openid 那套 AppID 隔离逻辑");
        assertFalse(code.contains("qq_official_bindings"),
                "两个 DAO 按表分开，不互相写对方的表");
        assertFalse(code.contains("CASE WHEN"),
                "官方通道的「本机器人优先、历史行兜底」排序不属于这里");
    }

    @Test
    @DisplayName("跨平台展示查询仍走官方 DAO，不在本类复制 SQL")
    void crossPlatformQueryDelegatesToOfficialDao() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("new QqOfficialBindingDAO().findByUserIdAnyApp(userId)"),
                "跨平台查询应先查 OneBot 表再查官方表，第二步委托给官方 DAO");
        assertFalse(service.contains("SELECT "),
                "服务层不该再出现 SQL 语句");
    }
}
