package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.onebot.command.OneBotCommandContext;

public class BindCommand extends Command {

    public BindCommand() {
        super(new String[]{"bind", "绑定"},
                "将QQ号绑定到已有游戏账号",
                "/bind",
                "账户",
                null,
                true);
    }

    @Override
    public void execute(CommandContext ctx) {
        OneBotCommandContext octx = (OneBotCommandContext) ctx;
        octx.getAccountFlow().handleBind(octx.getSocket(), octx.getSelfId(), octx.getSenderId());
    }
}
