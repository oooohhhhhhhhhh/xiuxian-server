package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import com.mtxgdn.db.MapDAO.EventBiasRow;
import com.mtxgdn.db.MapDAO.PlayerPositionRow;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.config.MapDefaults;
import com.mtxgdn.game.config.WildernessMapConfig;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.map.WildernessChunkStore;
import com.mtxgdn.game.map.WildernessTerrain;
import com.mtxgdn.game.service.MapService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护「{@code MapService} 里的裸 JDBC 抽到 {@link MapDAO}」这次纯结构重构。
 *
 * <p><b>缺陷形态</b>：{@code MapService} 一个类里 1916 行、14 处
 * {@code DatabaseManager.getConnection()}，地图加载（含"老库缺列就退化"的列探测）、
 * 出厂数据补齐、缓存重填、探索记录、赶路 CAS、野外分片回收的 SQL 与业务逻辑交织在一起。
 * 混在一起的代价不是"文件长了"，而是：</p>
 * <ol>
 *   <li>改一条 SQL 与改一条地图语义（危险度、安全区、境界门槛、坐标换算）
 *       会出现在同一份 diff 里，review 时无法区分"只是搬了语句"与"改了玩法"；</li>
 *   <li>"哪些语句在事务里"看不出来：{@code loadFromDb} 全程共用一条连接、
 *       {@code recordFirstVisit} 的写库与发奖也必须共用一条连接，
 *       而其它读路径都是随手开的短连接 —— 全凭人眼判断；</li>
 *   <li>SQLite 上最致命的一条：事务里若用<b>另一个连接</b>读写同一张表，
 *       会抛 {@code SQLITE_BUSY_SNAPSHOT}（不受 {@code busy_timeout} 约束）
 *       或读到旧快照。所以 DAO 的每个方法都必须有"收调用方连接"的重载。</li>
 * </ol>
 *
 * <p>本测试钉住六类不变量：</p>
 * <ol>
 *   <li><b>公开 API 逐字不变</b>：{@code javap -public} 黄金清单（重构前后导出一致，
 *       调用方一行都不用改）；</li>
 *   <li><b>服务层不再有 JDBC</b>：{@code MapService} 源码里
 *       {@code PreparedStatement} / {@code ResultSet} / {@code setAutoCommit(} /
 *       {@code setAutoCommit(} / {@code rollback(} / SQL 关键字一处不剩；
 *       {@code DatabaseManager.getConnection()} 只允许出现在白名单里那 4 个
 *       "多条语句必须共用同一条连接"的方法中；</li>
 *   <li><b>SQL 守恒</b>：原 {@code MapService} 里的每一条 SQL 都还在
 *       {@link MapDAO} 里（去空白后比对，只允许"搬走"，不允许"改掉/丢掉"），
 *       并且 DAO 里不许出现清单之外的、看着像 SQL 的新字面量；</li>
 *   <li><b>连接与事务</b>：每个 DAO 方法都有 {@code Connection} 首参重载，
 *       而<b>收连接的方法绝不自己开连接、也不自己开事务</b>；
 *       任何事务 lambda 体内也不许再开连接；</li>
 *   <li><b>地图语义仍在服务层</b>：DAO 不认识 {@code MapLocation} / 野外 / 坐标；</li>
 *   <li><b>真跑 SQLite</b>：地图能加载出来、事件偏置增删改后能读回、野外资源查询正常、
 *       缓存重载后数据一致、事务内用 {@code Connection} 重载不会抛
 *       {@code SQLITE_BUSY_SNAPSHOT}。</li>
 * </ol>
 *
 * <p>源码级断言一律先剥掉注释（见 {@link #stripComments}）：把关键字写在注释里
 * 不算数，也不允许注释文本冒充断言锚点。</p>
 */
class MapDaoExtractionTest extends TestBase {

    private static final Path MAP_SERVICE =
            Paths.get("src/main/java/com/mtxgdn/game/service/MapService.java");
    private static final Path MAP_DAO =
            Paths.get("src/main/java/com/mtxgdn/db/MapDAO.java");

    private static final MapDAO DAO = new MapDAO();

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 与 {@code MapDefaults} 的出厂数据一致的地点 id。 */
    private static final long QINGYUN_VILLAGE = 1L;
    private static final long CANGYUE_TOWN = 3L;
    private static final long WANYAO_VALLEY = 12L;
    private static final long WENDAO_PEAK = 10L;

    /** 本类造的事件偏置 key 前缀：{@code @AfterEach} 按它清理，绝不碰出厂偏置。 */
    private static final String PROBE_PREFIX = "daoprobe_";

    private MapService mapService;

    /** 野外分片目录拨到临时目录：本类不该读写工作目录下的真实分片文件。 */
    @TempDir
    Path wildernessDir;

    /** 匹配"方法声明"（行首访问修饰符 + 名字 + 左括号），用于按名字取方法体。 */
    private static final java.util.regex.Pattern METHOD_DECL =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*(?:public|private|protected)(?:[^;{}\\n(]*?)\\b(\\w+)\\s*\\(");

    /** 匹配"第一个参数是 Connection 的方法声明"，形如 {@code public void addX(Connection conn, ...)}。 */
    private static final java.util.regex.Pattern CONNECTION_METHOD_DECL =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*(?:public|private|protected)[^;{}\\n]*\\(Connection conn");

    /** 看着像一整条 SQL 的字符串字面量（用于"DAO 不许自己发明语句"的反向断言）。 */
    private static final java.util.regex.Pattern SQL_LOOKING_LITERAL =
            java.util.regex.Pattern.compile("(?s)^(SELECT|INSERT|UPDATE|DELETE|CREATE|INTO)\\b.*");

    /**
     * {@code javap -public -classpath target/classes com.mtxgdn.game.service.MapService}
     * 在<b>重构前</b>导出的公开成员清单（逐字抄录，去掉行尾分号与 {@code synchronized}
     * 这类不影响签名的修饰符，与 {@link #describePublicApi(Class)} 的输出格式一致）。
     *
     * <p>重构后同一命令的输出与本清单<b>逐字相同</b>（{@code Compare-Object} 差异为 0）——
     * 也就是说 REST / WebSocket / QQ 指令 / 插件 / 其它服务里的调用方一行都不用改。</p>
     */
    private static final List<String> GOLDEN_SERVICE_API = List.of(
            "public com.mtxgdn.game.service.MapService()",
            "public void ensureInitialized()",
            "public void reload()",
            "public int getTravelTimeSeconds(long, long)",
            "public com.mtxgdn.game.entity.MapLocation getLocation(long)",
            "public java.util.List<com.mtxgdn.game.entity.MapLocation> getNeighbors(long)",
            "public com.mtxgdn.game.entity.MapLocation getLocationByName(java.lang.String)",
            "public com.mtxgdn.game.entity.MapLocation getLocationByToken(java.lang.String)",
            "public java.lang.String getLocationName(long)",
            "public double getCultivationBonus(long)",
            "public double getExplorationBonus(long)",
            "public java.util.Map<java.lang.String, java.lang.Double> getEventBias(long)",
            "public java.util.List<com.mtxgdn.game.entity.MapLocation> getAllLocations()",
            "public java.util.List<com.mtxgdn.game.entity.MapLocation> getLocationsByRegion(java.lang.String)",
            "public java.lang.String getRealmName(int)",
            "public long getPlayerLocationId(long)",
            "public boolean isLocationKnown(long)",
            "public long getPlayerLastTravelTime(long)",
            "public java.util.Map<java.lang.String, java.lang.Object> travel(long, long)",
            "public java.util.Map<java.lang.String, java.lang.Object> teleportToLocation(long, long)",
            "public java.util.Map<java.lang.String, java.lang.Object> getPlayerSurroundings(long)",
            "public java.util.Map<java.lang.Long, java.lang.String> getPlayerLocationNames(java.util.List<java.lang.Long>)",
            "public int getDangerLevel(long)",
            "public java.lang.String getDangerName(int)",
            "public boolean isSafeZone(long)",
            "public java.util.List<com.mtxgdn.game.entity.MapLocation> getKnownWorldLocations()",
            "public boolean isWildernessEnabled()",
            "public java.util.List<com.mtxgdn.game.entity.MapLocation> getChunkNodes(int, int)",
            "public int[] getChunkOf(long)",
            "public com.mtxgdn.game.map.WildernessChunk getChunk(int, int)",
            "public boolean isChunkPinned(int, int)",
            "public java.lang.String recordFirstVisit(long, long)",
            "public boolean isExplored(long, long)",
            "public java.util.Set<java.lang.Long> getExploredLocationIds(long)",
            "public long countExploredKnownWorld(long)",
            "public long getTeleportCost(long, long)",
            "public java.util.Map<java.lang.String, java.lang.Object> teleportByUnlock(long, long)",
            "public java.util.Map<java.lang.String, java.lang.Integer> getWildernessResource(long)",
            "public void maintainWildernessIfNeeded()",
            "public int restockWilderness()",
            "public java.util.Map<java.lang.String, java.lang.Object> getWildernessStats()",
            "public java.util.Map<java.lang.String, java.lang.Object> gather(long)");

    /**
     * 服务层自己开连接的允许位置 —— <b>现在是空的</b>。
     *
     * <p>原本这里登记了 4 个方法共 5 处（{@code loadFromDb} / {@code recordFirstVisit} ×2 /
     * {@code evictIdleChunks} / {@code gather}），理由是"多条语句必须落在同一条连接上，
     * 而其中一部分语句根本不属于地图（发物品走 {@code ItemService} 的 Connection 重载）"。</p>
     *
     * <p>这 5 处后来全部消掉了，各自换了不同的写法：</p>
     * <ul>
     *   <li>{@code loadFromDb} → {@link MapDAO#inLoadConnection}：连接由 DAO 取与归还，
     *       服务层只提供要执行的内容。刻意<b>不用</b> {@code runTransaction} ——
     *       加载只往缓存里「加」，而事务在 {@code SQLITE_BUSY} 上会重试，
     *       重试会把 {@code connections} 加重复。</li>
     *   <li>{@code recordFirstVisit} ×2 与 {@code gather} → {@code runTransaction}：
     *       这三处本来就是「多步写入必须原子」，用事务比裸连接更正确，
     *       失败时整体回滚而不是留下半截。</li>
     *   <li>{@code evictIdleChunks} → {@link MapDAO#hasPlayerInLocationRange} 的自取连接重载：
     *       那个连接只服务于「分片内是否还有玩家」一个判断，不需要跨语句复用。</li>
     * </ul>
     *
     * <p>这张表留着是为了<b>将来真有例外时</b>有地方登记理由；现在必须是空的。
     * 往这里加一行之前先问：能不能像上面五处一样换写法。</p>
     */
    private static final Map<String, String> SERVICE_GET_CONNECTION_WHITELIST = new LinkedHashMap<>();

    /** 服务层 {@code DatabaseManager.getConnection()} 的允许出现次数（与白名单逐条对应）。 */
    private static final int EXPECTED_SERVICE_GET_CONNECTION_COUNT = 0;

    /**
     * 原 {@code MapService.java} 里的全部 SQL（逐条抄录，去空白后比对）。
     *
     * <p>这批字符串是"重构前的真身"：只要有一条在 {@link MapDAO} 里找不到，
     * 就说明搬运时改了字面量（表名/列名/条件/ORDER BY/COALESCE/参数顺序），
     * 而那类改动不会让任何业务测试变红 —— 它只会在生产上悄悄换个结果集，
     * 或者让某个地点的加成在热重载后消失。</p>
     *
     * <p>注意三类"拼出来的语句"，它们在原文件里本来就没有单一字面量，
     * 因此按片段逐条列（硬要比对整句等于自欺）：</p>
     * <ul>
     *   <li>{@code SELECT * FROM " + table + " WHERE 1 = 0}（列探测）：表名现已是字面量；</li>
     *   <li>按列集合拼的 {@code INSERT INTO map_locations ...} 与
     *       {@code UPDATE map_locations SET ... COALESCE ...}；</li>
     *   <li>{@code IN (?,?,...)} 与 CAS 的谓词分支。</li>
     * </ul>
     */
    private static final List<String> GOLDEN_SQL = List.of(
            // ---- 列探测 / 空表判断 ----
            "SELECT * FROM map_locations WHERE 1 = 0",
            "SELECT COUNT(*) FROM map_locations",
            "SELECT COUNT(*) FROM map_locations WHERE id = ?",
            // ---- 加成列播种状态 ----
            "SELECT cultivation_bonus, exploration_bonus, danger_level FROM map_locations WHERE id = ?",
            "SELECT cultivation_bonus, exploration_bonus FROM map_locations WHERE id = ?",
            "UPDATE map_locations SET ",
            "cultivation_bonus = COALESCE(cultivation_bonus, ?), ",
            "exploration_bonus = COALESCE(exploration_bonus, ?)",
            ", danger_level = COALESCE(danger_level, ?)",
            " WHERE id = ?",
            // ---- 补齐单个地点（按列集合拼 INSERT） ----
            "(id, name, description, region, min_realm, is_safe_zone",
            "VALUES (?, ?, ?, ?, ?, ?",
            ", cultivation_bonus, exploration_bonus",
            ", ?, ?",
            ", danger_level",
            ", ?",
            "INSERT INTO map_locations ",
            // ---- 新库全量插入 ----
            "(id, name, description, region, min_realm, is_safe_zone, cultivation_bonus, exploration_bonus",
            ") ",
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?",
            // ---- 连接表 ----
            "INSERT INTO map_connections (from_location_id, to_location_id, travel_time_seconds) VALUES (?, ?, 5)",
            "SELECT from_location_id, to_location_id FROM map_connections",
            "SELECT COUNT(*) FROM map_connections WHERE from_location_id = ? AND to_location_id = ?",
            "INSERT INTO map_connections (from_location_id, to_location_id, travel_time_seconds) VALUES (?, ?, ?)",
            "SELECT * FROM map_connections",
            // ---- 事件偏置 ----
            "SELECT COUNT(*) FROM map_location_event_bias WHERE location_id = ? AND event_key = ?",
            "INSERT INTO map_location_event_bias (location_id, event_key, weight_multiplier) VALUES (?, ?, ?)",
            "SELECT location_id, event_key, weight_multiplier FROM map_location_event_bias",
            // 下面两条来自 AdminMapEventBiasResource（管理台的事件偏置 CRUD），
            // 原本写在 rest 包里。MapDAO 从"只服务 MapService"变成也服务 Admin 资源之后，
            // 这两条必须登记进来，否则 daoInventsNoNewSql 会把它们误判成自创语句。
            // 语句本身逐字未改，只是换了落脚点。
            "DELETE FROM map_location_event_bias WHERE location_id = ? AND event_key = ?",
            // ---- 加载地点 ----
            "SELECT * FROM map_locations ORDER BY id",
            // ---- 玩家位置 ----
            "SELECT current_location_id FROM players WHERE id = ?",
            "SELECT current_location_id, last_travel_time FROM players WHERE id = ?",
            "SELECT last_travel_time FROM players WHERE id = ?",
            "SELECT id, current_location_id FROM players WHERE id IN (",
            "UPDATE players SET current_location_id = ?, last_travel_time = ? ",
            "WHERE id = ? AND ",
            " AND COALESCE(last_travel_time, 0) = ?",
            "current_location_id IS NULL",
            "current_location_id = ?",
            "UPDATE players SET current_location_id = ?, last_travel_time = ? WHERE id = ?",
            // ---- 探索记录 ----
            "INSERT OR IGNORE INTO player_map_exploration (player_id, location_id, first_visited_at) VALUES (?, ?, ?)",
            "INSERT IGNORE INTO player_map_exploration (player_id, location_id, first_visited_at) VALUES (?, ?, ?)",
            "SELECT COUNT(*) FROM player_map_exploration WHERE player_id = ? AND location_id = ?",
            "SELECT location_id FROM player_map_exploration WHERE player_id = ?",
            // ---- 采集冷却 ----
            "SELECT last_gather_time FROM players WHERE id = ?",
            "UPDATE players SET last_gather_time = ? WHERE id = ?",
            // ---- 野外分片：分片内是否有玩家 ----
            "SELECT COUNT(*) FROM players WHERE current_location_id >= ? AND current_location_id < ?");

    // ==================== 夹具 ====================

    @BeforeEach
    void setUp() {
        WildernessChunkStore.get().setRootForTest(wildernessDir);
        restoreSeededLocations();
        mapService = ServiceRegistry.getMapService();
        mapService.reload();
    }

    @AfterEach
    void cleanUp() {
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM map_location_event_bias WHERE event_key LIKE ?")) {
                ps.setString(1, PROBE_PREFIX + "%");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // 清理失败不该掩盖真正的断言失败，但也必须留下痕迹
            System.err.println("[MapDaoExtractionTest] 清理探针偏置失败: " + e.getMessage());
        }
        WildernessChunkStore.get().setRootForTest(null);
        try {
            mapService.reload();
        } catch (Exception ignored) {
            // 收尾阶段的重载失败无所谓：下一个测试类会自己 reload
        }
    }

    /**
     * 把本类断言到的出厂值写回库。
     *
     * <p>测试库是全套件共用的（{@code TestBase} 只在每个类的 {@code @BeforeAll} 删库文件），
     * 别的用例改过 {@code map_locations} 之后本类的断言会凭空失败 —— 那反映的是用例顺序，
     * 不是被测代码有问题。</p>
     */
    private void restoreSeededLocations() {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE map_locations SET name = ?, cultivation_bonus = ?, exploration_bonus = ?, "
                             + "danger_level = ? WHERE id = ?")) {
            for (MapDefaults.LocationSeed seed : MapDefaults.LOCATIONS) {
                ps.setString(1, seed.name());
                ps.setDouble(2, seed.cultivationBonus());
                ps.setDouble(3, seed.explorationBonus());
                ps.setInt(4, seed.dangerLevel());
                ps.setLong(5, seed.id());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("恢复出厂地点失败", e);
        }
    }

    // ==================== 1. 公开 API 逐字不变 ====================

    @Test
    @DisplayName("API: MapService 的公开成员与重构前逐字一致（javap 黄金清单）")
    void mapServiceKeepsGoldenPublicApi() {
        List<String> actual = describePublicApi(MapService.class);
        assertEquals(GOLDEN_SERVICE_API.stream().sorted().toList(), actual,
                "MapService 的公开 API 必须与重构前完全一致：多一个、少一个、"
                        + "改一个参数类型都会让 REST / WebSocket / 指令 / 插件 / 其它服务的调用方编译不过");
    }

    // ==================== 2. 服务层不再有 JDBC ====================

    @Test
    @DisplayName("服务层: 不再自己开连接，DatabaseManager 只剩事务边界")
    void serviceLayerOnlyOpensConnectionsWhereItMust() throws IOException {
        String source = stripComments(read(MAP_SERVICE));
        List<String[]> bodies = methodBodies(source);

        Set<String> methodNames = new HashSet<>();
        List<String> offenders = new ArrayList<>();
        int total = 0;
        for (String[] named : bodies) {
            methodNames.add(named[0]);
            int occurrences = countOccurrences(named[1], "getConnection(");
            if (occurrences == 0) {
                continue;
            }
            total += occurrences;
            if (!SERVICE_GET_CONNECTION_WHITELIST.containsKey(named[0])) {
                offenders.add(named[0] + " -> " + occurrences + " 处 DatabaseManager.getConnection()");
            }
        }

        assertTrue(offenders.isEmpty(),
                "这些方法不该自己开连接（多条语句共用连接以外的场景一律走 MapDAO 的短连接重载）：\n"
                        + String.join("\n", offenders)
                        + "\n若确实必须开，请往 SERVICE_GET_CONNECTION_WHITELIST 里加一条并写明理由。");
        assertEquals(EXPECTED_SERVICE_GET_CONNECTION_COUNT, total,
                "服务层开连接的次数变了：每次新增都必须有白名单条目与理由，"
                        + "每次减少都要同步改这个数字（白名单不能留下失效条目）");
        for (String whitelisted : SERVICE_GET_CONNECTION_WHITELIST.keySet()) {
            assertTrue(methodNames.contains(whitelisted),
                    "白名单里的 " + whitelisted + " 已经不在服务层开连接了，请从白名单里删掉它");
        }

        // 服务层碰 DatabaseManager 的唯一理由是声明事务边界。方言判断、连接获取、
        // 表名工具都属于 DAO，不该再出现。
        assertEquals(countOccurrences(source, "DatabaseManager.runTransaction("),
                countOccurrences(source, "DatabaseManager."),
                "服务层只该用 DatabaseManager.runTransaction()（声明「这段要原子」）；"
                        + "方言判断、连接获取、表名工具都属于 DAO");
        assertFalse(source.contains("setAutoCommit("), "服务层不碰事务原语");
        assertFalse(source.contains("rollback("), "服务层不碰事务原语");
    }

    @Test
    @DisplayName("服务层: MapService 里 PreparedStatement / ResultSet / 事务原语 / SQL 字面量一处不剩")
    void serviceLayerContainsNoJdbc() throws IOException {
        String source = stripComments(read(MAP_SERVICE));

        List<String> offenders = new ArrayList<>();
        for (String forbidden : List.of(
                "PreparedStatement",
                "prepareStatement(",
                "ResultSet",
                "executeQuery(",
                "executeUpdate(",
                "createStatement(",
                "setAutoCommit(",
                "rollback(",
                "java.sql.PreparedStatement",
                "java.sql.ResultSet")) {
            if (source.contains(forbidden)) {
                offenders.add(forbidden);
            }
        }
        assertTrue(offenders.isEmpty(),
                "MapService 不该再碰 JDBC（连接、语句、事务都属于 MapDAO）：" + offenders);

        List<String> sqlKeywords = new ArrayList<>();
        for (String keyword : List.of("SELECT ", "INSERT ", "UPDATE ", "DELETE ", "CREATE TABLE")) {
            if (source.contains(keyword)) {
                sqlKeywords.add(keyword);
            }
        }
        assertTrue(sqlKeywords.isEmpty(),
                "服务层不该再有 SQL 字面量（搬走了就别留半句）：" + sqlKeywords);
    }

    @Test
    @DisplayName("服务层: 地图语义仍在服务层（缓存/热重载/坐标/边界），只是数据访问换成了 DAO")
    void mapSemanticsStayInTheService() throws IOException {
        String source = stripComments(read(MAP_SERVICE));
        for (String anchor : List.of(
                "locationCache",
                "travelTimes",
                "eventBias",
                "wildernessCache",
                "snapshotLocations",
                "attachGatewayLinks",
                "resolveWilderness",
                "resolveLocationId",
                "effectiveTravelSeconds",
                "DANGER_NAMES",
                "MAP_DAO.")) {
            assertTrue(source.contains(anchor),
                    "服务层必须保留地图语义锚点 " + anchor + "："
                            + "这次重构只搬数据访问，不许把缓存/坐标/边界判定搬进 DAO");
        }

        String daoSource = stripComments(read(MAP_DAO));
        for (String forbidden : List.of(
                "WildernessTerrain", "WildernessChunk", "MapLocation", "WildernessMapConfig",
                "I18n", "MapService", "com.mtxgdn.game.service")) {
            assertFalse(daoSource.contains(forbidden),
                    "MapDAO 不该知道地图语义（" + forbidden + "）：它只负责存取行，"
                            + "坐标换算、边界判定、缓存与事件触发都属于服务层");
        }
    }

    @Test
    @DisplayName("服务层: 业务逻辑通过 MapDAO 访问地图数据表")
    void serviceDelegatesToMapDao() throws Exception {
        Field field = MapService.class.getDeclaredField("MAP_DAO");
        assertEquals(MapDAO.class, field.getType(),
                "MapService 必须持有 MapDAO 入口，而不是把 SQL 搬回来");
        assertEquals("com.mtxgdn.db", MapDAO.class.getPackageName(),
                "MapDAO 必须与现有三个 DAO 同包（com.mtxgdn.db）");
    }

    // ==================== 3. SQL 守恒 ====================

    @Test
    @DisplayName("SQL: 原 MapService 的每一条 SQL 都逐字出现在 MapDAO 里（旧有新无为空）")
    void sqlIsConserved() throws IOException {
        String daoSource = stripComments(read(MAP_DAO));
        String fingerprint = squash(String.join("", stringLiterals(daoSource)));

        List<String> missing = new ArrayList<>();
        for (String sql : GOLDEN_SQL) {
            if (!fingerprint.contains(squash(sql))) {
                missing.add(sql.strip().replaceAll("\\R", " "));
            }
        }
        assertTrue(missing.isEmpty(),
                "下面这些 SQL 在 MapDAO 里找不到（搬丢了、或被「顺手优化」了）。"
                        + "本次重构要求表名/列名/条件/ORDER BY/COALESCE/参数顺序逐字不变：\n"
                        + String.join("\n---\n", missing));
    }

    @Test
    @DisplayName("SQL: MapDAO 里不许出现清单之外、看着像 SQL 的新字面量（不许自创语句）")
    void daoInventsNoNewSql() throws IOException {
        String daoSource = stripComments(read(MAP_DAO));
        String goldenFingerprint = squash(String.join("", GOLDEN_SQL));

        List<String> invented = new ArrayList<>();
        for (String literal : stringLiterals(daoSource)) {
            String trimmed = literal.strip();
            if (!SQL_LOOKING_LITERAL.matcher(trimmed).matches()) {
                continue; // 不是语句形态（异常文案、列名、空串等）
            }
            if (!goldenFingerprint.contains(squash(trimmed))) {
                invented.add(trimmed.replaceAll("\\R", " "));
            }
        }
        assertTrue(invented.isEmpty(),
                "这些语句在重构前的 MapService 里不存在（本次重构不许新增/改写 SQL）：\n"
                        + String.join("\n---\n", invented));
    }

    // ==================== 4. 连接与事务边界 ====================

    @Test
    @DisplayName("DAO: 每个 public 方法都有 Connection 首参重载（事务内可复用调用方的连接）")
    void everyDaoMethodHasConnectionFirstOverload() {
        Set<String> withConnection = new HashSet<>();
        for (Method m : MapDAO.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() > 0 && m.getParameterTypes()[0] == Connection.class) {
                withConnection.add(m.getName());
            }
        }

        List<String> offenders = new ArrayList<>();
        for (Method m : MapDAO.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() > 0 && m.getParameterTypes()[0] == Connection.class) continue;
            if (!withConnection.contains(m.getName())) {
                offenders.add(m.getName() + "(" + parameterList(m) + ")");
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些 DAO 方法没有 Connection 首参重载，事务内调用时只能自己开连接 —— "
                        + "SQLite 下会抛 SQLITE_BUSY_SNAPSHOT 或读到旧快照：\n" + String.join("\n", offenders));

        // 逐个点名关键重载，防止有人把它们改名后"规则依然成立"
        for (String name : List.of(
                "findLocationColumns", "isLocationTableEmpty", "findBonusSeedState", "existsLocation",
                "seedLocationBonusColumns", "insertLocation", "insertDefaultLocations",
                "insertDefaultConnections", "findAllConnectionEdges", "insertConnectionIfMissing",
                "insertEventBiasIfMissing",
                "findAllLocations", "findAllConnections", "findAllEventBiases",
                "findCurrentLocation", "findPlayerPosition", "findLastTravelTime",
                "findPlayerLocations", "updateTravelIfUnchanged", "updateTravel",
                "insertExplorationIfAbsent", "isExplored", "findExploredLocationIds",
                "findLastGatherTime", "updateLastGatherTime", "hasPlayerInLocationRange")) {
            assertTrue(withConnection.contains(name), name + " 缺少 Connection 首参重载");
        }
    }

    @Test
    @DisplayName("DAO: 收 Connection 的方法绝不自己开连接、也不自己开事务")
    void daoConnectionOverloadsNeverOpenTheirOwnConnection() throws IOException {
        String source = stripComments(read(MAP_DAO));
        List<String> bodies = methodBodiesTakingConnection(source);
        assertTrue(bodies.size() >= 26,
                "扫描到的 Connection 重载太少（" + bodies.size() + " 个），说明源码解析失效了");

        List<String> offenders = new ArrayList<>();
        for (String body : bodies) {
            if (body.contains("getConnection(")) {
                offenders.add("事务重载内部又开了连接：" + firstLine(body));
            }
            if (body.contains("runTransaction(")) {
                offenders.add("事务重载内部又开了一个事务：" + firstLine(body));
            }
            if (body.contains("setAutoCommit(")) {
                offenders.add("事务重载内部改写了 autocommit：" + firstLine(body));
            }
        }
        assertTrue(offenders.isEmpty(),
                "收 Connection 的重载只能用调用方的连接 —— 自己再开一条会在 SQLite 下死锁/读旧快照：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("DAO: 没有任何方法自己管事务（提交/回滚一律留给调用方）")
    void daoOwnsNoTransaction() throws IOException {
        String source = stripComments(read(MAP_DAO));
        List<String> owners = new ArrayList<>();
        for (String[] named : methodBodies(source)) {
            if (named[1].contains("setAutoCommit(") || named[1].contains("commit()")
                    || named[1].contains("rollback()")) {
                owners.add(named[0]);
            }
        }
        assertTrue(owners.isEmpty(),
                "地图 DAO 不该开事务：加载期与首达发奖的边界在服务层，"
                        + "这里自己提交/回滚会让调用方失去原子性保证：" + owners);
    }

    @Test
    @DisplayName("事务: 任何 runTransaction lambda 体内都不出现 DatabaseManager.getConnection()")
    void transactionLambdasNeverOpenTheirOwnConnections() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = stripComments(read(file));
                for (String body : transactionLambdaBodies(source)) {
                    if (body.contains("DatabaseManager.getConnection()")) {
                        offenders.add(file.getFileName() + " -> 事务块内调用了 DatabaseManager.getConnection()");
                    }
                    if (body.contains("runTransaction(")) {
                        offenders.add(file.getFileName() + " -> 事务块内又开了一个事务");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "事务块内再开连接/事务在 SQLite 下会死锁或读到旧快照。"
                        + "需要复用事务时请把 Connection 作为参数传下去：\n" + String.join("\n", offenders));
    }

    // ==================== 5. 真跑 SQLite：地图能加载 / 偏置能读回 / 野外资源 / 热重载 ====================

    @Test
    @DisplayName("行为: 地图经 DAO 加载出来（15 个地点、出厂加成、邻接、境界门槛都在）")
    void mapLoadsThroughTheDao() {
        List<MapLocation> known = mapService.getAllLocations();
        assertEquals(15, known.size(), "出厂地点应恰好 15 个（DAO 的全量加载不许漏行或多行）");

        MapLocation village = mapService.getLocation(QINGYUN_VILLAGE);
        assertNotNull(village, "青云村必须能加载出来");
        assertEquals("青云村", village.getName());
        assertTrue(village.isSafeZone(), "青云村是安全区");
        assertEquals(0, village.getDangerLevel());

        assertEquals(1.50, mapService.getCultivationBonus(WENDAO_PEAK), 1e-9,
                "问道峰灵气应来自 map_locations.cultivation_bonus");
        assertEquals(1.35, mapService.getExplorationBonus(WANYAO_VALLEY), 1e-9,
                "万妖谷地界加成应来自 map_locations.exploration_bonus");
        assertEquals(3, mapService.getDangerLevel(WANYAO_VALLEY), "万妖谷是 3 级（高危）险地");

        assertEquals(1, mapService.getNeighbors(QINGYUN_VILLAGE).size(),
                "青云村只有青云山一个邻居（map_connections 的邻接必须真的挂上缓存）");
        assertTrue(mapService.getTravelTimeSeconds(QINGYUN_VILLAGE, 2L) > 0,
                "连接耗时必须从 map_connections 读出来");

        // 事件偏置：出厂数据里万妖谷偏向妖兽、灵药谷偏向灵草
        Double monster = mapService.getEventBias(WANYAO_VALLEY).get("monster");
        assertNotNull(monster, "万妖谷的出厂偏置应加载出来");
        assertEquals(3.0, monster.doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("行为: 事件偏置经 DAO 增删改后，reload 能逐条读回（新增/覆盖/删除都生效）")
    void eventBiasCrudRoundTripsThroughReload() throws SQLException {
        String key = PROBE_PREFIX + SEQ.incrementAndGet();

        // 增：DAO 的 insertEventBiasIfMissing；重复插入必须被去重（先查后插）
        try (Connection conn = DatabaseManager.getConnection()) {
            DAO.insertEventBiasIfMissing(conn, CANGYUE_TOWN, key, 2.5);
            DAO.insertEventBiasIfMissing(conn, CANGYUE_TOWN, key, 9.9);
            assertEquals(1, countBiasRows(conn, CANGYUE_TOWN, key),
                    "偏置已存在时不该再插一行（否则热重载后同 key 出现两行，权重口径就乱了）");
        }
        mapService.reload();
        Double added = mapService.getEventBias(CANGYUE_TOWN).get(key);
        assertNotNull(added, "新增的偏置必须能读回");
        assertEquals(2.5, added.doubleValue(), 1e-9,
                "重复插入不该改掉已有的值（先查后插 = 只补缺）");

        // 改：后台通用表管理直接改库（MapDAO 不提供 update，因为它不在 MapService 里）
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE map_location_event_bias SET weight_multiplier = ? "
                             + "WHERE location_id = ? AND event_key = ?")) {
            ps.setDouble(1, 4.0);
            ps.setLong(2, CANGYUE_TOWN);
            ps.setString(3, key);
            assertEquals(1, ps.executeUpdate(), "改一行应命中");
        }
        mapService.reload();
        assertEquals(4.0, mapService.getEventBias(CANGYUE_TOWN).get(key).doubleValue(), 1e-9,
                "改库后 reload 必须读到新值（缓存不能是只加载一次的）");

        // 删
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM map_location_event_bias WHERE location_id = ? AND event_key = ?")) {
            ps.setLong(1, CANGYUE_TOWN);
            ps.setString(2, key);
            assertEquals(1, ps.executeUpdate(), "删一行应命中");
        }
        mapService.reload();
        assertFalse(mapService.getEventBias(CANGYUE_TOWN).containsKey(key),
                "删库后 reload 必须让偏置消失");

        // 出厂偏置不能因为本用例被改掉
        assertEquals(2.0, mapService.getEventBias(CANGYUE_TOWN).get("merchant").doubleValue(), 1e-9,
                "苍月镇出厂偏置（坊市 merchant 2.0）不该被动到");
    }

    @Test
    @DisplayName("行为: 野外资源查询正常（资源在分片文件里，DAO 只回答区间内有没有玩家）")
    void wildernessResourceQueryWorks() throws SQLException {
        assertTrue(WildernessMapConfig.get().isEnabled(), "测试环境应默认启用野外");

        boolean foundResource = false;
        for (int[] chunk : new int[][]{{1, 0}, {0, 1}}) {
            List<MapLocation> nodes = mapService.getChunkNodes(chunk[0], chunk[1]);
            assertFalse(nodes.isEmpty(), "分片 (" + chunk[0] + "," + chunk[1] + ") 应能列出节点");
            for (MapLocation node : nodes) {
                Map<String, Integer> resources = mapService.getWildernessResource(node.getId());
                assertNotNull(resources, "野外资源查询不该返回 null（无资源时是空表）");
                for (Map.Entry<String, Integer> entry : resources.entrySet()) {
                    assertTrue(entry.getValue() > 0, "剩余量应为正数，实际 " + entry.getValue());
                    foundResource = true;
                }
            }
            if (foundResource) {
                break;
            }
        }
        assertTrue(foundResource, "至少有一个野外节点应带资源（配置池非空）");

        // 已知世界地点不是野外：查询返回空表，而不是抛异常
        assertTrue(mapService.getWildernessResource(QINGYUN_VILLAGE).isEmpty(),
                "已知世界地点没有野外资源");

        // 分片内是否有玩家：野外回收前的安全检查，走 DAO 的区间查询
        long playerId = createPlayer(QINGYUN_VILLAGE);
        assertTrue(DAO.hasPlayerInLocationRange(0L, 1000L), "区间应命中刚建的玩家");

        long wildernessNode = WildernessTerrain.nodeId(1, 0, 0);
        long first = WildernessTerrain.nodeId(1, 0, 0);
        long last = first + WildernessTerrain.SLOT_STRIDE;
        assertFalse(DAO.hasPlayerInLocationRange(first, last), "该分片里还没有玩家");

        setPlayerLocation(playerId, wildernessNode);
        assertTrue(DAO.hasPlayerInLocationRange(first, last),
                "玩家站进野外分片后，回收检查必须能看到他（否则会把人脚下的地删掉）");
    }

    @Test
    @DisplayName("行为: 缓存重载后数据一致（同一份库 → 同一份缓存；改库 + reload 才变）")
    void reloadKeepsCacheConsistentWithDatabase() throws SQLException {
        List<Long> idsBefore = mapService.getAllLocations().stream().map(MapLocation::getId).toList();
        mapService.reload();
        List<Long> idsAfter = mapService.getAllLocations().stream().map(MapLocation::getId).toList();
        assertEquals(idsBefore, idsAfter, "同一份库连着重载两次，地点集合与顺序必须完全一致");

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE map_locations SET name = ? WHERE id = ?")) {
            ps.setString(1, "问道峰·DAO探针");
            ps.setLong(2, WENDAO_PEAK);
            ps.executeUpdate();
        }
        try {
            assertEquals("问道峰", mapService.getLocationName(WENDAO_PEAK),
                    "未 reload 前应仍是旧缓存（缓存本来就是为了省掉每请求一次查询）");
            mapService.reload();
            assertEquals("问道峰·DAO探针", mapService.getLocationName(WENDAO_PEAK),
                    "reload 后必须读到新名字（热重载是管理台改地图的唯一生效路径）");
        } finally {
            restoreSeededLocations();
            mapService.reload();
        }
        assertEquals("问道峰", mapService.getLocationName(WENDAO_PEAK), "复原后应回到出厂名");
    }

    @Test
    @DisplayName("行为: 探索记录经 DAO 写入（首次为 true、重复为 false、集合可读回）")
    void explorationRecordsRoundTripThroughTheDao() throws SQLException {
        long playerId = createPlayer(QINGYUN_VILLAGE);
        long locationId = WANYAO_VALLEY;

        assertFalse(mapService.isExplored(playerId, locationId), "前置条件：还没去过");
        try (Connection conn = DatabaseManager.getConnection()) {
            assertTrue(DAO.insertExplorationIfAbsent(conn, playerId, locationId, 1_700_000_000L),
                    "第一次写探索记录必须返回 true（INSERT OR IGNORE 影响 1 行）");
            assertFalse(DAO.insertExplorationIfAbsent(conn, playerId, locationId, 1_700_000_001L),
                    "同一地点再写一次必须返回 false（唯一键冲突 = 非首次，不能再发一份奖励）");
            assertTrue(DAO.isExplored(conn, playerId, locationId));
            assertTrue(DAO.findExploredLocationIds(conn, playerId).contains(locationId));
        }
        assertTrue(mapService.isExplored(playerId, locationId), "服务层应读到刚写的探索记录");
        assertTrue(mapService.getExploredLocationIds(playerId).contains(locationId),
                "批量查询（避免 N+1 的那条）也要能看到它");
    }

    // ==================== 6. 事务内用 Connection 重载 ====================

    @Test
    @DisplayName("事务: 事务内用 Connection 重载 —— 回滚就真的回滚（证明用的是同一条连接）")
    void daoConnectionOverloadsJoinTheCallerTransaction() throws SQLException {
        String key = PROBE_PREFIX + "rollback_" + SEQ.incrementAndGet();

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                DatabaseManager.runTransaction(conn -> {
                    DAO.insertEventBiasIfMissing(conn, CANGYUE_TOWN, key, 1.5);
                    assertTrue(DAO.findAllEventBiases(conn).stream()
                                    .anyMatch(row -> row.eventKey().equals(key)),
                            "同一事务内必须立刻读到自己刚写的偏置（说明用的是同一条连接）");
                    throw new IllegalStateException("故意回滚");
                }));
        assertTrue(String.valueOf(error.getMessage()).contains("故意回滚"),
                "事务里的异常必须原样冒出来，实际: " + error.getMessage());

        try (Connection conn = DatabaseManager.getConnection()) {
            assertEquals(0, countBiasRows(conn, CANGYUE_TOWN, key),
                    "事务已回滚，这条偏置不该留在库里 —— 若留下了，说明 DAO 在事务里另开了一条"
                            + "（autocommit）连接，SQLite 下正是 SQLITE_BUSY_SNAPSHOT 的来源");
        }
    }

    @Test
    @DisplayName("事务: 有并发写在跑时，事务内调 Connection 重载也不会抛 SQLITE_BUSY_SNAPSHOT")
    void transactionWithConcurrentWriterDoesNotRaiseBusySnapshot() throws Exception {
        String key = PROBE_PREFIX + "tx_" + SEQ.incrementAndGet();
        String otherKey = PROBE_PREFIX + "tx_other_" + SEQ.incrementAndGet();

        CountDownLatch txHasRead = new CountDownLatch(1);
        CountDownLatch otherCommitted = new CountDownLatch(1);
        AtomicBoolean interfered = new AtomicBoolean(false);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                txHasRead.await();
                // 另一个连接、另一行、同一张表 —— 它一提交，事务里已有的读快照就过期了
                DAO.insertEventBiasIfMissing(WANYAO_VALLEY, otherKey, 1.5);
            } catch (Throwable t) {
                writerFailure.set(t);
            } finally {
                otherCommitted.countDown();
            }
        }, "map-dao-concurrent-writer");
        writer.setDaemon(true);
        writer.start();

        try {
            for (int round = 0; round < 3; round++) {
                int expected = round; // 循环变量不是 effectively final，lambda 里只能捕获这个副本
                DatabaseManager.runTransaction(conn -> {
                    // 先读（建立快照）
                    List<EventBiasRow> before = DAO.findAllEventBiases(conn);
                    assertNotNull(before, "事务内必须能读到偏置表");
                    if (interfered.compareAndSet(false, true)) {
                        // 制造"读后写 + 期间别的连接提交了写"这一形状：
                        // 第一轮的 INSERT 会撞上 SQLITE_BUSY_SNAPSHOT，重试由 runTransaction 负责，
                        // 它绝不该把这个异常冒给调用方。
                        txHasRead.countDown();
                        assertTrue(otherCommitted.await(10, TimeUnit.SECONDS), "并发写者应在 10 秒内提交");
                    }
                    DAO.insertEventBiasIfMissing(conn, CANGYUE_TOWN, key, 2.5);
                    assertTrue(DAO.findAllEventBiases(conn).stream()
                                    .anyMatch(row -> row.eventKey().equals(key)),
                            "第 " + expected + " 轮：同一事务内必须看到自己的写入");
                    return null;
                });
            }
        } finally {
            writer.join(10_000L);
        }

        assertNull(writerFailure.get(), "并发写线程不该失败: " + writerFailure.get());
        try (Connection conn = DatabaseManager.getConnection()) {
            assertEquals(1, countBiasRows(conn, CANGYUE_TOWN, key), "3 轮事务的写入必须落地且不重复");
            assertEquals(1, countBiasRows(conn, WANYAO_VALLEY, otherKey), "并发写者的那笔写入也必须落地");
        }
    }

    @Test
    @DisplayName("事务: 事务内用 Connection 重载改玩家位置 —— 提交生效、回滚不生效")
    void travelCasOverloadRespectsTheCallerTransaction() throws SQLException {
        long playerId = createPlayer(QINGYUN_VILLAGE);
        long now = System.currentTimeMillis() / 1000;

        try (Connection conn = DatabaseManager.getConnection()) {
            PlayerPositionRow before = DAO.findPlayerPosition(conn, playerId);
            assertNotNull(before, "刚建的角色必须能读到位置");
            assertEquals(QINGYUN_VILLAGE, before.rawLocationId());

            // CAS 命中：期望值与库里一致
            assertEquals(1, DAO.updateTravelIfUnchanged(conn, playerId, before, CANGYUE_TOWN, now),
                    "期望值正确时 CAS 应命中 1 行");

            // CAS 落空：拿旧期望值再写一次，必须 0 行（并发移动只允许一次成功）
            PlayerPositionRow stale = new PlayerPositionRow(QINGYUN_VILLAGE, false, before.lastTravelTime());
            assertEquals(0, DAO.updateTravelIfUnchanged(conn, playerId, stale, WANYAO_VALLEY, now),
                    "位置已被改过，旧期望值的 CAS 必须落空（否则两个并发请求会双双成功）");
        }
        assertEquals(CANGYUE_TOWN, mapService.getPlayerLocationId(playerId), "CAS 写入应已落库");

        // 回滚：事务里写的移动不该生效
        assertThrows(RuntimeException.class, () -> DatabaseManager.runTransaction(conn -> {
            PlayerPositionRow current = DAO.findPlayerPosition(conn, playerId);
            DAO.updateTravel(conn, playerId, WANYAO_VALLEY, System.currentTimeMillis() / 1000);
            assertNotNull(current);
            throw new IllegalStateException("故意回滚");
        }));
        assertEquals(CANGYUE_TOWN, mapService.getPlayerLocationId(playerId),
                "事务回滚后位置必须还是苍月镇（若变成了万妖谷，说明 DAO 用了另一条 autocommit 连接）");
    }

    // ==================== 夹具 ====================

    /** 建一个用户 + 角色，返回 playerId（外键约束要求先有 user）。 */
    private long createPlayer(long locationId) throws SQLException {
        int n = SEQ.incrementAndGet();
        String username = "mapdao_" + n + "_" + System.nanoTime();
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            long userId;
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "用户写入失败: " + username);
                    userId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, realm, hp, max_hp, current_location_id, last_travel_time) "
                            + "VALUES (?, ?, ?, 100, 100, ?, 0)")) {
                ps.setLong(1, userId);
                ps.setString(2, "道友" + n);
                ps.setInt(3, 9);
                ps.setLong(4, locationId);
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

    private void setPlayerLocation(long playerId, long locationId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players SET current_location_id = ?, last_travel_time = 0 WHERE id = ?")) {
            ps.setLong(1, locationId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    private static int countBiasRows(Connection conn, long locationId, String eventKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM map_location_event_bias WHERE location_id = ? AND event_key = ?")) {
            ps.setLong(1, locationId);
            ps.setString(2, eventKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ==================== 辅助 ====================

    /** 反射导出与 {@code javap -public} 同格式的公开成员清单（排序后便于逐字比较）。 */
    private static List<String> describePublicApi(Class<?> type) {
        List<String> members = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ");
            if (Modifier.isStatic(field.getModifiers())) sb.append("static ");
            if (Modifier.isFinal(field.getModifiers())) sb.append("final ");
            members.add(sb.append(field.getGenericType().getTypeName())
                    .append(' ').append(field.getName()).toString());
        }
        for (var ctor : type.getDeclaredConstructors()) {
            if (!Modifier.isPublic(ctor.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ").append(type.getTypeName()).append('(');
            Type[] params = ctor.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getTypeName());
            }
            members.add(sb.append(')').toString());
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ");
            if (Modifier.isStatic(method.getModifiers())) sb.append("static ");
            sb.append(method.getGenericReturnType().getTypeName())
                    .append(' ').append(method.getName()).append('(');
            Type[] params = method.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getTypeName());
            }
            members.add(sb.append(')').toString());
        }
        return members.stream().sorted().toList();
    }

    private static String parameterList(Method m) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "找不到文件: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 去掉全部空白，用于"SQL 字面量守恒"的比对（换行/缩进不算差异）。 */
    private static String squash(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static String firstLine(String body) {
        String trimmed = body.strip();
        int nl = trimmed.indexOf('\n');
        return nl > 0 ? trimmed.substring(0, nl).strip() : trimmed;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        int index;
        while ((index = text.indexOf(needle, from)) >= 0) {
            count++;
            from = index + needle.length();
        }
        return count;
    }

    /**
     * 抽出源码里的全部字符串字面量（含 text block），按出现顺序拼接。
     *
     * <p>拼接后再去空白，是为了让 {@code "A " + "B"} 这种被拆开的 SQL 也能与
     * 完整的 SQL 文本对上 —— 本次重构允许把 SQL 拆成常量，但不允许改内容。</p>
     */
    private static List<String> stringLiterals(String source) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c != '"') {
                i++;
                continue;
            }
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                assertTrue(end > 0, "text block 未闭合：源码解析失败");
                literals.add(source.substring(i + 3, end));
                i = end + 3;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int j = i + 1;
            while (j < source.length()) {
                char d = source.charAt(j);
                if (d == '\\' && j + 1 < source.length()) {
                    sb.append(source.charAt(j + 1));
                    j += 2;
                    continue;
                }
                if (d == '"') break;
                sb.append(d);
                j++;
            }
            literals.add(sb.toString());
            i = j + 1;
        }
        return literals;
    }

    /**
     * 取出源码里所有"第一个参数是 {@code Connection} 的方法"的声明 + 方法体。
     *
     * <p>只认<b>方法声明</b>（行首访问修饰符 + 名字 + {@code (Connection conn}），
     * 否则 {@code try (Connection conn = DatabaseManager.getConnection())} 这种
     * try-with-resources 也会被误当成方法头，扫描结果就不可信了。</p>
     */
    private static List<String> methodBodiesTakingConnection(String source) {
        List<String> bodies = new ArrayList<>();
        Matcher matcher = CONNECTION_METHOD_DECL.matcher(source);
        while (matcher.find()) {
            String body = methodBodyAt(source, matcher.start());
            if (body != null) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    /** 取出源码里所有方法：每个元素是 {@code {方法名, 声明 + 方法体}}。 */
    private static List<String[]> methodBodies(String source) {
        List<String[]> bodies = new ArrayList<>();
        Matcher matcher = METHOD_DECL.matcher(source);
        while (matcher.find()) {
            String body = methodBodyAt(source, matcher.start());
            if (body != null) {
                bodies.add(new String[]{matcher.group(1), body});
            }
        }
        return bodies;
    }

    /** 从方法声明的起点（{@code start}）取出"声明 + 方法体"；抽象声明返回 {@code null}。 */
    private static String methodBodyAt(String source, int start) {
        int open = source.indexOf('(', start);
        int close = matchingParen(source, open);
        int brace = source.indexOf('{', close);
        int semi = source.indexOf(';', close);
        // 抽象方法声明（如回调接口里的一行签名）没有方法体，跳过
        if (semi >= 0 && (brace < 0 || semi < brace)) return null;
        int end = matchingBrace(source, brace);
        return source.substring(start, end + 1);
    }

    /** 取出每个 {@code DatabaseManager.runTransaction(...)} 调用的实参文本（即 lambda 体）。 */
    private static List<String> transactionLambdaBodies(String source) {
        List<String> bodies = new ArrayList<>();
        String needle = "DatabaseManager.runTransaction(";
        int from = 0;
        int idx;
        while ((idx = source.indexOf(needle, from)) >= 0) {
            int open = idx + needle.length() - 1;
            int close = matchingParen(source, open);
            bodies.add(source.substring(open + 1, close));
            from = close;
        }
        return bodies;
    }

    /** 从 {@code open} 处的 {@code (} 起找配对的 {@code )}，跳过字符串与字符字面量。 */
    private static int matchingParen(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j - 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalStateException("括号不匹配：源码扫描失败");
    }

    /** 从 {@code open} 处的 {@code {} 起找配对的 {@code }}，跳过字符串与字符字面量。 */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j - 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalStateException("花括号不匹配：源码扫描失败");
    }

    /** 剥掉 {@code //} 与 {@code /* *}{@code /} 注释，保留字符串/字符字面量。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                out.append(c);
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    out.append(d);
                    if (d == '\\' && j + 1 < source.length()) {
                        out.append(source.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
