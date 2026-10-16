# 修仙服务器项目代码问题报告

> 📦 **已归档**：本文档为历史审计产物，内容（版本号、文件路径、行号、"已修复"结论）不代表当前代码状态，
> 请勿作为开发依据。当前有效文档见项目根目录 README.md / README_PLUGIN.md / CHANGELOG.md。
> 归档索引：[docs/archive/README.md](README.md)

## 概述
本报告分析了修仙游戏服务器（xiuxian-server-alpha）的代码质量问题，涵盖安全、并发、资源管理、设计等多个维度。

---

## 1. 安全问题 🔴 高危

### 1.1 SQL注入风险 - 部分已修复
**位置**: `PlayerService.java:631`
```java
private void addStat(Connection conn, long playerId, String column, int amount) throws SQLException {
    String sql = "UPDATE players SET " + column + " = " + column + " + ? WHERE id = ?";
    // column 参数直接拼接到SQL中，存在SQL注入风险
}
```

**问题**: 
- `column` 参数虽然来自内部方法调用，但直接拼接到SQL语句中
- 如果未来有代码路径允许外部输入控制 column 参数，将导致SQL注入

**影响**: 可能导致数据库被恶意操作

**修复建议**: 
1. 使用白名单验证列名
2. 或者为每个属性单独编写SQL语句

**已修复的类似问题**: `DatabaseManager.java` 已实现表名白名单（272-282行）和列名校验（318-322行）

### 1.2 默认凭据警告机制
**位置**: `Main.java:203-213`
```java
if (!"127.0.0.1".equals(serverHost) && !"localhost".equals(serverHost)) {
    if (adminPwdHash.isEmpty() && (adminPwd.isEmpty() || "admin123".equals(adminPwd))) {
        System.err.println("⚠ 严重安全警告: 服务绑定到公网但admin凭据未配置或仍为默认！");
    }
}
```

**问题**: 
- 只是警告，不强制要求修改
- 公网暴露时应该直接拒绝启动

**修复建议**: 改为强制检查，公网部署时禁止使用默认密码

---

## 2. 并发安全问题 🔴 高危

### 2.1 静态可变集合缺少同步
**位置**: `BuffService.java:20-22`
```java
private static final Map<Long, long[]> EXP_BOOSTS = new ConcurrentHashMap<>();
private static final Map<Long, long[]> REFLECTION_BUFFS = new ConcurrentHashMap<>();
```

**问题**: 
- 使用了 `ConcurrentHashMap`，但存储的 `long[]` 数组本身不是线程安全的
- 多线程同时修改同一个数组会导致竞态条件

**示例问题**:
```java
// 线程A
long[] boost = EXP_BOOSTS.get(playerId);
boost[0] = newValue;  // ❌ 非原子操作

// 线程B同时读取
long[] boost = EXP_BOOSTS.get(playerId);
return boost[0];  // 可能读到不一致的值
```

**修复建议**: 
1. 将 `long[]` 改为不可变对象或使用 `AtomicLongArray`
2. 或者在访问数组时使用同步

### 2.2 静态单例模式缺少双重检查锁
**位置**: 多个 Service 类使用懒加载单例

**潜在问题示例**:
```java
private static volatile MapService mapService;
public static MapService getMapService() {
    if (mapService == null) {  // ❌ 竞态条件
        mapService = new MapService();
    }
    return mapService;
}
```

**修复建议**: 使用双重检查锁或静态内部类单例模式

### 2.3 共享状态的并发访问
**位置**: `PlayerService.java:32`
```java
private static final ScheduledExecutorService scheduler = 
    Executors.newSingleThreadScheduledExecutor();
```

**问题**: 
- 静态调度器在多玩家场景下可能成为瓶颈
- 自动回血任务每10秒执行，但没有看到具体实现

### 2.4 缓存一致性问题
**位置**: `DatabaseManager.java:285-315`
```java
private static volatile Set<String> tableNameCache;
private static volatile long tableNameCacheTime;
```

**问题**: 
- 两个 volatile 字段不是原子更新
- 线程A更新 cache，线程B更新 cacheTime，可能导致不一致

**修复建议**: 使用 `AtomicReference<CacheEntry>` 封装两个字段

---

## 3. 资源泄漏风险 🟡 中危

### 3.1 数据库连接管理 - 大部分已修复
**状态**: ✅ 项目已正确使用 try-with-resources

**示例** (正确做法):
```java
try (Connection conn = DatabaseManager.getConnection();
     PreparedStatement ps = conn.prepareStatement(sql)) {
    // ...
}
```

**问题**: 少数地方没有使用 try-with-resources，但大部分代码质量良好

### 3.2 静态集合持续增长
**位置**: 多个服务类

**问题集合**:
1. `ItemService.java:408` - `MILLIONAIRE_CHECKED` 集合无限增长
2. `FriendService.java:170` - `DAILY_VISITS` 每日访问记录未清理
3. `EnhanceService.java:26` - `FAIL_STREAK` 失败连击记录未清理
4. `TokenBlacklist.java:8` - Token黑名单有清理机制 ✅
5. `RateLimiter.java:8` - 限流窗口有清理机制 ✅

**示例问题**:
```java
// ItemService.java
private static final Set<String> MILLIONAIRE_CHECKED = 
    ConcurrentHashMap.newKeySet();

// 每次检查都加入，永不删除 ❌
MILLIONAIRE_CHECKED.add(key);
```

**影响**: 长期运行会导致内存泄漏

**修复建议**: 
1. 使用有界缓存（如 Guava Cache 或 Caffeine）
2. 添加定期清理任务
3. 使用弱引用

### 3.3 线程池未关闭
**位置**: `PlayerService.java:32-42`
```java
private static final ScheduledExecutorService scheduler = 
    Executors.newSingleThreadScheduledExecutor();

public static void shutdown() {
    scheduler.shutdown();
}
```

**问题**: shutdown() 方法存在，但没有被调用链路

**修复建议**: 在 `Main.java` 的 shutdown hook 中调用

---

## 4. 业务逻辑问题 🟡 中危

### 4.1 空指针风险
**位置**: `PlayerService.java:978-993`
```java
private Title getEquippedTitle(long playerId) {
    // ... 可能返回 null
}

public int getFinalAttack(Player player) {
    return applyTitleBonus(player, player.getAttack(), 
        t -> t.getAttackBonus())  // ❌ 如果title为null会抛异常
        + getEnhanceBonus(player.getId())[0];
}
```

**问题**: 多处没有检查 null 就直接使用

**修复建议**: 添加 null 检查或使用 Optional

### 4.2 魔法数字
**遍布整个项目**，示例：
```java
// PlayerService.java:521
long cost = (player.getRealm() + 1) * 50L;  // 50 是什么？

// Main.java:307
cfg.setMaximumPoolSize(Math.max(4, ...));  // 为什么是4？

// PlayerService.java:35
scheduler.scheduleAtFixedRate(..., 10, 10, TimeUnit.SECONDS);  // 10秒？
```

**修复建议**: 提取为常量并添加注释

### 4.3 异常处理不当
**位置**: 多处空catch块

**示例**:
```java
// PlayerService.java:966-972
try { 
    p.setBattleStrategy(rs.getString("battle_strategy")); 
} catch (Exception ignored) {}  // ❌ 吞掉异常
```

**问题**: 
- 忽略异常可能隐藏bug
- 无法诊断问题

**修复建议**: 至少记录日志

### 4.4 过长的方法
**位置**: 
- `PlayerService.java` 1200+ 行
- `Main.java:131-326` doMain() 方法 195 行
- `PlayerService.mapPlayer()` 方法过长

**问题**: 
- 难以维护和测试
- 违反单一职责原则

**修复建议**: 重构拆分

---

## 5. 设计问题 🟢 低危

### 5.1 紧耦合
**问题**: Service 类之间直接相互调用，没有接口抽象

**示例**:
```java
// PlayerService.java
ServiceRegistry.getItemService()
ServiceRegistry.getBuffService()
ServiceRegistry.getMapService()
```

**修复建议**: 
1. 引入依赖注入
2. 定义服务接口

### 5.2 上帝类
**位置**: 
- `PlayerService` - 1200+ 行，职责过多
- `EconomyService` - 1000+ 行
- `SectService` - 多种职责混杂

**修复建议**: 按功能拆分为多个类

### 5.3 重复代码
**位置**: Scanner 类（ItemScanner, ExplorationEventScanner, SecretRealmScanner）

**问题**: 三个 Scanner 类有几乎完全相同的类扫描逻辑

**修复建议**: 提取公共基类或工具类

---

## 6. 性能问题 🟢 低危

### 6.1 数据库查询效率
**位置**: `PlayerService.java:834-853`
```java
public List<PlayerInfo> getTopByWealth(int limit) {
    // 复杂的JOIN和聚合查询
    // 在大量玩家时可能很慢
}
```

**修复建议**: 
1. 添加索引
2. 使用缓存
3. 考虑物化视图

### 6.2 N+1 查询问题
**位置**: 多个地方循环查询数据库

**修复建议**: 使用批量查询或JOIN

---

## 7. 优点总结 ✅

1. **数据库安全**: 大部分地方正确使用 PreparedStatement，防止SQL注入
2. **连接池**: 使用 HikariCP，提高性能
3. **事务管理**: 正确使用事务回滚机制
4. **资源管理**: 大部分使用 try-with-resources
5. **并发工具**: 使用 ConcurrentHashMap 等并发安全类
6. **配置管理**: 完善的配置系统
7. **日志记录**: 有统一的日志系统
8. **安全意识**: 有Token黑名单、限流、白名单等机制

---

## 8. 修复优先级

### 🔴 紧急修复
1. `PlayerService.addStat()` SQL拼接问题
2. `BuffService` 数组竞态条件
3. 静态集合内存泄漏（MILLIONAIRE_CHECKED等）

### 🟡 重要修复
1. 空指针检查
2. 异常处理改进
3. 缓存一致性问题
4. 公网部署安全检查强化

### 🟢 优化改进
1. 代码重构（拆分大类）
2. 提取重复代码
3. 魔法数字常量化
4. 性能优化

---

## 9. 测试覆盖

**发现**: 项目有测试框架（JUnit 5 + Mockito），但测试覆盖率可能不足

**建议**:
1. 增加单元测试覆盖率
2. 添加并发测试
3. 添加集成测试
4. 压力测试

---

## 10. 总体评价

**代码质量**: ⭐⭐⭐⭐ (4/5)

**优点**:
- 架构清晰，分层合理
- 安全意识较强
- 使用了现代Java特性
- 有完善的配置和日志系统

**主要问题**:
- 部分并发安全问题需要修复
- 资源管理有少量泄漏风险
- 代码重复和过长方法较多
- 测试覆盖可能不足

**建议**:
这是一个功能完善的项目，但在投入生产环境前，建议优先修复🔴高危和🟡中危问题，特别是并发安全和内存泄漏相关的问题。
