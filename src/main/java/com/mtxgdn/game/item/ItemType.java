package com.mtxgdn.game.item;

public enum ItemType {
    CONSUMABLE("消耗品"),
    EQUIPMENT("装备"),
    MATERIAL("材料"),
    SKILL_BOOK("功法秘籍"),
    TREASURE("法宝"),
    QUEST("任务物品"),
    CURRENCY("货币");

    private final String displayName;

    ItemType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
