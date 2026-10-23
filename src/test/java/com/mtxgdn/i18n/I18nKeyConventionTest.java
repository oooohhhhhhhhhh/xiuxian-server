package com.mtxgdn.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言包的 key 必须可读、可对齐、可检索。
 *
 * <p><b>缺陷形态</b>：1500 多个 key 里有 750 个是"哈希式"的 ——
 * {@code admin.h00199ba0}、{@code sect.h000a7f98} 这种，后缀是按中文文案算出来的
 * 8 位十六进制。后果很具体：</p>
 *
 * <ul>
 *   <li>看代码不知道这句提示是什么内容，{@code admin.h00199ba0} 与
 *       {@code admin.h00567a14} 在肉眼层面没有区别；</li>
 *   <li>改文案时无法判断影响面 —— 文案一改，哈希与语义就对不上了；</li>
 *   <li>同一句中文在不同语境被迫共用一个 key，没法分别翻译；</li>
 *   <li>中英两个文件只能靠哈希对齐，漏译时没有任何提示。</li>
 * </ul>
 *
 * <p>已全部改成 {@code 域.语义名}（{@code admin.create_failed}、
 * {@code admin.no_admin_backend_permission} 这种）。本测试守住这条约定，
 * 防止有人再用脚本批量生成哈希式 key。</p>
 */
class I18nKeyConventionTest {

    private static final Path ZH = Paths.get("src/main/java/data/mtxgdn/lang/zh_cn.json");
    private static final Path EN = Paths.get("src/main/java/data/mtxgdn/lang/en_us.json");
    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /** 哈希式 key：{@code 域.h0123abcd}。 */
    private static final Pattern HASH_KEY = Pattern.compile("\\.h[0-9a-f]{8}\"");

    /** 只认字面量 key；动态拼接的（如 {@code "prefix." + x}）跳过。 */
    private static final Pattern I18N_CALL = Pattern.compile("I18n\\s*\\.\\s*t\\s*\\(\\s*\"([^\"\\\\]+)\"");

    @Test
    @DisplayName("两个语言文件里不得再有哈希式 key")
    void noHashStyleKeys() throws IOException {
        for (Path path : List.of(ZH, EN)) {
            List<String> offenders = new ArrayList<>();
            for (String key : keysOf(path)) {
                if (HASH_KEY.matcher("\"" + key + "\"").find()) {
                    offenders.add(key);
                }
            }
            assertTrue(offenders.isEmpty(),
                    path.getFileName() + " 里还有哈希式 key（按中文文案算出来的 h+8 位十六进制）："
                            + "\n" + String.join("\n", offenders.stream().limit(20).toList())
                            + "\n这种 key 看不出含义、无法检索，改文案还会让哈希与语义脱节。"
                            + "请改成「域.语义名」，例如 admin.create_failed。");
        }
    }

    @Test
    @DisplayName("中英两个文件的 key 集合必须完全一致（漏译立刻失败）")
    void languagesShareTheSameKeySet() throws IOException {
        Set<String> zh = new TreeSet<>(keysOf(ZH));
        Set<String> en = new TreeSet<>(keysOf(EN));

        Set<String> onlyZh = new TreeSet<>(zh);
        onlyZh.removeAll(en);
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(zh);

        assertTrue(onlyZh.isEmpty(), "这些 key 只有中文没有英文（漏译）：\n"
                + String.join("\n", onlyZh.stream().limit(20).toList()));
        assertTrue(onlyEn.isEmpty(), "这些 key 只有英文没有中文：\n"
                + String.join("\n", onlyEn.stream().limit(20).toList()));
    }

    @Test
    @DisplayName("key 命名规范：只含小写字母/数字/下划线/点，且至少两段")
    void keyNamingConvention() throws IOException {
        Pattern legal = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)+");
        List<String> offenders = new ArrayList<>();
        for (Path path : List.of(ZH, EN)) {
            for (String key : keysOf(path)) {
                if (!legal.matcher(key).matches()) {
                    offenders.add(path.getFileName() + " -> " + key);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些 key 不符合「域.语义名」的命名规范（只允许小写字母/数字/下划线/点）：\n"
                        + String.join("\n", offenders.stream().limit(20).toList()));
    }

    @Test
    @DisplayName("两个文件的 value 都不能为空")
    void valuesAreNotEmpty() throws IOException {
        for (Path path : List.of(ZH, EN)) {
            List<String> empty = new ArrayList<>();
            entriesOf(path).forEach((key, value) -> {
                if (value == null || value.isBlank()) {
                    empty.add(key);
                }
            });
            assertTrue(empty.isEmpty(), path.getFileName() + " 里这些 key 的文案是空的："
                    + "\n" + String.join("\n", empty.stream().limit(20).toList()));
        }
    }

    @Test
    @DisplayName("同一个文件里不能有重复 key（重复会让后一条静默覆盖前一条）")
    void keysAreUniqueWithinFile() throws IOException {
        for (Path path : List.of(ZH, EN)) {
            List<String> all = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                Matcher m = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:").matcher(line);
                if (m.find()) {
                    all.add(m.group(1));
                }
            }
            List<String> dupes = all.stream()
                    .collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                    .entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            assertTrue(dupes.isEmpty(), path.getFileName() + " 里有重复 key：\n"
                    + String.join("\n", dupes));
        }
    }

    @Test
    @DisplayName("代码里 I18n.t(\"字面量\") 引用的 key 必须在两个语言文件里都存在")
    void everyReferencedKeyExists() throws IOException {
        Set<String> zh = new TreeSet<>(keysOf(ZH));
        Set<String> en = new TreeSet<>(keysOf(EN));

        Set<String> referenced = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = I18N_CALL.matcher(code);
                while (m.find()) {
                    referenced.add(m.group(1));
                }
            }
        }
        assertFalse(referenced.isEmpty(), "没有扫到任何 I18n.t 调用，测试等于空转");

        Set<String> missingZh = new TreeSet<>(referenced);
        missingZh.removeAll(zh);
        Set<String> missingEn = new TreeSet<>(referenced);
        missingEn.removeAll(en);

        assertTrue(missingZh.isEmpty(), "这些 key 代码里在用，但 zh_cn.json 里没有：\n"
                + String.join("\n", missingZh.stream().limit(20).toList()));
        assertTrue(missingEn.isEmpty(), "这些 key 代码里在用，但 en_us.json 里没有：\n"
                + String.join("\n", missingEn.stream().limit(20).toList()));
    }

    // ==================== 夹具 ====================

    private static Map<String, String> entriesOf(Path path) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            Matcher m = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:\\s*\"(.*)\"\\s*,?\\s*$").matcher(line);
            if (m.find()) {
                map.put(m.group(1), m.group(2));
            }
        }
        return map;
    }

    private static Set<String> keysOf(Path path) throws IOException {
        return new LinkedHashSet<>(entriesOf(path).keySet());
    }

    /** 去掉注释：注释里的 key 示例不算引用，注释里的关键字也不能当断言依据。 */
    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
