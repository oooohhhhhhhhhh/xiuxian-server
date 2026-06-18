package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SectService {

    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();

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
            result.put("message", "境界不足，需要达到筑基期以上才能创建宗门（当前：" + realmName + "）");
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

        boolean ok = itemService.removeSpiritStones(playerId, Sect.CREATE_COST_SPIRIT_STONES);
        if (!ok) {
            result.put("success", false);
            result.put("message", "扣除灵石失败");
            return result;
        }

        Sect sect = DatabaseManager.runTransaction(conn -> {
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
                "CASE sm.role WHEN 'LEADER' THEN 0 WHEN 'ELDER' THEN 1 ELSE 2 END, sm.joined_at";
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
        String sql = "INSERT INTO sect_members (sect_id, player_id, role) VALUES (?, ?, 'MEMBER')";
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
        if (kicker.isElder() && target.isElder()) {
            result.put("success", false); result.put("message", "长老不能踢出同级的其他长老"); return result;
        }

        deleteMember(kicker.getSectId(), targetPlayerId);
        result.put("success", true);
        result.put("message", "已将 " + target.getPlayerName() + " 逐出宗门");
        return result;
    }

    public Map<String, Object> appointMember(long appointerPlayerId, long targetPlayerId, String newRole) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember appointer = getPlayerMember(appointerPlayerId);
        if (appointer == null || !appointer.isLeader()) {
            result.put("success", false); result.put("message", "只有宗主才能任命宗门职位"); return result;
        }

        SectMember target = getMember(appointer.getSectId(), targetPlayerId);
        if (target == null) {
            result.put("success", false); result.put("message", "该玩家不在你的宗门中"); return result;
        }
        if (target.isLeader()) {
            result.put("success", false); result.put("message", "不能改变宗主的职位"); return result;
        }

        String role;
        switch (newRole.toLowerCase()) {
            case "长老", "elder": role = SectMember.ROLE_ELDER; break;
            case "弟子", "member": role = SectMember.ROLE_MEMBER; break;
            default:
                result.put("success", false);
                result.put("message", "无效的职位，可用：长老/弟子");
                return result;
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
        result.put("message", "已将 " + target.getPlayerName() + " 任命为" + SectMember.getRoleDisplayName(role));
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
        if (member == null || !member.canManage()) {
            result.put("success", false); result.put("message", "只有宗主和长老才能从仓库取出物品"); return result;
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

        String deductSql = "UPDATE sect_warehouse SET quantity = quantity - ? WHERE sect_id = ? AND item_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(deductSql)) {
            ps.setInt(1, quantity);
            ps.setLong(2, sectId);
            ps.setString(3, fullKey);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("取出物品失败", e); }

        itemService.addItem(playerId, fullKey, quantity);

        result.put("success", true);
        result.put("message", "已从宗门仓库取出【" + item.getName() + "】×" + quantity);
        return result;
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
