package com.mtxgdn.db;

import com.mtxgdn.onebot.QqBinding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * OneBot 渠道的 QQ 号绑定（{@code qq_bindings} 表）的读写入口。
 *
 * <p>从 {@code QqBindingService} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询QQ绑定失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：{@code QqBindingService} 原来 5 处
 * {@code DatabaseManager.getConnection()}，而它是被 {@code QqAccountFlow}
 * （注册 / 绑定 / 改密 / 注销的整条对话流程）与 {@code QqMessageRouter}
 * 共用的入口。{@code doRegisterComplete} 的失败回滚是
 * "先 {@code unbindByQq} 再 {@code UserAccountDAO.deleteUser}"两步，
 * {@code handleDeleteConfirm} 的注销是"删角色 → 删用户 → 解绑"三步 ——
 * 这些链路都可能在事务里被调用，DAO 若自己另开连接就会把外层事务
 * 劈成多次独立提交。</p>
 *
 * <p><b>与 {@link QqOfficialBindingDAO} 的分界</b>：两边表不同、身份体系不同
 * （真实 QQ 号 vs openid），方法名刻意保持一致以便适配器无侵入切换，
 * 但 <b>不共用代码</b>：{@code qq_bindings} 没有 {@code app_id} 列，
 * 也就没有"限定 AppID / 历史行兜底 / CASE 排序"这三件事。
 * 把两边合并会让 OneBot 渠道凭空多出一堆永远走不到的分支。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>删不动就抛异常</b>：{@code unbindByQq} 影响 0 行时抛
 *       "该QQ号未绑定任何用户"，{@code unbindByUserId} 影响 0 行时抛
 *       "该用户未绑定任何QQ"。调用方（{@code handleUnbind}）靠 catch
 *       给玩家回"解绑失败: ..."，所以异常文案是业务契约；</li>
 *   <li><b>INSERT 不带 IGNORE</b>：{@code bind} 是普通 INSERT。
 *       "该QQ号已绑定用户 / 该用户已绑定QQ号"的拒绝逻辑在服务层
 *       （先查后插），DAO 不去重；</li>
 *   <li><b>取一条就是一条</b>：两个查询都没有 {@code LIMIT 1}（原实现如此），
 *       {@code rs.next()} 取首行。列清单 {@code id, qq_number, user_id, created_at}
 *       逐字保留，映射时同样按列名取。</li>
 * </ul>
 *
 * <p>SQL 逐字搬自原 {@code QqBindingService}，一条没改、没合并。</p>
 */
public class QqBindingDAO {

    // ==================== 查询 ====================

    /** 按 QQ 号查绑定；没有返回 {@code null}。 */
    public QqBinding findByQq(String qqNumber) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByQq(conn, qqNumber);
        } catch (SQLException e) {
            throw new RuntimeException("查询QQ绑定失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public QqBinding findByQq(Connection conn, String qqNumber) throws SQLException {
        String sql = "SELECT id, qq_number, user_id, created_at FROM qq_bindings WHERE qq_number = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, qqNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapBinding(rs);
                }
            }
        }
        return null;
    }

    /** 按用户查绑定；没有返回 {@code null}。 */
    public QqBinding findByUserId(Long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByUserId(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("查询用户绑定失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public QqBinding findByUserId(Connection conn, Long userId) throws SQLException {
        String sql = "SELECT id, qq_number, user_id, created_at FROM qq_bindings WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapBinding(rs);
                }
            }
        }
        return null;
    }

    // ==================== 写操作 ====================

    /**
     * 插入一条绑定。
     *
     * <p>普通 INSERT，<b>不</b>加 {@code IGNORE} / {@code ON CONFLICT}：
     * 去重是服务层"先查后插"的职责，DAO 层去重会把重复绑定变成静默成功。</p>
     */
    public void insertBinding(String qqNumber, Long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertBinding(conn, qqNumber, userId);
        } catch (SQLException e) {
            throw new RuntimeException("绑定QQ失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public void insertBinding(Connection conn, String qqNumber, Long userId) throws SQLException {
        String sql = "INSERT INTO qq_bindings (qq_number, user_id) VALUES (?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, qqNumber);
            stmt.setLong(2, userId);
            stmt.executeUpdate();
        }
    }

    /**
     * 按 QQ 号解绑。
     *
     * @return 实际删除的行数；0 表示该 QQ 号本来就没绑定（调用方据此报错）
     */
    public int deleteByQq(String qqNumber) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return deleteByQq(conn, qqNumber);
        } catch (SQLException e) {
            throw new RuntimeException("解绑QQ失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public int deleteByQq(Connection conn, String qqNumber) throws SQLException {
        String sql = "DELETE FROM qq_bindings WHERE qq_number = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, qqNumber);
            return stmt.executeUpdate();
        }
    }

    /**
     * 按用户解绑。
     *
     * @return 实际删除的行数；0 表示该用户本来就没绑定（调用方据此报错）
     */
    public int deleteByUserId(Long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return deleteByUserId(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("解绑用户失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public int deleteByUserId(Connection conn, Long userId) throws SQLException {
        String sql = "DELETE FROM qq_bindings WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            return stmt.executeUpdate();
        }
    }

    // ==================== ResultSet → 实体 ====================

    /**
     * 按列名取四列。
     *
     * <p>原实现的映射是内联在 {@code findByQq} / {@code findByUserId} 里的同一段代码，
     * 两处逐字相同。抽成私有方法不改变取值顺序与列名，也不新增
     * {@code appId} 的读取 —— {@code qq_bindings} 没有这一列，
     * 官方通道的历史行判定只属于 {@code QqOfficialBindingDAO}。</p>
     */
    private QqBinding mapBinding(ResultSet rs) throws SQLException {
        QqBinding binding = new QqBinding();
        binding.setId(rs.getLong("id"));
        binding.setQqNumber(rs.getString("qq_number"));
        binding.setUserId(rs.getLong("user_id"));
        binding.setCreatedAt(rs.getString("created_at"));
        return binding;
    }
}
