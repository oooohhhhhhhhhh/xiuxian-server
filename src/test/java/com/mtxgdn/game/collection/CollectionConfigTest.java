package com.mtxgdn.game.collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图鉴奖励配置解析的回归测试。
 *
 * <p>重点：嵌套结构能读对、缺配置/坏配置会回落到代码默认、显式 {@code {}} 与"没配"要能区分。</p>
 */
class CollectionConfigTest {

    private CollectionConfig configWith(String yaml) throws Exception {
        Path file = Files.createTempFile("collection-config-", ".yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return CollectionConfig.forTest(file);
    }

    @Test
    @DisplayName("分类开关与逐级回落：单条 > 分类默认 > 全局默认 > 代码默认")
    void rewardFallbackChain() throws Exception {
        CollectionConfig config = configWith("""
                categories:
                  item: false
                default_reward: { gold: 11 }
                rewards:
                  title:
                    default: { gold: 22 }
                    entries:
                      arena_champion: { gold: 33, spirit_stones: 3 }
                """);

        assertFalse(config.isCategoryEnabled(CollectionCategory.ITEM), "categories.item: false 应生效");
        assertTrue(config.isCategoryEnabled(CollectionCategory.TITLE), "没写的分类默认启用");

        CollectionReward entry = config.rewardFor(CollectionCategory.TITLE, "arena_champion");
        assertEquals(33, entry.gold());
        assertEquals(3, entry.spiritStones());

        CollectionReward categoryDefault = config.rewardFor(CollectionCategory.TITLE, "other_title");
        assertEquals(22, categoryDefault.gold(), "分类默认应优先于全局默认");

        CollectionReward globalDefault = config.rewardFor(CollectionCategory.SKILL, "anything");
        assertEquals(11, globalDefault.gold(), "没配分类时用全局默认");

        CollectionReward codeDefault = configWith("""
                rewards:
                  item:
                    default: {}
                """).rewardFor(CollectionCategory.TECHNIQUE, "x");
        assertEquals(CollectionConfig.codeDefaultReward(CollectionCategory.TECHNIQUE).gold(),
                codeDefault.gold(), "完全没配时回落代码默认");
    }

    @Test
    @DisplayName("显式空奖励 {} 表示这条不发奖，不会回落到分类默认")
    void explicitEmptyRewardDoesNotFallBack() throws Exception {
        CollectionConfig config = configWith("""
                rewards:
                  item:
                    default: { gold: 100 }
                    entries:
                      mtxgdn:spirit_sword: {}
                """);
        CollectionReward reward = config.rewardFor(CollectionCategory.ITEM, "mtxgdn:spirit_sword");
        assertTrue(reward.isEmpty(), "显式 {} 应解析为空奖励");
        assertEquals(100, config.rewardFor(CollectionCategory.ITEM, "mtxgdn:jade_armor").gold(),
                "其他条目仍用分类默认");
    }

    @Test
    @DisplayName("集齐奖励可覆盖；没配时用代码默认")
    void completionReward() throws Exception {
        CollectionConfig config = configWith("""
                rewards:
                  title:
                    completion: { gold: 500, spirit_stones: 5, items: { "mtxgdn:healing_pill": 2 } }
                """);
        CollectionReward completion = config.completionReward(CollectionCategory.TITLE);
        assertEquals(500, completion.gold());
        assertEquals(5, completion.spiritStones());
        assertEquals(2L, completion.items().get("mtxgdn:healing_pill"));

        CollectionConfig fallback = configWith("rewards: {}");
        assertEquals(CollectionConfig.codeCompletionReward(CollectionCategory.SKILL),
                fallback.completionReward(CollectionCategory.SKILL));
    }

    @Test
    @DisplayName("文件缺失 / 内容坏掉：不抛异常，回落代码默认")
    void brokenConfigFallsBack() throws Exception {
        CollectionConfig missing = CollectionConfig.forTest(
                Path.of("target", "not-exist-collection-" + System.nanoTime() + ".yml"));
        assertEquals(CollectionConfig.codeDefaultReward(CollectionCategory.ITEM),
                missing.rewardFor(CollectionCategory.ITEM, "x"));
        assertTrue(missing.isCategoryEnabled(CollectionCategory.ITEM));

        CollectionConfig broken = configWith("categories: [not, a, map]\nrewards: 42\n");
        assertEquals(CollectionConfig.codeDefaultReward(CollectionCategory.ITEM),
                broken.rewardFor(CollectionCategory.ITEM, "x"),
                "坏配置必须回落到默认而不是让图鉴崩掉");
    }

    @Test
    @DisplayName("负数与非法数量被忽略/归零，避免配置写错倒扣玩家")
    void negativesAreClamped() throws Exception {
        CollectionConfig config = configWith("""
                rewards:
                  item:
                    entries:
                      mtxgdn:spirit_sword: { gold: -100, spirit_stones: 5, items: { "mtxgdn:healing_pill": -2 } }
                """);
        CollectionReward reward = config.rewardFor(CollectionCategory.ITEM, "mtxgdn:spirit_sword");
        assertEquals(0, reward.gold());
        assertEquals(5, reward.spiritStones());
        assertTrue(reward.items().isEmpty(), "数量 <= 0 的物品奖励应被丢弃");
    }

    @Test
    @DisplayName("奖励描述文本便于指令展示")
    void describe() {
        CollectionReward reward = new CollectionReward(100, 5, 0,
                Map.of("mtxgdn:healing_pill", 2L));
        String text = reward.describe();
        assertTrue(text.contains("金币 +100"), text);
        assertTrue(text.contains("灵石 +5"), text);
        assertTrue(text.contains("mtxgdn:healing_pill ×2"), text);
        assertEquals("无", CollectionReward.EMPTY.describe());
    }
}
