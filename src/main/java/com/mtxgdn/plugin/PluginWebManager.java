package com.mtxgdn.plugin;

import com.google.gson.JsonObject;
import com.mtxgdn.util.GameLogger;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.websockets.WebSocket;
import org.glassfish.jersey.server.ResourceConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件 Web 管理器。
 * 负责管理插件的 REST 端点、静态页面和 WebSocket 消息处理器的注册。
 * <p>
 * 在服务器启动时由 Main.java 初始化，提供给 PluginContext 使用。
 */
public final class PluginWebManager {

    private static final GameLogger LOG = GameLogger.getLogger(PluginWebManager.class);
    private static final PluginWebManager INSTANCE = new PluginWebManager();

    private HttpServer server;
    private ResourceConfig resourceConfig;
    private boolean initialized = false;

    /** 已注册的插件 REST 资源类 */
    private final Set<Class<?>> restResources = ConcurrentHashMap.newKeySet();

    /** 插件静态页面注册：pluginName -> { path -> WebPage } */
    private final Map<String, Map<String, WebPage>> pluginPages = new LinkedHashMap<>();

    /** 插件 WebSocket 消息处理器：messageType -> [handler] */
    private final Map<String, List<WsHandlerEntry>> wsHandlers = new ConcurrentHashMap<>();

    public static PluginWebManager getInstance() { return INSTANCE; }

    private PluginWebManager() {}

    /**
     * 初始化 Web 管理器。在 Main.java 中创建 HttpServer 后调用。
     */
    public synchronized void init(HttpServer server, ResourceConfig resourceConfig) {
        if (initialized) return;
        this.server = server;
        this.resourceConfig = resourceConfig;
        this.initialized = true;
    }

    /**
     * 注册插件 JAX-RS 资源类。资源类会注册到 Jersey，由 Jersey 自动管理路由。
     */
    public void registerRestResource(String pluginName, Class<?> resourceClass) {
        if (resourceConfig == null) {
            LOG.warn("PluginWebManager 尚未初始化，无法注册 REST 资源: " + resourceClass.getName());
            return;
        }
        if (restResources.add(resourceClass)) {
            try {
                resourceConfig.register(resourceClass);
                LOG.info("插件 [" + pluginName + "] 注册 REST 资源: " + resourceClass.getSimpleName());
            } catch (Exception e) {
                LOG.error("插件 [" + pluginName + "] 注册 REST 资源失败: " + resourceClass.getName(), e);
                restResources.remove(resourceClass);
            }
        }
    }

    /**
     * 注册插件静态 Web 页面。
     * 页面内容（HTML）由插件提供，会被挂载到 /admin/plugins/{pluginName}/{path} 下。
     *
     * @param pluginName 插件名称
     * @param path       页面路径，例如 "" 或 "settings"
     * @param title      页面标题，显示在管理后台导航中
     * @param htmlContent HTML 内容字节数组
     */
    public void registerWebPage(String pluginName, String path, String title, byte[] htmlContent) {
        pluginPages.computeIfAbsent(pluginName, k -> new LinkedHashMap<>())
                .put(path, new WebPage(title, htmlContent));
        LOG.info("插件 [" + pluginName + "] 注册 Web 页面: /admin/plugins/" + pluginName
                + (path.isEmpty() ? "" : "/" + path) + " (" + title + ")");

        // 确保 /admin/plugins/ 路由已注册（幂等）
        ensurePluginPageHandler();
    }

    /**
     * 从插件 jar 中注册静态资源目录。
     * jar 内 resourceDir 下的所有文件会映射到 /admin/plugins/{pluginName}/ 下。
     *
     * @param pluginName  插件名称
     * @param resourceDir jar 内的资源目录，如 "webadmin/"
     * @param classLoader 插件的类加载器
     */
    public void registerWebResources(String pluginName, String resourceDir, ClassLoader classLoader) {
        // 注册一个通用路由，通过 classLoader 加载资源
        String normalizedDir = resourceDir.endsWith("/") ? resourceDir : resourceDir + "/";

        // 尝试加载 index.html 作为首页
        try (InputStream is = classLoader.getResourceAsStream(normalizedDir + "index.html")) {
            if (is != null) {
                String html = readFully(is);
                registerWebPage(pluginName, "", "主页", html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }

        // 注册一个资源服务路由来处理其他文件
        ensureResourceHandler(pluginName, normalizedDir, classLoader);

        LOG.info("插件 [" + pluginName + "] 注册静态资源目录: " + normalizedDir + " -> /admin/plugins/" + pluginName + "/");
    }

    /**
     * 注册 WebSocket 消息处理器。
     * 当游戏客户端发送指定 messageType 的 WebSocket 消息时，会调用 handler。
     *
     * @param pluginName  插件名称
     * @param messageType 消息类型（JSON 中的 type 字段值）
     * @param handler     处理器
     */
    public void registerWsHandler(String pluginName, String messageType, PluginWsHandler handler) {
        wsHandlers.computeIfAbsent(messageType, k -> new ArrayList<>())
                .add(new WsHandlerEntry(pluginName, handler));
        LOG.info("插件 [" + pluginName + "] 注册 WebSocket 消息处理器: type=" + messageType);
    }

    /**
     * 处理插件 WebSocket 消息（由 GameWebSocketApp 调用）。
     *
     * @return true 表示已处理，false 表示未被任何插件处理
     */
    public boolean handleWsMessage(WebSocket socket, String messageType, JsonObject data) {
        List<WsHandlerEntry> handlers = wsHandlers.get(messageType);
        if (handlers == null || handlers.isEmpty()) return false;
        boolean handled = false;
        for (WsHandlerEntry entry : handlers) {
            try {
                entry.handler.handle(socket, data);
                handled = true;
            } catch (Exception e) {
                LOG.error("插件 [" + entry.pluginName + "] WebSocket 处理器异常: type=" + messageType, e);
            }
        }
        return handled;
    }

    /** 获取所有已注册的插件页面（供 admin 导航使用）。 */
    public Map<String, Map<String, WebPage>> getPluginPages() {
        return Collections.unmodifiableMap(pluginPages);
    }

    /** 获取指定插件的页面。 */
    public WebPage getPage(String pluginName, String path) {
        Map<String, WebPage> pages = pluginPages.get(pluginName);
        if (pages == null) return null;
        return pages.get(path);
    }

    /**
     * 移除某个插件的所有 Web 注册（页面、资源、WebSocket 处理器）。
     * 在插件卸载时由 PluginManager 自动调用。
     */
    public void unregisterPlugin(String pluginName) {
        pluginPages.remove(pluginName);
        resourceEntries.remove(pluginName);
        // 清理 WebSocket 处理器
        for (List<WsHandlerEntry> list : wsHandlers.values()) {
            list.removeIf(e -> e.pluginName.equals(pluginName));
        }
        LOG.debug("已清理插件 [" + pluginName + "] 的所有 Web 资源");
    }

    /** 页面信息。 */
    public static final class WebPage {
        public final String title;
        public final byte[] htmlContent;

        WebPage(String title, byte[] htmlContent) {
            this.title = title;
            this.htmlContent = htmlContent;
        }
    }

    /** WebSocket 处理器接口。 */
    @FunctionalInterface
    public interface PluginWsHandler {
        void handle(WebSocket socket, JsonObject data);
    }

    /** WebSocket 处理器条目。 */
    private static final class WsHandlerEntry {
        final String pluginName;
        final PluginWsHandler handler;

        WsHandlerEntry(String pluginName, PluginWsHandler handler) {
            this.pluginName = pluginName;
            this.handler = handler;
        }
    }

    // ======================= 内部 HTTP 路由 =======================

    private volatile boolean httpHandlerRegistered = false;

    private void ensurePluginPageHandler() {
        ensureHttpHandler();
    }

    private final Map<String, ResourceEntry> resourceEntries = new ConcurrentHashMap<>();

    private static class ResourceEntry {
        final String resourceDir;
        final ClassLoader classLoader;
        ResourceEntry(String dir, ClassLoader cl) { resourceDir = dir; classLoader = cl; }
    }

    private void ensureResourceHandler(String pluginName, String resourceDir, ClassLoader classLoader) {
        resourceEntries.put(pluginName, new ResourceEntry(resourceDir, classLoader));
        ensureHttpHandler();
    }

    /** 确保 /admin/plugins 的 HTTP handler 已注册（只注册一次）。 */
    private void ensureHttpHandler() {
        if (!httpHandlerRegistered && server != null) {
            synchronized (this) {
                if (!httpHandlerRegistered) {
                    server.getServerConfiguration().addHttpHandler(new PluginPageHttpHandler(), "/admin/plugins");
                    httpHandlerRegistered = true;
                }
            }
        }
    }

    private class PluginPageHttpHandler extends HttpHandler {
        @Override
        public void service(Request request, Response response) throws Exception {
            String fullPath = request.getHttpHandlerPath();
            
            // 路径格式: /admin/plugins/{pluginName}/{...}
            String path = fullPath;
            if (path == null || path.isEmpty() || path.equals("/")) {
                sendPluginList(response);
                return;
            }

            // 分割路径: pluginName/rest...
            String[] parts = path.substring(1).split("/", 2);
            String pluginName = parts[0];
            String subPath = parts.length > 1 ? parts[1] : "";

            // 1. 先查找已注册的页面
            Map<String, WebPage> pages = pluginPages.get(pluginName);
            if (pages != null && !resourceEntries.containsKey(pluginName)) {
                WebPage page = pages.get(subPath);
                if (page == null && subPath.isEmpty()) {
                    page = pages.get("");
                }
                if (page != null) {
                    response.setContentType("text/html; charset=UTF-8");
                    response.setCharacterEncoding("UTF-8");
                    response.setHeader("Cache-Control", "no-cache");
                    response.getOutputStream().write(page.htmlContent);
                    return;
                }
            }

            // 2. 查找资源目录映射
            ResourceEntry resEntry = resourceEntries.get(pluginName);
            if (resEntry != null) {
                String resourcePath = resEntry.resourceDir + (subPath.isEmpty() ? "index.html" : subPath);
                try (InputStream is = resEntry.classLoader.getResourceAsStream(resourcePath)) {
                    if (is != null) {
                        setContentType(response, subPath);
                        response.setCharacterEncoding("UTF-8");
                        response.setHeader("Cache-Control", "no-cache");
                        byte[] buf = new byte[8192];
                        int n;
                        OutputStream os = response.getOutputStream();
                        while ((n = is.read(buf)) != -1) {
                            os.write(buf, 0, n);
                        }
                        return;
                    }
                } catch (Exception ignored) {
                }

                // 也尝试已注册页面
                if (pages != null) {
                    WebPage page = pages.get(subPath);
                    if (page != null) {
                        response.setContentType("text/html; charset=UTF-8");
                        response.setCharacterEncoding("UTF-8");
                        response.setHeader("Cache-Control", "no-cache");
                        response.getOutputStream().write(page.htmlContent);
                        return;
                    }
                }
            }

            // 3. 404
            response.setStatus(404);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write("404 Not Found");
        }

        private void sendPluginList(Response response) throws Exception {
            response.setContentType("text/html; charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
              .append("<title>插件管理</title></head><body style=\"font-family:sans-serif;padding:20px;\">")
              .append("<h1>已安装插件页面</h1><ul>");
            for (Map.Entry<String, Map<String, WebPage>> entry : pluginPages.entrySet()) {
                String name = entry.getKey();
                sb.append("<li><strong>").append(escapeHtml(name)).append("</strong><ul>");
                for (Map.Entry<String, WebPage> page : entry.getValue().entrySet()) {
                    String url = "/admin/plugins/" + name;
                    if (!page.getKey().isEmpty()) url += "/" + page.getKey();
                    sb.append("<li><a href=\"").append(url).append("\">")
                      .append(escapeHtml(page.getValue().title))
                      .append("</a></li>");
                }
                sb.append("</ul></li>");
            }
            // 也列出资源目录型插件
            for (String name : resourceEntries.keySet()) {
                if (!pluginPages.containsKey(name)) {
                    sb.append("<li><strong>").append(escapeHtml(name)).append("</strong>")
                      .append(" <a href=\"/admin/plugins/").append(name).append("/\">[查看]</a></li>");
                }
            }
            sb.append("</ul><p><a href=\"/admin/\">返回管理后台</a></p></body></html>");
            response.getWriter().write(sb.toString());
        }

        private void setContentType(Response response, String path) {
            if (path.endsWith(".html") || path.endsWith(".htm")) {
                response.setContentType("text/html; charset=UTF-8");
            } else if (path.endsWith(".css")) {
                response.setContentType("text/css; charset=UTF-8");
            } else if (path.endsWith(".js")) {
                response.setContentType("application/javascript; charset=UTF-8");
            } else if (path.endsWith(".json")) {
                response.setContentType("application/json; charset=UTF-8");
            } else if (path.endsWith(".png")) {
                response.setContentType("image/png");
            } else if (path.endsWith(".svg")) {
                response.setContentType("image/svg+xml");
            } else {
                response.setContentType("text/html; charset=UTF-8");
            }
        }

        private String escapeHtml(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;");
        }
    }

    private static String readFully(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    // ======================= API 接口（供 admin REST 使用） =======================

    /**
     * 插件页面 API 对应的 JSON 数据。
     */
    public com.google.gson.JsonArray getPluginPagesJson() {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Map.Entry<String, Map<String, WebPage>> entry : pluginPages.entrySet()) {
            com.google.gson.JsonObject pluginObj = new com.google.gson.JsonObject();
            pluginObj.addProperty("pluginName", entry.getKey());
            com.google.gson.JsonArray pagesArr = new com.google.gson.JsonArray();
            for (Map.Entry<String, WebPage> page : entry.getValue().entrySet()) {
                com.google.gson.JsonObject pageObj = new com.google.gson.JsonObject();
                pageObj.addProperty("path", page.getKey());
                pageObj.addProperty("title", page.getValue().title);
                pagesArr.add(pageObj);
            }
            pluginObj.add("pages", pagesArr);
            arr.add(pluginObj);
        }
        return arr;
    }
}
