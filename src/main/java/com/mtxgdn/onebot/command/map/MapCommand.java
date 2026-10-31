package com.mtxgdn.onebot.command.map;

import com.mtxgdn.game.map.PlayerSurroundings;
import com.mtxgdn.game.map.TravelResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.service.MapService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mtxgdn.i18n.I18n;

public class MapCommand extends Command {

    private final MapService mapService = ServiceRegistry.getMapService();

    public MapCommand() {
        super(new String[]{"地图", "map"}, "查看游戏地图",
                "/地图 — 查看当前位置与周围地点\n/地图 前往 <地名> — 前往相邻地点\n"
                        + "/地图 传送 <地名> — 付费直传（需已到过该地）\n/地图 列表 [区域] — 浏览全地图与探索度",
                "探索", "game.player.info");
        
        setI18nKeys("cmd.map.desc", "cmd.map.usage");
    setModule("map");

        registerSub(new String[]{"go", "前往"}, this::handleGo);
        registerSub(new String[]{"list", "列表", "浏览"}, this::handleList);
        registerSub(new String[]{"tp", "传送"}, this::handleTeleport);
    }

    @Override
    public void execute(CommandContext ctx) {
        // 有参数时走基类的子命令分发（go/前往、list/列表/浏览）。
        // 此前这里直接渲染当前位置就返回了，从未调用 super.execute()，
        // 于是 handleGo 与 handleList 全是死代码 —— /地图 list、/地图 go 都只会打印当前位置，
        // 那段硬编码 ASCII 全图也因此一直没人发现它早就过期了。
        String arg = ctx.getArg();
        if (arg != null && !arg.trim().isEmpty()) {
            super.execute(ctx);
            return;
        }

        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) { ctx.reply(I18n.t("map.please_create_a_character_first")); return; }

        PlayerSurroundings surround = mapService.getPlayerSurroundings(p.getId());
        if (!surround.success()) {
            ctx.reply(I18n.t("map.failed_to_retrieve_map_information")); return;
        }

        var current = surround.current();

        StringBuilder sb = new StringBuilder();
        sb.append("📍 当前位置：【").append(current.name()).append("】")
                .append("（").append(current.region()).append("）\n");

        // 危险度：安全区不打标，否则每个安全地点都挂一行"安全"，纯属噪音。
        // 注意这些字段在顶层结果里，不在 current 简介里 —— 以前从 current 取，
        // 一直是 null，所以危险度与野外标记从来没显示过。
        int dangerLevel = surround.dangerLevel() != null ? surround.dangerLevel() : 0;
        boolean inWilderness = Boolean.TRUE.equals(surround.wilderness());
        if (dangerLevel > 0) {
            sb.append("☠ 危险度：").append(surround.dangerName())
                    .append("（").append(dangerLevel).append("/4）");
            if (inWilderness) sb.append(" · 野外");
            sb.append("\n");
        } else if (inWilderness) {
            sb.append("☠ 危险度：安全 · 野外\n");
        }
        sb.append(current.description()).append("\n");

        if (surround.cultivationBonus() != null && surround.cultivationBonus() != 1.0) {
            sb.append("灵气浓度: ×").append(surround.cultivationBonus()).append("（闭关修炼加成）\n");
        }
        if (surround.explorationBonus() != null && surround.explorationBonus() != 1.0) {
            sb.append("地界加成: ×").append(surround.explorationBonus()).append("（游历/秘境收益）\n");
        }

        // 野外资源：野外是随机生成且会被采空的，因此必须显示剩余量，否则玩家无从判断值不值得来
        if (inWilderness) {
            sb.append(renderWildernessResource(surround));
        }
        sb.append("\n");

        var neighbors = surround.neighbors();
        if (neighbors.isEmpty()) {
            sb.append("此处荒无人烟，无路通往其他地方。");
        } else {
            sb.append("▍可前往的地点：\n");
            for (var nb : neighbors) {
                String icon = nb.accessible() ? "🚶" : "🔒";
                String hint = nb.accessible() ? "" : " [境界不足]";
                // 地名只在 "/地图 go <地名>" 里出现一次。早先这里还额外拼了一段
                // " → 目的地名"，渲染出来是「/地图 go 青云山  → 青云山」——
                // 目的地已经包含在命令里了，重复纯属噪音。
                sb.append("  ").append(icon).append(" /地图 go ").append(nb.name())
                        .append("（").append(nb.region()).append(" · 路程约 ").append(nb.travelSeconds()).append("秒）").append(hint);
                if (nb.safeZone()) sb.append(" 🛡安全区");
                if (nb.dangerLevel() > 0) sb.append(" ☠").append(nb.dangerName());
                if (nb.wilderness()) sb.append(" 🌿野外");
                sb.append("\n");
            }
        }
        sb.append("\n输入 /地图 list 浏览全图与探索度");

        ctx.reply(sb.toString());
    }

    /** 野外资源与到期时间（供当前位置展示）。 */
    private String renderWildernessResource(PlayerSurroundings surround) {
        StringBuilder sb = new StringBuilder();
        var resources = surround.wildernessResource();
        if (resources == null || resources.isEmpty()) {
            sb.append("🌿 此地资源已被采空，等下次野外刷新\n");
        } else {
            sb.append("🌿 可采集：");
            boolean first = true;
            for (var entry : resources.entrySet()) {
                if (!first) sb.append("、");
                sb.append(itemName(entry.getKey())).append(" ×").append(entry.getValue());
                first = false;
            }
            sb.append("（用 /采集 采集）\n");
        }
        long expiresAt = surround.wildernessExpiresAt() != null ? surround.wildernessExpiresAt() : 0;
        if (expiresAt > 0) {
            long leftMinutes = Math.max(0, (expiresAt - System.currentTimeMillis() / 1000) / 60);
            sb.append("⏳ 此地约 ").append(leftMinutes).append(" 分钟后随野外刷新消失\n");
        }
        return sb.toString();
    }

    /** 物品 key → 显示名；取不到就显示 key 本身（便于发现配置里写错的物品）。 */
    private static String itemName(String itemKey) {
        try {
            var item = com.mtxgdn.game.item.ItemRegistry.resolve(itemKey);
            return item != null ? item.getName() : itemKey;
        } catch (Exception e) {
            return itemKey;
        }
    }

    /**
     * 付费直传：到过的地点才解锁。
     *
     * <p>与 /地图 go 的区别是"无视路径"—— go 只能去相邻地点，传送可以跨全图；
     * 代价是灵石，且必须已经亲自抵达过，所以不会把探索本身的意义抹掉。</p>
     */
    private void handleTeleport(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (p == null) { ctx.reply(I18n.t("map.please_create_a_character_first")); return; }
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply(I18n.t("map.usage")); return; }

        // 用 token 而不是按名字查：野外节点不在地点表里、名字还是随机的，只能按 id 指定
        MapLocation target = mapService.getLocationByToken(targetName);
        if (target == null) {
            ctx.reply(I18n.t("map.location_not_found", targetName));
            return;
        }

        TravelResult result = mapService.teleportByUnlock(p.getId(), target.getId());
        ctx.reply(result.message());
    }

    private void handleGo(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (p == null) { ctx.reply(I18n.t("map.please_create_a_character_first")); return; }
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply(I18n.t("map.usage_2")); return; }

        MapLocation target = mapService.getLocationByToken(targetName);
        if (target == null) { ctx.reply(I18n.t("map.location_not_found", targetName)); return; }

        TravelResult result = mapService.travel(p.getId(), target.getId());
        ctx.reply(result.message());
    }

    private void handleList(CommandContext ctx, PlayerInfo p, String[] parts) {
        String region = parts.length > 1 ? parts[1].trim() : "";
        mapService.ensureInitialized();
        List<MapLocation> locations;
        if (!region.isEmpty()) {
            locations = mapService.getLocationsByRegion(region);
            if (locations.isEmpty()) {
                ctx.reply(I18n.t("map.region_not_found", region)); return;
            }
        } else {
            locations = mapService.getAllLocations();
        }

        // 修复 N+1 查询：玩家当前位置只需查一次，不要在循环里逐条查询。
        // 探索记录同样一次查完 —— 逐个 isExplored 会变成 15 次查询。
        long playerLocId = p != null ? mapService.getPlayerLocationId(p.getId()) : 0;
        Set<Long> explored = p != null ? mapService.getExploredLocationIds(p.getId()) : Set.of();

        StringBuilder sb = new StringBuilder("===== 修仙界地图 =====\n");

        // 探索度只统计已知世界：野外节点是流动的，把它算进完成度会让进度条永远无法"走满"
        if (p != null) {
            long totalKnown = mapService.getKnownWorldLocations().size();
            long exploredKnown = mapService.countExploredKnownWorld(p.getId());
            long percent = totalKnown > 0 ? exploredKnown * 100 / totalKnown : 0;
            sb.append("🧭 探索度：").append(exploredKnown).append("/").append(totalKnown)
                    .append("（").append(percent).append("%）");
            if (totalKnown > 0 && exploredKnown >= totalKnown) {
                sb.append(" 🏆已走遍全图");
            }
            sb.append("\n");
            sb.append("   首次抵达新地点有灵石与灵力奖励；到过的地点可用 /地图 传送 <地名> 付费直传\n");
        }

        // 按区域归并后再输出：地点 id 并不按区域连续（南荒是 8、12；中原是 4~6、13~14；
        // 西域是 7、15），原先"区域名一变就打一次标题"会让同一区域出现多个重复标题。
        Map<String, List<MapLocation>> byRegion = new LinkedHashMap<>();
        for (MapLocation loc : locations) {
            byRegion.computeIfAbsent(loc.getRegion(), k -> new ArrayList<>()).add(loc);
        }
        for (Map.Entry<String, List<MapLocation>> entry : byRegion.entrySet()) {
            sb.append("\n▍").append(entry.getKey()).append("\n");
            for (MapLocation loc : entry.getValue()) {
                String marker = (playerLocId == loc.getId()) ? " ★" : "";
                // ✅/❔ 是探索迷雾：名字不隐藏（/地图 go 需要名字，藏了就没法导航），
                // 但"去没去过"是进度指标，必须一眼可辨
                String exploredIcon = p == null ? "" : (explored.contains(loc.getId()) ? "✅" : "❔");
                sb.append(String.format("  %s[%d] %s%s — 需要≥%s", exploredIcon, loc.getId(), loc.getName(), marker,
                        mapService.getRealmName(loc.getMinRealm())));
                sb.append(" 灵气×").append(loc.getCultivationBonus());
                if (loc.isSafeZone()) sb.append(" 🛡");
                if (loc.getDangerLevel() > 0) {
                    sb.append(" ☠").append(mapService.getDangerName(loc.getDangerLevel()));
                }
                sb.append("\n");
            }
        }

        if (region.isEmpty() && mapService.isWildernessEnabled()) {
            sb.append("\n▍野外\n");
            sb.append("  一片由世界种子生成的无尽荒域，越远越险；用 /野外 查看你所处的分片\n");
        }

        sb.append("\n▍世界脉络（自当前所在地展开，仅示主干）\n");
        sb.append(renderWorldTree(playerLocId));
        ctx.reply(sb.toString());
    }

    /**
     * 由真实连接关系生成世界脉络树。
     *
     * <p>这里原先是一段硬编码的 ASCII 图，只画到 (10)问道峰 —— 东海龙宫、万妖谷、灵药谷、
     * 天机阁、白骨岭 5 个地点与 8 条连接全都不在图上，而且永远不会自己更新。地图数据既然
     * 已经是活的（可在后台改库并热重载），这张图就必须跟着数据走，否则只会越改越不准。</p>
     */
    private String renderWorldTree(long rootId) {
        MapLocation root = mapService.getLocation(rootId);
        if (root == null) {
            List<MapLocation> all = mapService.getAllLocations();
            if (all.isEmpty()) return "  （地图数据为空）\n";
            root = all.get(0);
        }

        StringBuilder sb = new StringBuilder();
        Set<Long> visited = new HashSet<>();
        visited.add(root.getId());
        sb.append("  ").append(nodeLabel(root)).append("\n");
        appendChildren(root, "  ", visited, sb);

        // 兜底：万一有地点与主干不连通，单独列出来，避免"地图上少了一块"却没人发现
        List<MapLocation> orphans = new ArrayList<>();
        for (MapLocation loc : mapService.getAllLocations()) {
            if (!visited.contains(loc.getId())) orphans.add(loc);
        }
        if (!orphans.isEmpty()) {
            sb.append("  （未与主干连通）");
            for (MapLocation orphan : orphans) {
                sb.append(" ").append(nodeLabel(orphan));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void appendChildren(MapLocation node, String prefix, Set<Long> visited, StringBuilder sb) {
        List<MapLocation> children = node.getConnections().stream()
                .sorted(Comparator.comparingLong(MapLocation::getId))
                .toList();
        for (int i = 0; i < children.size(); i++) {
            MapLocation child = children.get(i);
            // 兄弟子树可能已经展开过它（图里有环），跳过而不是重复画一遍
            if (visited.contains(child.getId())) continue;
            // 末尾判断要看"后面还有没有没画过的兄弟"，不能只看下标
            boolean last = true;
            for (int j = i + 1; j < children.size(); j++) {
                if (!visited.contains(children.get(j).getId())) { last = false; break; }
            }
            visited.add(child.getId());
            sb.append("  ").append(prefix).append(last ? "└─ " : "├─ ").append(nodeLabel(child)).append("\n");
            appendChildren(child, prefix + (last ? "   " : "│  "), visited, sb);
        }
    }

    private static String nodeLabel(MapLocation loc) {
        return "(" + loc.getId() + ")" + loc.getName();
    }
}
