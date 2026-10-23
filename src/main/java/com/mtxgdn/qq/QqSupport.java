package com.mtxgdn.qq;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.OneBotLogger;
import com.mtxgdn.util.PlayerActionLogger;

import java.util.HashMap;
import java.util.Map;

/**
 * QQ 官方适配器各协作类共用的一点点基础设施。
 *
 * <p><b>为什么有它</b>：{@code QqOfficialAdapter} 原来是一个近 1900 行的上帝类，
 * 同时扮演开放平台 HTTP 客户端、WebSocket 网关会话、事件分发器、消息发送器、
 * 指令路由与账号对话流程六种角色，所有方法共享同一批静态字段（Gson 实例、
 * 三个日志器、一批 URL / 窗口常量、Intent 映射表）。按"职责域 + 依赖集合"
 * 把这些方法拆到 {@code Qq*Client} / {@code Qq*Sender} / {@code Qq*Dispatcher} /
 * {@code Qq*Router} / {@code Qq*Flow} 之后，那批<b>跨域</b>用到的东西就无处安放了 ——
 * 放到这里，各协作类用 {@code import static} 引入即可。</p>
 *
 * <p><b>协议不变</b>：这里只做"搬家"。字段初始化表达式、{@link #parseIntents} 与
 * {@link #optString} 的方法体都是从 {@code QqOfficialAdapter} 逐字搬过来的，
 * 唯一改动是可见性（{@code private} → 包级）与位置。因此 access_token 的请求地址、
 * 开放平台 API 的 base URL、网关地址、消息分段上限、被动回复窗口与次数上限、
 * Intent 位映射，都与拆分前完全一致。</p>
 *
 * <p>本类没有实例状态、不可实例化，只被同包的适配器与其协作类使用，因此是包级可见的。</p>
 */
final class QqSupport {

    /** QQ 官方通道共用的 Gson 实例（Gson 无状态，可安全共享）。 */
    static final Gson gson = new Gson();

    /** 通道主日志。 */
    static final GameLogger log = GameLogger.getLogger("QQBot");

    /** 面向玩家的通道日志（管理台 / 日志文件里给玩家看的发送与系统事件）。 */
    static final OneBotLogger botLog = OneBotLogger.getInstance();

    /** 玩家行为日志单例（注册 / 改密等动作会写一条）。 */
    static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();

    // ==================== 常量 ====================

    static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    static final String API_BASE = "https://api.sgroup.qq.com";
    static final String DEFAULT_GATEWAY = "wss://api.sgroup.qq.com/websocket";
    static final String SANDBOX_GATEWAY = "wss://sandbox.api.sgroup.qq.com/websocket";

    /** 单条消息最大长度（官方限制 4000 字节，留余量按字符截断） */
    static final int MAX_CONTENT_LENGTH = 3500;

    /** 被动回复有效窗口：单聊 60 分钟，群聊 5 分钟 */
    static final long C2C_PASSIVE_WINDOW_MS = 60 * 60 * 1000L;
    static final long GROUP_PASSIVE_WINDOW_MS = 5 * 60 * 1000L;

    /** 同一条入站消息允许的被动回复次数上限（平台限制，msg_seq 需在同一 msg_id 内递增） */
    static final int MAX_PASSIVE_REPLIES = 5;

    /** 事件订阅 Intent 映射 */
    private static final Map<String, Integer> INTENTS = new HashMap<>();

    static {
        INTENTS.put("GUILDS", 1 << 0);
        INTENTS.put("GUILD_MEMBERS", 1 << 1);
        INTENTS.put("GUILD_MESSAGES", 1 << 9);
        INTENTS.put("GUILD_MESSAGE_REACTIONS", 1 << 10);
        INTENTS.put("DIRECT_MESSAGE", 1 << 12);
        INTENTS.put("AUDIO_ACTION", 1 << 19);
        INTENTS.put("C2C_MESSAGE_CREATE", 1 << 20);
        INTENTS.put("GROUP_ADD_ROBOT", 1 << 22);
        INTENTS.put("GROUP_DEL_ROBOT", 1 << 23);
        INTENTS.put("GROUP_MESSAGE_CREATE", 1 << 24);
        INTENTS.put("GROUP_AT_MESSAGE_CREATE", 1 << 25);
        INTENTS.put("INTERACTION", 1 << 26);
        INTENTS.put("MESSAGE_AUDIT", 1 << 27);
        INTENTS.put("FORUMS_EVENT", 1 << 28);
    }

    private QqSupport() {
    }

    /**
     * 解析 Intent 配置字符串（逗号分隔的事件名）。
     *
     * <p>方法体与拆分前逐字相同：未知名字只告警、不中断解析，返回的是位掩码。</p>
     */
    static int parseIntents(String names) {
        int mask = 0;
        if (names == null || names.trim().isEmpty()) {
            return mask;
        }
        for (String name : names.split(",")) {
            String key = name.trim().toUpperCase();
            Integer bit = INTENTS.get(key);
            if (bit != null) {
                mask |= bit;
            } else {
                log.warn("未知 intent: " + name);
            }
        }
        return mask;
    }

    /**
     * 安全读取 JsonObject 字符串字段（兼容旧版 Gson，无 optString）。
     *
     * <p>被网关事件分发器（读 group_openid / author / 按钮互动字段）与
     * access_token 错误码处理共用，因此搬到共用层。</p>
     */
    static String optString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        return obj.get(key).getAsString();
    }
}
