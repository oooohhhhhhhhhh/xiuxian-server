package com.mtxgdn.plugin.gui;

import java.io.File;
import java.util.List;

/**
 * CodeGenerator 冒烟测试 —— 不依赖 GUI，直接跑完整生成流程。
 *
 * <p>运行：{@code java -cp target/classes com.mtxgdn.plugin.gui.CodeGeneratorTest}</p>
 *
 * <p>生成到 {@code ./target/test-plugin-gen}，覆盖物品/探索事件/指令/秘境四类注册项
 * 与全部五种触发器动作，用于人工核对产物是否与真实 API 一致。</p>
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

        // PluginConfig 的构造函数带一个示例物品/指令，这里先清空，避免生成重复 key
        cfg.getItems().clear();
        cfg.getCommands().clear();
        cfg.getEvents().clear();
        cfg.getSecretRealms().clear();

        // ===== 物品：示例符箓（覆盖 发送消息 / 给予灵石 / 给予物品 / 执行代码 / 仅记录日志） =====
        RegistrableEntry item = RegistrableEntry.newItem("demo_talisman", "示例符箓");
        item.setDescription("测试物品，使用时触发多种动作");
        item.setExtraInfo("类型: 消耗品 | 稀有度: 珍品 | 堆叠: 10 | 价格: 888 | 可交易: 是 | 境界: 1");

        Trigger itemMsg = new Trigger();
        itemMsg.setTriggerWhen(Trigger.When.ITEM_ON_USE);
        itemMsg.setAction(Trigger.Action.SEND_MESSAGE);
        itemMsg.setActionParam("道友安好！");
        itemMsg.setDescription("玩家使用物品时回复问候");
        item.getTriggers().add(itemMsg);

        Trigger itemStones = new Trigger();
        itemStones.setTriggerWhen(Trigger.When.ITEM_ON_USE);
        itemStones.setAction(Trigger.Action.GIVE_SPIRIT_STONES);
        itemStones.setActionParam("100");
        itemStones.setDescription("使用后获得 100 灵石");
        item.getTriggers().add(itemStones);

        Trigger itemGift = new Trigger();
        itemGift.setTriggerWhen(Trigger.When.ITEM_ON_USE);
        itemGift.setAction(Trigger.Action.GIVE_ITEM);
        itemGift.setActionParam("healing_pill");
        itemGift.setDescription("使用后额外获得回春丹");
        item.getTriggers().add(itemGift);

        Trigger itemCode = new Trigger();
        itemCode.setTriggerWhen(Trigger.When.ITEM_ON_USE);
        itemCode.setAction(Trigger.Action.RUN_JAVA);
        itemCode.setJavaCode("msg.append(\"物品被玩家 \" + playerId + \" 使用了，当前等级物品数=\" + itemService.getInventory(playerId).size());");
        itemCode.setDescription("自定义代码：统计背包");
        item.getTriggers().add(itemCode);

        Trigger itemLog = new Trigger();
        itemLog.setTriggerWhen(Trigger.When.ITEM_ON_USE);
        itemLog.setAction(Trigger.Action.LOG_ONLY);
        itemLog.setDescription("使用后仅记录日志");
        item.getTriggers().add(itemLog);
        cfg.getItems().add(item);

        // ===== 指令：/你好（覆盖 发送消息 / 给予灵石 / 执行代码） =====
        RegistrableEntry cmd = RegistrableEntry.newCommand("/你好", "打招呼");
        cmd.setDescription("玩家发送 /你好 时回复问候");
        Trigger cmdExec = new Trigger();
        cmdExec.setTriggerWhen(Trigger.When.COMMAND_ON_EXECUTE);
        cmdExec.setAction(Trigger.Action.SEND_MESSAGE);
        cmdExec.setActionParam("你好，欢迎来到修仙世界！");
        cmdExec.setDescription("玩家执行 /你好 时回复消息");
        cmd.getTriggers().add(cmdExec);

        Trigger cmdStones = new Trigger();
        cmdStones.setTriggerWhen(Trigger.When.COMMAND_ON_EXECUTE);
        cmdStones.setAction(Trigger.Action.GIVE_SPIRIT_STONES);
        cmdStones.setActionParam("50");
        cmdStones.setDescription("执行指令赠送 50 灵石");
        cmd.getTriggers().add(cmdStones);

        Trigger cmdCode = new Trigger();
        cmdCode.setTriggerWhen(Trigger.When.COMMAND_ON_EXECUTE);
        cmdCode.setAction(Trigger.Action.RUN_JAVA);
        cmdCode.setJavaCode("ctx.reply(\"你的角色名是 \" + p.getName() + \"（uid=\" + userId + \"）\");");
        cmdCode.setDescription("自定义代码：回显角色信息");
        cmd.getTriggers().add(cmdCode);
        cfg.getCommands().add(cmd);

        // ===== 探索事件：幸运泉（覆盖 发送消息 / 给予物品 / 执行代码） =====
        RegistrableEntry ev = RegistrableEntry.newEvent("lucky_spring", "幸运灵泉");
        ev.setDescription("游历时偶遇灵泉");
        ev.setExtraInfo("权重: 8");
        Trigger evMsg = new Trigger();
        evMsg.setTriggerWhen(Trigger.When.EVENT_ON_FIRE);
        evMsg.setAction(Trigger.Action.SEND_MESSAGE);
        evMsg.setActionParam("你掬起一捧灵泉，浑身舒泰。");
        evMsg.setDescription("事件触发时写入游历日志");
        ev.getTriggers().add(evMsg);

        Trigger evGift = new Trigger();
        evGift.setTriggerWhen(Trigger.When.EVENT_ON_FIRE);
        evGift.setAction(Trigger.Action.GIVE_ITEM);
        evGift.setActionParam("spirit_grass");
        evGift.setDescription("额外获得灵草");
        ev.getTriggers().add(evGift);

        Trigger evCode = new Trigger();
        evCode.setTriggerWhen(Trigger.When.EVENT_ON_FIRE);
        evCode.setAction(Trigger.Action.RUN_JAVA);
        evCode.setJavaCode("long exp = (player.getRealm() + 1) * 20L;\nplayerService.addExperience(playerId, exp);\nresult.setExpGained(exp);\nlog.add(\"获得 \" + exp + \" 点经验。\");");
        evCode.setDescription("自定义代码：按境界给经验");
        ev.getTriggers().add(evCode);
        cfg.getEvents().add(ev);

        // ===== 秘境：新手秘境（不支持触发器，只做真实注册） =====
        RegistrableEntry realm = RegistrableEntry.newSecretRealm("beginner", "新手秘境");
        realm.setDescription("新手入门秘境");
        realm.setExtraInfo("境界: 1 | 冷却: 3分钟 | 团队: 否");
        cfg.getSecretRealms().add(realm);

        System.out.println("==== 生成测试插件项目 ====");
        List<String> files = new CodeGenerator(cfg).generateAll();
        System.out.println("成功生成 " + files.size() + " 个文件:");
        for (String f : files) {
            File file = new File(f);
            System.out.println("  " + f + (file.exists() ? "  ✓ " + file.length() + " 字节" : "  ✗ 不存在"));
        }

        System.out.println();
        System.out.println("==== 验证主类内容摘要 ====");
        File mainJava = new File(cfg.getOutputDir() + "/src/main/java/"
                + cfg.getPackagePath() + "/" + cfg.getMainClass() + ".java");
        if (mainJava.exists()) {
            String content = new String(java.nio.file.Files.readAllBytes(mainJava.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
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
