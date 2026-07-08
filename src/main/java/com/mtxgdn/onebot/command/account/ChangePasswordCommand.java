package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.onebot.command.OneBotCommandContext;

public class ChangePasswordCommand extends Command {

    public ChangePasswordCommand() {
        super(new String[]{"改密", "changepassword", "changepwd"},
                "修改游戏账号密码",
                "/改密",
                "账号",
                "game.player.info",
                true);
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx instanceof OneBotCommandContext) {
            OneBotCommandContext octx = (OneBotCommandContext) ctx;
            octx.getAccountFlow().handleChangePassword(
                    octx.getSocket(), octx.getSelfId(), octx.getSenderId());
        }
    }
}
