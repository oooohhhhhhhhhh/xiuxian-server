package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtxgdn.db.PermissionDAO;
import com.mtxgdn.db.UserAccountDAO;
import com.mtxgdn.db.UserServiceDAO;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.identity.QqBinding;
import com.mtxgdn.identity.QqOfficialBindingService;
import com.mtxgdn.service.UserService;
import com.mtxgdn.util.RateLimiter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * 玩家自助账号管理（登录网页后操作）。
 *
 * <p><b>为什么需要它</b>：{@code /解绑}、{@code /改密}、{@code /注销} 一直是「私聊专用」
 * 指令。新申请的 QQ 官方机器人常常没有单聊（C2C）权限，玩家根本收不到私聊提示，
 * 这三个操作在那种部署下完全无路可走。网页端用账号密码登录后自助操作，不依赖私聊。</p>
 *
 * <h3>鉴权</h3>
 * <p>路径在 {@code /admin} 下，由 {@link AdminAuthFilter} 的「玩家自我服务」白名单放行：
 * 只接受合法游戏 JWT，并以玩家身份设置 {@code userId}，<b>不授予任何 admin 权限</b>。
 * 所有操作只作用于调用者自己的账号。</p>
 *
 * <h3>为什么每个写操作都要密码</h3>
 * <p>网页会话（JWT）可能被留在公共电脑的浏览器里。解绑、改密、注销都是不可逆或
 * 影响账号归属的操作，必须再验一次密码，避免"捡到没退出的浏览器就能删号"。</p>
 */
@Path("/admin/account")
public class PlayerAccountResource extends BaseResource {

    /** 跨机器人（不限 AppID）：一个玩家可能在不同机器人下各有一条绑定。 */
    private static final QqOfficialBindingService BINDINGS = new QqOfficialBindingService();
    private static final UserServiceDAO USER_DAO = new UserServiceDAO();
    private static final PermissionDAO PERMISSION_DAO = new PermissionDAO();

    private final UserService userService = new UserService();

    @Context
    private ContainerRequestContext requestContext;

    // ==================== 查询 ====================

    /** 当前账号的 QQ 绑定状态（openid 只回显尾部，避免整串暴露）。 */
    @GET
    @Path("/qq-binding")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getQqBinding() {
        Long userId = currentUserId();
        if (userId == null) {
            return error(401, "请先登录");
        }
        JsonObject data = new JsonObject();
        QqBinding binding = BINDINGS.findByUserId(userId);
        data.addProperty("bound", binding != null);
        if (binding != null) {
            data.addProperty("openidTail", tail(binding.getQqNumber()));
            data.addProperty("appId", binding.getAppId() == null ? "" : binding.getAppId());
        }
        return ok(data);
    }

    // ==================== 解绑 ====================

    /** 解除当前账号与所有 QQ 官方机器人的绑定。 */
    @DELETE
    @Path("/qq-binding")
    @Produces(MediaType.APPLICATION_JSON)
    public Response unbindQq() {
        Long userId = currentUserId();
        if (userId == null) {
            return error(401, "请先登录");
        }
        if (BINDINGS.findByUserId(userId) == null) {
            return error(404, "当前账号尚未绑定任何 QQ");
        }
        try {
            BINDINGS.unbindByUserId(userId);
        } catch (RuntimeException e) {
            return error(500, "解绑失败：" + e.getMessage());
        }
        return okMessage("已解绑。现在可以在 QQ 里用 /绑定 重新绑定其他账号。");
    }

    // ==================== 改密 ====================

    /** 修改密码：必须验证原密码。 */
    @POST
    @Path("/password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response changePassword(String body) {
        Long userId = currentUserId();
        if (userId == null) {
            return error(401, "请先登录");
        }
        if (!RateLimiter.allow("player_chpwd_" + userId, 5, 60)) {
            return error(429, "操作过于频繁，请稍后再试");
        }
        JsonObject json = parse(body);
        String oldPassword = optString(json, "oldPassword");
        String newPassword = optString(json, "newPassword");
        if (oldPassword == null || oldPassword.isEmpty()) {
            return error(400, "原密码不能为空");
        }
        if (newPassword == null || newPassword.isEmpty()) {
            return error(400, "新密码不能为空");
        }
        if (!userService.verifyPassword(userId, oldPassword)) {
            return error(401, "原密码错误");
        }
        // 校验规则（长度、与原密码不同等）以 UserService 为准，不在这里复制第二套
        return userService.changePassword(userId, oldPassword, newPassword);
    }

    // ==================== 注销 ====================

    /** 注销账号：验密码后级联删除角色、绑定、权限与账号本身，不可逆。 */
    @POST
    @Path("/delete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteAccount(String body) {
        Long userId = currentUserId();
        if (userId == null) {
            return error(401, "请先登录");
        }
        if (!RateLimiter.allow("player_delete_" + userId, 3, 300)) {
            return error(429, "操作过于频繁，请稍后再试");
        }
        JsonObject json = parse(body);
        String password = optString(json, "password");
        if (password == null || password.isEmpty()) {
            return error(400, "请输入密码确认");
        }
        if (!userService.verifyPassword(userId, password)) {
            return error(401, "密码错误");
        }
        String username = UserAccountDAO.getUsernameByUserId(userId);
        try {
            // 顺序与聊天通道一致：先删角色（级联清角色相关表），再解绑，最后删账号与权限。
            // 反过来会留下"账号没了、角色还在"的孤儿行。
            PlayerInfo player = services.getPlayerService().getPlayerByUserId(userId);
            if (player != null) {
                services.getPlayerService().deletePlayer(player.getId());
            }
            try {
                BINDINGS.unbindByUserId(userId);
            } catch (RuntimeException ignored) {
                // 没绑定过就没有可解的，不影响删号
            }
            Boolean deleted = DatabaseManager.runTransaction(conn -> {
                PERMISSION_DAO.deleteAllUserRoles(conn, userId);
                PERMISSION_DAO.deleteAllUserPermissions(conn, userId);
                return USER_DAO.deleteUserById(conn, userId);
            });
            if (!Boolean.TRUE.equals(deleted)) {
                return error(404, "账号不存在");
            }
        } catch (RuntimeException e) {
            return error(500, "注销失败：" + e.getMessage());
        }
        return okMessage("账号（" + (username == null ? "未知" : username) + "）已注销，角色与绑定数据已清除。");
    }

    // ==================== 工具 ====================

    /** 从请求上下文取当前玩家 ID（由 {@link AdminAuthFilter} 的玩家自我服务分支设置）。 */
    private Long currentUserId() {
        Object value = requestContext == null ? null : requestContext.getProperty("userId");
        return value instanceof Number n ? n.longValue() : null;
    }

    private static JsonObject parse(String body) {
        try {
            return body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String optString(JsonObject json, String key) {
        try {
            return json != null && json.has(key) && !json.get(key).isJsonNull()
                    ? json.get(key).getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** openid 只回显尾部 6 位。 */
    private static String tail(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= 6 ? value : "…" + value.substring(value.length() - 6);
    }

    private static Response ok(JsonObject data) {
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        if (data != null) {
            out.add("data", data);
        }
        return Response.ok(out.toString()).build();
    }

    private static Response okMessage(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        out.addProperty("message", message);
        return Response.ok(out.toString()).build();
    }

    private static Response error(int status, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("code", status);
        out.addProperty("message", message);
        return Response.status(status).entity(out.toString()).build();
    }
}
