package com.mtxgdn.websocket;

import com.mtxgdn.game.item.UseItemResult;
import com.mtxgdn.game.item.EnhanceResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.actionLog;
import static com.mtxgdn.websocket.WsSupport.enhanceService;
import static com.mtxgdn.websocket.WsSupport.gson;
import static com.mtxgdn.websocket.WsSupport.itemService;
import static com.mtxgdn.websocket.WsSupport.playerService;

/**
 * WebSocket · 物品域处理器：背包、使用物品、物品图鉴、装备强化。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里 {@code inventory} / {@code item_use} /
 * {@code item_registry} / {@code equipment_enhance} 四个 action 的处理函数整块搬到这里。</p>
 *
 * <p><b>为什么单独成域</b>：四个动作都只碰 {@code itemService}（强化额外用
 * {@code enhanceService}），不涉及经验、宗门、地图。原来它们在文件里被拆成两段
 * （背包三连在中间，强化在后面 crafting 之后），搬到一起之后"物品相关的写操作"
 * 集中在一处，权限码（{@code game.inventory.view} / {@code game.item.use} /
 * {@code game.item.registry} / {@code game.equipment.enhance}）也仍然逐条写在
 * 分发 switch 里。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级）。
 * 背包条目的序列化方式（{@code gson.toJsonTree(entry.getItem())} 再补
 * {@code quantity}）、图鉴的全量字段、{@code item_use} 失败时用
 * {@code ITEM_USE_FAILED} 而不是 400 的既有差异、强化的回包结构都没动。</p>
 */
final class WsInventoryHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS =
            Set.of("inventory", "item_use", "item_registry", "equipment_enhance");

    WsInventoryHandler(WsContext context) {
        super(context);
    }

    void handleInventory(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            GameMessage err = GameMessage.error(msgId, "inventory", GameErrorCode.PLAYER_NOT_FOUND);
            socket.send(err.toJson());
            return;
        }

        List<ItemService.InventoryEntry> inventory = itemService.getInventory((int) player.getId());

        JsonArray items = new JsonArray();
        for (ItemService.InventoryEntry entry : inventory) {
            JsonObject obj = gson.toJsonTree(entry.getItem()).getAsJsonObject();
            obj.addProperty("quantity", entry.getQuantity());
            items.add(obj);
        }

        JsonObject data = new JsonObject();
        data.add("items", items);
        data.addProperty("count", items.size());

        GameMessage response = GameMessage.ok(msgId, "inventory", data);
        socket.send(response.toJson());
    }

    void handleItemUse(WebSocket socket, long msgId, Long userId, JsonObject reqData) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            GameMessage err = GameMessage.error(msgId, "item_use", GameErrorCode.PLAYER_NOT_FOUND);
            socket.send(err.toJson());
            return;
        }

        String itemKey = reqData != null && reqData.has("itemKey") ? reqData.get("itemKey").getAsString() : "";
        if (itemKey.isEmpty()) {
            GameMessage err = GameMessage.error(msgId, "item_use", GameErrorCode.PARAM_MISSING);
            socket.send(err.toJson());
            return;
        }

        UseItemResult useResult = itemService.useItem((int) player.getId(), itemKey);

        boolean success = useResult.success();
        String msg = useResult.message() != null ? useResult.message() : "";
        actionLog.logItemUse(userId, player.getName(), itemKey, success, msg);

        JsonObject respData = gson.toJsonTree(useResult).getAsJsonObject();

        GameMessage response = success
                ? GameMessage.ok(msgId, "item_use", msg, respData)
                : GameMessage.error(msgId, "item_use", GameErrorCode.ITEM_USE_FAILED.getCode(), msg);
        socket.send(response.toJson());
    }

    void handleItemRegistry(WebSocket socket, long msgId) {
        JsonArray items = new JsonArray();
        for (Item item : ItemRegistry.getAll()) {
            items.add(gson.toJsonTree(item));
        }

        JsonObject data = new JsonObject();
        data.add("items", items);
        data.addProperty("count", items.size());

        GameMessage response = GameMessage.ok(msgId, "item_registry", data);
        socket.send(response.toJson());
    }

    void handleEquipmentEnhance(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("slot")) {
            socket.send(GameMessage.error(msgId, "equipment_enhance", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "equipment_enhance", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        String slot = data.get("slot").getAsString();
        EnhanceResult result = enhanceService.enhanceItem(player.getId(), slot);
        if (result.success()) {
            JsonObject respData = gson.toJsonTree(result).getAsJsonObject();
            socket.send(GameMessage.ok(msgId, "equipment_enhance", result.message(), respData).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "equipment_enhance", 400, result.message()).toJson());
        }
    }
}
