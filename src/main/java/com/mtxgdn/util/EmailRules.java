package com.mtxgdn.util;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 邮箱校验规则（格式 + 域名白名单），全项目共用一份。
 *
 * <p><b>为什么单独成类</b>：{@code UserService} 的公开 API 被黄金清单钉死
 * （多一个 public 方法就会让 REST / QQ / 插件的调用方编译不过），
 * 而 QQ 官方通道的聊天注册、网页注册、REST 注册都需要同一份规则 ——
 * 两边各写一个正则，迟早会一边放过 {@code a@b}、另一边不放过。</p>
 */
public final class EmailRules {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private EmailRules() {
    }

    /** 邮箱格式是否合法（只校验形状）。 */
    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * 邮箱域名是否在白名单内（{@code verify_code.allowed_domains}，
     * 默认 qq.com,163.com,126.com,gmail.com,outlook.com,hotmail.com）。
     */
    public static boolean isAllowedEmailDomain(String email) {
        if (email == null) {
            return false;
        }
        String allowedDomains = AppConfig.get("verify_code.allowed_domains",
                "qq.com,163.com,126.com,gmail.com,outlook.com,hotmail.com");
        List<String> domains = Arrays.asList(allowedDomains.split(","));
        int atIndex = email.lastIndexOf('@');
        if (atIndex <= 0 || atIndex >= email.length() - 1) {
            return false;
        }
        String domain = email.substring(atIndex + 1).toLowerCase();
        return domains.contains(domain);
    }
}
