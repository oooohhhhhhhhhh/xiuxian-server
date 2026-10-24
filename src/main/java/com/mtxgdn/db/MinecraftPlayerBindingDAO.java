package com.mtxgdn.db;

import com.mtxgdn.minecraft.adapter.MinecraftPlayerBinding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MC 账号与游戏账号的绑定关系（{@code mc_bindings} 表）的读写入口。
 *
 * <p>从 {@code MinecraftPlayerBindingService} 抽出来的 DAO。每个意图一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，把 {@link SQLException} 包成
 *       {@link RuntimeException}；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：{@code MinecraftAuthService.doRegisterComplete}
 * 是"插用户 → 分配默认角色 → 绑定 MC → 建角色"的一条链，失败回滚是
 * "先 {@code unbindByMcUuid} 再 {@code deleteUser}"两步。这条链在适配器里
 * 可能被放进事务，DAO 若自己另开连接就会把外层事务劈成多次独立提交 ——
 * 半截账号会留在库里，而 MC 侧已经显示"注册成功"。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>一个 MC 账号对一个游戏账号的互斥约束在服务层</b>：
 *       {@code checkDuplicateBinding} 是先查后插的去重（按 mc_uuid 查一次、
 *       按 user_id 查一次），拒绝逻辑的异常文案"该MC玩家已绑定用户: " /
 *       "该用户已绑定MC玩家: "是业务契约，所以它<b>留在服务层</b>，
 *       DAO 里的 {@code insertBinding} 是普通 INSERT，不加
 *       {@code IGNORE} / {@code ON CONFLICT} —— DAO 层去重会把重复绑定
 *       变成静默成功，玩家看到"绑定成功"但实际没绑上。</li>
 *   <li><b>三个查询都没有 {@code LIMIT 1}</b>：原实现如此，靠
 *       {@code rs.next()} 取首行。加 {@code LIMIT 1} 会改变语义
 *       （同一 mc_uuid 出现多行时的行为）。</li>
 *   <li><b>列清单逐字保留</b>：{@code id, mc_uuid, mc_name, user_id, created_at}，
 *       映射时按列名取，不用列序号。</li>
 *   <li><b>删不动就抛异常</b>：{@code deleteByMcUuid} 影响 0 行时抛
 *       "该MC玩家未绑定任何用户"（自取连接版抛 RuntimeException，
 *       Connection 版返回行数由调用方判定）。调用方
 *       （{@code MinecraftAuthService.handleUnbind}）靠 catch 给玩家回
 *       "解绑失败: ..."，所以这条异常文案是业务契约。</li>
 *   <li><b>异常文案逐字保留</b>："绑定MC失败" / "解绑MC失败" /
 *       "更新MC昵称失败" / "查询MC绑定失败"。</li>
 * </ul>
 *
 * <p>SQL 逐字搬自原 {@code MinecraftPlayerBindingService}，一条没改、没合并。</p>
 */
public class MinecraftPlayerBindingDAO {

    /** 按 MC UUID 查绑定；没有返回 {@code null}。 */
    public MinecraftPlayerBinding findByMcUuid(String mcUuid) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByMcUuid(conn, mcUuid);
        } catch (SQLException e) {
            throw new RuntimeException("查询MC绑定失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public MinecraftPlayerBinding findByMcUuid(Connection conn, String mcUuid) throws SQLException {
        String sql = "SELECT id, mc_uuid, mc_name, user_id, created_at FROM mc_bindings WHERE mc_uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, mcUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapBinding(rs);
                }
            }
        }
        return null;
    }

    /** 按 MC 昵称查绑定；没有返回 {@code null}。 */
    public MinecraftPlayerBinding findByMcName(String mcName) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByMcName(conn, mcName);
        } catch (SQLException e) {
            throw new RuntimeException("查询MC绑定失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public MinecraftPlayerBinding findByMcName(Connection conn, String mcName) throws SQLException {
        String sql = "SELECT id, mc_uuid, mc_name, user_id, created_at FROM mc_bindings WHERE mc_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, mcName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapBinding(rs);
                }
            }
        }
        return null;
    }

    /** 按游戏账号 ID 查绑定；没有返回 {@code null}。 */
    public MinecraftPlayerBinding findByUserId(Long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findByUserId(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("查询MC绑定失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public MinecraftPlayerBinding findByUserId(Connection conn, Long userId) throws SQLException {
        String sql = "SELECT id, mc_uuid, mc_name, user_id, created_at FROM mc_bindings WHERE user_id = ?";
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
     * "一个 MC 账号对一个游戏账号"的互斥是服务层先查后插的职责，
     * DAO 层去重会把重复绑定变成静默成功。</p>
     */
    public void insertBinding(String mcUuid, String mcName, Long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertBinding(conn, mcUuid, mcName, userId);
        } catch (SQLException e) {
            throw new RuntimeException("绑定MC失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public void insertBinding(Connection conn, String mcUuid, String mcName, Long userId) throws SQLException {
        String sql = "INSERT INTO mc_bindings (mc_uuid, mc_name, user_id) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, mcUuid);
            stmt.setString(2, mcName);
            stmt.setLong(3, userId);
            stmt.executeUpdate();
        }
    }

    /**
     * 按 MC UUID 删除绑定。
     *
     * @return 实际删除的行数；0 表示该 MC 玩家本来就没绑定
     *         （自取连接版据此抛 "该MC玩家未绑定任何用户"）
     */
    public int deleteByMcUuid(String mcUuid) {
        try (Connection conn = DatabaseManager.getConnection()) {
            int affected = deleteByMcUuid(conn, mcUuid);
            if (affected == 0) {
                throw new RuntimeException("该MC玩家未绑定任何用户");
            }
            return affected;
        } catch (SQLException e) {
            throw new RuntimeException("解绑MC失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。0 行由调用方判定。 */
    public int deleteByMcUuid(Connection conn, String mcUuid) throws SQLException {
        String sql = "DELETE FROM mc_bindings WHERE mc_uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, mcUuid);
            return stmt.executeUpdate();
        }
    }

    /** 改 MC 昵称（玩家在 MC 侧改了名之后同步）。 */
    public void updateMcName(String mcUuid, String newName) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateMcName(conn, mcUuid, newName);
        } catch (SQLException e) {
            throw new RuntimeException("更新MC昵称失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public void updateMcName(Connection conn, String mcUuid, String newName) throws SQLException {
        String sql = "UPDATE mc_bindings SET mc_name = ? WHERE mc_uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newName);
            stmt.setString(2, mcUuid);
            stmt.executeUpdate();
        }
    }

    // ==================== ResultSet → 实体 ====================

    /**
     * 按列名取五列。
     *
     * <p>原实现的映射是内联在 {@code queryBinding} 里的同一段代码，
     * 三个查询共用。抽成私有方法不改变取值顺序与列名。</p>
     */
    private MinecraftPlayerBinding mapBinding(ResultSet rs) throws SQLException {
        MinecraftPlayerBinding binding = new MinecraftPlayerBinding();
        binding.setId(rs.getLong("id"));
        binding.setMcUuid(rs.getString("mc_uuid"));
        binding.setMcName(rs.getString("mc_name"));
        binding.setUserId(rs.getLong("user_id"));
        binding.setCreatedAt(rs.getString("created_at"));
        return binding;
    }
}
