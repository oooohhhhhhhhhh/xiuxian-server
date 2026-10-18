package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.config.MapDefaults;
import com.mtxgdn.game.config.MapDefaults.EventBiasSeed;
import com.mtxgdn.game.config.MapDefaults.LocationSeed;
import com.mtxgdn.game.config.WildernessMapConfig;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.map.WildernessChunk;
import com.mtxgdn.game.map.WildernessChunkStore;
import com.mtxgdn.game.map.WildernessTerrain;
import com.mtxgdn.entity.Player;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import com.mtxgdn.i18n.I18n;

/**
 * 游戏地图服务 —— 管理地点、路线和玩家移动。
 *
 * <p>本服务承载两类地点，它们的存储方式<b>刻意不同</b>：</p>
 *
 * <ul>
 *   <li><b>已知世界</b>：出厂 15 个固定地点（可被管理台增删改），以数据库为唯一真相源，
 *       内存缓存可通过 {@link #reload()} 热刷新，无需重启进程。</li>
 *   <li><b>野外</b>：一片由 {@code worldSeed} 确定性生成、可以无限向外扩展的分片世界。
 *       <b>地形完全不入库</b> —— 节点名、描述、危险度、连通关系都是
 *       {@link WildernessTerrain} 由 {@code (世界种子, 分片坐标)} 现算出来的，
 *       连 {@code map_locations} 里都不会有一行。真正需要落盘的只有"谁探索过、
 *       资源还剩多少"，存在 {@link WildernessChunkStore} 管理的分片文件里。</li>
 * </ul>
 *
 * <p>这样做的收益是：数据库不会随世界扩张而膨胀（无限世界里"已生成的区域"可以无限大），
 * 分片文件可读可备份可手工修，而且因为地形没有第二份副本，
 * <b>它永远不可能与存档走样</b> —— 分片被回收后重新走到，地形与原来逐字节一致。</p>
 *
 * <p>代价是野外节点不能走"从缓存查表"的老路：{@link #getLocation(long)} 对野外 id
 * 会现算节点，{@link #getNeighbors(long)} 会现算邻接。赶路冷却、境界门槛、
 * 并发 CAS 这些既有逻辑因此原样复用，不需要知道地点是查出来的还是算出来的。</p>
 */
public class MapService {

    private static final GameLogger LOG = GameLogger.getLogger(MapService.class);

    /** 连接未配置路程秒数时的兜底值（后台手加连接可能留空）。 */
    private static final long TRAVEL_COOLDOWN_SECONDS = 30;
    /** 路程下限：即使连接被配成 0 或负数，也不允许瞬移。 */
    private static final long MIN_TRAVEL_SECONDS = 5;
    private static final long DEFAULT_LOCATION_ID = 1;

    /** 危险度中文名（0~4），越界按最高档处理。 */
    private static final String[] DANGER_NAMES = {"安全", "低危", "中危", "高危", "极危"};

    /**
     * 单次维护最多扫多少个分片文件。
     *
     * <p>维护（补货 / 回收）要遍历目录树，而野外可以无限大 —— 这个上限保证一次维护
     * 不会把进程卡在读几万个文件上；扫不到的分片只是这次没轮到，下次继续。</p>
     */
    private static final int MAX_MAINTENANCE_SCAN = 5000;

    private final Map<Long, MapLocation> locationCache = new LinkedHashMap<>();
    private final List<long[]> connections = new ArrayList<>();
    /** 连接耗时表：key = "from-to"，value = 通行秒数 */
    private final Map<String, Integer> travelTimes = new HashMap<>();
    /** 地界 → (探索事件 key → 权重倍率) */
    private final Map<Long, Map<String, Double>> eventBias = new HashMap<>();
    /** 野外分片的文件存储（一个分片一个 JSON）。 */
    private final WildernessChunkStore chunkStore = WildernessChunkStore.get();
    /**
     * 野外节点缓存：id → 已解析的 {@link MapLocation}（含邻居，供 {@code travel} 判邻接）。
     *
     * <p>上限刻意设得不大：野外的"真相"是生成器，缓存只是省一次重算，
     * 淘汰掉随时能再算出来，不存在丢数据的风险。</p>
     */
    private final Map<Long, MapLocation> wildernessCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, MapLocation> eldest) {
                    return size() > 1024;
                }
            };
    /** 野外邻居"轻量对象"缓存：只有自身信息、不带邻居，用来避免解析时无限递归。 */
    private final Map<Long, MapLocation> wildernessStubCache = new LinkedHashMap<>();
    private volatile boolean initialized = false;

    private final PlayerService playerService;

    public MapService() {
        this.playerService = ServiceRegistry.getPlayerService();
    }

    /**
     * 初始化地图数据 —— 首次访问时从数据库加载，新安装则插入默认数据
     */
    public synchronized void ensureInitialized() {
        if (initialized) return;
        loadFromDb();
        initialized = true;
    }

    /**
     * 热重载地图数据：清空缓存后重新从数据库读取。
     *
     * <p>后台通过通用表管理改了 map_locations / map_connections / map_location_event_bias
     * 之后调用，避免必须重启进程。重载语义与重启一致（同样会补齐缺失的出厂数据）。</p>
     */
    public synchronized void reload() {
        loadFromDb();
        initialized = true;
        LOG.info("地图数据已重载: " + locationCache.size() + " 个地点, " + connections.size() + " 条连接");
    }
    /**
     * 从数据库全量加载。
     *
     * <p>进入时先清空缓存，因此加载中途失败不会留下"半新半旧"的数据；
     * 由于 {@code initialized} 此时仍为 false，下一次 {@link #ensureInitialized()} 会从干净状态重试。</p>
     */
    private void loadFromDb() {
        locationCache.clear();
        connections.clear();
        travelTimes.clear();
        eventBias.clear();
        wildernessCache.clear();
        wildernessStubCache.clear();
        try (Connection conn = DatabaseManager.getConnection()) {
            repairDefaultData(conn);
            loadLocations(conn);
            loadConnections(conn);
            loadEventBias(conn);
            // 出入口连边不存在数据库里（野外整个不在库中），加载完已知世界后现挂上
            attachGatewayLinks();
        } catch (SQLException e) {
            throw new RuntimeException("加载地图数据失败", e);
        }
    }

    /**
     * 把已知世界的出境口与野外入口分片接上。
     *
     * <p>这条边两端的性质完全不同（一端在库、一端是算出来的），所以它无法用
     * {@code map_connections} 表示 —— 那条边一旦落库，就等于把野外拽回了数据库，
     * 而野外"无限扩张"正是靠不入库才成立的。改为两侧各自现算，见
     * {@link #resolveWilderness(long)}。</p>
     */
    private void attachGatewayLinks() {
        WildernessMapConfig config = WildernessMapConfig.get();
        if (!config.isEnabled()) {
            return;
        }
        List<Long> entries = config.getEntryLocationIds();
        for (int index = 0; index < entries.size(); index++) {
            Long entryId = entries.get(index);
            if (entryId == null) {
                continue;
            }
            MapLocation entry = locationCache.get(entryId);
            if (entry == null) {
                continue;
            }
            // 入口必须落在硬上限之内，否则玩家会在边境看到一个永远走不到的路口
            int[] chunk = WildernessTerrain.gatewayChunkFor(index);
            if (!WildernessTerrain.inBounds(chunk[0], chunk[1], config)) {
                LOG.warn("野外入口分片 (" + chunk[0] + "," + chunk[1] + ") 超出 maxChunksFromOrigin，"
                        + "已忽略入口地点 " + entryId);
                continue;
            }
            MapLocation gateway = wildernessStub(WildernessTerrain.gatewayNodeId(index));
            if (gateway != null && entry.getConnections().stream()
                    .noneMatch(c -> c.getId() == gateway.getId())) {
                entry.getConnections().add(gateway);
            }
        }
    }

    // ==================== 出厂数据补齐 ====================

    /**
     * 补齐出厂数据：新库全量插入；老库逐条补齐缺失地点、缺失连接、缺失的事件偏置，
     * 并为刚升级上来的库回填尚未播种的加成列（NULL = 未播种）。
     */
    private void repairDefaultData(Connection conn) throws SQLException {
        // 列集合一次性取好：老库若迁移未成功可能缺 danger_level 等新列，
        // 此时退化为"只补旧列"，而不是让整个地图加载失败。
        Set<String> columns = columnsOf(conn, "map_locations");

        boolean empty;
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM map_locations")) {
            rs.next();
            empty = rs.getInt(1) == 0;
        }

        if (empty) {
            insertAllDefaultLocations(conn, columns);
        } else {
            for (LocationSeed seed : MapDefaults.LOCATIONS) {
                repairLocation(conn, seed, columns);
            }
        }

        // 默认连接必须双向补齐，避免出现"只能去不能回"的单向路径
        for (long[] pair : MapDefaults.CONNECTIONS) {
            insertConnectionIfMissing(conn, pair[0], pair[1]);
            insertConnectionIfMissing(conn, pair[1], pair[0]);
        }
        // 修复历史数据中的单向连接：若存在 A→B 但缺少 B→A，补上反向
        repairReverseConnections(conn);

        for (EventBiasSeed bias : MapDefaults.EVENT_BIAS) {
            insertEventBiasIfMissing(conn, bias.locationId(), bias.eventKey(), bias.weightMultiplier());
        }
    }

    /**
     * 地点缺失则整行插入；已存在则只回填仍为 NULL 的加成列（不覆盖管理员改过的值）。
     *
     * <p>加成列若因迁移未成功而不存在，则退化为"只补地点、不碰加成"，避免整个地图加载失败。</p>
     */
    private void repairLocation(Connection conn, LocationSeed seed, Set<String> columns) throws SQLException {
        boolean dangerAvailable = columns.contains("danger_level");
        String checkSql = dangerAvailable
                ? "SELECT cultivation_bonus, exploration_bonus, danger_level FROM map_locations WHERE id = ?"
                : "SELECT cultivation_bonus, exploration_bonus FROM map_locations WHERE id = ?";
        boolean exists = false;
        boolean cultivationUnseeded = false;
        boolean explorationUnseeded = false;
        boolean dangerUnseeded = false;
        boolean bonusColumnsAvailable = true;

        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, seed.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                    rs.getDouble("cultivation_bonus");
                    cultivationUnseeded = rs.wasNull();
                    rs.getDouble("exploration_bonus");
                    explorationUnseeded = rs.wasNull();
                    if (dangerAvailable) {
                        rs.getInt("danger_level");
                        dangerUnseeded = rs.wasNull();
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("map_locations 加成列不可用（迁移可能未成功），本次只补齐地点本身: " + e.getMessage());
            bonusColumnsAvailable = false;
            exists = locationRowExists(conn, seed.id());
        }

        if (!exists) {
            insertLocation(conn, seed, columns);
            return;
        }

        if (bonusColumnsAvailable && (cultivationUnseeded || explorationUnseeded || dangerUnseeded)) {
            String sql = "UPDATE map_locations SET "
                    + "cultivation_bonus = COALESCE(cultivation_bonus, ?), "
                    + "exploration_bonus = COALESCE(exploration_bonus, ?)"
                    + (dangerAvailable ? ", danger_level = COALESCE(danger_level, ?)" : "")
                    + " WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int index = 1;
                ps.setDouble(index++, seed.cultivationBonus());
                ps.setDouble(index++, seed.explorationBonus());
                if (dangerAvailable) {
                    ps.setInt(index++, seed.dangerLevel());
                }
                ps.setLong(index, seed.id());
                ps.executeUpdate();
            }
        }
    }

    /** 读表的列名集合（小写）。用于在老库缺列时优雅退化，而不是靠逐个 try/catch 猜。 */
    private static Set<String> columnsOf(Connection conn, String table) {
        Set<String> columns = new HashSet<>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnLabel(i).toLowerCase());
            }
        } catch (SQLException e) {
            LOG.warn("读取 " + table + " 的列名失败（将按老库处理）: " + e.getMessage());
        }
        return columns;
    }

    private boolean locationRowExists(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private void insertLocation(Connection conn, LocationSeed seed, Set<String> columns) throws SQLException {
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

    private void insertAllDefaultLocations(Connection conn, Set<String> columns) throws SQLException {
        boolean withDanger = columns.contains("danger_level");
        String sql = "INSERT INTO map_locations "
                + "(id, name, description, region, min_realm, is_safe_zone, cultivation_bonus, exploration_bonus"
                + (withDanger ? ", danger_level" : "") + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?" + (withDanger ? ", ?" : "") + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (LocationSeed seed : MapDefaults.LOCATIONS) {
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

        String connSql = "INSERT INTO map_connections (from_location_id, to_location_id, travel_time_seconds) VALUES (?, ?, 5)";
        try (PreparedStatement ps = conn.prepareStatement(connSql)) {
            for (long[] pair : MapDefaults.CONNECTIONS) {
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

    private void repairReverseConnections(Connection conn) throws SQLException {
        String reverseSql = "SELECT from_location_id, to_location_id FROM map_connections";
        List<long[]> existing = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(reverseSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                existing.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
        }
        for (long[] edge : existing) {
            insertConnectionIfMissing(conn, edge[1], edge[0]);
        }
    }

    private void insertConnectionIfMissing(Connection conn, long from, long to) throws SQLException {
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

    private void insertEventBiasIfMissing(Connection conn, long locationId, String eventKey, double multiplier)
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

    // ==================== 加载 ====================

    private void loadLocations(Connection conn) throws SQLException {
        Set<String> columns = columnsOf(conn, "map_locations");
        boolean hasDanger = columns.contains("danger_level");
        boolean hasWilderness = columns.contains("is_wilderness");
        boolean hasExpires = columns.contains("expires_at");
        boolean hasEntry = columns.contains("entry_location_id");

        String sql = "SELECT * FROM map_locations ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rows = ps.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                MapLocation loc = new MapLocation(
                        id, rows.getString("name"), rows.getString("description"),
                        rows.getString("region"), rows.getInt("min_realm"),
                        rows.getBoolean("is_safe_zone"),
                        readBonus(rows, "cultivation_bonus"),
                        readBonus(rows, "exploration_bonus"),
                        hasDanger ? readInt(rows, "danger_level", 0) : 0);
                loc.setWilderness(hasWilderness && readInt(rows, "is_wilderness", 0) != 0);
                loc.setExpiresAt(hasExpires ? readLong(rows, "expires_at", 0L) : 0L);
                loc.setEntryLocationId(hasEntry ? readLong(rows, "entry_location_id", 0L) : 0L);

                // 不变式：安全区不可能是险地。矛盾数据只告警、不静默改写（管理员可在后台修正）；
                // 且 PVP 判定以 is_safe_zone 为准，所以危险度被误设也不会打开 PK 的口子。
                if (loc.isSafeZone() && loc.getDangerLevel() > 0) {
                    LOG.warn("地点「" + loc.getName() + "」(id=" + id + ") 同时被标为安全区与危险度 "
                            + loc.getDangerLevel() + "，数据矛盾：PVP 仍按安全区处理，请到管理台修正");
                }
                locationCache.put(id, loc);
            }
        }
    }

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

    private void loadConnections(Connection conn) throws SQLException {
        String sql = "SELECT * FROM map_connections";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rows = ps.executeQuery()) {
            while (rows.next()) {
                long from = rows.getLong("from_location_id");
                long to = rows.getLong("to_location_id");
                int travelTime = rows.getInt("travel_time_seconds");
                connections.add(new long[]{from, to});
                travelTimes.put(key(from, to), Math.max(1, travelTime));
                MapLocation a = locationCache.get(from);
                MapLocation b = locationCache.get(to);
                if (a != null && b != null) {
                    if (!a.getConnections().contains(b)) a.getConnections().add(b);
                    if (!b.getConnections().contains(a)) b.getConnections().add(a);
                }
            }
        }
    }

    private void loadEventBias(Connection conn) {
        String sql = "SELECT location_id, event_key, weight_multiplier FROM map_location_event_bias";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rows = ps.executeQuery()) {
            while (rows.next()) {
                long locationId = rows.getLong("location_id");
                String eventKey = rows.getString("event_key");
                double multiplier = rows.getDouble("weight_multiplier");
                if (eventKey == null || eventKey.isBlank() || multiplier <= 0) {
                    continue;
                }
                eventBias.computeIfAbsent(locationId, k -> new LinkedHashMap<>()).put(eventKey, multiplier);
            }
        } catch (SQLException e) {
            // 老库尚未建表时降级为"无偏置"，不影响地图主流程
            LOG.warn("加载地界事件偏置失败，已按无偏置处理: " + e.getMessage());
        }
    }

    private static String key(long a, long b) {
        return a + "-" + b;
    }

    /**
     * 两地之间的通行耗时（秒），无连接则返回 -1。
     *
     * <p>野外所有边的耗时都取自地图文件的 {@code travelTimeSeconds}，因此两端至少有一端是野外时
     * 不需要查连接表 —— 那张表里根本没有野外的行。混边（已知世界 ↔ 野外入口）同理。</p>
     */
    public int getTravelTimeSeconds(long fromId, long toId) {
        ensureInitialized();
        if (WildernessTerrain.isWildernessId(fromId) || WildernessTerrain.isWildernessId(toId)) {
            return WildernessMapConfig.get().getTravelTimeSeconds();
        }
        Integer t = travelTimes.get(key(fromId, toId));
        if (t != null) {
            return t;
        }
        Integer reverse = travelTimes.get(key(toId, fromId));
        return reverse != null ? reverse : -1;
    }

    // ==================== 查询 ====================

    /**
     * 取地点。野外 id 会<b>现算</b>出来（见 {@link #resolveWilderness(long)}），
     * 而不是从缓存查表 —— 野外在数据库里不存在，缓存里自然也没有。
     */
    public MapLocation getLocation(long id) {
        ensureInitialized();
        if (WildernessTerrain.isWildernessId(id)) {
            return wildernessCache.computeIfAbsent(id, this::resolveWilderness);
        }
        return locationCache.get(id);
    }

    /**
     * 该地点的全部邻居。
     *
     * <p>已知世界读连接表（已在加载时挂到 {@code connections} 上），
     * 野外由生成器现算。两种地点走同一个出口，调用方不必区分。</p>
     */
    public List<MapLocation> getNeighbors(long locationId) {
        MapLocation loc = getLocation(locationId);
        if (loc == null) {
            return List.of();
        }
        return loc.getConnections().stream().filter(Objects::nonNull).toList();
    }

    public MapLocation getLocationByName(String name) {
        ensureInitialized();
        return locationCache.values().stream()
                .filter(l -> l.getName().equals(name))
                .findFirst().orElse(null);
    }

    /**
     * 按"地名 or 地点 id"定位，两者都找不到才返回 null。
     *
     * <p>野外节点必须能按 id 指定：野外节点名是随机生成的、不在地点表里，
     * 而且世界无限大意味着"按名字反查"根本没有可查的集合 —— 只能靠 id。
     * {@code /地图 go <id>} 因此是野外唯一的导航方式，{@code /野外} 会把 id 打印出来。</p>
     */
    public MapLocation getLocationByToken(String token) {
        ensureInitialized();
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            try {
                MapLocation byId = getLocation(Long.parseLong(trimmed));
                if (byId != null) {
                    return byId;
                }
            } catch (NumberFormatException ignored) {
                // 超长数字串不是合法 id，继续按地名找
            }
        }
        return getLocationByName(trimmed);
    }

    /** 地点名称（未知则返回"未知之地"） */
    public String getLocationName(long id) {
        ensureInitialized();
        MapLocation loc = getLocation(id);
        return loc != null ? loc.getName() : "未知之地";
    }

    /** 该地点的闭关修炼加成倍率（默认 1.0） */
    public double getCultivationBonus(long locationId) {
        ensureInitialized();
        MapLocation loc = getLocation(locationId);
        return loc != null ? loc.getCultivationBonus() : 1.0;
    }

    /** 该地点的游历 / 秘境收益倍率（默认 1.0） */
    public double getExplorationBonus(long locationId) {
        ensureInitialized();
        MapLocation loc = getLocation(locationId);
        return loc != null ? loc.getExplorationBonus() : 1.0;
    }

    /**
     * 该地点的探索事件权重偏置（事件 key → 倍率）；无偏置时返回空表。
     * 用于让"落霞山产落霞草""万妖谷妖兽聚集"这类地点描述真正作用于事件抽取。
     */
    public Map<String, Double> getEventBias(long locationId) {
        ensureInitialized();
        Map<String, Double> bias = eventBias.get(locationId);
        return bias == null ? Map.of() : Collections.unmodifiableMap(bias);
    }

    /**
     * 全部<b>已知世界</b>地点。
     *
     * <p>野外不在其中：野外是一片无限扩张的分片世界，既不可能被"全部列出来"，
     * 也不该混进"修仙界地图"让玩家看到一张每次都不一样的地图。野外请用
     * {@link #getChunkNodes(int, int)} 按分片取。</p>
     */
    public List<MapLocation> getAllLocations() {
        ensureInitialized();
        List<MapLocation> result = new ArrayList<>();
        for (MapLocation loc : locationCache.values()) {
            if (!loc.isWilderness()) result.add(loc);
        }
        return result;
    }

    /** 按区域取<b>已知世界</b>地点（区域筛选与 /地图 list 的区域参数）。 */
    public List<MapLocation> getLocationsByRegion(String region) {
        ensureInitialized();
        return locationCache.values().stream()
                .filter(l -> !l.isWilderness())
                .filter(l -> l.getRegion().equals(region))
                .toList();
    }

    /** 境界 id 对应的中文名（如 7 → 大乘期）。用于地点门槛提示，避免各处自行拼接境界文案。 */
    public String getRealmName(int realm) {
        var config = GameConfigLoader.getRealmConfig(realm, 0);
        return config != null ? config.getFullName() : "凡人";
    }

    // ==================== 玩家位置 ====================

    /**
     * 玩家当前位置 id。
     * <p>列值为 NULL / 0 / 指向已删除地点的失效 id 时，一律回落到出生地并记一条 warn，
     * 避免"看得见地图却走不动路"（展示层与移动层曾对失效 id 采取不同策略）。</p>
     */
    public long getPlayerLocationId(long playerId) {
        ensureInitialized();
        String sql = "SELECT current_location_id FROM players WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long locId = rs.getLong("current_location_id");
                    boolean wasNull = rs.wasNull();
                    return resolveLocationId(locId, wasNull, playerId);
                }
            }
        } catch (SQLException e) { throw new RuntimeException("获取玩家位置失败", e); }
        return DEFAULT_LOCATION_ID;
    }

    /** 玩家位置的原始读数。CAS 期望值必须是库里的原值，而不是回落后的值。 */
    private record RawPosition(long rawLocationId, boolean locationNull, long lastTravelTime) {
    }

    private RawPosition readRawPosition(long playerId) {
        String sql = "SELECT current_location_id, last_travel_time FROM players WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long rawLoc = rs.getLong("current_location_id");
                    boolean locNull = rs.wasNull();
                    long lastTravel = rs.getLong("last_travel_time");
                    if (rs.wasNull()) lastTravel = 0;
                    return new RawPosition(rawLoc, locNull, lastTravel);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家位置失败", e);
        }
        return new RawPosition(DEFAULT_LOCATION_ID, true, 0);
    }

    /** 把库里的位置解析为有效地点 id；失效值回落到出生地并告警。 */
    private long resolveLocationId(long rawId, boolean wasNull, long playerId) {
        // 野外 id 在 map_locations 里没有行，因此这里必须问生成器而不是查表：
        // 否则站在野外的玩家每次读位置都会被判为"位置已失效"并扔回出生地。
        if (!wasNull && rawId > 0 && isLocationKnown(rawId)) {
            return rawId;
        }
        if (!wasNull && rawId > 0) {
            LOG.warn("玩家 " + playerId + " 的位置 " + rawId + " 已不存在，回落到出生地 " + DEFAULT_LOCATION_ID);
        }
        return DEFAULT_LOCATION_ID;
    }

    /**
     * 该 id 是否是一个真实存在的地点。
     *
     * <p>已知世界查地点表；野外交给生成器判定（坐标越界或槽位超出本片范围都算不存在）。</p>
     */
    public boolean isLocationKnown(long id) {
        if (WildernessTerrain.isWildernessId(id)) {
            WildernessMapConfig config = WildernessMapConfig.get();
            return WildernessTerrain.nodeAt(config.getWorldSeed(), id, config) != null;
        }
        return locationCache.containsKey(id);
    }

    public long getPlayerLastTravelTime(long playerId) {
        String sql = "SELECT last_travel_time FROM players WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long t = rs.getLong("last_travel_time");
                    return rs.wasNull() ? 0 : t;
                }
            }
        } catch (SQLException e) { throw new RuntimeException("获取玩家旅行时间失败", e); }
        return 0;
    }

    // ==================== 移动 ====================

    public Map<String, Object> travel(long playerId, long targetLocationId) {
        ensureInitialized();

        MapLocation target = getLocation(targetLocationId);
        if (target == null) {
            return createResult(false, I18n.t("map.h88947944"));
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return createResult(false, I18n.t("map.h796f4b70"));
        }

        if (!checkRealm(player, target)) {
            return createResult(false, "境界不足，" + target.getName() + "需要"
                    + getRealmName(target.getMinRealm()) + "以上方可进入（当前："
                    + getRealmName(player.getRealm()) + "）");
        }

        RawPosition position = readRawPosition(playerId);
        long currentLocId = resolveLocationId(position.rawLocationId(), position.locationNull(), playerId);
        long lastTravel = position.lastTravelTime();

        if (currentLocId == targetLocationId) {
            return createResult(false, I18n.t("map.he9a170b8", target.getName()));
        }

        MapLocation current = getLocation(currentLocId);
        if (current == null) {
            return createResult(false, I18n.t("map.h41614d08"));
        }

        if (!isConnected(current, targetLocationId)) {
            List<String> neighborNames = current.getConnections().stream().map(MapLocation::getName).toList();
            return createResult(false, "无法直接前往「" + target.getName() + "」，你只能从"
                    + current.getName() + "前往：" + String.join("、", neighborNames));
        }

        int travelSeconds = getTravelTimeSeconds(currentLocId, targetLocationId);
        long cooldownSeconds = effectiveTravelSeconds(travelSeconds);
        long now = System.currentTimeMillis() / 1000;
        if ((now - lastTravel) < cooldownSeconds) {
            long remaining = cooldownSeconds - (now - lastTravel);
            return createResult(false, I18n.t("map.h25e197d4", Math.max(0, remaining)));
        }

        int updated = compareAndSetTravel(playerId, position, targetLocationId, now);
        if (updated == 0) {
            // 并发竞争：校验期间位置或冷却已被另一个请求改写，本次移动作废
            if (getPlayerLocationId(playerId) == targetLocationId) {
                return createResult(false, I18n.t("map.he9a170b8", target.getName()));
            }
            return createResult(false, I18n.t("map.heb20a82a"));
        }

        Map<String, Object> result = createResult(true,
                player.getName() + "离开了" + current.getName() + "，前往「" + target.getName() + "」。" + target.getDescription());
        result.put("from", current.getName());
        result.put("to", target.getName());
        result.put("toRegion", target.getRegion());
        result.put("safeZone", target.isSafeZone());
        result.put("travelSeconds", effectiveTravelSeconds(travelSeconds));
        result.put("dangerLevel", target.getDangerLevel());
        result.put("dangerName", getDangerName(target.getDangerLevel()));
        result.put("wilderness", target.isWilderness());

        // 首次抵达的奖励与解锁提示附在移动结果里：玩家刚走到就该看到，不必再敲一次指令
        String firstVisit = recordFirstVisit(playerId, targetLocationId);
        if (firstVisit != null) {
            result.put("message", result.get("message") + "\n" + firstVisit);
            result.put("firstVisit", true);
        }
        return result;
    }

    /**
     * 单一口径的路程秒数：校验冷却、返回给客户端、展示提示都走这里。
     *
     * <p>此前校验用 {@code Math.max(5, ...)} 而展示直接用配置值，导致连接被配成 1~4 秒时
     * 玩家看到"路程约 3 秒"却必须等 5 秒 —— 说一套做一套。</p>
     */
    private long effectiveTravelSeconds(int configuredSeconds) {
        return Math.max(MIN_TRAVEL_SECONDS,
                configuredSeconds > 0 ? configuredSeconds : TRAVEL_COOLDOWN_SECONDS);
    }

    /**
     * 条件写回（CAS）：只有位置与冷却戳仍是校验时读到的旧值才落库，返回影响行数。
     *
     * <p>这是并发安全的唯一可靠做法：{@code SELECT ... FOR UPDATE} 在本项目的 SQLite 方言下
     * 被降级为空串（无锁），"先读后写"会让两个并发请求双双通过校验、各自移动一次并各自回复成功。</p>
     *
     * <p>期望位置用的是<b>库里的原值</b>而非回落后的有效值：位置失效（如地点被删）时两者不同，
     * 若拿回落值去比对会永远匹配不上，玩家就再也动不了 —— 正是本次要修掉的那个毛病。</p>
     */
    private int compareAndSetTravel(long playerId, RawPosition expected, long targetLocationId, long now) {
        String locationPredicate = expected.locationNull()
                ? "current_location_id IS NULL"
                : "current_location_id = ?";
        String sql = "UPDATE players SET current_location_id = ?, last_travel_time = ? "
                + "WHERE id = ? AND " + locationPredicate + " AND COALESCE(last_travel_time, 0) = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, targetLocationId);
            ps.setLong(2, now);
            ps.setLong(3, playerId);
            int index = 4;
            if (!expected.locationNull()) {
                ps.setLong(index++, expected.rawLocationId());
            }
            ps.setLong(index, expected.lastTravelTime());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("移动失败", e);
        }
    }

    private boolean checkRealm(Player player, MapLocation target) {
        return player.getRealm() >= target.getMinRealm();
    }

    private boolean isConnected(MapLocation current, long targetId) {
        return current.getConnections().stream().anyMatch(c -> c.getId() == targetId);
    }

    /**
     * 传送符效果：无视路径直接传送到指定地点（保留境界校验），并重置赶路冷却。
     */
    public Map<String, Object> teleportToLocation(long playerId, long targetLocationId) {
        ensureInitialized();

        MapLocation target = getLocation(targetLocationId);
        if (target == null) {
            return createResult(false, I18n.t("map.h88947944"));
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return createResult(false, I18n.t("map.h796f4b70"));
        }

        if (player.getRealm() < target.getMinRealm()) {
            return createResult(false, "境界不足，" + target.getName() + "需要"
                    + getRealmName(target.getMinRealm()) + "以上方可进入（当前："
                    + getRealmName(player.getRealm()) + "）");
        }

        executeTravel(playerId, targetLocationId);
        String firstVisit = recordFirstVisit(playerId, targetLocationId);
        Map<String, Object> result = createResult(true,
                I18n.t("map.h6967c9a8", target.getName()));
        if (firstVisit != null) {
            result.put("message", result.get("message") + "\n" + firstVisit);
            result.put("firstVisit", true);
        }
        return result;
    }

    private void executeTravel(long playerId, long targetLocationId) {
        String sql = "UPDATE players SET current_location_id = ?, last_travel_time = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, targetLocationId);
            pstmt.setLong(2, System.currentTimeMillis() / 1000);
            pstmt.setLong(3, playerId);
            pstmt.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("移动失败", e); }
    }

    /**
     * 查看当前位置及周围
     */
    public Map<String, Object> getPlayerSurroundings(long playerId) {
        ensureInitialized();

        long locId = getPlayerLocationId(playerId);
        MapLocation current = getLocation(locId);
        if (current == null) {
            current = getLocation(DEFAULT_LOCATION_ID);
            locId = DEFAULT_LOCATION_ID;
        }
        if (current == null) {
            return createResult(false, I18n.t("map.hcf096c23"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", formatLocationBrief(current));

        Player player = playerService.getPlayerById(playerId);
        List<Map<String, Object>> neighbors = new ArrayList<>();
        for (MapLocation nb : current.getConnections()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", nb.getId());
            info.put("name", nb.getName());
            info.put("region", nb.getRegion());
            info.put("safeZone", nb.isSafeZone());
            info.put("accessible", player != null && player.getRealm() >= nb.getMinRealm());
            int travelSeconds = getTravelTimeSeconds(locId, nb.getId());
            info.put("travelSeconds", effectiveTravelSeconds(travelSeconds));
            info.put("dangerLevel", nb.getDangerLevel());
            info.put("dangerName", getDangerName(nb.getDangerLevel()));
            info.put("wilderness", nb.isWilderness());
            neighbors.add(info);
        }
        result.put("neighbors", neighbors);
        result.put("cultivationBonus", current.getCultivationBonus());
        result.put("explorationBonus", current.getExplorationBonus());
        result.put("dangerLevel", current.getDangerLevel());
        result.put("dangerName", getDangerName(current.getDangerLevel()));
        result.put("wilderness", current.isWilderness());
        if (current.isWilderness()) {
            result.put("wildernessResource", getWildernessResource(locId));
            result.put("wildernessExpiresAt", current.getExpiresAt());
        }
        result.put("success", true);
        return result;
    }

    private Map<String, Object> formatLocationBrief(MapLocation loc) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", loc.getId());
        info.put("name", loc.getName());
        info.put("description", loc.getDescription());
        info.put("region", loc.getRegion());
        info.put("safeZone", loc.isSafeZone());
        return info;
    }

    private Map<String, Object> createResult(boolean success, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("message", message);
        return result;
    }

    /**
     * 批量获取玩家位置信息（用于玩家列表展示）
     */
    public Map<Long, String> getPlayerLocationNames(List<Long> playerIds) {
        Map<Long, String> result = new LinkedHashMap<>();
        ensureInitialized();
        if (playerIds.isEmpty()) return result;

        StringBuilder sql = new StringBuilder("SELECT id, current_location_id FROM players WHERE id IN (");
        for (int i = 0; i < playerIds.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < playerIds.size(); i++) {
                ps.setLong(i + 1, playerIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pid = rs.getLong("id");
                    long rawLoc = rs.getLong("current_location_id");
                    boolean wasNull = rs.wasNull();
                    MapLocation loc = locationCache.get(resolveLocationId(rawLoc, wasNull, pid));
                    result.put(pid, loc != null ? loc.getName() : "未知");
                }
            }
        } catch (SQLException e) { throw new RuntimeException("获取玩家位置失败", e); }
        return result;
    }

    // ==================== 危险度 ====================

    /** 该地点的危险度（0~4）。地点不存在按 0 处理。 */
    public int getDangerLevel(long locationId) {
        ensureInitialized();
        MapLocation loc = getLocation(locationId);
        return loc == null ? 0 : loc.getDangerLevel();
    }

    /** 危险度中文名（0~4）。越界按最高档，避免后台误填大数字时显示成 null。 */
    public String getDangerName(int dangerLevel) {
        if (dangerLevel <= 0) return DANGER_NAMES[0];
        if (dangerLevel >= DANGER_NAMES.length) return DANGER_NAMES[DANGER_NAMES.length - 1];
        return DANGER_NAMES[dangerLevel];
    }

    /** 该地点是否禁止 PVP（安全区）。以 is_safe_zone 为唯一依据，危险度不参与判定。 */
    public boolean isSafeZone(long locationId) {
        ensureInitialized();
        MapLocation loc = getLocation(locationId);
        return loc != null && loc.isSafeZone();
    }

    // ==================== 已知世界 / 野外 ====================

    /** 已知世界的固定地点（出厂 15 个）。 */
    public List<MapLocation> getKnownWorldLocations() {
        ensureInitialized();
        List<MapLocation> result = new ArrayList<>();
        for (MapLocation loc : locationCache.values()) {
            if (!loc.isWilderness()) result.add(loc);
        }
        return result;
    }

    /** 野外功能是否启用（地图文件里 enabled=false 即关闭）。 */
    public boolean isWildernessEnabled() {
        return WildernessMapConfig.get().isEnabled();
    }

    /**
     * 取一片分片里的全部节点。
     *
     * <p>这是野外唯一"列举"节点的入口：野外无限扩张，不存在"全部野外地点"这种列表，
     * 只能按分片取。越界分片返回空表（表现为"混沌未开"）。</p>
     */
    public List<MapLocation> getChunkNodes(int chunkX, int chunkY) {
        ensureInitialized();
        WildernessMapConfig config = WildernessMapConfig.get();
        if (!config.isEnabled() || !WildernessTerrain.inBounds(chunkX, chunkY, config)) {
            return List.of();
        }
        int size = config.getChunkSize();
        List<MapLocation> nodes = new ArrayList<>(size * size);
        for (int slot = 0; slot < size * size; slot++) {
            MapLocation loc = getLocation(WildernessTerrain.nodeId(chunkX, chunkY, slot));
            if (loc != null) {
                nodes.add(loc);
            }
        }
        return nodes;
    }

    /** 某地点所属分片的坐标；已知世界地点返回 null。 */
    public int[] getChunkOf(long locationId) {
        if (!WildernessTerrain.isWildernessId(locationId)) {
            return null;
        }
        return new int[]{WildernessTerrain.chunkXOfNode(locationId), WildernessTerrain.chunkYOfNode(locationId)};
    }

    /**
     * 取（并确保已加载）某地点所属分片的持久化状态。
     *
     * <p>分片文件是懒创建的：第一次走到这里才产生文件，从未被访问过的分片
     * 在磁盘上完全不存在 —— 这正是"无限世界不占存储"的落点。</p>
     */
    public WildernessChunk getChunk(int chunkX, int chunkY) {
        ensureInitialized();
        return loadChunk(chunkX, chunkY);
    }

    /** 该分片是否已被固化（有探索记录或为出入口分片，永不回收）。 */
    public boolean isChunkPinned(int chunkX, int chunkY) {
        return getChunk(chunkX, chunkY).pinned;
    }

    // ==================== 探索进度 ====================

    /**
     * 记录首次到达；返回首达奖励文案，重复到达返回 null。
     *
     * <p>两类地点的落盘方式不同，但对外是同一个入口：</p>
     * <ul>
     *   <li><b>已知世界</b> → {@code player_map_exploration} 表，用
     *       {@code INSERT OR IGNORE} + 唯一键判重，而不是"先查后插"：后者在两个请求
     *       同时首次到达同一地点时会双双判定为首次、各发一份奖励。</li>
     *   <li><b>野外</b> → 该分片的文件（{@code exploredBy}）。写文件是同步的，
     *       所以"走到过"这件事立刻落盘；同时它会把分片置为 {@code pinned}，
     *       从此这片野外<b>永远不会被回收</b>。</li>
     * </ul>
     */
    public String recordFirstVisit(long playerId, long locationId) {
        ensureInitialized();
        MapLocation loc = getLocation(locationId);
        if (loc == null) return null;

        long now = System.currentTimeMillis() / 1000;
        boolean firstVisit;
        if (WildernessTerrain.isWildernessId(locationId)) {
            firstVisit = recordWildernessVisit(playerId, locationId, now);
            if (firstVisit) {
                try (Connection conn = DatabaseManager.getConnection()) {
                    grantFirstVisitReward(conn, playerId, loc);
                } catch (SQLException e) {
                    LOG.error("发放野外首达奖励失败 player=" + playerId + " location=" + locationId, e);
                }
            }
        } else {
            try (Connection conn = DatabaseManager.getConnection()) {
                firstVisit = insertExplorationIfAbsent(conn, playerId, locationId, now);
                if (firstVisit) {
                    grantFirstVisitReward(conn, playerId, loc);
                }
            } catch (SQLException e) {
                LOG.error("记录探索进度失败 player=" + playerId + " location=" + locationId, e);
                return null;
            }
        }
        if (!firstVisit) return null;

        WildernessMapConfig.FirstVisitReward reward = WildernessMapConfig.get().getFirstVisitReward();
        long stones = firstVisitSpiritStones(reward, loc);
        long exp = firstVisitExperience(reward, loc);
        StringBuilder sb = new StringBuilder("🎉 首次抵达【").append(loc.getName()).append("】，获得 ");
        sb.append(stones).append(" 灵石、").append(exp).append(" 灵力");

        // 全图探索度达成时额外奖励（只对已知世界计算，野外是无限的，不该参与完成度）
        if (!loc.isWilderness()) {
            long explored = countExploredKnownWorld(playerId);
            long total = getKnownWorldLocations().size();
            if (total > 0 && explored == total) {
                long bonus = reward.getCompletionBonusSpiritStones();
                if (bonus > 0) {
                    try {
                        ServiceRegistry.getItemService().addSpiritStones(playerId, bonus);
                        sb.append("\n🏆 你已走遍已知世界的全部 ").append(total)
                                .append(" 个地点，额外获得 ").append(bonus).append(" 灵石！");
                    } catch (Exception e) {
                        LOG.error("发放全图探索奖励失败 player=" + playerId, e);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 野外首达：写进分片文件并置 pinned；返回是否新增。 */
    private boolean recordWildernessVisit(long playerId, long locationId, long now) {
        int cx = WildernessTerrain.chunkXOfNode(locationId);
        int cy = WildernessTerrain.chunkYOfNode(locationId);
        WildernessChunk chunk = loadChunk(cx, cy);
        boolean added = chunk.markExplored(WildernessTerrain.slotOfNode(locationId), playerId);
        chunk.lastActiveAt = now;
        if (added) {
            chunkStore.save(chunk);
        }
        return added;
    }

    /** 插入探索记录；返回 true 表示本次是首次（唯一键冲突即非首次）。 */
    private boolean insertExplorationIfAbsent(Connection conn, long playerId, long locationId, long now)
            throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "INSERT OR IGNORE INTO player_map_exploration (player_id, location_id, first_visited_at) VALUES (?, ?, ?)"
                : "INSERT IGNORE INTO player_map_exploration (player_id, location_id, first_visited_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, locationId);
            ps.setLong(3, now);
            return ps.executeUpdate() > 0;
        }
    }

    /** 首达灵石奖励：基础 + 危险度×每级，野外再乘野外倍率。 */
    private static long firstVisitSpiritStones(WildernessMapConfig.FirstVisitReward reward, MapLocation loc) {
        long base = reward.getSpiritStonesBase() + reward.getSpiritStonesPerDanger() * loc.getDangerLevel();
        double multiplier = loc.isWilderness() ? reward.getSpiritStonesWildernessMultiplier() : 1.0;
        return Math.max(0, Math.round(base * multiplier));
    }

    /** 首达灵力奖励：基础 + 危险度×每级，野外再乘野外倍率。 */
    private static long firstVisitExperience(WildernessMapConfig.FirstVisitReward reward, MapLocation loc) {
        long base = reward.getExperienceBase() + reward.getExperiencePerDanger() * loc.getDangerLevel();
        double multiplier = loc.isWilderness() ? reward.getExperienceWildernessMultiplier() : 1.0;
        return Math.max(0, Math.round(base * multiplier));
    }

    private void grantFirstVisitReward(Connection conn, long playerId, MapLocation loc) throws SQLException {
        WildernessMapConfig.FirstVisitReward reward = WildernessMapConfig.get().getFirstVisitReward();
        long stones = firstVisitSpiritStones(reward, loc);
        long exp = firstVisitExperience(reward, loc);
        if (stones > 0) {
            ServiceRegistry.getItemService().addSpiritStones(conn, playerId, stones);
        }
        if (exp > 0) {
            ServiceRegistry.getPlayerService().addExperience(conn, playerId, exp);
        }
    }

    /** 玩家是否到过该地点（传送点解锁与迷雾展示都以此为准）。 */
    public boolean isExplored(long playerId, long locationId) {
        ensureInitialized();
        if (WildernessTerrain.isWildernessId(locationId)) {
            // 野外不查库：探索记录就在分片文件里
            WildernessChunk chunk = loadChunk(WildernessTerrain.chunkXOfNode(locationId),
                    WildernessTerrain.chunkYOfNode(locationId));
            return chunk.isExploredBy(WildernessTerrain.slotOfNode(locationId), playerId);
        }
        String sql = "SELECT COUNT(*) FROM player_map_exploration WHERE player_id = ? AND location_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            LOG.error("查询探索记录失败 player=" + playerId, e);
            return false;
        }
    }

    /** 玩家已探索的地点 id 集合（一次查完，供列表页避免 N+1）。 */
    public Set<Long> getExploredLocationIds(long playerId) {
        ensureInitialized();
        Set<Long> result = new HashSet<>();
        String sql = "SELECT location_id FROM player_map_exploration WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            LOG.error("查询探索记录失败 player=" + playerId, e);
        }
        return result;
    }

    /** 已探索的已知世界地点数（野外流动，不计入完成度）。 */
    public long countExploredKnownWorld(long playerId) {
        Set<Long> explored = getExploredLocationIds(playerId);
        long count = 0;
        for (MapLocation loc : getKnownWorldLocations()) {
            if (explored.contains(loc.getId())) count++;
        }
        return count;
    }

    // ==================== 传送（到过即解锁，付费直传） ====================

    /**
     * 传送费用：基础 + 两地危险度之和×每级，野外节点另乘附加系数。
     * <p>以"目的地危险度 + 出发地危险度"计价，让"从安全区直传极危之地"比"从邻境直传"更贵。</p>
     */
    public long getTeleportCost(long fromLocationId, long targetLocationId) {
        WildernessMapConfig.Teleport cfg = WildernessMapConfig.get().getTeleport();
        MapLocation from = getLocation(fromLocationId);
        MapLocation to = getLocation(targetLocationId);
        int dangerSum = (from == null ? 0 : from.getDangerLevel()) + (to == null ? 0 : to.getDangerLevel());
        double cost = cfg.getBaseCost() + cfg.getCostPerDanger() * dangerSum;
        if (to != null && to.isWilderness()) {
            cost *= cfg.getWildernessSurcharge();
        }
        return Math.max(cfg.getMinCost(), Math.round(cost));
    }

    /**
     * 付费直传：无视路径，但仍校验境界门槛，且目的地必须已经到过（传送点已解锁）。
     *
     * <p>与传送符（{@link #teleportToLocation}）的区别：传送符是物品效果、可去没去过的地方；
     * 这里是玩法内的常规位移，必须"到过才能传"，避免付费直传把探索意义抹平。</p>
     */
    public Map<String, Object> teleportByUnlock(long playerId, long targetLocationId) {
        ensureInitialized();
        WildernessMapConfig.Teleport cfg = WildernessMapConfig.get().getTeleport();
        if (!cfg.isEnabled()) {
            return createResult(false, I18n.t("map.h574940a0"));
        }

        MapLocation target = getLocation(targetLocationId);
        if (target == null) {
            return createResult(false, I18n.t("map.h88947944"));
        }
        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return createResult(false, I18n.t("map.h796f4b70"));
        }
        if (!checkRealm(player, target)) {
            return createResult(false, "境界不足，" + target.getName() + "需要"
                    + getRealmName(target.getMinRealm()) + "以上方可进入（当前："
                    + getRealmName(player.getRealm()) + "）");
        }
        if (!isExplored(playerId, targetLocationId)) {
            return createResult(false, I18n.t("map.ha50dacf8", target.getName()));
        }

        RawPosition position = readRawPosition(playerId);
        long currentLocId = resolveLocationId(position.rawLocationId(), position.locationNull(), playerId);
        if (currentLocId == targetLocationId) {
            return createResult(false, I18n.t("map.he9a170b8", target.getName()));
        }

        long cost = getTeleportCost(currentLocId, targetLocationId);
        long balance = ServiceRegistry.getItemService().getSpiritStoneCount(playerId);
        if (balance < cost) {
            return createResult(false, I18n.t("map.hce4606f4", cost, balance));
        }

        // 先扣费再落库位置：扣费失败直接返回，避免"免费传送"
        if (!ServiceRegistry.getItemService().removeSpiritStones(playerId, cost)) {
            return createResult(false, I18n.t("map.he3affb16"));
        }

        long now = System.currentTimeMillis() / 1000;
        int updated = compareAndSetTravel(playerId, position, targetLocationId, now);
        if (updated == 0) {
            // 位置在扣费与写回之间被改动：把灵石退回去，不能既收钱又不办事
            ServiceRegistry.getItemService().addSpiritStones(playerId, cost);
            if (getPlayerLocationId(playerId) == targetLocationId) {
                return createResult(false, I18n.t("map.he9a170b8", target.getName()));
            }
            return createResult(false, I18n.t("map.he04326c4"));
        }

        Map<String, Object> result = createResult(true,
                I18n.t("map.h654a09b8", target.getName(), cost, target.getDescription()));
        result.put("to", target.getName());
        result.put("toRegion", target.getRegion());
        result.put("cost", cost);
        result.put("safeZone", target.isSafeZone());
        result.put("dangerLevel", target.getDangerLevel());
        result.put("dangerName", getDangerName(target.getDangerLevel()));
        return result;
    }

    // ==================== 野外（分片 + 文件存储） ====================

    /**
     * 取（并确保已加载）一片分片的持久化状态。
     *
     * <p>分片文件是<b>懒创建</b>的：第一次走到这里才产生文件，从未被访问过的分片在磁盘上
     * 完全不存在 —— 这正是"无限世界不占存储"的落点。资源在首次加载时抽一次，
     * 之后一直存在文件里、只随采集与补货变化。</p>
     */
    private WildernessChunk loadChunk(int chunkX, int chunkY) {
        WildernessMapConfig config = WildernessMapConfig.get();
        long now = System.currentTimeMillis() / 1000;
        long seedHash = WildernessTerrain.seedHash(config.getWorldSeed(), config.getChunkSize());
        boolean gateway = gatewayIndexOf(chunkX, chunkY, config) >= 0;
        WildernessChunk chunk = chunkStore.load(chunkX, chunkY, seedHash, config.getChunkSize(),
                gateway, now, config.getRestockIntervalMinutes() * 60L);
        if (!chunk.resourcesRolled) {
            rollChunkResources(chunk, chunkX, chunkY, config);
            chunk.resourcesRolled = true;
            chunkStore.save(chunk);
        }
        return chunk;
    }

    /**
     * 按资源池给整片分片的每个节点抽资源。
     *
     * <p>抽签结果<b>不入库</b>，直接写进分片文件：这正是"野外用文件存"最直接的体现 ——
     * 一份随机生成的资源分布，天然属于它所在的那片地，而不属于某张需要索引的表。</p>
     */
    private void rollChunkResources(WildernessChunk chunk, int chunkX, int chunkY,
                                    WildernessMapConfig config) {
        for (WildernessTerrain.Node node : WildernessTerrain.chunkAt(
                config.getWorldSeed(), chunkX, chunkY, config).nodes()) {
            List<WildernessMapConfig.ResourceSeed> pool = config.getResourcePool(node.biomeKey(), node.danger());
            if (pool == null || pool.isEmpty()) {
                continue;
            }
            // 多数节点 1 种资源，少数 2 种，让"富矿"成为惊喜而不是常态
            int kinds = pool.size() > 1 && ThreadLocalRandom.current().nextBoolean() ? 2 : 1;
            Set<String> picked = new LinkedHashSet<>();
            for (int i = 0; i < kinds; i++) {
                WildernessMapConfig.ResourceSeed seed =
                        pickWeighted(pool, WildernessMapConfig.ResourceSeed::getWeight);
                if (seed != null) {
                    picked.add(seed.getItemKey());
                }
            }
            for (String itemKey : picked) {
                WildernessMapConfig.ResourceSeed seed = findSeed(pool, itemKey);
                if (seed == null) {
                    continue;
                }
                int quantity = randomBetween(seed.getMinQuantity(), seed.getMaxQuantity());
                chunk.resourcesOf(node.slot()).put(itemKey, new WildernessChunk.Resource(quantity, quantity));
            }
        }
    }

    private static WildernessMapConfig.ResourceSeed findSeed(
            List<WildernessMapConfig.ResourceSeed> pool, String itemKey) {
        for (WildernessMapConfig.ResourceSeed seed : pool) {
            if (itemKey.equals(seed.getItemKey())) {
                return seed;
            }
        }
        return null;
    }

    /**
     * 把一个野外 id 解析成 {@link MapLocation}。
     *
     * <p>这是野外"不存在于数据库中"的关键：查不到任何一行，一切由生成器从坐标现算，
     * 包括邻居。因此玩家可以走到任意远的地方，而数据库的行数始终不变。</p>
     */
    private MapLocation resolveWilderness(long id) {
        WildernessMapConfig config = WildernessMapConfig.get();
        if (!config.isEnabled()) {
            return null;
        }
        WildernessTerrain.Node node = WildernessTerrain.nodeAt(config.getWorldSeed(), id, config);
        if (node == null) {
            return null;
        }
        int chunkX = WildernessTerrain.chunkXOfNode(id);
        int chunkY = WildernessTerrain.chunkYOfNode(id);
        MapLocation loc = buildWildernessLocation(id, node, config);
        loc.setExpiresAt(loadChunk(chunkX, chunkY).restockAt);

        for (long neighborId : WildernessTerrain.neighborIds(config.getWorldSeed(), id, config)) {
            MapLocation neighbor = wildernessStub(neighborId);
            if (neighbor != null) {
                loc.getConnections().add(neighbor);
            }
        }

        // 出入口分片的 0 号节点同时连着已知世界的出境口（这条边两端性质不同，无法落库）
        int entryIndex = gatewayIndexOf(chunkX, chunkY, config);
        if (entryIndex >= 0 && node.slot() == 0) {
            Long entryId = config.getEntryLocationIds().get(entryIndex);
            MapLocation entry = entryId == null ? null : locationCache.get(entryId);
            if (entry != null) {
                loc.getConnections().add(entry);
                loc.setEntryLocationId(entry.getId());
            }
        }
        return loc;
    }

    /**
     * 邻居用的"轻量地点"：只有自身信息、不带邻居。
     *
     * <p>刻意不带邻居 —— 否则解析一个节点就要求出它每个邻居的邻居，无限递归下去。
     * 玩家真正站在某节点上时，{@link #getLocation(long)} 会返回带邻居的完整版本。</p>
     */
    private MapLocation wildernessStub(long id) {
        if (!WildernessTerrain.isWildernessId(id)) {
            return locationCache.get(id);
        }
        MapLocation cached = wildernessStubCache.get(id);
        if (cached != null) {
            return cached;
        }
        WildernessMapConfig config = WildernessMapConfig.get();
        if (!config.isEnabled()) {
            return null;
        }
        WildernessTerrain.Node node = WildernessTerrain.nodeAt(config.getWorldSeed(), id, config);
        if (node == null) {
            return null;
        }
        MapLocation stub = buildWildernessLocation(id, node, config);
        if (wildernessStubCache.size() > 4096) {
            wildernessStubCache.clear();
        }
        wildernessStubCache.put(id, stub);
        return stub;
    }

    private static MapLocation buildWildernessLocation(long id, WildernessTerrain.Node node,
                                                       WildernessMapConfig config) {
        MapLocation loc = new MapLocation();
        loc.setId(id);
        loc.setName(node.name());
        loc.setDescription(node.description());
        // 区域直接用地形名：玩家看到的"地形"就是区域，管理台也按它筛，不必再存一份
        loc.setRegion(node.biomeName());
        loc.setMinRealm(node.minRealm());
        loc.setSafeZone(false);
        loc.setCultivationBonus(node.cultivationBonus());
        loc.setExplorationBonus(node.explorationBonus());
        loc.setDangerLevel(node.danger());
        loc.setWilderness(true);
        return loc;
    }

    /** 该分片是否为第 index 个出入口分片；不是则返回 -1。 */
    private static int gatewayIndexOf(int chunkX, int chunkY, WildernessMapConfig config) {
        List<Long> entries = config.getEntryLocationIds();
        for (int index = 0; index < entries.size(); index++) {
            int[] chunk = WildernessTerrain.gatewayChunkFor(index);
            if (chunk[0] == chunkX && chunk[1] == chunkY) {
                return index;
            }
        }
        return -1;
    }

    /** 该节点的剩余资源（物品 key → 余量）；空表表示此地无资源或已采空。 */
    public Map<String, Integer> getWildernessResource(long locationId) {
        ensureInitialized();
        if (!WildernessTerrain.isWildernessId(locationId)) {
            return Map.of();
        }
        return loadChunk(WildernessTerrain.chunkXOfNode(locationId),
                WildernessTerrain.chunkYOfNode(locationId))
                .remainingOf(WildernessTerrain.slotOfNode(locationId));
    }

    /**
     * 野外定期维护：给到点的分片<b>原地补货</b>，回收长期无人涉足的<b>未固化</b>分片。
     *
     * <p>这两件事都<b>不动地形</b>：补货只把剩余量补回上限，回收只是删掉一个没人来过的
     * 分片文件（重新走到时会由种子算出完全一样的地形）。这正是用户要的
     * "无限扩大，而不是已探索的被刷新掉"。</p>
     *
     * <p>由 {@code Main} 的清理调度器定期调用，异常一律吞掉：维护失败不该影响主流程。</p>
     */
    public synchronized void maintainWildernessIfNeeded() {
        try {
            ensureInitialized();
            WildernessMapConfig config = WildernessMapConfig.get();
            if (!config.isEnabled()) {
                return;
            }
            long now = System.currentTimeMillis() / 1000;
            restockDueChunks(now, config);
            evictIdleChunks(now, config);
        } catch (Throwable t) {
            LOG.warn("野外维护失败（不影响主流程）: " + t.getMessage());
        }
    }

    private void restockDueChunks(long now, WildernessMapConfig config) {
        int restocked = 0;
        for (int[] coords : chunkStore.listChunks(MAX_MAINTENANCE_SCAN)) {
            WildernessChunk chunk = loadChunk(coords[0], coords[1]);
            if (chunk.restockAt <= now) {
                if (chunk.restock(now, config.getRestockIntervalMinutes() * 60L)) {
                    chunkStore.save(chunk);
                    restocked++;
                } else {
                    // 没有任何消耗也要把补货时间推后，否则每次维护都会白扫一遍
                    chunk.restockAt = now + config.getRestockIntervalMinutes() * 60L;
                    chunkStore.save(chunk);
                }
            }
        }
        if (restocked > 0) {
            LOG.info("野外资源补货完成: " + restocked + " 个分片");
        }
    }

    private void evictIdleChunks(long now, WildernessMapConfig config) {
        long idleLimit = config.getEvictAfterMinutes() * 60L;
        int evicted = 0;
        try (Connection conn = DatabaseManager.getConnection()) {
            for (int[] coords : chunkStore.listChunks(MAX_MAINTENANCE_SCAN)) {
                if (!WildernessTerrain.inBounds(coords[0], coords[1], config)) {
                    // 硬上限调小之后，外面的分片直接成为历史
                    chunkStore.delete(coords[0], coords[1]);
                    evicted++;
                    continue;
                }
                WildernessChunk chunk = chunkStore.load(coords[0], coords[1],
                        WildernessTerrain.seedHash(config.getWorldSeed(), config.getChunkSize()),
                        config.getChunkSize(), false, now, config.getRestockIntervalMinutes() * 60L);
                if (chunk.pinned || chunk.gateway) {
                    continue;
                }
                if (now - chunk.lastActiveAt < idleLimit) {
                    continue;
                }
                if (hasPlayerInChunk(conn, coords[0], coords[1])) {
                    continue;
                }
                if (chunkStore.delete(coords[0], coords[1])) {
                    evicted++;
                }
            }
        } catch (SQLException e) {
            LOG.warn("回收野外分片时读取在线玩家失败: " + e.getMessage());
        }
        if (evicted > 0) {
            LOG.info("回收无人涉足的野外分片: " + evicted + " 个");
        }
    }

    /**
     * 该分片范围内是否还有玩家。
     *
     * <p>靠"同一分片的 id 是连续区间"这一性质，用一个范围查询就能问出来，
     * 不必把玩家逐个取回来比对。这也是当初把 id 设计成
     * {@code ID_BASE + chunkKey * SLOT_STRIDE + slot} 的回报之一。</p>
     */
    private static boolean hasPlayerInChunk(Connection conn, int chunkX, int chunkY) {
        long first = WildernessTerrain.nodeId(chunkX, chunkY, 0);
        long last = first + WildernessTerrain.SLOT_STRIDE;
        String sql = "SELECT COUNT(*) FROM players WHERE current_location_id >= ? AND current_location_id < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, first);
            ps.setLong(2, last);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            // 查不出来就当有玩家在：宁可少回收一片，也不能把人脚下的地删了
            return true;
        }
    }

    /**
     * 立即给全部已生成分片补货（管理台用）。
     *
     * <p>只补货、不换地形、不删任何东西：已探索的内容永远保留，
     * 这里补的只是"被采空的矿重新长出来"。</p>
     */
    public synchronized int restockWilderness() {
        ensureInitialized();
        WildernessMapConfig config = WildernessMapConfig.get();
        long now = System.currentTimeMillis() / 1000;
        int count = 0;
        for (int[] coords : chunkStore.listChunks(MAX_MAINTENANCE_SCAN)) {
            WildernessChunk chunk = loadChunk(coords[0], coords[1]);
            chunk.restock(now, config.getRestockIntervalMinutes() * 60L);
            chunkStore.save(chunk);
            count++;
        }
        return count;
    }

    /** 野外总览（管理台用）：分片数、已固化数、资源条目数等。 */
    public Map<String, Object> getWildernessStats() {
        ensureInitialized();
        Map<String, Object> stats = new LinkedHashMap<>(chunkStore.stats());
        WildernessMapConfig config = WildernessMapConfig.get();
        stats.put("enabled", config.isEnabled());
        stats.put("chunkSize", config.getChunkSize());
        stats.put("maxChunksFromOrigin", config.getMaxChunksFromOrigin());
        stats.put("worldSeed", config.getWorldSeed());

        int chunks = 0;
        int pinned = 0;
        int exploredNodes = 0;
        int resourceEntries = 0;
        for (int[] coords : chunkStore.listChunks(MAX_MAINTENANCE_SCAN)) {
            chunks++;
            WildernessChunk chunk = loadChunk(coords[0], coords[1]);
            if (chunk.pinned) {
                pinned++;
            }
            exploredNodes += chunk.exploredNodeCount();
            if (chunk.resources != null) {
                for (Map<String, WildernessChunk.Resource> slot : chunk.resources.values()) {
                    resourceEntries += slot.size();
                }
            }
        }
        stats.put("chunkCount", chunks);
        stats.put("pinnedChunks", pinned);
        stats.put("exploredNodes", exploredNodes);
        stats.put("resourceEntries", resourceEntries);
        stats.put("scanLimit", MAX_MAINTENANCE_SCAN);
        return stats;
    }

    // ==================== 采集 ====================

    /**
     * 在当前野外地点采集资源。
     *
     * <p>并发安全靠分片对象自身的 {@code synchronized}（见
     * {@link WildernessChunk#consume(int, String, int)}）：同一分片全进程只有一份实例，
     * 所以"读余量 → 扣减"在分片粒度上互斥，两个玩家同时点采集不会把同一份资源发两次。</p>
     *
     * <p>顺序是<b>先扣库存并落盘、再发物品、发失败就加回去</b>，
     * 不允许出现"库存扣了、东西没到"。</p>
     */
    public Map<String, Object> gather(long playerId) {
        ensureInitialized();
        WildernessMapConfig config = WildernessMapConfig.get();
        if (!config.isEnabled()) {
            return createResult(false, I18n.t("map.hb37f0c87"));
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return createResult(false, I18n.t("map.h796f4b70"));
        }
        long locationId = getPlayerLocationId(playerId);
        if (!WildernessTerrain.isWildernessId(locationId)) {
            return createResult(false, I18n.t("map.h7f1cf394"));
        }
        MapLocation loc = getLocation(locationId);
        if (loc == null) {
            return createResult(false, I18n.t("map.h41614d08"));
        }

        long now = System.currentTimeMillis() / 1000;
        long cooldown = config.getGather().getCooldownSeconds();
        long lastGather = readLastGatherTime(playerId);
        if (cooldown > 0 && (now - lastGather) < cooldown) {
            long remaining = cooldown - (now - lastGather);
            return createResult(false, I18n.t("map.hdf5bd533", Math.max(0, remaining)));
        }

        int chunkX = WildernessTerrain.chunkXOfNode(locationId);
        int chunkY = WildernessTerrain.chunkYOfNode(locationId);
        int slot = WildernessTerrain.slotOfNode(locationId);
        WildernessChunk chunk = loadChunk(chunkX, chunkY);

        Map<String, Integer> available = chunk.remainingOf(slot);
        if (available.isEmpty()) {
            return createResult(false, I18n.t("map.h775899f0"));
        }
        String itemKey = available.keySet().iterator().next();
        int take = Math.min(config.getGather().getMaxPerGather(), available.get(itemKey));
        if (take < 1) {
            return createResult(false, I18n.t("map.h775899f0"));
        }

        int taken = chunk.consume(slot, itemKey, take);
        if (taken <= 0) {
            return createResult(false, I18n.t("map.h0ce2a85a"));
        }
        chunk.lastActiveAt = now;
        chunkStore.save(chunk);

        try (Connection conn = DatabaseManager.getConnection()) {
            boolean granted = ServiceRegistry.getItemService().addItem(conn, playerId, itemKey, taken);
            if (!granted) {
                chunk.refund(slot, itemKey, taken);
                chunkStore.save(chunk);
                return createResult(false, I18n.t("map.h9a627ec0"));
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET last_gather_time = ? WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setLong(2, playerId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            chunk.refund(slot, itemKey, taken);
            chunkStore.save(chunk);
            LOG.error("采集失败 player=" + playerId, e);
            return createResult(false, I18n.t("map.h6b113628", e.getMessage()));
        }

        int left = chunk.remainingOf(slot).getOrDefault(itemKey, 0);
        String itemName = resolveItemName(itemKey);
        Map<String, Object> result = createResult(true,
                I18n.t("map.h444e9d6e", loc.getName(), itemName, taken, left));
        result.put("itemKey", itemKey);
        result.put("itemName", itemName);
        result.put("quantity", taken);
        result.put("remaining", left);
        return result;
    }

    private long readLastGatherTime(long playerId) {
        String sql = "SELECT last_gather_time FROM players WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long value = rs.getLong(1);
                    return rs.wasNull() ? 0 : value;
                }
            }
        } catch (SQLException e) {
            LOG.warn("读取采集冷却失败（老库可能尚无该列）: " + e.getMessage());
        }
        return 0;
    }
    // ==================== 小工具 ====================

    /**
     * 物品 key → 显示名。取不到时退回 key 本身：
     * 玩家看到 "spirit_grass" 也好过看到 "null"，而且这通常意味着物品被删了，key 反而更有诊断价值。
     */
    private static String resolveItemName(String itemKey) {
        try {
            var item = com.mtxgdn.game.item.ItemRegistry.resolve(itemKey);
            return item != null ? item.getName() : itemKey;
        } catch (Exception e) {
            return itemKey;
        }
    }

    private static int randomBetween(int min, int max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /** 按权重随机取一个元素；权重全部非法时退回均匀随机。 */
    private static <T> T pickWeighted(List<T> items, java.util.function.ToIntFunction<T> weightOf) {
        if (items.isEmpty()) return null;
        int total = 0;
        for (T item : items) {
            total += Math.max(1, weightOf.applyAsInt(item));
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (T item : items) {
            roll -= Math.max(1, weightOf.applyAsInt(item));
            if (roll < 0) return item;
        }
        return items.get(items.size() - 1);
    }
}
