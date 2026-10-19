package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.minecraft.adapter.MinecraftAdapter;
import com.mtxgdn.minecraft.adapter.MinecraftCommandContext;
import com.mtxgdn.onebot.command.OneBotClientCommandContext;
import com.mtxgdn.onebot.command.OneBotCommandContext;

public class UnbindCommand extends Command {

    public UnbindCommand() {
        super(new String[]{"解绑", "unbind"},
                "解除QQ号/MC号与游戏账号的绑定",
                "/解绑",
                "账号",
                "game.player.info",
                true);
    
        setI18nKeys("cmd.unbind.desc", "cmd.unbind.usage");
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx instanceof OneBotCommandContext) {
            OneBotCommandContext octx = (OneBotCommandContext) ctx;
            octx.getAccountFlow().handleUnbind(octx.getSocket(), octx.getSelfId(), octx.getSenderId());
        } else if (ctx instanceof OneBotClientCommandContext) {
            OneBotClientCommandContext cctx = (OneBotClientCommandContext) ctx;
            cctx.getAccountFlow().handleUnbind(cctx.getSession(), cctx.getSelfId(), cctx.getSenderId());
        } else if (ctx instanceof MinecraftCommandContext) {
            MinecraftCommandContext mctx = (MinecraftCommandContext) ctx;
            MinecraftAdapter.getInstance().handleUnbind(mctx.getMcName(), mctx.getMcUuid());
        }
    }
}
