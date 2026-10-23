package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.plugin.PluginWebManager;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.File;
import java.util.List;
import java.util.Map;

import static com.mtxgdn.rest.AdminSupport.gson;

/**
 * 管理台 · 插件管理接口（页面清单、加载/卸载/重载、启停、文件监听）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：{@code /plugins/**}
 * 全部围绕 {@code PluginManager} / {@code BuiltInPluginRegistry} 这一套插件基础设施，
 * 与其它管理端点的依赖集合完全不重叠，因此按"路径前缀 + 依赖集合"整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码（{@code admin.plugins.manage}）、
 * 返回结构全部保持原样，前端 {@code /api/admin/plugins/*} 不受影响。
 * 尤其是 {@code /plugins/load/{jarName}} 里的路径穿越校验，一字未改。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /plugins/list} 拼出来的地址与拆分前
 * 逐字一致（Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminPluginsResource {

    // ========== 插件管理 API ==========

    @GET
    @Path("/plugins")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response getPluginPages() {
        com.google.gson.JsonArray plugins = PluginWebManager.getInstance().getPluginPagesJson();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("plugins", plugins);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/plugins/list")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response getPluginList() {
        JsonArray arr = new JsonArray();

        // 添加内置插件
        List<Map<String, Object>> builtInStatuses = com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().getStatuses();
        for (Map<String, Object> s : builtInStatuses) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "builtin");
            for (Map.Entry<String, Object> entry : s.entrySet()) {
                Object val = entry.getValue();
                if (val == null) {
                    obj.add(entry.getKey(), null);
                } else if (val instanceof Boolean) {
                    obj.addProperty(entry.getKey(), (Boolean) val);
                } else if (val instanceof Number) {
                    obj.addProperty(entry.getKey(), (Number) val);
                } else {
                    obj.addProperty(entry.getKey(), String.valueOf(val));
                }
            }
            arr.add(obj);
        }

        // 添加外部插件
        List<Map<String, Object>> externalStatuses = com.mtxgdn.plugin.PluginManager.getInstance().getPluginStatuses();
        for (Map<String, Object> s : externalStatuses) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "external");
            for (Map.Entry<String, Object> entry : s.entrySet()) {
                Object val = entry.getValue();
                if (val == null) {
                    obj.add(entry.getKey(), null);
                } else if (val instanceof Boolean) {
                    obj.addProperty(entry.getKey(), (Boolean) val);
                } else if (val instanceof Number) {
                    obj.addProperty(entry.getKey(), (Number) val);
                } else {
                    obj.addProperty(entry.getKey(), String.valueOf(val));
                }
            }
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("plugins", arr);
        result.addProperty("watcherRunning", com.mtxgdn.plugin.PluginManager.getInstance().isFileWatcherRunning());
        // 内置插件开关落盘位置（WebUI 提示用户去哪里改配置）
        result.addProperty("pluginConfigFile", com.mtxgdn.plugin.PluginSwitches.getConfigPath().toString());
        result.addProperty("pluginConfigKeyPrefix", com.mtxgdn.plugin.PluginSwitches.KEY_PREFIX);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/reload/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response reloadPlugin(@PathParam("name") String name) {
        boolean ok = com.mtxgdn.plugin.PluginManager.getInstance().reloadPlugin(name);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 404);
        result.addProperty("message", ok ? I18n.t("admin.plugin.reloaded", name) : I18n.t("admin.plugin.not_found", name));
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/unload/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response unloadPlugin(@PathParam("name") String name) {
        boolean ok = com.mtxgdn.plugin.PluginManager.getInstance().unloadPlugin(name);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 404);
        result.addProperty("message", ok ? I18n.t("admin.plugin.unloaded", name) : I18n.t("admin.plugin.not_found", name));
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/enable/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response enablePlugin(@PathParam("name") String name, @QueryParam("type") String type) {
        boolean ok = enableDisablePlugin(name, type, true);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 400);
        result.addProperty("message", ok ? I18n.t("admin.plugin.enabled", name) : I18n.t("admin.plugin.enable_failed", name));
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/disable/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response disablePlugin(@PathParam("name") String name, @QueryParam("type") String type) {
        boolean ok = enableDisablePlugin(name, type, false);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 400);
        result.addProperty("message", ok ? I18n.t("admin.plugin.disabled", name) : I18n.t("admin.plugin.disable_failed", name));
        return Response.ok(gson.toJson(result)).build();
    }

    /**
     * 启用/停用插件并按类型路由：
     * 内置插件（编译在主 jar 中，如 farm/sect）由 BuiltInPluginRegistry 管理，
     * 外部插件（plugins/*.jar）由 PluginManager 管理。
     * type 未指定时按名字自动路由（内置插件不在 PluginManager 中，直接调用会返回失败）。
     */
    private boolean enableDisablePlugin(String name, String type, boolean enable) {
        boolean isBuiltIn = "builtin".equalsIgnoreCase(type)
                || (type == null && com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().getRegisteredNames().contains(name));
        if (isBuiltIn) {
            return enable
                    ? com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().enablePlugin(name)
                    : com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().disablePlugin(name);
        }
        return enable
                ? com.mtxgdn.plugin.PluginManager.getInstance().enablePlugin(name)
                : com.mtxgdn.plugin.PluginManager.getInstance().disablePlugin(name);
    }

    @POST
    @Path("/plugins/load/{jarName}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response loadPlugin(@PathParam("jarName") String jarName) {
        // S4 修复：路径校验 — 只允许 plugins/ 目录下的 .jar 文件，禁止路径穿越与绝对路径
        if (jarName == null || jarName.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.jarname_cannot_be_empty"));
            return Response.ok(gson.toJson(err)).build();
        }
        if (jarName.contains("..") || jarName.contains("/") || jarName.contains("\\")
                || jarName.contains("\0") || !jarName.toLowerCase().endsWith(".jar")) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.illegal_plugin_filename"));
            return Response.ok(gson.toJson(err)).build();
        }
        File pluginsDir = new File("plugins").getAbsoluteFile();
        File jarFile = new File(pluginsDir, jarName).getAbsoluteFile();
        // 再次校验：解析后的真实路径必须在 pluginsDir 下
        try {
            if (!jarFile.getCanonicalPath().startsWith(pluginsDir.getCanonicalPath() + File.separator)
                    && !jarFile.getCanonicalPath().equals(pluginsDir.getCanonicalPath())) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 400);
                err.addProperty("message", I18n.t("admin.illegal_plugin_path"));
                return Response.ok(gson.toJson(err)).build();
            }
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.path_resolution_failed"));
            return Response.ok(gson.toJson(err)).build();
        }
        if (!jarFile.exists()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.plugin_file_not_found", jarName));
            return Response.ok(gson.toJson(err)).build();
        }
        boolean ok = com.mtxgdn.plugin.PluginManager.getInstance().loadPlugin(jarFile);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 500);
        result.addProperty("message", ok ? I18n.t("admin.plugin.loaded", jarName) : I18n.t("admin.plugin.load_failed", jarName));
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/watcher/start")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response startFileWatcher() {
        com.mtxgdn.plugin.PluginManager.getInstance().startFileWatcher();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.file_watcher_started"));
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/watcher/stop")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response stopFileWatcher() {
        com.mtxgdn.plugin.PluginManager.getInstance().stopFileWatcher();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.file_watcher_stopped"));
        return Response.ok(gson.toJson(result)).build();
    }
}
