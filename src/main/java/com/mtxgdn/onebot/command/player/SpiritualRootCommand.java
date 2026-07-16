package com.mtxgdn.onebot.command.player;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.LangManager;

public class SpiritualRootCommand extends Command {

    public static final String QIANKUN_DAN_KEY = "mtxgdn:qiankun_zaohua_dan";
    public static final long RECAST_SPIRIT_STONE_COST = 500000;

    public SpiritualRootCommand() {
        super(new String[]{"灵根", "spiritualroot", "talent"},
                "查看角色的灵根天赋",
                "/灵根 [重铸]",
                "我的角色",
                null);

        registerSub(new String[]{"重铸", "recast"}, this::doRecast);
    }

    @Override
    public void execute(CommandContext ctx) {
        String arg = ctx.getArg();
        if (arg != null && !arg.trim().isEmpty()) {
            super.execute(ctx);
            return;
        }

        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        showRoot(ctx, p);
    }

    private void showRoot(CommandContext ctx, PlayerInfo p) {
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
            String effectKey = "spiritualroot.effect." + root.getEffect().name().toLowerCase();
            String effectName = LangManager.get(effectKey, root.getEffect().name());
            sb.append("\n特殊效果：").append(effectName).append("\n");
            if (root.getEffectValue() != 0) {
                sb.append("效果值：").append(formatEffect(root.getEffect(), root.getEffectValue())).append("\n");
            }
        }

        ctx.reply(sb.toString());
    }

    private void doRecast(CommandContext ctx, PlayerInfo p, String[] parts) {
        int realm = p.getRealm();
        if (realm < 3) {
            ctx.reply("灵根重铸需要达到【金丹期】及以上境界，你当前境界不足以重铸灵根。");
            return;
        }

        var itemService = ServiceRegistry.getItemService();
        if (!itemService.hasItem(p.getId(), QIANKUN_DAN_KEY, 1)) {
            ctx.reply("灵根重铸需要消耗道具【乾坤造化丹】x1，你的背包中没有该物品。");
            return;
        }

        long spiritStones = itemService.getSpiritStoneCount(p.getId());
        if (spiritStones < RECAST_SPIRIT_STONE_COST) {
            ctx.reply("灵根重铸需要消耗 " + RECAST_SPIRIT_STONE_COST + " 灵石（等值下品灵石），你当前仅有 " + spiritStones + "。");
            return;
        }

        itemService.removeItem(p.getId(), QIANKUN_DAN_KEY, 1);

        SpiritualRoot oldRoot = p.getSpiritualRoot();
        SpiritualRoot newRoot = SpiritualRoot.drawRandomForRecast(new java.util.Random());
        var playerService = ServiceRegistry.getPlayerService();
        playerService.updateSpiritualRoot(p.getId(), newRoot);

        StringBuilder sb = new StringBuilder();
        sb.append("🔮 灵根重铸\n");
        sb.append("══════════\n");
        sb.append("原有灵根：").append(oldRoot != null ? oldRoot.getDisplayName() : "无").append("\n");
        sb.append("重铸结果：").append(newRoot.getDisplayName()).append("\n");
        sb.append("品级：").append(newRoot.getTier().getDisplayName()).append("\n");
        sb.append("描述：").append(newRoot.getDescription()).append("\n");
        sb.append("\n消耗：乾坤造化丹 x1 + ").append(RECAST_SPIRIT_STONE_COST).append(" 灵石");

        ctx.reply(sb.toString());

        com.mtxgdn.util.PlayerActionLogger.getInstance().logCustom(
                p.getId(), "灵根", "重铸",
                oldRoot + " -> " + newRoot.name());
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
