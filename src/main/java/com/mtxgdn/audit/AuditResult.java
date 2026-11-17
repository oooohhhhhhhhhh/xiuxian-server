package com.mtxgdn.audit;

/**
 * 审计结果：这件事最后成了没有。
 *
 * <p>{@link #DENY} 与 {@link #FAIL} 的区别很重要：</p>
 * <ul>
 *   <li>{@code DENY} = 规则拒绝（权限不足、密码错误、重复绑定）——操作被拦下，系统正常；</li>
 *   <li>{@code FAIL} = 执行出错（数据库异常、适配器 API 失败）——操作尝试过但没成功。</li>
 * </ul>
 * <p>排障时先看 FAIL，安全审计时先看 DENY。</p>
 */
public enum AuditResult {
    OK,
    DENY,
    FAIL
}
