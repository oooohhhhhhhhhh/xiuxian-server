package com.mtxgdn.minecraft.adapter;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.RateLimiter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MinecraftAdapter implements MinecraftMessageSender {

    private static final GameLogger log = GameLogger.getLogger("McAdapter");

    private static volatile MinecraftAdapter instance;

    public static MinecraftAdapter getInstance() {
        return instance;
    }

    private final MinecraftServerProcess serverProcess;
    private final MinecraftPlayerBindingService bindingService = ServiceRegistry.getMcBindingService();
    private final MinecraftAuthService authService = new MinecraftAuthService();
    private final Map<String, String> nameToUuid = new ConcurrentHashMap<>();
    private volatile boolean running = false;

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

    public String cmdPrefix() {
        String p = serverProcess.getCommandPrefix();
        return p.isEmpty() ? "/" : "/" + p + " ";
    }

    // ==================== 服务端事件回调 ====================

    void onServerReady() {
    }

    void onServerStopped() {
    }

    void onPlayerJoin(String mcName) {
        String uuid = resolveUuid(mcName);
        nameToUuid.put(mcName.toLowerCase(), uuid);
        log.info("[MC] 玩家 " + mcName + " UUID: " + uuid);

        MinecraftPlayerBinding binding = bindingService.findByMcUuid(uuid);
        if (binding == null) {
            sendPrivateMsg(mcName, "欢迎！输入 " + cmdPrefix() + "register <角色名> 注册修仙角色");
        } else {
            sendPrivateMsg(mcName, "欢迎回来！输入 " + cmdPrefix() + "status 查看角色");
        }
    }

    void onPlayerLeave(String mcName) {
        nameToUuid.remove(mcName.toLowerCase());
        authService.removePendingSession(mcName);
    }

    void onChatMessage(String mcName, String message) {
        if (!running) return;
        MinecraftAuthService.PendingSession session = authService.getPendingSession(mcName);
        if (session == null) return;

        String uuid = resolveUuidFor(mcName);
        String result = authService.handlePendingFlow(this, mcName, uuid, session, message.trim(), cmdPrefix());
        if (result != null) {
            sendPrivateMsg(mcName, result);
        }
    }

    void onCommand(String mcName, String cmd, String args) {
        dispatchMcCommand(mcName, cmd, args, this);
    }

    public McCommandResult handleMcCommand(String mcName, String mcUuid, String cmd, String args) {
        if (!running) return McCommandResult.error("Minecraft 适配器未启动");

        if ("cancel".equalsIgnoreCase(cmd)) {
            MinecraftAuthService.PendingSession session = authService.getPendingSession(mcName);
            if (session != null) {
                authService.removePendingSession(mcName);
                return McCommandResult.ok("已取消。");
            }
            return McCommandResult.ok("(无待取消的操作)");
        }

        String uuid = (mcUuid != null && !mcUuid.isEmpty()) ? mcUuid : resolveUuidFor(mcName);
        if (mcUuid != null && !mcUuid.isEmpty() && !nameToUuid.containsKey(mcName.toLowerCase())) {
            nameToUuid.put(mcName.toLowerCase(), mcUuid);
        }

        Command command = CommandRegistry.get(cmd);
        if (command == null) return null;

        StringCollector collector = new StringCollector();
        MinecraftCommandContext ctx = new MinecraftCommandContext(mcName, uuid, args, collector, cmdPrefix());
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.COMMAND)
                        .data("command", cmd)
                        .data("arg", args == null ? "" : args)
                        .data("mcName", mcName)
                        .build());
        command.execute(ctx);

        String text = collector.get();
        return McCommandResult.ok(text != null && !text.isEmpty() ? text : "(无输出)");
    }

    private void dispatchMcCommand(String mcName, String cmd, String args, MinecraftMessageSender sender) {
        String uuid = resolveUuidFor(mcName);

        if (!RateLimiter.allow("mc:" + uuid, 10, 60)) {
            sendPrivateMsg(mcName, "操作太频繁，请稍后再试");
            return;
        }

        if ("cancel".equalsIgnoreCase(cmd)) {
            MinecraftAuthService.PendingSession session = authService.getPendingSession(mcName);
            if (session != null) {
                authService.removePendingSession(mcName);
                sendPrivateMsg(mcName, "已取消。");
            }
            return;
        }

        Command command = CommandRegistry.get(cmd);
        if (command == null) return;

        MinecraftCommandContext ctx = new MinecraftCommandContext(mcName, uuid, args, sender, cmdPrefix());
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.COMMAND)
                        .data("command", cmd)
                        .data("arg", args == null ? "" : args)
                        .data("mcName", mcName)
                        .build());
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

    private void sendTellraw(String target, String rawText) {
        serverProcess.sendCommand("tellraw " + target + " {\"text\":\"" + escapeJson(rawText) + "\"}");
    }

    // ==================== 注册/绑定接口 ====================

    public void handleRegister(String mcName, String mcUuid, String arg) {
        String result = authService.handleRegister(this, mcName, mcUuid, arg, cmdPrefix());
        if (result != null) {
            sendPrivateMsg(mcName, result);
        }
    }

    public void handleBind(String mcName, String mcUuid) {
        String result = authService.handleBind(this, mcName, mcUuid, cmdPrefix());
        if (result != null) {
            sendPrivateMsg(mcName, result);
        }
    }

    public void handleUnbind(String mcName, String mcUuid) {
        String result = authService.handleUnbind(mcUuid);
        sendPrivateMsg(mcName, result);
    }

    // ==================== UUID 解析 ====================

    private String resolveUuid(String playerName) {
        boolean onlineMode = AppConfig.getBoolean("minecraft.online_mode", false);
        if (onlineMode) {
            String uuid = resolveFromMojang(playerName);
            if (uuid != null) return uuid;
        }
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
                    if (json != null && json.contains("\"id\"")) {
                        int start = json.indexOf("\"id\"") + 6;
                        int end = json.indexOf("\"", start);
                        String rawUuid = json.substring(start, end);
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

    // ==================== StringCollector ====================

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