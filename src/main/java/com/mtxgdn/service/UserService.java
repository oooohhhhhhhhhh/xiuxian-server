package com.mtxgdn.service;

import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.User;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.JwtUtil;
import jakarta.ws.rs.core.Response;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

public class UserService {

    public Response register(String username, String rawPassword, String email, String code) {
        if (username == null || username.trim().isEmpty()) {
            return buildError(400, "用户名不能为空");
        }
        if (rawPassword == null || rawPassword.length() < 6) {
            return buildError(400, "密码长度不能少于6位");
        }
        if (username.length() > 64) {
            return buildError(400, "用户名长度不能超过64位");
        }

        String trimmedUsername = username.trim();

        if (isUsernameExists(trimmedUsername)) {
            return buildError(409, GameErrorCode.AUTH_USERNAME_EXISTS.getMessage());
        }

        boolean verifyCodeEnabled = AppConfig.getBoolean("verify_code.enabled", true);
        String trimmedEmail = null;

        if (verifyCodeEnabled) {
            if (email == null || email.trim().isEmpty()) {
                return buildError(400, "邮箱不能为空");
            }
            trimmedEmail = email.trim().toLowerCase();

            if (!isValidEmail(trimmedEmail)) {
                return buildError(400, "邮箱格式不正确");
            }

            if (!isAllowedEmailDomain(trimmedEmail)) {
                return buildError(400, "不支持的邮箱类型");
            }

            if (isEmailExists(trimmedEmail)) {
                return buildError(409, "该邮箱已被注册");
            }

            if (code == null || code.trim().isEmpty()) {
                return buildError(400, "验证码不能为空");
            }

            VerificationCodeService verificationCodeService = new VerificationCodeService();
            if (!verificationCodeService.verifyCode(trimmedEmail, code.trim())) {
                return buildError(400, "验证码错误或已过期");
            }
        }

        String hashedPassword = BCrypt.hashpw(rawPassword, BCrypt.gensalt());

        User user = insertUser(trimmedUsername, hashedPassword, trimmedEmail);
        if (user == null) {
            return buildError(500, "注册失败，请稍后重试");
        }

        PermissionService.assignDefaultRole(user.getId());

        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        JsonObject data = new JsonObject();
        data.addProperty("id", user.getId());
        data.addProperty("username", user.getUsername());
        data.addProperty("token", token);

        return Response.ok(GameMessage.restOk("注册成功", data).toString()).build();
    }

    private boolean isValidEmail(String email) {
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    }

    private boolean isAllowedEmailDomain(String email) {
        String allowedDomains = AppConfig.get("verify_code.allowed_domains", "qq.com,163.com,126.com,gmail.com,outlook.com,hotmail.com");
        List<String> domains = Arrays.asList(allowedDomains.split(","));
        int atIndex = email.lastIndexOf('@');
        if (atIndex <= 0 || atIndex >= email.length() - 1) {
            return false;
        }
        String domain = email.substring(atIndex + 1).toLowerCase();
        return domains.contains(domain);
    }

    private boolean isEmailExists(String email) {
        String sql = "SELECT COUNT(*) FROM users WHERE email = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("数据库查询失败", e);
        }
        return false;
    }

    public Response login(String username, String rawPassword) {
        if (username == null || username.trim().isEmpty()) {
            return buildError(400, "用户名不能为空");
        }
        if (rawPassword == null || rawPassword.isEmpty()) {
            return buildError(400, "密码不能为空");
        }

        String trimmedUsername = username.trim();

        User user = findUserByUsername(trimmedUsername);
        if (user == null) {
            return buildError(401, GameErrorCode.AUTH_WRONG_PASSWORD.getMessage());
        }

        if (!BCrypt.checkpw(rawPassword, user.getPassword())) {
            return buildError(401, GameErrorCode.AUTH_WRONG_PASSWORD.getMessage());
        }

        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        JsonObject data = new JsonObject();
        data.addProperty("id", user.getId());
        data.addProperty("username", user.getUsername());
        data.addProperty("token", token);

        return Response.ok(GameMessage.restOk("登录成功", data).toString()).build();
    }

    public User authenticate(String username, String rawPassword) {
        String trimmedUsername = username.trim();
        if (trimmedUsername.isEmpty() || rawPassword == null || rawPassword.isEmpty()) {
            return null;
        }
        User user = findUserByUsername(trimmedUsername);
        if (user == null || !BCrypt.checkpw(rawPassword, user.getPassword())) {
            return null;
        }
        return user;
    }

    private User findUserByUsername(String username) {
        String sql = "SELECT id, username, password FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    User user = new User();
                    user.setId(rs.getLong("id"));
                    user.setUsername(rs.getString("username"));
                    user.setPassword(rs.getString("password"));
                    return user;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("数据库查询失败", e);
        }
        return null;
    }

    private boolean isUsernameExists(String username) {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("数据库查询失败", e);
        }
        return false;
    }

    private User insertUser(String username, String hashedPassword, String email) {
        String sql = "INSERT INTO users (username, password, email) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, hashedPassword);
            ps.setString(3, email);
            int affected = ps.executeUpdate();
            if (affected > 0) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        User user = new User();
                        user.setId(rs.getLong(1));
                        user.setUsername(username);
                        user.setEmail(email);
                        return user;
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("用户插入失败", e);
        }
        return null;
    }

    private Response buildError(int httpStatus, String message) {
        JsonObject body = GameMessage.restError(httpStatus, message);
        return Response.status(httpStatus).entity(body.toString()).build();
    }

    public void updateUserEmail(long userId, String email) {
        String sql = "UPDATE users SET email = ? WHERE id = ? AND (email IS NULL OR email = '' OR email = ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setLong(2, userId);
            ps.setString(3, email);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新邮箱失败", e);
        }
    }

    public String getUserEmail(long userId) {
        String sql = "SELECT email FROM users WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("email");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询邮箱失败", e);
        }
        return null;
    }

    public Response changePassword(long userId, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isEmpty()) {
            return buildError(400, "原密码不能为空");
        }
        if (newPassword == null || newPassword.length() < 6) {
            return buildError(400, "新密码长度不能少于6位");
        }
        if (oldPassword.equals(newPassword)) {
            return buildError(400, "新密码不能与原密码相同");
        }

        String sql = "SELECT id, password FROM users WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return buildError(404, "用户不存在");
                }
                String hashedPassword = rs.getString("password");
                if (!BCrypt.checkpw(oldPassword, hashedPassword)) {
                    return buildError(401, "原密码错误");
                }
            }

            String newHashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());
            String updateSql = "UPDATE users SET password = ? WHERE id = ?";
            try (PreparedStatement ups = conn.prepareStatement(updateSql)) {
                ups.setString(1, newHashedPassword);
                ups.setLong(2, userId);
                ups.executeUpdate();
            }

            JsonObject data = new JsonObject();
            data.addProperty("message", "密码修改成功");
            return Response.ok(GameMessage.restOk("密码修改成功", data).toString()).build();
        } catch (SQLException e) {
            throw new RuntimeException("修改密码失败", e);
        }
    }

    public Response deleteUser(long userId) {
        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                return buildError(404, "用户不存在");
            }
            JsonObject data = new JsonObject();
            data.addProperty("message", "账户注销成功");
            return Response.ok(GameMessage.restOk("账户注销成功", data).toString()).build();
        } catch (SQLException e) {
            throw new RuntimeException("注销账户失败", e);
        }
    }
}
