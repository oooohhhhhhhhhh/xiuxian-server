package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.service.UserService;
import com.mtxgdn.util.JwtUtil;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/auth")
public class Auth {

    private final UserService userService = new UserService();
    private final PlayerService playerService = new PlayerService();

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

        Response resp = userService.register(username, password);
        System.out.println("[Auth] <<< POST /register (" + (System.currentTimeMillis() - start) + "ms) status=" + resp.getStatus());
        return resp;
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
