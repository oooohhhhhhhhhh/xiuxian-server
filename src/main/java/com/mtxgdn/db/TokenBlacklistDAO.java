package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 吊销名单（{@code token_blacklist} 表）的读写入口。
 *
 * <p>从 {@code TokenBlacklist} 抽出来的 DAO。每个意图一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 </ul>
 *
 * <p><b>这个 DAO 刻意"不抛异常"</b>：它是认证链路，登出与令牌吊销
 * <b>不能因为数据库不可用而失败</b>。原实现在每个 catch 里只往
 * {@code System.err} 打一行告警然后降级（写失败 → 仅内存生效；
 * 读失败 → 保守放行）。所以自取连接的重载也是吞掉异常后降级，
 * 而不是包成 {@link RuntimeException} 往上抛 —— 抛出去会让玩家
 * 无法登出，或让一次鉴权因为数据库抖动而误判成"token 有效"。</p>
 *
 * <p><b>Connection 版则是另一种契约</b>：它们直接抛
 * {@link SQLException}，由调用方决定怎么降级。原实现里没有"事务内
 * 吊销 token"的调用方，但这套重载仍然必须提供 —— 登出流程将来若要
 * 和"删会话记录"放进同一个事务，没有 Connection 重载就只能另开连接，
 * SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>双方言的 INSERT</b>：SQLite 用
 *       {@code ON CONFLICT(token_hash) DO NOTHING}，
 *       MySQL 用 {@code INSERT IGNORE}。同一个 token 重复吊销
 *       （双击登出、多端同时登出）不能变成报错，所以是"插入或无视"，
 *       逐字保留。</li>
 *   <li><b>只存 SHA-256 摘要，不存明文 token</b>：哈希由调用方算好传进来，
 *       DAO 不做哈希、也不知道明文。</li>
 *   <li><b>清理按 {@code expires_at < ?}</b>：精确到每个 token 的自然过期时间，
 *       不是"早于 N 天"。{@code cleanupExpired} 的
 *       {@code maxAgeMs} 参数只是兼容旧签名，实际清理用它取到的 {@code now}。</li>
 *   <li><b>{@code expires_at > ?} 载入</b>：启动时只把还没过期的记录读进内存，
 *       已过期的直接不读（顺手由清理逻辑删掉）。</li>
 *   <li><b>告警文案逐字保留</b>：四句 {@code [TokenBlacklist] 警告：...}。</li>
 *   <li><b>{@code clearAll} 清内存 + 清库</b>，{@code clear} 只清内存
 *       （测试与调试用）。两者的区别不能合并。</li>
 * </ul>
 */
public class TokenBlacklistDAO {

    /** {@code token_hash → expires_at} 的一行。 */
    public record BlacklistEntry(String tokenHash, long expiresAt) {
    }

    /** 双方言的"插入或无视"：SQLite 用 ON CONFLICT，MySQL 用 INSERT IGNORE。 */
    private static String insertIgnoreSql() {
        if (DatabaseManager.isSqlite()) {
            return "INSERT INTO token_blacklist (token_hash, expires_at) VALUES (?, ?) "
                    + "ON CONFLICT(token_hash) DO NOTHING";
        }
        return "INSERT IGNORE INTO token_blacklist (token_hash, expires_at) VALUES (?, ?)";
    }

    /**
     * 写入一条吊销记录（自取连接版）。
     *
     * <p>失败只告警不抛：DB 不可用不应阻断登出，内存已生效。
     * {@code RuntimeException} 也一并接住 —— {@code DatabaseManager}
     * 未初始化（如单元测试环境）时同样降级为内存模式。</p>
     */
    public void insert(String tokenHash, long expiresAt) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insert(conn, tokenHash, expiresAt);
        } catch (SQLException e) {
            System.err.println("[TokenBlacklist] 警告：吊销记录落库失败，重启后可能失效: " + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("[TokenBlacklist] 警告：数据库未就绪，吊销仅存内存: " + e.getMessage());
        }
    }

    /** 事务内版本：只用调用方连接，异常由调用方处理。 */
    public void insert(Connection conn, String tokenHash, long expiresAt) throws SQLException {
        String sql = insertIgnoreSql();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tokenHash);
            ps.setLong(2, expiresAt);
            ps.executeUpdate();
        }
    }

    /**
     * 按摘要查过期时间（自取连接版）。
     *
     * <p>失败保守放行（返回 {@code null}，即"未吊销"）并告警 ——
     * 不误伤正常用户。</p>
     */
    public Long findExpiresAt(String tokenHash) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findExpiresAt(conn, tokenHash);
        } catch (SQLException | RuntimeException e) {
            System.err.println("[TokenBlacklist] 警告：吊销名单查询失败: " + e.getMessage());
        }
        return null;
    }

    /** 事务内版本：只用调用方连接。 */
    public Long findExpiresAt(Connection conn, String tokenHash) throws SQLException {
        String sql = "SELECT expires_at FROM token_blacklist WHERE token_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("expires_at");
                }
            }
        }
        return null;
    }

    /**
     * 载入全部未过期的吊销记录（自取连接版）。
     *
     * <p>失败返回空列表并告警 —— 启动时载入失败不该让服务起不来。</p>
     */
    public List<BlacklistEntry> findUnexpired(long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findUnexpired(conn, now);
        } catch (SQLException | RuntimeException e) {
            System.err.println("[TokenBlacklist] 警告：载入历史吊销名单失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<BlacklistEntry> findUnexpired(Connection conn, long now) throws SQLException {
        String sql = "SELECT token_hash, expires_at FROM token_blacklist WHERE expires_at > ?";
        List<BlacklistEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new BlacklistEntry(rs.getString("token_hash"), rs.getLong("expires_at")));
                }
            }
        }
        return entries;
    }

    /** 删掉已过期的吊销记录（自取连接版）；失败静默（清理不影响主流程）。 */
    public int deleteExpiredBefore(long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return deleteExpiredBefore(conn, now);
        } catch (SQLException | RuntimeException ignored) {
            return 0;
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public int deleteExpiredBefore(Connection conn, long now) throws SQLException {
        String sql = "DELETE FROM token_blacklist WHERE expires_at < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        }
    }

    /** 清空整表（自取连接版）；失败静默。 */
    public int deleteAll() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return deleteAll(conn);
        } catch (SQLException | RuntimeException ignored) {
            return 0;
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public int deleteAll(Connection conn) throws SQLException {
        String sql = "DELETE FROM token_blacklist";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }
}
