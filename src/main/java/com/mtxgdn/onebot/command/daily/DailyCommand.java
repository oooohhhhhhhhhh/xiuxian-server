package com.mtxgdn.onebot.command.daily;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

public class DailyCommand extends Command {
    public DailyCommand() { super(new String[]{"天象", "daily"}, "查看今日天象与机缘", "/天象", "修炼", "game.player.info"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        var result = ServiceRegistry.getDailyService().getDailyInfo(p.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("===== 今日天象 =====\n");
        sb.append("天象: ").append(result.get("phenomenon")).append("\n");
        sb.append(result.get("phenomenonDesc")).append("\n\n");
        sb.append("今日机缘: ").append(result.get("dailyTask")).append("\n");
        if (result.get("taskReward") != null) sb.append("完成奖励: ").append(result.get("taskReward")).append("\n");
        sb.append("\n使用 /晨修 进行每日修炼");
        ctx.reply(sb.toString());
    }
}
