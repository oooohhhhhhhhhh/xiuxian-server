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
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.mtxgdn.i18n.I18n;

public class SectService {

    private static final GameLogger LOG = GameLogger.getLogger(SectService.class);

    private final PlayerService playerService;
    private final ItemService itemService;
    private final Random random = new Random();

    public SectService() {
        this.playerService = ServiceRegistry.getPlayerService();
        this.itemService = ServiceRegistry.getItemService();
    }

    // ==================== 宗主缺位继位 / 竞争期：配置与调度 ====================

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** 宗主争夺战局数（三局两胜）。 */
    public static final int CONTEST_ROUNDS = 3;

    /** 挑战者需要赢下的局数。 */
    public static final int CONTEST_WINS_NEEDED = 2;

    /** 归一化后强者战力至少保留的比例：再强的宗主也不会被压到弱者的一半以下。 */
    private static final double MIN_CONTEST_RATIO = 0.5;

    /**
     * 继位扫描调度器。
     * <p>只负责两件低频维护：把"长期不活跃的宗主"标记为缺位、清理到期的竞争期。
     * 主动退位走的是同步路径，不依赖这个任务。</p>
     */
    private static final ScheduledExecutorService successionScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sect-succession-sweeper");
                t.setDaemon(true); // 守护线程：不阻止 JVM 退出
                return t;
            });

    static {
        // 首个 tick 延迟 45s（等数据库与服务注册表就绪），之后按配置间隔扫一次。
        successionScheduler.scheduleAtFixedRate(() -> {
            try {
                new SectService().sweepLeaderSuccession(System.currentTimeMillis());
            } catch (Throwable t) {
                // 绝不让异常冒泡：一次失败不应终止后续所有 tick
                LOG.warn("宗门宗主继位扫描任务异常: " + t);
            }
        }, 45, successionSweepSeconds(), TimeUnit.SECONDS);
    }

    /** 停止继位扫描任务（服务端关闭 / 测试清理时调用）。 */
    public static void shutdownSuccessionSweeper() {
        successionScheduler.shutdown();
    }

    /** 宗主连续不活跃多少天判定弃坑；0 = 关闭该判定（只保留主动退位路径）。 */
    public static int leaderAbsentDays() {
        return Math.max(0, AppConfig.getInt("sect.leader_absent_days", Sect.LEADER_ABSENT_DAYS_DEFAULT));
    }

    /** 竞争期持续天数。 */
    public static int contestDays() {
        return Math.max(1, AppConfig.getInt("sect.contest_days", Sect.CONTEST_DAYS_DEFAULT));
    }

    /** 竞争期开启的境界序位差门槛：小于该值才开竞争期。 */
    public static int contestLevelGap() {
        return Math.max(0, AppConfig.getInt("sect.contest_level_gap", Sect.CONTEST_LEVEL_GAP_DEFAULT));
    }

    /** 继位扫描间隔（秒），最小 30 秒。 */
    public static int successionSweepSeconds() {
        return Math.max(30, AppConfig.getInt("sect.succession_sweep_seconds", 600));
    }

    /**
     * 境界序位：把 (大境界, 小境界) 压成一个单调递增的整数。
     * <p>用于"继任者与竞争者实力是否接近"的判定。注意 {@code Player.level} 在本项目里
     * <b>并不随修炼推进</b>（只有建档与后台改库会写），因此不能拿它做实力比较。</p>
     */
    static int realmStage(Player player) {
        if (player == null) {
            return 0;
        }
        return player.getRealmStage();
    }

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
            result.put("message", I18n.t("sect.hefebafae"));
            return result;
        }
        final String name = sectName.trim();
        if (description != null && description.length() > 100) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h490fc096"));
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h59a76b2c"));
            return result;
        }

        if (player.getRealm() < Sect.MIN_LEVEL_CREATE) {
            String realmName = GameConfigLoader.getRealmConfig(player.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(player.getRealm(), 0).getFullName() : "凡人";
            result.put("success", false);
            result.put("message", I18n.t("sect.hf3a1ea6c", realmName));
            return result;
        }

        if (getPlayerSect(playerId) != null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h616b0664"));
            return result;
        }

        if (getSectByName(name) != null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hf52ad26c", name));
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < Sect.CREATE_COST_SPIRIT_STONES) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h97fb485c", Sect.CREATE_COST_SPIRIT_STONES, spiritStones));
            return result;
        }

        Sect sect = DatabaseManager.runTransaction(conn -> {
            // 扣除灵石（在事务内，跨等级扣除）
            if (!itemService.removeSpiritStones(conn, playerId, Sect.CREATE_COST_SPIRIT_STONES)) {
                throw new SQLException("灵石扣除失败");
            }
            String sql = "INSERT INTO sects (name, description, leader_player_id, prestige) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, description != null ? description : "一个新兴的修仙宗门");
                ps.setLong(3, playerId);
                // 初始声望 1000：使宗门升级/宗门战在创建后即可积累使用，避免声望死锁
                ps.setLong(4, 1000L);
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
                    // 直接构造 Sect 返回，避免事务内跨连接查询读不到未提交数据
                    Sect created = new Sect();
                    created.setId(sectId);
                    created.setName(name);
                    created.setDescription(description != null ? description : "一个新兴的修仙宗门");
                    created.setLeaderPlayerId(playerId);
                    created.setLeaderName(player.getName());
                    created.setLevel(1);
                    created.setPrestige(1000L);
                    created.setMemberCount(1);
                    return created;
                }
            }
            return null;
        });

        if (sect != null) {
            // 创建宗门授予「开山祖师」称号
            try {
                new com.mtxgdn.game.service.TitleService().grantTitle(playerId, "sect_founder");
            } catch (Exception ignored) {
            }
            result.put("success", true);
            result.put("message", I18n.t("sect.ha1d9a2dc", player.getName(), name, Sect.CREATE_COST_SPIRIT_STONES));
            result.put("sect", sect);
        } else {
            result.put("success", false);
            result.put("message", I18n.t("sect.h96ac1870"));
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
            result.put("success", false); result.put("message", I18n.t("sect.h59a76b2c")); return result;
        }

        Sect sect = getSectById(sectId);
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hbffa2528")); return result;
        }

        if (getPlayerSect(playerId) != null) {
            result.put("success", false); result.put("message", I18n.t("sect.h6052ac88")); return result;
        }

        List<SectMember> members = getSectMembers(sectId);
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
            result.put("success", false); result.put("message", I18n.t("sect.h71d5bb28")); return result;
        }

        Sect sect = getSectById(app.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hbffa2528")); return result;
        }

        SectMember approver = getMember(sect.getId(), approverPlayerId);
        if (approver == null || !approver.canManage()) {
            result.put("success", false); result.put("message", I18n.t("sect.h33d54619")); return result;
        }

        String status;
        String msg;
        if (approved) {
            List<SectMember> members = getSectMembers(sect.getId());
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
            result.put("success", false); result.put("message", I18n.t("sect.h6b4e3074")); return result;
        }

        Sect sect = getSectById(member.getSectId());
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
                        result.put("message", "身为一宗之主，不可随意离开。\n"
                                + "请先 /宗门 退位 卸任（系统会按贡献自动选出继任宗主），"
                                + "或 /宗门 解散 结束这个宗门");
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
            result.put("message", playerName + " 已离开宗门【" + sect.getName() + "】");
        }
        return result;
    }

    public Map<String, Object> kickMember(long kickerPlayerId, long targetPlayerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember kicker = getPlayerMember(kickerPlayerId);
        if (kicker == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h6b4e3074")); return result;
        }
        if (!kicker.canManage()) {
            result.put("success", false); result.put("message", I18n.t("sect.hcbedcf90")); return result;
        }

        SectMember target = getMember(kicker.getSectId(), targetPlayerId);
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

    public Map<String, Object> appointMember(long appointerPlayerId, long targetPlayerId, String newRole) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember appointer = getPlayerMember(appointerPlayerId);
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

        SectMember target = getMember(appointer.getSectId(), targetPlayerId);
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
                result.put("message", I18n.t("sect.h582e9a70"));
            } else {
                result.put("success", false);
                result.put("message", I18n.t("sect.h5378f0dc"));
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
            result.put("success", false); result.put("message", I18n.t("sect.h9e5d5d34")); return result;
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hadf213b8")); return result;
        }
        String fullKey = item.getFullKey();
        if (quantity <= 0) {
            result.put("success", false); result.put("message", I18n.t("sect.h893a9a8c")); return result;
        }
        if (!itemService.hasItem(playerId, fullKey, quantity)) {
            result.put("success", false); result.put("message", I18n.t("sect.h125ccfd8", item.getName())); return result;
        }

        // 修复刷贡献 bug：管理职位（宗主/副宗主/长老）可以免费从仓库取出物品，
        // 若捐献时仍发放贡献值，则取出→捐献→取出无限循环即可刷取海量贡献值。
        // 因此管理职位捐献不再获得贡献值（其特权已是免费取物），仅普通弟子捐献获得贡献值。
        // 贡献值按物品价格折算（约为取出成本的 1/3~1/2），保证"捐低价物换高价物"无利可图。
        final boolean isManager = member.canManage();
        final long contributionGain = isManager ? 0L : quantity * Math.max(2L, item.getPrice() / 10L);

        DatabaseManager.runTransaction(conn -> {
            itemService.removeItem(conn, playerId, fullKey, quantity);
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
            // 仅普通弟子捐献发放贡献值
            if (contributionGain > 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE sect_members SET contribution = contribution + ? WHERE sect_id = ? AND player_id = ?")) {
                    ps.setLong(1, contributionGain);
                    ps.setLong(2, sectId);
                    ps.setLong(3, playerId);
                    ps.executeUpdate();
                }
            }
            // 任何成员捐献都小幅增加宗门声望（声望是宗门升级/宗门战的资源，此前无来源导致死锁）
            long prestigeGain = Math.max(1L, quantity / 10L);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE sects SET prestige = prestige + ? WHERE id = ?")) {
                ps.setLong(1, prestigeGain);
                ps.setLong(2, sectId);
                ps.executeUpdate();
            }
            return null;
        });

        result.put("success", true);
        if (isManager) {
            result.put("message", I18n.t("sect.h69936cdc", item.getName(), quantity));
        } else {
            result.put("message", I18n.t("sect.he11c0848", item.getName(), quantity, contributionGain));
        }
        result.put("contributionGain", contributionGain);
        return result;
    }

    public Map<String, Object> withdrawFromWarehouse(long playerId, long sectId, String itemKey, int quantity) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = getMember(sectId, playerId);
        if (member == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h9e5d5d34")); return result;
        }
        if (quantity <= 0) {
            result.put("success", false); result.put("message", I18n.t("sect.h893a9a8c")); return result;
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hadf213b8")); return result;
        }
        String fullKey = item.getFullKey();

        int available = getWarehouseItemCount(sectId, fullKey);
        if (available < quantity) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hfb15b644", item.getName(), available)); return result;
        }

        boolean isManager = member.canManage();
        if (!isManager) {
            long costPerItem = getItemContributionCost(item);
            long totalCost = costPerItem * quantity;
            if (member.getContribution() < totalCost) {
                result.put("success", false);
                result.put("message", I18n.t("sect.h5c16ae54", item.getName(), quantity, totalCost, member.getContribution()));
                return result;
            }
        }

        // 单事务 + 条件扣除：贡献值、仓库数量、背包发放原子完成，防止并发超取/重复取物
        try {
            DatabaseManager.runTransaction(conn -> {
                if (!isManager) {
                    long totalCost = getItemContributionCost(item) * quantity;
                    String costSql = "UPDATE sect_members SET contribution = contribution - ? WHERE id = ? AND contribution >= ?";
                    try (PreparedStatement ps = conn.prepareStatement(costSql)) {
                        ps.setLong(1, totalCost);
                        ps.setLong(2, member.getId());
                        ps.setLong(3, totalCost);
                        if (ps.executeUpdate() == 0) {
                            throw new SQLException("贡献值不足");
                        }
                    }
                }

                String deductSql = "UPDATE sect_warehouse SET quantity = quantity - ? WHERE sect_id = ? AND item_key = ? AND quantity >= ?";
                try (PreparedStatement ps = conn.prepareStatement(deductSql)) {
                    ps.setInt(1, quantity);
                    ps.setLong(2, sectId);
                    ps.setString(3, fullKey);
                    ps.setInt(4, quantity);
                    if (ps.executeUpdate() == 0) {
                        throw new SQLException("仓库物品数量不足");
                    }
                }

                if (!itemService.addItem(conn, playerId, fullKey, quantity)) {
                    throw new SQLException("发放物品失败");
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            // "未知错误" 保留在代码里当兜底，不走词条：
            // 它只在异常消息为空时出现，属于"连系统都说不清"的例外情况
            result.put("message", I18n.t("sect.withdraw.failed",
                    e.getMessage() != null ? e.getMessage() : "未知错误"));
            return result;
        }

        String msg;
        if (isManager) {
            msg = "已从宗门仓库取出【" + item.getName() + "】×" + quantity;
        } else {
            long cost = getItemContributionCost(item) * quantity;
            msg = "已从宗门仓库取出【" + item.getName() + "】×" + quantity + "，消耗贡献值 " + cost;
        }
        result.put("success", true);
        result.put("message", msg);
        return result;
    }

    /**
     * 物品兑换贡献值（取价格/5，最低10）。
     * 高于捐献所得（价格/10），保证"捐献→取出"循环总是亏损，杜绝套利。
     */
    public long getItemContributionCost(Item item) {
        int price = item.getPrice();
        return Math.max(10, price / 5);
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
            result.put("success", false); result.put("message", I18n.t("sect.hf951c000")); return result;
        }

        Sect sect = getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hbffa2528")); return result;
        }
        if (sect.getLevel() >= Sect.MAX_LEVEL) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h3168868c", Sect.MAX_LEVEL)); return result;
        }

        long cost = Sect.getLevelUpCost(sect.getLevel());
        if (sect.getPrestige() < cost) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h21c77ff4", cost, sect.getPrestige()));
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
        result.put("message", I18n.t("sect.hc068d71a", sect.getName(), newLevel, cost, newMaxMembers));
        return result;
    }

    // ==================== 宗主转让 ====================

    public Map<String, Object> transferLeader(long fromPlayerId, long toPlayerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember fromMember = getPlayerMember(fromPlayerId);
        if (fromMember == null || !fromMember.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.h72d25ae4")); return result;
        }

        Sect sect = getSectById(fromMember.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hbffa2528")); return result;
        }

        SectMember toMember = getMember(sect.getId(), toPlayerId);
        if (toMember == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h6ee9c207")); return result;
        }
        if (toMember.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.hb527d929")); return result;
        }

        Player toPlayer = playerService.getPlayerById(toPlayerId);
        if (toPlayer == null) {
            result.put("success", false); result.put("message", I18n.t("sect.ha2ad4da4")); return result;
        }
        if (toPlayer.getRealm() < Sect.MIN_LEVEL_CREATE) {
            String realmName = GameConfigLoader.getRealmConfig(toPlayer.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(toPlayer.getRealm(), 0).getFullName() : "凡人";
            result.put("success", false);
            result.put("message", I18n.t("sect.h062ede40", realmName));
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(fromPlayerId);
        if (spiritStones < Sect.TRANSFER_COST_SPIRIT_STONES) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h6c450565", Sect.TRANSFER_COST_SPIRIT_STONES, spiritStones));
            return result;
        }
        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, fromPlayerId, Sect.TRANSFER_COST_SPIRIT_STONES)) {
                throw new Exception("扣除灵石失败");
            }
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

    /** 实验性插件名：宗门战（开关见 config/plugins.yml 的 plugins.experimental_sect_war，默认关闭）。 */
    private static final String SECT_WAR_PLUGIN = "experimental_sect_war";

    public Map<String, Object> declareWar(long attackerPlayerId, long defenderSectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 实验性插件门控：宗门战停用后，REST / WebSocket / 指令三个入口统一拒绝
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown(SECT_WAR_PLUGIN)) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hfe4ed1bc"));
            return result;
        }
        SectMember attackerMember = getPlayerMember(attackerPlayerId);
        if (attackerMember == null || !attackerMember.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.h07a48c10")); return result;
        }

        Sect attackerSect = getSectById(attackerMember.getSectId());
        if (attackerSect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.ha5ef1b70")); return result;
        }
        if (attackerSect.getId() == defenderSectId) {
            result.put("success", false); result.put("message", I18n.t("sect.h12f51fdc")); return result;
        }

        Sect defenderSect = getSectById(defenderSectId);
        if (defenderSect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h091dc05c")); return result;
        }

        List<SectMember> defenderMembers = getSectMembers(defenderSectId);
        if (defenderMembers.isEmpty()) {
            result.put("success", false); result.put("message", I18n.t("sect.h50602204")); return result;
        }

        // 宗门战冷却：同一防守方 24 小时内只能被宣战一次，防止无休止骚扰
        long warCooldownMs = 24 * 3600 * 1000L;
        String lastWarSql = "SELECT MAX(created_at) FROM sect_wars WHERE defender_sect_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(lastWarSql)) {
            ps.setLong(1, defenderSectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) {
                    long lastWarTime = rs.getTimestamp(1).getTime();
                    long remaining = warCooldownMs - (System.currentTimeMillis() - lastWarTime);
                    if (remaining > 0) {
                        result.put("success", false);
                        result.put("message", I18n.t("sect.hb6f9926c", Math.max(1, remaining / 3600000)));
                        return result;
                    }
                }
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门战记录失败", e); }

        if (attackerSect.getPrestige() < Sect.DECLARE_WAR_PRESTIGE_COST) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h72ced260", Sect.DECLARE_WAR_PRESTIGE_COST, attackerSect.getPrestige()));
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(attackerPlayerId);
        if (spiritStones < Sect.DECLARE_WAR_SPIRIT_STONE_COST) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h1009eea4", Sect.DECLARE_WAR_SPIRIT_STONE_COST, spiritStones));
            return result;
        }
        // 扣除灵石和声望（事务包裹）
        try {
            DatabaseManager.runTransaction(conn -> {
                if (!itemService.removeSpiritStones(conn, attackerPlayerId, Sect.DECLARE_WAR_SPIRIT_STONE_COST)) {
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
            result.put("message", I18n.t("sect.hfda59a4f", e.getMessage()));
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
            String loseSql = DatabaseManager.isSqlite() 
                    ? "UPDATE sects SET prestige = MAX(0, prestige - ?) WHERE id = ?"
                    : "UPDATE sects SET prestige = GREATEST(0, prestige - ?) WHERE id = ?";
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
        return duelFirstWins(a, b, log) ? a.getName() : b.getName();
    }

    /**
     * 纯函数对拼：{@code a} 是否获胜。不读写数据库、不改动传入的 Player。
     * <p>抽成布尔返回值是为了让宗主争夺战能直接判胜负 —— 按玩家名比较在同名时不可靠。</p>
     */
    private boolean duelFirstWins(Player a, Player b, List<String> log) {
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
                if (bHp <= 0) { log.add("  " + a.getName() + " 造成 " + dmg + " 伤害，" + b.getName() + " HP:" + Math.max(0, bHp) + "/" + b.getMaxHp()); return true; }

                dmg = calcWarDamage(bAtk, bRoot, aDef, aRoot);
                aHp -= dmg;
                if (aHp <= 0) { log.add("  " + b.getName() + " 造成 " + dmg + " 伤害，" + a.getName() + " HP:" + Math.max(0, aHp) + "/" + a.getMaxHp()); return false; }
            } else {
                int dmg = calcWarDamage(bAtk, bRoot, aDef, aRoot);
                aHp -= dmg;
                if (aHp <= 0) { log.add("  " + b.getName() + " 造成 " + dmg + " 伤害，" + a.getName() + " HP:" + Math.max(0, aHp) + "/" + a.getMaxHp()); return false; }

                dmg = calcWarDamage(aAtk, aRoot, bDef, bRoot);
                bHp -= dmg;
                if (bHp <= 0) { log.add("  " + a.getName() + " 造成 " + dmg + " 伤害，" + b.getName() + " HP:" + Math.max(0, bHp) + "/" + b.getMaxHp()); return true; }
            }
        }

        // HP百分比胜
        double aPct = (double) aHp / a.getMaxHp();
        double bPct = (double) bHp / b.getMaxHp();
        if (aPct >= bPct) {
            log.add("  双方鏖战" + maxRounds + "回合，" + a.getName() + " HP剩余" + aPct * 100 + "% 胜出");
            return true;
        } else {
            log.add("  双方鏖战" + maxRounds + "回合，" + b.getName() + " HP剩余" + bPct * 100 + "% 胜出");
            return false;
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

    // ==================== 宗主缺位继位 / 竞争期 ====================

    /**
     * 继位结果。
     * <p>{@link #succeeded} 为 false 时 {@link #note} 说明原因（宗主在任 / 竞争期进行中 /
     * 无可继位成员 / 状态被并发推进），调用方直接把它转成玩家提示即可。</p>
     */
    public static class SuccessionOutcome {
        public final boolean succeeded;
        public final long successorId;
        public final String successorName;
        public final long rivalId;
        public final String rivalName;
        /** >0 表示同时开启了竞争期，值为截止时间戳。 */
        public final long contestUntil;
        public final String note;

        private SuccessionOutcome(boolean succeeded, long successorId, String successorName,
                                  long rivalId, String rivalName, long contestUntil, String note) {
            this.succeeded = succeeded;
            this.successorId = successorId;
            this.successorName = successorName;
            this.rivalId = rivalId;
            this.rivalName = rivalName;
            this.contestUntil = contestUntil;
            this.note = note;
        }

        static SuccessionOutcome none(String note) {
            return new SuccessionOutcome(false, 0, null, 0, null, 0, note);
        }

        static SuccessionOutcome success(long successorId, String successorName,
                                         long rivalId, String rivalName, long contestUntil) {
            return new SuccessionOutcome(true, successorId, successorName, rivalId, rivalName, contestUntil, null);
        }

        public boolean hasContest() {
            return contestUntil > 0;
        }
    }

    /**
     * 宗主主动退位：卸任但仍留在宗门（降为副宗主），随后立刻按贡献选出继任者。
     *
     * <p>这是打破"宗主弃坑 → 宗门死锁"的关键入口：改造前宗主<b>既不能退位也不能退宗</b>
     * （{@link #leaveSect} 直接拒绝），一旦宗主不玩了，这个宗门就永远没人能接手。
     * 现在宗主可以先 {@code /宗门 退位}，再正常 {@code /宗门 退出}。</p>
     */
    public Map<String, Object> stepDown(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = getPlayerMember(playerId);
        if (member == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h6b4e3074"));
            return result;
        }
        if (!member.isLeader()) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h0349c594"));
            return result;
        }

        Sect sect = getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hbffa2528"));
            return result;
        }
        if (sect.getMemberCount() <= 1) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h2a6b3b5e"));
            return result;
        }

        long now = System.currentTimeMillis();
        markLeaderAbsent(sect.getId(), now);
        SuccessionOutcome outcome = resolveSuccession(sect.getId(), now);

        result.put("success", true);
        result.put("message", buildStepDownMessage(member.getPlayerName(), outcome));
        if (outcome.succeeded) {
            result.put("successorId", outcome.successorId);
            result.put("successorName", outcome.successorName);
            result.put("contestUntil", outcome.contestUntil);
        }
        return result;
    }

    /**
     * 按贡献继位：为缺位的宗门选出新宗主，并在继任者与最强竞争者实力接近时开启竞争期。
     *
     * <p>三条不变量：</p>
     * <ol>
     *   <li><b>幂等</b>：{@code leader_absent_since = 0} 或竞争期未结束时直接返回，不重复换人；</li>
     *   <li><b>并发安全</b>：落库用条件更新（{@code WHERE leader_absent_since = 读到的值}），
     *       指令与扫描任务同时触发时只有一个生效；</li>
     *   <li><b>现任宗主不是候选人</b>：被替换的人不会再被"选"回来 —— 否则退位等于没退。</li>
     * </ol>
     *
     * @param now 统一由调用方传入，便于测试注入时间（不要在方法内部取系统时间）
     */
    public SuccessionOutcome resolveSuccession(long sectId, long now) {
        Sect sect = getSectById(sectId);
        if (sect == null) {
            return SuccessionOutcome.none("宗门不存在");
        }
        if (!sect.isLeaderAbsent()) {
            return SuccessionOutcome.none("宗主在任");
        }
        if (sect.getContestUntil() > now) {
            return SuccessionOutcome.none("宗主竞争期正在进行中");
        }

        List<SectMember> members = getSectMembers(sectId);
        long incumbentId = sect.getLeaderPlayerId();

        List<SectMember> candidates = new ArrayList<>();
        for (SectMember m : members) {
            if (m.getPlayerId() != incumbentId) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            // 无人可继位：清掉缺位标记，等有新成员加入后由扫描任务重新判定。
            // 不清的话每次扫描都会重复命中同一条记录。
            clearLeaderAbsent(sectId, sect.getLeaderAbsentSince());
            return SuccessionOutcome.none("宗门内没有可继位的成员");
        }

        Map<Long, Player> players = loadPlayers(candidates);

        // 继任者：贡献 → 境界序位 → 玩家 ID（末位保证排序稳定，避免同分时结果抖动）
        candidates.sort(Comparator
                .comparingLong((SectMember m) -> -m.getContribution())
                .thenComparingInt(m -> -realmStage(players.get(m.getPlayerId())))
                .thenComparingLong(SectMember::getPlayerId));
        SectMember successor = candidates.get(0);

        // 竞争者：余下成员里「职位最高 → 境界最高 → 贡献最高」的第一名
        List<SectMember> rivals = new ArrayList<>(candidates.subList(1, candidates.size()));
        rivals.sort(Comparator
                .comparingInt(SectMember::getRoleRank)
                .thenComparingInt(m -> -realmStage(players.get(m.getPlayerId())))
                .thenComparingLong(m -> -m.getContribution())
                .thenComparingLong(SectMember::getPlayerId));
        SectMember rival = rivals.isEmpty() ? null : rivals.get(0);

        long contestUntil = 0;
        long challengerId = 0;
        if (rival != null) {
            int gap = realmStage(players.get(successor.getPlayerId())) - realmStage(players.get(rival.getPlayerId()));
            // 差距过大时挑战毫无意义，直接让继任者坐稳，省掉一次无意义的弹窗骚扰
            if (gap < contestLevelGap()) {
                contestUntil = now + (long) contestDays() * DAY_MS;
                challengerId = rival.getPlayerId();
            }
        }

        final long expectedAbsent = sect.getLeaderAbsentSince();
        final long newLeaderId = successor.getPlayerId();
        final long finalContestUntil = contestUntil;
        final long finalChallengerId = challengerId;

        Boolean applied = DatabaseManager.runTransaction(conn -> {
            int rows;
            String sql = "UPDATE sects SET leader_player_id = ?, leader_absent_since = 0, "
                    + "contest_until = ?, contest_challenger_id = ?, contest_requested = 0, "
                    + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND leader_absent_since = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, newLeaderId);
                ps.setLong(2, finalContestUntil);
                if (finalChallengerId > 0) {
                    ps.setLong(3, finalChallengerId);
                } else {
                    ps.setNull(3, Types.BIGINT);
                }
                ps.setLong(4, sectId);
                ps.setLong(5, expectedAbsent);
                rows = ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            if (rows == 0) {
                return false; // 状态已被其他请求推进，放弃本次继位
            }
            setMemberRole(conn, sectId, newLeaderId, SectMember.ROLE_LEADER);
            // 原宗主降为副宗主（若已退宗则这条 UPDATE 自然不命中任何行）
            if (incumbentId > 0) {
                setMemberRole(conn, sectId, incumbentId, SectMember.ROLE_VICE_LEADER);
            }
            return true;
        });

        if (!Boolean.TRUE.equals(applied)) {
            return SuccessionOutcome.none("继位状态已被其他请求处理");
        }

        LOG.info("宗门[" + sect.getName() + "] 宗主继位：playerId=" + newLeaderId
                + (challengerId > 0 ? "，开启竞争期至 " + contestUntil + "，挑战者 playerId=" + challengerId : ""));

        return SuccessionOutcome.success(
                newLeaderId,
                players.containsKey(newLeaderId) ? players.get(newLeaderId).getName() : successor.getPlayerName(),
                challengerId,
                challengerId > 0 && players.containsKey(challengerId) ? players.get(challengerId).getName() : null,
                contestUntil);
    }

    /**
     * 竞争期操作入口：挑战者发起、宗主查看状态、宗主应战。
     *
     * <p>两段式设计（而不是挑战者一发起就打）是为了给宗主一个明确的应战入口，
     * 也让"发起但宗主不应战"成为一种可记录的状态 —— 到期后宗主保住位置。</p>
     *
     * @param accept true = 宗主应战；false = 挑战者发起 / 宗主查看竞争期状态
     */
    public Map<String, Object> challengeLeader(long playerId, boolean accept) {
        Map<String, Object> result = new LinkedHashMap<>();
        Sect sect = getPlayerSect(playerId);
        if (sect == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h6b4e3074"));
            return result;
        }

        long now = System.currentTimeMillis();
        if (!sect.hasActiveContest(now)) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hd8088058"));
            return result;
        }

        boolean isChallenger = playerId == sect.getContestChallengerId();
        boolean isLeader = playerId == sect.getLeaderPlayerId();
        if (!isChallenger && !isLeader) {
            result.put("success", false);
            result.put("message", I18n.t("sect.he21dc790"));
            return result;
        }

        if (isChallenger) {
            if (accept) {
                result.put("success", false);
                result.put("message", I18n.t("sect.h3e4690e4"));
                return result;
            }
            return requestContest(sect, now);
        }

        // 宗主
        if (!accept) {
            result.put("success", true);
            result.put("message", buildContestStatus(sect));
            return result;
        }
        return acceptContest(sect, now);
    }

    /**
     * 竞争期 / 宗主缺位状态的一行式描述，供 {@code /宗门 info} 展示。
     *
     * @return 无竞争期且宗主在任时返回 {@code null}（调用方据此决定是否追加这一行）
     */
    public String leadershipStatus(Sect sect) {
        if (sect == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (sect.isLeaderAbsent()) {
            return "宗主缺位中：等待同门按贡献继任（自 " + formatTime(sect.getLeaderAbsentSince()) + " 起）";
        }
        if (sect.hasActiveContest(now)) {
            SectMember challenger = getMember(sect.getId(), sect.getContestChallengerId());
            String name = challenger != null && challenger.getPlayerName() != null
                    ? challenger.getPlayerName() : ("UID " + sect.getContestChallengerId());
            return "宗主竞争期至 " + formatTime(sect.getContestUntil()) + "，挑战者：" + name
                    + (sect.isContestRequested() ? "（已发起，待宗主应战）" : "（尚未发起）");
        }
        return null;
    }

    /** 宗主视角的竞争期状态说明。 */
    private String buildContestStatus(Sect sect) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 宗主竞争期 =====\n");
        sb.append("截止：").append(formatTime(sect.getContestUntil())).append("\n");
        sb.append("挑战者：");
        Player rival = playerService.getPlayerById(sect.getContestChallengerId());
        sb.append(rival != null ? rival.getName() : ("UID " + sect.getContestChallengerId())).append("\n");
        sb.append("状态：").append(sect.isContestRequested() ? "挑战者已发起，等待你应战" : "挑战者尚未发起").append("\n");
        if (sect.isContestRequested()) {
            sb.append("\n使用 /宗门 挑战 接受 应战（三局两胜，双方战力归一化）。\n");
            sb.append("不应战则竞争期结束后你继续担任宗主。");
        } else {
            sb.append("\n挑战者需先发起挑战，你才能应战。");
        }
        return sb.toString();
    }

    /** 挑战者发起挑战：置 {@code contest_requested}，等宗主应战。 */
    private Map<String, Object> requestContest(Sect sect, long now) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sect.isContestRequested()) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h937adf7c"));
            return result;
        }
        String sql = "UPDATE sects SET contest_requested = 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND contest_challenger_id = ? AND contest_until > ?";
        int rows;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sect.getId());
            ps.setLong(2, sect.getContestChallengerId());
            ps.setLong(3, now);
            rows = ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("发起宗主挑战失败", e);
        }
        if (rows == 0) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hb77b5854"));
            return result;
        }
        result.put("success", true);
        result.put("message", I18n.t("sect.h63a32578", formatTime(sect.getContestUntil())));
        return result;
    }

    /**
     * 宗主应战：三局两胜决定宗主之位。
     *
     * <p><b>归一化</b>：先把境界更高的一方按境界序位比例压低（下限 {@link #MIN_CONTEST_RATIO}），
     * 让争夺战考校的是灵根 / 装备 / 属性分配这些"构筑"，而不是单纯比谁境界高。
     * 归一化只作用于内存副本，<b>绝不回写</b>玩家真实属性。</p>
     */
    private Map<String, Object> acceptContest(Sect sect, long now) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!sect.isContestRequested()) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hb12ade10"));
            return result;
        }

        long leaderId = sect.getLeaderPlayerId();
        long challengerId = sect.getContestChallengerId();
        Player leader = playerService.getPlayerById(leaderId);
        Player challenger = playerService.getPlayerById(challengerId);
        if (leader == null || challenger == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h07ebe188"));
            return result;
        }

        int leaderStage = realmStage(leader);
        int challengerStage = realmStage(challenger);
        Player left = leader;
        Player right = challenger;
        if (leaderStage > challengerStage) {
            left = scaleDown(leader, challengerStage, leaderStage);
        } else if (challengerStage > leaderStage) {
            right = scaleDown(challenger, leaderStage, challengerStage);
        }

        List<String> battleLog = new ArrayList<>();
        battleLog.add("===== 宗主争夺战（三局两胜）=====");
        battleLog.add("宗主：" + leader.getName() + "（境界序位 " + leaderStage + "）");
        battleLog.add("挑战者：" + challenger.getName() + "（境界序位 " + challengerStage + "）");
        battleLog.add("双方战力已归一化至相近水位，胜负取决于灵根与构筑。");

        int challengerWins = 0;
        for (int round = 1; round <= CONTEST_ROUNDS; round++) {
            battleLog.add("── 第 " + round + " 局 ──");
            boolean leaderWon = duelFirstWins(left, right, battleLog);
            if (!leaderWon) {
                challengerWins++;
            }
            battleLog.add("  → 本局胜者：" + (leaderWon ? leader.getName() : challenger.getName()));
        }

        boolean challengerTakes = challengerWins >= CONTEST_WINS_NEEDED;
        long winnerId = challengerTakes ? challengerId : leaderId;
        long loserId = challengerTakes ? leaderId : challengerId;
        String winnerName = challengerTakes ? challenger.getName() : leader.getName();
        String loserName = challengerTakes ? leader.getName() : challenger.getName();

        final long finalWinnerId = winnerId;
        final long finalLoserId = loserId;
        Boolean applied = DatabaseManager.runTransaction(conn -> {
            int rows;
            // 条件更新：只有"挑战已发起 + 挑战者/宗主都没变"才落库，杜绝双端重复结算
            String sql = "UPDATE sects SET leader_player_id = ?, contest_until = 0, "
                    + "contest_challenger_id = NULL, contest_requested = 0, leader_absent_since = 0, "
                    + "updated_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND contest_requested = 1 AND contest_challenger_id = ? AND leader_player_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, finalWinnerId);
                ps.setLong(2, sect.getId());
                ps.setLong(3, challengerId);
                ps.setLong(4, leaderId);
                rows = ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            if (rows == 0) {
                return false;
            }
            setMemberRole(conn, sect.getId(), finalWinnerId, SectMember.ROLE_LEADER);
            setMemberRole(conn, sect.getId(), finalLoserId, SectMember.ROLE_VICE_LEADER);
            return true;
        });

        if (!Boolean.TRUE.equals(applied)) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hfad55360"));
            return result;
        }

        battleLog.add("");
        battleLog.add("══════════════════════");
        battleLog.add("挑战者胜局：" + challengerWins + " / " + CONTEST_ROUNDS);
        battleLog.add("【" + winnerName + "】继任宗主，" + loserName + " 改任副宗主");

        result.put("success", true);
        result.put("challengerWins", challengerWins);
        result.put("winnerId", winnerId);
        result.put("winnerName", winnerName);
        result.put("battleLog", String.join("\n", battleLog));
        result.put("message", I18n.t("sect.he75cb234", winnerName));
        return result;
    }

    /**
     * 低频维护扫描：清理到期竞争期 → 标记不活跃宗主为缺位 → 执行继位。
     *
     * @param now 统一由调用方传入（测试可注入任意时间）
     * @return 本次实际处理（换宗主 / 标记缺位 / 清理竞争期）的宗门数量
     */
    public int sweepLeaderSuccession(long now) {
        int handled = clearExpiredContests(now);

        int absentDays = leaderAbsentDays();
        if (absentDays > 0) {
            handled += markInactiveLeaders(now, absentDays);
        }

        for (long sectId : listAbsentLeaderSects(now)) {
            if (resolveSuccession(sectId, now).succeeded) {
                handled++;
            }
        }
        return handled;
    }

    /** 竞争期到期但未被挑战：宗主保住位置，只清字段。 */
    private int clearExpiredContests(long now) {
        String sql = "UPDATE sects SET contest_until = 0, contest_challenger_id = NULL, "
                + "contest_requested = 0, updated_at = CURRENT_TIMESTAMP "
                + "WHERE contest_until > 0 AND contest_until <= ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("清理到期宗主竞争期失败", e);
        }
    }

    /**
     * 把"长期不活跃的宗主"标记为缺位。
     *
     * <p>用 {@code players.last_active_time} 判定，且<b>只认 {@code > 0} 的行</b>：
     * 老库升级后该列全为 0（表示"尚未观测到活跃"），若把 0 当成纪元起点，
     * 升级瞬间会把全服宗主一次性判定成弃坑。</p>
     *
     * <p>SQL 刻意写成"先 SELECT id 再逐条 UPDATE"：MySQL 不允许在 UPDATE 的子查询里
     * 直接引用被更新的表（ERROR 1093），拆开写两种方言都安全。</p>
     */
    private int markInactiveLeaders(long now, int absentDays) {
        long cutoff = now - (long) absentDays * DAY_MS;
        List<Long> sectIds = new ArrayList<>();
        // 「成员数 > 1」是必须的：单人宗门无人可继位，标了缺位也只会在下一轮被清掉，
        // 徒增两次 UPDATE 并让 /宗门 info 的"宗主缺位中"来回闪烁。
        String sql = "SELECT s.id FROM sects s JOIN players p ON s.leader_player_id = p.id "
                + "WHERE s.leader_absent_since = 0 AND s.contest_until <= ? "
                + "AND p.last_active_time > 0 AND p.last_active_time < ? "
                + "AND (SELECT COUNT(*) FROM sect_members m WHERE m.sect_id = s.id) > 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sectIds.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询不活跃宗主失败", e);
        }

        int marked = 0;
        for (long sectId : sectIds) {
            marked += markLeaderAbsent(sectId, now);
        }
        return marked;
    }

    /** 列出"缺位且无进行中竞争期"的宗门，交给 {@link #resolveSuccession} 逐个处理。 */
    private List<Long> listAbsentLeaderSects(long now) {
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id FROM sects WHERE leader_absent_since > 0 AND contest_until <= ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询宗主缺位的宗门失败", e);
        }
        return ids;
    }

    /** 标记宗主缺位（条件更新：仅在当前仍在任时生效）。返回受影响行数。 */
    private int markLeaderAbsent(long sectId, long now) {
        String sql = "UPDATE sects SET leader_absent_since = ?, contest_until = 0, "
                + "contest_challenger_id = NULL, contest_requested = 0, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND leader_absent_since = 0";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, sectId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("标记宗主缺位失败", e);
        }
    }

    /** 清除缺位标记（条件更新：仅在值未被他人改动时生效）。 */
    private void clearLeaderAbsent(long sectId, long expectedAbsentSince) {
        String sql = "UPDATE sects SET leader_absent_since = 0, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND leader_absent_since = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, expectedAbsentSince);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("清除宗主缺位标记失败", e);
        }
    }

    /** 事务内设置成员职位。 */
    private void setMemberRole(Connection conn, long sectId, long playerId, String role) {
        String sql = "UPDATE sect_members SET role = ? WHERE sect_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            ps.setLong(2, sectId);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<Long, Player> loadPlayers(List<SectMember> members) {
        Map<Long, Player> players = new LinkedHashMap<>();
        for (SectMember member : members) {
            Player player = playerService.getPlayerById(member.getPlayerId());
            if (player != null) {
                players.put(player.getId(), player);
            }
        }
        return players;
    }

    /**
     * 按境界序位比例把强者压到弱者水位，返回<b>内存副本</b>（绝不回写真实属性）。
     */
    private Player scaleDown(Player stronger, int weakerStage, int strongerStage) {
        double ratio = (double) Math.max(1, weakerStage) / Math.max(1, strongerStage);
        ratio = Math.max(MIN_CONTEST_RATIO, Math.min(1.0, ratio));
        if (ratio >= 1.0) {
            return stronger;
        }
        Player copy = new Player();
        copy.setId(stronger.getId());
        copy.setName(stronger.getName());
        copy.setSpiritualRoot(stronger.getSpiritualRoot());
        copy.setRealm(stronger.getRealm());
        copy.setSubRealm(stronger.getSubRealm());
        copy.setMaxHp(scaleInt(stronger.getMaxHp(), ratio));
        copy.setHp(copy.getMaxHp());
        copy.setAttack(scaleInt(stronger.getAttack(), ratio));
        copy.setDefense(scaleInt(stronger.getDefense(), ratio));
        copy.setSpeed(scaleInt(stronger.getSpeed(), ratio));
        copy.setSpirit(scaleInt(stronger.getSpirit(), ratio));
        return copy;
    }

    private static int scaleInt(int value, double ratio) {
        return Math.max(1, (int) Math.round(value * ratio));
    }

    private String buildStepDownMessage(String formerLeaderName, SuccessionOutcome outcome) {
        if (!outcome.succeeded) {
            return "你已卸任宗主之位。\n" + (outcome.note != null ? outcome.note : "")
                    + "\n宗门暂由你继续代管，待有成员符合继位条件后自动交接。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你已卸任宗主之位，改任副宗主。\n");
        sb.append("宗门按贡献选出新宗主：").append(outcome.successorName != null ? outcome.successorName : "未知").append("\n");
        if (outcome.hasContest()) {
            sb.append("\n⚠ 你与新宗主的境界接近，已开启 ").append(contestDays()).append(" 天宗主竞争期：\n");
            sb.append("  挑战者：").append(outcome.rivalName != null ? outcome.rivalName : "未知").append("\n");
            sb.append("  截止：").append(formatTime(outcome.contestUntil)).append("\n");
            sb.append("  期内挑战者可发起三局两胜的争夺战，胜者继任宗主。");
        }
        sb.append("\n\n你现在可以正常使用 /宗门 退出 离开宗门。");
        return sb.toString();
    }

    /** 毫秒时间戳 → 本地时间字符串（文案展示用）。 */
    private static String formatTime(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }

    // ==================== 解散宗门 ====================

    public Map<String, Object> disbandSect(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = getPlayerMember(playerId);
        if (member == null || !member.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.he343e014")); return result;
        }

        Sect sect = getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.hbffa2528")); return result;
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
        result.put("message", I18n.t("sect.ha42a16cc", sect.getName()));
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
        // 继位/竞争期列：老库可能尚未迁移，读不到就当"宗主在任、无竞争期"
        try { s.setLeaderAbsentSince(rs.getLong("leader_absent_since")); } catch (SQLException ignored) {}
        try { s.setContestUntil(rs.getLong("contest_until")); } catch (SQLException ignored) {}
        try { s.setContestChallengerId(rs.getLong("contest_challenger_id")); } catch (SQLException ignored) {}
        try { s.setContestRequested(rs.getBoolean("contest_requested")); } catch (SQLException ignored) {}
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
