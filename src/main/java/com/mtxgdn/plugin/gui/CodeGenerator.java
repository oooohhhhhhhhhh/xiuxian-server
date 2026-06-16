package com.mtxgdn.plugin.gui;

import com.mtxgdn.plugin.PluginMaker;
import com.mtxgdn.plugin.event.PluginEvent;

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
 * 职责：读取 jar 中的模板文件、替换占位符、写入目标目录。
 */
public final class CodeGenerator {

    private final PluginConfig config;
    private final Map<String, String> placeholders;

    public CodeGenerator(PluginConfig config) {
        this.config = config;
        this.placeholders = buildPlaceholders(config);
    }

    /** 生成所有文件，返回生成的文件清单。 */
    public List<String> generateAll() throws IOException {
        List<String> files = new ArrayList<>();
        File outDir = new File(config.getOutputDir());
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建输出目录: " + outDir.getAbsolutePath());
        }

        // 基础文件：总是生成
        files.add(writeFile(outDir, "pom.xml", "pom.xml.template"));
        files.add(writeFile(outDir, "plugin.json", "plugin.json.template"));
        files.add(writeFile(outDir, "src/main/java/" + config.getPackagePath() + "/" + config.getMainClass() + ".java",
                "Main.java.template"));

        // 可选文件：根据功能开关
        if (config.isIncludeCommand()) {
            files.add(writeFile(outDir,
                    "src/main/java/" + config.getPackagePath() + "/command/HelloCommand.java",
                    "HelloCommand.java.template"));
        }
        if (config.isIncludeItem()) {
            files.add(writeFile(outDir,
                    "src/main/java/" + config.getPackagePath() + "/item/DemoItem.java",
                    "DemoItem.java.template"));
        }

        // 事件触发器：如果配置了触发器，生成触发器文件
        if (config.isIncludeEvent() || !config.getTriggers().isEmpty()) {
            files.add(writeTriggerFile(outDir));
        }

        // 秘境示例：如果开启，则生成简单示例
        if (config.isIncludeSecretRealm()) {
            files.add(writeSecretRealmFile(outDir));
        }

        return files;
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
        m.put("{{TRIGGER_COUNT}}", String.valueOf(cfg.getTriggers().size()));
        return m;
    }

    private String apply(String template) {
        String result = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }

    private String writeFile(File outDir, String relativePath, String templateName) throws IOException {
        String content = loadTemplate(templateName);
        content = apply(content);
        File target = new File(outDir, relativePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) throw new IOException("无法创建目录: " + parent);
        }
        Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    private String writeTriggerFile(File outDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(config.getPackageName()).append(";\n\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n");
        sb.append("import com.mtxgdn.plugin.event.PluginEvent;\n");
        sb.append("import com.mtxgdn.plugin.event.PluginEventManager;\n");
        sb.append("import com.mtxgdn.common.service.ServiceRegistry;\n\n");
        sb.append("/**\n");
        sb.append(" * 事件触发器注册 —— 由 PluginMaker 自动生成。\n");
        sb.append(" * 共 ").append(config.getTriggers().size()).append(" 条触发器配置。\n");
        sb.append(" */\n");
        sb.append("public final class ").append(config.getMainClass()).append("Triggers {\n\n");
        sb.append("    public static void registerAll(PluginContext context) {\n");

        int idx = 0;
        for (TriggerConfig t : config.getTriggers()) {
            idx++;
            if (!t.isEnabled()) {
                sb.append("        // (已禁用) ").append(t.getDescription()).append("\n");
                continue;
            }
            sb.append("        // ").append(idx).append(". ").append(t.getDescription()).append("\n");
            if (t.getEventType() == PluginEvent.Type.CUSTOM) {
                sb.append("        context.registerCustomHandler(\"").append(escape(t.getCustomKey())).append("\", ");
            } else {
                sb.append("        context.registerHandler(PluginEvent.Type.").append(t.getEventType().name()).append(", ");
            }
            sb.append("\"").append(escape(t.getCondition())).append("\", event -> {\n");
            appendAction(sb, t);
            sb.append("        });\n\n");
        }
        sb.append("    }\n\n");

        // 也提供一个静态便捷方法：触发事件（插件内调用）
        sb.append("    /** 便捷方法：从插件中触发一个事件。 */\n");
        sb.append("    public static void fireEvent(PluginEvent event) {\n");
        sb.append("        PluginEventManager.getInstance().fire(event);\n");
        sb.append("    }\n");

        sb.append("}\n");

        String fileName = "src/main/java/" + config.getPackagePath() + "/" + config.getMainClass() + "Triggers.java";
        File target = new File(outDir, fileName);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) if (!parent.mkdirs()) throw new IOException("无法创建目录: " + parent);
        Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    private void appendAction(StringBuilder sb, TriggerConfig t) {
        sb.append("            // 动作类型: ").append(t.getAction().label).append("\n");
        switch (t.getAction()) {
            case SEND_MESSAGE:
                sb.append("            String reply = \"").append(escape(t.getActionParam())).append("\";\n");
                sb.append("            context.getLogger().info(\"[").append(escape(t.getDescription())).append("] \" + reply);\n");
                sb.append("            // 如需在聊天中广播，可使用 context.getChatService().sendGlobal(reply);\n");
                break;
            case GIVE_SPIRIT_STONES:
                long amount = 100;
                try { amount = Long.parseLong(t.getActionParam().trim()); } catch (Exception ignored) {}
                sb.append("            long stones = ").append(amount).append("L;\n");
                sb.append("            Object pidObj = event.getData().get(\"playerId\");\n");
                sb.append("            if (pidObj instanceof Number) {\n");
                sb.append("                long pid = ((Number) pidObj).longValue();\n");
                sb.append("                ServiceRegistry.getItemService().addSpiritStones(pid, stones);\n");
                sb.append("                context.getLogger().info(\"给予玩家 \" + pid + \" \" + stones + \" 灵石\");\n");
                sb.append("            }\n");
                break;
            case GIVE_ITEM:
                sb.append("            String itemKey = \"").append(escape(t.getActionParam())).append("\";\n");
                sb.append("            Object pidObj = event.getData().get(\"playerId\");\n");
                sb.append("            if (pidObj instanceof Number) {\n");
                sb.append("                long pid = ((Number) pidObj).longValue();\n");
                sb.append("                // ServiceRegistry.getItemService().addItem(pid, itemKey, 1);\n");
                sb.append("                context.getLogger().info(\"尝试给予物品: \" + itemKey);\n");
                sb.append("            }\n");
                break;
            case RUN_JAVA:
                sb.append("            // —— 自定义 Java 代码 ——\n");
                for (String line : t.getJavaCode().split("\n")) {
                    sb.append("            ").append(line).append("\n");
                }
                break;
            case LOG_ONLY:
            default:
                sb.append("            context.getLogger().info(\"事件触发: ").append(escape(t.getDescription())).append("\");\n");
        }
    }

    private String writeSecretRealmFile(File outDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(config.getPackageName()).append(";\n\n");
        sb.append("import com.mtxgdn.game.secretrealm.SecretRealm;\n");
        sb.append("import com.mtxgdn.game.secretrealm.SecretRealmRegistry;\n\n");
        sb.append("/**\n");
        sb.append(" * 示例秘境 —— 由 PluginMaker 自动生成。\n");
        sb.append(" * 请在此类中实现具体的秘境行为逻辑。\n");
        sb.append(" */\n");
        sb.append("public class ").append(config.getMainClass()).append("Realm extends SecretRealm {\n\n");
        sb.append("    public ").append(config.getMainClass()).append("Realm() {\n");
        sb.append("        super(\"").append(escape(config.getPluginName())).append("\", \"example_realm\", 1);\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public String getName() { return \"示例秘境\"; }\n\n");
        sb.append("    @Override\n");
        sb.append("    public String getDescription() { return \"由 PluginMaker 自动生成的示例秘境\" + \"\\n\" + super.getDescription(); }\n\n");
        sb.append("    @Override\n");
        sb.append("    public long getMinLevel() { return 1; }\n\n");
        sb.append("    @Override\n");
        sb.append("    public long getStaminaCost() { return 10; }\n");
        sb.append("}\n");
        String fileName = "src/main/java/" + config.getPackagePath() + "/" + config.getMainClass() + "Realm.java";
        File target = new File(outDir, fileName);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) if (!parent.mkdirs()) throw new IOException("无法创建目录: " + parent);
        Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    /** 从 classpath 中加载模板内容。 */
    private static String loadTemplate(String name) throws IOException {
        String full = "plugin-template/" + name;
        InputStream is = PluginMaker.class.getClassLoader().getResourceAsStream(full);
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

    /** Java 字符串字面量转义 —— 将用户输入安全地嵌入到 Java 源代码中。 */
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
