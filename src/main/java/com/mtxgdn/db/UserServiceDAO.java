package com.mtxgdn.db;

import com.mtxgdn.entity.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * {@code users} 表上「账号中心」用的全部读写：注册查重、按用户名/邮箱取账号、
 * 插入账号、改密与重置密码、登录失败计数与锁定、改邮箱、注销。
 *
 * <p><b>为什么要有这个类</b>：这些 SQL 原来散在
 * {@code com.mtxgdn.service.UserService} 里，一共 13 处
 * {@code DatabaseManager.getConnection()}，业务方法里混着
 * {@code PreparedStatement} / {@code ResultSet} / {@code SQLException}。
 * 后果不是"文件长了 490 行"，而是：</p>
 * <ul>
 *   <li>这是<b>安全代码</b>：注册查重、BCrypt 校验、失败计数与锁定、改密，
 *       全都写在同一份文件里，与 {@code PreparedStatement} 的参数绑定逐行交织。
 *       review 时无法区分"只是搬了语句"与"放宽了某个判定"；</li>
 *   <li>"哪些语句在事务里、哪些不在"看不出来：{@code changePassword} 与
 *       {@code resetPasswordByEmail} 都是"先读后写"，读与写用的是同一条短连接，
 *       但<b>没有</b>事务 —— 这种细节只能靠人眼从 try-with-resources 的范围里读出来；</li>
 *   <li>同一张表被两个类各自读写（另一个是 {@link UserAccountDAO}），
 *       列清单与失败文案各维护一份。</li>
 * </ul>
 *
 * <p><b>切法</b>：按<b>业务意图</b>切，不按"一条 SQL 一个方法"切。
 * 例如"按用户名取账号"有两个方法（{@link #findByUsername} 与
 * {@link #findByUsernameWithLockInfo}）：它们选<b>不同的列</b>
 * （后者多读 {@code failed_attempts} / {@code locked_until}），
 * 属于两个不同的意图（认证 / 登录并判锁），合并等于改 SQL。
 * 反过来，"改密"与"邮箱重置密码"虽然都写 {@code password}，
 * 但后者的 SQL 里还带 {@code failed_attempts = 0, locked_until = NULL}（重置必须解锁），
 * 因此也是两个方法。</p>
 *
 * <p><b>与 {@link UserAccountDAO} 的分工（为什么不复用）</b>：那个类是 QQ 侧
 * （OneBot / QQ 官方机器人）共用的账号读写，方法全是 {@code static}，
 * 表面上与本类有几条重叠的 SQL，但<b>没有一条能直接复用</b>，
 * 因为它的契约与本类调用点需要的契约不同：</p>
 * <ul>
 *   <li>{@code UserAccountDAO.isUsernameExists} 查询失败时<b>吞掉异常</b>返回
 *       {@code false}；注册流程要的是把失败抛成 {@code RuntimeException("数据库查询失败")}
 *       —— 吞掉会让"库挂了"变成"用户名可用"，然后走到 INSERT 才炸，错误码与文案都变；</li>
 *   <li>{@code UserAccountDAO.insertUser} 只返回自增主键 {@code Long}，
 *       而注册成功要立刻拿 {@code id + username + email} 去发角色与 JWT；</li>
 *   <li>{@code UserAccountDAO.deleteUser} 是 {@code void}，而注销要把
 *       "0 行受影响"翻译成 404；{@code UserAccountDAO.updatePassword} 在 0 行时
 *       主动抛 {@code RuntimeException("用户不存在")}，而本类的调用点先查过存在性、
 *       不检查行数 —— 换成它等于凭空多出一个失败分支；</li>
 *   <li>更要紧的是：它<b>没有</b> {@code Connection} 首参重载。事务内调用它必然另开
 *       一条连接（SQLite 下 {@code SQLITE_BUSY_SNAPSHOT} / 池子借空 10 秒超时）。</li>
 * </ul>
 * <p>因此本类与它<b>各自保留</b>自己那份语句，而不是"为了少写 4 条 SQL 把两种契约
 * 折中成一种"。真要合并，前提是先把两个通道的失败语义统一 —— 那是一次行为变更，
 * 不属于本次纯结构重构。</p>
 *
 * <p><b>连接与事务</b>：每个方法都有一对重载 ——</p>
 * <ul>
 *   <li>{@code xxx(...)}：自己从 {@link DatabaseManager} 取一个短连接，用完即关，
 *       失败按<b>原实现的文案</b>包成 {@link RuntimeException}；</li>
 *   <li>{@code xxx(Connection conn, ...)}：<b>只用调用方传进来的连接</b>，
 *       绝不自己开连接、也绝不碰 {@code setAutoCommit}，失败直接抛
 *       {@link SQLException} 交给调用方。事务内必须走这一版：SQLite 的
 *       {@code BEGIN DEFERRED} 事务里若先读后写、期间又用<b>另一个连接</b>写同一张表，
 *       会直接抛 {@code SQLITE_BUSY_SNAPSHOT}（该错误不受 {@code busy_timeout} 约束），
 *       或者读到旧快照。</li>
 * </ul>
 * <p>重构前后<b>没有新增任何事务</b>：原实现里这几条语句全部跑在 autocommit 下，
 * 每条语句自己就是一个提交单元；所以"先 SELECT 再 UPDATE"的两次调用即使各用一条
 * 短连接，事务边界也与原来完全一致（原来也没有把这两条语句包在一个事务里）。</p>
 *
 * <p><b>SQL 逐字保留</b>：表名、列名、条件、{@code COALESCE} / {@code CASE} 表达式、
 * 参数顺序全部原样搬过来，一条没改、没合并、没"优化"。
 * 回归测试：{@code UserServiceDaoExtractionTest}（含 SQL 守恒比对）。</p>
 */
public class UserServiceDAO {

    // ==================== 结果载体 ====================

    /**
     * {@code SELECT id, password FROM users WHERE id = ?} 的一行（改密前定位账号用）。
     *
     * <p>为什么单独一个方法，而不是复用 {@link #findPasswordHashById}：那一条是
     * {@code SELECT password ...}，列清单不同。本次重构一条 SQL 都不许改，
     * 所以两种读法各留一个方法。</p>
     */
    public record CredentialRow(long id, String hashedPassword) {
    }

    /** {@code SELECT id, username FROM users WHERE email = ?} 的一行（邮箱重置密码用）。 */
    public record EmailOwnerRow(long id, String username) {
    }

    // ==================== 1. 注册查重 ====================

    /**
     * 用户名是否已被占用。
     *
     * <p>查询失败<b>抛出</b> {@link RuntimeException}（文案与重构前一致）：
     * 注册流程必须把"查不了"和"没被占用"分开，否则库故障会被当成"可以注册"。</p>
     */
    public boolean existsByUsername(String username) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return existsByUsername(conn, username);
        } catch (SQLException e) {
            throw new RuntimeException("数据库查询失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean existsByUsername(Connection conn, String username) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    /** 邮箱是否已被占用。失败语义同 {@link #existsByUsername}。 */
    public boolean existsByEmail(String email) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return existsByEmail(conn, email);
        } catch (SQLException e) {
            throw new RuntimeException("数据库查询失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean existsByEmail(Connection conn, String email) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE email = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    // ==================== 2. 按用户名 / 邮箱取账号 ====================

    /**
     * 按用户名取账号（只带 {@code id / username / password}）；不存在返回 {@code null}。
     *
     * <p>用于"认证"这条路径：不读失败计数，因此不会把锁定信息带出来。</p>
     */
    public User findByUsername(String username) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByUsername(conn, username);
        } catch (SQLException e) {
            throw new RuntimeException("数据库查询失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public User findByUsername(Connection conn, String username) throws SQLException {
        String sql = "SELECT id, username, password FROM users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    User user = new User();
                    user.setId(rs.getLong("id"));
                    user.setUsername(rs.getString("username"));
                    user.setPassword(rs.getString("password"));
                    return user;
                }
            }
        }
        return null;
    }

    /**
     * 按用户名取账号，并带上登录锁定所需的 {@code failed_attempts} / {@code locked_until}；
     * 不存在返回 {@code null}。
     *
     * <p>{@code locked_until} 可能是 {@code NULL}（从未被锁定），
     * {@code setLockedUntil(null)} 就是"未锁定"，判定留给调用方。</p>
     */
    public User findByUsernameWithLockInfo(String username) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByUsernameWithLockInfo(conn, username);
        } catch (SQLException e) {
            throw new RuntimeException("数据库查询失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public User findByUsernameWithLockInfo(Connection conn, String username) throws SQLException {
        String sql = "SELECT id, username, password, failed_attempts, locked_until FROM users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    User user = new User();
                    user.setId(rs.getLong("id"));
                    user.setUsername(rs.getString("username"));
                    user.setPassword(rs.getString("password"));
                    user.setFailedAttempts(rs.getInt("failed_attempts"));
                    user.setLockedUntil(rs.getTimestamp("locked_until"));
                    return user;
                }
            }
        }
        return null;
    }

    /** 按账号 id 取邮箱；账号不存在或邮箱为 {@code NULL} 时返回 {@code null}。 */
    public String findEmailById(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findEmailById(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("查询邮箱失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public String findEmailById(Connection conn, long userId) throws SQLException {
        String sql = "SELECT email FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("email");
                }
            }
        }
        return null;
    }

    /**
     * 按账号 id 取"密码哈希"这一列（二次确认密码用：不累加失败计数、不触发锁定）。
     *
     * <p>返回 {@code null} 表示账号不存在或该列为 {@code NULL}；
     * BCrypt 比对属于安全语义，留在调用方。</p>
     */
    public String findPasswordHashById(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findPasswordHashById(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("校验密码失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public String findPasswordHashById(Connection conn, long userId) throws SQLException {
        String sql = "SELECT password FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("password");
                }
            }
        }
        return null;
    }

    /**
     * 按账号 id 取 {@code id + password}（改密前"账号存在吗 + 原密码对不对"用）。
     *
     * <p>不存在时返回 {@code null}（调用方据此回 404）。</p>
     */
    public CredentialRow findCredentialById(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findCredentialById(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("修改密码失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public CredentialRow findCredentialById(Connection conn, long userId) throws SQLException {
        String sql = "SELECT id, password FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new CredentialRow(rs.getLong("id"), rs.getString("password"));
                }
            }
        }
        return null;
    }

    /** 按邮箱取账号的 {@code id + username}（邮箱重置密码用）；没有这个邮箱返回 {@code null}。 */
    public EmailOwnerRow findIdAndUsernameByEmail(String email) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findIdAndUsernameByEmail(conn, email);
        } catch (SQLException e) {
            throw new RuntimeException("重置密码失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public EmailOwnerRow findIdAndUsernameByEmail(Connection conn, String email) throws SQLException {
        String sql = "SELECT id, username FROM users WHERE email = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new EmailOwnerRow(rs.getLong("id"), rs.getString("username"));
                }
            }
        }
        return null;
    }

    // ==================== 3. 写入：插入账号 ====================

    /**
     * 插入一行账号，返回带自增主键的 {@link User}；<b>一行都没插入时返回 {@code null}</b>。
     *
     * <p>返回 {@code null} 是注册流程的既有契约（调用方据此回 500「注册失败，请稍后重试」），
     * 不是异常路径 —— 所以这里不能改抛异常。同理，SQL 失败抛的文案仍是"用户插入失败"。</p>
     *
     * @param hashedPassword 必须是<b>已经哈希过</b>的值：本类不做哈希，
     *                       免得哪天有人把明文传进来
     */
    public User insertUser(String username, String hashedPassword, String email) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return insertUser(conn, username, hashedPassword, email);
        } catch (SQLException e) {
            throw new RuntimeException("用户插入失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接，返回自增主键填好的 {@link User}（取不到时 {@code null}）。 */
    public User insertUser(Connection conn, String username, String hashedPassword, String email) throws SQLException {
        String sql = "INSERT INTO users (username, password, email) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, hashedPassword);
            ps.setString(3, email);
            int affected = ps.executeUpdate();
            if (affected > 0) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        User user = new User();
                        user.setId(rs.getLong(1));
                        user.setUsername(username);
                        user.setEmail(email);
                        return user;
                    }
                }
            }
        }
        return null;
    }

    // ==================== 4. 写入：密码 / 邮箱 / 登录失败计数 ====================

    /** 改密：只写 {@code password}（账号存在性由调用方先判过）。 */
    public void updatePasswordHash(long userId, String hashedPassword) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updatePasswordHash(conn, userId, hashedPassword);
        } catch (SQLException e) {
            throw new RuntimeException("修改密码失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updatePasswordHash(Connection conn, long userId, String hashedPassword) throws SQLException {
        String sql = "UPDATE users SET password = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hashedPassword);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    /**
     * 邮箱重置密码：写新密码<b>并同时解锁</b>（{@code failed_attempts = 0,
     * locked_until = NULL}）—— 忘记密码的人往往正是刚被锁的人，
     * 只改密码不解锁等于重置完还是进不去。
     */
    public void resetPassword(long userId, String hashedPassword) {
        try (Connection conn = DatabaseManager.getConnection()) {
            resetPassword(conn, userId, hashedPassword);
        } catch (SQLException e) {
            throw new RuntimeException("重置密码失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void resetPassword(Connection conn, long userId, String hashedPassword) throws SQLException {
        String sql = "UPDATE users SET password = ?, failed_attempts = 0, locked_until = NULL WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hashedPassword);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    /**
     * 记一次登录失败：计数 +1，达到 {@code maxAttempts} 时把 {@code locked_until} 设成
     * {@code lockedUntil}。
     *
     * <p>计数必须在 SQL 里做加法（{@code failed_attempts = failed_attempts + 1}）：
     * 读内存值再写绝对值，会在两条通道同时输错密码时丢掉一次计数，
     * 让暴力破解实际可用的尝试次数比策略多。
     * {@code COALESCE(..., locked_until)} 保证"未达阈值时不动原有的锁定时刻"。</p>
     *
     * <p>阈值与锁定时长<b>不在本类</b>：那是认证策略，由调用方算好后传进来
     * （{@code maxAttempts} 与已经加好时长的 {@code lockedUntil}）。</p>
     */
    public void recordFailedAttempt(long userId, int maxAttempts, Timestamp lockedUntil) {
        try (Connection conn = DatabaseManager.getConnection()) {
            recordFailedAttempt(conn, userId, maxAttempts, lockedUntil);
        } catch (SQLException e) {
            throw new RuntimeException("更新失败次数失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void recordFailedAttempt(Connection conn, long userId, int maxAttempts, Timestamp lockedUntil)
            throws SQLException {
        String sql = "UPDATE users SET failed_attempts = failed_attempts + 1, " +
                "locked_until = COALESCE(" +
                "CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE NULL END, " +
                "locked_until) " +
                "WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maxAttempts);
            ps.setTimestamp(2, lockedUntil);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    /** 登录成功：清零失败计数并解除锁定。 */
    public void clearFailedAttempts(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            clearFailedAttempts(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("重置失败次数失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void clearFailedAttempts(Connection conn, long userId) throws SQLException {
        String sql = "UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    /**
     * 设置（或清空）账号邮箱。
     *
     * <p>两条语句的差别是"要不要真的写一个值"：{@code email == null} 时把列置
     * {@code NULL}，否则写入新值。两者都带 {@code AND (email IS NULL OR email = '' ...)}
     * —— 这个条件是<b>业务规则的一部分</b>（只允许在"原本没有邮箱"时补填，
     * 或者用同一个邮箱覆盖自己），因此它跟着 SQL 一起留在本类，
     * 而不是被拆成"先查再判断再写"（那会变成两条语句、多一个竞态窗口）。</p>
     */
    public void updateEmail(long userId, String email) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateEmail(conn, userId, email);
        } catch (SQLException e) {
            throw new RuntimeException("更新邮箱失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateEmail(Connection conn, long userId, String email) throws SQLException {
        String sql;
        if (email == null) {
            sql = "UPDATE users SET email = NULL WHERE id = ? AND (email IS NULL OR email = '')";
        } else {
            sql = "UPDATE users SET email = ? WHERE id = ? AND (email IS NULL OR email = '' OR email = ?)";
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (email != null) {
                ps.setString(idx++, email);
            }
            ps.setLong(idx++, userId);
            if (email != null) {
                ps.setString(idx, email);
            }
            ps.executeUpdate();
        }
    }

    // ==================== 5. 写入：注销 ====================

    /**
     * 删除一行账号，返回是否真的删掉了（账号不存在时 {@code false}，调用方据此回 404）。
     *
     * <p>注意与 {@link UserAccountDAO#deleteUser(long)} 的区别：那个是 {@code void} 且不报行数，
     * 满足不了"注销要能区分 404"这条契约，因此不复用（理由见类注释）。</p>
     */
    public boolean deleteUserById(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return deleteUserById(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("注销账户失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean deleteUserById(Connection conn, long userId) throws SQLException {
        String sql = "DELETE FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            return ps.executeUpdate() > 0;
        }
    }
}
