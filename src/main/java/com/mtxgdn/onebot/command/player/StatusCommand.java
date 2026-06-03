package com.mtxgdn.onebot.command.player;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
public class StatusCommand extends Command {

    public StatusCommand() {
        super(new String[]{"status", "状态", "info", "信息"},
                "查看角色状态",
                "/状态",
                "我的角色",
                "game.player.info");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.player.info")) return;

        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        ctx.reply(CommandContext.formatPlayerStatus(p));
    }
}
