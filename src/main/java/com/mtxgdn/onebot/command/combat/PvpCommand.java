package com.mtxgdn.onebot.command.combat;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandUtils;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.CombatService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.command.OneBotCommandContext;

import java.util.List;
import com.mtxgdn.i18n.I18n;

public class PvpCommand extends Command {

    public PvpCommand() {
        super(new String[]{"挑战", "pvp"}, "挑战其他修士", "/挑战 <角色名>\n/挑战 接受 — 接受正在进行的切磋\n/挑战 拒绝 — 拒绝切磋", "战斗", "game.pvp.challenge");
        
        setI18nKeys("cmd.pvp.desc", "cmd.pvp.usage");
    setModule("pvp");

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
                ctx.reply(I18n.t("combat.h22695b44"));
            } else {
                ctx.reply(I18n.t("combat.h6a592ae4", challengerName));
            }
        });
    }

    @Override
    public void execute(CommandContext ctx) {
        PlayerInfo p = requirePlayerInfo(ctx);
        if (p == null) return;

        String arg = ctx.getArg();
        if (CommandUtils.isBlank(arg)) {
            String tactic = getTacticDisplay(p);
            ctx.reply(I18n.t("combat.h169d1898", tactic));
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
        if (targets.isEmpty()) { ctx.reply(I18n.t("combat.h7e37bde8", targetName)); return; }
        PlayerInfo target = targets.get(0);
        if (target.getId() == p.getId()) { ctx.reply(I18n.t("combat.h96cde842")); return; }

        var combatService = ServiceRegistry.getCombatService();
        var challengeResult = combatService.createChallenge(p.getId(), target.getId());

        if (!challengeResult.isSuccess()) {
            ctx.reply(challengeResult.getMessage());
            return;
        }

        // 通知挑战者
        String challengerTactic = getTacticDisplay(p);
        ctx.reply(I18n.t("combat.h78015670", target.getName(), challengerTactic));

        // 通知被挑战者
        notifyTarget(ctx, target, p.getName());
    }

    private void notifyTarget(CommandContext ctx, PlayerInfo target, String challengerName) {
        try {
            QqBinding official = new com.mtxgdn.onebot.QqOfficialBindingService().findByUserId(target.getUserId());
            QqBinding onebot = new QqBindingService().findByUserId(target.getUserId());
            if (official == null && onebot == null) return;

            var playerService = ServiceRegistry.getPlayerService();
            var targetPlayer = playerService.getPlayerByUserIdRaw(target.getUserId());
            String targetTactic = "";
            if (targetPlayer != null && targetPlayer.getBattleStrategy() != null) {
                targetTactic = getTacticDisplay(targetPlayer);
            }
            String msg = "⚔ 【" + challengerName + "】向你发起了切磋挑战！" + targetTactic + "\n"
                    + "回复 /挑战 接受 迎战，/挑战 拒绝 回避（30秒超时自动取消）";

            // 当前渠道是 QQ 官方：必须用「本机器人」的绑定——
            // openid 是按 AppID 隔离的，拿别的机器人的 openid 通过本适配器发送必然失败
            if (ctx instanceof com.mtxgdn.onebot.command.OneBotClientCommandContext occ
                    && occ.getSession() instanceof com.mtxgdn.qq.QqOfficialAdapter qq) {
                QqBinding scoped = new com.mtxgdn.onebot.QqOfficialBindingService(qq.getAppId())
                        .findByUserId(target.getUserId());
                if (scoped == null) return;
                qq.sendPrivateMsg(qq, qq.getSelfId(), scoped.getQqNumber(), msg);
                return;
            }
            // OneBot 渠道：给目标发 QQ 号私聊
            if (onebot != null && ctx instanceof OneBotCommandContext oCtx) {
                oCtx.sendPrivateMsg(onebot.getQqNumber(), msg);
            }
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
        var player = ps.getPlayerByUserIdRaw(p.getUserId());
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
