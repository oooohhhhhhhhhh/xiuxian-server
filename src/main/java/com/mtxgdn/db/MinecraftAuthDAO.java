package com.mtxgdn.db;

import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Minecraft 认证链路在 {@code users} 表上的读写入口。
 *
 * <p>从 {@code MinecraftAuthService} 抽出来的 DAO。每个意图一对重载：</p>
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
 * <p><b>为什么需要 Connection 重载</b>：{@code doRegisterComplete} 是
 * "插用户 → 分配默认角色 → 绑定 MC → 建角色"的一条链，失败回滚是
 * "先 {@code unbindByMcUuid} 再 {@code deleteUser}"两步。这条链在适配器里
 * 可能被放进事务，DAO 若自己另开连接就会把外层事务劈成多次独立提交 ——
 * 半截账号（用户插了、角色没建）会留在库里，而 MC 侧已经显示"注册成功"。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>凭据查询失败不抛异常</b>：原实现在 catch 里只打一条
 *       {@code log.error} 然后返回 {@code null}，调用方
 *       （{@code doBindComplete}）据此回"用户名或密码错误，请重新绑定"。
 *       所以自取连接的重载也是吞掉异常后返回 {@code null}，而不是包成
 *       RuntimeException 往上抛 —— 抛出去会把"数据库挂了"变成
 *       "密码错误"，玩家反复重试也查不出真正原因。</li>
 *   <li><b>{@code isUsernameExists} 失败同样返回 false</b>：文案
 *       "查询用户名失败"逐字保留。查询失败按"用户名可用"放行，
 *       与原实现一致。</li>
 *   <li><b>{@code insertUser} 用 {@code RETURN_GENERATED_KEYS}</b>：
 *       注册成功必须拿到自增主键才能建角色；取不到时返回 {@code null}
 *       （调用方据此回"注册失败，请稍后重试"）。</li>
 *   <li><b>异常文案逐字保留</b>："用户插入失败" / "删除用户失败"。</li>
 *   <li><b>密码比对不在 DAO 里</b>：原实现的
 *       {@code rs.next() && BCrypt.checkpw(password, rs.getString("password"))}
 *       被拆成"DAO 取回 id + 哈希"与"调用方做 BCrypt 比对"两步，
 *       判定顺序与短路行为完全一致（查不到行 → null；哈希不匹配 → null）。
 *       {@code org.mindrot} 的比对留在服务层，DAO 只负责 SQL。</li>
 * </ul>
 *
 * <p><b>与 {@link UserAccountDAO} 的关系</b>：{@code isUsernameExists} 与
 * {@code deleteUser} 的 SQL 和那边的同名方法逐字相同，但那边面向 QQ 注册
 * 流程（{@code insertUser} 带 {@code email} 三参），且是 static 工具类。
 * MC 这条链路刻意自包含，避免两条通道的账号写入互相耦合 ——
 * 与 {@code QqBindingDAO} / {@code QqOfficialBindingDAO} 按表分开是同一条理由。</p>
 */
public class MinecraftAuthDAO {

    private static final GameLogger log = GameLogger.getLogger(MinecraftAuthDAO.class);

    /** {@code users} 表上一次凭据查询的结果：自增主键 + 密码哈希。 */
    public record UserCredential(long id, String passwordHash) {
    }

    /**
     * 按用户名取 {@code id} 与 {@code password}；不存在返回 {@code null}。
     *
     * <p>列清单逐字保留自原实现的
     * {@code SELECT id, password FROM users WHERE username = ?}。</p>
     */
    public UserCredential findCredentialByUsername(String username) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findCredentialByUsername(conn, username);
        } catch (SQLException e) {
            log.error("验证用户凭据失败", e);
        }
        return null;
    }

    /** 事务内版本：只用调用方连接。 */
    public UserCredential findCredentialByUsername(Connection conn, String username) throws SQLException {
        String sql = "SELECT id, password FROM users WHERE username = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new UserCredential(rs.getLong("id"), rs.getString("password"));
                }
            }
        }
        return null;
    }

    /** 用户名是否已被占用；查询失败返回 {@code false}（与原实现一致）。 */
    public boolean isUsernameExists(String username) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return isUsernameExists(conn, username);
        } catch (SQLException e) {
            log.error("查询用户名失败", e);
        }
        return false;
    }

    /** 事务内版本：只用调用方连接。 */
    public boolean isUsernameExists(Connection conn, String username) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    /**
     * 插入一行用户，返回自增主键；取不到主键返回 {@code null}。
     *
     * <p>{@code hashedPassword} 必须是<b>已经哈希过</b>的值 ——
     * 本类不做哈希，免得哪天有人把明文传进来。</p>
     */
    public Long insertUser(String username, String hashedPassword) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return insertUser(conn, username, hashedPassword);
        } catch (SQLException e) {
            throw new RuntimeException("用户插入失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public Long insertUser(Connection conn, String username, String hashedPassword) throws SQLException {
        String sql = "INSERT INTO users (username, password) VALUES (?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.setString(2, hashedPassword);
            if (stmt.executeUpdate() > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        }
        return null;
    }

    /** 删除一行用户（注册失败回滚时使用）。 */
    public void deleteUser(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            deleteUser(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("删除用户失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public void deleteUser(Connection conn, long userId) throws SQLException {
        String sql = "DELETE FROM users WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.executeUpdate();
        }
    }
}
