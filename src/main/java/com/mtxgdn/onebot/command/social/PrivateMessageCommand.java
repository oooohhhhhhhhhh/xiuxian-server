package com.mtxgdn.onebot.command.social;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.util.PlayerActionLogger;

import java.util.List;
import com.mtxgdn.i18n.I18n;

public class PrivateMessageCommand extends Command {

    public PrivateMessageCommand() {
        super(new String[]{"私聊", "msg"},
                "发送私聊消息给其他玩家",
                "/私聊 <玩家名> <消息内容>",
                "社交",
                null,
                false,
                true);
        setModule("chat");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply(I18n.t("social.h3eb53952"));
            return;
        }
        String[] parts = arg.split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            ctx.reply(I18n.t("social.h3eb53952"));
            return;
        }

        String targetName = parts[0].trim();
        String content = parts[1].trim();

        var playerService = ServiceRegistry.getPlayerService();
        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) {
            ctx.reply(I18n.t("social.h7e37bde8", targetName));
            return;
        }
        PlayerInfo target = targets.get(0);

        if (target.getId() == p.getId()) {
            ctx.reply(I18n.t("social.hb61ed708"));
            return;
        }

        ServiceRegistry.getChatService().sendPrivateMessage(p.getId(), p.getName(), target.getId(), content);
        PlayerActionLogger.getInstance().logChat(userId, p.getName(), "[私聊→" + target.getName() + "] " + content);
        ctx.reply(I18n.t("social.h5c322cf4", target.getName()));
    }
}