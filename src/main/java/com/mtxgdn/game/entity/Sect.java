package com.mtxgdn.game.entity;

public class Sect {

    private long id;
    private String name;
    private String description;
    private long leaderPlayerId;
    private String leaderName;
    private int level;
    private long prestige;
    private int maxMembers;
    private int memberCount;
    private String createdAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public long getLeaderPlayerId() { return leaderPlayerId; }
    public void setLeaderPlayerId(long leaderPlayerId) { this.leaderPlayerId = leaderPlayerId; }

    public String getLeaderName() { return leaderName; }
    public void setLeaderName(String leaderName) { this.leaderName = leaderName; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public long getPrestige() { return prestige; }
    public void setPrestige(long prestige) { this.prestige = prestige; }

    public int getMaxMembers() { return maxMembers; }
    public void setMaxMembers(int maxMembers) { this.maxMembers = maxMembers; }

    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public static final int MIN_LEVEL_CREATE = 3;
    public static final long CREATE_COST_SPIRIT_STONES = 100;
    public static final int MAX_MEMBERS_BASE = 20;
    public static final int MAX_MEMBERS_PER_LEVEL = 5;

    public static int getMaxMembersForLevel(int level) {
        return MAX_MEMBERS_BASE + (level - 1) * MAX_MEMBERS_PER_LEVEL;
    }
}
