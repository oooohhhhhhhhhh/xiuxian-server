package com.mtxgdn.audit;

import com.mtxgdn.db.AuditLogDAO;
import com.mtxgdn.util.GameLogger;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 审计服务：所有主体（玩家 / 管理员 / 系统 / 适配器 / 插件）的统一入口。
 *
 * <h3>两种写入语义</h3>
 * <ul>
 *   <li>{@link #recordOrThrow}：<b>fail-closed</b>。写库失败就抛异常，调用方据此拒绝操作。
 *       用于状态变更、登录、权限、敏感数据读写 —— 审计写不进去就不允许发生；</li>
 *   <li>{@link #recordQuietly}：<b>fail-open</b>。写库失败只告警，不阻断。
 *       用于普通数据读取与旁路事件（发送失败日志等），避免审计库故障把全站拖垮。</li>
 * </ul>
 *
 * <h3>为什么有 begin/finish</h3>
 * <p>fail-closed 的写操作需要「先写审计、再执行、最后回填结果」：如果等操作执行完再写，
 * 写失败时已经无法撤销。{@link #begin} 先落一条「进行中」记录（失败即拒绝），
 * 操作结束后 {@link #finish} 把结果回填。</p>
 */
public final class AuditService {

    private static final GameLogger LOG = GameLogger.getLogger(AuditService.class);
    private static final AuditService INSTANCE = new AuditService();
    private static final int QUERY_LIMIT_MAX = 500;

    private final AuditLogDAO dao = new AuditLogDAO();

    private AuditService() {
    }

    public static AuditService getInstance() {
        return INSTANCE;
    }

    // ==================== 写入 ====================

    /** fail-closed：写失败抛 {@link AuditWriteException}。 */
    public long recordOrThrow(AuditActor actor, String actorId, String actorName, String action,
                              String targetType, String targetId, String detail, String ip,
                              AuditResult result, String traceId) {
        AuditLog log = build(actor, actorId, actorName, action, targetType, targetId, detail, ip, result, traceId);
        try {
            return dao.insert(log);
        } catch (SQLException e) {
            LOG.error("审计写入失败（将拒绝本次操作）: " + log.getActor() + "." + log.getAction()
                    + " -> " + log.getTargetType() + ":" + log.getTargetId(), e);
            throw new AuditWriteException("审计日志写入失败，操作已拒绝", e);
        }
    }

    /** fail-open：写失败只告警。返回 -1 表示没写进去。 */
    public long recordQuietly(AuditActor actor, String actorId, String actorName, String action,
                              String targetType, String targetId, String detail, String ip,
                              AuditResult result, String traceId) {
        AuditLog log = build(actor, actorId, actorName, action, targetType, targetId, detail, ip, result, traceId);
        try {
            return dao.insert(log);
        } catch (SQLException e) {
            LOG.error("审计写入失败（已放行）: " + log.getActor() + "." + log.getAction(), e);
            return -1;
        }
    }

    /** 预写一条进行中记录（fail-closed）；失败抛 {@link AuditWriteException}。 */
    public long begin(AuditActor actor, String actorId, String actorName, String action,
                      String targetType, String targetId, String detail, String ip, String traceId) {
        AuditLog log = build(actor, actorId, actorName, action, targetType, targetId, detail, ip,
                AuditResult.OK, traceId);
        try {
            return dao.insertPending(log);
        } catch (SQLException e) {
            LOG.error("审计预写失败（将拒绝本次操作）: " + action, e);
            throw new AuditWriteException("审计日志写入失败，操作已拒绝", e);
        }
    }

    /** 回填执行结果；失败只告警（此时操作已经发生，不能反过来阻断）。 */
    public void finish(long auditId, AuditResult result, String detail) {
        if (auditId <= 0) {
            return;
        }
        try {
            dao.updateResult(auditId, result, detail);
        } catch (Exception e) {
            LOG.error("审计结果回填失败: id=" + auditId + ", result=" + result, e);
        }
    }

    // ==================== 便捷入口 ====================

    /** 玩家行为（fail-open：和旧的行为日志语义一致，不阻断玩法）。 */
    public void player(long userId, String playerName, String action, String detail) {
        recordQuietly(AuditActor.PLAYER, String.valueOf(userId), playerName, action,
                "PLAYER", String.valueOf(userId), detail, "", AuditResult.OK, "");
    }

    /** 系统任务。 */
    public void system(String action, String detail) {
        recordQuietly(AuditActor.SYSTEM, "system", "system", action, "", "", detail, "",
                AuditResult.OK, "");
    }

    /** 适配器事件。 */
    public void adapter(String adapterId, String action, String detail) {
        recordQuietly(AuditActor.ADAPTER, adapterId, adapterId, action, "ADAPTER", adapterId,
                detail, "", AuditResult.OK, "");
    }

    /** 插件事件。 */
    public void plugin(String pluginName, String action, String detail) {
        recordQuietly(AuditActor.PLUGIN, pluginName, pluginName, action, "PLUGIN", pluginName,
                detail, "", AuditResult.OK, "");
    }

    // ==================== 查询 ====================

    public List<Map<String, Object>> query(String actorType, String action, String actorId,
                                           String targetId, String result, String keyword,
                                           Long fromTs, Long toTs, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, QUERY_LIMIT_MAX));
        return dao.query(actorType, action, actorId, targetId, result, keyword,
                fromTs, toTs, safeLimit, Math.max(0, offset));
    }

    public int count(String actorType, String action, String actorId, String targetId,
                     String result, String keyword, Long fromTs, Long toTs) {
        return dao.count(actorType, action, actorId, targetId, result, keyword, fromTs, toTs);
    }

    public List<AuditLog> findOlderThan(long cutoffTs, int limit) {
        return dao.findOlderThan(cutoffTs, limit);
    }

    public int deleteByIds(List<Long> ids) {
        return dao.deleteByIds(ids);
    }

    // ==================== 内部 ====================

    private static AuditLog build(AuditActor actor, String actorId, String actorName, String action,
                                  String targetType, String targetId, String detail, String ip,
                                  AuditResult result, String traceId) {
        AuditLog log = new AuditLog();
        log.setTs(System.currentTimeMillis());
        log.setActor(actor == null ? AuditActor.SYSTEM : actor);
        log.setActorId(actorId);
        log.setActorName(actorName);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        log.setIp(ip);
        log.setResult(result == null ? AuditResult.OK : result);
        log.setTraceId(traceId);
        return log;
    }
}
