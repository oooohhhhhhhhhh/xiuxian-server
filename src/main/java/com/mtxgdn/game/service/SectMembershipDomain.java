package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.i18n.I18n;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宗门成员域：入宗申请、审批、退宗、踢人、任命、贡献值。
 *
 * <p>这些方法都在回答同一个问题 ——「<b>谁在这个宗门里、是什么身份</b>」，
 * 所以放在一起：审批要看成员数上限、踢人要比职位高低、任命要判管理权限，
 * 它们共享同一套职位语义（{@link SectMember#canManage()} /
 * {@link SectMember#canKickOrAppoint(SectMember)}），拆开就会互相借私有方法。</p>
 *
 * <p>方法体、SQL、异常文案、{@code Map} 里 key 的顺序都与拆分前逐字相同。</p>
 *
 * <p><b>事务边界</b>：本类的写操作要么是单条 SQL 的短连接（与拆分前一致），
 * 要么是 {@link #leaveSect} 里那段"宗主退宗 → 解散宗门"的 {@code runTransaction}。
 * 那段事务块整体搬了过来，块内只使用事务传入的 {@code conn}，不调用任何会自己开连接的
 * 方法（{@code deleteMember} 在事务<b>之前</b>单独执行，位置与拆分前一致）。</p>
 */
final class SectMembershipDomain {

    private final PlayerService playerService;
    private final SectQueryDomain queryDomain;

    SectMembershipDomain(PlayerService playerService, SectQueryDomain queryDomain) {
        this.playerService = playerService;
        this.queryDomain = queryDomain;
    }

    // ==================== 申请管理 ====================

    Map<String, Object> applyToSect(long playerId, long sectId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h59a76b2c")); return result;
        }

        Sect sect = queryDomain.getSectById(sectId);
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hbffa2528")); return result;
        }

        if (queryDomain.getPlayerSect(playerId) != null) {
            result.put("success", false); result.put("message", I18n.t("sect.h6052ac88")); return result;
        }

        List<SectMember> members = queryDomain.getSectMembers(sectId);
        int maxMembers = Sect.getMaxMembersForLevel(sect.getLevel());
        if (members.size() >= maxMembers) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h853e85aa", sect.getName(), members.size(), maxMembers));
            return result;
        }

        String checkSql = "SELECT id FROM sect_applications WHERE sect_id = ? AND player_id = ? AND status = 'pending'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result.put("success", false);
                    result.put("message", I18n.t("sect.h0c4f82a8"));
                    return result;
                }
            }
        } catch (SQLException e) { throw new RuntimeException("查询申请失败", e); }

        String sql = "INSERT INTO sect_applications (sect_id, player_id, message) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.setString(3, message != null ? message : "");
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("提交申请失败", e); }

        result.put("success", true);
        result.put("message", I18n.t("sect.h7189800c", sect.getName()));
        return result;
    }

    List<SectApplication> getPendingApplications(long sectId) {
        String sql = "SELECT sa.*, p.name AS player_name FROM sect_applications sa " +
                "JOIN players p ON sa.player_id = p.id WHERE sa.sect_id = ? AND sa.status = 'pending' ORDER BY sa.created_at";
        List<SectApplication> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(SectSupport.mapApplication(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询申请列表失败", e); }
        return result;
    }

    Map<String, Object> approveApplication(long approverPlayerId, long applicationId, boolean approved) {
        Map<String, Object> result = new LinkedHashMap<>();

        SectApplication app = getApplicationById(applicationId);
        if (app == null || !"pending".equals(app.getStatus())) {
            result.put("success", false); result.put("message", I18n.t("sect.h71d5bb28")); return result;
        }

        Sect sect = queryDomain.getSectById(app.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hbffa2528")); return result;
        }

        SectMember approver = queryDomain.getMember(sect.getId(), approverPlayerId);
        if (approver == null || !approver.canManage()) {
            result.put("success", false); result.put("message", I18n.t("sect.h33d54619")); return result;
        }

        String status;
        String msg;
        if (approved) {
            List<SectMember> members = queryDomain.getSectMembers(sect.getId());
            int maxMembers = Sect.getMaxMembersForLevel(sect.getLevel());
            if (members.size() >= maxMembers) {
                result.put("success", false);
                result.put("message", I18n.t("sect.h084db6a4")); return result;
            }
            status = "accepted";
            msg = "已通过玩家【" + app.getPlayerName() + "】的加入申请";
            addMember(sect.getId(), app.getPlayerId());
            // 首次入宗授予「首席弟子」称号
            try {
                new com.mtxgdn.game.service.TitleService().grantTitle(app.getPlayerId(), "senior_disciple");
            } catch (Exception ignored) {
            }
        } else {
            status = "rejected";
            msg = "已拒绝玩家【" + app.getPlayerName() + "】的加入申请";
        }

        String sql = "UPDATE sect_applications SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, applicationId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("处理申请失败", e); }

        result.put("success", true);
        result.put("message", msg);
        return result;
    }

    private SectApplication getApplicationById(long appId) {
        String sql = "SELECT sa.*, p.name AS player_name FROM sect_applications sa " +
                "JOIN players p ON sa.player_id = p.id WHERE sa.id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, appId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return SectSupport.mapApplication(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询申请失败", e); }
        return null;
    }

    private void addMember(long sectId, long playerId) {
        String sql = "INSERT INTO sect_members (sect_id, player_id, role) VALUES (?, ?, 'OUTER_MEMBER')";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("添加成员失败", e); }
    }

    // ==================== 退出/踢人 ====================

    Map<String, Object> leaveSect(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = queryDomain.getPlayerMember(playerId);
        if (member == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h6b4e3074")); return result;
        }

        Sect sect = queryDomain.getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hbffa2528")); return result;
        }

        if (member.isLeader()) {
            String disbandSql = "SELECT COUNT(*) FROM sect_members WHERE sect_id = ?";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(disbandSql)) {
                ps.setLong(1, sect.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 1) {
                        result.put("success", false);
                        result.put("message", I18n.t("sect.leave_leader_blocked"));
                        return result;
                    }
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }

        deleteMember(member.getSectId(), playerId);

        String playerName = member.getPlayerName() != null ? member.getPlayerName() : "未知";

        // 宗主退出则解散宗门
        if (member.isLeader()) {
            DatabaseManager.runTransaction(conn -> {
                String sql = "DELETE FROM sect_warehouse WHERE sect_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) { ps.setLong(1, sect.getId()); ps.executeUpdate(); }
                catch (SQLException e) { throw new RuntimeException(e); }
                sql = "DELETE FROM sect_applications WHERE sect_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) { ps.setLong(1, sect.getId()); ps.executeUpdate(); }
                catch (SQLException e) { throw new RuntimeException(e); }
                sql = "DELETE FROM sect_members WHERE sect_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) { ps.setLong(1, sect.getId()); ps.executeUpdate(); }
                catch (SQLException e) { throw new RuntimeException(e); }
                sql = "DELETE FROM sects WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) { ps.setLong(1, sect.getId()); ps.executeUpdate(); }
                catch (SQLException e) { throw new RuntimeException(e); }
                return null;
            });
            result.put("success", true);
            result.put("message", I18n.t("sect.he5e40358", sect.getName()));
        } else {
            result.put("success", true);
            result.put("message", I18n.t("sect.member_left", playerName, sect.getName()));
        }
        return result;
    }

    Map<String, Object> kickMember(long kickerPlayerId, long targetPlayerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember kicker = queryDomain.getPlayerMember(kickerPlayerId);
        if (kicker == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h6b4e3074")); return result;
        }
        if (!kicker.canManage()) {
            result.put("success", false); result.put("message", I18n.t("sect.hcbedcf90")); return result;
        }

        SectMember target = queryDomain.getMember(kicker.getSectId(), targetPlayerId);
        if (target == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h32acc4f3")); return result;
        }
        if (target.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.h587f8ffc")); return result;
        }
        if (target.isViceLeader() && !kicker.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.h30ecd258")); return result;
        }
        if (!kicker.canKickOrAppoint(target)) {
            result.put("success", false); result.put("message", I18n.t("sect.hb01e0fa0")); return result;
        }

        deleteMember(kicker.getSectId(), targetPlayerId);
        result.put("success", true);
        result.put("message", I18n.t("sect.h6bc422a4", target.getPlayerName()));
        return result;
    }

    Map<String, Object> appointMember(long appointerPlayerId, long targetPlayerId, String newRole) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember appointer = queryDomain.getPlayerMember(appointerPlayerId);
        if (appointer == null || !appointer.canManage()) {
            result.put("success", false); result.put("message", I18n.t("sect.haa4398a4")); return result;
        }

        // 先解析目标角色
        String role;
        switch (newRole.toLowerCase()) {
            case "副宗主", "vice_leader", "viceleader": role = SectMember.ROLE_VICE_LEADER; break;
            case "长老", "elder": role = SectMember.ROLE_ELDER; break;
            case "内门", "内门弟子", "inner", "inner_member": role = SectMember.ROLE_INNER_MEMBER; break;
            case "外门", "外门弟子", "outer", "outer_member": role = SectMember.ROLE_OUTER_MEMBER; break;
            default:
                result.put("success", false);
                result.put("message", I18n.t("sect.hda58b418"));
                return result;
        }

        // 权限检查
        if (appointer.isElder()) {
            if (!role.equals(SectMember.ROLE_INNER_MEMBER) && !role.equals(SectMember.ROLE_OUTER_MEMBER)) {
                result.put("success", false);
                result.put("message", I18n.t("sect.h5e1d7370")); return result;
            }
        }
        if (appointer.isViceLeader() && role.equals(SectMember.ROLE_VICE_LEADER)) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hd5b6e8a8")); return result;
        }

        SectMember target = queryDomain.getMember(appointer.getSectId(), targetPlayerId);
        if (target == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h32acc4f3")); return result;
        }
        if (target.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.h53eadc4b")); return result;
        }
        if (target.isViceLeader() && !appointer.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.h20bb459f")); return result;
        }
        if (target.isElder() && appointer.isElder()) {
            result.put("success", false); result.put("message", I18n.t("sect.h8ebfac78")); return result;
        }

        if (role.equals(target.getRole())) {
            result.put("success", false);
            result.put("message", I18n.t("sect.role_already",
                    target.getPlayerName(), SectMember.getRoleDisplayName(role))); return result;
        }

        String sql = "UPDATE sect_members SET role = ? WHERE sect_id = ? AND player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            ps.setLong(2, appointer.getSectId());
            ps.setLong(3, targetPlayerId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("任命失败", e); }

        result.put("success", true);
        result.put("message", I18n.t("sect.h1ad74e30", target.getPlayerName(), SectMember.getRoleDisplayName(role), SectMember.getRoleDisplayName(target.getRole())));
        return result;
    }

    private void deleteMember(long sectId, long playerId) {
        String sql = "DELETE FROM sect_members WHERE sect_id = ? AND player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("删除成员失败", e); }
    }

    // ==================== 贡献系统 ====================

    Map<String, Object> addContribution(long sectId, long playerId, long amount) {
        Map<String, Object> result = new LinkedHashMap<>();
        String sql = "UPDATE sect_members SET contribution = contribution + ? WHERE sect_id = ? AND player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, sectId);
            ps.setLong(3, playerId);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                if (amount > 0) {
                    String prestigeSql = "UPDATE sects SET prestige = prestige + ? WHERE id = ?";
                    try (PreparedStatement pps = conn.prepareStatement(prestigeSql)) {
                        pps.setLong(1, amount);
                        pps.setLong(2, sectId);
                        pps.executeUpdate();
                    }
                }
                result.put("success", true);
                result.put("message", I18n.t("sect.h582e9a70"));
            } else {
                result.put("success", false);
                result.put("message", I18n.t("sect.h5378f0dc"));
            }
        } catch (SQLException e) { throw new RuntimeException("更新贡献值失败", e); }
        return result;
    }
}
