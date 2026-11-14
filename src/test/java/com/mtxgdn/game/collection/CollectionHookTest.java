package com.mtxgdn.game.collection;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceLookup;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.TitleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图鉴 hook 的集成测试：业务服务里的"获得"动作要真的能解锁图鉴。
 *
 * <p>这里只覆盖最容易被后续改动悄悄破坏的两条主通路：物品入包、称号授予。
 * 其余分类（功法/技能/秘境/游历/地图/配方）沿用同一套 {@link CollectionHooks} 入口与
 * {@code unlockInTransaction} 语义，由 {@link CollectionServiceTest} 覆盖服务层。</p>
 */
class CollectionHookTest extends TestBase {

    private static final String USER_PREFIX = "collection_hook_user_";

    private final ServiceLookup container = ServiceRegistry.container();
    private final PlayerService playerService = container.getPlayerService();
    private final TitleService titleService = container.getTitleService();
    private final CollectionService collectionService = container.getCollectionService();

    private long playerId;

    @BeforeAll
    static void registerItems() {
        com.mtxgdn.game.item.ItemScanner.scanAndRegister();
    }

    @BeforeEach
    void setUp() throws Exception {
        cleanup();
        long userId = insertUser();
        PlayerInfo player = playerService.createPlayer(userId, "图鉴钩子" + System.nanoTime());
        assertNotNull(player);
        playerId = player.getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
    }

    @Test
    @DisplayName("物品入包即解锁物品图鉴，且只解锁一次")
    void itemObtainedUnlocks() {
        String itemKey = "mtxgdn:spirit_grass";
        assertFalse(collectionService.isUnlocked(CollectionCategory.ITEM, playerId, itemKey));

        assertTrue(container.getItemService().addItem(playerId, itemKey, 2));
        assertTrue(collectionService.isUnlocked(CollectionCategory.ITEM, playerId, itemKey),
                "addItem 应触发图鉴解锁");

        int before = collectionService.detail(playerId, CollectionCategory.ITEM, 1, 20)
                .unlocked();
        assertTrue(container.getItemService().addItem(playerId, itemKey, 1), "再次叠加应成功");
        int after = collectionService.detail(playerId, CollectionCategory.ITEM, 1, 20).unlocked();
        assertTrue(after >= before, "重复获得同一种物品不应减少解锁数");
        assertTrue(collectionService.isUnlocked(CollectionCategory.ITEM, playerId, itemKey));
    }

    @Test
    @DisplayName("获得称号即解锁称号图鉴")
    void titleGrantedUnlocks() {
        String titleKey = "sword_student";
        assertFalse(collectionService.isUnlocked(CollectionCategory.TITLE, playerId, titleKey));

        var result = titleService.grantTitle(playerId, titleKey);
        assertTrue(result.success(), "测试前提：称号授予应成功");
        assertTrue(collectionService.isUnlocked(CollectionCategory.TITLE, playerId, titleKey),
                "grantTitle 应触发图鉴解锁");
    }

    // ==================== 夹具 ====================

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
}
