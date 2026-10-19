package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.minecraft.adapter.MinecraftAdapter;
import com.mtxgdn.minecraft.adapter.MinecraftCommandContext;
import com.mtxgdn.onebot.command.OneBotClientCommandContext;
import com.mtxgdn.onebot.command.OneBotCommandContext;

public class BindCommand extends Command {

    public BindCommand() {
        super(new String[]{"绑定", "bind"},
                "将QQ/MC号绑定到已有游戏账号",
                "/绑定",
                "账号",
                null,
                true,
                false);
    
        setI18nKeys("cmd.bind.desc", "cmd.bind.usage");
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx instanceof OneBotCommandContext) {
            OneBotCommandContext octx = (OneBotCommandContext) ctx;
            octx.getAccountFlow().handleBind(octx.getSocket(), octx.getSelfId(), octx.getSenderId());
        } else if (ctx instanceof OneBotClientCommandContext) {
            OneBotClientCommandContext cctx = (OneBotClientCommandContext) ctx;
            cctx.getAccountFlow().handleBind(cctx.getSession(), cctx.getSelfId(), cctx.getSenderId());
        } else if (ctx instanceof MinecraftCommandContext) {
            MinecraftCommandContext mctx = (MinecraftCommandContext) ctx;
            MinecraftAdapter.getInstance().handleBind(mctx.getMcName(), mctx.getMcUuid());
        }
    }
}
