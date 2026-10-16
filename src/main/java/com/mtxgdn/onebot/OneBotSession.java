package com.mtxgdn.onebot;

/**
 * OneBot 连接抽象接口，解耦传输层（WebSocket/HTTP）。
 * 无论是 WS 服务端、WS 客户端还是 HTTP 模式，都通过此接口发送消息。
 */
public interface OneBotSession {

    /**
     * 发送原始 JSON 到 OneBot 实现端。
     */
    void send(String json);

    /**
     * 获取当前 Bot 的 QQ 号。
     */
    String getSelfId();

    /**
     * 连接是否活跃。
     */
    boolean isOpen();

    /**
     * 关闭连接。
     */
    void close();
}
