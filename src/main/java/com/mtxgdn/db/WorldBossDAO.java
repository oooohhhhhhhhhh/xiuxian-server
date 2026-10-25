package com.mtxgdn.db;

import com.mtxgdn.game.entity.WorldBoss;
import com.mtxgdn.game.entity.WorldBossDamage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code world_bosses} / {@code world_boss_damage} 两张表的读写入口。
 *
 * <p>从 {@code WorldBossService} 抽出来的 DAO。查询类成对重载；结算、
 * 抢占、写账本这些必须在事务里的操作只有 Connection 版。</p>
 *
 * <p><b>这个域的三条不变量直接决定了 SQL 的形状</b>：</p>
 * <ol>
 *   <li><b>全服共享同一份血量</b>：{@code world_bosses.hp} 是唯一真相，
 *       没有内存态、没有定时器。因此"读血量 → 扣血 → 判击杀"必须在
 *       <b>一个事务 + 行锁</b>里，见 {@link #lockBossForSettlement}。</li>
 *   <li><b>奖励按伤害分红</b>：累计发放天然不超过预算，因为累计伤害不会
 *       超过最大血量。这是结构性保证，不依赖事后核对 —— 所以
 *       {@code recordDamage} 里 {@code damage} 的累加必须是
 *       {@code damage = damage + ?} 而不是覆盖写。</li>
 *   <li><b>挑战冷却靠条件更新抢占</b>：{@code last_hit_at} 既是"上次出手
 *       时间"也是"冷却令牌"，见 {@link #claimChallengeSlot}。</li>
 * </ol>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>重建池子先删台账再删池</b>：顺序不能反 —— 台账的删除条件
 *       是 {@code boss_id IN (SELECT id FROM world_bosses WHERE scope = ?)}，
 *       先删池会让子查询查不到 id，台账就永远删不掉，随着每次重建无限累积。</li>
 *   <li><b>结算用的血量读取带 {@code FOR UPDATE}</b>：否则两个并发挑战会
 *       读到同一个旧血量，各扣各的，最后血量比实际伤害之和还高（丢更新），
 *       而且两个人都以为自己补了最后一刀。</li>
 *   <li><b>扣血与判击杀是两条不同的 UPDATE</b>：击杀那条把
 *       {@code killed_at} 与 {@code killer_player_id} 一起写，未击杀那条只写
 *       {@code hp}。合成一条会需要"把 killer 传 NULL"的分支，反而更容易错。</li>
 *   <li><b>{@code updated_at = CURRENT_TIMESTAMP} 是数据库侧算的</b>：
 *       不是绑定参数。抽 DAO 时保留 —— 它与 {@code killed_at}（Java 侧
 *       {@code now}）语义不同，后者要能注入测试时间。</li>
 *   <li><b>账本写入是"先试 UPDATE，命中 0 行才 INSERT"</b>：这是没有
 *       UPSERT 方言差异的写法（SQLite 的 {@code ON CONFLICT} 与 MySQL 的
 *       {@code ON DUPLICATE KEY} 语法不同）。注意它<b>不是</b>原子的
 *       SELECT-then-UPDATE —— 直接 UPDATE 的命中行数就是判据，没有读的
 *       那一步。</li>
 *   <li><b>抢占冷却的三段式</b>：先条件 UPDATE；没抢到再查有没有账本行
 *       （有 = 冷却未过，拒）；没有行就 INSERT 一条零伤害的账本
 *       （{@code damage = 0, hits = 0}）。INSERT 撞唯一键说明并发对手刚
 *       插进去，于是回到条件 UPDATE 再判一次。</li>
 *   <li><b>抢占用的连接必须自动提交</b>：三段式依赖每条语句各自生效，
 *       尤其是 INSERT 撞唯一键之后还要能继续用同一条连接跑 UPDATE。
 *       放进事务里的话，MySQL 上唯一键冲突会让整条连接不可再用。</li>
 *   <li><b>冷却 cutoff 是 {@code now - max(0, cooldownMs)}</b>：
 *       冷却配成负数时不能把 cutoff 推到未来（那会让所有人永远不能打）。</li>
 *   <li><b>排行榜带 {@code damage > 0}</b>：只抢占过冷却、一次都没打中的
 *       记录不该上榜。{@code ORDER BY d.damage DESC, d.player_id ASC} 的
 *       第二键是必要的 —— 伤害相同时没有它顺序不稳定，分页会漏人或重复。</li>
 *   <li><b>排行榜的 {@code LIMIT} 是绑定参数且夹到至少 1</b>：
 *       {@code LIMIT 0} 在部分数据库上返回全部行，不是"零行"。</li>
 *   <li><b>{@code killer_name} / {@code player_name} 用 try/catch 兜</b>：
 *       没有 JOIN {@code players} 的查询里没有这两列，读不到就当没有，
 *       而不是让整个映射失败。</li>
 *   <li><b>{@code b.*} / {@code d.*} 的列头</b>：保留 {@code *} 而不是
 *       展开列名 —— 表结构演进时映射靠列名而不是位置，展开反而更容易漏。</li>
 *   <li><b>池子查询按 {@code b.slot} 排序</b>：序号是玩家看到的"第几只"，
 *       顺序必须稳定，否则同一个序号每次查询指向不同的 Boss。</li>
 * </ul>
 */
public class WorldBossDAO {

    // ==================== 池子 ====================

    /**
     * 全服最高境界（用于决定这批 Boss 的境界上限）。
     *
     * <p>没有玩家时返回 0：{@code MAX(realm)} 在空表上是 NULL，
     * {@code getInt} 得到 0。境界下限由调用方夹（{@code Math.max(3, ...)}）。</p>
     *
     * <p>0 参方法，不需要 Connection 重载。</p>
     */
    public int findMaxPlayerRealm() {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT MAX(realm) FROM players");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("读取全服最高境界失败", e);
        }
    }

    /**
     * 某个 scope 的整池 Boss（带击杀者名字）。
     *
     * <p>{@code LEFT JOIN} 不能改成 INNER JOIN：没被击杀的 Boss
     * {@code killer_player_id} 是 0 或 NULL，INNER JOIN 会把它们整行滤掉，
     * 于是池子看起来是空的。</p>
     */
    public List<WorldBoss> findPool(String scope) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findPool(conn, scope);
        } catch (SQLException e) {
            throw new RuntimeException("查询世界Boss失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<WorldBoss> findPool(Connection conn, String scope) throws SQLException {
        String sql = "SELECT b.*, p.name AS killer_name FROM world_bosses b "
                + "LEFT JOIN players p ON b.killer_player_id = p.id "
                + "WHERE b.scope = ? ORDER BY b.slot";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scope);
            try (ResultSet rs = ps.executeQuery()) {
                List<WorldBoss> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapBoss(rs));
                }
                return result;
            }
        }
    }

    /** 按 id 取一只 Boss（带击杀者名字）。 */
    public WorldBoss findBossById(long bossId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findBossById(conn, bossId);
        } catch (SQLException e) {
            throw new RuntimeException("查询世界Boss失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。读不到返回 {@code null}。 */
    public WorldBoss findBossById(Connection conn, long bossId) throws SQLException {
        String sql = "SELECT b.*, p.name AS killer_name FROM world_bosses b "
                + "LEFT JOIN players p ON b.killer_player_id = p.id WHERE b.id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bossId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapBoss(rs) : null;
            }
        }
    }

    /**
     * 删掉某个 scope 下所有 Boss 的伤害台账。
     *
     * <p><b>必须在新池插入之前、且在删池之前执行</b>：条件是
     * {@code boss_id IN (SELECT id FROM world_bosses WHERE scope = ?)}，
     * 先删池会让子查询查不到 id，台账就永远删不掉，随着每次重建无限累积。</p>
     */
    public void deleteDamageForScope(Connection conn, String scope) throws SQLException {
        String sql = "DELETE FROM world_boss_damage WHERE boss_id IN "
                + "(SELECT id FROM world_bosses WHERE scope = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scope);
            ps.executeUpdate();
        }
    }

    /** 删掉某个 scope 下所有 Boss。 */
    public void deleteBossesForScope(Connection conn, String scope) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM world_bosses WHERE scope = ?")) {
            ps.setString(1, scope);
            ps.executeUpdate();
        }
    }

    /**
     * 插入一只 Boss。
     *
     * <p>{@code killed_at} 写的是字面量 {@code 0} 而不是占位符：
     * 表里用 0 表示"未被击杀"（不是 NULL），与 {@code WorldBoss.isDead()} 对应。</p>
     */
    public void insertBoss(Connection conn, WorldBoss boss) throws SQLException {
        String sql = "INSERT INTO world_bosses (scope, slot, name, description, realm, hp, max_hp, "
                + "attack, defense, speed, stone_budget, exp_budget, expires_at, killed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, boss.getScope());
            ps.setInt(2, boss.getSlot());
            ps.setString(3, boss.getName());
            ps.setString(4, boss.getDescription());
            ps.setInt(5, boss.getRealm());
            ps.setLong(6, boss.getHp());
            ps.setLong(7, boss.getMaxHp());
            ps.setInt(8, boss.getAttack());
            ps.setInt(9, boss.getDefense());
            ps.setInt(10, boss.getSpeed());
            ps.setLong(11, boss.getStoneBudget());
            ps.setLong(12, boss.getExpBudget());
            ps.setLong(13, boss.getExpiresAt());
            ps.executeUpdate();
        }
    }

    // ==================== 结算 ====================

    /**
     * 锁住 Boss 行并读出结算需要的四个值。
     *
     * <p><b>{@code FOR UPDATE} 不能少</b>：否则两个并发挑战会读到同一个旧
     * 血量，各扣各的，最后血量比实际伤害之和还高（丢更新），而且两个人都
     * 以为自己补了最后一刀。锁从句由
     * {@code DatabaseManager.getDialect().getForUpdateClause()} 给出
     * （SQLite 下事务本身即写锁，该从句为空串）。</p>
     *
     * <p>读不到行抛 {@link IllegalStateException}：那是"传了一个不存在的
     * bossId"，属于调用方的编程错误，不是可以继续的运行时状况。</p>
     */
    public BossSettlementRow lockBossForSettlement(Connection conn, long bossId) throws SQLException {
        String sql = "SELECT hp, max_hp, stone_budget, exp_budget FROM world_bosses WHERE id = ? "
                + DatabaseManager.getDialect().getForUpdateClause();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bossId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("世界Boss不存在: " + bossId);
                }
                BossSettlementRow row = new BossSettlementRow();
                // 位置下标（1..4）而不是列名：这条 SELECT 的列序就是
                // hp / max_hp / stone_budget / exp_budget，原实现如此
                row.hp = rs.getLong(1);
                row.maxHp = rs.getLong(2);
                row.stoneBudget = rs.getLong(3);
                row.expBudget = rs.getLong(4);
                return row;
            }
        }
    }

    /** 结算需要的四个值。 */
    public static class BossSettlementRow {
        public long hp;
        public long maxHp;
        public long stoneBudget;
        public long expBudget;
    }

    /**
     * 扣血（未击杀）。
     *
     * <p>{@code updated_at = CURRENT_TIMESTAMP} 是数据库侧算的，不是绑定参数 ——
     * 与 {@code killed_at}（Java 侧的 {@code now}）语义不同，后者要能注入
     * 测试时间。</p>
     */
    public void updateBossHp(Connection conn, long bossId, long newHp) throws SQLException {
        String sql = "UPDATE world_bosses SET hp = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, newHp);
            ps.setLong(2, bossId);
            ps.executeUpdate();
        }
    }

    /**
     * 标记击杀：血量归零 + 记录击杀时间与击杀者。
     *
     * <p>与 {@link #updateBossHp} 是两条不同的 UPDATE，不合并：合并需要
     * "把 killer 传 NULL"的分支，反而更容易错。</p>
     */
    public void markBossKilled(Connection conn, long bossId, long now, long killerPlayerId)
            throws SQLException {
        String sql = "UPDATE world_bosses SET hp = 0, killed_at = ?, killer_player_id = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, killerPlayerId);
            ps.setLong(3, bossId);
            ps.executeUpdate();
        }
    }

    // ==================== 伤害台账 ====================

    /**
     * 累加一次伤害（没有该玩家的账本行就新建一条）。
     *
     * <p><b>先试 UPDATE，命中 0 行才 INSERT</b>：这是没有 UPSERT 方言差异
     * 的写法（SQLite 的 {@code ON CONFLICT DO UPDATE} 与 MySQL 的
     * {@code ON DUPLICATE KEY UPDATE} 语法不同）。注意它<b>不是</b>原子的
     * SELECT-then-UPDATE —— 直接 UPDATE 的命中行数就是判据，没有读的那一步。</p>
     *
     * <p><b>{@code damage = damage + ?} 与 {@code hits = hits + 1} 必须是累加</b>：
     * 奖励按"预算 × 本次伤害 / 最大血量"分红，账本里的 {@code damage} 是
     * 排行榜与后续分红的依据，覆盖写会让累计发放超过预算。</p>
     *
     * <p><b>INSERT 分支的 {@code hits} 写的是字面量 1</b>：这是第一次命中，
     * 不是 0。</p>
     */
    public void recordDamage(Connection conn, long bossId, long playerId, long damage, long now)
            throws SQLException {
        String update = "UPDATE world_boss_damage SET damage = damage + ?, hits = hits + 1, last_hit_at = ? "
                + "WHERE boss_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setLong(1, damage);
            ps.setLong(2, now);
            ps.setLong(3, bossId);
            ps.setLong(4, playerId);
            if (ps.executeUpdate() > 0) {
                return;
            }
        }
        String insert = "INSERT INTO world_boss_damage (boss_id, player_id, damage, hits, last_hit_at) "
                + "VALUES (?, ?, ?, 1, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setLong(1, bossId);
            ps.setLong(2, playerId);
            ps.setLong(3, damage);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    /**
     * 抢占挑战冷却槽：条件更新，只有"冷却确实已过"的那一次返回 true。
     *
     * <p>自取连接版（自动提交）：三段式依赖每条语句各自生效。</p>
     */
    public boolean claimChallengeSlot(long bossId, long playerId, long now, long cooldownMs) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return claimChallengeSlot(conn, bossId, playerId, now, cooldownMs);
        } catch (SQLException e) {
            throw new RuntimeException("抢占世界Boss挑战冷却失败", e);
        }
    }

    /**
     * 抢占挑战冷却槽（事务内版本）。
     *
     * <p><b>调用方的连接必须是自动提交的</b>：INSERT 撞唯一键之后还要能继续
     * 用同一条连接跑 UPDATE。放进事务里的话，MySQL 上唯一键冲突会让整条
     * 连接不可再用。这个方法签名存在只为满足"每个自取连接方法都有
     * Connection 重载"的一致性约束，正常路径走自取连接版。</p>
     *
     * <p>三段式：</p>
     * <ol>
     *   <li>条件 UPDATE {@code last_hit_at <= cutoff} —— 冷却已过则抢占成功；</li>
     *   <li>没抢到且已有账本行 —— 冷却未过，拒；</li>
     *   <li>没有账本行 —— INSERT 一条零伤害零命中的行（{@code damage = 0, hits = 0}），
     *       撞唯一键说明并发对手刚插进去，回到条件 UPDATE 再判一次。</li>
     * </ol>
     *
     * <p>{@code cutoff = now - max(0, cooldownMs)}：冷却配成负数时不能把
     * cutoff 推到未来（那会让所有人永远不能打）。</p>
     */
    public boolean claimChallengeSlot(Connection conn, long bossId, long playerId,
                                      long now, long cooldownMs) throws SQLException {
        long cutoff = now - Math.max(0, cooldownMs);
        String update = "UPDATE world_boss_damage SET last_hit_at = ? "
                + "WHERE boss_id = ? AND player_id = ? AND last_hit_at <= ?";
        if (tryClaimSlot(conn, update, bossId, playerId, now, cutoff)) {
            return true;
        }
        if (hasDamageRow(conn, bossId, playerId)) {
            return false; // 记录存在但冷却未过
        }
        String insert = "INSERT INTO world_boss_damage (boss_id, player_id, damage, hits, last_hit_at) "
                + "VALUES (?, ?, 0, 0, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setLong(1, bossId);
            ps.setLong(2, playerId);
            ps.setLong(3, now);
            ps.executeUpdate();
            return true;
        } catch (SQLException duplicate) {
            // 并发插入撞唯一键：回到条件更新再判一次
            return tryClaimSlot(conn, update, bossId, playerId, now, cutoff);
        }
    }

    /**
     * 条件更新抢占（内部）。
     *
     * <p><b>private</b>：它是 {@link #claimChallengeSlot} 的一段，不是独立
     * 意图。公开它只会让人绕开三段式的前置判断。</p>
     */
    private boolean tryClaimSlot(Connection conn, String update, long bossId, long playerId,
                                 long now, long cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setLong(1, now);
            ps.setLong(2, bossId);
            ps.setLong(3, playerId);
            ps.setLong(4, cutoff);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 该玩家在这只 Boss 上是否有账本行。
     *
     * <p>{@code SELECT 1} 而不是 {@code SELECT *}：只判存在。</p>
     *
     * <p><b>private</b>：只服务于 {@link #claimChallengeSlot} 的中间判断。
     * 对外读账本走 {@link #findDamage}。</p>
     */
    private boolean hasDamageRow(Connection conn, long bossId, long playerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM world_boss_damage WHERE boss_id = ? AND player_id = ?")) {
            ps.setLong(1, bossId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 某玩家在某只 Boss 上的伤害账本。读不到返回 {@code null}。 */
    public WorldBossDamage findDamage(long bossId, long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findDamage(conn, bossId, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询世界Boss伤害记录失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public WorldBossDamage findDamage(Connection conn, long bossId, long playerId) throws SQLException {
        String sql = "SELECT d.*, p.name AS player_name FROM world_boss_damage d "
                + "LEFT JOIN players p ON d.player_id = p.id "
                + "WHERE d.boss_id = ? AND d.player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bossId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapDamage(rs) : null;
            }
        }
    }

    /**
     * 伤害贡献榜（按伤害降序）。
     *
     * <p><b>带 {@code damage > 0}</b>：只抢占过冷却、一次都没打中的记录不该上榜。</p>
     *
     * <p><b>第二排序键 {@code player_id ASC} 是必要的</b>：伤害相同时没有它
     * 顺序不稳定，分页会漏人或重复。</p>
     *
     * <p><b>{@code LIMIT} 是绑定参数且夹到至少 1</b>：{@code LIMIT 0} 在部分
     * 数据库上返回全部行，不是"零行"。</p>
     */
    public List<WorldBossDamage> findLeaderboard(long bossId, int limit) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findLeaderboard(conn, bossId, limit);
        } catch (SQLException e) {
            throw new RuntimeException("查询世界Boss排行榜失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<WorldBossDamage> findLeaderboard(Connection conn, long bossId, int limit)
            throws SQLException {
        String sql = "SELECT d.*, p.name AS player_name FROM world_boss_damage d "
                + "LEFT JOIN players p ON d.player_id = p.id "
                + "WHERE d.boss_id = ? AND d.damage > 0 ORDER BY d.damage DESC, d.player_id ASC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bossId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<WorldBossDamage> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapDamage(rs));
                }
                return result;
            }
        }
    }

    // ==================== 行映射 ====================

    /**
     * {@code world_bosses} 行 → {@link WorldBoss}。
     *
     * <p>{@code b.*} 的列头：保留 {@code *} 而不是展开列名 —— 表结构演进时
     * 映射靠列名而不是位置。</p>
     */
    private static WorldBoss mapBoss(ResultSet rs) throws SQLException {
        WorldBoss boss = new WorldBoss();
        boss.setId(rs.getLong("id"));
        boss.setScope(rs.getString("scope"));
        boss.setSlot(rs.getInt("slot"));
        boss.setName(rs.getString("name"));
        boss.setDescription(rs.getString("description"));
        boss.setRealm(rs.getInt("realm"));
        boss.setHp(rs.getLong("hp"));
        boss.setMaxHp(rs.getLong("max_hp"));
        boss.setAttack(rs.getInt("attack"));
        boss.setDefense(rs.getInt("defense"));
        boss.setSpeed(rs.getInt("speed"));
        boss.setStoneBudget(rs.getLong("stone_budget"));
        boss.setExpBudget(rs.getLong("exp_budget"));
        boss.setExpiresAt(rs.getLong("expires_at"));
        boss.setKilledAt(rs.getLong("killed_at"));
        boss.setKillerPlayerId(rs.getLong("killer_player_id"));
        try {
            boss.setKillerName(rs.getString("killer_name"));
        } catch (SQLException ignored) {
            // 没有 JOIN players 的查询里没有这一列
        }
        return boss;
    }

    /** {@code world_boss_damage} 行 → {@link WorldBossDamage}。 */
    private static WorldBossDamage mapDamage(ResultSet rs) throws SQLException {
        WorldBossDamage d = new WorldBossDamage();
        d.setBossId(rs.getLong("boss_id"));
        d.setPlayerId(rs.getLong("player_id"));
        d.setDamage(rs.getLong("damage"));
        d.setHits(rs.getInt("hits"));
        d.setLastHitAt(rs.getLong("last_hit_at"));
        try {
            d.setPlayerName(rs.getString("player_name"));
        } catch (SQLException ignored) {
            // 没有 JOIN players 的查询里没有这一列
        }
        return d;
    }
}
