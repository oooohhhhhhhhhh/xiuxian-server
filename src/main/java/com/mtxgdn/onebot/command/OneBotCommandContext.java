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
