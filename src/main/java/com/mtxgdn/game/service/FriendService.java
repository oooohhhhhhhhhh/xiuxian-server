package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.FriendDAO;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.entity.Player;
import com.mtxgdn.common.service.ServiceRegistry;

import java.sql.Connection;
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
    private static final FriendDAO FRIEND_DAO = new FriendDAO();

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

        FRIEND_DAO.insertRequest(playerId, targetPlayerId);

        Friend friend = new Friend();
        friend.setPlayerId(playerId);
        friend.setFriendPlayerId(targetPlayerId);
        friend.setFriendName(target.getName());
        friend.setFriendRealm(getRealmName(target.getRealm()));
        friend.setStatus("pending");
        return friend;
    }

    public boolean acceptRequest(long playerId, long requesterPlayerId) {
        // 两步必须共用同一条连接：改申请状态 + 补反向记录。
        // 若拆成两次自取连接，中间失败就会留下"申请已接受但没有反向记录"的脏数据，
        // 对方的好友列表里看不到你。
        try (Connection conn = DatabaseManager.getConnection()) {
            int affected = FRIEND_DAO.acceptRequest(conn, playerId, requesterPlayerId);
            if (affected == 0) {
                return false;
            }
            FRIEND_DAO.insertReverseRelationship(conn, playerId, requesterPlayerId);
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("接受好友申请失败", e);
        }
    }

    public boolean rejectRequest(long playerId, long requesterPlayerId) {
        return FRIEND_DAO.rejectRequest(playerId, requesterPlayerId);
    }

    public boolean removeFriend(long playerId, long friendPlayerId) {
        return FRIEND_DAO.removeFriend(playerId, friendPlayerId);
    }

    public List<Friend> getFriends(long playerId) {
        List<Friend> friends = new ArrayList<>();
        Set<Long> seenFriendIds = new HashSet<>();
        for (Friend f : FRIEND_DAO.findFriends(playerId)) {
            long friendId = (f.getPlayerId() == playerId) ? f.getFriendPlayerId() : f.getPlayerId();
            if (seenFriendIds.contains(friendId)) {
                continue;
            }
            seenFriendIds.add(friendId);

            populateFriendPlayerInfo(f, friendId);
            f.setFriendPlayerId(friendId);
            friends.add(f);
        }
        return friends;
    }

    public List<Friend> getPendingRequests(long playerId) {
        List<Friend> requests = new ArrayList<>();
        for (Friend f : FRIEND_DAO.findPendingRequests(playerId)) {
            populateFriendPlayerInfo(f, f.getPlayerId());
            requests.add(f);
        }
        return requests;
    }

    public boolean areFriends(long playerId, long otherPlayerId) {
        String existingStatus = checkExistingRelationship(playerId, otherPlayerId);
        return "accepted".equals(existingStatus);
    }

    /** 双向查一次关系状态（null 表示没关系）。 */
    private String checkExistingRelationship(long playerId, long targetPlayerId) {
        return FRIEND_DAO.checkExistingRelationship(playerId, targetPlayerId);
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
            result.put("message", I18n.t("friend.you_cannot_visit_yourself"));
            return result;
        }
        if (!areFriends(playerId, friendPlayerId)) {
            result.put("success", false);
            result.put("message", I18n.t("friend.you_are_not_friends_so"));
            return result;
        }
        ensureVisitDate();
        java.util.Set<Long> visited = DAILY_VISITS.computeIfAbsent(playerId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (!visited.add(friendPlayerId)) {
            result.put("success", false);
            result.put("message", I18n.t("friend.you_have_already_visited"));
            return result;
        }

        Player me = playerService.getPlayerById(playerId);
        Player friend = playerService.getPlayerById(friendPlayerId);
        if (me == null || friend == null) {
            result.put("success", false);
            result.put("message", I18n.t("friend.player_data_error"));
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
        result.put("message", I18n.t("friend.you_visited_and_discussed", friend.getName(), myExp, myStones, friend.getName(), friendExp, friendStones));
        return result;
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
