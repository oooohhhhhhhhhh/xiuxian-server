package com.mtxgdn.game.entity;

import java.util.Random;

public class Monster {

    private String name;
    private int hp;
    private int maxHp;
    private int attack;
    private int defense;
    private int speed;
    private int realm;
    private boolean isBoss;
    private String[] lootTable;
    private double lootChance;
    private long expReward;
    private long goldReward;
    private long spiritStoneReward;
    private String description;

    public Monster(String name, int hp, int attack, int defense, int speed,
                   int realm, boolean isBoss, String[] lootTable, double lootChance,
                   long expReward, long goldReward, long spiritStoneReward, String description) {
        this.name = name;
        this.hp = hp;
        this.maxHp = hp;
        this.attack = attack;
        this.defense = defense;
        this.speed = speed;
        this.realm = realm;
        this.isBoss = isBoss;
        this.lootTable = lootTable;
        this.lootChance = lootChance;
        this.expReward = expReward;
        this.goldReward = goldReward;
        this.spiritStoneReward = spiritStoneReward;
        this.description = description;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getHp() { return hp; }
    public void setHp(int hp) { this.hp = hp; }
    public int getMaxHp() { return maxHp; }
    public int getAttack() { return attack; }
    public void setAttack(int attack) { this.attack = attack; }
    public int getDefense() { return defense; }
    public void setDefense(int defense) { this.defense = defense; }
    public int getSpeed() { return speed; }
    public int getRealm() { return realm; }
    public boolean isBoss() { return isBoss; }
    public String[] getLootTable() { return lootTable; }
    public double getLootChance() { return lootChance; }
    public long getExpReward() { return expReward; }
    public long getGoldReward() { return goldReward; }
    public long getSpiritStoneReward() { return spiritStoneReward; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    private static final String[] MONSTER_PREFIXES = {"烈焰", "冰霜", "暗影", "血牙", "铁甲", "疾风", "毒雾", "岩石", "幽冥", "金翅", "霜月", "邪眼", "嗜血", "枯骨"};
    private static final String[] MONSTER_NAMES = {"妖狼", "巨蟒", "魔蛛", "赤虎", "黑熊", "妖鹰", "石魔", "蛇妖", "蝎王", "魅狐", "尸鬼", "蝠妖"};

    private static final String[][] COMMON_LOOT = {
        {"spirit_grass"}, {"iron_ore"}, {"healing_pill"}, {"mana_pill"}, {"spirit_recovery_pill"}, {"beast_core"}
    };
    private static final String[][] BOSS_LOOT = {
        {"spirit_grass", "iron_ore", "healing_pill", "mana_pill", "beast_core", "enhance_stone"},
        {"cultivation_elixir", "spirit_stone_pouch", "spirit_sword", "guardian_jade", "protect_charm"},
        {"scripture_page", "basic_sword_manual", "power_buff_pill", "speed_talisman", "spirit_spring_water", "heavenly_jade"}
    };

    public static Monster random(int playerRealm, Random random) {
        int realm = Math.max(0, playerRealm);
        String monsterName = MONSTER_PREFIXES[random.nextInt(MONSTER_PREFIXES.length)]
                + MONSTER_NAMES[random.nextInt(MONSTER_NAMES.length)];

        int baseAtk = 5 + realm * 12;
        int baseDef = 2 + realm * 4;
        int baseSpd = 3 + realm * 2;
        int baseHp = 30 + realm * 35;

        int hp = baseHp + random.nextInt(realm * 20 + 10);
        int atk = baseAtk + random.nextInt(realm * 8 + 5);
        int def = baseDef + random.nextInt(realm * 3 + 2);
        int spd = baseSpd + random.nextInt(realm * 3 + 1);

        String[] loot = COMMON_LOOT[random.nextInt(COMMON_LOOT.length)];
        double lootChance = 0.30 + random.nextDouble() * 0.15;
        long exp = realm * 80L + random.nextLong(realm * 40L + 10);
        long gold = random.nextLong(realm * 20L + 10);
        long ss = random.nextLong(realm * 10L + 5);

        return new Monster(monsterName, hp, atk, def, spd, realm, false,
                loot, lootChance, exp, gold, ss, "一只游荡的妖兽");
    }

    public static Monster createBoss(String name, int realm, String description, Random random) {

        int hp = 80 + realm * 120 + random.nextInt(realm * 60 + 30);
        int atk = 15 + realm * 30 + random.nextInt(realm * 15 + 10);
        int def = 8 + realm * 12 + random.nextInt(realm * 6 + 5);
        int spd = 5 + realm * 5 + random.nextInt(realm * 4 + 3);

        String[] loot = BOSS_LOOT[random.nextInt(BOSS_LOOT.length)];
        double lootChance = 0.60 + random.nextDouble() * 0.25;
        long exp = realm * 250L + random.nextLong(realm * 120L + 50);
        long gold = random.nextLong(realm * 80L + 30);
        long ss = random.nextLong(realm * 40L + 20);

        return new Monster(name, hp, atk, def, spd, realm, true,
                loot, lootChance, exp, gold, ss, description);
    }

    public static Monster createBossForRealm(int realm, Random random) {
        int effectiveRealm = Math.max(0, realm - 1);
        String name;
        String desc;
        switch (effectiveRealm) {
            case 0:
                name = "草原霸主·奔雷兽";
                desc = "荒野草原的王者，一身皮毛刀枪不入，奔袭间雷光闪烁";
                break;
            case 1:
                name = "暗影领主·噬魂蛛皇";
                desc = "盘踞在幽暗森林深处的巨型蛛皇，毒液可腐蚀修士的护体灵气";
                break;
            case 2:
                name = "兽王·金翼裂天雕";
                desc = "百兽山脉的绝对统治者，双翅展开遮天蔽日，爪可裂金断石";
                break;
            case 3:
                name = "遗迹守卫·青铜巨像";
                desc = "上古遗迹的守护者，由青铜铸就的巨像，拳风可摧山岳";
                break;
            case 4:
                name = "战场英魂·不灭战将";
                desc = "古战场中残留的英魂所化，战意不灭，杀伐之气冲天";
                break;
            case 5:
                name = "洞府守护灵·九霄剑魂";
                desc = "仙人遗留的剑意化形而成，剑气纵横三万里，一剑光寒十九州";
                break;
            default:
                name = "远古魔神·混沌之影";
                desc = "来自鸿蒙初辟的远古存在，混沌之力令人望而生畏";
                break;
        }
        return createBoss(name, Math.max(1, effectiveRealm), desc, random);
    }
}
