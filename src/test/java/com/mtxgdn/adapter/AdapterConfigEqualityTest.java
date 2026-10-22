package com.mtxgdn.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器配置必须按"内容"比较，否则热重载会无谓地掐断所有连接。
 *
 * <p><b>缺陷形态</b>：{@code AdapterManager.reloadAll} 用
 * {@code !oldCfg.equals(newCfg)} 判断"配置是否变更，需要先停后启"。
 * {@code AdapterConfig} 没有重写 {@code equals}，那是<b>引用比较</b> ——
 * 热重载读出来的永远是新的对象，判断恒为"已变更"。
 * 结果：每次保存适配器配置，所有启用的适配器都被停掉再启动，
 * 正在聊天的 QQ / Minecraft 玩家连接被无谓掐断。</p>
 *
 * <p>第二个坑：重载走 {@code Gson.fromJson(..., Map.class)}，JSON 里的数字一律变成
 * {@code Double}，而内存里的原始值可能是 {@code Integer}（默认配置里写死的
 * {@code port: 8080}）。若直接用 {@code Map.equals}，{@code 8080} 与 {@code 8080.0}
 * 会被判为不同 —— 等于没修。</p>
 */
class AdapterConfigEqualityTest {

    @Test
    @DisplayName("内容相同即相等，且 hashCode 一致")
    void equalByContent() {
        AdapterConfig a = cfg("onebot", "onebot", true, Map.of("host", "127.0.0.1", "port", 8080));
        AdapterConfig b = cfg("onebot", "onebot", true, Map.of("host", "127.0.0.1", "port", 8080));

        assertEquals(a, b, "内容相同的两份配置必须相等，否则热重载会把适配器全部重启");
        assertEquals(a.hashCode(), b.hashCode(), "equals 相等则 hashCode 必须一致");
    }

    @Test
    @DisplayName("数字类型漂移（Integer vs Double）不影响相等判断")
    void numericTypeDriftStillEqual() {
        // 内存里的默认配置：Integer
        AdapterConfig inMemory = cfg("onebot", "onebot", true,
                Map.of("port", 8080, "access_token", "abc", "auto_start", true));
        // 从 adapters.json 重新解析：数字变成 Double
        AdapterConfig reloaded = cfg("onebot", "onebot", true,
                Map.of("port", 8080.0, "access_token", "abc", "auto_start", true));

        assertEquals(inMemory, reloaded,
                "JSON 往返会把 Integer 变成 Double；若这里不相等，"
                        + "热重载仍会认为配置变更并重启适配器");
    }

    @Test
    @DisplayName("内容不同则不相等")
    void differentContentNotEqual() {
        AdapterConfig a = cfg("onebot", "onebot", true, Map.of("host", "127.0.0.1"));
        AdapterConfig b = cfg("onebot", "onebot", true, Map.of("host", "0.0.0.0"));
        assertNotEquals(a, b, "配置值变了必须判为不等，否则改了配置不会生效");

        AdapterConfig c = cfg("onebot", "onebot", false, Map.of("host", "127.0.0.1"));
        assertNotEquals(a, c, "启用状态变了必须判为不等");

        AdapterConfig d = cfg("onebot", "minecraft", true, Map.of("host", "127.0.0.1"));
        assertNotEquals(a, d, "类型变了必须判为不等");

        AdapterConfig e = cfg("other", "onebot", true, Map.of("host", "127.0.0.1"));
        assertNotEquals(a, e, "id 变了必须判为不等");
    }

    @Test
    @DisplayName("嵌套 Map / List 也要逐层比较")
    void nestedStructuresCompared() {
        Map<String, Object> nestedA = new LinkedHashMap<>();
        nestedA.put("retry", Map.of("times", 3, "delay", 100));
        nestedA.put("groups", List.of(1, 2, 3));
        Map<String, Object> nestedB = new LinkedHashMap<>();
        nestedB.put("retry", Map.of("times", 3.0, "delay", 100.0));
        nestedB.put("groups", List.of(1.0, 2.0, 3.0));

        AdapterConfig a = cfg("x", "onebot", true, nestedA);
        AdapterConfig b = cfg("x", "onebot", true, nestedB);
        assertEquals(a, b, "嵌套结构的数字类型漂移同样不应影响相等判断");

        nestedB.put("groups", List.of(1, 2, 4));
        assertFalse(a.equals(cfg("x", "onebot", true, nestedB)),
                "嵌套列表内容变了必须判为不等");
    }

    @Test
    @DisplayName("copy() 得到的副本与原对象相等（热重载对比的前提）")
    void copyIsEqual() {
        AdapterConfig original = cfg("onebot", "onebot", true, Map.of("port", 8080));
        assertEquals(original, original.copy(), "深拷贝必须与原对象相等");
        assertTrue(original.copy() != original, "copy 必须是新对象，不能返回 this");
    }

    @Test
    @DisplayName("与 null / 其它类型比较不抛异常")
    void handlesNullOrForeignType() {
        AdapterConfig a = cfg("onebot", "onebot", true, Map.of());
        assertNotEquals(null, a);
        assertNotEquals("onebot", a);
        assertEquals(a, a);
    }

    private static AdapterConfig cfg(String id, String type, boolean enabled, Map<String, Object> config) {
        return new AdapterConfig(id, type, enabled, new LinkedHashMap<>(config));
    }
}
