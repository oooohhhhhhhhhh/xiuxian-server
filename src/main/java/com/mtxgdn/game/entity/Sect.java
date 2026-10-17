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

    /** 宗主缺位起始时间（毫秒）；0 = 宗主在任。>0 表示自该时刻起宗门无人主事，等待按贡献继位。 */
    private long leaderAbsentSince;
    /** 宗主竞争期截止时间（毫秒）；0 = 无竞争期。 */
    private long contestUntil;
    /** 竞争期内的挑战者玩家 ID；0 = 无。 */
    private long contestChallengerId;
    /** 挑战者是否已发起挑战（等待宗主应战）。 */
    private boolean contestRequested;

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

    public long getLeaderAbsentSince() { return leaderAbsentSince; }
    public void setLeaderAbsentSince(long leaderAbsentSince) { this.leaderAbsentSince = leaderAbsentSince; }

    public long getContestUntil() { return contestUntil; }
    public void setContestUntil(long contestUntil) { this.contestUntil = contestUntil; }

    public long getContestChallengerId() { return contestChallengerId; }
    public void setContestChallengerId(long contestChallengerId) { this.contestChallengerId = contestChallengerId; }

    public boolean isContestRequested() { return contestRequested; }
    public void setContestRequested(boolean contestRequested) { this.contestRequested = contestRequested; }

    /** 宗主是否处于缺位状态（主动退位或长期不活跃被判定弃坑）。 */
    public boolean isLeaderAbsent() { return leaderAbsentSince > 0; }

    /** 是否处于<b>进行中</b>的宗主竞争期（有挑战者且未到期）。 */
    public boolean hasActiveContest(long now) {
        return contestUntil > now && contestChallengerId > 0;
    }

    /** 竞争期是否已过期但字段尚未清理。 */
    public boolean isContestExpired(long now) {
        return contestUntil > 0 && contestUntil <= now;
    }

    public static final int MIN_LEVEL_CREATE = 4;                  // 元婴期（按 realm_config 大境界序号，realm>=4）
    public static final long CREATE_COST_SPIRIT_STONES = 500;      // 灵石
    public static final int MAX_LEVEL = 10;
    public static final int MAX_MEMBERS_BASE = 20;
    public static final int MAX_MEMBERS_PER_LEVEL = 5;
    public static final long LEVEL_UP_PRESTIGE_PER_LEVEL = 1000;   // 每级消耗声望基数
    public static final long TRANSFER_COST_SPIRIT_STONES = 200;    // 转让消耗灵石
    public static final long DECLARE_WAR_PRESTIGE_COST = 1000;      // 宣战消耗声望
    public static final long DECLARE_WAR_SPIRIT_STONE_COST = 300;   // 宣战消耗灵石
    public static final int WAR_MEMBERS_PER_SIDE = 5;               // 每方出战人数
    public static final long WAR_WIN_PRESTIGE = 500;                // 战胜方额外获得的声望

    // ---- 宗主缺位继位 / 竞争期（默认值，运行期可由 config/application.yml 的 sect.* 覆盖）----

    /**
     * 竞争期开启门槛：继任者与最强竞争者的<b>境界序位差</b>小于该值时才开启竞争期。
     * <p>境界序位 = {@code realm * 3 + subRealm}，因此 6 ≈ 两个大境界。差距过大时
     * 挑战毫无意义，直接让继任者坐稳，避免无意义的弹窗骚扰。</p>
     */
    public static final int CONTEST_LEVEL_GAP_DEFAULT = 6;

    /** 竞争期默认持续天数。期内挑战者可发起三局两胜的宗主之位争夺。 */
    public static final int CONTEST_DAYS_DEFAULT = 7;

    /** 宗主连续不活跃多少天判定为"弃坑"（0 = 关闭该判定，只保留主动退位路径）。 */
    public static final int LEADER_ABSENT_DAYS_DEFAULT = 14;

    public static int getMaxMembersForLevel(int level) {
        return MAX_MEMBERS_BASE + (level - 1) * MAX_MEMBERS_PER_LEVEL;
    }

    public static long getLevelUpCost(int currentLevel) {
        return currentLevel * LEVEL_UP_PRESTIGE_PER_LEVEL;
    }
}
