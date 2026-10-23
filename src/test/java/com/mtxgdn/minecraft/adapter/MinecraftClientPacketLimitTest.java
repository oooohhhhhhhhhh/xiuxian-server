package com.mtxgdn.minecraft.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据包长度必须校验后才允许分配内存。
 *
 * <p><b>缺陷形态</b>：{@code MinecraftClient.readPacket()} 直接拿对端给的数字当数组长度：</p>
 *
 * <pre>{@code
 * int packetLen = VarInt.readVarInt(in);
 * ...
 * int dataLen = VarInt.readVarInt(in);       // 压缩包里的"未压缩长度"，同样来自对端
 * byte[] decompressed = new byte[dataLen];   // 2GB 就直接 OutOfMemoryError
 * }</pre>
 *
 * <p>一个恶意/异常的 MC 服务端（或中间人、或损坏的流）只要声明一个巨大的长度，
 * 就能让我们在收到任何实际数据之前先 {@code OutOfMemoryError} —— 整个 Java 进程直接死掉，
 * 连日志都来不及写。这是典型的"用输入当分配长度"。</p>
 *
 * <p>修复：分配前按 {@code MAX_PACKET_SIZE}（2,097,151，即协议 3 字节 VarInt 上限）校验，
 * 超限抛 {@link IOException}，由调用方按断线处理。</p>
 */
class MinecraftClientPacketLimitTest {

    @Test
    @DisplayName("无压缩分支：声明超大包长直接抛 IOException，不分配内存")
    void rejectsOversizedUncompressedPacket() throws Exception {
        MinecraftClient client = new MinecraftClient("127.0.0.1", 25565, "TestBot", 0);
        setField(client, "compressionThreshold", -1);

        // packetLen 声明为 Integer.MAX_VALUE（VarInt 5 字节）
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        writeVarInt(bos, Integer.MAX_VALUE);
        setField(client, "in", new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        IOException ex = assertThrows(IOException.class, () -> invokeReadPacket(client),
                "声明 2GB 的包必须被拒绝；若这里抛 OutOfMemoryError 说明校验没生效");
        assertTrue(ex.getMessage().contains("包长度超过上限"),
                "异常信息应说明是长度超限，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("压缩分支：压缩包里的未压缩长度同样要校验")
    void rejectsOversizedUncompressedLength() throws Exception {
        MinecraftClient client = new MinecraftClient("127.0.0.1", 25565, "TestBot", 0);
        setField(client, "compressionThreshold", 256);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // packetLen 本身合法（比如 10 字节），但里面的"未压缩长度"声明成 2GB
        writeVarInt(bos, 10);
        writeVarInt(bos, Integer.MAX_VALUE);
        setField(client, "in", new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        IOException ex = assertThrows(IOException.class, () -> invokeReadPacket(client));
        assertTrue(ex.getMessage().contains("包长度超过上限") || ex.getMessage().contains("非法包长度"),
                "异常信息应说明长度非法，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("负数长度被拒绝（不是抛 NegativeArraySizeException）")
    void rejectsNegativeLength() throws Exception {
        Method check = MinecraftClient.class.getDeclaredMethod("checkPacketLength", int.class, String.class);
        check.setAccessible(true);
        Exception ex = assertThrows(Exception.class,
                () -> check.invoke(null, -5, "packetLen"));
        assertTrue(ex.getCause() instanceof IOException,
                "负数长度应抛 IOException，实际: " + ex.getCause());
    }

    @Test
    @DisplayName("正常长度不误伤：一个合法的未压缩包能正常读出")
    void acceptsNormalPacket() throws Exception {
        MinecraftClient client = new MinecraftClient("127.0.0.1", 25565, "TestBot", 0);
        setField(client, "compressionThreshold", -1);

        int packetId = 0x0A;
        byte[] payload = new byte[]{1, 2, 3, 4};
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, packetId);
        packet.write(payload);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        writeVarInt(bos, packet.size());
        bos.write(packet.toByteArray());
        setField(client, "in", new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        Object[] result = assertDoesNotThrow(() -> invokeReadPacket(client));
        assertTrue(result != null && result.length == 2);
        assertTrue(((Integer) result[0]) == packetId, "包 ID 应正确");
        assertTrue(java.util.Arrays.equals(payload, (byte[]) result[1]), "负载应正确");
    }

    @Test
    @DisplayName("压缩包正常路径不受影响（zlib 往返）")
    void acceptsNormalCompressedPacket() throws Exception {
        MinecraftClient client = new MinecraftClient("127.0.0.1", 25565, "TestBot", 0);
        setField(client, "compressionThreshold", 1);

        int packetId = 0x0B;
        byte[] payload = "hello xiuxian".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        writeVarInt(raw, packetId);
        raw.write(payload);
        byte[] rawBytes = raw.toByteArray();

        Deflater deflater = new Deflater();
        deflater.setInput(rawBytes);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            compressed.write(buf, 0, n);
        }
        deflater.end();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, rawBytes.length);   // 未压缩长度
        body.write(compressed.toByteArray());

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        writeVarInt(stream, body.size());     // packetLen
        stream.write(body.toByteArray());
        setField(client, "in", new DataInputStream(new ByteArrayInputStream(stream.toByteArray())));

        Object[] result = assertDoesNotThrow(() -> invokeReadPacket(client),
                "合法的压缩包必须能正常解出");
        assertTrue(((Integer) result[0]) == packetId);
        assertTrue(java.util.Arrays.equals(payload, (byte[]) result[1]));
    }

    @Test
    @DisplayName("上限常量与协议一致（3 字节 VarInt 最大值）")
    void maxPacketSizeMatchesProtocol() {
        assertTrue(MinecraftClient.MAX_PACKET_SIZE == 2_097_151,
                "上限应为 2,097,151（3 字节 VarInt 最大值），实际: " + MinecraftClient.MAX_PACKET_SIZE);
    }

    // ==================== 夹具 ====================

    private static Object[] invokeReadPacket(MinecraftClient client) throws Exception {
        Method m = MinecraftClient.class.getDeclaredMethod("readPacket");
        m.setAccessible(true);
        try {
            return (Object[]) m.invoke(client);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 与 Minecraft 协议一致的无符号 VarInt 写入。 */
    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }
}
