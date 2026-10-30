package com.mtxgdn.rest;

import com.mtxgdn.game.economy.CurrencyExchangeResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.EconomyService;
import com.mtxgdn.game.service.TradeService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

import static com.mtxgdn.rest.GameSupport.gson;
import static com.mtxgdn.rest.GameSupport.playerService;
import static com.mtxgdn.rest.GameSupport.getPlayerIdByUserId;

/**
 * 玩家侧 · 坊市与货币兑换接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：坊市挂单列表、行情查询、
 * 挂单/购买/撤单、我的挂单，以及货币兑换。它们共用交易服务（挂单、行情、结算），
 * 与背包域只通过"扣物品"间接耦合，因此按依赖集合单独成类。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /market}、{@code /market/price}、{@code /market/list}、{@code /market/buy}、
 * {@code /market/cancel}、{@code /market/my_listings}、{@code /economy/currency-exchange}）
 * 逐字照搬；权限码 {@code game.market.view} / {@code game.market.list} /
 * {@code game.market.buy} / {@code game.market.cancel} / {@code game.player.info}
 * 全部保持原样，返回结构未动。</p>
 *
 * <p>{@code formatRemaining}（把剩余毫秒格式化成"3天2小时"）只被挂单列表与我的挂单用到，
 * 跟着本域搬进来。</p>
 */
@Path("/game")
public class GameEconomyResource {

    private static final TradeService tradeService = ServiceRegistry.getTradeService();

    @Context
    private ContainerRequestContext requestContext;

    /** 取当前登录用户 id；未登录抛 401。实现见 {@link GameSupport#getCurrentUserId}。 */
    private Long getCurrentUserId() {
        return GameSupport.getCurrentUserId(requestContext);
    }

    @GET
    @Path("/market")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.view")
    public Response getMarketListings() {
        var listings = tradeService.getActiveListings();
        JsonArray arr = new JsonArray();
        for (var l : listings) {
            JsonObject o = new JsonObject();
            o.addProperty("id", l.id);
            o.addProperty("sellerPlayerId", l.sellerPlayerId);
            o.addProperty("itemKey", l.itemKey);
            Item item = ItemRegistry.get(l.itemKey);
            o.addProperty("itemName", item != null ? item.getName() : l.itemKey);
            o.addProperty("quantity", l.quantity);
            o.addProperty("priceSpiritStones", l.priceSpiritStones);
            o.addProperty("fee", l.fee);
            o.addProperty("createdAt", l.createdAt);
            // 单价：挂单数量不同，只有单价之间可比
            o.addProperty("unitPrice", l.unitPrice());
            // 到期信息：expiresAt=0 表示历史挂单永不过期
            o.addProperty("expiresAt", l.expiresAt);
            long remaining = l.remainingMs();
            o.addProperty("remainingMs", remaining);
            if (remaining >= 0) {
                o.addProperty("remainingText", formatRemaining(remaining));
            }
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("listings", arr);
        data.addProperty("count", arr.size());
        data.addProperty("tradeFeeRate", "5%");
        data.addProperty("listingDurationHours", TradeService.getListingDurationHours());
        return Response.ok(GameMessage.restOk(I18n.t("market.list_title"), data).toString()).build();
    }

    @GET
    @Path("/market/price")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.view")
    public Response getMarketPrice(@QueryParam("itemKey") String itemKey,
                                   @QueryParam("item") String item,
                                   @QueryParam("days") String daysParam) {
        String key = (itemKey != null && !itemKey.isBlank()) ? itemKey : item;
        if (key == null || key.isBlank()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }
        int days = 0;
        if (daysParam != null && !daysParam.isBlank()) {
            try {
                days = Integer.parseInt(daysParam.trim());
            } catch (NumberFormatException ignored) {
                // 非法天数走默认窗口
            }
        }
        var quote = tradeService.getPriceHistory(key, days);
        JsonObject data = gson.toJsonTree(quote).getAsJsonObject();
        return Response.ok(GameMessage.restOk(quote.message(), data).toString()).build();
    }

    /** 把剩余毫秒格式化为"3天2小时"这类可读文本。 */
    private static String formatRemaining(long ms) {
        if (ms <= 0) return "已到期";
        long totalMinutes = ms / 60000;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        if (days > 0) return days + "天" + hours + "小时";
        if (hours > 0) return hours + "小时" + minutes + "分钟";
        return Math.max(1, minutes) + "分钟";
    }

    @POST
    @Path("/market/list")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.list")
    public Response listItem(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String itemKey = json.get("itemKey").getAsString();
        int quantity = json.has("quantity") ? json.get("quantity").getAsInt() : 1;
        long price = json.get("priceSpiritStones").getAsLong();
        var result = tradeService.listItem(playerId, itemKey, quantity, price);
        return Response.ok(GameMessage.restOk(result.message(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @POST
    @Path("/market/buy")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.buy")
    public Response buyItem(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        long listingId = json.get("listingId").getAsLong();
        var result = tradeService.buyItem(playerId, listingId);
        return Response.ok(GameMessage.restOk(result.message(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @POST
    @Path("/market/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.cancel")
    public Response cancelListing(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        long listingId = json.get("listingId").getAsLong();
        var result = tradeService.cancelListing(playerId, listingId);
        return Response.ok(GameMessage.restOk(result.message(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @GET
    @Path("/market/my_listings")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.view")
    public Response getMyListings() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        var listings = tradeService.getPlayerListings(playerId);
        JsonArray arr = new JsonArray();
        for (var l : listings) {
            JsonObject o = new JsonObject();
            o.addProperty("id", l.id);
            o.addProperty("itemKey", l.itemKey);
            Item item = ItemRegistry.get(l.itemKey);
            o.addProperty("itemName", item != null ? item.getName() : l.itemKey);
            o.addProperty("quantity", l.quantity);
            o.addProperty("priceSpiritStones", l.priceSpiritStones);
            o.addProperty("fee", l.fee);
            o.addProperty("unitPrice", l.unitPrice());
            o.addProperty("expiresAt", l.expiresAt);
            long remaining = l.remainingMs();
            o.addProperty("remainingMs", remaining);
            if (remaining >= 0) {
                o.addProperty("remainingText", formatRemaining(remaining));
            }
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("listings", arr);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/economy/currency-exchange")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response currencyExchange(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject json = gson.fromJson(body, JsonObject.class);
        String type = json.has("type") ? json.get("type").getAsString() : "";
        long amount = json.has("amount") ? json.get("amount").getAsLong() : 0;
        if (amount <= 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        EconomyService economyService = ServiceRegistry.getEconomyService();
        CurrencyExchangeResult result;
        switch (type) {
            case "goldToStones" -> result = economyService.exchangeGoldToStones(player.getId(), amount);
            case "stonesToGold" -> result = economyService.exchangeStonesToGold(player.getId(), amount);
            default -> result = CurrencyExchangeResult.rejected(
                    I18n.t("game.type_must_be_either_goldtostones"));
        }

        if (!result.success()) {
            return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
        }
        return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
    }
}
