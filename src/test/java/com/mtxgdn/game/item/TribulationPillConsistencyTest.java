package com.mtxgdn.game.item;

import com.mtxgdn.game.service.RealmService;
import com.mtxgdn.i18n.GameLanguage;
import com.mtxgdn.i18n.I18nContext;
import com.mtxgdn.util.LangManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渡劫丹的加成数字只能有一个口径。
 *
 * <p><b>缺陷形态</b>：同一个数字散在四处且互不一致 ——</p>
 * <ul>
 *   <li>{@code RealmService} 实现里硬编码 {@code 10.0}（真正生效的值）</li>
 *   <li>{@code TribulationPillEffect} 的提示文案：+10%</li>
 *   <li>商店描述 {@code EconomyService}：+10%</li>
 *   <li>语言包 {@code item.tribulation_pill.desc}：<b>+20%</b></li>
 *   <li>另有死代码 {@code RealmBreakEffect}：+20%（从未被任何地方调用）</li>
 * </ul>
 *
 * <p>玩家在背包里看到 +20%，实际拿到 +10% —— 属于直接对玩家撒谎，
 * 而且这种漂移不会报错、不会被测试发现，只能靠人眼比对。</p>
 *
 * <p>修复：实现、提示文案、物品描述统一引用
 * {@link RealmService#TRIBULATION_PILL_BONUS}，死代码 {@code RealmBreakEffect} 删除。
 * 本测试把"语言包里的百分比 == 常量"钉死。</p>
 */
class TribulationPillConsistencyTest {

    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");

    @Test
    @DisplayName("中文物品描述里的百分比必须等于实现里的加成")
    void chineseDescriptionMatchesConstant() {
        assertPercentEqualsConstant(GameLanguage.ZH_CN);
    }

    @Test
    @DisplayName("英文物品描述里的百分比必须等于实现里的加成")
    void englishDescriptionMatchesConstant() {
        assertPercentEqualsConstant(GameLanguage.EN_US);
    }

    @Test
    @DisplayName("服用提示语里的百分比同样取自常量")
    void effectMessageUsesConstant() {
        String msg = TribulationPillEffect.of().execute(1L, null, null);
        assertNotNull(msg);
        double shown = extractPercent(msg);
        assertEquals(RealmService.TRIBULATION_PILL_BONUS, shown, 0.001,
                "TribulationPillEffect 提示语里的百分比与实现不一致: " + msg);
    }

    @Test
    @DisplayName("死代码 RealmBreakEffect 必须已被删除")
    void deadEffectClassIsGone() {
        assertFalse(classExists("com.mtxgdn.game.item.RealmBreakEffect"),
                "RealmBreakEffect 是死代码（无人调用），且它的 +20% 文案与实现矛盾，应保持删除状态");
    }

    @Test
    @DisplayName("常量本身是 10（与商店描述、提示文案一致）")
    void constantIsTen() {
        assertEquals(10.0, RealmService.TRIBULATION_PILL_BONUS, 0.001,
                "渡劫丹加成应为 +10 个百分点；若确实要改成别的值，"
                        + "需同时改语言包与商店描述，本测试会一起守住");
    }

    // ==================== 夹具 ====================

    private static void assertPercentEqualsConstant(GameLanguage lang) {
        AtomicReference<String> ref = new AtomicReference<>();
        I18nContext.runWith(lang, () -> ref.set(LangManager.get("item.tribulation_pill.desc", "")));
        String desc = ref.get();
        assertNotNull(desc);
        assertFalse(desc.isBlank(), "缺少词条 item.tribulation_pill.desc (" + lang.code() + ")");

        double shown = extractPercent(desc);
        assertEquals(RealmService.TRIBULATION_PILL_BONUS, shown, 0.001,
                "物品描述宣称的加成与实现不一致（玩家看到的和实际拿到的对不上）: " + desc);
    }

    private static double extractPercent(String text) {
        Matcher m = PERCENT.matcher(text);
        assertTrue(m.find(), "文案里没有百分比: " + text);
        return Double.parseDouble(m.group(1));
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
