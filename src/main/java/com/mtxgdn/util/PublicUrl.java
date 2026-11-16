package com.mtxgdn.util;

import java.util.Optional;

/**
 * 「玩家能不能访问到网页」的唯一判定点。
 *
 * <p>QQ 官方机器人可以把 {@code /绑定}、{@code /注册} 的后续步骤引到网页上完成
 * （密码与验证码不落在群聊里）。但那个链接只有在服务真的对外开放时才可用：
 * 默认部署监听 {@code 127.0.0.1}，此时拼出来的 {@code http://127.0.0.1:8080/...}
 * 对玩家是打不开的 —— 发出去只会让玩家反复点、反复失败。</p>
 *
 * <h3>判定规则</h3>
 * <ol>
 *   <li>配置了 {@code server.public-url}（含协议，如 {@code https://game.example.com}）→
 *       直接用它，末尾斜杠会被去掉；不是 {@code http(s)://} 开头则视为<strong>没配</strong>
 *       （宁可不发链接，也不发一条 {@code ftp://} 或漏了协议的坏链接）；</li>
 *   <li>没配 → 看 {@code server.host}：回环（{@code 127.x} / {@code localhost} / {@code ::1}）
 *       或通配（{@code 0.0.0.0} / {@code ::}）一律视为网页不可用；
 *       其它具体地址（如 {@code 192.168.1.10}、{@code game.example.com}）按
 *       {@code http://host:port} 拼。</li>
 * </ol>
 *
 * <p><b>刻意不做静态缓存</b>：安装向导与后台都能改 {@code server.*}，缓存会让
 * "改了配置却不生效"变成线上才发现的怪事（{@link EmailService} 那边的静态常量
 * 就是这个坑的历史版本）。这里每次现读，配置系统本身已经是内存 Map，代价可以忽略。</p>
 *
 * <p>反向代理下的路径前缀（如 {@code /xiuxian}）不在本类处理范围内：
 * 需要时把前缀一起写进 {@code server.public-url} 即可。</p>
 */
public final class PublicUrl {

    /** 对外地址配置项。 */
    static final String CONFIG_KEY = "server.public-url";

    /** 绑定落地页（复用管理后台页面）的路径与参数名。 */
    static final String BIND_PATH = "/admin/";
    static final String BIND_PARAM = "qq_bind";

    private PublicUrl() {
    }

    /**
     * 玩家可访问的站点根地址（无末尾斜杠）。
     *
     * @return 不可用时为空
     */
    public static Optional<String> baseUrl() {
        String configured = AppConfig.get(CONFIG_KEY, "").trim();
        if (!configured.isEmpty()) {
            return normalize(configured);
        }
        String host = AppConfig.get("server.host", "127.0.0.1").trim();
        if (isUnreachableHost(host)) {
            return Optional.empty();
        }
        int port = AppConfig.getInt("server.port", 8080);
        return Optional.of("http://" + host + (port == 80 ? "" : ":" + port));
    }

    /** 网页是否可用（决定菜单里要不要出现「网页登录」这一项）。 */
    public static boolean available() {
        return baseUrl().isPresent();
    }

    /**
     * 拼一条一次性绑定链接。
     *
     * @param token 一次性令牌（base64url，不含需要转义的字符）
     * @return 不可用时返回空串，调用方据此发"私聊机器人"的提示
     */
    public static String bindLink(String token) {
        return baseUrl()
                .map(base -> base + BIND_PATH + "?" + BIND_PARAM + "=" + token)
                .orElse("");
    }

    /**
     * 规范化配置值：只接受 http/https，去掉末尾斜杠。
     *
     * @return 协议不对时为空（当作没配）
     */
    private static Optional<String> normalize(String value) {
        String lower = value.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Optional.empty();
        }
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    /**
     * 该 host 是否意味着"玩家访问不到"。
     *
     * <p>{@code 0.0.0.0} / {@code ::} 是监听通配地址，不是可拨号地址；
     * 回环地址只对本机有意义。两者都不能拿去拼给玩家的链接。</p>
     */
    private static boolean isUnreachableHost(String host) {
        if (host.isEmpty()) {
            return true;
        }
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        String lower = h.toLowerCase();
        return lower.equals("localhost")
                || lower.equals("::1")
                || lower.equals("::")
                || lower.equals("0.0.0.0")
                || lower.startsWith("127.");
    }
}
