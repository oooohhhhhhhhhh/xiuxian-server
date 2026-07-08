package com.mtxgdn.onebot.command.map;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.service.MapService;

import java.util.List;

public class MapCommand extends Command {

    private final MapService mapService = new MapService();

    public MapCommand() {
        super(new String[]{"地图", "map"}, "查看游戏地图",
                "/地图 — 查看当前位置与周围地点\n/地图 前往 <地名> — 前往相邻地点\n/地图 列表 [区域] — 浏览全地图",
                "探索", "game.player.info");

        registerSub(new String[]{"go", "前往"}, this::handleGo);
        registerSub(new String[]{"list", "列表", "浏览"}, this::handleList);
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) { ctx.reply("请先创建角色"); return; }

        var surround = mapService.getPlayerSurroundings(p.getId());
        if (!(boolean) surround.get("success")) {
            ctx.reply("获取地图信息失败"); return;
        }

        @SuppressWarnings("unchecked")
        var current = (java.util.Map<String, Object>) surround.get("current");

        StringBuilder sb = new StringBuilder();
        sb.append("📍 当前位置：【").append(current.get("name")).append("】")
                .append("（").append(current.get("region")).append("）\n");
        sb.append(current.get("description")).append("\n\n");

        @SuppressWarnings("unchecked")
        var neighbors = (List<java.util.Map<String, Object>>) surround.get("neighbors");
        if (neighbors.isEmpty()) {
            sb.append("此处荒无人烟，无路通往其他地方。");
        } else {
            sb.append("▍可前往的地点：\n");
            for (var nb : neighbors) {
                boolean accessible = (boolean) nb.get("accessible");
                String icon = accessible ? "🚶" : "🔒";
                String hint = accessible ? "" : " [境界不足]";
                sb.append("  ").append(icon).append(" /地图 go ").append(nb.get("name"))
                        .append("  → ").append(nb.get("name"))
                        .append("（").append(nb.get("region")).append("）").append(hint);
                if ((boolean) nb.get("safeZone")) sb.append(" 🛡安全区");
                sb.append("\n");
            }
        }
        sb.append("\n输入 /地图 list 浏览全图");

        ctx.reply(sb.toString());
    }

    private void handleGo(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (p == null) { ctx.reply("请先创建角色"); return; }
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply("用法: /地图 go <地名>"); return; }

        MapLocation target = mapService.getLocationByName(targetName);
        if (target == null) { ctx.reply("找不到地点【" + targetName + "】，试试 /地图 list 查看全图"); return; }

        var result = mapService.travel(p.getId(), target.getId());
        ctx.reply((String) result.get("message"));
    }

    private void handleList(CommandContext ctx, PlayerInfo p, String[] parts) {
        String region = parts.length > 1 ? parts[1].trim() : "";
        mapService.ensureInitialized();
        List<MapLocation> locations;
        if (!region.isEmpty()) {
            locations = mapService.getLocationsByRegion(region);
            if (locations.isEmpty()) {
                ctx.reply("未找到区域【" + region + "】"); return;
            }
        } else {
            locations = mapService.getAllLocations();
        }

        StringBuilder sb = new StringBuilder("===== 修仙界地图 =====\n");
        String currentRegion = "";
        for (MapLocation loc : locations) {
            if (!loc.getRegion().equals(currentRegion)) {
                currentRegion = loc.getRegion();
                sb.append("\n▍").append(currentRegion).append("\n");
            }
            long playerLocId = p != null ? mapService.getPlayerLocationId(p.getId()) : 0;
            String marker = (playerLocId == loc.getId()) ? " ★" : "";
            sb.append(String.format("  [%d] %s%s — 需要≥%s", loc.getId(), loc.getName(), marker,
                    loc.getMinRealm() == 0 ? "凡人" : "炼气+" + loc.getMinRealm()));
            if (loc.isSafeZone()) sb.append(" 🛡");
            sb.append("\n");
        }
        ctx.reply(sb.toString());
    }
}
