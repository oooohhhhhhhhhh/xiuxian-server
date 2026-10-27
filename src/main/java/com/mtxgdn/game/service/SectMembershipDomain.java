package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.SectMembershipDAO;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.i18n.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 宗门成员域：入宗申请、审批、退宗、踢人、任命、贡献值。
 *
 * <p>这些方法都在回答同一个问题 —— 「<b>谁在这个宗门里、是什么身份</b>」，
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
 *
 * <p><b>JDBC 都在 DAO 里</b>：{@code sect_applications} / {@code sect_members} 的
 * 读写全部走 {@link SectMembershipDAO}，本类不再出现 {@code Connection} /
 * {@code PreparedStatement} / {@code ResultSet}。每个 DAO 方法一对重载 ——
 * 事务内一律传 {@code conn}，绝不在事务里另开连接（SQLite 下会
 * {@code SQLITE_BUSY_SNAPSHOT}）。业务规则（成员上限、职位权限、称号授予、
 * {@code OperationResult} 组装）留在本类。</p>
 */
final class SectMembershipDomain {

    private final PlayerService playerService;
    private final SectQueryDomain queryDomain;

    /** {@code sect_applications} / {@code sect_members} 的读写入口（无状态，可共享）。 */
    private static final SectMembershipDAO MEMBERSHIP_DAO = new SectMembershipDAO();

    SectMembershipDomain(PlayerService playerService, SectQueryDomain queryDomain) {
        this.playerService = playerService;
        this.queryDomain = queryDomain;
    }

    // ==================== 申请管理 ====================

    OperationResult applyToSect(long playerId, long sectId, String message) {

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return new OperationResult(false, I18n.t("sect.character_does_not_exist"));
        }

        Sect sect = queryDomain.getSectById(sectId);
        if (sect == null) {
            return new OperationResult(false, I18n.t("sect.sect_does_not_exist"));
        }

        if (queryDomain.getPlayerSect(playerId) != null) {
            return new OperationResult(false, I18n.t("sect.you_already_belong"));
        }

        List<SectMember> members = queryDomain.getSectMembers(sectId);
        int maxMembers = Sect.getMaxMembersForLevel(sect.getLevel());
        if (members.size() >= maxMembers) {
            return new OperationResult(false, I18n.t("sect.is_full", sect.getName(), members.size(), maxMembers));
        }

        if (MEMBERSHIP_DAO.hasPendingApplication(sectId, playerId)) {
            return new OperationResult(false, I18n.t("sect.you_have_already_applied"));
        }

        MEMBERSHIP_DAO.insertApplication(sectId, playerId, message);

        return new OperationResult(true, I18n.t("sect.applied_to_join", sect.getName()));
    }

    List<SectApplication> getPendingApplications(long sectId) {
        return MEMBERSHIP_DAO.findPendingApplications(sectId);
    }

    OperationResult approveApplication(long approverPlayerId, long applicationId, boolean approved) {

        SectApplication app = MEMBERSHIP_DAO.findApplicationById(applicationId);
        if (app == null || !"pending".equals(app.getStatus())) {
            return new OperationResult(false, I18n.t("sect.the_application_does_not_exist"));
        }

        Sect sect = queryDomain.getSectById(app.getSectId());
        if (sect == null) {
            return new OperationResult(false, I18n.t("sect.sect_does_not_exist"));
        }

        SectMember approver = queryDomain.getMember(sect.getId(), approverPlayerId);
        if (approver == null || !approver.canManage()) {
            return new OperationResult(false, I18n.t("sect.you_are_not_allowed"));
        }

        String status;
        String msg;
        if (approved) {
            List<SectMember> members = queryDomain.getSectMembers(sect.getId());
            int maxMembers = Sect.getMaxMembersForLevel(sect.getLevel());
            if (members.size() >= maxMembers) {
                return new OperationResult(false, I18n.t("sect.the_sect_is_full"));
            }
            status = "accepted";
            msg = "已通过玩家【" + app.getPlayerName() + "】的加入申请";
            MEMBERSHIP_DAO.addMember(sect.getId(), app.getPlayerId());
            // 首次入宗授予「首席弟子」称号
            try {
                new com.mtxgdn.game.service.TitleService().grantTitle(app.getPlayerId(), "senior_disciple");
            } catch (Exception ignored) {
            }
        } else {
            status = "rejected";
            msg = "已拒绝玩家【" + app.getPlayerName() + "】的加入申请";
        }

        MEMBERSHIP_DAO.updateApplicationStatus(applicationId, status);

        return new OperationResult(true, msg);
    }

    // ==================== 退出/踢人 ====================

    OperationResult leaveSect(long playerId) {
        SectMember member = queryDomain.getPlayerMember(playerId);
        if (member == null) {
            return new OperationResult(false, I18n.t("sect.you_have_not_joined_any"));
        }

        Sect sect = queryDomain.getSectById(member.getSectId());
        if (sect == null) {
            return new OperationResult(false, I18n.t("sect.sect_does_not_exist"));
        }

        if (member.isLeader()) {
            if (MEMBERSHIP_DAO.countMembers(sect.getId()) > 1) {
                return new OperationResult(false, I18n.t("sect.leave_leader_blocked"));
            }
        }

        MEMBERSHIP_DAO.deleteMember(member.getSectId(), playerId);

        String playerName = member.getPlayerName() != null ? member.getPlayerName() : "未知";

        // 宗主退出则解散宗门
        if (member.isLeader()) {
            DatabaseManager.runTransaction(conn -> {
                MEMBERSHIP_DAO.deleteSectCascade(conn, sect.getId());
                return null;
            });
            return new OperationResult(true, I18n.t("sect.you_disbanded_the_sect", sect.getName()));
        } else {
            return new OperationResult(true, I18n.t("sect.member_left", playerName, sect.getName()));
        }
    }

    OperationResult kickMember(long kickerPlayerId, long targetPlayerId) {
        SectMember kicker = queryDomain.getPlayerMember(kickerPlayerId);
        if (kicker == null) {
            return new OperationResult(false, I18n.t("sect.you_have_not_joined_any"));
        }
        if (!kicker.canManage()) {
            return new OperationResult(false, I18n.t("sect.only_the_sect_leader_vice_3"));
        }

        SectMember target = queryDomain.getMember(kicker.getSectId(), targetPlayerId);
        if (target == null) {
            return new OperationResult(false, I18n.t("sect.that_player"));
        }
        if (target.isLeader()) {
            return new OperationResult(false, I18n.t("sect.you_cannot_remove_the_sect"));
        }
        if (target.isViceLeader() && !kicker.isLeader()) {
            return new OperationResult(false, I18n.t("sect.only_the_sect_leader_4"));
        }
        if (!kicker.canKickOrAppoint(target)) {
            return new OperationResult(false, I18n.t("sect.you_cannot_remove_members"));
        }

        MEMBERSHIP_DAO.deleteMember(kicker.getSectId(), targetPlayerId);
        return new OperationResult(true, I18n.t("sect.expelled_from_the_sect", target.getPlayerName()));
    }

    OperationResult appointMember(long appointerPlayerId, long targetPlayerId, String newRole) {
        SectMember appointer = queryDomain.getPlayerMember(appointerPlayerId);
        if (appointer == null || !appointer.canManage()) {
            return new OperationResult(false, I18n.t("sect.only_the_sect_leader_vice_2"));
        }

        // 先解析目标角色
        String role;
        switch (newRole.toLowerCase()) {
            case "副宗主", "vice_leader", "viceleader": role = SectMember.ROLE_VICE_LEADER; break;
            case "长老", "elder": role = SectMember.ROLE_ELDER; break;
            case "内门", "内门弟子", "inner", "inner_member": role = SectMember.ROLE_INNER_MEMBER; break;
            case "外门", "外门弟子", "outer", "outer_member": role = SectMember.ROLE_OUTER_MEMBER; break;
            default:
                return new OperationResult(false, I18n.t("sect.invalid_position"));
        }

        // 权限检查
        if (appointer.isElder()) {
            if (!role.equals(SectMember.ROLE_INNER_MEMBER) && !role.equals(SectMember.ROLE_OUTER_MEMBER)) {
                return new OperationResult(false, I18n.t("sect.elders_can_only_appoint_inner"));
            }
        }
        if (appointer.isViceLeader() && role.equals(SectMember.ROLE_VICE_LEADER)) {
            return new OperationResult(false, I18n.t("sect.the_vice_leader_cannot_appoint"));
        }

        SectMember target = queryDomain.getMember(appointer.getSectId(), targetPlayerId);
        if (target == null) {
            return new OperationResult(false, I18n.t("sect.that_player"));
        }
        if (target.isLeader()) {
            return new OperationResult(false, I18n.t("sect.you_cannot_change_the_sect"));
        }
        if (target.isViceLeader() && !appointer.isLeader()) {
            return new OperationResult(false, I18n.t("sect.only_the_sect_leader_3"));
        }
        if (target.isElder() && appointer.isElder()) {
            return new OperationResult(false, I18n.t("sect.elders_cannot_act_on_members"));
        }

        if (role.equals(target.getRole())) {
            return new OperationResult(false, I18n.t("sect.role_already",
                    target.getPlayerName(), SectMember.getRoleDisplayName(role)));
        }

        MEMBERSHIP_DAO.setMemberRole(appointer.getSectId(), targetPlayerId, role);

        return new OperationResult(true, I18n.t("sect.appointed", target.getPlayerName(), SectMember.getRoleDisplayName(role), SectMember.getRoleDisplayName(target.getRole())));
    }

    // ==================== 贡献系统 ====================

    OperationResult addContribution(long sectId, long playerId, long amount) {
        int updated = MEMBERSHIP_DAO.addContribution(sectId, playerId, amount);
        if (updated > 0) {
            return new OperationResult(true, I18n.t("sect.contribution_updated"));
        } else {
            return new OperationResult(false, I18n.t("sect.update_failed"));
        }
    }
}
