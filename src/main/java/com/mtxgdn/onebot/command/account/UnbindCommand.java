package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.onebot.command.OneBotCommandContext;

public class UnbindCommand extends Command {

    public UnbindCommand() {
        super(new String[]{"unbind", "解绑"},
                "解除QQ号与游戏账号的绑定",
                "/unbind",
                "账户",
                "game.player.info",
                true);
    }

    @Override
    public void execute(CommandContext ctx) {
        OneBotCommandContext octx = (OneBotCommandContext) ctx;
        octx.getAccountFlow().handleUnbind(octx.getSocket(), octx.getSelfId(), octx.getSenderId());
    }
}
