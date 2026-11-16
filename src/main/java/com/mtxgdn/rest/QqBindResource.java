package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtxgdn.db.UserServiceDAO;
import com.mtxgdn.identity.CredentialAuthService;
import com.mtxgdn.identity.QqAccountRegistrar;
import com.mtxgdn.identity.QqBindTokenStore;
import com.mtxgdn.identity.QqOfficialBindingService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.EmailService;
import com.mtxgdn.util.EmailRules;
import com.mtxgdn.util.PublicUrl;
import com.mtxgdn.util.RateLimiter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * QQ 官方机器人的「网页绑定」落地端点。
 *
 * <p>流程：机器人在群里发一条 {@code /admin/?qq_bind=<token>}，玩家打开后由前端调这里完成
 * 密码或邮箱验证码校验，成功后把该 openid 绑定到账号。链接里<b>只有票据</b>，
 * 没有密码、没有用户名。</p>
 *
 * <h3>三条安全底线</h3>
 * <ol>
 *   <li><b>票据即凭据</b>：{@link QqBindTokenStore} 保证单次、10 分钟、内存里只存哈希。
 *       一个票据只能完成一次绑定，先成功者得；</li>
 *   <li><b>不暴露账号是否存在</b>：发送验证码恒回同一句话，有没有这个邮箱都照发；
 *       密码错误统一回「用户名或密码错误」。只有"已锁定 / 被限流"这种玩家必须知道的
 *       状态才单独说明 —— 那种情况下对方已经知道用户名了；</li>
 *   <li><b>按来源限流</b>：{@link ClientIp} 分桶，发码 10/分、确认 20/分；
 *       再加每条票据最多 5 次失败。</li>
 * </ol>
 *
 * <p><b>不签发 JWT</b>：绑定只是把 QQ 身份与账号关联起来，不是登录网页后台。
 * 拿到链接的人即使绑定成功，也进不了管理后台（那需要账号密码登录 + 角色权限）。</p>
 *
 * <p>路径在 {@code /auth} 前缀下，已被 {@code JwtAuthFilter} 整体放行 —— 玩家在绑定之前
 * 本来就没有 token，这个端点必须匿名可用。</p>
 */
@Path("/auth/qq-bind")
public class QqBindResource extends BaseResource {

    /** 网页通道的登录限流前缀（与聊天通道分开计数，互不误伤）。 */
    private static final String LOGIN_RATE_LIMIT_PREFIX = "qq_web_login_";

    private final VerificationCodeService verificationCodeService = new VerificationCodeService();
    private final UserServiceDAO userDao = new UserServiceDAO();

    @Context
    private ContainerRequestContext requestContext;

    // ==================== 首屏信息 ====================

    /**
     * 网页首屏：这条票据还能不能用、是绑定还是注册、要不要邮箱。
     *
     * <p>刻意不返回 openid —— 页面上没有任何地方需要它，少给一个标识就少一个泄漏面。</p>
     */
    @GET
    @Path("/info")
    @Produces(MediaType.APPLICATION_JSON)
    public Response info(@QueryParam("token") String token) {
        QqBindTokenStore.Pending pending = QqBindTokenStore.peek(token).orElse(null);
        if (pending == null) {
            return error(410, I18n.t("auth.qq_bind.link_expired"));
        }
        JsonObject data = new JsonObject();
        data.addProperty("code", 200);
        data.addProperty("intent", pending.intent().name());
        if (pending.intent() == QqBindTokenStore.Intent.REGISTER) {
            data.addProperty("username", pending.pendingUsername());
        }
        data.addProperty("emailEnabled", EmailService.isConfigured());
        data.addProperty("emailRequired", emailRequired(pending));
        data.addProperty("webAvailable", PublicUrl.available());
        return Response.ok(data.toString()).build();
    }

    // ==================== 发送验证码 ====================

    /**
     * 给邮箱发验证码。
     *
     * <p>无论邮箱有没有注册过，都回同一句话并照常发码（防枚举）。
     * 是否真的关联了账号，要等验证码输入正确之后才揭晓 —— 那时对方已经证明了邮箱所有权。</p>
     */
    @POST
    @Path("/send-code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendCode(@Context org.glassfish.grizzly.http.server.Request grizzlyReq, String body) {
        if (!EmailService.isConfigured()) {
            return error(400, I18n.t("auth.qq_bind.email_disabled"));
        }
        JsonObject json = parse(body);
        if (json == null) {
            return error(400, I18n.t("auth.qq_bind.bad_request"));
        }
        String token = optString(json, "token");
        if (QqBindTokenStore.peek(token).isEmpty()) {
            return error(410, I18n.t("auth.qq_bind.link_expired"));
        }

        String bucket = ClientIp.bucket(requestContext, grizzlyReq);
        if (!RateLimiter.allow("qq_bind_code_ip:" + bucket, 10, 60)) {
            return error(429, I18n.t("auth.qq_bind.rate_limited"));
        }
        if (!RateLimiter.allow("qq_bind_code_hour:" + bucket, 30, 3600)) {
            return error(429, I18n.t("auth.qq_bind.rate_limited"));
        }
        if (!RateLimiter.allow("qq_bind_code_global", 300, 3600)) {
            return error(429, I18n.t("auth.qq_bind.rate_limited"));
        }

        String email = optString(json, "email");
        if (email == null || email.isBlank()) {
            return error(400, I18n.t("auth.email_cannot_be_empty"));
        }
        String normalized = email.trim().toLowerCase();
        if (!EmailRules.isValidEmail(normalized)) {
            return error(400, I18n.t("auth.invalid_email_format"));
        }
        if (!verificationCodeService.canSendCode(normalized)) {
            return error(429, I18n.t("auth.qq_bind.rate_limited"));
        }
        try {
            String code = verificationCodeService.generateAndStoreCode(normalized);
            EmailService.sendVerificationCode(normalized, code);
        } catch (Exception e) {
            return error(500, I18n.t("auth.qq_bind.send_failed"));
        }
        return ok(I18n.t("auth.qq_bind.code_sent"), null);
    }

    // ==================== 确认 ====================

    /**
     * 完成绑定 / 注册。
     *
     * <p>请求体：{@code {token, mode:"password"|"email", username?, password?, email?, code?}}。</p>
     *
     * <p>顺序是"先绑定成功、再消费票据"：绑定失败（例如该账号已绑了别的 openid）
     * 不应该把票据也一起烧掉，玩家换个账号还能继续用这条链接。</p>
     */
    @POST
    @Path("/confirm")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response confirm(@Context org.glassfish.grizzly.http.server.Request grizzlyReq, String body) {
        JsonObject json = parse(body);
        if (json == null) {
            return error(400, I18n.t("auth.qq_bind.bad_request"));
        }
        String token = optString(json, "token");
        QqBindTokenStore.Pending pending = QqBindTokenStore.peek(token).orElse(null);
        if (pending == null) {
            return error(410, I18n.t("auth.qq_bind.link_expired"));
        }

        String bucket = ClientIp.bucket(requestContext, grizzlyReq);
        if (!RateLimiter.allow("qq_bind_confirm_ip:" + bucket, 20, 60)) {
            return error(429, I18n.t("auth.qq_bind.rate_limited"));
        }

        Response result = pending.intent() == QqBindTokenStore.Intent.REGISTER
                ? confirmRegister(pending, json)
                : confirmBind(pending, json);
        if (result.getStatus() >= 400) {
            return tooManyFailures(token, result);
        }
        QqBindTokenStore.consume(token);
        return result;
    }

    /** 绑定已有账号：密码或邮箱验证码。 */
    private Response confirmBind(QqBindTokenStore.Pending pending, JsonObject json) {
        String mode = optString(json, "mode");
        Long userId;
        String usernameLabel;
        if ("email".equals(mode)) {
            String email = optString(json, "email");
            String code = optString(json, "code");
            if (email == null || email.isBlank() || code == null || code.isBlank()) {
                return error(400, I18n.t("auth.qq_bind.bad_request"));
            }
            String normalized = email.trim().toLowerCase();
            if (!verificationCodeService.verifyCode(normalized, code.trim())) {
                return error(400, I18n.t("auth.qq_bind.code_failed"));
            }
            UserServiceDAO.EmailOwnerRow owner = userDao.findIdAndUsernameByEmail(normalized);
            if (owner == null) {
                return error(400, I18n.t("auth.qq_bind.email_unlinked"));
            }
            userId = owner.id();
            usernameLabel = owner.username();
        } else {
            String username = optString(json, "username");
            String password = optString(json, "password");
            if (username == null || username.isBlank() || password == null || password.isEmpty()) {
                return error(400, I18n.t("auth.qq_bind.bad_request"));
            }
            CredentialAuthService.Result check =
                    CredentialAuthService.check(username.trim(), password, LOGIN_RATE_LIMIT_PREFIX);
            if (!check.ok()) {
                // 锁定与限流必须照实说（否则玩家拿着正确密码反复"密码错误"）；
                // 其余一律泛化，不给"这个用户名存不存在"的探针
                String message = check.error() != null && (check.error().startsWith("该账号因多次")
                        || check.error().startsWith("尝试过于频繁"))
                        ? check.error()
                        : I18n.t("auth.qq_bind.password_failed");
                return error(401, message);
            }
            userId = check.userId();
            usernameLabel = username.trim();
        }

        try {
            new QqOfficialBindingService(pending.appId()).bind(pending.openid(), userId);
        } catch (RuntimeException e) {
            return error(409, I18n.t("auth.qq_bind.conflict") + (e.getMessage() == null ? "" : e.getMessage()));
        }
        JsonObject data = new JsonObject();
        data.addProperty("username", usernameLabel);
        return ok(I18n.t("auth.qq_bind.success"), data);
    }

    /** 注册新角色并绑定。角色名来自票据（在聊天里已确认），这里只补密码与（可选）邮箱。 */
    private Response confirmRegister(QqBindTokenStore.Pending pending, JsonObject json) {
        String password = optString(json, "password");
        String email = optString(json, "email");
        String code = optString(json, "code");
        boolean needEmail = emailRequired(pending);
        if (password == null || password.isEmpty()) {
            return error(400, I18n.t("auth.qq_bind.bad_request"));
        }
        if (needEmail && (email == null || email.isBlank() || code == null || code.isBlank())) {
            return error(400, I18n.t("auth.qq_bind.bad_request"));
        }

        String openid = pending.openid();
        QqOfficialBindingService bindingService = new QqOfficialBindingService(pending.appId());
        QqAccountRegistrar.Result result = QqAccountRegistrar.register(
                services, pending.pendingUsername(), password,
                email == null || email.isBlank() ? null : email.trim(),
                code == null ? null : code.trim(),
                new QqAccountRegistrar.BindHook() {
                    @Override
                    public void bind(long userId) {
                        bindingService.bind(openid, userId);
                    }

                    @Override
                    public void unbind(long userId) {
                        try {
                            bindingService.unbindByQq(openid);
                        } catch (RuntimeException ignored) {
                            // 没绑上就没有可解绑的
                        }
                    }
                });
        if (!result.ok()) {
            int status = result.error() != null && result.error().contains("已被占用") ? 409 : 400;
            return error(status, result.error());
        }
        JsonObject data = new JsonObject();
        data.addProperty("username", pending.pendingUsername());
        data.addProperty("userId", result.userId());
        return ok(I18n.t("auth.qq_bind.success"), data);
    }

    /**
     * 记一次失败；到上限就把票据作废并改写提示。
     *
     * <p>"票据作废"和"这次输错了"是两件事：前者要明确告诉玩家回 QQ 重新发起，
     * 否则他会一直重试一条已经死掉的链接。</p>
     */
    private Response tooManyFailures(String token, Response original) {
        int remaining = QqBindTokenStore.recordFailure(token);
        if (remaining > 0) {
            return original;
        }
        return error(410, I18n.t("auth.qq_bind.too_many_failures"));
    }

    /** 注册这条票据是否需要邮箱（{@code verify_code.enabled} 且 SMTP 可用）。 */
    private boolean emailRequired(QqBindTokenStore.Pending pending) {
        return pending.intent() == QqBindTokenStore.Intent.REGISTER
                && AppConfig.getBoolean("verify_code.enabled", true)
                && EmailService.isConfigured();
    }

    // ==================== 小工具 ====================

    private static JsonObject parse(String body) {
        try {
            if (body == null || body.isBlank()) {
                return null;
            }
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String optString(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Response ok(String message, JsonObject data) {
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        out.addProperty("message", message);
        if (data != null) {
            out.add("data", data);
        }
        return Response.ok(out.toString()).build();
    }

    private static Response error(int status, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("code", status);
        out.addProperty("message", message);
        return Response.status(status).entity(out.toString()).build();
    }
}
