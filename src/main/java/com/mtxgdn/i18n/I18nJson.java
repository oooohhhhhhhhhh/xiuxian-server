package com.mtxgdn.i18n;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语言包 JSON 的读写。包级私有，仅供 {@link I18nManager} 使用。
 *
 * <p>词条表是扁平的 {@code key → value}，与既有 {@code data/mtxgdn/lang/zh_cn.json}
 * 结构一致，便于复用存量文件。</p>
 */
final class I18nJson {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()   // 中文与全角标点原样输出，不转成反斜杠 u 转义
            .setPrettyPrinting()     // 导出的文件人眼可读，便于运维手改
            .create();

    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();

    private I18nJson() {
    }

    static Map<String, String> read(Reader reader) throws JsonSyntaxException {
        Map<String, String> parsed = GSON.fromJson(reader, MAP_TYPE);
        return parsed != null ? new LinkedHashMap<>(parsed) : Collections.emptyMap();
    }

    static String write(Map<String, String> entries) {
        // 代入一个 TreeMap 让导出结果按 key 排序，方便 diff
        Map<String, String> sorted = new java.util.TreeMap<>(entries);
        return GSON.toJson(sorted, MAP_TYPE);
    }

    static String charsetName() {
        return StandardCharsets.UTF_8.name();
    }
}
