package com.mtxgdn.onebot.command.map;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.map.WildernessTerrain;
import com.mtxgdn.game.service.MapService;

import java.util.List;
import java.util.Map;

/**
 * {@code /野外} —— 查看你当前所在的野外分片。
 *
 * <p>已知世界（{@code /地图 list}）是固定的 15 个地点；野外则是一片由世界种子
 * （{@code config/game/wilderness_map.json}）确定性生成、<b>可以无限向外扩展</b>的分片世界：
 * 每个分片 4×4 个节点，走多远生成多远，没人去过的地方根本不占任何存储。</p>
 *
 * <p>本指令显示<b>当前分片</b>的地形与资源，以及"往外再走一片"的方向 ——
 * 野外节点名是随机生成的，因此导航靠 id（{@code /地图 go <id>}）而不是名字。</p>
 */
public class WildernessCommand extends Command {

    private final MapService mapService = ServiceRegistry.getMapService();

    public WildernessCommand() {
        super(new String[]{"野外", "wilderness", "wild"}, "查看当前野外分片与资源",
                "/野外 — 查看所在分片的地形、危险度、资源与邻片",
                "探索", "game.player.info");
        setModule("map");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) { ctx.reply("请先创建角色"); return; }

        if (!mapService.isWildernessEnabled()) {
            ctx.reply("野外地图当前已关闭（可在 config/game/wilderness_map.json 里把 enabled 改为 true）");
            return;
        }

        long playerLocId = mapService.getPlayerLocationId(p.getId());
        int[] chunk = mapService.getChunkOf(playerLocId);
        long now = System.currentTimeMillis() / 1000;

        StringBuilder sb = new StringBuilder("===== 野外 =====\n");

        if (chunk == null) {
            // 站在已知世界里：给出每个出境口对应的野外入口分片
            sb.append("你正站在已知世界。从下列出境口可踏入荒域：\n");
            for (MapLocation entry : mapService.getAllLocations()) {
                long gatewayId = gatewayInto(entry);
                if (gatewayId <= 0) continue;
                MapLocation gateway = mapService.getLocation(gatewayId);
                if (gateway == null) continue;
                sb.append("\n🚪 ").append(entry.getName()).append(" → ")
                        .append(gateway.getName()).append("（").append(gateway.getRegion()).append("）\n");
                sb.append("   危险度：").append(mapService.getDangerName(gateway.getDangerLevel()))
                        .append(" · 需要≥").append(mapService.getRealmName(gateway.getMinRealm())).append("\n");
                sb.append("   /地图 go ").append(gatewayId).append("\n");
            }
            sb.append("\n提示：野外不是安全区，越往外越险；用 /采集 采集资源。");
            ctx.reply(sb.toString());
            return;
        }

        List<MapLocation> nodes = mapService.getChunkNodes(chunk[0], chunk[1]);
        if (nodes.isEmpty()) {
            ctx.reply("你所在的位置已不在世界边界之内，请联系管理员");
            return;
        }

        sb.append("分片 (").append(chunk[0]).append(",").append(chunk[1]).append(") · ")
                .append(nodes.size()).append(" 个节点\n");
        sb.append("地形：").append(nodes.get(0).getRegion()).append("\n");
        int distance = WildernessTerrain.distanceFromOrigin(chunk[0], chunk[1]);
        if (distance > 0) {
            sb.append("距中土 ").append(distance).append(" 片 · 越远越险\n");
        }
        if (mapService.isChunkPinned(chunk[0], chunk[1])) {
            sb.append("📌 本片已留有你的足迹，永久保留、不会被回收\n");
        }

        long restockAt = mapService.getChunk(chunk[0], chunk[1]).restockAt;
        if (restockAt > now) {
            sb.append("⏳ 资源补货：约 ").append(Math.max(0, (restockAt - now) / 60)).append(" 分钟后\n");
        }

        for (MapLocation node : nodes) {
            boolean accessible = p.getRealm() >= node.getMinRealm();
            sb.append("\n").append(accessible ? "🌿 " : "🔒 ").append(node.getName());
            if (playerLocId == node.getId()) sb.append(" ★你在这里");
            sb.append("\n");
            sb.append("   [").append(node.getId()).append("] 危险度：")
                    .append(mapService.getDangerName(node.getDangerLevel()))
                    .append("（").append(node.getDangerLevel()).append("/4）")
                    .append(" · 需要≥").append(mapService.getRealmName(node.getMinRealm()));
            if (!accessible) sb.append(" [境界不足]");
            sb.append("\n");
            sb.append("   灵气×").append(node.getCultivationBonus())
                    .append(" 地界加成×").append(node.getExplorationBonus()).append("\n");
            sb.append("   ").append(renderResources(node)).append("\n");

            if (playerLocId == node.getId()) {
                sb.append("   🚶 ").append(renderExits(node, p)).append("\n");
            }
        }

        sb.append("\n提示：用 /地图 go <id> 移动，/采集 采集资源。");
        ctx.reply(sb.toString());
    }

    /** 站在某个节点上时，把可去的邻居列出来（含"往邻片去"的方向）。 */
    private String renderExits(MapLocation node, PlayerInfo p) {
        List<MapLocation> neighbors = mapService.getNeighbors(node.getId());
        if (neighbors.isEmpty()) {
            return "四下无路";
        }
        StringBuilder sb = new StringBuilder("可去：");
        boolean first = true;
        for (MapLocation nb : neighbors) {
            if (!first) sb.append("、");
            String name = nb.isWilderness() ? nb.getName() + "(" + nb.getRegion() + ")" : nb.getName();
            sb.append(name).append("[").append(nb.getId()).append("]");
            if (p.getRealm() < nb.getMinRealm()) sb.append("🔒");
            first = false;
        }
        return sb.toString();
    }

    /** 该已知世界地点通向的野外入口节点 id；不是出境口则返回 0。 */
    private long gatewayInto(MapLocation entry) {
        for (MapLocation connected : entry.getConnections()) {
            if (connected.isWilderness()) {
                return connected.getId();
            }
        }
        return 0;
    }

    /** 剩余资源一行；采空时明确说明，避免玩家白跑一趟。 */
    private String renderResources(MapLocation node) {
        Map<String, Integer> resources = mapService.getWildernessResource(node.getId());
        if (resources.isEmpty()) {
            return "🌿 资源已被采空";
        }
        StringBuilder sb = new StringBuilder("🌿 可采集：");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : resources.entrySet()) {
            if (!first) sb.append("、");
            sb.append(itemName(entry.getKey())).append(" ×").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    /** 物品 key → 显示名；取不到就显示 key 本身（便于发现地图文件里写错的物品）。 */
    private static String itemName(String itemKey) {
        try {
            var item = com.mtxgdn.game.item.ItemRegistry.resolve(itemKey);
            return item != null ? item.getName() : itemKey;
        } catch (Exception e) {
            return itemKey;
        }
    }
}
