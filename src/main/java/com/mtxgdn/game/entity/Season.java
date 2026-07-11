package com.mtxgdn.game.entity;

import java.util.Calendar;

public enum Season {
    SPRING("春季", "万物复苏，木系作物生长加速"),
    SUMMER("夏季", "烈日炎炎，火系作物生长加速"),
    AUTUMN("秋季", "金风送爽，金系作物生长加速"),
    WINTER("冬季", "天寒地冻，水系/冰系作物生长加速");

    private final String displayName;
    private final String description;

    Season(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    public static Season getCurrentSeason() {
        Calendar calendar = Calendar.getInstance();
        int month = calendar.get(Calendar.MONTH) + 1;
        
        return switch (month) {
            case 3, 4, 5 -> SPRING;
            case 6, 7, 8 -> SUMMER;
            case 9, 10, 11 -> AUTUMN;
            default -> WINTER;
        };
    }

    public Season next() {
        return switch (this) {
            case SPRING -> SUMMER;
            case SUMMER -> AUTUMN;
            case AUTUMN -> WINTER;
            case WINTER -> SPRING;
        };
    }

    public double getGrowthModifier(CropConfig.ElementType elementType) {
        return switch (this) {
            case SPRING -> (elementType == CropConfig.ElementType.WOOD || 
                           elementType == CropConfig.ElementType.SPIRIT) ? 1.25 : 
                          (elementType == CropConfig.ElementType.FIRE) ? 0.75 : 1.0;
            case SUMMER -> (elementType == CropConfig.ElementType.FIRE) ? 1.30 : 
                          (elementType == CropConfig.ElementType.WATER || 
                           elementType == CropConfig.ElementType.ICE) ? 0.80 : 1.0;
            case AUTUMN -> (elementType == CropConfig.ElementType.METAL || 
                           elementType == CropConfig.ElementType.EARTH) ? 1.20 : 
                          (elementType == CropConfig.ElementType.WOOD) ? 0.90 : 1.0;
            case WINTER -> (elementType == CropConfig.ElementType.WATER || 
                           elementType == CropConfig.ElementType.ICE) ? 1.30 : 
                          (elementType == CropConfig.ElementType.FIRE) ? 0.70 : 1.0;
        };
    }
}
