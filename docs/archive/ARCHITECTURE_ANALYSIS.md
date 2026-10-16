# 项目架构与设计模式分析

> 📦 **已归档**：本文档为历史审计产物，内容（版本号、文件路径、行号、"已修复"结论）不代表当前代码状态，
> 请勿作为开发依据。当前有效文档见项目根目录 README.md / README_PLUGIN.md / CHANGELOG.md。
> 归档索引：[docs/archive/README.md](README.md)

## 项目概览

**项目名称**: 修仙游戏服务器 (xiuxian-server-alpha)  
**技术栈**: Java 23, Jersey (JAX-RS), Grizzly, WebSocket, MySQL/SQLite  
**架构类型**: 单体分层架构 + WebSocket 实时通信  
**代码规模**: ~1000+ 文件，估计 5万+ 行代码

---

## 1. 整体架构

### 1.1 分层结构

```
┌─────────────────────────────────────────────┐
│         表示层 (Presentation)                │
│  ┌─────────────┬──────────────┬──────────┐  │
│  │ REST API    │  WebSocket   │  适配器  │  │
│  │ (Jersey)    │  (Grizzly)   │ (QQ/MC)  │  │
│  └─────────────┴──────────────┴──────────┘  │
├─────────────────────────────────────────────┤
│         业务逻辑层 (Business)                │
│  ┌─────────────────────────────────────┐    │
│  │     Service 层 (25+ 服务类)         │    │
│  │  PlayerService, ItemService, etc.   │    │
│  └─────────────────────────────────────┘    │
├─────────────────────────────────────────────┤
│         数据访问层 (Data Access)             │
│  ┌─────────────────────────────────────┐    │
│  │    DatabaseManager + HikariCP       │    │
│  │    JDBC + PreparedStatement         │    │
│  └─────────────────────────────────────┘    │
├─────────────────────────────────────────────┤
│         数据层 (Data)                        │
│  ┌──────────────┬──────────────────────┐    │
│  │   MySQL      │   文件系统           │    │
│  │   SQLite     │   (玩家数据JSON)     │    │
│  └──────────────┴──────────────────────┘    │
└─────────────────────────────────────────────┘

横向关注点:
├── 配置管理 (AppConfig, GameConfigLoader)
├── 日志系统 (GameLogger)
├── 插件系统 (PluginManager)
├── 权限系统 (PermissionService)
└── 安全机制 (JWT, RateLimiter, TokenBlacklist)
```

### 1.2 核心模块

#### A. REST API 层
```
com.mtxgdn.rest/
├── GameResource.java          # 游戏核心API
├── AdminResource.java         # 管理后台API
├── InstallResource.java       # 安装向导API
├── AuthResource.java          # 认证API
├── UnifiedRestResource.java   # 统一路由
├── AdminAuthFilter.java       # 管理员认证过滤器
└── GenericExceptionMapper.java # 全局异常处理
```

#### B. WebSocket 层
```
com.mtxgdn.websocket/
└── GameWebSocketApp.java      # WebSocket应用入口
    - 玩家连接管理
    - 实时消息推送
    - 在线状态维护
```

#### C. Service 层（核心业务逻辑）
```
com.mtxgdn.game.service/
├── PlayerService.java         # 玩家管理 (1200+ 行) ⚠️
├── ItemService.java           # 物品系统
├── RealmService.java          # 境界系统
├── CombatService.java         # 战斗系统
├── ExplorationService.java    # 游历系统
├── SecretRealmService.java    # 秘境系统
├── SectService.java           # 宗门系统
├── TechniqueService.java      # 功法系统
├── SkillService.java          # 技能系统
├── EconomyService.java        # 经济系统 (1000+ 行) ⚠️
├── TradeService.java          # 交易系统
├── CraftingService.java       # 炼制系统
├── EnhanceService.java        # 强化系统
├── FarmService.java           # 种植系统
├── CaveService.java           # 洞府系统
├── FormationService.java      # 阵法系统
├── BuffService.java           # Buff系统
├── TitleService.java          # 称号系统
├── FriendService.java         # 好友系统
├── ChatService.java           # 聊天系统
├── DailyService.java          # 每日系统
├── SeasonService.java         # 季节系统
├── MapService.java            # 地图系统
└── ... (更多)
```

#### D. 适配器层
```
com.mtxgdn.adapter/
├── AdapterManager.java        # 适配器管理器
│
com.mtxgdn.onebot/             # QQ机器人适配器
├── OneBotWebSocketServer.java # OneBot协议服务端
├── OneBotClientAdapter.java   # OneBot协议客户端
└── command/                   # QQ指令系统
│
com.mtxgdn.qq/                 # QQ官方API适配器
├── QqOfficialAdapter.java
└── QqWebhookServer.java
│
com.mtxgdn.minecraft/          # Minecraft适配器
├── MinecraftMotdServer.java   # MOTD服务器
└── adapter/
```

#### E. 数据层
```
com.mtxgdn.db/
├── DatabaseManager.java       # 数据库管理核心
├── DatabaseSchema.java        # 表结构定义
├── DatabaseDialect.java       # 数据库方言接口
├── MySQLDialect.java          # MySQL实现
├── SQLiteDialect.java         # SQLite实现
└── PlayerActionLogDAO.java    # 行为日志DAO
```

#### F. 插件系统
```
com.mtxgdn.plugin/
├── PluginManager.java         # 外部插件管理
├── PluginClassLoader.java     # 插件类加载器
├── BuiltInPluginRegistry.java # 内置玩法模块
├── builtin/                   # 内置模块
│   ├── ExplorationPlugin.java
│   ├── SecretRealmPlugin.java
│   ├── TradePlugin.java
│   ├── SectPlugin.java
│   ├── FarmPlugin.java
│   └── CavePlugin.java
└── event/
    ├── PluginEvent.java
    └── PluginEventManager.java
```

---

## 2. 设计模式使用

### 2.1 ✅ 正确使用的模式

#### 单例模式 (Singleton)
```java
// ServiceRegistry.java - 使用双重检查锁
public static PlayerService getPlayerService() {
    if (playerService == null) {
        synchronized (ServiceRegistry.class) {
            if (playerService == null) {
                playerService = new PlayerService();
            }
        }
    }
    return playerService;
}
```
**优点**: 
- 懒加载，节省资源
- 线程安全
- 全局访问点

**缺点**: 
- 紧耦合
- 难以测试（建议改为依赖注入）

#### 工厂模式 (Factory)
```java
// DatabaseDialect 工厂
DatabaseDialect DIALECT = IS_SQLITE ? new SQLiteDialect() : new MySQLDialect();
```

#### 策略模式 (Strategy)
```java
// 数据库方言策略
public interface DatabaseDialect {
    String getPkDefinition();
    String getTimestampDefault();
    void initConnection(Connection conn);
}

class MySQLDialect implements DatabaseDialect { ... }
class SQLiteDialect implements DatabaseDialect { ... }
```

#### 模板方法模式 (Template Method)
```java
// Scanner 基类模式（虽然实现中有重复代码）
abstract class BaseScanner<T> {
    public final ScanResult scanAndRegister() {
        List<Class<?>> classes = findClasses();
        List<T> instances = createInstances(classes);
        registerInstances(instances);
        return createResult();
    }
    
    protected abstract List<T> createInstances(List<Class<?>> classes);
    protected abstract void registerInstances(List<T> instances);
}
```

#### 观察者模式 (Observer)
```java
// 季节变化监听器
SeasonService.setChangeListener(season -> {
    GameWebSocketApp app = gameWebSocketApp;
    if (app != null) {
        // 广播季节变化
        app.broadcast(message);
    }
});
```

#### 装饰器模式 (Decorator)
```java
// 灵根效果装饰
class SpiritualRoot {
    public int applyHpBonus(int baseHp) {
        return (int) (baseHp * hpMultiplier);
    }
}
```

#### 命令模式 (Command)
```java
// QQ指令系统
@Route(value = "/探索", aliases = {"游历", "explore"})
public class ExploreCommand extends OneBotCommand {
    @Override
    public void execute(OneBotCommandContext ctx) {
        // 执行探索逻辑
    }
}
```

#### 注册表模式 (Registry)
```java
// 物品注册表
public class ItemRegistry {
    private static final Map<String, Item> items = new LinkedHashMap<>();
    
    public static void register(Item item) {
        items.put(item.getKey(), item);
    }
    
    public static Item get(String key) {
        return items.get(key);
    }
}
```

### 2.2 ⚠️ 可以改进的模式

#### 服务定位器模式 (Service Locator) - 待改进
```java
// 当前: 静态服务定位器
ServiceRegistry.getPlayerService()
ServiceRegistry.getItemService()

// 建议: 依赖注入
@Inject
private PlayerService playerService;

@Inject
private ItemService itemService;
```

**改进方向**: 引入 Spring Boot 或 Guice 依赖注入框架

#### 仓储模式 (Repository) - 缺失
```java
// 当前: Service 层直接操作数据库
public class PlayerService {
    public Player getPlayerById(long id) {
        String sql = "SELECT * FROM players WHERE id = ?";
        try (Connection conn = ...) {
            // JDBC代码
        }
    }
}

// 建议: 引入 Repository 层
public interface PlayerRepository {
    Optional<Player> findById(long id);
    List<Player> findAll();
    void save(Player player);
    void delete(long id);
}

public class PlayerService {
    private final PlayerRepository playerRepository;
    
    public Player getPlayerById(long id) {
        return playerRepository.findById(id)
            .orElseThrow(() -> new PlayerNotFoundException(id));
    }
}
```

---

## 3. 架构优势

### 3.1 ✅ 做得好的地方

1. **清晰的分层**
   - REST API 和 WebSocket 分离
   - Service 层职责明确
   - 数据访问统一通过 DatabaseManager

2. **完善的插件系统**
   - 支持外部插件
   - 内置模块化设计
   - 热加载机制

3. **多数据库支持**
   - 方言模式支持 MySQL 和 SQLite
   - 统一的数据访问接口

4. **多适配器支持**
   - QQ（OneBot协议）
   - QQ（官方API）
   - Minecraft
   - 统一的适配器管理

5. **完善的配置系统**
   - YAML配置文件
   - 运行时配置更新
   - 实验性配置支持

6. **安全机制**
   - JWT认证
   - 限流（RateLimiter）
   - Token黑名单
   - SQL注入防护（大部分地方）

7. **日志系统**
   - 统一的日志接口
   - 内存日志缓存
   - 按模块分类

### 3.2 ⚠️ 架构问题

1. **上帝类（God Class）**
   - `PlayerService` 1200+ 行
   - `EconomyService` 1000+ 行
   - 职责过多，难以维护

2. **紧耦合**
   - Service 层直接调用其他 Service
   - 缺少接口抽象
   - 依赖关系复杂

3. **缺少抽象层**
   - 没有 Repository 层
   - Service 层直接写 SQL
   - 业务逻辑和数据访问混杂

4. **重复代码**
   - Scanner 类重复
   - 数据映射代码重复
   - 错误处理重复

5. **测试困难**
   - 静态方法过多
   - 单例模式难以mock
   - 缺少依赖注入

---

## 4. 架构改进建议

### 4.1 短期改进（1-2个月）

#### A. 引入 Repository 层
```
┌─────────────────────────────────────┐
│         Service 层                  │
│  ┌──────────────────────────────┐   │
│  │  PlayerService               │   │
│  │  - 业务逻辑                  │   │
│  │  - 调用 Repository           │   │
│  └──────────────────────────────┘   │
├─────────────────────────────────────┤
│         Repository 层               │
│  ┌──────────────────────────────┐   │
│  │  PlayerRepository            │   │
│  │  - 数据访问                  │   │
│  │  - CRUD操作                  │   │
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
```

#### B. 拆分大类
```java
// PlayerService 拆分为：
PlayerCoreService         # 核心玩家数据
PlayerCultivationService  # 修炼相关
PlayerAttributeService    # 属性计算
PlayerQueryService        # 查询和排行榜
```

#### C. 提取公共基类
```java
// Scanner 公共基类
abstract class BaseScanner<T> {
    protected List<Class<?>> scanPackage(String packageName) {
        return ClassScanner.scan(packageName);
    }
    
    protected abstract T createInstance(Class<?> clazz);
    protected abstract void register(T instance);
}
```

### 4.2 中期改进（3-6个月）

#### A. 引入依赖注入
```java
// 使用 Guice 或 Spring
@Singleton
public class PlayerService {
    private final PlayerRepository playerRepository;
    private final ItemService itemService;
    
    @Inject
    public PlayerService(
        PlayerRepository playerRepository,
        ItemService itemService
    ) {
        this.playerRepository = playerRepository;
        this.itemService = itemService;
    }
}
```

#### B. 事件驱动架构
```java
// 使用事件总线
EventBus eventBus = new EventBus();

// 发布事件
eventBus.post(new PlayerLevelUpEvent(playerId, newLevel));

// 订阅事件
@Subscribe
public void onPlayerLevelUp(PlayerLevelUpEvent event) {
    // 处理等级提升
}
```

#### C. CQRS 模式（可选）
```
Command (写操作)          Query (读操作)
    │                         │
    ▼                         ▼
CommandHandler          QueryHandler
    │                         │
    ▼                         ▼
 写数据库                  读数据库/缓存
```

### 4.3 长期改进（6-12个月）

#### A. 微服务架构（可选）
```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│  玩家服务   │  │  战斗服务   │  │  社交服务   │
└─────────────┘  └─────────────┘  └─────────────┘
        │                │                │
        └────────────────┴────────────────┘
                         │
                  ┌──────┴──────┐
                  │  API Gateway │
                  └──────────────┘
```

#### B. 引入缓存层
```
┌─────────────────────────────────────┐
│         Service 层                  │
├─────────────────────────────────────┤
│         Cache 层 (Redis)            │
│  - 玩家数据缓存                     │
│  - 排行榜缓存                       │
│  - 会话缓存                         │
├─────────────────────────────────────┤
│         Database 层                 │
└─────────────────────────────────────┘
```

#### C. 消息队列
```
┌──────────┐      ┌──────────┐      ┌──────────┐
│  生产者  │ ───> │   MQ     │ ───> │  消费者  │
└──────────┘      │ (RabbitMQ│      └──────────┘
                  │ /Kafka)  │
                  └──────────┘
```

---

## 5. 技术债务评估

### 5.1 债务清单

| 类别 | 债务 | 影响 | 优先级 |
|------|------|------|--------|
| 代码质量 | 上帝类过多 | 维护困难 | 高 |
| 架构 | 缺少抽象层 | 扩展困难 | 高 |
| 测试 | 测试覆盖率低 | 质量风险 | 中 |
| 并发 | 竞态条件 | 稳定性风险 | 高 |
| 内存 | 静态集合泄漏 | 性能问题 | 高 |
| 重复代码 | Scanner重复 | 维护成本 | 中 |
| 文档 | 缺少API文档 | 使用困难 | 低 |

### 5.2 偿还计划

**第1个月**: 
- 修复所有🔴高危问题
- 添加单元测试

**第2-3个月**:
- 引入 Repository 层
- 拆分大类

**第4-6个月**:
- 引入依赖注入
- 提升测试覆盖率到60%+

**第7-12个月**:
- 持续重构
- 性能优化
- 文档完善

---

## 6. 总体评价

### 优点 ⭐⭐⭐⭐ (4/5)
- 功能完整，覆盖面广
- 架构清晰，分层合理
- 安全意识较强
- 扩展性较好（插件系统）

### 缺点
- 部分类过于庞大
- 缺少抽象层
- 测试覆盖不足
- 技术债务需要偿还

### 建议
这是一个功能完善、架构合理的项目，但在投入大规模生产使用前，建议：

1. **立即修复**安全和并发问题
2. **短期内**引入 Repository 层和拆分大类
3. **中期**引入依赖注入和提升测试覆盖
4. **长期**根据业务规模考虑进一步架构演进

---

## 7. 参考资源

- [Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)
- [Domain-Driven Design](https://martinfowler.com/bliki/DomainDrivenDesign.html)
- [Microservices Patterns](https://microservices.io/patterns/index.html)
- [Refactoring Guru - Design Patterns](https://refactoring.guru/design-patterns)
