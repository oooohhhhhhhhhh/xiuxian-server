package com.mtxgdn.websocket;

import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.OfflineRewardService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.util.JwtUtil;
import com.mtxgdn.util.RateLimiter;
import com.mtxgdn.util.TokenBlacklist;
import org.glassfish.grizzly.websockets.DataFrame;
import org.glassfish.grizzly.websockets.WebSocket;
import org.glassfish.grizzly.websockets.WebSocketApplication;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.mtxgdn.websocket.WsSupport.actionLog;
import static com.mtxgdn.websocket.WsSupport.checkWsPermission;
import static com.mtxgdn.websocket.WsSupport.playerService;

/**
 * 游戏 WebSocket 入口：连接生命周期 + 鉴权 + 动作分发。
 *
 * <p><b>这次拆分做了什么</b>：本类原来约 2070 行，里面既有连接管理（会话表、空闲巡检、
 * 鉴权、优雅关闭），又有 55 个 action 的处理函数。现在按"action 域 + 依赖集合"把
 * 处理函数整块搬到 11 个 {@code Ws*Handler} 类（聊天 / 角色 / 修炼 / 物品 / 功法 /
 * 炼制 / 秘境探索 / 社交 / 宗门 / 地图 / 连接与全局信息），本类只保留：</p>
 * <ul>
 *   <li>会话表与空闲巡检（{@code sessionUsers} / {@code userSessions} /
 *       {@code authenticated} / {@code lastActivity} + {@code ws-idle-sweeper}）；</li>
 *   <li>帧长校验（{@link #MAX_MESSAGE_CHARS}）与反序列化；</li>
 *   <li>鉴权（{@link #handleAuth}）与上下线广播；</li>
 *   <li>动作分发的 {@code switch}（{@link #handleGameMessage}）—— 每个分支只做
 *       "权限检查 + 委托给处理器"，处理逻辑一律不在本类。</li>
 * </ul>
 *
 * <p><b>协议一个字节都没变</b>：55 个 action 的名字、请求字段、响应 type/字段、
 * 错误码与文案、消息顺序语义全部逐字保留。分发 {@code switch} 的分支顺序、
 * 以及 switch 之前的"限流 → 内置模块门控"两道前置检查顺序也原样保留。
 * 处理器里的方法体是从本类逐字搬过去的，只改了可见性
 * （{@code private} → 包级，因为分发入口在本类）。</p>
 *
 * <p><b>三条必须守住的不变量（拆分时特意没有动）</b>：</p>
 * <ol>
 *   <li><b>帧长校验在反序列化之前</b>：{@link #onMessage} 开头先刷活跃时间，
 *       再用 {@link #MAX_MESSAGE_CHARS} 拦超长帧，之后才 {@code GameMessage.fromJson}。
 *       超长帧走"错误码 {@code PARAM_INVALID} + close(4009)"，绝不会进入 Gson 或业务逻辑。
 *       这条顺序由 {@code WsActionDispatchTest} 从行为与源码两侧钉住。</li>
 *   <li><b>空闲连接巡检</b>：{@code lastActivity} 在 {@code onConnect} 登记、
 *       在 {@code onMessage} 开头刷新、在 {@code onClose} 清理；
 *       {@code ws-idle-sweeper} 仍是懒启动的 daemon 线程，超时后即使
 *       {@code close()} 没触发 {@code onClose} 也会主动清本地状态。</li>
 *   <li><b>权限检查一条不少</b>：53 个需要权限的 action 仍在分发 {@code switch} 里
 *       逐条显式调用 {@link WsSupport#checkWsPermission}（世界聊天 {@code chat} 的
 *       {@code game.chat.world} 也在其中 —— 它曾经是唯一漏检查的聊天动作）。
 *       检查<b>没有</b>下移到各处理器里：分散到 11 个类之后，
 *       "哪个 action 有权限门"就不再是一眼可见的了。</li>
 * </ol>
 *
 * <p><b>跨 action 状态与执行顺序</b>：本类的会话表是唯一的跨 action 共享状态
 * （广播、私聊、好友通知都靠它），处理器通过 {@link WsContext} 拿广播/单发能力，
 * 不直接碰会话表。分发 switch 本身无状态、各分支互不影响，
 * 因此拆成委托调用不改变任何执行顺序语义；唯一需要保持顺序的是
 * {@code handleGameMessage} 里"限流 → 模块门控 → 分发"这三步，已原样保留。</p>
 */
public class GameWebSocketApp extends WebSocketApplication implements WsContext {

    private static final com.mtxgdn.util.GameLogger LOG =
            com.mtxgdn.util.GameLogger.getLogger(GameWebSocketApp.class);

    // ==================== 各 action 域处理器（依赖由 WsSupport 提供，上下文为 this）====================

    private final WsChatHandler chatHandler = new WsChatHandler(this);
    private final WsPlayerHandler playerHandler = new WsPlayerHandler(this);
    private final WsCultivationHandler cultivationHandler = new WsCultivationHandler(this);
    private final WsInventoryHandler inventoryHandler = new WsInventoryHandler(this);
    private final WsTechniqueHandler techniqueHandler = new WsTechniqueHandler(this);
    private final WsCraftingHandler craftingHandler = new WsCraftingHandler(this);
    private final WsSecretRealmHandler secretRealmHandler = new WsSecretRealmHandler(this);
    private final WsSocialHandler socialHandler = new WsSocialHandler(this);
    private final WsSectHandler sectHandler = new WsSectHandler(this);
    private final WsMapHandler mapHandler = new WsMapHandler(this);
    private final WsSystemHandler systemHandler = new WsSystemHandler(this);

    private final Map<WebSocket, Long> sessionUsers = new ConcurrentHashMap<>();
    private final Map<Long, WebSocket> userSessions = new ConcurrentHashMap<>();
    private final Map<WebSocket, Boolean> authenticated = new ConcurrentHashMap<>();

    /**
     * 每条连接最后一次收到消息的时间戳（毫秒）。
     *
     * <p><b>为什么需要</b>：{@code network.read_timeout_ms} 只对「请求事务」生效，
     * 而 WebSocket 一旦升级成功就没有事务了。于是当客户端网络静默断开
     * （手机切网、笔记本合盖、代理超时）时，服务端<b>永远收不到 close 帧</b>：
     * 连接对象留在 {@link #sessionUsers} / {@link #userSessions} 里，玩家表现为
     * "一直在线"，广播/私聊都往一个死连接发，内存与句柄只增不减。</p>
     *
     * <p>应用层有 {@code heartbeat} 动作，但那是<b>客户端驱动</b>的（服务端只回 pong）——
     * 客户端不发或发不出去时，服务端依然察觉不到。所以必须有服务端侧的空闲判定。</p>
     *
     * <p>这条表的维护（登记/刷新/清理）在拆分中<b>一行没动</b>：它仍在
     * {@code onConnect} / {@code onMessage} 开头 / {@code onClose} 里，
     * 处理函数搬到 {@code Ws*Handler} 之后也不会绕过它 —— 因为所有入站消息
     * 都必须先经过 {@link #onMessage}。</p>
     */
    private final Map<WebSocket, Long> lastActivity = new ConcurrentHashMap<>();

    /** 空闲多久算死连接（{@code network.ws_idle_timeout_ms}，默认 5 分钟，<=0 关闭该功能）。 */
    private static final long WS_IDLE_TIMEOUT_MS =
            Math.max(0, com.mtxgdn.util.AppConfig.getInt("network.ws_idle_timeout_ms", 300_000));

    /** 巡检间隔（秒）：取空闲超时的 1/5，最长 60 秒，最短 10 秒。 */
    private static final long WS_IDLE_SWEEP_SECONDS = WS_IDLE_TIMEOUT_MS <= 0 ? 0
            : Math.min(60, Math.max(10, WS_IDLE_TIMEOUT_MS / 5000));

    private static final java.util.concurrent.ScheduledExecutorService WS_IDLE_SWEEPER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-idle-sweeper");
                t.setDaemon(true);
                return t;
            });

    private static volatile boolean idleSweeperStarted;

    @Override
    public void onConnect(WebSocket socket) {
        authenticated.put(socket, false);
        lastActivity.put(socket, System.currentTimeMillis());
        startIdleSweeperOnce();
    }

    /**
     * 首次真正有连接时才启动巡检线程。
     *
     * <p>不在静态初始化里启动，是为了让「没有 WebSocket 连接的进程」
     * （例如单元测试、纯 REST 部署）完全不产生额外线程。</p>
     */
    private void startIdleSweeperOnce() {
        if (idleSweeperStarted || WS_IDLE_TIMEOUT_MS <= 0) {
            return;
        }
        synchronized (GameWebSocketApp.class) {
            if (idleSweeperStarted) {
                return;
            }
            idleSweeperStarted = true;
            WS_IDLE_SWEEPER.scheduleAtFixedRate(this::closeIdleSockets,
                    WS_IDLE_SWEEP_SECONDS, WS_IDLE_SWEEP_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            LOG.info("WebSocket 空闲巡检已启动：超过 " + (WS_IDLE_TIMEOUT_MS / 1000)
                    + " 秒没有任何消息的连接会被断开（network.ws_idle_timeout_ms）");
        }
    }

    /**
     * 断开空闲超时的连接。
     *
     * <p>即使 {@code close()} 没有触发 {@link #onClose}（半开连接正是如此），
     * 也要把本地状态清干净 —— 否则"玩家一直在线"的问题依然存在。</p>
     */
    void closeIdleSockets() {
        long now = System.currentTimeMillis();
        for (Map.Entry<WebSocket, Long> entry : lastActivity.entrySet()) {
            WebSocket ws = entry.getKey();
            long idleMs = now - entry.getValue();
            if (idleMs <= WS_IDLE_TIMEOUT_MS) {
                continue;
            }
            LOG.info("WebSocket 空闲超时（" + (idleMs / 1000) + " 秒），断开连接");
            try {
                ws.close(4001, "空闲超时");
            } catch (RuntimeException ignored) {
                // 连接已经烂掉了，close 本身也可能失败
            }
            lastActivity.remove(ws, entry.getValue());
            onClose(ws, null);
        }
    }

    /**
     * V1 修复：单帧大小上限（默认 64KB，可由 {@code network.ws_max_message_size} 配置）。
     * <p>
     * Grizzly 4.0.2 的 WebSocket 栈不提供框架级单帧上限（已核实 API），
     * 因此这里在反序列化<b>之前</b>做长度检查，是唯一可靠的拦截点：
     * 保证超大帧不会进入 Gson 解析与业务逻辑，也不会被复制进各 service。
     * <p>
     * 拆分后这段检查仍然留在 {@link #onMessage} 的最前面，<b>没有</b>被挪到
     * 任何处理器里 —— 处理器的入口在解析之后，把校验放过去就等于失效。
     */
    private static final int MAX_MESSAGE_CHARS =
            com.mtxgdn.server.ServerConfigurator.getMaxWebSocketMessageSize();

    @Override
    public void onMessage(WebSocket socket, String text) {
        // 任何入站消息都算"活着"：空闲巡检靠这个时间戳判断死连接
        lastActivity.put(socket, System.currentTimeMillis());
        // V1：超长帧直接断开，不给 Gson/业务代码分配内存的机会
        if (text == null || text.length() > MAX_MESSAGE_CHARS) {
            try {
                GameMessage err = GameMessage.error(0, "error", GameErrorCode.PARAM_INVALID);
                socket.send(err.toJson());
            } catch (Exception ignored) {
                // 连接可能已断开
            }
            try {
                socket.close(4009, "消息过大");
            } catch (Exception ignored) {
            }
            return;
        }

        if (!authenticated.getOrDefault(socket, false)) {
            handleAuth(socket, text);
            return;
        }

        Long userId = sessionUsers.get(socket);
        if (userId == null) {
            return;
        }

        try {
            GameMessage req = GameMessage.fromJson(text);
            handleGameMessage(socket, userId, req);
        } catch (Exception e) {
            GameMessage err = GameMessage.error(0, "error", GameErrorCode.MESSAGE_PARSE_ERROR);
            socket.send(err.toJson());
        }
    }

    private void handleAuth(WebSocket socket, String text) {
        try {
            GameMessage gameMessage = GameMessage.fromJson(text);
            if (!"auth".equals(gameMessage.getType())) {
                GameMessage err = GameMessage.error(0, "error", GameErrorCode.AUTH_NOT_LOGGED_IN);
                socket.send(err.toJson());
                socket.close(4001, GameErrorCode.AUTH_NOT_LOGGED_IN.getMessage());
                return;
            }

            JsonObject data = gameMessage.getData();
            if (data == null || !data.has("token")) {
                GameMessage err = GameMessage.error(0, "error", GameErrorCode.PARAM_MISSING);
                socket.send(err.toJson());
                socket.close(4001, "缺少认证token");
                return;
            }

            String token = data.get("token").getAsString();
            if (!JwtUtil.validateToken(token)) {
                GameMessage err = GameMessage.error(0, "error", GameErrorCode.AUTH_INVALID_TOKEN);
                socket.send(err.toJson());
                socket.close(4001, GameErrorCode.AUTH_INVALID_TOKEN.getMessage());
                return;
            }

            // 登出/被吊销的 token 禁止建立 WebSocket 连接
            if (TokenBlacklist.isBlacklisted(token)) {
                GameMessage err = GameMessage.error(0, "error", GameErrorCode.AUTH_INVALID_TOKEN);
                socket.send(err.toJson());
                socket.close(4001, "Token 已失效，请重新登录");
                return;
            }

            Long userId = JwtUtil.extractUserId(token);
            String username = JwtUtil.extractUsername(token);

            // 同一账号重复登录：关闭旧连接，避免两个连接同时在线导致
            // 消息双发 / userSessions 被覆盖后旧连接变成"幽灵连接"继续收消息。
            WebSocket previous = userSessions.get(userId);
            if (previous != null && previous != socket) {
                sessionUsers.remove(previous);
                authenticated.put(previous, false);
                try {
                    previous.close(4002, "账号在其它位置登录");
                } catch (Exception ignored) {
                    // 旧连接可能已断开
                }
            }

            sessionUsers.put(socket, userId);
            userSessions.put(userId, socket);
            authenticated.put(socket, true);

            actionLog.logConnect(userId, username);

            // 插件事件：玩家登录
            com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                    com.mtxgdn.plugin.event.PluginEvent.builder()
                            .type(com.mtxgdn.plugin.event.PluginEvent.Type.PLAYER_LOGIN)
                            .data("userId", userId)
                            .data("username", username)
                            .build());

            OfflineRewardService.OfflineRewardResult offlineReward =
                    ServiceRegistry.getOfflineRewardService().processOfflineRewards(userId);

            JsonObject welcomeData = new JsonObject();
            welcomeData.addProperty("userId", userId);
            welcomeData.addProperty("username", username);

            if (offlineReward.hasReward) {
                JsonObject offlineJson = new JsonObject();
                offlineJson.addProperty("offlineSeconds", offlineReward.offlineSeconds);
                offlineJson.addProperty("offlineMinutes", offlineReward.offlineMinutes);
                offlineJson.addProperty("offlineHours", offlineReward.offlineHours);
                offlineJson.addProperty("hpRecovered", offlineReward.hpRecovered);
                offlineJson.addProperty("mpRecovered", offlineReward.mpRecovered);
                if (offlineReward.wasCultivating) {
                    offlineJson.addProperty("expGained", offlineReward.expGained);
                    offlineJson.addProperty("rawExpGained", offlineReward.rawExpGained);
                    if (offlineReward.heartDemonTriggered) {
                        offlineJson.addProperty("heartDemonTriggered", true);
                        offlineJson.addProperty("heartDemonSeverity", offlineReward.heartDemonSeverity);
                        offlineJson.addProperty("heartDemonNarrative", offlineReward.heartDemonNarrative);
                        offlineJson.addProperty("heartDemonExpLost", offlineReward.heartDemonExpLost);
                    }
                }
                welcomeData.add("offlineReward", offlineJson);
            }
            welcomeData.addProperty("message", I18n.t("websocket.h39800675"));

            GameMessage welcome = GameMessage.ok(0, "welcome", welcomeData);
            socket.send(welcome.toJson());

            broadcastUserOnline(userId, username);
        } catch (Exception e) {
            GameMessage err = GameMessage.error(0, "error", GameErrorCode.MESSAGE_PARSE_ERROR);
            socket.send(err.toJson());
            socket.close(4001, GameErrorCode.MESSAGE_PARSE_ERROR.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket socket, DataFrame frame) {
        Long userId = sessionUsers.remove(socket);
        authenticated.remove(socket);
        lastActivity.remove(socket);
        if (userId != null) {
            // 条件删除：仅当映射仍指向本连接时才移除，避免同账号多连接时误删新连接
            userSessions.remove(userId, socket);
            broadcastUserOffline(userId);
            PlayerInfo player = playerService.getPlayerByUserId(userId);
            if (player != null) {
                playerService.updateLastOfflineTime(player.getId(), System.currentTimeMillis());
            }
            String name = player != null ? player.getName() : "未知玩家";
            actionLog.logDisconnect(userId, name);
            // 插件事件：玩家登出
            com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                    com.mtxgdn.plugin.event.PluginEvent.builder()
                            .type(com.mtxgdn.plugin.event.PluginEvent.Type.PLAYER_LOGOUT)
                            .data("userId", userId)
                            .data("playerName", name)
                            .build());
        }
    }

    /** WebSocket 动作 → 所属内置插件名（config/plugins.yml 的 plugins.*）；非模块动作返回 null。 */
    private static String wsModuleForAction(String type) {
        if (type == null) return null;
        // 实验性插件：宗门战独立于宗门系统开关
        if ("sect_war".equals(type)) return "experimental_sect_war";
        if (type.startsWith("sect_")) return "sect";
        if (type.startsWith("secret_realm_")) return "secret_realm";
        if ("exploration".equals(type)) return "exploration";
        if (type.startsWith("friend_")) return "friend";
        if (type.startsWith("technique_") || "techniques".equals(type)
                || "my_techniques".equals(type)) return "technique";
        if (type.startsWith("crafting_")) return "crafting";
        if (type.startsWith("map_")) return "map";
        switch (type) {
            case "chat":
            case "chat_private":
            case "chat_history":
                return "chat";
            case "rank":
                return "rank";
            case "heal":
            case "player_info":
                return "player";
            case "cultivate_start":
            case "cultivate_stop":
            case "breakthrough":
            case "season_info":
                return "cultivation";
            case "inventory":
            case "item_use":
            case "item_registry":
            case "equipment_enhance":
                return "inventory";
            default:
                return null;
        }
    }

    /**
     * 动作分发：限流 → 内置模块门控 → 按 action 分派。
     *
     * <p>三道前置步骤的顺序不能变：限流在最前（否则被停用的玩法仍然能打满限流桶），
     * 模块门控在分发之前（停用的玩法不该走到任何业务代码）。</p>
     *
     * <p>每个分支只做两件事：需要权限的先 {@link WsSupport#checkWsPermission}
     * （不通过就 {@code break}，与拆分前完全一致），然后委托给对应处理器。
     * 处理器返回前已经把回包发出去了，本方法不额外回包 ——
     * 因此消息顺序语义（先广播再回执、先推通知再回执等）与拆分前逐字一致。</p>
     */
    private void handleGameMessage(WebSocket socket, Long userId, GameMessage req) {
        String type = req.getType();
        long msgId = req.getMsgId();
        JsonObject data = req.getData();

        String rateKey = "ws:" + userId + ":" + type;
        if (!RateLimiter.allow(rateKey, 20, 60)) {
            socket.send(GameMessage.error(msgId, type, GameErrorCode.RATE_LIMITED).toJson());
            return;
        }

        // 内置模块门控：模块停用后其 WebSocket 动作一并失效
        String module = wsModuleForAction(type);
        if (module != null && !com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown(module)) {
            socket.send(GameMessage.error(msgId, type, 403, "该玩法系统已停用，暂时无法使用").toJson());
            return;
        }

        switch (type) {
            case "chat":
                // 世界聊天此前是唯一没有权限检查的聊天动作（chat_private / chat_history /
                // rank 都有），被禁用 game.chat.world 的角色照样能往世界频道发言。
                if (!checkWsPermission(socket, msgId, userId, "game.chat.world")) break;
                chatHandler.handleChat(socket, msgId, userId, data);
                break;
            case "chat_private":
                if (!checkWsPermission(socket, msgId, userId, "game.chat.private")) break;
                chatHandler.handlePrivateChat(socket, msgId, userId, data);
                break;
            case "chat_history":
                if (!checkWsPermission(socket, msgId, userId, "game.chat.world")) break;
                chatHandler.handleChatHistory(socket, msgId, data);
                break;
            case "rank":
                if (!checkWsPermission(socket, msgId, userId, "game.rank.view")) break;
                socialHandler.handleRanking(socket, msgId, data);
                break;
            case "friend_add":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                socialHandler.handleFriendAdd(socket, msgId, userId, data);
                break;
            case "friend_accept":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                socialHandler.handleFriendAccept(socket, msgId, userId, data);
                break;
            case "friend_reject":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                socialHandler.handleFriendReject(socket, msgId, userId, data);
                break;
            case "friend_remove":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                socialHandler.handleFriendRemove(socket, msgId, userId, data);
                break;
            case "friend_list":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                socialHandler.handleFriendList(socket, msgId, userId);
                break;
            case "friend_pending":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                socialHandler.handleFriendPending(socket, msgId, userId);
                break;
            case "heal":
                if (!checkWsPermission(socket, msgId, userId, "game.player.info")) break;
                playerHandler.handleHeal(socket, msgId, userId);
                break;
            case "morning_cultivation":
                if (!checkWsPermission(socket, msgId, userId, "game.player.info")) break;
                playerHandler.handleMorningCultivation(socket, msgId, userId);
                break;
            case "player_info":
                if (!checkWsPermission(socket, msgId, userId, "game.player.info")) break;
                playerHandler.handlePlayerInfo(socket, msgId, userId);
                break;
            case "cultivate_start":
                if (!checkWsPermission(socket, msgId, userId, "game.cultivate")) break;
                cultivationHandler.handleCultivateStart(socket, msgId, userId);
                break;
            case "cultivate_stop":
                if (!checkWsPermission(socket, msgId, userId, "game.cultivate")) break;
                cultivationHandler.handleCultivateStop(socket, msgId, userId, data);
                break;
            case "breakthrough":
                if (!checkWsPermission(socket, msgId, userId, "game.realm.breakthrough")) break;
                cultivationHandler.handleBreakthrough(socket, msgId, userId);
                break;
            case "inventory":
                if (!checkWsPermission(socket, msgId, userId, "game.inventory.view")) break;
                inventoryHandler.handleInventory(socket, msgId, userId);
                break;
            case "item_use":
                if (!checkWsPermission(socket, msgId, userId, "game.item.use")) break;
                inventoryHandler.handleItemUse(socket, msgId, userId, data);
                break;
            case "item_registry":
                if (!checkWsPermission(socket, msgId, userId, "game.item.registry")) break;
                inventoryHandler.handleItemRegistry(socket, msgId);
                break;
            case "heartbeat":
                systemHandler.handleHeartbeat(socket, msgId);
                break;
            case "secret_realm_areas":
                if (!checkWsPermission(socket, msgId, userId, "game.secret_realm")) break;
                secretRealmHandler.handleSecretRealmAreas(socket, msgId, userId);
                break;
            case "secret_realm_enter":
                if (!checkWsPermission(socket, msgId, userId, "game.secret_realm")) break;
                secretRealmHandler.handleSecretRealmEnter(socket, msgId, userId, data);
                break;
            case "exploration":
                if (!checkWsPermission(socket, msgId, userId, "game.explore")) break;
                secretRealmHandler.handleExploration(socket, msgId, userId);
                break;
            case "techniques":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.learn")) break;
                techniqueHandler.handleTechniques(socket, msgId);
                break;
            case "my_techniques":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.learn")) break;
                techniqueHandler.handleMyTechniques(socket, msgId, userId);
                break;
            case "technique_learn":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.learn")) break;
                techniqueHandler.handleTechniqueLearn(socket, msgId, userId, data);
                break;
            case "technique_equip":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.equip")) break;
                techniqueHandler.handleTechniqueEquip(socket, msgId, userId, data);
                break;
            case "technique_unequip":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.equip")) break;
                techniqueHandler.handleTechniqueUnequip(socket, msgId, userId, data);
                break;
            case "technique_upgrade":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.upgrade")) break;
                techniqueHandler.handleTechniqueUpgrade(socket, msgId, userId, data);
                break;
            case "crafting_recipes":
                if (!checkWsPermission(socket, msgId, userId, "game.crafting.recipes")) break;
                craftingHandler.handleCraftingRecipes(socket, msgId, data);
                break;
            case "crafting_craft":
                if (!checkWsPermission(socket, msgId, userId, "game.crafting.craft")) break;
                craftingHandler.handleCraftingCraft(socket, msgId, userId, data);
                break;
            case "equipment_enhance":
                if (!checkWsPermission(socket, msgId, userId, "game.equipment.enhance")) break;
                inventoryHandler.handleEquipmentEnhance(socket, msgId, userId, data);
                break;
            case "sect_list":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectList(socket, msgId);
                break;
            case "sect_info":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectInfo(socket, msgId, userId, data);
                break;
            case "sect_members":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectMembers(socket, msgId, userId);
                break;
            case "sect_create":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectCreate(socket, msgId, userId, data);
                break;
            case "sect_join":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectJoin(socket, msgId, userId, data);
                break;
            case "sect_applications":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectApplications(socket, msgId, userId);
                break;
            case "sect_approve":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectApprove(socket, msgId, userId, data);
                break;
            case "sect_reject":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectReject(socket, msgId, userId, data);
                break;
            case "sect_leave":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectLeave(socket, msgId, userId);
                break;
            case "sect_kick":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectKick(socket, msgId, userId, data);
                break;
            case "sect_appoint":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectAppoint(socket, msgId, userId, data);
                break;
            case "sect_warehouse":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectWarehouse(socket, msgId, userId);
                break;
            case "sect_donate":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.donate")) break;
                sectHandler.handleSectDonate(socket, msgId, userId, data);
                break;
            case "sect_take":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.warehouse")) break;
                sectHandler.handleSectTake(socket, msgId, userId, data);
                break;
            case "sect_disband":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectDisband(socket, msgId, userId);
                break;
            case "sect_levelup":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectLevelUp(socket, msgId, userId);
                break;
            case "sect_transfer":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectTransfer(socket, msgId, userId, data);
                break;
            case "sect_war":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectWar(socket, msgId, userId, data);
                break;
            case "sect_top":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                sectHandler.handleSectTop(socket, msgId);
                break;
            case "map_surroundings":
                if (!checkWsPermission(socket, msgId, userId, "game.player.info")) break;
                mapHandler.handleMapSurroundings(socket, msgId, userId);
                break;
            case "map_travel":
                if (!checkWsPermission(socket, msgId, userId, "game.player.info")) break;
                mapHandler.handleMapTravel(socket, msgId, userId, data);
                break;
            case "map_locations":
                if (!checkWsPermission(socket, msgId, userId, "game.player.info")) break;
                mapHandler.handleMapLocations(socket, msgId);
                break;
            case "season_info":
                systemHandler.handleSeasonInfo(socket, msgId);
                break;
            default:
                // 先尝试插件 WebSocket 处理器
                if (com.mtxgdn.plugin.PluginWebManager.getInstance()
                        .handleWsMessage(socket, type, data)) {
                    break;
                }
                GameMessage err = GameMessage.error(msgId, type, GameErrorCode.UNKNOWN_TYPE);
                socket.send(err.toJson());
                break;
        }
    }

    public void broadcast(GameMessage message) {
        String json = message.toJson();
        for (WebSocket socket : sessionUsers.keySet()) {
            if (socket.isConnected()) {
                try {
                    socket.send(json);
                } catch (Exception e) {
                    // 单连接发送失败不影响其他玩家
                }
            }
        }
    }

    public void sendToUser(Long userId, GameMessage message) {
        WebSocket socket = userSessions.get(userId);
        if (socket != null && socket.isConnected()) {
            try {
                socket.send(message.toJson());
            } catch (Exception e) {
                // 发送失败静默处理
            }
        }
    }

    /**
     * 该账号当前是否持有活跃的 WebSocket 会话。
     *
     * <p>玩家数据文件恢复前必须问这一句：正在玩的角色被回滚，
     * 等于把玩家眼前的进度偷偷换掉，且他下一次操作就会把回滚结果写回去。
     * 注意只把"连接仍然连着"算在线 —— 掉线的幽灵会话不该永久挡住数据修复。</p>
     */
    public boolean isUserOnline(Long userId) {
        if (userId == null) {
            return false;
        }
        WebSocket socket = userSessions.get(userId);
        return socket != null && socket.isConnected();
    }

    private void broadcastUserOnline(Long userId, String username) {
        JsonObject data = new JsonObject();
        data.addProperty("userId", userId);
        data.addProperty("username", username);
        data.addProperty("online", true);

        GameMessage message = GameMessage.ok(0, "user_online", data);
        broadcast(message);
    }

    private void broadcastUserOffline(Long userId) {
        JsonObject data = new JsonObject();
        data.addProperty("userId", userId);
        data.addProperty("online", false);

        GameMessage message = GameMessage.ok(0, "user_offline", data);
        broadcast(message);
    }

    public int getOnlineCount() {
        return sessionUsers.size();
    }

    public void shutdownGracefully() {
        try {
            var sockets = new java.util.ArrayList<>(authenticated.keySet());
            authenticated.clear();
            sessionUsers.clear();
            userSessions.clear();
            for (WebSocket socket : sockets) {
                try {
                    socket.close(1001, "服务器关闭");
                } catch (Exception ignore) {
                }
            }
        } catch (Exception e) {
            System.err.println("WebSocket 优雅关闭失败: " + e.getMessage());
        }
    }
}
