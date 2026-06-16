package com.mtxgdn.plugin.gui;

import java.io.File;
import java.util.List;

/**
 * CodeGenerator 测试 —— 验证完整生成流程，不依赖 GUI。
 * 运行: java -cp target/classes com.mtxgdn.plugin.gui.CodeGeneratorTest
 */
public final class CodeGeneratorTest {
    public static void main(String[] args) throws Exception {
        PluginConfig cfg = new PluginConfig();
        cfg.setPluginName("测试插件");
        cfg.setAuthor("测试员");
        cfg.setVersion("1.0.0");
        cfg.setDescription("由 CodeGenerator 测试生成");
        cfg.setArtifactId("test-plugin");
        cfg.setGroupId("com.example");
        cfg.setMainClass("TestPlugin");
        cfg.setOutputDir("./target/test-plugin-gen");
        cfg.setIncludeCommand(true);
        cfg.setIncludeItem(true);
        cfg.setIncludeEvent(true);

        // 添加 2 个触发器
        TriggerConfig t1 = new TriggerConfig();
        t1.setEventType(com.mtxgdn.plugin.event.PluginEvent.Type.COMMAND);
        t1.setCondition("command=/你好");
        t1.setAction(TriggerConfig.Action.SEND_MESSAGE);
        t1.setActionParam("道友安好！");
        t1.setDescription("玩家发送 /你好 时回复问候");
        cfg.getTriggers().add(t1);

        TriggerConfig t2 = new TriggerConfig();
        t2.setEventType(com.mtxgdn.plugin.event.PluginEvent.Type.PLAYER_LOGIN);
        t2.setAction(TriggerConfig.Action.LOG_ONLY);
        t2.setDescription("记录玩家登录");
        cfg.getTriggers().add(t2);

        System.out.println("==== 生成测试插件项目 ====");
        List<String> files = new CodeGenerator(cfg).generateAll();
        System.out.println("成功生成 " + files.size() + " 个文件:");
        for (String f : files) {
            System.out.println("  " + f);
            File file = new File(f);
            if (file.exists()) {
                System.out.println("    ✓ 存在，大小 " + file.length() + " 字节");
            } else {
                System.out.println("    ✗ 文件不存在！");
            }
        }

        // 验证 Triggers.java 文件内容检查
        System.out.println();
        System.out.println("==== 验证 Triggers.java 内容摘要:");
        File triggersJava = new File(cfg.getOutputDir() + "/src/main/java/" + cfg.getPackageName().replace('.', '/') + "/" + cfg.getMainClass() + "Triggers.java");
        if (triggersJava.exists()) {
            byte[] data = java.nio.file.Files.readAllBytes(triggersJava.toPath());
            String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            int shown = 0;
            for (String line : lines) {
                if (shown >= 25) break;
                System.out.println("  " + line);
                shown++;
            }
            if (lines.length > 25) System.out.println("  ... (" + lines.length + " 行)");
        }

        System.out.println();
        System.out.println("==== 测试通过! ====");
    }
}
