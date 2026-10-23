package com.mtxgdn.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtxgdn.common.GameErrorCode;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.websockets.DataFrame;
import org.glassfish.grizzly.websockets.WebSocket;
import org.glassfish.grizzly.websockets.WebSocketListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GameWebSocketApp} 拆分后必须守住的四件事。
 *
 * <p><b>为什么需要它</b>：入口类原来有 55 个 {@code case} 和 55 个处理函数挤在同一个
 * 文件里，拆成 11 个 {@code Ws*Handler} 之后，"某个 action 忘记接上处理器"
 * "某个 action 的权限检查被顺手丢掉" "帧长校验被挪到反序列化之后"这三类回退
 * <b>在编译期完全看不出来</b> —— 代码照样能编译、照样能跑，只是某个动作静默失效
 * 或变成任何登录用户都能调。所以这里用四条断言把它们钉死：</p>
 *
 * <ol>
 *   <li><b>action ↔ 处理器双向对齐</b>：分发 {@code switch} 里的每个 action 必须有且
 *       只有一个处理器声明它，处理器声明的每个 action 也必须真的在 {@code switch} 里，
 *       并且该分支实际调用的那个处理器类确实声明了它。漏一个、重复一个都失败。</li>
 *   <li><b>权限检查逐条对账</b>：53 个需要权限的 action 的权限码逐个与拆分前的契约比对
 *       （世界聊天 {@code chat} 的 {@code game.chat.world} 也在其中），且检查必须出现在
 *       委托调用<b>之前</b>；另有两个公开动作（{@code heartbeat} / {@code season_info}）
 *       必须保持无权限门。</li>
 *   <li><b>帧长校验仍在反序列化之前</b>：行为级 —— 超长帧必须被
 *       "错误码 {@code PARAM_INVALID} + close(4009)"拒掉，而同一套入口对<b>短</b>消息
 *       会真的去解析（回 {@code MESSAGE_PARSE_ERROR}），两者对比才能证明超长帧
 *       根本没进解析器；源码级 —— 校验语句在 {@code onMessage} 里位于
 *       {@code GameMessage.fromJson} 之前，且没有任何处理器持有这段校验。</li>
 *   <li><b>拆分确实发生了</b>：处理器类不止一个（防止有人把处理函数又塞回入口类，
 *       让前三条变成对单文件的空检查）。</li>
 * </ol>
 *
 * <p>所有"扫源码"的断言都先剥掉注释再匹配，因此注释里的关键字不构成证据。</p>
 */
class WsActionDispatchTest {

    private static final Path WS_DIR = Paths.get("src/main/java/com/mtxgdn/websocket");
    private static final Path APP_SOURCE = WS_DIR.resolve("GameWebSocketApp.java");

    /**
     * 拆分<b>之前</b>从 {@code GameWebSocketApp.handleGameMessage} 的 {@code switch (type)}
     * 里逐个抽出来的 action 名（55 个，排序）。
     *
     * <p>它是"协议不许变"的第一道锁：任何人删掉一个 action 或改名，这条断言立刻失败。</p>
     */
    private static final Set<String> EXPECTED_ACTIONS = new TreeSet<>(List.of(
            "breakthrough", "chat", "chat_history", "chat_private", "crafting_craft",
            "crafting_recipes", "cultivate_start", "cultivate_stop", "equipment_enhance",
            "exploration", "friend_accept", "friend_add", "friend_list", "friend_pending",
            "friend_reject", "friend_remove", "heal", "heartbeat", "inventory", "item_registry",
            "item_use", "map_locations", "map_surroundings", "map_travel", "morning_cultivation",
            "my_techniques", "player_info", "rank", "season_info", "secret_realm_areas",
            "secret_realm_enter", "sect_applications", "sect_appoint", "sect_approve",
            "sect_create", "sect_disband", "sect_donate", "sect_info", "sect_join", "sect_kick",
            "sect_leave", "sect_levelup", "sect_list", "sect_members", "sect_reject", "sect_take",
            "sect_top", "sect_transfer", "sect_war", "sect_warehouse", "technique_equip",
            "technique_learn", "technique_unequip", "technique_upgrade", "techniques"));

    /**
     * 拆分前每个 action 的权限码（53 条）。
     *
     * <p>这是"权限检查一条都不能少"的第二道锁：少一条、改一个字符都失败。
     * 不在表里的两个 action（{@code heartbeat} / {@code season_info}）必须保持无权限门 ——
     * 它们是心跳与全局季节信息，客户端在拿到角色之前就要用。</p>
     */
    private static final Map<String, String> EXPECTED_PERMISSIONS = new TreeMap<>(Map.ofEntries(
            Map.entry("chat", "game.chat.world"),
            Map.entry("chat_private", "game.chat.private"),
            Map.entry("chat_history", "game.chat.world"),
            Map.entry("rank", "game.rank.view"),
            Map.entry("friend_add", "game.friend.manage"),
            Map.entry("friend_accept", "game.friend.manage"),
            Map.entry("friend_reject", "game.friend.manage"),
            Map.entry("friend_remove", "game.friend.manage"),
            Map.entry("friend_list", "game.friend.manage"),
            Map.entry("friend_pending", "game.friend.manage"),
            Map.entry("heal", "game.player.info"),
            Map.entry("morning_cultivation", "game.player.info"),
            Map.entry("player_info", "game.player.info"),
            Map.entry("cultivate_start", "game.cultivate"),
            Map.entry("cultivate_stop", "game.cultivate"),
            Map.entry("breakthrough", "game.realm.breakthrough"),
            Map.entry("inventory", "game.inventory.view"),
            Map.entry("item_use", "game.item.use"),
            Map.entry("item_registry", "game.item.registry"),
            Map.entry("secret_realm_areas", "game.secret_realm"),
            Map.entry("secret_realm_enter", "game.secret_realm"),
            Map.entry("exploration", "game.explore"),
            Map.entry("techniques", "game.technique.learn"),
            Map.entry("my_techniques", "game.technique.learn"),
            Map.entry("technique_learn", "game.technique.learn"),
            Map.entry("technique_equip", "game.technique.equip"),
            Map.entry("technique_unequip", "game.technique.equip"),
            Map.entry("technique_upgrade", "game.technique.upgrade"),
            Map.entry("crafting_recipes", "game.crafting.recipes"),
            Map.entry("crafting_craft", "game.crafting.craft"),
            Map.entry("equipment_enhance", "game.equipment.enhance"),
            Map.entry("sect_list", "game.sect.manage"),
            Map.entry("sect_info", "game.sect.manage"),
            Map.entry("sect_members", "game.sect.manage"),
            Map.entry("sect_create", "game.sect.manage"),
            Map.entry("sect_join", "game.sect.manage"),
            Map.entry("sect_applications", "game.sect.manage"),
            Map.entry("sect_approve", "game.sect.manage"),
            Map.entry("sect_reject", "game.sect.manage"),
            Map.entry("sect_leave", "game.sect.manage"),
            Map.entry("sect_kick", "game.sect.manage"),
            Map.entry("sect_appoint", "game.sect.manage"),
            Map.entry("sect_warehouse", "game.sect.manage"),
            Map.entry("sect_donate", "game.sect.donate"),
            Map.entry("sect_take", "game.sect.warehouse"),
            Map.entry("sect_disband", "game.sect.manage"),
            Map.entry("sect_levelup", "game.sect.manage"),
            Map.entry("sect_transfer", "game.sect.manage"),
            Map.entry("sect_war", "game.sect.manage"),
            Map.entry("sect_top", "game.sect.manage"),
            Map.entry("map_surroundings", "game.player.info"),
            Map.entry("map_travel", "game.player.info"),
            Map.entry("map_locations", "game.player.info")));

    /** 拆分前就没有权限门的 action（公开动作）。 */
    private static final Set<String> EXPECTED_UNGUARDED = new TreeSet<>(List.of("heartbeat", "season_info"));

    private static final Pattern CASE_LABEL = Pattern.compile("case \"([^\"]+)\":");
    private static final Pattern PERMISSION_CALL =
            Pattern.compile("checkWsPermission\\(socket, msgId, userId, \"([^\"]+)\"\\)");
    private static final Pattern HANDLER_CALL = Pattern.compile("(\\w+)\\.(handle\\w+)\\(");
    private static final Pattern HANDLER_FIELD = Pattern.compile("(Ws\\w+Handler)\\s+(\\w+)\\s*=");

    // ==================== 1. action ↔ 处理器 双向对齐 ====================

    @Test
    @DisplayName("每个 action 都有且只有一个处理器：分发 switch 与处理器声明双向对齐")
    void everyActionHasExactlyOneHandler() throws Exception {
        Map<String, String> blocks = caseBlocks();
        assertEquals(EXPECTED_ACTIONS, new TreeSet<>(blocks.keySet()),
                "分发 switch 的 action 集合与拆分前不一致（漏了或多了 action）：\n"
                        + "缺少: " + minus(EXPECTED_ACTIONS, blocks.keySet())
                        + "\n多出: " + minus(blocks.keySet(), EXPECTED_ACTIONS));

        Map<String, Set<String>> handlers = handlerActions();
        assertTrue(handlers.size() > 1,
                "只发现一个处理器类，说明拆分被回退成单文件了: " + handlers.keySet());

        // 处理器侧：谁声明了哪些 action
        Map<String, List<String>> owners = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : handlers.entrySet()) {
            assertFalse(entry.getValue().isEmpty(),
                    entry.getKey() + " 声明了空 ACTIONS —— 它的动作会变成谁也调不到的死代码");
            for (String action : entry.getValue()) {
                owners.computeIfAbsent(action, k -> new ArrayList<>()).add(entry.getKey());
            }
        }

        Set<String> declared = new TreeSet<>(owners.keySet());
        assertEquals(EXPECTED_ACTIONS, declared,
                "处理器声明的 action 集合与分发 switch 不一致：\n"
                        + "处理器漏声明: " + minus(EXPECTED_ACTIONS, declared)
                        + "\n处理器多声明: " + minus(declared, EXPECTED_ACTIONS));

        List<String> duplicated = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : owners.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicated.add(entry.getKey() + " -> " + entry.getValue());
            }
        }
        assertTrue(duplicated.isEmpty(),
                "以下 action 被多个处理器同时声明（职责重叠，改一处会漏另一处）:\n"
                        + String.join("\n", duplicated));

        // 分支里真正调用的那个处理器，必须就是声明该 action 的那个类
        Map<String, String> fieldTypes = handlerFieldTypes();
        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            String action = entry.getKey();
            Matcher call = HANDLER_CALL.matcher(entry.getValue());
            if (!call.find()) {
                mismatched.add(action + " -> 分支里没有任何处理器调用（动作会静默失效）");
                continue;
            }
            String field = call.group(1);
            String type = fieldTypes.get(field);
            if (type == null) {
                mismatched.add(action + " -> 调用了未知的处理器字段 " + field);
                continue;
            }
            if (!handlers.getOrDefault(type, Set.of()).contains(action)) {
                mismatched.add(action + " -> 调用了 " + type + "，但该类的 ACTIONS 里没有这个 action");
            }
        }
        assertTrue(mismatched.isEmpty(),
                "分发分支与处理器声明对不上（漏接/接错处理器）:\n" + String.join("\n", mismatched));
    }

    // ==================== 2. 权限检查逐条对账 ====================

    @Test
    @DisplayName("53 个需要权限的 action 仍然逐条带权限检查，且检查在委托调用之前")
    void permissionChecksMatchTheRecordedContract() throws IOException {
        Map<String, String> blocks = caseBlocks();
        Map<String, String> actual = new TreeMap<>();
        List<String> outOfOrder = new ArrayList<>();

        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            String action = entry.getKey();
            String block = entry.getValue();
            Matcher m = PERMISSION_CALL.matcher(block);
            String permission = m.find() ? m.group(1) : null;
            actual.put(action, permission);

            if (permission != null) {
                int checkIdx = block.indexOf("checkWsPermission");
                Matcher call = HANDLER_CALL.matcher(block);
                int callIdx = call.find() ? call.start() : -1;
                if (callIdx < 0 || checkIdx > callIdx) {
                    outOfOrder.add(action);
                }
            }
        }

        List<String> problems = new ArrayList<>();
        for (String action : EXPECTED_ACTIONS) {
            String expected = EXPECTED_PERMISSIONS.get(action);
            String got = actual.get(action);
            if (expected == null) {
                if (got != null) {
                    problems.add(action + " 不该有权限门，实际检查了 " + got);
                }
            } else if (!expected.equals(got)) {
                problems.add(action + " 的权限码应为 " + expected + "，实际: " + got);
            }
        }
        assertTrue(problems.isEmpty(),
                "权限检查与拆分前的契约不一致（丢一条就等于对任何登录用户开放）:\n"
                        + String.join("\n", problems));

        assertTrue(outOfOrder.isEmpty(),
                "以下 action 的权限检查出现在处理器调用之后（先干活再判权 = 白判）:\n"
                        + String.join("\n", outOfOrder));

        assertEquals(53, EXPECTED_PERMISSIONS.size(), "权限契约表自身应覆盖 53 个 action");
        Set<String> unguarded = new TreeSet<>(EXPECTED_ACTIONS);
        unguarded.removeAll(EXPECTED_PERMISSIONS.keySet());
        assertEquals(EXPECTED_UNGUARDED, unguarded,
                "无权限门的 action 应当只有心跳与季节信息");
    }

    // ==================== 3. 帧长校验必须在反序列化之前 ====================

    @Test
    @DisplayName("超长帧在解析之前就被拒：回 PARAM_INVALID + close(4009)，且没有解析痕迹")
    void oversizedFrameIsRejectedBeforeDeserialization() {
        int max = com.mtxgdn.server.ServerConfigurator.getMaxWebSocketMessageSize();
        assertTrue(max > 0, "帧长上限必须是正数，否则校验会退化成全拒: " + max);

        GameWebSocketApp app = new GameWebSocketApp();

        RecordingSocket oversized = new RecordingSocket();
        app.onMessage(oversized, "x".repeat(max + 1));

        assertEquals(1, oversized.sent.size(),
                "超长帧只应回一条错误包，实际: " + oversized.sent);
        assertEquals(GameErrorCode.PARAM_INVALID.getCode(), codeOf(oversized.sent.get(0)),
                "超长帧必须回 PARAM_INVALID（不是 MESSAGE_PARSE_ERROR —— 那说明先进了解析器）");
        assertTrue(oversized.closedWith(4009, "消息过大"),
                "超长帧必须以 4009「消息过大」断开，实际: " + oversized.closeCode
                        + " / " + oversized.closeReason);

        // 正向对照：同一套入口对"短"消息会真的去解析，因此上面那条 PARAM_INVALID
        // 才能证明超长帧根本没进解析器（否则判据就只是个恒真的空壳）。
        RecordingSocket small = new RecordingSocket();
        app.onMessage(small, "这不是 JSON");
        assertEquals(1, small.sent.size(), "短消息应当进入解析并回一条错误包");
        assertEquals(GameErrorCode.MESSAGE_PARSE_ERROR.getCode(), codeOf(small.sent.get(0)),
                "短消息应当走到解析器并因解析失败回 MESSAGE_PARSE_ERROR");

        // 边界：正好等于上限的帧不该被帧长校验拦下（只允许"超过"才拦）
        RecordingSocket atLimit = new RecordingSocket();
        app.onMessage(atLimit, "y".repeat(max));
        assertEquals(GameErrorCode.MESSAGE_PARSE_ERROR.getCode(), codeOf(atLimit.sent.get(0)),
                "长度正好等于上限的帧不该被帧长校验拒掉（说明边界被写成了 >=）");
    }

    @Test
    @DisplayName("源码里帧长校验位于 onMessage 内、fromJson 之前，且没有搬到任何处理器里")
    void frameSizeCheckStaysInOnMessageBeforeParsing() throws IOException {
        String app = stripComments(Files.readString(APP_SOURCE, StandardCharsets.UTF_8));
        int onMessage = app.indexOf("public void onMessage(WebSocket socket, String text)");
        assertTrue(onMessage > 0, "GameWebSocketApp 里找不到 onMessage");

        int sizeCheck = app.indexOf("MAX_MESSAGE_CHARS", onMessage);
        int parse = app.indexOf("GameMessage.fromJson", onMessage);
        assertTrue(sizeCheck > onMessage, "onMessage 里没有帧长校验");
        assertTrue(parse > sizeCheck,
                "帧长校验必须排在 GameMessage.fromJson 之前，否则超长帧会先进 Gson：\n"
                        + app.substring(onMessage, Math.min(app.length(), onMessage + 900)));

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(WS_DIR)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("GameWebSocketApp.java")) {
                    continue;
                }
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains("MAX_MESSAGE_CHARS")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "帧长校验被搬进了处理器类 —— 处理器在解析之后才被调用，放到那里等于失效: " + offenders);
    }

    // ==================== 工具 ====================

    /**
     * 把分发 {@code switch} 拆成 "action → 该分支的源码片段"。
     *
     * <p>只看 {@code handleGameMessage} 里的那一个 {@code switch (type)}：文件里还有
     * 另外两个同名 switch（模块映射、排行榜类型），它们的 {@code case} 不是 action。</p>
     */
    private static Map<String, String> caseBlocks() throws IOException {
        String code = stripComments(Files.readString(APP_SOURCE, StandardCharsets.UTF_8));
        int gm = code.indexOf("private void handleGameMessage(");
        assertTrue(gm > 0, "找不到 handleGameMessage");
        int sw = code.indexOf("switch (type) {", gm);
        assertTrue(sw > gm, "handleGameMessage 里找不到 switch (type)");
        int def = code.indexOf("default:", sw);
        assertTrue(def > sw, "分发 switch 找不到 default 分支");

        String region = code.substring(sw, def);
        Matcher m = CASE_LABEL.matcher(region);
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
            starts.add(m.start());
        }
        assertFalse(names.isEmpty(), "分发 switch 里一个 case 都没扫到 —— 本测试会变成空壳");

        Map<String, String> blocks = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < names.size() ? starts.get(i + 1) : region.length();
            String action = names.get(i);
            assertFalse(blocks.containsKey(action),
                    "分发 switch 里 action " + action + " 出现了两次（后面的分支永远走不到）");
            blocks.put(action, region.substring(from, to));
        }
        return blocks;
    }

    /** 反射读出每个处理器类声明的 {@code ACTIONS}（类名 → action 集合）。 */
    private static Map<String, Set<String>> handlerActions() throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(WS_DIR)) {
            List<Path> candidates = files
                    .filter(p -> p.getFileName().toString().matches("Ws[A-Za-z]+Handler\\.java"))
                    .sorted()
                    .toList();
            for (Path file : candidates) {
                String simpleName = file.getFileName().toString().replace(".java", "");
                if ("WsHandler".equals(simpleName)) {
                    continue; // 抽象基类只提供广播/单发能力，不处理 action
                }
                Class<?> cls = Class.forName("com.mtxgdn.websocket." + simpleName);
                Field field = cls.getDeclaredField("ACTIONS");
                field.setAccessible(true);
                Object value = field.get(null);
                assertTrue(value instanceof Set, simpleName + ".ACTIONS 必须是 Set<String>");
                @SuppressWarnings("unchecked")
                Set<String> actions = (Set<String>) value;
                result.put(simpleName, new LinkedHashSet<>(actions));
            }
        }
        return result;
    }

    /** 入口类里"字段名 → 处理器类名"的映射（分发分支靠字段名调用处理器）。 */
    private static Map<String, String> handlerFieldTypes() throws IOException {
        String code = stripComments(Files.readString(APP_SOURCE, StandardCharsets.UTF_8));
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = HANDLER_FIELD.matcher(code);
        while (m.find()) {
            result.put(m.group(2), m.group(1));
        }
        return result;
    }

    private static Set<String> minus(Set<String> left, Set<String> right) {
        Set<String> diff = new TreeSet<>(left);
        diff.removeAll(right);
        return diff;
    }

    private static int codeOf(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(object.has("code"), "回包里没有 code 字段: " + json);
        return object.get("code").getAsInt();
    }

    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    /**
     * 只记录、不真发消息的 {@link WebSocket} 假连接。
     *
     * <p>用它而不是 Mockito：{@code WebSocket} 是 Grizzly 的接口，手写实现能精确记录
     * "发了什么、关到哪个码"，也不受 mock 框架对 JDK 版本的支持限制。</p>
     */
    private static final class RecordingSocket implements WebSocket {

        private final List<String> sent = new ArrayList<>();
        private int closeCode = Integer.MIN_VALUE;
        private String closeReason;

        private boolean closedWith(int expectedCode, String expectedReason) {
            return closeCode == expectedCode && expectedReason.equals(closeReason);
        }

        @Override
        public GrizzlyFuture<DataFrame> send(String data) {
            sent.add(data);
            return null;
        }

        @Override
        public GrizzlyFuture<DataFrame> send(byte[] bytes) {
            throw new UnsupportedOperationException("测试假连接不发送二进制帧");
        }

        @Override
        public void broadcast(Iterable<? extends WebSocket> recipients, String data) {
            throw new UnsupportedOperationException("测试假连接不做广播");
        }

        @Override
        public void broadcast(Iterable<? extends WebSocket> recipients, byte[] bytes) {
            throw new UnsupportedOperationException("测试假连接不做广播");
        }

        @Override
        public void broadcastFragment(Iterable<? extends WebSocket> recipients, String data, boolean last) {
            throw new UnsupportedOperationException("测试假连接不做广播");
        }

        @Override
        public void broadcastFragment(Iterable<? extends WebSocket> recipients, byte[] bytes, boolean last) {
            throw new UnsupportedOperationException("测试假连接不做广播");
        }

        @Override
        public GrizzlyFuture<DataFrame> sendPing(byte[] bytes) {
            throw new UnsupportedOperationException("测试假连接不发送 ping");
        }

        @Override
        public GrizzlyFuture<DataFrame> sendPong(byte[] bytes) {
            throw new UnsupportedOperationException("测试假连接不发送 pong");
        }

        @Override
        public GrizzlyFuture<DataFrame> stream(boolean last, String fragment) {
            throw new UnsupportedOperationException("测试假连接不支持分片");
        }

        @Override
        public GrizzlyFuture<DataFrame> stream(boolean last, byte[] bytes, int offset, int length) {
            throw new UnsupportedOperationException("测试假连接不支持分片");
        }

        @Override
        public void close() {
            closeCode = 1000;
            closeReason = "";
        }

        @Override
        public void close(int code) {
            closeCode = code;
            closeReason = "";
        }

        @Override
        public void close(int code, String reason) {
            closeCode = code;
            closeReason = reason;
        }

        @Override
        public boolean isConnected() {
            return closeCode == Integer.MIN_VALUE;
        }

        @Override
        public void onConnect() {
        }

        @Override
        public void onMessage(String text) {
        }

        @Override
        public void onMessage(byte[] bytes) {
        }

        @Override
        public void onFragment(boolean last, String fragment) {
        }

        @Override
        public void onFragment(boolean last, byte[] bytes) {
        }

        @Override
        public void onClose(DataFrame frame) {
        }

        @Override
        public void onPing(DataFrame frame) {
        }

        @Override
        public void onPong(DataFrame frame) {
        }

        @Override
        public boolean add(WebSocketListener listener) {
            return false;
        }

        @Override
        public boolean remove(WebSocketListener listener) {
            return false;
        }
    }
}
