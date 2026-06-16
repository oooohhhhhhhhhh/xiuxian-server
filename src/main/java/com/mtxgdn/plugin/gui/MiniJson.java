package com.mtxgdn.plugin.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量级 JSON 读写工具 —— 不依赖第三方库。
 * <p>
 * 支持：字符串、数字、布尔、null、对象(Map)、数组(List)。
 * <p>
 * 主要用途：PluginMakerGUI 的配置持久化（保存/加载插件制作配置）。
 */
public final class MiniJson {

    // ==================== 解析（从 JSON 字符串到 Java 对象） ====================

    public static Object parse(String json) {
        if (json == null) return null;
        Parser p = new Parser(json);
        p.skipWhitespace();
        return p.parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String s) { this.src = s; this.pos = 0; }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) throw new RuntimeException("JSON 解析: 意外结束");
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { pos += 4; return null; }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // skip '{'
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; return map; }
            while (pos < src.length()) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != ':') throw new RuntimeException("JSON 解析: 期望 ':'");
                pos++;
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
                if (pos < src.length() && src.charAt(pos) == '}') { pos++; return map; }
                throw new RuntimeException("JSON 解析: 期望 ',' 或 '}'");
            }
            throw new RuntimeException("JSON 解析: 对象未闭合");
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // skip '['
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
            while (pos < src.length()) {
                list.add(parseValue());
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
                if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
                throw new RuntimeException("JSON 解析: 数组中期望 ',' 或 ']'");
            }
            throw new RuntimeException("JSON 解析: 数组未闭合");
        }

        String parseString() {
            if (pos >= src.length() || src.charAt(pos) != '"') throw new RuntimeException("JSON 解析: 期望 '\"'");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '"') { pos++; return sb.toString(); }
                if (c == '\\') {
                    pos++;
                    if (pos >= src.length()) throw new RuntimeException("JSON 解析: 转义未完成");
                    char esc = src.charAt(pos);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 >= src.length()) throw new RuntimeException("JSON 解析: unicode 不完整");
                            sb.append((char) Integer.parseInt(src.substring(pos + 1, pos + 5), 16));
                            pos += 4;
                            break;
                        default: sb.append(esc);
                    }
                    pos++;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            throw new RuntimeException("JSON 解析: 字符串未闭合");
        }

        Boolean parseBoolean() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new RuntimeException("JSON 解析: 非法布尔值");
        }

        Object parseNumber() {
            int start = pos;
            if (pos < src.length() && src.charAt(pos) == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            boolean isFloat = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            String num = src.substring(start, pos);
            try {
                return isFloat ? Double.parseDouble(num) : Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new RuntimeException("JSON 解析: 非法数字 '" + num + "'");
            }
        }
    }

    // ==================== 序列化（从 Java 对象到 JSON 字符串） ====================

    public static String stringify(Object obj) {
        StringBuilder sb = new StringBuilder();
        write(sb, obj, 0);
        return sb.toString();
    }

    /** 格式化输出（带缩进）。 */
    public static String stringifyPretty(Object obj) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, obj, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object obj, int indent) {
        if (obj == null) { sb.append("null"); return; }
        if (obj instanceof Boolean) { sb.append(((Boolean) obj).toString()); return; }
        if (obj instanceof Number) { sb.append(obj.toString()); return; }
        if (obj instanceof String) { writeString(sb, (String) obj); return; }
        if (obj instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) obj;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), indent);
            }
            sb.append('}');
            return;
        }
        if (obj instanceof List) {
            List<?> l = (List<?>) obj;
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, indent);
            }
            sb.append(']');
            return;
        }
        // 其他类型按字符串处理
        writeString(sb, obj.toString());
    }

    private static void writePretty(StringBuilder sb, Object obj, int indent) {
        if (obj == null) { sb.append("null"); return; }
        if (obj instanceof Boolean || obj instanceof Number) { sb.append(obj.toString()); return; }
        if (obj instanceof String) { writeString(sb, (String) obj); return; }
        if (obj instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) obj;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
            return;
        }
        if (obj instanceof List) {
            List<?> l = (List<?>) obj;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writePretty(sb, o, indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
            return;
        }
        writeString(sb, obj.toString());
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent * 2; i++) sb.append(' ');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ==================== 辅助：从 Map 安全提取值 ====================

    public static String getString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static boolean getBoolean(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getListOfObjects(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object o : (List<Object>) v) {
                if (o instanceof Map) result.add((Map<String, Object>) o);
            }
            return result;
        }
        return new ArrayList<>();
    }

    private MiniJson() {}
}
