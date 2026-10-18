package com.mtxgdn.minecraft.adapter;

import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;

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
        reply(I18n.t("minecraft.h9c8a05b2", cmdPrefix, cmdPrefix));
        return null;
    }

    @Override
    public PlayerInfo requirePlayer(Long userId) {
        PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerByUserId(userId);
        if (player == null) {
            reply(com.mtxgdn.i18n.I18n.t("game.no_player").replace(
                    "/register", cmdPrefix + "register"));
            return null;
        }
        return player;
    }

    /**
     * Minecraft 通道的语言：按 MC UUID 找绑定用户，取其账号语言；
     * 未绑定的玩家回落服务端默认。
     *
     * <p>同样吞掉解析异常——MC 玩家掉线时绑定表可能瞬时查不到，
     * 那时回落中文比报错好。</p>
     */
    @Override
    public com.mtxgdn.i18n.GameLanguage lang() {
        try {
            MinecraftPlayerBinding mcB =
                    ServiceRegistry.getMcBindingService().findByMcUuid(mcUuid);
            if (mcB == null) {
                return com.mtxgdn.i18n.I18nManager.serverDefault();
            }
            return com.mtxgdn.i18n.LanguageResolver.forUser(mcB.getUserId());
        } catch (Exception ignored) {
            return com.mtxgdn.i18n.I18nManager.serverDefault();
        }
    }
}