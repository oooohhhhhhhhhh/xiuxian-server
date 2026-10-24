package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.CraftingDAO;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.RecipeDefaults;
import com.mtxgdn.game.entity.Recipe;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.util.GameLogger;

import java.sql.SQLException;
import java.util.*;
import com.mtxgdn.i18n.I18n;

public class CraftingService {

    private static final GameLogger LOG = GameLogger.getLogger(CraftingService.class);
    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();
    private final Random random = new Random();

    /** {@code recipes} 表的读写入口（无状态，可共享）。 */
    private static final CraftingDAO CRAFTING_DAO = new CraftingDAO();

    public List<Recipe> getAllRecipes() {
        return CRAFTING_DAO.findAllRecipes();
    }

    public List<Recipe> getRecipesByCategory(Recipe.Category category) {
        return CRAFTING_DAO.findRecipesByCategory(category.name());
    }

    public Recipe getRecipeById(long recipeId) {
        return CRAFTING_DAO.findRecipeById(recipeId);
    }

    public Map<String, Object> craft(long playerId, long recipeId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Recipe recipe = getRecipeById(recipeId);
        if (recipe == null) {
            result.put("success", false); result.put("message", I18n.t("crafting.recipe_not_found")); return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false); result.put("message", I18n.t("crafting.character_not_found")); return result;
        }
        if (player.getRealm() < recipe.getRequiredRealm()) {
            result.put("success", false); result.put("message", I18n.t("crafting.realm_too_low")); return result;
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
                result.put("message", I18n.t("crafting.not_enough_materials", itemName, needed, has));
                return result;
            }
        }

        if (player.getGold() < recipe.getCostGold()) {
            result.put("success", false); result.put("message", I18n.t("crafting.not_enough_gold", recipe.getCostGold())); return result;
        }
        long ssCount = itemService.getSpiritStoneCount(playerId);
        if (ssCount < recipe.getCostSpiritStones()) {
            result.put("success", false); result.put("message", I18n.t("crafting.not_enough_spirit_stones", recipe.getCostSpiritStones())); return result;
        }

        // 合成熟练度加成：由累计制造成功次数（craft_count）派生，每级 +0.5% 成功率（上限 +4.5%）
        final double proficiencyBonus = proficiencySuccessBonus(player.getCraftCount());

        try {
            result = DatabaseManager.runTransaction(conn -> {
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
                if (!itemService.removeSpiritStones(conn, playerId, recipe.getCostSpiritStones())) {
                    throw new SQLException("灵石扣除失败");
                }

                double effectiveRate = Math.min(0.95, recipe.getSuccessRate() + proficiencyBonus);
                boolean success = random.nextDouble() < effectiveRate;

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
                    txResult.put("craftSuccess", true);

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
                    txResult.put("message", I18n.t("crafting.crafting_failed_2", pityExp));
                    txResult.put("craftSuccess", false);
                    txResult.put("expGained", pityExp);
                }

                return txResult;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("crafting.crafting_failed", e.getMessage()));
            return result;
        }

        // 制造成功：炼丹计数 + 称号检查（炼丹学徒/丹道尊者）
        if (Boolean.TRUE.equals(result.get("craftSuccess"))) {
            try {
                playerService.incrementCraftCount(playerId);
                com.mtxgdn.entity.Player p = playerService.getPlayerById(playerId);
                if (p != null) {
                    p.setCraftCount(p.getCraftCount() + 1);
                    new com.mtxgdn.game.service.TitleService().checkAndGrantTitles(p);
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    // ==================== 合成熟练度（真实派生，不再占位） ====================

    /** 熟练度等级：由累计制造成功次数（craft_count，每次成功 +1）派生，每 20 次 +1 级，最高 10 级。 */
    public static int proficiencyLevelFromCount(int craftCount) {
        return Math.min(10, 1 + craftCount / 20);
    }

    /** 熟练度成功率加成：每级 +0.5%（Lv1 为 0），封顶 +4.5%，统一封顶综合成功率 95%。 */
    public static double proficiencySuccessBonus(int craftCount) {
        return (proficiencyLevelFromCount(craftCount) - 1) * 0.005;
    }

    /** 合成熟练度详情（供 `/合成 熟练度` 展示）。 */
    public Map<String, Object> getProficiencyInfo(long playerId) {
        Map<String, Object> info = new LinkedHashMap<>();
        Player p = playerService.getPlayerById(playerId);
        int count = p != null ? p.getCraftCount() : 0;
        int level = proficiencyLevelFromCount(count);
        info.put("craftCount", count);
        info.put("level", level);
        info.put("maxLevel", 10);
        info.put("nextLevelAt", level >= 10 ? -1 : (level * 20) - count);
        info.put("successBonus", proficiencySuccessBonus(count));
        return info;
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
        // 出厂数据的本体搬到 RecipeDefaults，这里只做编排。
        // 幂等查询与插入、历史改名共用同一条连接：
        // 拆成两次连接时，中间的失败会留下"查过但其实没插"的状态。
        DatabaseManager.runTransaction(conn -> {
            for (Object[] row : RecipeDefaults.ROWS) {
                String recipeName = (String) row[0];
                if (CRAFTING_DAO.existsByName(conn, recipeName)) {
                    continue;
                }
                CRAFTING_DAO.insertRecipe(conn, row);
            }

            // 历史配方命名修正：名称与产物对齐（仅当新名未被占用时重命名，保留原配方 ID）
            for (String[] pair : RecipeDefaults.LEGACY_RENAME) {
                if (CRAFTING_DAO.existsByName(conn, pair[1])) {
                    continue;
                }
                CRAFTING_DAO.renameRecipe(conn, pair[0], pair[1]);
            }
            return null;
        });
        LOG.info("已初始化 " + RecipeDefaults.ROWS.length + " 种默认配方");
    }
}
