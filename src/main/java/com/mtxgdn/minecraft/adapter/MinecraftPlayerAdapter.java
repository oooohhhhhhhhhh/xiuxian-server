package com.mtxgdn.minecraft.adapter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mtxgdn.adapter.AdapterConfig;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.RateLimiter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft 玩家适配器 —— 以玩家身份（仅离线模式）登录 Minecraft 服务器。
 * <p>
 * 自动识别 /tell、/msg 等私聊格式的消息，等同于 OneBot 中的私聊环境。
 * 玩家通过私聊与该适配器交互，执行修仙指令。
 * <p>
 * 绑定：使用服务器返回的 UUID 作为绑定凭证，获取不到则降级为离线 UUID（用户名）。
 * 登录：自动检测 auth 插件，首次使用 /reg、后续使用 /l 自动登录。
 */
public class MinecraftPlayerAdapter implements MinecraftMessageSender {

    private static final GameLogger log = GameLogger.getLogger("McPlayerAdapter");
    private static final Gson gson = new Gson();

    // ---- 配置 ----
    private final AdapterConfig config;
    private final String adapterId;
    private final String serverHost;
    private final int serverPort;
    private final String botUsername;
    private final String botPassword;
    private final int protocolVersion;
    private final String commandPrefix;
    private final long loginDelayMs;

    // ---- 组件 ----
    private MinecraftClient client;
    private final MinecraftAuthService authService = new MinecraftAuthService();
    private final MinecraftPlayerBindingService bindingService = ServiceRegistry.getMcBindingService();

    // ---- 状态 ----
    private final Map<String, AuthPendingSession> pendingSessions = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private String botUuid;
    private String botEffectiveName;

    // ---- 首次登录记录 ----
    private final Path firstLoginFile;
    private Map<String, ServerLoginRecord> loginRecords;

    public MinecraftPlayerAdapter(AdapterConfig config) {
        this.config = config;
        this.adapterId = config.getId();
        this.serverHost = config.getString("host", "127.0.0.1");
        this.serverPort = config.getInt("port", 25565);
        this.botUsername = config.getString("username", "XiuXianBot");
        this.botPassword = config.getString("password", "");
        // 0/-1 = 用 Status Ping 自动检测。默认必须是「自动」：写死一个旧协议号时，
        // 服务端在登录阶段就会以「版本过旧」直接拒绝，表现为莫名其妙连不上。
        this.protocolVersion = config.getInt("protocol_version", 0);
        this.commandPrefix = config.getString("command_prefix", "");
        this.loginDelayMs = config.getInt("login_delay_ms", 3000);

        // 首次登录记录文件: config/mc-player-logins.json
        Path configDir = Paths.get("config");
        try {
            Files.createDirectories(configDir);
        } catch (IOException ignored) {
        }
        this.firstLoginFile = configDir.resolve("mc-player-logins.json");
        loadLoginRecords();
    }

    // ==================== 生命周期 ====================

    public boolean start() {
        if (botPassword == null || botPassword.isEmpty()) {
            log.warn("[" + adapterId + "] 未配置 password，跳过启动。");
            return false;
        }

        log.info("[" + adapterId + "] 正在连接 " + serverHost + ":" + serverPort
                + " (用户名: " + botUsername + ")");

        client = new MinecraftClient(serverHost, serverPort, botUsername, protocolVersion);
        setupClientCallbacks();

        if (!client.connect()) {
            log.error("[" + adapterId + "] 连接失败");
            return false;
        }

        if (!client.login()) {
            log.error("[" + adapterId + "] 登录失败");
            return false;
        }

        // 1.20.2+ 登录成功后还要过一遍配置阶段（注册表同步等），只有真正进入 PLAY
        // 状态才能发指令。配置阶段里发指令包会被服务端按配置阶段的包号解析而直接断连。
        if (!client.awaitPlayReady(15000)) {
            log.warn("[" + adapterId + "] 等待进入 PLAY 状态超时，仍继续尝试后续操作");
        }

        // 登录成功后延迟一段时间，让服务器消息到达
        try {
            Thread.sleep(loginDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 自动注册/登录
        performAutoAuth();

        running = true;
        log.info("[" + adapterId + "] Minecraft 玩家适配器已启动 (UUID: " + botUuid + ")");
        return true;
    }

    public void stop() {
        running = false;
        if (client != null) {
            client.disconnect();
        }
        pendingSessions.clear();
        log.info("[" + adapterId + "] Minecraft 玩家适配器已停止");
    }

    public boolean isRunning() {
        return running && client != null && client.isRunning();
    }

    public String getBotUuid() {
        return botUuid;
    }

    public String getBotName() {
        return botEffectiveName != null ? botEffectiveName : botUsername;
    }

    // ==================== 自动注册/登录 ====================

    private void performAutoAuth() {
        String serverKey = serverHost + ":" + serverPort;
        ServerLoginRecord record = loginRecords.get(serverKey);
        boolean isFirst = (record == null || record.firstLogin);

        // 必须走「指令包」而不是聊天包：服务端的 handleChat 只会把内容广播出去，
        // 永远不会当成指令执行；而指令包里的字符串不带前导 "/"（真实客户端是
        // ChatScreen 里 sendCommand(msg.substring(1))）。
        if (isFirst) {
            log.info("[" + adapterId + "] 首次登录，发送注册指令: /reg ****");
            client.sendCommand("reg " + botPassword);
        } else {
            log.info("[" + adapterId + "] 非首次登录，发送登录指令: /l ****");
            client.sendCommand("l " + botPassword);
        }

        // 记录为已登录
        if (record == null) {
            record = new ServerLoginRecord();
        }
        record.firstLogin = false;
        record.lastLogin = System.currentTimeMillis();
        loginRecords.put(serverKey, record);
        saveLoginRecords();
    }

    // ==================== 客户端回调 ====================

    private void setupClientCallbacks() {
        client.setOnLoginSuccess(uuid -> {
            botUuid = uuid;
            botEffectiveName = client.getPlayerName();
            if (botEffectiveName == null) {
                botEffectiveName = botUsername;
            }
            log.info("[" + adapterId + "] 登录成功: " + botEffectiveName + " UUID: " + botUuid);
        });

        client.setOnKick(reason -> {
            log.warn("[" + adapterId + "] 被踢出: " + reason);
        });

        client.setOnTellMessage((sender, message) -> {
            handleTellMessage(sender, message);
        });

        client.setOnChatMessage((sender, message) -> {
            // 公屏消息忽略（只处理私聊）
            log.debug("[" + adapterId + "] 公屏: " + (sender != null ? sender + ": " : "") + com.mtxgdn.util.LogMasker.mask(message));
        });

        client.setOnDisconnected(reason -> {
            running = false;
            log.info("[" + adapterId + "] 连接断开");
        });
    }

    // ==================== 私聊消息处理 ====================

    private void handleTellMessage(String senderName, String message) {
        if (!running) return;

        String cleanMsg = MinecraftClient.stripColor(message).trim();
        if (cleanMsg.isEmpty()) return;

        String playerName = MinecraftClient.stripColor(senderName).trim();
        log.info("[" + adapterId + "] 私聊 " + playerName + ": " + cleanMsg);

        // 1. 检查是否有待处理的认证会话
        AuthPendingSession session = pendingSessions.get(playerName.toLowerCase());
        if (session != null) {
            handlePendingFlow(playerName, cleanMsg, session);
            return;
        }

        // 2. 检查是否是取消指令
        if (commandPrefix.isEmpty() && "cancel".equalsIgnoreCase(cleanMsg)) {
            sendTellRaw(playerName, "(无待取消的操作)");
            return;
        }
        if (!commandPrefix.isEmpty() && cleanMsg.equalsIgnoreCase(commandPrefix + "cancel")) {
            sendTellRaw(playerName, "(无待取消的操作)");
            return;
        }

        // 3. 解析指令
        String cmd;
        String arg;

        if (commandPrefix.isEmpty()) {
            // 无前缀：将消息第一段作为指令
            int spaceIdx = cleanMsg.indexOf(' ');
            if (spaceIdx > 0) {
                cmd = cleanMsg.substring(0, spaceIdx);
                arg = cleanMsg.substring(spaceIdx + 1);
            } else {
                cmd = cleanMsg;
                arg = "";
            }
        } else {
            // 有前缀：检查是否以前缀开头
            if (!cleanMsg.startsWith(commandPrefix)) return;
            String content = cleanMsg.substring(commandPrefix.length()).trim();
            int spaceIdx = content.indexOf(' ');
            if (spaceIdx > 0) {
                cmd = content.substring(0, spaceIdx);
                arg = content.substring(spaceIdx + 1);
            } else {
                cmd = content;
                arg = "";
            }
        }

        // 4. 分发指令
        dispatchCommand(playerName, cmd, arg);
    }

    private void handlePendingFlow(String playerName, String message, AuthPendingSession session) {
        String uuid = resolveUuidForPlayer(playerName);
        String result = authService.handlePendingFlow(this, playerName, uuid, session.delegate, message, getCmdPrefix());
        if (result != null) {
            sendTellRaw(playerName, result);
        } else {
            pendingSessions.remove(playerName.toLowerCase());
        }
    }

    // ==================== 指令分发 ====================

    private void dispatchCommand(String senderName, String cmd, String arg) {
        String uuid = resolveUuidForPlayer(senderName);

        // 限流
        if (!RateLimiter.allow("mcplayer:" + uuid, 10, 60)) {
            sendTellRaw(senderName, "操作太频繁，请稍后再试");
            return;
        }

        // 取消指令
        if ("cancel".equalsIgnoreCase(cmd)) {
            AuthPendingSession session = pendingSessions.get(senderName.toLowerCase());
            if (session != null) {
                pendingSessions.remove(senderName.toLowerCase());
                sendTellRaw(senderName, "已取消。");
            }
            return;
        }

        // 注册 / 绑定 / 解绑 特殊处理
        if ("register".equalsIgnoreCase(cmd)) {
            handleRegister(senderName, uuid, arg);
            return;
        }
        if ("bind".equalsIgnoreCase(cmd)) {
            handleBind(senderName, uuid);
            return;
        }
        if ("unbind".equalsIgnoreCase(cmd)) {
            handleUnbind(senderName, uuid);
            return;
        }

        // 所属内置模块已停用：给出提示而非静默忽略（与 OneBot 适配器保持一致）
        Command raw = CommandRegistry.getRaw(cmd);
        if (raw != null && !raw.isModuleEnabled()) {
            sendTellRaw(senderName, "该玩法系统已停用，暂时无法使用。");
            return;
        }

        // 查询 CommandRegistry
        Command command = CommandRegistry.get(cmd);
        if (command == null) return;

        // 权限校验：覆写了 execute() 的指令（例如 /地图）不会走 Command 基类里的权限检查，
        // 必须在分发处兜住，否则构造时声明的权限形同虚设。OneBot 与 QQ 官方适配器都是这么做的。
        String permission = command.getPermission();
        if (permission != null) {
            MinecraftPlayerBinding binding = bindingService.findByMcUuid(uuid);
            if (binding == null) {
                sendTellRaw(senderName, "请先注册或绑定账号。\n注册: " + getCmdPrefix()
                        + "register <角色名>\n绑定: " + getCmdPrefix() + "bind");
                return;
            }
            if (!PermissionService.hasPermission(binding.getUserId(), permission)) {
                sendTellRaw(senderName, "权限不足，你无权使用此功能。");
                return;
            }
        }

        MinecraftCommandContext ctx = new MinecraftCommandContext(
                senderName, uuid, arg, this, getCmdPrefix());
        // 指令执行挪到独立线程池，别占着 MC 的读线程 / Grizzly worker
        final Command execCmd = command;
        final MinecraftCommandContext execCtx = ctx;
        if (!com.mtxgdn.common.command.CommandExecutor.submit("mcplayer:/" + cmd,
                () -> execCmd.execute(execCtx))) {
            sendTellRaw(senderName, "服务器繁忙，请稍后再试");
        }
    }

    // ==================== 注册/绑定/解绑 ====================

    private void handleRegister(String senderName, String senderUuid, String arg) {
        String result = authService.handleRegister(this, senderName, senderUuid, arg, getCmdPrefix());
        if (result != null) {
            sendTellRaw(senderName, result);
        } else {
            // handleRegister 成功时会直接发送欢迎消息，不需要额外处理
        }
        // 如果进入了 pending 状态，记录到 pendingSessions
        if (authService.getPendingSession(senderName) != null) {
            pendingSessions.put(senderName.toLowerCase(),
                    new AuthPendingSession(authService.getPendingSession(senderName)));
        }
    }

    private void handleBind(String senderName, String senderUuid) {
        String result = authService.handleBind(this, senderName, senderUuid, getCmdPrefix());
        sendTellRaw(senderName, result);
        if (authService.getPendingSession(senderName) != null) {
            pendingSessions.put(senderName.toLowerCase(),
                    new AuthPendingSession(authService.getPendingSession(senderName)));
        }
    }

    private void handleUnbind(String senderName, String senderUuid) {
        String result = authService.handleUnbind(senderUuid);
        sendTellRaw(senderName, result);
    }

    private String getCmdPrefix() {
        return commandPrefix.isEmpty() ? "/" : "/" + commandPrefix + " ";
    }

    // ==================== UUID 解析 ====================

    /**
     * 解析远程玩家的 UUID。仅离线模式：使用离线 UUID 算法。
     * 如果没有获取到真实 UUID，降级为玩家用户名。
     */
    private String resolveUuidForPlayer(String playerName) {
        // 离线模式：使用 Minecraft 离线 UUID 算法
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ==================== 消息发送 ====================

    /**
     * 通过 /tellraw 发送私聊消息给指定玩家（模拟私聊格式）。
     */
    private void sendTellRaw(String targetPlayer, String message) {
        if (client == null || !client.isRunning()) return;
        // 使用 tellraw 发送 JSON 格式的私聊消息
        String escaped = message.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        client.sendCommand("tellraw " + targetPlayer
                + " {\"text\":\"§6[修仙] §r" + escaped + "\"}");
    }

    // ==================== MinecraftMessageSender 实现 ====================

    @Override
    public void replyToSource(String mcName, String minecraftUuid, String message) {
        sendTellRaw(mcName, message);
    }

    @Override
    public void sendPrivateMsg(String mcName, String message) {
        sendTellRaw(mcName, message);
    }

    @Override
    public void sendBroadcast(String message) {
        if (client == null || !client.isRunning()) return;
        String escaped = message.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        client.sendCommand("tellraw @a {\"text\":\"§6[修仙] §r" + escaped + "\"}");
    }

    @Override
    public void sendTitle(String mcName, String title, String subtitle) {
        if (client == null || !client.isRunning()) return;
        client.sendCommand("title " + mcName + " title {\"text\":\""
                + escapeJson(title) + "\",\"color\":\"gold\",\"bold\":true}");
        if (subtitle != null && !subtitle.isEmpty()) {
            client.sendCommand("title " + mcName + " subtitle {\"text\":\""
                    + escapeJson(subtitle) + "\",\"color\":\"yellow\"}");
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== 首次登录记录管理 ====================

    private void loadLoginRecords() {
        if (Files.exists(firstLoginFile)) {
            try {
                String json = Files.readString(firstLoginFile);
                Map<String, ServerLoginRecord> map = gson.fromJson(json,
                        new TypeToken<Map<String, ServerLoginRecord>>() {}.getType());
                loginRecords = map != null ? new ConcurrentHashMap<>(map) : new ConcurrentHashMap<>();
            } catch (Exception e) {
                log.warn("读取首次登录记录失败: " + e.getMessage());
                loginRecords = new ConcurrentHashMap<>();
            }
        } else {
            loginRecords = new ConcurrentHashMap<>();
        }
    }

    private void saveLoginRecords() {
        try {
            String json = gson.toJson(loginRecords);
            Files.writeString(firstLoginFile, json);
        } catch (Exception e) {
            log.warn("保存首次登录记录失败: " + e.getMessage());
        }
    }

    // ==================== 内部类 ====================

    static class ServerLoginRecord {
        boolean firstLogin = true;
        long lastLogin;
    }

    /** 包装 MinecraftAuthService.PendingSession 用于对外部玩家名管理 */
    static class AuthPendingSession {
        final MinecraftAuthService.PendingSession delegate;

        AuthPendingSession(MinecraftAuthService.PendingSession delegate) {
            this.delegate = delegate;
        }
    }
}
