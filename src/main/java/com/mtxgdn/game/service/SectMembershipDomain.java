package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.sect.SectOperationResult;
import com.mtxgdn.i18n.I18n;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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

    SectOperationResult applyToSect(long playerId, long sectId, String message) {

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return new SectOperationResult(false, I18n.t("sect.character_does_not_exist"));
        }

        Sect sect = queryDomain.getSectById(sectId);
        if (sect == null) {
            return new SectOperationResult(false, I18n.t("sect.sect_does_not_exist"));
        }

        if (queryDomain.getPlayerSect(playerId) != null) {
            return new SectOperationResult(false, I18n.t("sect.you_already_belong"));
        }

        List<SectMember> members = queryDomain.getSectMembers(sectId);
        int maxMembers = Sect.getMaxMembersForLevel(sect.getLevel());
        if (members.size() >= maxMembers) {
            return new SectOperationResult(false, I18n.t("sect.is_full", sect.getName(), members.size(), maxMembers));
        }

        String checkSql = "SELECT id FROM sect_applications WHERE sect_id = ? AND player_id = ? AND status = 'pending'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SectOperationResult(false, I18n.t("sect.you_have_already_applied"));
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

        return new SectOperationResult(true, I18n.t("sect.applied_to_join", sect.getName()));
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

    SectOperationResult approveApplication(long approverPlayerId, long applicationId, boolean approved) {

        SectApplication app = getApplicationById(applicationId);
        if (app == null || !"pending".equals(app.getStatus())) {
            return new SectOperationResult(false, I18n.t("sect.the_application_does_not_exist"));
        }

        Sect sect = queryDomain.getSectById(app.getSectId());
        if (sect == null) {
            return new SectOperationResult(false, I18n.t("sect.sect_does_not_exist"));
        }

        SectMember approver = queryDomain.getMember(sect.getId(), approverPlayerId);
        if (approver == null || !approver.canManage()) {
            return new SectOperationResult(false, I18n.t("sect.you_are_not_allowed"));
        }

        String status;
        String msg;
        if (approved) {
            List<SectMember> members = queryDomain.getSectMembers(sect.getId());
            int maxMembers = Sect.getMaxMembersForLevel(sect.getLevel());
            if (members.size() >= maxMembers) {
                return new SectOperationResult(false, I18n.t("sect.the_sect_is_full"));
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

        return new SectOperationResult(true, msg);
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

    SectOperationResult leaveSect(long playerId) {
        SectMember member = queryDomain.getPlayerMember(playerId);
        if (member == null) {
            return new SectOperationResult(false, I18n.t("sect.you_have_not_joined_any"));
        }

        Sect sect = queryDomain.getSectById(member.getSectId());
        if (sect == null) {
            return new SectOperationResult(false, I18n.t("sect.sect_does_not_exist"));
        }

        if (member.isLeader()) {
            String disbandSql = "SELECT COUNT(*) FROM sect_members WHERE sect_id = ?";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(disbandSql)) {
                ps.setLong(1, sect.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 1) {
                        return new SectOperationResult(false, I18n.t("sect.leave_leader_blocked"));
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
            return new SectOperationResult(true, I18n.t("sect.you_disbanded_the_sect", sect.getName()));
        } else {
            return new SectOperationResult(true, I18n.t("sect.member_left", playerName, sect.getName()));
        }
    }

    SectOperationResult kickMember(long kickerPlayerId, long targetPlayerId) {
        SectMember kicker = queryDomain.getPlayerMember(kickerPlayerId);
        if (kicker == null) {
            return new SectOperationResult(false, I18n.t("sect.you_have_not_joined_any"));
        }
        if (!kicker.canManage()) {
            return new SectOperationResult(false, I18n.t("sect.only_the_sect_leader_vice_3"));
        }

        SectMember target = queryDomain.getMember(kicker.getSectId(), targetPlayerId);
        if (target == null) {
            return new SectOperationResult(false, I18n.t("sect.that_player"));
        }
        if (target.isLeader()) {
            return new SectOperationResult(false, I18n.t("sect.you_cannot_remove_the_sect"));
        }
        if (target.isViceLeader() && !kicker.isLeader()) {
            return new SectOperationResult(false, I18n.t("sect.only_the_sect_leader_4"));
        }
        if (!kicker.canKickOrAppoint(target)) {
            return new SectOperationResult(false, I18n.t("sect.you_cannot_remove_members"));
        }

        deleteMember(kicker.getSectId(), targetPlayerId);
        return new SectOperationResult(true, I18n.t("sect.expelled_from_the_sect", target.getPlayerName()));
    }

    SectOperationResult appointMember(long appointerPlayerId, long targetPlayerId, String newRole) {
        SectMember appointer = queryDomain.getPlayerMember(appointerPlayerId);
        if (appointer == null || !appointer.canManage()) {
            return new SectOperationResult(false, I18n.t("sect.only_the_sect_leader_vice_2"));
        }

        // 先解析目标角色
        String role;
        switch (newRole.toLowerCase()) {
            case "副宗主", "vice_leader", "viceleader": role = SectMember.ROLE_VICE_LEADER; break;
            case "长老", "elder": role = SectMember.ROLE_ELDER; break;
            case "内门", "内门弟子", "inner", "inner_member": role = SectMember.ROLE_INNER_MEMBER; break;
            case "外门", "外门弟子", "outer", "outer_member": role = SectMember.ROLE_OUTER_MEMBER; break;
            default:
                return new SectOperationResult(false, I18n.t("sect.invalid_position"));
        }

        // 权限检查
        if (appointer.isElder()) {
            if (!role.equals(SectMember.ROLE_INNER_MEMBER) && !role.equals(SectMember.ROLE_OUTER_MEMBER)) {
                return new SectOperationResult(false, I18n.t("sect.elders_can_only_appoint_inner"));
            }
        }
        if (appointer.isViceLeader() && role.equals(SectMember.ROLE_VICE_LEADER)) {
            return new SectOperationResult(false, I18n.t("sect.the_vice_leader_cannot_appoint"));
        }

        SectMember target = queryDomain.getMember(appointer.getSectId(), targetPlayerId);
        if (target == null) {
            return new SectOperationResult(false, I18n.t("sect.that_player"));
        }
        if (target.isLeader()) {
            return new SectOperationResult(false, I18n.t("sect.you_cannot_change_the_sect"));
        }
        if (target.isViceLeader() && !appointer.isLeader()) {
            return new SectOperationResult(false, I18n.t("sect.only_the_sect_leader_3"));
        }
        if (target.isElder() && appointer.isElder()) {
            return new SectOperationResult(false, I18n.t("sect.elders_cannot_act_on_members"));
        }

        if (role.equals(target.getRole())) {
            return new SectOperationResult(false, I18n.t("sect.role_already",
                    target.getPlayerName(), SectMember.getRoleDisplayName(role)));
        }

        String sql = "UPDATE sect_members SET role = ? WHERE sect_id = ? AND player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            ps.setLong(2, appointer.getSectId());
            ps.setLong(3, targetPlayerId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("任命失败", e); }

        return new SectOperationResult(true, I18n.t("sect.appointed", target.getPlayerName(), SectMember.getRoleDisplayName(role), SectMember.getRoleDisplayName(target.getRole())));
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

    SectOperationResult addContribution(long sectId, long playerId, long amount) {
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
                return new SectOperationResult(true, I18n.t("sect.contribution_updated"));
            } else {
                return new SectOperationResult(false, I18n.t("sect.update_failed"));
            }
        } catch (SQLException e) { throw new RuntimeException("更新贡献值失败", e); }
    }
}
