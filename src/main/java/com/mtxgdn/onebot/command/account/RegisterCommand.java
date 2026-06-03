package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.onebot.command.OneBotCommandContext;

public class RegisterCommand extends Command {

    public RegisterCommand() {
        super(new String[]{"register", "注册"},
                "注册修仙角色",
                "/register <角色名>",
                "账户");
    }

    @Override
    public void execute(CommandContext ctx) {
        OneBotCommandContext octx = (OneBotCommandContext) ctx;
        octx.getAccountFlow().handleRegister(
                octx.getSocket(), octx.getSelfId(),
                octx.getSenderId(), octx.getArg(), octx.getGroupId());
    }
}
