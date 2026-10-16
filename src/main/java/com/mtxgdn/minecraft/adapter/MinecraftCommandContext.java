package com.mtxgdn.minecraft.adapter;

import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

/**
 * Minecraft 指令上下文。
 */
public class MinecraftCommandContext extends CommandContext {

    private final String mcName;
    private final String mcUuid;
    private final MinecraftMessageSender sender;
    private final String cmdPrefix;

    public MinecraftCommandContext(String mcName, String mcUuid, String arg,
                                   MinecraftMessageSender sender, String cmdPrefix) {
        super(mcUuid, mcName, arg);
        this.mcName = mcName;
        this.mcUuid = mcUuid;
        this.sender = sender;
        this.cmdPrefix = cmdPrefix;
    }

    public String getMcName() {
        return mcName;
    }

    public String getMcUuid() {
        return mcUuid;
    }

    public MinecraftMessageSender getMessageSender() {
        return sender;
    }

    @Override
    public boolean isGroup() {
        return false;
    }

    @Override
    public void reply(String message) {
        sender.replyToSource(mcName, mcUuid, message);
    }

    @Override
    public void replyPrivate(String message) {
        sender.sendPrivateMsg(mcName, message);
    }

    public void sendBroadcast(String message) {
        sender.sendBroadcast(message);
    }

    @Override
    public Long requireBinding() {
        MinecraftPlayerBinding mcB = ServiceRegistry.getMcBindingService().findByMcUuid(mcUuid);
        if (mcB != null) {
            return mcB.getUserId();
        }
        reply("请先注册或绑定账号。\n注册: " + cmdPrefix + "register <角色名>\n绑定: " + cmdPrefix + "bind");
        return null;
    }

    @Override
    public PlayerInfo requirePlayer(Long userId) {
        PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerByUserId(userId);
        if (player == null) {
            reply("你还没有创建修仙角色。\n请使用 " + cmdPrefix + "register <角色名> 注册角色");
            return null;
        }
        return player;
    }
}