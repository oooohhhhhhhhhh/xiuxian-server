package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.minecraft.adapter.MinecraftAdapter;
import com.mtxgdn.minecraft.adapter.MinecraftCommandContext;
import com.mtxgdn.onebot.command.OneBotClientCommandContext;
import com.mtxgdn.onebot.command.OneBotCommandContext;

public class BindCommand extends Command {

    /**
     * 绑定入口。
     *
     * <p><b>为什么不再是「私聊专用」</b>：新申请的 QQ 官方机器人常常没有单聊（C2C）权限，
     * 群里发 {@code /绑定} 若被直接拒绝，玩家在这个部署下就永远绑定不了账号。
     * 现在两种通道都不在群里收凭据 —— OneBot 把"请输入用户名/密码"发到私聊，
     * QQ 官方通道先试私聊、发不出去就回一条一次性网页链接（密码与验证码在浏览器里输入），
     * 群里只会出现链接本身。</p>
     */
    public BindCommand() {
        super(new String[]{"绑定", "bind"},
                "将QQ/MC号绑定到已有游戏账号",
                "/绑定",
                "账号",
                null,
                false,
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
