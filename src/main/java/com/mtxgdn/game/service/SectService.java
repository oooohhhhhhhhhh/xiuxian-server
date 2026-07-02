package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class SectService {

    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();
    private final Random random = new Random();

    // ==================== 宗门查询 ====================

    public List<Sect> getAllSects() {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id " +
                "ORDER BY s.prestige DESC, s.level DESC";
        List<Sect> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(mapSect(rs));
        } catch (SQLException e) { throw new RuntimeException("查询宗门列表失败", e); }
        return result;
    }

    public Sect getSectById(long sectId) {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id WHERE s.id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapSect(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门失败", e); }
        return null;
    }

    public Sect getSectByName(String name) {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id WHERE s.name = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapSect(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门失败", e); }
        return null;
    }

    public Sect getPlayerSect(long playerId) {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s JOIN sect_members sm ON s.id = sm.sect_id " +
                "LEFT JOIN players p ON s.leader_player_id = p.id WHERE sm.player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapSect(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询玩家宗门失败", e); }
        return null;
    }

    // ==================== 宗门创建 ====================

    public Map<String, Object> createSect(long playerId, String sectName, String description) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sectName == null || sectName.trim().isEmpty() || sectName.trim().length() < 2 || sectName.trim().length() > 8) {
            result.put("success", false);
            result.put("message", "宗门名称需要 2-8 个字符");
            return result;
        }
        final String name = sectName.trim();
        if (description != null && description.length() > 100) {
            result.put("success", false);
            result.put("message", "宗门描述最多 100 个字符");
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            return result;
        }

        if (player.getRealm() < Sect.MIN_LEVEL_CREATE) {
            String realmName = GameConfigLoader.getRealmConfig(player.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(player.getRealm(), 0).getFullName() : "凡人";
            result.put("success", false);
            result.put("message", "境界不足，需要达到金丹期以上才能创建宗门（当前：" + realmName + "）");
            return result;
        }

        if (getPlayerSect(playerId) != null) {
            result.put("success", false);
            result.put("message", "你已经有宗门了，需要先退出才能创建新宗门");
            return result;
        }

        if (getSectByName(name) != null) {
            result.put("success", false);
            result.put("message", "宗门名称【" + name + "】已被使用");
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < Sect.CREATE_COST_SPIRIT_STONES) {
            result.put("success", false);
            result.put("message", "灵石不足，创建宗门需要 " + Sect.CREATE_COST_SPIRIT_STONES + " 灵石（你目前有 " + spiritStones + " 灵石）");
            return result;
        }

        Sect sect = DatabaseManager.runTransaction(conn -> {
            // 扣除灵石（在事务内）
            if (!itemService.removeItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, Sect.CREATE_COST_SPIRIT_STONES)) {
                throw new SQLException("灵石扣除失败");
            }
            String sql = "INSERT INTO sects (name, description, leader_player_id) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, description != null ? description : "一个新兴的修仙宗门");
                ps.setLong(3, playerId);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    long sectId = keys.getLong(1);
                    String memberSql = "INSERT INTO sect_members (sect_id, player_id, role) VALUES (?, ?, 'LEADER')";
                    try (PreparedStatement mps = conn.prepareStatement(memberSql)) {
                        mps.setLong(1, sectId);
                        mps.setLong(2, playerId);
                        mps.executeUpdate();
                    }
                    return getSectById(sectId);
                }
            }
            return null;
        });

        if (sect != null) {
            result.put("success", true);
            result.put("message", "恭贺【" + player.getName() + "】开创宗门【" + name + "】！消耗灵石 " + Sect.CREATE_COST_SPIRIT_STONES);
            result.put("sect", sect);
        } else {
            result.put("success", false);
            result.put("message", "创建宗门失败");
        }
        return result;
    }

    // ==================== 成员管理 ====================

    public List<SectMember> getSectMembers(long sectId) {
        String sql = "SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level " +
                "FROM sect_members sm JOIN players p ON sm.player_id = p.id " +
                "WHERE sm.sect_id = ? ORDER BY " +
                "CASE sm.role WHEN 'LEADER' THEN 0 WHEN 'VICE_LEADER' THEN 1 WHEN 'ELDER' THEN 2 WHEN 'INNER_MEMBER' THEN 3 ELSE 4 END, sm.joined_at";
        List<SectMember> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapMember(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门成员失败", e); }
        return result;
    }

    public SectMember getMember(long sectId, long playerId) {
        String sql = "SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level " +
                "FROM sect_members sm JOIN players p ON sm.player_id = p.id " +
                "WHERE sm.sect_id = ? AND sm.player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapMember(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门成员失败", e); }
        return null;
    }

    public SectMember getPlayerMember(long playerId) {
        String sql = "SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level " +
                "FROM sect_members sm JOIN players p ON sm.player_id = p.id WHERE sm.player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapMember(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门成员失败", e); }
        return null;
    }

    // ==================== 申请管理 ====================

    public Map<String, Object> applyToSect(long playerId, long sectId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false); result.put("message", "角色不存在"); return result;
        }

        Sect sect = getSectById(sectId);
        if (sect == null) {
            result.put("success", false); result.put("message", "宗门不存在"); return result;
        }

        if (getPlayerSect(playerId) != null) {
            result.put("success", false); result.put("message", "你已经有了宗门，需要先退出才能加入新宗门"); return result;
        }

        List<SectMember> members = getSectMembers(sectId);
        int maxMembers = Sect.getMaxMembersForLevel(sect.getLevel());
        if (members.size() >= maxMembers) {
            result.put("success", false);
            result.put("message", "【" + sect.getName() + "】成员已满（" + members.size() + "/" + maxMembers + "）");
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
                    result.put("message", "你已经向该宗门提交过申请，请等待审批");
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
        result.put("message", "已向【" + sect.getName() + "】提交加入申请，请等待宗主或长老审批");
        return result;
    }

    public List<SectApplication> getPendingApplications(long sectId) {
        String sql = "SELECT sa.*, p.name AS player_name FROM sect_applications sa " +
                "JOIN players p ON sa.player_id = p.id WHERE sa.sect_id = ? AND sa.status = 'pending' ORDER BY sa.created_at";
        List<SectApplication> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapApplication(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询申请列表失败", e); }
        return result;
    }

    public Map<String, Object> approveApplication(long approverPlayerId, long applicationId, boolean approved) {
        Map<String, Object> result = new LinkedHashMap<>();

        SectApplication app = getApplicationById(applicationId);
        if (app == null || !"pending".equals(app.getStatus())) {
            result.put("success", false); result.put("message", "申请不存在或已处理"); return result;
        }

        Sect sect = getSectById(app.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", "宗门不存在"); return result;
        }

        SectMember approver = getMember(sect.getId(), approverPlayerId);
        if (approver == null || !approver.canManage()) {
            result.put("success", false); result.put("message", "你无权处理宗门申请"); return result;
        }

        String status;
        String msg;
        if (approved) {
            List<SectMember> members = getSectMembers(sect.getId());
            int maxMembers = Sect.getMaxMembersForLevel(sect.getLevel());
            if (members.size() >= maxMembers) {
                result.put("success", false);
                result.put("message", "宗门成员已满"); return result;
            }
            status = "accepted";
            msg = "已通过玩家【" + app.getPlayerName() + "】的加入申请";
            addMember(sect.getId(), app.getPlayerId());
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
                if (rs.next()) return mapApplication(rs);
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

    public Map<String, Object> leaveSect(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = getPlayerMember(playerId);
        if (member == null) {
            result.put("success", false); result.put("message", "你没有加入任何宗门"); return result;
        }

        Sect sect = getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", "宗门不存在"); return result;
        }

        if (member.isLeader()) {
            String disbandSql = "SELECT COUNT(*) FROM sect_members WHERE sect_id = ?";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(disbandSql)) {
                ps.setLong(1, sect.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 1) {
                        result.put("success", false);
                        result.put("message", "身为一宗之主，不可随意离开。请先转让宗主之位或解散宗门（/宗门 解散）");
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
            result.put("message", "你已解散宗门【" + sect.getName() + "】，从此云游天下");
        } else {
            result.put("success", true);
            result.put("message", playerName + " 已离开宗门【" + sect.getName() + "】");
        }
        return result;
    }

    public Map<String, Object> kickMember(long kickerPlayerId, long targetPlayerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember kicker = getPlayerMember(kickerPlayerId);
        if (kicker == null) {
            result.put("success", false); result.put("message", "你没有加入任何宗门"); return result;
        }
        if (!kicker.canManage()) {
            result.put("success", false); result.put("message", "只有宗主和长老才能踢人"); return result;
        }

        SectMember target = getMember(kicker.getSectId(), targetPlayerId);
        if (target == null) {
            result.put("success", false); result.put("message", "该玩家不在你的宗门中"); return result;
        }
        if (target.isLeader()) {
            result.put("success", false); result.put("message", "不能踢出宗主"); return result;
        }
        if (target.isViceLeader() && !kicker.isLeader()) {
            result.put("success", false); result.put("message", "只有宗主才能踢出副宗主"); return result;
        }
        if (!kicker.canKickOrAppoint(target)) {
            result.put("success", false); result.put("message", "不能踢出同级或更高级别的成员"); return result;
        }

        deleteMember(kicker.getSectId(), targetPlayerId);
        result.put("success", true);
        result.put("message", "已将 " + target.getPlayerName() + " 逐出宗门");
        return result;
    }

    public Map<String, Object> appointMember(long appointerPlayerId, long targetPlayerId, String newRole) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember appointer = getPlayerMember(appointerPlayerId);
        if (appointer == null || !appointer.canManage()) {
            result.put("success", false); result.put("message", "只有宗主、副宗主和长老才能任命宗门职位"); return result;
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
                result.put("message", "无效的职位，可用：副宗主/长老/内门弟子/外门弟子");
                return result;
        }

        // 权限检查
        if (appointer.isElder()) {
            if (!role.equals(SectMember.ROLE_INNER_MEMBER) && !role.equals(SectMember.ROLE_OUTER_MEMBER)) {
                result.put("success", false);
                result.put("message", "长老只能任命内门弟子和外门弟子"); return result;
            }
        }
        if (appointer.isViceLeader() && role.equals(SectMember.ROLE_VICE_LEADER)) {
            result.put("success", false);
            result.put("message", "副宗主不能任命副宗主，只有宗主可以"); return result;
        }

        SectMember target = getMember(appointer.getSectId(), targetPlayerId);
        if (target == null) {
            result.put("success", false); result.put("message", "该玩家不在你的宗门中"); return result;
        }
        if (target.isLeader()) {
            result.put("success", false); result.put("message", "不能改变宗主的职位"); return result;
        }
        if (target.isViceLeader() && !appointer.isLeader()) {
            result.put("success", false); result.put("message", "只有宗主才能改变副宗主的职位"); return result;
        }
        if (target.isElder() && appointer.isElder()) {
            result.put("success", false); result.put("message", "长老不能操作同级"); return result;
        }

        if (role.equals(target.getRole())) {
            result.put("success", false);
            result.put("message", target.getPlayerName() + " 已经是" + SectMember.getRoleDisplayName(role)); return result;
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
        result.put("message", "已将 " + target.getPlayerName() + " 任命为" + SectMember.getRoleDisplayName(role) + "（原为" + SectMember.getRoleDisplayName(target.getRole()) + "）");
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

    public Map<String, Object> addContribution(long sectId, long playerId, long amount) {
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
                result.put("message", "贡献值已更新");
            } else {
                result.put("success", false);
                result.put("message", "更新失败，该玩家不在宗门中");
            }
        } catch (SQLException e) { throw new RuntimeException("更新贡献值失败", e); }
        return result;
    }

    // ==================== 仓库系统 ====================

    public List<SectWarehouseItem> getWarehouse(long sectId) {
        String sql = "SELECT sw.*, p.name AS donated_by_name FROM sect_warehouse sw " +
                "LEFT JOIN players p ON sw.donated_by_player_id = p.id " +
                "WHERE sw.sect_id = ? AND sw.quantity > 0 ORDER BY sw.created_at";
        List<SectWarehouseItem> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapWarehouseItem(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门仓库失败", e); }
        return result;
    }

    public Map<String, Object> donateToWarehouse(long playerId, long sectId, String itemKey, int quantity) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = getMember(sectId, playerId);
        if (member == null) {
            result.put("success", false); result.put("message", "你不是该宗门成员"); return result;
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false); result.put("message", "物品不存在"); return result;
        }
        String fullKey = item.getFullKey();
        if (quantity <= 0) {
            result.put("success", false); result.put("message", "数量必须大于 0"); return result;
        }
        if (!itemService.hasItem(playerId, fullKey, quantity)) {
            result.put("success", false); result.put("message", "背包中【" + item.getName() + "】数量不足"); return result;
        }

        itemService.removeItem(playerId, fullKey, quantity);

        DatabaseManager.runTransaction(conn -> {
            String upsertSql;
            if (DatabaseManager.isSqlite()) {
                upsertSql = "INSERT INTO sect_warehouse (sect_id, item_key, quantity, donated_by_player_id) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(sect_id, item_key) DO UPDATE SET quantity = quantity + ?, donated_by_player_id = ?";
            } else {
                upsertSql = "INSERT INTO sect_warehouse (sect_id, item_key, quantity, donated_by_player_id) VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE quantity = quantity + ?, donated_by_player_id = ?";
            }
            try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                ps.setLong(1, sectId);
                ps.setString(2, fullKey);
                ps.setInt(3, quantity);
                ps.setLong(4, playerId);
                ps.setInt(5, quantity);
                ps.setLong(6, playerId);
                ps.executeUpdate();
            }
            return null;
        });

        long contributionGain = quantity * 10L;
        addContribution(sectId, playerId, contributionGain);

        result.put("success", true);
        result.put("message", "已向宗门仓库捐献【" + item.getName() + "】×" + quantity + "，获得 " + contributionGain + " 贡献值");
        return result;
    }

    public Map<String, Object> withdrawFromWarehouse(long playerId, long sectId, String itemKey, int quantity) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = getMember(sectId, playerId);
        if (member == null) {
            result.put("success", false); result.put("message", "你不是该宗门成员"); return result;
        }
        if (quantity <= 0) {
            result.put("success", false); result.put("message", "数量必须大于 0"); return result;
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false); result.put("message", "物品不存在"); return result;
        }
        String fullKey = item.getFullKey();

        int available = getWarehouseItemCount(sectId, fullKey);
        if (available < quantity) {
            result.put("success", false);
            result.put("message", "宗门仓库中【" + item.getName() + "】数量不足（当前：" + available + "）"); return result;
        }

        // 弟子需消耗贡献值
        if (!member.canManage()) {
            long costPerItem = getItemContributionCost(item);
            long totalCost = costPerItem * quantity;
            if (member.getContribution() < totalCost) {
                result.put("success", false);
                result.put("message", "贡献值不足，取出【" + item.getName() + "】×" + quantity
                        + " 需要 " + totalCost + " 贡献值（当前：" + member.getContribution() + "）");
                return result;
            }
            // 扣除贡献值
            String costSql = "UPDATE sect_members SET contribution = contribution - ? WHERE id = ?";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(costSql)) {
                ps.setLong(1, totalCost);
                ps.setLong(2, member.getId());
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException("扣除贡献值失败", e); }
        }

        String deductSql = "UPDATE sect_warehouse SET quantity = quantity - ? WHERE sect_id = ? AND item_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(deductSql)) {
            ps.setInt(1, quantity);
            ps.setLong(2, sectId);
            ps.setString(3, fullKey);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("取出物品失败", e); }

        itemService.addItem(playerId, fullKey, quantity);

        String msg;
        if (member.canManage()) {
            msg = "已从宗门仓库取出【" + item.getName() + "】×" + quantity;
        } else {
            long cost = getItemContributionCost(item) * quantity;
            msg = "已从宗门仓库取出【" + item.getName() + "】×" + quantity + "，消耗贡献值 " + cost;
        }
        result.put("success", true);
        result.put("message", msg);
        return result;
    }

    /** 物品兑换贡献值（取价格/10，最低50） */
    public long getItemContributionCost(Item item) {
        int price = item.getPrice();
        return Math.max(50, price / 10);
    }

    private int getWarehouseItemCount(long sectId, String itemKey) {
        String sql = "SELECT quantity FROM sect_warehouse WHERE sect_id = ? AND item_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("quantity");
            }
        } catch (SQLException e) { throw new RuntimeException("查询仓库物品失败", e); }
        return 0;
    }

    // ==================== 宗门升级 ====================

    public Map<String, Object> levelUp(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = getPlayerMember(playerId);
        if (member == null || !member.isLeader()) {
            result.put("success", false); result.put("message", "只有宗主才能升级宗门"); return result;
        }

        Sect sect = getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", "宗门不存在"); return result;
        }
        if (sect.getLevel() >= Sect.MAX_LEVEL) {
            result.put("success", false);
            result.put("message", "宗门已达到最高等级 " + Sect.MAX_LEVEL + " 级"); return result;
        }

        long cost = Sect.getLevelUpCost(sect.getLevel());
        if (sect.getPrestige() < cost) {
            result.put("success", false);
            result.put("message", "声望不足，升级需要 " + cost + " 声望（当前：" + sect.getPrestige() + "）");
            return result;
        }

        int newLevel = sect.getLevel() + 1;
        int newMaxMembers = Sect.getMaxMembersForLevel(newLevel);
        String sql = "UPDATE sects SET level = ?, prestige = prestige - ?, max_members = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, newLevel);
            ps.setLong(2, cost);
            ps.setInt(3, newMaxMembers);
            ps.setLong(4, sect.getId());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("宗门升级失败", e); }

        result.put("success", true);
        result.put("message", "宗门【" + sect.getName() + "】晋升为 " + newLevel + " 级宗门！消耗 " + cost + " 声望，成员上限提升至 " + newMaxMembers + " 人");
        return result;
    }

    // ==================== 宗主转让 ====================

    public Map<String, Object> transferLeader(long fromPlayerId, long toPlayerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember fromMember = getPlayerMember(fromPlayerId);
        if (fromMember == null || !fromMember.isLeader()) {
            result.put("success", false); result.put("message", "只有宗主才能转让"); return result;
        }

        Sect sect = getSectById(fromMember.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", "宗门不存在"); return result;
        }

        SectMember toMember = getMember(sect.getId(), toPlayerId);
        if (toMember == null) {
            result.put("success", false); result.put("message", "对方不在你的宗门中"); return result;
        }
        if (toMember.isLeader()) {
            result.put("success", false); result.put("message", "对方已经是宗主了"); return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(fromPlayerId);
        if (spiritStones < Sect.TRANSFER_COST_SPIRIT_STONES) {
            result.put("success", false);
            result.put("message", "灵石不足，转让宗主之位需要 " + Sect.TRANSFER_COST_SPIRIT_STONES + " 灵石（你目前有 " + spiritStones + " 灵石）");
            return result;
        }
        if (!itemService.removeSpiritStones(fromPlayerId, Sect.TRANSFER_COST_SPIRIT_STONES)) {
            result.put("success", false);
            result.put("message", "扣除灵石失败"); return result;
        }

        DatabaseManager.runTransaction(conn -> {
            String sql = "UPDATE sects SET leader_player_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, toPlayerId);
                ps.setLong(2, sect.getId());
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }

            sql = "UPDATE sect_members SET role = 'VICE_LEADER' WHERE sect_id = ? AND player_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, sect.getId());
                ps.setLong(2, fromPlayerId);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }

            sql = "UPDATE sect_members SET role = 'LEADER' WHERE sect_id = ? AND player_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, sect.getId());
                ps.setLong(2, toPlayerId);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
            return null;
        });

        result.put("success", true);
        result.put("message", fromMember.getPlayerName() + " 已将宗主之位传于 " + toMember.getPlayerName()
                + "，消耗灵石 " + Sect.TRANSFER_COST_SPIRIT_STONES + "，从此退居副宗主之位");
        return result;
    }

    // ==================== 宗门战 ====================

    public Map<String, Object> declareWar(long attackerPlayerId, long defenderSectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember attackerMember = getPlayerMember(attackerPlayerId);
        if (attackerMember == null || !attackerMember.isLeader()) {
            result.put("success", false); result.put("message", "只有宗主才能发起宗门战"); return result;
        }

        Sect attackerSect = getSectById(attackerMember.getSectId());
        if (attackerSect == null) {
            result.put("success", false); result.put("message", "你的宗门不存在"); return result;
        }
        if (attackerSect.getId() == defenderSectId) {
            result.put("success", false); result.put("message", "不能对自己的宗门宣战"); return result;
        }

        Sect defenderSect = getSectById(defenderSectId);
        if (defenderSect == null) {
            result.put("success", false); result.put("message", "目标宗门不存在"); return result;
        }

        List<SectMember> defenderMembers = getSectMembers(defenderSectId);
        if (defenderMembers.isEmpty()) {
            result.put("success", false); result.put("message", "目标宗门没有成员"); return result;
        }

        if (attackerSect.getPrestige() < Sect.DECLARE_WAR_PRESTIGE_COST) {
            result.put("success", false);
            result.put("message", "宗门声望不足，宣战需要 " + Sect.DECLARE_WAR_PRESTIGE_COST + " 声望（当前：" + attackerSect.getPrestige() + "）");
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(attackerPlayerId);
        if (spiritStones < Sect.DECLARE_WAR_SPIRIT_STONE_COST) {
            result.put("success", false);
            result.put("message", "灵石不足，宣战需要 " + Sect.DECLARE_WAR_SPIRIT_STONE_COST + " 灵石（你目前有 " + spiritStones + " 灵石）");
            return result;
        }
        // 扣除灵石和声望（事务包裹）
        try {
            DatabaseManager.runTransaction(conn -> {
                if (!itemService.removeItem(conn, attackerPlayerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, Sect.DECLARE_WAR_SPIRIT_STONE_COST)) {
                    throw new SQLException("扣除灵石失败");
                }
                String deductPrestigeSql = "UPDATE sects SET prestige = prestige - ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deductPrestigeSql)) {
                    ps.setLong(1, Sect.DECLARE_WAR_PRESTIGE_COST);
                    ps.setLong(2, attackerSect.getId());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "宣战失败: " + e.getMessage());
            return result;
        }

        // 选出战成员：按境界和攻击力排序取前N名
        List<SectMember> attackerMembers = getSectMembers(attackerSect.getId());
        List<SectMember> attackerFighters = selectFighters(attackerMembers, Sect.WAR_MEMBERS_PER_SIDE);
        List<SectMember> defenderFighters = selectFighters(defenderMembers, Sect.WAR_MEMBERS_PER_SIDE);

        List<String> battleLog = new ArrayList<>();
        battleLog.add("⚔【" + attackerSect.getName() + "】VS【" + defenderSect.getName() + "】宗门战");
        battleLog.add("出战人数：" + attackerFighters.size() + " vs " + defenderFighters.size());
        battleLog.add("");

        int attackerWins = 0;
        int defenderWins = 0;
        int totalBattles = Math.min(attackerFighters.size(), defenderFighters.size());

        for (int i = 0; i < totalBattles; i++) {
            SectMember af = attackerFighters.get(i);
            SectMember df = defenderFighters.get(i);
            Player ap = playerService.getPlayerById(af.getPlayerId());
            Player dp = playerService.getPlayerById(df.getPlayerId());

            if (ap == null || dp == null) continue;

            battleLog.add("第" + (i + 1) + "场：" + ap.getName() + " VS " + dp.getName());
            String winner = simulateDuel(ap, dp, battleLog);
            if (winner.equals(ap.getName())) {
                attackerWins++;
                battleLog.add("  ▶ " + ap.getName() + " 获胜！");
            } else {
                defenderWins++;
                battleLog.add("  ▶ " + dp.getName() + " 获胜！");
            }
            battleLog.add("");
        }

        boolean attackerWin = attackerWins > defenderWins;
        String winnerSectName = attackerWin ? attackerSect.getName() : defenderSect.getName();
        String loserSectName = attackerWin ? defenderSect.getName() : attackerSect.getName();
        long winnerSectId = attackerWin ? attackerSect.getId() : defenderSect.getId();

        battleLog.add("===== 宗门战结果 =====");
        battleLog.add(attackerSect.getName() + " " + attackerWins + "胜 - " + defenderWins + "胜 " + defenderSect.getName());
        battleLog.add("胜者：【" + winnerSectName + "】，额外获得 " + Sect.WAR_WIN_PRESTIGE + " 声望");

        // 发放声望奖励
        String rewardSql = "UPDATE sects SET prestige = prestige + ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(rewardSql)) {
                ps.setLong(1, Sect.WAR_WIN_PRESTIGE);
                ps.setLong(2, winnerSectId);
                ps.executeUpdate();
            }
            // 失败方扣声望
            long loserSectId = attackerWin ? defenderSect.getId() : attackerSect.getId();
            String loseSql = "UPDATE sects SET prestige = GREATEST(0, prestige - ?) WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(loseSql)) {
                ps.setLong(1, Sect.WAR_WIN_PRESTIGE);
                ps.setLong(2, loserSectId);
                ps.executeUpdate();
            }
        } catch (SQLException e) { throw new RuntimeException("发放宗门战奖励失败", e); }

        // 保存战报
        String logText = String.join("\n", battleLog);
        String insertSql = "INSERT INTO sect_wars (attacker_sect_id, defender_sect_id, winner_sect_id, attacker_wins, defender_wins, prestige_stake, battle_log) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setLong(1, attackerSect.getId());
            ps.setLong(2, defenderSect.getId());
            ps.setLong(3, winnerSectId);
            ps.setInt(4, attackerWins);
            ps.setInt(5, defenderWins);
            ps.setLong(6, Sect.WAR_WIN_PRESTIGE);
            ps.setString(7, logText);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("保存宗门战记录失败", e); }

        result.put("success", true);
        result.put("message", attackerSect.getName() + " 对 " + defenderSect.getName() + " 发起宗门战！\n结果：" + winnerSectName + " 获胜！\n比分：" + attackerWins + ":" + defenderWins);
        result.put("battleLog", logText);
        result.put("attackerWins", attackerWins);
        result.put("defenderWins", defenderWins);
        result.put("winner", winnerSectName);
        return result;
    }

    private List<SectMember> selectFighters(List<SectMember> members, int count) {
        return members.stream()
                .sorted(Comparator.comparingInt((SectMember m) -> -m.getPlayerRealm())
                        .thenComparingInt(m -> -m.getPlayerLevel()))
                .limit(count)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private String simulateDuel(Player a, Player b, List<String> log) {
        int aHp = a.getMaxHp();
        int bHp = b.getMaxHp();
        int aAtk = a.getAttack();
        int bAtk = b.getAttack();
        int aDef = a.getDefense();
        int bDef = b.getDefense();
        int aSpd = a.getSpeed();
        int bSpd = b.getSpeed();
        SpiritualRoot aRoot = a.getSpiritualRoot();
        SpiritualRoot bRoot = b.getSpiritualRoot();

        boolean aFirst = aSpd >= bSpd;
        int maxRounds = 15;

        for (int round = 1; round <= maxRounds && aHp > 0 && bHp > 0; round++) {
            // 灵根再生
            if (aRoot != null && aRoot.hasEffect(SpiritualRoot.SpecialEffect.REGENERATION)) {
                aHp = Math.min(a.getMaxHp(), aHp + (int)(a.getMaxHp() * aRoot.getEffectValue()));
            }
            if (bRoot != null && bRoot.hasEffect(SpiritualRoot.SpecialEffect.REGENERATION)) {
                bHp = Math.min(b.getMaxHp(), bHp + (int)(b.getMaxHp() * bRoot.getEffectValue()));
            }

            if (aFirst) {
                int dmg = calcWarDamage(aAtk, aRoot, bDef, bRoot);
                bHp -= dmg;
                if (bHp <= 0) { log.add("  " + a.getName() + " 造成 " + dmg + " 伤害，" + b.getName() + " HP:" + Math.max(0, bHp) + "/" + b.getMaxHp()); return a.getName(); }

                dmg = calcWarDamage(bAtk, bRoot, aDef, aRoot);
                aHp -= dmg;
                if (aHp <= 0) { log.add("  " + b.getName() + " 造成 " + dmg + " 伤害，" + a.getName() + " HP:" + Math.max(0, aHp) + "/" + a.getMaxHp()); return b.getName(); }
            } else {
                int dmg = calcWarDamage(bAtk, bRoot, aDef, aRoot);
                aHp -= dmg;
                if (aHp <= 0) { log.add("  " + b.getName() + " 造成 " + dmg + " 伤害，" + a.getName() + " HP:" + Math.max(0, aHp) + "/" + a.getMaxHp()); return b.getName(); }

                dmg = calcWarDamage(aAtk, aRoot, bDef, bRoot);
                bHp -= dmg;
                if (bHp <= 0) { log.add("  " + a.getName() + " 造成 " + dmg + " 伤害，" + b.getName() + " HP:" + Math.max(0, bHp) + "/" + b.getMaxHp()); return a.getName(); }
            }
        }

        // HP百分比胜
        double aPct = (double) aHp / a.getMaxHp();
        double bPct = (double) bHp / b.getMaxHp();
        if (aPct >= bPct) {
            log.add("  双方鏖战" + maxRounds + "回合，" + a.getName() + " HP剩余" + aPct * 100 + "% 胜出");
            return a.getName();
        } else {
            log.add("  双方鏖战" + maxRounds + "回合，" + b.getName() + " HP剩余" + bPct * 100 + "% 胜出");
            return b.getName();
        }
    }

    private int calcWarDamage(int atk, SpiritualRoot atkRoot, int def, SpiritualRoot defRoot) {
        double dmg = atk;

        // 攻击方灵根加成
        if (atkRoot != null) {
            if (atkRoot.hasEffect(SpiritualRoot.SpecialEffect.SKILL_DAMAGE)) {
                dmg *= (1 + atkRoot.getEffectValue());
            }
            if (atkRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_BOOST)) {
                dmg *= (1 + atkRoot.getEffectValue());
            }
        }

        // 暴击
        double critChance = atkRoot != null && atkRoot.hasEffect(SpiritualRoot.SpecialEffect.CRIT_CHANCE)
                ? atkRoot.getEffectValue() : 0.05;
        double critMult = 1.5;
        if (atkRoot != null && atkRoot.hasEffect(SpiritualRoot.SpecialEffect.CRIT_DAMAGE)) {
            critMult += atkRoot.getEffectValue();
        }
        if (random.nextDouble() < critChance) {
            dmg *= critMult;
        }

        // 随机浮动 + 防御减免
        dmg += random.nextInt(11) - 5;
        dmg -= def / 3.0;
        dmg = Math.max(1, dmg);

        // 防御方减伤
        if (defRoot != null && defRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_REDUCTION)) {
            dmg *= (1 - defRoot.getEffectValue());
        }

        return Math.max(1, (int) Math.round(dmg));
    }

    // ==================== 解散宗门 ====================

    public Map<String, Object> disbandSect(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = getPlayerMember(playerId);
        if (member == null || !member.isLeader()) {
            result.put("success", false); result.put("message", "只有宗主才能解散宗门"); return result;
        }

        Sect sect = getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", "宗门不存在"); return result;
        }

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
        result.put("message", "宗门【" + sect.getName() + "】已解散，从此江湖再无此门");
        return result;
    }

    // ==================== 宗门排行 ====================

    public List<Sect> getTopSects(int limit) {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id " +
                "ORDER BY s.prestige DESC LIMIT ?";
        List<Sect> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapSect(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门排行失败", e); }
        return result;
    }

    // ==================== 映射方法 ====================

    private Sect mapSect(ResultSet rs) throws SQLException {
        Sect s = new Sect();
        s.setId(rs.getLong("id"));
        s.setName(rs.getString("name"));
        s.setDescription(rs.getString("description"));
        s.setLeaderPlayerId(rs.getLong("leader_player_id"));
        s.setLevel(rs.getInt("level"));
        s.setPrestige(rs.getLong("prestige"));
        s.setMaxMembers(rs.getInt("max_members"));
        s.setCreatedAt(rs.getString("created_at"));
        try { s.setLeaderName(rs.getString("leader_name")); } catch (SQLException ignored) {}
        try { s.setMemberCount(rs.getInt("member_count")); } catch (SQLException ignored) {}
        return s;
    }

    private SectMember mapMember(ResultSet rs) throws SQLException {
        SectMember m = new SectMember();
        m.setId(rs.getLong("id"));
        m.setSectId(rs.getLong("sect_id"));
        m.setPlayerId(rs.getLong("player_id"));
        m.setRole(rs.getString("role"));
        m.setContribution(rs.getLong("contribution"));
        m.setJoinedAt(rs.getString("joined_at"));
        try { m.setPlayerName(rs.getString("player_name")); } catch (SQLException ignored) {}
        try { m.setPlayerRealm(rs.getInt("player_realm")); } catch (SQLException ignored) {}
        try { m.setPlayerLevel(rs.getInt("player_level")); } catch (SQLException ignored) {}
        return m;
    }

    private SectApplication mapApplication(ResultSet rs) throws SQLException {
        SectApplication a = new SectApplication();
        a.setId(rs.getLong("id"));
        a.setSectId(rs.getLong("sect_id"));
        a.setPlayerId(rs.getLong("player_id"));
        a.setMessage(rs.getString("message"));
        a.setStatus(rs.getString("status"));
        a.setCreatedAt(rs.getString("created_at"));
        try { a.setPlayerName(rs.getString("player_name")); } catch (SQLException ignored) {}
        return a;
    }

    private SectWarehouseItem mapWarehouseItem(ResultSet rs) throws SQLException {
        SectWarehouseItem i = new SectWarehouseItem();
        i.setId(rs.getLong("id"));
        i.setSectId(rs.getLong("sect_id"));
        i.setItemKey(rs.getString("item_key"));
        i.setQuantity(rs.getInt("quantity"));
        i.setCreatedAt(rs.getString("created_at"));
        try { i.setDonatedByPlayerId(rs.getLong("donated_by_player_id")); } catch (SQLException ignored) {}
        try { i.setDonatedByName(rs.getString("donated_by_name")); } catch (SQLException ignored) {}
        return i;
    }
}
