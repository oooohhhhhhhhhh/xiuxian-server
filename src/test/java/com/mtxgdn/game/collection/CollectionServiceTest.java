package com.mtxgdn.game.collection;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceLookup;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.PlayerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图鉴服务核心行为的回归测试。
 *
 * <p>用「临时配置文件 + 独立 CollectionService 实例」来测，不依赖仓库里那份
 * {@code config/collection.yml} 的内容 —— 运维改奖励不该让测试变红。</p>
 */
class CollectionServiceTest extends TestBase {

    private static final String USER_PREFIX = "collection_service_user_";
    private static final String SWORD = "mtxgdn:spirit_sword";
    private static final String PILL = "mtxgdn:healing_pill";

    private final ServiceLookup container = ServiceRegistry.container();
    private final PlayerService playerService = container.getPlayerService();

    private PlayerInfo player;
    private long playerId;

    @BeforeAll
    static void registerItems() {
        com.mtxgdn.game.item.ItemScanner.scanAndRegister();
    }

    @BeforeEach
    void setUp() throws Exception {
        cleanup();
        long userId = insertUser();
        player = playerService.createPlayer(userId, "图鉴测试者" + System.nanoTime());
        assertNotNull(player);
        playerId = player.getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
    }

    @Test
    @DisplayName("解锁一条：发一次奖励；重复解锁不重复发奖")
    void unlockGrantsRewardOnce() throws Exception {
        CollectionService service = serviceWith("""
                categories:
                  item: true
                rewards:
                  item:
                    default: { gold: 123 }
                """);
        long goldBefore = playerService.getPlayerById(playerId).getGold();

        CollectionService.UnlockResult first = service.unlock(CollectionCategory.ITEM, playerId, SWORD);
        assertTrue(first.unlocked(), "首次解锁应成功");
        assertTrue(service.isUnlocked(CollectionCategory.ITEM, playerId, SWORD));
        assertEquals(goldBefore + 123, playerService.getPlayerById(playerId).getGold(),
                "解锁应发放配置的 123 金币");

        CollectionService.UnlockResult second = service.unlock(CollectionCategory.ITEM, playerId, SWORD);
        assertFalse(second.unlocked(), "重复解锁应返回没发生");
        assertEquals(goldBefore + 123, playerService.getPlayerById(playerId).getGold(),
                "重复解锁不得重复发奖");
    }

    @Test
    @DisplayName("单条覆盖优先于分类默认（金币 + 灵石同时发）")
    void entryOverrideWins() throws Exception {
        CollectionService service = serviceWith("""
                rewards:
                  item:
                    default: { gold: 0 }
                    entries:
                      mtxgdn:healing_pill: { gold: 7, spirit_stones: 2 }
                """);
        long goldBefore = playerService.getPlayerById(playerId).getGold();
        long stonesBefore = container.getItemService().getSpiritStoneCount(playerId);

        assertTrue(service.unlock(CollectionCategory.ITEM, playerId, PILL).unlocked());
        assertEquals(goldBefore + 7, playerService.getPlayerById(playerId).getGold());
        assertEquals(stonesBefore + 2, container.getItemService().getSpiritStoneCount(playerId),
                "灵石奖励应记入背包");
    }

    @Test
    @DisplayName("分类被配置关闭后不再记录、不再发奖")
    void disabledCategoryIsNoOp() throws Exception {
        CollectionService service = serviceWith("""
                categories:
                  item: false
                """);
        long goldBefore = playerService.getPlayerById(playerId).getGold();

        CollectionService.UnlockResult result = service.unlock(CollectionCategory.ITEM, playerId, SWORD);
        assertFalse(result.unlocked());
        assertFalse(service.isUnlocked(CollectionCategory.ITEM, playerId, SWORD));
        assertEquals(goldBefore, playerService.getPlayerById(playerId).getGold());
    }

    @Test
    @DisplayName("总览与分页详情：全集来自注册表，已解锁状态正确")
    void overviewAndDetail() throws Exception {
        CollectionService service = serviceWith("""
                rewards:
                  item:
                    default: { gold: 0 }
                """);
        service.unlock(CollectionCategory.ITEM, playerId, SWORD);
        service.unlock(CollectionCategory.ITEM, playerId, PILL);

        List<CollectionService.CategoryProgress> overview = service.overview(playerId);
        CollectionService.CategoryProgress itemProgress = overview.stream()
                .filter(p -> p.category() == CollectionCategory.ITEM)
                .findFirst().orElseThrow();
        assertTrue(itemProgress.total() > 20, "物品全集应远多于 20 条");
        assertEquals(2, itemProgress.unlocked());
        assertFalse(itemProgress.completed());

        CollectionService.CategoryDetail page1 = service.detail(playerId, CollectionCategory.ITEM, 1, 20);
        assertEquals(20, page1.entries().size());
        assertTrue(page1.totalPages() >= 2);
        // 已解锁条目按 key 排序后可能不在第一页，逐页找一遍
        boolean foundUnlocked = false;
        boolean foundSword = false;
        for (int page = 1; page <= page1.totalPages(); page++) {
            CollectionService.CategoryDetail detail =
                    service.detail(playerId, CollectionCategory.ITEM, page, 20);
            for (CollectionService.EntryView entry : detail.entries()) {
                if (entry.unlocked()) {
                    foundUnlocked = true;
                    if (SWORD.equals(entry.key())) {
                        foundSword = true;
                    }
                }
            }
        }
        assertTrue(foundUnlocked, "至少应有一个已解锁条目出现在条目列表里");
        assertTrue(foundSword, "灵剑应在物品图鉴里以已解锁状态出现");

        CollectionService.CategoryDetail page2 = service.detail(playerId, CollectionCategory.ITEM, 2, 20);
        assertEquals(2, page2.page());
        assertNotEquals(page1.entries().get(0).key(), page2.entries().get(0).key());
    }

    @Test
    @DisplayName("集齐一个分类：发一次集齐奖励，之后不再发")
    void completionRewardGrantedOnce() throws Exception {
        CollectionService service = serviceWith("""
                rewards:
                  title:
                    default: { gold: 0 }
                    completion: { gold: 777 }
                """);
        long goldBefore = playerService.getPlayerById(playerId).getGold();
        List<CollectionService.Entry> titles = service.universe(CollectionCategory.TITLE);
        assertFalse(titles.isEmpty(), "称号全集不该为空");

        boolean completedSeen = false;
        for (int i = 0; i < titles.size(); i++) {
            CollectionService.UnlockResult result = service.unlock(
                    CollectionCategory.TITLE, playerId, titles.get(i).key());
            if (i == titles.size() - 1) {
                assertTrue(result.categoryCompleted(), "最后一条解锁应触发集齐");
                completedSeen = true;
                assertEquals(777, result.reward().gold(), "最后一次应带上集齐奖励");
            }
        }
        assertTrue(completedSeen);
        assertEquals(goldBefore + 777, playerService.getPlayerById(playerId).getGold(),
                "集齐奖励只发一次（每条默认 0 金币）");

        // 再解锁一次已解锁条目，不得再发集齐奖励
        service.unlock(CollectionCategory.TITLE, playerId, titles.get(0).key());
        assertEquals(goldBefore + 777, playerService.getPlayerById(playerId).getGold());
    }

    @Test
    @DisplayName("回填：直接写进背包的历史物品也能补进图鉴")
    void backfillPicksUpExistingItems() throws Exception {
        String legacyItem = "mtxgdn:spirit_grass";
        // 绕开 ItemService（避免触发实时 hook），模拟"功能上线前就已经在背包里"
        DatabaseManager.insertRow("players_items", Map.of(
                "player_id", playerId,
                "item_key", legacyItem,
                "quantity", 3));
        CollectionService service = container.getCollectionService();
        assertFalse(service.isUnlocked(CollectionCategory.ITEM, playerId, legacyItem));

        service.backfill(playerId);
        assertTrue(service.isUnlocked(CollectionCategory.ITEM, playerId, legacyItem),
                "背包里已有的物品应在回填时解锁");
    }

    @Test
    @DisplayName("已解锁集合查询包含实时解锁的条目")
    void unlockedKeySet() throws Exception {
        CollectionService service = serviceWith("""
                rewards:
                  item:
                    default: { gold: 0 }
                """);
        service.unlock(CollectionCategory.ITEM, playerId, SWORD);
        Set<String> keys = service.unlockedKeySet(CollectionCategory.ITEM, playerId);
        assertTrue(keys.contains(SWORD));
        assertFalse(keys.contains(CollectionDAO_COMPLETION));
    }

    private static final String CollectionDAO_COMPLETION = com.mtxgdn.db.CollectionDAO.COMPLETION_KEY;

    // ==================== 夹具 ====================

    private static CollectionService serviceWith(String yaml) throws Exception {
        Path file = Files.createTempFile("collection-test-", ".yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        CollectionConfig config = CollectionConfig.forTest(file);
        return new CollectionService(ServiceRegistry.container(), config);
    }

    private long insertUser() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (username, password) VALUES (?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, USER_PREFIX + System.nanoTime());
            ps.setString(2, "x");
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    private void cleanup() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM players WHERE user_id IN (SELECT id FROM users WHERE username LIKE ?)")) {
            ps.setString(1, USER_PREFIX + "%");
            ps.executeUpdate();
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE username LIKE ?")) {
            ps.setString(1, USER_PREFIX + "%");
            ps.executeUpdate();
        }
    }

    private static void assertNotEquals(Object a, Object b) {
        assertFalse(java.util.Objects.equals(a, b), "两个值不应相等: " + a);
    }
}
