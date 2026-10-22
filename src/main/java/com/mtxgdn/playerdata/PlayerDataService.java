package com.mtxgdn.playerdata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.mtxgdn.i18n.I18n;

/**
 * 玩家数据文件化服务 —— 将玩家数据抽离至单个文件（多文件，一用户一个）。
 *
 * <p>每个玩家在 {@code data/players/<playerId>.json} 下拥有一份完整的数据快照，
 * 涵盖基础属性、背包、装备、技能、功法、每日、经济、灵庄、称号、能量、农田、Buff、好友、
 * 洞府、阵法、坊市挂单、宗门成员等全部个人数据。</p>
 *
 * <p>工作模式（application.yml）：</p>
 * <ul>
 *   <li>{@code player-data.enabled}：是否启用文件化（默认 true）</li>
 *   <li>{@code player-data.dir}：文件目录（默认 data/players）</li>
 *   <li>{@code player-data.auto-sync-seconds}：定期把变更过的玩家数据落盘（默认 30 秒，0 关闭）</li>
 *   <li>{@code player-data.self-restore}：是否允许玩家用自己导出的文件自助恢复（默认 false，防刷）</li>
 * </ul>
 */
public class PlayerDataService {

    private static final GameLogger LOG = GameLogger.getLogger(PlayerDataService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final boolean ENABLED = AppConfig.getBoolean("player-data.enabled", true);
    private static final Path DATA_DIR = Paths.get(AppConfig.get("player-data.dir", "data/players"));
    private static final int AUTO_SYNC_SECONDS = Math.max(0, AppConfig.getInt("player-data.auto-sync-seconds", 30));
    private static final boolean SELF_RESTORE = AppConfig.getBoolean("player-data.self-restore", false);

    /** 等待落盘的玩家集合 */
    private static final Set<Long> dirtyPlayers = ConcurrentHashMap.newKeySet();

    /** 已进入停机流程：后台线程不再发起任何落盘，避免与连接池关闭抢时序。 */
    private static volatile boolean stopping = false;

    /** 快照格式版本；改动行内值编码方式时递增。 */
    private static final int SCHEMA_VERSION = 1;

    /**
     * 导出/还原的成功失败计数。
     *
     * <p>以前导出失败只打一条 WARN，运维根本看不见 —— 备份目录可能几个月都是空的，
     * 直到真要恢复时才发现。这些计数会随 {@link #getStats()} 暴露到管理接口，
     * 让"备份到底有没有在成功"变成可观测的事实。</p>
     */
    private static final AtomicLong EXPORT_OK = new AtomicLong();
    private static final AtomicLong EXPORT_FAIL = new AtomicLong();
    private static final AtomicLong RESTORE_OK = new AtomicLong();
    private static final AtomicLong RESTORE_FAIL = new AtomicLong();
    private static volatile String lastError = "";
    private static volatile long lastErrorAt = 0;

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "player-data-sync");
                t.setDaemon(true);
                return t;
            });

    static {
        if (ENABLED && AUTO_SYNC_SECONDS > 0) {
            scheduler.scheduleAtFixedRate(PlayerDataService::flushDirty, AUTO_SYNC_SECONDS, AUTO_SYNC_SECONDS, TimeUnit.SECONDS);
            LOG.info("玩家数据文件化已启用，目录: " + DATA_DIR.toAbsolutePath() + "（自动同步间隔 " + AUTO_SYNC_SECONDS + " 秒）");
        } else {
            LOG.info("玩家数据文件化未启用或自动同步已关闭");
        }
    }

    public PlayerDataService() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean isSelfRestoreEnabled() {
        return SELF_RESTORE;
    }

    /** 标记某玩家数据已变更（由各服务在写入后调用） */
    public static void markDirty(long playerId) {
        if (ENABLED && playerId > 0) {
            dirtyPlayers.add(playerId);
        }
    }

    /** 立即落盘所有待同步的玩家（供定时任务与停机时调用） */
    public static void flushDirty() {
        if (!ENABLED || dirtyPlayers.isEmpty() || stopping) {
            return;
        }
        // 连接池已关闭（停机或测试收尾）：这时刷盘只会得到 "no such table"，
        // 既救不了数据也会把日志刷满，直接放弃这一轮。
        if (!DatabaseManager.isAvailable()) {
            return;
        }
        List<Long> ids = new ArrayList<>(dirtyPlayers);
        dirtyPlayers.clear();
        PlayerDataService service = new PlayerDataService();
        for (long id : ids) {
            try {
                service.exportPlayer(id);
            } catch (Exception e) {
                // 只有数据库还活着才值得重试；否则会把死任务永久留在队列里
                if (DatabaseManager.isAvailable() && !stopping) {
                    dirtyPlayers.add(id);
                }
                LOG.warn("玩家 " + id + " 数据落盘失败，稍后重试: " + e.getMessage());
            }
        }
    }

    public static void shutdown() {
        // 顺序很重要：先趁数据库还活着把脏数据刷完，再关调度器并置停机标记。
        // 反过来的话最后一次落盘会被 stopping 挡掉 —— 那恰恰是最不能丢的一次。
        flushDirty();
        stopping = true;
        scheduler.shutdown();
    }

    // ==================== 导出 ====================

    /**
     * 将指定玩家的全部数据导出到其个人文件（data/players/{playerId}.json）。
     *
     * <p>相比"写出去就算成功"的旧实现，这里多了三道保障：</p>
     * <ol>
     *   <li><b>读失败即导出失败</b>：任何一张表读不出来都会抛异常，不再静默产出一份
     *       缺表的"完整快照"。</li>
     *   <li><b>写后回读校验</b>：落盘后立刻把文件读回来重新算摘要与行数，
     *       与内存中的快照比对，不一致就判定失败 —— 宁可这次不更新，也不留一份坏备份。</li>
     *   <li><b>保留上一版</b>：覆盖前先把旧文件复制为 {@code <id>.json.bak}，
     *       这样"新快照是坏的但校验没抓到"这类极端情况还有退路。</li>
     * </ol>
     */
    public Map<String, Object> exportPlayer(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!ENABLED) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h9573c3bc"));
            return result;
        }

        PlayerData data;
        try {
            data = collectPlayerData(playerId);
        } catch (RuntimeException e) {
            // 读表失败：绝不产出不完整的快照
            recordExportFailure("采集玩家 " + playerId + " 数据失败: " + e.getMessage());
            result.put("success", false);
            result.put("message", I18n.t("playerdata.export_read_failed", e.getMessage()));
            return result;
        }
        if (data == null) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.hc014f818", playerId));
            return result;
        }

        data.schemaVersion = SCHEMA_VERSION;

        try {
            Files.createDirectories(DATA_DIR);
            Path target = DATA_DIR.resolve(playerId + ".json");
            Path tmp = DATA_DIR.resolve(playerId + ".json.tmp");

            // 先写一遍，再读回来解析。摘要必须算在"解析后的规范形态"上：
            // JDBC 取出的数字是 Long/Integer，JSON 里没有整数类型，读回来一律变成 Double
            // （123 vs 123.0）。如果直接拿内存对象算摘要，跟磁盘那份永远对不上 ——
            // 这正是最初版本每次导出都报"摘要不一致"的原因。
            Files.write(tmp, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
            PlayerData canonical;
            try {
                canonical = GSON.fromJson(Files.readString(tmp, StandardCharsets.UTF_8), PlayerData.class);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                recordExportFailure("玩家 " + playerId + " 快照解析失败: " + e.getMessage());
                result.put("success", false);
                result.put("message", I18n.t("playerdata.export_verify_failed", e.getMessage()));
                return result;
            }
            if (canonical == null || canonical.tables == null) {
                Files.deleteIfExists(tmp);
                recordExportFailure("玩家 " + playerId + " 快照内容为空");
                result.put("success", false);
                result.put("message", I18n.t("playerdata.export_verify_failed", "内容为空"));
                return result;
            }
            canonical.playerId = data.playerId;
            canonical.userId = data.userId;
            canonical.playerName = data.playerName;
            canonical.exportedAt = data.exportedAt;
            canonical.schemaVersion = SCHEMA_VERSION;
            canonical.rowCount = countRows(canonical);
            canonical.checksum = computeChecksum(canonical);
            Files.write(tmp, GSON.toJson(canonical).getBytes(StandardCharsets.UTF_8));

            // 回读校验：确认磁盘上那份内容确实能还原成同一份数据
            String verifyError = verifySerialized(Files.readString(tmp, StandardCharsets.UTF_8), canonical);
            if (verifyError != null) {
                Files.deleteIfExists(tmp);
                recordExportFailure("玩家 " + playerId + " 快照回读校验失败: " + verifyError);
                result.put("success", false);
                result.put("message", I18n.t("playerdata.export_verify_failed", verifyError));
                return result;
            }
            data = canonical;

            // 保留上一版，再原子替换
            if (Files.exists(target)) {
                try {
                    Files.copy(target, DATA_DIR.resolve(playerId + ".json.bak"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // 备份旧文件失败不阻断新快照落盘
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            recordExportFailure("玩家 " + playerId + " 快照落盘失败: " + e.getMessage());
            result.put("success", false);
            result.put("message", I18n.t("playerdata.heb291fb8", e.getMessage()));
            return result;
        }

        EXPORT_OK.incrementAndGet();
        result.put("success", true);
        result.put("message", I18n.t("playerdata.h31da32fc", data.playerName, playerId, filePath(playerId)));
        result.put("filePath", filePath(playerId));
        result.put("exportedAt", data.exportedAt);
        result.put("rowCount", data.rowCount);
        result.put("checksum", data.checksum);
        return result;
    }

    /** 统计所有表的行数。 */
    private static int countRows(PlayerData data) {
        int total = 0;
        for (List<Map<String, Object>> rows : data.tables.values()) {
            if (rows != null) total += rows.size();
        }
        return total;
    }

    /**
     * 对 {@code tables} 求 SHA-256。刻意只算 tables、不算 meta 字段本身
     * —— 否则导出时间一变化摘要就变，"内容没变但摘要不同"会让校验失去意义。
     */
    private static String computeChecksum(PlayerData data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(GSON.toJson(data.tables).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，这里不可能发生
            return null;
        }
    }

    /**
     * 校验一段 JSON 文本是否与内存中的快照一致。
     *
     * @return 不一致时返回错误描述；一致返回 null
     */
    private String verifySerialized(String json, PlayerData expected) {
        PlayerData parsed;
        try {
            parsed = GSON.fromJson(json, PlayerData.class);
        } catch (Exception e) {
            return "文件无法解析: " + e.getMessage();
        }
        if (parsed == null || parsed.tables == null) {
            return "文件内容为空";
        }
        int actualRows = countRows(parsed);
        if (actualRows != expected.rowCount) {
            return "行数不一致（期望 " + expected.rowCount + "，实际 " + actualRows + "）";
        }
        String actualChecksum = computeChecksum(parsed);
        if (expected.checksum != null && !expected.checksum.equals(actualChecksum)) {
            return "内容摘要不一致";
        }
        return null;
    }

    private static void recordExportFailure(String message) {
        EXPORT_FAIL.incrementAndGet();
        lastError = message;
        lastErrorAt = System.currentTimeMillis();
        LOG.error(message);
    }

    /** 导出/还原的运行统计，供管理后台展示。 */
    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", ENABLED);
        stats.put("dir", DATA_DIR.toAbsolutePath().toString());
        stats.put("exportOk", EXPORT_OK.get());
        stats.put("exportFail", EXPORT_FAIL.get());
        stats.put("restoreOk", RESTORE_OK.get());
        stats.put("restoreFail", RESTORE_FAIL.get());
        stats.put("pendingDirty", dirtyPlayers.size());
        stats.put("lastError", lastError);
        stats.put("lastErrorAt", lastErrorAt);
        return stats;
    }

    /** 导出全部玩家（迁移/备份用） */
    public Map<String, Object> exportAllPlayers() {
        Map<String, Object> result = new LinkedHashMap<>();
        int ok = 0;
        int fail = 0;
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM players")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                try {
                    exportPlayer(id);
                    ok++;
                } catch (Exception e) {
                    fail++;
                    LOG.warn("导出玩家 " + id + " 失败: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("导出全部玩家失败", e);
        }
        result.put("success", true);
        result.put("exported", ok);
        result.put("failed", fail);
        result.put("dir", DATA_DIR.toAbsolutePath().toString());
        return result;
    }

    /** 列出所有玩家数据文件 */
    public List<String> listPlayerFiles() {
        List<String> files = new ArrayList<>();
        try {
            if (Files.isDirectory(DATA_DIR)) {
                try (var stream = Files.list(DATA_DIR)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(p -> files.add(p.getFileName().toString()));
                }
            }
        } catch (IOException e) {
            LOG.warn("列出玩家数据文件失败: " + e.getMessage());
        }
        return files;
    }

    public static String filePath(long playerId) {
        return DATA_DIR.resolve(playerId + ".json").toAbsolutePath().toString();
    }

    // ==================== 收集与还原 ====================

    /** 表 → 玩家关联列（默认为 player_id；个别表使用 seller_player_id 等） */
    private static final Map<String, String> PLAYER_TABLES = new LinkedHashMap<>();

    static {
        PLAYER_TABLES.put("players_items", "player_id");
        PLAYER_TABLES.put("players_equipment", "player_id");
        PLAYER_TABLES.put("players_skills", "player_id");
        PLAYER_TABLES.put("players_techniques", "player_id");
        PLAYER_TABLES.put("player_daily", "player_id");
        PLAYER_TABLES.put("player_economy", "player_id");
        PLAYER_TABLES.put("player_bank", "player_id");
        PLAYER_TABLES.put("player_energy", "player_id");
        PLAYER_TABLES.put("player_titles", "player_id");
        PLAYER_TABLES.put("player_buffs", "player_id");
        PLAYER_TABLES.put("farm_plots", "player_id");
        PLAYER_TABLES.put("caves", "player_id");
        PLAYER_TABLES.put("formations", "player_id");
        PLAYER_TABLES.put("trade_listings", "seller_player_id");
        PLAYER_TABLES.put("redeemed_codes", "player_id");
    }

    /** 获取表的玩家关联列 */
    private static String playerColumn(String table) {
        return PLAYER_TABLES.getOrDefault(table, "player_id");
    }

    private PlayerData collectPlayerData(long playerId) {
        PlayerData data = new PlayerData();
        Map<String, Object> playerRow = querySingleRow("players", "id", playerId);
        if (playerRow == null) {
            return null;
        }
        data.playerId = playerId;
        data.userId = ((Number) playerRow.getOrDefault("user_id", 0L)).longValue();
        Object name = playerRow.get("name");
        data.playerName = name != null ? name.toString() : ("#" + playerId);
        data.exportedAt = System.currentTimeMillis();

        List<Map<String, Object>> playerRows = new ArrayList<>();
        playerRows.add(playerRow);
        data.tables.put("players", playerRows);

        for (Map.Entry<String, String> entry : PLAYER_TABLES.entrySet()) {
            String table = entry.getKey();
            List<Map<String, Object>> rows = queryRowsByColumn(table, entry.getValue(), playerId);
            if (!rows.isEmpty()) {
                data.tables.put(table, rows);
            }
        }

        // 好友：双向记录
        List<Map<String, Object>> friends = queryRowsByColumnOr("friends", "player_id", "friend_player_id", playerId);
        if (!friends.isEmpty()) {
            data.tables.put("friends", friends);
        }

        // 宗门成员
        List<Map<String, Object>> sectMembers = queryRowsByColumn("sect_members", "player_id", playerId);
        if (!sectMembers.isEmpty()) {
            data.tables.put("sect_members", sectMembers);
        }
        return data;
    }

    /**
     * 读某张表里属于该玩家的所有行。
     *
     * <p><b>读失败必须抛异常</b>：旧实现只是打一条 WARN 然后返回空列表，
     * 结果一张表读挂了会产出一份"看起来正常、实际缺表"的快照，
     * 等到需要恢复时才发现少了一半数据。备份的价值在于完整性，
     * 所以这里宁可让整次导出失败。</p>
     */
    private List<Map<String, Object>> queryRowsByColumn(String table, String column, long value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SELECT * FROM " + table + " WHERE " + column + " = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rowToMap(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取表 " + table + " 失败: " + e.getMessage(), e);
        }
        return rows;
    }

    private List<Map<String, Object>> queryRowsByColumnOr(String table, String colA, String colB, long value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SELECT * FROM " + table + " WHERE " + colA + " = ? OR " + colB + " = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.setLong(2, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rowToMap(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取表 " + table + " 失败: " + e.getMessage(), e);
        }
        return rows;
    }

    private Map<String, Object> querySingleRow(String table, String column, long value) {
        List<Map<String, Object>> rows = queryRowsByColumn(table, column, value);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String columnName = meta.getColumnName(i);
            Object value = rs.getObject(i);
            row.put(columnName, toJsonSafe(value));
        }
        return row;
    }

    /** 将 JDBC 值转换为可 JSON 序列化的基本类型（时间戳 → "ts:毫秒" 标签，便于还原类型） */
    private Object toJsonSafe(Object value) {
        if (value instanceof Date) {
            return "ts:" + ((Date) value).getTime();
        }
        if (value instanceof byte[]) {
            return java.util.Base64.getEncoder().encodeToString((byte[]) value);
        }
        return value;
    }

    // ==================== 还原（导入）====================

    /** 用玩家数据 JSON 还原该玩家（管理员接口；自我恢复受 self-restore 开关控制） */
    public Map<String, Object> restorePlayer(long playerId, String jsonData) {
        return restorePlayer(playerId, jsonData, false);
    }

    /**
     * 用玩家数据 JSON 还原该玩家。
     *
     * @param force true = 跳过"玩家在线"保护（仅在管理员明确知道后果时使用）
     */
    public Map<String, Object> restorePlayer(long playerId, String jsonData, boolean force) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!ENABLED) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h9573c3bc"));
            return result;
        }
        PlayerData data;
        try {
            data = GSON.fromJson(jsonData, PlayerData.class);
        } catch (Exception e) {
            recordRestoreFailure("解析玩家 " + playerId + " 数据失败: " + e.getMessage());
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h16464a5c", e.getMessage()));
            return result;
        }
        if (data == null || data.tables == null || data.tables.isEmpty()) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.haaee0960"));
            return result;
        }
        if (data.playerId != playerId) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.ha165961c", data.playerId, playerId));
            return result;
        }

        // 完整性校验：老版本导出的文件没有 checksum，只做行数粗校验；
        // 新版本有摘要就严格比对，文件被截断/改坏会在这里被挡住，不会灌回数据库。
        if (data.checksum != null) {
            String actual = computeChecksum(data);
            if (!data.checksum.equals(actual)) {
                recordRestoreFailure("玩家 " + playerId + " 数据文件校验失败：摘要不匹配");
                result.put("success", false);
                result.put("message", I18n.t("playerdata.restore_checksum_mismatch"));
                return result;
            }
            int rows = countRows(data);
            if (data.rowCount > 0 && rows != data.rowCount) {
                recordRestoreFailure("玩家 " + playerId + " 数据文件校验失败：行数不一致");
                result.put("success", false);
                result.put("message", I18n.t("playerdata.restore_checksum_mismatch"));
                return result;
            }
        }

        // 在线保护：正在玩的角色被回滚会直接覆盖内存里/正在写入的进度，
        // 而且玩家本人毫无察觉。默认拒绝，要求先下线。
        if (!force && isPlayerOnline(data.userId)) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.restore_online"));
            return result;
        }

        // 标识符校验：表名/列名来自这份 JSON，而它们要拼进 SQL 文本（无法参数化）。
        // 放在事务之前 —— 宁可整份文件拒收，也不要在事务中途抛异常回滚一堆已执行的 DELETE。
        String invalidStructure = validateTableStructure(data.tables);
        if (invalidStructure != null) {
            recordRestoreFailure("玩家 " + playerId + " 数据文件结构非法: " + invalidStructure);
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h0a551337", invalidStructure));
            return result;
        }

        int restored = 0;
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. 还原基础玩家行（UPDATE）
                List<Map<String, Object>> playerRows = data.tables.get("players");
                if (playerRows != null && !playerRows.isEmpty()) {
                    restorePlayerRow(conn, playerRows.get(0));
                    restored++;
                }
                // 2. 清空并重建其他个人表
                for (Map.Entry<String, List<Map<String, Object>>> entry : data.tables.entrySet()) {
                    String table = entry.getKey();
                    if ("players".equals(table)) {
                        continue;
                    }
                    if ("friends".equals(table)) {
                        deleteByColumnOr(conn, "friends", "player_id", "friend_player_id", playerId);
                    } else if ("sect_members".equals(table)) {
                        // 宗门成员不做删除重建，避免破坏宗门关系（仅提示）
                        continue;
                    } else {
                        deleteByColumn(conn, table, playerColumn(table), playerId);
                    }
                    for (Map<String, Object> row : entry.getValue()) {
                        if (insertRow(conn, table, row)) {
                            restored++;
                        }
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                recordRestoreFailure("玩家 " + playerId + " 数据还原失败: " + e.getMessage());
                result.put("success", false);
                result.put("message", I18n.t("playerdata.h30cace6c", e.getMessage()));
                return result;
            }
        } catch (SQLException e) {
            recordRestoreFailure("玩家 " + playerId + " 数据还原失败: " + e.getMessage());
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h0a551337", e.getMessage()));
            return result;
        }

        RESTORE_OK.incrementAndGet();
        result.put("success", true);
        result.put("restored", restored);
        result.put("message", I18n.t("playerdata.ha130304a", playerId, restored));
        return result;
    }

    private static void recordRestoreFailure(String message) {
        RESTORE_FAIL.incrementAndGet();
        lastError = message;
        lastErrorAt = System.currentTimeMillis();
        LOG.error(message);
    }

    /**
     * 该账号当前是否在线。
     *
     * <p>用 userId 判断而不是 playerId：WebSocket 会话是按账号建的，
     * 一个账号可能同时挂在多个通道上（WebSocket + QQ + MC），
     * 只要任一通道在线就说明这个角色正在被写。</p>
     */
    private static boolean isPlayerOnline(long userId) {
        try {
            com.mtxgdn.websocket.GameWebSocketApp app = com.mtxgdn.Main.gameWebSocketApp;
            return app != null && userId > 0 && app.isUserOnline(userId);
        } catch (Throwable t) {
            // 判断不了就当作"不在线"，避免因为一个探测异常把恢复功能彻底堵死
            return false;
        }
    }

    /** 从文件还原（管理员：把某个玩家的数据文件写回数据库） */
    public Map<String, Object> restoreFromFile(long playerId) {
        return restoreFromFile(playerId, false, false);
    }

    /**
     * 从文件还原。
     *
     * @param useBackup true = 改用 {@code <id>.json.bak}（当前快照损坏时的退路）
     * @param force     true = 跳过在线保护
     */
    public Map<String, Object> restoreFromFile(long playerId, boolean useBackup, boolean force) {
        Map<String, Object> result = new LinkedHashMap<>();
        Path file = DATA_DIR.resolve(playerId + (useBackup ? ".json.bak" : ".json"));
        if (!Files.exists(file)) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h6fec2084", file.toAbsolutePath()));
            return result;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return restorePlayer(playerId, json, force);
        } catch (IOException e) {
            recordRestoreFailure("读取玩家 " + playerId + " 数据文件失败: " + e.getMessage());
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h092cb197", e.getMessage()));
            return result;
        }
    }

    /**
     * 校验某个玩家的数据文件是否完整（不写库）。供管理后台"体检"使用。
     */
    public Map<String, Object> verifyFile(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Path file = DATA_DIR.resolve(playerId + ".json");
        if (!Files.exists(file)) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h6fec2084", file.toAbsolutePath()));
            return result;
        }
        try {
            PlayerData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PlayerData.class);
            if (data == null || data.tables == null) {
                result.put("success", false);
                result.put("message", I18n.t("playerdata.restore_checksum_mismatch"));
                return result;
            }
            if (data.checksum == null) {
                result.put("success", true);
                result.put("legacy", true);
                result.put("rowCount", countRows(data));
                result.put("message", I18n.t("playerdata.verify_legacy"));
                return result;
            }
            boolean ok = data.checksum.equals(computeChecksum(data));
            result.put("success", ok);
            result.put("legacy", false);
            result.put("rowCount", countRows(data));
            result.put("checksum", data.checksum);
            result.put("message", ok ? I18n.t("playerdata.verify_ok") : I18n.t("playerdata.restore_checksum_mismatch"));
            return result;
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h092cb197", e.getMessage()));
            return result;
        }
    }

    /**
     * 还原前校验数据文件的"结构"，也就是表名与列名。
     *
     * <p><b>为什么必须校验</b>：备份文件是外部输入（备份目录里的 JSON、或管理后台上传），
     * 而 {@code insertRow} / {@code deleteByColumn} / {@code restorePlayerRow} 都要把
     * 表名和列名拼进 SQL 文本 —— 标识符天生无法用 {@code ?} 参数化。
     * 也就是说，一个被篡改或损坏的文件里只要把表名写成
     * {@code players; DROP TABLE users; --}、或把列名写成 {@code hp = 999, mp},
     * 就会被原样拼进 SQL 执行。数据值本身一直走占位符，是安全的；漏掉的是标识符。</p>
     *
     * <p>校验放在事务之前：宁可整份文件拒收并明确报错，也不要在事务中途抛异常
     * 回滚一堆已经执行过的 DELETE。</p>
     *
     * @return 校验失败时返回可读原因；通过则返回 {@code null}
     */
    private static String validateTableStructure(Map<String, List<Map<String, Object>>> tables) {
        for (Map.Entry<String, List<Map<String, Object>>> entry : tables.entrySet()) {
            try {
                DatabaseManager.assertSafeTableName(entry.getKey());
            } catch (IllegalArgumentException e) {
                return "表名非法: " + e.getMessage();
            }
            List<Map<String, Object>> rows = entry.getValue();
            if (rows == null) {
                continue;
            }
            for (Map<String, Object> row : rows) {
                if (row == null) {
                    continue;
                }
                for (String column : row.keySet()) {
                    try {
                        DatabaseManager.assertSafeColumnName(column);
                    } catch (IllegalArgumentException e) {
                        return "表 " + entry.getKey() + " 的列名非法: " + e.getMessage();
                    }
                }
            }
        }
        return null;
    }

    private void restorePlayerRow(Connection conn, Map<String, Object> row) throws SQLException {
        Long id = ((Number) row.get("id")).longValue();
        StringBuilder sql = new StringBuilder("UPDATE players SET ");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            // user_id 关联账号所有权，还原时禁止覆盖，防止数据文件把玩家挂到他人账号
            if ("id".equals(entry.getKey()) || "user_id".equals(entry.getKey())
                    || "created_at".equals(entry.getKey()) || "updated_at".equals(entry.getKey())) {
                continue;
            }
            // 列名要拼进 SQL 文本，必须逐个校验（值走占位符，列名没有占位符可用）
            DatabaseManager.assertSafeColumnName(entry.getKey());
            if (!first) {
                sql.append(", ");
            }
            sql.append(entry.getKey()).append(" = ?");
            params.add(restoreValue(entry.getValue()));
            first = false;
        }
        sql.append(" WHERE id = ?");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.setLong(params.size() + 1, id);
            ps.executeUpdate();
        }
    }

    private boolean insertRow(Connection conn, String table, Map<String, Object> row) {
        // 纵深防御：即便上游漏了结构校验，这里也不允许把非法标识符拼进 SQL
        DatabaseManager.assertSafeTableName(table);
        StringBuilder sql = new StringBuilder("INSERT INTO " + table + " (");
        StringBuilder values = new StringBuilder("VALUES (");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            DatabaseManager.assertSafeColumnName(entry.getKey());
            if (!first) {
                sql.append(", ");
                values.append(", ");
            }
            sql.append(entry.getKey());
            values.append("?");
            params.add(restoreValue(entry.getValue()));
            first = false;
        }
        sql.append(") ").append(values).append(")");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOG.warn("还原行失败 [" + table + "]: " + e.getMessage());
            return false;
        }
    }

    /** 还原值时，"ts:" 标签的字符串还原为 Timestamp（兼容 MySQL DATETIME 列），其余原样 */
    private Object restoreValue(Object value) {
        if (value instanceof String && ((String) value).startsWith("ts:")) {
            try {
                return new java.sql.Timestamp(Long.parseLong(((String) value).substring(3)));
            } catch (NumberFormatException e) {
                return value;
            }
        }
        // JSON 数字统一为 Double，整数值还原为 Long，避免写入 DB 变成浮点
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return d.longValue();
        }
        return value;
    }

    private void deleteByColumn(Connection conn, String table, String column, long value) throws SQLException {
        DatabaseManager.assertSafeTableName(table);
        DatabaseManager.assertSafeColumnName(column);
        String sql = "DELETE FROM " + table + " WHERE " + column + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.executeUpdate();
        }
    }

    private void deleteByColumnOr(Connection conn, String table, String colA, String colB, long value) throws SQLException {
        DatabaseManager.assertSafeTableName(table);
        DatabaseManager.assertSafeColumnName(colA);
        DatabaseManager.assertSafeColumnName(colB);
        String sql = "DELETE FROM " + table + " WHERE " + colA + " = ? OR " + colB + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.setLong(2, value);
            ps.executeUpdate();
        }
    }
}
