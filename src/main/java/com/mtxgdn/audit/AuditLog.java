package com.mtxgdn.audit;


/**
 * 一条审计记录。
 *
 * <p>字段与 {@code audit_logs} 表一一对应；{@code detail} 存 JSON 字符串
 * （前后值、参数摘要、失败原因），长度上限由表结构约束在 2048 字符，
 * 写库前会截断。</p>
 */
public class AuditLog {

    private long id;
    private long ts;
    private AuditActor actor = AuditActor.SYSTEM;
    private String actorId = "";
    private String actorName = "";
    private String action = "";
    private String targetType = "";
    private String targetId = "";
    private String detail = "";
    private String ip = "";
    private AuditResult result = AuditResult.OK;
    private String traceId = "";

    public AuditLog() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getTs() {
        return ts;
    }

    public void setTs(long ts) {
        this.ts = ts;
    }

    public AuditActor getActor() {
        return actor;
    }

    public void setActor(AuditActor actor) {
        this.actor = actor;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId == null ? "" : actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName == null ? "" : actorName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action == null ? "" : action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType == null ? "" : targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId == null ? "" : targetId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail == null ? "" : detail;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip == null ? "" : ip;
    }

    public AuditResult getResult() {
        return result;
    }

    public void setResult(AuditResult result) {
        this.result = result == null ? AuditResult.OK : result;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId == null ? "" : traceId;
    }

}
