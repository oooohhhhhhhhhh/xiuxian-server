# 代码问题详细示例与修复方案

> 📦 **已归档**：本文档为历史审计产物，内容（版本号、文件路径、行号、"已修复"结论）不代表当前代码状态，
> 请勿作为开发依据。当前有效文档见项目根目录 README.md / README_PLUGIN.md / CHANGELOG.md。
> 归档索引：[docs/archive/README.md](README.md)

## 问题 1: BuffService 竞态条件 🔴 高危

### 当前代码 (BuffService.java:20-22, 42-56)
```java
private static final Map<Long, long[]> EXP_BOOSTS = new ConcurrentHashMap<>();
private static final Map<Long, long[]> REFLECTION_BUFFS = new ConcurrentHashMap<>();

public void addExpBoost(long playerId, int percent, int durationSeconds) {
    if (percent <= 0) return;
    long expireAt = System.currentTimeMillis() + (long) durationSeconds * 1000;
    EXP_BOOSTS.put(playerId, new long[]{percent, expireAt}); // ❌ 创建新数组
}

public double getExpMultiplier(long playerId) {
    long[] boost = EXP_BOOSTS.get(playerId);  // ❌ 获取数组引用
    if (boost == null) return 1.0;
    long now = System.currentTimeMillis();
    if (boost[1] <= now) {  // ❌ 读取数组元素
        EXP_BOOSTS.remove(playerId);
        return 1.0;
    }
    return 1.0 + boost[0] / 100.0;  // ❌ 读取数组元素
}

private static void cleanupExpiredMemoryBuffs() {
    long now = System.currentTimeMillis();
    EXP_BOOSTS.entrySet().removeIf(e -> e.getValue()[1] <= now);  // ❌ 读取数组
}
```

### 问题分析
1. **ConcurrentHashMap 只保证 Map 操作的线程安全**，不保证存储值（`long[]`）的线程安全
2. **竞态条件场景**：
   - 线程A: `boost[0]` 读到 50
   - 线程B: 调用 `addExpBoost()` 替换整个数组
   - 线程A: `boost[1]` 读到的可能是新数组的值
   - 结果：读到不一致的数据

3. **虽然当前代码总是替换整个数组而不是修改**，但仍有风险：
   - 读取过程中数组可能被替换
   - 在读取 `boost[0]` 和 `boost[1]` 之间，数组引用可能已经无效

### 修复方案 1: 使用不可变对象
```java
// 定义不可变的 Buff 数据类
private static class BuffData {
    final int percent;
    final long expireAt;
    
    BuffData(int percent, long expireAt) {
        this.percent = percent;
        this.expireAt = expireAt;
    }
    
    boolean isExpired() {
        return System.currentTimeMillis() > expireAt;
    }
    
    double getMultiplier() {
        return 1.0 + percent / 100.0;
    }
}

private static final Map<Long, BuffData> EXP_BOOSTS = new ConcurrentHashMap<>();

public void addExpBoost(long playerId, int percent, int durationSeconds) {
    if (percent <= 0) return;
    long expireAt = System.currentTimeMillis() + (long) durationSeconds * 1000;
    EXP_BOOSTS.put(playerId, new BuffData(percent, expireAt));
}

public double getExpMultiplier(long playerId) {
    BuffData boost = EXP_BOOSTS.get(playerId);
    if (boost == null) return 1.0;
    
    if (boost.isExpired()) {
        EXP_BOOSTS.remove(playerId);
        return 1.0;
    }
    
    return boost.getMultiplier();
}
```

### 修复方案 2: 使用 AtomicLongArray
```java
import java.util.concurrent.atomic.AtomicLongArray;

// 每个 AtomicLongArray 有两个元素: [percent, expireAt]
private static final Map<Long, AtomicLongArray> EXP_BOOSTS = new ConcurrentHashMap<>();

public void addExpBoost(long playerId, int percent, int durationSeconds) {
    if (percent <= 0) return;
    long expireAt = System.currentTimeMillis() + (long) durationSeconds * 1000;
    AtomicLongArray data = new AtomicLongArray(2);
    data.set(0, percent);
    data.set(1, expireAt);
    EXP_BOOSTS.put(playerId, data);
}

public double getExpMultiplier(long playerId) {
    AtomicLongArray boost = EXP_BOOSTS.get(playerId);
    if (boost == null) return 1.0;
    
    long expireAt = boost.get(1);
    long now = System.currentTimeMillis();
    
    if (expireAt <= now) {
        EXP_BOOSTS.remove(playerId);
        return 1.0;
    }
    
    long percent = boost.get(0);
    return 1.0 + percent / 100.0;
}
```

**推荐**: 方案1（不可变对象）更清晰、更易维护

---

## 问题 2: PlayerService SQL 拼接 🔴 高危

### 当前代码 (PlayerService.java:630-638)
```java
private void addStat(Connection conn, long playerId, String column, int amount) 
        throws SQLException {
    String sql = "UPDATE players SET " + column + " = " + column + " + ? WHERE id = ?";
    // ❌ column 参数直接拼接到 SQL
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setInt(1, amount);
        ps.setLong(2, playerId);
        ps.executeUpdate();
    }
    markDataDirty(playerId);
}

// 调用示例
public void addAttack(Connection conn, long playerId, int amount) throws SQLException {
    addStat(conn, playerId, "attack", amount);
}
```

### 问题分析
1. 虽然当前所有调用都是硬编码的字符串，但如果未来有代码路径允许外部输入，会导致 SQL 注入
2. 例如攻击者可能传入: `"attack = 999999; DROP TABLE players; --"`
3. 不符合安全编码最佳实践

### 修复方案 1: 白名单验证
```java
private static final Set<String> ALLOWED_STAT_COLUMNS = Set.of(
    "attack", "defense", "speed", "spirit", "max_hp", "max_mp", "hp", "mp", "gold"
);

private void addStat(Connection conn, long playerId, String column, int amount) 
        throws SQLException {
    // 白名单验证
    if (!ALLOWED_STAT_COLUMNS.contains(column)) {
        throw new IllegalArgumentException("Invalid stat column: " + column);
    }
    
    String sql = "UPDATE players SET " + column + " = " + column + " + ? WHERE id = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setInt(1, amount);
        ps.setLong(2, playerId);
        ps.executeUpdate();
    }
    markDataDirty(playerId);
}
```

### 修复方案 2: 为每个属性单独编写方法（更安全但代码更多）
```java
public void addAttack(Connection conn, long playerId, int amount) throws SQLException {
    String sql = "UPDATE players SET attack = attack + ? WHERE id = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setInt(1, amount);
        ps.setLong(2, playerId);
        ps.executeUpdate();
    }
    markDataDirty(playerId);
}

public void addDefense(Connection conn, long playerId, int amount) throws SQLException {
    String sql = "UPDATE players SET defense = defense + ? WHERE id = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setInt(1, amount);
        ps.setLong(2, playerId);
        ps.executeUpdate();
    }
    markDataDirty(playerId);
}

// ... 其他属性类似
```

**推荐**: 方案1（白名单验证）在安全性和代码简洁性之间取得平衡

---

## 问题 3: 静态集合内存泄漏 🔴 高危

### 当前代码 (ItemService.java:408)
```java
private static final Set<String> MILLIONAIRE_CHECKED = 
    ConcurrentHashMap.newKeySet();

// 某处代码
public void checkMillionaireAchievement(long playerId) {
    String key = String.valueOf(playerId);
    if (MILLIONAIRE_CHECKED.contains(key)) {
        return;  // 已检查过
    }
    
    // 检查是否达到百万富翁成就
    // ...
    
    MILLIONAIRE_CHECKED.add(key);  // ❌ 永远不删除
}
```

### 问题分析
1. 每个玩家ID加入后永不删除
2. 长期运行后，集合会包含所有曾经检查过的玩家
3. 如果有10万玩家，集合会持续占用内存

### 修复方案 1: 使用 Guava Cache（推荐）
```java
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

// 缓存最近检查的玩家，1小时后过期
private static final Cache<Long, Boolean> MILLIONAIRE_CHECKED = 
    CacheBuilder.newBuilder()
        .maximumSize(10_000)  // 最多缓存1万条
        .expireAfterWrite(1, TimeUnit.HOURS)  // 1小时后过期
        .build();

public void checkMillionaireAchievement(long playerId) {
    if (MILLIONAIRE_CHECKED.getIfPresent(playerId) != null) {
        return;  // 已检查过
    }
    
    // 检查是否达到百万富翁成就
    // ...
    
    MILLIONAIRE_CHECKED.put(playerId, true);
}
```

### 修复方案 2: 使用数据库标记（更可靠）
```java
// 在 players 表添加字段: millionaire_checked BOOLEAN DEFAULT 0

public void checkMillionaireAchievement(long playerId) {
    // 从数据库读取是否已检查
    if (isMillionaireChecked(playerId)) {
        return;
    }
    
    // 检查是否达到百万富翁成就
    // ...
    
    // 标记为已检查
    markMillionaireChecked(playerId);
}

private boolean isMillionaireChecked(long playerId) {
    String sql = "SELECT millionaire_checked FROM players WHERE id = ?";
    // ... 查询数据库
}

private void markMillionaireChecked(long playerId) {
    String sql = "UPDATE players SET millionaire_checked = 1 WHERE id = ?";
    // ... 更新数据库
}
```

### 修复方案 3: 定期清理
```java
private static final Set<String> MILLIONAIRE_CHECKED = 
    ConcurrentHashMap.newKeySet();

// 在某个调度器中定期清理
static {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    scheduler.scheduleAtFixedRate(() -> {
        // 每天清理一次
        MILLIONAIRE_CHECKED.clear();
    }, 24, 24, TimeUnit.HOURS);
}
```

**推荐**: 方案2（数据库标记）最可靠，或方案1（Guava Cache）如果不需要持久化

---

## 问题 4: DatabaseManager 缓存一致性 🟡 中危

### 当前代码 (DatabaseManager.java:285-315)
```java
private static volatile Set<String> tableNameCache;
private static volatile long tableNameCacheTime;

private static void assertSafeTableName(String tableName) {
    // ... 验证逻辑 ...
    
    long now = System.currentTimeMillis();
    Set<String> cache = tableNameCache;
    
    // ❌ 问题: 两个 volatile 字段不是原子更新
    if (cache == null || now - tableNameCacheTime > 30_000L) {
        cache = new HashSet<>(getAllTableNames());
        tableNameCache = cache;      // 线程A执行到这里
        tableNameCacheTime = now;     // 线程B可能看到旧的time但新的cache
    }
    
    if (!cache.contains(tableName)) {
        throw new IllegalArgumentException("表不存在: " + tableName);
    }
}
```

### 问题分析
**竞态条件示例**:
```
时刻1: 线程A更新 tableNameCache = newCache
时刻2: 线程B读取 tableNameCache (新值) 和 tableNameCacheTime (旧值)
时刻3: 线程B判断 now - tableNameCacheTime > 30_000，认为缓存过期
时刻4: 线程B重新加载缓存（不必要的数据库查询）
```

### 修复方案: 使用 AtomicReference 封装
```java
private static class TableNameCache {
    final Set<String> names;
    final long timestamp;
    
    TableNameCache(Set<String> names, long timestamp) {
        this.names = Collections.unmodifiableSet(names);
        this.timestamp = timestamp;
    }
    
    boolean isExpired(long now, long ttlMs) {
        return now - timestamp > ttlMs;
    }
}

private static final AtomicReference<TableNameCache> tableNameCacheRef = 
    new AtomicReference<>(null);

private static void assertSafeTableName(String tableName) {
    if (tableName == null || tableName.isBlank()) {
        throw new IllegalArgumentException("非法表名: " + tableName);
    }
    
    if (!tableName.matches("^[a-z_]+$")) {
        throw new IllegalArgumentException("非法表名格式: " + tableName);
    }
    
    if (!ALLOWED_TABLES.contains(tableName)) {
        throw new IllegalArgumentException("表名不在白名单中: " + tableName);
    }
    
    // 原子地检查和更新缓存
    long now = System.currentTimeMillis();
    TableNameCache cache = tableNameCacheRef.get();
    
    if (cache == null || cache.isExpired(now, 30_000L)) {
        // 可能多个线程同时重新加载，但这是可以接受的
        Set<String> names = new HashSet<>(getAllTableNames());
        TableNameCache newCache = new TableNameCache(names, now);
        tableNameCacheRef.set(newCache);
        cache = newCache;
    }
    
    if (!cache.names.contains(tableName)) {
        throw new IllegalArgumentException("表不存在: " + tableName);
    }
}
```

**改进点**:
1. 使用 `AtomicReference` 保证缓存对象的原子更新
2. 封装为不可变对象，避免并发修改
3. 多线程同时重新加载是可接受的（相比竞态条件）

---

## 问题 5: 空 catch 块吞掉异常 🟡 中危

### 当前代码 (PlayerService.java:966-972)
```java
private Player mapPlayer(ResultSet rs) throws SQLException {
    Player p = new Player();
    // ... 设置必需字段 ...
    
    try { 
        p.setBattleStrategy(rs.getString("battle_strategy")); 
    } catch (Exception ignored) {}  // ❌ 吞掉异常
    
    try { 
        p.setCurrentLocationId(rs.getLong("current_location_id")); 
    } catch (Exception ignored) {}  // ❌ 吞掉异常
    
    try { 
        p.setLastTravelTime(rs.getLong("last_travel_time")); 
    } catch (Exception ignored) {}  // ❌ 吞掉异常
    
    return p;
}
```

### 问题分析
1. 可能隐藏数据库结构不一致的问题
2. 难以诊断为什么某些字段总是 null
3. 如果字段名拼写错误，不会报错

### 修复方案 1: 记录日志
```java
private static final GameLogger LOG = GameLogger.getLogger(PlayerService.class);

private Player mapPlayer(ResultSet rs) throws SQLException {
    Player p = new Player();
    // ... 设置必需字段 ...
    
    try { 
        p.setBattleStrategy(rs.getString("battle_strategy")); 
    } catch (Exception e) {
        LOG.warn("Failed to get battle_strategy for player: " + e.getMessage());
    }
    
    try { 
        p.setCurrentLocationId(rs.getLong("current_location_id")); 
    } catch (Exception e) {
        LOG.warn("Failed to get current_location_id for player: " + e.getMessage());
    }
    
    return p;
}
```

### 修复方案 2: 使用辅助方法
```java
private Player mapPlayer(ResultSet rs) throws SQLException {
    Player p = new Player();
    // ... 设置必需字段 ...
    
    // 可选字段：不存在时返回默认值
    p.setBattleStrategy(getStringOrDefault(rs, "battle_strategy", "balanced"));
    p.setCurrentLocationId(getLongOrDefault(rs, "current_location_id", 1L));
    p.setLastTravelTime(getLongOrDefault(rs, "last_travel_time", 0L));
    
    return p;
}

private String getStringOrDefault(ResultSet rs, String column, String defaultValue) {
    try {
        String value = rs.getString(column);
        return value != null ? value : defaultValue;
    } catch (SQLException e) {
        LOG.debug("Column not found: " + column + ", using default: " + defaultValue);
        return defaultValue;
    }
}

private long getLongOrDefault(ResultSet rs, String column, long defaultValue) {
    try {
        return rs.getLong(column);
    } catch (SQLException e) {
        LOG.debug("Column not found: " + column + ", using default: " + defaultValue);
        return defaultValue;
    }
}
```

**推荐**: 方案2更清晰，并提供默认值

---

## 问题 6: 魔法数字遍布 🟢 低危

### 当前代码示例
```java
// PlayerService.java:35
scheduler.scheduleAtFixedRate(PlayerService::tickAutoHeal, 10, 10, TimeUnit.SECONDS);

// PlayerService.java:521
long cost = (player.getRealm() + 1) * 50L;

// DatabaseManager.java:51
cfg.setMaximumPoolSize(Math.max(4, AppConfig.getInt("database.pool_max", 4)));

// Main.java:307
if (cache == null || now - tableNameCacheTime > 30_000L) {
```

### 修复方案: 提取为常量
```java
public class PlayerService {
    // 自动回血配置
    private static final int AUTO_HEAL_INITIAL_DELAY_SECONDS = 10;
    private static final int AUTO_HEAL_PERIOD_SECONDS = 10;
    private static final double AUTO_HEAL_PERCENT = 0.01;  // 每次回复1%
    
    // 疗伤消耗配置
    private static final long HEAL_BASE_COST = 50L;  // 基础消耗50灵石
    
    static {
        scheduler.scheduleAtFixedRate(
            PlayerService::tickAutoHeal, 
            AUTO_HEAL_INITIAL_DELAY_SECONDS, 
            AUTO_HEAL_PERIOD_SECONDS, 
            TimeUnit.SECONDS
        );
    }
    
    public Map<String, Object> healPlayer(long playerId) {
        // ...
        long cost = (player.getRealm() + 1) * HEAL_BASE_COST;
        // ...
    }
}

public class DatabaseManager {
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final int MIN_POOL_SIZE = 4;
    
    public static void init(String url, String user, String password) {
        // ...
        cfg.setMaximumPoolSize(Math.max(
            MIN_POOL_SIZE, 
            AppConfig.getInt("database.pool_max", DEFAULT_POOL_SIZE)
        ));
        // ...
    }
}

public class DatabaseManager {
    private static final long TABLE_NAME_CACHE_TTL_MS = 30_000L;  // 30秒
    
    private static void assertSafeTableName(String tableName) {
        // ...
        if (cache == null || now - tableNameCacheTime > TABLE_NAME_CACHE_TTL_MS) {
            // ...
        }
    }
}
```

---

## 总结

以上是项目中最重要的6类问题及其修复方案。建议按以下优先级处理：

1. **立即修复** (🔴): BuffService 竞态条件、SQL拼接、静态集合泄漏
2. **近期修复** (🟡): 缓存一致性、异常处理
3. **持续改进** (🟢): 魔法数字、代码重构

修复这些问题后，代码质量将显著提升，生产环境的稳定性也会大大增强。
