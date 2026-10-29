package com.mtxgdn.onebot.command.player;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.cultivation.HealResult;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;

public class HealCommand extends Command {
    public HealCommand() { super(new String[]{"疗伤", "heal"}, "恢复伤势", "/疗伤", "我的角色", "game.player.info"); 
        setI18nKeys("cmd.heal.desc", "cmd.heal.usage");
    setModule("player"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId); if (p == null) return;
        if (p.getHp() >= p.getMaxHp()) { ctx.reply(I18n.t("player.your_hp_is_already_full")); return; }
        HealResult result = ServiceRegistry.getPlayerService().healPlayer(p.getId());
        ctx.reply(result.message() != null ? result.message() : I18n.t("player.heal_failed"));
    }
}
