package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.JwtUtil;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * 安装向导 API（/api/install/*）。
 * <ul>
 *   <li>未安装（jwt.secret 为空）：允许全部配置操作</li>
 *   <li>已安装：只允许 /status 和 /reset（需提供当前管理员密码）</li>
 * </ul>
 */
@Path("/install")
public class InstallResource {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** JWT 密钥最少 48 字节（384 位），高于 HMAC-SHA256 的 32 字节最低要求 */
    private static final int JWT_SECRET_MIN_BYTES = 48;

    @jakarta.ws.rs.core.Context
    private jakarta.ws.rs.container.ContainerRequestContext requestContext;

    // ==================== 安装状态检测 ====================

    /**
     * 安装完成锁文件名（与 application.yml 同目录）。
     *
     * <p><b>V7 修复</b>：原实现仅以"application.yml 中 jwt.secret 是否为空"判断
     * 安装状态。一旦有人（或某次误操作/配置回滚）清空了 jwt.secret，
     * {@code /install/configure} 会重新开放 —— 攻击者即可用该端点篡改数据库连接串、
     * 管理员密码等核心配置。锁文件是一次写入后永不删除的独立标记，
     * 不再依赖任何可被单独修改的配置字段。</p>
     */
    private static final String INSTALL_LOCK_FILE = ".installed";

    /**
     * 是否已完成安装。满足以下任一条件即视为已安装：
     * <ol>
     *   <li>存在安装锁文件（最强信号，不受配置改动影响）；</li>
     *   <li>application.yml 中已填写 jwt.secret（兼容旧版本升级上来的实例）。</li>
     * </ol>
     */
    public static boolean isInstalled() {
        return lockFileExists() || hasJwtSecretConfigured();
    }

    private static boolean lockFileExists() {
        try {
            return Files.exists(lockFilePath());
        } catch (Exception e) {
            return false;
        }
    }

    private static java.nio.file.Path lockFilePath() {
        return AppConfig.getJarDir().resolve("config").resolve(INSTALL_LOCK_FILE);
    }

    /**
     * 写入安装锁文件。安装成功后调用；写入失败不阻断安装流程，
     * 但会打印告警（此时仍由 jwt.secret 兜底判断）。
     */
    private static void writeInstallLock() {
        try {
            java.nio.file.Path lock = lockFilePath();
            Files.createDirectories(lock.getParent());
            Files.writeString(lock,
                    "installed_at=" + java.time.Instant.now() + System.lineSeparator()
                            + "note=此文件标记服务器已完成安装，删除它会使 /api/install/configure 重新开放" + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Install] 警告：写入安装锁文件失败，安装状态将仅依赖 jwt.secret 判断: " + e.getMessage());
        }
    }

    private static boolean hasJwtSecretConfigured() {
        try {
            java.nio.file.Path path = configPath();
            if (!Files.exists(path)) {
                return false;
            }
            Yaml yaml = new Yaml();
            Map<String, Object> root;
            try (InputStream in = Files.newInputStream(path)) {
                Object loaded = yaml.load(in);
                root = loaded instanceof Map ? (Map<String, Object>) loaded : null;
            }
            if (root == null) return false;

            Object jwt = root.get("jwt");
            if (jwt instanceof Map) {
                Object secret = ((Map<?, ?>) jwt).get("secret");
                return secret != null && !String.valueOf(secret).isBlank();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * V7：安装配置端点只允许本机访问。
     *
     * <p>安装向导本就是"坐在服务器前完成首次配置"的场景，没有任何合法理由
     * 需要从公网调用它。绑定回环地址可以彻底消除"服务器暴露在公网且尚未完成
     * 安装"这个高危窗口，即便安装锁被绕过也无法被远程利用。</p>
     *
     * <p>可通过 {@code install.allow_remote=true} 显式放行（用于容器化部署等场景，
     * 使用者需自行确认网络边界安全）。</p>
     */
    private static boolean isLocalRequest(jakarta.ws.rs.container.ContainerRequestContext ctx) {
        if (AppConfig.getBoolean("install.allow_remote", false)) {
            return true;
        }
        try {
            Object grizzlyReq = ctx.getProperty(org.glassfish.grizzly.http.server.Request.class.getName());
            if (grizzlyReq instanceof org.glassfish.grizzly.http.server.Request req) {
                String remote = req.getRemoteAddr();
                if (remote == null) {
                    return false;
                }
                return "127.0.0.1".equals(remote)
                        || "0:0:0:0:0:0:0:1".equals(remote)
                        || "::1".equals(remote)
                        || "localhost".equalsIgnoreCase(remote);
            }
        } catch (Throwable ignored) {
            // 取不到来源地址时保守拒绝
        }
        return false;
    }

    private static Response remoteDenied() {
        JsonObject o = new JsonObject();
        o.addProperty("code", 403);
        o.addProperty("success", false);
        o.addProperty("message", "安装向导仅允许从服务器本机访问。如需远程安装，请设置 install.allow_remote=true（务必确认网络边界安全）。");
        return Response.status(403).entity(o.toString()).build();
    }

    private static java.nio.file.Path configPath() {
        return AppConfig.getJarDir().resolve("config").resolve("application.yml");
    }

    // ==================== 状态接口（无需认证） ====================

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        boolean installed = isInstalled();
        JsonObject o = new JsonObject();
        o.addProperty("installed", installed);
        o.addProperty("serverHost", AppConfig.get("server.host", "127.0.0.1"));
        o.addProperty("serverPort", AppConfig.getInt("server.port", 8080));
        o.addProperty("databaseType", AppConfig.get("database.type", "sqlite"));
        o.addProperty("sqlitePath", AppConfig.get("database.sqlite_path", "xiuxian.db"));
        o.addProperty("adminConfigured",
                !isBlank(AppConfig.get("admin.username", ""))
                && (!isBlank(AppConfig.get("admin.password_hash", "")) || !isBlank(AppConfig.get("admin.password", ""))));
        o.addProperty("jwtConfigured", installed);
        o.addProperty("jwtFingerprint", installed ? JwtUtil.getKeyFingerprint() : "未配置");
        o.addProperty("onebotEnabled", AppConfig.getBoolean("onebot.enabled", true));
        o.addProperty("javaVersion", System.getProperty("java.version", "未知"));
        o.addProperty("osName", System.getProperty("os.name", "未知"));
        o.addProperty("jarDir", AppConfig.getJarDir().toAbsolutePath().toString());
        return Response.ok(o.toString()).build();
    }

    // ==================== 安装接口（未安装时可用） ====================

    /**
     * 首次安装配置。
     * body: { serverPort?, databaseType?("sqlite"|"mysql"), mysqlUrl?, mysqlUser?, mysqlPassword?,
     *        sqlitePath?, adminUsername, adminPassword, adminPassword2, jwtSecret?(留空自动生成), onebotEnabled? }
     */
    @POST
    @Path("/configure")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response configure(String body) {
        JsonObject result = new JsonObject();

        // V7：安装配置仅允许本机调用
        if (!isLocalRequest(requestContext)) {
            return remoteDenied();
        }

        if (isInstalled()) {
            result.addProperty("code", 403);
            result.addProperty("success", false);
            result.addProperty("message", "服务器已完成安装。如需重置，请使用 /api/install/reset 接口。");
            return Response.status(403).entity(result.toString()).build();
        }

        JsonObject req;
        try {
            req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return badRequest(result, "请求格式错误");
        }

        // 管理员账号校验
        String adminUsername = str(req, "adminUsername").trim();
        String adminPassword = str(req, "adminPassword");
        String adminPassword2 = str(req, "adminPassword2");
        if (isBlank(adminUsername) || adminUsername.length() < 3) {
            return badRequest(result, "管理员用户名至少 3 个字符");
        }
        if (!adminUsername.matches("^[a-zA-Z0-9_]{3,32}$")) {
            return badRequest(result, "管理员用户名只能包含字母、数字和下划线");
        }
        if (isBlank(adminPassword) || adminPassword.length() < 8) {
            return badRequest(result, "管理员密码至少 8 位");
        }
        if (!adminPassword.equals(adminPassword2)) {
            return badRequest(result, "两次输入的密码不一致");
        }

        // 数据库校验
        String dbType = "sqlite".equalsIgnoreCase(str(req, "databaseType")) ? "sqlite" : "mysql";
        if ("mysql".equals(dbType) && isBlank(str(req, "mysqlUrl"))) {
            return badRequest(result, "选择 MySQL 模式时需填写数据库连接地址");
        }

        // JWT 密钥生成/验证
        String jwtSecret = str(req, "jwtSecret").trim();
        if (isBlank(jwtSecret)) {
            // 自动生成 64 字节（512 位）随机密钥
            byte[] bytes = new byte[64];
            RANDOM.nextBytes(bytes);
            jwtSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } else {
            // 用户提供密钥，验证最小长度
            byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < JWT_SECRET_MIN_BYTES) {
                return badRequest(result, "JWT 密钥至少 " + JWT_SECRET_MIN_BYTES + " 字节（约 " +
                        (JWT_SECRET_MIN_BYTES * 8 / 6) + " 个 Base64 字符），当前 " + keyBytes.length + " 字节。建议留空自动生成。");
            }
        }

        // 写入配置
        String persistMsg = persistConfig(req, dbType, adminUsername, adminPassword, jwtSecret);

        // V7：安装成功立即落锁，此后即便 application.yml 被清空 /install/configure 也不会重开
        writeInstallLock();

        result.addProperty("code", 200);
        result.addProperty("success", true);
        result.addProperty("message", "配置已保存" + (persistMsg != null ? "（" + persistMsg + "）" : "") + "，正在重启服务器…");
        result.addProperty("restartRequired", true);
        return Response.ok(result.toString()).build();
    }

    // ==================== 重置接口（已安装时可用） ====================

    /**
     * 重置安装（需提供当前管理员密码）。
     * 将清除所有配置，服务器回到未安装状态，需要重新运行安装向导。
     */
    @POST
    @Path("/reset")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response reset(String body) {
        JsonObject result = new JsonObject();

        // V7：重置同样只允许本机调用（重置会清空所有配置，影响面等同重新安装）
        if (!isLocalRequest(requestContext)) {
            return remoteDenied();
        }

        JsonObject req;
        try {
            req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return badRequest(result, "请求格式错误");
        }

        // 验证管理员密码
        String adminPassword = str(req, "adminPassword");
        if (isBlank(adminPassword)) {
            return badRequest(result, "请输入当前管理员密码以确认重置");
        }

        // 从配置读取管理员用户名并验证密码
        String adminUsername = AppConfig.get("admin.username", "");
        String adminPasswordHash = AppConfig.get("admin.password_hash", "");
        if (!isBlank(adminUsername) && !isBlank(adminPasswordHash)) {
            try {
                if (!org.mindrot.jbcrypt.BCrypt.checkpw(adminPassword, adminPasswordHash)) {
                    return forbidden(result, "管理员密码错误，重置被拒绝");
                }
            } catch (Exception e) {
                return serverError(result, "密码验证失败: " + e.getMessage());
            }
        }

        try {
            java.nio.file.Path path = configPath();
            if (Files.exists(path)) {
                Files.delete(path);
            }
            // V7：重置必须同时清除安装锁，否则重新安装会被 isInstalled() 永久拒绝
            try {
                java.nio.file.Path lock = lockFilePath();
                if (Files.exists(lock)) {
                    Files.delete(lock);
                }
            } catch (Exception e) {
                System.err.println("[Install] 警告：清除安装锁文件失败: " + e.getMessage());
            }
            result.addProperty("code", 200);
            result.addProperty("success", true);
            result.addProperty("message", "配置已清除，服务器即将重启，请重新运行安装向导…");
            result.addProperty("restartRequired", true);

            // 异步重启
            new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                com.mtxgdn.Main.restartServer();
            }, "install-reset-restart").start();

            return Response.ok(result.toString()).build();
        } catch (Exception e) {
            return serverError(result, "重置失败: " + e.getMessage());
        }
    }

    // ==================== 重启接口 ====================

    @POST
    @Path("/restart")
    @Produces(MediaType.APPLICATION_JSON)
    public Response restart() {
        JsonObject result = new JsonObject();

        // V7：重启会中断服务，同样限制为本机调用，避免公网任意触发 DoS
        if (!isLocalRequest(requestContext)) {
            return remoteDenied();
        }

        result.addProperty("code", 200);
        result.addProperty("success", true);
        result.addProperty("message", "服务器即将重启，请稍候…（页面将自动刷新）");
        com.mtxgdn.Main.restartServer();
        return Response.ok(result.toString()).build();
    }

    // ==================== 配置写入 ====================

    /**
     * 将安装配置写回 config/application.yml（保留注释与排版，只改值）。
     * 未提供的字段保持不变；admin 只存 bcrypt 哈希，绝不落盘明文密码（null 删除旧明文键）。
     */
    private static synchronized String persistConfig(JsonObject req, String dbType,
                                                     String adminUsername, String adminPassword, String jwtSecret) {
        try {
            java.util.Map<String, Object> updates = new java.util.LinkedHashMap<>();
            updates.put("server.host", "0.0.0.0");
            updates.put("server.port", intOr(req, "serverPort", AppConfig.getInt("server.port", 8080)));

            updates.put("database.type", dbType);
            if ("mysql".equals(dbType)) {
                updates.put("database.url", str(req, "mysqlUrl"));
                updates.put("database.username", str(req, "mysqlUser"));
                updates.put("database.password", str(req, "mysqlPassword"));
            } else {
                updates.put("database.sqlite_path", isBlank(str(req, "sqlitePath")) ? "xiuxian.db" : str(req, "sqlitePath"));
            }

            // admin（只存 bcrypt 哈希，绝不落盘明文密码）
            updates.put("admin.username", adminUsername);
            updates.put("admin.password_hash",
                    org.mindrot.jbcrypt.BCrypt.hashpw(adminPassword, org.mindrot.jbcrypt.BCrypt.gensalt(10)));
            updates.put("admin.password", null); // 清除可能存在的旧明文（null=删除该键行）

            updates.put("jwt.secret", jwtSecret);

            if (req.has("onebotEnabled") && !req.get("onebotEnabled").isJsonNull()) {
                updates.put("onebot.enabled", req.get("onebotEnabled").getAsBoolean());
            }

            AppConfig.update(updates);
            return "已写入 " + configPath().toAbsolutePath();
        } catch (Exception e) {
            return "写入失败: " + e.getMessage();
        }
    }

    // ==================== 工具方法 ====================

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static int intOr(JsonObject o, String key, int def) {
        if (o.has(key) && o.get(key).isJsonPrimitive()) {
            try { return o.get(key).getAsInt(); } catch (Exception ignored) {}
        }
        return def;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Response badRequest(JsonObject result, String msg) {
        result.addProperty("code", 400);
        result.addProperty("success", false);
        result.addProperty("message", msg);
        return Response.status(400).entity(result.toString()).build();
    }

    private static Response forbidden(JsonObject result, String msg) {
        result.addProperty("code", 403);
        result.addProperty("success", false);
        result.addProperty("message", msg);
        return Response.status(403).entity(result.toString()).build();
    }

    private static Response serverError(JsonObject result, String msg) {
        result.addProperty("code", 500);
        result.addProperty("success", false);
        result.addProperty("message", msg);
        return Response.status(500).entity(result.toString()).build();
    }
}
