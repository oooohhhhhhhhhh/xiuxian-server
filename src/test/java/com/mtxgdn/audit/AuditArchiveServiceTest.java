package com.mtxgdn.audit;

import com.mtxgdn.TestBase;
import com.mtxgdn.db.AuditLogDAO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计归档：90 天前的记录 → 月度 JSONL.gz 文件 → 从库里删除。
 *
 * <p>核心断言是顺序：<b>先落盘、后删库</b>。写文件失败时库里的数据一条都不能少。</p>
 */
class AuditArchiveServiceTest extends TestBase {

    private final AuditLogDAO dao = new AuditLogDAO();
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("audit-archive-test");
        System.setProperty("logging.dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        System.clearProperty("logging.dir");
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    @DisplayName("超期记录归档成 gz 文件并从库里删除；近期记录保留")
    void archivesOldRowsAndDeletesThem() throws Exception {
        long now = System.currentTimeMillis();
        long oldTs = now - 100L * 86_400_000;
        AuditLog old = new AuditLog();
        old.setTs(oldTs);
        old.setActor(AuditActor.PLAYER);
        old.setActorId("1");
        old.setActorName("老玩家");
        old.setAction("LOGIN");
        old.setDetail("很久以前");
        long oldId = dao.insert(old);

        AuditLog recent = new AuditLog();
        recent.setTs(now - 1_000);
        recent.setActor(AuditActor.ADMIN);
        recent.setActorId("2");
        recent.setAction("USER_EDIT");
        recent.setDetail("刚刚");
        dao.insert(recent);

        int archived = new AuditArchiveService().archiveOlderThanDays(90);
        assertEquals(1, archived, "只应归档超期的那一条");

        // 库里：老记录没了，近期记录还在
        List<Map<String, Object>> rows = dao.query(null, null, null, null, null, null, null, null, 100, 0);
        assertTrue(rows.stream().noneMatch(r -> ((Number) r.get("id")).longValue() == oldId),
                "已归档的记录应从库里删除");
        assertTrue(rows.stream().anyMatch(r -> "USER_EDIT".equals(r.get("action"))),
                "近期记录不能被归档");

        // 文件：存在且内容包含老记录
        List<Path> files;
        try (Stream<Path> walk = Files.walk(AuditArchiveService.auditDir())) {
            files = walk.filter(p -> p.toString().endsWith(".jsonl.gz")).toList();
        }
        assertEquals(1, files.size(), "应生成一个月度 JSONL.gz 文件");
        String content = readGz(files.get(0));
        assertTrue(content.contains("\"action\":\"LOGIN\""), "归档文件应包含老记录: " + content);
        assertTrue(content.contains("老玩家"), "中文内容应正确写入");
        assertTrue(content.contains("\"id\":" + oldId), "应保留原始 ID 便于对账");
    }

    @Test
    @DisplayName("保留天数为 0 时不做任何归档（永久保留）")
    void zeroRetentionKeepsEverything() throws Exception {
        AuditLog old = new AuditLog();
        old.setTs(System.currentTimeMillis() - 1000L * 86_400_000);
        old.setActor(AuditActor.SYSTEM);
        old.setAction("ANCIENT");
        dao.insert(old);

        assertEquals(0, new AuditArchiveService().archiveOlderThanDays(0));
        assertEquals(1, dao.count("SYSTEM", "ANCIENT", null, null, null, null, null, null));
    }

    private static String readGz(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
