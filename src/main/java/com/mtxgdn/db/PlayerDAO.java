package com.mtxgdn.db;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code players} 表（角色聚合根）上的全部读写。
 *
 * <p><b>为什么要有这个类</b>：这些 SQL 原来散在
 * {@code com.mtxgdn.game.service.PlayerService} 里，一共 39 处
 * {@code DatabaseManager.getConnection()}，业务方法里混着
 * {@code PreparedStatement} / {@code ResultSet} / {@code SQLException}。
 * 后果不是"文件长了 1450 行"，而是：</p>
 * <ul>
 *   <li>改一列就要在服务层里翻半天，且很容易漏改（{@code mapPlayer} 与
 *       {@code mapPlayerInfo} 是两个各自维护的列清单，漏一列不会编译失败，
 *       只会让某个字段悄悄变回默认值）；</li>
 *   <li>"哪些语句在事务里、哪些不在"看不出来 —— 服务层一个方法里既有
 *       {@code runTransaction} 又有随手开的短连接时，谁在事务里全靠人眼判断；</li>
 *   <li>纯业务改动（倍率、校验、文案）与 SQL 改动混在同一份 diff 里。</li>
 * </ul>
 *
 * <p><b>切法</b>：按<b>业务意图</b>切，不按"一条 SQL 一个方法"切。
 * 例如 {@code SELECT * FROM players WHERE id = ?} 只有一条，但"按角色 id 取
 * {@link Player}"（玩法层用）与"按角色 id 取 {@link PlayerInfo}"（榜单/语言解析用）
 * 是两个不同意图，因此是两个方法；反过来，四个属性增量方法（攻/防/速/灵）
 * 共用同一个 {@link #addStat} 意图，只是列名不同。</p>
 *
 * <p><b>连接与事务</b>：每个方法都有一对重载 ——</p>
 * <ul>
 *   <li>{@code xxx(long id, ...)}：自己从 {@link DatabaseManager} 取一个短连接，
 *       用完即关，失败按原实现的文案包成 {@link RuntimeException}；</li>
 *   <li>{@code xxx(Connection conn, long id, ...)}：<b>只用调用方传进来的连接</b>，
 *       绝不自己开连接，失败直接抛 {@link SQLException} 交给调用方。
 *       事务内必须走这一版：SQLite 的 {@code BEGIN DEFERRED} 事务里若先读后写、
 *       期间又用<b>另一个连接</b>写同一张表，会直接抛 {@code SQLITE_BUSY_SNAPSHOT}
 *       （该错误不受 {@code busy_timeout} 约束），或者读到旧快照。</li>
 * </ul>
 *
 * <p><b>SQL 逐字保留</b>：表名、列名、条件、{@code ORDER BY}、{@code LIMIT}、
 * 参数顺序全部原样搬过来，一条没改、没合并、没"优化"。
 * 回归测试：{@code PlayerDaoExtractionTest}（含 SQL 守恒比对）。</p>
 */
public class PlayerDAO {

    private static final GameLogger LOG = GameLogger.getLogger(PlayerDAO.class);

    /** 按角色 id 取行。{@link #findById} 与 {@link #findInfoById} 共用（同一句 SQL，两种映射）。 */
    private static final String SQL_SELECT_BY_ID = "SELECT * FROM players WHERE id = ?";

    /** 按账号 id 取行。{@link #findByUserId} 与 {@link #findInfoByUserId} 共用。 */
    private static final String SQL_SELECT_BY_USER_ID = "SELECT * FROM players WHERE user_id = ?";

    /**
     * 创角基准属性（未计灵根加成）。
     *
     * <p>这组值同时出现在两处：{@link #insertPlayer} 的 INSERT（{@code hp=100, mp=50,
     * attack=10, defense=5, speed=5, spirit=5}）与 {@link #updateSpiritualRoot} 的
     * UPDATE（重算灵根时把上限/当前值一起改成"基准 × 灵根倍率"）。原来两处各写了一遍
     * 字面量，改一处忘另一处会让洗灵根后的属性与创角时的属性不一致。</p>
     */
    private static final int BASE_HP = 100;
    private static final int BASE_MP = 50;
    private static final int BASE_ATTACK = 10;
    private static final int BASE_DEFENSE = 5;
    private static final int BASE_SPEED = 5;
    private static final int BASE_SPIRIT = 5;

    /**
     * 删除角色时要先清空的从表（这些表的列名就是 {@code player_id}）。
     *
     * <p>{@code friends} 的列名是 {@code friend_player_id} 而不是 {@code friend_id}
     * （S11 修复），因此它不在本数组里、单独处理。</p>
     */
    private static final String[] PLAYER_DEPENDENT_TABLES = {
            "players_items", "players_equipment", "players_skills", "players_techniques",
            "player_daily", "player_economy", "player_bank", "player_titles", "player_energy",
            "player_buffs", "player_map_exploration", "player_item_durability",
            "world_boss_damage", "farm_plots", "caves", "formations",
            "sect_members", "sect_applications", "redeemed_codes"
    };

    /**
     * 列名<b>不是</b> {@code player_id} 的从表：{表名, 列名}。
     *
     * <p>这些表以前被塞进上面那个数组里、统一按 {@code WHERE player_id = ?} 删，
     * 结果 DELETE 直接报 {@code no such column: player_id}，整笔事务回滚 ——
     * 也就是说"注销账号 / 后台删角色"从来就没有成功过，用户侧看到的是
     * 「删除玩家失败」。表名与列名必须以 {@link DatabaseSchema} 为准，
     * 不能靠命名猜测。</p>
     */
    private static final String[][] PLAYER_DEPENDENT_TABLES_BY_OTHER_COLUMN = {
            {"auction_listings", "seller_player_id"},
            {"auction_bids", "bidder_player_id"},
            {"trade_listings", "seller_player_id"},
            {"chat_messages", "sender_player_id"}
    };

    /**
     * 创角成功、且<b>仍在同一个连接上</b>时要跑的回调（新手礼包落库）。
     *
     * <p>为什么不是"插入 → 返回 id → 调用方自己开连接发奖励"：新手礼包要往
     * {@code players} / {@code players_items} 写多行，原实现与插入共用同一个连接，
     * 这里用回调把连接传回去，才能在不把 {@code Connection} 的获取权交给服务层的前提下
     * 保持"同一个连接"这一点。</p>
     */
    @FunctionalInterface
    public interface PlayerCreatedHook {
        void afterPlayerInserted(Connection conn, long playerId) throws SQLException;
    }

    // ==================== 1. 读取：按 id 域定位角色 ====================

    /** 按<b>角色 id</b>（{@code players.id}）取玩家；不存在返回 {@code null}。 */
    public Player findById(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findById(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接，绝不自己开连接。 */
    public Player findById(Connection conn, long playerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BY_ID)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlayer(rs);
                }
            }
        }
        return null;
    }

    /** 按<b>账号 id</b>（{@code players.user_id}）取玩家；账号下没有角色时返回 {@code null}。 */
    public Player findByUserId(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByUserId(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public Player findByUserId(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BY_USER_ID)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlayer(rs);
                }
            }
        }
        return null;
    }

    /** 按角色 id 取展示用摘要 {@link PlayerInfo}（榜单、语言解析等只读场景）。 */
    public PlayerInfo findInfoById(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findInfoById(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public PlayerInfo findInfoById(Connection conn, long playerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BY_ID)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlayerInfo(rs);
                }
            }
        }
        return null;
    }

    /** 按账号 id 取展示用摘要 {@link PlayerInfo}。 */
    public PlayerInfo findInfoByUserId(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findInfoByUserId(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public PlayerInfo findInfoByUserId(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BY_USER_ID)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlayerInfo(rs);
                }
            }
        }
        return null;
    }

    /** 该账号是否已经有角色（一个账号一个角色）。 */
    public boolean existsByUserId(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return existsByUserId(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("检查玩家存在失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean existsByUserId(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM players WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    /** 角色总数（管理后台统计）。 */
    public int countPlayers() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return countPlayers(conn);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家数量失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public int countPlayers(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM players");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * 账号总数（管理后台统计）。
     *
     * <p>为什么在 {@code PlayerDAO} 里查 {@code users}：管理后台的"玩家数 / 账号数"
     * 永远成对展示，放在同一个 DAO 里免得调用方为了两个数字跨两个类；
     * 真正的账号<b>读写</b>（注册、改密、绑定）仍然只在 {@link UserAccountDAO} 里。</p>
     */
    public int countUserAccounts() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return countUserAccounts(conn);
        } catch (SQLException e) {
            throw new RuntimeException("获取用户数量失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public int countUserAccounts(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    // ==================== 2. 写入：创角 / 保存整行 / 改名 / 语言 ====================

    /**
     * 插入一行角色（属性按灵根加成算好），返回自增主键；取不到主键时返回 {@code -1}。
     *
     * @param afterInsert 插入成功后、在同一连接上执行的回调（新手礼包）；可为 {@code null}
     */
    public long insertPlayer(long userId, String name, SpiritualRoot root, PlayerCreatedHook afterInsert) {
        try (Connection conn = DatabaseManager.getConnection()) {
            long playerId = insertPlayer(conn, userId, name, root);
            if (afterInsert != null) {
                afterInsert.afterPlayerInserted(conn, playerId);
            }
            return playerId;
        } catch (SQLException e) {
            throw new RuntimeException("创建玩家失败: " + e.getMessage(), e);
        }
    }

    /** 事务内版本：只使用传入的连接，返回自增主键（取不到时 {@code -1}）。 */
    public long insertPlayer(Connection conn, long userId, String name, SpiritualRoot root) throws SQLException {
        String sql = """
            INSERT INTO players (user_id, name, spiritual_root, level, experience, realm, sub_realm,
                hp, max_hp, mp, max_mp, attack, defense, speed, spirit,
                gold, cultivation_progress, is_cultivating, cultivation_start_time, last_secret_realm_time, last_exploration_time, tutorial_step, tutorial_tips, current_location_id)
            VALUES (?, ?, ?, 1, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 1, 0, 1)
            """;
        long playerId = -1;
        try (PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, root.name());
            ps.setInt(4, root.applyHpBonus(BASE_HP));
            ps.setInt(5, root.applyHpBonus(BASE_HP));
            ps.setInt(6, root.applyMpBonus(BASE_MP));
            ps.setInt(7, root.applyMpBonus(BASE_MP));
            ps.setInt(8, root.applyAttackBonus(BASE_ATTACK));
            ps.setInt(9, root.applyDefenseBonus(BASE_DEFENSE));
            ps.setInt(10, root.applySpeedBonus(BASE_SPEED));
            ps.setInt(11, root.applySpiritBonus(BASE_SPIRIT));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    playerId = rs.getLong(1);
                }
            }
        }
        return playerId;
    }

    /**
     * 保存角色的"战斗/修炼主属性"整行（突破、战斗结算、快照回写都走这里）。
     *
     * <p>{@code last_active_time} 不取 {@code player} 上的值，而是写"当前时间" ——
     * 落库本身就是一次活跃信号（与 {@code PlayerService.updatePlayer} 原实现一致）。</p>
     */
    public void savePlayer(long playerId, Player player) {
        try (Connection conn = DatabaseManager.getConnection()) {
            savePlayer(conn, playerId, player);
        } catch (SQLException e) {
            throw new RuntimeException("更新玩家失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void savePlayer(Connection conn, long playerId, Player player) throws SQLException {
        String sql = """
            UPDATE players SET level = ?, experience = ?, realm = ?, sub_realm = ?,
                hp = ?, max_hp = ?, mp = ?, max_mp = ?, attack = ?, defense = ?,
                speed = ?, spirit = ?, gold = ?,
                cultivation_progress = ?, is_cultivating = ?, cultivation_start_time = ?,
                last_secret_realm_time = ?, last_exploration_time = ?, last_offline_time = ?,
                last_breakthrough_time = ?, last_active_time = ?,
                breakthrough_pity = ?, consolidation = ?
            WHERE id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, player.getLevel());
            ps.setLong(2, player.getExperience());
            ps.setInt(3, player.getRealm());
            ps.setInt(4, player.getSubRealm());
            ps.setInt(5, player.getHp());
            ps.setInt(6, player.getMaxHp());
            ps.setInt(7, player.getMp());
            ps.setInt(8, player.getMaxMp());
            ps.setInt(9, player.getAttack());
            ps.setInt(10, player.getDefense());
            ps.setInt(11, player.getSpeed());
            ps.setInt(12, player.getSpirit());
            ps.setLong(13, player.getGold());
            ps.setInt(14, player.getCultivationProgress());
            ps.setBoolean(15, player.isCultivating());
            ps.setLong(16, player.getCultivationStartTime());
            ps.setLong(17, player.getLastSecretRealmTime());
            ps.setLong(18, player.getLastExplorationTime());
            ps.setLong(19, player.getLastOfflineTime());
            ps.setLong(20, player.getLastBreakthroughTime());
            ps.setLong(21, System.currentTimeMillis()); // last_active_time：落库本身就是一次活跃信号
            ps.setInt(22, player.getBreakthroughPity());
            ps.setInt(23, player.getConsolidation());
            ps.setLong(24, playerId);
            ps.executeUpdate();
        }
    }

    /** 改名；返回是否真的更新了一行（角色不存在时为 {@code false}）。 */
    public boolean updateName(long playerId, String safeName) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return updateName(conn, playerId, safeName);
        } catch (SQLException e) {
            throw new RuntimeException("修改玩家名称失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean updateName(Connection conn, long playerId, String safeName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET name = ? WHERE id = ?")) {
            ps.setString(1, safeName);
            ps.setLong(2, playerId);
            return ps.executeUpdate() > 0;
        }
    }

    /** 写入界面语言；返回是否真的更新了一行。 */
    public boolean updateLanguage(long playerId, String language) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return updateLanguage(conn, playerId, language);
        } catch (SQLException e) {
            throw new RuntimeException("更新界面语言失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean updateLanguage(Connection conn, long playerId, String language) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET lang = ? WHERE id = ?")) {
            ps.setString(1, language);
            ps.setLong(2, playerId);
            return ps.executeUpdate() > 0;
        }
    }

    // ==================== 3. 增量修改：属性/资源在 SQL 里做加减 ====================

    /**
     * 经验增量。
     *
     * <p>增量必须交给 SQL 做（{@code experience = experience + ?}）：读内存快照再写绝对值
     * 会在两条通道同时结算时丢掉一次增量（lost update）。</p>
     */
    public void addExperience(long playerId, long exp) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addExperience(conn, playerId, exp);
        } catch (SQLException e) {
            throw new RuntimeException("增加经验失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void addExperience(Connection conn, long playerId, long exp) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET experience = experience + ? WHERE id = ?")) {
            ps.setLong(1, exp);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 金币增量。 */
    public void addGold(long playerId, long amount) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addGold(conn, playerId, amount);
        } catch (SQLException e) {
            throw new RuntimeException("增加金币失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void addGold(Connection conn, long playerId, long amount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET gold = gold + ? WHERE id = ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * 单项属性的增量（攻/防/速/灵共用）。
     *
     * <p>{@code column} 是拼进 SQL 的标识符（占位符无法参数化列名），
     * 调用方只能传编译期字面量：{@code attack} / {@code defense} / {@code speed} / {@code spirit}。
     * 这里刻意不做额外校验 —— 本次重构只搬位置，不改行为。</p>
     *
     * @param operationName 失败时的中文动作名，用于拼异常文案（如"增加攻击力"）
     */
    public void addStat(long playerId, String column, int amount, String operationName) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addStat(conn, playerId, column, amount);
        } catch (SQLException e) {
            throw new RuntimeException(operationName + "失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void addStat(Connection conn, long playerId, String column, int amount) throws SQLException {
        String sql = "UPDATE players SET " + column + " = " + column + " + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 回血并夹到上限（{@code MIN}/{@code LEAST} 由方言决定，不能写成绝对值）。 */
    public void addHp(long playerId, int amount) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addHp(conn, playerId, amount);
        } catch (SQLException e) {
            throw new RuntimeException("恢复生命值失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void addHp(Connection conn, long playerId, int amount) throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET hp = MIN(hp + ?, max_hp) WHERE id = ?"
                : "UPDATE players SET hp = LEAST(hp + ?, max_hp) WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * 扣血并保留下限（陷阱、跌落等"不应致死"的场景）。
     *
     * <p>同样必须在 SQL 里做减法：写绝对值会覆盖掉另一条通道刚做的治疗/伤害。</p>
     *
     * @param minHp 血量下限（调用方已做过 {@code Math.max(0, floor)}）
     */
    public void damageHp(long playerId, int amount, int minHp) {
        try (Connection conn = DatabaseManager.getConnection()) {
            damageHp(conn, playerId, amount, minHp);
        } catch (SQLException e) {
            throw new RuntimeException("扣除生命值失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void damageHp(Connection conn, long playerId, int amount, int minHp) throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET hp = MAX(?, hp - ?) WHERE id = ?"
                : "UPDATE players SET hp = GREATEST(?, hp - ?) WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, minHp);
            ps.setInt(2, amount);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        }
    }

    /** 回蓝并夹到上限。 */
    public void addMp(long playerId, int amount) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addMp(conn, playerId, amount);
        } catch (SQLException e) {
            throw new RuntimeException("恢复法力失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void addMp(Connection conn, long playerId, int amount) throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET mp = MIN(mp + ?, max_mp) WHERE id = ?"
                : "UPDATE players SET mp = LEAST(mp + ?, max_mp) WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 疗伤：生命与法力直接回满（{@code hp = max_hp, mp = max_mp}）。 */
    public void restoreFullVitals(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            restoreFullVitals(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("疗伤失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void restoreFullVitals(Connection conn, long playerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET hp = max_hp, mp = max_mp WHERE id = ?")) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * 洗灵根：写入新灵根，并把上限/当前值一起改成"基准 × 新灵根倍率"。
     *
     * <p>当前值用 {@code MIN}/{@code LEAST} 夹到新上限之下，否则换成低倍率灵根时
     * 会出现"血量高于上限"的脏数据。</p>
     */
    public void updateSpiritualRoot(long playerId, SpiritualRoot newRoot) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateSpiritualRoot(conn, playerId, newRoot);
        } catch (SQLException e) {
            throw new RuntimeException("更新灵根失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateSpiritualRoot(Connection conn, long playerId, SpiritualRoot newRoot) throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET spiritual_root = ?, max_hp = ?, hp = MIN(hp, ?), max_mp = ?, mp = MIN(mp, ?), attack = ?, defense = ?, speed = ?, spirit = ? WHERE id = ?"
                : "UPDATE players SET spiritual_root = ?, max_hp = ?, hp = LEAST(hp, ?), max_mp = ?, mp = LEAST(mp, ?), attack = ?, defense = ?, speed = ?, spirit = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newRoot.name());
            ps.setInt(2, newRoot.applyHpBonus(BASE_HP));
            ps.setInt(3, newRoot.applyHpBonus(BASE_HP));
            ps.setInt(4, newRoot.applyMpBonus(BASE_MP));
            ps.setInt(5, newRoot.applyMpBonus(BASE_MP));
            ps.setInt(6, newRoot.applyAttackBonus(BASE_ATTACK));
            ps.setInt(7, newRoot.applyDefenseBonus(BASE_DEFENSE));
            ps.setInt(8, newRoot.applySpeedBonus(BASE_SPEED));
            ps.setInt(9, newRoot.applySpiritBonus(BASE_SPIRIT));
            ps.setLong(10, playerId);
            ps.executeUpdate();
        }
    }

    // ==================== 4. 状态位：修炼 / 引导 / 时间戳 / 策略 ====================

    /** 更新修炼进度（0-100）。 */
    public void updateCultivationProgress(long playerId, int progress) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateCultivationProgress(conn, playerId, progress);
        } catch (SQLException e) {
            throw new RuntimeException("更新修炼进度失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateCultivationProgress(Connection conn, long playerId, int progress) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET cultivation_progress = ? WHERE id = ?")) {
            ps.setInt(1, progress);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 开始闭关：置位并把 {@code last_offline_time} 清零（离线收益从出关那一刻重新计时）。 */
    public void startCultivation(long playerId, long startTime) {
        try (Connection conn = DatabaseManager.getConnection()) {
            startCultivation(conn, playerId, startTime);
        } catch (SQLException e) {
            throw new RuntimeException("更新修炼状态失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void startCultivation(Connection conn, long playerId, long startTime) throws SQLException {
        String sql = "UPDATE players SET is_cultivating = ?, cultivation_start_time = ?, last_offline_time = 0 WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, true);
            ps.setLong(2, startTime);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * 条件停止闭关：仅当玩家确实在闭关时清零并返回 {@code true}。
     *
     * <p>{@code AND is_cultivating = 1} 是"原子认领"：并发多次停止时只有第一次返回
     * {@code true}，防止闭关经验被重复发放。</p>
     */
    public boolean stopCultivation(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return stopCultivation(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("更新修炼状态失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean stopCultivation(Connection conn, long playerId) throws SQLException {
        String sql = "UPDATE players SET is_cultivating = ?, cultivation_start_time = 0 WHERE id = ? AND is_cultivating = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, false);
            ps.setLong(2, playerId);
            return ps.executeUpdate() > 0;
        }
    }

    /** 更新新手引导步骤。 */
    public void updateTutorialStep(long playerId, int step) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateTutorialStep(conn, playerId, step);
        } catch (SQLException e) {
            throw new RuntimeException("更新引导步骤失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateTutorialStep(Connection conn, long playerId, int step) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET tutorial_step = ? WHERE id = ?")) {
            ps.setInt(1, step);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 更新新手引导提示标记。 */
    public void updateTutorialTips(long playerId, int tips) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateTutorialTips(conn, playerId, tips);
        } catch (SQLException e) {
            throw new RuntimeException("更新引导提示标记失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateTutorialTips(Connection conn, long playerId, int tips) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET tutorial_tips = ? WHERE id = ?")) {
            ps.setInt(1, tips);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 更新上次秘境时间（秘境冷却）。 */
    public void updateLastSecretRealmTime(long playerId, long lastSecretRealmTime) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateLastSecretRealmTime(conn, playerId, lastSecretRealmTime);
        } catch (SQLException e) {
            throw new RuntimeException("更新秘境时间失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateLastSecretRealmTime(Connection conn, long playerId, long lastSecretRealmTime) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET last_secret_realm_time = ? WHERE id = ?")) {
            ps.setLong(1, lastSecretRealmTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 更新上次游历时间（游历冷却）。 */
    public void updateLastExplorationTime(long playerId, long lastExplorationTime) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateLastExplorationTime(conn, playerId, lastExplorationTime);
        } catch (SQLException e) {
            throw new RuntimeException("更新游历时间失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateLastExplorationTime(Connection conn, long playerId, long lastExplorationTime) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET last_exploration_time = ? WHERE id = ?")) {
            ps.setLong(1, lastExplorationTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 更新战斗策略（自动战斗的招式偏好）。 */
    public void updateBattleStrategy(long playerId, String strategy) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateBattleStrategy(conn, playerId, strategy);
        } catch (SQLException e) {
            throw new RuntimeException("更新战斗策略失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateBattleStrategy(Connection conn, long playerId, String strategy) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET battle_strategy = ? WHERE id = ?")) {
            ps.setString(1, strategy);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 更新离线时间（下线时打点，作为离线收益的起点）。 */
    public void updateLastOfflineTime(long playerId, long lastOfflineTime) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateLastOfflineTime(conn, playerId, lastOfflineTime);
        } catch (SQLException e) {
            throw new RuntimeException("更新离线时间失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateLastOfflineTime(Connection conn, long playerId, long lastOfflineTime) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET last_offline_time = ? WHERE id = ?")) {
            ps.setLong(1, lastOfflineTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * 原子"认领"离线收益：仅当 {@code last_offline_time} 仍等于期望值时清零并返回
     * {@code true}。并发下只有第一个请求能认领成功，防止离线收益被重复发放。
     */
    public boolean claimOfflineTime(long playerId, long expectedLastOfflineTime) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return claimOfflineTime(conn, playerId, expectedLastOfflineTime);
        } catch (SQLException e) {
            throw new RuntimeException("认领离线时间失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean claimOfflineTime(Connection conn, long playerId, long expectedLastOfflineTime) throws SQLException {
        String sql = "UPDATE players SET last_offline_time = 0 WHERE id = ? AND last_offline_time = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, expectedLastOfflineTime);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 惰性刷新"最后出现时间"（{@code last_active_time}，弃坑判定的唯一依据）。
     *
     * <p>无参版本写入失败<b>静默吞掉</b>：这个字段只服务于天级的弃坑判定，
     * 让它挡住任何玩法都是净亏（与原实现一致）。节流由调用方负责。</p>
     */
    public void touchActiveTime(long playerId, long activeTime) {
        try (Connection conn = DatabaseManager.getConnection()) {
            touchActiveTime(conn, playerId, activeTime);
        } catch (SQLException ignored) {
            // 活跃时间只用于弃坑判定，写失败不应阻断任何玩法
        }
    }

    /** 事务内版本：只使用传入的连接，失败抛 {@link SQLException} 交给调用方决定。 */
    public void touchActiveTime(Connection conn, long playerId, long activeTime) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET last_active_time = ? WHERE id = ?")) {
            ps.setLong(1, activeTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * 玩法计数 +1（秘境通关 / PVP 胜场 / 炼丹次数，称号系统按此发奖）。
     *
     * <p>{@code column} 同样是拼进 SQL 的标识符，调用方只能传编译期字面量：
     * {@code secret_realm_count} / {@code pvp_wins} / {@code craft_count}。</p>
     */
    public void incrementCounter(long playerId, String column) {
        try (Connection conn = DatabaseManager.getConnection()) {
            incrementCounter(conn, playerId, column);
        } catch (SQLException e) {
            throw new RuntimeException("更新玩法计数失败: " + column, e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void incrementCounter(Connection conn, long playerId, String column) throws SQLException {
        String sql = "UPDATE players SET " + column + " = " + column + " + 1 WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }
    }

    // ==================== 5. 查询：列表 / 搜索 / 榜单 ====================

    /** 全服角色分页（等级优先、经验次之）。 */
    public List<PlayerInfo> findAll(int limit, int offset) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findAll(conn, limit, offset);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家列表失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<PlayerInfo> findAll(Connection conn, int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM players ORDER BY level DESC, experience DESC LIMIT ? OFFSET ?";
        List<PlayerInfo> players = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        }
        return players;
    }

    /**
     * 按一批角色 id 批量取"id → 名字"。
     *
     * <p><b>为什么要批量</b>：后台挂单列表要先拿到一批 {@code seller_player_id}
     * 再显示卖家名。逐行查库是 N+1 —— 一页 20 条挂单就是 20 次往返，
     * 在 1C 的机器上这比查一次 {@code IN (...)} 慢一个数量级。</p>
     *
     * <p><b>查不到的 id 不出现在返回的 map 里</b>（不是映射成 {@code null}）：
     * 卖家账号被删、角色被清空都会走到这里，调用方据此显示原始 id 即可，
     * 不该让整个列表因为一行缺失而失败。</p>
     *
     * <p><b>空入参返回空 map，不发 SQL</b>：{@code IN ()} 是语法错误。</p>
     */
    public Map<Long, String> findNamesByIds(List<Long> playerIds) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findNamesByIds(conn, playerIds);
        } catch (SQLException e) {
            throw new RuntimeException("批量查询玩家名失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public Map<Long, String> findNamesByIds(Connection conn, List<Long> playerIds) throws SQLException {
        Map<Long, String> names = new LinkedHashMap<>();
        if (playerIds == null || playerIds.isEmpty()) {
            return names;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(playerIds.size(), "?"));
        String sql = "SELECT id, name FROM players WHERE id IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < playerIds.size(); i++) {
                ps.setLong(i + 1, playerIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.put(rs.getLong("id"), rs.getString("name"));
                }
            }
        }
        return names;
    }

    /** 按名字模糊搜索（{@code LIKE %name%}），同样按等级/经验排序。 */
    public List<PlayerInfo> searchByName(String name, int limit, int offset) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return searchByName(conn, name, limit, offset);
        } catch (SQLException e) {
            throw new RuntimeException("搜索玩家失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<PlayerInfo> searchByName(Connection conn, String name, int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM players WHERE name LIKE ? ORDER BY level DESC, experience DESC LIMIT ? OFFSET ?";
        List<PlayerInfo> players = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + name + "%");
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        }
        return players;
    }

    /** 境界榜：境界优先、经验次之。 */
    public List<PlayerInfo> findTopByRealm(int limit) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findTopByRealm(conn, limit);
        } catch (SQLException e) {
            throw new RuntimeException("查询玩家列表失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<PlayerInfo> findTopByRealm(Connection conn, int limit) throws SQLException {
        return queryInfoList(conn, "SELECT * FROM players ORDER BY realm DESC, experience DESC LIMIT ?", limit);
    }

    /** 战力榜：攻+防+速+最大生命的粗排（与战斗结算的取值口径一致）。 */
    public List<PlayerInfo> findTopByPower(int limit) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findTopByPower(conn, limit);
        } catch (SQLException e) {
            throw new RuntimeException("查询玩家列表失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<PlayerInfo> findTopByPower(Connection conn, int limit) throws SQLException {
        return queryInfoList(conn, "SELECT * FROM players ORDER BY (attack + defense + speed + max_hp) DESC LIMIT ?", limit);
    }

    /**
     * 财富榜：金币 + 背包里灵石折合的低阶灵石数。
     *
     * <p>折算率（低=1 / 中=1000 / 高=1000000 / 极=1000000000）写死在 SQL 里，
     * 与灵石兑换口径必须一致；{@code LEFT JOIN} 保证没有物品的角色也在榜上。</p>
     */
    public List<PlayerInfo> findTopByWealth(int limit) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findTopByWealth(conn, limit);
        } catch (SQLException e) {
            throw new RuntimeException("获取财富榜失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<PlayerInfo> findTopByWealth(Connection conn, int limit) throws SQLException {
        String sql = """
            SELECT p.*, COALESCE(SUM(CASE
                WHEN pi.item_key = 'mtxgdn:spirit_stone_low' THEN pi.quantity
                WHEN pi.item_key = 'mtxgdn:spirit_stone_mid' THEN pi.quantity * 1000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_high' THEN pi.quantity * 1000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_supreme' THEN pi.quantity * 1000000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone' THEN pi.quantity
                ELSE 0 END), 0) AS spirit_stones
            FROM players p
            LEFT JOIN players_items pi ON p.id = pi.player_id
            GROUP BY p.id
            ORDER BY (p.gold + COALESCE(SUM(CASE
                WHEN pi.item_key = 'mtxgdn:spirit_stone_low' THEN pi.quantity
                WHEN pi.item_key = 'mtxgdn:spirit_stone_mid' THEN pi.quantity * 1000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_high' THEN pi.quantity * 1000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_supreme' THEN pi.quantity * 1000000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone' THEN pi.quantity
                ELSE 0 END), 0)) DESC
            LIMIT ?
            """;
        List<PlayerInfo> players = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        }
        return players;
    }

    /** 榜单查询的公共壳（只差一句 SQL，因此不各自抄一遍连接与映射）。 */
    private List<PlayerInfo> queryInfoList(Connection conn, String sql, int limit) throws SQLException {
        List<PlayerInfo> players = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        }
        return players;
    }

    // ==================== 6. 关联查询：称号 / 装备强化 ====================

    /**
     * 取当前佩戴的称号 key（没有则 {@code null}）。
     *
     * <p>只返回 key，不在这里解析成 {@code Title}：称号定义由
     * {@code TitleRegistry} 持有，DAO 不该依赖配置层。</p>
     *
     * <p>查询失败只记日志、返回 {@code null} —— 称号只影响加成数值，
     * 不该让一次读失败挡住整场战斗。</p>
     */
    public String findEquippedTitleKey(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findEquippedTitleKey(conn, playerId);
        } catch (SQLException e) {
            LOG.error("查询玩家称号失败", e);
        }
        return null;
    }

    /** 事务内版本：只使用传入的连接，失败抛 {@link SQLException} 交给调用方决定。 */
    public String findEquippedTitleKey(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT title_key FROM player_titles WHERE player_id = ? AND is_equipped = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setBoolean(2, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("title_key");
                }
            }
        }
        return null;
    }

    /**
     * 取"仍生效"的装备强化等级（只含 {@code enhance_level > 0} 且未损坏的装备）。
     *
     * <p>耐久为 0（损坏）的装备不提供强化加成 —— 否则"装备坏了但强化还在"，
     * 修理就没有意义；耐久表没有记录 = 从未磨损 = 按生效处理
     * （{@code d.durability IS NULL}）。</p>
     *
     * <p>只返回等级，不在这里算加成：加成公式属于
     * {@code EnhanceService}。查询失败记日志并返回空列表（等于"没有加成"），
     * 与原实现"失败不阻断战斗"一致。</p>
     */
    public List<Integer> findActiveEnhanceLevels(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findActiveEnhanceLevels(conn, playerId);
        } catch (SQLException e) {
            LOG.warn("查询强化加成失败: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    /** 事务内版本：只使用传入的连接，失败抛 {@link SQLException} 交给调用方决定。 */
    public List<Integer> findActiveEnhanceLevels(Connection conn, long playerId) throws SQLException {
        List<Integer> levels = new ArrayList<>();
        String sql = "SELECT e.enhance_level FROM players_equipment e "
                + "LEFT JOIN player_item_durability d ON d.player_id = e.player_id AND d.item_key = e.item_key "
                + "WHERE e.player_id = ? AND e.enhance_level > 0 AND (d.durability IS NULL OR d.durability > 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    levels.add(rs.getInt("enhance_level"));
                }
            }
        }
        return levels;
    }

    // ==================== 7. 删除：角色 + 全部从表 + 孤儿宗门 ====================

    /**
     * 删除角色及其全部从属数据，返回是否真的删掉了（角色不存在时 {@code false}）。
     *
     * <p><b>为什么整个删除是一个方法而不是"服务层拼事务"</b>：删除必须先解散
     * "最后一个管理者被删掉"的宗门（M20），再删 {@code sect_members} ——
     * 两件事必须在<b>同一个事务</b>里、且顺序固定。把事务开在这里，
     * 服务层就不用碰 {@code setAutoCommit/commit/rollback}，
     * 也不可能出现"事务里又开一个连接"。</p>
     *
     * <p>事务控制刻意保持手写（{@code setAutoCommit(false)} + 显式 rollback），
     * 而不是换成 {@code DatabaseManager.runTransaction}：后者的失败文案是
     * "事务执行失败: ..."，本方法的公开契约是"删除玩家失败"，
     * 换成它等于改了异常行为。</p>
     */
    public boolean deletePlayerCascade(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // M20 修复：若该玩家是某宗门的最后一名有管理权限成员，先解散宗门并清空仓库。
                // 必须在删除 sect_members 之前执行，否则无法查出其宗门身份。
                disbandOrphanedSect(conn, playerId);

                for (String table : PLAYER_DEPENDENT_TABLES) {
                    String sql = "DELETE FROM " + table + " WHERE player_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, playerId);
                        ps.executeUpdate();
                    }
                }

                // 拍卖：先把**别人挂单上**属于他的出价清掉，再删他自己的出价与挂单。
                // 顺序反了会留下指向不存在挂单的孤儿出价（挂单被删、出价还在）。
                deleteByLong(conn, "DELETE FROM auction_bids WHERE listing_id IN "
                        + "(SELECT id FROM auction_listings WHERE seller_player_id = ?)", playerId);

                // 列名不是 player_id 的那几张表
                for (String[] pair : PLAYER_DEPENDENT_TABLES_BY_OTHER_COLUMN) {
                    deleteByLong(conn, "DELETE FROM " + pair[0] + " WHERE " + pair[1] + " = ?", playerId);
                }
                // 私聊消息：收件人是他也要删（发件人在上面已经删过）
                deleteByLong(conn, "DELETE FROM chat_messages WHERE receiver_player_id = ?", playerId);

                // 行为日志按 user_id 关联（这张表没有 player_id 列），
                // 必须在删掉 players 行之前取到 user_id
                deleteActionLogsOfPlayer(conn, playerId);

                // S11 修复：friends 表列名是 friend_player_id，不是 friend_id
                String deleteFriendSql = "DELETE FROM friends WHERE player_id = ? OR friend_player_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deleteFriendSql)) {
                    ps.setLong(1, playerId);
                    ps.setLong(2, playerId);
                    ps.executeUpdate();
                }

                String deletePlayerSql = "DELETE FROM players WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deletePlayerSql)) {
                    ps.setLong(1, playerId);
                    int affected = ps.executeUpdate();
                    if (affected == 0) {
                        conn.rollback();
                        return false;
                    }
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw new RuntimeException("删除玩家失败", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("删除玩家失败", e);
        }
    }

    /**
     * 执行一条"单个 long 参数"的删除语句，复用调用方的事务连接。
     *
     * @param conn 事务连接，不能是 null
     */
    private static void deleteByLong(Connection conn, String sql, long value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.executeUpdate();
        }
    }

    /**
     * 删除该玩家的行为日志。
     *
     * <p>{@code player_action_logs} 没有 {@code player_id} 列，它是按
     * {@code user_id}（账号维度）关联的 —— 一个账号可以有多个角色，
     * 所以这里删的是整个账号的日志。必须在删除 {@code players} 行<b>之前</b>
     * 取到 {@code user_id}，删完就查不到了。</p>
     */
    private static void deleteActionLogsOfPlayer(Connection conn, long playerId) throws SQLException {
        Long userId = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM players WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong("user_id");
                    if (!rs.wasNull()) {
                        userId = v;
                    }
                }
            }
        }
        if (userId == null) {
            return;
        }
        deleteByLong(conn, "DELETE FROM player_action_logs WHERE user_id = ?", userId);
    }

    /**
     * M20 修复：玩家被删除时，若该玩家是某宗门的最后一名有管理权限成员
     * （宗主/副宗主/长老），则解散该宗门并清空宗门仓库、申请、战争记录。
     *
     * <p>必须在同一事务内、且删除 {@code sect_members} 之前调用。</p>
     */
    private void disbandOrphanedSect(Connection conn, long playerId) throws SQLException {
        long sectId = -1;
        String role = null;
        String findSql = "SELECT sect_id, role FROM sect_members WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sectId = rs.getLong("sect_id");
                    role = rs.getString("role");
                }
            }
        }
        if (sectId < 0) {
            return;
        }
        boolean isManager = SectMember.ROLE_LEADER.equals(role)
                || SectMember.ROLE_VICE_LEADER.equals(role)
                || SectMember.ROLE_ELDER.equals(role);
        if (!isManager) {
            return;
        }

        // 若宗门内还有其他管理权限成员，则无需解散
        String countSql = "SELECT COUNT(*) FROM sect_members WHERE sect_id = ? AND player_id != ? " +
                "AND role IN ('LEADER', 'VICE_LEADER', 'ELDER')";
        try (PreparedStatement ps = conn.prepareStatement(countSql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        }

        // 解散宗门：清空仓库/申请/成员/战争记录，再删除宗门本体
        String[] sectTables = {"sect_warehouse", "sect_applications", "sect_members"};
        for (String table : sectTables) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE sect_id = ?")) {
                ps.setLong(1, sectId);
                ps.executeUpdate();
            }
        }
        // sect_wars 没有 sect_id 列，按攻/守两方宗门 id 清理
        String deleteWarsSql = "DELETE FROM sect_wars WHERE attacker_sect_id = ? OR defender_sect_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(deleteWarsSql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, sectId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sects WHERE id = ?")) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
        LOG.info("删除玩家 " + playerId + " 时解散了无管理成员的宗门 sectId=" + sectId);
    }

    // ==================== 8. 后台任务 ====================

    /**
     * 全服自动回血：每秒回 1% 最大生命（由服务层的定时器驱动）。
     *
     * <p>{@code CEILING}/{@code CEIL} 与 {@code MIN}/{@code LEAST} 由方言决定，
     * SQLite 上写 MySQL 函数会直接抛"no such function"。</p>
     *
     * @return 受影响的行数；失败时记日志并返回 0
     */
    public int healAllPlayersByOnePercent() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return healAllPlayersByOnePercent(conn);
        } catch (SQLException e) {
            LOG.error("自动回血失败", e);
            return 0;
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public int healAllPlayersByOnePercent(Connection conn) throws SQLException {
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = "UPDATE players SET hp = MIN(hp + CAST(CEILING(max_hp * 0.01) AS INTEGER), max_hp) WHERE hp < max_hp";
        } else {
            sql = "UPDATE players SET hp = LEAST(hp + CEIL(max_hp * 0.01), max_hp) WHERE hp < max_hp";
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }

    // ==================== 9. 行 → 实体映射 ====================

    /**
     * 灵根字符串 → 枚举（历史数据里存的是短名 {@code METAL} 这类旧值）。
     *
     * <p>放在这里是为了让"读 {@code players} 行"这条链路只有一份解析实现：
     * {@code PlayerService.safeParseSpiritualRoot} 保留原签名，转调本方法。</p>
     */
    public static SpiritualRoot parseSpiritualRoot(String s) {
        if (s == null || s.isBlank()) return SpiritualRoot.CHAOS_MIXED;
        s = s.trim().toUpperCase();
        return switch (s) {
            case "METAL" -> SpiritualRoot.TAIYI_GOLDEN;
            case "WOOD" -> SpiritualRoot.QINGDI_WOOD;
            case "WATER" -> SpiritualRoot.XUANMING_WATER;
            case "FIRE" -> SpiritualRoot.LIHUO_FIRE;
            case "EARTH" -> SpiritualRoot.HOUTU_EARTH;
            case "WIND" -> SpiritualRoot.XUNFENG_WIND;
            case "THUNDER" -> SpiritualRoot.JINGLEI_THUNDER;
            case "ICE" -> SpiritualRoot.XUANBING_ICE;
            default -> {
                try { yield SpiritualRoot.valueOf(s); }
                catch (Exception e) { yield SpiritualRoot.CHAOS_MIXED; }
            }
        };
    }

    /**
     * 行 → {@link PlayerInfo}（列表/榜单/语言解析用）。
     *
     * <p>{@code last_active_time} / {@code breakthrough_pity} / {@code consolidation} /
     * {@code lang} 这四列是迁移后补的，老库可能没有；读不到时保留字段默认值，
     * 不抛异常（原实现如此）。</p>
     */
    private static PlayerInfo mapPlayerInfo(ResultSet rs) throws SQLException {
        PlayerInfo pi = new PlayerInfo();
        pi.setId(rs.getLong("id"));
        pi.setUserId(rs.getLong("user_id"));
        pi.setName(rs.getString("name"));
        pi.setSpiritualRoot(parseSpiritualRoot(rs.getString("spiritual_root")));
        pi.setLevel(rs.getInt("level"));
        pi.setExperience(rs.getLong("experience"));
        pi.setRealm(rs.getInt("realm"));
        pi.setSubRealm(rs.getInt("sub_realm"));
        pi.setHp(rs.getInt("hp"));
        pi.setMaxHp(rs.getInt("max_hp"));
        pi.setMp(rs.getInt("mp"));
        pi.setMaxMp(rs.getInt("max_mp"));
        pi.setAttack(rs.getInt("attack"));
        pi.setDefense(rs.getInt("defense"));
        pi.setSpeed(rs.getInt("speed"));
        pi.setSpirit(rs.getInt("spirit"));
        pi.setGold(rs.getLong("gold"));
        pi.setCultivationProgress(rs.getInt("cultivation_progress"));
        pi.setCultivating(rs.getBoolean("is_cultivating"));
        pi.setCultivationStartTime(rs.getLong("cultivation_start_time"));
        pi.setLastSecretRealmTime(rs.getLong("last_secret_realm_time"));
        pi.setLastExplorationTime(rs.getLong("last_exploration_time"));
        pi.setTutorialStep(rs.getInt("tutorial_step"));
        pi.setTutorialTips(rs.getInt("tutorial_tips"));
        pi.setLastOfflineTime(rs.getLong("last_offline_time"));
        try { pi.setLastActiveTime(rs.getLong("last_active_time")); } catch (Exception ignored) {}
        try { pi.setBreakthroughPity(rs.getInt("breakthrough_pity")); } catch (Exception ignored) {}
        try { pi.setConsolidation(rs.getInt("consolidation")); } catch (Exception ignored) {}
        // 老库迁移后该列是空串：getString 返回 "" 而不是 null，与"未设置"等价。
        try { pi.setLanguage(rs.getString("lang")); } catch (Exception ignored) {}
        pi.setCreatedAt(rs.getString("created_at"));
        pi.setUpdatedAt(rs.getString("updated_at"));

        RealmConfig realmConfig = GameConfigLoader.getRealmConfig(pi.getRealm(), pi.getSubRealm());
        if (realmConfig != null) {
            pi.setRealmName(realmConfig.getFullName());
        }

        return pi;
    }

    /**
     * 行 → {@link Player}（玩法层用的完整实体）。
     *
     * <p>后半段的列都是后来迁移补上的，老库读不到时保留默认值。</p>
     */
    private static Player mapPlayer(ResultSet rs) throws SQLException {
        Player p = new Player();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setName(rs.getString("name"));
        p.setSpiritualRoot(parseSpiritualRoot(rs.getString("spiritual_root")));
        p.setLevel(rs.getInt("level"));
        p.setExperience(rs.getLong("experience"));
        p.setRealm(rs.getInt("realm"));
        p.setSubRealm(rs.getInt("sub_realm"));
        p.setHp(rs.getInt("hp"));
        p.setMaxHp(rs.getInt("max_hp"));
        p.setMp(rs.getInt("mp"));
        p.setMaxMp(rs.getInt("max_mp"));
        p.setAttack(rs.getInt("attack"));
        p.setDefense(rs.getInt("defense"));
        p.setSpeed(rs.getInt("speed"));
        p.setSpirit(rs.getInt("spirit"));
        p.setGold(rs.getLong("gold"));
        p.setCultivationProgress(rs.getInt("cultivation_progress"));
        p.setCultivating(rs.getBoolean("is_cultivating"));
        p.setCultivationStartTime(rs.getLong("cultivation_start_time"));
        p.setLastSecretRealmTime(rs.getLong("last_secret_realm_time"));
        p.setLastExplorationTime(rs.getLong("last_exploration_time"));
        p.setTutorialStep(rs.getInt("tutorial_step"));
        p.setTutorialTips(rs.getInt("tutorial_tips"));
        p.setLastOfflineTime(rs.getLong("last_offline_time"));
        try { p.setLastActiveTime(rs.getLong("last_active_time")); } catch (Exception ignored) {}
        try { p.setBattleStrategy(rs.getString("battle_strategy")); } catch (Exception ignored) {}
        try { p.setCurrentLocationId(rs.getLong("current_location_id")); } catch (Exception ignored) {}
        try { p.setLastTravelTime(rs.getLong("last_travel_time")); } catch (Exception ignored) {}
        try { p.setLastBreakthroughTime(rs.getLong("last_breakthrough_time")); } catch (Exception ignored) {}
        try { p.setBreakthroughPity(rs.getInt("breakthrough_pity")); } catch (Exception ignored) {}
        try { p.setConsolidation(rs.getInt("consolidation")); } catch (Exception ignored) {}
        try { p.setSecretRealmCount(rs.getInt("secret_realm_count")); } catch (Exception ignored) {}
        try { p.setPvpWins(rs.getInt("pvp_wins")); } catch (Exception ignored) {}
        try { p.setCraftCount(rs.getInt("craft_count")); } catch (Exception ignored) {}
        p.setCreatedAt(rs.getString("created_at"));
        p.setUpdatedAt(rs.getString("updated_at"));
        return p;
    }
}
