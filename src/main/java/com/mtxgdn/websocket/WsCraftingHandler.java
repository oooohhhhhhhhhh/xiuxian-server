package com.mtxgdn.websocket;

import com.mtxgdn.game.crafting.CraftResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Recipe;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.craftingService;
import static com.mtxgdn.websocket.WsSupport.playerService;

/**
 * WebSocket · 炼制域处理器：配方表、按配方炼制。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里 {@code crafting_recipes} /
 * {@code crafting_craft} 两个 action 的处理函数整块搬到这里。</p>
 *
 * <p><b>为什么单独成域</b>：只依赖 {@code craftingService}（炼制额外读一次角色记录），
 * 与物品域的背包/使用/强化是两条不同的写路径；配方分类的解析（把客户端字符串
 * {@code toUpperCase()} 后交给 {@code Recipe.Category.valueOf}）也只在这一处。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级）。
 * 配方里三个材料字段"仅当非 null 才下发"的条件、非法分类时的
 * {@code 400 "无效的分类"}、炼制成功时按 {@code containsKey} 逐个补字段的写法，
 * 以及响应 type 字段，全部逐字保留。权限码（{@code game.crafting.recipes} /
 * {@code game.crafting.craft}）仍写在分发 switch 里。</p>
 */
final class WsCraftingHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("crafting_recipes", "crafting_craft");

    WsCraftingHandler(WsContext context) {
        super(context);
    }

    void handleCraftingRecipes(WebSocket socket, long msgId, JsonObject data) {
        List<Recipe> recipes;
        if (data != null && data.has("category")) {
            try {
                recipes = craftingService.getRecipesByCategory(Recipe.Category.valueOf(data.get("category").getAsString().toUpperCase()));
            } catch (IllegalArgumentException e) {
                socket.send(GameMessage.error(msgId, "crafting_recipes", 400, "无效的分类").toJson());
                return;
            }
        } else {
            recipes = craftingService.getAllRecipes();
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
        JsonObject respData = new JsonObject();
        respData.add("recipes", arr);
        socket.send(GameMessage.ok(msgId, "crafting_recipes", respData).toJson());
    }

    void handleCraftingCraft(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("recipeId")) {
            socket.send(GameMessage.error(msgId, "crafting_craft", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "crafting_craft", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long recipeId = data.get("recipeId").getAsLong();
        CraftResult result = craftingService.craft(player.getId(), recipeId);
        if (result.success()) {
            JsonObject respData = new JsonObject();
            respData.addProperty("message", result.message());
            if (result.craftSuccess() != null) respData.addProperty("craftSuccess", result.craftSuccess());
            if (result.expGained() != null) respData.addProperty("expGained", result.expGained());
            if (result.itemGained() != null) respData.addProperty("itemGained", result.itemGained());
            if (result.itemQuantity() != null) respData.addProperty("itemQuantity", result.itemQuantity());
            socket.send(GameMessage.ok(msgId, "crafting_craft", result.message(), respData).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "crafting_craft", 400, result.message()).toJson());
        }
    }
}
