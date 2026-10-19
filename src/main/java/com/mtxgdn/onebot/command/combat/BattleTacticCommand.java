package com.mtxgdn.onebot.command.combat;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.Set;
import com.mtxgdn.i18n.I18n;

public class BattleTacticCommand extends Command {

    private static final Set<String> AGGRESSIVE_ALIASES = Set.of("猛攻", "aggressive", "进攻", "猛");
    private static final Set<String> BALANCED_ALIASES = Set.of("均衡", "balanced", "平衡", "均");
    private static final Set<String> DEFENSIVE_ALIASES = Set.of("防守", "defensive", "防御", "守");

    public BattleTacticCommand() {
        super(new String[]{"战斗策略", "战术", "tactic"}, "设置战斗策略", "/战斗策略 <猛攻|均衡|防守>", "战斗", null);
        
        setI18nKeys("cmd.battle_tactic.desc", "cmd.battle_tactic.usage");
    setModule("skill");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg().trim();
        if (arg.isEmpty()) {
            showCurrent(ctx, p);
            return;
        }

        String tactic = resolveTactic(arg);
        if (tactic == null) {
            ctx.reply(I18n.t("combat.hbcd08f60", arg));
            return;
        }

        var playerService = ServiceRegistry.getPlayerService();
        playerService.updateBattleStrategy(p.getId(), tactic);

        String display = switch (tactic) {
            case "aggressive" -> "🔥 猛攻 — 优先使用最强技能，全力输出";
            case "defensive" -> "🛡 防守 — 保留一半法力，稳扎稳打";
            default -> "⚖ 均衡 — 不偏不倚，见招拆招";
        };
        ctx.reply(I18n.t("combat.h48e22b10", display));
    }

    private void showCurrent(CommandContext ctx, PlayerInfo p) {
        var playerService = ServiceRegistry.getPlayerService();
        var player = playerService.getPlayerRaw(p.getUserId());
        String tactic = player != null ? player.getBattleStrategy() : "balanced";
        if (tactic == null) tactic = "balanced";

        String display = switch (tactic) {
            case "aggressive" -> "🔥 猛攻 — 优先使用最强技能，全力输出";
            case "defensive" -> "🛡 防守 — 保留一半法力，稳扎稳打";
            default -> "⚖ 均衡 — 不偏不倚，见招拆招";
        };
        ctx.reply(I18n.t("combat.h4e19b0c2", display));
    }

    private String resolveTactic(String input) {
        String lower = input.toLowerCase();
        if (AGGRESSIVE_ALIASES.contains(lower)) return "aggressive";
        if (BALANCED_ALIASES.contains(lower)) return "balanced";
        if (DEFENSIVE_ALIASES.contains(lower)) return "defensive";
        return null;
    }
}
