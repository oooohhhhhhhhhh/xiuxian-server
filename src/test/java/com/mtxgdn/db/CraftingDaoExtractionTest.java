package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 炼丹/制造模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code CraftingService} 原来 384 行里 4 处
 * {@code DatabaseManager.getConnection()}，而且 {@code insertDefaultRecipes}
 * 把一张 20 列 × 33 行的出厂数据数组、20 个 {@code ps.setXxx}、
 * 幂等查询和历史改名全塞在一个方法里 —— 既有业务又有 200 行 JDBC 样板。
 * {@code craft} 则是"扣材料 → 扣金币 → 扣灵石 → 发产物 → 发经验"的单体事务。</p>
 */
class CraftingDaoExtractionTest extends DaoExtractionAssertions {

    private static final java.nio.file.Path DEFAULTS =
            java.nio.file.Paths.get("src/main/java/com/mtxgdn/game/config/RecipeDefaults.java");

    @Override
    protected Class<?> daoClass() {        return CraftingDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/CraftingService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/CraftingDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of("proficiencySuccessBonus", "proficiencyLevelFromCount",
                "determinePillQuality", "getRequiredMaterials", "getProficiencyInfo",
                "RecipeDefaults.ROWS", "RecipeDefaults.LEGACY_RENAME",
                "runTransaction", "removeSpiritStones", "addExperience",
                "incrementCraftCount", "TitleService", "I18n.t");
    }

    @Test
    @DisplayName("出厂配方数据一条没少、一条没改")
    void defaultRecipesDataIsIntact() throws IOException {
        String code = stripComments(read(DEFAULTS.toString()));
        // 只数 ROWS 段的行：LEGACY_RENAME 里的三行也以 {"炼制 开头，
        // 混在一起数会把"历史改名"也算成"出厂配方"。
        int rowsStart = code.indexOf("ROWS = {");
        int rowsEnd = code.indexOf("LEGACY_RENAME");
        assertTrue(rowsStart >= 0 && rowsEnd > rowsStart, "找不到 ROWS 或 LEGACY_RENAME 段");
        String rows = code.substring(rowsStart, rowsEnd);
        int recipeRows = countOccurrences(rows, "{\"炼制") + countOccurrences(rows, "{\"锻造");
        assertEquals(33, recipeRows, "出厂配方应保持 33 行");
        // 逐条点名，防数据被改
        for (String anchor : List.of("{\"炼制回血丹\"", "{\"锻造灵剑\"", "{\"炼制乾坤造化丹\"",
                "{\"炼制天元丹\"", "{\"炼制仙丹\"", "{\"锻造空间戒指\"")) {
            assertTrue(code.contains(anchor), "配方 " + anchor + " 不能丢");
        }
        // 三条历史改名一条不能少（只数 LEGACY_RENAME 段，别把 33 行出厂配方算进来）
        String renames = code.substring(rowsEnd);
        assertEquals(3, countOccurrences(renames, "{\"炼制"),
                "LEGACY_RENAME 应保持 3 条");
        assertTrue(code.contains("\"炼制聚灵石\", \"炼制灵石袋\""), "历史改名 1 不能丢");
        assertTrue(code.contains("\"炼制九转金丹\", \"炼制乾坤造化丹\""), "历史改名 2 不能丢");
        assertTrue(code.contains("\"炼制混沌仙丹\", \"炼制天元丹\""), "历史改名 3 不能丢");
    }

    @Test
    @DisplayName("三条查询的 SELECT * 与排序逐字保留")
    void queryShapeKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("SELECT * FROM recipes ORDER BY category, required_realm"),
                "全部配方的排序不能丢");
        assertTrue(code.contains("SELECT * FROM recipes WHERE category = ? ORDER BY required_realm"),
                "按分类查询的条件与排序不能丢");
        assertTrue(code.contains("SELECT * FROM recipes WHERE id = ?"), "按 id 查询不能丢");
    }

    @Test
    @DisplayName("出厂数据的幂等查询与插入共用同一条连接")
    void insertDefaultsSharesOneConnection() throws IOException {
        String service = stripComments(read(servicePath()));
        int start = service.indexOf("public void insertDefaultRecipes(");
        assertTrue(start >= 0, "找不到 insertDefaultRecipes");
        String body = service.substring(start);
        assertTrue(body.contains("DatabaseManager.runTransaction(conn ->"),
                "出厂数据的插入必须整批走一条事务连接：拆成多次自取连接时，"
                        + "中间的失败会留下「查过但其实没插」的状态");
        assertTrue(body.contains("CRAFTING_DAO.existsByName(conn, recipeName)"),
                "幂等查询必须显式用事务连接");
        assertTrue(body.contains("CRAFTING_DAO.insertRecipe(conn, row)"),
                "插入必须显式用同一条事务连接");
        assertTrue(body.contains("CRAFTING_DAO.existsByName(conn, pair[1])"),
                "历史改名的占用查询也要用同一条连接");
        assertTrue(body.contains("CRAFTING_DAO.renameRecipe(conn, pair[0], pair[1])"),
                "历史改名必须用同一条连接");
        // DAO 侧的这三个方法只收 Connection，没有自取连接的重载
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("public boolean existsByName(Connection conn,"), "existsByName 只收连接");
        assertTrue(dao.contains("public void insertRecipe(Connection conn, Object[] row)"), "insertRecipe 只收连接");
        assertTrue(dao.contains("public int renameRecipe(Connection conn,"), "renameRecipe 只收连接");
        assertFalse(dao.contains("public boolean existsByName(String"),
                "existsByName 不该有自取连接的重载：它只在 insertDefaultRecipes 的事务里用");
    }

    @Test
    @DisplayName("20 列 INSERT 的参数绑定形状不能丢（NULL 材料槽位的不对称处理）")
    void insertParameterShapeKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("INSERT INTO recipes (name, description, category, required_realm,"),
                "列清单前半不能丢");
        assertTrue(code.contains("material1_key, material1_count, material2_key, material2_count, material3_key, material3_count,"),
                "三个材料槽位的列顺序不能丢");
        assertTrue(code.contains("cost_gold, cost_spirit_stones, success_rate, min_exp_gain, max_exp_gain,"),
                "消耗与收益列不能丢");
        assertTrue(code.contains("high_quality_rate, max_quality_rate, quality_bonus_rate)"),
                "品质列不能丢");
        assertEquals(1, countOccurrences(code, "setObject(9, row[8], java.sql.Types.VARCHAR)"),
                "material2_key 必须走 setObject 写 NULL。insertRecipe 只收 Connection，"
                        + "所以这个形状只出现一次 —— 它只在 insertDefaultRecipes 的事务里被调用");
        assertEquals(1, countOccurrences(code, "setObject(11, row[10], java.sql.Types.VARCHAR)"),
                "material3_key 必须走 setObject 写 NULL");
        assertEquals(1, countOccurrences(code, "ps.setInt(10, row[9] == null ? 0 : (int) row[9]);"),
                "key 可以为 NULL、数量必须补 0 —— 这个不对称不能顺手统一");
        assertEquals(1, countOccurrences(code, "ps.setInt(12, row[11] == null ? 0 : (int) row[11]);"),
                "同上，material3_count 也要补 0");
    }

    @Test
    @DisplayName("craft 的五行事事务边界与熟练度封顶规则留在服务层")
    void craftBusinessRulesStayInService() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("Math.min(0.95, recipe.getSuccessRate() + proficiencyBonus)"),
                "综合成功率封顶 95% 是业务规则，必须留在服务层");
        assertTrue(service.contains("proficiencySuccessBonus(player.getCraftCount())"),
                "熟练度加成由 craft_count 派生，必须留在服务层");
        assertTrue(service.contains("Math.min(10, 1 + craftCount / 20)"),
                "熟练度每 20 次 +1 级、封顶 10 级必须留在服务层");
        assertTrue(service.contains("itemService.removeItem(conn, playerId, key, needed)"),
                "扣材料必须走事务连接");
        assertTrue(service.contains("playerService.addGold(conn, playerId, -recipe.getCostGold())"),
                "扣金币必须走事务连接");
        assertTrue(service.contains("itemService.removeSpiritStones(conn, playerId, recipe.getCostSpiritStones())"),
                "扣灵石必须走事务连接");
        assertTrue(service.contains("itemService.addItem(conn, playerId, recipe.getResultItemKey(), actualQuantity)"),
                "发产物必须走事务连接");
        assertTrue(service.contains("playerService.addExperience(conn, playerId, expGain)"),
                "发经验必须走事务连接");
        assertTrue(service.contains("Recipe.PillQuality.MAX"), "品质判定必须留在服务层");
        assertTrue(service.contains("(long) (expGain * (1 + recipe.getQualityBonusRate() * (bonusMultiplier - 1)))"),
                "品质经验倍率必须留在服务层");
    }
}
