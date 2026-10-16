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
