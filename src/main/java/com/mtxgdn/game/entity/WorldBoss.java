package com.mtxgdn.game.entity;

/**
 * 世界 Boss（B6）。
 *
 * <p>与 {@link Monster}（一次性 PVE 对手）和 {@link BossForm}（团队 Raid 形态）不同，
 * 世界 Boss 的<b>血量是全服共享的，并且直接存在数据库里</b>：</p>
 * <ul>
 *   <li>没有内存态，也没有定时器 —— 服务重启后血条原样还在；</li>
 *   <li>任何玩家造成的伤害都直接扣这一份 {@link #hp}；</li>
 *   <li>到期（{@link #expiresAt}）后整池重建，由读取时的惰性逻辑处理。</li>
 * </ul>
 *
 * <p>奖励按<b>伤害比例分红</b>而不是击杀独占：{@link #stoneBudget} / {@link #expBudget}
 * 是这只 Boss 一生能发出的总量，每次挑战按 {@code 预算 × 本次伤害 / 最大血量} 发放。
 * 因为全服累计伤害不会超过 {@link #maxHp}，总发放天然有界，不需要额外的账本核对。</p>
 */
public class WorldBoss {

    private long id;
    /** Boss 池的归属键；单世界服务器固定 {@code global}。 */
    private String scope;
    /** 池内序号（1..N），与 scope 组成唯一键。 */
    private int slot;
    private String name;
    private String description;
    /** Boss 所在大境界，用于属性生成与挑战门槛判定。 */
    private int realm;
    /** 当前剩余血量（全服共享）。归零即被击杀。 */
    private long hp;
    private long maxHp;
    private int attack;
    private int defense;
    private int speed;
    /** 灵石总预算：按伤害分红，发完为止。 */
    private long stoneBudget;
    /** 修为总预算：按伤害分红，发完为止。 */
    private long expBudget;
    /** 整池重建的截止时刻（毫秒时间戳）。 */
    private long expiresAt;
    /** 被击杀的时刻；0 = 尚存活。 */
    private long killedAt;
    /** 补上最后一刀（把 hp 打到 0）的玩家；0 = 无。 */
    private long killerPlayerId;
    private String killerName;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getRealm() { return realm; }
    public void setRealm(int realm) { this.realm = realm; }

    public long getHp() { return hp; }
    public void setHp(long hp) { this.hp = hp; }

    public long getMaxHp() { return maxHp; }
    public void setMaxHp(long maxHp) { this.maxHp = maxHp; }

    public int getAttack() { return attack; }
    public void setAttack(int attack) { this.attack = attack; }

    public int getDefense() { return defense; }
    public void setDefense(int defense) { this.defense = defense; }

    public int getSpeed() { return speed; }
    public void setSpeed(int speed) { this.speed = speed; }

    public long getStoneBudget() { return stoneBudget; }
    public void setStoneBudget(long stoneBudget) { this.stoneBudget = stoneBudget; }

    public long getExpBudget() { return expBudget; }
    public void setExpBudget(long expBudget) { this.expBudget = expBudget; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public long getKilledAt() { return killedAt; }
    public void setKilledAt(long killedAt) { this.killedAt = killedAt; }

    public long getKillerPlayerId() { return killerPlayerId; }
    public void setKillerPlayerId(long killerPlayerId) { this.killerPlayerId = killerPlayerId; }

    public String getKillerName() { return killerName; }
    public void setKillerName(String killerName) { this.killerName = killerName; }

    /** 是否已被击杀（血量归零）。 */
    public boolean isDead() {
        return hp <= 0;
    }

    /** 是否已到期（需要重建整池）。 */
    public boolean isExpired(long now) {
        return expiresAt <= now;
    }

    /** 血量百分比（0..100），用于展示血条。 */
    public int hpPercent() {
        if (maxHp <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, hp * 100 / maxHp));
    }
}
