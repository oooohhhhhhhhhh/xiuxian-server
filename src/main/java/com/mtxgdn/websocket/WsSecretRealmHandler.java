package com.mtxgdn.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SecretRealmResult;
import com.mtxgdn.game.secretrealm.SecretRealm;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.List;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.actionLog;
import static com.mtxgdn.websocket.WsSupport.explorationService;
import static com.mtxgdn.websocket.WsSupport.gson;
import static com.mtxgdn.websocket.WsSupport.playerService;
import static com.mtxgdn.websocket.WsSupport.secretRealmService;

/**
 * WebSocket · 秘境/探索域处理器：秘境列表、进入秘境、游历探索。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里 {@code secret_realm_areas} /
 * {@code secret_realm_enter} / {@code exploration} 三个 action 的处理函数整块搬到这里。</p>
 *
 * <p><b>为什么单独成域</b>：秘境与探索共用同一批依赖（{@code playerService} + 冷却判定），
 * 也都是"有冷却的一次性产出"玩法，内置插件门控里还同属
 * {@code secret_realm} / {@code exploration} 两个模块（门控仍在
 * {@code GameWebSocketApp.wsModuleForAction}，不在本类）。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级）。
 * 秘境列表的字段（{@code requiredRealm} / {@code cooldownSeconds} 由毫秒折算）与
 * {@code count}、进入失败时的错误码（{@code SECRET_REALM_NOT_FOUND}）、
 * 探索失败时的 {@code EXPLORATION_COOLDOWN}、以及"先写行为日志再判断成败"的既有次序
 * （日志里会记下失败原因）都逐字保留。</p>
 */
final class WsSecretRealmHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("secret_realm_areas", "secret_realm_enter", "exploration");

    WsSecretRealmHandler(WsContext context) {
        super(context);
    }

    void handleSecretRealmAreas(WebSocket socket, long msgId, Long userId) {
        List<SecretRealm> areas = secretRealmService.getAvailableAreas(userId);

        JsonArray areasArray = new JsonArray();
        for (SecretRealm area : areas) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", area.getName());
            obj.addProperty("requiredRealm", area.getRequiredRealm());
            obj.addProperty("cooldownSeconds", area.getCooldownMs() / 1000);
            obj.addProperty("description", area.getDescription());
            areasArray.add(obj);
        }

        JsonObject data = new JsonObject();
        data.add("areas", areasArray);
        data.addProperty("count", areasArray.size());

        GameMessage response = GameMessage.ok(msgId, "secret_realm_areas", data);
        socket.send(response.toJson());
    }

    void handleSecretRealmEnter(WebSocket socket, long msgId, Long userId, JsonObject reqData) {
        if (reqData == null || !reqData.has("area")) {
            GameMessage err = GameMessage.error(msgId, "secret_realm_enter", GameErrorCode.PARAM_MISSING);
            socket.send(err.toJson());
            return;
        }

        String area = reqData.get("area").getAsString();
        SecretRealmResult result = secretRealmService.enterSecretRealm(userId, area);

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        String name = player != null ? player.getName() : "未知玩家";
        actionLog.logSecretRealmEnter(userId, name, area, result.isSuccess(), result.getMessage());

        if (!result.isSuccess()) {
            GameMessage err = GameMessage.error(msgId, "secret_realm_enter", GameErrorCode.SECRET_REALM_NOT_FOUND.getCode(), result.getMessage());
            socket.send(err.toJson());
            return;
        }

        JsonObject respData = gson.toJsonTree(result).getAsJsonObject();
        GameMessage response = GameMessage.ok(msgId, "secret_realm_enter", result.getMessage(), respData);
        socket.send(response.toJson());
    }

    void handleExploration(WebSocket socket, long msgId, Long userId) {
        ExplorationResult result = explorationService.explore(userId);

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        String name = player != null ? player.getName() : "未知玩家";
        String eventName = result.getEventType() != null ? result.getEventType() : "未知事件";
        actionLog.logExploration(userId, name, eventName, result.getMessage());

        if (!result.isSuccess()) {
            GameMessage err = GameMessage.error(msgId, "exploration", GameErrorCode.EXPLORATION_COOLDOWN.getCode(), result.getMessage());
            socket.send(err.toJson());
            return;
        }

        JsonObject respData = gson.toJsonTree(result).getAsJsonObject();
        GameMessage response = GameMessage.ok(msgId, "exploration", result.getMessage(), respData);
        socket.send(response.toJson());
    }
}
