package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 注销账号 / 后台删角色时，级联删除必须真的能跑完。
 *
 * <p><b>缺陷形态</b>：{@code deletePlayerCascade} 原来把所有从表都塞进一个数组、
 * 统一执行 {@code DELETE FROM <表> WHERE player_id = ?}。但其中有 5 张表压根没有
 * {@code player_id} 列：</p>
 *
 * <ul>
 *   <li>{@code auction_listings} → 列名是 {@code seller_player_id}</li>
 *   <li>{@code auction_bids} → 列名是 {@code bidder_player_id}</li>
 *   <li>{@code trade_listings} → 列名是 {@code seller_player_id}</li>
 *   <li>{@code chat_messages} → 列名是 {@code sender_player_id} / {@code receiver_player_id}</li>
 *   <li>{@code player_action_logs} → 按 {@code user_id} 关联，没有 player 维度</li>
 * </ul>
 *
 * <p>SQLite 对不存在的列直接报 {@code no such column: player_id}，异常一抛整笔事务
 * 回滚 —— 于是"注销账号"和"后台删角色"（{@code Auth}、{@code AdminPlayersResource}、
 * {@code QqAccountFlow}、{@code OneBotWebSocketServer} 四个入口）<b>从来就没有成功过</b>，
 * 用户看到的永远是「删除玩家失败」。这个测试就是把那条路径真的跑一遍。</p>
 *
 * <p>另外还有 7 张表带 {@code player_id} 却不在清单里（{@code player_buffs}、
 * {@code player_map_exploration}、{@code player_item_durability}、
 * {@code world_boss_damage}、{@code farm_plots}、{@code caves}、{@code formations}），
 * 删号后会留下孤儿行 —— 这里一并锁住。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlayerCascadeDeleteTest extends TestBase {

    /** 每张从表插入的行数（插入用"最小必需列"策略，见 seedMinimalRow）。 */
    private static final List<String> DEPENDENT_TABLES = List.of(
            "players_items", "players_equipment", "players_skills", "players_techniques",
            "player_daily", "player_economy", "player_bank", "player_titles", "player_energy",
            "player_buffs", "player_map_exploration", "player_item_durability",
            "world_boss_damage", "farm_plots", "caves", "formations",
            "auction_listings", "auction_bids", "trade_listings", "chat_messages",
            "player_action_logs", "friends", "redeemed_codes", "sect_members", "sect_applications");

    private static long userId;
    private static long playerId;
    private static long otherPlayerId;
    private static long ownListingId;

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            // 关掉外键检查：本测试只关心"级联删除能不能跑完"，
            // 不想为了插一条 auction_bids 而先造完整的拍卖链路。
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = OFF");
            }

            userId = insertUser(conn, "cascade_delete_user");
            long otherUserId = insertUser(conn, "cascade_delete_other");
            playerId = insertPlayer(conn, userId, "CascadeHero");
            otherPlayerId = insertPlayer(conn, otherUserId, "Bystander");

            // 该玩家自己挂的拍卖单（seller_player_id）—— 旧代码就是在这里炸的
            ownListingId = insertAuctionListing(conn, playerId, "spirit_grass");

            // 别人的挂单，他去出价（bidder_player_id）
            long foreignListingId = insertAuctionListing(conn, otherPlayerId, "mtxgdn:spirit_grass");
            insertAuctionBid(conn, foreignListingId, playerId, 500L);
            // 他给自己挂单出价（删挂单时这条也得跟着走，否则成孤儿）
            insertAuctionBid(conn, ownListingId, otherPlayerId, 800L);

            insertTradeListing(conn, playerId, "spirit_grass");
            // 私聊：他发出去的、以及别人发给他的
            insertChatMessage(conn, playerId, null, "他发出的私聊");
            insertChatMessage(conn, otherPlayerId, playerId, "别人发给他的私聊");
            insertActionLog(conn, userId, "login");
            insertFriends(conn, playerId, otherPlayerId);

            // 其余从表：按 PRAGMA 拿到 NOT NULL 且无默认值的列，填最小合法值
            for (String table : DEPENDENT_TABLES) {
                if (ALREADY_SEEDED.contains(table)) {
                    continue;
                }
                seedMinimalRow(conn, table, playerId);
            }
        }
    }

    /** 上面已经用"语义正确"的方式插过的表，跳过最小行生成。 */
    private static final List<String> ALREADY_SEEDED = List.of(
            "auction_listings", "auction_bids", "trade_listings",
            "chat_messages", "player_action_logs", "friends");

    @Test
    @DisplayName("级联删除能跑完：注销账号不再抛「删除玩家失败」")
    @Order(1)
    void cascadeDeleteCompletes() {
        PlayerDAO dao = new PlayerDAO();
        boolean deleted;
        try {
            deleted = dao.deletePlayerCascade(playerId);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            fail("deletePlayerCascade 抛异常了 —— 注销账号/后台删角色会直接失败。"
                    + "\n根因通常是某张从表没有 player_id 列（auction_listings 用的是 "
                    + "seller_player_id，chat_messages 用的是 sender/receiver_player_id，"
                    + "player_action_logs 按 user_id 关联）。"
                    + "\n实际异常: " + e.getMessage()
                    + (cause == null ? "" : "\n底层原因: " + cause.getMessage()));
            return;
        }
        assertTrue(deleted, "玩家存在时 deletePlayerCascade 应该返回 true");
    }

    @Test
    @DisplayName("删号后所有从表都不留孤儿行")
    @Order(2)
    void noOrphanRowsRemain() throws SQLException {
        // 依赖上一个测试的执行顺序：JUnit 5 默认按方法名哈希排序，
        // 所以这里不假设顺序，先确保已经删过（幂等：已删则再删返回 false）。
        new PlayerDAO().deletePlayerCascade(playerId);

        try (Connection conn = DatabaseManager.getConnection()) {
            assertGone(conn, "players", "id = " + playerId);
            assertGone(conn, "players_items", "player_id = " + playerId);
            assertGone(conn, "player_buffs", "player_id = " + playerId);
            assertGone(conn, "player_map_exploration", "player_id = " + playerId);
            assertGone(conn, "player_item_durability", "player_id = " + playerId);
            assertGone(conn, "world_boss_damage", "player_id = " + playerId);
            assertGone(conn, "farm_plots", "player_id = " + playerId);
            assertGone(conn, "caves", "player_id = " + playerId);
            assertGone(conn, "formations", "player_id = " + playerId);

            // 列名不是 player_id 的那几张
            assertGone(conn, "auction_listings", "seller_player_id = " + playerId);
            assertGone(conn, "auction_bids", "bidder_player_id = " + playerId);
            assertGone(conn, "trade_listings", "seller_player_id = " + playerId);
            assertGone(conn, "chat_messages", "sender_player_id = " + playerId);
            assertGone(conn, "chat_messages", "receiver_player_id = " + playerId);
            assertGone(conn, "player_action_logs", "user_id = " + userId);
            assertGone(conn, "friends", "player_id = " + playerId);
            assertGone(conn, "friends", "friend_player_id = " + playerId);

            // 他挂单上的出价必须跟着挂单一起消失，不能留孤儿出价
            assertGone(conn, "auction_bids", "listing_id = " + ownListingId);

            // 别人的数据不能被误删
            assertExists(conn, "players", "id = " + otherPlayerId);
            // 别人自己挂的单还在。他对我方挂单的那笔出价被删是**对的**：
            // 挂单没了，出价留着就是指向不存在挂单的孤儿。
            assertExists(conn, "auction_listings", "seller_player_id = " + otherPlayerId);
        }
    }

    // ==================== 夹具 ====================

    private static long insertUser(Connection conn, String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (username, password) VALUES (?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, "x");
            ps.executeUpdate();
        }
        return lastId(conn, "users", "username = '" + username + "'");
    }

    private static long insertPlayer(Connection conn, long uid, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO players (user_id, name) VALUES (?, ?)")) {
            ps.setLong(1, uid);
            ps.setString(2, name);
            ps.executeUpdate();
        }
        return lastId(conn, "players", "name = '" + name + "'");
    }

    private static long insertAuctionListing(Connection conn, long sellerId, String itemKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO auction_listings (seller_player_id, item_key, quantity, start_price, end_time) "
                        + "VALUES (?, ?, 1, 100, 0)")) {
            ps.setLong(1, sellerId);
            ps.setString(2, itemKey);
            ps.executeUpdate();
        }
        return lastId(conn, "auction_listings",
                "seller_player_id = " + sellerId + " AND item_key = '" + itemKey + "'");
    }

    private static void insertAuctionBid(Connection conn, long listingId, long bidderId, long amount)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO auction_bids (listing_id, bidder_player_id, amount) VALUES (?, ?, ?)")) {
            ps.setLong(1, listingId);
            ps.setLong(2, bidderId);
            ps.setLong(3, amount);
            ps.executeUpdate();
        }
    }

    private static void insertTradeListing(Connection conn, long sellerId, String itemKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trade_listings (seller_player_id, item_key, quantity, price_spirit_stones) "
                        + "VALUES (?, ?, 1, 100)")) {
            ps.setLong(1, sellerId);
            ps.setString(2, itemKey);
            ps.executeUpdate();
        }
    }

    private static void insertChatMessage(Connection conn, long senderId, Long receiverId, String content)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chat_messages (sender_player_id, receiver_player_id, content) VALUES (?, ?, ?)")) {
            ps.setLong(1, senderId);
            if (receiverId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, receiverId);
            }
            ps.setString(3, content);
            ps.executeUpdate();
        }
    }

    private static void insertActionLog(Connection conn, long uid, String action) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_action_logs (user_id, action) VALUES (?, ?)")) {
            ps.setLong(1, uid);
            ps.setString(2, action);
            ps.executeUpdate();
        }
    }

    private static void insertFriends(Connection conn, long a, long b) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO friends (player_id, friend_player_id) VALUES (?, ?)")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.executeUpdate();
        }
    }

    /**
     * 按表结构自动插入一条"最小合法行"：只填 NOT NULL 且没有默认值的列。
     *
     * <p>这样不用把 20 多张表的列定义抄进测试，表结构变了测试也不会变成
     * "因为少写一个 NOT NULL 列而失败"。</p>
     */
    private static void seedMinimalRow(Connection conn, String table, long pid) throws SQLException {
        List<String> required = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + table + ")")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    boolean notNull = rs.getInt("notnull") != 0;
                    boolean pk = rs.getInt("pk") != 0;
                    String dflt = rs.getString("dflt_value");
                    if (pk || !notNull || dflt != null || "player_id".equals(name)) {
                        continue;
                    }
                    required.add(name);
                }
            }
        }
        StringBuilder cols = new StringBuilder("player_id");
        StringBuilder vals = new StringBuilder("?");
        List<String> textColumns = new ArrayList<>();
        for (String col : required) {
            cols.append(", ").append(col);
            vals.append(", ?");
            textColumns.add(col);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")")) {
            ps.setLong(1, pid);
            int i = 2;
            for (String col : textColumns) {
                // 全部按字符串填：SQLite 是动态类型，数字列收到 "1" 也能存。
                ps.setString(i++, "1");
            }
            ps.executeUpdate();
        }
    }

    private static long lastId(Connection conn, String table, String where) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM " + table + " WHERE " + where + " ORDER BY id DESC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "插入后应该能查到: " + table + " where " + where);
                return rs.getLong(1);
            }
        }
    }

    private static void assertGone(Connection conn, String table, String where) throws SQLException {
        assertEquals(0, count(conn, table, where),
                table + " 里还有该玩家的行（" + where + "）—— 删号后留下孤儿数据");
    }

    private static void assertExists(Connection conn, String table, String where) throws SQLException {
        assertTrue(count(conn, table, where) > 0,
                table + " 里本应保留的行被误删了（" + where + "）");
    }

    private static int count(Connection conn, String table, String where) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            try (ResultSet rs = ps.executeQuery()) {
                assertNotNull(rs);
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /** 表结构自检：上面清单里的表必须真的存在，否则本测试等于空转。 */
    @Test
    @DisplayName("清单里的表全部存在（防止表被改名后测试空转）")
    @Order(3)
    void allListedTablesExist() throws SQLException {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getConnection()) {
            for (String table : DEPENDENT_TABLES) {
                try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + table + ")")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        seen.put(table, rs.next());
                    }
                }
            }
        }
        List<String> missing = new ArrayList<>();
        seen.forEach((table, exists) -> {
            if (!exists) {
                missing.add(table);
            }
        });
        assertTrue(missing.isEmpty(), "这些表不存在，测试会空转: " + missing);
    }
}
