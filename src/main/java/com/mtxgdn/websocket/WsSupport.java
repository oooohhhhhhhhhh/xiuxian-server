package com.mtxgdn.websocket;

import com.google.gson.Gson;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.service.ChatService;
import com.mtxgdn.game.service.CraftingService;
import com.mtxgdn.game.service.EnhanceService;
import com.mtxgdn.game.service.ExplorationService;
import com.mtxgdn.game.service.FriendService;
import com.mtxgdn.game.service.HeartDemonService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.MapService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.RealmService;
import com.mtxgdn.game.service.SecretRealmService;
import com.mtxgdn.game.service.SectService;
import com.mtxgdn.game.service.TechniqueService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.PlayerActionLogger;
import org.glassfish.grizzly.websockets.WebSocket;

/**
 * WebSocket 各动作处理器共用的一点点基础设施。
 *
 * <p><b>为什么有它</b>：{@code GameWebSocketApp} 原来把「连接生命周期 + 鉴权 + 55 个 action
 * 的处理」全塞在一个 2000 行的类里，所有处理函数共享同一批静态字段（Gson 实例、
 * 行为日志、十来个游戏服务单例）。按"action 域 + 依赖集合"把这些处理函数拆到
 * {@code Ws*Handler} 之后，那批<b>跨域</b>用到的东西（Gson、日志、服务单例、
 * 权限判断、排行榜条数夹取）就无处安放了 —— 放到这里，各处理器用
 * {@code import static} 引入即可。</p>
 *
 * <p><b>协议不变</b>：这里只做"搬家"。字段初始化表达式、{@link #clampLimit} 与
 * {@link #checkWsPermission} 的方法体都是从 {@code GameWebSocketApp} 逐字搬过来的，
 * 唯一改动是可见性（{@code private} → 包级）与位置。因此错误码、错误文案、
 * 权限码字符串、排行榜上限都和拆分前完全一致。</p>
 *
 * <p><b>服务单例的来源也必须逐字照搬</b>：拆分前 {@code GameWebSocketApp} 里
 * {@code playerService} / {@code itemService} / {@code chatService} 等走
 * {@link ServiceRegistry}，而 {@code realmService} / {@code secretRealmService} /
 * {@code explorationService} 是就地构造（它们只包一层 {@code playerService}，无实例状态）。
 * 这里原样保留：改成一律走注册表会改变 WebSocket 通道看到的服务实例，
 * 那是行为改动，不属于本次纯结构重构。</p>
 *
 * <p>本类没有状态、不可实例化，只被同包的 {@code GameWebSocketApp} 与
 * {@code Ws*Handler} 使用，因此是包级可见的。</p>
 */
final class WsSupport {

    /** 全 WebSocket 侧共用的 Gson 实例（Gson 无状态，可安全共享）。 */
    static final Gson gson = new Gson();

    /** 玩家行为日志单例（聊天/修炼/突破/战斗等动作都会写一条）。 */
    static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();

    /** 玩家服务单例（走注册表，保证与 REST / QQ / Minecraft 通道看到同一份实例）。 */
    static final PlayerService playerService = ServiceRegistry.getPlayerService();

    /** 境界/突破服务：拆分前就是就地构造，这里保持原样。 */
    static final RealmService realmService = new RealmService(playerService);

    /** 物品服务单例。 */
    static final ItemService itemService = ServiceRegistry.getItemService();

    /** 秘境服务：拆分前就是就地构造，这里保持原样。 */
    static final SecretRealmService secretRealmService = new SecretRealmService(playerService);

    /** 探索服务：拆分前就是就地构造，这里保持原样。 */
    static final ExplorationService explorationService = new ExplorationService(playerService);

    /** 心魔服务单例。 */
    static final HeartDemonService heartDemonService = ServiceRegistry.getHeartDemonService();

    /** 功法服务单例。 */
    static final TechniqueService techniqueService = ServiceRegistry.getTechniqueService();

    /** 炼制服务单例。 */
    static final CraftingService craftingService = ServiceRegistry.getCraftingService();

    /** 强化服务单例。 */
    static final EnhanceService enhanceService = ServiceRegistry.getEnhanceService();

    /** 聊天服务单例。 */
    static final ChatService chatService = ServiceRegistry.getChatService();

    /** 好友服务单例。 */
    static final FriendService friendService = ServiceRegistry.getFriendService();

    /** 宗门服务单例。 */
    static final SectService sectService = ServiceRegistry.getSectService();

    /** 地图服务单例（拆分前用的是全限定名调用，这里逐字保留）。 */
    static final MapService mapService = com.mtxgdn.common.service.ServiceRegistry.getMapService();

    /** 排行榜默认返回条数。 */
    static final int DEFAULT_RANKING_LIMIT = 20;

    /** 排行榜返回条数上限：再多也没有展示意义，只会被用来打数据库。 */
    static final int MAX_RANKING_LIMIT = 100;

    private WsSupport() {
    }

    /**
     * 把客户端传来的条数夹进 [{@code 1}, {@code max}]。
     *
     * <p>这些数字会直接进 SQL 的 {@code LIMIT ?}：传 2,000,000,000 会让数据库把整张表
     * 查出来再逐个构造对象；传负数在 SQLite 里等价于"不限条数"，同样是全表。
     * 因此边界必须在入口处夹死，而不是指望调用方自觉。</p>
     *
     * <p>方法体与拆分前逐字相同；搬到 {@code WsSupport} 只是为了让
     * {@code WsSocialHandler} 与{@code GameWebSocketApp} 都能用。</p>
     */
    static int clampLimit(int value, int defaultValue, int max) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, max);
    }

    /**
     * 权限检查：不通过时回包并返回 {@code false}，调用方 {@code break} 掉该 action。
     *
     * <p><b>这是 WebSocket 唯一的鉴权闸门</b>，每条需要权限的 action 分支都要先过它
     * （世界聊天 {@code chat} 曾经漏过这一句，被禁用 {@code game.chat.world} 的角色
     * 照样能往世界频道发言）。拆分后它仍然被 {@code GameWebSocketApp} 的分发
     * {@code switch} 逐条显式调用 —— 检查没有下移到各处理器里，因为分散到 11 个类之后
     * "哪个 action 有检查"就不再是一眼可见的了。</p>
     *
     * <p>方法体与拆分前逐字相同（同样的错误码、同样的文案格式）。</p>
     */
    static boolean checkWsPermission(WebSocket socket, long msgId, Long userId, String permission) {
        if (!PermissionService.hasPermission(userId, permission)) {
            GameMessage err = GameMessage.error(msgId, "error", GameErrorCode.PARAM_INVALID.getCode(), "权限不足: " + permission);
            socket.send(err.toJson());
            return false;
        }
        return true;
    }
}
