package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.service.UserService;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.EmailService;
import com.mtxgdn.util.JwtUtil;
import jakarta.mail.MessagingException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/auth")
public class Auth {

    private final UserService userService = new UserService();
    private final PlayerService playerService = new PlayerService();
    private final VerificationCodeService verificationCodeService = new VerificationCodeService();

    @Context
    private ContainerRequestContext requestContext;

    private Long getCurrentUserId() {
        String authHeader = requestContext.getHeaderString("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return JwtUtil.extractUserId(token);
        }
        return null;
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
    public Response sendCode(String body) {
        System.out.println("[Auth] >>> POST /send-code");
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String email = json.has("email") ? json.get("email").getAsString() : null;

        if (email == null || email.trim().isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", "邮箱不能为空");
            return Response.status(400).entity(err.toString()).build();
        }

        String trimmedEmail = email.trim();
        if (!isValidEmail(trimmedEmail)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", "邮箱格式不正确");
            return Response.status(400).entity(err.toString()).build();
        }

        try {
            String verificationCode = verificationCodeService.generateAndStoreCode(trimmedEmail);
            EmailService.sendVerificationCode(trimmedEmail, verificationCode);

            JsonObject data = new JsonObject();
            data.addProperty("message", "验证码已发送，请查收邮件");
            return Response.ok(data.toString()).build();
        } catch (MessagingException e) {
            System.err.println("[Auth] 发送邮件失败: " + e.getMessage());
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "发送验证码失败，请稍后重试");
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
    public Response login(String body) {
        System.out.println("[Auth] >>> POST /login");
        long start = System.currentTimeMillis();

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

        String username = json.has("username") ? json.get("username").getAsString() : null;
        String password = json.has("password") ? json.get("password").getAsString() : null;

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
            err.addProperty("message", "请先登录");
            return Response.status(401).entity(err.toString()).build();
        }

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String oldPassword = json.has("oldPassword") ? json.get("oldPassword").getAsString() : null;
        String newPassword = json.has("newPassword") ? json.get("newPassword").getAsString() : null;

        return userService.changePassword(userId, oldPassword, newPassword);
    }

    @DELETE
    @Path("/account")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteAccount() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", "请先登录");
            return Response.status(401).entity(err.toString()).build();
        }

        var player = playerService.getPlayerByUserId(userId);
        if (player != null) {
            playerService.deletePlayer(player.getId());
        }

        return userService.deleteUser(userId);
    }
}
