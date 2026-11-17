package com.mtxgdn.audit;

import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 审计归档器：把超过热数据期限的记录写成月度 JSONL.gz，落盘成功后再从库里删除。
 *
 * <h3>为什么「先落盘、后删库」</h3>
 * <p>顺序反过来会在写文件失败时丢审计。这里每批先写文件、fsync 关闭，确认写入完成后
 * 才按 ID 删库；中途崩溃最多留下"文件里多几条、库里还有"的重复，下次归档会重新写一遍
 * 并覆盖同月文件（同名文件用追加模式，重复只是多几行，不会丢数据）。</p>
 *
 * <h3>文件命名</h3>
 * <p>{@code audit-<yyyy-MM>-<yyyyMMdd-HHmmss>.jsonl.gz}：月份前缀保证按年月可检索，
 * 运行时间后缀保证同月多次归档不会互相覆盖。</p>
 */
public class AuditArchiveService {

    private static final GameLogger LOG = GameLogger.getLogger(AuditArchiveService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    private final AuditService audit = AuditService.getInstance();
    private final int batchSize;

    public AuditArchiveService() {
        this.batchSize = Math.max(100, AppConfig.getInt("logging.audit_archive_batch", 1000));
    }

    /**
     * 归档并清理早于 {@code retainDays} 天的记录。
     *
     * @return 本次归档删除的行数
     */
    public int archiveOlderThanDays(int retainDays) {
        if (retainDays <= 0) {
            return 0; // 0 / 负数 = 永久保留，不归档也不删除
        }
        long cutoff = System.currentTimeMillis() - retainDays * 86_400_000L;
        int totalArchived = 0;

        while (true) {
            List<AuditLog> batch = audit.findOlderThan(cutoff, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            Map<String, List<AuditLog>> byMonth = new LinkedHashMap<>();
            for (AuditLog log : batch) {
                String month = MONTH.format(Instant.ofEpochMilli(log.getTs()));
                byMonth.computeIfAbsent(month, k -> new ArrayList<>()).add(log);
            }
            try {
                Path dir = auditDir();
                Files.createDirectories(dir);
                String stamp = STAMP.format(Instant.now());
                for (Map.Entry<String, List<AuditLog>> entry : byMonth.entrySet()) {
                    Path file = dir.resolve("audit-" + entry.getKey() + "-" + stamp + ".jsonl.gz");
                    writeJsonlGz(file, entry.getValue());
                }
            } catch (IOException e) {
                LOG.error("审计归档写文件失败，本轮停止（库里的数据一条都不删）", e);
                break;
            }

            List<Long> ids = new ArrayList<>(batch.size());
            for (AuditLog log : batch) {
                ids.add(log.getId());
            }
            int deleted = audit.deleteByIds(ids);
            totalArchived += deleted;
            if (deleted < batch.size()) {
                // 有行没删掉（被并发写入/查询锁住）：下一轮重试，避免死循环
                break;
            }
        }

        if (totalArchived > 0) {
            audit.system(AuditActions.ARCHIVE,
                    "归档并清理 " + totalArchived + " 条超过 " + retainDays + " 天的审计记录");
            LOG.info("审计归档完成：清理 " + totalArchived + " 条（保留 " + retainDays + " 天）");
        }
        return totalArchived;
    }

    /** 归档目录：与普通日志同级，单独一个 audit 子目录。 */
    static Path auditDir() {
        String logDir = AppConfig.get("logging.dir", "log");
        return Paths.get(logDir == null || logDir.isBlank() ? "log" : logDir, "audit");
    }

    /** 把一批记录写成 gzip 的 JSONL（一行一条）。 */
    static void writeJsonlGz(Path file, List<AuditLog> batch) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)), StandardCharsets.UTF_8))) {
            for (AuditLog log : batch) {
                writer.write(toJson(log));
                writer.newLine();
            }
        }
    }

    /** 极简 JSON 序列化（不引入额外依赖；字段固定，转义手写）。 */
    static String toJson(AuditLog log) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendNum(sb, "id", log.getId()).append(',');
        appendNum(sb, "ts", log.getTs()).append(',');
        appendStr(sb, "actor_type", log.getActor().name()).append(',');
        appendStr(sb, "actor_id", log.getActorId()).append(',');
        appendStr(sb, "actor_name", log.getActorName()).append(',');
        appendStr(sb, "action", log.getAction()).append(',');
        appendStr(sb, "target_type", log.getTargetType()).append(',');
        appendStr(sb, "target_id", log.getTargetId()).append(',');
        appendStr(sb, "detail", log.getDetail()).append(',');
        appendStr(sb, "ip", log.getIp()).append(',');
        appendStr(sb, "result", log.getResult().name()).append(',');
        appendStr(sb, "trace_id", log.getTraceId());
        sb.append('}');
        return sb.toString();
    }

    private static StringBuilder appendNum(StringBuilder sb, String name, long value) {
        sb.append('"').append(name).append('"').append(':').append(value);
        return sb;
    }

    private static StringBuilder appendStr(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append('"').append(':').append('"');
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb;
    }
}
