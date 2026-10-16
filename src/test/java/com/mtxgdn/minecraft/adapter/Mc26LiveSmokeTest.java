package com.mtxgdn.minecraft.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 对真实 Minecraft 26.2 服务端的端到端冒烟测试（默认跳过）。
 * <p>
 * 单测只能证明「字节布局符合从官方源码读出来的结论」，这个测试证明「服务端真的接受这些包」。
 * 它会真的登录、过配置阶段、发公屏、发私聊。
 * <p>
 * 跑法（先起一个离线模式 26.2 服务端）：
 * <pre>
 * java -jar server.jar nogui        # server.properties: online-mode=false, enforce-secure-profile=false
 * mvn test -Dtest=Mc26LiveSmokeTest -Dmc26.live=true -Dmc26.port=25577
 * </pre>
 * 不开 {@code -Dmc26.live=true} 时整类被跳过，不会影响常规测试。
 */
@EnabledIfSystemProperty(named = "mc26.live", matches = "true")
class Mc26LiveSmokeTest {

    private static final String HOST = System.getProperty("mc26.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("mc26.port", 25577);
    private static final long TIMEOUT_SEC = 20;

    @Test
    @DisplayName("真实 26.2 服务端：登录→配置阶段→PLAY，公屏与私聊都能正确分类")
    void publicChatAndTellAgainstRealServer() throws Exception {
        MinecraftClient bot = newBot("XiuXianBot");
        BlockingQueue<String[]> tells = new LinkedBlockingQueue<>();
        BlockingQueue<String[]> chats = new LinkedBlockingQueue<>();
        AtomicReference<String> kick = new AtomicReference<>();
        bot.setOnTellMessage((sender, message) -> tells.add(new String[]{sender, message}));
        bot.setOnChatMessage((sender, message) -> chats.add(new String[]{sender, message}));
        bot.setOnKick(kick::set);

        assertTrue(bot.connect(), "连不上 " + HOST + ":" + PORT);
        assertTrue(bot.login(), "登录失败");
        assertTrue(bot.awaitPlayReady(TimeUnit.SECONDS.toMillis(TIMEOUT_SEC)),
                "没能在 " + TIMEOUT_SEC + "s 内进入 PLAY 状态（配置阶段卡住了？）");
        assertEquals("XiuXianBot", bot.getPlayerName());

        MinecraftClient alice = newBot("Alice");
        AtomicReference<String> aliceKick = new AtomicReference<>();
        alice.setOnKick(aliceKick::set);
        assertTrue(alice.connect(), "第二个客户端连不上");
        assertTrue(alice.login(), "第二个客户端登录失败");
        assertTrue(alice.awaitPlayReady(TimeUnit.SECONDS.toMillis(TIMEOUT_SEC)),
                "第二个客户端没进入 PLAY 状态");

        // 让服务端把加入消息、注册表都发完
        Thread.sleep(1000);

        // ---- 公屏：Alice 发一句，机器人必须收到，而且不能当成私聊 ----
        alice.sendChatMessage("hello everyone");
        String[] chat = awaitFrom(chats, "Alice", TIMEOUT_SEC, "公屏消息");
        assertEquals("hello everyone", chat[1], "公屏正文不对");
        assertTrue(tells.isEmpty(), "公屏消息被误判成私聊（聊天类型注册表没生效？）");
        assertTrue(aliceKick.get() == null, "第二个客户端被踢: " + aliceKick.get());

        // ---- 私聊：/msg 走 DisguisedChat，必须落进 tell 回调 ----
        alice.sendCommand("msg XiuXianBot status");
        String[] tell = awaitFrom(tells, "Alice", TIMEOUT_SEC, "私聊消息");
        assertEquals("status", tell[1], "私聊正文不对");
        assertTrue(kick.get() == null, "机器人被踢: " + kick.get());

        bot.disconnect();
        alice.disconnect();
    }

    /** 协议版本传 0 = 走 Status Ping 自动检测（生产环境的推荐配置）。 */
    private static MinecraftClient newBot(String name) {
        return new MinecraftClient(HOST, PORT, name, 0);
    }

    /** 忽略系统消息（sender 为 null），只等指定玩家发的消息。 */
    private static String[] awaitFrom(BlockingQueue<String[]> queue, String sender,
                                      long timeoutSec, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
        List<String> seen = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            String[] msg = queue.poll(500, TimeUnit.MILLISECONDS);
            if (msg == null) {
                continue;
            }
            if (sender.equals(msg[0])) {
                return msg;
            }
            seen.add(String.valueOf(msg[0]) + ": " + msg[1]);
        }
        return fail("没等到 " + what + "，期间收到: " + seen);    }
}
