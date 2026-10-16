package com.mtxgdn.qq;

/**
 * QQ 官方「消息交互」按钮（Keyboard Button）。
 *
 * <p>文档：<a href="https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/trans/overview.html">消息交互（按钮互动）</a></p>
 *
 * <ul>
 *   <li>点击按钮后服务端会收到 {@code INTERACTION_CREATE} 事件（intent：INTERACTION），
 *       事件携带 {@code button_id} 与 {@code button_data}（即 {@link #data}）</li>
 *   <li>{@link #data} 用作游戏指令（如 {@code 签到}），适配器收到回调后按指令分发执行</li>
 * </ul>
 */
public class QqButton {

    /** 按钮 ID（同一条消息内唯一） */
    public final String id;

    /** 按钮显示文字 */
    public final String label;

    /** 按钮样式：0 灰 / 1 蓝 / 2 红 / 3 绿 / 4 橙 */
    public final int style;

    /** 自定义数据：点击回调时作为 button_data 返回（用于绑定游戏指令） */
    public final String data;

    /** 点击后是否同时在会话中发送一条文本消息（默认 false） */
    public final boolean enter;

    /** 跳转链接（仅跳转按钮） */
    public final String url;

    private QqButton(String id, String label, int style, String data, boolean enter, String url) {
        this.id = id;
        this.label = label;
        this.style = style;
        this.data = data;
        this.enter = enter;
        this.url = url;
    }

    /** 回调按钮：点击触发 INTERACTION_CREATE，data 会作为 button_data 回传 */
    public static QqButton of(String id, String label, String data) {
        return new QqButton(id, label, 1, data, false, null);
    }

    /** 回调按钮（指定样式） */
    public static QqButton of(String id, String label, int style, String data) {
        return new QqButton(id, label, style, data, false, null);
    }

    /** 点击后发消息 + 回调（enter=true 时点击会先发一条 label 文本消息） */
    public static QqButton ofEnter(String id, String label, int style, String data) {
        return new QqButton(id, label, style, data, true, null);
    }

    /** 跳转链接按钮 */
    public static QqButton url(String id, String label, String url) {
        return new QqButton(id, label, 0, null, false, url);
    }
}
