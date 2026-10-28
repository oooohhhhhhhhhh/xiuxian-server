package com.mtxgdn.websocket;

import com.mtxgdn.common.result.OperationResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Technique;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.gson;
import static com.mtxgdn.websocket.WsSupport.playerService;
import static com.mtxgdn.websocket.WsSupport.techniqueService;

/**
 * WebSocket · 功法域处理器：功法总表、我的功法、学习/装备/卸下/升级。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里 {@code techniques} /
 * {@code my_techniques} / {@code technique_learn} / {@code technique_equip} /
 * {@code technique_unequip} / {@code technique_upgrade} 六个 action 的处理函数整块搬到这里。</p>
 *
 * <p><b>为什么单独成域</b>：六个动作全部只依赖 {@code techniqueService}
 * （外加读一次角色记录拿 playerId），与物品、宗门、地图没有交集；
 * 而且它们是同一套"列表 → 学习 → 装备/卸下 → 升级"的流程，
 * 拆成两个类反而会让这条流程的首尾分离。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级）。
 * 四个写动作成功时 {@code data} 为 {@code null}（回包里没有 data 字段）、
 * 失败时统一用 {@code 400 + message} 的既有约定，以及
 * {@code my_techniques} 在 Gson 序列化之上追加 {@code scaledXxx} 字段的做法，
 * 都逐字保留。权限码（{@code game.technique.learn} / {@code .equip} / {@code .upgrade}）
 * 仍写在分发 switch 里，注意 {@code technique_unequip} 用的是 {@code .equip}，
 * 与拆分前一致。</p>
 */
final class WsTechniqueHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("techniques", "my_techniques", "technique_learn",
            "technique_equip", "technique_unequip", "technique_upgrade");

    WsTechniqueHandler(WsContext context) {
        super(context);
    }

    void handleTechniques(WebSocket socket, long msgId) {
        List<Technique> techniques = techniqueService.getAllTechniques();
        JsonArray arr = new JsonArray();
        for (Technique t : techniques) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.getId());
            o.addProperty("name", t.getName());
            o.addProperty("description", t.getDescription());
            o.addProperty("requiredRealm", t.getRequiredRealm());
            o.addProperty("learnCostGold", t.getLearnCostGold());
            o.addProperty("learnCostSpiritStones", t.getLearnCostSpiritStones());
            o.addProperty("type", t.getType().name());
            o.addProperty("maxLevel", t.getMaxLevel());
            o.addProperty("hpBonus", t.getHpBonus());
            o.addProperty("mpBonus", t.getMpBonus());
            o.addProperty("attackBonus", t.getAttackBonus());
            o.addProperty("defenseBonus", t.getDefenseBonus());
            o.addProperty("speedBonus", t.getSpeedBonus());
            o.addProperty("spiritBonus", t.getSpiritBonus());
            o.addProperty("cultivationSpeedBonus", t.getCultivationSpeedBonus());
            o.addProperty("expBonus", t.getExpBonus());
            o.addProperty("combatDamageBonus", t.getCombatDamageBonus());
            o.addProperty("damageReduction", t.getDamageReduction());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("techniques", arr);
        socket.send(GameMessage.ok(msgId, "techniques", data).toJson());
    }

    void handleMyTechniques(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "my_techniques", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        List<Technique> techniques = techniqueService.getPlayerTechniques(player.getId());
        JsonArray arr = new JsonArray();
        for (Technique t : techniques) {
            JsonObject o = gson.toJsonTree(t).getAsJsonObject();
            o.addProperty("scaledHpBonus", t.getScaledHpBonus());
            o.addProperty("scaledMpBonus", t.getScaledMpBonus());
            o.addProperty("scaledAttackBonus", t.getScaledAttackBonus());
            o.addProperty("scaledDefenseBonus", t.getScaledDefenseBonus());
            o.addProperty("scaledSpeedBonus", t.getScaledSpeedBonus());
            o.addProperty("scaledSpiritBonus", t.getScaledSpiritBonus());
            o.addProperty("scaledCultivationSpeedBonus", t.getScaledCultivationSpeedBonus());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("techniques", arr);
        socket.send(GameMessage.ok(msgId, "my_techniques", data).toJson());
    }

    void handleTechniqueLearn(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("techniqueId")) {
            socket.send(GameMessage.error(msgId, "technique_learn", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "technique_learn", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long techniqueId = data.get("techniqueId").getAsLong();
        OperationResult result = techniqueService.learnTechnique(player.getId(), techniqueId);
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "technique_learn", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "technique_learn", 400, result.message()).toJson());
        }
    }

    void handleTechniqueEquip(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("techniqueId")) {
            socket.send(GameMessage.error(msgId, "technique_equip", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "technique_equip", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long techniqueId = data.get("techniqueId").getAsLong();
        OperationResult result = techniqueService.equipTechnique(player.getId(), techniqueId);
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "technique_equip", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "technique_equip", 400, result.message()).toJson());
        }
    }

    void handleTechniqueUnequip(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("techniqueId")) {
            socket.send(GameMessage.error(msgId, "technique_unequip", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "technique_unequip", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long techniqueId = data.get("techniqueId").getAsLong();
        OperationResult result = techniqueService.unequipTechnique(player.getId(), techniqueId);
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "technique_unequip", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "technique_unequip", 400, result.message()).toJson());
        }
    }

    void handleTechniqueUpgrade(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("techniqueId")) {
            socket.send(GameMessage.error(msgId, "technique_upgrade", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "technique_upgrade", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long techniqueId = data.get("techniqueId").getAsLong();
        OperationResult result = techniqueService.upgradeTechnique(player.getId(), techniqueId);
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "technique_upgrade", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "technique_upgrade", 400, result.message()).toJson());
        }
    }
}
