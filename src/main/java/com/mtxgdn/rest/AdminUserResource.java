package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.PermissionCode;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mtxgdn.rest.AdminSupport.gson;

/**
 * 管理台 · 账号与权限接口（角色、权限码、权限组、用户增删改、用户单独授权）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：{@code /roles}、
 * {@code /permissions}、{@code /groups/**}、{@code /users}、{@code /user/**}
 * 全部围绕 {@code PermissionService} 与 users / user_roles / user_permissions
 * 这几张表，是同一个依赖集合，因此按"路径前缀 + 依赖集合"整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码（{@code admin.roles.manage} /
 * {@code admin.users.manage}）、返回结构、HTTP 状态码全部保持原样，
 * 前端 {@code /api/admin/roles|permissions|groups|users|user/*} 不受影响。
 * 尤其是 {@code DELETE /user/{userId}/role/{roleName}} 里那三条显式校验
 * （不能移除默认角色 / 用户没这个角色回 404）一字未改。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /groups/{name}} 拼出来的地址与拆分前
 * 逐字一致（Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminUserResource {

    @GET
    @Path("/roles")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response getRoles() {
        JsonArray roles = new JsonArray();
        for (Map.Entry<String, Integer> entry : PermissionService.getRoleHierarchy().entrySet()) {
            JsonObject role = new JsonObject();
            role.addProperty("name", entry.getKey());
            role.addProperty("level", entry.getValue());
            role.addProperty("displayName", PermissionService.getGroupDisplayName(entry.getKey()));

            JsonArray perms = new JsonArray();
            for (String permCode : PermissionService.getGroupPermissionCodes(entry.getKey())) {
                JsonObject perm = new JsonObject();
                perm.addProperty("code", permCode);
                PermissionCode pc = PermissionCode.fromCode(permCode);
                if (pc != null) {
                    perm.addProperty("name", pc.getName());
                    perm.addProperty("category", pc.getCategory());
                } else if (PermissionService.isPluginPermission(permCode)) {
                    PermissionService.PluginPermissionInfo info = PermissionService.getPluginPermissions().get(permCode);
                    perm.addProperty("name", info.name);
                    perm.addProperty("category", info.category);
                } else {
                    perm.addProperty("name", permCode);
                    perm.addProperty("category", "");
                }
                perms.add(perm);
            }
            role.add("permissions", perms);
            roles.add(role);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("roles", roles);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/permissions")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response getPermissions() {
        JsonArray perms = new JsonArray();
        for (PermissionCode pc : PermissionCode.values()) {
            JsonObject perm = new JsonObject();
            perm.addProperty("code", pc.getCode());
            perm.addProperty("name", pc.getName());
            perm.addProperty("category", pc.getCategory());
            perms.add(perm);
        }
        // 也列出插件权限
        for (PermissionService.PluginPermissionInfo info : PermissionService.getPluginPermissions().values()) {
            JsonObject perm = new JsonObject();
            perm.addProperty("code", info.code);
            perm.addProperty("name", info.name);
            perm.addProperty("category", info.category);
            perms.add(perm);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("permissions", perms);
        return Response.ok(gson.toJson(result)).build();
    }

    // ========== 权限组管理 API ==========

    @GET
    @Path("/groups")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response getGroups() {
        List<Map<String, Object>> groups = PermissionService.getAllGroups();
        JsonArray arr = new JsonArray();
        for (Map<String, Object> g : groups) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", (String) g.get("name"));
            obj.addProperty("displayName", (String) g.get("displayName"));
            obj.addProperty("level", (Integer) g.get("level"));
            obj.addProperty("permissionCount", (Integer) g.get("permissionCount"));
            obj.addProperty("system", (Boolean) g.get("system"));

            JsonArray permsArr = new JsonArray();
            @SuppressWarnings("unchecked")
            List<String> permList = (List<String>) g.get("permissions");
            if (permList != null) {
                for (String code : permList) {
                    JsonObject po = new JsonObject();
                    po.addProperty("code", code);
                    PermissionCode pc = PermissionCode.fromCode(code);
                    if (pc != null) {
                        po.addProperty("name", pc.getName());
                        po.addProperty("category", pc.getCategory());
                    } else if (PermissionService.isPluginPermission(code)) {
                        PermissionService.PluginPermissionInfo info = PermissionService.getPluginPermissions().get(code);
                        po.addProperty("name", info.name);
                        po.addProperty("category", info.category);
                    } else {
                        po.addProperty("name", code);
                        po.addProperty("category", "");
                    }
                    permsArr.add(po);
                }
            }
            obj.add("permissions", permsArr);
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("groups", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/groups")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response createGroup(String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String name = req.has("name") ? req.get("name").getAsString() : null;
        String displayName = req.has("displayName") ? req.get("displayName").getAsString() : null;
        int level = req.has("level") ? req.get("level").getAsInt() : 10;

        if (name == null || name.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.permission_group_name_cannot"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        try {
            PermissionService.createGroup(name, displayName, level);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.permission_group_created"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/groups/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response updateGroup(@PathParam("name") String groupName, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String displayName = req.has("displayName") ? req.get("displayName").getAsString() : null;
        int level = req.has("level") ? req.get("level").getAsInt() : -1;

        try {
            // 如果 level 没传，保持现有等级
            if (level < 0) {
                Integer existingLevel = PermissionService.getRoleHierarchy().get(groupName);
                if (existingLevel != null) level = existingLevel;
            }
            PermissionService.updateGroup(groupName, displayName, level);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.permission_group_updated"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/groups/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response deleteGroup(@PathParam("name") String groupName) {
        try {
            PermissionService.deleteGroup(groupName);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.permission_group_deleted"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/groups/{name}/permissions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response addGroupPermission(@PathParam("name") String groupName, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String permissionCode = req.has("permission") ? req.get("permission").getAsString() : null;

        if (permissionCode == null || permissionCode.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.permission_code_cannot_be_empty"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        try {
            PermissionService.addGroupPermission(groupName, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.permission_added_to_the_group"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/groups/{name}/permissions/{permissionCode}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response removeGroupPermission(
            @PathParam("name") String groupName,
            @PathParam("permissionCode") String permissionCode) {
        try {
            PermissionService.removeGroupPermission(groupName, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.permission_removed_from_the_group"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/groups/{name}/permissions/set")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response setGroupPermissions(@PathParam("name") String groupName, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        JsonArray permArr = req.has("permissions") ? req.getAsJsonArray("permissions") : new JsonArray();

        Set<String> permissionCodes = new java.util.HashSet<>();
        for (int i = 0; i < permArr.size(); i++) {
            permissionCodes.add(permArr.get(i).getAsString());
        }

        try {
            PermissionService.setGroupPermissions(groupName, permissionCodes);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.permission_group_permissions_updated"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/users")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response getUsersWithRoles() {
        List<Map<String, Object>> users = PermissionService.getAllUsersWithRoles();

        JsonArray arr = new JsonArray();
        for (Map<String, Object> user : users) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", ((Number) user.get("id")).longValue());
            obj.addProperty("username", (String) user.get("username"));
            JsonArray roles = new JsonArray();
            @SuppressWarnings("unchecked")
            List<String> roleList = (List<String>) user.get("roles");
            if (roleList != null) {
                for (String role : roleList) {
                    roles.add(role);
                }
            }
            obj.add("roles", roles);
            // 单独分配的权限
            JsonArray directPerms = new JsonArray();
            @SuppressWarnings("unchecked")
            List<String> permList = (List<String>) user.get("directPermissions");
            if (permList != null) {
                for (String perm : permList) {
                    directPerms.add(perm);
                }
            }
            obj.add("directPermissions", directPerms);
            // 全部有效权限
            JsonArray allPerms = new JsonArray();
            for (String code : PermissionService.getAllUserPermissionCodes(((Number) user.get("id")).longValue())) {
                allPerms.add(code);
            }
            obj.add("allPermissions", allPerms);
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("users", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/user/{userId}/role")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response assignRole(@PathParam("userId") long userId, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String roleName = req.has("role") ? req.get("role").getAsString() : null;

        if (roleName == null || !PermissionService.getRoleNames().contains(roleName)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.invalid_role_name"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        PermissionService.assignRole(userId, roleName);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.role_assigned_successfully"));
        return Response.ok(gson.toJson(result)).build();
    }

    @DELETE
    @Path("/user/{userId}/role/{roleName}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response removeRole(@PathParam("userId") long userId, @PathParam("roleName") String roleName) {
        if (!PermissionService.getRoleNames().contains(roleName)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.invalid_role_name"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        // 原来这里是 currentLevel <= roleLevel 才移除，方向是反的：
        // currentLevel 是目标用户的最高角色等级，必然 >= 他持有的任何角色等级，
        // 于是"摘掉低于他最高角色的那个角色"永远不生效，接口却照样回 200 成功。
        // 现在改为显式检查三件事，并给出诚实的响应。
        if (PermissionService.getDefaultRoleName().equals(roleName)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.cannot_remove_default_role"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }
        if (!PermissionService.getUserRoles(userId).contains(roleName)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.user_role_not_held"));
            return Response.status(404).entity(gson.toJson(err)).build();
        }
        PermissionService.removeRole(userId, roleName);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.role_removed_successfully"));
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/user/create-admin")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response createAdminUser(String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";
        String role = req.has("role") ? req.get("role").getAsString() : "ADMIN";

        if (username.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.username_cannot_be_empty"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }
        if (password.length() < 6) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.password_must_be_at_least"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }
        if (!PermissionService.getRoleNames().contains(role)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.invalid_role_name"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        String sql = "SELECT id FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JsonObject err = new JsonObject();
                    err.addProperty("code", 409);
                    err.addProperty("message", I18n.t("admin.username_already_exists"));
                    return Response.status(409).entity(gson.toJson(err)).build();
                }
            }

            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            String insertSql = "INSERT INTO users (username, password) VALUES (?, ?)";
            try (PreparedStatement ips = conn.prepareStatement(insertSql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ips.setString(1, username);
                ips.setString(2, hashedPassword);
                ips.executeUpdate();
                try (ResultSet keys = ips.getGeneratedKeys()) {
                    if (keys.next()) {
                        long userId = keys.getLong(1);
                        PermissionService.assignRole(userId, role);
                        PermissionService.assignRole(userId, "PLAYER");

                        JsonObject result = new JsonObject();
                        result.addProperty("code", 200);
                        result.addProperty("message", I18n.t("admin.admin_account_created"));
                        result.addProperty("userId", userId);
                        result.addProperty("username", username);
                        result.addProperty("role", role);
                        return Response.ok(gson.toJson(result)).build();
                    }
                }
            }
        } catch (SQLException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.creation_failed_2", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }

        JsonObject err = new JsonObject();
        err.addProperty("code", 500);
        err.addProperty("message", I18n.t("admin.creation_failed"));
        return Response.status(500).entity(gson.toJson(err)).build();
    }

    @POST
    @Path("/user/{userId}/password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response setUserPassword(@PathParam("userId") long userId, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String newPassword = req.has("password") ? req.get("password").getAsString() : "";

        if (newPassword.length() < 6) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.password_must_be_at_least"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        String hashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        String sql = "UPDATE users SET password = ?, failed_attempts = 0, locked_until = NULL WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hashedPassword);
            ps.setLong(2, userId);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 404);
                err.addProperty("message", I18n.t("admin.user_does_not_exist"));
                return Response.status(404).entity(gson.toJson(err)).build();
            }

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.password_changed_successfully"));
            return Response.ok(gson.toJson(result)).build();
        } catch (SQLException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.modification_failed_2", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/user/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response deleteUser(@PathParam("userId") long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_roles WHERE user_id = ?")) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_permissions WHERE user_id = ?")) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
                String sql = "DELETE FROM users WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, userId);
                    int affected = ps.executeUpdate();
                    if (affected == 0) {
                        conn.rollback();
                        JsonObject err = new JsonObject();
                        err.addProperty("code", 404);
                        err.addProperty("message", I18n.t("admin.user_does_not_exist"));
                        return Response.status(404).entity(gson.toJson(err)).build();
                    }
                }
                conn.commit();

                JsonObject result = new JsonObject();
                result.addProperty("code", 200);
                result.addProperty("message", I18n.t("admin.user_deleted"));
                return Response.ok(gson.toJson(result)).build();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.delete_failed_2", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }
    }

    // ========== 用户单独权限管理 ==========

    @GET
    @Path("/user/{userId}/permissions")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response getUserDirectPermissions(@PathParam("userId") long userId) {
        JsonArray perms = new JsonArray();
        for (PermissionCode pc : PermissionService.getUserDirectPermissions(userId)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("code", pc.getCode());
            obj.addProperty("name", pc.getName());
            obj.addProperty("category", pc.getCategory());
            perms.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("directPermissions", perms);

        JsonArray effectivePerms = new JsonArray();
        for (String code : PermissionService.getAllUserPermissionCodes(userId)) {
            effectivePerms.add(code);
        }
        result.add("allPermissions", effectivePerms);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/user/{userId}/permissions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response assignUserPermission(@PathParam("userId") long userId, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String permissionCode = req.has("permission") ? req.get("permission").getAsString() : null;

        if (permissionCode == null || permissionCode.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.permission_code_cannot_be_empty"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        if (!PermissionService.isValidPermissionCode(permissionCode)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.invalid_permission_code", permissionCode));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        try {
            PermissionService.assignPermission(userId, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.permission_assigned_successfully"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.assignment_failed", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/user/{userId}/permissions/{permissionCode}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response removeUserPermission(@PathParam("userId") long userId, @PathParam("permissionCode") String permissionCode) {
        try {
            PermissionService.removePermission(userId, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.permission_removed_successfully"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.removal_failed", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }
    }
}
