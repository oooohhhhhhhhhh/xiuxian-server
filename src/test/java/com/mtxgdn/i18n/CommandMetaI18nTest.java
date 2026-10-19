package com.mtxgdn.i18n;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指令元数据（description / usage）的本地化回归测试。
 *
 * <p>这两项过去只是 {@code super(...)} 的第二、第三参数字面量，是 i18n 的盲区：
 * 它们经 {@code ctx.reply(getUsage())} 直达玩家、也出现在 /help 列表和
 * WebSocket 的命令清单里，却从来没进过语言包。</p>
 *
 * <p>为什么不直接用 {@code CommandRegistry.getAllUnique()} 遍历真实命令：
 * 命令是在 {@code Main} 里 new 出来的，构造函数里才 register，
 * 而测试 JVM 从不跑 {@code Main}，所以注册表在测试里是空的。
 * 于是这里分两条路走——解析机制用桩命令直接测，
 * "词条是否齐全"用源码扫描守。</p>
 */
class CommandMetaI18nTest {

    /** 只用来验证解析机制的桩命令；不关心 execute 做什么。 */
    private static final class StubCommand extends Command {
        StubCommand(String[] names, String desc, String usage) {
            super(names, desc, usage, "测试");
        }

        StubCommand(String[] names, String desc, String usage, String descKey, String usageKey) {
            super(names, desc, usage, "测试");
            setI18nKeys(descKey, usageKey);
        }

        @Override
        public void execute(CommandContext ctx) {
            // 桩命令不执行任何逻辑
        }
    }

    @BeforeEach
    void setUp() {
        I18nManager.load();
        I18nContext.clear();
        // 用桩词条验证解析链路，不依赖 zh_cn 里恰好有什么
        I18nManager.mergeFromStream(GameLanguage.ZH_CN,
                new ByteArrayInputStream(
                        "{\"test.cmd.desc\":\"桩描述\",\"test.cmd.usage\":\"/桩\"}"
                                .getBytes(StandardCharsets.UTF_8)));
    }

    @AfterEach
    void tearDown() {
        I18nContext.clear();
    }

    @Test
    @DisplayName("设了 key 就按语言包解析")
    void resolvesThroughPack() {
        StubCommand cmd = new StubCommand(new String[]{"stub"}, "中文兜底", "/兜底",
                "test.cmd.desc", "test.cmd.usage");
        assertEquals("桩描述", cmd.getDescription());
        assertEquals("/桩", cmd.getUsage());
    }

    @Test
    @DisplayName("没设 key 时原样返回中文字面量")
    void fallsBackToLiteralWhenNoKey() {
        StubCommand cmd = new StubCommand(new String[]{"stub2"}, "中文兜底", "/兜底");
        assertNull(cmd.getDescriptionKey(), "没调 setI18nKeys 时 key 应为 null");
        assertEquals("中文兜底", cmd.getDescription());
        assertEquals("/兜底", cmd.getUsage());
    }

    @Test
    @DisplayName("key 在语言包里不存在时回落到中文字面量，绝不露出裸 key")
    void fallsBackToLiteralWhenKeyMissing() {
        StubCommand cmd = new StubCommand(new String[]{"stub3"}, "中文兜底", "/兜底",
                "test.cmd.absent_desc", "test.cmd.absent_usage");
        // 本类最关键的断言：I18n.tOr 的语义就是"查不到就给我兜底"，
        // 玩家永远不该看到 cmd.xxx.desc 这种东西。
        assertEquals("中文兜底", cmd.getDescription());
        assertEquals("/兜底", cmd.getUsage());
    }

    @Test
    @DisplayName("命令名不参与本地化")
    void commandNamesAreNotTranslated() {
        // names 是"命令匹配"而不是"消息文案"。翻译它会让玩家不知道该打什么，
        // 中英文别名本来就该并存于同一个数组（如 {"阵法", "formation"}）。
        StubCommand cmd = new StubCommand(new String[]{"阵法", "formation"}, "描述", "/用法",
                "test.cmd.desc", "test.cmd.usage");
        assertArrayEquals(new String[]{"阵法", "formation"}, cmd.getNames());
    }

    @Test
    @DisplayName("源码里每处 setI18nKeys 的 key 都在 zh_cn 有词条")
    void everyInjectedKeyHasEntry() throws IOException {
        Path srcDir = Paths.get("src/main/java");
        // surefire 的 cwd 通常是模块根目录；换个环境跑就跳过，不误报
        Assumptions.assumeTrue(Files.isDirectory(srcDir), "找不到 src/main/java，跳过源码扫描");

        // 抓 setI18nKeys("a", "b") —— 允许参数跨行
        Pattern p = Pattern.compile("setI18nKeys\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*\\)");
        List<String> keys = new ArrayList<>();
        Map<String, String> where = new LinkedHashMap<>();

        try (Stream<Path> walk = Files.walk(srcDir)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = p.matcher(text);
                while (m.find()) {
                    keys.add(m.group(1));
                    keys.add(m.group(2));
                    where.put(m.group(1), file.getFileName().toString());
                    where.put(m.group(2), file.getFileName().toString());
                }
            }
        }

        assertFalse(keys.isEmpty(), "源码里一处 setI18nKeys 都没找到，工具是不是没跑？");

        Map<String, String> zh = I18nManager.bundle(GameLanguage.ZH_CN);
        StringBuilder bad = new StringBuilder();
        for (String key : keys) {
            if (!zh.containsKey(key)) {
                bad.append("\n  ").append(key).append("  ← ").append(where.get(key));
            }
        }
        assertEquals("", bad.toString(),
                "以下 key 在 zh_cn 里没有词条（加了 key 等于没加）:" + bad);
    }
}
