package com.mtxgdn.onebot.command.social;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;
import java.util.List;

public class PrivateMessageCommand extends Command {
    public PrivateMessageCommand() { super(new String[]{"msg", "私聊"}, "发送私聊消息给其他玩家", "/私聊 <玩家名> <消息内容>", "世界", "game.chat.private"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        if (!ctx.checkPermission("game.chat.private")) return;
        PlayerInfo p = ctx.requirePlayer(userId); if (p == null) return;
        String[] parts = ctx.getArg().split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) { ctx.reply("用法: /私聊 <玩家名> <消息内容>"); return; }
        String targetName = parts[0].trim(); String content = parts[1].trim();
        var playerService = ServiceRegistry.getPlayerService();
        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) { ctx.reply("找不到玩家: " + targetName); return; }
        PlayerInfo target = targets.get(0);
        if (target.getId() == p.getId()) { ctx.reply("不能给自己发私聊消息。"); return; }
        ServiceRegistry.getChatService().sendPrivateMessage(p.getId(), p.getName(), target.getId(), content);
        PlayerActionLogger.getInstance().logChat(userId, p.getName(), "[私聊→" + target.getName() + "] " + content);
        ctx.reply("私聊消息已发送给 " + target.getName());
    }
}
