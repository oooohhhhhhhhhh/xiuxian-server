package com.mtxgdn.common.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台定时任务必须能被关掉，并且不能在连接池关闭后继续查库。
 *
 * <p><b>缺陷形态</b>：仓库里有十来个服务在<b>静态初始化块</b>里直接起
 * "每 N 秒扫一遍"的守护任务，而：</p>
 *
 * <ul>
 *   <li>有的用 {@code Executors.newSingleThreadScheduledExecutor()} ——
 *       线程不是 daemon，只要它活着 JVM 就不退出，忘了 shutdown 就"关不掉"；</li>
 *   <li>关服钩子里只停了 3 个服务，其余（玩家自动回血、增益过期、洞府灵气、
 *       阵法、天象、宗门换届）没人停，关服后还会继续查已经关闭的连接池，
 *       刷一屏 "Connection is closed" 把真正的关闭日志淹掉；</li>
 *   <li>测试里每个测试类都会 {@code DatabaseManager.init()} 换一次池，
 *       这些常驻任务于是和新池抢写锁，表现为"某个毫不相干的测试突然
 *       {@code SQLITE_BUSY} 或者连接 10 秒超时"。</li>
 * </ul>
 *
 * <p>本测试盯住三件事：定时任务的线程是 daemon 且带名字、每次 tick 都先问
 * {@code DatabaseManager.isReady()}、以及关服钩子确实把每个服务都停了。</p>
 */
class ScheduledTaskLifecycleTest {

    private static final Path SERVICES = Paths.get("src/main/java/com/mtxgdn/game/service");
    private static final Path MAIN = Paths.get("src/main/java/com/mtxgdn/Main.java");
    private static final Path SHUTDOWN_HOOK =
            Paths.get("src/main/java/com/mtxgdn/server/ServerShutdownHook.java");

    /**
     * 静态块里起定时任务的服务 → 它的停止方法名。
     *
     * <p>名字不统一是历史原因（{@code shutdownScheduler} / {@code shutdownSuccessionSweeper}），
     * 本测试只保证"每个都有能停掉它的方法、并且关服路径真的调了它"。</p>
     */
    private static final java.util.Map<String, String> SCHEDULED_SERVICES = new java.util.LinkedHashMap<>();

    static {
        SCHEDULED_SERVICES.put("PlayerService", "shutdown");
        SCHEDULED_SERVICES.put("BuffService", "shutdownScheduler");
        SCHEDULED_SERVICES.put("CaveService", "shutdown");
        SCHEDULED_SERVICES.put("FormationService", "shutdown");
        SCHEDULED_SERVICES.put("SeasonService", "shutdown");
        SCHEDULED_SERVICES.put("TradeService", "shutdown");
        SCHEDULED_SERVICES.put("EconomyService", "shutdown");
        SCHEDULED_SERVICES.put("SectService", "shutdownSuccessionSweeper");
    }

    @Test
    @DisplayName("每个起定时任务的服务都必须有可调用的 public static 停止方法")
    void everyScheduledServiceIsStoppable() throws IOException {
        List<String> missing = new ArrayList<>();
        for (var entry : SCHEDULED_SERVICES.entrySet()) {
            String code = stripComments(read(entry.getKey()));
            String signature = "public static void " + entry.getValue() + "()";
            if (!code.contains(signature)) {
                missing.add(entry.getKey() + " 缺少 " + signature);
            }
        }
        assertTrue(missing.isEmpty(),
                "这些服务起了定时任务却没有停止方法，关服时没人能停掉它们：\n"
                        + String.join("\n", missing));
    }

    @Test
    @DisplayName("关服路径必须停掉每一个定时任务服务")
    void shutdownPathStopsEveryScheduledService() throws IOException {
        // 关服任务分散在两个地方：Main 自己 addTask，以及
        // ServerShutdownHook.createDefault()（Main 会调用它）
        String shutdownPath = stripComments(Files.readString(MAIN, StandardCharsets.UTF_8))
                + "\n"
                + stripComments(Files.readString(SHUTDOWN_HOOK, StandardCharsets.UTF_8));

        List<String> missing = new ArrayList<>();
        for (var entry : SCHEDULED_SERVICES.entrySet()) {
            String call = entry.getValue() + "()";
            String byReference = entry.getKey() + "::" + entry.getValue();
            String byCall = entry.getKey() + "." + call;
            String byQualified = "service." + entry.getKey() + "." + call;
            if (!shutdownPath.contains(byReference)
                    && !shutdownPath.contains(byCall)
                    && !shutdownPath.contains(byQualified)) {
                missing.add(entry.getKey() + "（应调用 " + entry.getValue() + "()）");
            }
        }
        assertTrue(missing.isEmpty(),
                "关服路径里没有停掉这些定时任务（关服后它们还会去查已经关掉的连接池）：\n"
                        + String.join("\n", missing));
    }

    @Test
    @DisplayName("定时任务的线程必须是 daemon 且带可读名字")
    void schedulerThreadsAreDaemonAndNamed() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (var walk = Files.walk(SERVICES)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!code.contains("ScheduledExecutorService")) {
                    continue;
                }
                boolean usesHelper = code.contains("Schedulers.newDaemonSingle(");
                boolean explicitDaemon = code.contains("setDaemon(true)");
                // 只关心"静态初始化块里起定时任务"的那些；不新建执行器的跳过
                boolean createsExecutor = code.contains("Executors.newSingleThreadScheduledExecutor(")
                        || code.contains("newScheduledThreadPool(");
                if (createsExecutor && !explicitDaemon) {
                    offenders.add(file.getFileName() + " 自己 new 了执行器却没设 setDaemon(true)"
                            + "（非 daemon 线程会阻止 JVM 退出，忘了 shutdown 就关不掉）");
                } else if (!createsExecutor && !usesHelper && !explicitDaemon
                        && code.contains("scheduleAtFixedRate(")) {
                    offenders.add(file.getFileName() + " 起了定时任务，但执行器既不是 daemon 也不是 "
                            + "Schedulers.newDaemonSingle 建的");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "定时任务线程必须 daemon 且带名字：\n" + String.join("\n", offenders));
    }

    @Test
    @DisplayName("每个定时 tick 方法都要先检查连接池是否可用")
    void everyTickGuardsOnDatabaseReady() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String service : SCHEDULED_SERVICES.keySet()) {
            String code = stripComments(read(service));
            // 找出 scheduleAtFixedRate(Cls::method, ...) 形式注册的方法
            Matcher m = Pattern.compile("scheduleAtFixedRate\\s*\\(\\s*" + service + "\\s*::\\s*(\\w+)")
                    .matcher(code);
            Set<String> tickMethods = new LinkedHashSet<>();
            while (m.find()) {
                tickMethods.add(m.group(1));
            }
            for (String method : tickMethods) {
                String body = methodBody(code, method);
                if (body == null) {
                    offenders.add(service + "." + method + "：找不到方法体，测试无法确认守卫是否存在");
                    continue;
                }
                if (!body.contains("DatabaseManager.isReady()")) {
                    offenders.add(service + "." + method
                            + "：tick 开头没有 DatabaseManager.isReady() 守卫，"
                            + "连接池关掉之后它还会继续查库并刷异常");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "定时 tick 必须先检查连接池：\n" + String.join("\n", offenders));
    }

    @Test
    @DisplayName("扫描到的 tick 方法不能为空，否则上面的断言等于空转")
    void scanIsNotEmpty() throws IOException {
        int found = 0;
        for (String service : SCHEDULED_SERVICES.keySet()) {
            String code = stripComments(read(service));
            Matcher m = Pattern.compile("scheduleAtFixedRate\\s*\\(\\s*" + service + "\\s*::\\s*(\\w+)")
                    .matcher(code);
            while (m.find()) {
                found++;
            }
        }
        assertTrue(found > 0, "一个 tick 方法都没扫到，守卫断言等于空转");
    }

    // ==================== 夹具 ====================

    private static String read(String service) throws IOException {
        return Files.readString(SERVICES.resolve(service + ".java"), StandardCharsets.UTF_8);
    }

    /** 用花括号配对取方法体；找不到返回 null。 */
    private static String methodBody(String code, String methodName) {
        Matcher decl = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(\\s*\\)\\s*\\{")
                .matcher(code);
        if (!decl.find()) {
            return null;
        }
        int start = decl.end() - 1;
        int depth = 0;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** 去注释：注释里的示例不算数，也不能拿注释当断言锚点。 */
    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("守卫方法本身要存在（防止有人把 isReady 改名后测试空转）")
    void databaseManagerExposesReadinessCheck() throws IOException {
        String code = stripComments(Files.readString(
                Paths.get("src/main/java/com/mtxgdn/db/DatabaseManager.java"), StandardCharsets.UTF_8));
        assertTrue(code.contains("public static boolean isReady()"),
                "DatabaseManager.isReady() 不见了 —— 定时任务的守卫会全部失效");
        assertFalse(code.isEmpty(), "源码为空");
    }
}
