package com.mtxgdn.game.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TeamService {

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
        }
        return instance;
    }

    public Team createTeam(long leaderId) {
        if (isInTeam(leaderId)) {
            return null;
        }

        long teamId = teamIdGenerator.getAndIncrement();
        Team team = new Team(teamId, leaderId);
        teams.put(teamId, team);
        playerTeamMap.put(leaderId, teamId);
        return team;
    }

    public boolean invitePlayer(long inviterId, long targetId) {
        Long teamId = playerTeamMap.get(inviterId);
        if (teamId == null) {
            return false;
        }

        Team team = teams.get(teamId);
        if (team == null) {
            return false;
        }

        if (team.getLeaderId() != inviterId) {
            return false;
        }

        if (team.getMemberCount() >= MAX_TEAM_SIZE) {
            return false;
        }

        if (isInTeam(targetId)) {
            return false;
        }

        pendingInvites.put(targetId, teamId);
        return true;
    }

    public Team acceptInvite(long playerId) {
        Long teamId = pendingInvites.remove(playerId);
        if (teamId == null) {
            return null;
        }

        Team team = teams.get(teamId);
        if (team == null) {
            return null;
        }

        if (team.getMemberCount() >= MAX_TEAM_SIZE) {
            return null;
        }

        team.addMember(playerId);
        playerTeamMap.put(playerId, teamId);
        return team;
    }

    public void declineInvite(long playerId) {
        pendingInvites.remove(playerId);
    }

    public boolean leaveTeam(long playerId) {
        Long teamId = playerTeamMap.get(playerId);
        if (teamId == null) {
            return false;
        }

        Team team = teams.get(teamId);
        if (team == null) {
            playerTeamMap.remove(playerId);
            return false;
        }

        if (team.getLeaderId() == playerId) {
            dissolveTeam(teamId);
            return true;
        }

        team.removeMember(playerId);
        playerTeamMap.remove(playerId);

        if (team.getMemberCount() < MIN_TEAM_SIZE) {
            dissolveTeam(teamId);
        }

        return true;
    }

    public void dissolveTeam(long teamId) {
        Team team = teams.remove(teamId);
        if (team != null) {
            for (long memberId : team.getMemberIds()) {
                playerTeamMap.remove(memberId);
            }
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