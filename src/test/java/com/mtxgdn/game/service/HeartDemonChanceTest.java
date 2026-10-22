package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 心魔触发概率必须是"活的"。
 *
 * <p><b>缺陷形态</b>：基础概率原本写成 {@code BASE_CHANCE_PER_HOUR = 0.08}
 * （按"比例"理解是 8%），而灵力抗性、灵根抗性、洞府抗性与上限 {@code 40.0}
 * 全都按"百分点"计。于是
 * {@code finalChance = 0.08 - spirit/15 - 灵根抗性 - 洞府抗性} 对任何真实玩家都是负数，
 * 被 {@code Math.max(0.0, ...)} 夹到 0，而判定是
 * {@code eventRoll >= finalChance} → {@code eventRoll >= 0} 恒成立 →
 * <b>心魔从来不会触发</b>。整套心魔玩法（文案、修为倒退、各类抗性）实际是死代码。</p>
 *
 * <p>心魔本身是随机的，且 {@code RANDOM} 是静态不可注入的，所以这里用统计断言：
 * 对同一名玩家反复结算 1 小时，触发次数必须显著大于 0，且落在合理量级内。
 * 试算：灵力 10 → 灵力抗性 0.67；洞府 0 级 → 0；灵根抗性 ≥ -2。
 * 于是概率约 7%~9%，3000 次期望命中两百多次，{@code 0 次} 只可能来自"死代码"。</p>
 */
class HeartDemonChanceTest extends TestBase {

    private static final int TRIALS = 3000;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    @DisplayName("普通玩家修炼一小时应当有可观测的心魔概率")
    void heartDemonCanActuallyTrigger() throws SQLException {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("heartdemon" + n, "心魔测试" + n);

        HeartDemonService service = ServiceRegistry.getHeartDemonService();
        int triggered = 0;
        for (int i = 0; i < TRIALS; i++) {
            HeartDemonService.HeartDemonResult result =
                    service.processCultivation(playerId, 1000, 3600);
            if (result.triggered) {
                triggered++;
                assertTrue(result.expLost > 0, "触发心魔必须真的扣修为");
                assertTrue(result.netExpChange < result.rawExpGained,
                        "触发心魔后净收益必须小于原始收益");
                assertTrue(result.narrative != null && !result.narrative.isBlank(),
                        "触发心魔必须给出文案");
            }
        }

        double rate = triggered * 100.0 / TRIALS;
        // 旧实现这里恒为 0。放宽到 1% 是为了避开极端运气，同时仍能可靠地抓住"死代码"。
        assertTrue(rate > 1.0,
                "心魔触发率不应为 0 —— 旧实现把基础概率当成比例（0.08）去减按百分点计的"
                        + "抗性，结果被夹到 0，心魔永不触发。实测 " + triggered + "/" + TRIALS
                        + " = " + rate + "%");
        // 上限是 40%，再加一层余量防止有人把单位反过来放大 100 倍
        assertTrue(rate < 45.0, "心魔触发率不应超过单次上限 40%，实测 " + rate + "%");
    }

    @Test
    @DisplayName("抗性应当真的降低心魔概率：3 级洞府足以压到零")
    void resistanceReducesChance() throws SQLException {
        int n = SEQ.incrementAndGet();
        long exposed = createPlayer("heartdemon_exposed" + n, "无洞府" + n);
        long sheltered = createPlayer("heartdemon_sheltered" + n, "有洞府" + n);

        HeartDemonService service = ServiceRegistry.getHeartDemonService();
        int exposedHits = countTriggers(service, exposed, 0);
        // 洞府 3 级 → 抗性 9 个百分点，超过 8% 的基础概率，应当完全压到 0
        int shelteredHits = countTriggers(service, sheltered, 3);

        assertTrue(exposedHits > 0, "无洞府玩家应当能触发心魔，实测 " + exposedHits + " 次");
        assertTrue(exposedHits > shelteredHits,
                "洞府抗性必须真的降低心魔概率：无洞府 " + exposedHits
                        + " 次 vs 3 级洞府 " + shelteredHits + " 次");
        assertTrue(shelteredHits == 0,
                "3 级洞府(9 个百分点抗性) 应把 8% 的基础概率压到 0，实测 " + shelteredHits + " 次");
    }

    private static int countTriggers(HeartDemonService service, long playerId, int caveLevel) {
        int hits = 0;
        for (int i = 0; i < TRIALS; i++) {
            if (service.processCultivationWithCave(playerId, 1000, 3600, caveLevel).triggered) {
                hits++;
            }
        }
        return hits;
    }

    private static long createPlayer(String username, String name) throws SQLException {
        long userId;
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    userId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, spiritual_root, realm, sub_realm, "
                            + "hp, max_hp, mp, max_mp, attack, defense, speed, spirit, experience, "
                            + "gold, last_breakthrough_time) "
                            + "VALUES (?, ?, 'FIRE_WOOD_EARTH', 0, 0, 100, 100, 50, 50, 10, 10, 10, 10, 0, 0, 0)")) {
                ps.setLong(1, userId);
                ps.setString(2, name);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return rs.getLong(1);
                }
            }
        }
    }
}
