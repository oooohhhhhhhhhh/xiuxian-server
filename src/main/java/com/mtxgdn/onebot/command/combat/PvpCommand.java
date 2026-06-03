package com.mtxgdn.onebot.command.combat;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;
import java.util.List;

public class PvpCommand extends Command {
    public PvpCommand() {
        super(new String[]{"pvp", "挑战"}, "挑战其他修士", "/挑战 <角色名>", "战斗与成长", "game.pvp.challenge");
    }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.pvp.challenge")) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        String targetName = ctx.getArg().trim();
        if (targetName.isEmpty()) { ctx.reply("用法: /挑战 <角色名>"); return; }
        var playerService = ServiceRegistry.getPlayerService();
        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) { ctx.reply("找不到玩家: " + targetName); return; }
        PlayerInfo target = targets.get(0);
        if (target.getId() == p.getId()) { ctx.reply("不能挑战自己。"); return; }
        var combatService = ServiceRegistry.getCombatService();
        var result = combatService.pvpChallenge(p.getId(), target.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("===== PVP 挑战 =====\n");
        sb.append(result.getMessage()).append("\n");
        if (result.isSuccess()) {
            sb.append("挑战者: ").append(result.getChallengerName())
              .append(" (剩余HP: ").append(result.getChallengerRemainingHp()).append(")\n");
            sb.append("被挑战者: ").append(result.getTargetName())
              .append(" (剩余HP: ").append(result.getTargetRemainingHp()).append(")\n");
            sb.append("战斗回合: ").append(result.getTotalRounds()).append("\n");
            if (result.getExpReward() > 0) sb.append("灵力奖励: ").append(result.getExpReward()).append("\n");
            if (result.getGoldReward() > 0) sb.append("金币奖励: ").append(result.getGoldReward()).append("\n");
            if (result.getWinner() != null) sb.append("胜者: ").append(result.getWinner());
        }
        ctx.reply(sb.toString());
    }
}
