package com.mtxgdn.minecraft.adapter;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.PlayerActionLogger;
import com.mtxgdn.util.RateLimiter;
import org.mindrot.jbcrypt.BCrypt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft 适配器 —— 修仙游戏 × Minecraft。
 * <p>
 * 工作方式（与 OneBotWebSocketServer 结构对称）：
 * <ol>
 *   <li>通过 {@link MinecraftServerProcess} 读写 MC 服务端 stdin/stdout</li>
 *   <li>解析 stdout 中的聊天消息，分发给 {@link CommandRegistry}</li>
 *   <li>通过 tellraw/msg 指令将回复写入 MC 服务端 stdin</li>
 *   <li>账号系统：Minecraft 玩家名 → UUID → 游戏账号，与 QQ 绑定共用同一个 user 体系</li>
 * </ol>
 */
public class MinecraftAdapter implements MinecraftMessageSender {

    private static final GameLogger log = GameLogger.getLogger("McAdapter");
    private static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();

    /** 全局单例，供 Minecraft 命令处理器使用 */
    private static volatile MinecraftAdapter instance;

    public static MinecraftAdapter getInstance() {
        return instance;
    }

    private final MinecraftServerProcess serverProcess;
    private final MinecraftPlayerBindingService bindingService = new MinecraftPlayerBindingService();
    private final Map<String, PendingSession> pendingSessions = new ConcurrentHashMap<>();
    private final Map<String, String> nameToUuid = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    private static class PendingSession {
        String type;      // "register" / "bind"
        String state;     // "WAITING_USERNAME" / "WAITING_PASSWORD"
        String username;

        PendingSession(String type, String mcName, String mcUuid) {
            this.type = type;
            if ("register".equals(type)) {
                this.state = "WAITING_PASSWORD"; // 直接等密码，角色名用 MC 名
                this.username = mcName;
            } else {
                this.state = "WAITING_USERNAME";
            }
        }
    }

    public MinecraftAdapter() {
        instance = this;
        this.serverProcess = new MinecraftServerProcess();
        this.serverProcess.setAdapter(this);
    }

    // ==================== 生命周期 ====================

    public boolean start() {
        boolean started = serverProcess.start();
        if (started) {
            running = true;
            log.info("Minecraft 适配器已启动");
        }
        return started;
    }

    public void stop() {
        running = false;
        serverProcess.stop();
        log.info("Minecraft 适配器已停止");
    }

    public boolean isRunning() {
        return running;
    }

    /** 返回 MC 中可用的指令前缀："/xiuxian " 或 "/" */
    public String cmdPrefix() {
        String p = serverProcess.getCommandPrefix();
        return p.isEmpty() ? "/" : "/" + p + " ";
    }

    // ==================== 服务端事件回调 ====================

    void onServerReady() {
        serverReady = true;
    }

    void onServerStopped() {
        serverReady = false;
        pendingSessions.clear();
    }

    void onPlayerJoin(String mcName) {
        // 解析 UUID — 先走 Mojang API（正版），回退到 offline UUID
        String uuid = resolveUuid(mcName);
        nameToUuid.put(mcName.toLowerCase(), uuid);
        log.info("[MC] 玩家 " + mcName + " UUID: " + uuid);

        // 检查是否已绑定，给个欢迎提示
        MinecraftPlayerBinding binding = bindingService.findByMcUuid(uuid);
        if (binding == null) {
            serverProcess.sendCommand("tellraw " + mcName +
                    " [{\"text\":\"[修仙] \",\"color\":\"gold\"}," +
                    "{\"text\":\"欢迎！输入 " + cmdPrefix() + "register <角色名> 注册修仙角色\",\"color\":\"green\"}]");
        } else {
            serverProcess.sendCommand("tellraw " + mcName +
                    " [{\"text\":\"[修仙] \",\"color\":\"gold\"}," +
                    "{\"text\":\"欢迎回来！输入 " + cmdPrefix() + "status 查看角色\",\"color\":\"green\"}]");
        }
    }

    void onPlayerLeave(String mcName) {
        nameToUuid.remove(mcName.toLowerCase());
        pendingSessions.remove(mcName.toLowerCase());
    }

    /**
     * 聊天消息回调 — 仅处理待处理会话（注册/绑定多步流程中玩家的输入）。
     * 指令已通过 onCommand() 处理，此处不再做指令分发。
     */
    void onChatMessage(String mcName, String message) {
        if (!running) return;
        PendingSession session = pendingSessions.get(mcName.toLowerCase());
        if (session == null) return; // 普通聊天，忽略

        String uuid = resolveUuidFor(mcName);
        handlePendingFlow(mcName, uuid, session, message.trim());
    }

    /**
     * MC 指令回调 — 玩家在游戏里输入了 /xxx，已被 MinecraftServerProcess 从日志行中解析出来。
     * 仅用于 stdout/stderr 拦截路径（旧方案），新方案走 REST API + 插件。
     */
    void onCommand(String mcName, String cmd, String args) {
        dispatchMcCommand(mcName, cmd, args, this);
    }

    /**
     * 处理 MC 指令并收集回复文本。
     * 供 REST API（McCommandResource）和 stdout 拦截路径共用。
     */
    public McCommandResult handleMcCommand(String mcName, String mcUuid, String cmd, String args) {
        if (!running) return McCommandResult.error("Minecraft 适配器未启动");

        // 特殊处理 /cancel
        if ("cancel".equalsIgnoreCase(cmd)) {
            PendingSession session = pendingSessions.get(mcName.toLowerCase());
            if (session != null) {
                pendingSessions.remove(mcName.toLowerCase());
                return McCommandResult.ok("已取消。");
            }
            return McCommandResult.ok("(无待取消的操作)");
        }

        // 确保 UUID 已缓存
        String uuid = (mcUuid != null && !mcUuid.isEmpty()) ? mcUuid : resolveUuidFor(mcName);
        if (mcUuid != null && !mcUuid.isEmpty() && !nameToUuid.containsKey(mcName.toLowerCase())) {
            nameToUuid.put(mcName.toLowerCase(), mcUuid);
        }

        Command command = CommandRegistry.get(cmd);
        if (command == null) return null; // 未知指令，上层处理

        StringCollector collector = new StringCollector();
        MinecraftCommandContext ctx = new MinecraftCommandContext(mcName, uuid, args, collector, cmdPrefix());
        command.execute(ctx);

        String text = collector.get();
        return McCommandResult.ok(text != null && !text.isEmpty() ? text : "(无输出)");
    }

    /** 实际上不需要单独的 dispatch，直接让 onCommand 委托 handleMcCommand */
    private void dispatchMcCommand(String mcName, String cmd, String args, MinecraftMessageSender sender) {
        String uuid = resolveUuidFor(mcName);

        if (!RateLimiter.allow("mc:" + uuid, 10, 60)) {
            sendPrivateMsg(mcName, "操作太频繁，请稍后再试");
            return;
        }

        // 特殊处理 /cancel
        if ("cancel".equalsIgnoreCase(cmd)) {
            PendingSession session = pendingSessions.get(mcName.toLowerCase());
            if (session != null) {
                pendingSessions.remove(mcName.toLowerCase());
                sendPrivateMsg(mcName, "已取消。");
            }
            return;
        }

        Command command = CommandRegistry.get(cmd);
        if (command == null) return;

        MinecraftCommandContext ctx = new MinecraftCommandContext(mcName, uuid, args, sender, cmdPrefix());
        command.execute(ctx);
    }

    private String resolveUuidFor(String mcName) {
        String uuid = nameToUuid.get(mcName.toLowerCase());
        if (uuid == null) {
            uuid = resolveUuid(mcName);
            nameToUuid.put(mcName.toLowerCase(), uuid);
        }
        return uuid;
    }

    // ==================== MinecraftMessageSender 实现 ====================

    @Override
    public void replyToSource(String mcName, String mcUuid, String message) {
        // MC 中来源就是私聊，直接复述给该玩家
        sendTellraw(mcName, "§6[修仙] §r" + message.replace("\n", "\\n"));
    }

    @Override
    public void sendPrivateMsg(String mcName, String message) {
        sendTellraw(mcName, "§6[修仙] §r" + message.replace("\n", "\\n"));
    }

    @Override
    public void sendBroadcast(String message) {
        sendTellraw("@a", "§6[修仙] §r" + message.replace("\n", "\\n"));
    }

    @Override
    public void sendTitle(String mcName, String title, String subtitle) {
        serverProcess.sendCommand("title " + mcName + " title {\"text\":\"" +
                escapeJson(title) + "\",\"color\":\"gold\",\"bold\":true}");
        if (subtitle != null && !subtitle.isEmpty()) {
            serverProcess.sendCommand("title " + mcName + " subtitle {\"text\":\"" +
                    escapeJson(subtitle) + "\",\"color\":\"yellow\"}");
        }
    }

    /** 通过 tellraw 发送格式化消息 */
    private void sendTellraw(String target, String rawText) {
        serverProcess.sendCommand("tellraw " + target + " {\"text\":\"" + escapeJson(rawText) + "\"}");
    }

    // ==================== 注册/绑定流程 ====================

    public void handleRegister(String mcName, String mcUuid, String arg) {
        MinecraftPlayerBinding existingBinding = bindingService.findByMcUuid(mcUuid);
        if (existingBinding != null) {
            // 检查是否"已绑定但无角色"
            if (ServiceRegistry.getPlayerService().getPlayerByUserId(existingBinding.getUserId()) == null) {
                try {
                    PlayerInfo player = ServiceRegistry.getPlayerService()
                            .createPlayer(existingBinding.getUserId(), arg.trim());
                    String guide = NewbieGuideService.getWelcomeMessage(player);
                    sendPrivateMsg(mcName, guide);
                    actionLog.logCreatePlayer(existingBinding.getUserId(), player.getName());
                } catch (RuntimeException e) {
                    sendPrivateMsg(mcName, "创建角色失败: " + e.getMessage());
                }
                return;
            }
            sendPrivateMsg(mcName, "你已注册角色，无需重复注册。如需重来请先用 " + cmdPrefix() + "unbind 解绑。");
            return;
        }

        String name = arg.trim();
        if (name.isEmpty()) {
            sendPrivateMsg(mcName, "用法: " + cmdPrefix() + "register <角色名>");
            return;
        }
        if (name.length() > 16) {
            sendPrivateMsg(mcName, "角色名不能超过16个字");
            return;
        }
        if (isUsernameExists(name)) {
            sendPrivateMsg(mcName, "角色名已被占用，请换一个。");
            return;
        }
        if (bindingService.findByMcUuid(mcUuid) != null) {
            sendPrivateMsg(mcName, "你已注册角色，无需重复注册。");
            return;
        }

        PendingSession session = new PendingSession("register", mcName, mcUuid);
        session.username = name;
        pendingSessions.put(mcName.toLowerCase(), session);
        sendPrivateMsg(mcName, "角色名【" + name + "】可用！\n请在聊天中设置你的密码（不少于6位，不会明文显示）\n(输入 " + cmdPrefix() + "cancel 取消)");
    }

    public void handleBind(String mcName, String mcUuid) {
        if (bindingService.findByMcUuid(mcUuid) != null) {
            MinecraftPlayerBinding b = bindingService.findByMcUuid(mcUuid);
            sendPrivateMsg(mcName, "你已绑定账号 (用户ID: " + b.getUserId() + ")，" + cmdPrefix() + "unbind 可解绑。");
            return;
        }
        PendingSession session = new PendingSession("bind", mcName, mcUuid);
        pendingSessions.put(mcName.toLowerCase(), session);
        sendPrivateMsg(mcName, "===== 账号绑定 =====\n请输入游戏用户名：\n(输入 " + cmdPrefix() + "cancel 取消)");
    }

    public void handleUnbind(String mcName, String mcUuid) {
        MinecraftPlayerBinding b = bindingService.findByMcUuid(mcUuid);
        if (b == null) {
            sendPrivateMsg(mcName, "你尚未绑定账号。");
            return;
        }
        try {
            bindingService.unbindByMcUuid(mcUuid);
            sendPrivateMsg(mcName, "解绑成功！");
        } catch (RuntimeException e) {
            sendPrivateMsg(mcName, "解绑失败: " + e.getMessage());
        }
    }

    // ==================== 会话流程 ====================

    private void handlePendingFlow(String mcName, String mcUuid,
                                   PendingSession session, String message) {
        switch (session.state) {
            case "WAITING_USERNAME":
                session.username = message.trim();
                session.state = "WAITING_PASSWORD";
                sendPrivateMsg(mcName, "请输入密码：\n(输入 " + cmdPrefix() + "cancel 取消)");
                break;
            case "WAITING_PASSWORD":
                String password = message.trim();
                if (password.length() < 6) {
                    sendPrivateMsg(mcName, "密码不少于6位，请重新输入：\n(输入 " + cmdPrefix() + "cancel 取消)");
                    return;
                }
                if ("register".equals(session.type)) {
                    doRegisterComplete(mcName, mcUuid, session, password);
                } else {
                    doBindComplete(mcName, mcUuid, session, password);
                }
                pendingSessions.remove(mcName.toLowerCase());
                break;
        }
    }

    private void doRegisterComplete(String mcName, String mcUuid,
                                    PendingSession session, String password) {
        if (isUsernameExists(session.username)) {
            sendPrivateMsg(mcName, "角色名【" + session.username + "】已被占用，请重新注册。");
            return;
        }
        Long userId = null;
        try {
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            userId = insertUser(session.username, hashedPassword);
            if (userId == null) {
                sendPrivateMsg(mcName, "注册失败，请稍后重试。");
                return;
            }
            PermissionService.assignDefaultRole(userId);
            bindingService.bind(mcUuid, mcName, userId);
            PlayerInfo player = ServiceRegistry.getPlayerService().createPlayer(userId, session.username);
            String guide = NewbieGuideService.getWelcomeMessage(player);
            sendPrivateMsg(mcName, guide);
            actionLog.logCreatePlayer(userId, session.username);
        } catch (RuntimeException e) {
            if (userId != null) {
                try { bindingService.unbindByMcUuid(mcUuid); } catch (Exception ignored) {}
                try { deleteUser(userId); } catch (Exception ignored) {}
            }
            sendPrivateMsg(mcName, "注册失败: " + e.getMessage());
        }
    }

    private void doBindComplete(String mcName, String mcUuid,
                                PendingSession session, String password) {
        Long userId = verifyUserCredentials(session.username, password);
        if (userId == null) {
            sendPrivateMsg(mcName, "用户名或密码错误，请重新 " + cmdPrefix() + "bind。");
            return;
        }
        try {
            bindingService.bind(mcUuid, mcName, userId);
            sendPrivateMsg(mcName, "绑定成功！\n用户名: " + session.username + "\n用户ID: " + userId);
        } catch (RuntimeException e) {
            sendPrivateMsg(mcName, "绑定失败: " + e.getMessage());
        }
    }

    // ==================== UUID 解析 ====================

    /**
     * 解析玩家 UUID。
     * 在线模式走 Mojang API，离线模式用 name 生成确定性 UUID。
     */
    private String resolveUuid(String playerName) {
        boolean onlineMode = AppConfig.getBoolean("minecraft.online_mode", false);
        if (onlineMode) {
            String uuid = resolveFromMojang(playerName);
            if (uuid != null) return uuid;
        }
        // 离线模式：基于名字生成 UUID (同 MC 离线服务器行为)
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes()).toString();
    }

    private String resolveFromMojang(String playerName) {
        try {
            URI uri = URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String json = reader.readLine();
                    // {"name":"...","id":"..."}  id 是无连字符的 UUID
                    if (json != null && json.contains("\"id\"")) {
                        int start = json.indexOf("\"id\"") + 6;
                        int end = json.indexOf("\"", start);
                        String rawUuid = json.substring(start, end);
                        // 加上连字符
                        return rawUuid.replaceFirst(
                                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                                "$1-$2-$3-$4-$5");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Mojang API 查询失败: " + e.getMessage());
        }
        return null;
    }

    // ==================== 工具方法 ====================

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
        } catch (SQLException e) { log.error("验证用户凭据失败", e); }
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
        } catch (SQLException e) { log.error("查询用户名失败", e); }
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
        } catch (SQLException e) { throw new RuntimeException("用户插入失败", e); }
        return null;
    }

    private void deleteUser(long userId) {
        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("删除用户失败", e); }
    }

    // ==================== StringCollector ====================

    /** 收集 reply() 文本到字符串，用于 REST API 返回值 */
    static class StringCollector implements MinecraftMessageSender {
        private final StringBuilder sb = new StringBuilder();

        String get() { return sb.toString(); }

        @Override
        public void replyToSource(String mcName, String minecraftUuid, String message) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(message);
        }

        @Override
        public void sendPrivateMsg(String mcName, String message) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(message);
        }

        @Override
        public void sendBroadcast(String message) {}

        @Override
        public void sendTitle(String mcName, String title, String subtitle) {}
    }
}
