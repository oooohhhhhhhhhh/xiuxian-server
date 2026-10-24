package com.mtxgdn.db;

import com.mtxgdn.onebot.QqBinding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * QQ 官方开放平台绑定（{@code qq_official_bindings} 表）的读写入口。
 *
 * <p>从 {@code QqOfficialBindingService} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询QQ官方绑定失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：{@code QqOfficialBindingService} 原来 7 处
 * {@code DatabaseManager.getConnection()}，其中 {@code findByQq}/{@code findByUserId}
 * 的不限 AppID 路径与限定 AppID 路径是<b>同一意图的两种写法</b>（前者是后者的
 * 退化形态）。抽出来之后，服务层的 {@code bind} 会先查 openid 再查 user_id，
 * 两个查询之间没有任何共享状态，但它们都在"绑定前校验"这个业务步骤里；
 * 更关键的是 {@code QqAccountFlow} 的注册回滚（先 {@code unbindByQq} 再
 * {@code UserAccountDAO.deleteUser}）以及 {@code deleteUser} 的注销链路都可能在
 * 事务内调用，因此必须保留 Connection 版，让调用方能在同一条连接上走完多步。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>AppID 隔离与历史兜底</b>：限定 AppID 时用
 *       {@code WHERE openid = ? AND (app_id = ? OR app_id = '')} 加上
 *       {@code ORDER BY CASE WHEN app_id = ? THEN 0 ELSE 1 END}，
 *       让"本机器人的绑定"排在"升级前的历史行（app_id 为空）"之前。
 *       升级前写入的行 app_id 是空字符串，必须能被兜底命中，否则老玩家升级后会被登出；</li>
 *   <li><b>不限 AppID 的两条退化 SQL</b>：{@code WHERE openid = ? LIMIT 1} 与
 *       {@code WHERE user_id = ? LIMIT 1} 是不限 AppID 时的专用语句，
 *       和限定 AppID 的语句<b>不是同一条</b>（没有 {@code app_id} 条件、
 *       没有 CASE 排序），必须分开保留；</li>
 *   <li><b>解绑的两级退化</b>：{@code unbindByQq} 先按
 *       {@code openid = ? AND (app_id = ? OR app_id = '')} 删本机器人，
 *       影响行数为 0 且限定了 AppID 时，退回按 {@code openid = ?} 全量删除。
 *       两步用的是<b>同一条连接</b>（原实现里 fallback 复用外层 conn），
 *       因此 Connection 重载里必须能拿到调用方那条连接；</li>
 *   <li><b>删不动的行要报错</b>：{@code unbindByQq} 两级删除都影响 0 行时抛
 *       "该 openid 未绑定任何用户"，{@code unbindByUserId} 影响 0 行时抛
 *       "该用户未绑定任何QQ官方 openid"。这两个异常是业务契约
 *       （调用方据此给玩家回"解绑失败: ..."），必须原样保留；</li>
 *   <li><b>INSERT 不带 IGNORE</b>：{@code bind} 用的是一条普通 INSERT。
 *       "openid 已绑定 / 用户已绑定"的拒绝逻辑在服务层（查完再插），
 *       DAO 不做去重，也不加 {@code INSERT IGNORE} —— 那会让重复绑定静默成功。</li>
 * </ul>
 *
 * <p>SQL 逐字搬自原 {@code QqOfficialBindingService}，一条没改、没合并。</p>
 */
public class QqOfficialBindingDAO {

    // ==================== 查询 ====================

    /**
     * 按 openid 查绑定，不限 AppID（跨机器人展示用途）。
     *
     * @return 命中返回绑定；没有返回 {@code null}
     */
    public QqBinding findByQqAnyApp(String openid) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByQqAnyApp(conn, openid);
        } catch (SQLException e) {
            throw new RuntimeException("查询QQ官方绑定失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public QqBinding findByQqAnyApp(Connection conn, String openid) throws SQLException {
        String sql = "SELECT id, app_id, openid, user_id, created_at FROM qq_official_bindings "
                + "WHERE openid = ? LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, openid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapBinding(rs) : null;
            }
        }
    }

    /**
     * 按 openid 查绑定，限定 AppID：优先本机器人，其次回落到历史行（app_id 为空）。
     *
     * @param appId 机器人 AppID（限定后不再接受空值）
     */
    public QqBinding findByQqForApp(String openid, String appId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByQqForApp(conn, openid, appId);
        } catch (SQLException e) {
            throw new RuntimeException("查询QQ官方绑定失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public QqBinding findByQqForApp(Connection conn, String openid, String appId) throws SQLException {
        String sql = "SELECT id, app_id, openid, user_id, created_at FROM qq_official_bindings "
                + "WHERE openid = ? AND (app_id = ? OR app_id = '') "
                + "ORDER BY CASE WHEN app_id = ? THEN 0 ELSE 1 END LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, openid);
            stmt.setString(2, appId);
            stmt.setString(3, appId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapBinding(rs) : null;
            }
        }
    }

    /** 按用户查绑定，不限 AppID（只取一条）。 */
    public QqBinding findByUserIdAnyApp(Long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByUserIdAnyApp(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("查询QQ官方绑定失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public QqBinding findByUserIdAnyApp(Connection conn, Long userId) throws SQLException {
        String sql = "SELECT id, app_id, openid, user_id, created_at FROM qq_official_bindings "
                + "WHERE user_id = ? LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapBinding(rs) : null;
            }
        }
    }

    /** 按用户查绑定，限定 AppID：优先本机器人，其次回落到历史行。 */
    public QqBinding findByUserIdForApp(Long userId, String appId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByUserIdForApp(conn, userId, appId);
        } catch (SQLException e) {
            throw new RuntimeException("查询用户QQ官方绑定失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public QqBinding findByUserIdForApp(Connection conn, Long userId, String appId) throws SQLException {
        String sql = "SELECT id, app_id, openid, user_id, created_at FROM qq_official_bindings "
                + "WHERE user_id = ? AND (app_id = ? OR app_id = '') "
                + "ORDER BY CASE WHEN app_id = ? THEN 0 ELSE 1 END LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setString(2, appId);
            stmt.setString(3, appId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapBinding(rs) : null;
            }
        }
    }

    // ==================== 写操作 ====================

    /**
     * 插入一条绑定。
     *
     * <p>普通 INSERT，<b>不</b>加 {@code IGNORE} / {@code ON CONFLICT}：
     * "openid 已绑定 / 用户已绑定"的拒绝由服务层先查后插完成，
     * DAO 层去重会把重复绑定变成静默成功。</p>
     */
    public void insertBinding(String appId, String openid, Long userId) {
        String sql = "INSERT INTO qq_official_bindings (app_id, openid, user_id) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, appId);
            stmt.setString(2, openid);
            stmt.setLong(3, userId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("绑定QQ官方失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public void insertBinding(Connection conn, String appId, String openid, Long userId) throws SQLException {
        String sql = "INSERT INTO qq_official_bindings (app_id, openid, user_id) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, appId);
            stmt.setString(2, openid);
            stmt.setLong(3, userId);
            stmt.executeUpdate();
        }
    }

    /**
     * 按 openid 解绑：先删本机器人（含历史兼容行），不存在则退化为全量删除。
     *
     * <p><b>两级删除必须共用同一条连接</b>：原实现的 fallback 语句复用外层
     * {@code conn}。拆开后如果让两级各自自取连接，第一次删除未提交时第二次
     * 可能看不到刚删掉的行（SQLite 下更会直接 {@code SQLITE_BUSY}），
     * 于是"明明删掉了"却继续走 fallback，最终抛"该 openid 未绑定任何用户"。</p>
     *
     * @param appId 机器人 AppID；为空表示不限 AppID（只走全量删除）
     * @return 实际删除的行数
     */
    public int deleteByQq(String openid, String appId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return deleteByQq(conn, openid, appId);
        } catch (SQLException e) {
            throw new RuntimeException("解绑QQ官方失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。两级删除与 fallback 都在这条连接上完成。 */
    public int deleteByQq(Connection conn, String openid, String appId) throws SQLException {
        boolean scoped = appId != null && !appId.isEmpty();
        int affected;
        if (scoped) {
            String sql = "DELETE FROM qq_official_bindings WHERE openid = ? AND (app_id = ? OR app_id = '')";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, openid);
                stmt.setString(2, appId);
                affected = stmt.executeUpdate();
            }
            if (affected == 0) {
                // 本机器人没有绑定记录时，退回按 openid 全量删除（用户视角：解绑这个 QQ）
                try (PreparedStatement fallback = conn.prepareStatement(
                        "DELETE FROM qq_official_bindings WHERE openid = ?")) {
                    fallback.setString(1, openid);
                    affected = fallback.executeUpdate();
                }
            }
        } else {
            String sql = "DELETE FROM qq_official_bindings WHERE openid = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, openid);
                affected = stmt.executeUpdate();
            }
        }
        return affected;
    }

    /**
     * 按用户解绑全部机器人的绑定（管理员/注销账号场景）。
     *
     * @return 实际删除的行数；0 表示该用户本来就没有绑定
     */
    public int deleteByUserId(Long userId) {
        String sql = "DELETE FROM qq_official_bindings WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("解绑用户QQ官方失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public int deleteByUserId(Connection conn, Long userId) throws SQLException {
        String sql = "DELETE FROM qq_official_bindings WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            return stmt.executeUpdate();
        }
    }

    // ==================== ResultSet → 实体 ====================

    /**
     * 复用 {@link QqBinding} 实体承载 openid：{@code qqNumber} 字段存的是 openid 字符串。
     *
     * <p>这是原实现的写法（QQ 官方平台下发 openid 而不是真实 QQ 号），
     * 换成一个新实体类会改动服务层所有 {@code getQqNumber()} 调用点。</p>
     */
    private QqBinding mapBinding(ResultSet rs) throws SQLException {
        QqBinding binding = new QqBinding();
        binding.setId(rs.getLong("id"));
        binding.setQqNumber(rs.getString("openid"));
        binding.setUserId(rs.getLong("user_id"));
        binding.setCreatedAt(rs.getString("created_at"));
        binding.setAppId(rs.getString("app_id"));
        return binding;
    }
}
