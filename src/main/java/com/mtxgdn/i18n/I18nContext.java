package com.mtxgdn.i18n;

/**
 * 当前线程的"目标语言"载体。
 *
 * <p>取代旧 {@code LangManager.currentLang} 那个静态字段：那是进程级单例，
 * 两个玩家同时在线时后设置的会覆盖前一个，导致 A 收到 B 的语言。
 * 这里用 {@link ThreadLocal} 把语言绑到处理请求的线程上，
 * Jersey 给每个请求一个工作线程、Grizzly 给每条 WS 帧一个处理线程，天然对齐。</p>
 *
 * <p>非 REST 通路（聊天平台指令）没有 Jersey 线程模型，由适配器在指令入口
 * 显式 {@link #runWith(GameLanguage, Runnable)} 包裹，保证同样的隔离语义。</p>
 */
public final class I18nContext {

    private static final ThreadLocal<GameLanguage> CURRENT = new ThreadLocal<>();

    private I18nContext() {
    }

    /** 当前线程的语言；未绑定时返回服务端默认语言。 */
    public static GameLanguage current() {
        GameLanguage lang = CURRENT.get();
        return lang != null ? lang : I18nManager.serverDefault();
    }

    /** 当前线程的语言；未绑定时返回 {@code null}。供"有没有显式协商过"这类判断使用。 */
    public static GameLanguage peek() {
        return CURRENT.get();
    }

    /** 绑定（不清理，调用方负责配对 {@link #clear()}）。 */
    public static void set(GameLanguage lang) {
        if (lang == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(lang);
        }
    }

    /** 清除绑定。用完必须调用，否则线程被池复用后会带着上一个请求的语言。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 在指定语言下执行任务，结束后恢复原绑定。
     *
     * <p>返回 {@code null} 由调用方按需处理——本方法的职责只是保证清理一定发生。</p>
     */
    public static void runWith(GameLanguage lang, Runnable body) {
        GameLanguage previous = CURRENT.get();
        if (lang == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(lang);
        }
        try {
            body.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
