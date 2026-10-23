package com.mtxgdn.db;

import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * {@code users} 表上被 QQ 侧（OneBot 与 QQ 官方机器人）共用的账号读写。
 *
 * <p><b>为什么要有这个类</b>：这些方法原来在
 * {@code OneBotWebSocketServer} 与 {@code QqOfficialAdapter} 里各存了一份，
 * 且两份是逐字节相同的复制粘贴。复制两份的代价不是"多写了 130 行"，而是：</p>
 * <ul>
 *   <li>改一处忘另一处 —— 例如给 {@code users} 加列或改列名时，
 *       只改了一个通道，另一个通道在运行时才报 "no such column"；</li>
 *   <li>两份各自演化后，同一个用户名在两个通道上的可用性判断可能不一致。</li>
 * </ul>
 *
 * <p>这里只收敛<b>与传输无关</b>的数据库操作。注册/绑定/改密/注销的<b>对话流程</b>
 * 仍然留在各自适配器里，因为两条通道的流程确实不同（例如 OneBot 绑定成功后会补一个
 * {@code <QQ>@qq.com} 的占位邮箱，QQ 官方通道刻意不覆盖用户已有邮箱；
 * 两条通道的失败提示措辞也不同），强行合并会把这种差异抹平成 bug。</p>
 */
public final class UserAccountDAO {

    private static final GameLogger LOG = GameLogger.getLogger(UserAccountDAO.class);

    /** 邮箱格式：只做最基本的形状校验，真正的可用性靠验证码。 */
    private static final String EMAIL_PATTERN = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";

    private UserAccountDAO() {
    }

    /** 用户名是否已被占用。查询失败时返回 false（与原实现一致，避免阻塞注册流程）。 */
    public static boolean isUsernameExists(String username) {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            LOG.error("查询用户名失败", e);
        }
        return false;
    }

    /**
     * 插入一行用户，返回自增主键；插入失败返回 null。
     *
     * <p>注意 {@code password} 必须是<b>已经哈希过</b>的值 —— 本类不做哈希，
     * 免得哪天有人把明文传进来。</p>
     */
    public static Long insertUser(String username, String hashedPassword, String email) {
        String sql = "INSERT INTO users (username, password, email) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.setString(2, hashedPassword);
            stmt.setString(3, email);
            if (stmt.executeUpdate() > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("用户插入失败", e);
        }
        return null;
    }

    /** 删除一行用户（注册回滚、注销账号时使用）。 */
    public static void deleteUser(long userId) {
        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除用户失败", e);
        }
    }

    /** 邮箱格式校验。 */
    public static boolean isValidEmail(String email) {
        return email != null && email.matches(EMAIL_PATTERN);
    }

    /** 按用户 ID 取用户名；不存在返回 null。 */
    public static String getUsernameByUserId(long userId) {
        String sql = "SELECT username FROM users WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("username");
            }
        } catch (SQLException e) {
            LOG.error("查询用户名失败", e);
        }
        return null;
    }

    /** 更新密码（已哈希）。用户不存在时抛 {@link RuntimeException}，与原实现一致。 */
    public static void updatePassword(long userId, String hashedPassword) {
        String sql = "UPDATE users SET password = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hashedPassword);
            stmt.setLong(2, userId);
            if (stmt.executeUpdate() == 0) {
                throw new RuntimeException("用户不存在");
            }
        } catch (SQLException e) {
            throw new RuntimeException("密码更新失败", e);
        }
    }
}
