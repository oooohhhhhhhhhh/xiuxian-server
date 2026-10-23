package com.mtxgdn.websocket;

import com.google.gson.JsonObject;
import com.mtxgdn.common.GameMessage;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.Set;

/**
 * WebSocket · 连接/全局信息域处理器：心跳 {@code heartbeat} 与季节信息 {@code season_info}。
 *
 * <p><b>拆出哪一块</b>：{@code heartbeat} 原来是分发 switch 里唯一一段<b>内联</b>的处理代码
 * （四行拼 {@code pong} 回包），这里把它提成方法 {@link #handleHeartbeat}，方法体逐字照搬；
 * {@code season_info} 原本就是独立方法 {@code handleSeasonInfo}，整块搬过来。</p>
 *
 * <p><b>为什么这两个放一起</b>：它们是仅有的两个<b>不需要角色、也不需要权限</b>的动作
 * —— 心跳只要连接活着就能回（客户端用它维持前台/后台状态），季节信息是全局公开数据。
 * 其余 53 个 action 都要先解析出玩家或先过权限码，因此这两个单独成域，
 * 分发 switch 里它们也是唯一不带 {@code checkWsPermission} 的分支。</p>
 *
 * <p><b>协议不变的保证</b>：{@code heartbeat} 的回包 type 仍是 {@code pong}
 * （不是 {@code heartbeat}）、{@code msgId} 原样回显、{@code data.timestamp}
 * 仍是服务器当前毫秒；{@code season_info} 的十一个字段（季节/倍率/起止时间/剩余毫秒）
 * 逐字保留。心跳<b>不</b>在这里刷新 {@code lastActivity} —— 那条逻辑在
 * {@code GameWebSocketApp.onMessage} 的最开头，任何入站消息都算活跃，
 * 拆分类没有把它挪走。</p>
 */
final class WsSystemHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("heartbeat", "season_info");

    WsSystemHandler(WsContext context) {
        super(context);
    }

    void handleHeartbeat(WebSocket socket, long msgId) {
        JsonObject pongData = new JsonObject();
        pongData.addProperty("timestamp", System.currentTimeMillis());
        GameMessage pong = GameMessage.ok(msgId, "pong", pongData);
        socket.send(pong.toJson());
    }

    void handleSeasonInfo(WebSocket socket, long msgId) {
        JsonObject data = new JsonObject();
        data.addProperty("season", com.mtxgdn.game.service.SeasonService.getCurrentSeason().name());
        data.addProperty("seasonDisplay", com.mtxgdn.game.service.SeasonService.getCurrentSeason().getDisplayName());
        data.addProperty("seasonDesc", com.mtxgdn.game.service.SeasonService.getCurrentSeason().getDescription());
        data.addProperty("seasonEffect", com.mtxgdn.game.service.SeasonService.getSeasonEffectDescription());
        data.addProperty("cultivationMultiplier", com.mtxgdn.game.service.SeasonService.getCultivationMultiplier());
        data.addProperty("explorationMultiplier", com.mtxgdn.game.service.SeasonService.getExplorationMultiplier());
        data.addProperty("spiritStoneMultiplier", com.mtxgdn.game.service.SeasonService.getSpiritStoneMultiplier());
        data.addProperty("dropMultiplier", com.mtxgdn.game.service.SeasonService.getDropMultiplier());
        data.addProperty("seasonStartMs", com.mtxgdn.game.service.SeasonService.getSeasonStartMs());
        data.addProperty("seasonEndMs", com.mtxgdn.game.service.SeasonService.getSeasonEndMs());
        data.addProperty("remainingMs", com.mtxgdn.game.service.SeasonService.getSeasonRemainingMs());
        GameMessage response = GameMessage.ok(msgId, "season_info", data);
        socket.send(response.toJson());
    }
}
