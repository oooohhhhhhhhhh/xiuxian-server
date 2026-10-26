package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.PlayerDAO;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.TradeService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mtxgdn.rest.AdminSupport.LOG;
import static com.mtxgdn.rest.AdminSupport.gson;

/**
 * 管理台 · 坊市管理接口（挂单总览、成交行情）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：{@code /market/**}
 * 只依赖 {@code TradeService}、物品注册表与一份"批量查玩家名"的本地 SQL 辅助，
 * 与其它管理端点没有共享状态，因此按"路径前缀 + 依赖集合"整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码（{@code admin.economy}）、
 * 返回结构全部保持原样，前端 {@code /api/admin/market/*} 不受影响。
 * 这两个接口<b>不受</b>「交易」玩法插件门控影响这一点也保持不变。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /market/listings} 拼出来的地址与拆分前
 * 逐字一致（Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminMarketResource {

    private static final PlayerDAO PLAYER_DAO = new PlayerDAO();

    // ==================== 坊市管理（挂单 / 成交行情） ====================

    /**
     * 坊市挂单总览。
     * <p>
     * 与 {@code GET /game/market} 的区别：本接口是<b>只读管理视图</b>，用管理台权限
     * （admin.economy）而非玩家权限，且不受「交易」玩法插件门控影响 ——
     * 即使交易插件已停用，管理员仍能查看存量挂单与成交历史。
     */
    @GET
    @Path("/market/listings")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.economy")
    public Response getMarketListings() {
        JsonObject out = new JsonObject();
        try {
            var listings = ServiceRegistry.getTradeService().getActiveListings();
            // 一次性查出卖家名，避免逐行查库（挂单可能有上百条）
            Map<Long, String> sellerNames = loadPlayerNames(
                    listings.stream().map(l -> l.sellerPlayerId).distinct().toList());

            JsonArray arr = new JsonArray();
            for (var l : listings) {
                Item item = ItemRegistry.get(l.itemKey);
                JsonObject o = new JsonObject();
                o.addProperty("id", l.id);
                o.addProperty("sellerPlayerId", l.sellerPlayerId);
                o.addProperty("sellerName", sellerNames.get(l.sellerPlayerId));
                o.addProperty("itemKey", l.itemKey);
                o.addProperty("itemName", item != null ? item.getName() : l.itemKey);
                o.addProperty("quantity", l.quantity);
                o.addProperty("priceSpiritStones", l.priceSpiritStones);
                o.addProperty("unitPrice", l.unitPrice());
                o.addProperty("fee", l.fee);
                o.addProperty("createdAt", l.createdAt);
                o.addProperty("expiresAt", l.expiresAt);
                long remaining = l.remainingMs();
                o.addProperty("remainingMs", remaining);
                // expiresAt=0（历史遗留挂单）时 remainingMs 返回 -1，语义是"永不过期"
                o.addProperty("remainingText", remaining < 0 ? "永不过期" : formatDuration(remaining));
                arr.add(o);
            }
            out.addProperty("code", 200);
            JsonObject data = new JsonObject();
            data.add("listings", arr);
            data.addProperty("count", arr.size());
            data.addProperty("listingDurationHours", TradeService.getListingDurationHours());
            data.addProperty("priceHistoryKeepDays", TradeService.getPriceHistoryKeepDays());
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.failed_to_fetch_market_listings", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 某物品的成交行情（均价/最低/最高 + 当前在售最低价），供管理台做定价参考。
     *
     * @param itemKey 物品 key（支持短 key）
     * @param days    统计窗口天数；空或非法值走服务端默认 7 天，上限 90 天
     */
    @GET
    @Path("/market/price")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.economy")
    public Response getMarketPrice(@QueryParam("itemKey") String itemKey,
                                   @QueryParam("item") String item,
                                   @QueryParam("days") String daysParam) {
        JsonObject out = new JsonObject();
        String key = (itemKey != null && !itemKey.isBlank()) ? itemKey : item;
        if (key == null || key.isBlank()) {
            out.addProperty("code", 400);
            out.addProperty("message", I18n.t("admin.please_provide_the_itemkey_query"));
            return Response.ok(gson.toJson(out)).build();
        }
        int days = 0;
        if (daysParam != null && !daysParam.isBlank()) {
            try {
                days = Integer.parseInt(daysParam.trim());
            } catch (NumberFormatException ignored) {
                // 非法天数走服务端默认窗口
            }
        }
        try {
            Map<String, Object> quote = ServiceRegistry.getTradeService().getPriceHistory(key, days);
            boolean ok = Boolean.TRUE.equals(quote.get("success"));
            out.addProperty("code", ok ? 200 : 400);
            out.addProperty("message", String.valueOf(quote.getOrDefault("message", "")));
            JsonObject data = new JsonObject();
            for (Map.Entry<String, Object> e : quote.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (v instanceof Number n) data.addProperty(e.getKey(), n);
                else if (v instanceof Boolean b) data.addProperty(e.getKey(), b);
                else data.addProperty(e.getKey(), String.valueOf(v));
            }
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.failed_to_query_market_prices", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 批量查询玩家名（playerId → name）；查不到的玩家不入 map，避免逐行查库。 */
    private static Map<Long, String> loadPlayerNames(List<Long> playerIds) {
        try {
            return PLAYER_DAO.findNamesByIds(playerIds);
        } catch (RuntimeException e) {
            // 玩家名查不到不影响挂单列表本身，管理员仍能看到 id 与物品
            LOG.warn("批量查询玩家名失败: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 剩余毫秒 → "3天2小时" 这类可读文本。 */
    private static String formatDuration(long ms) {
        if (ms <= 0) return "已到期";
        long totalMinutes = ms / 60000;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        if (days > 0) return days + "天" + hours + "小时";
        if (hours > 0) return hours + "小时" + minutes + "分钟";
        return Math.max(1, minutes) + "分钟";
    }
}
