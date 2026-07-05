package com.mtxgdn.game.entity;

public class FarmPlot {
    public enum PlotState {
        EMPTY("空地"),
        PLANTED("已种植"),
        GROWING("生长中"),
        READY("可收获"),
        WILTED("枯萎");

        private final String displayName;

        PlotState(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private long id;
    private long playerId;
    private int plotIndex;
    private PlotState state;
    private String seedKey;
    private String cropKey;
    private long plantedTime;
    private long harvestTime;
    private int growthStage;
    private int waterLevel;
    private int fertilizerLevel;
    private int yield;

    public FarmPlot() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getPlayerId() { return playerId; }
    public void setPlayerId(long playerId) { this.playerId = playerId; }
    public int getPlotIndex() { return plotIndex; }
    public void setPlotIndex(int plotIndex) { this.plotIndex = plotIndex; }
    public PlotState getState() { return state; }
    public void setState(PlotState state) { this.state = state; }
    public String getSeedKey() { return seedKey; }
    public void setSeedKey(String seedKey) { this.seedKey = seedKey; }
    public String getCropKey() { return cropKey; }
    public void setCropKey(String cropKey) { this.cropKey = cropKey; }
    public long getPlantedTime() { return plantedTime; }
    public void setPlantedTime(long plantedTime) { this.plantedTime = plantedTime; }
    public long getHarvestTime() { return harvestTime; }
    public void setHarvestTime(long harvestTime) { this.harvestTime = harvestTime; }
    public int getGrowthStage() { return growthStage; }
    public void setGrowthStage(int growthStage) { this.growthStage = growthStage; }
    public int getWaterLevel() { return waterLevel; }
    public void setWaterLevel(int waterLevel) { this.waterLevel = waterLevel; }
    public int getFertilizerLevel() { return fertilizerLevel; }
    public void setFertilizerLevel(int fertilizerLevel) { this.fertilizerLevel = fertilizerLevel; }
    public int getYield() { return yield; }
    public void setYield(int yield) { this.yield = yield; }
}