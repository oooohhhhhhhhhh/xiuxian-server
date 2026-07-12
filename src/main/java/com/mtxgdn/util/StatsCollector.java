package com.mtxgdn.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息和指令调用统计收集器（内存）。
 * 线程安全，重启后清零。
 */
public class StatsCollector {

    private static final StatsCollector INSTANCE = new StatsCollector();

    // 消息统计
    private final ConcurrentHashMap<String, AtomicLong> messageByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> messageByGroup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> messageByHour = new ConcurrentHashMap<>();
    private final AtomicLong totalMessages = new AtomicLong(0);

    // 指令调用统计
    private final ConcurrentHashMap<String, AtomicLong> commandByCmd = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> commandByGroup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> commandByHour = new ConcurrentHashMap<>();
    private final AtomicLong totalCommands = new AtomicLong(0);

    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00").withZone(ZoneId.of("Asia/Shanghai"));

    public static StatsCollector getInstance() {
        return INSTANCE;
    }

    /* ---------- 记录 ---------- */

    public void recordMessage(String senderQq, Long groupId) {
        totalMessages.incrementAndGet();
        messageByUser.computeIfAbsent(senderQq, k -> new AtomicLong()).incrementAndGet();
        if (groupId != null) {
            messageByGroup.computeIfAbsent(groupId, k -> new AtomicLong()).incrementAndGet();
        }
        messageByHour.computeIfAbsent(hourKey(), k -> new AtomicLong()).incrementAndGet();
    }

    public void recordCommand(String command, Long groupId) {
        totalCommands.incrementAndGet();
        commandByCmd.computeIfAbsent(command, k -> new AtomicLong()).incrementAndGet();
        if (groupId != null) {
            commandByGroup.computeIfAbsent(groupId, k -> new AtomicLong()).incrementAndGet();
        }
        commandByHour.computeIfAbsent(hourKey(), k -> new AtomicLong()).incrementAndGet();
    }

    /* ---------- 查询：消息 ---------- */

    public JsonObject getMessageStats() {
        JsonObject result = new JsonObject();

        // 总计
        result.addProperty("total", totalMessages.get());

        // 按用户 top 20
        JsonArray byUser = new JsonArray();
        messageByUser.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(20)
                .forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("key", e.getKey());
                    o.addProperty("count", e.getValue().get());
                    byUser.add(o);
                });
        result.add("byUser", byUser);

        // 按群 top 20
        JsonArray byGroup = new JsonArray();
        messageByGroup.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(20)
                .forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("key", String.valueOf(e.getKey()));
                    o.addProperty("count", e.getValue().get());
                    byGroup.add(o);
                });
        result.add("byGroup", byGroup);

        // 按时间（最近24小时，每小时）
        JsonArray byTime = buildHourlyArray(messageByHour, 24);
        result.add("byTime", byTime);

        return result;
    }

    /* ---------- 查询：指令 ---------- */

    public JsonObject getCommandStats() {
        JsonObject result = new JsonObject();

        // 总计
        result.addProperty("total", totalCommands.get());

        // 按群 top 20
        JsonArray byGroup = new JsonArray();
        commandByGroup.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(20)
                .forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("key", String.valueOf(e.getKey()));
                    o.addProperty("count", e.getValue().get());
                    byGroup.add(o);
                });
        result.add("byGroup", byGroup);

        // 按时间（最近24小时，每小时）
        JsonArray byTime = buildHourlyArray(commandByHour, 24);
        result.add("byTime", byTime);

        // 按指令种类 top 30
        JsonArray byCmd = new JsonArray();
        commandByCmd.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(30)
                .forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("key", e.getKey());
                    o.addProperty("count", e.getValue().get());
                    byCmd.add(o);
                });
        result.add("byCommand", byCmd);

        return result;
    }

    /* ---------- 辅助 ---------- */

    private String hourKey() {
        return HOUR_FMT.format(Instant.now());
    }

    /**
     * 返回最近 N 个小时的按小时统计数组（无数据的填 0）。
     */
    private JsonArray buildHourlyArray(ConcurrentHashMap<String, AtomicLong> map, int hours) {
        List<String> slots = new ArrayList<>();
        long nowHourMillis = Instant.now().toEpochMilli() / 3600000 * 3600000;
        for (int i = hours - 1; i >= 0; i--) {
            long ms = nowHourMillis - i * 3600000L;
            slots.add(HOUR_FMT.format(Instant.ofEpochMilli(ms)));
        }
        JsonArray arr = new JsonArray();
        for (String slot : slots) {
            AtomicLong count = map.get(slot);
            JsonObject o = new JsonObject();
            o.addProperty("hour", slot);
            o.addProperty("count", count != null ? count.get() : 0);
            arr.add(o);
        }
        return arr;
    }
}
