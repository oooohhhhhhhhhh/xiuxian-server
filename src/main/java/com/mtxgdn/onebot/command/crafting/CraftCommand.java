package com.mtxgdn.onebot.command.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Recipe;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;
import java.util.Map;

public class CraftCommand extends Command {
    public CraftCommand() {
        super(new String[]{"合成", "craft", "炼制"}, "合成系统 — 查看配方/合成物品",
                "/合成 [列表|配方 <分类>|制造 <配方ID>]", "实用功能", null);

        registerSub(new String[]{"列表", "list"}, this::listRecipes);
        registerSub(new String[]{"配方", "recipes"}, this::listRecipesWithCategory);
        registerSub(new String[]{"制造", "craft", "make"}, this::doCraft);

        addRoute(RouteDefinition.get("crafting/recipes", this::handleListHttp));
        addRoute(RouteDefinition.post("crafting/craft", this::handleCraftHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        String arg = ctx.getArg();
        if (arg != null && !arg.trim().isEmpty()) {
            super.execute(ctx);
            return;
        }
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        showDefault(ctx, p);
    }

    private void showDefault(CommandContext ctx, PlayerInfo p) {
        var service = ServiceRegistry.getCraftingService();
        List<Recipe> recipes = service.getAllRecipes();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 合成系统 ===\n");
        sb.append("可用配方: ").append(recipes.size()).append(" 种\n\n");
        sb.append("分类: 丹药PILL | 装备EQUIPMENT | 消耗品CONSUMABLE\n\n");
        sb.append("查看配方: /合成 列表\n");
        sb.append("按分类查看: /合成 配方 <分类>\n");
        sb.append("制造: /合成 制造 <配方ID>");
        ctx.reply(sb.toString());
    }

    private void listRecipes(CommandContext ctx, PlayerInfo p, String[] parts) {
        var service = ServiceRegistry.getCraftingService();
        List<Recipe> recipes = service.getAllRecipes();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 合成配方 (共").append(recipes.size()).append("种) ===\n\n");
        for (Recipe r : recipes) {
            sb.append("[").append(r.getId()).append("] ").append(r.getName())
              .append(" (").append(categoryLabel(r.getCategory())).append(")")
              .append(" 成功率:").append((int)(r.getSuccessRate() * 100)).append("%\n");
            sb.append("  产物: ").append(r.getResultItemKey()).append(" x").append(r.getResultQuantity()).append("\n");

            StringBuilder mats = new StringBuilder();
            if (r.getMaterial1Key() != null && !r.getMaterial1Key().isEmpty())
                mats.append("  ").append(r.getMaterial1Key()).append(" x").append(r.getMaterial1Count());
            if (r.getMaterial2Key() != null && !r.getMaterial2Key().isEmpty())
                mats.append("、").append(r.getMaterial2Key()).append(" x").append(r.getMaterial2Count());
            if (r.getMaterial3Key() != null && !r.getMaterial3Key().isEmpty())
                mats.append("、").append(r.getMaterial3Key()).append(" x").append(r.getMaterial3Count());
            if (!mats.isEmpty()) sb.append("  材料:").append(mats).append("\n");

            sb.append("  费用:").append(r.getCostGold()).append("金").append(r.getCostSpiritStones()).append("灵石");
            if (r.getRequiredRealm() > 0) sb.append(" | 需境界Lv").append(r.getRequiredRealm());
            sb.append("\n\n");
        }
        sb.append("制造: /合成 制造 <配方ID>");
        ctx.reply(sb.toString());
    }

    private void listRecipesWithCategory(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) { ctx.reply("用法: /合成 配方 <分类>\n分类: PILL(丹药) / EQUIPMENT(装备) / CONSUMABLE(消耗品)"); return; }
        String cat = parts[1].toUpperCase();
        Recipe.Category category;
        try {
            category = Recipe.Category.valueOf(cat);
        } catch (IllegalArgumentException e) {
            ctx.reply("无效分类: " + cat + "\n可用: PILL / EQUIPMENT / CONSUMABLE");
            return;
        }

        var service = ServiceRegistry.getCraftingService();
        List<Recipe> recipes = service.getRecipesByCategory(category);
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(categoryLabel(category)).append("配方 ===\n");
        if (recipes.isEmpty()) {
            sb.append("暂无此类配方。");
        } else {
            for (Recipe r : recipes) {
                sb.append("[").append(r.getId()).append("] ").append(r.getName())
                  .append(" 成功率:").append((int)(r.getSuccessRate() * 100)).append("%")
                  .append(" 费用:").append(r.getCostGold()).append("金").append(r.getCostSpiritStones()).append("灵石\n");
            }
            sb.append("\n制造: /合成 制造 <配方ID>");
        }
        ctx.reply(sb.toString());
    }

    private void doCraft(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) { ctx.reply("用法: /合成 制造 <配方ID>\n先用 /合成 列表 查看配方"); return; }
        long recipeId;
        try { recipeId = Long.parseLong(parts[1]); } catch (NumberFormatException e) { ctx.reply("配方ID无效"); return; }

        var service = ServiceRegistry.getCraftingService();
        Map<String, Object> result = service.craft(p.getId(), recipeId);
        ctx.reply((String) result.get("message"));
    }

    private String categoryLabel(Recipe.Category cat) {
        return switch (cat) {
            case PILL -> "丹药";
            case EQUIPMENT -> "装备";
            case CONSUMABLE -> "消耗品";
        };
    }

    // ==================== REST API ====================

    private JsonObject handleListHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var service = ServiceRegistry.getCraftingService();
            String category = ctx.queryParam("category");
            List<Recipe> recipes;
            if (category != null && !category.isEmpty()) {
                try {
                    Recipe.Category cat = Recipe.Category.valueOf(category.toUpperCase());
                    recipes = service.getRecipesByCategory(cat);
                } catch (IllegalArgumentException e) {
                    result.addProperty("code", 400);
                    result.addProperty("message", "无效的配方分类: " + category);
                    return result;
                }
            } else {
                recipes = service.getAllRecipes();
            }
            JsonArray arr = new JsonArray();
            for (Recipe r : recipes) {
                JsonObject o = new JsonObject();
                o.addProperty("id", r.getId());
                o.addProperty("name", r.getName());
                o.addProperty("description", r.getDescription());
                o.addProperty("category", r.getCategory().name());
                o.addProperty("requiredRealm", r.getRequiredRealm());
                o.addProperty("resultItemKey", r.getResultItemKey());
                o.addProperty("resultQuantity", r.getResultQuantity());
                if (r.getMaterial1Key() != null) o.addProperty("material1Key", r.getMaterial1Key());
                if (r.getMaterial1Key() != null) o.addProperty("material1Count", r.getMaterial1Count());
                if (r.getMaterial2Key() != null) o.addProperty("material2Key", r.getMaterial2Key());
                if (r.getMaterial2Key() != null) o.addProperty("material2Count", r.getMaterial2Count());
                if (r.getMaterial3Key() != null) o.addProperty("material3Key", r.getMaterial3Key());
                if (r.getMaterial3Key() != null) o.addProperty("material3Count", r.getMaterial3Count());
                o.addProperty("costGold", r.getCostGold());
                o.addProperty("costSpiritStones", r.getCostSpiritStones());
                o.addProperty("successRate", r.getSuccessRate());
                arr.add(o);
            }
            result.addProperty("code", 200);
            result.add("recipes", arr);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    private JsonObject handleCraftHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            long recipeId = req.has("recipeId") ? req.get("recipeId").getAsLong() : 0;
            if (recipeId <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供有效的配方ID(recipeId)");
                return result;
            }
            var service = ServiceRegistry.getCraftingService();
            Map<String, Object> data = service.craft(ctx.playerId(), recipeId);
            result.addProperty("code", 200);
            result.addProperty("success", (boolean) data.get("success"));
            result.addProperty("message", (String) data.get("message"));
            if (data.containsKey("craftSuccess")) result.addProperty("craftSuccess", (boolean) data.get("craftSuccess"));
            if (data.containsKey("expGained")) result.addProperty("expGained", ((Number) data.get("expGained")).longValue());
            if (data.containsKey("itemGained")) result.addProperty("itemGained", (String) data.get("itemGained"));
            if (data.containsKey("itemQuantity")) result.addProperty("itemQuantity", ((Number) data.get("itemQuantity")).intValue());
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
