package com.mtxgdn.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.entity.PlayerInfo;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.mapService;
import static com.mtxgdn.websocket.WsSupport.playerService;

/**
 * WebSocket · 地图域处理器：周围地点、移动、地点总表。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里 {@code map_surroundings} /
 * {@code map_travel} / {@code map_locations} 三个 action 的处理函数整块搬到这里。</p>
 *
 * <p><b>为什么单独成域</b>：只依赖 {@code mapService}（读角色记录只为拿 playerId），
 * 且 {@code map_locations} 还带着"首次调用先 {@code ensureInitialized()} 再取缓存"的
 * 前置动作 —— 这条初始化次序必须和同域的其它读取放在一起，否则容易被漏掉。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级）。
 * {@code map_surroundings} 里 {@code neighbors} 的字段（含
 * {@code travelSeconds} 缺省 5 秒的兜底）、{@code cultivationBonus} /
 * {@code explorationBonus} 仅在是数字时才下发、以及三个动作失败时的
 * {@code 400 + message}，全部逐字保留。</p>
 */
final class WsMapHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("map_surroundings", "map_travel", "map_locations");

    WsMapHandler(WsContext context) {
        super(context);
    }

    void handleMapSurroundings(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "map_surroundings", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        Map<String, Object> result = mapService.getPlayerSurroundings(player.getId());
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject resp = new JsonObject();
            @SuppressWarnings("unchecked")
            var current = (Map<String, Object>) result.get("current");
            JsonObject cur = new JsonObject();
            cur.addProperty("id", (long) current.get("id"));
            cur.addProperty("name", (String) current.get("name"));
            cur.addProperty("description", (String) current.get("description"));
            cur.addProperty("region", (String) current.get("region"));
            cur.addProperty("safeZone", (boolean) current.get("safeZone"));
            resp.add("current", cur);

            JsonArray neighbors = new JsonArray();
            @SuppressWarnings("unchecked")
            var nbs = (List<Map<String, Object>>) result.get("neighbors");
            for (var nb : nbs) {
                JsonObject n = new JsonObject();
                n.addProperty("id", (long) nb.get("id"));
                n.addProperty("name", (String) nb.get("name"));
                n.addProperty("region", (String) nb.get("region"));
                n.addProperty("safeZone", (boolean) nb.get("safeZone"));
                n.addProperty("accessible", (boolean) nb.get("accessible"));
                n.addProperty("travelSeconds", ((Number) nb.getOrDefault("travelSeconds", 5)).intValue());
                neighbors.add(n);
            }
            resp.add("neighbors", neighbors);
            Object cultivationBonus = result.get("cultivationBonus");
            if (cultivationBonus instanceof Number) {
                resp.addProperty("cultivationBonus", ((Number) cultivationBonus).doubleValue());
            }
            Object explorationBonus = result.get("explorationBonus");
            if (explorationBonus instanceof Number) {
                resp.addProperty("explorationBonus", ((Number) explorationBonus).doubleValue());
            }
            socket.send(GameMessage.ok(msgId, "map_surroundings", resp).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "map_surroundings", 400, (String) result.get("message")).toJson());
        }
    }

    void handleMapTravel(WebSocket socket, long msgId, Long userId, JsonObject data) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "map_travel", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        if (!data.has("locationId") || data.get("locationId").getAsLong() == 0) {
            socket.send(GameMessage.error(msgId, "map_travel", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        long locationId = data.get("locationId").getAsLong();
        Map<String, Object> result = mapService.travel(player.getId(), locationId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject resp = new JsonObject();
            resp.addProperty("from", (String) result.get("from"));
            resp.addProperty("to", (String) result.get("to"));
            resp.addProperty("toRegion", (String) result.get("toRegion"));
            resp.addProperty("safeZone", (boolean) result.get("safeZone"));
            socket.send(GameMessage.ok(msgId, "map_travel", (String) result.get("message"), resp).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "map_travel", 400, (String) result.get("message")).toJson());
        }
    }

    void handleMapLocations(WebSocket socket, long msgId) {
        mapService.ensureInitialized();
        List<MapLocation> locations = mapService.getAllLocations();
        JsonArray arr = new JsonArray();
        for (MapLocation loc : locations) {
            JsonObject o = new JsonObject();
            o.addProperty("id", loc.getId());
            o.addProperty("name", loc.getName());
            o.addProperty("description", loc.getDescription());
            o.addProperty("region", loc.getRegion());
            o.addProperty("minRealm", loc.getMinRealm());
            o.addProperty("safeZone", loc.isSafeZone());
            o.addProperty("cultivationBonus", loc.getCultivationBonus());
            o.addProperty("explorationBonus", loc.getExplorationBonus());
            arr.add(o);
        }
        JsonObject resp = new JsonObject();
        resp.add("locations", arr);
        socket.send(GameMessage.ok(msgId, "map_locations", resp).toJson());
    }
}
