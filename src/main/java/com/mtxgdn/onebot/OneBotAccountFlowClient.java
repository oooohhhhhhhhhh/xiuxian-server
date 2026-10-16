package com.mtxgdn.onebot;

/**
 * 基于 OneBotSession 的账号流程接口（不依赖 Grizzly WebSocket）。
 */
public interface OneBotAccountFlowClient {

    void handleRegister(OneBotSession session, String selfId, String senderQq, String arg, Long sourceGroupId);

    void handleBind(OneBotSession session, String selfId, String senderQq);

    void handleUnbind(OneBotSession session, String selfId, String senderQq);

    void handleChangePassword(OneBotSession session, String selfId, String senderQq);

    void handleDeleteAccount(OneBotSession session, String selfId, String senderQq);
}
