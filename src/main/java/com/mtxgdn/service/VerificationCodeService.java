package com.mtxgdn.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.VerificationCodeDAO;
import com.mtxgdn.util.AppConfig;

import java.security.SecureRandom;
import java.time.Instant;
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

    /** {@code verification_codes} 表的读写入口。 */
    private final VerificationCodeDAO codeDao = new VerificationCodeDAO();

    public String generateAndStoreCode(String email) {
        String code = generateCode();
        // 限流检查与插入必须落在同一条连接上：分开两次连接时，两个并发请求
        // 可以同时看到"60 秒内没有发送记录"，然后双双插入 —— 限流形同虚设，
        // 而它挡的是短信/邮件轰炸。
        Boolean success = DatabaseManager.runTransaction(conn -> {
            int rateLimitSeconds = AppConfig.getInt("verify_code.rate_limit_seconds", RATE_LIMIT_SECONDS);
            Long latestId = codeDao.findLatestSentId(conn, email,
                    Instant.now().minusSeconds(rateLimitSeconds));
            if (latestId != null) {
                return false;
            }
            codeDao.insertCode(conn, email, code,
                    Instant.now().plusSeconds(EXPIRY_MINUTES * 60L), Instant.now());
            return true;
        });
        if (!success) {
            throw new RuntimeException("发送过于频繁，请稍后再试");
        }
        return code;
    }

    public boolean canSendCode(String email) {
        int rateLimitSeconds = AppConfig.getInt("verify_code.rate_limit_seconds", RATE_LIMIT_SECONDS);
        return codeDao.findLatestSentId(email, Instant.now().minusSeconds(rateLimitSeconds)) == null;
    }

    public boolean verifyCode(String email, String code) {
        if (email == null || code == null) {
            return false;
        }
        if (isVerifyLocked(email)) {
            return false;
        }
        Long id = codeDao.findValidCodeId(email, code, Instant.now());
        if (id != null) {
            VERIFY_FAILURES.remove(email);
            // 用掉即删：一次性消费，删掉该邮箱全部验证码
            deleteCodes(email);
            return true;
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
        codeDao.deleteCodes(email);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
