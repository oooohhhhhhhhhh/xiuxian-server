package com.mtxgdn.common;

public enum GameErrorCode {
    OK(0, "ok"),

    AUTH_INVALID_TOKEN(1001, "token无效或已过期"),
    AUTH_NOT_LOGGED_IN(1002, "请先登录"),
    AUTH_USERNAME_EXISTS(1003, "用户名已存在"),
    AUTH_EMAIL_EXISTS(1004, "邮箱已被注册"),
    AUTH_WRONG_PASSWORD(1005, "用户名或密码错误"),

    PARAM_MISSING(2001, "缺少必要参数"),
    PARAM_INVALID(2002, "参数格式错误"),

    PLAYER_NOT_FOUND(3001, "角色不存在，请先创建角色"),
    PLAYER_ALREADY_EXISTS(3002, "角色已存在"),

    ITEM_NOT_FOUND(4001, "物品不存在"),
    ITEM_COUNT_NOT_ENOUGH(4002, "物品数量不足"),
    ITEM_USE_FAILED(4003, "使用物品失败"),

    REALM_BREAKTHROUGH_FAILED(5001, "境界突破失败"),
    REALM_EXP_NOT_ENOUGH(5002, "经验不足"),
    REALM_SPIRIT_STONES_NOT_ENOUGH(5003, "灵石不足"),
    REALM_MAX(5004, "已达最高境界"),
    REALM_NOT_CULTIVATING(5005, "未在修炼状态"),

    SKILL_NOT_FOUND(6101, "技能不存在"),
    SKILL_ALREADY_LEARNED(6102, "技能已学习"),
    SKILL_REALM_TOO_LOW(6103, "境界不足，无法学习该技能"),
    SKILL_LEARN_FAILED(6104, "学习技能失败"),

    PVP_TARGET_NOT_FOUND(7101, "对手不存在"),
    PVP_SELF_TARGET(7102, "不能挑战自己"),

    SECRET_REALM_NOT_FOUND(8101, "秘境不存在"),
    SECRET_REALM_REALM_TOO_LOW(8102, "境界不足，无法进入该秘境"),
    SECRET_REALM_COOLDOWN(8103, "秘境冷却中，请稍后再试"),
    SECRET_REALM_HP_NOT_ENOUGH(8104, "生命值不足，无法进入秘境"),

    EXPLORATION_COOLDOWN(8201, "游历冷却中"),
    EXPLORATION_NO_EVENT(8202, "暂无可用游历事件"),

    UNKNOWN_TYPE(6001, "未知的消息类型"),
    MESSAGE_PARSE_ERROR(6002, "消息格式错误"),

    INTERNAL_ERROR(9001, "服务器内部错误"),
    NETWORK_ERROR(9002, "网络请求失败");

    private final int code;
    private final String message;

    GameErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
