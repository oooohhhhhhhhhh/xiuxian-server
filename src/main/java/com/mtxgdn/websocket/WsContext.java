package com.mtxgdn.websocket;

import com.mtxgdn.common.GameMessage;

/**
 * 处理器需要的「向别处发消息」能力。
 *
 * <p><b>为什么不让处理器直接拿 {@code GameWebSocketApp}</b>：拆分后每个处理器只负责
 * 一个 action 域，它需要的东西只有两样 —— 当前连接的 socket（作为方法参数传入，
 * <b>不缓存成字段</b>）与"把消息发给别人"的能力（广播 / 按 userId 单发）。
 * 后者由这个接口表达，{@link GameWebSocketApp} 实现它并注入到各处理器。</p>
 *
 * <p>这样做的好处是处理器不持有 socket 副本：socket 随每次调用传入，
 * 断线重连后不会有人往旧连接上写；也避免处理器拿到整个 Application 后
 * 顺手去碰会话表（{@code sessionUsers} / {@code userSessions}）从而绕开
 * 空闲巡检与"同账号只保留一条连接"的既有约束。</p>
 *
 * <p>接口里只有拆分前 {@code GameWebSocketApp} 就已经存在的两个公开方法
 * （{@code broadcast} / {@code sendToUser}），签名逐字不变 —— 拆分不新增对外协议，
 * 也不改变消息内容。</p>
 */
interface WsContext {

    /** 广播给所有已认证连接（拆分前 {@code GameWebSocketApp.broadcast}）。 */
    void broadcast(GameMessage message);

    /** 发给指定 userId 的连接（拆分前 {@code GameWebSocketApp.sendToUser}）。 */
    void sendToUser(Long userId, GameMessage message);
}
