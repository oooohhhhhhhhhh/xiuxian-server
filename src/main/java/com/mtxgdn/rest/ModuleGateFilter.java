package com.mtxgdn.rest;

import com.mtxgdn.plugin.BuiltInPluginRegistry;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * 内置玩法模块的 HTTP 接口门控。
 * <p>
 * 内置插件被 WebUI「插件管理」页停用后，其 REST 接口（玩家侧的显式资源类
 * 与 UnifiedRestResource 派发的 Command 路由，均在 /game/ 路径空间下）仍可被调用。
 * 本过滤器按路径首段把请求映射到所属内置模块，模块已停用时直接返回 403。
 * <p>
 * 未映射到任何内置模块前缀的路径（/game/player/data 数据备份、/admin/ 管理接口、
 * /install/ 安装向导等）不受影响。
 */
@Provider
@Priority(Priorities.AUTHORIZATION - 50)
public class ModuleGateFilter implements ContainerRequestFilter {

    /** /game/ 下第一个路径段 → 所属内置插件名（config/plugins.yml 的 plugins.*）。 */
    private static final Map<String, String> MODULE_BY_FIRST_SEGMENT = Map.ofEntries(
            // 已注册的基础玩法模块
            Map.entry("farm", "farm"),
            Map.entry("cave", "cave"),
            Map.entry("formation", "formation"),
            Map.entry("sect", "sect"),
            Map.entry("market", "trade"),
            Map.entry("secret_realm", "secret_realm"),
            Map.entry("exploration", "exploration"),
            // 聊天/好友/团队/地图/称号
            Map.entry("chat", "chat"),
            Map.entry("friend", "friend"),
            Map.entry("team", "team"),
            Map.entry("raid", "team"),
            Map.entry("map", "map"),
            Map.entry("title", "title"),
            // 功法/合成/增益/能量
            Map.entry("technique", "technique"),
            Map.entry("techniques", "technique"),
            Map.entry("crafting", "crafting"),
            Map.entry("buff", "buff"),
            Map.entry("energy", "energy"),
            // 经济/排行/搜索
            Map.entry("economy", "economy"),
            Map.entry("rank", "rank"),
            Map.entry("players", "search"),
            // 修炼/技能/战斗
            Map.entry("realm", "cultivation"),
            Map.entry("cultivate", "cultivation"),
            Map.entry("daily", "cultivation"),
            Map.entry("season", "cultivation"),
            Map.entry("skill", "skill"),
            Map.entry("skills", "skill"),
            Map.entry("pvp", "pvp"),
            // 背包/装备/角色
            Map.entry("item", "inventory"),
            Map.entry("inventory", "inventory"),
            Map.entry("equipment", "inventory"),
            Map.entry("heal", "player"),
            Map.entry("status", "player"),
            Map.entry("spiritual_roots", "player"),
            Map.entry("player", "player")
    );

    private static final String DISABLED_BODY =
            "{\"code\":403,\"message\":\"该玩法系统已停用，暂时无法使用\"}";

    private static final String EXPERIMENTAL_DISABLED_BODY =
            "{\"code\":403,\"message\":\"该实验性功能未开放，暂时无法使用\"}";

    /**
     * 供测试断言用：/game/ 首段 → 内置模块名的映射。
     *
     * <p>这张表是"手写的第二份真相"：新增一个 /game/xxx 接口时如果忘了在这里登记，
     * 该接口就<b>永远不会</b>被模块开关拦住 —— 停用玩法后它照样能用，而且没有任何报错。
     * 所以需要一个测试把"表里的模块名必须真实存在"和
     * "玩家侧每个 /game/xxx 必须被登记"这两件事钉死。</p>
     *
     * <p>这张表按 <b>URL 一级分段</b>匹配，与资源类怎么划分无关：玩家接口从
     * {@code GameResource} 拆成多个 {@code Game*Resource} 之后，本表一个字都不用改。</p>
     */
    static Map<String, String> moduleByFirstSegment() {
        return MODULE_BY_FIRST_SEGMENT;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path == null) return;

        String[] segments = path.split("/");
        if (segments.length < 2 || !"game".equals(segments[0])) {
            return; // 非 /game/ 路径（admin/、install/ 等）不参与模块门控
        }

        // 管理数据备份/恢复接口（/game/player/data/*）不参与「角色系统」门控
        if (segments.length >= 3 && "player".equals(segments[1]) && "data".equals(segments[2])) {
            return;
        }

        // /game/sect/war/* 属于实验性插件「宗门战」，归它自己的开关门控
        if (segments.length >= 3 && "sect".equals(segments[1]) && "war".equals(segments[2])) {
            if (!BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("experimental_sect_war")) {
                requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(EXPERIMENTAL_DISABLED_BODY)
                        .build());
            }
            return;
        }

        String module = MODULE_BY_FIRST_SEGMENT.get(segments[1]);
        if (module == null) {
            return; // 非内置模块路径
        }

        if (!BuiltInPluginRegistry.getInstance().isEnabledOrUnknown(module)) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(DISABLED_BODY)
                    .build());
        }
    }
}