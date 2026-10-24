package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 邮箱验证码（{@code verification_codes} 表）的读写入口。
 *
 * <p>从 {@code VerificationCodeService} 抽出来的 DAO。每个意图一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，把 {@link SQLException} 包成
 *       {@link RuntimeException}；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里（{@code runTransaction} 的 lambda 内）必须用这一版，
 *       否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：{@code generateAndStoreCode} 是
 * "查发送记录（限流）→ 插入新验证码"的两步，原实现把它们包在
 * {@code DatabaseManager.runTransaction} 的 lambda 里。限流检查与插入
 * <b>必须落在同一条连接上</b>：分开两次连接时，两个并发请求可以同时看到
 * "60 秒内没有发送记录"，然后双双插入 —— 限流就形同虚设，
 * 而它挡的是短信/邮件轰炸。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>限流查询带 {@code ORDER BY id DESC LIMIT 1}</b>：只看<b>最新</b>
 *       一条发送记录。去掉 {@code ORDER BY} 或 {@code LIMIT} 会让
 *       {@code rs.next()} 读到任意一条，限流判断随之失效。</li>
 *   <li><b>有效期窗口</b>：插入时 {@code expires_at = now + EXPIRY_MINUTES * 60}
 *       （5 分钟）；校验时 {@code expires_at > now} 而非 {@code >=} ——
 *       临界值逐字保留，改成 {@code >=} 会让刚到点的验证码多用一瞬。</li>
 *   <li><b>消费后立即失效</b>：{@code verifyCode} 命中后<b>删掉该邮箱的全部
 *       验证码</b>，而不是只删命中那条。一次性消费的语义是"这个邮箱本次
 *       验证流程结束"，留着旧码会让它被反复使用。</li>
 *   <li><b>删除是 {@code DELETE FROM ... WHERE email = ?}</b>：不按 id 删，
 *       所以锁定（连续失败作废全部验证码）与正常消费走同一条语句。</li>
 *   <li><b>时间戳用 {@code java.sql.Timestamp}</b>：MySQL 的 {@code DATETIME}
 *       与 SQLite 的 {@code TEXT} 都接受驱动转换后的值。</li>
 * </ul>
 *
 * <p><b>不属于这个 DAO 的部分</b>：验证码生成（{@code SecureRandom}）、
 * 失败次数与锁定期（{@code VERIFY_FAILURES}）、过期判断的业务措辞、
 * "发送过于频繁"的异常文案，全部留在 {@code VerificationCodeService}。</p>
 */
public class VerificationCodeDAO {

    /** 限流窗口内最新一条发送记录的 id；没有返回 {@code null}。 */
    public Long findLatestSentId(String email, Instant sentAfter) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findLatestSentId(conn, email, sentAfter);
        } catch (SQLException e) {
            throw new RuntimeException("查询发送记录失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public Long findLatestSentId(Connection conn, String email, Instant sentAfter) throws SQLException {
        String sql = "SELECT id FROM verification_codes WHERE email = ? AND sent_at > ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setTimestamp(2, Timestamp.from(sentAfter));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    /** 插入一条验证码；{@code expiresAt} 是它的自然过期时间。 */
    public void insertCode(String email, String code, Instant expiresAt, Instant sentAt) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertCode(conn, email, code, expiresAt, sentAt);
        } catch (SQLException e) {
            throw new RuntimeException("保存验证码失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public void insertCode(Connection conn, String email, String code, Instant expiresAt, Instant sentAt)
            throws SQLException {
        String sql = "INSERT INTO verification_codes (email, code, expires_at, sent_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, code);
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            ps.setTimestamp(4, Timestamp.from(sentAt));
            ps.executeUpdate();
        }
    }

    /**
     * 按邮箱 + 验证码 + 未过期取最新一条的 id；没有返回 {@code null}。
     *
     * <p>{@code now} 由调用方传入（而不是在 DAO 里取），这样"有效期窗口"
     * 与"失败锁定"的时间基准完全一致，不会因为两次取时间差出一毫秒
     * 而让临界点的验证码时好时坏。</p>
     */
    public Long findValidCodeId(String email, String code, Instant now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findValidCodeId(conn, email, code, now);
        } catch (SQLException e) {
            throw new RuntimeException("验证码校验失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public Long findValidCodeId(Connection conn, String email, String code, Instant now) throws SQLException {
        String sql = "SELECT id FROM verification_codes WHERE email = ? AND code = ? AND expires_at > ? "
                + "ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, code);
            ps.setTimestamp(3, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    /**
     * 删掉某个邮箱的全部验证码。
     *
     * <p>不按 id 删：正常消费（用掉即删）与锁定时作废全部验证码走同一条语句，
     * 一次性消费的语义是"这个邮箱本次验证流程结束"。</p>
     */
    public int deleteCodes(String email) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return deleteCodes(conn, email);
        } catch (SQLException e) {
            throw new RuntimeException("清理验证码失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public int deleteCodes(Connection conn, String email) throws SQLException {
        String sql = "DELETE FROM verification_codes WHERE email = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            return ps.executeUpdate();
        }
    }
}
