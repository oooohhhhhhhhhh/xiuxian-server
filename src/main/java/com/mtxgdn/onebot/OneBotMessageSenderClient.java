package com.mtxgdn.onebot;

/**
 * 基于 OneBotSession 的消息发送接口（不依赖 Grizzly WebSocket）。
 */
public interface OneBotMessageSenderClient {

    void replyToSource(OneBotSession session, String selfId, String senderQq, Long groupId, String message);

    void sendPrivateMsg(OneBotSession session, String selfId, String targetQq, String message);

    void sendGroupMsg(OneBotSession session, String selfId, Long groupId, String message);
}
