package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.item.CurrencyEffect;
import com.mtxgdn.game.item.ItemScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灵石面额压缩回归测试。
 *
 * <p>钉住的核心语义：<b>四档灵石是面额，不是稀有度</b>。1 极品 = 10 亿下品，
 * 所以"高等级灵石"只应该作为"存量过大的紧凑表示"出现，而不该因为"这次奖励发得多"
 * 就凭空变成极品。旧实现从极品往下填，导致同样持有 10 亿灵石的两个人，
 * 领 1000 次小奖励的手里全是下品、领 1 次大奖励的手里是 1 个极品。</p>
 *
 * <p>另一条必须守住的底线是<b>价值守恒</b>：压缩前后
 * {@link ItemService#getSpiritStoneCount} 必须逐位相等 —— 压缩只换表示，不吞钱。</p>
 */
class SpiritStoneGradeTest extends TestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final ItemService itemService = ServiceRegistry.getItemService();

    @BeforeAll
    static void registerItems() {
        // 不先扫描物品，addItem 会以"物品不存在"炸掉
        ItemScanner.scanAndRegister();
    }

    @Test
    @DisplayName("小额发放保持下品，不会凭空变成高等级灵石")
    void smallGrantStaysLowGrade() throws SQLException {
        long playerId = createPlayer();

        itemService.addSpiritStones(playerId, 500);

        assertEquals(500, count(playerId, 0));
        assertEquals(0, count(playerId, 1));
        assertEquals(0, count(playerId, 2));
        assertEquals(0, count(playerId, 3));
        assertEquals(500, itemService.getSpiritStoneCount(playerId));
    }

    @Test
    @DisplayName("10 亿灵石压成上品，但不会产出极品")
    void tenBillionStaysBelowSupreme() throws SQLException {
        long playerId = createPlayer();

        itemService.addSpiritStones(playerId, 1_000_000_000L);

        // 下品留阈值以内的 2000，其余按 1000:1 逐级折算
        assertEquals(2000, count(playerId, 0), "下品应保留阈值以内的一小部分");
        assertEquals(2998, count(playerId, 1));
        assertEquals(997, count(playerId, 2));
        assertEquals(0, count(playerId, 3), "上品等值 9.97 亿 < 20 亿，不该出现极品");
        assertEquals(1_000_000_000L, itemService.getSpiritStoneCount(playerId));
    }

    @Test
    @DisplayName("超过 20 亿才开始出现极品灵石")
    void supremeAppearsOnlyAboveThreshold() throws SQLException {
        long playerId = createPlayer();

        itemService.addSpiritStones(playerId, 10_000_000_000L);

        assertEquals(7, count(playerId, 3), "100 亿 = 7 极品 + 余量");
        assertEquals(2000, count(playerId, 0));
        assertEquals(2998, count(playerId, 1));
        assertEquals(2997, count(playerId, 2));
        assertEquals(10_000_000_000L, itemService.getSpiritStoneCount(playerId));
    }

    @Test
    @DisplayName("任意数额压缩后价值守恒")
    void valueIsAlwaysConserved() throws SQLException {
        long playerId = createPlayer();

        itemService.addSpiritStones(playerId, 123_456_789L);

        long total = count(playerId, 0)
                + count(playerId, 1) * 1000
                + count(playerId, 2) * 1_000_000L
                + count(playerId, 3) * 1_000_000_000L;
        assertEquals(123_456_789L, total);
        assertEquals(123_456_789L, itemService.getSpiritStoneCount(playerId));
    }

    @Test
    @DisplayName("存量超过阈值时，下一次发放会把旧灵石一起压缩")
    void existingLowStonesAreCompressedOnNextGrant() throws SQLException {
        long playerId = createPlayer();
        itemService.addItem(playerId, CurrencyEffect.SPIRIT_STONE_LOW, 3000);

        itemService.addSpiritStones(playerId, 1);

        assertEquals(2001, count(playerId, 0), "3001 下品 → 留 2000，余下 1000 折成 1 中品");
        assertEquals(1, count(playerId, 1));
        assertEquals(3001, itemService.getSpiritStoneCount(playerId));
    }

    @Test
    @DisplayName("压缩后的灵石仍能按面额汇总扣款")
    void compressedStonesAreSpendable() throws SQLException {
        long playerId = createPlayer();
        itemService.addSpiritStones(playerId, 1_000_000_000L);

        // 分档持有，但扣款必须按"下品等值"一次性扣干净
        boolean removed = DatabaseManager.runTransaction(conn -> {
            try {
                return itemService.removeSpiritStones(conn, playerId, 1_000_000_000L);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(removed, "10 亿灵石应当能被全额扣掉");
        assertEquals(0, itemService.getSpiritStoneCount(playerId));
    }

    @Test
    @DisplayName("余额不足时扣款返回 false 而不是抛异常")
    void insufficientStonesReturnsFalse() throws SQLException {
        long playerId = createPlayer();
        itemService.addSpiritStones(playerId, 1000);

        boolean removed = DatabaseManager.runTransaction(conn -> {
            try {
                return itemService.removeSpiritStones(conn, playerId, 5000);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        assertFalse(removed);
    }

    // ==================== 辅助 ====================

    private long count(long playerId, int grade) {
        return itemService.getItemCount(playerId, CurrencyEffect.getStoneKey(grade));
    }

    private long createPlayer() throws SQLException {
        int n = SEQ.incrementAndGet();
        String username = "stoneuser" + n;
        try (Connection conn = DatabaseManager.getConnection()) {
            long userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "用户写入失败");
                    userId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, spiritual_root, realm, sub_realm, hp, max_hp, "
                            + "mp, max_mp, attack, defense, speed, spirit, experience, "
                            + "last_breakthrough_time) VALUES (?, ?, 'FIRE_WOOD_EARTH', 0, 0, 100, 100, "
                            + "50, 50, 20, 10, 10, 0, 0, 0)")) {
                ps.setLong(1, userId);
                ps.setString(2, "道友" + n);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "角色写入失败");
                    return rs.getLong(1);
                }
            }
        }
    }
}
