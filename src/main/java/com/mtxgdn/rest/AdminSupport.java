package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.util.GameLogger;

/**
 * 管理台各资源类共用的一点点基础设施。
 *
 * <p>管理台原来只有一个 4000+ 行的 {@code AdminResource}，里面所有端点共享同一批
 * 静态字段与私有辅助方法。按"路径前缀 + 依赖集合"把它拆成多个资源类之后，
 * 那些<b>跨域</b>用到的东西（Gson 实例、日志分类、玩家/物品服务单例、
 * 地图缓存热重载）就无处安放了 —— 放到这里，各资源类用
 * {@code import static} 引入即可。</p>
 *
 * <p>抽出来时只改了可见性（{@code private} → 包级）与位置，逻辑一行未动：
 * 这是纯结构重构，不夹带任何行为改动。</p>
 *
 * <p>本类不是 JAX-RS 资源（没有类级 {@code @Path}），不会被
 * {@code new ResourceConfig().packages("com.mtxgdn.rest")} 注册成端点。</p>
 */
final class AdminSupport {

    /** 全管理台共用的 Gson 实例（Gson 无状态，可安全共享）。 */
    static final Gson gson = new Gson();

    /** 管理台共用的日志分类。 */
    static final GameLogger LOG = GameLogger.getLogger(AdminSupport.class);

    /**
     * 玩家服务单例。
     *
     * <p>必须走 {@link ServiceRegistry}：直接构造会让管理台拿到与其它通道
     * （REST / WebSocket / QQ / Minecraft）互不可见的另一份实例。</p>
     */
    static final PlayerService playerService = ServiceRegistry.getPlayerService();

    /** 物品服务单例（同上，走注册表）。 */
    static final ItemService itemService = ServiceRegistry.getItemService();

    private AdminSupport() {
    }

    /**
     * 字段存在且不是 JSON null（区分"没传"与"传了 null"）。
     *
     * <p>地图域的两个资源类（地点/连接 与 事件偏置）都要用，因此放在这里共享。</p>
     */
    static boolean hasJsonValue(JsonObject req, String key) {
        return req.has(key) && !req.get(key).isJsonNull();
    }

    /**
     * 通用表管理改动地图表后热重载地图缓存。
     *
     * <p>此前后台改完 map_locations 必须重启进程才生效（MapService 只缓存内存，
     * 没有失效入口），管理员改完看不到变化会以为改错了。</p>
     */
    static void reloadMapCacheIfNeeded(String tableName) {
        if (tableName == null) return;
        String table = tableName.toLowerCase();
        if (!table.equals("map_locations")
                && !table.equals("map_connections")
                && !table.equals("map_location_event_bias")
                && !table.equals("map_wilderness_resources")) {
            return;
        }
        try {
            ServiceRegistry.getMapService().reload();
        } catch (Exception e) {
            LOG.warn("地图表 " + table + " 已改动，但地图缓存重载失败: " + e.getMessage());
        }
    }
}
