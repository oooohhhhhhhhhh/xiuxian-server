package com.mtxgdn.websocket;

import com.mtxgdn.common.GameMessage;

/**
 * 所有 action 处理器类的基类。
 *
 * <p><b>为什么需要这一层</b>：拆分前 {@code handleChat} 里直接写
 * {@code broadcast(chatMessage)}、{@code handlePrivateChat} 里直接写
 * {@code sendToUser(...)} —— 它们都是 {@code GameWebSocketApp} 的实例方法。
 * 处理函数搬到 {@code Ws*Handler} 之后，如果每个类各自去拿一次
 * {@code GameWebSocketApp} 再写 {@code app.broadcast(...)}，等于把同一份依赖
 * 复制了 11 次，而且方法体也要跟着改。基类把这两个能力包成同名方法，
 * 于是<b>搬过去的方法体一行都不用改</b>（{@code broadcast(...)} /
 * {@code sendToUser(...)} 的调用点保持逐字不变）。</p>
 *
 * <p>上下文对象通过构造函数注入（{@link WsContext}），处理器不缓存 socket ——
 * socket 是每次调用的第一个参数，随消息进来、随方法结束丢弃。</p>
 *
 * <p>本类是包级可见的，只在 {@code com.mtxgdn.websocket} 内部使用，
 * 不构成任何对外协议。</p>
 */
abstract class WsHandler {

    /** 广播/单发能力的来源（由 {@link GameWebSocketApp} 注入）。 */
    private final WsContext context;

    WsHandler(WsContext context) {
        this.context = context;
    }

    /** 广播给所有已认证连接（与拆分前 {@code GameWebSocketApp} 的实例方法同名同义）。 */
    final void broadcast(GameMessage message) {
        context.broadcast(message);
    }

    /** 发给指定 userId 的连接（与拆分前 {@code GameWebSocketApp} 的实例方法同名同义）。 */
    final void sendToUser(Long userId, GameMessage message) {
        context.sendToUser(userId, message);
    }
}
