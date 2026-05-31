package com.mtxgdn.onebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.entity.SecretRealmResult;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.game.entity.ChatMessage;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.game.service.CombatService;
import com.mtxgdn.game.service.DailyService;
import com.mtxgdn.game.service.ExplorationService;
import com.mtxgdn.game.service.HeartDemonService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.RealmService;
import com.mtxgdn.game.service.SecretRealmService;
import com.mtxgdn.game.service.SkillService;
import com.mtxgdn.game.service.TradeService;
import com.mtxgdn.game.service.ChatService;
import com.mtxgdn.game.service.FriendService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.LangManager;
import com.mtxgdn.util.OneBotLogger;
import com.mtxgdn.util.PlayerActionLogger;
import com.mtxgdn.util.RateLimiter;
import org.glassfish.grizzly.websockets.DataFrame;
import org.glassfish.grizzly.websockets.WebSocket;
import org.glassfish.grizzly.websockets.WebSocketApplication;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OneBotWebSocketServer extends WebSocketApplication {

    private static final Gson gson = new Gson();
    private static final GameLogger log = GameLogger.getLogger("OneBotServer");
    private static final OneBotLogger botLog = OneBotLogger.getInstance();
    private static final QqBindingService bindingService = new QqBindingService();
    private static final PlayerService playerService = new PlayerService();
    private static final RealmService realmService = new RealmService(playerService);
    private static final ItemService itemService = new ItemService();
    private static final SecretRealmService secretRealmService = new SecretRealmService(playerService);
    private static final ExplorationService explorationService = new ExplorationService(playerService);
    private static final NewbieGuideService guideService = new NewbieGuideService();
    private static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();
    private static final SkillService skillService = new SkillService();
    private static final CombatService combatService = new CombatService();
    private static final DailyService dailyService = new DailyService();
    private static final TradeService tradeService = new TradeService();
    private static final HeartDemonService heartDemonService = new HeartDemonService();
    private static final ChatService chatService = new ChatService();
    private static final FriendService friendService = new FriendService();

    private final Map<WebSocket, String> sessionBots = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> botSessions = new ConcurrentHashMap<>();
    private final Map<String, PendingSession> pendingSessions = new ConcurrentHashMap<>();
    private final Map<Long, Long> cultivateStartTimes = new ConcurrentHashMap<>();

    private static class PendingSession {
        final String qqNumber;
        String selfId;
        Long sourceGroupId;
        String type;  // "register", "bind"
        String state;
        String username;

        PendingSession(String qqNumber, String type, String selfId, Long sourceGroupId) {
            this.qqNumber = qqNumber;
            this.type = type;
            this.selfId = selfId;
            this.sourceGroupId = sourceGroupId;
            this.state = "WAITING_PASSWORD";
        }

        PendingSession(String qqNumber) {
            this.qqNumber = qqNumber;
            this.type = "bind";
            this.state = "WAITING_USERNAME";
        }
    }

    @Override
    public void onConnect(WebSocket socket) {
        botLog.logSystem("OneBot 客户端已连接");
        log.info("OneBot 客户端已连接");
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
        botLog.logRecv(text);

        try {
            JsonObject json = gson.fromJson(text, JsonObject.class);

            if (json.has("post_type")) {
                handleEvent(socket, json);
            } else if (json.has("echo") && json.has("status")) {
                handleApiResponse(socket, json);
            } else if (json.has("meta_event_type")) {
                handleMetaEvent(socket, json);
            } else {
                log.warn("未知消息类型");
            }
        } catch (Exception e) {
            log.error("处理消息异常: " + e.getMessage(), e);
        }
    }

    @Override
    public void onClose(WebSocket socket, DataFrame frame) {
        String selfId = sessionBots.remove(socket);
        if (selfId != null) {
            botSessions.remove(selfId);
            botLog.logSystem("OneBot 客户端已断开: " + selfId);
            log.info("OneBot 客户端已断开: " + selfId);
        }
    }

    private void handleMetaEvent(WebSocket socket, JsonObject json) {
        String metaType = json.get("meta_event_type").getAsString();
        if ("lifecycle".equals(metaType)) {
            String selfId = json.has("self_id") ? json.get("self_id").getAsString() : "unknown";
            sessionBots.put(socket, selfId);
            botSessions.put(selfId, socket);
            botLog.logSystem("OneBot Bot 已上线: " + selfId);
            log.info("OneBot Bot 已上线: " + selfId);
        }
    }

    private void handleEvent(WebSocket socket, JsonObject json) {
        String postType = json.get("post_type").getAsString();
        switch (postType) {
            case "message":
                handleMessageEvent(socket, json);
                break;
            case "notice":
                break;
            case "request":
                break;
            default:
                log.warn("未知 post_type: " + postType);
        }
    }

    private void handleMessageEvent(WebSocket socket, JsonObject json) {
        String messageType = json.get("message_type").getAsString();
        String selfId = json.get("self_id").getAsString();
        String rawMessage = json.get("raw_message").getAsString();
        JsonObject sender = json.getAsJsonObject("sender");
        String senderQq = sender.get("user_id").getAsString();
        String senderNickname = sender.has("nickname") ? sender.get("nickname").getAsString() : "未知";

        if ("private".equals(messageType)) {
            handlePrivateMessage(socket, selfId, senderQq, senderNickname, rawMessage);
        } else if ("group".equals(messageType)) {
            Long groupId = json.get("group_id").getAsLong();
            handleGroupMessage(socket, selfId, groupId, senderQq, senderNickname, rawMessage);
        }
    }

    // ==================== 私聊处理 ====================

    private void handlePrivateMessage(WebSocket socket, String selfId, String senderQq,
                                       String senderNickname, String rawMessage) {
        log.info("[私聊:" + senderQq + "] " + senderNickname + ": " + rawMessage);

        String trimmed = rawMessage.trim();

        PendingSession session = pendingSessions.get(senderQq);
        if (session != null) {
            handlePendingFlow(socket, selfId, senderQq, session, trimmed);
            return;
        }

        if (!isCommand(trimmed)) {
            return;
        }

        String[] parsed = parseCommand(trimmed);
        String cmd = parsed[0];
        String arg = parsed[1];

        if (!RateLimiter.allow("qq:" + senderQq, 10, 60)) {
            sendPrivateMsg(socket, selfId, senderQq, "操作太频繁，请稍后再试");
            return;
        }

        switch (cmd) {
            case "help", "帮助":
                handleHelp(socket, selfId, senderQq, null);
                break;
            case "register", "注册":
                handleRegister(socket, selfId, senderQq, arg, null);
                break;
            case "bind", "绑定":
                handleBind(socket, selfId, senderQq);
                break;
            case "unbind", "解绑":
                handleUnbind(socket, selfId, senderQq);
                break;

            case "status", "状态":
            case "info", "信息":
                handleStatus(socket, selfId, senderQq, null);
                break;
            case "cultivate", "修炼", "闭关":
                handleCultivate(socket, selfId, senderQq, null);
                break;
            case "stop", "停止":
                handleCultivateStop(socket, selfId, senderQq, arg, null);
                break;
            case "explore", "游历":
                handleExplore(socket, selfId, senderQq, null);
                break;
            case "breakthrough", "突破":
                handleBreakthrough(socket, selfId, senderQq, null);
                break;
            case "backpack", "背包":
                handleBackpack(socket, selfId, senderQq, null);
                break;
            case "itemuse", "使用":
                handleItemUse(socket, selfId, senderQq, arg, null);
                break;
            case "itemmap", "物品列表":
                handleItemMap(socket, selfId, senderQq, null);
                break;
            case "secret", "秘境":
                handleSecretAreas(socket, selfId, senderQq, null);
                break;
            case "secret_enter", "进入秘境":
                handleSecretEnter(socket, selfId, senderQq, arg, null);
                break;

            case "skills", "技能":
                handleSkills(socket, selfId, senderQq, null);
                break;
            case "learn", "学习":
                handleLearnSkill(socket, selfId, senderQq, arg, null);
                break;

            case "pvp", "挑战":
                handlePvp(socket, selfId, senderQq, arg, null);
                break;
            case "equip", "装备":
                handleEquip(socket, selfId, senderQq, arg, null);
                break;
            case "unequip", "卸下":
                handleUnequip(socket, selfId, senderQq, arg, null);
                break;
            case "equipped", "已装备":
                handleEquipped(socket, selfId, senderQq, null);
                break;

            case "market", "坊市":
                handleMarket(socket, selfId, senderQq, null);
                break;
            case "list", "上架":
                handleListItem(socket, selfId, senderQq, arg, null);
                break;
            case "buy", "购买":
                handleBuyItem(socket, selfId, senderQq, arg, null);
                break;
            case "cancel", "撤单":
                handleCancelListing(socket, selfId, senderQq, arg, null);
                break;
            case "mylistings", "我的挂单":
                handleMyListings(socket, selfId, senderQq, null);
                break;
            case "morning", "晨修":
                handleMorning(socket, selfId, senderQq, null);
                break;
            case "daily", "天象":
                handleDaily(socket, selfId, senderQq, null);
                break;

            case "msg", "私聊":
                handlePrivateMessageCmd(socket, selfId, senderQq, arg, null);
                break;
            case "rank", "排行":
            case "rank2", "排行榜":
                handleRankCmd(socket, selfId, senderQq, arg, null);
                break;
            case "friend", "好友":
                handleFriendCmd(socket, selfId, senderQq, arg, null);
                break;

            case "heal", "疗伤":
                handleHealCmd(socket, selfId, senderQq, null);
                break;

            case "cleardb_players", "清除玩家数据":
                handleClearPlayersDb(socket, selfId, senderQq, null);
                break;
            case "cleardb_all", "重置全部数据":
                handleResetAllDb(socket, selfId, senderQq, null);
                break;

            default:
                sendPrivateMsg(socket, selfId, senderQq, "未知指令，请输入 /help 查看可用指令");
        }
    }

    // ==================== 群聊处理 ====================

    private void handleGroupMessage(WebSocket socket, String selfId, Long groupId,
                                     String senderQq, String senderNickname, String rawMessage) {
        log.info("[群聊:" + groupId + "] [QQ:" + senderQq + "] " + senderNickname + ": " + rawMessage);

        String trimmed = rawMessage.trim();
        if (!isCommand(trimmed)) {
            return;
        }

        String[] parsed = parseCommand(trimmed);
        String cmd = parsed[0];
        String arg = parsed[1];

        if (!RateLimiter.allow("qq:" + senderQq, 10, 60)) {
            sendGroupMsg(socket, selfId, groupId,
                    "[CQ:at,qq=" + senderQq + "] 操作太频繁，请稍后再试");
            return;
        }

        switch (cmd) {
            case "help", "帮助":
                handleHelp(socket, selfId, senderQq, groupId);
                break;
            case "register", "注册":
                handleRegister(socket, selfId, senderQq, arg, groupId);
                break;
            case "bind", "绑定":
            case "unbind", "解绑":
            case "cleardb_players", "清除玩家数据":
            case "cleardb_all", "重置全部数据":
                sendGroupMsg(socket, selfId, groupId,
                        "[CQ:at,qq=" + senderQq + "] 请私聊机器人使用此指令，保护您的账户安全。");
                break;
            case "status", "状态":
            case "info", "信息":
                handleStatus(socket, selfId, senderQq, groupId);
                break;
            case "cultivate", "修炼", "闭关":
                handleCultivate(socket, selfId, senderQq, groupId);
                break;
            case "stop", "停止":
                handleCultivateStop(socket, selfId, senderQq, arg, groupId);
                break;
            case "explore", "游历":
                handleExplore(socket, selfId, senderQq, groupId);
                break;
            case "breakthrough", "突破":
                handleBreakthrough(socket, selfId, senderQq, groupId);
                break;
            case "backpack", "背包":
                handleBackpack(socket, selfId, senderQq, groupId);
                break;
            case "itemuse", "使用":
                handleItemUse(socket, selfId, senderQq, arg, groupId);
                break;
            case "itemmap", "物品列表":
                handleItemMap(socket, selfId, senderQq, groupId);
                break;
            case "secret", "秘境":
                handleSecretAreas(socket, selfId, senderQq, groupId);
                break;
            case "secret_enter", "进入秘境":
                handleSecretEnter(socket, selfId, senderQq, arg, groupId);
                break;
            case "skills", "技能":
                handleSkills(socket, selfId, senderQq, groupId);
                break;
            case "learn", "学习":
                handleLearnSkill(socket, selfId, senderQq, arg, groupId);
                break;
            case "pvp", "挑战":
                handlePvp(socket, selfId, senderQq, arg, groupId);
                break;
            case "equip", "装备":
                handleEquip(socket, selfId, senderQq, arg, groupId);
                break;
            case "unequip", "卸下":
                handleUnequip(socket, selfId, senderQq, arg, groupId);
                break;
            case "equipped", "已装备":
                handleEquipped(socket, selfId, senderQq, groupId);
                break;
            case "market", "坊市":
                handleMarket(socket, selfId, senderQq, groupId);
                break;
            case "list", "上架":
                handleListItem(socket, selfId, senderQq, arg, groupId);
                break;
            case "buy", "购买":
                handleBuyItem(socket, selfId, senderQq, arg, groupId);
                break;
            case "cancel", "撤单":
                handleCancelListing(socket, selfId, senderQq, arg, groupId);
                break;
            case "mylistings", "我的挂单":
                handleMyListings(socket, selfId, senderQq, groupId);
                break;
            case "morning", "晨修":
                handleMorning(socket, selfId, senderQq, groupId);
                break;
            case "daily", "天象":
                handleDaily(socket, selfId, senderQq, groupId);
                break;
            case "msg", "私聊":
                handlePrivateMessageCmd(socket, selfId, senderQq, arg, groupId);
                break;
            case "rank", "排行":
            case "rank2", "排行榜":
                handleRankCmd(socket, selfId, senderQq, arg, groupId);
                break;
            case "friend", "好友":
                handleFriendCmd(socket, selfId, senderQq, arg, groupId);
                break;
            case "heal", "疗伤":
                handleHealCmd(socket, selfId, senderQq, groupId);
                break;
        }
    }

    private void handleApiResponse(WebSocket socket, JsonObject json) {
        String echo = json.has("echo") ? json.get("echo").getAsString() : "";
        String status = json.get("status").getAsString();
        if (!"ok".equals(status)) {
            log.warn("API 调用失败 [echo=" + echo + "]");
        }
    }

    // ==================== 帮助指令 ====================

    private String getHelpText(Long userId) {
        if (userId == null) {
            return "修仙世界 - QQ Bot 指令列表\n" +
                   "==============================\n" +
                   "【账户】\n" +
                   "/register <角色名>       注册角色（群聊/私聊均可，密码私聊发送）\n" +
                   "/bind                    绑定已有游戏账号\n" +
                   "/unbind                  解绑账号\n" +
                   "\n==============================\n" +
                   "请先 /register 或 /bind 绑定账号后再查看完整指令";
        }
        boolean hasStatus = PermissionService.hasPermission(userId, "game.player.info");
        boolean hasCultivate = PermissionService.hasPermission(userId, "game.cultivate");
        boolean hasExplore = PermissionService.hasPermission(userId, "game.explore");
        boolean hasBreakthrough = PermissionService.hasPermission(userId, "game.realm.breakthrough");
        boolean hasBackpack = PermissionService.hasPermission(userId, "game.inventory.view");
        boolean hasItemUse = PermissionService.hasPermission(userId, "game.item.use");
        boolean hasSecretRealm = PermissionService.hasPermission(userId, "game.secret_realm");
        boolean hasSkill = PermissionService.hasPermission(userId, "game.skill.learn");
        boolean hasPvp = PermissionService.hasPermission(userId, "game.pvp.challenge");
        boolean hasEquip = PermissionService.hasPermission(userId, "game.equipment.equip");

        StringBuilder sb = new StringBuilder();
        sb.append("修仙世界 - QQ Bot 指令列表\n");
        sb.append("==============================\n");

        sb.append("【账户】\n");
        sb.append("/register <角色名>       注册角色（群聊/私聊均可，密码私聊发送）\n");
        sb.append("/bind                    绑定已有游戏账号\n");
        sb.append("/unbind                  解绑账号\n");

        if (hasStatus) {
            sb.append("\n【我的角色】\n");
            sb.append("/status                  查看角色状态\n");
        }

        if (hasCultivate) {
            sb.append("\n【修炼】\n");
            sb.append("/cultivate               开始闭关\n");
            sb.append("/stop                    结束闭关并结算灵力（可能触发心魔）\n");
        }

        if (hasExplore || hasSecretRealm) {
            sb.append("\n【探索】\n");
            if (hasExplore) sb.append("/explore                 游历探索\n");
            if (hasSecretRealm) {
                sb.append("/secret                  查看可用秘境\n");
                sb.append("/secret_enter <名称>      进入秘境\n");
            }
        }

        boolean hasBattleSection = hasBreakthrough || hasPvp || hasBackpack || hasItemUse || hasEquip || hasSkill;
        if (hasBattleSection) {
            sb.append("\n【战斗与成长】\n");
            if (hasBreakthrough) sb.append("/breakthrough            境界突破\n");
            if (hasPvp) sb.append("/pvp <角色名>            挑战其他修士\n");
            if (hasBackpack) sb.append("/backpack                查看背包\n");
            if (hasItemUse) sb.append("/itemuse <物品名>       使用物品\n");
            sb.append("/itemmap                 物品中文名与key映射表\n");
            if (hasEquip) {
                sb.append("/equip <物品key> <部位>   装备物品\n");
                sb.append("/unequip <部位>           卸下装备\n");
                sb.append("/equipped                查看已装备\n");
            }
            if (hasSkill) {
                sb.append("/skills                  查看技能列表\n");
                sb.append("/learn <技能ID>           学习技能\n");
            }
        }

        sb.append("\n【世界】\n");
        sb.append("/market                  查看坊市挂单\n");
        sb.append("/list <key> <数量> <灵石> 上架物品\n");
        sb.append("/buy <挂单ID>             购买坊市物品\n");
        sb.append("/cancel <挂单ID>          撤回挂单\n");
        sb.append("/mylistings              我的挂单\n");
        sb.append("/morning                 每日晨修\n");
        sb.append("/daily                   查看今日天象与机缘\n");

        boolean hasClearPlayers = PermissionService.hasPermission(userId, "admin.database.clear_players");
        boolean hasResetAll = PermissionService.hasPermission(userId, "admin.database.reset_all");
        if (hasClearPlayers || hasResetAll) {
            sb.append("\n【管理】\n");
            if (hasClearPlayers) sb.append("/cleardb_players          清除所有玩家数据（仅私聊）\n");
            if (hasResetAll) sb.append("/cleardb_all              重置全部数据并重新初始化（仅私聊）\n");
        }

        sb.append("\n==============================\n");
        sb.append("注册请在群聊或私聊发送 /register <角色名>，密码通过私聊发送；\n");
        sb.append("绑定和解绑请在私聊中进行");
        return sb.toString();
    }

    private void handleHelp(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = null;
        QqBinding b = bindingService.findByQq(senderQq);
        if (b != null) userId = b.getUserId();
        String msg = getHelpText(userId);
        if (groupId == null) {
            sendPrivateMsg(socket, selfId, senderQq, msg);
        } else {
            sendGroupMsg(socket, selfId, groupId, msg);
        }
    }

    private void handleRegister(WebSocket socket, String selfId, String senderQq, String arg, Long sourceGroupId) {
        if (bindingService.findByQq(senderQq) != null) {
            replyToSource(socket, selfId, senderQq, sourceGroupId, "你已注册角色，无需重复注册。如需重来请先用 /unbind 解绑。");
            return;
        }

        String name = arg.trim();
        if (name.isEmpty()) {
            replyToSource(socket, selfId, senderQq, sourceGroupId, "用法: /register <角色名>\n密码将在下一步通过私聊安全发送");
            return;
        }

        String[] parts = name.split("\\s+", 2);
        name = parts[0].trim();

        if (name.length() > 16) {
            replyToSource(socket, selfId, senderQq, sourceGroupId, "角色名不能超过16个字");
            return;
        }

        if (isUsernameExists(name)) {
            replyToSource(socket, selfId, senderQq, sourceGroupId, "角色名已被占用，请换一个。");
            return;
        }

        PendingSession session = new PendingSession(senderQq, "register", selfId, sourceGroupId);
        session.username = name;
        pendingSessions.put(senderQq, session);

        replyToSource(socket, selfId, senderQq, sourceGroupId, "角色名【" + name + "】可用！\n请在私聊中发送你的密码（不少于6位）\n(输入 /cancel 取消)");
    }

    private void handleBind(WebSocket socket, String selfId, String senderQq) {
        if (bindingService.findByQq(senderQq) != null) {
            QqBinding b = bindingService.findByQq(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "你已绑定账号 (用户ID: " + b.getUserId() + ")，/unbind 可解绑。");
            return;
        }

        PendingSession session = new PendingSession(senderQq);
        pendingSessions.put(senderQq, session);
        sendPrivateMsg(socket, selfId, senderQq, "===== 账号绑定 =====\n请输入游戏用户名：\n(输入 /cancel 取消)");
    }

    private void handlePendingFlow(WebSocket socket, String selfId, String senderQq,
                                    PendingSession session, String message) {
        if ("/cancel".equalsIgnoreCase(message.trim())) {
            pendingSessions.remove(senderQq);
            replyToSource(socket, selfId, senderQq, session.sourceGroupId, "已取消。");
            return;
        }

        switch (session.state) {
            case "WAITING_USERNAME":
                session.username = message.trim();
                session.state = "WAITING_PASSWORD";
                sendPrivateMsg(socket, selfId, senderQq, "请输入密码：\n(输入 /cancel 取消)");
                break;

            case "WAITING_PASSWORD":
                String password = message.trim();
                if (password.length() < 6) {
                    sendPrivateMsg(socket, selfId, senderQq, "密码不少于6位，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }

                if ("register".equals(session.type)) {
                    if (isUsernameExists(session.username)) {
                        replyToSource(socket, selfId, senderQq, session.sourceGroupId, "角色名【" + session.username + "】已被占用，请重新注册。");
                        pendingSessions.remove(senderQq);
                        return;
                    }
                    if (bindingService.findByQq(senderQq) != null) {
                        replyToSource(socket, selfId, senderQq, session.sourceGroupId, "你已注册角色，无需重复注册。如需重来请先用 /unbind 解绑。");
                        pendingSessions.remove(senderQq);
                        return;
                    }
                    try {
                        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
                        Long userId = insertUser(session.username, hashedPassword);
                        if (userId == null) {
                            replyToSource(socket, selfId, senderQq, session.sourceGroupId, "注册失败，请稍后重试。");
                            pendingSessions.remove(senderQq);
                            return;
                        }
                        PermissionService.assignDefaultRole(userId);
                        bindingService.bind(senderQq, userId);

                        PlayerInfo player = playerService.createPlayer(userId, session.username);
                        String guideMsg = NewbieGuideService.getWelcomeMessage(player);
                        replyToSource(socket, selfId, senderQq, session.sourceGroupId, guideMsg);
                        actionLog.logCreatePlayer(userId, session.username);
                    } catch (RuntimeException e) {
                        replyToSource(socket, selfId, senderQq, session.sourceGroupId, "注册失败: " + e.getMessage());
                    }
                } else {
                    Long userId = verifyUserCredentials(session.username, password);
                    if (userId == null) {
                        sendPrivateMsg(socket, selfId, senderQq, "用户名或密码错误，请重新 /bind。");
                        pendingSessions.remove(senderQq);
                        return;
                    }
                    try {
                        bindingService.bind(senderQq, userId);
                        sendPrivateMsg(socket, selfId, senderQq, "绑定成功！\n用户名: " + session.username + "\n用户ID: " + userId);
                    } catch (RuntimeException e) {
                        sendPrivateMsg(socket, selfId, senderQq, "绑定失败: " + e.getMessage());
                    }
                }
                pendingSessions.remove(senderQq);
                break;
        }
    }

    private void handleUnbind(WebSocket socket, String selfId, String senderQq) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            sendPrivateMsg(socket, selfId, senderQq, "你尚未绑定账号。");
            return;
        }
        try {
            bindingService.unbindByQq(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "解绑成功！");
        } catch (RuntimeException e) {
            sendPrivateMsg(socket, selfId, senderQq, "解绑失败: " + e.getMessage());
        }
    }

    // ==================== 游戏指令 ====================

    private void sendReply(WebSocket socket, String selfId, String senderQq, Long groupId, String message) {
        if (groupId != null) {
            sendGroupMsg(socket, selfId, groupId, "[CQ:at,qq=" + senderQq + "]\n" + message);
        } else {
            sendPrivateMsg(socket, selfId, senderQq, message);
        }
    }

    private void replyToSource(WebSocket socket, String selfId, String senderQq, Long groupId, String message) {
        if (groupId != null) {
            sendGroupMsg(socket, selfId, groupId, "[CQ:at,qq=" + senderQq + "]\n" + message);
        } else {
            sendPrivateMsg(socket, selfId, senderQq, message);
        }
    }

    private Long requireBinding(WebSocket socket, String selfId, String senderQq, Long groupId) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            String msg = "请先注册或绑定账号。\n注册: /register <用户名> <密码>\n绑定: /bind";
            if (groupId != null) {
                sendGroupMsg(socket, selfId, groupId,
                        "[CQ:at,qq=" + senderQq + "] " + msg + "\n（请在私聊中操作）");
            } else {
                sendPrivateMsg(socket, selfId, senderQq, msg);
            }
            return null;
        }
        return b.getUserId();
    }

    private PlayerInfo requirePlayer(WebSocket socket, String selfId, String senderQq, Long userId, Long groupId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            sendReply(socket, selfId, senderQq, groupId,
                    "你还没有创建修仙角色。\n请使用 /register <角色名> 注册角色");
            return null;
        }
        return player;
    }

    private boolean checkQqPermission(WebSocket socket, String selfId, String senderQq, Long groupId, String permission) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            return false;
        }
        if (!PermissionService.hasPermission(b.getUserId(), permission)) {
            sendReply(socket, selfId, senderQq, groupId,
                    "权限不足，你无权使用此功能。");
            return false;
        }
        return true;
    }

    private boolean requireQqPermission(WebSocket socket, String selfId, String senderQq, Long groupId, String permission) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            sendReply(socket, selfId, senderQq, groupId, "请先绑定账号。\n私聊使用 /bind");
            return false;
        }
        if (!PermissionService.hasPermission(b.getUserId(), permission)) {
            sendReply(socket, selfId, senderQq, groupId,
                    "权限不足，你无权使用此功能。");
            return false;
        }
        return true;
    }

    private void handleStatus(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.player.info")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        sendReply(socket, selfId, senderQq, groupId, formatPlayerStatus(p));
    }

    private void handleCultivate(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.cultivate")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        if (p.isCultivating()) {
            sendReply(socket, selfId, senderQq, groupId, "你已经在闭关中了，使用 /stop 来结束。");
            return;
        }

        playerService.setCultivating(p.getId(), true);
        cultivateStartTimes.put(userId, System.currentTimeMillis());
        actionLog.logCultivateStart(userId, p.getName(), p.getRealm());

        int ratePerSec = GameConfigLoader.getCultivationPerSecond(p.getRealm());
        int ratePerMin = ratePerSec * 60;
        String msg = "开始闭关！\n每分钟获得 " + ratePerMin + " 灵力\n使用 /stop 结束闭关并结算灵力";
        NewbieGuideService.GuideResult guide = guideService.checkAndAdvance((int) p.getId(), p, "cultivate_start");
        if (guide.message != null) msg += "\n\n💡 " + guide.message;

        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    private void handleCultivateStop(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.cultivate")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        if (!p.isCultivating()) {
            sendReply(socket, selfId, senderQq, groupId, "你还没有开始闭关，使用 /cultivate 开始。");
            return;
        }

        Long startTime = cultivateStartTimes.remove(userId);
        if (startTime == null) {
            playerService.setCultivating(p.getId(), false);
            sendReply(socket, selfId, senderQq, groupId, "闭关状态异常，已强制结束。");
            return;
        }

        long elapsedMillis = System.currentTimeMillis() - startTime;
        if (elapsedMillis < 1000) {
            playerService.setCultivating(p.getId(), false);
            sendReply(socket, selfId, senderQq, groupId, "闭关时间太短，未获得灵力。");
            return;
        }

        int elapsedSeconds = (int) (elapsedMillis / 1000);
        int cultivationPerSec = GameConfigLoader.getCultivationPerSecond(p.getRealm());
        long lingliGained = (long) elapsedSeconds * cultivationPerSec;
        if (lingliGained <= 0) {
            lingliGained = 1;
        }
        playerService.setCultivating(p.getId(), false);

        HeartDemonService.HeartDemonResult hdResult = heartDemonService.processCultivation(
                p.getId(), lingliGained, elapsedSeconds);
        long finalExp;
        if (hdResult.triggered) {
            finalExp = hdResult.netExpChange;
        } else {
            finalExp = lingliGained;
        }
        playerService.addExperience(p.getId(), finalExp);

        int elapsedMinutes = elapsedSeconds / 60;
        int remainingSeconds = elapsedSeconds % 60;
        String timeStr = elapsedMinutes > 0
                ? elapsedMinutes + " 分 " + remainingSeconds + " 秒"
                : elapsedSeconds + " 秒";

        actionLog.logCultivateStop(userId, p.getName(), finalExp, elapsedSeconds);

        PlayerInfo updated = playerService.getPlayerByUserId(userId);
        String msg = "闭关结束！\n闭关时长: " + timeStr + "\n获得灵力: " + finalExp;
        if (hdResult.triggered) {
            msg += "\n\n⚠️ 修炼原得 " + lingliGained + " 灵力，但";
            msg += "\n" + hdResult.narrative;
            msg += "\n损失灵力: " + hdResult.expLost + " (心魔劫:" + hdResult.severity + ")";
        }
        msg += "\n\n" + formatPlayerStatus(updated);
        NewbieGuideService.GuideResult guide = guideService.checkAndAdvance((int) updated.getId(), updated, "cultivate_stop");
        if (guide.message != null) msg += "\n\n💡 " + guide.message;

        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    private void handleExplore(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.explore")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        ExplorationResult result = explorationService.explore(userId);
        actionLog.logExploration(userId, p.getName(),
                result.getEventType() != null ? result.getEventType() : "未知", result.getMessage());

        StringBuilder sb = new StringBuilder();
        if (result.isSuccess()) {
            sb.append("===== 游历探索 =====\n");
            if (result.getLog() != null) {
                for (String l : result.getLog()) {
                    sb.append(l).append("\n");
                }
            }
            sb.append("\n").append(result.getMessage());
            if (result.getExpGained() > 0) sb.append("\n灵力: +").append(result.getExpGained());
            if (result.getGoldGained() > 0) sb.append("\n金币: +").append(result.getGoldGained());
            if (result.getSpiritStonesGained() > 0) sb.append("\n灵石: +").append(result.getSpiritStonesGained());
            if (result.getItemGained() != null) sb.append("\n获得物品: ").append(itemName(result.getItemGained())).append(" x").append(result.getItemQuantity());
            if (result.getHpLost() > 0) sb.append("\n损失生命: -").append(result.getHpLost());
        } else {
            sb.append(result.getMessage());
        }

        NewbieGuideService.GuideResult guide = guideService.checkAndAdvance((int) p.getId(), p, "explore");
        if (guide.message != null) sb.append("\n\n💡 ").append(guide.message);

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    private void handleBreakthrough(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.realm.breakthrough")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        RealmBreakthroughResult result = realmService.tryBreakthrough(userId);
        actionLog.logBreakthrough(userId, p.getName(), result.isSuccess(), result.getMessage());

        StringBuilder sb = new StringBuilder();
        sb.append("===== 境界突破 =====\n");
        if (result.isSuccess()) {
            sb.append(result.getMessage()).append("\n");
            sb.append("生命上限: +").append(result.getHpAdded()).append("\n");
            sb.append("法力上限: +").append(result.getMpAdded()).append("\n");
            sb.append("攻击力: +").append(result.getAttackAdded()).append("\n");
            sb.append("防御力: +").append(result.getDefenseAdded()).append("\n");
            sb.append("速度: +").append(result.getSpeedAdded()).append("\n");
            sb.append("神识: +").append(result.getSpiritAdded());

            PlayerInfo updated = playerService.getPlayerByUserId(userId);
            sb.append("\n\n").append(formatPlayerStatus(updated));
            NewbieGuideService.GuideResult guide = guideService.checkAndAdvance((int) updated.getId(), updated, "breakthrough");
            if (guide.message != null) sb.append("\n\n💡 ").append(guide.message);
        } else {
            sb.append(result.getMessage());
        }

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    private void handleBackpack(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.inventory.view")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        List<ItemService.InventoryEntry> inventory = itemService.getInventory((int) p.getId());

        if (inventory.isEmpty()) {
            String discTip = guideService.checkDiscovery((int) p.getId(), p, "backpack");
            String msg = "===== 背包 =====\n空空如也...";
            if (discTip != null) msg += "\n" + discTip;
            sendReply(socket, selfId, senderQq, groupId, msg);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 背包 =====\n");
        for (ItemService.InventoryEntry entry : inventory) {
            Item item = entry.getItem();
            sb.append(item.getName()).append(" x").append(entry.getQuantity()).append("\n");
            sb.append("  ").append(item.getDescription()).append("\n");
        }

        String discTip = guideService.checkDiscovery((int) p.getId(), p, "backpack");
        if (discTip != null) sb.append("\n").append(discTip);

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    private void handleItemUse(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.item.use")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        String itemKey = arg.trim();
        if (itemKey.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "用法: /itemuse <物品名称或key>\n可在 /backpack 中查看你的物品\n例如: /使用 回血丹");
            return;
        }

        Map<String, Object> useResult = itemService.useItem((int) p.getId(), itemKey);
        boolean success = (boolean) useResult.getOrDefault("success", false);
        String msg = (String) useResult.getOrDefault("message", "");
        actionLog.logItemUse(userId, p.getName(), itemKey, success, msg);

        String discTip = guideService.checkDiscovery((int) p.getId(), p, "item_use");
        if (discTip != null) msg += "\n\n" + discTip;

        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    private void handleItemMap(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Collection<Item> allItems = ItemRegistry.getAll();
        if (allItems.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "物品列表为空。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 物品映射表 =====\n");
        sb.append("中文名  →  key（用于 /itemuse /list /equip 等指令）\n");
        sb.append("--------------------------------\n");

        for (Item item : allItems) {
            String translatedName = LangManager.get(item.getNameKey());
            String displayName = (translatedName != null && !translatedName.isEmpty())
                    ? translatedName : item.getFullKey();
            sb.append(displayName).append("  →  ").append(item.getFullKey()).append("\n");
        }

        sb.append("--------------------------------\n");
        sb.append("共 ").append(allItems.size()).append(" 个物品\n");
        sb.append("使用 /itemuse <物品名称或key> 来使用物品");

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    private void handleSecretAreas(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.secret_realm")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        List<SecretRealm> areas = secretRealmService.getAvailableAreas(userId);

        if (areas.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "当前没有可用的秘境。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 可用秘境 =====\n");
        for (SecretRealm area : areas) {
            sb.append("【").append(area.getName()).append("】\n");
            sb.append("  所需境界: ").append(realmName(area.getRequiredRealm())).append("\n");
            sb.append("  冷却: ").append(area.getCooldownMs() / 1000).append(" 秒\n");
            sb.append("  ").append(area.getDescription()).append("\n\n");
        }
        sb.append("使用 /secret_enter <秘境名称> 进入");

        NewbieGuideService.GuideResult guide = guideService.checkAndAdvance((int) p.getId(), p, "secret_areas");
        if (guide.message != null) sb.append("\n\n💡 ").append(guide.message);

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    private void handleSecretEnter(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.secret_realm")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        String areaName = arg.trim();
        if (areaName.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "用法: /secret_enter <秘境名称>\n使用 /secret 查看可用秘境");
            return;
        }

        SecretRealmResult result = secretRealmService.enterSecretRealm(userId, areaName);
        actionLog.logSecretRealmEnter(userId, p.getName(), areaName, result.isSuccess(), result.getMessage());

        StringBuilder sb = new StringBuilder();
        sb.append("===== 秘境探索 =====\n");
        if (result.isSuccess()) {
            if (result.getLog() != null) {
                for (String l : result.getLog()) {
                    sb.append(l).append("\n");
                }
            }
            sb.append("\n").append(result.getMessage());
            if (result.getExpGained() > 0) sb.append("\n灵力: +").append(result.getExpGained());
            if (result.getGoldGained() > 0) sb.append("\n金币: +").append(result.getGoldGained());
            if (result.getSpiritStonesGained() > 0) sb.append("\n灵石: +").append(result.getSpiritStonesGained());
            if (result.getItemGained() != null) sb.append("\n获得物品: ").append(itemName(result.getItemGained())).append(" x").append(result.getItemQuantity());
            if (result.getHpLost() > 0) sb.append("\n损失生命: -").append(result.getHpLost());
        } else {
            sb.append(result.getMessage());
        }

        NewbieGuideService.GuideResult guide = guideService.checkAndAdvance((int) p.getId(), p, "secret_enter");
        if (guide.message != null) sb.append("\n\n💡 ").append(guide.message);

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    private void handleSkills(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        List<Skill> allSkills = skillService.getAllSkills();
        if (allSkills.isEmpty()) {
            String discTip = guideService.checkDiscovery((int) p.getId(), p, "skills");
            String msg = "暂无可用技能。";
            if (discTip != null) msg += "\n\n" + discTip;
            sendReply(socket, selfId, senderQq, groupId, msg);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 技能列表 =====");
        for (Skill s : allSkills) {
            sb.append("\n[ID:").append(s.getId()).append("] ").append(s.getName());
            if (s.getDamage() > 0) {
                sb.append("  伤害:").append(s.getDamage());
            }
            if (s.getHealAmount() > 0) {
                sb.append("  治疗:").append(s.getHealAmount());
            }
            sb.append("\n  境界要求: ").append(realmName(s.getRequiredRealm()));
            if (s.getLearnCostGold() > 0) sb.append("  金币:").append(s.getLearnCostGold());
            if (s.getLearnCostSpiritStones() > 0) sb.append("  灵石:").append(s.getLearnCostSpiritStones());
            if (s.getMpCost() > 0) sb.append("  消耗法力:").append(s.getMpCost());
            if (!s.getDescription().isEmpty()) {
                sb.append("\n  ").append(s.getDescription());
            }
        }
        sb.append("\n\n使用 /学习 <技能ID> 来学习技能");

        String discTip = guideService.checkDiscovery((int) p.getId(), p, "skills");
        if (discTip != null) sb.append("\n\n").append(discTip);

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    private void handleLearnSkill(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.skill.learn")) return;

        if (arg == null || arg.trim().isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "用法: /学习 <技能ID>\n先用 /技能 查看可用技能列表。");
            return;
        }

        long skillId;
        try {
            skillId = Long.parseLong(arg.trim());
        } catch (NumberFormatException e) {
            sendReply(socket, selfId, senderQq, groupId, "技能ID必须是数字，请使用 /技能 查看技能列表。");
            return;
        }

        Map<String, Object> learnResult = skillService.learnSkill(p.getId(), skillId);
        boolean success = (boolean) learnResult.getOrDefault("success", false);
        String message = (String) learnResult.getOrDefault("message", "");

        if (success) {
            Skill skill = (Skill) learnResult.get("skill");
            String skillName = skill != null ? skill.getName() : "技能ID:" + skillId;
            sendReply(socket, selfId, senderQq, groupId, "学习成功！\n学会了【" + skillName + "】！");
        } else {
            sendReply(socket, selfId, senderQq, groupId, "学习失败: " + message);
        }
    }

    // ==================== 战斗与装备 ====================

    private void handlePvp(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.pvp.challenge")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        String targetName = arg.trim();
        if (targetName.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "用法: /挑战 <角色名>\n或 /pvp <角色名>");
            return;
        }

        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "找不到玩家: " + targetName);
            return;
        }
        PlayerInfo target = targets.get(0);

        if (target.getId() == p.getId()) {
            sendReply(socket, selfId, senderQq, groupId, "不能挑战自己！修行之路需要真正的对手。");
            return;
        }

        CombatService.CombatResult result = combatService.pvpChallenge(p.getId(), target.getId());
        actionLog.logCombat(userId, p.getName(), "PVP", target.getName(), result.isChallengerWon(), result.getMessage());

        StringBuilder sb = new StringBuilder();
        sb.append("===== 修士对决 =====\n");
        if (result.isSuccess()) {
            for (String log : result.getBattleLog()) {
                sb.append(log).append("\n");
            }
            sb.append("\n");
            if (result.isChallengerWon()) {
                sb.append("你击败了【").append(result.getTargetName()).append("】！");
                if (result.getExpReward() > 0) sb.append("\n灵力: +").append(result.getExpReward());
                if (result.getGoldReward() > 0) sb.append("\n金币: +").append(result.getGoldReward());
            } else {
                sb.append("你被【").append(result.getTargetName()).append("】击败了……");
            }
            sb.append("\n回合数: ").append(result.getTotalRounds());
        } else {
            sb.append(result.getMessage());
        }

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    private void handleEquip(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.equipment.equip")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        String[] parts = arg.split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            sendReply(socket, selfId, senderQq, groupId,
                    "用法: /装备 <物品key> <部位>\n部位: weapon(武器) | armor(防具) | accessory(饰品)\n物品key 可在 /背包 中查看");
            return;
        }

        String itemKey = parts[0].trim();
        String slot = parts[1].trim().toLowerCase();

        var result = itemService.equipItem((int) p.getId(), itemKey, slot);
        String msg = (String) result.getOrDefault("message", "");
        if (msg.isEmpty()) msg = "装备失败，请检查物品key和部位是否正确。";

        String discTip = guideService.checkDiscovery((int) p.getId(), p, "equip");
        if (discTip != null) msg += "\n\n" + discTip;

        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    private void handleUnequip(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.equipment.equip")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        String slot = arg.trim().toLowerCase();
        if (slot.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId,
                    "用法: /卸下 <部位>\n部位: weapon | armor | accessory");
            return;
        }

        var result = itemService.unequipItem((int) p.getId(), slot);
        String msg = (String) result.getOrDefault("message", "");
        if (msg.isEmpty()) msg = "卸下失败，请检查部位是否正确。";

        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    private void handleEquipped(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        Map<String, String> equipment = itemService.getEquipment((int) p.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("===== 已装备 =====\n");
        boolean hasAny = false;
        String[] slots = {"weapon", "armor", "accessory"};
        String[] labels = {"武器", "防具", "饰品"};
        for (int i = 0; i < slots.length; i++) {
            String itemKey = equipment.get(slots[i]);
            if (itemKey != null) {
                Item item = ItemRegistry.get(itemKey);
                sb.append(labels[i]).append(": ").append(item != null ? item.getName() : itemKey).append("\n");
                hasAny = true;
            } else {
                sb.append(labels[i]).append(": (空)\n");
            }
        }
        if (!hasAny) sb.append("暂无装备，在秘境中探索获取吧！");

        String discTip = guideService.checkDiscovery((int) p.getId(), p, "equip");
        if (discTip != null) sb.append("\n\n").append(discTip);

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    // ==================== 坊市 ====================

    private void handleMarket(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        var listings = tradeService.getActiveListings();
        if (listings.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "===== 坊市 =====\n坊市上空空荡荡，还没有人挂单。\n使用 /上架 <物品key> <数量> <灵石价格> 来卖东西吧！");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 坊市 =====\n");
        int shown = 0;
        for (var l : listings) {
            if (shown >= 15) { sb.append("\n...(仅显示前15条，更多请用Web客户端查看)"); break; }
            Item item = ItemRegistry.get(l.itemKey);
            String itemName = item != null ? item.getName() : l.itemKey;
            String sellerName = "未知";
            var seller = playerService.getPlayerById(l.sellerPlayerId);
            if (seller != null) sellerName = seller.getName();
            sb.append("[").append(l.id).append("] ").append(itemName).append(" x").append(l.quantity);
            sb.append("  售价:").append(l.priceSpiritStones).append("灵石");
            sb.append("  卖家:").append(sellerName);
            sb.append("\n");
            shown++;
        }
        String discTip = guideService.checkDiscovery((int) p.getId(), p, "market");
        if (discTip != null) sb.append("\n").append(discTip);

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    // ==================== 每日晨修 ====================

    private void handleMorning(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        var result = dailyService.doMorningCultivation(p.getId());
        String msg = (String) result.getOrDefault("message", "晨修出现异常");

        if (Boolean.TRUE.equals(result.get("success"))) {
            long exp = ((Number) result.getOrDefault("expGained", 0)).longValue();
            long ss = ((Number) result.getOrDefault("spiritStonesGained", 0)).longValue();
            int consecutive = ((Number) result.getOrDefault("consecutiveDays", 1)).intValue();
            String phenom = (String) result.getOrDefault("phenomenon", "");
            msg += "\n灵力 +" + exp + "  灵石 +" + ss;
            if (consecutive > 1) msg += "\n连续晨修 " + consecutive + " 天！";
            if (!phenom.isEmpty()) msg += "\n今日天象: " + phenom;
        }

        NewbieGuideService.GuideResult guide = guideService.checkAndAdvance((int) p.getId(), p, "morning");
        if (guide.message != null) msg += "\n\n💡 " + guide.message;

        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    // ==================== 每日天象 ====================

    private void handleDaily(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        var result = dailyService.getDailyInfo(p.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("===== 今日天象 =====\n");
        sb.append("天象: ").append(result.get("phenomenon")).append("\n");
        sb.append(result.get("phenomenonDesc")).append("\n\n");
        sb.append("修炼加成: ").append(result.get("cultivationBonus")).append("\n");
        sb.append("游历加成: ").append(result.get("explorationBonus")).append("\n");
        sb.append("灵石加成: ").append(result.get("spiritStoneBonus")).append("\n\n");

        boolean doneMorning = Boolean.TRUE.equals(result.get("morningCultivationDone"));
        sb.append("晨修: ").append(doneMorning ? "✅ 已完成" : "❌ 未完成").append("\n");
        sb.append("连续活跃: ").append(result.get("consecutiveDays")).append("天");
        sb.append("  累计活跃: ").append(result.get("totalActiveDays")).append("天");

        String discTip = guideService.checkDiscovery((int) p.getId(), p, "morning_tip");
        if (discTip != null) sb.append("\n\n").append(discTip);

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    // ==================== 坊市交易 ====================

    private void handleListItem(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        String[] parts = arg.trim().split("\\s+", 3);
        if (parts.length < 3 || parts[0].isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId,
                    "用法: /list <物品key> <数量> <灵石价格>\n物品key可在 /backpack 中查看\n例如: /list spirit_grass 5 100");
            return;
        }

        String itemKey = parts[0];
        int quantity, price;
        try {
            quantity = Integer.parseInt(parts[1]);
            price = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            sendReply(socket, selfId, senderQq, groupId, "数量和价格必须是整数");
            return;
        }

        Map<String, Object> result = tradeService.listItem(p.getId(), itemKey, quantity, price);
        String msg = (String) result.getOrDefault("message", "上架失败");
        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    private void handleBuyItem(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        if (arg.trim().isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId,
                    "用法: /buy <挂单ID>\n先使用 /market 查看坊市中的挂单ID\n例如: /buy 5");
            return;
        }

        long listingId;
        try {
            listingId = Long.parseLong(arg.trim());
        } catch (NumberFormatException e) {
            sendReply(socket, selfId, senderQq, groupId, "挂单ID必须是数字，请使用 /market 查看");
            return;
        }

        Map<String, Object> result = tradeService.buyItem(p.getId(), listingId);
        String msg = (String) result.getOrDefault("message", "购买失败");
        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    private void handleCancelListing(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        if (arg.trim().isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId,
                    "用法: /cancel <挂单ID>\n使用 /mylistings 查看你的挂单\n例如: /cancel 3");
            return;
        }

        long listingId;
        try {
            listingId = Long.parseLong(arg.trim());
        } catch (NumberFormatException e) {
            sendReply(socket, selfId, senderQq, groupId, "挂单ID必须是数字");
            return;
        }

        Map<String, Object> result = tradeService.cancelListing(p.getId(), listingId);
        String msg = (String) result.getOrDefault("message", "撤单失败");
        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    private void handleMyListings(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        var listings = tradeService.getPlayerListings(p.getId());
        if (listings.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "===== 我的挂单 =====\n你还没有在坊市挂单。\n使用 /list <key> <数量> <灵石> 上架吧！");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 我的挂单 =====\n");
        for (var l : listings) {
            Item item = ItemRegistry.get(l.itemKey);
            String itemName = item != null ? item.getName() : l.itemKey;
            sb.append("[").append(l.id).append("] ").append(itemName).append(" x").append(l.quantity);
            sb.append("  售价:").append(l.priceSpiritStones).append("灵石");
            sb.append("  手续费:").append(l.fee).append("灵石");
            sb.append("\n");
        }
        sb.append("使用 /cancel <ID> 撤单");

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    // ==================== 超级管理员指令 ====================

    private void handleClearPlayersDb(WebSocket socket, String selfId, String senderQq, Long groupId) {
        if (!requireQqPermission(socket, selfId, senderQq, groupId, "admin.database.clear_players")) return;

        try {
            Map<String, Integer> counts = DatabaseManager.clearPlayerData();
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();

            StringBuilder sb = new StringBuilder();
            sb.append("===== 清除玩家数据 =====\n");
            sb.append("共删除 ").append(total).append(" 条记录:\n");
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > 0) {
                    sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append(" 条\n");
                }
            }
            sb.append("\n玩家数据已全部清除。");
            sendReply(socket, selfId, senderQq, groupId, sb.toString());
        } catch (Exception e) {
            sendReply(socket, selfId, senderQq, groupId, "清除失败: " + e.getMessage());
        }
    }

    private void handleResetAllDb(WebSocket socket, String selfId, String senderQq, Long groupId) {
        if (!requireQqPermission(socket, selfId, senderQq, groupId, "admin.database.reset_all")) return;

        try {
            Map<String, Integer> counts = DatabaseManager.resetAllData();
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();

            StringBuilder sb = new StringBuilder();
            sb.append("===== 重置全部数据 =====\n");
            sb.append("共删除 ").append(total).append(" 条记录:\n");
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > 0) {
                    sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append(" 条\n");
                }
            }
            sb.append("\n全部数据已重置，默认角色权限已重新初始化。");
            sendReply(socket, selfId, senderQq, groupId, sb.toString());
        } catch (Exception e) {
            sendReply(socket, selfId, senderQq, groupId, "重置失败: " + e.getMessage());
        }
    }

    // ==================== 格式化 ====================

    private String formatPlayerStatus(PlayerInfo p) {
        SpiritualRoot root = p.getSpiritualRoot();
        String rootStr = root != null
                ? "【" + root.getDisplayName() + "】" + root.getTier().getDisplayName()
                        + " | " + root.getDescription()
                : "无";
        return "【" + p.getName() + "】" +
                "\n灵根: " + rootStr +
                "\n境界: " + p.getRealmName() + " (Lv." + p.getLevel() + ")" +
                "\n灵力: " + p.getExperience() +
                "\n生命: " + p.getHp() + "/" + p.getMaxHp() +
                "  法力: " + p.getMp() + "/" + p.getMaxMp() +
                "\n攻击: " + p.getAttack() +
                "  防御: " + p.getDefense() +
                "  速度: " + p.getSpeed() +
                "  神识: " + p.getSpirit() +
                "\n金币: " + p.getGold() +
                "  灵石: " + itemService.getSpiritStoneCount(p.getId()) +
                (p.isCultivating() ? "\n状态: 闭关中" : "");
    }

    private static String realmName(int realmId) {
        var cfg = GameConfigLoader.getRealmConfig(realmId, 0);
        return cfg != null ? cfg.getName() : "境界" + realmId;
    }

    private static String itemName(String itemKey) {
        Item item = ItemRegistry.get(itemKey);
        return item != null ? item.getName() : itemKey;
    }

    // ==================== 工具方法 ====================

    private boolean isCommand(String msg) {
        return msg.startsWith("/") || msg.startsWith(".") || msg.startsWith("！") || msg.startsWith("!");
    }

    private String[] parseCommand(String raw) {
        String cmdLine = raw;
        if (cmdLine.startsWith(".")) cmdLine = "/" + cmdLine.substring(1);
        if (cmdLine.startsWith("！")) cmdLine = "/" + cmdLine.substring(1);
        if (cmdLine.startsWith("!")) cmdLine = "/" + cmdLine.substring(1);

        String[] parts = cmdLine.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";
        return new String[]{cmd, arg};
    }

    private Long verifyUserCredentials(String username, String password) {
        String sql = "SELECT id, password FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && BCrypt.checkpw(password, rs.getString("password"))) {
                    return rs.getLong("id");
                }
            }
        } catch (SQLException e) {
            log.error("验证用户凭据失败", e);
        }
        return null;
    }

    private boolean isUsernameExists(String username) {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("查询用户名失败", e);
        }
        return false;
    }

    private Long insertUser(String username, String hashedPassword) {
        String sql = "INSERT INTO users (username, password) VALUES (?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.setString(2, hashedPassword);
            if (stmt.executeUpdate() > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("用户插入失败", e);
        }
        return null;
    }

    // ==================== 聊天指令 ====================

    private void handlePrivateMessageCmd(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.chat.private")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        String[] parts = arg.split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            sendReply(socket, selfId, senderQq, groupId, "用法: /msg <玩家名> <消息内容>\n或 /私聊 <玩家名> <消息内容>");
            return;
        }

        String targetName = parts[0].trim();
        String content = parts[1].trim();

        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "找不到玩家: " + targetName);
            return;
        }
        PlayerInfo target = targets.get(0);

        if (target.getId() == p.getId()) {
            sendReply(socket, selfId, senderQq, groupId, "不能给自己发私聊消息。");
            return;
        }

        ChatMessage msg = chatService.sendPrivateMessage(p.getId(), p.getName(), target.getId(), content);
        actionLog.logChat(userId, p.getName(), "[私聊→" + target.getName() + "] " + content);
        sendReply(socket, selfId, senderQq, groupId, "私聊消息已发送给 " + target.getName());
    }

    // ==================== 排行榜指令 ====================

    private void handleRankCmd(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.rank.view")) return;

        String type = arg.trim().toLowerCase();
        List<PlayerInfo> players;
        String title;
        switch (type) {
            case "power", "战力":
                players = playerService.getTopByPower(10);
                title = "战力排行榜";
                break;
            case "wealth", "财富":
                players = playerService.getTopByWealth(10);
                title = "财富排行榜";
                break;
            default:
                players = playerService.getTopByRealm(10);
                title = "境界排行榜";
                break;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== ").append(title).append(" =====\n");
        sb.append("排名  玩家              境界\n");

        int rank = 1;
        for (PlayerInfo p : players) {
            String realmName = p.getRealmName() != null ? p.getRealmName() : "凡人";
            String name = p.getName();
            if (name.length() < 8) {
                name = name + "                ".substring(0, 8 - name.length());
            }
            sb.append(String.format("%-5d %-18s %s\n", rank++, name, realmName));
        }

        if (type.equals("power")) {
            sb.append("\n---\n");
            sb.append("战力 = 攻击 + 防御 + 速度 + 生命上限");
        } else if (type.equals("wealth")) {
            sb.append("\n---\n");
            sb.append("财富 = 金币 + 灵石");
        }

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    // ==================== 好友指令 ====================

    private void handleFriendCmd(WebSocket socket, String selfId, String senderQq, String arg, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;
        if (!checkQqPermission(socket, selfId, senderQq, groupId, "game.friend.manage")) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        String[] parts = arg.split("\\s+", 2);
        String subCmd = parts.length > 0 ? parts[0].trim().toLowerCase() : "";
        String subArg = parts.length > 1 ? parts[1].trim() : "";

        switch (subCmd) {
            case "add", "添加":
                handleFriendAddCmd(socket, selfId, senderQq, groupId, p, subArg);
                break;
            case "accept", "接受":
                handleFriendAcceptCmd(socket, selfId, senderQq, groupId, p, subArg);
                break;
            case "remove", "删除":
                handleFriendRemoveCmd(socket, selfId, senderQq, groupId, p, subArg);
                break;
            case "list", "列表":
                handleFriendListCmd(socket, selfId, senderQq, groupId, p);
                break;
            default:
                sendReply(socket, selfId, senderQq, groupId,
                        "用法:\n" +
                        "/好友 add <玩家名>   发送好友申请\n" +
                        "/好友 accept <玩家名> 接受好友申请\n" +
                        "/好友 remove <玩家名> 删除好友\n" +
                        "/好友 list           查看好友列表");
                break;
        }
    }

    private void handleFriendAddCmd(WebSocket socket, String selfId, String senderQq, Long groupId, PlayerInfo p, String targetName) {
        if (targetName.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "用法: /好友 add <玩家名>");
            return;
        }

        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "找不到玩家: " + targetName);
            return;
        }
        PlayerInfo target = targets.get(0);

        if (target.getId() == p.getId()) {
            sendReply(socket, selfId, senderQq, groupId, "不能添加自己为好友。");
            return;
        }

        Friend result = friendService.sendRequest(p.getId(), target.getId());
        if (result == null) {
            sendReply(socket, selfId, senderQq, groupId, "发送好友申请失败。");
            return;
        }
        if ("exists".equals(result.getStatus())) {
            sendReply(socket, selfId, senderQq, groupId, "已经是好友或已发送过申请。");
            return;
        }

        sendReply(socket, selfId, senderQq, groupId, "好友申请已发送给 " + target.getName());
    }

    private void handleFriendAcceptCmd(WebSocket socket, String selfId, String senderQq, Long groupId, PlayerInfo p, String targetName) {
        if (targetName.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "用法: /好友 accept <玩家名>");
            return;
        }

        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "找不到玩家: " + targetName);
            return;
        }
        PlayerInfo target = targets.get(0);

        boolean success = friendService.acceptRequest(p.getId(), target.getId());
        if (!success) {
            sendReply(socket, selfId, senderQq, groupId, "没有来自 " + target.getName() + " 的好友申请。");
            return;
        }

        sendReply(socket, selfId, senderQq, groupId, "已与 " + target.getName() + " 结为好友！");
    }

    private void handleFriendRemoveCmd(WebSocket socket, String selfId, String senderQq, Long groupId, PlayerInfo p, String targetName) {
        if (targetName.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "用法: /好友 remove <玩家名>");
            return;
        }

        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "找不到玩家: " + targetName);
            return;
        }
        PlayerInfo target = targets.get(0);

        boolean success = friendService.removeFriend(p.getId(), target.getId());
        if (!success) {
            sendReply(socket, selfId, senderQq, groupId, target.getName() + " 不是你的好友。");
            return;
        }

        sendReply(socket, selfId, senderQq, groupId, "已删除好友 " + target.getName());
    }

    private void handleFriendListCmd(WebSocket socket, String selfId, String senderQq, Long groupId, PlayerInfo p) {
        List<Friend> friends = friendService.getFriends(p.getId());
        if (friends.isEmpty()) {
            sendReply(socket, selfId, senderQq, groupId, "你还没有好友。\n使用 /好友 add <玩家名> 发送好友申请");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 好友列表 =====\n");
        for (Friend f : friends) {
            String name = f.getFriendName() != null ? f.getFriendName() : "未知";
            String realm = f.getFriendRealm() != null ? f.getFriendRealm() : "未知";
            sb.append(name).append("  ").append(realm).append("\n");
        }
        sb.append("共 ").append(friends.size()).append(" 位好友");

        sendReply(socket, selfId, senderQq, groupId, sb.toString());
    }

    // ==================== 疗伤指令 ====================

    private void handleHealCmd(WebSocket socket, String selfId, String senderQq, Long groupId) {
        Long userId = requireBinding(socket, selfId, senderQq, groupId);
        if (userId == null) return;

        PlayerInfo p = requirePlayer(socket, selfId, senderQq, userId, groupId);
        if (p == null) return;

        if (p.getHp() >= p.getMaxHp()) {
            sendReply(socket, selfId, senderQq, groupId, "你的生命值已满，无需治疗。");
            return;
        }

        Map<String, Object> result = playerService.healPlayer(p.getId());
        String msg = (String) result.getOrDefault("message", "疗伤失败");
        sendReply(socket, selfId, senderQq, groupId, msg);
    }

    // ==================== API 发送 ====================

    private void sendPrivateMsg(WebSocket socket, String selfId, String targetQq, String message) {
        JsonObject api = new JsonObject();
        api.addProperty("action", "send_private_msg");
        JsonObject params = new JsonObject();
        params.addProperty("user_id", targetQq);
        params.addProperty("message", message);
        api.add("params", params);
        api.addProperty("echo", UUID.randomUUID().toString());

        String jsonStr = gson.toJson(api);
        botLog.logSend(targetQq, jsonStr);
        socket.send(jsonStr);
    }

    private void sendGroupMsg(WebSocket socket, String selfId, Long groupId, String message) {
        JsonObject api = new JsonObject();
        api.addProperty("action", "send_group_msg");
        JsonObject params = new JsonObject();
        params.addProperty("group_id", groupId);
        params.addProperty("message", message);
        api.add("params", params);
        api.addProperty("echo", UUID.randomUUID().toString());

        String jsonStr = gson.toJson(api);
        botLog.logSendToGroup(groupId, jsonStr);
        socket.send(jsonStr);
    }
}
