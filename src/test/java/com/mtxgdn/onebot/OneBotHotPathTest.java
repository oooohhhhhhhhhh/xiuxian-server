package com.mtxgdn.onebot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每条消息的热路径上不能重复做重活。
 *
 * <p><b>缺陷形态</b>（P1-21）：OneBot 通道的每条消息（包括根本不构成指令的闲聊）
 * 都要走这几步：</p>
 * <ol>
 *   <li>{@code bindingService.findByQq(...)} —— 一次数据库查询；</li>
 *   <li>{@code processOfflineRewards(userId)} —— 一次完整的离线结算（读玩家 + 写回状态）；</li>
 *   <li>{@code blacklistService.isBlacklisted(...)} —— 重新打开并解析 {@code blacklist.yml}，
 *       且循环里对每条 userId 记录都 {@code new QqBindingService()} 再查一次库；</li>
 *   <li>{@code groupConfigService.isAutoMuteEnabled(...)} —— 再解析一遍群配置 YAML。</li>
 * </ol>
 * <p>一个热闹的群里，这等于每说一句话就跑一次「两次磁盘 IO + 一次 YAML 解析 + 一次离线结算写库」。</p>
 *
 * <p>修复：离线奖励按 QQ 做冷却（冷却内连绑定都不查）；两个 YAML 服务用文件指纹缓存解析结果，
 * 写入时主动失效；黑名单的绑定查询从循环里提到循环外。</p>
 */
class OneBotHotPathTest {

    @Test
    @DisplayName("消息入口不再逐条结算离线奖励，而是走节流方法")
    void messageHandlersUseThrottledOfflineReward() throws IOException {
        String code = stripComments(read("src/main/java/com/mtxgdn/onebot/OneBotWebSocketServer.java"));

        // 私聊与群聊两个入口都必须走节流
        int privateIdx = code.indexOf("private void handlePrivateMessage");
        int groupIdx = code.indexOf("private void handleGroupMessage");
        int dispatchIdx = code.indexOf("private void dispatchCommand");
        assertTrue(privateIdx > 0 && groupIdx > privateIdx && dispatchIdx > groupIdx,
                "OneBotWebSocketServer 的结构变了，请更新本测试");

        String privateBody = code.substring(privateIdx, groupIdx);
        String groupBody = code.substring(groupIdx, dispatchIdx);

        assertTrue(privateBody.contains("settleOfflineRewardIfDue"),
                "私聊入口必须走节流的离线奖励结算");
        assertTrue(groupBody.contains("settleOfflineRewardIfDue"),
                "群聊入口必须走节流的离线奖励结算");
        assertFalse(privateBody.contains("processOfflineReward("),
                "私聊入口不得直接调用 processOfflineReward（会绕过冷却）");
        assertFalse(groupBody.contains("processOfflineReward("),
                "群聊入口不得直接调用 processOfflineReward（会绕过冷却）");
        assertFalse(groupBody.contains("bindingService.findByQq"),
                "群聊入口不得在每条消息上都查绑定（节流方法内部会按需查）");
    }

    @Test
    @DisplayName("节流方法必须先查冷却、再查绑定")
    void throttleChecksCooldownBeforeQueryingBinding() throws IOException {
        String code = stripComments(read("src/main/java/com/mtxgdn/onebot/OneBotWebSocketServer.java"));
        int start = code.indexOf("private void settleOfflineRewardIfDue");
        assertTrue(start > 0, "找不到 settleOfflineRewardIfDue");
        int end = code.indexOf("private void processOfflineReward", start);
        assertTrue(end > start, "找不到 settleOfflineRewardIfDue 的结尾");
        String body = code.substring(start, end);

        int cooldownIdx = body.indexOf("OFFLINE_REWARD_COOLDOWN_MS");
        int bindingIdx = body.indexOf("findByQq");
        assertTrue(cooldownIdx > 0, "节流方法必须使用 OFFLINE_REWARD_COOLDOWN_MS");
        assertTrue(bindingIdx > cooldownIdx,
                "必须先判断冷却、再查绑定 —— 否则省下的那次查询又还回去了");
    }

    @Test
    @DisplayName("黑名单判定最多只查一次绑定")
    void blacklistResolvesBindingOnce() throws IOException {
        String code = stripComments(read("src/main/java/com/mtxgdn/onebot/BlacklistService.java"));
        int start = code.indexOf("public boolean isBlacklisted(");
        assertTrue(start > 0, "找不到 isBlacklisted");
        int end = code.indexOf("public List<String> resolveQqNumbers", start);
        assertTrue(end > start);
        String body = code.substring(start, end);

        int occurrences = body.split("new QqBindingService\\(\\)", -1).length - 1;
        assertTrue(occurrences == 1,
                "isBlacklisted 里 new QqBindingService() 只应出现一次（实际 " + occurrences
                        + " 次）—— 原来写在循环里，每条 userId 模式的记录都会查一次库：\n" + body);
        assertTrue(body.contains("bindingResolved"),
                "绑定查询必须用 bindingResolved 之类的守卫保证只执行一次"
                        + "（同时保留「没有 userId 记录时根本不查库」这个优化）：\n" + body);
    }

    @Test
    @DisplayName("两个 YAML 服务都用文件指纹缓存，且写入时主动失效")
    void yamlServicesCacheParsedResult() throws IOException {
        for (String file : new String[]{
                "src/main/java/com/mtxgdn/onebot/BlacklistService.java",
                "src/main/java/com/mtxgdn/onebot/OneBotGroupConfigService.java"}) {
            String code = stripComments(read(file));
            assertTrue(code.contains("cachedStamp"),
                    file + " 必须用文件指纹缓存解析结果，否则每条消息都要重新解析 YAML");
            assertTrue(code.contains("getLastModifiedTime"),
                    file + " 的指纹应包含文件修改时间（手工改配置能立刻生效）");
            int saveIdx = code.indexOf("private void saveRawList");
            assertTrue(saveIdx > 0, file + " 找不到 saveRawList");
            String saveBody = code.substring(saveIdx, Math.min(code.length(), saveIdx + 900));
            assertTrue(saveBody.contains("cachedList = null"),
                    file + " 的 saveRawList 必须主动让缓存失效"
                            + "（同一毫秒内同长度的两次写入指纹相同）");
        }
    }

    @Test
    @DisplayName("缓存确实生效：同一份文件第二次读取不再重新解析")
    void cacheAvoidsReparse(@TempDir Path tempDir) throws Exception {
        Path yml = tempDir.resolve("blacklist.yml");
        Files.writeString(yml, "entries:\n  - qqNumber: '10001'\n    reason: test\n",
                StandardCharsets.UTF_8);

        BlacklistService service = new BlacklistService();
        setField(service, "configPath", yml);

        // 第一次：解析并写入缓存
        assertTrue(service.isBlacklisted("10001"), "命中黑名单");
        assertFalse(service.isBlacklisted("10002"), "未命中黑名单");

        Object cached = getField(service, "cachedList");
        assertNotNull(cached, "第一次读取后应该有缓存");
        long stamp = (Long) getField(service, "cachedStamp");

        // 缓存对象必须是同一个实例（说明第二次没有再解析）
        assertFalse(service.isBlacklisted("10002"), "文件没变，结论也不该变");
        assertTrue(getField(service, "cachedList") == cached, "第二次读取应命中缓存");
        assertTrue((Long) getField(service, "cachedStamp") == stamp, "文件没变，指纹不该变");

        // 外部改文件 -> 指纹变化 -> 重新解析，新条目立刻生效
        Files.writeString(yml, "entries:\n  - qqNumber: '10001'\n    reason: test\n"
                + "  - qqNumber: '10002'\n    reason: added\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(yml, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 2000));
        assertTrue(service.isBlacklisted("10002"), "手工编辑配置文件后必须立刻生效，不能读到旧缓存");
    }

    // ==================== 夹具 ====================

    private static String read(String relative) throws IOException {
        Path path = Paths.get(relative);
        assertTrue(Files.exists(path), "找不到文件: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
