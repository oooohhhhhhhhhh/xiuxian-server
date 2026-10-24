package com.mtxgdn.game.config;

/**
 * 出厂配方数据（{@code recipes} 表的初始内容）。
 *
 * <p>从 {@code CraftingService.insertDefaultRecipes()} 里搬出来的纯数据。
 * 搬出来有两个原因：</p>
 *
 * <ul>
 *   <li>服务类里塞一张 20 列、33 行的二维数组，读代码时要在数据和
 *       {@code ps.setXxx} 样板之间来回翻，改一个数值得先拨开半屏；</li>
 *   <li>数据放在独立的 {@code config} 包里，之后要调平衡性（改成功率、改消耗）
 *       只需要动这一个文件，diff 也好看。</li>
 * </ul>
 *
 * <p><b>数值一个都没改</b>，逐行逐列搬自原实现（含最后的"高境界配方"注释分段）。
 * {@code null} 表示该材料槽位为空，插入时对应列写 NULL、数量列写 0。</p>
 *
 * <p>列顺序（与 {@code INSERT} 的 20 个占位符一一对应）：</p>
 * <pre>
 * 0 name, 1 description, 2 category, 3 required_realm,
 * 4 result_item_key, 5 result_quantity,
 * 6 material1_key, 7 material1_count, 8 material2_key, 9 material2_count,
 * 10 material3_key, 11 material3_count,
 * 12 cost_gold, 13 cost_spirit_stones, 14 success_rate,
 * 15 min_exp_gain, 16 max_exp_gain,
 * 17 high_quality_rate, 18 max_quality_rate, 19 quality_bonus_rate
 * </pre>
 */
public final class RecipeDefaults {

    /** 见类注释里的列顺序说明。 */
    public static final Object[][] ROWS = {
            {"炼制回血丹", "将灵草炼化为回血丹药，可恢复生命值", "PILL", 0, "mtxgdn:healing_pill", 2, "mtxgdn:spirit_grass", 3, null, 0, null, 0, 50L, 10L, 0.85, 20L, 60L, 0.10, 0.02, 0.2},
            {"炼制回蓝丹", "将灵草与灵石粉末炼化为回蓝丹药", "PILL", 0, "mtxgdn:mana_pill", 2, "mtxgdn:spirit_grass", 2, "mtxgdn:spirit_recovery_pill", 1, null, 0, 60L, 15L, 0.80, 30L, 70L, 0.08, 0.02, 0.2},
            {"炼制培元丹", "多种灵草炼制的高阶丹药，大幅提升修为", "PILL", 1, "mtxgdn:cultivation_elixir", 1, "mtxgdn:spirit_grass", 5, "mtxgdn:healing_pill", 2, "mtxgdn:mana_pill", 2, 200L, 50L, 0.70, 50L, 150L, 0.08, 0.02, 0.3},
            {"炼制力量丹药", "蕴含天地灵力的强力丹药，短时间内提升战力", "PILL", 2, "mtxgdn:power_buff_pill", 1, "mtxgdn:spirit_grass", 4, "mtxgdn:iron_ore", 2, "mtxgdn:spirit_recovery_pill", 2, 300L, 80L, 0.65, 80L, 200L, 0.06, 0.015, 0.3},
            {"炼制净化丹", "清除所有负面状态的神奇丹药", "PILL", 1, "mtxgdn:purification_pill", 1, "mtxgdn:spirit_grass", 3, "mtxgdn:ice_crystal", 1, null, 0, 150L, 40L, 0.75, 30L, 80L, 0.06, 0.015, 0.25},
            {"炼制复活丹", "可原地复活的神丹", "PILL", 3, "mtxgdn:rebirth_pill", 1, "mtxgdn:phoenix_feather", 2, "mtxgdn:cultivation_elixir", 2, "mtxgdn:spirit_spring_water", 3, 2000L, 800L, 0.30, 150L, 400L, 0.03, 0.005, 0.5},
            {"炼制洗髓丹", "重置属性分配的神奇丹药", "PILL", 2, "mtxgdn:attribute_reset_pill", 1, "mtxgdn:star_sand", 3, "mtxgdn:spirit_grass", 5, "mtxgdn:heaven_fire_stone", 1, 800L, 300L, 0.50, 60L, 150L, 0.05, 0.01, 0.35},
            {"炼制经验丹", "短时间内提升经验获取效率", "PILL", 2, "mtxgdn:exp_multiplier_pill", 1, "mtxgdn:spirit_grass", 4, "mtxgdn:beast_core", 2, "mtxgdn:cultivation_elixir", 1, 500L, 150L, 0.60, 50L, 120L, 0.05, 0.01, 0.25},
            {"炼制反弹丹", "反弹伤害的神奇丹药", "PILL", 1, "mtxgdn:damage_reflection_pill", 1, "mtxgdn:iron_ore", 3, "mtxgdn:beast_core", 1, "mtxgdn:demon_core", 1, 250L, 60L, 0.70, 30L, 80L, 0.06, 0.015, 0.2},
            {"炼制渡劫丹", "以天材地宝和灵泉水炼制的护体仙丹", "PILL", 3, "mtxgdn:tribulation_pill", 1, "mtxgdn:heavenly_jade", 1, "mtxgdn:spirit_spring_water", 3, "mtxgdn:cultivation_elixir", 1, 1000L, 500L, 0.50, 100L, 300L, 0.05, 0.01, 0.4},
            {"锻造灵剑", "以铁矿石为主材，融入灵石锻造的灵剑", "EQUIPMENT", 1, "mtxgdn:spirit_sword", 1, "mtxgdn:iron_ore", 5, "mtxgdn:spirit_stone_low", 3, null, 0, 400L, 100L, 0.75, 50L, 120L, 0.05, 0.01, 0.2},
            {"炼制护体玉符", "用灵草和灵石炼制的护身符，可抵挡伤害", "EQUIPMENT", 2, "mtxgdn:guardian_jade", 1, "mtxgdn:spirit_grass", 3, "mtxgdn:healing_pill", 2, "mtxgdn:iron_ore", 2, 600L, 150L, 0.70, 60L, 150L, 0.05, 0.01, 0.2},
            {"锻造龙剑", "以龙鳞为主材锻造的神兵", "EQUIPMENT", 4, "mtxgdn:dragon_sword", 1, "mtxgdn:dragon_scale", 5, "mtxgdn:heaven_fire_stone", 3, "mtxgdn:spirit_stone_low", 5, 20000L, 5000L, 0.40, 200L, 500L, 0.03, 0.005, 0.4},
            {"锻造凤甲", "以凤羽编织的神甲", "EQUIPMENT", 4, "mtxgdn:phoenix_armor", 1, "mtxgdn:phoenix_feather", 5, "mtxgdn:ice_crystal", 3, "mtxgdn:spirit_wood", 5, 25000L, 6000L, 0.35, 250L, 600L, 0.03, 0.005, 0.4},
            {"炼制麒麟护符", "蕴含麒麟之力的至尊护符", "EQUIPMENT", 5, "mtxgdn:qilin_amulet", 1, "mtxgdn:qilin_blood", 3, "mtxgdn:dragon_scale", 2, "mtxgdn:phoenix_feather", 2, 80000L, 20000L, 0.20, 500L, 1000L, 0.02, 0.003, 0.5},
            {"炼制星辰法袍", "以星砂编织的法袍", "EQUIPMENT", 3, "mtxgdn:star_robes", 1, "mtxgdn:star_sand", 5, "mtxgdn:spirit_wood", 3, "mtxgdn:spirit_stone_low", 5, 10000L, 2000L, 0.50, 100L, 300L, 0.04, 0.01, 0.3},
            {"锻造空间戒指", "以灵木和星沙锻造的神奇戒指，可增加背包容量", "EQUIPMENT", 2, "mtxgdn:space_ring", 1, "mtxgdn:spirit_wood", 5, "mtxgdn:star_sand", 3, "mtxgdn:spirit_pearl", 2, 800L, 200L, 0.60, 60L, 150L, 0.04, 0.01, 0.2},
            {"炼制灵石袋", "浓缩灵石精华而成，蕴含大量灵石能量", "CONSUMABLE", 1, "mtxgdn:spirit_stone_pouch", 1, "mtxgdn:spirit_stone_low", 5, "mtxgdn:iron_ore", 3, null, 0, 100L, 0L, 0.90, 10L, 40L, 0.03, 0.005, 0.1},
            {"炼制加速符", "以疾风灵力绘制的符箓，使用后提升速度", "CONSUMABLE", 2, "mtxgdn:speed_talisman", 1, "mtxgdn:spirit_grass", 4, "mtxgdn:mana_pill", 2, "mtxgdn:heaven_pill", 1, 500L, 120L, 0.65, 40L, 100L, 0.04, 0.01, 0.15},
            {"炼制强化石", "将铁矿石和妖兽内丹熔炼成强化石", "CONSUMABLE", 1, "mtxgdn:enhance_stone", 1, "mtxgdn:iron_ore", 3, "mtxgdn:beast_core", 1, null, 0, 200L, 30L, 0.80, 15L, 40L, 0.05, 0.01, 0.1},
            {"炼制保护符", "蕴含守护道韵的符箓，强化时可保装备不碎", "CONSUMABLE", 2, "mtxgdn:protect_charm", 1, "mtxgdn:spirit_spring_water", 2, "mtxgdn:healing_pill", 3, "mtxgdn:beast_core", 2, 400L, 80L, 0.70, 30L, 80L, 0.04, 0.01, 0.1},
            {"炼制传送符", "可传送到主城的符箓", "CONSUMABLE", 1, "mtxgdn:teleport_talisman", 1, "mtxgdn:spirit_grass", 3, "mtxgdn:star_sand", 1, null, 0, 200L, 50L, 0.85, 20L, 50L, 0.03, 0.005, 0.1},
            {"炼制超级回血丹", "千年人参炼制的高级回血丹", "PILL", 2, "mtxgdn:super_healing_pill", 1, "mtxgdn:thousand_year_ginseng", 2, "mtxgdn:spirit_grass", 3, "mtxgdn:healing_pill", 2, 500L, 150L, 0.65, 60L, 150L, 0.06, 0.015, 0.3},
            {"炼制超级回蓝丹", "血灵芝炼制的高级回蓝丹", "PILL", 2, "mtxgdn:super_mana_pill", 1, "mtxgdn:blood_lingzhi", 2, "mtxgdn:spirit_grass", 3, "mtxgdn:mana_pill", 2, 500L, 150L, 0.65, 60L, 150L, 0.06, 0.015, 0.3},
            {"炼制仙丹", "万年灵芝与天山雪莲炼制的传说级丹药", "PILL", 5, "mtxgdn:immortal_pill", 1, "mtxgdn:ten_thousand_year_lingzhi", 1, "mtxgdn:tianshan_snow_lotus", 2, "mtxgdn:cultivation_elixir", 5, 5000L, 2000L, 0.20, 300L, 800L, 0.03, 0.005, 0.5},
            {"炼制灵力大增丹", "暗冰草与月华精华炼制的灵力丹药", "PILL", 3, "mtxgdn:spirit_boost_pill", 1, "mtxgdn:dark_ice_grass", 3, "mtxgdn:moon_essence", 1, "mtxgdn:spirit_grass", 5, 1000L, 5000L, 0.50, 80L, 200L, 0.05, 0.01, 0.35},
            {"炼制至尊力量丹", "龙心与日华精华炼制的至尊丹药", "PILL", 5, "mtxgdn:ultimate_power_pill", 1, "mtxgdn:dragon_heart", 1, "mtxgdn:sun_essence", 2, "mtxgdn:power_buff_pill", 3, 10000L, 5000L, 0.15, 200L, 500L, 0.02, 0.003, 0.5},
            {"炼制灵草种子", "培育灵草的种子", "SEED", 0, "mtxgdn:spirit_grass_seed", 5, "mtxgdn:spirit_grass", 2, "mtxgdn:enhance_stone", 1, null, 0, 50L, 10L, 0.95, 5L, 20L, 0.02, 0.005, 0.1},
            {"炼制千年人参种子", "培育千年人参的珍贵种子", "SEED", 1, "mtxgdn:thousand_year_ginseng_seed", 2, "mtxgdn:thousand_year_ginseng", 1, "mtxgdn:spirit_pearl", 1, null, 0, 200L, 80L, 0.80, 20L, 50L, 0.03, 0.01, 0.15},
            {"炼制暗冰草种子", "培育暗冰草的种子", "SEED", 1, "mtxgdn:dark_ice_grass_seed", 3, "mtxgdn:dark_ice_grass", 2, "mtxgdn:ice_crystal", 1, null, 0, 100L, 30L, 0.90, 10L, 30L, 0.02, 0.005, 0.1},
            {"炼制火焰藤种子", "培育火焰藤的种子", "SEED", 1, "mtxgdn:fire_vine_seed", 3, "mtxgdn:fire_vine", 2, "mtxgdn:heaven_fire_stone", 1, null, 0, 100L, 30L, 0.90, 10L, 30L, 0.02, 0.005, 0.1},
            // ===== 高境界配方（补全合体~渡劫的制造内容） =====
            {"炼制乾坤造化丹", "万年灵药与龙血精华炼制的无上仙丹", "PILL", 6, "mtxgdn:qiankun_zaohua_dan", 1, "mtxgdn:ten_thousand_year_lingzhi", 2, "mtxgdn:dragon_blood_crystal", 3, "mtxgdn:tianshan_snow_lotus", 2, 100000L, 50000L, 0.30, 2000L, 5000L, 0.02, 0.003, 0.4},
            {"炼制天元丹", "以麒麟真血与龙心为引，炼就夺天地造化的仙丹", "PILL", 7, "mtxgdn:heaven_pill", 1, "mtxgdn:qilin_blood", 2, "mtxgdn:dragon_heart", 2, "mtxgdn:five_colored_divine_stone", 1, 300000L, 150000L, 0.20, 5000L, 10000L, 0.015, 0.002, 0.5},
    };

    /**
     * 历史配方命名修正：名称与产物对齐（仅当新名未被占用时重命名，保留原配方 ID）。
     *
     * <p>{@code [0]} 是旧名，{@code [1]} 是新名。</p>
     */
    public static final String[][] LEGACY_RENAME = {
            {"炼制聚灵石", "炼制灵石袋"},
            {"炼制九转金丹", "炼制乾坤造化丹"},
            {"炼制混沌仙丹", "炼制天元丹"},
    };
}
