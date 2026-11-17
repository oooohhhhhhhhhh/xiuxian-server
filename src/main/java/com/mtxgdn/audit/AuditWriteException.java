package com.mtxgdn.audit;

/**
 * 审计写入失败。
 *
 * <p>由「写操作 / 敏感读」路径抛出：这些操作要求审计先落库才允许执行
 * （fail-closed）。普通读路径捕获后只告警，不阻断请求。</p>
 */
public class AuditWriteException extends RuntimeException {

    public AuditWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
