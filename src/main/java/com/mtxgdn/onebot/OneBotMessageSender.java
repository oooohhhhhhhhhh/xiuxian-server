package com.mtxgdn.onebot;

import com.google.gson.JsonObject;
import org.glassfish.grizzly.websockets.WebSocket;

public interface OneBotMessageSender {

    void replyToSource(WebSocket socket, String selfId, String senderQq, Long groupId, String message);

    void sendPrivateMsg(WebSocket socket, String selfId, String targetQq, String message);

    void sendGroupMsg(WebSocket socket, String selfId, Long groupId, String message);
}
