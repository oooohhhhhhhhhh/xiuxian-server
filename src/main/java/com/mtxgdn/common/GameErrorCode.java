package com.mtxgdn.common;

import com.mtxgdn.i18n.I18n;

/**
 * 统一错误码。
 *
 * <p>每个错误码携带三份信息：</p>
 * <ul>
 *   <li>{@link #getCode()} —— 数字码，客户端逻辑判断用</li>
 *   <li>{@link #getKey()} —— i18n key，新客户端用它本地化</li>
 *   <li>{@link #getMessage()} —— zh_cn 兜底文案，老客户端与日志用</li>
 * </ul>
 *
 * <p>{@link #getLocalized()} 按当前请求语言取文案。刻意把 message 保留在枚举里
 * 而不是全部塞进语言包：语言包缺失时仍要能给人看一句人话，而不是一行 {@code error.xxx}。</p>
 */
public enum GameErrorCode {
    OK(0, "error.ok", "ok"),

    AUTH_INVALID_TOKEN(1001, "error.auth_invalid_token", "token无效或已过期"),
    AUTH_NOT_LOGGED_IN(1002, "error.auth_not_logged_in", "请先登录"),
    AUTH_USERNAME_EXISTS(1003, "error.auth_username_exists", "用户名已存在"),
    AUTH_EMAIL_EXISTS(1004, "error.auth_email_exists", "邮箱已被注册"),
    AUTH_WRONG_PASSWORD(1005, "error.auth_wrong_password", "用户名或密码错误"),

    PARAM_MISSING(2001, "error.param_missing", "缺少必要参数"),
    PARAM_INVALID(2002, "error.param_invalid", "参数格式错误"),

    PLAYER_NOT_FOUND(3001, "error.player_not_found", "角色不存在，请先创建角色"),
    PLAYER_ALREADY_EXISTS(3002, "error.player_already_exists", "角色已存在"),

    ITEM_NOT_FOUND(4001, "error.item_not_found", "物品不存在"),
    ITEM_COUNT_NOT_ENOUGH(4002, "error.item_count_not_enough", "物品数量不足"),
    ITEM_USE_FAILED(4003, "error.item_use_failed", "使用物品失败"),

    REALM_BREAKTHROUGH_FAILED(5001, "error.realm_breakthrough_failed", "境界突破失败"),
    REALM_EXP_NOT_ENOUGH(5002, "error.realm_exp_not_enough", "经验不足"),
    REALM_SPIRIT_STONES_NOT_ENOUGH(5003, "error.realm_spirit_stones_not_enough", "灵石不足"),
    REALM_MAX(5004, "error.realm_max", "已达最高境界"),
    REALM_NOT_CULTIVATING(5005, "error.realm_not_cultivating", "未在修炼状态"),

    SKILL_NOT_FOUND(6101, "error.skill_not_found", "技能不存在"),
    SKILL_ALREADY_LEARNED(6102, "error.skill_already_learned", "技能已学习"),
    SKILL_REALM_TOO_LOW(6103, "error.skill_realm_too_low", "境界不足，无法学习该技能"),
    SKILL_LEARN_FAILED(6104, "error.skill_learn_failed", "学习技能失败"),

    PVP_TARGET_NOT_FOUND(7101, "error.pvp_target_not_found", "对手不存在"),
    PVP_SELF_TARGET(7102, "error.pvp_self_target", "不能挑战自己"),

    SECRET_REALM_NOT_FOUND(8101, "error.secret_realm_not_found", "秘境不存在"),
    SECRET_REALM_REALM_TOO_LOW(8102, "error.secret_realm_realm_too_low", "境界不足，无法进入该秘境"),
    SECRET_REALM_COOLDOWN(8103, "error.secret_realm_cooldown", "秘境冷却中，请稍后再试"),
    SECRET_REALM_HP_NOT_ENOUGH(8104, "error.secret_realm_hp_not_enough", "生命值不足，无法进入秘境"),

    EXPLORATION_COOLDOWN(8201, "error.exploration_cooldown", "游历冷却中"),
    EXPLORATION_NO_EVENT(8202, "error.exploration_no_event", "暂无可用游历事件"),

    CHAT_RECEIVER_NOT_FOUND(8301, "error.chat_receiver_not_found", "接收者不存在"),
    CHAT_SELF_MESSAGE(8302, "error.chat_self_message", "不能给自己发消息"),

    FRIEND_NOT_FOUND(8401, "error.friend_not_found", "好友不存在"),
    FRIEND_ALREADY_EXISTS(8402, "error.friend_already_exists", "已经是好友或已发送过申请"),
    FRIEND_REQUEST_NOT_FOUND(8403, "error.friend_request_not_found", "好友申请不存在"),
    FRIEND_SELF_TARGET(8404, "error.friend_self_target", "不能添加自己为好友"),

    UNKNOWN_TYPE(6001, "error.unknown_type", "未知的消息类型"),
    MESSAGE_PARSE_ERROR(6002, "error.message_parse_error", "消息格式错误"),
    RATE_LIMITED(6003, "error.rate_limited", "操作太频繁，请稍后再试"),

    INTERNAL_ERROR(9001, "error.internal_error", "服务器内部错误"),
    NETWORK_ERROR(9002, "error.network_error", "网络请求失败");

    private final int code;

    /** i18n key，如 {@code error.item_not_found}。新客户端应优先用它本地化。 */
    private final String key;

    /** zh_cn 兜底文案。语言包缺 key 时 {@link #getLocalized()} 会回落到它。 */
    private final String message;

    GameErrorCode(int code, String key, String message) {
        this.code = code;
        this.key = key;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    /** i18n key。 */
    public String getKey() {
        return key;
    }

    /** 固定文案（zh_cn）。不随请求语言变化，供日志与老客户端使用。 */
    public String getMessage() {
        return message;
    }

    /**
     * 按当前请求语言取文案。
     *
     * <p>无请求上下文时（启动期、定时任务线程）落到服务端默认语言。
     * 语言包缺 key 时返回 {@link #getMessage()}——即硬编码兜底，
     * 不会把 {@code error.xxx} 这类 key 露给玩家。</p>
     */
    public String getLocalized() {
        String localized = I18n.t(key);
        // I18n 在缺失时返回 key 本身，这里换成硬编码兜底
        return key.equals(localized) ? message : localized;
    }
}
