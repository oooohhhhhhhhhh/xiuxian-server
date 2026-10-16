package com.mtxgdn.minecraft.adapter;

import com.mtxgdn.minecraft.VarInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minecraft 26.2（协议 776）协议层的回归测试。
 * <p>
 * 期望值全部来自官方未混淆服务端 {@code server-26.2.jar} 里对应包类的 {@code write/read} 实现，
 * 不是「跑通就算对」：这里的字节布局一旦写错，服务端会在 PacketDecoder 阶段直接断开连接。
 */
class McProtocol26Test {

    /** 26.2 的协议号（server-26.2.jar/version.json: protocol_version）。 */
    private static final McProtocolMapper V26_2 = McProtocolMapper.of(776);
    /** 1.21.x 的协议号，用来验证旧分支没有被改坏。 */
    private static final McProtocolMapper V1_21 = McProtocolMapper.of(775);

    // ==================== 发送方向：字节布局 ====================

    @Test
    @DisplayName("26.2 的 /指令 包只有命令字符串，多一个字节都会被服务端判成包过大")
    void chatCommandPacketIsOnlyTheCommandString() throws Exception {
        byte[] payload = MinecraftClient.encodeChatCommandPayload(V26_2, "tellraw Alice {\"text\":\"hi\"}", 1234L);

        byte[] expected = concat(
                varInt("tellraw Alice {\"text\":\"hi\"}".getBytes(StandardCharsets.UTF_8).length),
                "tellraw Alice {\"text\":\"hi\"}".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected, payload,
                "26.2 的 ServerboundChatCommandPacket 是 record(String command)，只写字符串");
    }

    @Test
    @DisplayName("旧协议的 /指令 包保留原有字段（时间戳/盐/签名数），不受 26.2 改动影响")
    void legacyChatCommandPacketKeepsOldFields() throws Exception {
        byte[] payload = MinecraftClient.encodeChatCommandPayload(V1_21, "status", 1234L);

        byte[] expected = concat(
                varInt(6), "status".getBytes(StandardCharsets.UTF_8),
                longBytes(1234L), longBytes(0L),          // Instant + salt
                new byte[]{0},                            // 无签名
                varInt(0), varInt(0));                    // ArgumentSignatures + LastSeenMessages(旧)
        assertArrayEquals(expected, payload,
                "旧分支保持原样：只有 26.2 走「纯字符串」的新布局");
    }

    @Test
    @DisplayName("26.2 的聊天包带上正确的 LastSeenMessages.Update（偏移+20位位图+校验字节）")
    void chatMessageCarries26_2LastSeenUpdate() throws Exception {
        byte[] payload = MinecraftClient.encodeChatPayload(V26_2, "hi", 42L);

        byte[] expected = concat(
                varInt(2), "hi".getBytes(StandardCharsets.UTF_8),
                longBytes(42L),                 // Instant
                longBytes(0L),                  // salt
                new byte[]{0},                  // 无签名
                varInt(0),                      // Update.offset
                new byte[]{0, 0, 0},            // 20 位确认位图 = ceil(20/8) = 3 字节
                new byte[]{0});                 // Update.checksum = 0（忽略校验）
        assertArrayEquals(expected, payload);
    }

    @Test
    @DisplayName("聊天包的 Update 字段长度必须是 5 字节，写少了服务端读越界")
    void lastSeenUpdateFieldIsFiveBytes() throws Exception {
        byte[] payload = MinecraftClient.encodeChatPayload(V26_2, "hi", 42L);
        int afterSalt = 1 + 2 + 8 + 8 + 1;   // 长度前缀 + "hi" + Instant + salt + 无签名
        assertEquals(5, payload.length - afterSalt,
                "offset(1) + 位图(3) + 校验(1)");
    }

    // ==================== 接收方向：解析 ====================

    @Test
    @DisplayName("26.2 DisguisedChat：组件走 NBT，chatType 是「注册表 id + 1」")
    void decodeDisguisedChat26() throws Exception {
        // 离线模式下玩家发的 /msg 就是这个包：Component + Bound(id, name, 可选 target)
        byte[] payload = concat(
                textComponent("status"),
                varInt(3),                       // holder id 3 → 注册表索引 2
                textComponent("Alice"),
                new byte[]{0});                  // 没有 target

        MinecraftClient.ChatMessage msg = MinecraftClient.decodeDisguisedChat(V26_2, payload);

        assertEquals(3, msg.chatTypeId());
        assertEquals("Alice", msg.sender());
        assertEquals("status", msg.content());
        assertNull(msg.target());
    }

    @Test
    @DisplayName("26.2 PlayerChat：首字段是 globalIndex，正文与发送者都要能读出来")
    void decodePlayerChat26() throws Exception {
        byte[] payload = concat(
                varInt(7),                       // globalIndex（旧解析漏掉这个字段，整包错位）
                longBytes(1L), longBytes(2L),    // sender UUID
                varInt(3),                       // index
                new byte[]{0},                   // 没有签名
                varInt(5), "hello".getBytes(StandardCharsets.UTF_8),
                longBytes(1000L), longBytes(0L), // body.timestamp / salt
                varInt(0),                       // LastSeenMessages.Packed 为空
                new byte[]{0},                   // 没有 unsignedContent
                varInt(0),                       // FilterMask.PASS_THROUGH
                varInt(1),                       // chatType holder id 1 → 索引 0
                textComponent("Bob"),
                new byte[]{0});                  // 没有 target

        MinecraftClient.ChatMessage msg = MinecraftClient.decodePlayerChat(V26_2, payload);

        assertEquals(1, msg.chatTypeId());
        assertEquals("Bob", msg.sender());
        assertEquals("hello", msg.content());
    }

    @Test
    @DisplayName("26.2 SystemChat：NBT 组件 + overlay 布尔")
    void decodeSystemChat26() throws Exception {
        byte[] payload = concat(textComponent("§6[修仙] §r你好"), new byte[]{1});

        MinecraftClient.ChatMessage msg = MinecraftClient.decodeSystemChat(V26_2, payload);

        assertEquals("[修仙] 你好", msg.content(), "颜色代码应被剥掉");
    }

    @Test
    @DisplayName("配置阶段同步的 chat_type 注册表决定 id 顺序")
    void parseChatTypeRegistryKeepsOrder() throws Exception {
        byte[] payload = concat(
                varInt("minecraft:chat_type".length()), "minecraft:chat_type".getBytes(StandardCharsets.UTF_8),
                varInt(3),
                idWithNoData("minecraft:chat"),
                idWithNoData("minecraft:msg_command_incoming"),
                idWithNoData("minecraft:msg_command_outgoing"));

        List<String> ids = MinecraftClient.parseChatTypeRegistry(payload);

        assertNotNull(ids);
        assertEquals(List.of("minecraft:chat", "minecraft:msg_command_incoming", "minecraft:msg_command_outgoing"), ids);
    }

    @Test
    @DisplayName("非 chat_type 注册表直接跳过，不解析也不分配内存")
    void parseChatTypeRegistrySkipsOtherRegistries() throws Exception {
        byte[] payload = concat(
                varInt("minecraft:dimension_type".length()),
                "minecraft:dimension_type".getBytes(StandardCharsets.UTF_8));

        assertNull(MinecraftClient.parseChatTypeRegistry(payload));
    }

    // ==================== 私聊判定 ====================

    private static final List<String> VANILLA_CHAT_TYPES = List.of(
            "minecraft:chat",
            "minecraft:emote_command",
            "minecraft:msg_command_incoming",
            "minecraft:msg_command_outgoing",
            "minecraft:say_command",
            "minecraft:team_msg_command_incoming",
            "minecraft:team_msg_command_outgoing");

    @Test
    @DisplayName("私聊判定看聊天类型：收件是 msg_command_incoming，自己发出的回显是 outgoing")
    void tellClassificationUsesChatType() {
        int incoming = indexOf(VANILLA_CHAT_TYPES, "minecraft:msg_command_incoming") + 1;
        int outgoing = indexOf(VANILLA_CHAT_TYPES, "minecraft:msg_command_outgoing") + 1;
        int chat = indexOf(VANILLA_CHAT_TYPES, "minecraft:chat") + 1;

        assertTrue(MinecraftClient.isIncomingTell(VANILLA_CHAT_TYPES, incoming, "Alice", "XiuXianBot", null));
        assertFalse(MinecraftClient.isIncomingTell(VANILLA_CHAT_TYPES, outgoing, "XiuXianBot", "XiuXianBot", null));
        assertTrue(MinecraftClient.isOutgoingTell(VANILLA_CHAT_TYPES, outgoing));
        assertFalse(MinecraftClient.isIncomingTell(VANILLA_CHAT_TYPES, chat, "Alice", "XiuXianBot", null),
                "公屏不能被当成私聊");
    }

    @Test
    @DisplayName("注册表缺失时退化为「非本人发送」，而不是把所有私聊丢掉")
    void tellClassificationFallsBackWhenRegistryMissing() {
        assertTrue(MinecraftClient.isIncomingTell(List.of(), 2, "Alice", "XiuXianBot", null));
        assertFalse(MinecraftClient.isIncomingTell(List.of(), 2, "XiuXianBot", "XiuXianBot", null));
    }

    // ==================== NBT 组件 ====================

    @Test
    @DisplayName("translate 组件的参数要拼出来，extra 兄弟节点也不能丢")
    void componentTextHandlesTranslateAndExtra() throws Exception {
        byte[] component = concat(
                new byte[]{10},                                     // TAG_Compound
                strTag("translate", "commands.message.display.incoming"),
                listTagOfText("with", "Alice", "status"),
                extraTextTag("（后缀）"),
                new byte[]{0});
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(component));

        String text = McNbt.toPlainText(McNbt.readTag(in));

        assertEquals("Alice whispers to you: status（后缀）", text);
    }

    @Test
    @DisplayName("组件可以是裸字符串、列表或带样式的复合节点")
    void componentTextHandlesAllShapes() throws Exception {
        assertEquals("裸字符串", readComponentText(concat(new byte[]{8}, strTagValue("裸字符串"))));
        assertEquals("a-b", readComponentText(listOfText("a", "-", "b")));
        assertEquals("样式文本", readComponentText(concat(
                new byte[]{10}, strTag("text", "样式文本"), strTag("color", "gold"), new byte[]{0})));
    }

    @Test
    @DisplayName("畸形长度直接报错，不会按声明长度分配巨大数组")
    void malformedLengthIsRejected() {
        // TAG_Byte_Array + 长度 0x7FFFFFFF，但后面没有数据
        byte[] payload = concat(new byte[]{7}, intBytes(Integer.MAX_VALUE));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        assertThrowsIo(() -> McNbt.readTag(in));
    }

    // ==================== 协议映射表 ====================

    @Test
    @DisplayName("26.2 的包号与字段布局开关都对上")
    void protocol776Mapping() {
        assertEquals(0x79, V26_2.s2cSystemChat);
        assertEquals(0x21, V26_2.s2cDisguisedChat);
        assertEquals(0x41, V26_2.s2cPlayerChat);
        assertEquals(0x20, V26_2.s2cDisconnect);
        assertEquals(0x09, V26_2.c2sChatMessage);
        assertEquals(0x07, V26_2.c2sChatCommand);
        assertEquals(0x1C, V26_2.c2sKeepAlive);
        assertEquals(0x07, V26_2.s2cConfigRegistryData);
        assertEquals(0x13, V26_2.s2cConfigCodeOfConduct);
        assertEquals(0x09, V26_2.c2sConfigAcceptCodeOfConduct);
        assertEquals(0x2C, V26_2.c2sPlayerLoaded);

        assertTrue(V26_2.componentsAreNbt);
        assertTrue(V26_2.plainChatCommand);
        assertTrue(V26_2.chatAckV2);
        assertEquals("26.2+", V26_2.versionLabel);
    }

    @Test
    @DisplayName("1.21.x 仍然是旧布局（26.2 的改动不能泄漏到旧协议）")
    void protocol775KeepsOldLayout() {
        assertFalse(V1_21.plainChatCommand);
        assertFalse(V1_21.chatAckV2);
        assertTrue(V1_21.componentsAreNbt, "1.20.3 起组件就是 NBT");
        assertEquals(-1, V1_21.s2cConfigCodeOfConduct, "行为准则是 26.2 新增的");
    }

    // ==================== 辅助 ====================

    private static int indexOf(List<String> list, String value) {
        int index = list.indexOf(value);
        assertTrue(index >= 0, "测试数据里必须有 " + value);
        return index;
    }

    private static String readComponentText(byte[] nbt) throws IOException {
        return McNbt.toPlainText(McNbt.readTag(new DataInputStream(new ByteArrayInputStream(nbt))));
    }

    private static void assertThrowsIo(ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("期望抛出 IOException");
        } catch (IOException expected) {
            // ok
        }
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    private static byte[] varInt(int value) {
        return VarInt.writeVarInt(value);
    }

    private static byte[] longBytes(long value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeLong(value);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return b.toByteArray();
    }

    private static byte[] intBytes(int value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeInt(value);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return b.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    /** 无名 TAG_String 的 payload（列表元素用）。 */
    private static byte[] strTagValue(String value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeUTF(value);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return b.toByteArray();
    }

    /** 无名 TAG_Compound { key: value }。 */
    private static byte[] strTag(String key, String value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeByte(8);
            d.writeUTF(key);
            d.writeUTF(value);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return b.toByteArray();
    }

    /** 一个聊天组件：{@code {text: "..."}}。 */
    private static byte[] textComponent(String text) {
        return concat(new byte[]{10}, strTag("text", text), new byte[]{0});
    }

    /** 复合节点里的 {@code extra} 列表：一个 {@code {text: "..."}} 组件。 */
    private static byte[] extraTextTag(String text) {
        return concat(new byte[]{9}, strTagValue("extra"), new byte[]{10}, intBytes(1),
                new byte[]{8}, strTagValue("text"), strTagValue(text), new byte[]{0});
    }

    /** TAG_List，元素是 {@code {text: "..."}} 组件。 */
    private static byte[] listTagOfText(String key, String... texts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeByte(9);
            d.writeUTF(key);
            d.writeByte(10);
            d.writeInt(texts.length);
            for (String text : texts) {
                d.writeByte(8);
                d.writeUTF("text");
                d.writeUTF(text);
                d.writeByte(0);
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return b.toByteArray();
    }

    private static byte[] listOfText(String... texts) {
        return concat(new byte[]{9}, new byte[]{8}, intBytes(texts.length), concat(java.util.Arrays.stream(texts)
                .map(McProtocol26Test::strTagValue).toArray(byte[][]::new)));
    }

    /** 注册表条目：Identifier + Optional&lt;Tag&gt; = false。 */
    private static byte[] idWithNoData(String id) {
        return concat(varInt(id.length()), id.getBytes(StandardCharsets.UTF_8), new byte[]{0});
    }
}
