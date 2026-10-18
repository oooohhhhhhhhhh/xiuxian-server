package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.entity.Player;
import com.mtxgdn.common.service.ServiceRegistry;

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
import com.mtxgdn.i18n.I18n;

public class FriendService {

    private final PlayerService playerService;

    public FriendService() {
        this.playerService = ServiceRegistry.getPlayerService();
    }

    public Friend sendRequest(long playerId, long targetPlayerId) {
        if (playerId == targetPlayerId) {
            Friend result = new Friend();
            result.setStatus("self");
            return result;
        }

        Player target = playerService.getPlayerById(targetPlayerId);
        if (target == null) {
            return null;
        }

        String existingStatus = checkExistingRelationship(playerId, targetPlayerId);
        if (existingStatus != null) {
            Friend result = new Friend();
            result.setStatus(existingStatus);
            return result;
        }

        String sql = "INSERT INTO friends (player_id, friend_player_id, status) VALUES (?, ?, 'pending')";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, targetPlayerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("发送好友申请失败", e);
        }

        Friend friend = new Friend();
        friend.setPlayerId(playerId);
        friend.setFriendPlayerId(targetPlayerId);
        friend.setFriendName(target.getName());
        friend.setFriendRealm(getRealmName(target.getRealm()));
        friend.setStatus("pending");
        return friend;
    }

    public boolean acceptRequest(long playerId, long requesterPlayerId) {
        String sql = "UPDATE friends SET status = 'accepted', updated_at = CURRENT_TIMESTAMP WHERE player_id = ? AND friend_player_id = ? AND status = 'pending'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, requesterPlayerId);
            ps.setLong(2, playerId);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                return false;
            }
            insertReverseRelationship(conn, playerId, requesterPlayerId);
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("接受好友申请失败", e);
        }
    }

    public boolean rejectRequest(long playerId, long requesterPlayerId) {
        String sql = "DELETE FROM friends WHERE player_id = ? AND friend_player_id = ? AND status = 'pending'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, requesterPlayerId);
            ps.setLong(2, playerId);
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            throw new RuntimeException("拒绝好友申请失败", e);
        }
    }

    public boolean removeFriend(long playerId, long friendPlayerId) {
        String sql = "DELETE FROM friends WHERE (player_id = ? AND friend_player_id = ?) OR (player_id = ? AND friend_player_id = ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, friendPlayerId);
            ps.setLong(3, friendPlayerId);
            ps.setLong(4, playerId);
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            throw new RuntimeException("删除好友失败", e);
        }
    }

    public List<Friend> getFriends(long playerId) {
        String sql = "SELECT f.id, f.player_id, f.friend_player_id, f.status, f.created_at, f.updated_at FROM friends f " +
                "WHERE ((f.player_id = ?) OR (f.friend_player_id = ? AND f.status = 'accepted')) AND f.status = 'accepted'";
        List<Friend> friends = new ArrayList<>();
        Set<Long> seenFriendIds = new HashSet<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Friend f = createFriendFromResultSet(rs);

                    long friendId = (f.getPlayerId() == playerId) ? f.getFriendPlayerId() : f.getPlayerId();
                    if (seenFriendIds.contains(friendId)) {
                        continue;
                    }
                    seenFriendIds.add(friendId);

                    populateFriendPlayerInfo(f, friendId);
                    f.setFriendPlayerId(friendId);
                    friends.add(f);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取好友列表失败", e);
        }
        return friends;
    }

    public List<Friend> getPendingRequests(long playerId) {
        String sql = "SELECT f.id, f.player_id, f.friend_player_id, f.status, f.created_at FROM friends f " +
                "WHERE f.friend_player_id = ? AND f.status = 'pending'";
        List<Friend> requests = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Friend f = createFriendFromResultSet(rs);
                    populateFriendPlayerInfo(f, f.getPlayerId());
                    requests.add(f);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取好友申请失败", e);
        }
        return requests;
    }

    public boolean areFriends(long playerId, long otherPlayerId) {
        String existingStatus = checkExistingRelationship(playerId, otherPlayerId);
        return "accepted".equals(existingStatus);
    }

    // ==================== 好友拜访（每日互动） ====================

    /** 拜访记录：playerId -> 今日已拜访的好友 id 集合（内存态，跨天自动清空） */
    private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.Set<Long>> DAILY_VISITS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String visitDate = java.time.LocalDate.now().toString();

    private static void ensureVisitDate() {
        String today = java.time.LocalDate.now().toString();
        if (!today.equals(visitDate)) {
            DAILY_VISITS.clear();
            visitDate = today;
        }
    }

    /**
     * 拜访好友：每日每位好友限一次，双方各获得少量灵力与灵石（好友系统的实际互动玩法）。
     */
    public Map<String, Object> visitFriend(long playerId, long friendPlayerId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (playerId == friendPlayerId) {
            result.put("success", false);
            result.put("message", I18n.t("friend.hc7efe258"));
            return result;
        }
        if (!areFriends(playerId, friendPlayerId)) {
            result.put("success", false);
            result.put("message", I18n.t("friend.hc0ed9cb8"));
            return result;
        }
        ensureVisitDate();
        java.util.Set<Long> visited = DAILY_VISITS.computeIfAbsent(playerId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (!visited.add(friendPlayerId)) {
            result.put("success", false);
            result.put("message", I18n.t("friend.hd893e16c"));
            return result;
        }

        Player me = playerService.getPlayerById(playerId);
        Player friend = playerService.getPlayerById(friendPlayerId);
        if (me == null || friend == null) {
            result.put("success", false);
            result.put("message", I18n.t("friend.h5fca123a"));
            return result;
        }

        // 双方奖励：经验 (境界+1)×30，灵石 (境界+1)×5（仅拜访者主动消耗，好友被动受益）
        long myExp = (me.getRealm() + 1L) * 30L;
        long friendExp = (friend.getRealm() + 1L) * 30L;
        long myStones = (me.getRealm() + 1L) * 5L;
        long friendStones = (friend.getRealm() + 1L) * 5L;

        playerService.addExperience(playerId, myExp);
        com.mtxgdn.game.service.ItemService itemService = ServiceRegistry.getItemService();
        itemService.addSpiritStones(playerId, myStones);
        playerService.addExperience(friendPlayerId, friendExp);
        itemService.addSpiritStones(friendPlayerId, friendStones);

        result.put("success", true);
        result.put("message", I18n.t("friend.hbcde7c40", friend.getName(), myExp, myStones, friend.getName(), friendExp, friendStones));
        return result;
    }

    private String checkExistingRelationship(long playerId, long targetPlayerId) {
        String sql = "SELECT status FROM friends WHERE (player_id = ? AND friend_player_id = ?) OR (player_id = ? AND friend_player_id = ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, targetPlayerId);
            ps.setLong(3, targetPlayerId);
            ps.setLong(4, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("status");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("检查好友关系失败", e);
        }
        return null;
    }

    private void insertReverseRelationship(Connection conn, long playerId, long friendPlayerId) throws SQLException {
        String reverseSql;
        if (DatabaseManager.isSqlite()) {
            reverseSql = "INSERT OR REPLACE INTO friends (player_id, friend_player_id, status) VALUES (?, ?, 'accepted')";
        } else {
            reverseSql = "INSERT INTO friends (player_id, friend_player_id, status) VALUES (?, ?, 'accepted') ON DUPLICATE KEY UPDATE status = 'accepted'";
        }
        try (PreparedStatement ps = conn.prepareStatement(reverseSql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, friendPlayerId);
            ps.executeUpdate();
        }
    }

    private Friend createFriendFromResultSet(ResultSet rs) throws SQLException {
        Friend f = new Friend();
        f.setId(rs.getLong("id"));
        f.setPlayerId(rs.getLong("player_id"));
        f.setFriendPlayerId(rs.getLong("friend_player_id"));
        f.setStatus(rs.getString("status"));
        if (rs.getMetaData().getColumnCount() > 4) {
            f.setCreatedAt(rs.getString("created_at"));
        }
        if (rs.getMetaData().getColumnCount() > 5) {
            f.setUpdatedAt(rs.getString("updated_at"));
        }
        return f;
    }

    private void populateFriendPlayerInfo(Friend f, long friendPlayerId) {
        Player friendPlayer = playerService.getPlayerById(friendPlayerId);
        if (friendPlayer != null) {
            f.setFriendName(friendPlayer.getName());
            f.setFriendRealm(getRealmName(friendPlayer.getRealm()));
        }
    }

    private String getRealmName(int realm) {
        RealmConfig config = GameConfigLoader.getRealmConfig(realm, 0);
        return config != null ? config.getFullName() : "未知";
    }
}