package com.mtxgdn.game.item;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增益必须跟随调用方的事务一起提交/回滚。
 *
 * <p><b>缺陷形态</b>：{@code BuffEffect.execute(Connection conn, ...)} 明明收了连接，
 * 但"有持续时间的增益"这一支却调用 {@code buffService.addBuff(playerId, ...)} ——
 * 那是<b>自开事务</b>的版本。于是丹药/装备效果在外层事务里执行时，增益行
 * （{@code player_buffs} + 直接加在 {@code players.attack} 等列上的属性）会<b>提前提交</b>：
 * 外层事务随后回滚（扣物品失败、后续效果抛异常……），玩家已经白得了一个增益，
 * 而且没有任何东西会去撤销它 —— 可以反复用"让事务失败"的方式白嫖属性。</p>
 *
 * <p>本测试在一个必然回滚的事务里执行带持续时间的增益效果，断言
 * {@code player_buffs} 没有留下行、攻击力也没有变化；再用正常提交的事务做对照。</p>
 */
class BuffEffectTransactionTest extends TestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    @Test
    @DisplayName("外层事务回滚时，增益行与属性加成必须一起回滚")
    void buffRollsBackWithOuterTransaction() throws SQLException {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("buffrollback" + n, "回滚测试" + n);

        int attackBefore = playerService.getPlayerById(playerId).getAttack();
        // 持续 600 秒 → 走 addBuff 分支（这是出问题的那一支）
        BuffEffect effect = BuffEffect.of(50, 0, 0, 0, 600);

        try {
            DatabaseManager.runTransaction(conn -> {
                effect.execute(conn, playerId, playerService, itemService);
                throw new IllegalStateException("故意失败，触发回滚");
            });
        } catch (RuntimeException expected) {
            // runTransaction 会把内部异常包成 RuntimeException
        }

        assertEquals(0, buffCount(playerId),
                "外层事务回滚后不得留下增益行 —— 说明 addBuff 用了自开事务的版本，"
                        + "增益被提前提交了");
        assertEquals(attackBefore, playerService.getPlayerById(playerId).getAttack(),
                "外层事务回滚后攻击力加成也必须一起回滚");
    }

    @Test
    @DisplayName("正常提交时增益照常生效（确认重载没有把功能改坏）")
    void buffStillAppliesOnCommit() throws SQLException {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("buffcommit" + n, "提交测试" + n);

        int attackBefore = playerService.getPlayerById(playerId).getAttack();
        BuffEffect effect = BuffEffect.of(30, 0, 0, 0, 600);

        DatabaseManager.runTransaction(conn -> {
            effect.execute(conn, playerId, playerService, itemService);
            return true;
        });

        assertEquals(1, buffCount(playerId), "正常提交后应留下一条增益行");
        assertEquals(attackBefore + 30, playerService.getPlayerById(playerId).getAttack(),
                "正常提交后攻击力应提升 30");
    }

    // ==================== 夹具 ====================

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

    private static int buffCount(long playerId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM player_buffs WHERE player_id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}
