package com.mtxgdn.onebot.command.map;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
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
}
