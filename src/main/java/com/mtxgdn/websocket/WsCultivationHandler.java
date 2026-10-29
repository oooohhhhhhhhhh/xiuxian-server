package com.mtxgdn.websocket;

import com.mtxgdn.game.cultivation.BeginCultivationResult;
import com.mtxgdn.game.cultivation.FinishCultivationResult;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.service.HeartDemonService;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.Map;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.actionLog;
import static com.mtxgdn.websocket.WsSupport.gson;
import static com.mtxgdn.websocket.WsSupport.heartDemonService;
import static com.mtxgdn.websocket.WsSupport.playerService;
import static com.mtxgdn.websocket.WsSupport.realmService;

/**
 * WebSocket · 修炼域处理器：开始闭关、结束闭关（含心魔结算）、境界突破。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里 {@code cultivate_start} /
 * {@code cultivate_stop} / {@code breakthrough} 三个 action 的处理函数整块搬到这里。</p>
 *
 * <p><b>为什么单独成域</b>：这三个动作写的是同一条状态链 —— 闭关开始时记时间戳，
 * 结束时按秒结算经验并让 {@code heartDemonService} 抽心魔，突破则把经验换成境界。
 * 它们共同依赖 {@code playerService} + {@code realmService} + {@code heartDemonService}，
 * 与角色域（只读角色记录）、物品域（只动物品）互不重叠。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级）。
 * 响应 type、字段（{@code cultivationPerSecond}、{@code experienceGained}、
 * {@code heartDemonTriggered} …）、提示文案的拼接方式（"开始闭关修炼（地点）"、
 * "停止修炼，获得了 N 经验"）、错误码（{@code REALM_NOT_CULTIVATING}）都没变；
 * {@code breakthrough} 里"成功与失败都走 {@code GameMessage.ok}"这个既有约定
 * 也原样保留（两个分支现在看起来冗余，但改掉就是改协议）。</p>
 *
 * <p><b>执行顺序</b>：{@code cultivate_stop} 里"先结算经验、再写行为日志、
 * 再回读玩家"的次序不能调换（日志记的是净经验，回包里的玩家是加了经验之后的），
 * 拆分没有改变这个顺序。</p>
 */
final class WsCultivationHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("cultivate_start", "cultivate_stop", "breakthrough");

    WsCultivationHandler(WsContext context) {
        super(context);
    }

    void handleCultivateStart(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);

        if (player == null) {
            GameMessage err = GameMessage.error(msgId, "cultivate_start", GameErrorCode.PLAYER_NOT_FOUND);
            socket.send(err.toJson());
            return;
        }

        BeginCultivationResult result = playerService.beginCultivation(player.getId());
        if (!result.success()) {
            GameMessage err = GameMessage.error(msgId, "cultivate_start", GameErrorCode.REALM_NOT_CULTIVATING.getCode(), result.message());
            socket.send(err.toJson());
            return;
        }

        actionLog.logCultivateStart(userId, player.getName(), player.getRealm());

        JsonObject data = new JsonObject();
        data.addProperty("cultivating", true);
        data.addProperty("cultivationPerSecond", result.cultivationPerSecond());
        data.addProperty("startTime", result.startTime());
        data.addProperty("season", result.season());
        data.addProperty("locationName", result.locationName());
        data.addProperty("locationMultiplier", result.locationMultiplier());

        GameMessage response = GameMessage.ok(msgId, "cultivate_start",
                "开始闭关修炼（" + result.locationName() + "）", data);
        socket.send(response.toJson());
    }

    void handleCultivateStop(WebSocket socket, long msgId, Long userId, JsonObject reqData) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);

        if (player == null) {
            GameMessage err = GameMessage.error(msgId, "cultivate_stop", GameErrorCode.PLAYER_NOT_FOUND);
            socket.send(err.toJson());
            return;
        }

        FinishCultivationResult result = playerService.finishCultivation(player.getId());
        if (!result.success()) {
            GameMessage err = GameMessage.error(msgId, "cultivate_stop", GameErrorCode.REALM_NOT_CULTIVATING.getCode(), result.message());
            socket.send(err.toJson());
            return;
        }

        int elapsedSeconds = result.elapsedSeconds().intValue();
        long expGained = result.rawExpGained();

        HeartDemonService.HeartDemonResult hdResult = heartDemonService.processCultivation(
                player.getId(), expGained, elapsedSeconds);
        long netExp = hdResult.netExpChange;
        if (netExp != 0) {
            playerService.addExperience(player.getId(), netExp);
        }

        actionLog.logCultivateStop(userId, player.getName(), netExp, elapsedSeconds);

        PlayerInfo updated = playerService.getPlayerByUserId(userId);
        JsonObject respData = gson.toJsonTree(updated).getAsJsonObject();
        respData.addProperty("experienceGained", netExp);
        respData.addProperty("elapsedSeconds", elapsedSeconds);
        if (hdResult.triggered) {
            respData.addProperty("heartDemonTriggered", true);
            respData.addProperty("heartDemonSeverity", hdResult.severity);
            respData.addProperty("heartDemonNarrative", hdResult.narrative);
            respData.addProperty("heartDemonExpLost", hdResult.expLost);
        }

        String msg;
        if (hdResult.triggered) {
            msg = "修炼结束，" + hdResult.narrative;
        } else {
            msg = "停止修炼，获得了 " + netExp + " 经验";
        }

        GameMessage response = GameMessage.ok(msgId, "cultivate_stop", msg, respData);
        socket.send(response.toJson());
    }

    void handleBreakthrough(WebSocket socket, long msgId, Long userId) {
        RealmBreakthroughResult btResult = realmService.tryBreakthrough(userId);

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        String name = player != null ? player.getName() : "未知玩家";
        actionLog.logBreakthrough(userId, name, btResult.isSuccess(), btResult.getMessage());

        JsonObject data = gson.toJsonTree(btResult).getAsJsonObject();

        if (btResult.isSuccess()) {
            PlayerInfo updated = playerService.getPlayerByUserId(userId);
            data.add("player", gson.toJsonTree(updated));

            GameMessage response = GameMessage.ok(msgId, "breakthrough", btResult.getMessage(), data);
            socket.send(response.toJson());
        } else {
            GameMessage response = GameMessage.ok(msgId, "breakthrough", btResult.getMessage(), data);
            socket.send(response.toJson());
        }
    }
}
