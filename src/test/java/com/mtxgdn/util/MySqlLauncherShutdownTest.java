package com.mtxgdn.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关闭内置 MySQL 时不能"等 5 秒就 SIGKILL"。
 *
 * <p><b>缺陷形态</b>：{@code killPreviousProcess} 固定等 5 秒后直接
 * {@code destroyForcibly()}（SIGKILL）。而 mysqld 收到终止信号后要做的是
 * <b>刷脏页 + 关 binlog + 释放锁</b>，InnoDB 缓冲池越大这一步越久 ——
 * 5 秒对大缓冲池实例远远不够。于是每次重启都被打断在刷盘中途，
 * 下次启动必须跑一遍崩溃恢复（日志里会出现
 * {@code InnoDB: Database was not shut down normally}）。
 * 数据一般不会丢，但"正常关闭"变成了"每次都是崩溃恢复"。</p>
 *
 * <p>修复：宽限时间可配置（默认 30 秒，最小 5 秒），
 * 真超时才强制结束，并明确告警告知下次启动会走崩溃恢复。</p>
 */
class MySqlLauncherShutdownTest {

    private static final Path SOURCE = Paths.get("src/main/java/com/mtxgdn/util/MySqlLauncher.java");

    @Test
    @DisplayName("宽限时间必须可配置且不小于 5 秒")
    void gracePeriodIsConfigurable() throws IOException {
        String code = stripComments(readSource());
        assertTrue(code.contains("mysql.shutdown_grace_seconds"),
                "宽限时间必须可配置（mysql.shutdown_grace_seconds），否则大缓冲池实例无从调整");
        assertTrue(code.contains("SHUTDOWN_GRACE_SECONDS"),
                "缺少 SHUTDOWN_GRACE_SECONDS 常量");
        assertTrue(code.contains("Math.max(5,"),
                "宽限时间必须有下限（Math.max(5, ...)），不能配置成 0 秒立刻 SIGKILL");
    }

    @Test
    @DisplayName("强制结束前必须先等宽限时间，并且要告警")
    void forceKillOnlyAfterGracePeriod() throws IOException {
        String code = stripComments(readSource());
        // 只看 killPreviousProcess 这一段：文件里另有一处初始化时的 destroyForcibly（进程启动失败时用）
        int start = code.indexOf("private static void killPreviousProcess()");
        assertTrue(start >= 0, "找不到 killPreviousProcess");
        int end = code.indexOf("public static void shutdown()", start);
        assertTrue(end > start, "找不到 killPreviousProcess 的结尾");
        String body = code.substring(start, end);

        int waitIdx = body.indexOf("waitFor(SHUTDOWN_GRACE_SECONDS");
        int killIdx = body.indexOf("destroyForcibly()");
        assertTrue(waitIdx >= 0, "必须先 waitFor(SHUTDOWN_GRACE_SECONDS)");
        assertTrue(killIdx > waitIdx,
                "destroyForcibly() 必须出现在等待宽限时间之后（原来是无条件 5 秒后强杀）：\n" + body);
        assertTrue(body.contains("LOG.warn"),
                "超时强制结束时必须告警说明下次启动会走崩溃恢复");
    }

    @Test
    @DisplayName("源码里不得再出现硬编码的 waitFor(5, ...)")
    void noHardcodedFiveSecondWait() throws IOException {
        String code = stripComments(readSource());
        assertTrue(!code.contains("waitFor(5, TimeUnit.SECONDS)"),
                "宽限时间不该再硬编码成 5 秒");
    }

    private static String readSource() throws IOException {
        assertTrue(Files.exists(SOURCE), "找不到 MySqlLauncher.java: " + SOURCE.toAbsolutePath());
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
