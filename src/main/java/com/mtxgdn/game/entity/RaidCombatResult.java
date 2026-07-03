package com.mtxgdn.game.entity;

import java.util.ArrayList;
import java.util.List;

public class RaidCombatResult {

    private boolean success;
    private String message;
    private boolean teamWon;
    private String bossName;
    private int bossForm;
    private long lastHitPlayerId;
    private String lastHitPlayerName;
    private int totalRounds;
    private List<Long> defeatedPlayers = new ArrayList<>();
    private List<String> battleLog = new ArrayList<>();

    public RaidCombatResult() {}

    public static RaidCombatResult failure(String message) {
        RaidCombatResult r = new RaidCombatResult();
        r.success = false;
        r.message = message;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isTeamWon() { return teamWon; }
    public void setTeamWon(boolean teamWon) { this.teamWon = teamWon; }

    public String getBossName() { return bossName; }
    public void setBossName(String bossName) { this.bossName = bossName; }

    public int getBossForm() { return bossForm; }
    public void setBossForm(int bossForm) { this.bossForm = bossForm; }

    public long getLastHitPlayerId() { return lastHitPlayerId; }
    public void setLastHitPlayerId(long lastHitPlayerId) { this.lastHitPlayerId = lastHitPlayerId; }

    public String getLastHitPlayerName() { return lastHitPlayerName; }
    public void setLastHitPlayerName(String lastHitPlayerName) { this.lastHitPlayerName = lastHitPlayerName; }

    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }

    public List<Long> getDefeatedPlayers() { return defeatedPlayers; }
    public void setDefeatedPlayers(List<Long> defeatedPlayers) { this.defeatedPlayers = defeatedPlayers; }

    public List<String> getBattleLog() { return battleLog; }
    public void setBattleLog(List<String> battleLog) { this.battleLog = battleLog; }
}