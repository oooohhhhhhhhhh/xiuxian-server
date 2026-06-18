package com.mtxgdn.plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 独立测试程序 —— 直接验证模板加载和替换，不依赖 Grizzly/WebSocket。
 *
 * 运行方式:
 *   java -cp target/classes com.mtxgdn.plugin.PluginMakerSelfTest
 */
public final class PluginMakerSelfTest {

    private static final String TEMPLATE_DIR = "plugin-template/";
    private static final String[] TEMPLATES = {
        "pom.xml.template",
        "plugin.json.template",
        "Main.java.template",
        "HelloCommand.java.template",
        "DemoItem.java.template",
    };

    public static void main(String[] args) throws Exception {
        System.out.println("==== PluginMaker 自测试 ====");

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("{{NAME}}",          "测试插件");
        placeholders.put("{{VERSION}}",       "1.0.0");
        placeholders.put("{{AUTHOR}}",        "单元测试");
        placeholders.put("{{DESCRIPTION}}",   "一个自动测试");
        placeholders.put("{{ARTIFACT_ID}}",   "test-plugin");
        placeholders.put("{{GROUP_ID}}",      "com.example");
        placeholders.put("{{PACKAGE}}",       "com.example.testplugin");
        placeholders.put("{{PACKAGE_PATH}}",  "com/example/testplugin");
        placeholders.put("{{MAIN_CLASS}}",    "TestPlugin");
        placeholders.put("{{SERVER_VERSION}}","V1.4.1-alpha1");

        int pass = 0;
        int fail = 0;

        for (String t : TEMPLATES) {
            try (InputStream is = PluginMakerSelfTest.class.getClassLoader()
                    .getResourceAsStream(TEMPLATE_DIR + t)) {
                if (is == null) {
                    System.out.println("  [FAIL] " + t + " —— 模板文件不存在");
                    fail++;
                    continue;
                }

                // 读取内容
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
                String content = sb.toString();

                // 替换占位符
                for (Map.Entry<String, String> e : placeholders.entrySet()) {
                    content = content.replace(e.getKey(), e.getValue());
                }

                // 检查替换后是否还有残留占位符
                boolean hasUnreplaced = content.contains("{{");

                if (hasUnreplaced) {
                    System.out.println("  [WARN] " + t + " —— 可能仍有未替换的 {{ 占位符");
                }

                // 检查 Java 文件是否包含正确的 package 声明
                if (t.endsWith("java.template") && !content.contains("package com.example")) {
                    System.out.println("  [FAIL] " + t + " —— package 声明未替换");
                    fail++;
                    continue;
                }

                System.out.println("  [ OK ] " + t + " (" + content.length() + " bytes)");
                pass++;
            }
        }

        System.out.println();
        System.out.println("==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
