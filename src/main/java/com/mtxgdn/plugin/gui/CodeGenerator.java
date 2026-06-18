package com.mtxgdn.plugin.gui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 PluginConfig 转换成完整的插件项目文件。
 * <p>
 * 新版本按注册项模型生成：每个物品/事件/指令/秘境拥有自己的触发器列表，生成对应的 Java 类 + 注册代码。
 */
public final class CodeGenerator {

    private final PluginConfig config;
    private final Map<String, String> placeholders;
    private final List<String> generatedPaths;

    public CodeGenerator(PluginConfig config) {
        this.config = config;
        this.placeholders = buildPlaceholders(config);
        this.generatedPaths = new ArrayList<>();
    }

    /** 生成所有文件，返回生成的文件清单。 */
    public List<String> generateAll() throws IOException {
        File outDir = new File(config.getOutputDir());
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建输出目录: " + outDir.getAbsolutePath());
        }

        // ========== 基础文件 ==========
        generatedPaths.add(writeTemplated(outDir, "pom.xml", "pom.xml.template"));
        generatedPaths.add(writeTemplated(outDir, "plugin.json", "plugin.json.template"));
        generatedPaths.add(writeMainClass(outDir));

        // ========== 物品（每个物品一个文件，含其触发器） ==========
        for (RegistrableEntry item : config.getItems()) {
            if (!item.isEnabled() || item.getKey().isEmpty()) continue;
            generatedPaths.add(writeItemClass(outDir, item));
        }

        // ========== 事件（事件注册 + 触发器） ==========
        // 生成一个统一的 EventHandlers 类，把所有事件的触发器集中注册
        if (!config.getEvents().isEmpty()) {
            generatedPaths.add(writeEventsClass(outDir));
        }

        // ========== 指令（每个指令一个文件） ==========
        for (RegistrableEntry cmd : config.getCommands()) {
            if (!cmd.isEnabled() || cmd.getKey().isEmpty()) continue;
            generatedPaths.add(writeCommandClass(outDir, cmd));
        }

        // ========== 秘境（每个秘境一个文件） ==========
        for (RegistrableEntry realm : config.getSecretRealms()) {
            if (!realm.isEnabled() || realm.getKey().isEmpty()) continue;
            generatedPaths.add(writeRealmClass(outDir, realm));
        }

        // ========== README ==========
        generatedPaths.add(writeReadme(outDir));

        return generatedPaths;
    }

    // ==================== 内部辅助方法 ====================

    private Map<String, String> buildPlaceholders(PluginConfig cfg) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{NAME}}", cfg.getPluginName());
        m.put("{{VERSION}}", cfg.getVersion());
        m.put("{{AUTHOR}}", cfg.getAuthor());
        m.put("{{DESCRIPTION}}", cfg.getDescription());
        m.put("{{ARTIFACT_ID}}", cfg.getArtifactId());
        m.put("{{GROUP_ID}}", cfg.getGroupId());
        m.put("{{PACKAGE}}", cfg.getPackageName());
        m.put("{{PACKAGE_PATH}}", cfg.getPackagePath());
        m.put("{{MAIN_CLASS}}", cfg.getMainClass());
        m.put("{{SERVER_VERSION}}", "V1.4.1-alpha1");
        m.put("{{TOTAL_ENTRIES}}", String.valueOf(cfg.totalEntries()));
        m.put("{{TOTAL_TRIGGERS}}", String.valueOf(cfg.totalTriggers()));
        return m;
    }

    private String apply(String template) {
        String result = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }

    private String writeTemplated(File outDir, String relativePath, String templateName) throws IOException {
        String content = loadTemplate(templateName);
        content = apply(content);
        File target = new File(outDir, relativePath);
        ensureParent(target);
        Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    private static void ensureParent(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
    }

    // ==================== 主类 ====================

    private String writeMainClass(File outDir) throws IOException {
        String pkg = config.getPackageName();
        String mainClass = config.getMainClass();
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import com.mtxgdn.plugin.Plugin;\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n");
        sb.append("import com.mtxgdn.plugin.PluginInfo;\n");
        sb.append("import com.mtxgdn.plugin.PluginMeta;\n\n");

        sb.append("/**\n");
        sb.append(" * ").append(config.getPluginName()).append(" —— 由 PluginMaker 自动生成。\n");
        sb.append(" * 本插件注册了 ").append(config.totalEntries()).append(" 个注册项 (物品/事件/指令/秘境)，");
        sb.append("含 ").append(config.totalTriggers()).append(" 个触发器。\n");
        sb.append(" */\n");
        sb.append("public final class ").append(mainClass).append(" implements Plugin {\n\n");

        sb.append("    private PluginContext ctx;\n\n");

        sb.append("    @Override\n");
        sb.append("    public PluginInfo getInfo() {\n");
        sb.append("        PluginInfo info = new PluginInfo();\n");
        sb.append("        info.setName(\"").append(escape(config.getPluginName())).append("\");\n");
        sb.append("        info.setVersion(\"").append(escape(config.getVersion())).append("\");\n");
        sb.append("        info.setAuthor(\"").append(escape(config.getAuthor())).append("\");\n");
        sb.append("        info.setDescription(\"").append(escape(config.getDescription())).append("\");\n");
        sb.append("        return info;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public PluginMeta getMeta() {\n");
        sb.append("        PluginMeta meta = new PluginMeta();\n");
        sb.append("        meta.setMainClass(\"").append(escape(pkg)).append(".").append(escape(mainClass)).append("\");\n");
        sb.append("        return meta;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public void onEnable(PluginContext context) {\n");
        sb.append("        this.ctx = context;\n");
        sb.append("        context.getLogger().info(\"[").append(escape(config.getPluginName())).append("] 正在加载...\");\n\n");

        // 物品注册
        for (RegistrableEntry item : config.getItems()) {
            if (!item.isEnabled() || item.getKey().isEmpty()) continue;
            String className = itemClassName(item);
            sb.append("        new ").append(className).append("().register(context);\n");
        }

        // 事件处理器注册
        if (!config.getEvents().isEmpty()) {
            sb.append("        ").append(mainClass).append("Events.registerAll(context);\n");
        }

        // 指令注册
        for (RegistrableEntry cmd : config.getCommands()) {
            if (!cmd.isEnabled() || cmd.getKey().isEmpty()) continue;
            String className = commandClassName(cmd);
            sb.append("        new ").append(className).append("().register(context);\n");
        }

        // 秘境注册
        for (RegistrableEntry realm : config.getSecretRealms()) {
            if (!realm.isEnabled() || realm.getKey().isEmpty()) continue;
            String className = realmClassName(realm);
            sb.append("        new ").append(className).append("().register(context);\n");
        }

        sb.append("\n");
        sb.append("        context.getLogger().info(\"[").append(escape(config.getPluginName())).append("] 加载完成！\");\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public void onDisable(PluginContext context) {\n");
        sb.append("        context.getLogger().info(\"[").append(escape(config.getPluginName())).append("] 已卸载。\");\n");
        sb.append("    }\n");

        sb.append("}\n");

        File target = new File(outDir, "src/main/java/" + config.getPackagePath() + "/" + mainClass + ".java");
        ensureParent(target);
        Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    // ==================== 物品类 ====================

    private String writeItemClass(File outDir, RegistrableEntry item) throws IOException {
        String pkg = config.getPackageName();
        String className = itemClassName(item);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".item;\n\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n");
        sb.append("import com.mtxgdn.common.service.ServiceRegistry;\n\n");

        sb.append("/**\n");
        sb.append(" * 物品: ").append(item.getName()).append(" (key=").append(item.getKey()).append(")\n");
        if (item.getDescription() != null && !item.getDescription().isEmpty()) {
            sb.append(" * ").append(item.getDescription()).append("\n");
        }
        sb.append(" * 含 ").append(item.getTriggers().size()).append(" 个触发器 —— 由 PluginMaker 生成\n");
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");

        sb.append("    public static final String KEY = \"").append(escape(item.getKey())).append("\";\n");
        sb.append("    public static final String NAME = \"").append(escape(item.getName())).append("\";\n\n");

        sb.append("    public void register(PluginContext context) {\n");
        sb.append("        context.getLogger().info(\"[").append(escape(config.getPluginName())).append("] 注册物品: ").append(escape(item.getName())).append("\");\n");
        // 这里需要调用 ServiceRegistry 进行物品注册，实际服务端 API 名称以服务端为准
        sb.append("        // TODO: 根据服务端实际 API 注册物品。\n");
        sb.append("        // 例如: ServiceRegistry.getItemService().register(KEY, NAME, ...);\n\n");
        sb.append("        // —— 绑定该物品的触发器 ——\n");

        int idx = 0;
        for (Trigger t : item.getTriggers()) {
            if (!t.isEnabled()) continue;
            idx++;
            if (t.getTriggerWhen() == Trigger.When.ITEM_ON_USE) {
                sb.append("        // [").append(idx).append("] 玩家使用物品时 (").append(t.getDescription()).append(")\n");
                sb.append("        context.registerItemUseHandler(KEY, event -> {\n");
                appendTriggerAction(sb, t, 3);
                sb.append("        });\n\n");
            } else if (t.getTriggerWhen() == Trigger.When.ITEM_ON_OBTAIN) {
                sb.append("        // [").append(idx).append("] 玩家获得物品时 (").append(t.getDescription()).append(")\n");
                sb.append("        context.registerItemObtainHandler(KEY, event -> {\n");
                appendTriggerAction(sb, t, 3);
                sb.append("        });\n\n");
            }
        }

        if (idx == 0) {
            sb.append("        // (无启用的触发器)\n\n");
        }

        sb.append("    }\n");
        sb.append("}\n");

        File target = new File(outDir, "src/main/java/" + config.getPackagePath() + "/item/" + className + ".java");
        ensureParent(target);
        Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    // ==================== 事件类 ====================

    private String writeEventsClass(File outDir) throws IOException {
        String pkg = config.getPackageName();
        String className = config.getMainClass() + "Events";
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".event;\n\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n");
        sb.append("import com.mtxgdn.plugin.event.PluginEvent;\n");
        sb.append("import com.mtxgdn.common.service.ServiceRegistry;\n\n");

        sb.append("/**\n");
        sb.append(" * 事件触发器汇总 —— 由 PluginMaker 自动生成。\n");
        sb.append(" * 共 ").append(countEventTriggers()).append(" 个事件触发器。\n");
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");

        sb.append("    public static void registerAll(PluginContext context) {\n");

        int idx = 0;
        for (RegistrableEntry ev : config.getEvents()) {
            if (!ev.isEnabled() || ev.getKey().isEmpty()) continue;
            idx++;
            sb.append("        // ===== 事件: ").append(ev.getName()).append(" (key=").append(ev.getKey()).append(") =====\n");
            if (ev.getDescription() != null && !ev.getDescription().isEmpty()) {
                sb.append("        // ").append(ev.getDescription()).append("\n");
            }
            for (Trigger t : ev.getTriggers()) {
                if (!t.isEnabled()) continue;
                sb.append("        // - ").append(t.getTriggerWhen().label).append(" → ")
                        .append(t.getAction().label).append(" (").append(t.getDescription()).append(")\n");
                sb.append("        context.registerCustomHandler(\"").append(escape(ev.getKey())).append("\", event -> {\n");
                appendTriggerAction(sb, t, 3);
                sb.append("        });\n");
            }
            sb.append("\n");
        }

        if (idx == 0) {
            sb.append("        // (无启用的事件项)\n");
        }

        sb.append("    }\n");
        sb.append("}\n");

        File target = new File(outDir, "src/main/java/" + config.getPackagePath() + "/event/" + className + ".java");
        ensureParent(target);
        Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    private int countEventTriggers() {
        int c = 0;
        for (RegistrableEntry e : config.getEvents()) {
            for (Trigger t : e.getTriggers()) {
                if (t.isEnabled()) c++;
            }
        }
        return c;
    }

    // ==================== 指令类 ====================

    private String writeCommandClass(File outDir, RegistrableEntry cmd) throws IOException {
        String pkg = config.getPackageName();
        String className = commandClassName(cmd);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".command;\n\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n");
        sb.append("import com.mtxgdn.common.service.ServiceRegistry;\n\n");

        sb.append("/**\n");
        sb.append(" * 指令: ").append(cmd.getName()).append(" (").append(cmd.getKey()).append(")\n");
        if (cmd.getDescription() != null && !cmd.getDescription().isEmpty()) {
            sb.append(" * ").append(cmd.getDescription()).append("\n");
        }
        sb.append(" * 含 ").append(cmd.getTriggers().size()).append(" 个触发器 —— 由 PluginMaker 生成\n");
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");

        sb.append("    public static final String COMMAND = \"").append(escape(cmd.getKey())).append("\";\n\n");

        sb.append("    public void register(PluginContext context) {\n");
        sb.append("        context.getLogger().info(\"[").append(escape(config.getPluginName()))
                .append("] 注册指令: ").append(escape(cmd.getKey())).append("\");\n");

        int idx = 0;
        for (Trigger t : cmd.getTriggers()) {
            if (!t.isEnabled()) continue;
            idx++;
            sb.append("        // [").append(idx).append("] 玩家执行指令时 (").append(t.getDescription()).append(")\n");
            sb.append("        context.registerCommandHandler(COMMAND, event -> {\n");
            appendTriggerAction(sb, t, 3);
            sb.append("        });\n\n");
        }

        if (idx == 0) {
            sb.append("        // (无启用的触发器)\n\n");
        }

        sb.append("    }\n");
        sb.append("}\n");

        File target = new File(outDir, "src/main/java/" + config.getPackagePath() + "/command/" + className + ".java");
        ensureParent(target);
        Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    // ==================== 秘境类 ====================

    private String writeRealmClass(File outDir, RegistrableEntry realm) throws IOException {
        String pkg = config.getPackageName();
        String className = realmClassName(realm);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".realm;\n\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n");
        sb.append("import com.mtxgdn.common.service.ServiceRegistry;\n\n");

        sb.append("/**\n");
        sb.append(" * 秘境: ").append(realm.getName()).append(" (key=").append(realm.getKey()).append(")\n");
        if (realm.getDescription() != null && !realm.getDescription().isEmpty()) {
            sb.append(" * ").append(realm.getDescription()).append("\n");
        }
        if (realm.getExtraInfo() != null && !realm.getExtraInfo().isEmpty()) {
            sb.append(" * 配置信息: ").append(realm.getExtraInfo()).append("\n");
        }
        sb.append(" * 含 ").append(realm.getTriggers().size()).append(" 个触发器 —— 由 PluginMaker 生成\n");
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");

        sb.append("    public static final String KEY = \"").append(escape(realm.getKey())).append("\";\n");
        sb.append("    public static final String NAME = \"").append(escape(realm.getName())).append("\";\n\n");

        sb.append("    public void register(PluginContext context) {\n");
        sb.append("        context.getLogger().info(\"[").append(escape(config.getPluginName())).append("] 注册秘境: ").append(escape(realm.getName())).append("\");\n");
        sb.append("        // TODO: 根据服务端实际 API 注册秘境。\n\n");
        sb.append("        // —— 绑定该秘境的触发器 ——\n");

        int idx = 0;
        for (Trigger t : realm.getTriggers()) {
            if (!t.isEnabled()) continue;
            idx++;
            String handlerName;
            switch (t.getTriggerWhen()) {
                case REALM_ON_ENTER: handlerName = "context.registerRealmEnterHandler"; break;
                case REALM_ON_EXIT: handlerName = "context.registerRealmExitHandler"; break;
                case REALM_ON_ENCOUNTER: handlerName = "context.registerRealmEncounterHandler"; break;
                default: handlerName = "context.registerRealmEnterHandler";
            }
            sb.append("        // [").append(idx).append("] ").append(t.getTriggerWhen().label).append(" (").append(t.getDescription()).append(")\n");
            sb.append("        ").append(handlerName).append("(KEY, event -> {\n");
            appendTriggerAction(sb, t, 3);
            sb.append("        });\n\n");
        }

        if (idx == 0) {
            sb.append("        // (无启用的触发器)\n\n");
        }

        sb.append("    }\n");
        sb.append("}\n");

        File target = new File(outDir, "src/main/java/" + config.getPackagePath() + "/realm/" + className + ".java");
        ensureParent(target);
        Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    // ==================== README ====================

    private String writeReadme(File outDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(config.getPluginName()).append(" (v").append(config.getVersion()).append(")\n\n");
        if (config.getDescription() != null && !config.getDescription().isEmpty()) {
            sb.append(config.getDescription()).append("\n\n");
        }
        sb.append("> 本项目由 **PluginMaker (v1.4.1-alpha1)** 自动生成。\n\n");
        sb.append("## 📦 项目结构\n\n");
        sb.append("```\n");
        sb.append(config.getPackageName()).append("/\n");
        sb.append("├── ").append(config.getMainClass()).append(".java    # 主类 (Plugin 入口)\n");
        if (!config.getItems().isEmpty()) sb.append("├── item/                   # 物品类 (").append(config.getItems().size()).append(")\n");
        if (!config.getEvents().isEmpty()) sb.append("├── event/                  # 事件处理器 (").append(config.getEvents().size()).append(")\n");
        if (!config.getCommands().isEmpty()) sb.append("├── command/                # 指令类 (").append(config.getCommands().size()).append(")\n");
        if (!config.getSecretRealms().isEmpty()) sb.append("├── realm/                  # 秘境类 (").append(config.getSecretRealms().size()).append(")\n");
        sb.append("```\n\n");
        sb.append("## 🚀 构建与部署\n\n");
        sb.append("```bash\n");
        sb.append("mvn clean package\n");
        sb.append("# 将 target/").append(config.getArtifactId()).append("-").append(config.getVersion()).append(".jar 复制到服务端 ./plugins 目录\n");
        sb.append("```\n\n");
        sb.append("## ⚙ 触发器说明\n\n");
        sb.append("每个注册项（物品/事件/指令/秘境）可配置多个触发器：\n\n");
        sb.append("- **物品**: 玩家使用物品 / 玩家获得物品\n");
        sb.append("- **事件**: 自定义事件被触发\n");
        sb.append("- **指令**: 玩家执行指令\n");
        sb.append("- **秘境**: 进入 / 退出 / 遭遇事件\n\n");
        sb.append("每个触发器可执行以下动作之一：\n\n");
        sb.append("- 发送消息 (SEND_MESSAGE)\n");
        sb.append("- 给予灵石 (GIVE_SPIRIT_STONES)\n");
        sb.append("- 给予物品 (GIVE_ITEM)\n");
        sb.append("- 执行自定义 Java 代码 (RUN_JAVA)\n");
        sb.append("- 仅记录日志 (LOG_ONLY)\n\n");
        sb.append("**作者**: ").append(config.getAuthor()).append("\n");

        File target = new File(outDir, "README.md");
        Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    // ==================== 触发器动作生成 ====================

    /** 将一个触发器的动作生成为 Java 代码。indent: 相对缩进层级。 */
    private void appendTriggerAction(StringBuilder sb, Trigger t, int indent) {
        String prefix = repeat("    ", indent);
        sb.append(prefix).append("// 动作: ").append(t.getAction().label);
        if (t.getDescription() != null && !t.getDescription().isEmpty()) {
            sb.append(" —— ").append(t.getDescription());
        }
        sb.append("\n");
        switch (t.getAction()) {
            case SEND_MESSAGE:
                sb.append(prefix).append("String reply = \"").append(escape(t.getActionParam())).append("\";\n");
                sb.append(prefix).append("context.getLogger().info(\"[").append(escape(config.getPluginName())).append("] \" + reply);\n");
                sb.append(prefix).append("// 如需在聊天中广播，可使用: context.getChatService().sendGlobal(reply);\n");
                break;
            case GIVE_SPIRIT_STONES:
                long amount = parseLongSafe(t.getActionParam(), 100);
                sb.append(prefix).append("long stones = ").append(amount).append("L;\n");
                sb.append(prefix).append("Object pidObj = event.getData().get(\"playerId\");\n");
                sb.append(prefix).append("if (pidObj instanceof Number) {\n");
                sb.append(prefix).append("    long pid = ((Number) pidObj).longValue();\n");
                sb.append(prefix).append("    ServiceRegistry.getItemService().addSpiritStones(pid, stones);\n");
                sb.append(prefix).append("    context.getLogger().info(\"给予玩家 \" + pid + \" \" + stones + \" 灵石\");\n");
                sb.append(prefix).append("}\n");
                break;
            case GIVE_ITEM:
                sb.append(prefix).append("String targetItemKey = \"").append(escape(t.getActionParam())).append("\";\n");
                sb.append(prefix).append("Object pidObj = event.getData().get(\"playerId\");\n");
                sb.append(prefix).append("if (pidObj instanceof Number) {\n");
                sb.append(prefix).append("    long pid = ((Number) pidObj).longValue();\n");
                sb.append(prefix).append("    ServiceRegistry.getItemService().addItem(pid, targetItemKey, 1);\n");
                sb.append(prefix).append("    context.getLogger().info(\"给予玩家 \" + pid + \" 物品: \" + targetItemKey);\n");
                sb.append(prefix).append("}\n");
                break;
            case RUN_JAVA:
                sb.append(prefix).append("// —— 自定义 Java 代码（来自 GUI 配置）——\n");
                if (t.getJavaCode() != null && !t.getJavaCode().trim().isEmpty()) {
                    for (String line : t.getJavaCode().split("\n")) {
                        sb.append(prefix).append(line).append("\n");
                    }
                } else {
                    sb.append(prefix).append("// (未填写代码)\n");
                }
                break;
            case LOG_ONLY:
            default:
                sb.append(prefix).append("context.getLogger().info(\"事件触发: ").append(escape(t.getDescription())).append("\");\n");
        }
    }

    // ==================== 命名辅助 ====================

    private String itemClassName(RegistrableEntry item) {
        return "Item_" + sanitizeName(item.getKey());
    }
    private String commandClassName(RegistrableEntry cmd) {
        return "Cmd_" + sanitizeName(cmd.getKey());
    }
    private String realmClassName(RegistrableEntry realm) {
        return "Realm_" + sanitizeName(realm.getKey());
    }

    private static String sanitizeName(String key) {
        if (key == null || key.isEmpty()) return "Unnamed";
        StringBuilder sb = new StringBuilder();
        boolean upperNext = true;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (upperNext) { sb.append(Character.toUpperCase(c)); upperNext = false; }
                else sb.append(c);
            } else {
                upperNext = true;
            }
        }
        String r = sb.toString();
        return r.isEmpty() ? "Unnamed" : r;
    }

    // ==================== 杂项 ====================

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }

    private static long parseLongSafe(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) { return fallback; }
    }

    /** 从 classpath 中加载模板内容。 */
    private static String loadTemplate(String name) throws IOException {
        String full = "plugin-template/" + name;
        InputStream is = CodeGenerator.class.getClassLoader().getResourceAsStream(full);
        if (is == null) {
            throw new IOException("模板文件不存在: " + full);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Java 字符串字面量转义 —— 将用户输入安全地嵌入 Java 源码。 */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
