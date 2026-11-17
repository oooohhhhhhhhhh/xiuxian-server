package com.mtxgdn.audit;

/**
 * 审计动作常量。
 *
 * <p>集中定义而不是到处写字符串：查询接口、后台筛选、归档脚本都依赖这些值，
 * 拼错一个字母就会让「按动作过滤」悄悄失效。</p>
 */
public final class AuditActions {

    private AuditActions() {
    }

    // ---- 认证 ----
    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String REGISTER = "REGISTER";
    public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";

    // ---- 管理员后台 ----
    /** 后台写操作（统一入口，detail 里带 HTTP 方法与路径）。 */
    public static final String ADMIN_REQUEST = "ADMIN_REQUEST";
    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_DELETE = "USER_DELETE";
    public static final String PERMISSION_CHANGE = "PERMISSION_CHANGE";
    public static final String PLAYER_EDIT = "PLAYER_EDIT";
    public static final String REDEEM_CODE_CHANGE = "REDEEM_CODE_CHANGE";
    public static final String CONFIG_CHANGE = "CONFIG_CHANGE";
    public static final String DB_QUERY = "DB_QUERY";
    public static final String DB_RESTORE = "DB_RESTORE";
    public static final String DATA_CLEAR = "DATA_CLEAR";

    // ---- 数据读取 ----
    public static final String DATA_READ = "DATA_READ";

    // ---- 系统 ----
    public static final String SYSTEM_START = "SYSTEM_START";
    public static final String SYSTEM_STOP = "SYSTEM_STOP";
    public static final String MIGRATION = "MIGRATION";
    public static final String CLEANUP = "CLEANUP";
    public static final String ARCHIVE = "ARCHIVE";
    public static final String BACKUP = "BACKUP";

    // ---- 适配器 ----
    public static final String ADAPTER_START = "ADAPTER_START";
    public static final String ADAPTER_STOP = "ADAPTER_STOP";
    public static final String QQ_BIND = "QQ_BIND";
    public static final String QQ_UNBIND = "QQ_UNBIND";
    public static final String SEND_FAILED = "SEND_FAILED";

    // ---- 插件 ----
    public static final String PLUGIN_ENABLE = "PLUGIN_ENABLE";
    public static final String PLUGIN_DISABLE = "PLUGIN_DISABLE";
    public static final String PLUGIN_ERROR = "PLUGIN_ERROR";

    // ---- 迁移标记 ----
    public static final String MIGRATED_ACTION_LOGS = "MIGRATED_ACTION_LOGS";
}
