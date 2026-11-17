package com.mtxgdn.audit;

/**
 * 审计主体：谁做了这件事。
 *
 * <p>用枚举而不是字符串，是为了让「玩家行为 / 管理员操作 / 系统任务 / 适配器 / 插件」
 * 这五类在数据库、查询接口、后台筛选里始终是同一套取值，不会出现
 * {@code "admin"} / {@code "ADMIN"} / {@code "管理员"} 三种写法并存。</p>
 */
public enum AuditActor {
    /** 玩家。 */
    PLAYER,
    /** 管理员（后台登录后的一切写操作）。 */
    ADMIN,
    /** 系统任务（启动、迁移、清理、归档、定时任务、自动继位等）。 */
    SYSTEM,
    /** 消息适配器（QQ 官方 / OneBot / Minecraft）。 */
    ADAPTER,
    /** 插件（启停、异常、注册的资源）。 */
    PLUGIN
}
