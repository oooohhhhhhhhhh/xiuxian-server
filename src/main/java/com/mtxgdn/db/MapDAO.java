package com.mtxgdn.db;

import com.mtxgdn.game.config.MapDefaults.LocationSeed;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 地图相关的三张配置表（{@code map_locations} / {@code map_connections} /
 * {@code map_location_event_bias}）与两张玩家侧表（{@code player_map_exploration}，
 * 以及 {@code players} 上的位置/冷却列）上的全部读写。
 *
 * <p><b>为什么要有这个类</b>：这些 SQL 原来散在
 * {@code com.mtxgdn.game.service.MapService} 里，一共 14 处
 * {@code DatabaseManager.getConnection()}，业务方法里混着
 * {@code PreparedStatement} / {@code ResultSet} / {@code SQLException}。
 * 后果不是"文件长了 1916 行"，而是：</p>
 * <ul>
 *   <li>地图加载、出厂数据补齐（含"老库缺列就退化"的列探测）、缓存重填
 *       与 SQL 逐条交织在一起 —— 想确认"缓存里到底放了什么"要跨过十几条语句；</li>
 *   <li>同一批数据被两种口径读了两遍（一次给加载用、一次给补齐用），
 *       改动时很容易只改一处，表现是"重启后数据对了、热重载后没对"；</li>
 *   <li>"哪些语句在事务里"看不出来：{@code loadFromDb} 全程用同一条连接、
 *       {@code recordFirstVisit} 的写入与发奖也必须共用一条连接，
 *       而其它读路径都是随手开的短连接。</li>
 * </ul>
 *
 * <p><b>切法</b>：按<b>业务意图</b>切，不按"一条 SQL 一个方法"切。
 * 例如"读某个出厂地点在库里的加成列播种状态"是一件事，哪怕它内部要按
 * {@code danger_level} 列是否存在切换两条 SQL；反过来，
 * {@code current_location_id} 的三次读取（只有它 / 连 {@code last_travel_time} 一起 /
 * 批量按 id 集合）是三个不同意图、三条不同 SQL，因此是三个方法 ——
 * 合并它们等于改 SQL，而本次重构一条语句都不许动。</p>
 *
 * <p><b>行 → 对象的映射放在哪</b>：本类只把 {@link ResultSet} 变成
 * {@link LocationRow} / {@link ConnectionRow} 这样的扁平记录，
 * <b>不</b>构造 {@code MapLocation}：那是地图语义（安全区与危险度矛盾要告警、
 * 野外节点要标 {@code wilderness}），属于服务层。老库缺列时"按没有该列降级"
 * 的取值规则（{@code readInt} / {@code readLong} / {@code readBonus}）留在本类，
 * 因为它纯粹是"这一列读不出来怎么办"。</p>
 *
 * <p><b>连接与事务</b>：每个方法都有一对重载 ——</p>
 * <ul>
 *   <li>{@code xxx(...)}：自己从 {@link DatabaseManager} 取一个短连接，用完即关，
 *       失败按原实现的文案包成 {@link RuntimeException}；</li>
 *   <li>{@code xxx(Connection conn, ...)}：<b>只用调用方传进来的连接</b>，
 *       绝不自己开连接、也绝不碰 {@code setAutoCommit}，失败直接抛
 *       {@link SQLException} 交给调用方。事务内必须走这一版：SQLite 的
 *       {@code BEGIN DEFERRED} 事务里若先读后写、期间又用<b>另一个连接</b>写同一张表，
 *       会直接抛 {@code SQLITE_BUSY_SNAPSHOT}（该错误不受 {@code busy_timeout} 约束），
 *       或者读到旧快照。</li>
 * </ul>
 *
 * <p><b>SQL 逐字保留</b>：表名、列名、条件、{@code ORDER BY}、{@code COALESCE}、
 * 参数顺序全部原样搬过来，一条没改、没合并、没"优化"。
 * 回归测试：{@code MapDaoExtractionTest}（含 SQL 守恒比对）。</p>
 */
public class MapDAO {

    /**
     * 在一条由 DAO 管理的连接上执行一组地图数据操作。
     *
     * <p><b>为什么需要这个入口</b>：{@code MapService.loadFromDb} 要先补齐
     * 出厂数据（写）再把整张地图读进内存缓存，两件事必须看到同一个库状态。
     * 服务层不能自己开连接（见 {@code JdbcBoundaryTest}），所以"取连接 +
     * 保证归还"收进 DAO，服务层只提供要执行的内容。</p>
     *
     * <p><b>刻意不是 {@code DatabaseManager.runTransaction}</b>：加载流程只往
     * 内存缓存里<b>加</b>（{@code loadFromDb} 开头清空，之后
     * {@code loadLocations}/{@code loadConnections} 只 {@code put}/{@code add}），
     * 而 {@code runTransaction} 在 {@code SQLITE_BUSY} 这类可重试冲突上会自动
     * 重试 —— 重试会把 {@code connections} 加重复。所以这里要一条<b>自动提交的
     * 普通连接</b>，与原来的 {@code try (Connection conn = getConnection())}
     * 逐字等价。</p>
     *
     * <p><b>不要拿它当"随手要一条连接"用</b>：事务内的读写请用各方法已有的
     * {@code (Connection, ...)} 重载。这个方法存在只为"整张地图的一次加载"。</p>
     */
    public void inLoadConnection(LoadWork work) {
        try (Connection conn = DatabaseManager.getConnection()) {
            inLoadConnection(conn, work);
        } catch (SQLException e) {
            throw new RuntimeException("加载地图数据失败", e);
        }
    }

    /**
     * 在<b>调用方的连接</b>上执行加载内容。
     *
     * <p>正常路径走自取连接版。这个重载存在是为了满足"每个 DAO 方法都有
     * Connection 首参版"的一致性约束（否则事务内调用只能另开连接），
     * 也方便测试注入一条自己控制的连接。</p>
     */
    public void inLoadConnection(Connection conn, LoadWork work) {
        try {
            work.run(conn);
        } catch (SQLException e) {
            throw new RuntimeException("加载地图数据失败", e);
        }
    }

    /** {@link #inLoadConnection} 的执行体。允许抛 {@link SQLException} 让 DAO 统一包。 */
    @FunctionalInterface
    public interface LoadWork {
        void run(Connection conn) throws SQLException;
    }

    private static final GameLogger LOG = GameLogger.getLogger(MapDAO.class);

    // ==================== 结果载体 ====================

    /**
     * {@code map_locations} 的一行，且已按"老库可能缺列"的规则取好值。
     *
     * <p>缺列时的取值规则与原实现一致：{@code danger_level} / {@code is_wilderness} /
     * {@code expires_at} / {@code entry_location_id} 缺列时分别是 {@code 0} / {@code false} /
     * {@code 0} / {@code 0}；加成列缺列或为 NULL 时是 {@code 1.0}
     * （加成永不缺失，否则修炼收益会凭空归零）。</p>
     */
    public record LocationRow(long id, String name, String description, String region,
                              int minRealm, boolean safeZone,
                              double cultivationBonus, double explorationBonus, int dangerLevel,
                              boolean wilderness, long expiresAt, long entryLocationId) {
    }

    /**
     * 某个出厂地点在库里的"加成列播种状态"：{@code NULL} 表示尚未播种
     * （老库刚升级上来），需要按出厂值回填；非 {@code NULL} 表示管理员已经调过，
     * 不许覆盖。
     *
     * @param exists 该 id 是否已有行（{@code false} 时其余字段无意义）
     */
    public record BonusSeedState(boolean exists, boolean cultivationUnseeded,
                                 boolean explorationUnseeded, boolean dangerUnseeded) {
    }

    /** {@code map_connections} 的一行（有向边 + 通行秒数）。 */
    public record ConnectionRow(long fromLocationId, long toLocationId, int travelTimeSeconds) {
    }

    /** {@code map_location_event_bias} 的一行（地界 → 事件权重倍率）。 */
    public record EventBiasRow(long locationId, String eventKey, double weightMultiplier) {
    }

    /**
     * 只读 {@code players.current_location_id} 时的结果。
     *
     * @param locationNull 该列是否为 {@code NULL}（{@code getLong} 之后必须立刻取
     *                     {@code wasNull()}，否则 {@code NULL} 会被静默当成 {@code 0}）
     */
    public record CurrentLocationRow(long locationId, boolean locationNull) {
    }

    /**
     * 玩家位置与赶路冷却的原始读数。
     *
     * <p>{@code rawLocationId} 刻意保留<b>库里的原值</b>（可能是失效 id 或 {@code NULL}）：
     * 条件写回（CAS）的期望值必须是原值，拿"回落到出生地之后"的值去比对会永远匹配不上，
     * 玩家就再也动不了。</p>
     */
    public record PlayerPositionRow(long rawLocationId, boolean locationNull, long lastTravelTime) {
    }

    /** 批量读位置时的一行：玩家 id → 库里的原始位置 id。 */
    public record PlayerLocationRow(long playerId, long rawLocationId, boolean locationNull) {
    }

    // ==================== 1. 地图定义：列探测与出厂数据补齐 ====================

    /**
     * 读 {@code map_locations} 的列名集合（小写）。
     *
     * <p>老库若迁移未成功可能缺 {@code danger_level} 等新列，调用方据此退化为
     * "只补旧列"，而不是让整个地图加载失败。因此<b>读不到列名不抛异常</b>：
     * 返回空集合（= 按最老的库处理）并记一条 warn。</p>
     */
    public Set<String> findLocationColumns() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findLocationColumns(conn);
        } catch (SQLException e) {
            throw new RuntimeException("读取地图地点表列名失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。读不到列名同样只记 warn、返回空集合。 */
    public Set<String> findLocationColumns(Connection conn) {
        Set<String> columns = new HashSet<>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT * FROM map_locations WHERE 1 = 0")) {
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnLabel(i).toLowerCase());
            }
        } catch (SQLException e) {
            LOG.warn("读取 map_locations 的列名失败（将按老库处理）: " + e.getMessage());
        }
        return columns;
    }

    /** {@code map_locations} 是否一行都没有（{@code true} = 新库，需要全量插入出厂数据）。 */
    public boolean isLocationTableEmpty() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return isLocationTableEmpty(conn);
        } catch (SQLException e) {
            throw new RuntimeException("检查地图地点表失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean isLocationTableEmpty(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM map_locations")) {
            rs.next();
            return rs.getInt(1) == 0;
        }
    }

    /**
     * 某个出厂地点的"加成列播种状态"。
     *
     * @param dangerColumnAvailable 库里是否有 {@code danger_level} 列；没有时那条列既不查也不回填
     */
    public BonusSeedState findBonusSeedState(long locationId, boolean dangerColumnAvailable) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findBonusSeedState(conn, locationId, dangerColumnAvailable);
        } catch (SQLException e) {
            throw new RuntimeException("读取地图地点加成列失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public BonusSeedState findBonusSeedState(Connection conn, long locationId, boolean dangerColumnAvailable)
            throws SQLException {
        String checkSql = dangerColumnAvailable
                ? "SELECT cultivation_bonus, exploration_bonus, danger_level FROM map_locations WHERE id = ?"
                : "SELECT cultivation_bonus, exploration_bonus FROM map_locations WHERE id = ?";
        boolean exists = false;
        boolean cultivationUnseeded = false;
        boolean explorationUnseeded = false;
        boolean dangerUnseeded = false;
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                    rs.getDouble("cultivation_bonus");
                    cultivationUnseeded = rs.wasNull();
                    rs.getDouble("exploration_bonus");
                    explorationUnseeded = rs.wasNull();
                    if (dangerColumnAvailable) {
                        rs.getInt("danger_level");
                        dangerUnseeded = rs.wasNull();
                    }
                }
            }
        }
        return new BonusSeedState(exists, cultivationUnseeded, explorationUnseeded, dangerUnseeded);
    }

    /** 该地点 id 是否已经有行。 */
    public boolean existsLocation(long id) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return existsLocation(conn, id);
        } catch (SQLException e) {
            throw new RuntimeException("检查地图地点是否存在失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean existsLocation(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 回填仍为 {@code NULL} 的加成列（{@code COALESCE} 保证管理员调过的值不被覆盖）。
     *
     * <p>"播种"是单向的：一旦有值就不再改，因此这条 UPDATE 对已经播过种的行是空操作。</p>
     *
     * @param withDangerLevel 库里是否有 {@code danger_level} 列
     */
    public void seedLocationBonusColumns(long locationId, double cultivationBonus, double explorationBonus,
                                         boolean withDangerLevel, int dangerLevel) {
        try (Connection conn = DatabaseManager.getConnection()) {
            seedLocationBonusColumns(conn, locationId, cultivationBonus, explorationBonus,
                    withDangerLevel, dangerLevel);
        } catch (SQLException e) {
            throw new RuntimeException("回填地图地点加成失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void seedLocationBonusColumns(Connection conn, long locationId, double cultivationBonus,
                                         double explorationBonus, boolean withDangerLevel, int dangerLevel)
            throws SQLException {
        String sql = "UPDATE map_locations SET "
                + "cultivation_bonus = COALESCE(cultivation_bonus, ?), "
                + "exploration_bonus = COALESCE(exploration_bonus, ?)"
                + (withDangerLevel ? ", danger_level = COALESCE(danger_level, ?)" : "")
                + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            ps.setDouble(index++, cultivationBonus);
            ps.setDouble(index++, explorationBonus);
            if (withDangerLevel) {
                ps.setInt(index++, dangerLevel);
            }
            ps.setLong(index, locationId);
            ps.executeUpdate();
        }
    }

    /**
     * 插入一个出厂地点（缺列时不写该列）。
     *
     * <p>列清单只能由 {@link #findLocationColumns} 的结果驱动：老库缺
     * {@code cultivation_bonus} / {@code danger_level} 时写这些列会直接报
     * {@code no such column}，整个地图加载随之失败。</p>
     */
    public void insertLocation(LocationSeed seed, Set<String> columns) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertLocation(conn, seed, columns);
        } catch (SQLException e) {
            throw new RuntimeException("补齐地图地点失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertLocation(Connection conn, LocationSeed seed, Set<String> columns) throws SQLException {
        boolean withBonuses = columns.contains("cultivation_bonus") && columns.contains("exploration_bonus");
        boolean withDanger = columns.contains("danger_level");

        StringBuilder cols = new StringBuilder("(id, name, description, region, min_realm, is_safe_zone");
        StringBuilder marks = new StringBuilder("VALUES (?, ?, ?, ?, ?, ?");
        if (withBonuses) {
            cols.append(", cultivation_bonus, exploration_bonus");
            marks.append(", ?, ?");
        }
        if (withDanger) {
            cols.append(", danger_level");
            marks.append(", ?");
        }
        cols.append(")");
        marks.append(")");

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO map_locations " + cols + " " + marks)) {
            int i = 1;
            ps.setLong(i++, seed.id());
            ps.setString(i++, seed.name());
            ps.setString(i++, seed.description());
            ps.setString(i++, seed.region());
            ps.setInt(i++, seed.minRealm());
            ps.setBoolean(i++, seed.safeZone());
            if (withBonuses) {
                ps.setDouble(i++, seed.cultivationBonus());
                ps.setDouble(i++, seed.explorationBonus());
            }
            if (withDanger) {
                ps.setInt(i, seed.dangerLevel());
            }
            ps.executeUpdate();
        }
    }

    /** 全量插入出厂地点（新库首次安装）。 */
    public void insertDefaultLocations(List<LocationSeed> seeds, Set<String> columns) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertDefaultLocations(conn, seeds, columns);
        } catch (SQLException e) {
            throw new RuntimeException("写入出厂地图地点失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertDefaultLocations(Connection conn, List<LocationSeed> seeds, Set<String> columns)
            throws SQLException {
        boolean withDanger = columns.contains("danger_level");
        String sql = "INSERT INTO map_locations "
                + "(id, name, description, region, min_realm, is_safe_zone, cultivation_bonus, exploration_bonus"
                + (withDanger ? ", danger_level" : "") + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?" + (withDanger ? ", ?" : "") + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (LocationSeed seed : seeds) {
                ps.setLong(1, seed.id());
                ps.setString(2, seed.name());
                ps.setString(3, seed.description());
                ps.setString(4, seed.region());
                ps.setInt(5, seed.minRealm());
                ps.setBoolean(6, seed.safeZone());
                ps.setDouble(7, seed.cultivationBonus());
                ps.setDouble(8, seed.explorationBonus());
                if (withDanger) {
                    ps.setInt(9, seed.dangerLevel());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 全量插入出厂连接（无向，每个 {@code {a, b}} 落两行）。
     *
     * <p>耗时 {@code 5} 秒是写死在 SQL 里的字面量，与原实现一致 ——
     * 出厂路网的默认时长就该是常量，不需要调用方传。</p>
     */
    public void insertDefaultConnections(long[][] pairs) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertDefaultConnections(conn, pairs);
        } catch (SQLException e) {
            throw new RuntimeException("写入出厂地图连接失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertDefaultConnections(Connection conn, long[][] pairs) throws SQLException {
        String connSql = "INSERT INTO map_connections (from_location_id, to_location_id, travel_time_seconds) VALUES (?, ?, 5)";
        try (PreparedStatement ps = conn.prepareStatement(connSql)) {
            for (long[] pair : pairs) {
                ps.setLong(1, pair[0]);
                ps.setLong(2, pair[1]);
                ps.addBatch();
                ps.setLong(1, pair[1]);
                ps.setLong(2, pair[0]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 全部连接的端点（{@code {from, to}}），用于补"只能去不能回"的单向历史数据。 */
    public List<long[]> findAllConnectionEdges() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findAllConnectionEdges(conn);
        } catch (SQLException e) {
            throw new RuntimeException("读取地图连接失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<long[]> findAllConnectionEdges(Connection conn) throws SQLException {
        String reverseSql = "SELECT from_location_id, to_location_id FROM map_connections";
        List<long[]> existing = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(reverseSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                existing.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
        }
        return existing;
    }

    /**
     * 连接不存在时插入（默认耗时 5 秒）。
     *
     * <p>{@code map_connections} 上没有唯一键，所以"先查后插"是唯一的去重手段；
     * 这一步只在地图加载期（单线程、持锁）执行，不承担并发去重的职责。</p>
     */
    public void insertConnectionIfMissing(long from, long to) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertConnectionIfMissing(conn, from, to);
        } catch (SQLException e) {
            throw new RuntimeException("补齐地图连接失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertConnectionIfMissing(Connection conn, long from, long to) throws SQLException {
        String checkSql = "SELECT COUNT(*) FROM map_connections WHERE from_location_id = ? AND to_location_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        }
        String sql = "INSERT INTO map_connections (from_location_id, to_location_id, travel_time_seconds) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setInt(3, 5);
            ps.executeUpdate();
        }
    }

    /** 事件偏置不存在时插入（表上有 {@code UNIQUE (location_id, event_key)}，但仍沿用先查后插）。 */
    public void insertEventBiasIfMissing(long locationId, String eventKey, double multiplier) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertEventBiasIfMissing(conn, locationId, eventKey, multiplier);
        } catch (SQLException e) {
            throw new RuntimeException("补齐地图事件偏置失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertEventBiasIfMissing(Connection conn, long locationId, String eventKey, double multiplier)
            throws SQLException {
        String checkSql = "SELECT COUNT(*) FROM map_location_event_bias WHERE location_id = ? AND event_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, locationId);
            ps.setString(2, eventKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        }
        String sql = "INSERT INTO map_location_event_bias (location_id, event_key, weight_multiplier) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, locationId);
            ps.setString(2, eventKey);
            ps.setDouble(3, multiplier);
            ps.executeUpdate();
        }
    }

    /**
     * 先删后插地替换某地点某事件的权重偏置。
     *
     * <p>两步必须共用同一条连接：中间若另开连接，删与插会落在两次独立提交上，
     * 崩在中间就留下"偏置被删了却没插回去"的空档 —— 该事件退回默认权重，
     * 而管理员看到的是"保存成功"。</p>
     *
     * <p><b>刻意不用 UPSERT 语法</b>：SQLite 写
     * {@code ON CONFLICT ... DO UPDATE}，MySQL 写
     * {@code ON DUPLICATE KEY UPDATE}，两份分支要各自测。而这里对并发不敏感
     * （管理台手工操作），换一份两端通用的 SQL 更划算。</p>
     */
    public void replaceEventBias(long locationId, String eventKey, double multiplier) {
        try (Connection conn = DatabaseManager.getConnection()) {
            replaceEventBias(conn, locationId, eventKey, multiplier);
        } catch (SQLException e) {
            throw new RuntimeException("保存地图事件偏置失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void replaceEventBias(Connection conn, long locationId, String eventKey, double multiplier)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM map_location_event_bias WHERE location_id = ? AND event_key = ?")) {
            ps.setLong(1, locationId);
            ps.setString(2, eventKey);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO map_location_event_bias (location_id, event_key, weight_multiplier) "
                        + "VALUES (?, ?, ?)")) {
            ps.setLong(1, locationId);
            ps.setString(2, eventKey);
            ps.setDouble(3, multiplier);
            ps.executeUpdate();
        }
    }

    /** 删掉某地点某事件的偏置，返回受影响行数（0 表示本来就没有）。 */
    public int deleteEventBias(long locationId, String eventKey) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return deleteEventBias(conn, locationId, eventKey);
        } catch (SQLException e) {
            throw new RuntimeException("删除地图事件偏置失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public int deleteEventBias(Connection conn, long locationId, String eventKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM map_location_event_bias WHERE location_id = ? AND event_key = ?")) {
            ps.setLong(1, locationId);
            ps.setString(2, eventKey);
            return ps.executeUpdate();
        }
    }

    // ==================== 2. 加载：地点 / 连接 / 事件偏置 ====================

    /**
     * 全部地点（{@code ORDER BY id}，保证缓存里 {@code LinkedHashMap} 的顺序稳定）。
     *
     * <p>列探测与取值退化都在这里一次做完，调用方拿到的行已经可以直接变成
     * {@code MapLocation}；过滤与告警（安全区不能是险地）留在服务层。</p>
     */
    public List<LocationRow> findAllLocations() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findAllLocations(conn);
        } catch (SQLException e) {
            throw new RuntimeException("读取地图地点失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<LocationRow> findAllLocations(Connection conn) throws SQLException {
        Set<String> columns = findLocationColumns(conn);
        boolean hasDanger = columns.contains("danger_level");
        boolean hasWilderness = columns.contains("is_wilderness");
        boolean hasExpires = columns.contains("expires_at");
        boolean hasEntry = columns.contains("entry_location_id");

        List<LocationRow> locations = new ArrayList<>();
        String sql = "SELECT * FROM map_locations ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rows = ps.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                locations.add(new LocationRow(
                        id, rows.getString("name"), rows.getString("description"),
                        rows.getString("region"), rows.getInt("min_realm"),
                        rows.getBoolean("is_safe_zone"),
                        readBonus(rows, "cultivation_bonus"),
                        readBonus(rows, "exploration_bonus"),
                        hasDanger ? readInt(rows, "danger_level", 0) : 0,
                        hasWilderness && readInt(rows, "is_wilderness", 0) != 0,
                        hasExpires ? readLong(rows, "expires_at", 0L) : 0L,
                        hasEntry ? readLong(rows, "entry_location_id", 0L) : 0L));
            }
        }
        return locations;
    }

    /** 全部连接（有向边 + 通行秒数）。 */
    public List<ConnectionRow> findAllConnections() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findAllConnections(conn);
        } catch (SQLException e) {
            throw new RuntimeException("读取地图连接失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<ConnectionRow> findAllConnections(Connection conn) throws SQLException {
        List<ConnectionRow> rows = new ArrayList<>();
        String sql = "SELECT * FROM map_connections";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new ConnectionRow(rs.getLong("from_location_id"),
                        rs.getLong("to_location_id"), rs.getInt("travel_time_seconds")));
            }
        }
        return rows;
    }

    /**
     * 全部地界事件偏置。
     *
     * <p>原样返回库里的行（含 {@code NULL} 的 key 与非正的倍率），
     * "哪些偏置算数"是事件抽取的语义，留在服务层过滤。</p>
     */
    public List<EventBiasRow> findAllEventBiases() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findAllEventBiases(conn);
        } catch (SQLException e) {
            throw new RuntimeException("读取地图事件偏置失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<EventBiasRow> findAllEventBiases(Connection conn) throws SQLException {
        List<EventBiasRow> rows = new ArrayList<>();
        String sql = "SELECT location_id, event_key, weight_multiplier FROM map_location_event_bias";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new EventBiasRow(rs.getLong("location_id"), rs.getString("event_key"),
                        rs.getDouble("weight_multiplier")));
            }
        }
        return rows;
    }

    // ==================== 3. 玩家位置：读 ====================

    /** 只读玩家当前所在地点 id；玩家不存在时返回 {@code null}。 */
    public CurrentLocationRow findCurrentLocation(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findCurrentLocation(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家位置失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public CurrentLocationRow findCurrentLocation(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT current_location_id FROM players WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long locId = rs.getLong("current_location_id");
                    boolean wasNull = rs.wasNull();
                    return new CurrentLocationRow(locId, wasNull);
                }
            }
        }
        return null;
    }

    /**
     * 读位置与赶路冷却的原始值（CAS 的期望值来源）；玩家不存在时返回 {@code null}。
     *
     * <p>与 {@link #findCurrentLocation} 是两条不同的语句：这里多读
     * {@code last_travel_time}，因为条件写回要用它做"原子认领"的第二个谓词。</p>
     */
    public PlayerPositionRow findPlayerPosition(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findPlayerPosition(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家位置失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public PlayerPositionRow findPlayerPosition(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT current_location_id, last_travel_time FROM players WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long rawLoc = rs.getLong("current_location_id");
                    boolean locNull = rs.wasNull();
                    long lastTravel = rs.getLong("last_travel_time");
                    if (rs.wasNull()) lastTravel = 0;
                    return new PlayerPositionRow(rawLoc, locNull, lastTravel);
                }
            }
        }
        return null;
    }

    /** 上次赶路时刻（epoch 秒）；玩家不存在或列为 {@code NULL} 时返回 {@code 0}。 */
    public long findLastTravelTime(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findLastTravelTime(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家旅行时间失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public long findLastTravelTime(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT last_travel_time FROM players WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long t = rs.getLong("last_travel_time");
                    return rs.wasNull() ? 0 : t;
                }
            }
        }
        return 0;
    }

    /**
     * 批量读玩家位置（一次查完，供列表页避免 N+1）。
     *
     * <p>{@code IN} 的占位符个数由 {@code playerIds} 的长度决定，因此这条 SQL
     * 只能拼出来（参数化不了占位符个数）。空集合不该走到这里 —— 拼出
     * {@code IN ()} 是语法错误，调用方必须先判空。</p>
     */
    public List<PlayerLocationRow> findPlayerLocations(List<Long> playerIds) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findPlayerLocations(conn, playerIds);
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家位置失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<PlayerLocationRow> findPlayerLocations(Connection conn, List<Long> playerIds) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id, current_location_id FROM players WHERE id IN (");
        for (int i = 0; i < playerIds.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");

        List<PlayerLocationRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < playerIds.size(); i++) {
                ps.setLong(i + 1, playerIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pid = rs.getLong("id");
                    long rawLoc = rs.getLong("current_location_id");
                    boolean wasNull = rs.wasNull();
                    rows.add(new PlayerLocationRow(pid, rawLoc, wasNull));
                }
            }
        }
        return rows;
    }

    // ==================== 4. 玩家位置：写 ====================

    /**
     * 条件写回位置与冷却戳（CAS），返回影响行数（{@code 0} = 校验期间被别的请求改过）。
     *
     * <p>{@code current_location_id IS NULL} 与 {@code current_location_id = ?} 是
     * 两个不同的谓词：期望值是 {@code NULL} 时只能比 {@code IS NULL}，
     * 拿 {@code = NULL} 去比永远匹配不上。</p>
     *
     * @param expected CAS 的期望值（必须是库里的原值，不是回落后的有效值）
     */
    public int updateTravelIfUnchanged(long playerId, PlayerPositionRow expected,
                                       long targetLocationId, long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return updateTravelIfUnchanged(conn, playerId, expected, targetLocationId, now);
        } catch (SQLException e) {
            throw new RuntimeException("移动失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public int updateTravelIfUnchanged(Connection conn, long playerId, PlayerPositionRow expected,
                                       long targetLocationId, long now) throws SQLException {
        String locationPredicate = expected.locationNull()
                ? "current_location_id IS NULL"
                : "current_location_id = ?";
        String sql = "UPDATE players SET current_location_id = ?, last_travel_time = ? "
                + "WHERE id = ? AND " + locationPredicate + " AND COALESCE(last_travel_time, 0) = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, targetLocationId);
            ps.setLong(2, now);
            ps.setLong(3, playerId);
            int index = 4;
            if (!expected.locationNull()) {
                ps.setLong(index++, expected.rawLocationId());
            }
            ps.setLong(index, expected.lastTravelTime());
            return ps.executeUpdate();
        }
    }

    /** 无条件写回位置与冷却戳（传送符用：它不走邻接校验，也不需要 CAS）。 */
    public void updateTravel(long playerId, long targetLocationId, long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateTravel(conn, playerId, targetLocationId, now);
        } catch (SQLException e) {
            throw new RuntimeException("移动失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateTravel(Connection conn, long playerId, long targetLocationId, long now) throws SQLException {
        String sql = "UPDATE players SET current_location_id = ?, last_travel_time = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, targetLocationId);
            ps.setLong(2, now);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        }
    }

    // ==================== 5. 探索记录（player_map_exploration） ====================

    /**
     * 写首次到达记录，返回 {@code true} 表示<b>本次</b>是首次。
     *
     * <p>用 {@code INSERT OR IGNORE} + {@code UNIQUE (player_id, location_id)} 判重，
     * 而不是"先查后插"：后者在两个请求同时首次到达同一地点时会双双判定为首次、
     * 各发一份奖励。影响行数为 0 即"唯一键冲突 = 早就来过"。</p>
     */
    public boolean insertExplorationIfAbsent(long playerId, long locationId, long firstVisitedAt) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return insertExplorationIfAbsent(conn, playerId, locationId, firstVisitedAt);
        } catch (SQLException e) {
            throw new RuntimeException("记录探索进度失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean insertExplorationIfAbsent(Connection conn, long playerId, long locationId, long firstVisitedAt)
            throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "INSERT OR IGNORE INTO player_map_exploration (player_id, location_id, first_visited_at) VALUES (?, ?, ?)"
                : "INSERT IGNORE INTO player_map_exploration (player_id, location_id, first_visited_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, locationId);
            ps.setLong(3, firstVisitedAt);
            return ps.executeUpdate() > 0;
        }
    }

    /** 玩家是否到过该地点（传送点解锁与迷雾展示都以此为准）。 */
    public boolean isExplored(long playerId, long locationId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return isExplored(conn, playerId, locationId);
        } catch (SQLException e) {
            throw new RuntimeException("查询探索记录失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean isExplored(Connection conn, long playerId, long locationId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM player_map_exploration WHERE player_id = ? AND location_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** 玩家已探索的全部地点 id（一次查完，供列表页避免 N+1）。 */
    public Set<Long> findExploredLocationIds(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findExploredLocationIds(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询探索记录失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public Set<Long> findExploredLocationIds(Connection conn, long playerId) throws SQLException {
        Set<Long> result = new HashSet<>();
        String sql = "SELECT location_id FROM player_map_exploration WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong(1));
                }
            }
        }
        return result;
    }

    // ==================== 6. 采集冷却（players.last_gather_time） ====================

    /** 上次采集时刻（epoch 秒）；玩家不存在、列为 {@code NULL} 或该列尚不存在时返回 {@code 0}。 */
    public long findLastGatherTime(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findLastGatherTime(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("读取采集冷却失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public long findLastGatherTime(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT last_gather_time FROM players WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long value = rs.getLong(1);
                    return rs.wasNull() ? 0 : value;
                }
            }
        }
        return 0;
    }

    /** 写入本次采集时刻。 */
    public void updateLastGatherTime(long playerId, long gatherTime) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateLastGatherTime(conn, playerId, gatherTime);
        } catch (SQLException e) {
            throw new RuntimeException("更新采集时间失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateLastGatherTime(Connection conn, long playerId, long gatherTime) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET last_gather_time = ? WHERE id = ?")) {
            ps.setLong(1, gatherTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    // ==================== 7. 野外分片维护：位置区间查询 ====================

    /**
     * 区间 {@code [fromInclusive, toExclusive)} 内是否有玩家（野外分片回收前的安全检查）。
     *
     * <p>靠"同一分片的节点 id 是连续区间"这一性质，用一个范围查询就能问出来，
     * 不必把玩家逐个取回来比对 —— 这也是当初把 id 设计成
     * {@code ID_BASE + chunkKey * SLOT_STRIDE + slot} 的回报之一。</p>
     */
    public boolean hasPlayerInLocationRange(long fromInclusive, long toExclusive) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return hasPlayerInLocationRange(conn, fromInclusive, toExclusive);
        } catch (SQLException e) {
            throw new RuntimeException("查询分片内玩家失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean hasPlayerInLocationRange(Connection conn, long fromInclusive, long toExclusive)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM players WHERE current_location_id >= ? AND current_location_id < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fromInclusive);
            ps.setLong(2, toExclusive);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    // ==================== 行 → 值的退化读取（老库缺列时用默认值） ====================

    /** 读取整型列；列为 NULL 或不存在（老库尚未升级）时用默认值。 */
    private static int readInt(ResultSet rows, String column, int fallback) {
        try {
            int value = rows.getInt(column);
            return rows.wasNull() ? fallback : value;
        } catch (SQLException e) {
            return fallback;
        }
    }

    /** 读取长整型列；列为 NULL 或不存在（老库尚未升级）时用默认值。 */
    private static long readLong(ResultSet rows, String column, long fallback) {
        try {
            long value = rows.getLong(column);
            return rows.wasNull() ? fallback : value;
        } catch (SQLException e) {
            return fallback;
        }
    }

    /** 读取加成列；列为 NULL 或异常（老库尚未升级）时按 1.0 处理，保证加成永不缺失。 */
    private static double readBonus(ResultSet rows, String column) {
        try {
            double value = rows.getDouble(column);
            if (rows.wasNull() || value <= 0) {
                return 1.0;
            }
            return value;
        } catch (SQLException e) {
            return 1.0;
        }
    }
    // ==================== 后台编辑：地点 / 连接的直接增删改 ====================
    //
    // 这一段服务 /admin 的地图编辑器。与上面的"玩家路径"方法刻意分开：
    // 玩家路径只读、只改自己的位置；这里动的是地图定义本身，会连带清理
    // 指向它的连接/偏置/探索记录，并把还站在这里的玩家送回出生点。

    /**
     * 按列集合更新一个地点（列名由调用方给，必须是<b>字面量白名单</b>）。
     *
     * <p><b>为什么列名是拼进去的</b>：{@code UPDATE ... SET ? = ?} 不是合法 SQL，
     * 列名不能绑参。调用方（后台资源）的列名全部来自自己的字面量分支，
     * 不是用户输入，因此没有注入面；这条约定必须跟着方法一起写在文档里，
     * 否则下一个人很可能把请求里的 key 直接拼进来。</p>
     *
     * <p><b>值按类型绑参</b>：{@code Boolean} / {@code Integer} / {@code Double}
     * 各有对应的 setter，其余一律当字符串。SQLite 的类型是动态的，
     * 用错 setter 会把 {@code is_safe_zone} 写成字符串 {@code "true"}。</p>
     *
     * @return 受影响行数（0 表示这个 id 不存在，调用方据此回 404）
     */
    public int updateLocationFields(Connection conn, long id, Map<String, Object> updates) throws SQLException {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE map_locations SET "
                + String.join(", ", updates.keySet().stream().map(col -> col + " = ?").toList())
                + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            for (Object value : updates.values()) {
                if (value instanceof Boolean b) {
                    ps.setBoolean(index++, b);
                } else if (value instanceof Integer i) {
                    ps.setInt(index++, i);
                } else if (value instanceof Double d) {
                    ps.setDouble(index++, d);
                } else {
                    ps.setString(index++, (String) value);
                }
            }
            ps.setLong(index, id);
            return ps.executeUpdate();
        }
    }

    /**
     * 后台新建一个<b>已知世界</b>地点（{@code is_wilderness} 写死 0）。
     *
     * <p>{@code is_wilderness = 0} 是字面量而不是参数：野外节点由坐标推导、
     * 由刷新逻辑自己维护，管理员新建的永远是已知世界的地点。
     * 把它做成参数只会让人误以为可以手建野外节点。</p>
     */
    public void insertAdminLocation(Connection conn, long id, String name, String description,
                                    String region, int minRealm, boolean safeZone,
                                    double cultivationBonus, double explorationBonus, int dangerLevel)
            throws SQLException {
        String sql = "INSERT INTO map_locations "
                + "(id, name, description, region, min_realm, is_safe_zone, cultivation_bonus,"
                + " exploration_bonus, danger_level, is_wilderness) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, region);
            ps.setInt(5, minRealm);
            ps.setBoolean(6, safeZone);
            ps.setDouble(7, cultivationBonus);
            ps.setDouble(8, explorationBonus);
            ps.setInt(9, dangerLevel);
            ps.executeUpdate();
        }
    }

    /** 取地点名；不存在返回 {@code null}（删除前要拿它写日志）。 */
    public String findLocationName(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * 把一个地点的<b>安全区 / 危险度</b>读出来。
     *
     * <p>调用方拿它和"本次请求要改的值"合并后再校验不变式 ——
     * 只改其中一个字段同样能造出矛盾数据（例如给已是安全区的地点填危险度 3）。</p>
     *
     * <p>{@code danger_level} 用 {@code COALESCE(..., 0)} 兜底：老库可能还没有这一列的值。</p>
     *
     * @return 行不存在时返回 {@code null}；列缺失等 SQL 错误照常抛出，
     *         由调用方决定是否放过（后台编辑器的做法是放过，把错误留给随后的 UPDATE）
     */
    public DangerRow findDangerInfo(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT is_safe_zone, COALESCE(danger_level, 0) FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new DangerRow(rs.getBoolean(1), rs.getInt(2));
            }
        }
    }

    /** 一个地点的安全区标志与危险度。 */
    public record DangerRow(boolean safeZone, int dangerLevel) {
    }

    /**
     * 数一下除自己以外还有没有同名地点。
     *
     * <p>地点名是 {@code /地图 go <地名>} 的唯一解析依据，重名会让其中一个
     * 永远无法通过指令抵达（{@code getLocationByName} 只返回第一个）。</p>
     *
     * @param excludeId 更新场景下排除自身；新建传 0
     */
    public int countLocationsByNameExceptId(Connection conn, String name, long excludeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM map_locations WHERE name = ? AND id <> ?")) {
            ps.setString(1, name);
            ps.setLong(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * 已知世界范围内（{@code id < wildernessFloor}）的下一个空闲 id。
     *
     * <p>结果被夹在 {@code [1, wildernessFloor - 1]}：{@code MAX(id) + 1} 在表空时是 1，
     * 但如果已知世界已经排到边界，再加一就会落进野外 id 段 ——
     * 那一段由坐标推导，手填进去的地点会在下次野外刷新时被当成过期节点删掉。</p>
     */
    public long findNextKnownWorldLocationId(Connection conn, long wildernessFloor) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM map_locations WHERE id < ?")) {
            ps.setLong(1, wildernessFloor);
            try (ResultSet rs = ps.executeQuery()) {
                long next = rs.next() ? rs.getLong(1) : 1L;
                return Math.max(1L, Math.min(next, wildernessFloor - 1));
            }
        }
    }

    /**
     * 删地点前把还站在这里的玩家送回出生点。
     *
     * <p><b>必须先于删除执行</b>：留在已删除地点上的存档会在下次读取时
     * 被判为异常位置。{@code last_travel_time} 一并清零，免得残留的冷却
     * 让玩家刚被送回就动不了。</p>
     *
     * @return 被挪走的玩家数（调用方据此记 warn）
     */
    public int relocatePlayersToSpawn(Connection conn, long locationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE players SET current_location_id = 1, last_travel_time = 0 "
                        + "WHERE current_location_id = ?")) {
            ps.setLong(1, locationId);
            return ps.executeUpdate();
        }
    }

    /** 删掉所有<b>一端</b>是这个地点的连接（无向意义上的清场）。 */
    public int deleteConnectionsTouching(Connection conn, long locationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM map_connections WHERE from_location_id = ? OR to_location_id = ?")) {
            ps.setLong(1, locationId);
            ps.setLong(2, locationId);
            return ps.executeUpdate();
        }
    }

    /** 删掉某地点的全部事件偏置。 */
    public int deleteEventBiasByLocation(Connection conn, long locationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM map_location_event_bias WHERE location_id = ?")) {
            ps.setLong(1, locationId);
            return ps.executeUpdate();
        }
    }

    /**
     * 删掉某地点的全部探索记录。
     *
     * <p>不删的话会留下指向不存在地点的孤儿行；同一个 id 被复用时，
     * 新地点会直接显示成"已探索"，首达奖励也就永远发不出来。</p>
     */
    public int deleteExplorationByLocation(Connection conn, long locationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM player_map_exploration WHERE location_id = ?")) {
            ps.setLong(1, locationId);
            return ps.executeUpdate();
        }
    }

    /** 删掉地点本身，返回受影响行数。 */
    public int deleteLocationById(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    /**
     * 删一条<b>有向</b>连接；{@code bothWays} 时连反向边一起删。
     *
     * <p>{@code bothWays} 是默认行为：地图的邻接是双向可达的，
     * 只删单向会留下"只能来不能回"的路，而库里历史上就出现过这种数据
     * （{@code repairReverseConnections} 一直在兜）。</p>
     *
     * <p>两条 SQL 的差别只在 {@code WHERE}：{@code bothWays} 那条用
     * {@code (from,to) OR (to,from)} 一次删完，不分成两次往返。</p>
     *
     * @return 受影响行数（0 表示这条连接不存在，调用方据此回 404）
     */
    public int deleteConnectionPair(Connection conn, long from, long to, boolean bothWays) throws SQLException {
        String sql = bothWays
                ? "DELETE FROM map_connections WHERE (from_location_id = ? AND to_location_id = ?)"
                + " OR (from_location_id = ? AND to_location_id = ?)"
                : "DELETE FROM map_connections WHERE from_location_id = ? AND to_location_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            if (bothWays) {
                ps.setLong(3, to);
                ps.setLong(4, from);
            }
            return ps.executeUpdate();
        }
    }

    /**
     * 覆盖写一条<b>有向</b>连接：先删同向的旧边，再插入新边。
     *
     * <p><b>为什么是"先删再插"而不是 {@code INSERT OR REPLACE}</b>：
     * {@code map_connections} 没有唯一约束（历史上允许重复边，
     * {@code repairReverseConnections} 会去重），{@code INSERT OR REPLACE}
     * 在没有唯一键时等价于纯插入，删不掉旧边 —— 结果是同一个方向出现两条
     * 不同时长的路，走哪条取决于查询顺序。</p>
     *
     * <p>两步必须由调用方放在同一个事务里（本方法不自己开事务）：
     * 删完插失败会留下一条不存在的路。</p>
     */
    public void replaceConnection(Connection conn, long from, long to, int travelSeconds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM map_connections WHERE from_location_id = ? AND to_location_id = ?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO map_connections (from_location_id, to_location_id, travel_time_seconds) "
                        + "VALUES (?, ?, ?)")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setInt(3, travelSeconds);
            ps.executeUpdate();
        }
    }
}