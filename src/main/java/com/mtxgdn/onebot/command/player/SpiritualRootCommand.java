package com.mtxgdn.onebot.command.player;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SpiritualRoot;

public class SpiritualRootCommand extends Command {

    public SpiritualRootCommand() {
        super(new String[]{"灵根", "spiritualroot", "talent"},
                "查看角色的灵根天赋",
                "/灵根",
                "我的角色",
                null);
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        SpiritualRoot root = p.getSpiritualRoot();
        if (root == null) {
            ctx.reply("你尚未觉醒灵根，请先创建角色。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔮 灵根天赋\n");
        sb.append("══════════\n");
        sb.append("灵根：").append(root.getDisplayName()).append("\n");
        sb.append("品级：").append(root.getTier().getDisplayName()).append("\n");
        sb.append("描述：").append(root.getDescription()).append("\n\n");

        sb.append("══ 属性加成 ══\n");
        if (root.getAttackBonus() != 0) sb.append("攻击 +").append(root.getAttackBonus()).append("\n");
        if (root.getHpBonus() != 0) sb.append("生命 +").append(root.getHpBonus()).append("\n");
        if (root.getMpBonus() != 0) sb.append("法力 +").append(root.getMpBonus()).append("\n");
        if (root.getSpiritBonus() != 0) sb.append("神识 +").append(root.getSpiritBonus()).append("\n");
        if (root.getDefenseBonus() != 0) sb.append("防御 +").append(root.getDefenseBonus()).append("\n");
        if (root.getSpeedBonus() != 0) sb.append("速度 +").append(root.getSpeedBonus()).append("\n");

        if (root.getEffect() != SpiritualRoot.SpecialEffect.NONE) {
            sb.append("\n特殊效果：").append(root.getEffect().name()).append("\n");
            if (root.getEffectValue() != 0) {
                sb.append("效果值：").append(formatEffect(root.getEffect(), root.getEffectValue())).append("\n");
            }
        }

        ctx.reply(sb.toString());
    }

    private String formatEffect(SpiritualRoot.SpecialEffect effect, double value) {
        return switch (effect) {
            case CULTIVATION_EFFICIENCY -> "+" + (int)(value * 100) + "% 修炼效率";
            case CRIT_CHANCE -> "+" + (int)(value * 100) + "% 暴击率";
            case TRADE_FEE_HALF -> "交易手续费减半";
            case DAMAGE_BOOST -> "+" + (int)(value * 100) + "% 伤害";
            case DAMAGE_REDUCTION -> "+" + (int)(value * 100) + "% 减伤";
            case SKILL_DAMAGE -> "+" + (int)(value * 100) + "% 技能伤害";
            case REGENERATION -> "+" + (int)(value) + "% 回复速率";
            case MP_COST_REDUCTION -> "-" + (int)(value * 100) + "% 法力消耗";
            case EXPLORATION_CD -> "-" + (int)(value * 100) + "% 探索冷却";
            case CRIT_DAMAGE -> "+" + (int)(value * 100) + "% 暴击伤害";
            case MONSTER_EXP -> "+" + (int)(value * 100) + "% 怪物经验";
            case SPIRIT_STONE_DROP -> "+" + (int)(value * 100) + "% 灵石掉落";
            case PROFICIENCY_BOOST -> "+" + (int)(value * 100) + "% 熟练度";
            default -> String.valueOf(value);
        };
    }
}
