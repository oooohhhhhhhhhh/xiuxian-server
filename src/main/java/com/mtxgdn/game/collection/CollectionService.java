package com.mtxgdn.game.collection;

import com.mtxgdn.common.service.ServiceLookup;
import com.mtxgdn.db.CollectionDAO;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.config.TechniqueDefaults;
import com.mtxgdn.game.entity.InventoryEntry;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.entity.Recipe;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.entity.Technique;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.explorationevent.ExplorationEventRegistry;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.game.secretrealm.SecretRealmRegistry;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.title.TitleRegistry;
import com.mtxgdn.plugin.BuiltInPluginRegistry;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全图鉴：把"玩家收集到的东西"逐条记录下来，集齐分类再发一次大奖。
 *
 * <h2>解锁时机</h2>
 * 每个分类的解锁点各不相同（物品是拿到、称号是获得、功法/技能是学会、
 * 秘境是进入、游历事件是遇到、地图地点是首次到达、配方是首次炼成），
 * 由 {@link CollectionHooks} 在各业务服务里各调一刀。图鉴不自己扫描玩家状态去猜，
 * 因为"曾经拥有过"没法从当前背包反推。
 *
 * <h2>奖励</h2>
 * 奖励来自 {@link CollectionConfig}（{@code config/collection.yml}），条目级 &gt;
 * 分类级 &gt; 全局级 &gt; 代码内置默认，逐级回落。发奖与写解锁记录在同一个事务里，
 * 用 {@code player_collection} 的 {@code UNIQUE (player_id, category, entry_key)}
 * 保证"一条只发一次"。
 *
 * <h2>兼容老玩家</h2>
 * 指令/REST 首次访问时会 {@link #backfill(long)} 一次：从当前背包、装备、已学功法/技能、
 * 已有称号、已探索地图反推能反推的部分；秘境/游历事件/配方没有历史台账，只能从本功能上线后开始记。
 */
public class CollectionService {

    private static final GameLogger LOG = GameLogger.getLogger(CollectionService.class);
    private static final CollectionDAO DAO = new CollectionDAO();

    /**
     * 发奖时会调用 {@code addItem(conn, ...)}，而它又会触发物品解锁 hook ——
     * 不加标记就会在"发奖物品正好是新条目"时递归。用 ThreadLocal 挡住重入，
     * 该奖励物品会在下一次获得时正常解锁。
     */
    private static final ThreadLocal<Boolean> IN_UNLOCK = ThreadLocal.withInitial(() -> false);

    private final ServiceLookup services;
    private final CollectionConfig config;

    /** 全集缓存：注册表/数据库的一次性读取结果。 */
    private final Map<CollectionCategory, List<Entry>> universeCache = new EnumMap<>(CollectionCategory.class);
    /** 已经回填过的玩家（容器实例级别，重启后重新回填一次，幂等）。 */
    private final Set<Long> backfilled = ConcurrentHashMap.newKeySet();

    public CollectionService(ServiceLookup services) {
        this(services, new CollectionConfig());
    }

    /** 测试用：注入自定义配置。 */
    public CollectionService(ServiceLookup services, CollectionConfig config) {
        this.services = services;
        this.config = config;
    }

    public CollectionConfig getConfig() {
        return config;
    }

    /** 图鉴模块是否可用（受 {@code config/plugins/collection.yml} 的 enabled 门控）。 */
    public boolean enabled() {
        try {
            return BuiltInPluginRegistry.isModuleEnabled("collection");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 分类是否记录（模块启用 + 配置未关闭该分类）。 */
    public boolean canTrack(CollectionCategory category) {
        return category != null && enabled() && config.isCategoryEnabled(category);
    }

    // ==================== 解锁 ====================

    /**
     * 解锁一个条目（自开事务）。已解锁、分类关闭、模块停用时返回"没发生"。
     */
    public UnlockResult unlock(CollectionCategory category, long playerId, String entryKey) {
        if (!canTrack(category) || entryKey == null || entryKey.isBlank()) {
            return UnlockResult.none();
        }
        try {
            // 重入保护在 unlockInTransaction 里统一处理（它也是 hook 的直接入口）
            return DatabaseManager.runTransaction(conn -> unlockInTransaction(conn, category, playerId, entryKey));
        } catch (Exception e) {
            LOG.warn("图鉴解锁失败 " + category.getKey() + " / " + playerId + " / " + entryKey + ": " + e.getMessage());
            return UnlockResult.none();
        }
    }

    /**
     * 在调用方已有的事务里解锁（业务内 hook 用，避免嵌套事务）。
     *
     * <p>写解锁行 + 发奖都在同一条连接上，随调用方事务一起提交/回滚。
     * 异常由调用方决定是否吞掉（hook 侧一律吞，不阻断主流程）。</p>
     */
    public UnlockResult unlockInTransaction(Connection conn, CollectionCategory category,
                                            long playerId, String entryKey) throws SQLException {
        if (!canTrack(category) || entryKey == null || entryKey.isBlank() || IN_UNLOCK.get()) {
            return UnlockResult.none();
        }
        IN_UNLOCK.set(true);
        try {
            if (!DAO.insertUnlock(conn, playerId, category.getKey(), entryKey, System.currentTimeMillis())) {
                return UnlockResult.none();
            }
            CollectionReward reward = config.rewardFor(category, entryKey);
            grantReward(conn, playerId, reward);
            CollectionReward completion = grantCompletionIfFinished(conn, category, playerId);
            return new UnlockResult(true, reward.plus(completion), !completion.isEmpty());
        } finally {
            IN_UNLOCK.set(false);
        }
    }

    /** 集齐检查：分类真实条目全部解锁后，发一次集齐奖励（用伪条目防重复）。 */
    private CollectionReward grantCompletionIfFinished(Connection conn, CollectionCategory category,
                                                       long playerId) throws SQLException {
        int total = total(category);
        if (total <= 0) {
            return CollectionReward.EMPTY;
        }
        if (DAO.countUnlocked(conn, playerId, category.getKey()) < total) {
            return CollectionReward.EMPTY;
        }
        if (DAO.exists(conn, playerId, category.getKey(), CollectionDAO.COMPLETION_KEY)) {
            return CollectionReward.EMPTY;
        }
        if (!DAO.insertUnlock(conn, playerId, category.getKey(), CollectionDAO.COMPLETION_KEY,
                System.currentTimeMillis())) {
            return CollectionReward.EMPTY;
        }
        CollectionReward completion = config.completionReward(category);
        grantReward(conn, playerId, completion);
        return completion;
    }

    private void grantReward(Connection conn, long playerId, CollectionReward reward) throws SQLException {
        if (reward == null || reward.isEmpty()) {
            return;
        }
        if (reward.gold() > 0) {
            services.getPlayerService().addGold(conn, playerId, reward.gold());
        }
        if (reward.spiritStones() > 0) {
            services.getItemService().addSpiritStones(conn, playerId, reward.spiritStones());
        }
        if (reward.exp() > 0) {
            services.getPlayerService().addExperience(conn, playerId, reward.exp());
        }
        for (Map.Entry<String, Long> entry : reward.items().entrySet()) {
            String itemKey = entry.getKey();
            long amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }
            if (!ItemRegistry.contains(itemKey)) {
                LOG.warn("图鉴奖励配置了不存在的物品，已跳过: " + itemKey);
                continue;
            }
            try {
                services.getItemService().addItem(conn, playerId, itemKey, amount);
            } catch (SQLException | RuntimeException e) {
                // 背包满等单人问题不该让"解锁记录"回滚：奖励跳过、解锁保留
                LOG.warn("图鉴奖励发放失败（解锁保留）" + itemKey + " ×" + amount + ": " + e.getMessage());
            }
        }
    }

    // ==================== 查询 ====================

    /** 已解锁条目（不含集齐伪条目）。 */
    public Set<String> unlockedKeys(CollectionCategory category, long playerId) {
        try {
            return DAO.unlockedKeys(playerId, category.getKey());
        } catch (RuntimeException e) {
            LOG.error("查询图鉴解锁失败", e);
            return Set.of();
        }
    }

    /** 某条目是否已解锁。 */
    public boolean isUnlocked(CollectionCategory category, long playerId, String entryKey) {
        try {
            return DAO.exists(playerId, category.getKey(), entryKey);
        } catch (RuntimeException e) {
            LOG.error("查询图鉴条目失败", e);
            return false;
        }
    }

    /** 总览：每个启用分类的进度。 */
    public List<CategoryProgress> overview(long playerId) {
        List<CategoryProgress> result = new ArrayList<>();
        for (CollectionCategory category : CollectionCategory.values()) {
            if (!canTrack(category)) {
                continue;
            }
            try {
                int total = total(category);
                CollectionDAO.CategorySnapshot snapshot = DAO.readSnapshot(playerId, category.getKey());
                result.add(new CategoryProgress(category, category.getDisplayName(),
                        snapshot.unlockedCount(), total, snapshot.completed(),
                        config.completionReward(category)));
            } catch (RuntimeException e) {
                LOG.warn("查询图鉴进度失败 " + category.getKey() + ": " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * 某分类的条目列表（带分页）；{@code page} 从 1 开始，{@code pageSize ≤ 0} 用 20。
     */
    public CategoryDetail detail(long playerId, CollectionCategory category, int page, int pageSize) {
        int size = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        int currentPage = Math.max(1, page);
        List<Entry> universe = universe(category);
        Map<String, Long> unlockedAt = Map.of();
        int unlockedCount = 0;
        boolean completed = false;
        try {
            CollectionDAO.CategorySnapshot snapshot = DAO.readSnapshot(playerId, category.getKey());
            unlockedAt = snapshot.unlockedAt();
            unlockedCount = snapshot.unlockedCount();
            completed = snapshot.completed();
        } catch (RuntimeException e) {
            LOG.error("查询图鉴分类失败", e);
        }
        int totalPages = Math.max(1, (universe.size() + size - 1) / size);
        int effectivePage = Math.min(currentPage, totalPages);
        int from = (effectivePage - 1) * size;
        int to = Math.min(from + size, universe.size());
        List<EntryView> entries = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Entry entry = universe.get(i);
            boolean unlocked = unlockedAt.containsKey(entry.key());
            entries.add(new EntryView(entry.key(), entry.name(), unlocked,
                    unlocked ? unlockedAt.getOrDefault(entry.key(), 0L) : 0L));
        }
        return new CategoryDetail(category, category.getDisplayName(), unlockedCount, universe.size(),
                effectivePage, totalPages, completed, config.completionReward(category), entries);
    }

    /** 某分类的全部条目（配置视角，含未解锁）。 */
    public List<Entry> universe(CollectionCategory category) {
        synchronized (universeCache) {
            return universeCache.computeIfAbsent(category, this::loadUniverse);
        }
    }

    /** 跳过缓存重算（测试/热加载注册表用）。 */
    public void invalidateUniverseCache() {
        synchronized (universeCache) {
            universeCache.clear();
        }
    }

    /** 分类条目总数（= 全集大小）。 */
    public int total(CollectionCategory category) {
        return universe(category).size();
    }

    private List<Entry> loadUniverse(CollectionCategory category) {
        List<Entry> entries = new ArrayList<>();
        try {
            switch (category) {
                case ITEM -> {
                    for (Item item : ItemRegistry.getAll()) {
                        entries.add(new Entry(item.getFullKey(), item.getName()));
                    }
                }
                case TITLE -> {
                    TitleRegistry.init();
                    for (Title title : TitleRegistry.getAll()) {
                        entries.add(new Entry(title.getKey(), title.getName()));
                    }
                }
                case TECHNIQUE -> {
                    for (Object[] row : TechniqueDefaults.ROWS) {
                        String name = String.valueOf(row[0]);
                        entries.add(new Entry(name, name));
                    }
                }
                case SKILL -> {
                    for (Skill skill : services.getSkillService().getAllSkills()) {
                        entries.add(new Entry(skill.getName(), skill.getName()));
                    }
                }
                case SECRET_REALM -> {
                    for (SecretRealm realm : SecretRealmRegistry.getAll()) {
                        entries.add(new Entry(realm.getFullKey(), realm.getName()));
                    }
                }
                case EXPLORATION_EVENT -> {
                    for (ExplorationEvent event : ExplorationEventRegistry.getAll()) {
                        entries.add(new Entry(event.getFullKey(), event.getName()));
                    }
                }
                case MAP_LOCATION -> {
                    for (MapLocation location : services.getMapService().getAllLocations()) {
                        entries.add(new Entry(String.valueOf(location.getId()), location.getName()));
                    }
                    entries.sort(Comparator.comparingLong(e -> parseLong(e.key())));
                }
                case RECIPE -> {
                    for (Recipe recipe : services.getCraftingService().getAllRecipes()) {
                        entries.add(new Entry(recipe.getName(), recipe.getName()));
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("加载图鉴全集失败 " + category.getKey() + ": " + e.getMessage());
        }
        entries.sort(Comparator.comparing(Entry::key));
        return entries;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    // ==================== 回填 ====================

    /**
     * 从当前可反推的状态回填解锁记录（每个玩家每个容器实例只跑一次）。
     * 反推不到的历史（秘境/游历事件/配方）不回填，从功能上线后开始记录。
     */
    public void backfill(long playerId) {
        if (!enabled() || playerId <= 0 || !backfilled.add(playerId)) {
            return;
        }
        try {
            for (InventoryEntry entry : services.getItemService().getInventory(playerId)) {
                unlock(CollectionCategory.ITEM, playerId, entry.getItem().getFullKey());
            }
            for (String itemKey : services.getItemService().getEquipment(playerId).values()) {
                unlock(CollectionCategory.ITEM, playerId, itemKey);
            }
            for (Map<String, Object> title : services.getTitleService().getPlayerTitles(playerId)) {
                Object key = title.get("titleKey");
                if (key != null) {
                    unlock(CollectionCategory.TITLE, playerId, String.valueOf(key));
                }
            }
            for (Skill skill : services.getSkillService().getPlayerSkills(playerId)) {
                unlock(CollectionCategory.SKILL, playerId, skill.getName());
            }
            for (Technique technique : services.getTechniqueService().getPlayerTechniques(playerId)) {
                unlock(CollectionCategory.TECHNIQUE, playerId, technique.getName());
            }
            for (Long locationId : services.getMapService().getExploredLocationIds(playerId)) {
                unlock(CollectionCategory.MAP_LOCATION, playerId, String.valueOf(locationId));
            }
        } catch (RuntimeException e) {
            LOG.warn("图鉴回填失败 player=" + playerId + ": " + e.getMessage());
        }
    }

    // ==================== 数据载体 ====================

    /** 全集里的一条（未解锁也用）。 */
    public record Entry(String key, String name) {
    }

    /** 分类进度。 */
    public record CategoryProgress(CollectionCategory category, String displayName,
                                   int unlocked, int total, boolean completed,
                                   CollectionReward completionReward) {

        public int percent() {
            return total <= 0 ? 0 : (int) Math.floor(unlocked * 100.0 / total);
        }
    }

    /** 分页后的分类详情。 */
    public record CategoryDetail(CollectionCategory category, String displayName,
                                 int unlocked, int total, int page, int totalPages,
                                 boolean completed, CollectionReward completionReward,
                                 List<EntryView> entries) {
    }

    /** 条目视图（含解锁状态）。 */
    public record EntryView(String key, String name, boolean unlocked, long unlockedAt) {
    }

    /** 一次解锁的结果。 */
    public record UnlockResult(boolean unlocked, CollectionReward reward, boolean categoryCompleted) {

        public static UnlockResult none() {
            return new UnlockResult(false, CollectionReward.EMPTY, false);
        }
    }

    /** 分类启用列表（指令/REST 用）。 */
    public List<CollectionCategory> enabledCategories() {
        List<CollectionCategory> result = new ArrayList<>();
        for (CollectionCategory category : CollectionCategory.values()) {
            if (canTrack(category)) {
                result.add(category);
            }
        }
        return result;
    }

    /** 分类默认奖励与集齐奖励（指令展示用）。 */
    public Map<CollectionCategory, List<CollectionReward>> rewardOverview() {
        Map<CollectionCategory, List<CollectionReward>> result = new LinkedHashMap<>();
        for (CollectionCategory category : enabledCategories()) {
            List<CollectionReward> rewards = new ArrayList<>();
            rewards.add(config.rewardFor(category, ""));
            rewards.add(config.completionReward(category));
            result.put(category, rewards);
        }
        return result;
    }

    /** 已解锁条目 key 集合（REST 输出用）。 */
    public Set<String> unlockedKeySet(CollectionCategory category, long playerId) {
        return new LinkedHashSet<>(unlockedKeys(category, playerId));
    }
}
