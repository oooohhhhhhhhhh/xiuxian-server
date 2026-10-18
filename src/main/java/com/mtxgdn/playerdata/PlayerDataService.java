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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
        if (!ENABLED || dirtyPlayers.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(dirtyPlayers);
        dirtyPlayers.clear();
        PlayerDataService service = new PlayerDataService();
        for (long id : ids) {
            try {
                service.exportPlayer(id);
            } catch (Exception e) {
                dirtyPlayers.add(id);
                LOG.warn("玩家 " + id + " 数据落盘失败，稍后重试: " + e.getMessage());
            }
        }
    }

    public static void shutdown() {
        scheduler.shutdown();
        flushDirty();
    }

    // ==================== 导出 ====================

    /** 将指定玩家的全部数据导出到其个人文件（data/players/{playerId}.json） */
    public Map<String, Object> exportPlayer(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!ENABLED) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.h9573c3bc"));
            return result;
        }

        PlayerData data = collectPlayerData(playerId);
        if (data == null) {
            result.put("success", false);
            result.put("message", "玩家不存在: " + playerId);
            return result;
        }

        try {
            Files.createDirectories(DATA_DIR);
            Path target = DATA_DIR.resolve(playerId + ".json");
            Path tmp = DATA_DIR.resolve(playerId + ".json.tmp");
            Files.write(tmp, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "写入玩家数据文件失败: " + e.getMessage());
            return result;
        }

        result.put("success", true);
        result.put("message", "玩家 " + data.playerName + "（#" + playerId + "）数据已导出至 " + filePath(playerId));
        result.put("filePath", filePath(playerId));
        result.put("exportedAt", data.exportedAt);
        return result;
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
            LOG.warn("读取表 " + table + " 失败: " + e.getMessage());
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
            LOG.warn("读取表 " + table + " 失败: " + e.getMessage());
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
            result.put("success", false);
            result.put("message", "玩家数据 JSON 解析失败: " + e.getMessage());
            return result;
        }
        if (data == null || data.tables == null || data.tables.isEmpty()) {
            result.put("success", false);
            result.put("message", I18n.t("playerdata.haaee0960"));
            return result;
        }
        if (data.playerId != playerId) {
            result.put("success", false);
            result.put("message", "数据文件中的玩家 #" + data.playerId + " 与当前玩家 #" + playerId + " 不一致，拒绝还原");
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
                result.put("success", false);
                result.put("message", "还原失败，已回滚: " + e.getMessage());
                return result;
            }
        } catch (SQLException e) {
            result.put("success", false);
            result.put("message", "还原失败: " + e.getMessage());
            return result;
        }

        result.put("success", true);
        result.put("message", "玩家 #" + playerId + " 数据还原成功，共恢复 " + restored + " 行数据");
        return result;
    }

    /** 从文件还原（管理员：把某个玩家的数据文件写回数据库） */
    public Map<String, Object> restoreFromFile(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Path file = DATA_DIR.resolve(playerId + ".json");
        if (!Files.exists(file)) {
            result.put("success", false);
            result.put("message", "玩家数据文件不存在: " + file.toAbsolutePath());
            return result;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return restorePlayer(playerId, json);
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "读取玩家数据文件失败: " + e.getMessage());
            return result;
        }
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
        StringBuilder sql = new StringBuilder("INSERT INTO " + table + " (");
        StringBuilder values = new StringBuilder("VALUES (");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
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
        String sql = "DELETE FROM " + table + " WHERE " + column + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.executeUpdate();
        }
    }

    private void deleteByColumnOr(Connection conn, String table, String colA, String colB, long value) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE " + colA + " = ? OR " + colB + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.setLong(2, value);
            ps.executeUpdate();
        }
    }
}
