package com.mtxgdn.game.service;

import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.util.GameLogger;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 宗门各职责域共用的一点点基础设施。
 *
 * <p><b>为什么有它</b>：{@code SectService} 原来是 2383 行的上帝类，宗门基础、成员申请、
 * 仓库、宗门战、宗主继位、金库工资六个域的方法共享同一批私有字段与私有辅助方法。
 * 按"职责域 + 依赖集合"把这些方法拆到 {@code Sect*Domain} 之后，那些<b>跨域</b>用到的东西
 * （日志实例、{@code ResultSet} → 实体的映射、毫秒时间戳的展示格式化）就无处安放了 ——
 * 放到这里，各域类直接调用即可。</p>
 *
 * <p><b>只搬家，不改逻辑</b>：{@link #mapSect} / {@link #mapMember} / {@link #mapApplication} /
 * {@link #mapWarehouseItem} 与 {@link #formatTime} 的方法体都是从 {@code SectService}
 * 逐字搬过来的，唯一改动是可见性（{@code private} → 包级静态）与位置。列读取顺序、
 * 每列各自的容错 {@code try/catch}（老库缺列时退化为默认值）、时间格式串全部原样保留。</p>
 *
 * <p><b>日志类别也刻意照搬</b>：{@link #LOG} 仍然用 {@code SectService.class} 取类别。
 * 拆分前所有宗门日志都打在这个类别下，改成各域类名会让"按类名过滤日志文件"的行为
 * 发生变化 —— 那是行为改动，不属于本次纯结构重构。</p>
 *
 * <p>依赖（玩家服务 / 物品服务）不放在这里：它们由 {@code SectService} 从
 * {@code ServiceRegistry} 取一份，再通过构造函数注入给各域类，保证整个宗门域
 * 看到的是同一批实例，也让"某域到底依赖谁"在构造函数上就能读出来。</p>
 *
 * <p>本类无状态、不可实例化，只被同包的 {@code SectService} 与 {@code Sect*Domain} 使用，
 * 因此是包级可见的。</p>
 */
final class SectSupport {

    /** 宗门域日志实例（类别沿用拆分前的 {@code SectService}）。 */
    static final GameLogger LOG = GameLogger.getLogger(SectService.class);

    private SectSupport() {
    }

    /** 毫秒时间戳 → 本地时间字符串（文案展示用）。 */
    static String formatTime(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }

    /** {@code sects} 行 → 实体。 */
    static Sect mapSect(ResultSet rs) throws SQLException {
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
        try { s.setTreasury(rs.getLong("treasury")); } catch (SQLException ignored) {}
        try { s.setSalaryPerMember(rs.getLong("salary_per_member")); } catch (SQLException ignored) {}
        try { s.setLastSalaryTime(rs.getLong("last_salary_time")); } catch (SQLException ignored) {}
        try { s.setLeaderName(rs.getString("leader_name")); } catch (SQLException ignored) {}
        try { s.setMemberCount(rs.getInt("member_count")); } catch (SQLException ignored) {}
        return s;
    }

    /** {@code sect_members} 行（含 JOIN 出来的玩家列）→ 实体。 */
    static SectMember mapMember(ResultSet rs) throws SQLException {
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

    /** {@code sect_applications} 行（含 JOIN 出来的玩家名）→ 实体。 */
    static SectApplication mapApplication(ResultSet rs) throws SQLException {
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

    /** {@code sect_warehouse} 行（含 JOIN 出来的捐献者名）→ 实体。 */
    static SectWarehouseItem mapWarehouseItem(ResultSet rs) throws SQLException {
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
