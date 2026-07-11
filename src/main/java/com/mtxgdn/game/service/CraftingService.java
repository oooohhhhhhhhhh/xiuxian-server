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
            long has = itemService.getItemCount(playerId, key);
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

        try {
            return DatabaseManager.runTransaction(conn -> {
                Map<String, Object> txResult = new LinkedHashMap<>();

                // 扣除材料
                for (Map.Entry<String, Integer> entry : requiredMaterials.entrySet()) {
                    String key = entry.getKey();
                    int needed = entry.getValue();
                    if (key == null || key.isEmpty() || needed <= 0) continue;
                    if (!itemService.removeItem(conn, playerId, key, needed)) {
                        throw new SQLException("材料扣除失败: " + key);
                    }
                }

                // 扣除金币和灵石
                playerService.addGold(conn, playerId, -recipe.getCostGold());
                itemService.removeItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, recipe.getCostSpiritStones());

                boolean success = random.nextDouble() < recipe.getSuccessRate();

                if (success) {
                    Recipe.PillQuality quality = determinePillQuality(recipe);
                    int bonusMultiplier = 1;
                    String qualityLabel = "";
                    String qualitySuffix = "";

                    switch (quality) {
                        case HIGH -> {
                            bonusMultiplier = 2;
                            qualityLabel = "上品";
                            qualitySuffix = "（效果翻倍）";
                        }
                        case MAX -> {
                            bonusMultiplier = 3;
                            qualityLabel = "极品";
                            qualitySuffix = "（效果三倍）";
                        }
                        default -> {}
                    }

                    int actualQuantity = recipe.getResultQuantity() * bonusMultiplier;
                    itemService.addItem(conn, playerId, recipe.getResultItemKey(), actualQuantity);

                    long expGain = recipe.getMinExpGain() + random.nextLong(recipe.getMaxExpGain() - recipe.getMinExpGain() + 1);
                    expGain = (long) (expGain * (1 + recipe.getQualityBonusRate() * (bonusMultiplier - 1)));
                    playerService.addExperience(conn, playerId, expGain);

                    Item resultItem = ItemRegistry.get(recipe.getResultItemKey());
                    String resultName = resultItem != null ? resultItem.getName() : recipe.getResultItemKey();

                    String message;
                    if (quality != Recipe.PillQuality.NORMAL) {
                        message = "制造成功！获得了【" + qualityLabel + "】" + resultName + " x" + actualQuantity + qualitySuffix + "，" + expGain + " 经验";
                    } else {
                        message = "制造成功！获得了 " + resultName + " x" + actualQuantity + "，" + expGain + " 经验";
                    }

                    txResult.put("success", true);
                    txResult.put("message", message);
                    txResult.put("expGained", expGain);
                    txResult.put("itemGained", recipe.getResultItemKey());
                    txResult.put("itemQuantity", actualQuantity);
                    txResult.put("quality", quality.name());
                    txResult.put("qualityLabel", qualityLabel);
                } else {
                    long pityExp = recipe.getMinExpGain() / 3;
                    playerService.addExperience(conn, playerId, pityExp);
                    txResult.put("success", true);
                    txResult.put("message", "制造失败...材料已消耗，但参悟中获得了 " + pityExp + " 点经验");
                    txResult.put("craftSuccess", false);
                    txResult.put("expGained", pityExp);
                }

                return txResult;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "制造失败: " + e.getMessage());
            return result;
        }
    }

    private Recipe.PillQuality determinePillQuality(Recipe recipe) {
        double roll = random.nextDouble();
        if (roll < recipe.getMaxQualityRate()) {
            return Recipe.PillQuality.MAX;
        }
        if (roll < recipe.getMaxQualityRate() + recipe.getHighQualityRate()) {
            return Recipe.PillQuality.HIGH;
        }
        return Recipe.PillQuality.NORMAL;
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
                cost_gold, cost_spirit_stones, success_rate, min_exp_gain, max_exp_gain,
                high_quality_rate, max_quality_rate, quality_bonus_rate)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        Object[][] defaults = {
            {"炼制回血丹", "将灵草炼化为回血丹药，可恢复生命值", "PILL", 0, "mtxgdn:healing_pill", 2, "mtxgdn:spirit_grass", 3, null, 0, null, 0, 50L, 10L, 0.85, 20L, 60L, 0.10, 0.02, 0.2},
            {"炼制回蓝丹", "将灵草与灵石粉末炼化为回蓝丹药", "PILL", 0, "mtxgdn:mana_pill", 2, "mtxgdn:spirit_grass", 2, "mtxgdn:spirit_recovery_pill", 1, null, 0, 60L, 15L, 0.80, 30L, 70L, 0.08, 0.02, 0.2},
            {"炼制培元丹", "多种灵草炼制的高阶丹药，大幅提升修为", "PILL", 1, "mtxgdn:cultivation_elixir", 1, "mtxgdn:spirit_grass", 5, "mtxgdn:healing_pill", 2, "mtxgdn:mana_pill", 2, 200L, 50L, 0.70, 50L, 150L, 0.08, 0.02, 0.3},
            {"炼制力量丹药", "蕴含天地灵力的强力丹药，短时间内提升战力", "PILL", 2, "mtxgdn:power_buff_pill", 1, "mtxgdn:spirit_grass", 4, "mtxgdn:iron_ore", 2, "mtxgdn:spirit_recovery_pill", 2, 300L, 80L, 0.65, 80L, 200L, 0.06, 0.015, 0.3},
            {"炼制净化丹", "清除所有负面状态的神奇丹药", "PILL", 1, "mtxgdn:purification_pill", 1, "mtxgdn:spirit_grass", 3, "mtxgdn:ice_crystal", 1, null, 0, 150L, 40L, 0.75, 30L, 80L, 0.06, 0.015, 0.25},
            {"炼制复活丹", "可原地复活的神丹", "PILL", 3, "mtxgdn:rebirth_pill", 1, "mtxgdn:phoenix_feather", 2, "mtxgdn:cultivation_elixir", 2, "mtxgdn:spirit_spring_water", 3, 2000L, 800L, 0.30, 150L, 400L, 0.03, 0.005, 0.5},
            {"炼制洗髓丹", "重置属性分配的神奇丹药", "PILL", 2, "mtxgdn:attribute_reset_pill", 1, "mtxgdn:star_sand", 3, "mtxgdn:spirit_grass", 5, "mtxgdn:heaven_fire_stone", 1, 800L, 300L, 0.50, 60L, 150L, 0.05, 0.01, 0.35},
            {"炼制经验丹", "短时间内提升经验获取效率", "PILL", 2, "mtxgdn:exp_multiplier_pill", 1, "mtxgdn:spirit_grass", 4, "mtxgdn:beast_core", 2, "mtxgdn:cultivation_elixir", 1, 500L, 150L, 0.60, 50L, 120L, 0.05, 0.01, 0.25},
            {"炼制反弹丹", "反弹伤害的神奇丹药", "PILL", 1, "mtxgdn:damage_reflection_pill", 1, "mtxgdn:iron_ore", 3, "mtxgdn:beast_core", 1, "mtxgdn:demon_core", 1, 250L, 60L, 0.70, 30L, 80L, 0.06, 0.015, 0.2},
            {"炼制渡劫丹", "以天材地宝和灵泉水炼制的护体仙丹", "PILL", 3, "mtxgdn:tribulation_pill", 1, "mtxgdn:heavenly_jade", 1, "mtxgdn:spirit_spring_water", 3, "mtxgdn:cultivation_elixir", 1, 1000L, 500L, 0.50, 100L, 300L, 0.05, 0.01, 0.4},
            {"锻造灵剑", "以铁矿石为主材，融入灵石锻造的灵剑", "EQUIPMENT", 1, "mtxgdn:spirit_sword", 1, "mtxgdn:iron_ore", 5, "mtxgdn:spirit_stone_low", 3, null, 0, 400L, 100L, 0.75, 50L, 120L, 0.05, 0.01, 0.2},
            {"炼制护体玉符", "用灵草和灵石炼制的护身符，可抵挡伤害", "EQUIPMENT", 2, "mtxgdn:guardian_jade", 1, "mtxgdn:spirit_grass", 3, "mtxgdn:healing_pill", 2, "mtxgdn:iron_ore", 2, 600L, 150L, 0.70, 60L, 150L, 0.05, 0.01, 0.2},
            {"锻造龙剑", "以龙鳞为主材锻造的神兵", "EQUIPMENT", 4, "mtxgdn:dragon_sword", 1, "mtxgdn:dragon_scale", 5, "mtxgdn:heaven_fire_stone", 3, "mtxgdn:spirit_stone_low", 5, 20000L, 5000L, 0.40, 200L, 500L, 0.03, 0.005, 0.4},
            {"锻造凤甲", "以凤羽编织的神甲", "EQUIPMENT", 4, "mtxgdn:phoenix_armor", 1, "mtxgdn:phoenix_feather", 5, "mtxgdn:ice_crystal", 3, "mtxgdn:spirit_wood", 5, 25000L, 6000L, 0.35, 250L, 600L, 0.03, 0.005, 0.4},
            {"炼制麒麟护符", "蕴含麒麟之力的至尊护符", "EQUIPMENT", 5, "mtxgdn:qilin_amulet", 1, "mtxgdn:qilin_blood", 3, "mtxgdn:dragon_scale", 2, "mtxgdn:phoenix_feather", 2, 80000L, 20000L, 0.20, 500L, 1000L, 0.02, 0.003, 0.5},
            {"炼制星辰法袍", "以星砂编织的法袍", "EQUIPMENT", 3, "mtxgdn:star_robes", 1, "mtxgdn:star_sand", 5, "mtxgdn:spirit_wood", 3, "mtxgdn:spirit_stone_low", 5, 10000L, 2000L, 0.50, 100L, 300L, 0.04, 0.01, 0.3},
            {"锻造空间戒指", "以灵木和星沙锻造的神奇戒指，可增加背包容量", "EQUIPMENT", 2, "mtxgdn:space_ring", 1, "mtxgdn:spirit_wood", 5, "mtxgdn:star_sand", 3, "mtxgdn:spirit_pearl", 2, 800L, 200L, 0.60, 60L, 150L, 0.04, 0.01, 0.2},
            {"炼制聚灵石", "浓缩灵石精华而成，蕴含大量灵石能量", "CONSUMABLE", 1, "mtxgdn:spirit_stone_pouch", 1, "mtxgdn:spirit_stone_low", 5, "mtxgdn:iron_ore", 3, null, 0, 100L, 0L, 0.90, 10L, 40L, 0.03, 0.005, 0.1},
            {"炼制加速符", "以疾风灵力绘制的符箓，使用后提升速度", "CONSUMABLE", 2, "mtxgdn:speed_talisman", 1, "mtxgdn:spirit_grass", 4, "mtxgdn:mana_pill", 2, "mtxgdn:heaven_pill", 1, 500L, 120L, 0.65, 40L, 100L, 0.04, 0.01, 0.15},
            {"炼制强化石", "将铁矿石和妖兽内丹熔炼成强化石", "CONSUMABLE", 1, "mtxgdn:enhance_stone", 1, "mtxgdn:iron_ore", 3, "mtxgdn:beast_core", 1, null, 0, 200L, 30L, 0.80, 15L, 40L, 0.05, 0.01, 0.1},
            {"炼制保护符", "蕴含守护道韵的符箓，强化时可保装备不碎", "CONSUMABLE", 2, "mtxgdn:protect_charm", 1, "mtxgdn:spirit_spring_water", 2, "mtxgdn:healing_pill", 3, "mtxgdn:beast_core", 2, 400L, 80L, 0.70, 30L, 80L, 0.04, 0.01, 0.1},
            {"炼制传送符", "可传送到主城的符箓", "CONSUMABLE", 1, "mtxgdn:teleport_talisman", 1, "mtxgdn:spirit_grass", 3, "mtxgdn:star_sand", 1, null, 0, 200L, 50L, 0.85, 20L, 50L, 0.03, 0.005, 0.1},
            {"炼制超级回血丹", "千年人参炼制的高级回血丹", "PILL", 2, "mtxgdn:super_healing_pill", 1, "mtxgdn:thousand_year_ginseng", 2, "mtxgdn:spirit_grass", 3, "mtxgdn:healing_pill", 2, 500L, 150L, 0.65, 60L, 150L, 0.06, 0.015, 0.3},
            {"炼制超级回蓝丹", "血灵芝炼制的高级回蓝丹", "PILL", 2, "mtxgdn:super_mana_pill", 1, "mtxgdn:blood_lingzhi", 2, "mtxgdn:spirit_grass", 3, "mtxgdn:mana_pill", 2, 500L, 150L, 0.65, 60L, 150L, 0.06, 0.015, 0.3},
            {"炼制仙丹", "万年灵芝与天山雪莲炼制的传说级丹药", "PILL", 5, "mtxgdn:immortal_pill", 1, "mtxgdn:ten_thousand_year_lingzhi", 1, "mtxgdn:tianshan_snow_lotus", 2, "mtxgdn:cultivation_elixir", 5, 5000L, 2000L, 0.20, 300L, 800L, 0.03, 0.005, 0.5},
            {"炼制灵力大增丹", "暗冰草与月华精华炼制的灵力丹药", "PILL", 3, "mtxgdn:spirit_boost_pill", 1, "mtxgdn:dark_ice_grass", 3, "mtxgdn:moon_essence", 1, "mtxgdn:spirit_grass", 5, 1000L, 300L, 0.50, 80L, 200L, 0.05, 0.01, 0.35},
            {"炼制至尊力量丹", "龙心与日华精华炼制的至尊丹药", "PILL", 5, "mtxgdn:ultimate_power_pill", 1, "mtxgdn:dragon_heart", 1, "mtxgdn:sun_essence", 2, "mtxgdn:power_buff_pill", 3, 10000L, 5000L, 0.15, 200L, 500L, 0.02, 0.003, 0.5},
            {"炼制灵草种子", "培育灵草的种子", "SEED", 0, "mtxgdn:spirit_grass_seed", 5, "mtxgdn:spirit_grass", 2, "mtxgdn:enhance_stone", 1, null, 0, 50L, 10L, 0.95, 5L, 20L, 0.02, 0.005, 0.1},
            {"炼制千年人参种子", "培育千年人参的珍贵种子", "SEED", 1, "mtxgdn:thousand_year_ginseng_seed", 2, "mtxgdn:thousand_year_ginseng", 1, "mtxgdn:spirit_pearl", 1, null, 0, 200L, 80L, 0.80, 20L, 50L, 0.03, 0.01, 0.15},
            {"炼制暗冰草种子", "培育暗冰草的种子", "SEED", 1, "mtxgdn:dark_ice_grass_seed", 3, "mtxgdn:dark_ice_grass", 2, "mtxgdn:ice_crystal", 1, null, 0, 100L, 30L, 0.90, 10L, 30L, 0.02, 0.005, 0.1},
            {"炼制火焰藤种子", "培育火焰藤的种子", "SEED", 1, "mtxgdn:fire_vine_seed", 3, "mtxgdn:fire_vine", 2, "mtxgdn:heaven_fire_stone", 1, null, 0, 100L, 30L, 0.90, 10L, 30L, 0.02, 0.005, 0.1},
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
                ps.setDouble(18, (double) row[17]);
                ps.setDouble(19, (double) row[18]);
                ps.setDouble(20, (double) row[19]);
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
        r.setHighQualityRate(rs.getDouble("high_quality_rate"));
        r.setMaxQualityRate(rs.getDouble("max_quality_rate"));
        r.setQualityBonusRate(rs.getDouble("quality_bonus_rate"));
        return r;
    }
}
