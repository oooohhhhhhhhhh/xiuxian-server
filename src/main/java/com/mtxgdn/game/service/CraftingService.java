package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Recipe;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class CraftingService {

    private static final GameLogger LOG = GameLogger.getLogger(CraftingService.class);
    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();
    private final Random random = new Random();

    public List<Recipe> getAllRecipes() {
        String sql = "SELECT * FROM recipes ORDER BY category, required_realm";
        List<Recipe> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(mapRecipe(rs));
        } catch (SQLException e) { throw new RuntimeException("查询配方列表失败", e); }
        return result;
    }

    public List<Recipe> getRecipesByCategory(Recipe.Category category) {
        String sql = "SELECT * FROM recipes WHERE category = ? ORDER BY required_realm";
        List<Recipe> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, category.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapRecipe(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询配方列表失败", e); }
        return result;
    }

    public Recipe getRecipeById(long recipeId) {
        String sql = "SELECT * FROM recipes WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, recipeId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return mapRecipe(rs); }
        } catch (SQLException e) { throw new RuntimeException("查询配方失败", e); }
        return null;
    }

    public Map<String, Object> craft(long playerId, long recipeId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Recipe recipe = getRecipeById(recipeId);
        if (recipe == null) {
            result.put("success", false); result.put("message", "配方不存在"); return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false); result.put("message", "角色不存在"); return result;
        }
        if (player.getRealm() < recipe.getRequiredRealm()) {
            result.put("success", false); result.put("message", "境界不足，需要更高境界才能使用此配方"); return result;
        }

        Map<String, Integer> requiredMaterials = getRequiredMaterials(recipe);
        for (Map.Entry<String, Integer> entry : requiredMaterials.entrySet()) {
            String key = entry.getKey();
            int needed = entry.getValue();
            if (key == null || key.isEmpty() || needed <= 0) continue;
            int has = itemService.getItemCount(playerId, key);
            if (has < needed) {
                Item item = ItemRegistry.get(key);
                String itemName = item != null ? item.getName() : key;
                result.put("success", false);
                result.put("message", "材料不足：需要 " + itemName + " x" + needed + "，拥有 " + has);
                return result;
            }
        }

        if (player.getGold() < recipe.getCostGold()) {
            result.put("success", false); result.put("message", "金币不足，制造需要 " + recipe.getCostGold() + " 金币"); return result;
        }
        long ssCount = itemService.getSpiritStoneCount(playerId);
        if (ssCount < recipe.getCostSpiritStones()) {
            result.put("success", false); result.put("message", "灵石不足，制造需要 " + recipe.getCostSpiritStones() + " 灵石"); return result;
        }

        for (Map.Entry<String, Integer> entry : requiredMaterials.entrySet()) {
            String key = entry.getKey();
            int needed = entry.getValue();
            if (key == null || key.isEmpty() || needed <= 0) continue;
            itemService.removeItem(playerId, key, needed);
        }

        playerService.addGold(playerId, -recipe.getCostGold());
        itemService.addSpiritStones(playerId, -recipe.getCostSpiritStones());

        boolean success = random.nextDouble() < recipe.getSuccessRate();

        if (success) {
            itemService.addItem(playerId, recipe.getResultItemKey(), recipe.getResultQuantity());
            long expGain = recipe.getMinExpGain() + random.nextLong(recipe.getMaxExpGain() - recipe.getMinExpGain() + 1);
            playerService.addExperience(playerId, expGain);

            Item resultItem = ItemRegistry.get(recipe.getResultItemKey());
            String resultName = resultItem != null ? resultItem.getName() : recipe.getResultItemKey();
            result.put("success", true);
            result.put("message", "制造成功！获得了 " + resultName + " x" + recipe.getResultQuantity() + "，" + expGain + " 经验");
            result.put("expGained", expGain);
            result.put("itemGained", recipe.getResultItemKey());
            result.put("itemQuantity", recipe.getResultQuantity());
        } else {
            long pityExp = recipe.getMinExpGain() / 3;
            playerService.addExperience(playerId, pityExp);
            result.put("success", true);
            result.put("message", "制造失败...材料已消耗，但参悟中获得了 " + pityExp + " 点经验");
            result.put("craftSuccess", false);
            result.put("expGained", pityExp);
        }

        return result;
    }

    private Map<String, Integer> getRequiredMaterials(Recipe recipe) {
        Map<String, Integer> mats = new LinkedHashMap<>();
        if (recipe.getMaterial1Key() != null && !recipe.getMaterial1Key().isEmpty() && recipe.getMaterial1Count() > 0) {
            mats.put(recipe.getMaterial1Key(), recipe.getMaterial1Count());
        }
        if (recipe.getMaterial2Key() != null && !recipe.getMaterial2Key().isEmpty() && recipe.getMaterial2Count() > 0) {
            mats.put(recipe.getMaterial2Key(), recipe.getMaterial2Count());
        }
        if (recipe.getMaterial3Key() != null && !recipe.getMaterial3Key().isEmpty() && recipe.getMaterial3Count() > 0) {
            mats.put(recipe.getMaterial3Key(), recipe.getMaterial3Count());
        }
        return mats;
    }

    public void insertDefaultRecipes() {
        if (!getAllRecipes().isEmpty()) return;

        String sql = """
            INSERT INTO recipes (name, description, category, required_realm,
                result_item_key, result_quantity,
                material1_key, material1_count, material2_key, material2_count, material3_key, material3_count,
                cost_gold, cost_spirit_stones, success_rate, min_exp_gain, max_exp_gain)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        Object[][] defaults = {
            {"炼制回血丹", "将灵草炼化为回血丹药，可恢复生命值", "PILL", 0, "healing_pill", 2, "spirit_grass", 3, null, 0, null, 0, 50L, 10L, 0.85, 20L, 60L},
            {"炼制回蓝丹", "将灵草与灵石粉末炼化为回蓝丹药", "PILL", 0, "mana_pill", 2, "spirit_grass", 2, "spirit_recovery_pill", 1, null, 0, 60L, 15L, 0.80, 30L, 70L},
            {"炼制培元丹", "多种灵草炼制的高阶丹药，大幅提升修为", "PILL", 1, "cultivation_elixir", 1, "spirit_grass", 5, "healing_pill", 2, "mana_pill", 2, 200L, 50L, 0.70, 50L, 150L},
            {"炼制力量丹药", "蕴含天地灵力的强力丹药，短时间内提升战力", "PILL", 2, "power_buff_pill", 1, "spirit_grass", 4, "iron_ore", 2, "spirit_recovery_pill", 2, 300L, 80L, 0.65, 80L, 200L},
            {"锻造灵剑", "以铁矿石为主材，融入灵石锻造的灵剑", "EQUIPMENT", 1, "spirit_sword", 1, "iron_ore", 5, "spirit_stone", 3, null, 0, 400L, 100L, 0.75, 50L, 120L},
            {"炼制护体玉符", "用灵草和灵石炼制的护身符，可抵挡伤害", "EQUIPMENT", 2, "guardian_jade", 1, "spirit_grass", 3, "healing_pill", 2, "iron_ore", 2, 600L, 150L, 0.70, 60L, 150L},
            {"炼制聚灵石", "浓缩灵石精华而成，蕴含大量灵石能量", "CONSUMABLE", 1, "spirit_stone_pouch", 1, "spirit_stone", 5, "iron_ore", 3, null, 0, 100L, 0L, 0.90, 10L, 40L},
            {"炼制加速符", "以疾风灵力绘制的符箓，使用后提升速度", "CONSUMABLE", 2, "speed_talisman", 1, "spirit_grass", 4, "mana_pill", 2, "heaven_pill", 1, 500L, 120L, 0.65, 40L, 100L},
        };

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] row : defaults) {
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
                ps.executeUpdate();
            }
        } catch (SQLException e) { throw new RuntimeException("初始化配方失败", e); }
        LOG.info("已初始化 " + defaults.length + " 种默认配方");
    }

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
        return r;
    }
}
