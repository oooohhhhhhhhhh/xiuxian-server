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
import java.util.concurrent.ConcurrentHashMap;

public class VerificationCodeService {

    private static final int CODE_LENGTH = 6;
    private static final int EXPIRY_MINUTES = 5;
    private static final int RATE_LIMIT_SECONDS = 60;
    /** 连续验证失败达到该次数后，作废该邮箱所有验证码并锁定 */
    private static final int MAX_VERIFY_FAILURES = 3;
    private static final long VERIFY_LOCK_MS = 5 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** email -> {failCount, lockUntilMs} */
    private static final ConcurrentHashMap<String, long[]> VERIFY_FAILURES = new ConcurrentHashMap<>();

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
        if (email == null || code == null) {
            return false;
        }
        if (isVerifyLocked(email)) {
            return false;
        }
        String sql = "SELECT id FROM verification_codes WHERE email = ? AND code = ? AND expires_at > ? ORDER BY id DESC LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, code);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    VERIFY_FAILURES.remove(email);
                    deleteCodes(email);
                    return true;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("验证码校验失败", e);
        }
        recordVerifyFailure(email);
        return false;
    }

    /** 该邮箱是否处于验证码锁定状态（连续失败 MAX_VERIFY_FAILURES 次后锁定 5 分钟）。 */
    public boolean isVerifyLocked(String email) {
        if (email == null) {
            return false;
        }
        long[] rec = VERIFY_FAILURES.get(email);
        return rec != null && rec[1] > System.currentTimeMillis();
    }

    /** 记录一次验证失败；达到阈值后作废该邮箱全部验证码并锁定。 */
    private void recordVerifyFailure(String email) {
        long now = System.currentTimeMillis();
        VERIFY_FAILURES.compute(email, (k, old) -> {
            long[] rec = old;
            if (rec == null || rec[1] > now) {
                rec = new long[]{0, 0};
            }
            rec[0]++;
            if (rec[0] >= MAX_VERIFY_FAILURES) {
                rec[1] = now + VERIFY_LOCK_MS;
                rec[0] = 0;
                try {
                    deleteCodes(email);
                } catch (Exception ignored) {
                }
            }
            return rec;
        });
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
