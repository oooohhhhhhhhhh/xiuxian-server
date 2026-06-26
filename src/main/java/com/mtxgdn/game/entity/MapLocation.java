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
    private List<MapLocation> connections;

    public MapLocation() {
        this.connections = new ArrayList<>();
    }

    public MapLocation(long id, String name, String description, String region, int minRealm, boolean safeZone) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.region = region;
        this.minRealm = minRealm;
        this.safeZone = safeZone;
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

    public List<MapLocation> getConnections() { return connections; }
    public void setConnections(List<MapLocation> connections) { this.connections = connections; }

    @Override
    public String toString() {
        return "MapLocation{id=" + id + ", name='" + name + "', region='" + region + "'}";
    }
}
