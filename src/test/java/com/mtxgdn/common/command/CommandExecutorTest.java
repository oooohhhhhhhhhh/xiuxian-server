package com.mtxgdn.common.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指令执行必须离开 Grizzly worker 线程，且线程池要"有界 + 不吞异常"。
 *
 * <p><b>缺陷形态</b>（P1-3）：QQ / Minecraft 通道的指令回调直接跑在 Grizzly 的
 * worker 线程上（core=4 / max=8），而这个池同时服务所有 REST 请求。一条指令
 * 3~12 次数据库查询，{@code /注册} 还要跑 BCrypt（故意慢），于是几个玩家同时注册
 * 就能把 worker 占满 —— 管理台、静态资源、健康检查<b>一起</b>卡住。</p>
 *
 * <p>修复：{@link CommandExecutor} 提供独立的有界线程池，worker 只负责投递。</p>
 */
class CommandExecutorTest {

    @Test
    @DisplayName("任务跑在独立的 cmd-worker 线程上，不占调用线程")
    void runsOnDedicatedThread() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<String> callerName = new AtomicReference<>(Thread.currentThread().getName());

        assertTrue(CommandExecutor.submit("test:thread", () -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        }), "正常情况应该入队成功");

        assertTrue(done.await(5, TimeUnit.SECONDS), "任务应在 5 秒内执行");
        assertNotNull(threadName.get());
        assertTrue(threadName.get().startsWith("cmd-worker-"),
                "指令应在专用线程池执行，实际线程: " + threadName.get());
        assertFalse(threadName.get().equals(callerName.get()),
                "不能退化成在调用线程上同步执行");
    }

    @Test
    @DisplayName("任务抛异常不会打死线程池，也不会静默消失")
    void exceptionDoesNotKillPool() throws Exception {
        CountDownLatch second = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean(false);

        assertTrue(CommandExecutor.submit("test:boom", () -> {
            throw new IllegalStateException("故意抛出的异常");
        }), "抛异常的任务也应该入队成功");

        assertTrue(CommandExecutor.submit("test:after", () -> {
            secondRan.set(true);
            second.countDown();
        }), "异常之后线程池仍应可用");

        assertTrue(second.await(5, TimeUnit.SECONDS), "后续任务必须还能执行");
        assertTrue(secondRan.get());
    }

    @Test
    @DisplayName("线程池有界，配置项存在")
    void poolIsBoundedAndConfigurable() throws IOException {
        assertTrue(CommandExecutor.getThreads() >= 1, "线程数至少 1");
        assertTrue(CommandExecutor.getQueueCapacity() >= 1, "队列上限至少 1");

        String yml = read("src/main/resources/application.yml");
        assertTrue(yml.contains("command_threads"), "application.yml 必须提供 performance.command_threads");
        assertTrue(yml.contains("command_queue"), "application.yml 必须提供 performance.command_queue");
        assertTrue(yml.contains("有界"), "配置注释要说明队列有界是刻意的");
    }

    @Test
    @DisplayName("四个异步分发点都走 CommandExecutor，同步取输出的那个保持不变")
    void dispatchSitesUseExecutor() throws IOException {
        // 异步通道：结果通过 socket/sender 回发，可以离开调用线程
        assertUsesExecutor("src/main/java/com/mtxgdn/onebot/OneBotWebSocketServer.java");
        assertUsesExecutor("src/main/java/com/mtxgdn/qq/QqOfficialAdapter.java");
        assertUsesExecutor("src/main/java/com/mtxgdn/minecraft/adapter/MinecraftAdapter.java");
        assertUsesExecutor("src/main/java/com/mtxgdn/minecraft/adapter/MinecraftPlayerAdapter.java");

        // 同步通道：handleMcCommand 要把指令输出作为返回值给 HTTP 调用方，必须留在调用线程
        String mc = stripComments(read("src/main/java/com/mtxgdn/minecraft/adapter/MinecraftAdapter.java"));
        int syncIdx = mc.indexOf("public McCommandResult handleMcCommand");
        assertTrue(syncIdx > 0, "找不到 handleMcCommand");
        int syncEnd = mc.indexOf("private void dispatchMcCommand", syncIdx);
        assertTrue(syncEnd > syncIdx);
        String syncBody = mc.substring(syncIdx, syncEnd);
        assertTrue(syncBody.contains("command.execute(ctx)"),
                "handleMcCommand 是同步取输出的接口，必须直接执行（不能异步）");
        assertFalse(syncBody.contains("CommandExecutor.submit"),
                "handleMcCommand 不能改成异步：它要把输出作为返回值交回去");
    }

    @Test
    @DisplayName("关服时先停指令池、再关数据库")
    void shutdownOrderIsCorrect() throws IOException {
        String main = stripComments(read("src/main/java/com/mtxgdn/Main.java"));
        int executorIdx = main.indexOf("CommandExecutor.shutdown()");
        int dbIdx = main.indexOf("DatabaseManager.shutdown()");
        assertTrue(executorIdx > 0, "Main 的关闭流程必须停止指令线程池");
        assertTrue(dbIdx > 0, "找不到 DatabaseManager.shutdown()");
        assertTrue(executorIdx < dbIdx,
                "指令池必须在数据库关闭之前停：指令执行要用数据库连接");
    }

    // ==================== 夹具 ====================

    private static void assertUsesExecutor(String file) throws IOException {
        String code = stripComments(read(file));
        assertTrue(code.contains("CommandExecutor.submit"),
                file + " 的指令分发必须走 CommandExecutor，否则仍会阻塞 Grizzly worker 线程");
        assertTrue(code.contains("服务器繁忙"),
                file + " 在线程池拒绝时应该明确告知玩家，而不是假装成功");
    }

    private static String read(String relative) throws IOException {
        Path path = Paths.get(relative);
        assertTrue(Files.exists(path), "找不到文件: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
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
