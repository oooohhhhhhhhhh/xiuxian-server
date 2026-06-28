package com.mtxgdn.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件生成器 —— 根据内置模板自动生成一个可编译的插件项目骨架。
 *
 * <p>使用方式（无需启动服务端）：
 * <pre>
 *   java -jar 服务端.jar --plugin-make
 * </pre>
 *
 * <p>运行后会进入交互式向导，依次询问：插件名 / 版本 / 作者 / 描述 / 包名 / 主类名 / 输出目录。
 * 生成完成后将得到一个可直接用 {@code mvn package} 打包的插件项目目录。
 */
public final class PluginMaker {

    private static final String TEMPLATE_DIR = "plugin-template/";

    /** 模板文件清单 —— "模板资源相对路径" -> "输出到项目目录下的相对路径" */
    private static final String[][] TEMPLATES = {
        {"pom.xml.template",              "pom.xml"},
        {"plugin.json.template",          "plugin.json"},
        {"Main.java.template",            "src/main/java/{{PACKAGE_PATH}}/{{MAIN_CLASS}}.java"},
        {"HelloCommand.java.template",    "src/main/java/{{PACKAGE_PATH}}/command/HelloCommand.java"},
        {"DemoItem.java.template",        "src/main/java/{{PACKAGE_PATH}}/item/DemoItem.java"},
    };

    /** 服务端版本号 —— 用于在模板 pom.xml 的注释中提示依赖版本 */
    private static final String SERVER_VERSION = "V1.4.1-beta1";

    private final BufferedReader in;
    private final PrintStream out;

    public PluginMaker() {
        this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.out = System.out;
    }

    /** 入口：运行交互式向导 */
    public void run() throws IOException {
        printBanner();

        out.println("╭─────────────────────────────────────────────────────╮");
        out.println("│  插件项目生成器 —— 按模板创建一个可运行插件       │");
        out.println("│  （方括号内为默认值，直接回车即可使用默认）       │");
        out.println("╰─────────────────────────────────────────────────────╯");
        out.println();

        // 1. 收集参数
        String name        = ask(" ① 插件名称",         "我的插件");
        String version     = ask(" ② 版本号",            "1.0.0");
        String author      = ask(" ③ 作者",              "匿名");
        String description = ask(" ④ 一句话描述",        "由 PluginMaker 生成的示例插件");
        String artifact    = ask(" ⑤ Maven artifactId", toArtifactId(name));
        String groupId     = ask(" ⑥ Maven groupId",    "com.example");
        String mainClass   = ask(" ⑦ 主类名",           "MyPlugin");
        String outputDir   = ask(" ⑧ 输出目录",         "./" + artifact);

        // 2. 合成包名 & 路径
        String pkg = groupId + (artifact.isEmpty() ? "" : "." + artifact);
        String packagePath = pkg.replace('.', '/');

        // 3. 构建占位符映射
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("{{NAME}}",          name);
        placeholders.put("{{VERSION}}",       version);
        placeholders.put("{{AUTHOR}}",        author);
        placeholders.put("{{DESCRIPTION}}",   description);
        placeholders.put("{{ARTIFACT_ID}}",   artifact);
        placeholders.put("{{GROUP_ID}}",      groupId);
        placeholders.put("{{PACKAGE}}",       pkg);
        placeholders.put("{{PACKAGE_PATH}}",  packagePath);
        placeholders.put("{{MAIN_CLASS}}",    mainClass);
        placeholders.put("{{SERVER_VERSION}}", SERVER_VERSION);

        // 4. 输出摘要，供用户确认
        out.println();
        out.println("────────────── 项目摘要 ──────────────");
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out.printf("  %-20s => %s%n", e.getKey(), e.getValue());
        }
        out.println("──────────────────────────────────────");
        String confirm = ask("开始生成？(y/N)", "y");
        if (!confirm.equalsIgnoreCase("y") && !confirm.equalsIgnoreCase("yes")) {
            out.println("已取消。");
            return;
        }

        // 5. 生成文件
        File outDir = new File(outputDir);
        if (outDir.exists()) {
            String overwrite = ask("目录已存在，是否覆盖？(y/N)", "n");
            if (!overwrite.equalsIgnoreCase("y") && !overwrite.equalsIgnoreCase("yes")) {
                out.println("已取消。");
                return;
            }
        }

        int ok = 0;
        for (String[] pair : TEMPLATES) {
            String templateFile = pair[0];
            String relativeOut  = applyPlaceholders(pair[1], placeholders);
            File   targetFile   = new File(outDir, relativeOut);

            try {
                String content = loadTemplate(templateFile);
                content = applyPlaceholders(content, placeholders);
                writeFile(targetFile, content);
                out.println("  ✓ 已生成 " + targetFile.getPath());
                ok++;
            } catch (Exception e) {
                out.println("  ✗ 失败 " + templateFile + " — " + e.getMessage());
            }
        }

        // 6. 结束提示
        out.println();
        out.println("══════════════════════════════════════════════");
        out.println(" 完成！共生成 " + ok + " 个文件。");
        out.println(" 输出目录: " + outDir.getAbsolutePath());
        out.println();
        out.println(" 下一步:");
        out.println("   1. cd " + outDir.getPath());
        out.println("   2. 将服务端 jar 安装到本地 Maven 仓库（见生成的 pom.xml 内注释）");
        out.println("   3. 运行: mvn package");
        out.println("   4. 将 target/" + artifact + "-" + version + ".jar 复制到服务端 ./plugins/ 目录");
        out.println("   5. 重启服务端即可生效 ✨");
        out.println("══════════════════════════════════════════════");
    }

    // ============================================================
    // 以下为工具方法
    // ============================================================

    private void printBanner() {
        out.println();
        out.println("╔═════════════════════════════════════════════════╗");
        out.println("║              PluginMaker  " + pad(SERVER_VERSION, 12) + "               ║");
        out.println("║        修仙服务端插件项目生成器                    ║");
        out.println("╚═════════════════════════════════════════════════╝");
        out.println();
    }

    private static String pad(String s, int len) {
        if (s.length() >= len) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    /** 向用户提问，返回输入（或默认值） */
    private String ask(String prompt, String defaultValue) {
        String display = defaultValue == null || defaultValue.isEmpty()
            ? prompt + " : "
            : prompt + " [" + defaultValue + "] : ";
        out.print(display);
        out.flush();
        try {
            String line = in.readLine();
            if (line == null) return defaultValue;
            line = line.trim();
            return line.isEmpty() ? defaultValue : line;
        } catch (IOException e) {
            return defaultValue;
        }
    }

    /** 把插件名转换为合法的 Maven artifactId */
    private static String toArtifactId(String name) {
        if (name == null || name.isEmpty()) return "my-plugin";
        return name.toLowerCase()
            .replaceAll("[^a-z0-9\\-]+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    }

    /** 从 classpath 读取模板内容 */
    private static String loadTemplate(String name) throws IOException {
        InputStream is = PluginMaker.class.getClassLoader().getResourceAsStream(TEMPLATE_DIR + name);
        if (is == null) {
            throw new IOException("在 jar 中找不到模板文件: " + TEMPLATE_DIR + name);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /** 在模板字符串中替换所有 {{占位符}} */
    private static String applyPlaceholders(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            result = result.replace(e.getKey(), v);
        }
        return result;
    }

    /** 把内容写入目标文件（自动创建父目录） */
    private static void writeFile(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("无法创建目录: " + parent.getAbsolutePath());
            }
        }
        try (OutputStream os = Files.newOutputStream(target.toPath())) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
