package com.mtxgdn.onebot.command;

import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.onebot.OneBotAccountFlowClient;
import com.mtxgdn.onebot.OneBotSession;
import com.mtxgdn.onebot.OneBotMessageSenderClient;

/**
 * 基于 OneBotSession 的命令上下文，用于 WS 客户端 / HTTP 模式。
 */
public class OneBotClientCommandContext extends CommandContext {

    private final OneBotSession session;
    private final String selfId;
    private final Long groupId;
    private final OneBotMessageSenderClient sender;
    private final OneBotAccountFlowClient accountFlow;

    public OneBotClientCommandContext(OneBotSession session, String selfId, String senderQq,
                                       String senderNickname, Long groupId, String arg,
                                       OneBotMessageSenderClient sender,
                                       OneBotAccountFlowClient accountFlow) {
        this(session, selfId, senderQq, senderNickname, groupId, arg, sender, accountFlow, null);
    }

    /**
     * @param qqBindingLookup 平台感知绑定查找器（QQ 官方传入 openid 查找；OneBot 传 null 用默认 qq_bindings）
     */
    public OneBotClientCommandContext(OneBotSession session, String selfId, String senderQq,
                                       String senderNickname, Long groupId, String arg,
                                       OneBotMessageSenderClient sender,
                                       OneBotAccountFlowClient accountFlow,
                                       CommandContext.QqBindingLookup qqBindingLookup) {
        super(senderQq, senderNickname, arg, qqBindingLookup);
        this.session = session;
        this.selfId = selfId;
        this.groupId = groupId;
        this.sender = sender;
        this.accountFlow = accountFlow;
    }

    public OneBotSession getSession() {
        return session;
    }

    public String getSelfId() {
        return selfId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public OneBotMessageSenderClient getMessageSender() {
        return sender;
    }

    public OneBotAccountFlowClient getAccountFlow() {
        return accountFlow;
    }

    @Override
    public boolean isGroup() {
        return groupId != null;
    }

    /**
     * QQ 官方 / WS 客户端通道的语言。
     *
     * <p>与 {@link OneBotCommandContext#lang()} 同一套逻辑：已绑定取账号语言，
     * 未绑定回落服务端默认。QQ 官方的 openid 与 OneBot 的 QQ 号分属两张表，
     * 由基类构造时注入的 {@code qqBindingLookup} 负责区分，这里无需关心。</p>
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
    public void reply(String message) {
        sender.replyToSource(session, selfId, getSenderId(), groupId, message);
    }

    @Override
    public void replyPrivate(String message) {
        sender.sendPrivateMsg(session, selfId, getSenderId(), message);
    }

    public void sendPrivateMsg(String targetQq, String message) {
        sender.sendPrivateMsg(session, selfId, targetQq, message);
    }

    public void sendGroupMsg(Long group, String message) {
        sender.sendGroupMsg(session, selfId, group, message);
    }
}
