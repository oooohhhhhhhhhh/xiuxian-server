# 快速修复行动清单

> 📦 **已归档**：本文档为历史审计产物，内容（版本号、文件路径、行号、"已修复"结论）不代表当前代码状态，
> 请勿作为开发依据。当前有效文档见项目根目录 README.md / README_PLUGIN.md / CHANGELOG.md。
> 归档索引：[docs/archive/README.md](README.md)

## 🔴 优先级1：必须立即修复（安全关键）

### 1. BuffService 竞态条件
**文件**: `src/main/java/com/mtxgdn/game/service/BuffService.java`  
**行**: 20-22, 42-56

**问题**: `long[]` 数组不是线程安全的

**修复步骤**:
```bash
# 1. 创建不可变 BuffData 类
# 2. 替换 Map<Long, long[]> 为 Map<Long, BuffData>
# 3. 测试多线程场景
```

**预计时间**: 30分钟  
**风险**: 低（向后兼容）

---

### 2. PlayerService SQL 拼接
**文件**: `src/main/java/com/mtxgdn/game/service/PlayerService.java`  
**行**: 630-638

**问题**: 列名直接拼接到 SQL

**修复步骤**:
```java
// 在 PlayerService 类添加常量
private static final Set<String> ALLOWED_STAT_COLUMNS = Set.of(
    "attack", "defense", "speed", "spirit", "max_hp", "max_mp", "hp", "mp", "gold"
);

// 在 addStat 方法开头添加验证
if (!ALLOWED_STAT_COLUMNS.contains(column)) {
    throw new IllegalArgumentException("Invalid stat column: " + column);
}
```

**预计时间**: 15分钟  
**风险**: 无（纯加固）

---

### 3. 静态集合内存泄漏 - ItemService
**文件**: `src/main/java/com/mtxgdn/game/service/ItemService.java`  
**行**: 408

**问题**: `MILLIONAIRE_CHECKED` 集合无限增长

**修复选项A（推荐）**: 改用数据库标记
```java
// 在 players 表添加字段
ALTER TABLE players ADD COLUMN millionaire_checked BOOLEAN DEFAULT 0;

// 查询和更新数据库而不是内存
```

**修复选项B**: 使用有界缓存
```java
// 添加依赖 com.google.guava:guava
private static final Cache<Long, Boolean> MILLIONAIRE_CHECKED = 
    CacheBuilder.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build();
```

**预计时间**: 1小时（选项A）/ 30分钟（选项B）  
**风险**: 中等（需要数据库迁移或添加依赖）

---

### 4. 静态集合内存泄漏 - FriendService
**文件**: `src/main/java/com/mtxgdn/game/service/FriendService.java`  
**行**: 170

**问题**: `DAILY_VISITS` 每日访问记录未清理

**修复步骤**:
```java
// 添加定时清理任务
static {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    // 每天凌晨清空
    scheduler.scheduleAtFixedRate(() -> {
        DAILY_VISITS.clear();
    }, getSecondsUntilMidnight(), 24 * 3600, TimeUnit.SECONDS);
}

private static long getSecondsUntilMidnight() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime midnight = now.plusDays(1).withHour(0).withMinute(0).withSecond(0);
    return Duration.between(now, midnight).getSeconds();
}
```

**预计时间**: 20分钟  
**风险**: 低

---

### 5. 静态集合内存泄漏 - EnhanceService
**文件**: `src/main/java/com/mtxgdn/game/service/EnhanceService.java`  
**行**: 26

**问题**: `FAIL_STREAK` 失败连击记录未清理

**修复步骤**:
```java
// 改用有界 ConcurrentHashMap
private static final Map<String, Integer> FAIL_STREAK = new LinkedHashMap<>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
        return size() > 10_000;  // 最多保留1万条
    }
};

// 或使用 Guava Cache
private static final Cache<String, Integer> FAIL_STREAK = 
    CacheBuilder.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build();
```

**预计时间**: 15分钟  
**风险**: 低

---

## 🟡 优先级2：应尽快修复（稳定性）

### 6. DatabaseManager 缓存一致性
**文件**: `src/main/java/com/mtxgdn/db/DatabaseManager.java`  
**行**: 285-315

**问题**: 两个 volatile 字段不是原子更新

**修复步骤**: 见 `CODE_ISSUES_EXAMPLES.md` 问题4

**预计时间**: 30分钟  
**风险**: 低

---

### 7. 空 catch 块改进
**文件**: 多个文件

**批量修复步骤**:
```bash
# 搜索所有空 catch 块
grep -rn "catch.*ignored\s*{}" src/main/java/

# 对于每个空 catch，评估：
# 1. 是否应该记录日志？
# 2. 是否应该重新抛出？
# 3. 是否真的可以忽略？
```

**关键文件**:
- `PlayerService.java:966-972` - 添加日志或使用辅助方法
- `FarmService.java` - 数据库操作失败应该记录
- `EconomyService.java` - 事务失败应该记录

**预计时间**: 2-3小时（根据数量）  
**风险**: 低

---

### 8. PlayerService shutdown 未调用
**文件**: `src/main/java/com/mtxgdn/Main.java`  
**行**: 86-113 (registerShutdownHook)

**问题**: `PlayerService.shutdown()` 没有在关闭钩子中调用

**修复步骤**:
```java
private static void registerShutdownHook() {
    ServerShutdownHook hook = ServerShutdownHook.createDefault();
    
    // 添加这一行
    hook.addTask(() -> {
        PlayerService.shutdown();
        LOG.info("玩家服务调度器已关闭");
    });
    
    hook.addTask(() -> AdapterManager.stopAll());
    // ... 其他任务
}
```

**预计时间**: 5分钟  
**风险**: 无

---

## 🟢 优先级3：持续改进（代码质量）

### 9. 魔法数字常量化
**文件**: 全局

**修复策略**:
```bash
# 1. 识别所有魔法数字
grep -rn "[^a-zA-Z0-9_][0-9]\+[^a-zA-Z0-9_]" src/main/java/ | grep -v "\.class" | head -100

# 2. 按模块分批处理
# 3. 为每个数字添加有意义的常量名和注释
```

**示例文件**:
- `PlayerService.java` - AUTO_HEAL_PERCENT, HEAL_BASE_COST
- `DatabaseManager.java` - TABLE_NAME_CACHE_TTL_MS
- `Main.java` - DEFAULT_POOL_SIZE

**预计时间**: 全项目 4-8小时  
**风险**: 低（逐步进行）

---

### 10. 重复代码提取 - Scanner 类
**文件**: 
- `ItemScanner.java`
- `ExplorationEventScanner.java`
- `SecretRealmScanner.java`

**问题**: 三个类有相同的类扫描逻辑

**修复步骤**:
```java
// 创建公共基类或工具类
public class ClassScanner {
    public static List<Class<?>> scanPackage(String packageName) throws Exception {
        // 公共扫描逻辑
    }
    
    private static void scanDirectory(File dir, String pkg, List<Class<?>> classes) {
        // ...
    }
    
    private static void scanJar(URL jarUrl, String packagePath, List<Class<?>> classes) {
        // ...
    }
}

// 然后每个 Scanner 调用
List<Class<?>> classes = ClassScanner.scanPackage("data.mtxgdn.item");
```

**预计时间**: 1-2小时  
**风险**: 中（需要彻底测试）

---

### 11. 过长方法重构
**文件**: 
- `PlayerService.java` (1200+ 行)
- `Main.java:doMain()` (195 行)
- `EconomyService.java` (1000+ 行)

**重构策略**:
```java
// PlayerService 可拆分为：
// - PlayerCultivationService (修炼相关)
// - PlayerStatService (属性相关)
// - PlayerQueryService (查询相关)
// - PlayerCombatAttributeService (战斗属性计算)

// Main.doMain() 可拆分为：
// - initializeSystems()
// - startWebServer()
// - configureAdapters()
// - runServerMode(mode)
```

**预计时间**: 每个类 2-4小时  
**风险**: 中（需要大量测试）

---

## 📋 修复追踪表

| 编号 | 问题 | 优先级 | 状态 | 负责人 | 完成日期 |
|------|------|--------|------|--------|----------|
| 1 | BuffService 竞态条件 | 🔴 | ⬜ 待修复 | | |
| 2 | PlayerService SQL拼接 | 🔴 | ⬜ 待修复 | | |
| 3 | ItemService 内存泄漏 | 🔴 | ⬜ 待修复 | | |
| 4 | FriendService 内存泄漏 | 🔴 | ⬜ 待修复 | | |
| 5 | EnhanceService 内存泄漏 | 🔴 | ⬜ 待修复 | | |
| 6 | DatabaseManager 缓存 | 🟡 | ⬜ 待修复 | | |
| 7 | 空catch块改进 | 🟡 | ⬜ 待修复 | | |
| 8 | PlayerService shutdown | 🟡 | ⬜ 待修复 | | |
| 9 | 魔法数字 | 🟢 | ⬜ 待修复 | | |
| 10 | Scanner重复代码 | 🟢 | ⬜ 待修复 | | |
| 11 | 过长方法重构 | 🟢 | ⬜ 待修复 | | |

---

## 🧪 测试计划

### 修复后必须测试的场景

1. **并发测试**:
   - 多个玩家同时使用经验倍率药品
   - 多个线程同时读取玩家属性
   - 并发修炼结算

2. **压力测试**:
   - 100个玩家持续游戏24小时
   - 观察内存使用情况
   - 确认集合大小不会无限增长

3. **功能回归测试**:
   - 所有核心游戏功能
   - 数据库操作（创建、查询、更新、删除）
   - WebSocket 连接和消息

4. **安全测试**:
   - 尝试 SQL 注入攻击
   - 验证白名单机制
   - 测试限流和黑名单

---

## 📊 预计总工时

- 🔴 优先级1: **3-4小时**（必须完成）
- 🟡 优先级2: **3-4小时**（强烈推荐）
- 🟢 优先级3: **8-16小时**（逐步改进）

**建议**: 先用1周完成优先级1和2，然后持续改进优先级3

---

## 🎯 成功标准

修复完成后应满足：

1. ✅ 无静态集合无限增长
2. ✅ 无SQL注入风险
3. ✅ 所有并发场景线程安全
4. ✅ 异常都有适当处理
5. ✅ 资源正确释放（连接、线程等）
6. ✅ 代码通过静态分析工具检查
7. ✅ 通过所有回归测试
8. ✅ 压力测试24小时无内存泄漏

---

## 📝 备注

- 每次修复前先创建分支
- 每个问题修复后提交独立commit
- 重要修复需要code review
- 修复后更新此清单的状态
- 定期运行性能监控
