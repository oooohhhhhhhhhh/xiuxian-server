package com.mtxgdn.game.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * 地图地点实体
 */
public class MapLocation {
    private long id;
    private String name;
    private String description;
    private String region;
    private int minRealm;
    private boolean safeZone;
    /** 闭关修炼加成倍率（灵气浓郁程度），数据来自 map_locations.cultivation_bonus。 */
    private double cultivationBonus = 1.0;
    /** 游历 / 秘境收益倍率，数据来自 map_locations.exploration_bonus。 */
    private double explorationBonus = 1.0;
    /** 危险度 0~4（0=安全 1=低危 2=中危 3=高危 4=极危），数据来自 map_locations.danger_level。 */
    private int dangerLevel = 0;
    /** 是否野外随机地点（程序生成、会过期），数据来自 map_locations.is_wilderness。 */
    private boolean wilderness = false;
    /** 野外节点的过期时刻（epoch 秒）；已知世界地点为 0（永不过期）。 */
    private long expiresAt = 0;
    /** 野外节点的入口地点 id（已知世界地点）；已知世界地点为 0。 */
    private long entryLocationId = 0;
    private List<MapLocation> connections;

    public MapLocation() {
        this.connections = new ArrayList<>();
    }

    public MapLocation(long id, String name, String description, String region, int minRealm, boolean safeZone) {
        this(id, name, description, region, minRealm, safeZone, 1.0, 1.0, 0);
    }

    public MapLocation(long id, String name, String description, String region, int minRealm, boolean safeZone,
                       double cultivationBonus, double explorationBonus) {
        this(id, name, description, region, minRealm, safeZone, cultivationBonus, explorationBonus, 0);
    }

    public MapLocation(long id, String name, String description, String region, int minRealm, boolean safeZone,
                       double cultivationBonus, double explorationBonus, int dangerLevel) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.region = region;
        this.minRealm = minRealm;
        this.safeZone = safeZone;
        this.cultivationBonus = cultivationBonus;
        this.explorationBonus = explorationBonus;
        this.dangerLevel = dangerLevel;
        this.connections = new ArrayList<>();
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public int getMinRealm() { return minRealm; }
    public void setMinRealm(int minRealm) { this.minRealm = minRealm; }

    public boolean isSafeZone() { return safeZone; }
    public void setSafeZone(boolean safeZone) { this.safeZone = safeZone; }

    public double getCultivationBonus() { return cultivationBonus; }
    public void setCultivationBonus(double cultivationBonus) { this.cultivationBonus = cultivationBonus; }

    public double getExplorationBonus() { return explorationBonus; }
    public void setExplorationBonus(double explorationBonus) { this.explorationBonus = explorationBonus; }

    public int getDangerLevel() { return dangerLevel; }
    public void setDangerLevel(int dangerLevel) { this.dangerLevel = dangerLevel; }

    public boolean isWilderness() { return wilderness; }
    public void setWilderness(boolean wilderness) { this.wilderness = wilderness; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public long getEntryLocationId() { return entryLocationId; }
    public void setEntryLocationId(long entryLocationId) { this.entryLocationId = entryLocationId; }

    public List<MapLocation> getConnections() { return connections; }
    public void setConnections(List<MapLocation> connections) { this.connections = connections; }

    @Override
    public String toString() {
        return "MapLocation{id=" + id + ", name='" + name + "', region='" + region + "'}";
    }
}
