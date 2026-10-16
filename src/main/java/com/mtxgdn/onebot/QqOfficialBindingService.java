package com.mtxgdn.onebot;

import com.mtxgdn.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * QQ 官方开放平台绑定服务（独立于 OneBot 的 {@link QqBindingService}）。
 *
 * <p>QQ官方平台下发的是 <b>openid</b>（平台内唯一、与真实 QQ 号无关），
 * 与 OneBot（真实 QQ 号）不是同一身份体系，因此绑定数据存放在独立的
 * {@code qq_official_bindings} 表（app_id + openid ↔ user_id），避免 openid 与 QQ 号
 * 互相挤占唯一键、以及同一玩家在两端无法同时绑定。</p>
 *
 * <p>openid 是按 <b>AppID 隔离</b>的：同一个真人在不同机器人下 openid 不同，因此绑定必须带
 * {@code app_id} 维度（唯一键 {@code (app_id, openid)} / {@code (app_id, user_id)}），
 * 否则同机运行多个 QQ 官方机器人时会出现"在 A 机器人绑定、在 B 机器人显示未绑定"，
 * 并且在 B 上重新绑定会因 user_id 已被占用而失败。</p>
 *
 * <p>升级前的历史行 {@code app_id} 为空字符串，查找时作为兜底命中，
 * 这样升级后已绑定的玩家不会被登出。</p>
 *
 * <p>方法签名与 {@link QqBindingService} 保持一致（内部字段 {@code qqNumber}
 * 承载 openid 字符串），以便 QQ 官方适配器无侵入切换。</p>
 */
public class QqOfficialBindingService {

    /** 机器人 AppID；为空 = 不限 AppID（仅用于跨平台展示查询，不能用于绑定） */
    private final String appId;

    /** 跨机器人查询用构造器（不限 AppID，仅展示用途）。 */
    public QqOfficialBindingService() {
        this("");
    }

    /**
     * @param appId 该适配器实例对应的机器人 AppID（{@code config/adapters.json} 中的 app_id）
     */
    public QqOfficialBindingService(String appId) {
        this.appId = appId == null ? "" : appId.trim();
    }

    /** 是否限定了机器人 AppID。 */
    private boolean scoped() {
        return !appId.isEmpty();
    }

    /**
     * 按 openid 查绑定。限定了 AppID 时优先精确命中本机器人，其次回落到历史数据（app_id 为空）。
     */
    public QqBinding findByQq(String openid) {
        if (openid == null || openid.isEmpty()) {
            return null;
        }
        if (!scoped()) {
            return queryOneByString("SELECT id, app_id, openid, user_id, created_at FROM qq_official_bindings "
                    + "WHERE openid = ? LIMIT 1", openid);
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, app_id, openid, user_id, created_at FROM qq_official_bindings "
                             + "WHERE openid = ? AND (app_id = ? OR app_id = '') "
                             + "ORDER BY CASE WHEN app_id = ? THEN 0 ELSE 1 END LIMIT 1")) {
            stmt.setString(1, openid);
            stmt.setString(2, appId);
            stmt.setString(3, appId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapBinding(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询QQ官方绑定失败", e);
        }
        return null;
    }

    /**
     * 按用户查绑定。限定了 AppID 时优先返回本机器人的绑定，其次回落到历史数据。
     * 一个用户可能在不同机器人下各有一条绑定，此处只取一条（展示/通知用途）。
     */
    public QqBinding findByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        if (!scoped()) {
            return queryOneByLong("SELECT id, app_id, openid, user_id, created_at FROM qq_official_bindings "
                    + "WHERE user_id = ? LIMIT 1", userId);
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, app_id, openid, user_id, created_at FROM qq_official_bindings "
                             + "WHERE user_id = ? AND (app_id = ? OR app_id = '') "
                             + "ORDER BY CASE WHEN app_id = ? THEN 0 ELSE 1 END LIMIT 1")) {
            stmt.setLong(1, userId);
            stmt.setString(2, appId);
            stmt.setString(3, appId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapBinding(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户QQ官方绑定失败", e);
        }
        return null;
    }

    /** 单参数（字符串）查询一条记录，不限 AppID 时使用。 */
    private QqBinding queryOneByString(String sql, String param) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapBinding(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询QQ官方绑定失败", e);
        }
    }

    /** 单参数（Long）查询一条记录，不限 AppID 时使用。 */
    private QqBinding queryOneByLong(String sql, Long param) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapBinding(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询QQ官方绑定失败", e);
        }
    }

    public void bind(String openid, Long userId) {
        if (!scoped()) {
            throw new RuntimeException("该 QQ 官方适配器未配置 app_id，无法绑定");
        }

        QqBinding existingByOpenid = findByQq(openid);
        if (existingByOpenid != null) {
            throw new RuntimeException("该 openid 已绑定用户: " + existingByOpenid.getUserId()
                    + legacyHint(existingByOpenid));
        }

        QqBinding existingByUser = findByUserId(userId);
        if (existingByUser != null && !isOtherBotLegacyRow(existingByUser, openid)) {
            throw new RuntimeException("该用户已绑定QQ官方 openid: " + existingByUser.getQqNumber()
                    + legacyHint(existingByUser));
        }

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

    /** 历史兼容行（升级前写入、app_id 为空）的补充说明，便于排查"为什么绑定不上"。 */
    private static String legacyHint(QqBinding binding) {
        return binding.getAppId() == null || binding.getAppId().isEmpty()
                ? "（该绑定来自升级前的历史数据，app_id 未知、按 openid 精确匹配，对所有机器人通用）"
                : "";
    }

    /**
     * 判断已存在的绑定是否是"另一个机器人的历史兼容行"。
     *
     * <p>升级前的行 app_id 为空，无法直接得知它属于哪个机器人；但 openid 是按 AppID 隔离的，
     * 所以 openid 不同即说明它来自另一个机器人。这种情况不应阻塞玩家在本机器人上绑定
     * （否则老玩家在新机器人上永远绑定不上）。openid 相同则说明就是本机器人的绑定，照常拒绝。</p>
     */
    private static boolean isOtherBotLegacyRow(QqBinding binding, String openid) {
        return (binding.getAppId() == null || binding.getAppId().isEmpty())
                && binding.getQqNumber() != null
                && !binding.getQqNumber().equals(openid);
    }

    /**
     * 解绑：先删本机器人（含历史兼容行）的绑定；若不存在则退化为按 openid 删除任意机器人的绑定。
     */
    public void unbindByQq(String openid) {
        String sql = scoped()
                ? "DELETE FROM qq_official_bindings WHERE openid = ? AND (app_id = ? OR app_id = '')"
                : "DELETE FROM qq_official_bindings WHERE openid = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, openid);
            if (scoped()) {
                stmt.setString(2, appId);
            }
            int affected = stmt.executeUpdate();
            if (affected == 0 && scoped()) {
                // 本机器人没有绑定记录时，退回按 openid 全量删除（用户视角：解绑这个 QQ）
                try (PreparedStatement fallback = conn.prepareStatement(
                        "DELETE FROM qq_official_bindings WHERE openid = ?")) {
                    fallback.setString(1, openid);
                    affected = fallback.executeUpdate();
                }
            }
            if (affected == 0) {
                throw new RuntimeException("该 openid 未绑定任何用户");
            }
        } catch (SQLException e) {
            throw new RuntimeException("解绑QQ官方失败", e);
        }
    }

    /** 按用户解绑全部机器人的绑定（管理员/注销账号场景）。 */
    public void unbindByUserId(Long userId) {
        String sql = "DELETE FROM qq_official_bindings WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            int affected = stmt.executeUpdate();
            if (affected == 0) {
                throw new RuntimeException("该用户未绑定任何QQ官方 openid");
            }
        } catch (SQLException e) {
            throw new RuntimeException("解绑用户QQ官方失败", e);
        }
    }

    private QqBinding mapBinding(ResultSet rs) throws SQLException {
        QqBinding binding = new QqBinding();
        binding.setId(rs.getLong("id"));
        // 复用 QqBinding 实体：qqNumber 字段承载 openid 字符串
        binding.setQqNumber(rs.getString("openid"));
        binding.setUserId(rs.getLong("user_id"));
        binding.setCreatedAt(rs.getString("created_at"));
        binding.setAppId(rs.getString("app_id"));
        return binding;
    }
}
