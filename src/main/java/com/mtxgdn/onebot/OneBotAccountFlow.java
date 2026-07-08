package com.mtxgdn.onebot;

import org.glassfish.grizzly.websockets.WebSocket;

public interface OneBotAccountFlow {

    void handleRegister(WebSocket socket, String selfId, String senderQq, String arg, Long sourceGroupId);

    void handleBind(WebSocket socket, String selfId, String senderQq);

    void handleUnbind(WebSocket socket, String selfId, String senderQq);

    void handleChangePassword(WebSocket socket, String selfId, String senderQq);

    void handleDeleteAccount(WebSocket socket, String selfId, String senderQq);
}
