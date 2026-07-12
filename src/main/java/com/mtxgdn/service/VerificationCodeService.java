package com.mtxgdn.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.util.AppConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.security.SecureRandom;

public class VerificationCodeService {

    private static final int CODE_LENGTH = 6;
    private static final int EXPIRY_MINUTES = 5;
    private static final int RATE_LIMIT_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generateAndStoreCode(String email) {
        String code = generateCode();
        Boolean success = DatabaseManager.runTransaction(conn -> {
            int rateLimitSeconds = AppConfig.getInt("verify_code.rate_limit_seconds", RATE_LIMIT_SECONDS);
            String checkSql = "SELECT id FROM verification_codes WHERE email = ? AND sent_at > ? ORDER BY id DESC LIMIT 1";
            try (var ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, email);
                ps.setTimestamp(2, Timestamp.from(Instant.now().minusSeconds(rateLimitSeconds)));
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            String insertSql = "INSERT INTO verification_codes (email, code, expires_at, sent_at) VALUES (?, ?, ?, ?)";
            try (var ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, email);
                ps.setString(2, code);
                ps.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(EXPIRY_MINUTES * 60L)));
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            return true;
        });
        if (!success) {
            throw new RuntimeException("发送过于频繁，请稍后再试");
        }
        return code;
    }

    public boolean canSendCode(String email) {
        int rateLimitSeconds = AppConfig.getInt("verify_code.rate_limit_seconds", RATE_LIMIT_SECONDS);
        String sql = "SELECT id FROM verification_codes WHERE email = ? AND sent_at > ? ORDER BY id DESC LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setTimestamp(2, Timestamp.from(Instant.now().minusSeconds(rateLimitSeconds)));
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询发送记录失败", e);
        }
    }

    public boolean verifyCode(String email, String code) {
        String sql = "SELECT id FROM verification_codes WHERE email = ? AND code = ? AND expires_at > ? ORDER BY id DESC LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, code);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    deleteCodes(email);
                    return true;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("验证码校验失败", e);
        }
        return false;
    }

    private void deleteCodes(String email) {
        String sql = "DELETE FROM verification_codes WHERE email = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("清理验证码失败", e);
        }
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
