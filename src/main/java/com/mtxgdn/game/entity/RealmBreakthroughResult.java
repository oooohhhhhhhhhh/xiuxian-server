package com.mtxgdn.game.entity;

import java.util.ArrayList;
import java.util.List;

public class RealmBreakthroughResult {

    private boolean success;
    private String message;
    private String newRealmName;
    private int hpAdded;
    private int mpAdded;
    private int attackAdded;
    private int defenseAdded;
    private int speedAdded;
    private int spiritAdded;
    private int unlockedSkillId;

    private boolean hasTribulation;
    private String tribulationType;
    private String tribulationDescription;
    private List<String> tribulationLog;
    private double baseSuccessRate;
    private double finalSuccessRate;
    private int roll;
    private List<String> successRateBreakdown;
    private String tribulationItemUsed;

    private long expPenalty;
    private int hpPenalty;

    /** 本次结算前已累计的连续渡劫失败次数。 */
    private int pityCount;
    /** 失败增益折算出的成功率加成（百分点）。 */
    private double pityBonus;
    /** 当前凝实（极境）层数。 */
    private int consolidation;
    /** 凝实层数上限。 */
    private int consolidationCap = 3;

    public RealmBreakthroughResult() {
        this.tribulationLog = new ArrayList<>();
        this.successRateBreakdown = new ArrayList<>();
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getNewRealmName() { return newRealmName; }
    public void setNewRealmName(String newRealmName) { this.newRealmName = newRealmName; }

    public int getHpAdded() { return hpAdded; }
    public void setHpAdded(int hpAdded) { this.hpAdded = hpAdded; }

    public int getMpAdded() { return mpAdded; }
    public void setMpAdded(int mpAdded) { this.mpAdded = mpAdded; }

    public int getAttackAdded() { return attackAdded; }
    public void setAttackAdded(int attackAdded) { this.attackAdded = attackAdded; }

    public int getDefenseAdded() { return defenseAdded; }
    public void setDefenseAdded(int defenseAdded) { this.defenseAdded = defenseAdded; }

    public int getSpeedAdded() { return speedAdded; }
    public void setSpeedAdded(int speedAdded) { this.speedAdded = speedAdded; }

    public int getSpiritAdded() { return spiritAdded; }
    public void setSpiritAdded(int spiritAdded) { this.spiritAdded = spiritAdded; }

    public int getUnlockedSkillId() { return unlockedSkillId; }
    public void setUnlockedSkillId(int unlockedSkillId) { this.unlockedSkillId = unlockedSkillId; }

    public boolean isHasTribulation() { return hasTribulation; }
    public void setHasTribulation(boolean hasTribulation) { this.hasTribulation = hasTribulation; }

    public String getTribulationType() { return tribulationType; }
    public void setTribulationType(String tribulationType) { this.tribulationType = tribulationType; }

    public String getTribulationDescription() { return tribulationDescription; }
    public void setTribulationDescription(String tribulationDescription) { this.tribulationDescription = tribulationDescription; }

    public List<String> getTribulationLog() { return tribulationLog; }
    public void setTribulationLog(List<String> tribulationLog) { this.tribulationLog = tribulationLog; }
    public void addTribulationLog(String line) { this.tribulationLog.add(line); }

    public double getBaseSuccessRate() { return baseSuccessRate; }
    public void setBaseSuccessRate(double baseSuccessRate) { this.baseSuccessRate = baseSuccessRate; }

    public double getFinalSuccessRate() { return finalSuccessRate; }
    public void setFinalSuccessRate(double finalSuccessRate) { this.finalSuccessRate = finalSuccessRate; }

    public int getRoll() { return roll; }
    public void setRoll(int roll) { this.roll = roll; }

    public List<String> getSuccessRateBreakdown() { return successRateBreakdown; }
    public void setSuccessRateBreakdown(List<String> successRateBreakdown) { this.successRateBreakdown = successRateBreakdown; }
    public void addSuccessRateBreakdown(String line) { this.successRateBreakdown.add(line); }

    public long getExpPenalty() { return expPenalty; }
    public void setExpPenalty(long expPenalty) { this.expPenalty = expPenalty; }

    public int getHpPenalty() { return hpPenalty; }
    public void setHpPenalty(int hpPenalty) { this.hpPenalty = hpPenalty; }

    public String getTribulationItemUsed() { return tribulationItemUsed; }
    public void setTribulationItemUsed(String item) { this.tribulationItemUsed = item; }

    public int getPityCount() { return pityCount; }
    public void setPityCount(int pityCount) { this.pityCount = pityCount; }

    public double getPityBonus() { return pityBonus; }
    public void setPityBonus(double pityBonus) { this.pityBonus = pityBonus; }

    public int getConsolidation() { return consolidation; }
    public void setConsolidation(int consolidation) { this.consolidation = consolidation; }

    public int getConsolidationCap() { return consolidationCap; }
    public void setConsolidationCap(int consolidationCap) { this.consolidationCap = consolidationCap; }
}
