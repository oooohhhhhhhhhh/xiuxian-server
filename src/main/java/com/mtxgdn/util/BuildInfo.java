package com.mtxgdn.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 构建期版本信息 —— 全项目版本号的**唯一读取入口**。
 *
 * <p>背景：版本号此前被硬编码在 10 处（后台接口、插件生成器的横幅、GUI 标题、
 * 生成的插件文档、模板 pom 的 Maven 坐标注释……），且写法还不统一
 * （同一时间既有 {@code V1.4.1-beta2} 也有 {@code v1.4.1-beta2}）。
 * 改一次版本号要逐一去找，漏一处就会出现"界面显示的版本和实际构建不一致"。</p>
 *
 * <p>现在版本号的唯一来源是 {@code pom.xml} 的 {@code <version>}，通过两条链路进入程序：</p>
 * <ol>
 *   <li>{@code src/main/resources/build-info.properties} 由 Maven 资源过滤写入
 *       {@code ${project.version}}，随 classpath 一起打包 —— 这是主要来源，
 *       开发期直接跑 {@code target/classes} 也能读到；</li>
 *   <li>shade 时写入 JAR manifest 的 {@code Implementation-Version} —— 作为兜底，
 *       万一资源文件缺失仍能从清单里拿到。</li>
 * </ol>
 *
 * <p>两者都拿不到时返回 {@code "unknown"}（例如未经 Maven 构建、手改 classpath 的场景），
 * 属于"显示不出来"而不是"显示错版本"。</p>
 *
 * <p><b>注意</b>：{@link #getVersion()} 返回纯净版本号（{@code 0.1.0}），
 * 用于 Maven 坐标等机器可读场景，<b>不要加 {@code v} 前缀</b>；
 * 面向玩家的展示请用 {@link #getDisplayVersion()}（{@code v0.1.0}）。</p>
 */
public final class BuildInfo {

    private static final String VERSION = resolve();

    private BuildInfo() {}

    /** 纯净版本号，如 {@code 0.1.0}。用于 Maven 坐标、插件模板等机器可读场景。 */
    public static String getVersion() {
        return VERSION;
    }

    /** 展示用版本号，如 {@code v0.1.0}。用于界面、横幅、文档文案。 */
    public static String getDisplayVersion() {
        return "v" + VERSION;
    }

    private static String resolve() {
        // 1) 资源过滤生成的 build-info.properties（主要来源）
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                Properties props = new Properties();
                // 显式按 UTF-8 读：该文件由 Maven 以 UTF-8 过滤生成
                // （pom 里钉了 <propertiesEncoding>UTF-8</propertiesEncoding>），
                // 而 Properties.load(InputStream) 默认按 ISO-8859-1 解码，注释会变乱码。
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                String version = props.getProperty("version");
                // 含 "${" 说明资源过滤没跑（文件被原样拷贝），当作没读到
                if (version != null && !version.isBlank() && !version.contains("${")) {
                    return version.trim();
                }
            }
        } catch (Exception ignored) {
            // 读不到就落到下一个来源
        }

        // 2) JAR manifest（shade 写入 Implementation-Version）
        Package pkg = BuildInfo.class.getPackage();
        String manifestVersion = pkg == null ? null : pkg.getImplementationVersion();
        if (manifestVersion != null && !manifestVersion.isBlank()) {
            return manifestVersion.trim();
        }

        // 3) 都没有：明确表示"未知"，而不是谎报一个版本
        return "unknown";
    }
}
