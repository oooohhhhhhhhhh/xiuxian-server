package data.mtxgdn.explorationevent;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.item.ItemScanner;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 探索事件必须走 PlayerService，而不是自己拼 SQL 写 {@code players} 表。
 *
 * <p><b>两个真实缺陷</b>：</p>
 * <ol>
 *   <li>{@code SpiritSpringEvent} 用的是 {@code UPDATE players SET hp = LEAST(max_hp, hp + ?)}。
 *       {@code LEAST()} 是 MySQL 函数，<b>SQLite 里不存在</b>，而默认数据库正是 SQLite ——
 *       于是每次触发灵泉都会抛「恢复生命法力失败」，整个探索事件直接失败，
 *       玩家在默认部署上永远拿不到灵泉奖励。</li>
 *   <li>{@code TrapEvent} 把"读内存快照算出来的绝对值"写回库（{@code SET hp = ?}），
 *       并发下会覆盖掉别的通道刚做的治疗/伤害（lost update），
 *       并且绕过了 {@code markDataDirty}。</li>
 * </ol>
 *
 * <p>本测试直接在 SQLite 测试库上跑这两个事件：灵泉必须不抛异常且把 HP/MP 补到上限，
 * 陷阱必须按"库里当前血量"扣血、且不把玩家打死（下限 1）。</p>
 */
class ExplorationEventSqlPortabilityTest extends TestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    @BeforeAll
    static void registerItems() {
        ItemScanner.scanAndRegister();
    }

    @Test
    @DisplayName("灵泉事件在 SQLite 上不得因 LEAST() 不存在而失败，且回血回蓝夹到上限")
    void spiritSpringWorksOnSqlite() throws SQLException {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("spring" + n, "灵泉测试" + n);
        // 先把血量打到半血，才能观察到恢复
        setHpMp(playerId, 40, 20);

        Player player = playerService.getPlayerById(playerId);
        ExplorationResult result = new ExplorationResult();
        List<String> log = new ArrayList<>();

        assertDoesNotThrow(
                () -> new SpiritSpringEvent().execute(
                        player, playerService, itemService, new Random(42), result, log),
                "灵泉事件不得抛异常 —— 旧实现在 SQLite 上必定抛「恢复生命法力失败」");

        Player after = playerService.getPlayerById(playerId);
        assertTrue(after.getHp() > 40, "灵泉应恢复生命，实际 hp=" + after.getHp());
        assertTrue(after.getHp() <= after.getMaxHp(),
                "恢复后不得超过上限（这正是 MIN/LEAST 夹取的作用），实际 hp=" + after.getHp()
                        + " maxHp=" + after.getMaxHp());
        assertTrue(after.getMp() > 20, "灵泉应恢复法力，实际 mp=" + after.getMp());
        assertTrue(after.getMp() <= after.getMaxMp(),
                "恢复后不得超过法力上限，实际 mp=" + after.getMp() + " maxMp=" + after.getMaxMp());
        assertTrue(result.getExpGained() > 0, "灵泉应给经验");
        assertEquals("mtxgdn:spirit_spring_water", result.getItemGained());
        assertTrue(log.size() >= 4, "应写入完整的过程日志");
    }

    @Test
    @DisplayName("陷阱事件按库里当前血量扣血，且不会把玩家打死")
    void trapDamagesBasedOnCurrentHpAndNeverKills() throws SQLException {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("trap" + n, "陷阱测试" + n);
        setHpMp(playerId, 100, 50);

        // 传一个"过期的快照"：满血。若实现按快照写绝对值，扣完会得到 100-loss；
        // 而库里此时其实只有 30 血 —— 正确实现应当从 30 往下扣。
        Player staleSnapshot = playerService.getPlayerById(playerId);
        setHpMp(playerId, 30, 50);

        ExplorationResult result = new ExplorationResult();
        List<String> log = new ArrayList<>();
        new TrapEvent().execute(staleSnapshot, playerService, itemService, new Random(7), result, log);

        int hpAfter = hpOf(playerId);
        assertTrue(hpAfter < 30,
                "陷阱必须基于库里当前血量扣血；若结果 >= 30 说明又用内存快照写了绝对值，实际: " + hpAfter);
        assertTrue(hpAfter >= 1, "陷阱不应把玩家直接打死（下限 1），实际: " + hpAfter);
        assertTrue(result.getHpLost() > 0, "应记录损失的血量");
    }

    @Test
    @DisplayName("血量很低时陷阱也不会把人打到 0")
    void trapKeepsAtLeastOneHp() throws SQLException {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("trap_low" + n, "残血测试" + n);
        setHpMp(playerId, 1, 50);

        Player player = playerService.getPlayerById(playerId);
        new TrapEvent().execute(player, playerService, itemService, new Random(11),
                new ExplorationResult(), new ArrayList<>());

        assertEquals(1, hpOf(playerId), "残血玩家踩陷阱后仍应剩 1 点血，不能被扣成 0 或负数");
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
                            + "VALUES (?, ?, 'FIRE_WOOD_EARTH', 2, 0, 100, 100, 50, 50, 10, 10, 10, 10, 0, 0, 0)")) {
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

    private static void setHpMp(long playerId, int hp, int mp) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players SET hp = ?, mp = ? WHERE id = ?")) {
            ps.setInt(1, hp);
            ps.setInt(2, mp);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        }
    }

    private static int hpOf(long playerId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT hp FROM players WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }
}
