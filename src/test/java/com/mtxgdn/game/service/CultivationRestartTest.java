package com.mtxgdn.game.service;

import com.mtxgdn.game.cultivation.BeginCultivationResult;
import com.mtxgdn.game.cultivation.FinishCultivationResult;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.service.UserService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闭关状态在「停服 / 重启」后的行为回归。
 * <p>
 * 线上现象（用户日志 2026-07-19）：
 * <pre>
 *   /闭关 → 你已经在打坐中了，使用 /停止 来结束。
 *   /停止 → 打坐状态异常，已强制结束。      ← 结算失败，闭关被强制结束
 *   /闭关 → 开始打坐！                      ← 玩家必须重新闭关
 * </pre>
 * 触发条件是数据库里出现<b>不一致状态</b>：{@code is_cultivating = 1} 但
 * {@code cultivation_start_time = 0}（或 NULL）。此时无法算出闭关时长，
 * 结算只能拿到 0 经验，玩家白白损失整段闭关时间，并且必须重新闭关。
 * <p>
 * 本测试锁定两件事：
 * <ol>
 *   <li><b>正常重启</b>：闭关中的玩家重启服务端后，状态与起始时间都还在，
 *       结算能拿到完整时长与经验（不需要重新闭关）。</li>
 *   <li><b>异常状态</b>：出现 {@code start_time = 0} 时，代码必须能<b>自愈</b> ——
 *       不能被卡住（既不能报错，也不能让玩家必须重新闭关）。</li>
 * </ol>
 */
class CultivationRestartTest extends TestBase {

    private static PlayerService playerService;
    private static UserService userService;

    @BeforeAll
    static void setUpCultivation() {
        playerService = ServiceRegistry.getPlayerService();
        userService = new UserService();
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

    /** 直接改库，模拟历史遗留 / 异常数据。 */
    private static void forceRawState(long playerId, boolean cultivating, long startTime) {
        try (var conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players SET is_cultivating = ?, cultivation_start_time = ? WHERE id = ?")) {
            ps.setBoolean(1, cultivating);
            ps.setLong(2, startTime);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long readStartTime(long playerId) {
        try (var conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT cultivation_start_time FROM players WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean readCultivating(long playerId) {
        try (var conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_cultivating FROM players WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("闭关: 停服重启后闭关状态与起始时间都保留，结算不丢时长")
    void restartKeepsCultivationStateAndSettlesFullDuration() throws Exception {
        long pid = makePlayer("restartcult", "重启修士");

        BeginCultivationResult start = playerService.beginCultivation(pid);
        assertTrue(start.success(), "开始闭关应成功");

        long persistedStart = readStartTime(pid);
        assertTrue(persistedStart > 0, "闭关起始时间必须落库（否则重启后算不出时长）");
        assertTrue(readCultivating(pid), "is_cultivating 必须落库");

        // 模拟停服重启：起始时间往前挪 1 小时，模拟这段闭关时间跨过了重启
        forceRawState(pid, true, System.currentTimeMillis() - 3600_000L);

        // 重启后重新读取（新 PlayerService 实例等价于进程重启后的全新读取）
        PlayerService fresh = new PlayerService();
        var player = fresh.getPlayerById(pid);
        assertNotNull(player, "重启后应能读到角色");
        assertTrue(player.isCultivating(), "重启后仍应处于闭关中（不需要玩家重新闭关）");

        FinishCultivationResult stop = fresh.finishCultivation(pid);
        assertTrue(stop.success(), "重启后停止闭关应结算成功，实际: " + stop.message());

        int elapsed = stop.elapsedSeconds().intValue();
        long rawExp = stop.rawExpGained();
        assertTrue(elapsed >= 3500, "闭关时长应包含重启前的时间，实际 " + elapsed + " 秒");
        assertTrue(rawExp > 0, "重启后结算必须给出经验，实际 " + rawExp);
        assertFalse(readCultivating(pid), "结算后应清除闭关标记");
    }

    @Test
    @DisplayName("闭关: 起始时间为 0 的异常状态必须自愈，不能卡住玩家")
    void abnormalZeroStartTimeIsSelfHealing() {
        long pid = makePlayer("abnormalcult", "异常修士");

        // 线上出现过的坏数据：标记为闭关中，但没有起始时间
        forceRawState(pid, true, 0L);

        // 1) 不能因为坏数据就把玩家永久挡在门外
        FinishCultivationResult stop = playerService.finishCultivation(pid);
        assertTrue(stop.success(),
                "异常状态下的停止请求也必须正常收尾，不能报错卡死，实际: " + stop.message());
        assertFalse(readCultivating(pid), "停止后应清除闭关标记（自愈）");

        // 2) 自愈之后必须能正常重新闭关
        BeginCultivationResult restart = playerService.beginCultivation(pid);
        assertTrue(restart.success(),
                "自愈后应能重新闭关，实际: " + restart.message());
        assertTrue(readStartTime(pid) > 0, "重新闭关后起始时间必须被正确写入");
        assertTrue(readCultivating(pid), "重新闭关后标记应为真");

        // 3) 再结算一次，这次必须有正常时长与经验
        FinishCultivationResult stop2 = playerService.finishCultivation(pid);
        assertTrue(stop2.success(), "正常闭关后应能结算");
        assertTrue(stop2.rawExpGained() >= 0, "经验不应为负");
    }

    @Test
    @DisplayName("闭关: 异常状态下重复打坐不应被无意义地挡住")
    void abnormalStateDoesNotBlockStartingAgain() {
        long pid = makePlayer("abnormalstart", "异常打坐修士");
        forceRawState(pid, true, 0L);

        // 起始时间缺失时，"闭关中"是无意义状态：应允许玩家直接重新闭关（自愈），
        // 而不是回一句"你已在闭关中"把玩家挡在外面、逼他先 /停止。
        BeginCultivationResult again = playerService.beginCultivation(pid);
        assertTrue(again.success(),
                "起始时间缺失时重新闭关应被接受（自愈），实际: " + again.message());
        assertTrue(readStartTime(pid) > 0, "自愈后应写入有效的起始时间");
    }

    @Test
    @DisplayName("闭关: 正常闭关中重复打坐仍应被挡下（防止自愈逻辑误伤）")
    void normalCultivationStillRejectsDuplicateStart() {
        long pid = makePlayer("normaldup", "正常重复修士");

        BeginCultivationResult first = playerService.beginCultivation(pid);
        assertTrue(first.success(), "首次闭关应成功");

        long startBefore = readStartTime(pid);
        assertTrue(startBefore > 0, "正常闭关应有起始时间");

        // 正常闭关中（起始时间有效）再打坐：必须仍然被拒绝，
        // 否则自愈逻辑会把玩家真实的闭关进度重置掉。
        BeginCultivationResult dup = playerService.beginCultivation(pid);
        assertFalse(dup.success(),
                "正常闭关中重复打坐应被拒绝，实际: " + dup.message());
        assertEquals(startBefore, readStartTime(pid),
                "被拒绝时不得改写已有的闭关起始时间（否则会重置玩家进度）");
    }
}
