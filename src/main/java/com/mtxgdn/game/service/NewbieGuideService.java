package com.mtxgdn.game.service;

import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SpiritualRoot;

public class NewbieGuideService {

    private final PlayerService playerService = new PlayerService();

    private static final int GUIDE_COMPLETE = -1;

    private static final int FLAG_SPIRITUAL_ROOT = 1;
    private static final int FLAG_MARKET = 2;
    private static final int FLAG_PVP = 4;
    private static final int FLAG_SKILLS = 8;
    private static final int FLAG_EQUIPMENT = 16;
    private static final int FLAG_MORNING = 32;
    private static final int FLAG_DAILY = 64;
    private static final int FLAG_HELP = 128;
    private static final int FLAG_ITEM_USE = 256;
    private static final int FLAG_BACKPACK = 512;
    private static final int FLAG_EQUIP = 2048;

    private static final String[][] ROOT_EASTER_EGGS = {
        { SpiritualRoot.TAIYI_GOLDEN.name(), "太乙金灵根——锐金之气贯体，天灵根中的攻伐之王！" },
        { SpiritualRoot.CHAOS_MIXED.name(), "混沌灵根……世人称之为杂灵根，但混沌之中蕴藏无限可能。不要放弃！" },
        { SpiritualRoot.EARTHEN_GOLD.name(), "土金灵根——金石为开，坊市交易天生优惠。" },
        { SpiritualRoot.JINGLEI_THUNDER.name(), "惊雷灵根——天威浩荡，一击惊雷！暴击伤害翻倍。" },
        { SpiritualRoot.XUANBING_ICE.name(), "玄冰灵根——冰封万里，寒彻骨髓。技能伤害更高。" },
    };

    public static class GuideResult {
        public String message;
    }

    public GuideResult checkAndAdvance(long playerId, PlayerInfo player, String action) {
        GuideResult result = new GuideResult();
        int step = player.getTutorialStep();

        switch (action) {
            case "create" -> {
                result.message = buildCreateMessage(player);
                advanceStep(playerId, 2);
            }
            case "cultivate_start" -> {
                if (step == 2) {
                    advanceStep(playerId, 3);
                    result.message = """
                        很好！修炼可以挂机获得经验。过一会儿用 /停止 来结束修炼结算修为。

                        💡 空闲时可以试试 /状态 看看自己的属性变化，或者 /灵根 了解天赋加成。""";
                }
            }
            case "cultivate_stop" -> {
                if (step == 3) {
                    advanceStep(playerId, 4);
                    result.message = """
                        修为提升了！现在去广阔天地探索吧。
                        主线：/游历
                        💡 你也可以看看 /背包，或去 /坊市 看看交易市场。""";
                }
            }
            case "explore" -> {
                if (step == 4) {
                    advanceStep(playerId, 5);
                    result.message = """
                        世界真大！探索会触发各种事件，有的有收益，有的有风险。
                        主线：/秘境 查看可探索的秘境福地
                        💡 也可以 /技能 看看能学什么仙术，或试试 /晨修 获取每日天象加成！""";
                }
            }
            case "secret_areas" -> {
                if (step == 5) {
                    advanceStep(playerId, 6);
                    result.message = """
                        每个秘境都有境界要求，选一个能进的！
                        主线：/进入秘境 <名称>
                        💡 秘境有冷却时间，合理规划很重要哦。""";
                }
            }
            case "secret_enter" -> {
                if (step == 6) {
                    advanceStep(playerId, 7);
                    result.message = """
                        秘境充满机遇！灵石、金币、稀有物品都有可能掉落。
                        现在试试突破境界吧！
                        主线：/突破
                        💡 高境界突破会触发天劫，做好心理准备！""";
                }
            }
            case "breakthrough" -> {
                if (step == 7) {
                    advanceStep(playerId, 8);
                    result.message = """
                        🎉 恭喜！你已步入修仙正轨！

                        继续修炼、探索秘境、学习技能、挑战天劫——
                        修行之路漫漫，道心始坚。

                        输入 /帮助 查看全部指令。
                        💡 进阶玩法：/坊市玩交易、/晨修拿每日奖励、收集技能书提升技能等级。""";
                }
            }
            case "morning" -> {
                if (step == 8) {
                    advanceStep(playerId, GUIDE_COMPLETE);
                    result.message = """
                        🌅 每日晨修完成！紫气东来，道心澄明。

                        你已经掌握了修仙的基础玩法。从现在开始——
                        自主探索这片广阔的修仙世界吧！

                        📖 /帮助 查看完整指令
                        🛒 /坊市 玩家交易市场
                        ⚔️ /技能 学习仙术秘籍
                        🏆 突破更高境界，成为一方大能！

                        祝你道运昌隆！""";
                }
            }
        }

        return result;
    }

    public String checkDiscovery(long playerId, PlayerInfo player, String action) {
        int tips = player.getTutorialTips();
        int flag;

        switch (action) {
            case "spiritual_root" -> flag = FLAG_SPIRITUAL_ROOT;
            case "market" -> flag = FLAG_MARKET;
            case "pvp" -> flag = FLAG_PVP;
            case "skills" -> flag = FLAG_SKILLS;
            case "equipment" -> flag = FLAG_EQUIPMENT;
            case "morning_tip" -> flag = FLAG_MORNING;
            case "daily" -> flag = FLAG_DAILY;
            case "help" -> flag = FLAG_HELP;
            case "item_use" -> flag = FLAG_ITEM_USE;
            case "backpack" -> flag = FLAG_BACKPACK;
            case "equip" -> flag = FLAG_EQUIP;
            default -> { return null; }
        }

        if ((tips & flag) != 0) return null;

        playerService.setTutorialTips(playerId, tips | flag);

        return switch (action) {
            case "spiritual_root" -> buildSpiritualRootTip(player);
            case "market" -> buildMarketTip();
            case "pvp" -> buildPvpTip();
            case "skills" -> buildSkillsTip();
            case "equipment" -> buildEquipmentTip();
            case "morning_tip" -> buildMorningTip();
            case "daily" -> buildDailyTip();
            case "help" -> "💡 /帮助 列出了所有可用指令。遇到不会的随时回来看看！";
            case "item_use" -> "💡 使用物品：/使用 <物品key>。在 /背包 中查看你的物品清单。";
            case "backpack" -> "💡 背包里的物品可以 /使用，也可以挂到 /坊市 上交易（卖成灵石哦！）。";
            case "equip" -> """
                    ⚔️ 穿戴装备，战力飙升！

                    ‣ /装备 <物品key> <部位> 穿装备
                    ‣ /卸下 <部位> 脱装备
                    ‣ /已装备 查看穿戴情况
                    ‣ 部位：weapon(武器) | armor(防具) | accessory(饰品)

                    💡 装备在秘境中掉落率更高！""";
            default -> null;
        };
    }

    private String buildSpiritualRootTip(PlayerInfo player) {
        SpiritualRoot root = player.getSpiritualRoot();
        if (root == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("🔮 你的灵根：【").append(root.getDisplayName())
                .append("】（").append(root.getTier().getDisplayName()).append("）\n\n");
        sb.append("灵根决定你的天赋，影响以下方面：\n");
        sb.append("‣ 修炼效率与属性成长\n");
        sb.append("‣ 渡劫成功率（天灵根+15%，杂灵根-10%）\n");
        sb.append("‣ 心魔抗性（神识越强越不容易走火入魔）\n");
        sb.append("‣ 特殊效果：");
        if (root.getEffect() != SpiritualRoot.SpecialEffect.NONE) {
            sb.append(root.getEffect().name());
        } else {
            sb.append("无");
        }
        sb.append("\n\n更多细节请看 /帮助 或自行探索！");
        return sb.toString();
    }

    private String buildMarketTip() {
        return """
            🛒 欢迎来到坊市——玩家交易市场！

            ‣ 挂单出售物品，获得灵石
            ‣ 使用灵石购买他人挂售的物品
            ‣ 每笔交易收取 5% 手续费（灵石）
            ‣ 土金灵根可以享受更低的手续费率

            💡 灵石是硬通货，别乱花！""";
    }

    private String buildPvpTip() {
        return """
            ⚔️ 修仙世界的残酷竞争！

            ‣ /pvp <玩家名> ：挑战指定修士
            ‣ 挑战消耗体力，失败会受伤
            ‣ 谨慎选择对手！

            💡 建议突破到筑基境后再尝试。""";
    }

    private String buildSkillsTip() {
        return """
            📜 仙术秘籍——传承的力量！

            ‣ /技能 ：查看你能学习的技能列表
            ‣ /学习 <技能ID> ：消耗灵石/金币学习
            ‣ 每次使用技能可增加熟练度
            ‣ 收集同名技能书可直接升1级

            💡 技能消耗法力（MP），等级越高消耗越大！""";
    }

    private String buildEquipmentTip() {
        return """
            🛡️ 装备系统提示

            ‣ 在秘境中可以获得稀有装备
            ‣ 装备提供攻击、防御、速度等加成
            ‣ 查看背包中的装备道具说明

            💡 不同装备适合不同的流派玩法！""";
    }

    private String buildMorningTip() {
        return """
            🌅 紫气东来——每日晨修

            ‣ /晨修 每天只能使用一次
            ‣ 获得随机天象加成（持续24小时）
            ‣ 天象影响修炼效率、游历收获、秘境掉率

            💡 每天上线第一件事就是晨修！""";
    }

    private String buildDailyTip() {
        return """
            📅 今日天象与机缘

            ‣ /日常 查看今天的特殊效果
            ‣ 天象每天刷新，影响战斗和探索
            ‣ 每日任务可以获得额外灵石

            💡 签到和日常不会显得突兀——它们本身就是天道的一部分！""";
    }

    private String buildCreateMessage(PlayerInfo player) {
        SpiritualRoot root = player.getSpiritualRoot();
        StringBuilder sb = new StringBuilder();
        sb.append("✨ 角色创建成功！\n\n");
        sb.append("════ 角色信息 ════\n");
        sb.append("姓名：").append(player.getName()).append("\n");
        sb.append("灵根：").append(root != null ? root.getDisplayName() : "未知")
                .append("（").append(root != null ? root.getTier().getDisplayName() : "").append("）\n");
        if (root != null) {
            sb.append("效果：");
            if (root.getEffect() != SpiritualRoot.SpecialEffect.NONE) {
                sb.append(root.getEffect().name());
            } else {
                sb.append("无特殊效果");
            }
            sb.append("\n");
        }
        sb.append("\n");
        for (String[] egg : ROOT_EASTER_EGGS) {
            if (root != null && root.name().equals(egg[0])) {
                sb.append("✨ ").append(egg[1]).append("\n\n");
                break;
            }
        }
        sb.append("════ 新手引导 ════\n");
        sb.append("主线任务 丨 自由探索（💡）\n\n");
        sb.append("1️⃣ 先来闭关修炼：/修炼\n");
        sb.append("2️⃣ 停止修炼：/停止\n");
        sb.append("3️⃣ 游历探索：/游历\n");
        sb.append("4️⃣ 秘境探宝：/秘境 → /进入秘境\n");
        sb.append("5️⃣ 境界突破：/突破\n");
        sb.append("6️⃣ 每日晨修：/晨修\n");
        sb.append("7️⃣ 修士，出师了！/帮助\n");
        sb.append("\n遇到 💡 的提示时，可以停下来自己探索哦。");
        return sb.toString();
    }

    public static String getWelcomeMessage(PlayerInfo player) {
        return new NewbieGuideService().buildCreateMessage(player);
    }

    private void advanceStep(long playerId, int step) {
        playerService.setTutorialStep(playerId, step);
    }
}
