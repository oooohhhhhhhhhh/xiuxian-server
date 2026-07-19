package com.mtxgdn.onebot.command.combat;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.CombatService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.command.OneBotCommandContext;

import java.util.List;

public class PvpCommand extends Command {

    private static final QqBindingService bindingService = new QqBindingService();

    public PvpCommand() {
        super(new String[]{"挑战", "pvp"}, "挑战其他修士", "/挑战 <角色名>\n/挑战 接受 — 接受正在进行的切磋\n/挑战 拒绝 — 拒绝切磋", "战斗", "game.pvp.challenge");

        registerSub("接受", (ctx, p, parts) -> {
            var combatService = ServiceRegistry.getCombatService();
            var result = combatService.acceptChallenge(p.getId());
            if (!result.isSuccess()) {
                ctx.reply(result.getMessage());
                return;
            }
            sendBattleReport(ctx, p, result, p.getName());
        });

        registerSub("拒绝", (ctx, p, parts) -> {
            var combatService = ServiceRegistry.getCombatService();
            String challengerName = combatService.rejectChallenge(p.getId());
            if (challengerName == null) {
                ctx.reply("没有待接受的挑战");
            } else {
                ctx.reply("你拒绝了【" + challengerName + "】的切磋挑战");
            }
        });
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.pvp.challenge")) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg();
        if (arg == null || arg.trim().isEmpty()) {
            String tactic = getTacticDisplay(p);
            ctx.reply("用法: /挑战 <角色名>\n" + tactic + "\n💡 也可用 /挑战 接受 或 /挑战 拒绝 回应切磋");
            return;
        }

        String[] parts = arg.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        if ("接受".equals(sub) || "accept".equals(sub) || "拒绝".equals(sub) || "reject".equals(sub)) {
            super.execute(ctx);
            return;
        }

        String targetName = arg.trim();
        var playerService = ServiceRegistry.getPlayerService();
        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) { ctx.reply("找不到玩家: " + targetName); return; }
        PlayerInfo target = targets.get(0);
        if (target.getId() == p.getId()) { ctx.reply("不能挑战自己。"); return; }

        var combatService = ServiceRegistry.getCombatService();
        var challengeResult = combatService.createChallenge(p.getId(), target.getId());

        if (!challengeResult.isSuccess()) {
            ctx.reply(challengeResult.getMessage());
            return;
        }

        // 通知挑战者
        String challengerTactic = getTacticDisplay(p);
        ctx.reply("⚔ 你向【" + target.getName() + "】发起了切磋挑战！" + challengerTactic + "\n等待对方回应中（30秒）...");

        // 通知被挑战者
        notifyTarget(ctx, target, p.getName());
    }

    private void notifyTarget(CommandContext ctx, PlayerInfo target, String challengerName) {
        try {
            QqBinding binding = bindingService.findByUserId(target.getUserId());
            if (binding == null) return;

            var playerService = ServiceRegistry.getPlayerService();
            var targetPlayer = playerService.getPlayerRaw(target.getUserId());
            String targetTactic = "";
            if (targetPlayer != null && targetPlayer.getBattleStrategy() != null) {
                targetTactic = getTacticDisplay(targetPlayer);
            }

            OneBotCommandContext oCtx = (OneBotCommandContext) ctx;
            oCtx.sendPrivateMsg(binding.getQqNumber(),
                    "⚔ 【" + challengerName + "】向你发起了切磋挑战！" + targetTactic + "\n"
                  + "回复 /挑战 接受 迎战，/挑战 拒绝 回避（30秒超时自动取消）");
        } catch (Exception ignored) {}
    }

    private void sendBattleReport(CommandContext ctx, PlayerInfo p, CombatService.CombatResult result, String myName) {
        List<String> log = result.getBattleLog();
        if (log == null || log.isEmpty()) return;

        // 分条发送：汇合回合，每组最多 4 行叙事
        StringBuilder sb = new StringBuilder();
        int lineCount = 0;

        for (int i = 0; i < log.size(); i++) {
            String line = log.get(i);
            // 换段标志：空行或回合分隔
            boolean isBreak = line.isEmpty() || (line.startsWith("-- 第") && line.contains("回合 --"));
            boolean isEnd = line.startsWith("🏆") || line.startsWith("🤝");
            boolean isReward = line.startsWith("获得") || line.startsWith("惜败");

            if ((isBreak || isEnd || isReward) && sb.length() > 0) {
                ctx.reply(sb.toString().trim());
                sb = new StringBuilder();
                lineCount = 0;
            }

            sb.append(line).append("\n");
            lineCount++;

            // 每 5 行切一条
            if (lineCount >= 5 && !isEnd && !isReward) {
                ctx.reply(sb.toString().trim());
                sb = new StringBuilder();
                lineCount = 0;
            }
        }

        if (sb.length() > 0) {
            ctx.reply(sb.toString().trim());
        }
    }

    private String getTacticDisplay(PlayerInfo p) {
        if (p == null) return "";
        var ps = ServiceRegistry.getPlayerService();
        var player = ps.getPlayerRaw(p.getUserId());
        if (player == null) return "";
        String tactic = player.getBattleStrategy();
        if (tactic == null) tactic = "balanced";
        return switch (tactic) {
            case "aggressive" -> "\n🔥 当前战术：猛攻（优先使用最强技能）";
            case "defensive" -> "\n🛡 当前战术：防守（保留法力，稳扎稳打）";
            default -> "\n⚖ 当前战术：均衡";
        };
    }

    private String getTacticDisplay(com.mtxgdn.entity.Player player) {
        if (player == null) return "";
        String tactic = player.getBattleStrategy();
        if (tactic == null) tactic = "balanced";
        return switch (tactic) {
            case "aggressive" -> "\n  🔥 战术：猛攻";
            case "defensive" -> "\n  🛡 战术：防守";
            default -> "\n  ⚖ 战术：均衡";
        };
    }
}
