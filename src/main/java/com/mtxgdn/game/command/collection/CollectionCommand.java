package com.mtxgdn.game.command.collection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.collection.CollectionCategory;
import com.mtxgdn.game.collection.CollectionReward;
import com.mtxgdn.game.collection.CollectionService;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.i18n.I18n;

import java.util.List;
import java.util.Map;

/**
 * 全图鉴指令：{@code /图鉴}。
 *
 * <p>三个入口：无参数看总览、{@code 查看 <分类>} 翻条目、{@code 奖励} 看奖励配置。
 * 首次访问会先做一次回填（把背包/称号/已学功法技能等已有状态补进图鉴），
 * 老玩家不用重新收集一遍。</p>
 *
 * <p>同时注册两条 REST 路由（{@code collection/overview}、{@code collection/category}），
 * 权限 {@code game.collection.view}，模块 {@code collection}。</p>
 */
public class CollectionCommand extends Command {

    private static final String PERMISSION = "game.collection.view";
    private static final int PAGE_SIZE = 20;

    public CollectionCommand() {
        super(new String[]{"图鉴", "collection", "atlas"},
                "全图鉴：查看收集进度与奖励",
                "/图鉴 - 查看各分类收集进度\n"
                        + "/图鉴 查看 <分类> [页码] - 查看某分类的条目\n"
                        + "/图鉴 奖励 - 查看图鉴奖励配置",
                "我的角色",
                PERMISSION);
        setI18nKeys("cmd.collection.desc", "cmd.collection.usage");
        setModule("collection");

        registerSub(new String[]{"查看", "分类", "list"}, (ctx, p, parts) -> {
            String categoryName = parts.length > 1 ? parts[1] : null;
            int page = parts.length > 2 ? parsePage(parts[2]) : 1;
            ctx.reply(buildCategoryText(p, categoryName, page));
        });
        registerSub(new String[]{"奖励", "rewards"}, (ctx, p, parts) -> ctx.reply(buildRewardText()));

        addRoute(RouteDefinition.get("collection/overview", PERMISSION, this::handleOverviewHttp));
        addRoute(RouteDefinition.get("collection/category", PERMISSION, this::handleCategoryHttp));
    }

    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        ctx.reply(buildOverviewText(p));
    }

    /** 未知子命令当成分类名：{@code /图鉴 物品} 等价于 {@code /图鉴 查看 物品}。 */
    @Override
    protected void onUnknown(CommandContext ctx, PlayerInfo p, String sub, String[] parts) {
        if (CollectionCategory.fromKey(sub) == null) {
            ctx.reply(I18n.t("game.usage", getUsage()));
            return;
        }
        int page = parts.length > 1 ? parsePage(parts[1]) : 1;
        ctx.reply(buildCategoryText(p, sub, page));
    }

    // ==================== 文本 ====================

    private String buildOverviewText(PlayerInfo p) {
        CollectionService service = services().getCollectionService();
        service.backfill(p.getId());
        List<CollectionService.CategoryProgress> progress = service.overview(p.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("══════ 全图鉴 ══════\n");
        if (progress.isEmpty()) {
            sb.append("图鉴模块未启用\n");
            return sb.toString();
        }
        long unlockedTotal = 0;
        long totalAll = 0;
        for (CollectionService.CategoryProgress item : progress) {
            unlockedTotal += item.unlocked();
            totalAll += item.total();
            sb.append("▍").append(item.displayName()).append(" ")
                    .append(item.unlocked()).append("/").append(item.total())
                    .append(" (").append(item.percent()).append("%)");
            if (item.completed()) {
                sb.append("  ★已集齐");
            }
            sb.append('\n');
        }
        int percent = totalAll <= 0 ? 0 : (int) Math.floor(unlockedTotal * 100.0 / totalAll);
        sb.append("──────────────\n");
        sb.append("总计: ").append(unlockedTotal).append("/").append(totalAll)
                .append(" (").append(percent).append("%)\n");
        sb.append("提示: /图鉴 查看 <分类> 看条目；/图鉴 奖励 看奖励配置");
        return sb.toString();
    }

    private String buildCategoryText(PlayerInfo p, String categoryName, int page) {
        CollectionService service = services().getCollectionService();
        service.backfill(p.getId());
        CollectionCategory category = CollectionCategory.fromKey(categoryName);
        if (category == null) {
            StringBuilder sb = new StringBuilder("可用分类：\n");
            for (CollectionCategory item : service.enabledCategories()) {
                sb.append("· ").append(item.getDisplayName())
                        .append("（").append(item.getKey()).append("）\n");
            }
            sb.append("用法: /图鉴 查看 <分类> [页码]");
            return sb.toString();
        }
        CollectionService.CategoryDetail detail = service.detail(p.getId(), category, page, PAGE_SIZE);
        StringBuilder sb = new StringBuilder();
        sb.append("===== ").append(detail.displayName()).append("图鉴 ")
                .append(detail.unlocked()).append("/").append(detail.total()).append(" =====\n");
        if (detail.entries().isEmpty()) {
            sb.append("（该分类暂无条目）\n");
            return sb.toString();
        }
        int index = (detail.page() - 1) * PAGE_SIZE;
        for (CollectionService.EntryView entry : detail.entries()) {
            index++;
            sb.append('[').append(index).append("] ")
                    .append(entry.unlocked() ? "✔ " : "✘ ")
                    .append(entry.name());
            if (entry.unlocked()) {
                sb.append("（已解锁）");
            }
            sb.append('\n');
        }
        if (detail.completed()) {
            sb.append("★ 该分类已集齐，集齐奖励已发放\n");
        }
        sb.append("第 ").append(detail.page()).append("/").append(detail.totalPages()).append(" 页");
        if (detail.totalPages() > 1) {
            sb.append("，/图鉴 查看 ").append(detail.displayName()).append(' ')
                    .append(detail.page() < detail.totalPages() ? detail.page() + 1 : 1)
                    .append(" 翻页");
        }
        return sb.toString();
    }

    private String buildRewardText() {
        CollectionService service = services().getCollectionService();
        StringBuilder sb = new StringBuilder("══════ 图鉴奖励 ══════\n");
        Map<CollectionCategory, List<CollectionReward>> rewards = service.rewardOverview();
        if (rewards.isEmpty()) {
            sb.append("图鉴模块未启用\n");
            return sb.toString();
        }
        rewards.forEach((category, list) -> {
            CollectionReward perEntry = list.isEmpty() ? CollectionReward.EMPTY : list.get(0);
            CollectionReward completion = list.size() > 1 ? list.get(1) : CollectionReward.EMPTY;
            sb.append("▍").append(category.getDisplayName()).append('\n');
            sb.append("  每条: ").append(perEntry.describe()).append('\n');
            sb.append("  集齐: ").append(completion.describe()).append('\n');
        });
        sb.append("（奖励在 config/collection.yml 里逐分类/逐条配置）");
        return sb.toString();
    }

    private static int parsePage(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    // ==================== REST ====================

    private JsonObject handleOverviewHttp(RouteDefinition.RestContext ctx) {
        CollectionService service = services().getCollectionService();
        long playerId = ctx.playerId();
        service.backfill(playerId);
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("enabled", service.enabled());
        JsonArray categories = new JsonArray();
        long unlockedTotal = 0;
        long totalAll = 0;
        for (CollectionService.CategoryProgress item : service.overview(playerId)) {
            JsonObject node = new JsonObject();
            node.addProperty("key", item.category().getKey());
            node.addProperty("name", item.displayName());
            node.addProperty("unlocked", item.unlocked());
            node.addProperty("total", item.total());
            node.addProperty("percent", item.percent());
            node.addProperty("completed", item.completed());
            node.add("completionReward", rewardJson(item.completionReward()));
            categories.add(node);
            unlockedTotal += item.unlocked();
            totalAll += item.total();
        }
        root.add("categories", categories);
        root.addProperty("unlockedTotal", unlockedTotal);
        root.addProperty("total", totalAll);
        return root;
    }

    private JsonObject handleCategoryHttp(RouteDefinition.RestContext ctx) {
        CollectionService service = services().getCollectionService();
        long playerId = ctx.playerId();
        service.backfill(playerId);
        CollectionCategory category = CollectionCategory.fromKey(ctx.queryParam("name"));
        JsonObject root = new JsonObject();
        if (category == null) {
            root.addProperty("success", false);
            root.addProperty("message", "未知分类，请传 name=item/title/technique/skill/secret_realm/exploration_event/map_location/recipe");
            return root;
        }
        int page = parsePage(ctx.queryParam("page"));
        CollectionService.CategoryDetail detail = service.detail(playerId, category, page, PAGE_SIZE);
        root.addProperty("success", true);
        root.addProperty("key", category.getKey());
        root.addProperty("name", detail.displayName());
        root.addProperty("unlocked", detail.unlocked());
        root.addProperty("total", detail.total());
        root.addProperty("page", detail.page());
        root.addProperty("totalPages", detail.totalPages());
        root.addProperty("completed", detail.completed());
        root.add("completionReward", rewardJson(detail.completionReward()));
        JsonArray entries = new JsonArray();
        for (CollectionService.EntryView entry : detail.entries()) {
            JsonObject node = new JsonObject();
            node.addProperty("key", entry.key());
            node.addProperty("name", entry.name());
            node.addProperty("unlocked", entry.unlocked());
            node.addProperty("unlockedAt", entry.unlockedAt());
            entries.add(node);
        }
        root.add("entries", entries);
        return root;
    }

    private static JsonObject rewardJson(CollectionReward reward) {
        JsonObject node = new JsonObject();
        node.addProperty("gold", reward.gold());
        node.addProperty("spiritStones", reward.spiritStones());
        node.addProperty("exp", reward.exp());
        JsonObject items = new JsonObject();
        reward.items().forEach(items::addProperty);
        node.add("items", items);
        return node;
    }
}
