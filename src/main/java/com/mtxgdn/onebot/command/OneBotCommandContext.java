package com.mtxgdn.onebot.command;

import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.onebot.OneBotAccountFlow;
import com.mtxgdn.onebot.OneBotMessageSender;
import org.glassfish.grizzly.websockets.WebSocket;

public class OneBotCommandContext extends CommandContext {

    private final WebSocket socket;
    private final String selfId;
    private final Long groupId;
    private final OneBotMessageSender sender;
    private final OneBotAccountFlow accountFlow;

    public OneBotCommandContext(WebSocket socket, String selfId, String senderQq,
                                String senderNickname, Long groupId, String arg,
                                OneBotMessageSender sender, OneBotAccountFlow accountFlow) {
        super(senderQq, senderNickname, arg);
        this.socket = socket;
        this.selfId = selfId;
        this.groupId = groupId;
        this.sender = sender;
        this.accountFlow = accountFlow;
    }

    public WebSocket getSocket() {
        return socket;
    }

    public String getSelfId() {
        return selfId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public OneBotMessageSender getMessageSender() {
        return sender;
    }

    public OneBotAccountFlow getAccountFlow() {
        return accountFlow;
    }

    /**
     * OneBot 通道的语言：已绑定玩家看自己设的，未绑定访客回落服务端默认。
     *
     * <p>刻意<b>不缓存</b>：玩家可能刚在 Web 端改过语言，缓存会让 QQ 侧的回复
     * 滞后一次。多一次按 QQ 号查绑定的代价相对一次网络回复可以忽略。</p>
     */
    @Override
    public com.mtxgdn.i18n.GameLanguage lang() {
        Long userId = resolveBoundUserId();
        if (userId == null) {
            return com.mtxgdn.i18n.I18nManager.serverDefault();
        }
        return com.mtxgdn.i18n.LanguageResolver.forUser(userId);
    }

    @Override
    public boolean isGroup() {
        return groupId != null;
    }

    @Override
    public void reply(String message) {
        sender.replyToSource(socket, selfId, getSenderId(), groupId, message);
    }

    @Override
    public void replyPrivate(String message) {
        sender.sendPrivateMsg(socket, selfId, getSenderId(), message);
    }

    public void sendPrivateMsg(String targetQq, String message) {
        sender.sendPrivateMsg(socket, selfId, targetQq, message);
    }

    public void sendGroupMsg(Long group, String message) {
        sender.sendGroupMsg(socket, selfId, group, message);
    }
}
