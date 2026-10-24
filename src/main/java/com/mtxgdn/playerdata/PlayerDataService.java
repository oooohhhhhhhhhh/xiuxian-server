package com.mtxgdn.playerdata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.PlayerDataCacheDAO;
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

    /** 玩家快照的读写入口（SQL 全部在这里，本类只做编排与文件）。 */
    private final PlayerDataCacheDAO cacheDao = new PlayerDataCacheDAO();

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
            result.put("message", I18n.t("playerdata.file_based_player_data"));
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
            result.put("message", I18n.t("playerdata.player_not_found", playerId));
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
            result.put("message", I18n.t("playerdata.failed_to_write_the_player", e.getMessage()));
            return result;
        }

        EXPORT_OK.incrementAndGet();
        result.put("success", true);
        result.put("message", I18n.t("playerdata.data_for_player_has_been", data.playerName, playerId, filePath(playerId)));
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
        for (long id : cacheDao.listPlayerIds()) {
            try {
                exportPlayer(id);
                ok++;
            } catch (Exception e) {
                fail++;
                LOG.warn("导出玩家 " + id + " 失败: " + e.getMessage());
            }
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
        List<Map<String, Object>> playerRows = cacheDao.findRowsByColumn("players", "id", playerId);
        if (playerRows.isEmpty()) {
            return null;
        }
        Map<String, Object> playerRow = playerRows.get(0);
        data.playerId = playerId;
        data.userId = ((Number) playerRow.getOrDefault("user_id", 0L)).longValue();
        Object name = playerRow.get("name");
        data.playerName = name != null ? name.toString() : ("#" + playerId);
        data.exportedAt = System.currentTimeMillis();

        data.tables.put("players", playerRows);

        for (Map.Entry<String, String> entry : PLAYER_TABLES.entrySet()) {
            String table = entry.getKey();
            List<Map<String, Object>> rows = cacheDao.findRowsByColumn(table, entry.getValue(), playerId);
            if (!rows.isEmpty()) {
                data.tables.put(table, rows);
            }
        }

        // 好友：双向记录
        List<Map<String, Object>> friends =
                cacheDao.findRowsByColumnOr("friends", "player_id", "friend_player_id", playerId);
        if (!friends.isEmpty()) {
            data.tables.put("friends", friends);
        }

        // 宗门成员
        List<Map<String, Object>> sectMembers = cacheDao.findRowsByColumn("sect_members", "player_id", playerId);
        if (!sectMembers.isEmpty()) {
            data.tables.put("sect_members", sectMembers);
        }
        return data;
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
            result.put("message", I18n.t("playerdata.file_based_player_data"));
            return result;
        }
        PlayerData data;
        try {
            data = GSON.fromJson(jsonData, PlayerData.class);
        } catch (Exception e) {
            recordRestoreFailure("解析玩家 " + playerId + " 数据失败: " + e.getMessage());
            result.put("success", false);
            result.put("message", I18n.t("playerdata.failed_to_parse_the_player", e.getMessage()));
            return result;
        }
        if (data == null || data.tables == null || data.tables.isEmpty()) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.the_player_data_is_empty"));
            return result;
        }
        if (data.playerId != playerId) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.the_player_in_the_data", data.playerId, playerId));
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
            result.put("message", I18n.t("playerdata.restore_failed", invalidStructure));
            return result;
        }

        // 事务边界走 DatabaseManager.runTransaction：还原是"删旧行 → 逐行插回"的
        // 大事务，两步必须共用同一条连接。DAO 里凡是收 Connection 的方法都只做 SQL、
        // 不碰事务，所以这里由服务层持有事务。
        //
        // runTransaction 会把业务异常包成 RuntimeException("事务执行失败: " + 原因)，
        // 而还原失败要给玩家的文案必须是"真实原因"而不是这层包装，所以 catch 后解包。
        int[] restoredBox = {0};
        try {
            restoredBox[0] = DatabaseManager.runTransaction(conn -> {
                int restored = 0;
                // 1. 还原基础玩家行（UPDATE）
                List<Map<String, Object>> playerRows = data.tables.get("players");
                if (playerRows != null && !playerRows.isEmpty()) {
                    cacheDao.updatePlayerRow(conn, restoredRow(playerRows.get(0)));
                    restored++;
                }
                // 2. 清空并重建其他个人表
                for (Map.Entry<String, List<Map<String, Object>>> entry : data.tables.entrySet()) {
                    String table = entry.getKey();
                    if ("players".equals(table)) {
                        continue;
                    }
                    if ("friends".equals(table)) {
                        cacheDao.deleteByColumnOr(conn, "friends", "player_id", "friend_player_id", playerId);
                    } else if ("sect_members".equals(table)) {
                        // 宗门成员不做删除重建，避免破坏宗门关系（仅提示）
                        continue;
                    } else {
                        cacheDao.deleteByColumn(conn, table, playerColumn(table), playerId);
                    }
                    for (Map<String, Object> row : entry.getValue()) {
                        if (cacheDao.insertRow(conn, table, restoredRow(row))) {
                            restored++;
                        }
                    }
                }
                return restored;
            });
        } catch (RuntimeException e) {
            recordRestoreFailure("玩家 " + playerId + " 数据还原失败: " + rootMessage(e));
            result.put("success", false);
            result.put("message", I18n.t("playerdata.restore_failed_and_was_rolled", rootMessage(e)));
            return result;
        }
        int restored = restoredBox[0];

        RESTORE_OK.incrementAndGet();
        result.put("success", true);
        result.put("restored", restored);
        result.put("message", I18n.t("playerdata.player_data_restored_successfully", playerId, restored));
        return result;
    }

    private static void recordRestoreFailure(String message) {
        RESTORE_FAIL.incrementAndGet();
        lastError = message;
        lastErrorAt = System.currentTimeMillis();
        LOG.error(message);
    }

    /**
     * 解包 {@code DatabaseManager.runTransaction} 包装的 RuntimeException，
     * 返回业务异常的真实原因消息。
     *
     * <p>还原失败要给玩家的文案必须是"真实原因"（哪一行插不进去、哪个列非法），
     * 而不是 {@code "事务执行失败: "} 这层包装 —— 运维与玩家都靠这句话定位问题。</p>
     */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause instanceof RuntimeException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
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
            result.put("message", I18n.t("playerdata.the_player_data_file_does", file.toAbsolutePath()));
            return result;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return restorePlayer(playerId, json, force);
        } catch (IOException e) {
            recordRestoreFailure("读取玩家 " + playerId + " 数据文件失败: " + e.getMessage());
            result.put("success", false);
            result.put("message", I18n.t("playerdata.failed_to_read_the_player", e.getMessage()));
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
            result.put("message", I18n.t("playerdata.the_player_data_file_does", file.toAbsolutePath()));
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
            result.put("message", I18n.t("playerdata.failed_to_read_the_player", e.getMessage()));
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

    /**
     * 把一行的每个值都过一遍 {@link #restoreValue(Object)}。
     *
     * <p>键（列名）不动 —— 结构与标识符校验由 {@code validateTableStructure}
     * 与 DAO 内的纵深防御负责，这里只做"JSON 值 → JDBC 值"的转换。</p>
     */
    private Map<String, Object> restoredRow(Map<String, Object> row) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            converted.put(entry.getKey(), restoreValue(entry.getValue()));
        }
        return converted;
    }

    /**
     * 还原值时，"ts:" 标签的字符串还原为 Timestamp（兼容 MySQL DATETIME 列），其余原样。
     *
     * <p>与 {@code PlayerDataCacheDAO} 的 {@code toJsonSafe} 是一对编解码，
     * 两边必须成对修改。JSON 反序列化语义属于服务层，所以留在这里。</p>
     */
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
}
