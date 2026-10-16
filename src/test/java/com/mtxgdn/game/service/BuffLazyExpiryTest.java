package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.service.UserService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Buff 过期回收的「惰性 + 低频兜底」回归测试。
 *
 * <p>改造前的实现是一个 <b>每秒执行一次</b> 的
 * {@code SELECT ... FROM player_buffs WHERE expire_time <= ?} 全表扫描，
 * 每个 tick 都要把整张表走一遍。改造后：</p>
 * <ul>
 *   <li>活跃玩家在访问 Buff 时被<b>即时</b>过期处理（惰性路径）；</li>
 *   <li>离线玩家由低频兜底任务回收（默认 60 秒，可配置）；</li>
 *   <li>属性回退与行删除绑定在同一条条件 DELETE 上（幂等认领），
 *       并发或重复触发都<b>只回退一次</b>。</li>
 * </ul>
 */
class BuffLazyExpiryTest extends TestBase {

    private static BuffService buffService;
    private static PlayerService playerService;
    private static UserService userService;
    private static boolean ready = false;

    @BeforeAll
    static void setUpBuff() {
        try {
            buffService = new BuffService();
            playerService = ServiceRegistry.getPlayerService();
            userService = new UserService();
            ready = true;
        } catch (Throwable t) {
            System.out.println("[BuffLazyExpiryTest] 环境初始化失败，测试将跳过: " + t);
            ready = false;
        }
    }

    private static long makePlayer(String username, String playerName) {
        String email = username + "@qq.com";
        String code = new com.mtxgdn.service.VerificationCodeService().generateAndStoreCode(email);
        Response resp = userService.register(username, "Password1!", email, code);
        assertEquals(200, resp.getStatus(), "注册应成功");
        long userId = extractLong(resp.getEntity().toString(), "id");
        assertTrue(userId > 0, "userId 应大于 0");
        var pi = playerService.createPlayer(userId, playerName);
        assertNotNull(pi, "角色创建不应为空");
        return pi.getId();
    }

    private static long extractLong(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /** 把该玩家的增益到期时间强行改到过去，模拟"已经过期"。 */
    private static void forceExpire(long playerId) {
        try (var conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE player_buffs SET expire_time = ? WHERE player_id = ?")) {
            ps.setLong(1, System.currentTimeMillis() - 1_000);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("设置过期时间失败", e);
        }
    }

    private static int buffRowCount(long playerId) {
        try (var conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) AS cnt FROM player_buffs WHERE player_id = ?")) {
            ps.setLong(1, playerId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("cnt") : -1;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int attackOf(long playerId) {
        return playerService.getPlayerById(playerId).getAttack();
    }

    // ==================== 惰性过期 ====================

    @Test
    @DisplayName("惰性过期: 读取时过期即回退属性并删除行，无需等后台任务")
    void testExpiredBuffRolledBackOnAccess() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazybuff1", "懒丹甲");
        int baseAttack = attackOf(playerId);

        buffService.addBuff(playerId, "test:buff_lazy_1", 50, 0, 0, 0, 3600);
        assertEquals(baseAttack + 50, attackOf(playerId), "服用增益后攻击应 +50");
        assertEquals(1, buffRowCount(playerId), "应有一条增益记录");

        forceExpire(playerId);

        // 只做一次读取，不跑任何后台任务
        Map<String, Object> active = buffService.getActiveBuffs(playerId);

        assertEquals(0, ((Number) active.get("count")).intValue(), "过期增益不应出现在生效列表中");
        assertEquals(baseAttack, attackOf(playerId), "过期后攻击应回到基准值");
        assertEquals(0, buffRowCount(playerId), "过期行应被删除");
    }

    @Test
    @DisplayName("惰性过期: 重复访问只回退一次属性（幂等认领，不得重复扣减）")
    void testRepeatedExpiryRollsBackExactlyOnce() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazybuff2", "懒丹乙");
        int baseAttack = attackOf(playerId);

        buffService.addBuff(playerId, "test:buff_lazy_2", 50, 0, 0, 0, 3600);
        forceExpire(playerId);

        buffService.getActiveBuffs(playerId);
        assertEquals(baseAttack, attackOf(playerId), "首次访问应回退一次");

        // 再访问多次：绝不能再扣
        buffService.getActiveBuffs(playerId);
        buffService.getActiveBuffs(playerId);
        buffService.getTotalAttackBonus(playerId);
        assertEquals(baseAttack, attackOf(playerId),
                "重复访问不得重复回退属性（否则玩家属性会被扣成负数）");
    }

    @Test
    @DisplayName("惰性过期: 重新嗑药刷新时长的增益不得被当成过期行删掉")
    void testRefreshedBuffIsNotTreatedAsExpired() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazybuff3", "懒丹丙");
        int baseAttack = attackOf(playerId);

        buffService.addBuff(playerId, "test:buff_lazy_3", 50, 0, 0, 0, 3600);
        forceExpire(playerId);
        // 在"过期"之后又嗑了一颗同种丹药：expire_time 被推到未来
        buffService.addBuff(playerId, "test:buff_lazy_3", 50, 0, 0, 0, 3600);

        Map<String, Object> active = buffService.getActiveBuffs(playerId);

        assertEquals(1, ((Number) active.get("count")).intValue(),
                "刷新过时长的增益仍应生效（不能被误判为过期）");
        assertEquals(1, buffRowCount(playerId), "刷新不应产生重复行");
        assertEquals(baseAttack + 50, attackOf(playerId),
                "刷新过时长的增益不得被回退属性；也不得重复叠加成 +100");
    }

    // ==================== 低频兜底 ====================

    @Test
    @DisplayName("兜底回收: 离线玩家（无任何访问）的过期增益仍会被回收")
    void testOfflinePlayerReclaimedBySweep() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazybuff4", "懒丹丁");
        int baseAttack = attackOf(playerId);

        buffService.addBuff(playerId, "test:buff_lazy_4", 40, 0, 0, 0, 3600);
        forceExpire(playerId);

        // 玩家再也不访问 Buff，只能靠兜底任务
        invokeSweep();

        assertEquals(baseAttack, attackOf(playerId), "兜底回收应回退离线玩家的虚高属性");
        assertEquals(0, buffRowCount(playerId), "兜底回收应删除过期行");
    }

    @Test
    @DisplayName("兜底回收: 与惰性路径重复触发也只回退一次（认领语义跨两条路径成立）")
    void testSweepAndLazyPathDoNotDoubleRollback() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazybuff5", "懒丹戊");
        int baseAttack = attackOf(playerId);

        buffService.addBuff(playerId, "test:buff_lazy_5", 60, 0, 0, 0, 3600);
        forceExpire(playerId);

        invokeSweep();                       // 兜底先处理掉
        buffService.getActiveBuffs(playerId); // 惰性路径再来一次
        invokeSweep();                       // 兜底再来一次

        assertEquals(baseAttack, attackOf(playerId),
                "两条路径重复触发不得重复扣减属性");
        assertEquals(0, buffRowCount(playerId), "过期行最终应被清理干净");
    }

    @Test
    @DisplayName("防回归: 过期回收必须是低频兜底，不得回退成每秒扫描")
    void testSweepIntervalIsNotOneSecond() throws Exception {
        var field = BuffService.class.getDeclaredField("EXPIRE_SWEEP_SECONDS");
        field.setAccessible(true);
        int seconds = field.getInt(null);

        assertTrue(seconds >= 10,
                "过期回收间隔不应回到秒级（原实现为每 1 秒一次全表扫描），实际 " + seconds + " 秒");
    }

    /** 直接调用私有的兜底回收方法（模拟后台任务 tick 一次）。 */
    private static void invokeSweep() throws Exception {
        Method m = BuffService.class.getDeclaredMethod("checkExpiredBuffs");
        m.setAccessible(true);
        m.invoke(null);
    }
}
