package com.mtxgdn.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -Dkey=value} 覆盖配置文件的回归测试。
 *
 * <p>这条能力有两个用途：运维应急改一个值不必动配置文件；测试把会写文件的目录
 * 整体拨到 {@code target/} 下，不再往真实的 {@code data/players} 灌快照。</p>
 *
 * <p><b>曾经的坑</b>：{@code get(path)} 支持了系统属性，但 {@code get(path, default)}
 * 直接读内部 map，而绝大多数调用点用的正是带默认值的那个重载 ——
 * 于是"覆盖生效了"和"覆盖没生效"在代码里看起来一模一样，
 * 只有去看磁盘上文件到底落在哪才发现根本没生效。</p>
 */
class AppConfigSystemPropertyTest {

    private static final String KEY = "__appconfig_test_key__";

    @AfterEach
    void clearProperty() {
        System.clearProperty(KEY);
    }

    @Test
    @DisplayName("-D 覆盖: 单参、带默认值、int、boolean 四个重载都要生效")
    void systemPropertyOverridesAllOverloads() {
        System.setProperty(KEY, "from-system-property");

        assertEquals("from-system-property", AppConfig.get(KEY),
                "单参重载必须返回系统属性");
        assertEquals("from-system-property", AppConfig.get(KEY, "from-yml"),
                "带默认值的重载同样必须走系统属性，否则绝大多数调用点都覆盖不到");
        assertEquals("from-system-property", AppConfig.get(KEY, null));
    }

    @Test
    @DisplayName("-D 覆盖: int / boolean 重载也要跟着系统属性走")
    void numericAndBooleanOverloads() {
        System.setProperty(KEY, "42");
        assertEquals(42, AppConfig.getInt(KEY, 7));

        System.setProperty(KEY, "true");
        assertTrue(AppConfig.getBoolean(KEY, false));

        System.setProperty(KEY, "false");
        assertFalse(AppConfig.getBoolean(KEY, true));
    }

    @Test
    @DisplayName("没有系统属性时回落到配置文件/默认值，不留残留")
    void fallsBackWhenNoOverride() {
        assertNull(AppConfig.get(KEY), "测试键不在配置文件里，应为 null");
        assertEquals("fallback", AppConfig.get(KEY, "fallback"));
        assertEquals(9, AppConfig.getInt(KEY, 9));
        assertTrue(AppConfig.getBoolean(KEY, true));
    }
}
