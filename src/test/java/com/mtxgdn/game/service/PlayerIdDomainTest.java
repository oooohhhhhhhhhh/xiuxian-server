package com.mtxgdn.game.service;

import com.mtxgdn.game.skill.LearnSkillResult;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护 {@code players.id} 与 {@code users.id} 两个 id 域不被混用。
 *
 * <p><b>为什么需要这个测试</b>：库里有两种 id —— 账号 {@code users.id}（玩家的
 * {@code user_id}）与角色 {@code players.id}。绝大多数玩法服务收的是<b>角色 id</b>，
 * 而指令层拿到的是<b>账号 id</b>。两者在真实数据里通常不相等，但开发库里往往恰好相等，
 * 于是混用不会立刻报错，只会在某些玩家身上表现为"角色不存在"。</p>
 *
 * <p>历史上确实混用过：{@code PlayerService.getPlayerRaw(long userId)} 因为形参名不显眼，
 * 被 5 处调用方传入了 playerId（学技能、技能书、团队副本入口、心魔结算）；
 * 反向还有 {@code onebot/command/pvp/PvpCommand} 整族把 userId 传给 CombatService。
 * 本测试把这种错误变成<b>必然失败</b>的断言。</p>
 *
 * <p>关键手法：构造一个 {@code players.id} 恰好等于<b>另一个账号</b>的 {@code users.id} 的角色。
 * 这样一来，任何"按 user_id 查 playerId"的调用都会读到<b>另一个人</b>，
 * 断言就能同时抓住"返回 null"和"返回错的人"两种错误。</p>
 */
class PlayerIdDomainTest extends TestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PlayerService playerService = ServiceRegistry.getPlayerService();

    @Test
    @DisplayName("按账号 id 与按角色 id 查询必须命中各自的角色，不能串号")
    void userIdAndPlayerIdMustNotCollide() throws SQLException {
        int n = SEQ.incrementAndGet();
        long userA = insertUser("iddomain_a" + n);
        long userB = insertUser("iddomain_b" + n);

        // 角色 A：显式指定 players.id，刻意让它不等于自己的 user_id
        long playerA = userA + 100_000L + n;
        insertPlayer(playerA, userA, "甲" + n);
        // 角色 B：显式指定 players.id 恰好等于 userA —— 制造跨域撞号
        long playerB = userA;
        insertPlayer(playerB, userB, "乙" + n);

        assertTrue(playerA != userA, "测试前置：角色 A 的 id 必须不等于自己的 user_id");

        // 正确用法
        Player byUserId = playerService.getPlayerByUserIdRaw(userA);
        assertNotNull(byUserId, "按账号 id 应能取到角色 A");
        assertEquals("甲" + n, byUserId.getName());

        Player byPlayerId = playerService.getPlayerById(playerA);
        assertNotNull(byPlayerId, "按角色 id 应能取到角色 A");
        assertEquals("甲" + n, byPlayerId.getName());

        // 错误用法必须"查不到"，而不是悄悄返回别人
        assertNull(playerService.getPlayerByUserIdRaw(playerA),
                "把角色 id 当账号 id 传进去必须返回 null：players.id=" + playerA
                        + " 恰好等于另一个账号的 users.id，若实现按 user_id 查询会读到「乙」");

        // 反向：userA 这个值在 players 表里是角色 B 的 id
        Player crossDomain = playerService.getPlayerById(userA);
        assertNotNull(crossDomain);
        assertEquals("乙" + n, crossDomain.getName(),
                "players.id=" + userA + " 属于角色 B —— 这正是 id 域混用会读到错误角色的原因");
    }

    @Test
    @DisplayName("learnSkill 必须用角色 id 定位玩家（曾误传 playerId 到按 userId 查询的方法）")
    void learnSkillResolvesPlayerByPlayerId() throws SQLException {
        int n = SEQ.incrementAndGet();
        long userId = insertUser("iddomain_skill" + n);
        long playerId = userId + 200_000L + n;
        insertPlayer(playerId, userId, "丙" + n);

        long skillId = insertFreeSkill("测试技能" + n);

        LearnSkillResult result = new SkillService().learnSkill(playerId, skillId);
        assertTrue(result.success(),
                "learnSkill(playerId) 应成功；若失败且 message 是「角色不存在」，说明又按 user_id 查了：" + result);
        assertTrue(new SkillService().hasSkill(playerId, skillId), "技能应已写入 players_skills");
    }

    // ==================== 夹具 ====================

    private static long insertUser(String username) throws SQLException {
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
                    assertTrue(rs.next(), "用户写入失败: " + username);
                    return rs.getLong(1);
                }
            }
        }
    }

    private static void insertPlayer(long playerId, long userId, String name) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO players (id, user_id, name, spiritual_root, realm, sub_realm, "
                             + "hp, max_hp, mp, max_mp, attack, defense, speed, spirit, experience, "
                             + "gold, last_breakthrough_time) "
                             + "VALUES (?, ?, ?, 'FIRE_WOOD_EARTH', 0, 0, 100, 100, 50, 50, 10, 10, 10, 10, 0, 0, 0)")) {
            ps.setLong(1, playerId);
            ps.setLong(2, userId);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private static long insertFreeSkill(String name) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO skills (name, description, required_realm, learn_cost_gold, "
                             + "learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, "
                             + "skill_type, heal_amount, max_level) "
                             + "VALUES (?, '', 0, 0, 0, 10, 0, 0, 0, 0, 10)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "技能写入失败");
                return keys.getLong(1);
            }
        }
    }
}
