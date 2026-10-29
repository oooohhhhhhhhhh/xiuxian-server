package com.mtxgdn.websocket;

import com.mtxgdn.game.cultivation.HealResult;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SpiritualRoot;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.Map;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.gson;
import static com.mtxgdn.websocket.WsSupport.itemService;
import static com.mtxgdn.websocket.WsSupport.playerService;

/**
 * WebSocket · 角色域处理器：角色信息、疗伤、每日早课。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里 {@code player_info} / {@code heal} /
 * {@code morning_cultivation} 三个 action 的处理函数整块搬到这里。</p>
 *
 * <p><b>为什么单独成域</b>：这三个动作只读/写"当前登录玩家自己的角色记录"，
 * 依赖集合是 {@code playerService}（{@code player_info} 额外要 {@code itemService}
 * 数灵石），与战斗、宗门、坊市都不共享状态。它们和 {@code WsCultivationHandler}
 * 分开是因为后者要动经验、心魔与境界突破，属于另一条状态链。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级）。
 * 响应 type（{@code player_info} / {@code heal} / {@code morning_cultivation}）、
 * 字段名（{@code spiritualRoot}、{@code spiritStones}、{@code exists} …）、
 * 错误码与"失败时用 400 + message"的既有约定都没动。
 * {@code morning_cultivation} 里对每日服务的那次就地构造也原样保留 ——
 * 改成走注册表会换掉 WebSocket 通道看到的那份"每日天象"状态，属于行为改动。</p>
 */
final class WsPlayerHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("player_info", "heal", "morning_cultivation");

    WsPlayerHandler(WsContext context) {
        super(context);
    }

    void handleHeal(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "heal", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        HealResult result = playerService.healPlayer(player.getId());
        if (result.success()) {
            JsonObject data = gson.toJsonTree(result).getAsJsonObject();
            socket.send(GameMessage.ok(msgId, "heal", result.message(), data).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "heal", 400, result.message()).toJson());
        }
    }

    void handleMorningCultivation(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "morning_cultivation", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        Map<String, Object> result = new com.mtxgdn.game.service.DailyService().doMorningCultivation(player.getId());
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject data = gson.toJsonTree(result).getAsJsonObject();
            socket.send(GameMessage.ok(msgId, "morning_cultivation", (String) result.get("message"), data).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "morning_cultivation", 400, (String) result.get("message")).toJson());
        }
    }

    void handlePlayerInfo(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        JsonObject data = new JsonObject();

        if (player != null) {
            data = gson.toJsonTree(player).getAsJsonObject();

            SpiritualRoot root = player.getSpiritualRoot();
            if (root != null) {
                JsonObject rootObj = new JsonObject();
                rootObj.addProperty("key", root.name());
                rootObj.addProperty("displayName", root.getDisplayName());
                rootObj.addProperty("description", root.getDescription());
                rootObj.addProperty("tier", root.getTier().getDisplayName());
                rootObj.addProperty("attackBonus", root.getAttackBonus());
                rootObj.addProperty("hpBonus", root.getHpBonus());
                rootObj.addProperty("mpBonus", root.getMpBonus());
                rootObj.addProperty("spiritBonus", root.getSpiritBonus());
                rootObj.addProperty("defenseBonus", root.getDefenseBonus());
                rootObj.addProperty("speedBonus", root.getSpeedBonus());
                rootObj.addProperty("effectName", root.getEffect().name());
                rootObj.addProperty("effectValue", root.getEffectValue());
                data.add("spiritualRoot", rootObj);
            }

            long spiritStones = itemService.getSpiritStoneCount(player.getId());
            data.addProperty("spiritStones", spiritStones);
        } else {
            data.addProperty("exists", false);
        }

        GameMessage response = GameMessage.ok(msgId, "player_info", data);
        socket.send(response.toJson());
    }
}
