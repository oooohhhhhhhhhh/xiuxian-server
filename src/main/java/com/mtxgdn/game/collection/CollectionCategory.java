package com.mtxgdn.game.collection;

/**
 * 图鉴分类。
 *
 * <p>每个分类对应一种"玩家能收集到的东西"：物品、称号、功法、技能、秘境、
 * 游历事件、地图地点、配方。分类的条目全集由 {@link CollectionService} 在运行时
 * 从各自的注册表/数据库读出，本枚举只描述分类本身与它在配置、数据库里的键。</p>
 *
 * <p><b>为什么是枚举而不是配置驱动的动态分类</b>：每个分类的"全集从哪来"、
 * "什么时候算解锁"都得在代码里各写一段（物品是拿到、称号是获得、秘境是进入……），
 * 纯配置没办法表达这些差异。配置只负责两件真正需要运维改的事：
 * 分类开关、每条奖励发什么。</p>
 */
public enum CollectionCategory {

    ITEM("item", "物品"),
    TITLE("title", "称号"),
    TECHNIQUE("technique", "功法"),
    SKILL("skill", "技能"),
    SECRET_REALM("secret_realm", "秘境"),
    EXPLORATION_EVENT("exploration_event", "游历事件"),
    MAP_LOCATION("map_location", "地图地点"),
    RECIPE("recipe", "配方");

    private final String key;
    private final String displayName;

    CollectionCategory(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    /** 配置键 / 数据库里存的分类名（小写下划线）。 */
    public String getKey() {
        return key;
    }

    /** 中文显示名（指令与 REST 输出用）。 */
    public String getDisplayName() {
        return displayName;
    }

    /** 按配置键/别名解析分类，认不出返回 {@code null}。 */
    public static CollectionCategory fromKey(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = input.trim().toLowerCase();
        for (CollectionCategory category : values()) {
            if (category.key.equals(normalized) || category.displayName.equals(input.trim())) {
                return category;
            }
        }
        return null;
    }
}
