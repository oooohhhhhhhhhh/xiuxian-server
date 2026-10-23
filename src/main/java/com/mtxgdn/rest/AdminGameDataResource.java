package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.title.TitleRegistry;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.mtxgdn.rest.AdminSupport.gson;

/**
 * 管理台 · 游戏内容与运行配置接口（物品/境界/灵根/称号目录、新人奖励、
 * 多语言词条、OAuth2 配置）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：这些端点全都是
 * <b>只读或整份覆写</b>的配置类数据 —— 物品表、境界表、称号表来自注册表与
 * {@code GameConfigLoader}，新人奖励/多语言/OAuth 都是"读一份配置、存一份配置"，
 * 与玩家数据、库表结构、进程运维都不共享状态，因此按"路径前缀 + 依赖集合"
 * 整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码、返回结构全部保持原样，
 * 前端 {@code /api/admin/items|realms|spiritual-roots|titles|newbie-reward|i18n|oauth}
 * 不受影响。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /i18n/status} 拼出来的地址与拆分前
 * 逐字一致（Jersey 的路径拼接规则）。</p>
 *
 * <p>注意 {@code /titles}（{@code admin.status} 的只读目录）与 {@code /titles/catalog}
 * （{@code admin.titles.manage} 的管理目录）是两个不同的既有端点，权限码不同、
 * 返回字段也不同 —— 拆分时原样保留，没有合并。</p>
 */
@Path("/admin")
public class AdminGameDataResource {

    @GET
    @Path("/spiritual-roots")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getSpiritualRoots() {
        JsonArray arr = new JsonArray();
        for (com.mtxgdn.game.entity.SpiritualRoot root : com.mtxgdn.game.entity.SpiritualRoot.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", root.name());
            obj.addProperty("displayName", root.getDisplayName());
            obj.addProperty("tier", root.getTier().getDisplayName());
            obj.addProperty("tierKey", root.getTier().name());
            obj.addProperty("description", root.getDescription());
            obj.addProperty("attackBonus", root.getAttackBonus());
            obj.addProperty("hpBonus", root.getHpBonus());
            obj.addProperty("mpBonus", root.getMpBonus());
            obj.addProperty("defenseBonus", root.getDefenseBonus());
            obj.addProperty("speedBonus", root.getSpeedBonus());
            obj.addProperty("spiritBonus", root.getSpiritBonus());
            arr.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("roots", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/items")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getAllItems() {
        JsonArray arr = new JsonArray();
        for (Item item : ItemRegistry.getAll()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("key", item.getFullKey());
            obj.addProperty("name", item.getName());
            obj.addProperty("description", item.getDescription());
            obj.addProperty("type", item.getType().name());
            obj.addProperty("rarity", item.getRarity().name());
            arr.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("items", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/realms")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getRealms() {
        JsonArray arr = new JsonArray();
        for (RealmConfig config : GameConfigLoader.getRealmConfigs()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", config.getId());
            obj.addProperty("subRealm", config.getSubRealm());
            obj.addProperty("name", config.getName());
            obj.addProperty("description", config.getDescription());
            obj.addProperty("requiredExp", config.getRequiredExp());
            obj.addProperty("requiredSpiritStones", config.getRequiredSpiritStones());
            arr.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("realms", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/titles")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getTitles() {
        TitleRegistry.init();
        JsonArray arr = new JsonArray();
        for (Title title : TitleRegistry.getAll()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("key", title.getKey());
            obj.addProperty("name", title.getName());
            obj.addProperty("description", title.getDescription());
            obj.addProperty("rarity", title.getRarity().name());
            obj.addProperty("requiredRealm", title.getRequiredRealm());
            obj.addProperty("attackBonus", title.getAttackBonus());
            obj.addProperty("defenseBonus", title.getDefenseBonus());
            obj.addProperty("speedBonus", title.getSpeedBonus());
            obj.addProperty("spiritBonus", title.getSpiritBonus());
            obj.addProperty("hpBonus", title.getHpBonus());
            obj.addProperty("mpBonus", title.getMpBonus());
            obj.addProperty("experienceBonus", title.getExperienceBonus());
            obj.addProperty("dropRateBonus", title.getDropRateBonus());
            obj.addProperty("cultivationSpeedBonus", title.getCultivationSpeedBonus());
            obj.addProperty("damageBonus", title.getDamageBonus());
            obj.addProperty("damageReduction", title.getDamageReduction());
            arr.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("titles", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/titles/catalog")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.titles.manage")
    public Response getTitleCatalog() {
        TitleRegistry.init();
        JsonArray arr = new JsonArray();
        for (var t : TitleRegistry.getAll()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", t.getKey());
            o.addProperty("name", t.getName());
            o.addProperty("description", t.getDescription());
            o.addProperty("rarity", t.getRarity().name());
            o.addProperty("rarityLabel", t.getRarityLabel());
            o.addProperty("requiredRealm", t.getRequiredRealm());
            o.addProperty("attackBonus", t.getAttackBonus());
            o.addProperty("hpBonus", t.getHpBonus());
            o.addProperty("mpBonus", t.getMpBonus());
            o.addProperty("expBonus", t.getExpBonus());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("titles", arr);
        return Response.ok(data.toString()).build();
    }

    // ========== 新人奖励配置 API ==========

    @GET
    @Path("/newbie-reward/config")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.config.manage")
    public Response getNewbieRewardConfig() {
        com.mtxgdn.game.config.NewbieRewardConfig config = com.mtxgdn.game.config.GameConfigLoader.getNewbieRewardConfig();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("enabled", config.isEnabled());
        result.addProperty("goldReward", config.getGoldReward());
        result.addProperty("spiritStoneReward", config.getSpiritStoneReward());
        result.addProperty("spiritStoneGrade", config.getSpiritStoneGrade());
        JsonArray itemsArr = new JsonArray();
        if (config.getItems() != null) {
            for (com.mtxgdn.game.config.NewbieRewardConfig.RewardItem item : config.getItems()) {
                JsonObject itemObj = new JsonObject();
                itemObj.addProperty("itemKey", item.getItemKey());
                itemObj.addProperty("quantity", item.getQuantity());
                itemsArr.add(itemObj);
            }
        }
        result.add("items", itemsArr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/newbie-reward/config")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.config.manage")
    public Response updateNewbieRewardConfig(String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            com.mtxgdn.game.config.NewbieRewardConfig config = new com.mtxgdn.game.config.NewbieRewardConfig();

            config.setEnabled(req.has("enabled") ? req.get("enabled").getAsBoolean() : false);
            config.setGoldReward(req.has("goldReward") ? req.get("goldReward").getAsLong() : 0);
            config.setSpiritStoneReward(req.has("spiritStoneReward") ? req.get("spiritStoneReward").getAsLong() : 0);
            config.setSpiritStoneGrade(req.has("spiritStoneGrade") ? req.get("spiritStoneGrade").getAsInt() : 0);

            java.util.List<com.mtxgdn.game.config.NewbieRewardConfig.RewardItem> items = new java.util.ArrayList<>();
            if (req.has("items") && req.get("items").isJsonArray()) {
                JsonArray itemsArr = req.getAsJsonArray("items");
                for (int i = 0; i < itemsArr.size(); i++) {
                    JsonObject itemObj = itemsArr.get(i).getAsJsonObject();
                    String itemKey = itemObj.has("itemKey") ? itemObj.get("itemKey").getAsString() : null;
                    long quantity = itemObj.has("quantity") ? itemObj.get("quantity").getAsLong() : 0;
                    if (itemKey != null && !itemKey.isBlank() && quantity > 0) {
                        items.add(new com.mtxgdn.game.config.NewbieRewardConfig.RewardItem(itemKey, quantity));
                    }
                }
            }
            config.setItems(items);

            com.mtxgdn.game.config.GameConfigLoader.saveNewbieRewardConfig(config);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.newcomer_reward_configuration_saved"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.save_failed", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    // ==================== 多语言（i18n）词条管理 ====================

    /**
     * 各语言的词条数与缺失数，用于管理后台的"翻译完整度"面板。
     *
     * <p>{@code missingCount} 的口径是"zh_cn 有、该语言没有"——zh_cn 是唯一
     * 保证完整的基线语言，其它语言的缺失都以它为参照。</p>
     */
    @GET
    @Path("/i18n/status")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.i18n.manage")
    public Response i18nStatus() {
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Object> e : com.mtxgdn.i18n.I18nManager.status().entrySet()) {
            arr.add(gson.toJsonTree(e.getValue()).getAsJsonObject());
        }
        data.add("languages", arr);
        data.addProperty("default", com.mtxgdn.i18n.I18nManager.serverDefault().code());
        // 回退次数已经按语言放在 languages 各项的 fallbackCount 里，
        // 它是"有多少次取值靠 zh_cn 顶上了"，也就是翻译缺口最直观的指标。
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    /**
     * 导出某语言当前生效的完整词条表（含插件合并与外部覆写的结果）。
     *
     * <p>用途：运维把导出的 JSON 落到 {@code config/lang/<code>.json}，
     * 就形成了一份可持久化的覆写配置——这样在管理后台改的译文不会
     * 因为重启而丢失。</p>
     */
    @POST
    @Path("/i18n/export")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.i18n.manage")
    public Response i18nExport(String body) {
        String requested = null;
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (json.has("lang") && !json.get("lang").isJsonNull()) {
                requested = json.get("lang").getAsString();
            }
        } catch (Exception ignored) {
            // 没传 body 或不是 JSON：导出服务端默认语言
        }

        com.mtxgdn.i18n.GameLanguage lang =
                requested == null || requested.isBlank()
                        ? com.mtxgdn.i18n.I18nManager.serverDefault()
                        : com.mtxgdn.i18n.GameLanguage.parse(requested);
        if (lang == null) {
            return Response.ok(GameMessage.restError(
                    GameErrorCode.PARAM_INVALID.getCode(),
                    "error.i18n.unsupported_language", "不支持的语言").toString()).build();
        }

        JsonObject data = new JsonObject();
        data.addProperty("language", lang.code());
        data.addProperty("count", com.mtxgdn.i18n.I18nManager.bundle(lang).size());
        // 直接给 JSON 文本而不是嵌套对象：运维要的是能原样存盘的文件内容
        data.addProperty("content", com.mtxgdn.i18n.I18nManager.exportJson(lang));
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.export_ok"), data).toString()).build();
    }

    /**
     * 重载全部语言包。
     *
     * <p>会丢掉运行期通过插件合并进来的词条，重新从 classpath 与
     * {@code config/lang/} 读——所以改完外部文件后调它即可生效，
     * 不需要重启服务。插件会在下次加载时重新合并自己的词条。</p>
     */
    @POST
    @Path("/i18n/reload")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.i18n.manage")
    public Response i18nReload() {
        try {
            com.mtxgdn.i18n.I18nManager.reload();
            JsonObject data = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Map.Entry<String, Object> e : com.mtxgdn.i18n.I18nManager.status().entrySet()) {
                arr.add(gson.toJsonTree(e.getValue()).getAsJsonObject());
            }
            data.add("languages", arr);
            return Response.ok(GameMessage.restOk(I18n.t("admin.i18n.reload_ok"), data).toString()).build();
        } catch (Exception e) {
            return Response.ok(GameMessage.restError(
                    GameErrorCode.INTERNAL_ERROR.getCode(),
                    "error.i18n.reload_failed", I18n.t("error.i18n.reload_failed_detail", e.getMessage())).toString()).build();
        }
    }

    // ==================== OAuth2 配置管理 ====================

    @GET
    @Path("/oauth/config")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.oauth.manage")
    public Response getOAuthConfig() {
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        JsonObject data = new JsonObject();
        for (Map.Entry<String, Object> entry : com.mtxgdn.service.OAuthService.getConfig(false).entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Boolean b) {
                data.addProperty(entry.getKey(), b);
            } else if (v instanceof Number n) {
                data.addProperty(entry.getKey(), n);
            } else {
                data.addProperty(entry.getKey(), v != null ? v.toString() : "");
            }
        }
        out.add("data", data);
        return Response.ok(gson.toJson(out)).build();
    }

    @POST
    @Path("/oauth/config")
    @Produces("application/json;charset=UTF-8")
    @Consumes("application/json;charset=UTF-8")
    @RequirePermission("admin.oauth.manage")
    public Response updateOAuthConfig(String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            Map<String, Object> update = new LinkedHashMap<>();
            if (req.has("enabled")) update.put("enabled", req.get("enabled").getAsBoolean());
            if (req.has("client_id")) update.put("client_id", req.get("client_id").getAsString());
            if (req.has("client_secret")) update.put("client_secret", req.get("client_secret").getAsString());
            if (req.has("scopes")) update.put("scopes", req.get("scopes").getAsString());
            if (req.has("token_ttl_seconds")) update.put("token_ttl_seconds", req.get("token_ttl_seconds").getAsLong());

            Map<String, Object> result = com.mtxgdn.service.OAuthService.updateConfig(update);
            JsonObject out = new JsonObject();
            out.addProperty("code", 200);
            out.addProperty("message", (String) result.get("message"));
            JsonObject data = new JsonObject();
            for (Map.Entry<String, Object> entry : com.mtxgdn.service.OAuthService.getConfig(false).entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Boolean b) {
                    data.addProperty(entry.getKey(), b);
                } else if (v instanceof Number n) {
                    data.addProperty(entry.getKey(), n);
                } else {
                    data.addProperty(entry.getKey(), v != null ? v.toString() : "");
                }
            }
            out.add("data", data);
            return Response.ok(gson.toJson(out)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.failed_to_update_oauth2_configuration", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }
}
