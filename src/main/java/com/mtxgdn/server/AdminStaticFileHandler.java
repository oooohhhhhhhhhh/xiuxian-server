package com.mtxgdn.server;

import com.mtxgdn.util.GameLogger;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class AdminStaticFileHandler extends HttpHandler {

    private static final GameLogger LOG = GameLogger.getLogger(AdminStaticFileHandler.class);
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

    static {
        CONTENT_TYPES.put(".html", "text/html; charset=UTF-8");
        CONTENT_TYPES.put(".htm", "text/html; charset=UTF-8");
        CONTENT_TYPES.put(".css", "text/css; charset=UTF-8");
        CONTENT_TYPES.put(".js", "application/javascript; charset=UTF-8");
        CONTENT_TYPES.put(".json", "application/json; charset=UTF-8");
        CONTENT_TYPES.put(".png", "image/png");
        CONTENT_TYPES.put(".svg", "image/svg+xml");
        CONTENT_TYPES.put(".jpg", "image/jpeg");
        CONTENT_TYPES.put(".jpeg", "image/jpeg");
        CONTENT_TYPES.put(".gif", "image/gif");
        CONTENT_TYPES.put(".ico", "image/x-icon");
        CONTENT_TYPES.put(".woff", "font/woff");
        CONTENT_TYPES.put(".woff2", "font/woff2");
        CONTENT_TYPES.put(".ttf", "font/ttf");
    }

    private static final String RESOURCE_PREFIX = "webadmin";
    private static final int BUFFER_SIZE = 8192;

    /** 挂载路径（如 /admin、/install），用于剥离 URI 前缀 */
    private final String mountPath;
    /** 静态资源根目录（classpath 内），默认 webadmin */
    private final String resourcePrefix;

    public AdminStaticFileHandler() {
        this("/admin", RESOURCE_PREFIX);
    }

    public AdminStaticFileHandler(String mountPath, String resourcePrefix) {
        this.mountPath = mountPath == null ? "" : mountPath;
        this.resourcePrefix = resourcePrefix == null ? RESOURCE_PREFIX : resourcePrefix;
    }

    @Override
    public void service(Request request, Response response) throws Exception {
        String p = request.getRequestURI();
        if (p == null) {
            p = "";
        }
        // Grizzly 传给挂载 handler 的 URI 为完整路径（含挂载前缀）；此处剥离前缀，
        // 并兼容"已剥离/相对路径"两种形态
        if (!mountPath.isEmpty() && p.startsWith(mountPath)) {
            p = p.substring(mountPath.length());
        }
        if (p.startsWith(resourcePrefix)) {
            p = p.substring(resourcePrefix.length());
        }
        if (p.isEmpty() || p.equals("/")) {
            p = "/index.html";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }

        String resourcePath = resourcePrefix + p;
        InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath);

        if (in == null) {
            response.setStatus(404);
            response.setContentType("text/plain; charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.getWriter().write("404 Not Found");
            LOG.debug("静态资源未找到: " + resourcePath);
            return;
        }

        setContentType(response, p);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setCharacterEncoding("UTF-8");

        try (OutputStream os = response.getOutputStream()) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            in.close();
        }
    }

    private void setContentType(Response response, String path) {
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx > 0 && dotIdx < path.length() - 1) {
            String extension = path.substring(dotIdx);
            String contentType = CONTENT_TYPES.get(extension.toLowerCase());
            if (contentType != null) {
                response.setContentType(contentType);
                return;
            }
        }
        response.setContentType("text/plain; charset=UTF-8");
    }
}