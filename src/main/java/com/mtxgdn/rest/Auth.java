package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.mtxgdn.service.UserService;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/auth")
public class Auth {

    private final UserService userService = new UserService();

    @POST
    @Path("/register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(String body) {
        System.out.println("[Auth] >>> POST /register body=" + (body != null ? body.substring(0, Math.min(100, body.length())) : "null"));
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
        System.out.println("[Auth] >>> POST /login body=" + (body != null ? body.substring(0, Math.min(100, body.length())) : "null"));
        long start = System.currentTimeMillis();

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

        String username = json.has("username") ? json.get("username").getAsString() : null;
        String password = json.has("password") ? json.get("password").getAsString() : null;

        Response resp = userService.login(username, password);
        System.out.println("[Auth] <<< POST /login (" + (System.currentTimeMillis() - start) + "ms) status=" + resp.getStatus());
        return resp;
    }
}
