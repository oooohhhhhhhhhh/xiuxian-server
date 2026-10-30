package com.mtxgdn.rest;

import com.mtxgdn.game.map.PlayerSurroundings;
import com.mtxgdn.game.map.TravelResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.MapService;
import com.mtxgdn.i18n.GameLanguage;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.i18n.I18nContext;
import com.mtxgdn.i18n.I18nManager;
import com.mtxgdn.i18n.LanguageResolver;
import com.mtxgdn.Main;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import static com.mtxgdn.rest.GameSupport.gson;
import static com.mtxgdn.rest.GameSupport.playerService;
import static com.mtxgdn.rest.GameSupport.itemService;
import static com.mtxgdn.rest.GameSupport.getPlayerIdByUserId;

/**
 * 玩家侧 · 系统与公共查询接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：服务器状态、多语言词条包/
 * 语言列表/语言偏好读写、排行榜、地图周边/移动/地点列表。这一组是"跨玩法的基础设施
 * 查询"，不属于任何一个玩法模块，依赖集合（玩家服务、物品服务、地图服务）也彼此独立，
 * 因此合在一个类里。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /status}、{@code /i18n/bundle}、{@code /i18n/languages}、{@code /i18n/lang}、
 * {@code /rank}、{@code /map}、{@code /map/locations}、{@code /map/travel/{locationId}}）
 * 逐字照搬，路径模板参数名未改；权限码 {@code game.i18n} / {@code game.rank.view} /
 * {@code game.player.info} 保持原样，{@code /i18n/bundle}、{@code /i18n/languages}、
 * {@code /status} 依旧不带权限码（前端未登录也要能调），返回结构未动。</p>
 *
 * <p>{@code formatUptime} 只被 {@code /status} 用到，跟着搬进来。</p>
 */
@Path("/game")
public class GameSystemResource {

    private static final MapService mapService = ServiceRegistry.getMapService();

    @Context
    private ContainerRequestContext requestContext;

    /** 取当前登录用户 id；未登录抛 401。实现见 {@link GameSupport#getCurrentUserId}。 */
    private Long getCurrentUserId() {
        return GameSupport.getCurrentUserId(requestContext);
    }

    // ==================== 多语言（i18n） ====================

    /**
     * 下载当前请求语言下的词条包。
     *
     * <p>客户端应在登录后取一次并缓存，之后用 {@code I18n.t(key)} 本地化时
     * 直接查缓存；缓存 miss 时再调本接口补一次。</p>
     *
     * <p>语言按 {@link LanguageRequestFilter} 的协商顺序决定：
     * {@code ?lang=} &gt; {@code X-Lang} &gt; {@code Accept-Language} &gt; 服务端默认。
     * 本接口<b>刻意不需要鉴权</b>——登录页的"忘记密码"等错误提示也要本地化，
     * 而此时还没有 token。</p>
     */
    @GET
    @Path("/i18n/bundle")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getI18nBundle() {
        GameLanguage lang = I18nContext.current();
        JsonObject data = new JsonObject();
        data.addProperty("language", lang.code());
        data.addProperty("displayName", lang.displayName());
        // 词条表直接塞进 data.messages，客户端无需二次解析
        data.add("messages", gson.toJsonTree(I18nManager.bundle(lang)).getAsJsonObject());
        data.addProperty("count", I18nManager.bundle(lang).size());
        // 回退链上还缺多少 key：给客户端一个"翻译完整度"的信号，
        // 便于它在 UI 上提示"部分内容尚未翻译"
        data.addProperty("missingCount", I18nManager.missingKeys(lang).size());
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    /** 服务端支持的语言列表（含各语言词条数与完整度），供语言选择器使用。免鉴权。 */
    @GET
    @Path("/i18n/languages")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getI18nLanguages() {
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Object> e : I18nManager.status().entrySet()) {
            arr.add(gson.toJsonTree(e.getValue()).getAsJsonObject());
        }
        JsonObject data = new JsonObject();
        data.add("languages", arr);
        data.addProperty("default", I18nManager.serverDefault().code());
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    /**
     * 读取当前玩家的界面语言偏好。
     *
     * <p>返回空串表示"未设置"——此时客户端应显示默认语言而不是空白。</p>
     */
    @GET
    @Path("/i18n/lang")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.i18n")
    public Response getPlayerLanguage() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        // 解析后的语言（含回落）与原始值都返回：客户端需要区分"没设过"与"设了个服务端不认识的"
        GameLanguage resolved = LanguageResolver.fromCode(player.getLanguage());
        JsonObject data = new JsonObject();
        data.addProperty("lang", player.getLanguage());
        data.addProperty("resolved", resolved.code());
        data.addProperty("isDefault", resolved.equals(I18nManager.serverDefault()));
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    /**
     * 设置当前玩家的界面语言偏好（持久化）。
     *
     * <p>这是<b>唯一</b>会落库的语言入口。请求头的 {@code X-Lang} / {@code ?lang=}
     * 只影响当次请求——否则一个带旧参数的老客户端就能把用户偏好永久改掉。</p>
     *
     * <p>传入空串表示"恢复默认"（清除偏好）。</p>
     */
    @POST
    @Path("/i18n/lang")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.i18n")
    public Response setPlayerLanguage(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        String requested = null;
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (json.has("lang") && !json.get("lang").isJsonNull()) {
                requested = json.get("lang").getAsString();
            }
        } catch (Exception ignored) {
            // 请求体缺失或不是 JSON：按"恢复默认"处理，不报错
        }

        String stored;
        if (requested == null || requested.isBlank()) {
            stored = "";  // 空串 = 清除偏好
        } else {
            GameLanguage parsed = GameLanguage.parse(requested);
            if (parsed == null) {
                // 明确报错：与"请求头协商失败静默回落"不同，这里用户是显式选择的
                JsonObject data = new JsonObject();
                data.add("supported", gson.toJsonTree(
                        java.util.Arrays.stream(GameLanguage.values())
                                .map(GameLanguage::code)
                                .toArray(String[]::new)).getAsJsonArray());
                return Response.ok(GameMessage.restError(
                        GameErrorCode.PARAM_INVALID.getCode(),
                        "error.i18n.unsupported_language", "不支持的语言").toString()).build();
            }
            stored = parsed.code();
        }

        playerService.setLanguage(player.getId(), stored);
        JsonObject data = new JsonObject();
        data.addProperty("lang", stored);
        data.addProperty("resolved", LanguageResolver.fromCode(stored).code());
        return Response.ok(GameMessage.restOk(I18n.t("common.saved"), data).toString()).build();
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getServerStatus() {
        long uptimeMillis = System.currentTimeMillis() - Main.serverStartTime;
        long uptimeSeconds = uptimeMillis / 1000;

        int onlineCount = Main.gameWebSocketApp != null ? Main.gameWebSocketApp.getOnlineCount() : 0;
        int totalPlayers = playerService.getPlayerCount();
        int totalUsers = playerService.getUserCount();

        JsonObject data = new JsonObject();
        data.addProperty("uptimeSeconds", uptimeSeconds);
        data.addProperty("uptimeFormatted", formatUptime(uptimeSeconds));
        data.addProperty("onlinePlayers", onlineCount);
        data.addProperty("totalPlayers", totalPlayers);
        data.addProperty("totalUsers", totalUsers);
        data.addProperty("serverVersion", "1.0.0");

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        data.addProperty("usedMemoryMB", usedMemory);
        data.addProperty("maxMemoryMB", maxMemory);

        return Response.ok(GameMessage.restOk(I18n.t("admin.status.healthy"), data).toString()).build();
    }

    private String formatUptime(long totalSeconds) {
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天 ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("小时 ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("分 ");
        }
        sb.append(seconds).append("秒");
        return sb.toString();
    }

    @GET
    @Path("/rank")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.rank.view")
    public Response getRanking(@QueryParam("type") @DefaultValue("realm") String type,
                                @QueryParam("limit") @DefaultValue("20") int limit) {
        List<PlayerInfo> players;
        switch (type) {
            case "power":
                players = playerService.getTopByPower(limit);
                break;
            case "wealth":
                players = playerService.getTopByWealth(limit);
                break;
            default:
                players = playerService.getTopByRealm(limit);
                break;
        }

        JsonArray arr = new JsonArray();
        int rank = 1;
        for (PlayerInfo p : players) {
            JsonObject o = new JsonObject();
            o.addProperty("rank", rank++);
            o.addProperty("playerId", p.getId());
            o.addProperty("name", p.getName());
            o.addProperty("realm", p.getRealm());
            o.addProperty("realmName", p.getRealmName());
            o.addProperty("level", p.getLevel());
            o.addProperty("attack", p.getAttack());
            o.addProperty("defense", p.getDefense());
            o.addProperty("gold", p.getGold());
            long spiritStones = itemService.getSpiritStoneCount(p.getId());
            o.addProperty("spiritStones", spiritStones);
            o.addProperty("totalWealth", p.getGold() + spiritStones);
            arr.add(o);
        }

        JsonObject data = new JsonObject();
        data.add("ranking", arr);
        data.addProperty("type", type);

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @GET
    @Path("/map")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getMapSurroundings() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        PlayerSurroundings result = mapService.getPlayerSurroundings(playerId);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @POST
    @Path("/map/travel/{locationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response travelTo(@PathParam("locationId") long locationId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        TravelResult result = mapService.travel(playerId, locationId);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @GET
    @Path("/map/locations")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getMapLocations() {
        mapService.ensureInitialized();
        List<MapLocation> locations = mapService.getAllLocations();
        JsonArray arr = new JsonArray();
        for (MapLocation loc : locations) {
            JsonObject o = new JsonObject();
            o.addProperty("id", loc.getId());
            o.addProperty("name", loc.getName());
            o.addProperty("description", loc.getDescription());
            o.addProperty("region", loc.getRegion());
            o.addProperty("minRealm", loc.getMinRealm());
            o.addProperty("safeZone", loc.isSafeZone());
            o.addProperty("cultivationBonus", loc.getCultivationBonus());
            o.addProperty("explorationBonus", loc.getExplorationBonus());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("locations", arr);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }
}
