package com.mtxgdn.game.entity;

import java.util.List;

public class PveCombatResult {

    private boolean success;
    private String message;
    private boolean playerWon;
    private String monsterName;
    private boolean isBoss;
    private int playerHpRemaining;
    private int monsterHpRemaining;
    private int totalRounds;
    private long expGained;
    private long goldGained;
    private long spiritStonesGained;
    private String itemGained;
    private int itemQuantity;
    private List<String> battleLog;

    public PveCombatResult() {
    }

    public static PveCombatResult failure(String message) {
        PveCombatResult r = new PveCombatResult();
        r.success = false;
        r.message = message;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isPlayerWon() { return playerWon; }
    public void setPlayerWon(boolean playerWon) { this.playerWon = playerWon; }

    public String getMonsterName() { return monsterName; }
    public void setMonsterName(String monsterName) { this.monsterName = monsterName; }

    public boolean isBoss() { return isBoss; }
    public void setBoss(boolean boss) { isBoss = boss; }

    public int getPlayerHpRemaining() { return playerHpRemaining; }
    public void setPlayerHpRemaining(int playerHpRemaining) { this.playerHpRemaining = playerHpRemaining; }

    public int getMonsterHpRemaining() { return monsterHpRemaining; }
    public void setMonsterHpRemaining(int monsterHpRemaining) { this.monsterHpRemaining = monsterHpRemaining; }

    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }

    public long getExpGained() { return expGained; }
    public void setExpGained(long expGained) { this.expGained = expGained; }

    public long getGoldGained() { return goldGained; }
    public void setGoldGained(long goldGained) { this.goldGained = goldGained; }

    public long getSpiritStonesGained() { return spiritStonesGained; }
    public void setSpiritStonesGained(long spiritStonesGained) { this.spiritStonesGained = spiritStonesGained; }

    public String getItemGained() { return itemGained; }
    public void setItemGained(String itemGained) { this.itemGained = itemGained; }

    public int getItemQuantity() { return itemQuantity; }
    public void setItemQuantity(int itemQuantity) { this.itemQuantity = itemQuantity; }

    public List<String> getBattleLog() { return battleLog; }
    public void setBattleLog(List<String> battleLog) { this.battleLog = battleLog; }
}
