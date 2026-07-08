package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 游戏地图服务 —— 管理地点、路线和玩家移动
 */
public class MapService {

    private static final long TRAVEL_COOLDOWN_SECONDS = 30;
    private static final long DEFAULT_LOCATION_ID = 1; // 青云村

    private final Map<Long, MapLocation> locationCache = new LinkedHashMap<>();
    private final List<long[]> connections = new ArrayList<>();
    private boolean initialized = false;

    /**
     * 初始化地图数据 —— 首次访问时从数据库加载，新安装则插入默认数据
     */
    public synchronized void ensureInitialized() {
        if (initialized) return;
        loadFromDb();
        initialized = true;
    }

    private void loadFromDb() {
        try (Connection conn = DatabaseManager.getConnection()) {
            // 检查是否有数据
            var stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM map_locations");
            rs.next();
            if (rs.getInt(1) == 0) {
                rs.close();
                stmt.close();
                insertDefaultData(conn);
            } else {
                rs.close();
            }

            // 加载地点
            String sql = "SELECT * FROM map_locations ORDER BY id";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong("id");
                    MapLocation loc = new MapLocation(
                            id, rows.getString("name"), rows.getString("description"),
                            rows.getString("region"), rows.getInt("min_realm"),
                            rows.getBoolean("is_safe_zone"));
                    locationCache.put(id, loc);
                }
            }

            // 加载连接
            String connSql = "SELECT * FROM map_connections";
            try (PreparedStatement ps = conn.prepareStatement(connSql);
                 ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    long from = rows.getLong("from_location_id");
                    long to = rows.getLong("to_location_id");
                    connections.add(new long[]{from, to});
                    // 双向关联
                    MapLocation a = locationCache.get(from);
                    MapLocation b = locationCache.get(to);
                    if (a != null && b != null) {
                        if (!a.getConnections().contains(b)) a.getConnections().add(b);
                        if (!b.getConnections().contains(a)) b.getConnections().add(a);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("加载地图数据失败", e);
        }
    }

    private void insertDefaultData(Connection conn) throws SQLException {
        // 10 个修仙世界地点
        Object[][] locs = {
                {1L, "青云村", "青山环绕的小村庄，灵气稀薄，是修仙者踏入仙途的第一站。村口的老槐树下，立着一块刻满道纹的石碑。", "新手区域", 0, true},
                {2L, "青云山", "青云村后山，山腰云雾缭绕，时有仙鹤飞过。传说山中藏有先贤洞府，只是从未有人寻得。", "新手区域", 0, false},
                {3L, "苍月镇", "坐落在苍月山脚下的小镇，因出产月华石而闻名。镇中坊市热闹非凡，往来的散修络绎不绝。", "新手区域", 1, true},
                {4L, "太虚城", "中原修仙第一大城，城墙高达百丈，城中央的太虚塔直入云霄。各大宗门在此设有驻地，坊市内珍品云集。", "中原", 2, true},
                {5L, "落霞山", "山势险峻，落日时分霞光万丈，故得名。山中妖兽横行，却也是筑基丹主材「落霞草」的唯一产地。", "中原", 2, false},
                {6L, "碧水潭", "一汪深不见底的碧色潭水，潭底据说连通东海龙宫。潭边灵气充沛，常有金丹修士在此打坐修炼。", "中原", 3, false},
                {7L, "昆仑墟", "上古神山昆仑的遗迹，山体早已崩碎，只剩下悬浮在虚空中的残垣断壁。唯有元婴期以上修士方能踏足此地。", "西域", 4, false},
                {8L, "九幽渊", "深不见底的黑暗深渊，传闻是九幽之地的入口。渊中阴气弥漫，滋生无数邪祟妖物，化神修士亦不敢轻入。", "南荒", 5, false},
                {9L, "天元古城", "远古修仙文明的遗迹，城中遍布上古禁制与阵法遗迹。合体期修士在此寻宝，一步不慎便可能触发禁制。", "远古遗迹", 6, false},
                {10L, "问道峰", "修仙界的至高圣地。峰顶立有历代飞升者留下的问道碑，传说登顶者可感悟天道，破境飞升。", "圣地", 7, false},
        };

        String sql = "INSERT INTO map_locations (id, name, description, region, min_realm, is_safe_zone) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] loc : locs) {
                ps.setLong(1, (Long) loc[0]);
                ps.setString(2, (String) loc[1]);
                ps.setString(3, (String) loc[2]);
                ps.setString(4, (String) loc[3]);
                ps.setInt(5, (Integer) loc[4]);
                ps.setBoolean(6, (Boolean) loc[5]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // 连接路线
        long[][] connPairs = {
                {1, 2}, {2, 3}, {3, 4}, {4, 5}, {4, 6},
                {5, 7}, {6, 8}, {7, 9}, {9, 10}
        };
        String connSql = "INSERT INTO map_connections (from_location_id, to_location_id, travel_time_seconds) VALUES (?, ?, 5)";
        try (PreparedStatement ps = conn.prepareStatement(connSql)) {
            for (long[] pair : connPairs) {
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

    // ==================== 查询 ====================

    public MapLocation getLocation(long id) {
        ensureInitialized();
        return locationCache.get(id);
    }

    public MapLocation getLocationByName(String name) {
        ensureInitialized();
        return locationCache.values().stream()
                .filter(l -> l.getName().equals(name))
                .findFirst().orElse(null);
    }

    public List<MapLocation> getAllLocations() {
        ensureInitialized();
        return new ArrayList<>(locationCache.values());
    }

    public List<MapLocation> getLocationsByRegion(String region) {
        ensureInitialized();
        return locationCache.values().stream()
                .filter(l -> l.getRegion().equals(region))
                .toList();
    }

    /**
     * 获取玩家的当前位置
     */
    public long getPlayerLocationId(long playerId) {
        String sql = "SELECT current_location_id FROM players WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long locId = rs.getLong("current_location_id");
                    return rs.wasNull() ? DEFAULT_LOCATION_ID : locId;
                }
            }
        } catch (SQLException e) { throw new RuntimeException("获取玩家位置失败", e); }
        return DEFAULT_LOCATION_ID;
    }

    /**
     * 获取玩家上次移动时间
     */
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
        Map<String, Object> result = new LinkedHashMap<>();
        ensureInitialized();

        MapLocation target = locationCache.get(targetLocationId);
        if (target == null) {
            result.put("success", false);
            result.put("message", "该地点不存在"); return result;
        }

        PlayerService ps = new PlayerService();
        Player player = ps.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "玩家不存在"); return result;
        }

        if (player.getRealm() < target.getMinRealm()) {
            String realmName = GameConfigLoader.getRealmConfig(player.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(player.getRealm(), 0).getFullName() : "凡人";
            String requiredName = GameConfigLoader.getRealmConfig(target.getMinRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(target.getMinRealm(), 0).getFullName() : "未知";
            result.put("success", false);
            result.put("message", "境界不足，" + target.getName() + "需要" + requiredName + "以上方可进入（当前：" + realmName + "）");
            return result;
        }

        long currentLocId = getPlayerLocationId(playerId);
        if (currentLocId == targetLocationId) {
            result.put("success", false);
            result.put("message", "你已经在" + target.getName() + "了"); return result;
        }

        MapLocation current = locationCache.get(currentLocId);
        if (current == null) {
            result.put("success", false);
            result.put("message", "当前位置异常，请联系管理员"); return result;
        }

        // 检查是否相邻
        boolean connected = current.getConnections().stream().anyMatch(c -> c.getId() == targetLocationId);
        if (!connected) {
            List<String> neighborNames = current.getConnections().stream().map(MapLocation::getName).toList();
            result.put("success", false);
            result.put("message", "无法直接前往「" + target.getName() + "」，你只能从" + current.getName() + "前往：" + String.join("、", neighborNames));
            return result;
        }

        // 检查冷却
        long lastTravel = getPlayerLastTravelTime(playerId);
        long now = System.currentTimeMillis() / 1000;
        long elapsed = now - lastTravel;
        if (elapsed < TRAVEL_COOLDOWN_SECONDS) {
            long remaining = TRAVEL_COOLDOWN_SECONDS - elapsed;
            result.put("success", false);
            result.put("message", "你需要休息片刻才能继续赶路（剩余 " + remaining + " 秒）");
            return result;
        }

        // 执行移动
        String sql = "UPDATE players SET current_location_id = ?, last_travel_time = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, targetLocationId);
            pstmt.setLong(2, now);
            pstmt.setLong(3, playerId);
            pstmt.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("移动失败", e); }

        result.put("success", true);
        result.put("message", player.getName() + "离开了" + current.getName() + "，前往「" + target.getName() + "」。" + target.getDescription());
        result.put("from", current.getName());
        result.put("to", target.getName());
        result.put("toRegion", target.getRegion());
        result.put("safeZone", target.isSafeZone());
        return result;
    }

    /**
     * 查看当前位置及周围
     */
    public Map<String, Object> getPlayerSurroundings(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        ensureInitialized();

        long locId = getPlayerLocationId(playerId);
        MapLocation current = locationCache.get(locId);
        if (current == null) {
            current = locationCache.get(DEFAULT_LOCATION_ID);
        }

        result.put("current", formatLocationBrief(current));

        List<Map<String, Object>> neighbors = new ArrayList<>();
        for (MapLocation nb : current.getConnections()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", nb.getId());
            info.put("name", nb.getName());
            info.put("region", nb.getRegion());
            info.put("safeZone", nb.isSafeZone());
            // 标注境界是否满足
            PlayerService ps = new PlayerService();
            Player player = ps.getPlayerById(playerId);
            info.put("accessible", player != null && player.getRealm() >= nb.getMinRealm());
            neighbors.add(info);
        }
        result.put("neighbors", neighbors);
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
                    long locId = rs.getLong("current_location_id");
                    if (rs.wasNull()) locId = DEFAULT_LOCATION_ID;
                    MapLocation loc = locationCache.get(locId);
                    result.put(pid, loc != null ? loc.getName() : "未知");
                }
            }
        } catch (SQLException e) { throw new RuntimeException("获取玩家位置失败", e); }
        return result;
    }
}
