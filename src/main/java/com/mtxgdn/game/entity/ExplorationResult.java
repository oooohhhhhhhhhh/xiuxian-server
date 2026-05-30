package com.mtxgdn.game.entity;

import java.util.List;

public class ExplorationResult {

    private boolean success;
    private String message;
    private String eventType;
    private String eventDescription;
    private long expGained;
    private long goldGained;
    private long spiritStonesGained;
    private String itemGained;
    private int itemQuantity;
    private int hpLost;
    private boolean monsterDefeated;
    private String monsterName;
    private List<String> log;
    private long nextExplorationTime;

    public ExplorationResult() {
    }

    public static ExplorationResult failure(String message) {
        ExplorationResult r = new ExplorationResult();
        r.success = false;
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventDescription() {
        return eventDescription;
    }

    public void setEventDescription(String eventDescription) {
        this.eventDescription = eventDescription;
    }

    public long getExpGained() {
        return expGained;
    }

    public void setExpGained(long expGained) {
        this.expGained = expGained;
    }

    public long getGoldGained() {
        return goldGained;
    }

    public void setGoldGained(long goldGained) {
        this.goldGained = goldGained;
    }

    public long getSpiritStonesGained() {
        return spiritStonesGained;
    }

    public void setSpiritStonesGained(long spiritStonesGained) {
        this.spiritStonesGained = spiritStonesGained;
    }

    public String getItemGained() {
        return itemGained;
    }

    public void setItemGained(String itemGained) {
        this.itemGained = itemGained;
    }

    public int getItemQuantity() {
        return itemQuantity;
    }

    public void setItemQuantity(int itemQuantity) {
        this.itemQuantity = itemQuantity;
    }

    public int getHpLost() {
        return hpLost;
    }

    public void setHpLost(int hpLost) {
        this.hpLost = hpLost;
    }

    public boolean isMonsterDefeated() {
        return monsterDefeated;
    }

    public void setMonsterDefeated(boolean monsterDefeated) {
        this.monsterDefeated = monsterDefeated;
    }

    public String getMonsterName() {
        return monsterName;
    }

    public void setMonsterName(String monsterName) {
        this.monsterName = monsterName;
    }

    public List<String> getLog() {
        return log;
    }

    public void setLog(List<String> log) {
        this.log = log;
    }

    public long getNextExplorationTime() {
        return nextExplorationTime;
    }

    public void setNextExplorationTime(long nextExplorationTime) {
        this.nextExplorationTime = nextExplorationTime;
    }
}
