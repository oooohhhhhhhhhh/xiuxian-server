package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.Monster;
import com.mtxgdn.game.entity.PveCombatResult;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 战斗服务单元测试（SQLite 模式）。
 * 覆盖：自我挑战拒绝、挑战超时、拒绝挑战、PVE 战斗 HP 扣除（值拷贝回归）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CombatServiceTest {

    private static CombatService combatService;
    private static PlayerService playerService;
    private static long player1Id;
    private static long player2Id;

    @BeforeAll
    static void setupAll() {
        java.io.File dbFile = new java.io.File("test_combat.db");
        if (dbFile.exists()) dbFile.delete();
        DatabaseManager.init("jdbc:sqlite:test_combat.db", "", "");

        combatService = new CombatService();
        playerService = new PlayerService();

        // 先建用户再建角色（外键约束生效）
        long uid1 = createUser("combat_user1");
        long uid2 = createUser("combat_user2");
        player1Id = playerService.createPlayer(uid1, "战斗甲").getId();
        player2Id = playerService.createPlayer(uid2, "战斗乙").getId();

        // 新角色出生在青云村（安全区），而安全区内禁止 PVP 私斗。
        // 本类验证的是挑战/拒绝机制本身，故先把双方移到非安全区青云山。
        moveToNonSafeZone(player1Id);
        moveToNonSafeZone(player2Id);
    }

    /** 把玩家移到非安全区（青云山 id=2），以避开"安全区禁止私斗"规则。 */
    private static void moveToNonSafeZone(long playerId) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players SET current_location_id = 2, last_travel_time = 0 WHERE id = ?")) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void tearDownAll() {
        try { PlayerService.shutdown(); } catch (Exception ignored) {}
        DatabaseManager.shutdown();
        new java.io.File("test_combat.db").delete();
    }

    private static long createUser(String username) {
        return DatabaseManager.insertRow("users", java.util.Map.of(
                "username", username,
                "password", org.mindrot.jbcrypt.BCrypt.hashpw("Password123!", org.mindrot.jbcrypt.BCrypt.gensalt())
        ));
    }

    @Test
    @Order(1)
    @DisplayName("createChallenge 应拒绝挑战自己")
    void testCreateChallengeSelfChallenge() {
        var result = combatService.createChallenge(player1Id, player1Id);
        assertFalse(result.isSuccess());
        assertEquals("不能挑战自己", result.getMessage());
    }

    @Test
    @Order(2)
    @DisplayName("PendingChallenge 应正确判断超时")
    void testPendingChallengeExpiration() {
        var challenge = new CombatService.PendingChallenge(player1Id, "战斗甲", player2Id, "战斗乙");
        assertFalse(challenge.isExpired());
    }

    @Test
    @Order(3)
    @DisplayName("rejectChallenge 应返回挑战者")
    void testRejectChallenge() {
        combatService.createChallenge(player1Id, player2Id);
        String challenger = combatService.rejectChallenge(player2Id);
        assertNotNull(challenger);
    }

    @Test
    @Order(4)
    @DisplayName("PVE 战斗应正确扣除玩家生命（回归：值拷贝 bug 导致玩家 HP 永不变）")
    void testPveFightDeductsPlayerHp() {
        // 给玩家一个较高的初始 HP
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE players SET hp = ?, max_hp = ? WHERE id = ?")) {
            ps.setInt(1, 500);
            ps.setInt(2, 500);
            ps.setLong(3, player1Id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // 高攻高血怪物：玩家必然战败且 HP 大幅下降
        Monster monster = new Monster("测试妖兽", 99999, 300, 0, 0, 0, false,
                new String[]{"mtxgdn:spirit_grass"}, 0.0, 100, 10, 5, "测试用妖兽");
        PveCombatResult result = combatService.pveFight(player1Id, monster);
        assertFalse(result.isPlayerWon(), "玩家应战败");

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT hp FROM players WHERE id = ?")) {
            ps.setLong(1, player1Id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                int hpAfter = rs.getInt("hp");
                assertTrue(hpAfter < 500, "战斗后玩家 HP 应低于战前（实际 " + hpAfter + "）");
                assertTrue(hpAfter >= 0);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}