package com.mtxgdn.game.entity;

import java.util.Arrays;
import java.util.List;

public class BossForm {

    private String name;
    private String description;
    private double hpMultiplier;
    private double attackMultiplier;
    private double defenseMultiplier;
    private double speedMultiplier;
    private double playerDebuffPercent;
    private String[] rewardTable;
    private double rewardChance;

    public BossForm() {}

    public BossForm(String name, String description, double hpMultiplier,
                    double attackMultiplier, double defenseMultiplier, double speedMultiplier,
                    double playerDebuffPercent, String[] rewardTable, double rewardChance) {
        this.name = name;
        this.description = description;
        this.hpMultiplier = hpMultiplier;
        this.attackMultiplier = attackMultiplier;
        this.defenseMultiplier = defenseMultiplier;
        this.speedMultiplier = speedMultiplier;
        this.playerDebuffPercent = playerDebuffPercent;
        this.rewardTable = rewardTable;
        this.rewardChance = rewardChance;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public double getHpMultiplier() { return hpMultiplier; }
    public void setHpMultiplier(double hpMultiplier) { this.hpMultiplier = hpMultiplier; }

    public double getAttackMultiplier() { return attackMultiplier; }
    public void setAttackMultiplier(double attackMultiplier) { this.attackMultiplier = attackMultiplier; }

    public double getDefenseMultiplier() { return defenseMultiplier; }
    public void setDefenseMultiplier(double defenseMultiplier) { this.defenseMultiplier = defenseMultiplier; }

    public double getSpeedMultiplier() { return speedMultiplier; }
    public void setSpeedMultiplier(double speedMultiplier) { this.speedMultiplier = speedMultiplier; }

    public double getPlayerDebuffPercent() { return playerDebuffPercent; }
    public void setPlayerDebuffPercent(double playerDebuffPercent) { this.playerDebuffPercent = playerDebuffPercent; }

    public String[] getRewardTable() { return rewardTable; }
    public void setRewardTable(String[] rewardTable) { this.rewardTable = rewardTable; }

    public double getRewardChance() { return rewardChance; }
    public void setRewardChance(double rewardChance) { this.rewardChance = rewardChance; }

    private static final String[][] RAID_REWARD_TABLES = {
        {"mtxgdn:spirit_grass", "mtxgdn:iron_ore", "mtxgdn:healing_pill", "mtxgdn:mana_pill", "mtxgdn:beast_core"},
        {"mtxgdn:cultivation_elixir", "mtxgdn:spirit_stone_pouch", "mtxgdn:spirit_sword", "mtxgdn:guardian_jade"},
        {"mtxgdn:scripture_page", "mtxgdn:basic_sword_manual", "mtxgdn:power_buff_pill", "mtxgdn:speed_talisman"},
        {"mtxgdn:dragon_blood_crystal", "mtxgdn:enhance_stone", "mtxgdn:fire_dragon_art", "mtxgdn:jade_armor"},
        {"mtxgdn:ancient_artifact", "mtxgdn:immortal_essence", "mtxgdn:celestial_pearl", "mtxgdn:void_crystal"},
        {"mtxgdn:world_core", "mtxgdn:origin_stone", "mtxgdn:dao_crystal", "mtxgdn:eternal_heart"}
    };

    public static List<BossForm> createFormsForRealm(int realm) {
        String[][] names = {
            {"草原霸主·奔雷兽", "雷霆领主·风暴化身", "远古雷神·天罚降临"},
            {"暗影领主·噬魂蛛皇", "深渊女王·万毒之母", "虚空织网者·次元吞噬"},
            {"兽王·金翼裂天雕", "苍穹主宰·鲲鹏化身", "太古神鸟·涅槃重生"},
            {"遗迹守卫·青铜巨像", "上古战魂·钢铁意志", "混沌泰坦·世界毁灭者"},
            {"战场英魂·不灭战将", "修罗战神·血刃狂魔", "冥界主宰·黄泉帝君"},
            {"洞府守护灵·九霄剑魂", "万剑之尊·剑灵真身", "天道裁决者·一剑斩天"}
        };

        String[][] descs = {
            {"荒野草原的王者，一身皮毛刀枪不入", "雷霆之力凝聚而成的风暴化身", "远古雷神，天罚之力无人能挡"},
            {"幽暗森林深处的巨型蛛皇", "深渊万毒之母，毒液腐蚀一切", "能编织次元之网的虚空存在"},
            {"百兽山脉统治者，双翅遮天蔽日", "传说鲲鹏化身，一振翅九万里", "涅槃重生的太古神鸟"},
            {"上古遗迹守护者，青铜铸就巨像", "钢铁意志的上古战魂", "能毁灭世界的混沌泰坦"},
            {"古战场英魂所化，战意不灭", "经历无数杀戮的修罗战神", "冥界黄泉帝君"},
            {"仙人剑意化形", "万剑之尊剑灵真身", "裁决天道的至高存在"}
        };

        int idx = Math.min(realm, names.length - 1);
        String[] rewards = RAID_REWARD_TABLES[Math.min(realm, RAID_REWARD_TABLES.length - 1)];

        return Arrays.asList(
            new BossForm(names[idx][0], descs[idx][0], 1.0, 1.0, 1.0, 1.0, 0.05, rewards, 0.5),
            new BossForm(names[idx][1], descs[idx][1], 1.4, 1.3, 1.2, 1.1, 0.10, rewards, 0.65),
            new BossForm(names[idx][2], descs[idx][2], 2.0, 1.6, 1.4, 1.2, 0.15, rewards, 0.8)
        );
    }
}