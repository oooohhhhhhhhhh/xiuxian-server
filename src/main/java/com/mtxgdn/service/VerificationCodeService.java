package com.mtxgdn.service;

import com.mtxgdn.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Random;

public class VerificationCodeService {

    private static final int CODE_LENGTH = 6;
    private static final int EXPIRY_MINUTES = 5;
    private static final Random RANDOM = new Random();

    public String generateAndStoreCode(String email) {
        String code = generateCode();
        String sql = "INSERT INTO verification_codes (email, code, expires_at) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, code);
            ps.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(EXPIRY_MINUTES * 60L)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("验证码存储失败", e);
        }
        return code;
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
