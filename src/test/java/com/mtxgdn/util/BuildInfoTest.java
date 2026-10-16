package com.mtxgdn.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 版本号「单一来源」机制的回归测试。
 *
 * <p>版本号此前硬编码在 10 处，改一次要逐一去找，漏一处就会出现「界面显示的版本与
 * 实际构建不一致」。现在唯一来源是 {@code pom.xml} 的 {@code <version>}，
 * 经资源过滤（{@code build-info.properties}）与 JAR manifest 两条链路进入程序。</p>
 *
 * <p>本测试<b>刻意不写死具体版本号</b>：那样每次升版本都会变红，反而会被当成噪音忽略。
 * 它锁的是「机制还在不在」——只要能解析出真实版本、且形态约定不被破坏即可。</p>
 */
class BuildInfoTest {

    @Test
    @DisplayName("版本号可从构建产物解析出来（机制失效会退化成 unknown）")
    void versionResolvesFromBuild() {
        String version = BuildInfo.getVersion();

        assertNotNull(version, "版本号不应为 null");
        assertFalse(version.isBlank(), "版本号不应为空白");
        assertNotEquals("unknown", version,
                "应能从 build-info.properties 或 JAR manifest 读到版本；"
                        + "出现 unknown 说明资源过滤或 manifest 注入失效了");
        assertFalse(version.contains("${"),
                "版本号里出现 ${ 说明 Maven 资源过滤没生效（文件被原样拷贝）: " + version);
    }

    @Test
    @DisplayName("展示版本号 = v + 纯净版本号；纯净版本号不得自带 v 前缀")
    void displayVersionIsPrefixedButRawVersionIsNot() {
        String raw = BuildInfo.getVersion();

        assertEquals("v" + raw, BuildInfo.getDisplayVersion(),
                "展示版本号应恰好是 v 加纯净版本号");
        assertFalse(raw.startsWith("v"),
                "纯净版本号不能带 v 前缀——它会被填进插件模板的 Maven 坐标，"
                        + "带前缀会直接装不上: " + raw);
    }
}
