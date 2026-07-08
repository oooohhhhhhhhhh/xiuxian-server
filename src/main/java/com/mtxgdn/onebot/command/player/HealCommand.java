package com.mtxgdn.onebot.command.player;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;

public class HealCommand extends Command {
    public HealCommand() { super(new String[]{"疗伤", "heal"}, "恢复伤势", "/疗伤", "我的角色", "game.player.info"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId); if (p == null) return;
        if (p.getHp() >= p.getMaxHp()) { ctx.reply("你的生命值已满，无需治疗。"); return; }
        Map<String, Object> result = ServiceRegistry.getPlayerService().healPlayer(p.getId());
        ctx.reply((String) result.getOrDefault("message", "疗伤失败"));
    }
}
