package com.mtxgdn.i18n;

import com.mtxgdn.game.config.WildernessMapConfig;
import com.mtxgdn.game.entity.RealmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据文件（realm_config.json / wilderness_map.json）的本地化回归测试。
 *
 * <p>这两份文件与代码里的硬编码文案不同：它们既有 {@code nameKey}/{@code descKey}
 * 指向语言包，又<b>保留</b>了原来的中文 {@code name}/{@code description}。
 * 后者是兜底——语言包缺失时界面必须还是完整中文，而不是一堆裸 key。
 * 所以这里同时验证两条路：能翻译的时候翻译，翻不了的时候回落原文。</p>
 */
class DataFileI18nTest {

    @BeforeEach
    void setUp() {
        I18nManager.load();
        I18nContext.clear();
    }

    @AfterEach
    void tearDown() {
        I18nContext.clear();
        I18nManager.reload();
    }

    // ==================== RealmConfig ====================

    @Test
    @DisplayName("RealmConfig: 无 nameKey 时直接返回中文字面量（老配置文件零改动可用）")
    void realmWithoutKeyReturnsLiteral() {
        RealmConfig r = new RealmConfig();
        r.setName("炼气期");
        r.setDescription("引气入体");
        assertEquals("炼气期", r.getName());
        assertEquals("引气入体", r.getDescription());
    }

    @Test
    @DisplayName("RealmConfig: 有 nameKey 且语言包有译文时返回译文")
    void realmWithKeyUsesTranslation() {
        RealmConfig r = new RealmConfig();
        r.setName("原文名");
        r.setDescription("原文描述");
        r.setNameKey("realm.1.0.name");
        r.setDescKey("realm.1.0.desc");

        // zh_cn 里 realm.1.0.name 就是「炼气期」
        I18nContext.set(GameLanguage.ZH_CN);
        assertEquals("炼气期", r.getName());

        // 换到 en_us：该 key 已有英文译文，必须返回英文，既不能是中文也不能是裸 key
        I18nContext.set(GameLanguage.EN_US);
        String en = r.getName();
        assertNotEquals("realm.1.0.name", en, "不能把 key 露给玩家");
        assertNotEquals("炼气期", en, "en_us 已有译文时不应再回落中文");
        assertEquals(I18nManager.raw("realm.1.0.name", GameLanguage.EN_US), en);
    }

    @Test
    @DisplayName("RealmConfig: 目标语言缺译文时回落 zh_cn 中文，而不是裸 key")
    void realmFallsBackWhenTargetLanguageMissing() {
        // 造一条只存在于 zh_cn 的词条，模拟"en_us 还没翻译"的情况
        I18nManager.mergeFromStream(GameLanguage.ZH_CN,
                new java.io.ByteArrayInputStream(
                        "{\"realm.test.only.zh\":\"仅中文词条\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        RealmConfig r = new RealmConfig();
        r.setName("原文名");
        r.setNameKey("realm.test.only.zh");

        I18nContext.set(GameLanguage.EN_US);
        assertEquals("仅中文词条", r.getName(), "en_us 缺词条时必须回落 zh_cn，不能露出 key");
    }

    @Test
    @DisplayName("RealmConfig: nameKey 指向不存在的词条时回落中文字面量")
    void realmWithBrokenKeyFallsBack() {
        RealmConfig r = new RealmConfig();
        r.setName("兜底名");
        r.setNameKey("realm.999.999.name");
        assertEquals("兜底名", r.getName(), "词条不存在时不能把 key 露给玩家");
    }

    @Test
    @DisplayName("RealmConfig: getRawName 永远返回未本地化的原文，供日志/匹配使用")
    void realmRawNameIsStable() {
        RealmConfig r = new RealmConfig();
        r.setName("炼气期");
        r.setNameKey("realm.1.0.name");
        I18nContext.set(GameLanguage.EN_US);
        assertEquals("炼气期", r.getRawName());
    }

    // ==================== 随包数据文件的完整性 ====================

    @Test
    @DisplayName("realm_config.json: 每个境界都带上了 nameKey 与 descKey，且词条确实在 zh_cn 里")
    void shippedRealmConfigIsFullyKeyed() {
        List<RealmConfig> realms = com.mtxgdn.game.config.GameConfigLoader.getRealmConfigs();
        assertNotNull(realms);
        assertFalse(realms.isEmpty(), "境界配置不应为空");

        for (RealmConfig r : realms) {
            assertNotNull(r.getNameKey(), "境界 " + r.getId() + "." + r.getSubRealm() + " 缺 nameKey");
            assertNotNull(r.getDescKey(), "境界 " + r.getId() + "." + r.getSubRealm() + " 缺 descKey");
            // 词条必须真的在 zh_cn 里，否则等于白加 key
            assertTrue(I18nManager.bundle(GameLanguage.ZH_CN).containsKey(r.getNameKey()),
                    "zh_cn 缺词条: " + r.getNameKey());
            assertTrue(I18nManager.bundle(GameLanguage.ZH_CN).containsKey(r.getDescKey()),
                    "zh_cn 缺词条: " + r.getDescKey());
        }
    }

    @Test
    @DisplayName("wilderness_map.json: 每个地形都带 nameKey，且 zh_cn 有对应词条")
    void shippedWildernessBiomesAreKeyed() {
        WildernessMapConfig cfg = WildernessMapConfig.get();
        assertNotNull(cfg);
        assertFalse(cfg.getBiomes().isEmpty(), "地形配置不应为空");

        for (WildernessMapConfig.BiomeSeed b : cfg.getBiomes()) {
            assertNotNull(b.getNameKey(), "地形 " + b.getKey() + " 缺 nameKey");
            assertTrue(I18nManager.bundle(GameLanguage.ZH_CN).containsKey(b.getNameKey()),
                    "zh_cn 缺词条: " + b.getNameKey());
            assertFalse(b.getName().isBlank(), "地形名不应为空");
        }
    }
}
