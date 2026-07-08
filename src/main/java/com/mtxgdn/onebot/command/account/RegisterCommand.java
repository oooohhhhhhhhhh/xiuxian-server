package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.minecraft.adapter.MinecraftAdapter;
import com.mtxgdn.minecraft.adapter.MinecraftCommandContext;
import com.mtxgdn.onebot.command.OneBotCommandContext;

public class RegisterCommand extends Command {

    public RegisterCommand() {
        super(new String[]{"注册", "register"},
                "注册修仙角色",
                "/注册 <角色名>",
                "账号");
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx instanceof OneBotCommandContext) {
            OneBotCommandContext octx = (OneBotCommandContext) ctx;
            octx.getAccountFlow().handleRegister(
                    octx.getSocket(), octx.getSelfId(),
                    octx.getSenderId(), octx.getArg(), octx.getGroupId());
        } else if (ctx instanceof MinecraftCommandContext) {
            MinecraftCommandContext mctx = (MinecraftCommandContext) ctx;
            MinecraftAdapter.getInstance().handleRegister(
                    mctx.getMcName(), mctx.getMcUuid(), mctx.getArg());
        }
    }
}
