package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.util.PlayerActionLogger;
import jakarta.ws.rs.WebApplicationException;

/**
 * 玩家侧各资源类共用的一点点基础设施。
 *
 * <p>玩家接口原来只有一个近 3000 行的 {@code GameResource}，里面所有端点共享同一批
 * 静态字段与私有辅助方法。按"路径前缀 + 依赖集合"把它拆成多个资源类之后，
 * 那些<b>跨域</b>用到的东西（Gson 实例、行为日志、玩家/物品服务单例、
 * 玩家名与玩家 id 的查询）就无处安放了 —— 放到这里，各资源类用
 * {@code import static} 引入即可。</p>
 *
 * <p>抽出来时只改了可见性（{@code private} → 包级）与位置，逻辑一行未动：
 * 这是纯结构重构，不夹带任何行为改动。</p>
 *
 * <p>只被单个域用到的辅助方法（坊市的剩余时间格式化、副本的境界名、
 * 称号的 JSON 组装等）跟着那个域搬到对应资源类里，不放在这里。需要请求上下文的
 * 实例辅助方法（取当前登录用户、动作限流）无法做成静态方法，因此在每个用到它们的
 * 资源类里各留一份，内容与拆分前逐字相同。</p>
 *
 * <p>本类不是 JAX-RS 资源（没有类级 {@code @Path}），不会被
 * {@code new ResourceConfig().packages("com.mtxgdn.rest")} 注册成端点。</p>
 */
final class GameSupport {

    /** 全玩家侧共用的 Gson 实例（Gson 无状态，可安全共享）。 */
    static final Gson gson = new Gson();

    /** 玩家行为日志单例（修炼/突破/战斗等动作都会写一条）。 */
    static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();

    /**
     * 玩家服务单例。
     *
     * <p>必须走 {@link ServiceRegistry}：直接构造会让 REST 通道拿到与其它通道
     * （WebSocket / QQ / Minecraft）互不可见的另一份实例。</p>
     */
    static final PlayerService playerService = ServiceRegistry.getPlayerService();

    /** 物品服务单例（同上，走注册表）。 */
    static final ItemService itemService = ServiceRegistry.getItemService();

    private GameSupport() {
    }

    /** 取玩家名用于回显/日志；查不到时返回占位文本（拆分前行为一致）。 */
    static String getPlayerName(Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        return player != null ? player.getName() : "未知玩家";
    }

    /**
     * 用户 id → 玩家 id；没有角色时抛 400。
     *
     * <p>拆出来后由多个域共用（背包/坊市/宗门/地图/种田等），因此不能再留在某一个
     * 资源类里，否则其它类要么重复实现、要么跨类调用私有方法。</p>
     */
    static int getPlayerIdByUserId(long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            throw new WebApplicationException("请先创建角色", 400);
        }
        return (int) player.getId();
    }
}
