package com.mtxgdn.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志脱敏：把玩家消息里"看起来是凭据"的片段打码后再写日志。
 *
 * <p><b>为什么需要它</b>：所有聊天通道（QQ 私聊/群聊、Minecraft 公屏、WebSocket 聊天）
 * 都会把收到的原始文本整条写进日志，而玩家经常直接在聊天框里打
 * {@code /注册 我的密码123}、{@code /绑定 账号 密码} 这类指令 ——
 * 于是明文口令就落进了 {@code log/} 下的日志文件和 OneBot 消息日志里，
 * 任何能读日志的人（运维、日志收集系统、误发的日志包）都能直接拿去登录。</p>
 *
 * <p>脱敏只做<b>保守的</b>两件事，避免把正常聊天改得面目全非：</p>
 * <ol>
 *   <li>形如 {@code password=xxx} / {@code token: xxx} 的键值对，值打码；</li>
 *   <li>紧跟在这些词后面的"下一个词"打码：{@code 注册 / register / 登录 / login /
 *       绑定 / bind / 改密 / 验证码 / code ...}。</li>
 * </ol>
 *
 * <p>打码一律替换成 {@code ***}（保留原长度会泄露口令长度信息，没有必要）。
 * 其余内容原样保留 —— 日志仍然要能用来排查问题。</p>
 */
public final class LogMasker {

    /** 出现这些词时，它后面的那个词视为凭据。 */
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "注册", "register", "reg",
            "登录", "登陆", "login", "signin",
            "绑定", "bind",
            "改密", "改密码", "修改密码", "重置密码", "设置密码", "password", "passwd", "pwd",
            "验证码", "code", "verify", "captcha",
            "密码", "口令");

    /**
     * 这些指令后面跟<b>两个</b>参数，第二个才是口令。
     *
     * <p>典型形态：{@code /绑定 账号 密码}、{@code /改密 旧密码 新密码}、
     * {@code /login 用户名 密码}。只打码一个参数会把口令留在日志里
     * —— 宁可多打码一个（用户名打码不影响排查）。</p>
     */
    private static final Set<String> TWO_ARGUMENT_KEYWORDS = Set.of(
            "注册", "register", "reg",
            "登录", "登陆", "login", "signin",
            "绑定", "bind",
            "改密", "改密码", "修改密码", "重置密码", "设置密码");

    /** 形如 {@code key=value} / {@code key: value} 且 key 命中这些词时，value 打码。 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "pass",
            "token", "access_token", "secret", "client_secret", "api_token",
            "code", "verify_code", "captcha",
            "密码", "口令", "验证码");

    /** 打码后的占位符。 */
    private static final String MASK = "***";

    /** 单词（非空白串）。 */
    private static final Pattern TOKEN = Pattern.compile("\\S+");

    /**
     * 键值对：key 后跟 = 或 :，再跟值。
     *
     * <p>值不能包含空白、引号、逗号、花括号，也<b>不能包含 {@code & ; ?}</b>
     * —— 否则 {@code ?username=a&password=secret} 里 {@code username} 会把整段吞掉，
     * 后面的 {@code password=...} 就再也匹配不到了。</p>
     */
    private static final Pattern KEY_VALUE =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*[\"']?\\s*[=:]\\s*[\"']?([^\\s\"',};&?]+)");

    private LogMasker() {
    }

    /**
     * 对一条日志文本做脱敏。
     *
     * @param text 原始文本（可为 {@code null}）
     * @return 脱敏后的文本；{@code null} 原样返回
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = maskKeyValuePairs(text);
        return maskKeywordArguments(masked);
    }

    /** 处理 {@code password=xxx} / {@code access_token: xxx} 这类键值对。 */
    private static String maskKeyValuePairs(String text) {
        Matcher m = KEY_VALUE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = m.group(0);
            if (isSensitiveKey(key)) {
                replacement = key + m.group(0).substring(key.length(), m.group(0).indexOf(m.group(2))) + MASK;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 处理"敏感词后面跟的那个词"。 */
    private static String maskKeywordArguments(String text) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int remaining = 0;
        while (m.find()) {
            String token = m.group();
            String replacement = token;
            if (remaining > 0) {
                // 已经是打码占位符就不再重复处理（例如上一步刚处理过的键值对）
                replacement = MASK.equals(token) ? token : MASK;
                remaining--;
            } else {
                int arity = sensitiveArity(token);
                if (arity > 0) {
                    remaining = arity;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 这个 token 是否像"后面还跟着凭据"的关键词，以及要打码几个参数。
     *
     * <p>指令通常带前缀（{@code /注册}、{@code !bind}），所以按去前缀后的词判断。</p>
     *
     * @return 0 表示不是敏感词；1 表示打码后一个参数；2 表示打码后两个参数
     */
    private static int sensitiveArity(String token) {
        String stripped = stripPrefix(token);
        if (TWO_ARGUMENT_KEYWORDS.contains(stripped)) {
            return 2;
        }
        if (SENSITIVE_KEYWORDS.contains(stripped)) {
            return 1;
        }
        // 形如 "/注册: xxx" 的变体：取第一个冒号之前的部分再试一次
        int cut = stripped.indexOf(':');
        if (cut > 0) {
            String head = stripped.substring(0, cut);
            if (TWO_ARGUMENT_KEYWORDS.contains(head)) {
                return 2;
            }
            if (SENSITIVE_KEYWORDS.contains(head)) {
                return 1;
            }
        }
        return 0;
    }

    /** 去掉指令前缀（{@code / ! # . ／ ！}）与尾部标点。 */
    private static String stripPrefix(String token) {
        int start = 0;
        while (start < token.length() && "/!#.／！".indexOf(token.charAt(start)) >= 0) {
            start++;
        }
        int end = token.length();
        while (end > start && "，,。.！!？?：:；;".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(start, end).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT));
    }
}
