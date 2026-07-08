package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.onebot.command.OneBotCommandContext;

public class DeleteAccountCommand extends Command {

    public DeleteAccountCommand() {
        super(new String[]{"注销", "deleteaccount"},
                "注销游戏账号（不可逆）",
                "/注销",
                "账号",
                "game.player.info",
                true);
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx instanceof OneBotCommandContext) {
            OneBotCommandContext octx = (OneBotCommandContext) ctx;
            octx.getAccountFlow().handleDeleteAccount(
                    octx.getSocket(), octx.getSelfId(), octx.getSenderId());
        }
    }
}
