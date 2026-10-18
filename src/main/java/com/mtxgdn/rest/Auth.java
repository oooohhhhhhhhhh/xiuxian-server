package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.service.UserService;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.EmailService;
import com.mtxgdn.util.JwtUtil;
import com.mtxgdn.util.RateLimiter;
import com.mtxgdn.util.TokenBlacklist;
import jakarta.mail.MessagingException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.mtxgdn.i18n.I18n;

@Path("/auth")
public class Auth {

    private final UserService userService = new UserService();
    private final PlayerService playerService = new PlayerService();
    private final VerificationCodeService verificationCodeService = new VerificationCodeService();

    @Context
    private ContainerRequestContext requestContext;

    /**
     * V5 修复：取当前登录用户 ID 时，必须完成与 {@link JwtAuthFilter} 一致的完整校验
     * （签名/过期 + 黑名单），不能再直接 {@code extractUserId}。
     * <p>
     * 原实现的三个问题：
     * <ol>
     *   <li>不查 {@link TokenBlacklist}——已登出的 token 仍可改密/删号；</li>
     *   <li>不先 {@code validateToken}——过期 token 会在 {@code extractUserId} 内部抛
     *       {@code ExpiredJwtException}，冒泡成 500 而不是 401；</li>
     *   <li>token 格式非法（非数字 subject）同样抛异常变 500。</li>
     * </ol>
     * 任一环节失败一律返回 {@code null}，由调用方统一回 401。
     */
    private Long getCurrentUserId() {
        String authHeader = requestContext.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.isEmpty() || TokenBlacklist.isBlacklisted(token)) {
            return null;
        }
        if (!JwtUtil.validateToken(token)) {
            return null;
        }
        try {
            return JwtUtil.extractUserId(token);
        } catch (Exception e) {
            // subject 非数字等异常 token：视为未登录，绝不冒泡成 500
            return null;
        }
    }

    /**
     * V2 修复：客户端指纹不再无条件信任 {@code X-Forwarded-For}。
     * <p>
     * 原实现直接取 XFF 首段作为限流 key，任何人都能伪造该头拿到无限多个
     * "新 IP 桶"，从而绕开按 IP 的邮件限流（配合任意新邮箱即可无限发信）。
     * <p>
     * 现在：仅在 {@code security.trust_proxy_headers=true}（部署在可信反代之后）
     * 时才采信 XFF，否则一律使用 TCP 直连地址。反代场景下也应只信任最后一跳，
     * 因此取 XFF 的<b>最后一段</b>而非第一段（第一段由客户端完全可控）。
     */
    private String clientBucket(org.glassfish.grizzly.http.server.Request grizzlyReq) {
        if (AppConfig.getBoolean("security.trust_proxy_headers", false)) {
            String fwd = requestContext.getHeaderString("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                String[] hops = fwd.split(",");
                // 最后一跳由可信反代写入，客户端无法伪造
                return hops[hops.length - 1].trim();
            }
        }
        return remoteAddress(grizzlyReq);
    }

    /**
     * TCP 直连地址；容器不支持时回退为全局桶（宁可粗粒度限流，也不放行伪造 IP）。
     *
     * <p><b>V8 修复</b>：原实现只从
     * {@code ContainerRequestContext.getProperty("org.glassfish.grizzly.http.server.Request")}
     * 取地址，但在 Jersey + Grizzly 这套容器里该属性并不存在（属性表里只有
     * TracingLogger），于是永远回退成 {@code "global"} —— 登录限流与邮箱验证码限流
     * 全部退化成「全服共享一个桶」，20 次/60 秒是所有人共用的额度，而不是按 IP。
     * 现在改为优先用 {@code @Context} 注入的
     * {@link org.glassfish.grizzly.http.server.Request}（GrizzlyBinder 以 RequestScoped 绑定）。</p>
     */
    private String remoteAddress(org.glassfish.grizzly.http.server.Request grizzlyReq) {
        String remote = null;
        if (grizzlyReq != null) {
            try {
                remote = grizzlyReq.getRemoteAddr();
            } catch (Throwable ignored) {
                // 落到下面的属性表兜底
            }
        }
        if (remote == null) {
            try {
                Object addr = requestContext.getProperty("org.glassfish.grizzly.http.server.Request");
                if (addr instanceof org.glassfish.grizzly.http.server.Request req) {
                    remote = req.getRemoteAddr();
                }
            } catch (Throwable ignored) {
                // 非 Grizzly 容器或属性不可用时走回退
            }
        }
        return (remote != null && !remote.isBlank()) ? remote : "global";
    }

    /**
     * V2 修复：验证码发送的多层限流检查（注册与找回密码两个入口共用）。
     * <p>
     * 三层桶的意义：
     * <ul>
     *   <li><b>来源 10/分</b>：拦截突发脚本；</li>
     *   <li><b>来源 30/时</b>：即使攻击者分散到分钟边界，单来源小时总量仍被封顶；</li>
     *   <li><b>全局 300/时</b>：兜底防止分布式（多 IP + 任意新邮箱）把 SMTP 配额打满，
     *       邮箱服务商通常按小时计费/限流，全局桶保证最坏情况成本可控。</li>
     * </ul>
     *
     * @return 需要拒绝时返回 429 响应；放行时返回 {@code null}
     */
    private Response checkSendCodeRateLimit(org.glassfish.grizzly.http.server.Request grizzlyReq) {
        String bucket = clientBucket(grizzlyReq);

        if (!RateLimiter.allow("send_code_ip:" + bucket, 10, 60)) {
            return rateLimitResponse("发送过于频繁，请稍后再试");
        }
        if (!RateLimiter.allow("send_code_hour:" + bucket, 30, 3600)) {
            return rateLimitResponse("发送次数过多，请稍后再试");
        }
        if (!RateLimiter.allow("send_code_global", 300, 3600)) {
            return rateLimitResponse("验证码服务繁忙，请稍后再试");
        }
        return null;
    }

    private Response rateLimitResponse(String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", 429);
        err.addProperty("message", message);
        return Response.status(429).entity(err.toString()).build();
    }

    @POST
    @Path("/register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(String body) {
        System.out.println("[Auth] >>> POST /register");
        long start = System.currentTimeMillis();

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

        String username = json.has("username") ? json.get("username").getAsString() : null;
        String password = json.has("password") ? json.get("password").getAsString() : null;
        String email = json.has("email") ? json.get("email").getAsString() : null;
        String code = json.has("code") ? json.get("code").getAsString() : null;

        Response resp = userService.register(username, password, email, code);
        System.out.println("[Auth] <<< POST /register (" + (System.currentTimeMillis() - start) + "ms) status=" + resp.getStatus());
        return resp;
    }

    @POST
    @Path("/send-code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendCode(@Context org.glassfish.grizzly.http.server.Request grizzlyReq, String body) {
        System.out.println("[Auth] >>> POST /send-code");
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String email = json.has("email") ? json.get("email").getAsString() : null;

        if (email == null || email.trim().isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("auth.h45338de2"));
            return Response.status(400).entity(err.toString()).build();
        }

        String trimmedEmail = email.trim();
        if (!isValidEmail(trimmedEmail)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("auth.h21c46d78"));
            return Response.status(400).entity(err.toString()).build();
        }

        // V2 修复：多层限流（来源 10/分 + 来源 30/时 + 全局 300/时）
        Response rateLimited = checkSendCodeRateLimit(grizzlyReq);
        if (rateLimited != null) {
            return rateLimited;
        }

        try {
            String verificationCode = verificationCodeService.generateAndStoreCode(trimmedEmail);
            EmailService.sendVerificationCode(trimmedEmail, verificationCode);

            JsonObject data = new JsonObject();
            data.addProperty("message", I18n.t("auth.h70c7a34a"));
            return Response.ok(data.toString()).build();
        } catch (MessagingException e) {
            System.err.println("[Auth] 发送邮件失败: " + e.getMessage());
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("auth.hd2e81c54"));
            return Response.status(500).entity(err.toString()).build();
        } catch (RuntimeException e) {
            System.err.println("[Auth] 验证码服务异常: " + e.getMessage());
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "发送验证码失败：" + e.getMessage());
            return Response.status(500).entity(err.toString()).build();
        }
    }

    private boolean isValidEmail(String email) {
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    }

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(@Context org.glassfish.grizzly.http.server.Request grizzlyReq, String body) {
        System.out.println("[Auth] >>> POST /login");
        long start = System.currentTimeMillis();

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

        String username = json.has("username") ? json.get("username").getAsString() : null;
        String password = json.has("password") ? json.get("password").getAsString() : null;

        // V3 修复：除按用户名限流外，必须叠加"按客户端"限流。
        // 原实现只按 username 计数（5 次/60s），攻击者可批量注册成千上万个账号
        // 逐个撞库，每个账号都不触发锁定。叠加来源维度后单机撞库速率被压到
        // 20 次/分钟，无论换多少用户名都受限。
        if (!RateLimiter.allow("login_ip:" + clientBucket(grizzlyReq), 20, 60)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 429);
            err.addProperty("message", I18n.t("auth.h9c54f628"));
            return Response.status(429).entity(err.toString()).build();
        }

        if (username != null && !username.trim().isEmpty()) {
            String rateLimitKey = "login_" + username.trim().toLowerCase();
            if (!RateLimiter.allow(rateLimitKey, 5, 60)) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 429);
                err.addProperty("message", I18n.t("auth.h9c54f628"));
                return Response.status(429).entity(err.toString()).build();
            }
        }

        Response resp = userService.login(username, password);
        System.out.println("[Auth] <<< POST /login (" + (System.currentTimeMillis() - start) + "ms) status=" + resp.getStatus());
        return resp;
    }

    @POST
    @Path("/change-password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response changePassword(String body) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", I18n.t("auth.he5c24b00"));
            return Response.status(401).entity(err.toString()).build();
        }

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String oldPassword = json.has("oldPassword") ? json.get("oldPassword").getAsString() : null;
        String newPassword = json.has("newPassword") ? json.get("newPassword").getAsString() : null;

        return userService.changePassword(userId, oldPassword, newPassword);
    }

    @POST
    @Path("/logout")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logout() {
        String authHeader = requestContext.getHeaderString("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            TokenBlacklist.invalidate(token);
        }

        JsonObject data = new JsonObject();
        data.addProperty("message", I18n.t("auth.hb273bcee"));
        return Response.ok(data.toString()).build();
    }

    @POST
    @Path("/forgot-password/send-code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendForgotPasswordCode(@Context org.glassfish.grizzly.http.server.Request grizzlyReq,
                                           String body) {
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String email = json.has("email") ? json.get("email").getAsString() : null;

        if (email == null || email.trim().isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("auth.h45338de2"));
            return Response.status(400).entity(err.toString()).build();
        }

        String trimmedEmail = email.trim().toLowerCase();
        if (!isValidEmail(trimmedEmail)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("auth.h21c46d78"));
            return Response.status(400).entity(err.toString()).build();
        }

        // V2 修复：多层限流（来源 10/分 + 来源 30/时 + 全局 300/时）
        Response rateLimited = checkSendCodeRateLimit(grizzlyReq);
        if (rateLimited != null) {
            return rateLimited;
        }

        try {
            String verificationCode = verificationCodeService.generateAndStoreCode(trimmedEmail);
            EmailService.sendVerificationCode(trimmedEmail, verificationCode);

            JsonObject data = new JsonObject();
            data.addProperty("message", I18n.t("auth.h70c7a34a"));
            return Response.ok(data.toString()).build();
        } catch (MessagingException e) {
            System.err.println("[Auth] 发送邮件失败: " + e.getMessage());
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("auth.hd2e81c54"));
            return Response.status(500).entity(err.toString()).build();
        } catch (RuntimeException e) {
            System.err.println("[Auth] 验证码服务异常: " + e.getMessage());
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "发送验证码失败：" + e.getMessage());
            return Response.status(500).entity(err.toString()).build();
        }
    }

    @POST
    @Path("/forgot-password/reset")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response resetPassword(String body) {
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String email = json.has("email") ? json.get("email").getAsString() : null;
        String code = json.has("code") ? json.get("code").getAsString() : null;
        String newPassword = json.has("newPassword") ? json.get("newPassword").getAsString() : null;

        if (email == null || email.trim().isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("auth.h45338de2"));
            return Response.status(400).entity(err.toString()).build();
        }

        if (code == null || code.trim().isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("auth.hd8155b98"));
            return Response.status(400).entity(err.toString()).build();
        }

        if (newPassword == null || newPassword.length() < 6) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("auth.hfbe8536f"));
            return Response.status(400).entity(err.toString()).build();
        }

        String trimmedEmail = email.trim().toLowerCase();
        if (verificationCodeService.isVerifyLocked(trimmedEmail)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 429);
            err.addProperty("message", I18n.t("auth.ha9aa782f"));
            return Response.status(429).entity(err.toString()).build();
        }
        if (!verificationCodeService.verifyCode(trimmedEmail, code.trim())) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("auth.h88dedbd9"));
            return Response.status(400).entity(err.toString()).build();
        }

        Response resp = userService.resetPasswordByEmail(trimmedEmail, newPassword);
        return resp;
    }

    @DELETE
    @Path("/account")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteAccount(String body) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", I18n.t("auth.he5c24b00"));
            return Response.status(401).entity(err.toString()).build();
        }

        // V13 修复：删号是不可逆操作，必须二次确认身份。
        // 原实现仅凭一个（可能被窃取的）Bearer token 就能删除账号与角色，
        // 一旦 token 泄露即造成不可恢复的数据丢失。
        // 现在要求 body 中携带当前密码（与 OneBot 通道的删号流程保持一致）。
        String password = null;
        try {
            if (body != null && !body.isBlank()) {
                JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                password = json.has("password") && !json.get("password").isJsonNull()
                        ? json.get("password").getAsString() : null;
            }
        } catch (Exception ignored) {
            // body 非法时按"未提供密码"处理
        }

        if (password == null || password.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("auth.hb543df00"));
            return Response.status(400).entity(err.toString()).build();
        }

        if (!userService.verifyPassword(userId, password)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 403);
            err.addProperty("message", I18n.t("auth.hdb26d337"));
            return Response.status(403).entity(err.toString()).build();
        }

        var player = playerService.getPlayerByUserId(userId);
        if (player != null) {
            playerService.deletePlayer(player.getId());
        }

        return userService.deleteUser(userId);
    }
}
