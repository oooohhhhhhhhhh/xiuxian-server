package com.mtxgdn.game.config;

import java.util.List;

/**
 * 地图默认数据（新库首次安装 / 老库补齐缺失项的唯一数据源）。
 *
 * <p>历史版本把「地点灵气加成」硬编码在 {@code MapService} 的常量表里，导致后台改库无法调整、
 * 新增地点永远没有加成。现已改为随地点行一起入库（{@code map_locations.cultivation_bonus} /
 * {@code exploration_bonus}），本类只保留"出厂值"。</p>
 *
 * <p>本类为纯数据，不依赖任何其它包，可被服务层与迁移逻辑共用。</p>
 */
public final class MapDefaults {

    private MapDefaults() {
    }

    /**
     * 一个出厂地点的全部字段。
     *
     * @param cultivationBonus 闭关修炼加成倍率（灵气浓郁程度）
     * @param explorationBonus 游历 / 秘境收益倍率（越凶险的地界产出越高）
     * @param dangerLevel 危险度 0~4（0=安全 1=低危 2=中危 3=高危 4=极危）。
     *                    影响 PK 规则（0 禁止私斗，≥3 败者损失灵石）与野外资源产出。
     *                    <b>不变式：{@code safeZone} 为 true 的地点危险度必须为 0</b>
     *                    （安全区不可能同时是险地），由 {@code MapService} 加载时校验。
     */
    public record LocationSeed(long id, String name, String description, String region,
                               int minRealm, boolean safeZone,
                               double cultivationBonus, double explorationBonus,
                               int dangerLevel) {
    }

    /** 地点 → 探索事件权重偏置（某地界更容易触发的事件）。 */
    public record EventBiasSeed(long locationId, String eventKey, double weightMultiplier) {
    }

    /** 15 个出厂地点。id 一旦发布即不可变更（玩家存档按 id 引用）。 */
    public static final List<LocationSeed> LOCATIONS = List.of(
            new LocationSeed(1L, "青云村",
                    "青山环绕的小村庄，灵气稀薄，是修仙者踏入仙途的第一站。村口的老槐树下，立着一块刻满道纹的石碑。",
                    "新手区域", 0, true, 1.00, 1.00, 0),
            new LocationSeed(2L, "青云山",
                    "青云村后山，山腰云雾缭绕，时有仙鹤飞过。传说山中藏有先贤洞府，只是从未有人寻得。",
                    "新手区域", 0, false, 1.05, 1.05, 1),
            new LocationSeed(3L, "苍月镇",
                    "坐落在苍月山脚下的小镇，因出产月华石而闻名。镇中坊市热闹非凡，往来的散修络绎不绝。",
                    "新手区域", 1, true, 1.05, 1.05, 0),
            new LocationSeed(4L, "太虚城",
                    "中原修仙第一大城，城墙高达百丈，城中央的太虚塔直入云霄。各大宗门在此设有驻地，坊市内珍品云集。",
                    "中原", 2, true, 1.15, 1.10, 0),
            new LocationSeed(5L, "落霞山",
                    "山势险峻，落日时分霞光万丈，故得名。山中妖兽横行，却也是筑基丹主材「落霞草」的唯一产地。",
                    "中原", 2, false, 1.10, 1.20, 2),
            new LocationSeed(6L, "碧水潭",
                    "一汪深不见底的碧色潭水，潭底据说连通东海龙宫。潭边灵气充沛，常有金丹修士在此打坐修炼。",
                    "中原", 3, false, 1.30, 1.15, 1),
            new LocationSeed(7L, "昆仑墟",
                    "上古神山昆仑的遗迹，山体早已崩碎，只剩下悬浮在虚空中的残垣断壁。唯有元婴期以上修士方能踏足此地。",
                    "西域", 4, false, 1.30, 1.45, 3),
            new LocationSeed(8L, "九幽渊",
                    "深不见底的黑暗深渊，传闻是九幽之地的入口。渊中阴气弥漫，滋生无数邪祟妖物，化神修士亦不敢轻入。",
                    "南荒", 5, false, 1.20, 1.40, 4),
            new LocationSeed(9L, "天元古城",
                    "远古修仙文明的遗迹，城中遍布上古禁制与阵法遗迹。合体期修士在此寻宝，一步不慎便可能触发禁制。",
                    "远古遗迹", 6, false, 1.35, 1.50, 4),
            new LocationSeed(10L, "问道峰",
                    "修仙界的至高圣地。峰顶立有历代飞升者留下的问道碑，传说登顶者可感悟天道，破境飞升。",
                    "圣地", 7, false, 1.50, 1.35, 2),
            new LocationSeed(11L, "东海龙宫",
                    "碧波万顷之下，隐匿着龙族的巍峨宫殿。宫墙由珊瑚砌成，夜明珠照亮百里海疆，机缘与凶险并存。",
                    "东海", 4, false, 1.40, 1.55, 3),
            new LocationSeed(12L, "万妖谷",
                    "南荒深处的妖兽聚集之地，谷中妖气冲天。此地妖兽内丹品质极高，是炼丹师梦寐以求的猎场。",
                    "南荒", 3, false, 1.20, 1.35, 3),
            new LocationSeed(13L, "灵药谷",
                    "灵气氤氲的世外桃源，谷中灵田常年不冻，四季如春。传言这里的土壤天生适宜灵植生长。",
                    "中原", 1, false, 1.10, 1.25, 1),
            new LocationSeed(14L, "天机阁",
                    "隐匿于太虚城中的神秘势力，号称上知天命、下晓地理。阁中出售各种奇门遁甲与推演秘术。",
                    "中原", 2, true, 1.15, 1.10, 0),
            new LocationSeed(15L, "白骨岭",
                    "西域大漠中的荒凉山岭，累累白骨铺满山道。此地阴魂不散，常有邪修出没，以死者怨气练功。",
                    "西域", 4, false, 1.25, 1.30, 3)
    );

    /** 出厂连通关系（无向，落库时双向写入）。 */
    public static final long[][] CONNECTIONS = {
            {1, 2}, {2, 3}, {3, 4}, {4, 5}, {4, 6}, {5, 7}, {6, 8}, {7, 9}, {9, 10},
            {4, 14}, {3, 13}, {4, 13}, {7, 15}, {8, 11}, {6, 11}, {5, 12}, {13, 6}
    };

    /**
     * 各地界的探索事件权重偏置：让地点描述里的承诺（落霞草产地、万妖谷妖兽、灵药谷灵植、
     * 天元古城阵法遗迹…）真正落到事件抽取上，而不是只写在文案里。
     */
    public static final List<EventBiasSeed> EVENT_BIAS = List.of(
            new EventBiasSeed(2L, "mystery", 1.6),          // 青云山：先贤洞府
            new EventBiasSeed(2L, "ruins", 1.6),
            new EventBiasSeed(3L, "merchant", 2.0),         // 苍月镇：坊市
            new EventBiasSeed(4L, "merchant", 2.0),         // 太虚城：第一大城坊市
            new EventBiasSeed(4L, "nothing", 1.5),
            new EventBiasSeed(5L, "material", 2.6),         // 落霞山：落霞草唯一产地
            new EventBiasSeed(5L, "monster", 1.3),          // 落霞山：妖兽横行
            new EventBiasSeed(6L, "spirit_spring", 2.6),    // 碧水潭：灵气充沛
            new EventBiasSeed(7L, "ruins", 2.6),            // 昆仑墟：上古遗迹
            new EventBiasSeed(7L, "strange_tower", 2.0),
            new EventBiasSeed(8L, "monster", 2.0),          // 九幽渊：邪祟妖物
            new EventBiasSeed(8L, "trap", 1.6),
            new EventBiasSeed(9L, "ruins", 3.0),            // 天元古城：阵法遗迹
            new EventBiasSeed(9L, "trap", 1.5),             // 天元古城：上古禁制
            new EventBiasSeed(10L, "cultivator", 3.0),      // 问道峰：问道碑悟道
            new EventBiasSeed(11L, "treasure", 3.0),        // 东海龙宫：机缘
            new EventBiasSeed(11L, "mystery", 1.5),
            new EventBiasSeed(12L, "monster", 3.0),         // 万妖谷：妖兽内丹
            new EventBiasSeed(13L, "herb", 3.0),            // 灵药谷：灵植
            new EventBiasSeed(13L, "seed", 2.2),
            new EventBiasSeed(14L, "mystery", 2.6),         // 天机阁：推演秘术
            new EventBiasSeed(15L, "trap", 2.0),            // 白骨岭：邪修阴魂
            new EventBiasSeed(15L, "monster", 1.6)
    );
}
