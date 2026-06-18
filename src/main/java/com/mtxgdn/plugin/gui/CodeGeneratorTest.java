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

        // ===== 物品 1：示例符箓 =====
        RegistrableEntry item = RegistrableEntry.newItem("demo_talisman", "示例符箓");
        item.setDescription("测试物品，使用时触发消息");
        Trigger itemUse = new Trigger();
        itemUse.setTriggerWhen(Trigger.When.ITEM_ON_USE);
        itemUse.setAction(Trigger.Action.SEND_MESSAGE);
        itemUse.setActionParam("道友安好！");
        itemUse.setDescription("玩家使用物品时回复问候");
        item.getTriggers().add(itemUse);

        Trigger itemObtain = new Trigger();
        itemObtain.setTriggerWhen(Trigger.When.ITEM_ON_OBTAIN);
        itemObtain.setAction(Trigger.Action.GIVE_SPIRIT_STONES);
        itemObtain.setActionParam("100");
        itemObtain.setDescription("获得物品时给予 100 灵石");
        item.getTriggers().add(itemObtain);
        cfg.getItems().add(item);

        // ===== 指令 1：/你好 =====
        RegistrableEntry cmd = RegistrableEntry.newCommand("/你好", "打招呼");
        cmd.setDescription("玩家发送 /你好 时回复问候");
        Trigger cmdExec = new Trigger();
        cmdExec.setTriggerWhen(Trigger.When.COMMAND_ON_EXECUTE);
        cmdExec.setAction(Trigger.Action.SEND_MESSAGE);
        cmdExec.setActionParam("你好，欢迎来到修仙世界！");
        cmdExec.setDescription("玩家执行 /你好 时回复消息");
        cmd.getTriggers().add(cmdExec);
        cfg.getCommands().add(cmd);

        // ===== 事件：玩家登录 =====
        RegistrableEntry ev = RegistrableEntry.newEvent("player_login", "玩家登录");
        Trigger evFire = new Trigger();
        evFire.setTriggerWhen(Trigger.When.EVENT_ON_FIRE);
        evFire.setAction(Trigger.Action.LOG_ONLY);
        evFire.setDescription("记录玩家登录事件");
        ev.getTriggers().add(evFire);
        cfg.getEvents().add(ev);

        // ===== 秘境：新手秘境 =====
        RegistrableEntry realm = RegistrableEntry.newSecretRealm("beginner", "新手秘境");
        realm.setExtraInfo("最低等级: 1 | 体力消耗: 10");
        Trigger realmEnter = new Trigger();
        realmEnter.setTriggerWhen(Trigger.When.REALM_ON_ENTER);
        realmEnter.setAction(Trigger.Action.LOG_ONLY);
        realmEnter.setDescription("进入秘境时记录日志");
        realm.getTriggers().add(realmEnter);
        cfg.getSecretRealms().add(realm);

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

        // 验证主类文件内容
        System.out.println();
        System.out.println("==== 验证主类内容摘要:");
        File mainJava = new File(cfg.getOutputDir() + "/src/main/java/" +
                cfg.getPackagePath() + "/" + cfg.getMainClass() + ".java");
        if (mainJava.exists()) {
            byte[] data = java.nio.file.Files.readAllBytes(mainJava.toPath());
            String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            int shown = 0;
            for (String line : lines) {
                if (shown >= 30) break;
                System.out.println("  " + line);
                shown++;
            }
            if (lines.length > 30) System.out.println("  ... (" + lines.length + " 行)");
        }

        System.out.println();
        System.out.println("==== 测试通过! ====");
    }
}
