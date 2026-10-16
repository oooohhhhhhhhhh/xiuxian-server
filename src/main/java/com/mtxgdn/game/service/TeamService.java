package com.mtxgdn.game.service;

import com.mtxgdn.util.GameLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TeamService {

    private static final GameLogger LOG = GameLogger.getLogger(TeamService.class);

    private static TeamService instance;

    private final ConcurrentHashMap<Long, Team> teams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> playerTeamMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> pendingInvites = new ConcurrentHashMap<>();
    private final AtomicLong teamIdGenerator = new AtomicLong(1);

    private static final int MIN_TEAM_SIZE = 2;
    private static final int MAX_TEAM_SIZE = 5;

    private TeamService() {}

    public static synchronized TeamService getInstance() {
        if (instance == null) {
            instance = new TeamService();
            LOG.info("组队服务已初始化");
        }
        return instance;
    }

    public Team createTeam(long leaderId) {
        if (isInTeam(leaderId)) {
            LOG.debug("玩家 " + leaderId + " 已在队伍中，无法创建新队伍");
            return null;
        }

        long teamId = teamIdGenerator.getAndIncrement();
        Team team = new Team(teamId, leaderId);
        teams.put(teamId, team);
        playerTeamMap.put(leaderId, teamId);
        LOG.info("玩家 " + leaderId + " 创建了新队伍 " + teamId);
        return team;
    }

    public boolean invitePlayer(long inviterId, long targetId) {
        Long teamId = playerTeamMap.get(inviterId);
        if (teamId == null) {
            LOG.debug("玩家 " + inviterId + " 不在队伍中，无法邀请");
            return false;
        }

        Team team = teams.get(teamId);
        if (team == null) {
            LOG.warn("队伍 " + teamId + " 不存在");
            playerTeamMap.remove(inviterId);
            return false;
        }

        if (team.getLeaderId() != inviterId) {
            LOG.debug("玩家 " + inviterId + " 不是队长，无法邀请");
            return false;
        }

        if (team.getMemberCount() >= MAX_TEAM_SIZE) {
            LOG.debug("队伍 " + teamId + " 已满，无法邀请");
            return false;
        }

        if (isInTeam(targetId)) {
            LOG.debug("目标玩家 " + targetId + " 已在队伍中");
            return false;
        }

        pendingInvites.put(targetId, teamId);
        LOG.info("玩家 " + inviterId + " 邀请玩家 " + targetId + " 加入队伍 " + teamId);
        return true;
    }

    public Team acceptInvite(long playerId) {
        Long teamId = pendingInvites.remove(playerId);
        if (teamId == null) {
            LOG.debug("玩家 " + playerId + " 没有待处理的邀请");
            return null;
        }

        Team team = teams.get(teamId);
        if (team == null) {
            LOG.warn("邀请的队伍 " + teamId + " 不存在");
            return null;
        }

        if (team.getMemberCount() >= MAX_TEAM_SIZE) {
            LOG.debug("队伍 " + teamId + " 已满，玩家 " + playerId + " 无法加入");
            return null;
        }

        team.addMember(playerId);
        playerTeamMap.put(playerId, teamId);
        LOG.info("玩家 " + playerId + " 接受邀请加入队伍 " + teamId);
        return team;
    }

    public void declineInvite(long playerId) {
        Long teamId = pendingInvites.remove(playerId);
        if (teamId != null) {
            LOG.info("玩家 " + playerId + " 拒绝了队伍 " + teamId + " 的邀请");
        }
    }

    public boolean leaveTeam(long playerId) {
        Long teamId = playerTeamMap.get(playerId);
        if (teamId == null) {
            LOG.debug("玩家 " + playerId + " 不在队伍中");
            return false;
        }

        Team team = teams.get(teamId);
        if (team == null) {
            LOG.warn("队伍 " + teamId + " 不存在，清理玩家 " + playerId + " 的队伍记录");
            playerTeamMap.remove(playerId);
            return false;
        }

        if (team.getLeaderId() == playerId) {
            dissolveTeam(teamId);
            LOG.info("队长 " + playerId + " 离开，队伍 " + teamId + " 已解散");
            return true;
        }

        team.removeMember(playerId);
        playerTeamMap.remove(playerId);

        if (team.getMemberCount() < MIN_TEAM_SIZE) {
            dissolveTeam(teamId);
            LOG.info("玩家 " + playerId + " 离开后队伍人数不足，队伍 " + teamId + " 已解散");
        } else {
            LOG.info("玩家 " + playerId + " 离开队伍 " + teamId);
        }

        return true;
    }

    public void dissolveTeam(long teamId) {
        Team team = teams.remove(teamId);
        if (team != null) {
            for (long memberId : team.getMemberIds()) {
                playerTeamMap.remove(memberId);
            }
            LOG.info("队伍 " + teamId + " 已解散，成员数: " + team.getMemberCount());
        }
    }

    public boolean isInTeam(long playerId) {
        return playerTeamMap.containsKey(playerId);
    }

    public Team getTeam(long playerId) {
        Long teamId = playerTeamMap.get(playerId);
        if (teamId == null) {
            return null;
        }
        return teams.get(teamId);
    }

    public Team getTeamById(long teamId) {
        return teams.get(teamId);
    }

    public boolean hasPendingInvite(long playerId) {
        return pendingInvites.containsKey(playerId);
    }

    public Long getPendingInviteTeamId(long playerId) {
        return pendingInvites.get(playerId);
    }

    public static class Team {
        private final long teamId;
        private final long leaderId;
        private final Set<Long> memberIds = new LinkedHashSet<>();

        public Team(long teamId, long leaderId) {
            this.teamId = teamId;
            this.leaderId = leaderId;
            this.memberIds.add(leaderId);
        }

        public long getTeamId() {
            return teamId;
        }

        public long getLeaderId() {
            return leaderId;
        }

        public Set<Long> getMemberIds() {
            return Collections.unmodifiableSet(memberIds);
        }

        public int getMemberCount() {
            return memberIds.size();
        }

        public void addMember(long memberId) {
            memberIds.add(memberId);
        }

        public void removeMember(long memberId) {
            memberIds.remove(memberId);
        }

        public boolean containsMember(long memberId) {
            return memberIds.contains(memberId);
        }

        public boolean isLeader(long playerId) {
            return leaderId == playerId;
        }
    }
}