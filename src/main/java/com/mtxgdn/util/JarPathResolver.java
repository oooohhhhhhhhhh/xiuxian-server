package com.mtxgdn.util;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 classpath 上的 {@code jar:file:...!/pkg} URL 还原成本地 jar 路径。
 *
 * <p><b>为什么需要它</b>：几个扫描器（{@code CommandScanner}、{@code ItemScanner}、
 * {@code ExplorationEventScanner}、{@code SecretRealmScanner}）原来都写成
 * {@code String jarPath = url.getPath()}，然后直接 {@code new JarFile(jarPath)}。
 * {@link URL#getPath()} 返回的是<b>未解码</b>的百分号编码形式：</p>
 *
 * <ul>
 *   <li>路径里有空格：{@code C:\Program Files\x.jar} → {@code /C:/Program%20Files/x.jar}</li>
 *   <li>路径里有中文（Windows 用户名、中文目录名很常见）：{@code E:\修仙\x.jar} →
 *       {@code /E:/%E4%BF%AE%E4%BB%99/x.jar}</li>
 * </ul>
 *
 * <p>用这种字符串去打开 jar 会失败，而失败又被扫描器里宽容的 catch 吞掉 ——
 * 表现为"整个游戏的指令/物品/秘境/探索事件全部消失"，且没有任何报错，
 * 极难排查（开发机路径是纯 ASCII，测试环境也发现不了）。</p>
 *
 * <p>这里统一做三件事：去掉 {@code !/...} 之后的部分、去掉多余的 {@code file:} 前缀、
 * 对百分号编码做<b>路径语义</b>的解码（不把 {@code +} 当空格 —— 那是表单编码的规则，
 * 文件路径里的 {@code +} 就是加号本身）。</p>
 */
public final class JarPathResolver {

    private JarPathResolver() {
    }

    /**
     * 解析 URL 指向的本地 jar 文件路径。
     *
     * @param url 形如 {@code jar:file:/C:/a%20b/app.jar!/com/x} 的 URL
     * @return 本地文件路径
     * @throws IllegalArgumentException URL 为空
     */
    public static Path resolve(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("url 不能为 null");
        }
        // file: URL 优先走 toURI：它自带正确的解码逻辑
        if ("file".equals(url.getProtocol())) {
            try {
                return Paths.get(url.toURI());
            } catch (Exception ignored) {
                // 落到下面的手工解码
            }
        }

        String raw = url.getPath();
        int bang = raw.indexOf('!');
        if (bang >= 0) {
            raw = raw.substring(0, bang);
        }
        if (raw.startsWith("file:")) {
            raw = raw.substring("file:".length());
        }
        String decoded = decodePath(raw);
        // jar:file:/C:/x.jar 在 Windows 上会留下前导斜杠，/C:/x.jar 不是合法路径
        // （InvalidPathException: Illegal char <:>），必须去掉。
        if (decoded.length() > 2 && decoded.charAt(0) == '/'
                && Character.isLetter(decoded.charAt(1)) && decoded.charAt(2) == ':') {
            decoded = decoded.substring(1);
        }
        return Paths.get(decoded);
    }

    /** 同 {@link #resolve(URL)}，但直接返回 {@link File}。 */
    public static File resolveFile(URL url) {
        return resolve(url).toFile();
    }

    /**
     * 只解码百分号编码，不把 {@code +} 当空格。
     *
     * <p>手写而不用 {@link java.net.URLDecoder}：后者的 {@code +} → 空格规则属于
     * {@code application/x-www-form-urlencoded}，用在文件路径上会把
     * {@code my+mod.jar} 解成 {@code my mod.jar}。先把 {@code +} 转义成 {@code %2B}
     * 再交给 URLDecoder 也能达到同样效果，这里选择显式实现以免依赖那个细节。</p>
     */
    static String decodePath(String raw) {
        if (raw.indexOf('%') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        byte[] pending = new byte[raw.length()];
        int pendingLen = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()
                    && isHex(raw.charAt(i + 1)) && isHex(raw.charAt(i + 2))) {
                pending[pendingLen++] = (byte) Integer.parseInt(raw.substring(i + 1, i + 3), 16);
                i += 2;
                continue;
            }
            // 遇到非转义字符：先把累积的多字节序列按 UTF-8 还原
            if (pendingLen > 0) {
                sb.append(new String(pending, 0, pendingLen, StandardCharsets.UTF_8));
                pendingLen = 0;
            }
            sb.append(c);
        }
        if (pendingLen > 0) {
            sb.append(new String(pending, 0, pendingLen, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * 把 URL 解析成 {@link URI}（失败返回 {@code null}），供需要 URI 语义的调用方使用。
     */
    public static URI toUriOrNull(URL url) {
        try {
            return url != null ? url.toURI() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
