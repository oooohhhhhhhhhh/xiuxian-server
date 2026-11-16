package com.mtxgdn.util;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

public class EmailService {

    private static final String SMTP_HOST = AppConfig.get("smtp.host", "smtp.qq.com");
    private static final String SMTP_PORT = AppConfig.get("smtp.port", "587");
    private static final String SMTP_USERNAME = AppConfig.get("smtp.username", "");
    private static final String SMTP_PASSWORD = AppConfig.get("smtp.password", "");
    private static final String SMTP_FROM = AppConfig.get("smtp.from", "");
    private static final String SMTP_AUTH = AppConfig.get("smtp.auth", "true");
    private static final String SMTP_STARTTLS = AppConfig.get("smtp.starttls", "true");

    private static final Properties mailProps = new Properties();

    static {
        mailProps.put("mail.smtp.host", SMTP_HOST);
        mailProps.put("mail.smtp.port", SMTP_PORT);
        mailProps.put("mail.smtp.auth", SMTP_AUTH);
        mailProps.put("mail.smtp.starttls.enable", SMTP_STARTTLS);
        mailProps.put("mail.smtp.ssl.trust", SMTP_HOST);
    }

    /**
     * 邮件功能是否真的可用。
     *
     * <p>只看 {@code smtp.host} 是不够的：默认配置里 host 就是 {@code smtp.qq.com}，
     * 但账号与授权码都是空串，此时发信必然失败。所以判定标准是
     * <b>{@code smtp.username} 与 {@code smtp.password} 都非空</b>。</p>
     *
     * <p>读的是静态常量而不是现读 {@code AppConfig}：{@link #sendVerificationCode}
     * 用的就是这两个常量（SMTP 配置需要重启生效），报告"可用"却发不出去
     * 比报告"不可用"更糟 —— 前者会把玩家晾在一个永远收不到验证码的页面上。</p>
     *
     * <p>菜单、网页表单都靠它决定是否展示「邮箱验证码」这一项。</p>
     */
    public static boolean isConfigured() {
        return !SMTP_USERNAME.isBlank() && !SMTP_PASSWORD.isBlank();
    }

    public static void sendVerificationCode(String toEmail, String code) throws MessagingException {
        Session session = Session.getInstance(mailProps, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SMTP_USERNAME, SMTP_PASSWORD);
            }
        });

        Message message = new MimeMessage(session);
        // from 留空时用登录账号（多数 SMTP 服务要求 From 与自己认证的账号一致，
        // 原来直接 new InternetAddress("") 会抛 AddressException，表现为"发码失败"）
        message.setFrom(new InternetAddress(SMTP_FROM.isBlank() ? SMTP_USERNAME : SMTP_FROM));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
        message.setSubject("修仙游戏 - 注册验证码");
        message.setText("您的注册验证码是：" + code + "，有效期为5分钟。\n\n如非本人操作，请忽略此邮件。");

        Transport.send(message);
    }
}
