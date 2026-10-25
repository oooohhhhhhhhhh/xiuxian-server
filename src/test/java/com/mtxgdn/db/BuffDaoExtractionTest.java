package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Buff 域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code BuffService} 原来 4 处
 * {@code DatabaseManager.getConnection()}，另有 8 处写在
 * {@code runTransaction} lambda 里的 SQL。</p>
 *
 * <p>这个域的核心是<b>认领</b>语义，不是 CRUD：增益的属性加成被直接加到
 * 了 {@code players} 行上，所以"删掉一行增益"和"回退属性"必须严格一一
 * 对应。原实现用条件 DELETE 的命中行数当认领凭证 ——
 * {@code executeUpdate() == 1} 才是"这一行归我处理"的证据。</p>
 */
class BuffDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return BuffDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/BuffService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/BuffDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // 属性回退必须留在服务层（DAO 不能依赖 PlayerService）
                "playerService.addAttack(conn, playerId",
                "playerService.addDefense(conn, playerId",
                "playerService.addSpeed(conn, playerId",
                "playerService.addSpirit(conn, playerId",
                // 兜底任务的模块门控
                "isEnabledOrUnknown(\"buff\")",
                // 事务编排
                "expirePlayerBuffs(conn, playerId",
                "rollbackIfClaimed(conn, playerId",
                // 兜底任务的异常日志
                "LOG.warn(\"增益过期兜底回收任务异常: \" + t)");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of(
                "PlayerService", "ServiceRegistry", "I18n", "BuiltInPluginRegistry",
                "setAutoCommit(", "runTransaction(", "commit(", "rollback(");
    }

    @Test
    @DisplayName("活跃增益 id 列表成对，自取连接版委托")
    void activeBuffIdsHaveBothOverloads() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(3, countOccurrences(code, "findActiveBuffIds("),
                "两个签名 + 自取连接版里那一行委托");
        String self = bodyOf(code, "public List<String> findActiveBuffIds(long playerId, long now)");
        assertTrue(self.contains("return findActiveBuffIds(conn, playerId, now)"),
                "自取连接版必须整体委托，SQL 只写一份");
        assertTrue(self.contains("throw new RuntimeException(\"查询活跃 Buff 失败\", e)"),
                "文案逐字保留");
    }

    @Test
    @DisplayName("活跃增益只取 id：调用方要逐条走完整移除路径")
    void activeBuffIdsSelectOnlyId() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public List<String> findActiveBuffIds(Connection conn, long playerId, long now)");
        assertTrue(body.contains("SELECT buff_id FROM player_buffs WHERE player_id = ? AND expire_time > ?"),
                "只取 id：removeAllBuffs 要逐条走 removeBuff 去回退属性，不需要加成本身");
        assertTrue(body.contains("ps.setLong(1, playerId)"), "按玩家过滤");
        assertTrue(body.contains("ps.setLong(2, now)"), "只取未过期的");
    }

    @Test
    @DisplayName("兜底扫描不带 player_id，且失败完全静默")
    void fallbackScanIsGlobalAndSilent() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public List<ExpiredBuffRow> findAllExpiredBuffs(Connection conn, long now)");
        assertTrue(body.contains("SELECT player_id, buff_id, attack_bonus, defense_bonus, "
                        + "speed_bonus, spirit_bonus "),
                "六列：兜底扫描要跨玩家，所以必须带 player_id");
        assertTrue(body.contains("FROM player_buffs WHERE expire_time <= ?"),
                "全表：它要覆盖离线玩家，而惰性路径只处理正在访问的那个玩家");
        assertFalse(body.contains("player_id = ?"),
                "刻意不带 player_id 过滤");

        String self = bodyOf(code, "public List<ExpiredBuffRow> findAllExpiredBuffs(long now)");
        assertTrue(self.contains("return List.of();"),
                "失败完全静默返回空列表：它是每分钟跑的定时任务，"
                        + "数据库短暂不可用时刷日志只会淹没真正的问题");
        assertFalse(self.contains("LOG."), "原实现这里连 warn 都没有");
    }

    @Test
    @DisplayName("认领是条件 DELETE，命中 1 才算成功")
    void claimUsesConditionalDeleteExactlyOne() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public boolean deleteExpiredBuffIfClaimed(Connection conn, long playerId, String buffId, long now)");
        assertTrue(body.contains("DELETE FROM player_buffs "),
                "条件 DELETE");
        assertTrue(body.contains("+ \"WHERE player_id = ? AND buff_id = ? AND expire_time <= ?\""),
                "条件里带 expire_time <= ?：玩家在期间重新嗑药刷新了时长时不会被误删、误扣");
        assertTrue(body.contains("return ps.executeUpdate() == 1"),
                "严格等于 1 才算认领成功。用 == 1 而不是 > 0："
                        + "主键约束下不可能删掉多于一行，但真发生了 > 0 会让"
                        + "「删了 3 行却只回退 1 行的属性」变成静默的账目不平");
        assertTrue(body.contains("LOG.warn(\"删除过期增益失败 player=\" + playerId + \" buff=\" + buffId"),
                "删除失败只 warn 并返回 false：让调用方跳过这一条继续处理其余的");
    }

    @Test
    @DisplayName("过期结算的查询失败只 warn 不抛")
    void expireQuerySwallowsFailure() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public List<ExpiredBuffRow> findExpiredBuffsForPlayer(Connection conn, long playerId, long now)");
        assertTrue(body.contains("SELECT buff_id, attack_bonus, defense_bonus, speed_bonus, spirit_bonus "),
                "五列：结算要知道该扣多少，但不需要 player_id（调用方已经知道）");
        assertTrue(body.contains("FROM player_buffs WHERE player_id = ? AND expire_time <= ?"),
                "只结算已过期的");
        assertTrue(body.contains("LOG.warn(\"查询过期增益失败 player=\" + playerId"),
                "查询失败只 warn：结算失败不该让「查一下我有几个 buff」这种只读请求报错");
        assertTrue(body.contains("return List.of();"), "失败返回空列表");
        assertFalse(body.contains("throws SQLException"), "不抛给调用方");
    }

    @Test
    @DisplayName("SUM 用 COALESCE 归零，列名过白名单")
    void sumActiveBonusUsesCoalesceAndWhitelist() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public int sumActiveBonus(Connection conn, long playerId, long now, String column)");
        assertTrue(body.contains("SELECT COALESCE(SUM(\" + requireBonusColumn(column) + \"), 0) as total "),
                "COALESCE 不能省：没有任何增益行时 SUM 返回 NULL，"
                        + "显式归零让「没有增益」与「加成恰好是 0」同形");
        assertTrue(body.contains("FROM player_buffs WHERE player_id = ? AND expire_time > ?"),
                "只统计仍然有效的");
        assertTrue(body.contains("requireBonusColumn(column)"),
                "列名要拼进 SQL，必须过白名单：DAO 是 public 的，"
                        + "不该指望调用方永远守规矩");
        assertTrue(code.contains("List.of(\"attack_bonus\", \"defense_bonus\", \"speed_bonus\", \"spirit_bonus\")"),
                "四个合法列名的白名单");
        assertTrue(code.contains("throw new IllegalArgumentException(\"未知的增益列: \" + column)"),
                "白名单外直接拒");
    }

    @Test
    @DisplayName("clearAllBuffs 的汇总不带时间过滤，reload 的带")
    void twoBonusSumsDifferOnTimeFilter() throws IOException {
        String code = stripComments(read(daoPath()));
        String all = bodyOf(code, "public BonusSum sumAllBonuses(Connection conn, long playerId)");
        assertTrue(all.contains("FROM player_buffs WHERE player_id = ?"),
                "clearAllBuffs 要删全部行，所以汇总也必须是全部行 —— "
                        + "带过滤会漏掉已过期但还没被结算的行，"
                        + "那些行的加成会永久留在 players 上");
        assertFalse(all.contains("expire_time"), "刻意不带时间过滤");

        String valid = bodyOf(code, "public BonusSum sumStillValidBonuses(Connection conn, long playerId, long now)");
        assertTrue(valid.contains("FROM player_buffs WHERE player_id = ? AND expire_time > ?"),
                "reloadBuffsOnStartup 是「加」：只加仍然有效的");
    }

    @Test
    @DisplayName("同 buffId 已存在只刷新时长，不叠加属性")
    void existsCheckIgnoresExpireTime() throws IOException {
        String code = stripComments(read(daoPath()));
        String exists = bodyOf(code, "public boolean buffExists(Connection conn, long playerId, String buffId)");
        assertTrue(exists.contains("SELECT id FROM player_buffs WHERE player_id = ? AND buff_id = ?"),
                "判存在用独立 SELECT，不是 UPSERT：只有插入分支才动属性");
        assertFalse(exists.contains("expire_time"),
                "刻意不看 expire_time：调用方必须先结算过期行，"
                        + "否则一条早已过期的行会让这里返回 true，"
                        + "于是只刷新时长而不加属性，玩家白嗑一颗药");

        String refresh = bodyOf(code,
                "public void refreshBuffExpire(Connection conn, long playerId, String buffId, long expireTime)");
        assertTrue(refresh.contains("UPDATE player_buffs SET expire_time = ? "
                        + "WHERE player_id = ? AND buff_id = ?"),
                "只推到期时间");
        assertFalse(refresh.contains("attack_bonus"),
                "刷新分支一个属性列都不碰");
    }

    @Test
    @DisplayName("插入的列清单与七个占位符")
    void insertShape() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void insertBuff(Connection conn, long playerId, String buffId,");
        assertTrue(body.contains("(player_id, buff_id, attack_bonus, defense_bonus, "
                        + "speed_bonus, spirit_bonus, expire_time) "),
                "七列");
        assertTrue(body.contains("+ \"VALUES (?, ?, ?, ?, ?, ?, ?)\""), "七个占位符");
        assertTrue(body.contains("ps.setLong(7, expireTime)"),
                "expireTime 是调用方算好的绝对时间戳（now + durationSeconds * 1000）");
    }

    @Test
    @DisplayName("移除前的读取：读不到返回全 0")
    void findBuffBonusesDefaultsToZero() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public BonusSum findBuffBonuses(Connection conn, long playerId, String buffId)");
        assertTrue(body.contains("SELECT attack_bonus, defense_bonus, speed_bonus, spirit_bonus "),
                "四列：移除前要知道该扣多少");
        assertTrue(body.contains("BonusSum sum = new BonusSum();"),
                "先给全 0：读不到行时四个分量都是 0，于是四个 if (x != 0) 全部跳过");
        assertTrue(body.contains("if (rs.next()) {"),
                "只有命中才覆盖，与原来的 0 初值等价");
    }

    @Test
    @DisplayName("两个删除方法各自的条件")
    void twoDeletesDiffer() throws IOException {
        String code = stripComments(read(daoPath()));
        String one = bodyOf(code, "public void deleteBuff(Connection conn, long playerId, String buffId)");
        assertTrue(one.contains("DELETE FROM player_buffs WHERE player_id = ? AND buff_id = ?"),
                "删一条");
        assertFalse(one.contains("expire_time"), "不判认领：调用方已经结算过");

        String all = bodyOf(code, "public void deleteAllBuffs(Connection conn, long playerId)");
        assertTrue(all.contains("DELETE FROM player_buffs WHERE player_id = ?"), "删全部");
    }

    @Test
    @DisplayName("活跃增益列表的六列")
    void activeBuffsSelectSixColumns() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public List<ActiveBuffRow> findActiveBuffs(Connection conn, long playerId, long now)");
        assertTrue(body.contains("SELECT buff_id, attack_bonus, defense_bonus, speed_bonus, spirit_bonus, "),
                "六列：展示要给剩余秒数，所以必须带 expire_time");
        assertTrue(body.contains("+ \"expire_time FROM player_buffs WHERE player_id = ? AND expire_time > ?\""),
                "expire_time 在第二行拼接，且只取未过期的");
        assertTrue(body.contains("row.expireTime = rs.getLong(\"expire_time\")"),
                "剩余秒数由调用方算：Math.max(0, (expireTime - now) / 1000)");
    }

    @Test
    @DisplayName("DAO 不自己取「现在」")
    void daoDoesNotReadClock() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(0, countOccurrences(code, "System.currentTimeMillis()"),
                "「现在」全部由调用方给：DAO 自己取会让一次重试里的时间悄悄变化");
        assertFalse(code.contains("CURRENT_TIMESTAMP"),
                "DAO 不自己取时间：一次重试里的时间会悄悄变化");
    }

    @Test
    @DisplayName("DAO 不依赖服务层")
    void daoDoesNotTouchServices() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("import com.mtxgdn.game.service"),
                "属性回退（addAttack/addDefense/...）留在服务层，DAO 只管表");
        assertFalse(code.contains("ServiceRegistry"),
                "DAO 不该去 ServiceRegistry 取任何东西");
    }
}
