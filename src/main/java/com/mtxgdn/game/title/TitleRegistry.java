package com.mtxgdn.game.title;

import com.mtxgdn.game.entity.Title;

import java.util.*;

public class TitleRegistry {

    private static final Map<String, Title> TITLES = new LinkedHashMap<>();
    private static boolean initialized = false;

    private TitleRegistry() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        // === 普通称号 ===
        register(title("beginner", "初入仙途", "刚刚踏上修仙之路的新人", Title.Rarity.COMMON, 0,
                3, 0, 5, 0, 0, 0, 0.0, 0.05, 0.0));

        register(title("sword_student", "剑道学徒", "以剑入道，初窥门径", Title.Rarity.COMMON, 0,
                5, 0, 0, 0, 0, 0, 0.0, 0.05, 0.0));

        register(title("pill_apprentice", "炼丹学徒", "丹炉之侧，药香初闻", Title.Rarity.COMMON, 0,
                0, 10, 0, 0, 0, 0, 0.0, 0.05, 0.0));

        // === 稀有称号 ===
        register(title("arena_champion", "擂台王者", "百战百胜的竞技场霸主", Title.Rarity.UNCOMMON, 2,
                8, 15, 10, 8, 0, 2, 0.0, 0.08, 0.0));

        register(title("exploration_master", "秘境探险家", "踏遍天下秘境，无所畏惧", Title.Rarity.UNCOMMON, 2,
                5, 10, 10, 5, 10, 3, 0.0, 0.10, 0.05));

        register(title("millionaire", "富甲一方", "灵石堆积如山的商业巨贾", Title.Rarity.UNCOMMON, 3,
                10, 15, 15, 5, 5, 5, 0.0, 0.10, 0.05));

        // === 珍贵称号 ===
        register(title("sword_master", "剑道宗师", "剑气纵横，天下无双", Title.Rarity.RARE, 3,
                15, 20, 20, 10, 10, 10, 0.05, 0.12, 0.05));

        register(title("alchemy_lord", "丹道尊者", "一炉丹药定乾坤", Title.Rarity.RARE, 3,
                10, 50, 20, 8, 5, 8, 0.05, 0.15, 0.05));

        register(title("beast_tamer", "万兽之主", "驭百兽于掌中", Title.Rarity.RARE, 3,
                12, 30, 25, 10, 8, 8, 0.0, 0.12, 0.08));

        register(title("sky_breaker", "破天者", "突破天劫如家常便饭", Title.Rarity.RARE, 4,
                10, 30, 25, 8, 10, 15, 0.05, 0.10, 0.0));

        // === 史诗称号 ===
        register(title("immortal_venerable", "不朽尊者", "千年修行，万古长青", Title.Rarity.EPIC, 5,
                20, 50, 40, 15, 15, 20, 0.10, 0.20, 0.10));

        register(title("heaven_defier", "逆天者", "天道之下，唯我独行", Title.Rarity.EPIC, 6,
                25, 40, 50, 20, 15, 15, 0.10, 0.20, 0.10));

        register(title("sect_founder", "开山祖师", "一宗之创始人，门人万千", Title.Rarity.EPIC, 6,
                15, 80, 60, 15, 20, 25, 0.15, 0.20, 0.10));

        // === 传说称号 ===
        register(title("dao_lord", "大道之主", "执掌天道，万界俯首", Title.Rarity.LEGENDARY, 7,
                30, 100, 80, 25, 25, 30, 0.20, 0.30, 0.15));

        register(title("chaos_emperor", "混沌帝尊", "超脱三界，不在五行", Title.Rarity.LEGENDARY, 8,
                40, 150, 100, 30, 30, 40, 0.25, 0.35, 0.20));

        // === 特殊称号 ===
        register(title("senior_disciple", "首席弟子", "宗门年轻一代的翘楚", Title.Rarity.UNCOMMON, 1,
                12, 20, 20, 8, 5, 5, 0.03, 0.08, 0.0));

        register(title("event_spring", "迎春使者", "2026年春节活动限定", Title.Rarity.RARE, 2,
                20, 50, 30, 10, 10, 10, 0.05, 0.15, 0.10));

        register(title("bug_hunter", "捉虫达人", "为修仙世界除虫的功臣", Title.Rarity.EPIC, 1,
                5, 20, 20, 5, 5, 5, 0.05, 0.10, 0.05));

        register(title("raid_conqueror", "副本征服者", "给予Boss致命一击的勇士", Title.Rarity.UNCOMMON, 1,
                15, 30, 20, 10, 8, 5, 0.05, 0.12, 0.08));
    }

    private static Title title(String key, String name, String desc, Title.Rarity rarity,
                               int reqRealm, int atk, int hp, int mp, int def, int spd, int spi,
                               double cultSpd, double exp, double drop) {
        Title t = new Title();
        t.setKey(key);
        t.setName(name);
        t.setDescription(desc);
        t.setRarity(rarity);
        t.setRequiredRealm(reqRealm);
        t.setAttackBonus(atk);
        t.setHpBonus(hp);
        t.setMpBonus(mp);
        t.setDefenseBonus(def);
        t.setSpeedBonus(spd);
        t.setSpiritBonus(spi);
        t.setCultivationSpeedBonus(cultSpd);
        t.setExpBonus(exp);
        t.setDropRateBonus(drop);
        return t;
    }

    private static void register(Title title) {
        TITLES.put(title.getKey(), title);
    }

    public static Title get(String key) {
        return TITLES.get(key);
    }

    public static Collection<Title> getAll() {
        return Collections.unmodifiableCollection(TITLES.values());
    }

    public static List<Title> getByRarity(Title.Rarity rarity) {
        List<Title> result = new ArrayList<>();
        for (Title t : TITLES.values()) {
            if (t.getRarity() == rarity) result.add(t);
        }
        return result;
    }

    public static boolean exists(String key) {
        return TITLES.containsKey(key);
    }
}
