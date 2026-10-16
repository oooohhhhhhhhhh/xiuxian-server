package com.mtxgdn.util;

import org.yaml.snakeyaml.Yaml;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * YAML 配置文件的行级编辑器：只改值，保留注释、空行与缩进结构。
 * <p>
 * 原实现内嵌在 {@link AppConfig#update(Map)} 中，仅能写 application.yml。
 * 插件开关迁到独立的 {@code config/plugins.yml} 后需要同样的“保留注释”写盘能力，
 * 故抽成通用工具，由 AppConfig 与 PluginSwitches 共用。
 * <p>
 * 值语义：{@code null} 表示删除该键所在行（含紧随其后缩进更深的注释延续行）；
 * 空字符串表示写入空值 {@code ''}；其余值按 YAML 标量写入（必要时自动加引号）。
 * 写盘使用临时文件 + 原子 rename，避免半截写入。
 */
public final class YamlFileEditor {

    private YamlFileEditor() {
    }

    /**
     * 更新指定 YAML 文件中的若干扁平键（如 {@code plugins.farm}）并写回磁盘。
     * <p>
     * 文件不存在时：若 classpath 根目录存在同名资源，则先释放模板再写入；否则不写
     * （调用方负责保证配置文件已释放）。
     *
     * @param filePath 目标文件路径
     * @param updates  扁平键 → 值
     * @return true 表示文件内容确实发生了变化（无变化时不触碰文件）
     */
    public static synchronized boolean update(Path filePath, Map<String, Object> updates) {
        if (filePath == null || updates == null || updates.isEmpty()) {
            return false;
        }
        if (!Files.exists(filePath)) {
            extractFromClasspath(filePath);
        }
        if (!Files.exists(filePath)) {
            return false;
        }

        String content;
        try {
            content = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("读取 " + filePath.getFileName() + " 失败: " + e.getMessage(), e);
        }
        String eol = content.contains("\r\n") ? "\r\n" : "\n";
        // split(..., -1) 保留末尾空元素（文件末尾换行），String.join 后不丢尾部换行
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\r?\n", -1)));

        boolean changed = false;
        // 第一遍：删除语义（值为 null）——先删后插，避免新插入的行挡在待删行及其注释延续行之间
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            if (e.getValue() != null) {
                continue;
            }
            int idx = findKeyLine(lines, e.getKey().split("\\."));
            if (idx >= 0) {
                changed |= removeKeyLine(lines, idx);
            }
        }
        // 第二遍：替换 / 追加
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            String[] path = e.getKey().split("\\.");
            int idx = findKeyLine(lines, path);
            if (idx >= 0) {
                changed |= replaceValue(lines, idx, v);
            } else {
                changed |= insertKeyWithParents(lines, path, v);
            }
        }
        if (!changed) {
            return false;
        }

        try {
            Files.createDirectories(filePath.getParent());
            Path tmp = Files.createTempFile(filePath.getParent(), filePath.getFileName().toString() + ".", ".tmp");
            // 显式 UTF-8：避免中文 Windows 默认 GBK 导致配置中文乱码（读取端按 UTF-8 解析）
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(String.join(eol, lines));
            }
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            throw new RuntimeException("写入 " + filePath.getFileName() + " 失败: " + ex.getMessage(), ex);
        }
        return true;
    }

    /** 目标文件缺失时，尝试从 classpath 释放同名模板。 */
    private static void extractFromClasspath(Path filePath) {
        String name = filePath.getFileName().toString();
        try (var in = YamlFileEditor.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                return;
            }
            Files.createDirectories(filePath.getParent());
            Files.copy(in, filePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("[Config] 释放 " + name + " 失败: " + e.getMessage());
        }
    }

    /** 解析 YAML 文本为嵌套结构（写盘前校验用；解析失败返回 null）。 */
    public static Object parse(String yamlText) {
        try {
            return new Yaml().load(yamlText);
        } catch (Exception e) {
            return null;
        }
    }

    /** 计算行前导空白数（仅统计空格，模板统一使用 2 空格缩进）。 */
    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    /**
     * 在 lines 中查找 flatKey（如 "admin.password_hash"）对应键行的下标；未找到返回 -1。
     * 基于缩进栈解析块映射的父链，避免同名键误匹配（如 database.password 与 smtp.password）。
     */
    private static int findKeyLine(List<String> lines, String[] path) {
        String leaf = path[path.length - 1];
        String[] parents = Arrays.copyOf(path, path.length - 1);
        List<String> stack = new ArrayList<>();          // 父链（键名）
        List<Integer> stackIndent = new ArrayList<>();
        for (int idx = 0; idx < lines.size(); idx++) {
            String line = lines.get(idx);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            int indent = leadingSpaces(line);
            while (!stackIndent.isEmpty() && stackIndent.get(stackIndent.size() - 1) >= indent) {
                stackIndent.remove(stackIndent.size() - 1);
                stack.remove(stack.size() - 1);
            }
            if (stack.size() == parents.length) {
                boolean match = true;
                for (int i = 0; i < parents.length; i++) {
                    if (!parents[i].equals(stack.get(i))) {
                        match = false;
                        break;
                    }
                }
                if (match && key.equals(leaf)) {
                    return idx;
                }
            }
            stack.add(key);
            stackIndent.add(indent);
        }
        return -1;
    }

    /**
     * 替换键行的值，保留行内注释与对齐；返回是否发生修改。
     * 若该键在文件中的值是多行列表（行内无值且下一内容行是列表项），跳过并警告。
     */
    private static boolean replaceValue(List<String> lines, int idx, Object value) {
        String line = lines.get(idx);
        int colon = line.indexOf(':');
        if (colon < 0) {
            return false;
        }
        // 列表值保护：键行无内联值且紧跟更深缩进的列表项 → 不做标量替换
        int next = idx + 1;
        while (next < lines.size() && (lines.get(next).isBlank() || lines.get(next).trim().startsWith("#"))) {
            next++;
        }
        if (next < lines.size() && leadingSpaces(lines.get(next)) > leadingSpaces(line)
                && lines.get(next).trim().startsWith("- ")) {
            System.err.println("[Config] 跳过列表键 " + line.trim() + " 的值替换（暂不支持行级修改列表）");
            return false;
        }
        String head = line.substring(0, colon + 1);       // 含 "key:"
        String tail = line.substring(colon + 1);
        int valueStart = 0;
        while (valueStart < tail.length() && (tail.charAt(valueStart) == ' ' || tail.charAt(valueStart) == '\t')) {
            valueStart++;
        }
        int valueEnd = valueStart;
        if (valueEnd < tail.length() && (tail.charAt(valueEnd) == '"' || tail.charAt(valueEnd) == '\'')) {
            char quote = tail.charAt(valueEnd);
            valueEnd++; // 跳过开引号
            while (valueEnd < tail.length() && tail.charAt(valueEnd) != quote) {
                valueEnd++;
            }
            if (valueEnd < tail.length()) {
                valueEnd++; // 跳过闭引号
            }
        } else {
            while (valueEnd < tail.length()) {
                char c = tail.charAt(valueEnd);
                if (c == '#' && (valueEnd == valueStart
                        || tail.charAt(valueEnd - 1) == ' ' || tail.charAt(valueEnd - 1) == '\t')) {
                    break;
                }
                valueEnd++;
            }
            // 去掉值尾部的对齐空格（保留在 tail 中，使行内注释的对齐不丢失）
            while (valueEnd > valueStart
                    && (tail.charAt(valueEnd - 1) == ' ' || tail.charAt(valueEnd - 1) == '\t')) {
                valueEnd--;
            }
        }
        String newLine = head + tail.substring(0, valueStart) + renderScalar(value) + tail.substring(valueEnd);
        if (!newLine.equals(line)) {
            lines.set(idx, newLine);
            return true;
        }
        return false;
    }

    /** 删除键行，并连带删除紧随其后缩进更深的注释延续行（如旧模板 admin.password 的说明注释）。 */
    private static boolean removeKeyLine(List<String> lines, int idx) {
        int keyIndent = leadingSpaces(lines.get(idx));
        lines.remove(idx);
        while (idx < lines.size()) {
            String line = lines.get(idx);
            if (line.isBlank()) {
                break;
            }
            if (line.trim().startsWith("#") && leadingSpaces(line) > keyIndent) {
                lines.remove(idx);
                continue;
            }
            break;
        }
        return true;
    }

    /**
     * 追加一个新键；若其祖先节缺失则逐级补建（含缩进），保证写出的 YAML 结构合法。
     * 例如父节 plugins 不存在时，写入 plugins.farm=false 会生成：
     * <pre>
     *   plugins:
     *     farm: false
     * </pre>
     * 而不是在文件末尾裸插一行缩进的子键（旧行为会产生无父节的子键，破坏 YAML 解析）。
     */
    private static boolean insertKeyWithParents(List<String> lines, String[] path, Object value) {
        int depth = path.length;
        // 找到最深已存在的祖先节（深度 1..depth-1）；0 表示整条父链都不存在
        int anchorDepth = 0;
        int anchorIdx = -1;
        for (int d = depth - 1; d >= 1; d--) {
            int idx = findKeyLine(lines, Arrays.copyOf(path, d));
            if (idx >= 0) {
                anchorDepth = d;
                anchorIdx = idx;
                break;
            }
        }
        int at;
        if (anchorDepth == 0) {
            at = trailingInsertIndex(lines);
        } else {
            int anchorIndent = leadingSpaces(lines.get(anchorIdx));
            int last = anchorIdx;
            for (int i = anchorIdx + 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.trim().startsWith("#")) {
                    continue;
                }
                if (leadingSpaces(line) > anchorIndent) {
                    last = i;
                } else {
                    break;
                }
            }
            at = last + 1;
        }
        // 逐级补建缺失的祖先节（每层 2 空格缩进），最后写入叶键
        for (int d = anchorDepth + 1; d < depth; d++) {
            lines.add(at++, indentFor(Arrays.copyOf(path, d)) + path[d - 1] + ":");
        }
        lines.add(at, indentFor(path) + path[depth - 1] + ": " + renderScalar(value));
        return true;
    }

    /** 文件末尾插入下标：若以换行结尾，插到末尾空元素之前。 */
    private static int trailingInsertIndex(List<String> lines) {
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            return lines.size() - 1;
        }
        return lines.size();
    }

    /** 新键的缩进：每层 2 空格。 */
    private static String indentFor(String[] path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length - 1; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    /** 将值渲染为 YAML 标量：空串→''；不安全的纯量自动单引号包裹。 */
    private static String renderScalar(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        String s = value.toString();
        if (s.isEmpty()) {
            return "''";
        }
        if (isPlainSafe(s)) {
            return s;
        }
        return "'" + s.replace("'", "''") + "'";
    }

    /** YAML 纯量安全判定：无需引号即可原样写出。 */
    private static boolean isPlainSafe(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c0 = s.charAt(0);
        if (c0 == ' ' || c0 == '\t' || c0 == '\n' || c0 == '\r') {
            return false;
        }
        if ("-?:,[]{}#&*!|>'\"%@`".indexOf(c0) >= 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                return false;
            }
            if (c == '#' && i > 0 && (s.charAt(i - 1) == ' ' || s.charAt(i - 1) == '\t')) {
                return false;
            }
            if (c == ':' && i + 1 < s.length() && (s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\t')) {
                return false;
            }
        }
        return !s.endsWith(" ");
    }
}
