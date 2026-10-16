package com.mtxgdn.minecraft.adapter;

import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.PlayerActionLogger;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MinecraftAuthService {

    private static final GameLogger log = GameLogger.getLogger(MinecraftAuthService.class);
    private static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();

    private final MinecraftPlayerBindingService bindingService = ServiceRegistry.getMcBindingService();
    private final Map<String, PendingSession> pendingSessions = new ConcurrentHashMap<>();

    public static class PendingSession {
        String type;
        String state;
        String username;

        PendingSession(String type, String mcName) {
            this.type = type;
            if ("register".equals(type)) {
                this.state = "WAITING_PASSWORD";
                this.username = mcName;
            } else {
                this.state = "WAITING_USERNAME";
            }
        }
    }

    public PendingSession getPendingSession(String mcName) {
        return pendingSessions.get(mcName.toLowerCase());
    }

    public void removePendingSession(String mcName) {
        pendingSessions.remove(mcName.toLowerCase());
    }

    public String handleRegister(MinecraftMessageSender sender, String mcName, String mcUuid,
                                  String arg, String cmdPrefix) {
        MinecraftPlayerBinding existingBinding = bindingService.findByMcUuid(mcUuid);
        if (existingBinding != null) {
            if (ServiceRegistry.getPlayerService().getPlayerByUserId(existingBinding.getUserId()) == null) {
                try {
                    PlayerInfo player = ServiceRegistry.getPlayerService()
                            .createPlayer(existingBinding.getUserId(), arg.trim());
                    String guide = NewbieGuideService.getWelcomeMessage(player);
                    sender.sendPrivateMsg(mcName, guide);
                    actionLog.logCreatePlayer(existingBinding.getUserId(), player.getName());
                    return null;
                } catch (RuntimeException e) {
                    return "创建角色失败: " + e.getMessage();
                }
            }
            return "你已注册角色，无需重复注册。如需重来请先用 " + cmdPrefix + "unbind 解绑。";
        }

        String name = arg.trim();
        if (name.isEmpty()) {
            return "用法: " + cmdPrefix + "register <角色名>";
        }
        if (name.length() > 16) {
            return "角色名不能超过16个字";
        }
        if (isUsernameExists(name)) {
            return "角色名已被占用，请换一个。";
        }

        PendingSession session = new PendingSession("register", mcName);
        session.username = name;
        pendingSessions.put(mcName.toLowerCase(), session);
        return "角色名【" + name + "】可用！\n请在聊天中设置你的密码（不少于6位，不会明文显示）\n(输入 " + cmdPrefix + "cancel 取消)";
    }

    public String handleBind(MinecraftMessageSender sender, String mcName, String mcUuid,
                              String cmdPrefix) {
        if (bindingService.findByMcUuid(mcUuid) != null) {
            MinecraftPlayerBinding b = bindingService.findByMcUuid(mcUuid);
            return "你已绑定账号 (用户ID: " + b.getUserId() + ")，" + cmdPrefix + "unbind 可解绑。";
        }
        PendingSession session = new PendingSession("bind", mcName);
        pendingSessions.put(mcName.toLowerCase(), session);
        return "===== 账号绑定 =====\n请输入游戏用户名：\n(输入 " + cmdPrefix + "cancel 取消)";
    }

    public String handleUnbind(String mcUuid) {
        MinecraftPlayerBinding b = bindingService.findByMcUuid(mcUuid);
        if (b == null) {
            return "你尚未绑定账号。";
        }
        try {
            bindingService.unbindByMcUuid(mcUuid);
            return "解绑成功！";
        } catch (RuntimeException e) {
            return "解绑失败: " + e.getMessage();
        }
    }

    public String handlePendingFlow(MinecraftMessageSender sender, String mcName, String mcUuid,
                                     PendingSession session, String message, String cmdPrefix) {
        switch (session.state) {
            case "WAITING_USERNAME":
                session.username = message.trim();
                session.state = "WAITING_PASSWORD";
                return "请输入密码：\n(输入 " + cmdPrefix + "cancel 取消)";
            case "WAITING_PASSWORD":
                String password = message.trim();
                if (password.length() < 6) {
                    return "密码不少于6位，请重新输入：\n(输入 " + cmdPrefix + "cancel 取消)";
                }
                String result = "register".equals(session.type)
                        ? doRegisterComplete(sender, mcName, mcUuid, session, password)
                        : doBindComplete(mcName, mcUuid, session, password);
                pendingSessions.remove(mcName.toLowerCase());
                return result;
            default:
                return "未知状态";
        }
    }

    private String doRegisterComplete(MinecraftMessageSender sender, String mcName, String mcUuid,
                                       PendingSession session, String password) {
        if (isUsernameExists(session.username)) {
            return "角色名【" + session.username + "】已被占用，请重新注册。";
        }
        Long userId = null;
        try {
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            userId = insertUser(session.username, hashedPassword);
            if (userId == null) {
                return "注册失败，请稍后重试。";
            }
            PermissionService.assignDefaultRole(userId);
            bindingService.bind(mcUuid, mcName, userId);
            PlayerInfo player = ServiceRegistry.getPlayerService().createPlayer(userId, session.username);
            String guide = NewbieGuideService.getWelcomeMessage(player);
            sender.sendPrivateMsg(mcName, guide);
            actionLog.logCreatePlayer(userId, session.username);
            return null;
        } catch (RuntimeException e) {
            if (userId != null) {
                try { bindingService.unbindByMcUuid(mcUuid); } catch (Exception ignored) {}
                try { deleteUser(userId); } catch (Exception ignored) {}
            }
            return "注册失败: " + e.getMessage();
        }
    }

    private String doBindComplete(String mcName, String mcUuid,
                                   PendingSession session, String password) {
        Long userId = verifyUserCredentials(session.username, password);
        if (userId == null) {
            return "用户名或密码错误，请重新绑定。";
        }
        try {
            bindingService.bind(mcUuid, mcName, userId);
            return "绑定成功！\n用户名: " + session.username + "\n用户ID: " + userId;
        } catch (RuntimeException e) {
            return "绑定失败: " + e.getMessage();
        }
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
}