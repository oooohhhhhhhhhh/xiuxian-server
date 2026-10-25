package com.mtxgdn.db;

import com.mtxgdn.game.entity.Cave;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * {@code caves} 表的读写入口。
 *
 * <p>从 {@code CaveService} 抽出来的 DAO。查询类成对重载；事务内的
 * 插入 / 升级 / 收取只有 Connection 版。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>开辟洞府只返回生成的 id，不回读整行</b>：原注释写明这是修过的
 *       bug —— {@code 事务内用另一条连接查询刚插入的行}时，事务未提交，
 *       新连接看不到该行，于是返回 null 报"开辟失败"，但插入其实已经提交。
 *       现在事务内只拿 {@code RETURN_GENERATED_KEYS}，提交后由调用方另查。
 *       抽 DAO 时这个形状必须保住，{@link #insertCave} 因此返回 {@code long}
 *       而不是 {@link Cave}。</li>
 *   <li><b>{@code insertCave} 返回 0 表示拿不到自增键</b>：不是异常。
 *       原实现返回 {@code null}，调用方据此报"开辟洞府失败"。</li>
 *   <li><b>收取灵气先锁行再读</b>：{@code SELECT ... FOR UPDATE}，锁从句由
 *       dialect 给（SQLite 下事务本身即写锁）。没有这把锁，两个并发收取会
 *       读到同一份灵能、各发一次灵石。</li>
 *   <li><b>读不到行时灵能与时间都留在 0</b>：调用方随后会看到
 *       {@code lastCollectTime == 0}，于是跳过冷却判断直接走"灵能不足"分支。
 *       这是原实现的隐式行为，不是遗漏。</li>
 *   <li><b>冷却判断在 Java 里做</b>（{@link #findSpiritEnergyForUpdate} 只
 *       返回两列，不判断）：{@code collectInterval} 是业务常量，且要能注入
 *       测试时间。</li>
 *   <li><b>收取时把灵能清零并同时写 {@code last_collect_time}</b>：一条
 *       UPDATE 两个字段，不能拆 —— 拆开会留下"灵能已清零但冷却没开始"
 *       的窗口，玩家可以立刻再收一次。</li>
 *   <li><b>升级写 {@code updated_at = CURRENT_TIMESTAMP}</b>：数据库侧算的，
 *       不是绑定参数。</li>
 *   <li><b>灵气自增是两种方言</b>：SQLite 没有 {@code LEAST}/{@code GREATEST}，
 *       用 {@code MIN}/{@code MAX}。这条分支刻意留在 DAO 里（
 *       {@link #incrementSpiritEnergy}），因为它是纯粹的方言差异，
 *       不是业务判断。</li>
 *   <li><b>自增是 {@code +10} 的字面量</b>：不是绑定参数。它是"每 10 秒长
 *       10 点"这个设计的一部分，改了要连着调度周期一起改。</li>
 *   <li><b>自增是<b>全表</b>一条 UPDATE，不按 player_id</b>：它服务所有玩家，
 *       每个 tick 一次。这是原实现刻意的低频全表写（配合 10 秒周期），
 *       不是遗漏了 WHERE。</li>
 * </ul>
 */
public class CaveDAO {

    // ==================== 查询 ====================

    /** 某玩家的洞府。读不到返回 {@code null}（玩家还没开辟）。 */
    public Cave findCaveByPlayerId(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findCaveByPlayerId(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询洞府失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public Cave findCaveByPlayerId(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT * FROM caves WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapCave(rs) : null;
            }
        }
    }

    /**
     * 按 id 取洞府。
     *
     * <p><b>只在事务<b>提交之后</b>调用</b>：开辟洞府时事务内看不到刚插入的
     * 行（未提交，另一条连接读不到），所以原实现是"事务内拿 id、提交后另查"。
     * 在事务内调它会得到 {@code null}。</p>
     */
    public Cave findCaveById(long caveId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findCaveById(conn, caveId);
        } catch (SQLException e) {
            throw new RuntimeException("查询洞府失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public Cave findCaveById(Connection conn, long caveId) throws SQLException {
        String sql = "SELECT * FROM caves WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, caveId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapCave(rs) : null;
            }
        }
    }

    // ==================== 开辟 / 升级 ====================

    /**
     * 插入一座新洞府，返回自增 id（拿不到返回 0）。
     *
     * <p><b>只有 Connection 版</b>：与"扣灵石"必须同生共死。</p>
     *
     * <p><b>只返回 id，不回读整行</b>：事务内另一条连接看不到未提交的插入，
     * 回读会得到 {@code null} 并让调用方误报失败。调用方应在提交后调
     * {@link #findCaveById}。</p>
     *
     * <p>{@code level} 写死字面量 1、{@code spirit_energy} 写死 0：新洞府
     * 总是 1 级、空灵能。三个"按 1 级算"的值由调用方传入（
     * {@code Cave.getMaxSpiritEnergyForLevel(1)} 等），DAO 不引业务常量。</p>
     */
    public long insertCave(Connection conn, long playerId, String name, int maxSpiritEnergy,
                           int cultivationBonus, int storageBonus, long lastCollectTime)
            throws SQLException {
        String sql = "INSERT INTO caves (player_id, name, level, spirit_energy, max_spirit_energy, "
                + "cultivation_bonus, storage_bonus, last_collect_time) VALUES (?, ?, 1, 0, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, playerId);
            ps.setString(2, name);
            ps.setInt(3, maxSpiritEnergy);
            ps.setInt(4, cultivationBonus);
            ps.setInt(5, storageBonus);
            ps.setLong(6, lastCollectTime);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    /**
     * 升到指定等级：同时更新上限、修炼加成、储物加成。
     *
     * <p><b>只有 Connection 版</b>：与"扣灵石"必须同生共死。</p>
     *
     * <p>三个"按新等级算"的值由调用方传入：{@code Cave.getXxxForLevel} 是
     * 业务规则，DAO 不引。</p>
     */
    public void updateCaveLevel(Connection conn, long caveId, int newLevel, int maxSpiritEnergy,
                                int cultivationBonus, int storageBonus) throws SQLException {
        String sql = "UPDATE caves SET level = ?, max_spirit_energy = ?, "
                + "cultivation_bonus = ?, storage_bonus = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, newLevel);
            ps.setInt(2, maxSpiritEnergy);
            ps.setInt(3, cultivationBonus);
            ps.setInt(4, storageBonus);
            ps.setLong(5, caveId);
            ps.executeUpdate();
        }
    }

    // ==================== 收取灵气 ====================

    /**
     * 锁住洞府行并读出灵能与上次收取时间。
     *
     * <p><b>只有 Connection 版</b>：必须和随后的"清零 + 发灵石"在同一个
     * 事务里，否则并发收取会读到同一份灵能、各发一次。</p>
     *
     * <p><b>{@code FOR UPDATE} 不能少</b>：锁从句由
     * {@code DatabaseManager.getDialect().getForUpdateClause()} 给出
     * （SQLite 下事务本身即写锁，该从句为空串）。</p>
     *
     * <p>读不到行时两列都是 0：调用方随后会看到 {@code lastCollectTime == 0}，
     * 跳过冷却判断直接走"灵能不足"分支。这是原实现的隐式行为。</p>
     */
    public SpiritEnergyRow lockSpiritEnergy(Connection conn, long caveId) throws SQLException {
        String sql = "SELECT spirit_energy, last_collect_time FROM caves WHERE id = ? "
                + DatabaseManager.getDialect().getForUpdateClause();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, caveId);
            try (ResultSet rs = ps.executeQuery()) {
                SpiritEnergyRow row = new SpiritEnergyRow();
                if (rs.next()) {
                    row.spiritEnergy = rs.getLong("spirit_energy");
                    row.lastCollectTime = rs.getLong("last_collect_time");
                }
                return row;
            }
        }
    }

    /** 灵能与上次收取时间。读不到行时两者都是 0。 */
    public static class SpiritEnergyRow {
        public long spiritEnergy;
        public long lastCollectTime;
    }

    /**
     * 把灵能清零并记下这次收取时间。
     *
     * <p><b>两个字段必须同一条 UPDATE</b>：拆开会留下"灵能已清零但冷却没开始"
     * 的窗口，玩家可以立刻再收一次（收 0 点，但冷却被重置）。</p>
     *
     * <p><b>只有 Connection 版</b>：与发灵石同生共死。</p>
     */
    public void resetSpiritEnergy(Connection conn, long caveId, long now) throws SQLException {
        String sql = "UPDATE caves SET spirit_energy = 0, last_collect_time = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, caveId);
            ps.executeUpdate();
        }
    }

    /**
     * 条件扣减灵气（灵石提纯用）。
     *
     * <p><b>从 {@code EconomyDAO} 搬过来</b>：{@code caves} 表现在只归本 DAO。
     * 原来放在经济域的理由是"唯一的调用方是 {@code EconomyService}，
     * 抽到洞府域会让经济域为一个条件扣减去依赖洞府域" —— 那个理由在
     * {@code CaveService} 抽出 DAO 之后不再成立（依赖的是 {@code db} 包，
     * 不是服务层），而"一张表两个所有者"是更贵的债。</p>
     *
     * <p><b>只有 Connection 版</b>：提纯是"扣灵石 → 扣灵气 → 发高阶灵石"
     * 三步，必须共用一条连接。</p>
     *
     * <p><b>{@code WHERE ... spirit_energy >= ?} 是条件扣减</b>：
     * 灵气不足时 {@code executeUpdate()} 命中 0 行，此时整个事务要失败
     * （服务层抛 {@code "洞府灵气不足"}）。SQLite 没有
     * {@code GREATEST(0, ...)}，也不能写成
     * {@code SET spirit_energy = spirit_energy - ?} 再回查 ——
     * 那会把灵气扣成负数。</p>
     */
    public boolean consumeSpiritEnergy(Connection conn, long caveId, long auraCost) throws SQLException {
        String sql = "UPDATE caves SET spirit_energy = spirit_energy - ? "
                + "WHERE id = ? AND spirit_energy >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auraCost);
            ps.setLong(2, caveId);
            ps.setLong(3, auraCost);
            return ps.executeUpdate() > 0;
        }
    }

    // ==================== 灵气自增（定时任务） ====================

    /**
     * 全服洞府灵气 +10（不超过上限）。
     *
     * <p><b>两种方言</b>：SQLite 没有 {@code LEAST}，用 {@code MIN}。
     * 这条分支留在 DAO 里，因为它是纯粹的方言差异，不是业务判断。</p>
     *
     * <p><b>全表一条 UPDATE，不带 {@code player_id}</b>：它服务所有玩家，
     * 每个 tick 一次。这是原实现刻意的低频全表写（配合 10 秒周期），
     * 不是遗漏了 WHERE。</p>
     *
     * <p>{@code + 10} 是字面量：它是"每 10 秒长 10 点"这个设计的一部分，
     * 改了要连着调度周期一起改。</p>
     *
     * <p>自取连接版：定时任务没有外层事务。</p>
     */
    public void incrementSpiritEnergy() {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE caves SET spirit_energy = MIN(max_spirit_energy, spirit_energy + 10)"
                : "UPDATE caves SET spirit_energy = LEAST(max_spirit_energy, spirit_energy + 10)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新洞府灵气失败", e);
        }
    }

    /**
     * 在调用方连接上跑同一条自增。
     *
     * <p>方言分支与 {@link #incrementSpiritEnergy()} 共用一处，避免两份字面量
     * 漂移。</p>
     */
    public void incrementSpiritEnergy(Connection conn) throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE caves SET spirit_energy = MIN(max_spirit_energy, spirit_energy + 10)"
                : "UPDATE caves SET spirit_energy = LEAST(max_spirit_energy, spirit_energy + 10)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    // ==================== 行映射 ====================

    /** {@code caves} 行 → {@link Cave}。 */
    private static Cave mapCave(ResultSet rs) throws SQLException {
        Cave cave = new Cave();
        cave.setId(rs.getLong("id"));
        cave.setPlayerId(rs.getLong("player_id"));
        cave.setName(rs.getString("name"));
        cave.setLevel(rs.getInt("level"));
        cave.setSpiritEnergy(rs.getLong("spirit_energy"));
        cave.setMaxSpiritEnergy(rs.getInt("max_spirit_energy"));
        cave.setCultivationBonus(rs.getInt("cultivation_bonus"));
        cave.setStorageBonus(rs.getInt("storage_bonus"));
        cave.setLastCollectTime(rs.getLong("last_collect_time"));
        cave.setCreatedAt(rs.getString("created_at"));
        return cave;
    }
}
