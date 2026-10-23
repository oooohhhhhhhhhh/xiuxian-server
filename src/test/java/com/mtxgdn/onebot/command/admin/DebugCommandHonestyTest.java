package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.i18n.GameLanguage;
import com.mtxgdn.i18n.I18nContext;
import com.mtxgdn.util.LangManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /debug test} 不得声称"已回滚"，因为那从来没发生过。
 *
 * <p><b>缺陷形态</b>：{@code DebugCommand.testCommand} 里是这样写的：</p>
 *
 * <pre>{@code
 * try (Connection conn = DatabaseManager.getConnection()) {
 *     conn.setAutoCommit(false);
 *     try { targetCmd.execute(debugCtx); } finally { conn.rollback(); }
 * }
 * }</pre>
 *
 * <p>但目标指令自己通过 {@code DatabaseManager.getConnection()} 拿连接，<b>根本不在这个事务里</b>
 * —— {@code rollback()} 什么也没回滚。而回复文案写着
 * 「注意: 测试操作不会修改真实数据（事务已回滚）」和「指令测试完成（事务已回滚）」。
 * 也就是说管理员以为在试跑，实际上加物品、改属性、发钱全部<b>永久生效</b>。</p>
 *
 * <p>真正的沙箱需要把连接一路透传到每个指令（几十个类、上百处 SQL），成本远高于收益。
 * 所以修复选择"诚实地直接执行 + 如实告知"，本测试守住这两点：
 * 代码里不再出现假事务，文案里不再出现"回滚"的承诺。</p>
 */
class DebugCommandHonestyTest {

    private static final String NOTE_KEY = "admin.note";
    private static final String DONE_KEY = "admin.command_test_complete_2";

    @Test
    @DisplayName("DebugCommand 源码里不得再出现假事务（setAutoCommit(false) / rollback）")
    void noFakeTransactionInSource() throws IOException {
        Path source = Paths.get("src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java");
        assertTrue(Files.exists(source), "找不到 DebugCommand.java: " + source.toAbsolutePath());
        String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));

        assertFalse(code.contains("setAutoCommit(false)"),
                "DebugCommand 里又出现了 setAutoCommit(false) —— 目标指令不在这个连接上执行，"
                        + "开事务只会让人误以为有沙箱");
        assertFalse(code.contains(".rollback()"),
                "DebugCommand 里又出现了 rollback() —— 它回滚不了别的连接上已经提交的写入");
    }

    /** 去掉行注释与块注释，避免注释里提到这些 API 就被误判。 */
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
    @DisplayName("中文文案必须如实说明副作用会真实生效")
    void chineseMessagesAreHonest() {
        String note = messageIn(GameLanguage.ZH_CN, NOTE_KEY);
        String done = messageIn(GameLanguage.ZH_CN, DONE_KEY);

        assertFalse(note.contains("不会修改") || note.contains("事务已回滚"),
                "提示语不得再声称不会修改数据，实际: " + note);
        assertTrue(note.contains("真实"), "提示语应明确说明会真实生效，实际: " + note);
        assertFalse(done.contains("事务已回滚"), "完成语不得再声称已回滚，实际: " + done);
    }

    @Test
    @DisplayName("英文文案同样不得声称已回滚")
    void englishMessagesAreHonest() {
        String note = messageIn(GameLanguage.EN_US, NOTE_KEY);
        String done = messageIn(GameLanguage.EN_US, DONE_KEY);

        assertFalse(note.toLowerCase().contains("rolled back") || note.contains("do not modify"),
                "英文提示语不得再声称不会修改数据，实际: " + note);
        assertFalse(done.toLowerCase().contains("rolled back"),
                "英文完成语不得再声称已回滚，实际: " + done);
    }

    private static String messageIn(GameLanguage lang, String key) {
        AtomicReference<String> value = new AtomicReference<>();
        I18nContext.runWith(lang, () -> value.set(LangManager.get(key, "")));
        String text = value.get();
        assertFalse(text == null || text.isBlank(), "缺少词条 " + key + "（" + lang.code() + "）");
        return text;
    }
}
