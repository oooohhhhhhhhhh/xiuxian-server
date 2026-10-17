package com.mtxgdn.game.entity;

/**
 * 世界 Boss 伤害台账（B6）：一个玩家对一只世界 Boss 的累计贡献。
 *
 * <p>两件事都靠它：</p>
 * <ul>
 *   <li><b>贡献榜</b>：按 {@link #damage} 排序展示；</li>
 *   <li><b>挑战冷却</b>：{@link #lastHitAt} 是唯一的冷却依据。本项目没有体力系统，
 *       若不限次，一个高境界玩家可以连着把 Boss 打死，其他人连参与的份都没有。</li>
 * </ul>
 */
public class WorldBossDamage {

    private long bossId;
    private long playerId;
    private String playerName;
    /** 累计造成的伤害。 */
    private long damage;
    /** 挑战次数。 */
    private int hits;
    /** 上次挑战时刻（毫秒时间戳），用于挑战冷却。 */
    private long lastHitAt;

    public long getBossId() { return bossId; }
    public void setBossId(long bossId) { this.bossId = bossId; }

    public long getPlayerId() { return playerId; }
    public void setPlayerId(long playerId) { this.playerId = playerId; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public long getDamage() { return damage; }
    public void setDamage(long damage) { this.damage = damage; }

    public int getHits() { return hits; }
    public void setHits(int hits) { this.hits = hits; }

    public long getLastHitAt() { return lastHitAt; }
    public void setLastHitAt(long lastHitAt) { this.lastHitAt = lastHitAt; }

    /** 冷却是否已结束；{@code cooldownMs <= 0} 表示不限制。 */
    public boolean isReady(long now, long cooldownMs) {
        return cooldownMs <= 0 || lastHitAt <= 0 || now - lastHitAt >= cooldownMs;
    }

    /** 距离冷却结束还有多少毫秒；已结束返回 0。 */
    public long remainingCooldownMs(long now, long cooldownMs) {
        if (cooldownMs <= 0 || lastHitAt <= 0) {
            return 0;
        }
        long remain = cooldownMs - (now - lastHitAt);
        return Math.max(0, remain);
    }
}
