package com.mtxgdn.game.entity;

import java.util.HashMap;
import java.util.Map;

public class CropConfig {

    public enum ElementType {
        WOOD("木"),
        WATER("水"),
        FIRE("火"),
        EARTH("土"),
        METAL("金"),
        ICE("冰"),
        DARK("暗"),
        LIGHT("光"),
        SPIRIT("灵");

        private final String displayName;
        ElementType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private String seedKey;
    private String cropKey;
    private String cropName;
    private int growthSeconds;
    private int stages;
    private int baseYield;
    private int maxYield;
    private int waterNeed;
    private int fertilizerBonus;
    private ElementType elementType;

    private static final Map<String, CropConfig> configs = new HashMap<>();

    static {
        configs.put("mtxgdn:spirit_grass_seed", new CropConfig(
            "mtxgdn:spirit_grass_seed", "mtxgdn:spirit_grass", "灵草",
            60, 3, 3, 5, 50, 10, ElementType.SPIRIT
        ));
        configs.put("mtxgdn:thousand_year_ginseng_seed", new CropConfig(
            "mtxgdn:thousand_year_ginseng_seed", "mtxgdn:thousand_year_ginseng", "千年人参",
            180, 5, 1, 3, 40, 15, ElementType.WOOD
        ));
        configs.put("mtxgdn:dark_ice_grass_seed", new CropConfig(
            "mtxgdn:dark_ice_grass_seed", "mtxgdn:dark_ice_grass", "暗冰草",
            90, 4, 2, 4, 45, 12, ElementType.ICE
        ));
        configs.put("mtxgdn:fire_vine_seed", new CropConfig(
            "mtxgdn:fire_vine_seed", "mtxgdn:fire_vine", "火焰藤",
            120, 4, 2, 4, 45, 12, ElementType.FIRE
        ));
        configs.put("mtxgdn:nether_flower_seed", new CropConfig(
            "mtxgdn:nether_flower_seed", "mtxgdn:nether_flower", "幽冥花",
            240, 5, 1, 3, 35, 20, ElementType.DARK
        ));
        configs.put("mtxgdn:star_grass_seed", new CropConfig(
            "mtxgdn:star_grass_seed", "mtxgdn:star_grass", "星辰草",
            300, 6, 1, 2, 30, 25, ElementType.LIGHT
        ));
        configs.put("mtxgdn:blood_lingzhi_seed", new CropConfig(
            "mtxgdn:blood_lingzhi_seed", "mtxgdn:blood_lingzhi", "血灵芝",
            200, 5, 1, 2, 40, 18, ElementType.EARTH
        ));
        configs.put("mtxgdn:tianshan_snow_lotus_seed", new CropConfig(
            "mtxgdn:tianshan_snow_lotus_seed", "mtxgdn:tianshan_snow_lotus", "天山雪莲",
            420, 7, 1, 2, 25, 30, ElementType.WATER
        ));

        configs.put("mtxgdn:peach_seed", new CropConfig(
            "mtxgdn:peach_seed", "mtxgdn:peach", "蟠桃",
            180, 4, 2, 4, 45, 15, ElementType.WOOD
        ));
        configs.put("mtxgdn:ginseng_fruit_seed", new CropConfig(
            "mtxgdn:ginseng_fruit_seed", "mtxgdn:ginseng_fruit", "人参果",
            300, 6, 1, 2, 35, 25, ElementType.WATER
        ));
        configs.put("mtxgdn:dragon_fruit_seed", new CropConfig(
            "mtxgdn:dragon_fruit_seed", "mtxgdn:dragon_fruit", "火龙果",
            120, 4, 2, 4, 40, 12, ElementType.FIRE
        ));
        configs.put("mtxgdn:immortal_date_seed", new CropConfig(
            "mtxgdn:immortal_date_seed", "mtxgdn:immortal_date", "仙枣",
            240, 5, 1, 3, 35, 20, ElementType.EARTH
        ));
        configs.put("mtxgdn:nine_turn_grass_seed", new CropConfig(
            "mtxgdn:nine_turn_grass_seed", "mtxgdn:nine_turn_grass", "九转还魂草",
            480, 8, 1, 2, 20, 35, ElementType.WATER
        ));
        configs.put("mtxgdn:he_shou_wu_seed", new CropConfig(
            "mtxgdn:he_shou_wu_seed", "mtxgdn:he_shou_wu", "千年何首乌",
            200, 5, 2, 4, 40, 18, ElementType.EARTH
        ));
        configs.put("mtxgdn:wan_ling_grass_seed", new CropConfig(
            "mtxgdn:wan_ling_grass_seed", "mtxgdn:wan_ling_grass", "万灵草",
            90, 3, 3, 6, 50, 10, ElementType.SPIRIT
        ));
        configs.put("mtxgdn:purple_river_cart_seed", new CropConfig(
            "mtxgdn:purple_river_cart_seed", "mtxgdn:purple_river_cart", "紫河车",
            360, 6, 1, 2, 30, 28, ElementType.DARK
        ));
    }

    public CropConfig() {}

    public CropConfig(String seedKey, String cropKey, String cropName,
                      int growthSeconds, int stages, int baseYield,
                      int maxYield, int waterNeed, int fertilizerBonus, ElementType elementType) {
        this.seedKey = seedKey;
        this.cropKey = cropKey;
        this.cropName = cropName;
        this.growthSeconds = growthSeconds;
        this.stages = stages;
        this.baseYield = baseYield;
        this.maxYield = maxYield;
        this.waterNeed = waterNeed;
        this.fertilizerBonus = fertilizerBonus;
        this.elementType = elementType;
    }

    public static CropConfig get(String seedKey) {
        return configs.get(seedKey);
    }

    public String getSeedKey() { return seedKey; }
    public void setSeedKey(String seedKey) { this.seedKey = seedKey; }
    public String getCropKey() { return cropKey; }
    public void setCropKey(String cropKey) { this.cropKey = cropKey; }
    public String getCropName() { return cropName; }
    public void setCropName(String cropName) { this.cropName = cropName; }
    public int getGrowthSeconds() { return growthSeconds; }
    public void setGrowthSeconds(int growthSeconds) { this.growthSeconds = growthSeconds; }
    public int getStages() { return stages; }
    public void setStages(int stages) { this.stages = stages; }
    public int getBaseYield() { return baseYield; }
    public void setBaseYield(int baseYield) { this.baseYield = baseYield; }
    public int getMaxYield() { return maxYield; }
    public void setMaxYield(int maxYield) { this.maxYield = maxYield; }
    public int getWaterNeed() { return waterNeed; }
    public void setWaterNeed(int waterNeed) { this.waterNeed = waterNeed; }
    public int getFertilizerBonus() { return fertilizerBonus; }
    public void setFertilizerBonus(int fertilizerBonus) { this.fertilizerBonus = fertilizerBonus; }
    public ElementType getElementType() { return elementType; }
    public void setElementType(ElementType elementType) { this.elementType = elementType; }
}