package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class FriendService {

    private final PlayerService playerService;

    public FriendService() {
        this.playerService = new PlayerService();
    }

    public Friend sendRequest(long playerId, long targetPlayerId) {
        Player target = playerService.getPlayerById(targetPlayerId);
        if (target == null) {
            return null;
        }

        String existingSql = "SELECT id FROM friends WHERE (player_id = ? AND friend_player_id = ?) OR (player_id = ? AND friend_player_id = ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(existingSql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, targetPlayerId);
            ps.setLong(3, targetPlayerId);
            ps.setLong(4, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Friend existing = new Friend();
                    existing.setId(rs.getLong("id"));
                    existing.setStatus("exists");
                    return existing;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("检查好友关系失败", e);
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
            String reverseSql = "INSERT INTO friends (player_id, friend_player_id, status) VALUES (?, ?, 'accepted') ON DUPLICATE KEY UPDATE status = 'accepted'";
            if (DatabaseManager.isSqlite()) {
                reverseSql = "INSERT OR REPLACE INTO friends (player_id, friend_player_id, status) VALUES (?, ?, 'accepted')";
            }
            try (PreparedStatement ps2 = conn.prepareStatement(reverseSql)) {
                ps2.setLong(1, playerId);
                ps2.setLong(2, requesterPlayerId);
                ps2.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("接受好友申请失败", e);
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
        java.util.Set<Long> seenFriendIds = new java.util.HashSet<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Friend f = new Friend();
                    f.setId(rs.getLong("id"));
                    f.setPlayerId(rs.getLong("player_id"));
                    f.setFriendPlayerId(rs.getLong("friend_player_id"));
                    f.setStatus(rs.getString("status"));
                    f.setCreatedAt(rs.getString("created_at"));
                    f.setUpdatedAt(rs.getString("updated_at"));

                    long friendId = (f.getPlayerId() == playerId) ? f.getFriendPlayerId() : f.getPlayerId();
                    if (seenFriendIds.contains(friendId)) {
                        continue;
                    }
                    seenFriendIds.add(friendId);

                    Player friendPlayer = playerService.getPlayerById(friendId);
                    if (friendPlayer != null) {
                        f.setFriendName(friendPlayer.getName());
                        f.setFriendRealm(getRealmName(friendPlayer.getRealm()));
                    }
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
                    Friend f = new Friend();
                    f.setId(rs.getLong("id"));
                    f.setPlayerId(rs.getLong("player_id"));
                    f.setFriendPlayerId(rs.getLong("friend_player_id"));
                    f.setStatus(rs.getString("status"));
                    f.setCreatedAt(rs.getString("created_at"));

                    Player requester = playerService.getPlayerById(f.getPlayerId());
                    if (requester != null) {
                        f.setFriendName(requester.getName());
                        f.setFriendRealm(getRealmName(requester.getRealm()));
                    }
                    requests.add(f);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取好友申请失败", e);
        }
        return requests;
    }

    private String getRealmName(int realm) {
        RealmConfig config = GameConfigLoader.getRealmConfig(realm, 0);
        return config != null ? config.getFullName() : "未知";
    }
}
