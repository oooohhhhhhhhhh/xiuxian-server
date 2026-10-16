package com.mtxgdn.minecraft.adapter;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 NBT 读取器 + 聊天组件（Component）纯文本提取。
 * <p>
 * <b>为什么需要它：</b>从 1.20.3（协议 765）起，聊天组件在网络层不再是 JSON 字符串，而是 NBT
 * （{@code FriendlyByteBuf.writeNbt} → {@code NbtIo.writeAnyTag}：1 字节 tag 类型 + 无名 payload）。
 * 26.2（协议 776）延续了这一点，因此把组件当 JSON 解析永远拿不到文本。
 * <p>
 * 读取严格按 {@code DataInput} 语义实现：NBT 字符串是 {@code writeUTF}/{@code readUTF}
 * （2 字节长度 + modified UTF-8），正好与 {@link DataInputStream#readUTF()} 一致。
 */
final class McNbt {

    /** 单个 tag 允许的最大元素个数，防止畸形包触发超大数组分配。 */
    private static final int MAX_ELEMENTS = 1 << 22;

    private McNbt() {
    }

    // ==================== tag 读取 ====================

    /**
     * 读取一个「无名」tag：1 字节类型 + payload（TAG_End 没有 payload）。
     *
     * @return CompoundTag → {@code Map<String,Object>}；ListTag → {@code List<Object>}；
     *         StringTag → {@link String}；数值 tag → 包装类型；数组 tag → {@code byte[]}/{@code int[]}/{@code long[]}；
     *         TAG_End → {@code null}
     */
    static Object readTag(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type == 0) {
            return null;
        }
        return readPayload(in, type);
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1:  // TAG_Byte
                return in.readByte();
            case 2:  // TAG_Short
                return in.readShort();
            case 3:  // TAG_Int
                return in.readInt();
            case 4:  // TAG_Long
                return in.readLong();
            case 5:  // TAG_Float
                return in.readFloat();
            case 6:  // TAG_Double
                return in.readDouble();
            case 7: { // TAG_Byte_Array
                int len = readLength(in);
                byte[] value = new byte[len];
                in.readFully(value);
                return value;
            }
            case 8:  // TAG_String（modified UTF-8，与 vanilla writeUTF 一致）
                return in.readUTF();
            case 9: { // TAG_List：元素类型 + 数量 + 元素（元素没有名字）
                int elementType = in.readUnsignedByte();
                int len = readLength(in);
                List<Object> list = new ArrayList<>(Math.min(len, 256));
                for (int i = 0; i < len; i++) {
                    list.add(elementType == 0 ? null : readPayload(in, elementType));
                }
                return list;
            }
            case 10: { // TAG_Compound：名字 + 类型 + payload，直到 TAG_End
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == 0) {
                        return map;
                    }
                    String name = in.readUTF();
                    map.put(name, readPayload(in, childType));
                }
            }
            case 11: { // TAG_Int_Array
                int len = readLength(in);
                int[] value = new int[len];
                for (int i = 0; i < len; i++) {
                    value[i] = in.readInt();
                }
                return value;
            }
            case 12: { // TAG_Long_Array
                int len = readLength(in);
                long[] value = new long[len];
                for (int i = 0; i < len; i++) {
                    value[i] = in.readLong();
                }
                return value;
            }
            default:
                throw new IOException("未知 NBT tag 类型: " + type);
        }
    }

    /** 跳过（但仍完整解析）一个 tag，用于忽略不关心的 payload。 */
    static void skipTag(DataInputStream in) throws IOException {
        readTag(in);
    }

    private static int readLength(DataInputStream in) throws IOException {
        int len = in.readInt();
        // 合法流里每个元素至少占 1 字节，因此长度不可能超过剩余字节数；
        // 越界说明包被截断或对端不按协议发，直接报错而不是分配巨大数组。
        if (len < 0 || len > MAX_ELEMENTS || len > in.available()) {
            throw new IOException("NBT 长度非法: " + len + " (剩余 " + in.available() + " 字节)");
        }
        return len;
    }

    // ==================== Component → 纯文本 ====================

    /**
     * 把 {@code ComponentSerialization.CODEC} 解出来的 NBT 还原为纯文本。
     * <p>
     * 组件编码是 {@code either(STRING, either(list(component), 完整记录))}：
     * 纯文本组件直接是 StringTag；多段组件是 ListTag；带样式的组件是 CompoundTag，
     * 内容字段（{@code text}/{@code translate}/{@code keybind}/{@code score}/{@code selector}/{@code nbt}）
     * 与 {@code extra} 兄弟节点一起拼接。样式字段全部忽略。
     */
    static String toPlainText(Object tag) {
        StringBuilder sb = new StringBuilder();
        append(tag, sb, 0);
        return sb.toString();
    }

    private static void append(Object tag, StringBuilder sb, int depth) {
        if (tag == null || depth > 32) {
            return;
        }
        if (tag instanceof String text) {
            sb.append(text);
            return;
        }
        if (tag instanceof List<?> list) {
            for (Object element : list) {
                append(element, sb, depth + 1);
            }
            return;
        }
        if (!(tag instanceof Map<?, ?> rawMap)) {
            // 数值/数组形态的组件不存在，但真出现了也不该抛异常
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) rawMap;

        if (map.containsKey("text")) {
            append(map.get("text"), sb, depth + 1);
        } else if (map.containsKey("translate")) {
            appendTranslate(map, sb, depth);
        } else if (map.containsKey("keybind")) {
            append(map.get("keybind"), sb, depth + 1);
        } else if (map.containsKey("selector")) {
            append(map.get("selector"), sb, depth + 1);
        } else if (map.containsKey("nbt")) {
            append(map.get("nbt"), sb, depth + 1);
        } else if (map.containsKey("score")) {
            appendScore(map.get("score"), sb);
        } else if (map.containsKey("object")) {
            append(map.get("object"), sb, depth + 1);
        }

        append(map.get("extra"), sb, depth + 1);
    }

    /**
     * 翻译组件。原版客户端会用语言文件把 key 渲染成人话，这里没有语言文件，
     * 因此保留两条私聊相关的 key（历史行为：解析成 {@code X whispers to you: msg}，
     * 让 {@link MinecraftClient} 的纯文本兜底逻辑仍然可用），其余退化为 key + 参数。
     */
    private static void appendTranslate(Map<String, Object> map, StringBuilder sb, int depth) {
        String key = String.valueOf(map.get("translate"));
        List<?> args = map.get("with") instanceof List<?> list ? list : List.of();

        switch (key) {
            case "commands.message.display.incoming" -> {
                if (args.size() >= 2) {
                    append(args.get(0), sb, depth + 1);
                    sb.append(" whispers to you: ");
                    append(args.get(1), sb, depth + 1);
                    return;
                }
            }
            case "commands.message.display.outgoing" -> {
                if (args.size() >= 2) {
                    sb.append("You whisper to ");
                    append(args.get(0), sb, depth + 1);
                    sb.append(": ");
                    append(args.get(1), sb, depth + 1);
                    return;
                }
            }
            default -> {
                // 继续走下面的通用退化
            }
        }

        sb.append(key);
        if (!args.isEmpty()) {
            sb.append('[');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                append(args.get(i), sb, depth + 1);
            }
            sb.append(']');
        }
    }

    private static void appendScore(Object score, StringBuilder sb) {
        if (!(score instanceof Map<?, ?> raw)) {
            return;
        }
        Object name = raw.get("name");
        Object objective = raw.get("objective");
        if (name != null) {
            sb.append(name);
        }
        if (objective != null) {
            if (name != null) {
                sb.append(':');
            }
            sb.append(objective);
        }
    }
}
