package com.mtxgdn.game.entity;

public class Formation {

    private long id;
    private long playerId;
    private String formationKey;
    private String name;
    private int level;
    private int spiritEnergyBoost;
    private int cultivationBonus;
    private int defenseBonus;
    private int heartDemonResist;
    private int durationMinutes;
    private long placedAt;
    private long expiresAt;
    private boolean active;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getPlayerId() { return playerId; }
    public void setPlayerId(long playerId) { this.playerId = playerId; }

    public String getFormationKey() { return formationKey; }
    public void setFormationKey(String formationKey) { this.formationKey = formationKey; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public int getSpiritEnergyBoost() { return spiritEnergyBoost; }
    public void setSpiritEnergyBoost(int spiritEnergyBoost) { this.spiritEnergyBoost = spiritEnergyBoost; }

    public int getCultivationBonus() { return cultivationBonus; }
    public void setCultivationBonus(int cultivationBonus) { this.cultivationBonus = cultivationBonus; }

    public int getDefenseBonus() { return defenseBonus; }
    public void setDefenseBonus(int defenseBonus) { this.defenseBonus = defenseBonus; }

    public int getHeartDemonResist() { return heartDemonResist; }
    public void setHeartDemonResist(int heartDemonResist) { this.heartDemonResist = heartDemonResist; }

    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }

    public long getPlacedAt() { return placedAt; }
    public void setPlacedAt(long placedAt) { this.placedAt = placedAt; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    public long getRemainingSeconds() {
        if (isExpired()) return 0;
        return Math.max(0, expiresAt - System.currentTimeMillis()) / 1000;
    }

    public static final int MAX_LEVEL = 5;

    public static String getNameForKey(String key) {
        return switch (key) {
            case "spirit_gathering" -> "聚灵阵";
            case "mountain_protection" -> "护山大阵";
            case "maze" -> "迷踪阵";
            case "purification" -> "净化阵";
            case "time_acceleration" -> "时间加速阵";
            default -> "未知阵法";
        };
    }

    public static int getBaseDuration(String key) {
        return switch (key) {
            case "spirit_gathering" -> 60;
            case "mountain_protection" -> 120;
            case "maze" -> 30;
            case "purification" -> 90;
            case "time_acceleration" -> 15;
            default -> 60;
        };
    }

    public static int getSpiritEnergyBoostForLevel(String key, int level) {
        return switch (key) {
            case "spirit_gathering" -> level * 5;
            case "time_acceleration" -> level * 3;
            default -> 0;
        };
    }

    public static int getCultivationBonusForLevel(String key, int level) {
        return switch (key) {
            case "spirit_gathering" -> level * 3;
            case "time_acceleration" -> level * 5;
            case "purification" -> level * 2;
            default -> 0;
        };
    }

    public static int getDefenseBonusForLevel(String key, int level) {
        return switch (key) {
            case "mountain_protection" -> level * 10;
            case "maze" -> level * 5;
            default -> 0;
        };
    }

    public static int getHeartDemonResistForLevel(String key, int level) {
        return switch (key) {
            case "purification" -> level * 2;
            case "spirit_gathering" -> level * 1;
            default -> 0;
        };
    }

    public static long getPlaceCost(String key, int level) {
        int baseCost = switch (key) {
            case "spirit_gathering" -> 100;
            case "mountain_protection" -> 300;
            case "maze" -> 50;
            case "purification" -> 200;
            case "time_acceleration" -> 500;
            default -> 100;
        };
        return (long) baseCost * level;
    }
}