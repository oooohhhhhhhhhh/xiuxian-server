package com.mtxgdn.onebot.command.map;

import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.MapService;

/**
 * {@code /采集} —— 在当前野外地点采集资源。
 *
 * <p>只能在野外使用。资源量由野外地图文件按危险度档位随机生成，采一点少一点，
 * 直到下次野外刷新；采集有冷却，具体数值见 {@code config/game/wilderness_map.json}。</p>
 */
public class GatherCommand extends Command {

    private final MapService mapService = ServiceRegistry.getMapService();

    public GatherCommand() {
        super(new String[]{"采集", "gather", "collect"}, "在野外采集资源",
                "/采集 — 采集当前野外地点的资源（需身处野外）",
                "探索", "game.player.info");
        setModule("map");
        // HTTP：POST /api/game/map/gather（与 OneBot 的 /采集 等价）
        addRoute(RouteDefinition.post("map/gather", "game.player.info", this::httpGather));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) { ctx.reply("请先创建角色"); return; }

        var result = mapService.gather(p.getId());
        ctx.reply((String) result.get("message"));
    }

    // ==================== HTTP：POST /api/game/map/gather ====================

    /**
     * 与 OneBot 的 {@code /采集} 等价的接口。
     *
     * <p>成功时除 {@code message} 外还返回 {@code data}：采集到的物品
     * （{@code itemKey} / {@code itemName} / {@code quantity}）与该地剩余量
     * （{@code remaining}），方便客户端直接展示与刷新。</p>
     */
    private JsonObject httpGather(RouteDefinition.RestContext ctx) {
        JsonObject out = new JsonObject();
        var result = mapService.gather(ctx.playerId());
        boolean success = Boolean.TRUE.equals(result.get("success"));

        out.addProperty("code", success ? 200 : 400);
        out.addProperty("success", success);
        out.addProperty("message", String.valueOf(result.get("message")));

        if (success && result.get("itemKey") != null) {
            JsonObject data = new JsonObject();
            data.addProperty("itemKey", String.valueOf(result.get("itemKey")));
            data.addProperty("itemName", String.valueOf(result.get("itemName")));
            data.addProperty("quantity", ((Number) result.get("quantity")).intValue());
            data.addProperty("remaining", ((Number) result.get("remaining")).intValue());
            out.add("data", data);
        }
        return out;
    }
}
