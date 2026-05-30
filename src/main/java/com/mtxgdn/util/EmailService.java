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

    public static void sendVerificationCode(String toEmail, String code) throws MessagingException {
        Session session = Session.getInstance(mailProps, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SMTP_USERNAME, SMTP_PASSWORD);
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(SMTP_FROM));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
        message.setSubject("修仙游戏 - 注册验证码");
        message.setText("您的注册验证码是：" + code + "，有效期为5分钟。\n\n如非本人操作，请忽略此邮件。");

        Transport.send(message);
    }
}
