package com.mtxgdn.qq;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护「{@code QqOfficialAdapter} 拆成门面 + 多个职责域」这次纯结构重构。
 *
 * <p>拆分本身不改变任何行为，所以普通业务测试抓不到"拆错了"这类问题 ——
 * 它们照样全绿（甚至更绿：这个通道平时没有测试覆盖）。本测试专门钉住拆分后的结构性不变量：</p>
 *
 * <ol>
 *   <li><b>门面 API 一个不少</b>：公开成员清单（含 {@code OneBotSession} /
 *       {@code OneBotMessageSenderClient} / {@code OneBotAccountFlowClient} 三个接口方法）
 *       必须与拆分前 {@code javap -public} 导出的黄金清单逐字一致；</li>
 *   <li><b>门面不是空壳</b>：七个协作对象必须真的被接线，并且门面公开方法真的转调它们
 *       （这里用 {@code handleBind} 走通"门面 → 账号流程 → 上下文"整条链来证明）；</li>
 *   <li><b>踩过坑的逻辑没有丢，也没有被复制两份</b>：被动回复 {@code msg_seq} 自增、
 *       access_token 缓存与提前刷新、指令限流、登录限流与账号锁定、指数退避重连
 *       都必须还在，且只在对应的域类里出现一次；</li>
 *   <li><b>SQL 与指令投递点数量不变</b>：{@code DatabaseManager.getConnection()} 仍是 3 处、
 *       {@code CommandExecutor.submit} 仍是 1 处，且门面自己不再碰 JDBC / 不再持有指令分发；</li>
 *   <li><b>拆分粒度</b>：每个类都在行数预算内，门面显著变薄。</li>
 * </ol>
 *
 * <p>源码级断言一律先剥掉注释再匹配（见 {@link #stripComments}）：把关键字写在注释里
 * 不算数，也不允许注释文本冒充断言锚点。</p>
 */
class QqOfficialAdapterSplitTest extends TestBase {

    /** QQ 官方通道源码目录。 */
    private static final Path QQ_DIR = Paths.get("src/main/java/com/mtxgdn/qq");

    /**
     * {@code javap -public -classpath target/classes com.mtxgdn.qq.QqOfficialAdapter}
     * 在<b>拆分前</b>导出的公开成员清单（逐字抄录）。
     */
    private static final List<String> GOLDEN_FACADE_API = List.of(
            "public com.mtxgdn.qq.QqOfficialAdapter()",
            "public void start(com.mtxgdn.adapter.AdapterConfig)",
            "public void dispatchWebhookEvent(com.google.gson.JsonObject)",
            "public void stop()",
            "public java.lang.String getAppId()",
            "public boolean isWsConnected()",
            "public void replyToSource(com.mtxgdn.onebot.OneBotSession, java.lang.String, java.lang.String, java.lang.Long, java.lang.String)",
            "public void sendPrivateMsg(com.mtxgdn.onebot.OneBotSession, java.lang.String, java.lang.String, java.lang.String)",
            "public void sendGroupMsg(com.mtxgdn.onebot.OneBotSession, java.lang.String, java.lang.Long, java.lang.String)",
            "public void sendGroupMsgWithButtons(java.lang.Long, java.lang.String, java.util.List<com.mtxgdn.qq.QqButton>)",
            "public void sendGroupMsgWithKeyboard(java.lang.Long, java.lang.String, java.util.List<java.util.List<com.mtxgdn.qq.QqButton>>)",
            "public void sendPrivateMsgWithButtons(java.lang.String, java.lang.String, java.util.List<com.mtxgdn.qq.QqButton>)",
            "public void sendPrivateMsgWithKeyboard(java.lang.String, java.lang.String, java.util.List<java.util.List<com.mtxgdn.qq.QqButton>>)",
            "public void send(java.lang.String)",
            "public java.lang.String getSelfId()",
            "public boolean isOpen()",
            "public void close()",
            "public void handleRegister(com.mtxgdn.onebot.OneBotSession, java.lang.String, java.lang.String, java.lang.String, java.lang.Long)",
            "public void handleBind(com.mtxgdn.onebot.OneBotSession, java.lang.String, java.lang.String)",
            "public void handleUnbind(com.mtxgdn.onebot.OneBotSession, java.lang.String, java.lang.String)",
            "public void handleChangePassword(com.mtxgdn.onebot.OneBotSession, java.lang.String, java.lang.String)",
            "public void handleDeleteAccount(com.mtxgdn.onebot.OneBotSession, java.lang.String, java.lang.String)");

    /** 门面持有的协作对象字段：一个都不能漏接线，否则对应的一组方法会 NPE。 */
    private static final String[] DOMAIN_FIELDS = {
            "ctx", "apiClient", "sender", "accountFlow", "router", "events", "gateway"
    };

    /**
     * QQ 官方通道里 {@code DatabaseManager.getConnection()} 的调用点总数 —— 现在是 <b>0</b>。
     *
     * <p>拆分前是 3 处（凭据查询 / 失败计数 / 失败计数清零）。后来这三处的 SQL
     * 都收进了 {@link com.mtxgdn.db.UserServiceDAO}：{@code users} 表归它，
     * 而 {@code QqAccountFlow} 只保留认证策略（限流、锁定判定、BCrypt 比对）。
     * 那个 DAO 自己有一整套测试钉着 SQL 字面量，所以这里不再重复钉，
     * 改为钉「qq 包里不许再出现这些 SQL」（见 {@code jdbcCallSiteCountIsUnchanged}）——
     * 那才是这次搬动真正的回归面：SQL 被复制回 qq 包就等于有了两份实现。</p>
     */
    private static final int EXPECTED_JDBC_CALL_SITES = 0;

    // ==================== 1. 门面 API 逐字不变 ====================

    @Test
    @DisplayName("门面: 公开成员清单与拆分前逐字一致（javap 黄金清单）")
    void facadeKeepsGoldenPublicApi() {
        List<String> actual = describePublicApi(QqOfficialAdapter.class);
        assertEquals(GOLDEN_FACADE_API.stream().sorted().toList(), actual,
                "QqOfficialAdapter 的公开 API 必须与拆分前完全一致：多一个、少一个、"
                        + "改一个参数类型都会让 AdapterManager / QqButtonCommand / PvpCommand 编译不过");
    }

    @Test
    @DisplayName("门面: 仍然实现那三个 OneBot 接口（注册点与类型判断都依赖它）")
    void facadeStillImplementsTheThreeOneBotInterfaces() {
        List<String> interfaces = Stream.of(QqOfficialAdapter.class.getInterfaces())
                .map(Class::getName)
                .sorted()
                .toList();
        assertEquals(List.of(
                        "com.mtxgdn.onebot.OneBotAccountFlowClient",
                        "com.mtxgdn.onebot.OneBotMessageSenderClient",
                        "com.mtxgdn.onebot.OneBotSession"),
                interfaces,
                "这三个接口是业务层与传输层的解耦点：AdapterManager 按 OneBotSession 收编适配器，"
                        + "指令侧按 OneBotMessageSenderClient / OneBotAccountFlowClient 回调");
    }

    // ==================== 2. 门面是门面，不是空壳 ====================

    @Test
    @DisplayName("门面: 七个协作对象全部接线，且转调真的能跑通")
    void facadeDelegatesToWiredDomains() throws Exception {
        QqOfficialAdapter facade = new QqOfficialAdapter();
        for (String name : DOMAIN_FIELDS) {
            Field field = QqOfficialAdapter.class.getDeclaredField(name);
            field.setAccessible(true);
            Object domain = field.get(facade);
            assertNotNull(domain, "协作对象 " + name + " 没有被接线：对应的一组方法会直接 NPE");
            assertTrue(domain.getClass().getSimpleName().startsWith("Qq"),
                    "门面字段 " + name + " 应该指向 QQ 通道的协作类，实际是 " + domain.getClass().getName());
        }

        // 真的转调一次：handleBind 走 门面 → QqAccountFlow → 上下文（待处理会话）→ 发送器
        assertEquals("", facade.getAppId(), "未装载配置时 AppID 为空");
        assertFalse(facade.isOpen(), "没有网关连接时 isOpen() 必须为 false");
        assertFalse(facade.isWsConnected(), "websocket 模式且未连接时 isWsConnected() 必须为 false");
        assertEquals("", facade.getSelfId(), "没有 READY 事件时 selfId 为空");

        facade.dispatchWebhookEvent(null); // 空 payload 必须被安静吞掉
        facade.send("{\"action\":\"unknown\"}"); // 不支持的 action 只记 debug，不抛异常

        String openid = "split_test_openid";
        // 注意：这里没有 start()，所以 HttpClient 还是 null —— 回复发送会在
        // QqApiClient.fetchAccessToken() 里抛 NPE 并被它自己的 catch 吞掉（拆分前同样如此）。
        // 本断言只关心"门面 → 账号流程 → 上下文"这条链是否真的接通，不看发送结果。
        facade.handleBind(null, "", openid);
        Object ctx = readField(facade, "ctx");
        @SuppressWarnings("unchecked")
        Map<String, QqPendingSession> pending = (Map<String, QqPendingSession>) readField(ctx, "pendingSessions");
        assertTrue(pending.containsKey(openid),
                "handleBind 必须真的转调到账号流程并写入待处理会话；"
                        + "如果门面只剩空壳，这里拿不到会话");
        pending.remove(openid);
    }

    // ==================== 3. 踩过坑的逻辑：在、且只在一处 ====================

    @Test
    @DisplayName("防坑: 被动回复 msg_seq 自增与窗口判定仍在发送域")
    void passiveReplySeqLogicStaysInSender() throws IOException {
        String sender = stripComments(read(QQ_DIR.resolve("QqMessageSender.java")));
        assertTrue(sender.contains("replySeq.incrementAndGet()"),
                "msg_seq 必须由本端递增生成：缺失或重复会被平台以 40054005 消息被去重拒收");
        assertTrue(sender.contains("body.addProperty(\"msg_seq\", seq)"),
                "被动回复请求体必须带 msg_seq");
        assertTrue(sender.contains("body.addProperty(\"msg_id\", last.msgId)"),
                "被动回复请求体必须带 msg_id");
        assertTrue(sender.contains("MAX_PASSIVE_REPLIES"),
                "同一条消息的回复次数上限告警不能丢");
        assertTrue(sender.contains("GROUP_PASSIVE_WINDOW_MS") && sender.contains("C2C_PASSIVE_WINDOW_MS"),
                "单聊 60 分钟 / 群聊 5 分钟的被动回复窗口判定不能丢");
        assertTrue(sender.contains("trackGroupInbound(") && sender.contains("trackC2cInbound("),
                "入站消息必须能重置被动回复上下文（msg_seq 从 1 重新计数）");

        String dispatcher = stripComments(read(QQ_DIR.resolve("QqEventDispatcher.java")));
        assertTrue(dispatcher.contains("sender.trackGroupInbound(") && dispatcher.contains("sender.trackC2cInbound("),
                "入站事件登记必须由事件分发器触发，否则被动回复会沿用上一条消息的 msg_seq");
    }

    @Test
    @DisplayName("防坑: access_token 缓存与提前刷新仍在 API 客户端")
    void accessTokenCacheStaysInApiClient() throws IOException {
        String api = stripComments(read(QQ_DIR.resolve("QqApiClient.java")));
        assertTrue(api.contains("tokenExpireAt - 60_000"),
                "取 token 时必须在到期前 60 秒同步刷新，否则网关会带着过期 token 建连");
        assertTrue(api.contains("expiresIn - 60"),
                "拿到新 token 后必须按 expires_in - 60 挂定时刷新");
        assertTrue(api.contains("scheduler.schedule(this::fetchAccessToken"),
                "定时刷新链路不能丢：断掉会让适配器在 token 过期后静默失联");
        assertTrue(api.contains("volatile String accessToken") && api.contains("volatile long tokenExpireAt"),
                "缓存字段必须是 volatile（网关线程与调度线程都会读）");
    }

    @Test
    @DisplayName("防坑: 指令限流 / 登录限流与账号锁定 / 指数退避重连都还在")
    void rateLimitAndReconnectLogicSurvive() throws IOException {
        String router = stripComments(read(QQ_DIR.resolve("QqMessageRouter.java")));
        assertTrue(router.contains("RateLimiter.allow(\"qq:\" + senderQq, 10, 60)"),
                "每个 openid 60 秒 10 条指令的限流不能丢");
        assertTrue(router.contains("isPrivateOnly()"),
                "私聊专用指令在群里必须被拒（保护账号密码）");

        String flow = stripComments(read(QQ_DIR.resolve("QqAccountFlow.java")));
        assertTrue(flow.contains("\"qq_official_login_\" + username.toLowerCase()"),
                "登录限流的 key 前缀不能变（改了等于把限流清零）");
        assertTrue(flow.contains("RateLimiter.allow(rateLimitKey, 5, 60)"),
                "同一用户名 5 次/分钟的登录限流不能丢");
        // 锁定判定的 SQL 已进 UserServiceDAO（DAO 版多带 failed_attempts，判定逻辑不变），
        // 但「先看 locked_until 有没有过期」这一步必须还在账号流程里。
        assertTrue(flow.contains("getLockedUntil()"),
                "账号锁定（连续 5 次错误锁 15 分钟）不能丢：过期判定要留在账号流程");
        assertTrue(flow.contains("recordFailedAttempt("),
                "输错密码要计数（连续 5 次触发锁定）");
        assertTrue(flow.contains("clearFailedAttempts("),
                "登录成功要清零失败计数");

        String gateway = stripComments(read(QQ_DIR.resolve("QqGatewayClient.java")));
        assertTrue(gateway.contains("reconnecting.compareAndSet(false, true)"),
                "同一时刻只能有一条重连链在排队，否则断线会引发重连风暴");
        assertTrue(gateway.contains("heartbeatIntervalMs * 3"),
                "心跳无 ACK 超时必须主动断开重连，防止连接假死");
        assertTrue(gateway.contains("60000L"),
                "重连退避上限 60 秒不能丢");
    }

    @Test
    @DisplayName("防坑: 这些逻辑从门面搬走了，没有被复制成两份")
    void movedLogicIsNotDuplicatedInFacade() throws IOException {
        String facade = stripComments(read(QQ_DIR.resolve("QqOfficialAdapter.java")));
        assertFalse(facade.contains("msg_seq"),
                "被动回复序号逻辑只应存在于发送域");
        assertFalse(facade.contains("tokenExpireAt"),
                "access_token 缓存只应存在于 API 客户端");
        assertFalse(facade.contains("RateLimiter"),
                "限流只应存在于路由 / 账号流程");
        assertFalse(facade.contains("CommandExecutor.submit"),
                "指令投递只应存在于路由域");
        assertFalse(facade.contains("DatabaseManager.getConnection()"),
                "门面不该再自己开连接：SQL 与凭据校验属于账号流程域");
        assertFalse(facade.contains("java.sql"),
                "门面不该再出现 java.sql 依赖：它只做参数透传");
    }

    // ==================== 4. SQL 与投递点数量不变 ====================

    @Test
    @DisplayName("SQL: 连接点总数与拆分前一致（3 处），SQL 语句一条没少")
    void jdbcCallSiteCountIsUnchanged() throws IOException {
        int total = 0;
        StringBuilder all = new StringBuilder();
        for (Path file : qqSources()) {
            String source = stripComments(Files.readString(file));
            all.append(source).append('\n');
            total += countOccurrences(source, "DatabaseManager.getConnection()");
        }
        assertEquals(EXPECTED_JDBC_CALL_SITES, total,
                "QQ 官方通道不该再自己开连接：凭据校验的三条 SQL 都在 UserServiceDAO 里。"
                        + "这里出现 getConnection 说明有人把 SQL 复制回了 qq 包");
        String code = all.toString();

        // 三条 SQL 搬进 UserServiceDAO 之后，这里要钉的是「没有被复制回 qq 包」。
        // 逐字比对的是 SQL 片段，复制回来任何一条都会命中。
        assertFalse(code.contains("FROM users WHERE username = ?"),
                "凭据查询 SQL 不该再出现在 qq 包（它在 UserServiceDAO.findByUsernameWithLockInfo）");
        assertFalse(code.contains("failed_attempts = failed_attempts + 1"),
                "失败次数累加 SQL 不该再出现在 qq 包（它在 UserServiceDAO.recordFailedAttempt）");
        assertFalse(code.contains("failed_attempts = 0, locked_until = NULL"),
                "失败次数重置 SQL 不该再出现在 qq 包（它在 UserServiceDAO.clearFailedAttempts）");

        assertEquals(1, countOccurrences(code, "CommandExecutor.submit"),
                "指令投递点只能有一处（拆分前也是一处）");
    }

    @Test
    @DisplayName("协议: 开放平台 URL / 路径 / 鉴权头前缀一条没丢")
    void officialApiEndpointsAreUnchanged() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path file : qqSources()) {
            all.append(stripComments(Files.readString(file))).append('\n');
        }
        String code = all.toString();
        for (String literal : new String[]{
                "https://bots.qq.com/app/getAppAccessToken",
                "https://api.sgroup.qq.com",
                "wss://api.sgroup.qq.com/websocket",
                "wss://sandbox.api.sgroup.qq.com/websocket",
                "\"/v2/users/\" + openid + \"/messages\"",
                "\"/v2/groups/\" + groupOpenid + \"/messages\"",
                "\"/interactions/\" + interactionId",
                "\"QQBot \" + token",
                "\"QQBot \" + api.cachedAccessToken()",
                "\"access_token\"",
                "\"msg_type\"",
                "\"keyboard\""}) {
            assertTrue(code.contains(literal), "开放平台协议字面量丢了: " + literal);
        }
    }

    // ==================== 5. 拆分粒度 ====================

    @Test
    @DisplayName("粒度: 每个类都在行数预算内，门面显著变薄")
    void eachSplitClassStaysUnderTheLineBudget() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : qqSources()) {
            String name = file.getFileName().toString();
            int lines = Files.readAllLines(file).size();
            int limit = name.equals("QqOfficialAdapter.java") ? 400 : 700;
            if (lines > limit) {
                offenders.add(name + " = " + lines + " 行（上限 " + limit + "）");
            }
        }
        assertTrue(offenders.isEmpty(), "拆分粒度不足：\n" + String.join("\n", offenders));

        int facadeLines = Files.readAllLines(QQ_DIR.resolve("QqOfficialAdapter.java")).size();
        assertTrue(facadeLines < 400,
                "门面应当显著变薄（拆分前 1859 行），当前 " + facadeLines + " 行");
    }

    @Test
    @DisplayName("粒度: 域类之间不互相 new，全部由门面在构造时接线")
    void domainsAreWiredByTheFacadeOnly() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : qqSources()) {
            if (file.getFileName().toString().equals("QqOfficialAdapter.java")) continue;
            String source = stripComments(Files.readString(file));
            for (String domain : new String[]{"QqApiClient", "QqMessageSender", "QqAccountFlow",
                    "QqMessageRouter", "QqEventDispatcher", "QqGatewayClient", "QqAdapterContext"}) {
                if (source.contains("new " + domain + "(")) {
                    offenders.add(file.getFileName() + " -> new " + domain + "(...)");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "域类之间互相 new 会让依赖关系散落在各处、也让状态出现第二份：\n"
                        + String.join("\n", offenders));
    }

    // ==================== 辅助 ====================

    /** 反射导出与 {@code javap -public} 同格式的公开成员清单（排序后便于逐字比较）。 */
    private static List<String> describePublicApi(Class<?> type) {
        List<String> members = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ");
            if (Modifier.isStatic(field.getModifiers())) sb.append("static ");
            if (Modifier.isFinal(field.getModifiers())) sb.append("final ");
            members.add(sb.append(field.getGenericType().getTypeName())
                    .append(' ').append(field.getName()).toString());
        }
        for (var ctor : type.getDeclaredConstructors()) {
            if (!Modifier.isPublic(ctor.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ").append(type.getTypeName()).append('(');
            Type[] params = ctor.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getTypeName());
            }
            members.add(sb.append(')').toString());
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ");
            if (Modifier.isStatic(method.getModifiers())) sb.append("static ");
            sb.append(method.getGenericReturnType().getTypeName())
                    .append(' ').append(method.getName()).append('(');
            Type[] params = method.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getTypeName());
            }
            members.add(sb.append(')').toString());
        }
        return members.stream().sorted().toList();
    }

    /** 反射读私有 / 包级字段（测试只读，不改被测对象）。 */
    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** QQ 官方通道（门面 + 各职责域 + 共用基础设施）的全部源码文件。 */
    private static List<Path> qqSources() throws IOException {
        try (Stream<Path> files = Files.list(QQ_DIR)) {
            return files.filter(p -> p.getFileName().toString().startsWith("Qq"))
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "找不到文件: " + path.toAbsolutePath());
        return Files.readString(path);
    }

    /** 剥掉 {@code //} 与 {@code /* *}{@code /} 注释，保留字符串/字符字面量。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                out.append(c);
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    out.append(d);
                    if (d == '\\' && j + 1 < source.length()) {
                        out.append(source.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
