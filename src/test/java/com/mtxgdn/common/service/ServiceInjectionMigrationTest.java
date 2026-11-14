package com.mtxgdn.common.service;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "服务获取方式"迁移是否完成的守护测试。
 *
 * <p><b>背景</b>：迁移前全仓有约 450 处 {@code ServiceRegistry.getXxxService()}，
 * 那是一层看不见的全局状态 —— 测试之间互相污染、依赖关系看不出来、
 * 换一套实现无从下手。迁移把服务获取收窄成两条路：</p>
 *
 * <ul>
 *   <li><b>构造注入</b>：服务、指令、REST 资源、适配器通过参数/字段拿到
 *       {@link ServiceLookup}；</li>
 *   <li><b>框架边界</b>：指令扫描、Jersey 资源创建、WebSocket 静态持有者、
 *       启动流程这些"框架自己 new 对象"的地方，在边界上取一次当前容器。</li>
 * </ul>
 *
 * <p>本测试钉死这两条边界，防止迁移成果被慢慢侵蚀：只要有人重新加回
 * 一个静态 {@code getXxxService()}，或在业务类里随手取容器，就会失败。</p>
 */
class ServiceInjectionMigrationTest {

    private static final Path MAIN = Paths.get("src/main/java");
    private static final Path FACADE = MAIN.resolve("com/mtxgdn/common/service/ServiceRegistry.java");
    private static final Path SERVICES_DIR = MAIN.resolve("com/mtxgdn/game/service");

    /**
     * 允许直接引用 {@code ServiceRegistry.container()} 的"框架边界"清单。
     *
     * <p>往这里加一项必须先回答：这个类为什么不能通过构造参数拿到服务？
     * 合理的答案只有"框架用无参反射创建它"（指令、REST 资源、WebSocket 处理器）
     * 或"它是进程级启动流程"（Main）。业务逻辑没有资格进这个清单。</p>
     */
    private static final Set<String> CONTAINER_ALLOWLIST = Set.of(
            // 门面自身与启动流程
            "com/mtxgdn/common/service/ServiceRegistry.java",
            "com/mtxgdn/Main.java",
            // 指令框架：反射无参构造，只能在校验/扫描边界注入
            "com/mtxgdn/common/command/Command.java",
            "com/mtxgdn/common/command/CommandContext.java",
            "com/mtxgdn/common/command/CommandScanner.java",
            // REST：Jersey 反射创建资源
            "com/mtxgdn/rest/BaseResource.java",
            "com/mtxgdn/rest/GameSupport.java",
            "com/mtxgdn/rest/AdminSupport.java",
            // WebSocket：静态处理器，没有实例可注入
            "com/mtxgdn/websocket/WsSupport.java",
            // 适配器：由 AdapterManager 在启动期装配
            "com/mtxgdn/onebot/OneBotWebSocketServer.java",
            "com/mtxgdn/qq/QqAccountFlow.java",
            "com/mtxgdn/qq/QqMessageRouter.java",
            "com/mtxgdn/minecraft/adapter/MinecraftAdapter.java",
            "com/mtxgdn/minecraft/adapter/MinecraftAuthService.java",
            "com/mtxgdn/minecraft/adapter/MinecraftPlayerAdapter.java",
            // 插件层：PluginContext 的回落是交接文档明确保留的兼容路径
            "com/mtxgdn/plugin/PluginContext.java",
            "com/mtxgdn/plugin/builtin/SectQueryProvider.java",
            // 语言解析 / 探索事件：无实例上下文的静态工具
            "com/mtxgdn/i18n/LanguageResolver.java",
            "data/mtxgdn/explorationevent/MonsterEvent.java",
            // 图鉴 hook：散布在 8 个业务服务里的纯旁路探针，拿不到 ServiceLookup，
            // 失败必须被吞掉、绝不能影响主玩法，因此允许取全局容器
            "com/mtxgdn/game/collection/CollectionHooks.java");

    @Test
    @DisplayName("静态门面只剩 container/install/reset 三个方法")
    void facadeOnlyExposesContainerLifecycle() throws IOException {
        List<String> publicMembers = new ArrayList<>();
        for (String line : stripComments(Files.readString(FACADE, StandardCharsets.UTF_8)).split("\\R")) {
            Matcher m = Pattern.compile("public static [\\w.<>\\[\\]]+ (\\w+)\\(").matcher(line);
            if (m.find()) {
                publicMembers.add(m.group(1));
            }
        }
        assertEquals(List.of("container", "install", "reset"), publicMembers,
                "静态门面一旦多出 getXxxService()，新代码就会继续用它，迁移会停在半路");
    }

    @Test
    @DisplayName("主源码里不允许再出现 ServiceRegistry.getXxxService()")
    void noStaticServiceGettersInMainSource() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = Pattern.compile("ServiceRegistry\\.(get\\w+Service|getMcBindingService)\\(")
                        .matcher(code);
                if (m.find()) {
                    offenders.add(MAIN.relativize(file) + " -> " + m.group(0));
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "仍在使用静态服务 getter（迁移已完成，请改成构造注入或框架边界取容器）：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("ServiceRegistry.container() 只能出现在框架边界")
    void containerAccessIsConfinedToFrameworkBoundaries() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!code.contains("ServiceRegistry.container()")) {
                    continue;
                }
                seen.add(rel);
                if (!CONTAINER_ALLOWLIST.contains(rel)) {
                    offenders.add(rel);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些业务类直接取了全局容器，应改为构造注入 ServiceLookup：\n"
                        + String.join("\n", offenders));
        // 反向检查：白名单里允许的文件确实还在用（避免以后清空了列表也"通过"）
        assertFalse(seen.isEmpty(), "一个引用容器的地方都没扫到，本测试等于空转");
    }

    @Test
    @DisplayName("服务只能通过构造参数拿查询面，不得回落到静态容器")
    void servicesUseConstructorInjectionOnly() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> walk = Files.walk(SERVICES_DIR)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!code.contains("ServiceLookup services")) {
                    continue; // 无状态工具/域对象，不持服务
                }
                checked++;
                // 只认"真的用了"：import 或 代码里出现 ServiceRegistry. / ServiceRegistry::
                // （javadoc 里写 {@code ServiceRegistry} 说明迁移背景是允许的）
                boolean importsFacade = code.contains(
                        "import com.mtxgdn.common.service.ServiceRegistry;");
                boolean usesFacade = code.matches("(?s).*ServiceRegistry[.:].*");
                if (importsFacade || usesFacade) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些服务没有做纯粹的构造注入（仍在引用静态门面）：\n"
                        + String.join("\n", offenders));
        assertTrue(checked >= 20,
                "只扫到 " + checked + " 个持查询面的服务，明显偏少，断言可能已空转");
    }

    @Test
    @DisplayName("生产代码不得再自行 new 有状态服务（会造出与容器不一致的第二份实例）")
    void noAdHocServiceInstantiation() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> serviceNames = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(SERVICES_DIR)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains("ServiceLookup services")) {
                    serviceNames.add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }
        String alternation = String.join("|", serviceNames);
        Pattern adHoc = Pattern.compile("new (?:com\\.mtxgdn\\.game\\.service\\.)?("
                + alternation + ")\\(\\)");
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = adHoc.matcher(code);
                while (m.find()) {
                    offenders.add(MAIN.relativize(file) + " -> " + m.group(0));
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "自行 new 服务会绕过容器，造出与其它通道不一致的第二份实例"
                        + "（有状态服务的表现为状态丢失 / 缓存不一致）：\n"
                        + String.join("\n", offenders));
    }

    // ==================== 夹具 ====================

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
