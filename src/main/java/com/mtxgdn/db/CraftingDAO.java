package com.mtxgdn.db;

import com.mtxgdn.game.entity.Recipe;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 配方（{@code recipes} 表）的读写入口。
 *
 * <p>从 {@code CraftingService} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询配方列表失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：{@code craft} 是"扣材料 → 扣金币 →
 * 扣灵石 → 发产物 → 发经验"的单体事务（{@code DatabaseManager.runTransaction}
 * 的 lambda），而它在扣材料之前要先 {@code getRecipeById} 拿配方定义。
 * 原实现那次查询是自取连接的短连接 —— 读到的配方可能与事务里的库存状态差一点点，
 * 但配方表是<b>静态出厂数据</b>，运行时不会被改，所以这个时间窗没有实际危害。
 * 尽管如此，Connection 重载仍然必须提供：插件（{@code PluginContext.getCraftingService()}）
 * 和后续的"炼制活动"都可能把配方查询放进自己的事务里，到那时没有 Connection
 * 重载就只能另开连接，SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}。</p>
 *
 * <p><b>不显然的原语义</b>：</p>
 * <ul>
 *   <li>三条查询都是 {@code SELECT *}，靠 {@code mapRecipe} 按列名取字段。
 *       列清单变了不会编译失败，所以这里逐字保留 {@code SELECT *}。</li>
 *   <li>{@code insertDefaultRecipes} 的幂等靠"按名称查 COUNT(*) > 0 就跳过"，
 *       <b>不是</b>靠主键冲突。这条 check 与 insert 必须共用同一条连接：
 *       分开两次连接时，中间的失败会留下"检查过但其实没插"的状态。</li>
 *   <li>同一方法里还有一段"历史配方改名"：新名没被占用时才把旧名改掉。
 *       这个 {@code SELECT COUNT(*)} + {@code UPDATE} 同样共用同一条连接。</li>
 *   <li>{@code material2_key} / {@code material3_key} 用
 *       {@code setObject(..., Types.VARCHAR)} 写 NULL，而 {@code material2_count} /
 *       {@code material3_count} 是 {@code row[..] == null ? 0 : ...}。
 *       "key 可以为空、数量必须补 0"这个不对称必须保留。</li>
 * </ul>
 */
public class CraftingDAO {

    /** 全部配方，按 category + required_realm 排序。 */
    public List<Recipe> findAllRecipes() {
        String sql = "SELECT * FROM recipes ORDER BY category, required_realm";
        List<Recipe> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(mapRecipe(rs));
        } catch (SQLException e) { throw new RuntimeException("查询配方列表失败", e); }
        return result;
    }

    public List<Recipe> findAllRecipes(Connection conn) throws SQLException {
        String sql = "SELECT * FROM recipes ORDER BY category, required_realm";
        List<Recipe> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(mapRecipe(rs));
        }
        return result;
    }

    /** 某一分类下的配方，按 required_realm 排序。 */
    public List<Recipe> findRecipesByCategory(String categoryName) {
        String sql = "SELECT * FROM recipes WHERE category = ? ORDER BY required_realm";
        List<Recipe> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, categoryName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapRecipe(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询配方列表失败", e); }
        return result;
    }

    public List<Recipe> findRecipesByCategory(Connection conn, String categoryName) throws SQLException {
        String sql = "SELECT * FROM recipes WHERE category = ? ORDER BY required_realm";
        List<Recipe> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, categoryName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapRecipe(rs));
            }
        }
        return result;
    }

    /** 单个配方；没有这个 id 返回 null。 */
    public Recipe findRecipeById(long recipeId) {
        String sql = "SELECT * FROM recipes WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, recipeId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return mapRecipe(rs); }
        } catch (SQLException e) { throw new RuntimeException("查询配方失败", e); }
        return null;
    }

    public Recipe findRecipeById(Connection conn, long recipeId) throws SQLException {
        String sql = "SELECT * FROM recipes WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, recipeId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return mapRecipe(rs); }
        }
        return null;
    }

    /** 按名称判断配方是否已存在（出厂数据幂等的依据）。 */
    public boolean existsByName(Connection conn, String recipeName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM recipes WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recipeName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 插入一条配方。
     *
     * <p>列清单与参数个数（20 个）逐字保留。{@code material2_key} /
     * {@code material3_key} 走 {@code setObject(..., Types.VARCHAR)} 以写入 NULL，
     * 对应数量则是 {@code null ? 0 : ...} —— 这个不对称不能"顺手统一"。</p>
     */
    public void insertRecipe(Connection conn, Object[] row) throws SQLException {
        String sql = """
            INSERT INTO recipes (name, description, category, required_realm,
                result_item_key, result_quantity,
                material1_key, material1_count, material2_key, material2_count, material3_key, material3_count,
                cost_gold, cost_spirit_stones, success_rate, min_exp_gain, max_exp_gain,
                high_quality_rate, max_quality_rate, quality_bonus_rate)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (String) row[0]);
            ps.setString(2, (String) row[1]);
            ps.setString(3, (String) row[2]);
            ps.setInt(4, (int) row[3]);
            ps.setString(5, (String) row[4]);
            ps.setInt(6, (int) row[5]);
            ps.setString(7, (String) row[6]);
            ps.setInt(8, (int) row[7]);
            ps.setObject(9, row[8], java.sql.Types.VARCHAR);
            ps.setInt(10, row[9] == null ? 0 : (int) row[9]);
            ps.setObject(11, row[10], java.sql.Types.VARCHAR);
            ps.setInt(12, row[11] == null ? 0 : (int) row[11]);
            ps.setLong(13, (long) row[12]);
            ps.setLong(14, (long) row[13]);
            ps.setDouble(15, (double) row[14]);
            ps.setLong(16, (long) row[15]);
            ps.setLong(17, (long) row[16]);
            ps.setDouble(18, (double) row[17]);
            ps.setDouble(19, (double) row[18]);
            ps.setDouble(20, (double) row[19]);
            ps.executeUpdate();
        }
    }

    /**
     * 历史配方改名（名称与产物对齐，保留原配方 ID）。
     *
     * <p>只在<b>新名没被占用</b>时才改 —— 否则会把另一条配方挤掉。
     * 调用方负责保证这个"先查再改"与 {@link #existsByName} 共用同一条连接。</p>
     *
     * @return 受影响行数
     */
    public int renameRecipe(Connection conn, String oldName, String newName) throws SQLException {
        String sql = "UPDATE recipes SET name = ? WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setString(2, oldName);
            return ps.executeUpdate();
        }
    }

    // ==================== ResultSet → 实体 ====================

    private Recipe mapRecipe(ResultSet rs) throws SQLException {
        Recipe r = new Recipe();
        r.setId(rs.getLong("id"));
        r.setName(rs.getString("name"));
        r.setDescription(rs.getString("description"));
        r.setCategory(Recipe.Category.valueOf(rs.getString("category")));
        r.setRequiredRealm(rs.getInt("required_realm"));
        r.setResultItemKey(rs.getString("result_item_key"));
        r.setResultQuantity(rs.getInt("result_quantity"));
        r.setMaterial1Key(rs.getString("material1_key"));
        r.setMaterial1Count(rs.getInt("material1_count"));
        r.setMaterial2Key(rs.getString("material2_key"));
        r.setMaterial2Count(rs.getInt("material2_count"));
        r.setMaterial3Key(rs.getString("material3_key"));
        r.setMaterial3Count(rs.getInt("material3_count"));
        r.setCostGold(rs.getLong("cost_gold"));
        r.setCostSpiritStones(rs.getLong("cost_spirit_stones"));
        r.setSuccessRate(rs.getDouble("success_rate"));
        r.setMinExpGain(rs.getLong("min_exp_gain"));
        r.setMaxExpGain(rs.getLong("max_exp_gain"));
        r.setHighQualityRate(rs.getDouble("high_quality_rate"));
        r.setMaxQualityRate(rs.getDouble("max_quality_rate"));
        r.setQualityBonusRate(rs.getDouble("quality_bonus_rate"));
        return r;
    }
}
