# 04 · 插件系统、REST 层、权限系统与 WebSocket 层

> **分析范围**（行号为读取工具返回的实际行号，与 `docs/PROJECT_STRUCTURE.md` 里的旧估算有出入）：
>
> | 包 / 文件 | 文件数 | 行数 |
> |---|---|---|
> | `com.mtxgdn.plugin`（含 `event` / `gui` / `builtin` / `experimental`） | 55 | **8 076** |
> | `com.mtxgdn.rest` | 18 | **9 524** |
> | `com.mtxgdn.permission` | 3 | **993**（`PermissionService` 865 / `PermissionCode` 116 / `RequirePermission` 12） |
> | `com.mtxgdn.server` | 3 | **286** |
> | `com.mtxgdn.websocket.GameWebSocketApp` | 1 | **1 945** |
> | 合计 | **80** | **≈ 20 824** |
>
> 另含 `src/main/resources/plugin-template/`（5 个模板）、`examples/sample-plugin/`（5 个文件）、
> `README_PLUGIN.md`（509 行）。
>
> 说明：任务书给出的估算行数（`PluginManager` 538、`PluginContext` 423、`AdminResource` 3771、
> `GameResource` 2642、`GameWebSocketApp` 1776、`PermissionService` 791）均偏低；实测分别为
> **596 / 482 / 4 101 / 2 962 / 1 945 / 865**。本文全部按实测行号引用。

---

## 1. 插件系统架构

### 1.1 两套并行的"插件"概念

项目里存在**两个互不相通**的插件体系，名字都叫"插件"，但生命周期、注册表、开关来源完全不同：

| 维度 | 外部插件（外部 jar） | 内置玩法模块（BuiltInPlugin） |
|---|---|---|
| 载体 | `./plugins/*.jar` | 编译进主 jar 的类 |
| 管理器 | `PluginManager`（单例） | `BuiltInPluginRegistry`（单例） |
| 基类型 | `interface Plugin`（`Plugin.java:13-23`） | `abstract class BuiltInPlugin implements Plugin`（`BuiltInPlugin.java:13-44`） |
| 清单 | jar 内 `plugin.json` 或 `@PluginMeta` | `PluginCatalog.createAll()`（`PluginCatalog.java:54-107`） |
| 开关 | 运行时 `enablePlugin/disablePlugin`，**不持久化** | `config/plugins.yml` 的 `plugins.<name>`，**持久化** |
| 上下文 | 每插件独立 `PluginContext` + 独立 `PluginClassLoader` | 全部共享一个 `PluginContext`（`Main.java:110-112` 构造） |
| 数量 | 仓库内 `plugins/` 目录为空（0 个） | 31 个实例（25 玩法 + 2 实验性 + 4 消息通道） |
| 门控生效点 | 无（只能靠插件自己判断） | `Command.isModuleEnabled()`、`ModuleGateFilter`、各 `*Service` 内 `isEnabledOrUnknown` 检查 |

**这是本层最重要的架构事实**：真正决定"玩家能不能用某个玩法"的是 `BuiltInPluginRegistry`，
而 `PluginManager` 那套外部插件体系在当前仓库里**一个插件都没加载**，实际处于"能力已建好、
无人使用"的状态。两套体系共享同一个 `Plugin` 接口，但 `PluginManager` 只认 jar 里的实现类，
`BuiltInPluginRegistry` 只认 `PluginCatalog` 里登记过的实例——`PluginCatalog` 的 `createAll()`
从不被 `PluginManager` 调用，反之亦然。

### 1.2 `PluginManager` 生命周期

`PluginManager`（596 行，`PluginManager.java`）是一个饿汉式单例（`INSTANCE`，第 33 行），
所有公共入口都是 `synchronized` 方法（类级锁）。

| 阶段 | 方法 | 行号 | 行为 |
|---|---|---|---|
| 初始化 | `init(File pluginsDir)` | 48-58 | 幂等；目录不存在则 `mkdirs()`；失败只 warn，不抛异常 |
| 扫描加载 | `loadPlugins()` | 61-94 | `listFiles(*.jar)` → 逐个 `loadSingle()` → **再**统一遍历 `loaded.values()` 调 `onLoad()`；返回 `LoadResult(success, failed)` |
| 批量启用 | `enablePlugins()` | 97-101 | 对全部已加载插件调 `enableSingle()` |
| 单启/单停 | `enablePlugin(name)` / `disablePlugin(name)` | 104-131 | `disablePlugin` **只调 `onDisable` 并置 `enabled=false`，不卸载、不清理注册** |
| 卸载 | `unloadPlugin(name)` | 155-160 | `loaded.remove` + `disableSingle`（清理事件、Web、关 ClassLoader） |
| 重载 | `reloadPlugin(name)` | 163-188 | `disableSingle` → `loaded.remove` → `loadSingle(jar)` → `onLoad` → `enableSingle` |
| 热加载单 jar | `loadPlugin(File jar)` | 227-253 | 若已存在同名插件则转 `reloadPlugin`，否则 `loadSingle` + `onLoad` + `enableSingle` |
| 全停 | `disablePlugins()` | 145-152 | 逆序 `disableSingle` 后 `loaded.clear()` |

**`loadSingle` 的六步**（425-476）：

1. `readPluginJson(jar)`（484-505）：依次找 `plugin.json`、`META-INF/plugin.json`，Gson 解析，
   缺字段用默认值（`name` 缺省为 jar 文件名、`version=1.0.0`、`author=匿名`）。
2. 若已有同名插件 → **warn 并直接 `return`（不抛异常）**，但 `loadPlugins()` 仍然 `success++`
   （第 74-81 行按"没抛异常"计成功），**加载统计会虚高**。
3. `new PluginClassLoader(jar, getClass().getClassLoader())`，加入 `classLoaders` 列表。
4. 主类名：优先 `plugin.json` 的 `main`；否则 `findPluginMainClass()`（514-536）遍历 jar 全部
   `.class` 条目，用 `Class.forName(name, false, cl)` 逐个试 `Plugin.class.isAssignableFrom`。
5. `new File(pluginsDir, info.getName())` 作为数据目录（466），`new PluginContext(...)`（467）。
6. `ctx.loadLang()`（470）→ 落库到 `loaded` map。

**`loadPlugins()` 的 `onLoad` 与 `loadSingle` 的 `onLoad` 分离**：`loadPlugins` 在循环结束后
统一调 `onLoad`（85-91），而 `loadPlugin(File)` 在 `loadSingle` 后单独调（243）。两条路径的
`onLoad` 语义一致，但 `loadPlugins()` 里**没有** `enableSingle`，所以启动流程必须是
`loadPlugins()` → `enablePlugins()` 两次调用，与类注释（21-28 行）一致。

#### 热替换文件监听

`startFileWatcher()`（320-386）：

- 单线程 `ScheduledExecutorService`，线程名 `plugin-file-watcher`，`setDaemon(true)`；
- `scheduleWithFixedDelay(..., 3, 3, TimeUnit.SECONDS)`——**固定 3 秒轮询**，不是 `WatchService`；
- 内部维护 `Map<String,Long> lastModified`（**普通 `HashMap`，但只在监听线程内访问，无竞争**）；
- 新增 jar → `Thread.sleep(500)` 后 `loadPlugin(jar)`；
- 已存在且 `lastModified()` 变大 → `Thread.sleep(500)` 后 `reloadPlugin(pluginName)`；
- jar 消失 → `unloadPlugin(pluginName)`；
- `stopFileWatcher()`（389-395）`shutdown()` 后置 `null`；`isFileWatcherRunning()`（397-399）暴露状态。

`AdminResource` 通过 `/api/admin/plugins/watcher/start|stop`（`AdminResource.java:450-472`）
把监听器暴露成 HTTP 开关。

**热替换的四个缺陷**（详见 §8、§9）：

1. `Thread.sleep(500)` 阻塞的是唯一的监听线程，多 jar 同时更新时会串行拖延，且不能防止
   "文件还在写"的竞态——500 ms 对几百 KB 的 jar 够，对大 jar 不够。
2. `reloadPlugin` 是 `synchronized`，但**它清理的对象是全局注册表**（`CommandRegistry`、
   `ItemRegistry` 等），而 `disableSingle`（190-207）只清理了 `PluginEventManager` 和
   `PluginWebManager`——**命令、物品、探索事件、秘境、称号全部残留**。旧 ClassLoader 被
   `close()` 了，但注册表里还握着它的对象实例 → `reloadPlugin` 后旧命令仍可被触发，
   且 `CommandRegistry` 里 `commands.put(name, command)` 会让**新实例覆盖旧实例**
   （因为 `Command` 构造函数自注册），实际表现是"命令被注册了两次、后者生效"。
3. `CommandRegistry` 是 `LinkedHashMap`（`CommandRegistry.java:13`），**非线程安全**；
   监听线程写、Grizzly 工作线程读（`CommandRegistry.get()`），存在
   `ConcurrentModificationException` / 读到半更新状态的窗口。
4. `classLoaders` 列表只在 `closeClassLoader`/`disableSingle` 里移除元素，
   没有"泄漏检测"；`getLoadedPlugins()`（210-212）每次 `new ArrayList<>` 拷贝。

### 1.3 `PluginClassLoader` 隔离机制与局限

全文 67 行（`PluginClassLoader.java`），继承 `URLClassLoader`，构造时 **parent 显式传 `null`**
（第 17 行 `super(new URL[]{jarFile.toURI().toURL()}, null)`），然后自己保存一份 `parent` 字段。

`loadClass` 重写为**四段式**（22-66）：

```
1. findLoadedClass(name)                                   → 命中即返回
2. name 属于"服务端核心命名空间"白名单 → parent.loadClass    → 命中即返回（parent-first）
   白名单：com.mtxgdn. / java. / javax. / jakarta. / sun. /
           org.glassfish. / io.jsonwebtoken. / com.google.gson. /
           org.yaml. / com.zaxxer. / org.mindrot. / com.mysql. / org.sqlite.
3. findClass(name)（从插件 jar 自身）                        → 命中即返回（child-first）
4. parent.loadClass(name) 兜底
```

**局限清单**：

| # | 局限 | 证据 | 影响 |
|---|---|---|---|
| 1 | 白名单是**前缀字符串匹配**，可被"包名近似"绕过 | `:29-35` | 插件在自己的 jar 里放一个 `com.mtxgdnx.*` 或 `com.mtxgdn2.*` 包不会被父类抢走；反过来，插件想自带一个新版本的 `com.google.gson.*` 会被强制用服务端的——这正是意图，但也意味着**插件无法覆盖任何白名单内的库版本** |
| 2 | `com.mtxgdn.*` 下**不存在于服务端**的类，会掉到第 3 步由插件自己定义 | `:36-45` + `:48-56` | 插件可以在 `com.mtxgdn.*` 命名空间里注入**新**类。虽然无法覆盖已有类，但可以定义 `com.mtxgdn.util.XxxUtil` 之类，后续任何 `Class.forName("com.mtxgdn.util.XxxUtil")` 的反射调用都会被劫持 |
| 3 | 无密封（sealing）、无签名校验、无 `SecurityManager` | 全文件 | **插件 = 完全可信代码**。`PluginContext` 直接给出 `Connection`、`AppConfig`、`JwtUtil`、文件系统访问（见 §1.4），恶意 jar 等价于 RCE |
| 4 | 非白名单的第三方库完全 child-first | `:48-56` | 两个插件各自带不同版本的同一库时互不干扰（好），但**同一个类在服务端与插件里出现两份**时会出现 `ClassCastException`（例如插件自带 `slf4j`，服务端用另一个） |
| 5 | `close()` 只是 `URLClassLoader.close()` | `PluginManager.java:205` | 只释放 jar 文件句柄，**不卸载已定义的类**；只要有任一强引用（注册表、线程、静态字段）残留，整个 ClassLoader 及其加载的全部类都无法回收 → 反复热重载必然 OOM（Metaspace） |
| 6 | `findPluginMainClass` 对 jar 内**每个** `.class` 调 `Class.forName(..., false, cl)` | `PluginManager.java:522-529` | 加载全部类元数据（含嵌套依赖），启动慢、可能触发 `UnsupportedClassVersionError`（被 `catch (Throwable)` 吞掉，静默跳过） |

### 1.4 `PluginContext` 提供的能力

`PluginContext`（482 行）是插件与服务端之间**唯一**的正式入口，能力可分五组：

**A. 元数据与文件（35-121）**

| 成员 | 行号 | 说明 |
|---|---|---|
| `getInfo()` | 57 | `PluginInfo`（name/version/author/description/mainClass） |
| `getDataFolder()` | 60-63 | `./plugins/{插件名}/`，**按需 `mkdirs()`** |
| `getClassLoader()` | 66 | 插件自己的 `PluginClassLoader` |
| `getLogger()` | 69 | `GameLogger.getLogger("Plugin[" + name + "]")` |
| `getResource(path)` | 75-77 | `classLoader.getResourceAsStream(path)` |
| `loadLang()` | 88-104 | 扫描 jar 内 `lang/zh_cn.json`、`lang/en_us.json`，`I18nManager.mergeFromStream` |
| `loadConfig(fileName)` | 110-121 | 从**数据目录**读 `.properties`；文件不存在返回空 `Properties`（不抛异常） |

**B. 内容注册（126-202）**——命令 / 物品 / 物品能量值 / 探索事件 / 秘境 / 称号，每个都有 `register*` 与 `unregister*` 成对接口。
注意 `registerCommand`（126-129）只是再调一次 `CommandRegistry.register`，而 **`Command` 的构造函数
已经自注册**（`Command.java:80`），所以文档里"注册命令"的写法是幂等重复调用。

**C. 权限注册（214-234）**——`registerPermission(code,name,category)` 会**写数据库**
`permissions` 表（`PermissionService.java:223-241`），且**不会自动分配给任何角色**。

**D. 事件（244-282）**——`registerHandler` / `registerCustomHandler` / `setHandlersEnabled` / `fireEvent`。

**E. 服务与基础设施（286-381）**

- 20 个 `getXxxService()` 直通 `ServiceRegistry`（286-305）；
- `getDatabaseConnection()`（314-316）——**裸 `java.sql.Connection`**；
- `getServerConfig/Boolean/Int`（319-331）——直读 `application.yml`；
- `checkRateLimit` / `getRateLimitRemaining`（340-347）；
- `isExperimentalFeatureEnabled(key)`（357-359）——委托 `BuiltInPluginRegistry.isModuleEnabled`；
- `getJwtUtil()`（364-366）——返回 `new JwtUtil()`（每次新建实例）；
- `getPlayerActionLogger()` / `getStatsCollector()` / `getGlobalLogger()`（369-381）。

**F. Web 扩展（399-481）**——`registerRestResource(Class<?>)`、`registerWebPage(path,title,html)`、
`registerWebResources(resourceDir)`、`registerWebSocketHandler(type, handler)`、
`registerAdapterFactory` / `unregisterAdapterType`。

`createSharedContext(File, ClassLoader)`（51-54）是给内置插件用的"伪插件"上下文，
`PluginInfo` 硬编码为 `("server","1.0.0","xiuxian","核心服务","Server")`——
**所有内置模块的日志前缀都是 `Plugin[server]`，无法区分是哪个模块打的**。

### 1.5 `PluginCatalog` 清单

`PluginCatalog`（108 行）是**唯一事实来源**，`createAll()`（54-107）按四组顺序 `new` 出 31 个实例：

```
基础玩法（6）  : Exploration, SecretRealm, Trade, Sect, Farm, Cave
社交玩法（4）  : Chat, Friend, Rank, Search
移动/养成（4） : Map, Team, Title, Player
修炼/战斗（5） : Cultivation, Skill, Pvp, WorldBoss, Inventory
经济/制造（6） : Economy, Energy, Technique, Crafting, Buff, Formation
实验性（2）    : SectWar, EnergyExchange
消息通道（4）  : AdapterChannelPlugin × {onebot, qq, minecraft, minecraft-player}
```

`Main.registerBuiltInPlugins`（`Main.java:127-130`）只做 `registry.registerAll(PluginCatalog.createAll())`。
一致性由测试 `src/test/java/com/mtxgdn/plugin/BuiltInModuleSetTest.java` 钉住：
`catalogCoversEveryRegisteredPluginAndTemplateKey()`（101-116）断言
`names.size() == 25 + 2 + 4 == 31`，且每个插件在 `src/main/resources/plugins.yml` 模板里
都有同名开关、模板默认值必须等于 `getDefaultEnabled()`。**这是本层质量最好的部分之一。**

### 1.6 `PluginSwitches` 开关持久化

`PluginSwitches`（382 行）是**全静态**工具类，静态初始化块（69-83）在类首次被引用时完成全部加载：

```
template      = loadClasspathTemplate()           // jar 内 plugins.yml 的平铺键值
externalPath  = resolveConfigPath()               // 默认 config/plugins.yml
if (!exists) extractTemplateFromClasspath()       // 首次启动落盘
legacyExperimentalPath = config/experimental.yml
reload()                                          // values = 模板 ← 外部文件
System.out.println("[PluginSwitches] ...")
```

**取值优先级**（`resolve()`，231-241）：

1. `values.get("plugins." + key)` → `Source.CONFIG_FILE`
2. `legacyValue(key)`：先 `application.yml` 的 `built_in_plugins.<key>`，
   再 `config/experimental.yml` 的 `experimental.<key>` → `Source.LEGACY`
3. 插件自身 `getDefaultEnabled()` → `Source.DEFAULT`

写回：`set(key, enabled)`（283-288）→ `YamlFileEditor.update(...)` **行级替换、保留注释** → `reload()`。
迁移：`migrateLegacyKeys(knownPluginNames)`（305-352）只迁移"旧配置里确实写过的"键。

模板 `src/main/resources/plugins.yml` 共 **31 个开关**，分 6 组注释，与 `PluginCatalog` 一一对应。

**问题**：

- 静态初始化用 `System.out.println` 而非 `GameLogger`（77、80、343），与全项目日志体系不一致；
- `values` 是 `static` 非 `volatile`，`reload()` 换整个 Map 引用；`resolve()` 读取时无同步——
  `set()` 是 `synchronized` 但 `resolve()` 不是，理论上可能读到旧引用（实际无危害，因为 Map 不可变）；
- `getRaw(key)`（211-213）查的是 `values`（含模板默认），所以 `BuiltInModuleSetTest`
  用它校验"模板里有这个键"时，**外部文件写了额外键也会通过**，无法发现模板缺键。

### 1.7 `PluginWebManager`

`PluginWebManager`（400 行）把插件扩展点挂到 Grizzly 上，三类：

| 能力 | 入口 | 挂载点 | 是否鉴权 |
|---|---|---|---|
| JAX-RS 资源 | `registerRestResource(pluginName, Class)`（59-73） | Jersey `ResourceConfig.register(class)`，落在 `/api/` 下 | 走 Filter 链 |
| 静态 HTML 页面 | `registerWebPage(pluginName, path, title, bytes)`（84-92） | `/admin/plugins/{pluginName}/{path}` | **无** |
| jar 内资源目录 | `registerWebResources(pluginName, dir, cl)`（102-…） | `/admin/plugins/{pluginName}/...` | **无** |
| WebSocket 处理器 | `registerWsHandler(pluginName, type, handler)`（129-133） | `GameWebSocketApp` 的 default 分支 | 由插件自理 |

`ensureHttpHandler()`（231-240）用双重检查 + `volatile boolean httpHandlerRegistered` 保证
`/admin/plugins` 的 `HttpHandler` 只注册一次。内部类 `PluginPageHttpHandler`（242-312）
按 `path.substring(1).split("/", 2)` 拆出 `pluginName` 与 `subPath`，先查 `pluginPages`，
再查 `resourceEntries`，最后 404。

`handleWsMessage(socket, type, data)`（140-153）对同一 type 的**全部** handler 逐个调用，
任一抛异常只记录不影响其它；返回 `handled`。

---

## 2. 插件契约与示例

### 2.1 `plugin.json` 字段

`readPluginJson`（`PluginManager.java:484-505`）只认 5 个字段，**全部可选**：

| 字段 | 缺省 | 用途 |
|---|---|---|
| `name` | jar 文件名（去 `.jar`） | **主键**：`loaded` map 的 key、数据目录名、事件归属、Web 注册归属、日志前缀 |
| `version` | `"1.0.0"` | 展示 |
| `author` | `"匿名"` | 展示 |
| `description` | `""` | 展示 |
| `main` | `null` → 触发 `findPluginMainClass` 扫描 | 主类全限定名 |

文件位置：jar 根 `plugin.json` 优先，其次 `META-INF/plugin.json`（`:486-487`）。

`@PluginMeta`（`PluginMeta.java:21-26`）只有 4 个属性：`name()`（**必填**）、`version`、`author`、`description`，
**没有 `main`**（主类由类本身确定，合理）。注解只在"没有 plugin.json"的回退路径里被读
（`buildPluginInfoFromClass`，538-551）。

### 2.2 `Plugin` 接口

`Plugin.java`（23 行）三个 `default` 空实现：`onLoad(PluginContext)` / `onEnable(PluginContext)` /
`onDisable(PluginContext)`。**没有 `getName()`、没有依赖声明、没有配置 schema、没有 reload 回调**。
这是它与 Bukkit/Sponge 式插件接口最大的差距——元数据完全外置到 json/注解。

### 2.3 示例插件 `examples/sample-plugin/`

| 文件 | 内容 | 与实现是否一致 |
|---|---|---|
| `plugin.json` | `name=示例插件, version=1.0.0, author=开发者, main=com.example.myplugin.MyPlugin` | ✅ 与 `readPluginJson` 完全匹配 |
| `pom.xml` | Java 23、`finalName`、`plugin.json` 打进 jar 根 | ⚠️ **`<dependencies>` 是空的**，`mvn package` 必然编译失败（见 §2.5） |
| `MyPlugin.java` | `@PluginMeta` + 三生命周期；`onEnable` 里 `registerCommand` / `registerItem` | ✅ |
| `command/HelloCommand.java` | 4 参 `super(names, desc, usage, category)`；`ctx.requireBinding()` / `ctx.requirePlayer()` / `itemService.addSpiritStones` | ✅ 与 `Command.java:93-95` 的 4 参构造匹配 |
| `item/DemoItem.java` | 9 参 `Item` 构造；`use(long playerId)` 覆写 | ✅ 与 `plugin-template/DemoItem.java.template` 一致 |

### 2.4 模板生成（`PluginMaker` / `CodeGenerator` / `PluginMakerGUI`）

**三条独立入口**，由 `Main.java:63-69` 分派：

```
--plugin-make-gui  →  PluginMakerGUI.launch()   Swing 图形化
--plugin-make      →  new PluginMaker().run()   控制台问答
（无参数）          →  正常启动服务器
```

| 组件 | 行数 | 定位 |
|---|---|---|
| `PluginMaker` | 258 | 控制台 8 问向导（名称/版本/作者/描述/artifactId/groupId/主类/输出目录），占位符替换 5 个模板文件 |
| `PluginMakerGUI` | 437 | Swing JFrame，5 个 Tab（基础配置/物品/事件/指令/秘境/生成），日志面板 |
| `CodeGenerator` | **1 118** | 把 `PluginConfig` 渲染成完整项目（pom/plugin.json/主类/lang json/物品/事件/指令/秘境/README） |
| `PluginConfig` | 197 | GUI 状态 + `MiniJson` 持久化 |
| `RegistrableEntry` | 184 | 一个"可注册项"（type/key/name/description/extraInfo/triggers/enabled） |
| `Trigger` | 171 | 触发器（时机 + 动作 + 参数） |
| `EntriesPanel` | 433 | 注册项列表编辑面板 |
| `TriggerEditorDialog` | 216 | 触发器编辑对话框 |
| `BasicConfigPanel` | 212 | 基础信息面板 |
| `MiniJson` | 301 | 手写 JSON 解析/序列化（**不依赖 Gson**） |
| `Theme` | 362 | Swing 主题（配色/字体） |
| `CodeGeneratorTest` | 157 | **放在 `src/main/java` 里的测试**（见 §9） |
| `PluginMakerSelfTest` | 95 | 同上，`main()` 形式的手工自测 |

`PluginMaker` 用 5 个 `*.template`（`pom.xml.template` / `plugin.json.template` /
`Main.java.template` / `HelloCommand.java.template` / `DemoItem.java.template`），
占位符 `{{NAME}} {{VERSION}} {{AUTHOR}} {{DESCRIPTION}} {{ARTIFACT_ID}} {{GROUP_ID}}
{{PACKAGE}} {{PACKAGE_PATH}} {{MAIN_CLASS}} {{SERVER_VERSION}}`。
`SERVER_VERSION` 来自 `BuildInfo.getVersion()`（读 `build-info.properties`），
**纯版本号**（不加 `v` 前缀），否则 Maven 坐标装不上——`PluginMaker.java:39-44` 的注释明确说明了这一点。

`CodeGenerator` 与 `PluginMaker` 是**两套不共用模板的生成器**：前者自己用 `StringBuilder`
拼 Java 源码（1 118 行里绝大部分是拼接），后者只做模板替换。两者生成的主类结构不同
（`CodeGenerator` 生成的注册项用 `static register(PluginContext)` 工厂 + `KEY/FULL_KEY` 常量），
但都遵循同一条原则：**构造器已自注册，不要再调 `context.registerXxx`**——`CodeGenerator.java:34-37`
的类注释把这条规则写得很清楚，且**明确说明指令是例外**（`CommandRegistry` 重复注册幂等，
所以额外调一次以保持与示例插件一致）。

### 2.5 文档与实现的一致性核对（`README_PLUGIN.md`）

| # | 文档说法 | 实现 | 结论 |
|---|---|---|---|
| 1 | 生命周期 `onLoad → onEnable → onDisable`（§二） | `Plugin.java:16-22` | ✅ |
| 2 | `plugin.json` 在 jar 根或 `META-INF/plugin.json`（§3.2） | `PluginManager.java:486-487` | ✅ |
| 3 | 无 `plugin.json` 时扫描 jar 内所有 `Plugin` 实现类 + 读 `@PluginMeta`（§3.2） | `findPluginMainClass` + `buildPluginInfoFromClass` | ✅ |
| 4 | 依赖方式 A（`mvn install:install-file` + `provided`）（§3.4） | `plugin-template/pom.xml.template` 用的正是方式 A | ✅ |
| 5 | 依赖方式 B（`system` scope + `systemPath`）（§3.4） | **无任何文件使用** | ⚠️ 文档承诺但示例未给 |
| 6 | 示例插件可直接 `mvn package`（§十四） | `examples/sample-plugin/pom.xml` 的 `<dependencies>` **为空** | ❌ **示例插件编译不过**，README 未提示需要先补依赖 |
| 7 | `context.getGuideService()` 返回 `GuideService`（§九） | 实际返回 `NewbieGuideService`（`PluginContext.java:302`） | ⚠️ 类型名不符 |
| 8 | 事件系统 | README **全文未提** `registerHandler` / `PluginEvent` / `PluginEventManager` | ❌ 重大文档缺口 |
| 9 | 权限注册 | README **未提** `registerPermission` | ❌ 缺口 |
| 10 | Web 扩展（REST 资源 / 管理页 / WebSocket 处理器 / 适配器工厂） | README **未提** | ❌ 缺口（4 个 API 完全无文档） |
| 11 | i18n（`lang/*.json` 自动合并） | README **未提** | ❌ 缺口 |
| 12 | 内置插件 / `plugins.yml` | README **未提**（只说"外部插件"） | ❌ 缺口：读者会误以为 `BuiltInPlugin` 不存在 |
| 13 | 类注释 `BuiltInPlugin.java:9` 说开关是 `config/plugins.yml` 的 `plugins.<name>` | ✅ 正确 | — |
| 14 | **24 个 `builtin/*Plugin.java` 的类注释写着 `配置键: built_in_plugins.<name>`（默认 true）**，`AdapterChannelPlugin.java:13` 同样写 `built_in_plugins.<type>` | 实际键是 `plugins.<name>`；`built_in_plugins.*` 已降级为**只读兜底**（`PluginSwitches.java:50,236-239`） | ❌ **25 处过时注释**；仅 `WorldBossPlugin`、`SectWarPlugin`、`EnergyExchangePlugin` 三个写对了 `plugins.*` |
| 15 | `docs/i18n-design.md:102` 说 `LanguageRequestFilter` 优先级是 `Priorities.AUTHENTICATION + 10`，"排在 JwtAuthFilter 之后" | 代码是 `AUTHENTICATION - 10`（`LanguageRequestFilter.java:38`），注释（32-35 行）说必须**先于** JwtAuthFilter | ❌ 文档与代码相反 |
| 16 | `docs/PROJECT_STRUCTURE.md:672-673` 说 `PermissionFilter` 77 行、`JwtAuthFilter` 72 行 | 实测 91 / 85 行 | ⚠️ 轻微过时 |

---

## 3. 事件系统

### 3.1 `PluginEvent` 类型

`PluginEvent`（108 行）是**单一具体类**，不是事件类层次——所有事件共用一个 `Map<String,Object> data` 袋子：

```
enum Type { COMMAND, PLAYER_LOGIN, PLAYER_LOGOUT, ITEM_USED, COMBAT_ENDED,
            EXPLORATION_START, EXPLORATION_END, SCHEDULED, SERVER_READY, CUSTOM }   // 24-45
```

- `type` / `customKey` / `data` / `timestamp` 全部 `final`（`timestamp` 在构造时取 `System.currentTimeMillis()`）；
- `cancelled` 是**普通 `boolean`**（51），`cancel()`（84）只置位；
- `get(key)`（69-71）是无检查的 `(T) data.get(key)`——**类型不安全，调用方写错泛型会在运行时 `ClassCastException`**；
- `set(key, value)`（77-81）**静默忽略 `null` 值**，无法用 `null` 覆盖已有值；
- `Builder`（95-107）默认 `type = CUSTOM`、`customKey = ""`。

### 3.2 `PluginEventManager` 分发模型

`PluginEventManager`（209 行）单例，三个索引：

```
Map<Type, List<HandlerEntry>>   handlersByType    // EnumMap，每 Type 一个 CopyOnWriteArrayList
Map<String, List<HandlerEntry>> handlersByKey     // ConcurrentHashMap，CUSTOM 的细粒度订阅
List<HandlerEntry>              allHandlers       // CopyOnWriteArrayList，统计/开关用
```

注册 API：`register(pluginName, type, condition, [priority], action)`（60-74）、
`registerCustom(pluginName, customKey, condition, [priority], action)`（77-89）。
优先级常量：`PRIORITY_NORMAL = 0` / `PRIORITY_HIGHEST = Integer.MAX_VALUE` / `PRIORITY_LOWEST = Integer.MIN_VALUE`（47-51）。

**同步 / 异步**：`fire()` **完全同步**，在调用者线程上按 priority 降序
（`Comparator`，见第 6 行 import）遍历 `handlersByType.get(type)` 逐个 `action.handle(event)`。
**没有任何异步队列、没有超时、没有线程池**。

**异常隔离**：单个 handler 抛异常被 `try/catch` 捕获并 `LOG.error`，不影响后续 handler；
`fireQuietly(...)` 是"不记录异常"的静默版本（`GameWebSocketApp.java:181-186` 用它发 `PLAYER_LOGIN`）。

**取消语义**：`PluginEvent.cancel()` 置位后，`fire()` 在每轮循环前检查 `event.isCancelled()` 并跳出——
但**已经执行过的 handler 无法回滚**，所以取消只对"后续 handler"有效。

**监听器注册的两条路径**：

1. 插件：`PluginContext.registerHandler(...)`（`PluginContext.java:244-258`）→ 自动带 `info.getName()` 作为归属；
2. 内置代码：直接 `PluginEventManager.getInstance().register(...)` 或静态 `fireQuietly`。

**清理**：`unregisterAll(pluginName)`（由 `PluginManager.disableSingle` 调用，`PluginManager.java:198`）
按 `pluginName` 从三处索引移除。

### 3.3 事件系统的四个问题

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| 1 | **同步阻塞**：handler 在调用者线程（通常是 Grizzly 工作线程或 OneBot 收包线程）执行，无超时 | `PluginEventManager` `fire()` | 一个慢插件 handler 直接拖慢整条消息链路；恶意/低质插件可拖垮服务器 |
| 2 | **`condition` 只存不解析**：`register` 把 `condition` 存进 `HandlerEntry`，但 `fire()` 里对它的处理是字符串 `split`/`equals` 级别的粗匹配，格式只有 `"command=/你好"` 一种 | `:70`（存储）/ `fire()`（匹配） | 号称"表达式"，实际是单键值对；`README_PLUGIN.md` 完全没写格式，插件作者只能读源码 |
| 3 | **每次注册都 `LOG.info`** | `:73`、`:88` | 插件多的服启动日志被刷屏 |
| 4 | **`setEnabled` 按 (pluginName, type) 批量改**，无法精确到单个 handler | `:92-…` | 同一插件注册两个 COMMAND handler 时无法单独关掉一个 |

---

## 4. 内置玩法模块（builtin 26 + experimental 2）

### 4.1 逐模块清单

> "依赖服务"列取自各模块 `getDescription()` 宣称的玩法，以及实际门控点
> （`grep isEnabledOrUnknown`）所在的服务类。

| # | 类名 | `getName()` | 玩法（description） | 实际依赖服务 / 门控点 |
|---|---|---|---|---|
| 1 | `ExplorationPlugin` | `exploration` | 游历探索：随机事件、冒险奖励 | `ExplorationService`（`GameResource` `/exploration`） |
| 2 | `SecretRealmPlugin` | `secret_realm` | 秘境探索：多层次秘境 | `SecretRealmService` |
| 3 | `TradePlugin` | `trade` | 坊市交易：挂单、购买、流通 | `TradeService`；`ModuleGateFilter` 把 `/game/market/*` 映射到 `trade` |
| 4 | `SectPlugin` | `sect` | 宗门：建宗、仓库、战争 | `SectService`（`SectService.java:2193` 门控 `sect`） |
| 5 | `FarmPlugin` | `farm` | 农场：种植灵药、浇灌、收获 | `FarmService.java:603`、`FarmLazySettlementTest:63` |
| 6 | `CavePlugin` | `cave` | 洞府：灵力收集、洞府修炼 | `CaveService` |
| 7 | `ChatPlugin` | `chat` | 聊天：世界频道、私聊 | `ChatService` |
| 8 | `FriendPlugin` | `friend` | 好友：添加、列表 | `FriendService` |
| 9 | `RankPlugin` | `rank` | 排行：排行榜 | `PlayerService.getTopBy*` |
| 10 | `SearchPlugin` | `search` | 搜索：按名搜索玩家 | `PlayerService.searchPlayersByName` |
| 11 | `MapPlugin` | `map` | 地图：查看、移动旅行 | `MapService`（含 `reload()` 热重载缓存） |
| 12 | `TeamPlugin` | `team` | 团队：创建、组队副本 | `TeamService`；`/game/raid/*` 也映射到 `team` |
| 13 | `TitlePlugin` | `title` | 称号：成就、装备 | `TitleService` + `TitleRegistry` |
| 14 | `PlayerPlugin` | `player` | 角色：状态、疗伤、灵根 | `PlayerService.java:1374` 门控 `player` |
| 15 | `CultivationPlugin` | `cultivation` | 修炼：打坐、突破、转世、天象晨修 | `RealmService`；`SeasonService.java:55` 门控 `cultivation` |
| 16 | `SkillPlugin` | `skill` | 技能：学习、列表、战斗策略 | `SkillService` |
| 17 | `PvpPlugin` | `pvp` | 战斗：挑战、决斗 | `CombatService` |
| 18 | `WorldBossPlugin` | `world_boss` | 世界 Boss：全服共享血量、按伤害分红、超发熔断 | 世界 Boss 服务（`WorldBossService`） |
| 19 | `InventoryPlugin` | `inventory` | 背包/装备：物品、装备、强化 | `ItemService` / `EnhanceService` |
| 20 | `EconomyPlugin` | `economy` | 经济：拍卖、灵庄、商店、签到、灵石流转 | `EconomyService`（含拍卖到期结算定时任务） |
| 21 | `EnergyPlugin` | `energy` | 能量：物品转化能量 | `EnergyService`（`registerItemEnergy`） |
| 22 | `TechniquePlugin` | `technique` | 功法：学习、装备、升级 | `TechniqueService` |
| 23 | `CraftingPlugin` | `crafting` | 炼丹锻造：配方、炼制 | `CraftingService` |
| 24 | `BuffPlugin` | `buff` | Buff：增益效果 | `BuffService`（内有 `isEnabledOrUnknown("buff")`） |
| 25 | `FormationPlugin` | `formation` | 阵法：阵法加成 | `FormationService.java:210` |
| 26 | `AdapterChannelPlugin` ×4 | `onebot` / `qq` / `minecraft` / `minecraft-player` | 消息通道**类型级总开关** | `AdapterManager.startType/stopType`；实例级 `enabled` 仍在 `config/adapters.json` |
| 27 | `SectWarPlugin`（experimental） | `experimental_sect_war` | 实验性：宗门战（REST `POST /api/game/sect/war/{sectId}`、WS `sect_war`） | `SectService.java:1004`、`ModuleGateFilter.java:95-103`；**默认 false**，`version=0.1.0` |
| 28 | `EnergyExchangePlugin`（experimental） | `experimental_energy_exchange` | 实验性：能量转化/等价交换（物品⇄能量） | `EnergyService`；`version=0.1.0`，**默认 true**（沿用旧 `experimental.yml`） |

### 4.2 关键发现：25 个玩法模块**全是空壳**

**除 `AdapterChannelPlugin` 之外**，`builtin/` 下 25 个类 + `experimental/` 下 2 个类的
`onEnable` / `onDisable` **全部只做日志输出**（`ExplorationPlugin.onEnable` 多一行注释，
`WorldBossPlugin` 等完全只有一行）。例如 `BuffPlugin.java`：

```java
@Override public void onEnable(PluginContext context) {
    context.getLogger().info("Buff系统已启用");
}
```

**它们不注册任何东西，也不持有任何状态。** 真正的门控逻辑散落在三处：

1. `Command.isModuleEnabled()`（`Command.java:130-133`）——指令分发时检查；
2. `ModuleGateFilter`（117 行）——HTTP 请求时按路径首段检查；
3. 各 `*Service` 内部的 `isEnabledOrUnknown("xxx")` 早退——**业务层兜底**，
   分布在 `SectService`（2 处）、`SeasonService`、`PlayerService`、`FormationService`、
   `FarmService`、`BuffService` 等至少 7 个服务里（`grep` 实测 11 处调用点）。

**所以"内置插件"实际上是一个纯粹的配置开关层，而不是模块化边界。** 玩法代码
（约 3 万行 `game/service`）与插件框架**完全没有解耦**——关掉 `farm` 只是让
`FarmService` 里的某个方法提前 `return`，`FarmService` 本身仍被 `ServiceRegistry` 实例化、
仍被 `GameWebSocketApp` 静态持有、仍被 `GameResource` 静态持有。

### 4.3 与 `com.mtxgdn.game.service` 的边界评估

| 期望的模块化边界 | 实际状况 | 评价 |
|---|---|---|
| 模块自带业务逻辑 | 全部在 `game/service`，模块是空壳 | ❌ **边界不存在** |
| 模块可独立启停而不影响其它模块 | 停用只影响入口拦截；`ServiceRegistry` 仍全量初始化 | ⚠️ 部分 |
| 模块声明自己依赖的服务 | `BuiltInPlugin` 无依赖声明机制 | ❌ 缺失 |
| 模块可被第三方替换 | 不可（编译期绑定） | ❌ |
| 模块开关单一事实来源 | `PluginCatalog` + `plugins.yml` + `BuiltInModuleSetTest` 三方锁定 | ✅ **做得很好** |
| 未知模块名不误杀 | `isEnabledOrUnknown` 对未注册名返回 `true`（`BuiltInPluginRegistry.java:167-170`） | ✅ 设计正确，但**拼错模块名会静默失效**（`ModuleGateFilter` 的 `Map.ofEntries` 里一个 typo 就让该路径永久不受门控） |

`ModuleGateFilter.MODULE_BY_FIRST_SEGMENT`（`ModuleGateFilter.java:29-71`）是**硬编码的 35 条映射**
（`Map.entry(...)` 实测 35 条），把 URL 首段映射到模块名。这张表与 `PluginCatalog`
**没有任何自动一致性校验**——新增一个玩法模块时，如果忘了往这张表里加映射，
该模块的所有 HTTP 接口就永久绕过门控。这是 `BuiltInModuleSetTest` 没覆盖到的盲区
（测试只校验 `PluginCatalog` ↔ `plugins.yml`）。

---

## 5. REST 层详解

### 5.1 Jersey / Grizzly 装配

```java
// Main.java:207
ResourceConfig config = new ResourceConfig().packages("com.mtxgdn.rest");
// Main.java:230
PluginWebManager.getInstance().init(server, config);
// Main.java:271,276
server.getServerConfiguration().addHttpHandler(new AdminStaticFileHandler("/admin", "webadmin"), "/admin");
server.getServerConfiguration().addHttpHandler(new AdminStaticFileHandler("/install", "install"), "/install");
```

- Jersey base URI 是 `/api/`（`GrizzlyHttpServerFactory.createHttpServer(URI.create(.../api/), config)`），
  所以**所有 `@Path` 都要加 `/api` 前缀**：`@Path("/game")` → `/api/game/*`。
- **没有 `JacksonFeature`、没有显式 `register(...)`**——全部靠 `@Provider` + 包扫描；
  序列化用手写 Gson（各资源类各持一份 `private static final Gson gson = new Gson()`）。
- `/admin` 与 `/install` 的静态资源**不在 Jersey 管辖范围内**（是 Grizzly `HttpHandler`），
  因此**不经过任何 Filter**。

### 5.2 资源类分组表

> "端点数"= 方法级 `@Path` 的数量（不含类级 `@Path`）。实测方式：
> 统计 `^\s+@Path\("` 出现次数，并用 `@GET/@POST/@PUT/@DELETE` 交叉核对。

| 文件 | 行数 | 端点数 | 方法分布 | 路径前缀 | 鉴权 |
|---|---|---|---|---|---|
| `GameResource.java` | **2 962** | **107** | GET 42 / POST 65 | `/api/game` | 玩家 JWT（`JwtAuthFilter`）+ 102 个 `@RequirePermission` |
| `AdminResource.java` | **4 101** | **100** | GET 38 / POST 46 / PUT 1 / DELETE 15 | `/api/admin` | 管理员 JWT（`AdminAuthFilter`）+ 94 个 `@RequirePermission` |
| `Auth.java` | 453 | **8** | POST 7 / DELETE 1 | `/api/auth` | 白名单（`JwtAuthFilter.java:25`）+ 方法内自查 token |
| `InstallResource.java` | 471 | **4** | GET 1 / POST 3 | `/api/install` | 白名单 + `install.allow_remote` 本机限制 |
| `OAuthResource.java` | 219 | **6** | GET 3 / POST 3 | `/api/auth/oauth` | 白名单 + `OAuthService` 客户端凭据 |
| `UnifiedRestResource.java` | 220 | **2** | GET 1 / POST 1（`{path:.*}` 通配） | `/api/game` | 路由自带权限码（`RouteDefinition.getPermission()`） |
| `AdminPlayerDataResource.java` | 113 | **5** | GET 3 / POST 2 | `/api/admin/player-data` | `admin.database.clear_players`（5/5） |
| `McCommandResource.java` | 89 | **1** | POST 1 | `/api/mc-command` | **完全无鉴权**（见 §8） |
| `Test.java` | 16 | **1** | GET 1 | `/api/test` | 需玩家 JWT（V9 已移除白名单） |
| **合计** | | **234** | | | |

**过滤器 / 异常映射（7 + 2）**：

| 文件 | 行数 | 类型 | `@Priority` |
|---|---|---|---|
| `LanguageRequestFilter` | 90 | 请求 | `AUTHENTICATION - 10` = **900** |
| `AdminAuthFilter` | 387 | 请求 | `AUTHENTICATION` = **1000** |
| `ModuleGateFilter` | 117 | 请求 | `AUTHORIZATION - 50` = **1950** |
| `PermissionFilter` | 91 | 请求 | `AUTHORIZATION` = **2000** |
| `EntityBufferFilter` | 41 | 请求 | `ENTITY_CODER - 100` = **3900** |
| `JwtAuthFilter` | 85 | 请求 | **无 `@Priority`** → JAX-RS 默认 `USER` = **5000** |
| `LanguageCleanupFilter` | 31 | 响应 | `HEADER_DECORATOR` = 3000 |
| `GenericExceptionMapper` | 20 | `ExceptionMapper<Exception>` | — |
| `EofExceptionMapper` | 18 | `ExceptionMapper<EOFException>` | — |

### 5.3 Filter 链的顺序与语义（**本节最关键**）

按 JAX-RS 规范（未标注 `@Priority` 的过滤器默认 `Priorities.USER = 5000`），实际执行顺序是：

```
900  LanguageRequestFilter   → 协商语言，I18nContext.set(lang)
1000 AdminAuthFilter         → /admin/* 管理员 JWT（含 /admin/login）
1950 ModuleGateFilter        → /game/* 按首段查内置模块开关，停用则 403
2000 PermissionFilter        → 读 @RequirePermission，查 PermissionService
3900 EntityBufferFilter      → 把 Grizzly POST body 读成 byte[] 重新塞回 entityStream
5000 JwtAuthFilter           → 解析玩家 Bearer token，setProperty("userId"/"token")
（响应）3000 LanguageCleanupFilter → I18nContext.clear()
```

#### 🔴 高危缺陷：`JwtAuthFilter` 排在 `PermissionFilter` **之后**

`PermissionFilter`（`PermissionFilter.java:65-80`）从 `requestContext.getProperty("userId")`
（或 `adminUserId`）取用户 ID，取不到就 `abortWith(401 "未登录")`。
而 `userId` 正是 `JwtAuthFilter` 在 `:69-70` 写入的：

```java
requestContext.setProperty("token", token);
requestContext.setProperty("userId", JwtUtil.extractUserId(token));
```

`JwtAuthFilter` **只有 `@Provider`，没有 `@Priority`**（`JwtAuthFilter.java:10-11`），
因此它排在 5000，晚于 2000 的 `PermissionFilter`。

**后果**：`GameResource` 里 102 个带 `@RequirePermission` 的端点，在 `PermissionFilter`
执行时 `userId` 恒为 `null` → **一律返回 401 "未登录"**。

**为什么线上没被发现**（推测，但逻辑自洽）：

- 游戏真实流量走 **OneBot 指令**与 **WebSocket**，`GameResource` 的 REST 只是辅助面；
- `/api/admin/*` 由 `AdminAuthFilter`（1000）负责，它在 `PermissionFilter` **之前**就把
  `adminRole` / `adminUserId` 塞进 property（`AdminAuthFilter.java:212-214`），所以
  **管理后台的 94 个 `@RequirePermission` 端点工作正常**；
- 现有测试（`AdminWritePermissionTest`、`EndpointAlignmentTest`）全是**反射/源码级静态断言**，
  没有任何一个用 Mock 容器或真实 HTTP 请求跑通"登录 → 调受保护端点"这条链。

**建议**：给 `JwtAuthFilter` 加 `@Priority(Priorities.AUTHENTICATION + 10)`（= 1010），
并补一个 `JerseyTest` 端到端用例。**这是本层最应该优先验证和修复的一条。**

> 备注：本节结论来自静态分析（JAX-RS 3.1 规范 + `@Priority` 缺席）。
> 由于任务限制不能构建运行，未做运行时验证，请在修复前用一个集成测试确认。

#### 各 Filter 语义细节

**`LanguageRequestFilter`（900）**——四级协商，第一个命中即用（`negotiate()`，54-89）：

1. `?lang=` 查询参数
2. `X-Lang` 请求头
3. `Accept-Language`（按 `,` 分割、按 `;` 截 `q`，**刻意不用 `split(";")[0]` 以避免 `";;,,"` 触发 AIOOBE**，71-79 行注释说明）
4. `I18nManager.serverDefault()`

任何异常都静默落到默认语言（46-49）。**刻意不读玩家 `players.lang`**（24-27 行注释）——因为需要 `userId`，
而本过滤器必须跑在 `JwtAuthFilter` 之前（否则 401 的错误信息没法本地化）。这个取舍是对的。
`LanguageCleanupFilter`（`HEADER_DECORATOR`）在响应阶段 `I18nContext.clear()`，防止线程池串味。

**`AdminAuthFilter`（1000）**——387 行，是整个鉴权体系最厚的一层：

- `ADMIN_KEY` 静态初始化：优先 `admin.jwt_secret`（要求 ≥32 字节，不足则告警并降级）；
  否则从 `jwt.secret` 用 HMAC-SHA256 做**域分离派生**（确定性，重启后 token 仍有效）；
  两者都空才随机生成（重启失效）；
- `filter()`（162-227）**只处理 `path.startsWith("admin/")`**（166-168），其它路径直接 `return`；
- 登录失败锁定：`LOGIN_FAILURES`（`ConcurrentHashMap`），**5 次失败锁 15 分钟**（43-45）；
- `ADMIN_TOKEN_EXPIRY = 12 小时`（46）；
- 路径分类：`admin/login`（POST）直接放行（173-175）；`admin/energy/` 走"双鉴权"（177）；
  其余 `/admin/*` 必须带合法 admin JWT；
- `tryAcceptPlayerSelf`（235-248）允许玩家 JWT 访问 `admin/me`、`admin/whoami`，
  只写 `userId`、**不授予任何 admin 权限**；
- **`getAdminRole()` 第一行就是 `if (!ADMIN_USERNAME.isEmpty()) return "super_admin";`**（358-361）。

**`ModuleGateFilter`（1950）**——35 条首段映射（`Map.entry(...)` 实测 35 条）；`/game/player/data/*` 显式豁免（`:89-92`）；
`/game/sect/war/*` 单独走 `experimental_sect_war` 开关（`:94-103`）；
未映射的路径直接放行（`:105-108`）。403 响应体是硬编码 JSON 字符串（`:73-77`），**不走 i18n**。

**`PermissionFilter`（2000）**——`ResourceInfo` 注入失败时**拒绝**而非放行（S2 修复，28-36）；
方法级注解优先于类级（47-52）；`super_admin` 直接放行（60-63，**这是唯一的权限短路**）；
无 `userId` → 401；`hasPermission` 失败 → 403 且响应体带权限码（便于排障，但也泄露权限码命名）。

**`EntityBufferFilter`（3900）**——把 Grizzly 的 POST/PUT body 预读成 `byte[]`
（`MAX_POST_SIZE = 65536`，第 17 行）再塞回 `setEntityStream`，解决 Jersey + Grizzly 下
"body 被提前消费"的问题。**上限 64 KB**——超出部分被静默截断，不会报 413。
每次请求都 `System.out.println("[EntityBuffer] 缓冲 N bytes")`（第 34 行），**生产环境刷屏**。

**`JwtAuthFilter`（5000）**——白名单按**路径段**匹配（`isBypassPath`，79-84，V9 修复）：
`auth` / `install` / `mc-command` 完全放行；`admin` 放行但 `admin/energy` 例外（"双鉴权"）。
校验 `TokenBlacklist`（52-57）→ `JwtUtil.validateToken`（59-67）→ 写 property（69-70）。

**`AdminStaticFileHandler`（非 Jersey）**——把 classpath 内 `webadmin/` 映射到 `/admin`，
`install/` 映射到 `/install`；`Cache-Control: no-cache, no-store, must-revalidate`；
MIME 表 14 项；`p` 为空或 `/` 时回落到 `/index.html`。**没有任何鉴权**。

### 5.4 异常映射与统一响应格式

**异常映射只有两个**，覆盖极不完整：

| Mapper | 覆盖 | 返回 |
|---|---|---|
| `EofExceptionMapper` | `EOFException` | 400 `{"code":400,"message":"请求体为空"}` |
| `GenericExceptionMapper` | `Exception`（兜底） | 500 `{"code":500,"message":"服务器内部错误，请稍后再试"}`，并 `LOG.error` 全栈 |

**缺失**：没有 `WebApplicationException` mapper（`GameResource.getCurrentUserId()` 抛的
`new WebApplicationException("未登录", 401)` 会走 Jersey 内置 mapper，**响应体是 HTML 错误页而非 JSON**）、
没有 `JsonSyntaxException` / `BadRequestException` / `NotFoundException` mapper。

**两套并存的响应格式**——这是 REST 层最大的不一致：

**① `GameMessage`（`common/GameMessage.java`，215 行）**——字段 `msgId / type / code / key / message / data`：

```java
// WS 出口
GameMessage.ok(msgId, type, message, data)     // code=0
GameMessage.error(msgId, type, GameErrorCode)  // code=错误码, key=错误码 key, message=当前语言译文

// REST 出口（返回裸 JsonObject，不带 msgId/type）
GameMessage.restOk(message, data)   → {"code":0,"key":"common.ok","message":...,"data":{...}}
GameMessage.restOk(key, message, data)
GameMessage.restError(GameErrorCode)
GameMessage.restError(code, message)
GameMessage.restError(code, key, message)
```

`code == 0` 表示成功（`isOk()`，103-105）——**不是 HTTP 200**。

**② 手写 `JsonObject`**——`AdminResource` 里 100 个端点几乎全部用
`result.addProperty("code", 200); ...`，**成功码是 200 而不是 0**，且**几乎全部
`Response.ok(...)` 返回 HTTP 200**，错误也只是 `code:400/403/404/500` 写在 body 里。

| 资源 | 成功 code | 失败 code | HTTP 状态 |
|---|---|---|---|
| `GameResource` | **0** | 400/403/… | 恒 200（除 `WebApplicationException`） |
| `AdminResource` | **200** | 400/404/500 | 恒 200（`login` 例外，用真实 401/403） |
| `Auth` | — | 400/403/401 | **真实 HTTP 状态**（`Response.status(400)`） |
| `InstallResource` | `success:true` | `success:false` | **真实 HTTP 状态**（400/403/500） |
| `OAuthResource` | RFC 6749 格式 | `{"error":...,"error_description":...}` | **真实 HTTP 状态** |

**三套风格并存**：`GameMessage`（code 0/非 0 + HTTP 200）、`AdminResource`（code 200/非 200 + HTTP 200）、
`Auth/Install/OAuth`（真实 HTTP 状态码）。客户端必须按路径分别适配，这是实际的可维护性负担。

`common/ApiResponse.java`（80 行）定义了第三种 `{code, message, data}` 结构，
但 **`grep` 实测在 `com.mtxgdn.rest` 里零引用**——是纯客户端辅助类，服务端从未使用。

### 5.5 安装向导（`InstallResource`，471 行）

**安装状态判定**（`isInstalled()`，60-62）——双信号：

1. `config/.installed` 锁文件存在（V7 修复引入，**一次写入永不删除**，`writeInstallLock()` 80-91）；
2. 兼容旧版：`application.yml` 的 `jwt.secret` 非空（`hasJwtSecretConfigured()`，93-…）。

设计意图（44-50 行注释）很清楚：**防止有人清空 `jwt.secret` 后重新打开 `/configure` 篡改数据库连接串**。

**4 个端点**：

| 方法 | 路径 | 鉴权 |
|---|---|---|
| `GET` | `/api/install/status` | **无**（`JwtAuthFilter` 白名单），**始终可访问** |
| `POST` | `/api/install/configure` | 仅未安装时可用 + 本机回环 |
| `POST` | `/api/install/reset` | 需当前管理员密码 + 本机回环 |
| `POST` | `/api/install/restart` | **无任何凭据校验** + 本机回环 |

**本机限制**：由 `install.allow_remote`（`application.yml:77-94`）控制，默认只接受
`127.0.0.1 / ::1 / localhost`。

**`configure` 的能力**：写 `application.yml`（数据库连接串、`server.host/port`、管理员账号密码、
`jwt.secret`）。`jwt.secret` 若用户不填，用 `SecureRandom` 生成 **≥48 字节（384 位）** base64
（`JWT_SECRET_MIN_BYTES = 48`，第 35 行）。

### 5.6 OAuth 2.0（`OAuthResource`，219 行）

**6 个端点**，`@Path("/auth/oauth")`，落在 `JwtAuthFilter` 的 `auth` 白名单内：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/token` | 只支持 `grant_type=client_credentials`；`OAuthService.validateClient(id, secret)` |
| `POST` | `/introspect` | **完全无鉴权**——任何人可拿任意 token 字符串做内省 |
| `GET` | `/me` | 需 OAuth Bearer token（scope `read`） |
| `GET` | `/protected/status` | 需 `read` scope |
| `POST` | `/protected/echo` | 需 `write` scope |
| `GET` | `/authorize` | **返回 501 Not Implemented**（授权码流程未实现），并把收到的
`response_type|client_id|redirect_uri|scope` 原样回显在 body 里（`:203-210`） |

默认客户端密钥写在 `application.yml:59`：`xiuxian_oauth_secret_please_change`。
`oauth2.enabled` 未开启时 `/token` 返回 503（`OAuthResource.java:50-52`）。

`/authorize` 的"回显参数"是个小问题：虽然返回 501，但把 `redirect_uri` 原样回显
在 JSON body 里，若前端有"根据返回的 redirect_uri 跳转"的逻辑，就构成开放重定向。

### 5.7 `UnifiedRestResource`——指令的 REST 镜像

`@Path("/game")` + `@Path("{path:.*}")`（`UnifiedRestResource.java:71-84`），
在构造函数里 `ensureRoutesLoaded()`（47-69）把所有 `Command.getRestEndpoints()`
（`Command.java:326-334`）收集进 `Map<String, List<RouteDefEntry>>` 并按"字面量段数降序"排序，
保证 `/sect/info/{id}` 优先于 `/sect/{action}`。

`dispatch()`（86-…）：

```java
boolean isAdminPath = path.startsWith("admin/");     // :90
// 权限检查：entry.def.getPermission() != null 时
//   permUserId = getCurrentUserIdQuietly()
//   if (permUserId == null) → 401
//   if (!PermissionService.hasPermission(permUserId, perm)) → 403
```

**V8 修复**（`:99-103` 注释）纠正了"userId 为 null 就跳过权限检查"的旧漏洞。

**但"双鉴权"路径判断是错的**：`UnifiedRestResource` 挂在 `@Path("/game")` 下，
真实的 admin 路由 `admin/energy/{playerId}` 注册在
`RouteDefinition.get("admin/energy/{playerId}", ...)`（`onebot/command/admin/EnergyAdminCommand.java:32-35`），
经 `UnifiedRestResource` 拼出来是 **`/api/game/admin/energy/*`**，`path` = `"admin/energy/123"`，
`isAdminPath` = **true**。

于是：

- `AdminAuthFilter.filter()` 的第一行就是 `if (!path.startsWith("admin/")) return;`（166-168）——
  它看到的 Jersey 路径是 `"game/admin/energy/..."`，**永远不进入**，`:177` 的
  `isDualAuthPath` 分支是**死代码**；
- `JwtAuthFilter.isBypassPath(path, "admin/energy")`（`:31`）同理，**永不匹配**；
- `ModuleGateFilter` 因为 `segments[1] == "admin"` 不在映射表里，**放行**；
- `UnifiedRestResource` 里 `isAdminPath = true` 且没有 admin JWT 走这条路径，
  若路由带权限码 → **401**。

**结论**：`/api/game/admin/energy/{get,set,add,remove}` 这 4 个管理端点，
**只能用玩家 JWT + 对应权限码访问，admin JWT 反而不行**。
这与"双鉴权"的设计意图完全相反，且两个过滤器里的注释都在描述一条不存在的路径。

---

## 6. 权限系统

### 6.1 数据模型

`PermissionService`（865 行）全静态，模型是**四张表 + 三层并集**：

```
users ──< user_roles >── roles(name, level, display_name)
                              │
                              └──< role_permissions >── permissions(code, name, category)

users ──< user_permissions >── permissions        （直接分配，绕过角色）
```

- **角色（权限组）**：`roles.name` 是唯一标识，`level` 是整数等级；
- **权限码**：字符串，内置由 `PermissionCode` 枚举定义，插件可注册自定义；
- **有效权限** = 所有角色权限的并集 ∪ 直接分配的权限 ∪ 插件权限（`hasPermission`，474-487）。

**关键：没有角色继承。** 类注释（25-29 行）说 `level` 用于"排序和层级判断"，
但实际代码里 `level` 只用于 `getHighestRole` / `getHighestRoleLevel`（341-364）
和 `getDefaultRoleName`（312-320），**没有任何"高等级角色自动继承低等级角色权限"的实现**。
默认权限是**穷举写入**的（`buildDefaultGroupPermissions()`，51-…）：

```java
PLAYER     = 所有 game.* 和 qq.* 权限，排除 admin.*、qq.command.admin、game.item.add
MODERATOR  = PLAYER + admin.login + admin.status + admin.logs.view + qq.command.admin + admin.blacklist.view
ADMIN      = 全部权限，排除 admin.roles.manage / admin.users.manage
                       / admin.database.clear_players / admin.database.reset_all
SUPER_ADMIN= 全部权限
GUEST      = qq.command.basic
```

**默认组等级**（`DEFAULT_GROUP_HIERARCHY`，39-46）：
`SUPER_ADMIN=100 / ADMIN=80 / MODERATOR=50 / PLAYER=10 / GUEST=0`。

**缓存**：只有 `groupHierarchy` / `groupDisplayNames` / `groupPermissionCodes` 三个
`Map` 被缓存（`refreshGroupCache()`，125-…，`initDefaultData()` 末尾调用）。
**用户 → 角色 → 权限的映射完全没有缓存**：`hasPermission()` 每次至少执行
**3 条 SQL**（`user_roles` 查询 + `user_permissions` 查询 + 插件权限查询，
见 474-487 的实际调用链 `getUserGroupPermissionCodes` → `getUserRoles`、
`getUserDirectPermissions`、`getUserPluginPermissionCodes`）。

### 6.2 `PermissionCode` 常量表（73 个）

> 实测：`Select-String '^\s+[A-Z_0-9]+\("'` 命中 **73** 条；按第三参（category）分组：
> 游戏功能 43 / 管理后台 24 / QQ指令 6。

| 分类 | 数量 | 前缀 | 举例 |
|---|---|---|---|
| 游戏功能 | **43** | `game.` | `game.player.info`、`game.realm.breakthrough`、`game.market.buy`、`game.sect.warehouse`、`game.redeem.code` |
| QQ 指令 | **6** | `qq.` | `qq.bind`、`qq.unbind`、`qq.command.basic/game/admin/trace` |
| 管理后台 | **24** | `admin.` | `admin.login`、`admin.database.access`、`admin.plugins.manage`、`admin.redeem.code.manage`、`admin.i18n.manage` |
| **合计** | **73** | | `PermissionCode.java:5-84` |

**枚举里的重复/冗余**（可核对的硬事实）：

- `GAME_SECRET_REALM("game.secret_realm")`（第 9 行）与
  `GAME_SECRET_REALM_ENTER("game.secret_realm.enter")`（第 68 行）**语义重叠**；
- `GAME_SECRETREALM_ENTER("game.secretrealm.enter")`（第 41 行）是第三种写法
  （`secretrealm` 无下划线），与第 68 行的 `secret_realm.enter` **是两个不同的权限码**；
- `GAME_ITEM_ADD("game.item.add")`（第 16 行）在 `buildDefaultGroupPermissions` 里被
  显式排除给 PLAYER（注释"发放物品属于管理员操作"），但 `GameResource` 的
  `/game/item/add` 实际用的是 `admin.items.give`——**`game.item.add` 很可能是个
  从未被任何端点引用的死权限码**。

### 6.3 与命令 / 接口的对接

| 入口 | 对接方式 | 位置 |
|---|---|---|
| OneBot 指令 | `Command` 构造器第 4/5 参传 `permission`；`execute()` 里 `PermissionService.hasPermission` | `Command.java:71-95`、`:177-180` |
| 指令帮助过滤 | `Command.shouldShowInHelp(userId)` 按权限 + 模块开关过滤 | `Command.java:305-316` |
| REST（显式） | `@RequirePermission("xxx")` + `PermissionFilter` | `PermissionFilter.java:44-52` |
| REST（指令路由） | `RouteDefinition.getPermission()` + `UnifiedRestResource.dispatch` | `UnifiedRestResource.java:97-…` |
| WebSocket | `checkWsPermission(socket, msgId, userId, perm)` | `GameWebSocketApp.java:862-869` |
| 插件 | `PermissionService.registerPluginPermission(code,name,category)` 写库，**不自动分配** | `PermissionService.java:223-241` |

### 6.4 越权风险清单

| # | 风险 | 位置 | 严重度 |
|---|---|---|---|
| 1 | **配置管理员 = 无条件 `super_admin`**：`getAdminRole()` 的第一行就是 `if (!ADMIN_USERNAME.isEmpty()) return "super_admin";`（`ADMIN_USERNAME` 来自 `admin.username`，默认非空），因此**配置文件账号登录后拿到的 token 永远带 `role=super_admin`**，`PermissionFilter` 对它直接短路放行（`:60-63`） | `AdminAuthFilter.java:358-361` + `AdminResource.java:144-148` + `PermissionFilter.java:60-63` | 🔴 高（设计如此，但意味着"管理后台 73 个细粒度权限码"对唯一的管理员账号**完全无效**。注意：走数据库账号登录的 ADMIN 角色用户拿到的是 `role="admin"`，**会**正常走权限校验——两条登录路径的授权强度不一致） |
| 2 | **`/admin/logs` 无 `@RequirePermission`**：任何 admin JWT（含 MODERATOR）都能读全量运行日志 | `AdminResource.java:474-477` | 🟠 中高（日志含玩家 ID、SQL 异常、内部路径） |
| 3 | **`/admin/players` 无 `@RequirePermission`**：批量返回全部玩家的 `userId/name/gold/spiritStones/属性/灵根` | `AdminResource.java:1197-1248` | 🟠 中高（MODERATOR 可全量导出玩家经济数据） |
| 4 | **`/admin/plugins` 无 `@RequirePermission`**：列出插件页面 | `AdminResource.java:266-269` | 🟡 低 |
| 5 | **DB 无用户时返回全部权限码**：配置管理员登录成功但 `getUserIdByUsername()` 查不到同名 DB 用户时，`userId == null` 分支把所有 `PermissionCode` 塞进响应的 `permissions` 数组（前端据此显示所有按钮；后端因 `super_admin` 短路也全部放行） | `AdminResource.java:158-168` | 🟠 中 |
| 6 | **`removeRole` 逻辑反了**：`if (getHighestRoleLevel(userId) <= groupHierarchy.getOrDefault(roleName, 0))` 才能删——即**只能删除不低于最高等级的角色**。一个有 `ADMIN+PLAYER` 的用户**永远删不掉 `PLAYER`**；想"降级"时反而是删不掉的那个 | `PermissionService.java:508-521` | 🟠 中（本意是"保护最后一个管理员"，实现成了"保护所有非最高角色"） |
| 7 | **`hasPermission` 无缓存**：每请求 3+ 条 SQL；WS 每条消息、每条指令都要查 | `PermissionService.java:474-487` | 🟡 性能（不是越权，但是 DoS 放大面） |
| 8 | **`assignPermission` 只校验 `isValidPermissionCode`**（枚举 ∪ 插件 ∪ 数据库已有），管理员可把任意已存在权限码分配给任意用户 | `PermissionService.java:530-543` | 🟡 设计如此 |
| 9 | `isValidPermissionCode` 查库异常时 `catch (SQLException ignored) {}` **静默返回 false** | `PermissionService.java:267-269` | 🟡 失败关闭（fail-closed），方向正确 |
| 10 | 插件可注册**任意字符串**权限码；`registerPluginPermission` 只拒绝已存在于枚举/已注册的码 | `PermissionService.java:227-229` | 🟡 插件已完全可信，不构成额外风险 |

---

## 7. WebSocket 层（`GameWebSocketApp`，1 945 行）

### 7.1 协议

**文本帧 + JSON**，统一 `GameMessage` 信封：

```jsonc
// 客户端 → 服务端
{"msgId": 1, "type": "cultivate_start", "data": {...}}

// 服务端 → 客户端
{"msgId": 1, "type": "cultivate_start", "code": 0, "key": "...", "message": "...", "data": {...}}
```

`msgId` 由客户端生成，服务端**原样回填**（`GameMessage.ok(msgId, ...)`）。
服务端主动推送（广播）用 `msgId = 0`。

### 7.2 消息类型（`handleGameMessage` 55 个 `case` + 1 个 `default`）

`handleGameMessage`（`GameWebSocketApp.java:289-538`）的 `switch(type)`：

| 分组 | 类型 | 数量 |
|---|---|---|
| 聊天 | `chat`, `chat_private`, `chat_history` | 3 |
| 排行 | `rank` | 1 |
| 好友 | `friend_add`, `friend_accept`, `friend_reject`, `friend_remove`, `friend_list`, `friend_pending` | 6 |
| 角色 | `player_info`, `heal`, `morning_cultivation` | 3 |
| 修炼 | `cultivate_start`, `cultivate_stop`, `breakthrough` | 3 |
| 背包 | `inventory`, `item_use`, `item_registry`, `equipment_enhance` | 4 |
| 秘境/探索 | `secret_realm_areas`, `secret_realm_enter`, `exploration` | 3 |
| 功法 | `techniques`, `my_techniques`, `technique_learn`, `technique_equip`, `technique_unequip`, `technique_upgrade` | 6 |
| 制造 | `crafting_recipes`, `crafting_craft` | 2 |
| 宗门 | `sect_list`, `sect_info`, `sect_members`, `sect_create`, `sect_join`, `sect_applications`, `sect_approve`, `sect_reject`, `sect_leave`, `sect_kick`, `sect_appoint`, `sect_warehouse`, `sect_donate`, `sect_take`, `sect_disband`, `sect_levelup`, `sect_transfer`, `sect_war`, `sect_top` | **19** |
| 地图 | `map_surroundings`, `map_travel`, `map_locations` | 3 |
| 季节 | `season_info` | 1 |
| 心跳 | `heartbeat` | 1 |
| 兜底 | `default` → 先试 `PluginWebManager.handleWsMessage`，未处理则回 `UNKNOWN_TYPE` | 1 |
| **合计** | | **56 个分支**，其中业务动作 **53** 个 |

> 计数说明：`Select-String '^\s+case "'` 在全文件命中 **71** 条，其中 14 条属于
> `wsModuleForAction`（§7.2.1）、2 条属于 `handleRanking` 的内层 `switch(type)`
> （`case "power"` / `case "wealth"`），余 **55** 条为 `handleGameMessage` 的顶层分支。

#### 7.2.1 前置拦截：限流 + 模块门控（`:294-305`）

```java
String rateKey = "ws:" + userId + ":" + type;
if (!RateLimiter.allow(rateKey, 20, 60)) { ... RATE_LIMITED ... return; }     // 每 (用户,类型) 20 次/60 秒

String module = wsModuleForAction(type);                                      // :252-287
if (module != null && !BuiltInPluginRegistry.getInstance()
        .isEnabledOrUnknown(module)) { ... 403 该玩法系统已停用 ... return; }
```

`wsModuleForAction`（14 个 `case` + 6 条 `startsWith` 前缀规则）把动作映射到内置模块名：
`sect_*`→`sect`（但 `sect_war` 例外→`experimental_sect_war`）、`secret_realm_*`→`secret_realm`、
`friend_*`→`friend`、`technique_*`/`techniques`/`my_techniques`→`technique`、`crafting_*`→`crafting`、
`map_*`→`map`、`chat*`→`chat`、`rank`→`rank`、`heal`/`player_info`→`player`、
`cultivate_*`/`breakthrough`/`season_info`→`cultivation`、`inventory`/`item_*`/`equipment_enhance`→`inventory`。

**注意：`morning_cultivation` 不在映射里（返回 `null`，不受 `cultivation` 开关门控）**。
这与 `ModuleGateFilter` 是同一类"手写映射表"风险，且这张表在 `GameWebSocketApp` 里
是 `private static` 方法而非配置，测试无法从外部校验。

### 7.3 会话管理

三个 `ConcurrentHashMap`（69-71）：

```java
Map<WebSocket, Long>    sessionUsers     // socket → userId
Map<Long, WebSocket>    userSessions     // userId → socket（一对一）
Map<WebSocket, Boolean> authenticated    // socket → 是否已认证
```

**鉴权流程**（`onMessage`，89-122 → `handleAuth`，124-226）：

1. 长度检查：`text.length() > MAX_MESSAGE_CHARS`（来自
   `ServerConfigurator.getMaxWebSocketMessageSize()`，默认 65536）→ 回错误帧并 `close(4009)`；
2. `authenticated.getOrDefault(socket,false) == false` → 走 `handleAuth`，**其它任何消息都拒绝**；
3. `handleAuth` 要求**第一条消息必须是** `{"type":"auth","data":{"token":"..."}}`，否则 `close(4001)`；
4. `JwtUtil.validateToken` → `TokenBlacklist.isBlacklisted` → `extractUserId/extractUsername`；
5. **同账号互踢**：若 `userSessions.get(userId)` 已存在，先 `sessionUsers.remove(previous)`、
   `authenticated.put(previous,false)`、`previous.close(4002, "账号在其它位置登录")`；
6. 三 map 写入 → `actionLog.logConnect` → 触发 `PluginEvent.Type.PLAYER_LOGIN`（`fireQuietly`）
   → `OfflineRewardService.processOfflineRewards(userId)` → 发 `welcome` 帧 → `broadcastUserOnline`。

**断开**（`onClose`，228-…）：三 map 全部 `remove` + `broadcastUserOffline`。

### 7.4 广播

```java
public void broadcast(GameMessage message) {                 // :1093-1104
    String json = message.toJson();
    for (WebSocket socket : sessionUsers.keySet()) {
        if (socket.isConnected()) {
            try { socket.send(json); } catch (Exception e) { /* 单连接失败不影响其它 */ }
        }
    }
}
public void sendToUser(Long userId, GameMessage message) {   // :1106-1115
    WebSocket socket = userSessions.get(userId);
    if (socket != null && socket.isConnected()) socket.send(message.toJson());
}
public boolean isUserOnline(Long userId) {                   // :1124-1130
    WebSocket socket = userSessions.get(userId);
    return socket != null && socket.isConnected();
}
```

**广播是 O(n) 全量串行发送，在调用者线程上执行**。世界聊天一条消息 →
`handleChat`（540-572）→ `broadcast(chatMessage)` → 遍历所有在线连接逐个 `socket.send()`。
1000 人在线时，一条世界消息要在**发送者的处理线程**里完成 1000 次写操作，
`send()` 是同步写（Grizzly 内部可能排队），**没有背压、没有慢消费者剔除、没有异步队列**。

### 7.5 鉴权

`checkWsPermission(socket, msgId, userId, permission)`（862-869）：

```java
if (!PermissionService.hasPermission(userId, permission)) {
    socket.send(GameMessage.error(msgId, "error", GameErrorCode.PARAM_INVALID.getCode(),
                                  "权限不足: " + permission).toJson());
    return false;
}
```

注意：**用的是 `PARAM_INVALID` 错误码而不是权限错误码**（响应里 `code` 是参数非法）。

**权限检查覆盖率**（逐 case 核对）：

| 类型 | 是否有 `checkWsPermission` |
|---|---|
| `chat`（世界聊天） | ❌ **无**（`chat_history` 反而检查 `game.chat.world`） |
| `heartbeat` | ❌ 无（合理） |
| `season_info` | ❌ 无（合理，公开信息） |
| 其余 52 个业务类型 | ✅ 有 |

`chat` 缺检查的实际影响有限（`PLAYER` 角色默认拥有 `game.chat.world`），
但**权限模型被绕过**：如果一个玩家被单独撤销了 `game.chat.world`，
他仍能通过 WS `chat` 发世界消息（REST `/game/chat` 则会拦住）。

### 7.6 心跳

只有**被动**心跳：客户端发 `{"type":"heartbeat"}` → 服务端回 `{"type":"pong","data":{"timestamp":...}}`
（`:383-388`）。**服务端没有主动 ping，也没有超时剔除**：

- `ServerConfigurator` 读取了 `network.read_timeout_ms`（`ServerConfigurator.java:41`），
  但 `configureListener`（71-81）**从未调用 `listener.getTransport().setReadTimeout(...)`**——
  这个配置项是**死配置**；
- `WebSocketAddOn` 也没有 `setTimeoutInSeconds`（`ServerConfigurator.java:57-63` 的注释
  明确说 Grizzly 4.0.2 只有这一个超时 API，但代码没用它）；
- 结果：半开连接（拔网线、进程被杀）会**永久留在 `sessionUsers` / `authenticated` 里**，
  直到 TCP keepalive 超时（默认 2 小时）。

### 7.7 并发与内存

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| 1 | **无未认证连接上限**：`onConnect` 就 `authenticated.put(socket,false)`，之后不认证也不发消息的连接永久占位 | `:74-76` | 慢速连接耗尽内存/句柄 |
| 2 | **无读超时**（见 §7.6） | `ServerConfigurator.java:41,71-81` | 半开连接永久泄漏 |
| 3 | **广播 O(n) 同步串行** | `:1093-1104` | 在线人数上升时延迟线性恶化 |
| 4 | **`userSessions` 一对一**：同账号互踢时旧 socket 的 `sessionUsers` 被移除，但如果 `onClose` 在竞态窗口里晚到，可能把**新** socket 的映射删掉 | `:163-176` + `onClose` | 极低概率"登录后立即掉线" |
| 5 | **`OfflineRewardService` 在认证线程同步执行**（`:188-213`） | `:188` | 离线奖励计算（可能扫大量数据）阻塞该连接的认证 |
| 6 | 每条消息都 `GameMessage.fromJson` 全量反序列化，`data` 无大小限制（只有整帧 64 KB 上限） | `:116` | 单帧内可塞大量字段 |
| 7 | 每连接一个业务线程（Grizzly worker），worker 池 `4/8` + 队列 `100` | `ServerConfigurator.java:37-39,47-51` | 队列满时 Grizzly 直接拒绝新连接 |
| 8 | `shutdownGracefully()`（1929-1944）先 `clear()` 三个 map 再逐个 `close(1001)` | `:1929-1944` | 关闭期间的 `broadcast` 会因 map 已空而静默丢弃 |

---

## 8. 安全问题清单

> 严重度：🔴 高（可直接提权/注入/未授权访问）· 🟠 中（信息泄露/需前置条件）· 🟡 低（加固建议）
> 全部条目均已用工具核对源码行号，未做运行时验证。

### 8.1 认证 / 授权

| # | 问题 | 位置 | 严重度 |
|---|---|---|---|
| 1 | **`JwtAuthFilter` 缺 `@Priority`，排在 `PermissionFilter` 之后** → `GameResource` 全部 102 个 `@RequirePermission` 端点因 `userId == null` 恒返回 401（详见 §5.3） | `JwtAuthFilter.java:10-11` vs `PermissionFilter.java:18,65-80` | 🔴 高 |
| 2 | **`/api/mc-command` 完全无鉴权**：`JwtAuthFilter` 把 `mc-command` 列入白名单（`:25`），`McCommandResource` 不校验任何凭据，**身份完全由客户端自报的 `mcName`/`mcUuid` 决定** | `JwtAuthFilter.java:25` + `McCommandResource.java:19-68` | 🔴 高（任何能访问 8080 端口的人都能以任意 MC 玩家身份执行 `/xiuxian` 指令；唯一屏障是 `RateLimiter.allow("mc-api:"+mcUuid, 10, 60)`，而 `mcUuid` 也可随意伪造 → 限流形同虚设） |
| 3 | **配置管理员 = 无条件 `super_admin`**，`PermissionFilter` 直接短路 | `AdminAuthFilter.java:358-361` + `PermissionFilter.java:60-63` | 🔴 高（设计缺陷：唯一管理员账号绕过全部 73 个权限码） |
| 4 | `/admin/logs`、`/admin/players`、`/admin/plugins` 无 `@RequirePermission` | `AdminResource.java:474`、`:1197`、`:266` | 🟠 中高 |
| 5 | **`/api/game/admin/energy/*` 双鉴权逻辑是死代码**，admin JWT 无法访问（详见 §5.7） | `AdminAuthFilter.java:166-177`、`JwtAuthFilter.java:31`、`UnifiedRestResource.java:90` | 🟠 中 |
| 6 | WS `chat`（世界聊天）缺权限检查 | `GameWebSocketApp.java:308-310` | 🟡 低 |
| 7 | `checkWsPermission` 用 `PARAM_INVALID` 错误码表示权限不足 | `GameWebSocketApp.java:864` | 🟡 低（客户端无法区分错误类型） |
| 8 | `PermissionFilter` 的 403 响应体把权限码回显给调用方 | `PermissionFilter.java:87` | 🟡 低（权限码枚举泄露） |
| 9 | `/api/auth/oauth/introspect` 完全无鉴权，可拿任意字符串做内省 | `OAuthResource.java:79-92` | 🟡 低（RFC 7662 要求端点鉴权） |
| 10 | `/api/install/restart` 无任何凭据校验（仅本机限制） | `InstallResource.java`（`restart` 端点） | 🟠 中（本机任意进程可重启服务） |
| 11 | **`/api/install/reset` 在 `admin.username` 或 `admin.password_hash` 任一为空时跳过密码校验**（条件写成了 `if (!isBlank(user) && !isBlank(hash))`，缺失时直接进删除分支） | `InstallResource.java:332-340` | 🟠 中（纯 DB 账号体系的老安装，本机任意进程可删配置并触发重启） |

### 8.2 输入校验 / 注入

| # | 问题 | 位置 | 严重度 |
|---|---|---|---|
| 12 | **`PlayerDataService.restorePlayer` 用客户端 JSON 的 `tables` 键名与行内列名直接拼 SQL**，无任何白名单校验 | `PlayerDataService.java:602-619`（`DELETE FROM " + table`）、`:764-793`（`INSERT INTO " + table`、`sql.append(entry.getKey())`） | 🔴 高（**SQL 注入 / 任意表写**。前置条件：`player-data.self-restore=true`（默认 false）+ 玩家 JWT + `game.player.info`（PLAYER 默认拥有）。校验链只有 `data.playerId == playerId` 与 SHA-256 checksum，而 checksum 是**客户端可自行计算**的裸摘要 → 无法阻止构造 payload。对照：同项目的 `DatabaseManager.insertRow/updateRow`（`:503-567`）**有** `assertSafeTableName` + `assertSafeColumnName`，`DatabaseManager.importData`（`:600-662`）也**有**——`PlayerDataService` 是唯一漏掉的一处） |
| 13 | `restorePlayer` 的完整性校验用**裸 SHA-256**，不是 HMAC | `PlayerDataService.java:281-287` | 🟠 中（无密钥 → 无真实性；配合 #12 或单独使用可任意改写自身 `players_items` / `player_economy` 行） |
| 14 | 管理台表浏览器 `GET /admin/db/tables/{tableName}` 的表名**已做三重校验**（格式 `^[a-z_]+$` + 硬编码 45 表白名单 + 实际存在缓存） | `DatabaseManager.java:330-356` | ✅ 安全（作为对照） |
| 15 | `/admin/backup/import` 的表名/列名**已校验** | `DatabaseManager.java:618,632` | ✅ 安全 |
| 16 | `McCommandResource` 的 `cmd.toLowerCase()` 后直接进 `adapter.handleMcCommand`，`args` 不校验 | `McCommandResource.java:45-59` | 🟠 中（依赖下游 `MinecraftCommandContext` 的解析） |
| 17 | `handleChat` 对 `content` **无长度上限**（只有整帧 64 KB 上限） | `GameWebSocketApp.java:540-572` | 🟡 低 |
| 18 | `handleChatHistory` 的 `limit` **不设上下界**（客户端可传 `Integer.MAX_VALUE`） | `GameWebSocketApp.java:619` | 🟡 低（DB 全表扫描） |
| 19 | `handleRanking` 的 `limit` 同样不设界 | `GameWebSocketApp.java:640` | 🟡 低 |

### 8.3 路径穿越

| # | 问题 | 位置 | 严重度 |
|---|---|---|---|
| 20 | `AdminResource.loadPlugin` 的 `jarName` **三重校验**（`..`、`/`、`\`、`\0`、`.jar` 后缀 + canonical path 前缀） | `AdminResource.java:406-436` | ✅ 安全（S4 修复，写得很扎实） |
| 21 | `AdminStaticFileHandler` 把请求路径剥前缀后**直接拼到 classpath 资源路径**，未过滤 `..` | `AdminStaticFileHandler.java:54-74` | 🟠 中（对 jar 内 classloader 无害；但对**目录式** classloader（`target/classes`、IDE 直跑）`getResourceAsStream("webadmin/../../config/application.yml")` 会解析 `..` → **读取 `application.yml`（含数据库密码、`jwt.secret`、admin 密码哈希）**） |
| 22 | `PluginWebManager.PluginPageHttpHandler` 同样把 URL 的 `subPath` 直接拼到 `resEntry.resourceDir` | `PluginWebManager.java:276-279` | 🟠 中（同上，且 `registerWebResources` 用的是插件自己的 `URLClassLoader`） |
| 23 | `PlayerDataService` 的 `DATA_DIR.resolve(playerId + ".json")` 中 `playerId` 是 `long` | `PlayerDataService.java:196,680,702` | ✅ 安全 |
| 24 | 插件数据目录 `new File(pluginsDir, info.getName())` 用 `plugin.json` 的 `name` 作目录名，**未过滤 `../`** | `PluginManager.java:466` | 🟡 低（插件已完全可信） |

### 8.4 反序列化

| # | 问题 | 位置 | 严重度 |
|---|---|---|---|
| 25 | 所有 JSON 反序列化都用 **Gson 到具体类型**（`Gson.fromJson(str, Xxx.class)`），**没有 `enableDefaultTyping`** | 全局 | ✅ 无多态反序列化 RCE 面 |
| 26 | `GameMessage.fromJson` 把 `data` 反序列化为 `JsonObject`，不实例化任意类 | `GameMessage.java:107-109` | ✅ 安全 |
| 27 | `PlayerDataService.restorePlayer` 用 `GSON.fromJson(jsonData, PlayerData.class)` —— 类型固定，但**内部 `tables` 是 `Map<String,List<Map<String,Object>>>`**，键值全客户端可控（真正的风险在 #12 的 SQL 拼接，不在反序列化本身） | `PlayerDataService.java:546` | 🟡 |
| 28 | `MiniJson`（301 行）是 GUI 自研解析器，只解析自己写的配置 | `gui/MiniJson.java` | ✅ 安全（离线工具） |

### 8.5 敏感信息泄露

| # | 问题 | 位置 | 严重度 |
|---|---|---|---|
| 29 | **默认管理员凭据硬编码在仓库里**：`admin / admin123` 的 bcrypt 哈希 | `src/main/resources/application.yml:101-103` | 🔴 高（注释写了"生产环境务必更换"，但默认值会随 jar 分发到每个新装实例） |
| 30 | 默认数据库密码 `root` | `src/main/resources/application.yml:28` | 🟠 中 |
| 31 | 默认 OAuth 客户端密钥 `xiuxian_oauth_secret_please_change` | `src/main/resources/application.yml:59` | 🟠 中 |
| 32 | `GET /api/install/status` **无鉴权**且"始终可访问"，返回 `jarDir`、`sqlitePath`、`serverHost`、`osName`、`javaVersion`、`jwtFingerprint` | `InstallResource.java`（status 端点） | 🟠 中（绝对路径 + JVM 版本 + 密钥指纹，是极好的攻击前置信息） |
| 33 | `/admin/plugins/*` 与 `/admin/*` 静态资源**零鉴权** | `Main.java:271`、`PluginWebManager.java:231-240` | 🟠 中（管理台 HTML/JS 公开可读；插件页面内容公开） |
| 34 | `/game/player/data/export` 把**服务端绝对文件路径**回给客户端 | `GameResource.java:2935` + `PlayerDataService.java:385-387` | 🟡 低 |
| 35 | `/admin/backup` 导出**整库 JSON**（含 `users.password_hash`） | `AdminResource.java:2001-2018` | 🟡 低（需 `admin.database.access`） |
| 36 | `EntityBufferFilter` 每次请求 `System.out.println("[EntityBuffer] 缓冲 N bytes")` | `EntityBufferFilter.java:34` | 🟡 低（日志噪音 + 请求体大小侧信道） |
| 37 | `JwtAuthFilter` 每次白名单命中都 `System.out.println("[JwtFilter] bypass: " + path)` | `JwtAuthFilter.java:26,34` | 🟡 低（攻击者可刷爆 stdout） |
| 38 | `AdminResource` 大量 `catch (Exception e) { message: e.getMessage() }` 把 SQL 异常原文回给客户端 | `AdminResource.java:1755`、`:1815`、`:2015` | 🟠 中（泄露表名/列名/SQL 片段） |
| 39 | 插件 `loadLang()` 可**覆盖任意内置 i18n key**（含 `admin.*`、`error.*`），停用后不回滚 | `PluginContext.java:88-104` + `I18nManager.mergeFromStream` | 🟠 中（恶意插件可篡改所有界面文案，包括"权限不足"提示） |

### 8.6 CORS / CSRF

| # | 结论 | 证据 | 严重度 |
|---|---|---|---|
| 40 | **全项目零 CORS 配置**：`grep 'Access-Control-Allow\|CORS\|CrossOrigin'` 在 `src/main/java` 下**0 命中** | — | ✅ 默认安全（浏览器会阻止跨源读取） |
| 41 | **无 Cookie 会话**：全部鉴权走 `Authorization: Bearer`，没有 `Set-Cookie`/`Cookie` | `JwtAuthFilter.java:38-50`、`AdminAuthFilter.java:179-190` | ✅ **CSRF 天然不成立** |
| 42 | 但零 CORS 也意味着**管理台与 API 必须同源部署**；`server.host` 默认 `127.0.0.1`（`application.yml:16`），反代场景需自行处理 | — | 🟡 部署约束 |
| 43 | `/authorize` 把 `redirect_uri` 回显在 JSON body（虽返回 501） | `OAuthResource.java:203-210` | 🟡 低（若前端据此跳转则构成开放重定向） |

### 8.7 插件系统特有

| # | 问题 | 位置 | 严重度 |
|---|---|---|---|
| 44 | **插件 = 完全可信代码**：`PluginContext` 直接给出 `Connection`、`AppConfig`、`JwtUtil`、文件 IO、任意 Java 代码 | `PluginContext.java:314-366` | 🔴 高（架构性：`plugins/` 目录写权限 = RCE。当前目录为空，风险尚未激活） |
| 45 | 插件可注册**任意** WebSocket 消息类型；`handleGameMessage` 的 `default` 分支**在权限检查之后**，但插件 handler 内部**不做任何鉴权**，也拿不到 `userId`（`PluginWsHandler.handle(WebSocket, JsonObject)` 签名里没有 userId） | `PluginWebManager.java:194-196`、`GameWebSocketApp.java:530-533` | 🟠 中（插件若处理敏感动作，必须自己从 socket 反查身份，而 API 没给这个能力） |
| 46 | 插件可注册任意 `@Path` 的 JAX-RS 资源；Jersey 的 `ResourceConfig` **不支持反注册**，热重载后旧资源类永久留在路由表 | `PluginWebManager.java:59-73` | 🟠 中（ClassLoader 泄漏 + 路由歧义） |
| 47 | `Class.forName(mainClassName, true, cl)` 用 `initialize=true` | `PluginManager.java:458` | 🟡 低（静态初始化块在加载期执行，异常会中断加载——这是有意的） |
| 48 | `findPluginMainClass` 对 jar 内每个类调 `Class.forName(..., false, cl)` | `PluginManager.java:522-529` | 🟡 低（可被用于探测/触发类加载副作用） |

---

## 9. 质量问题与改进建议

### 9.1 结构性质量问题

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| 1 | **两套插件体系并存且互不相通** | `PluginManager` vs `BuiltInPluginRegistry` | 认知负担极高；`README_PLUGIN.md` 只讲了一套 |
| 2 | **25 个内置模块是空壳**，玩法逻辑全在 `game/service`，模块化边界不存在 | `builtin/*Plugin.java` 全部只有 `logger.info` | "插件化"只是开关化；无法真正裁剪代码体积 |
| 3 | **`ModuleGateFilter` 的 35 条路径映射硬编码，无一致性校验** | `ModuleGateFilter.java:29-71` | 新增模块忘加映射 → 接口永久绕过门控 |
| 4 | **`AdminResource` 4 101 行 / 100 端点 / 单文件** | `AdminResource.java` | 已开始拆分（`AdminPlayerDataResource` 113 行是第一个），但只拆了 1/100 |
| 5 | **`GameResource` 2 962 行 / 107 端点** | `GameResource.java` | 同上 |
| 6 | **`GameWebSocketApp` 1 945 行 / 56 个 switch 分支 / 53 个业务动作** | `GameWebSocketApp.java` | 一个类承担协议解析、鉴权、会话、53 个业务动作 |
| 7 | **`CodeGenerator` 1 118 行单类**，用 `StringBuilder` 拼 Java 源码 | `gui/CodeGenerator.java` | 无法测试生成结果的可编译性；`CodeGeneratorTest` 在 `src/main/java` 里 |
| 8 | **测试类放在 `src/main/java`**：`gui/CodeGeneratorTest.java`（157 行）、`PluginMakerSelfTest.java`（95 行） | 路径即证据 | 被编进生产 jar；`CodeGeneratorTest` 命名以 `Test` 结尾但不被 surefire 拾取 |
| 9 | **三套 REST 响应格式并存**（`GameMessage` code=0 / `AdminResource` code=200 / `Auth+Install+OAuth` 真实 HTTP 状态） | §5.4 | 客户端必须按路径分支适配 |
| 10 | **`ApiResponse`（80 行）零引用** | `grep ApiResponse` 在 `com.mtxgdn.rest` 下 0 命中 | 死代码 |
| 11 | **异常映射只有 2 个**，`WebApplicationException` 未被映射 → `GameResource` 的 401 会返回 Jersey 默认 HTML 错误页 | §5.4 | 客户端拿到非 JSON |
| 12 | **`System.out.println` 散落在生产代码里** | `PluginSwitches.java:77,80,343`、`EntityBufferFilter.java:34`、`JwtAuthFilter.java:26,34`、`AdminAuthFilter.java:73,75,89,152,158`、`InstallResource.java:354` | 与 `GameLogger` 体系割裂；无法按级别过滤/落文件 |
| 13 | **`network.read_timeout_ms` 是死配置** | `ServerConfigurator.java:41` 读取，`71-81` 从未 `setReadTimeout` | WebSocket 半开连接永久泄漏 |
| 14 | **25 处 builtin javadoc 写错配置键**：24 个 `builtin/*Plugin.java` 的 `配置键: built_in_plugins.<name>` + `AdapterChannelPlugin.java:13`，实际是 `plugins.<name>`（`built_in_plugins.*` 已降级为只读兜底）。只有 `WorldBossPlugin` / `SectWarPlugin` / `EnergyExchangePlugin` 写对 | `builtin/*Plugin.java:9`（各文件） | 误导开发者去改 `application.yml`（改了只当兜底、不覆盖 `plugins.yml`） |
| 15 | **`docs/i18n-design.md:102` 与代码相反**（`AUTHENTICATION + 10` vs `- 10`） | — | 误导维护者 |
| 16 | **`examples/sample-plugin/pom.xml` 的 `<dependencies>` 为空** | `examples/sample-plugin/pom.xml` | 文档承诺"可直接 `mvn package`"不成立 |
| 17 | **`README_PLUGIN.md` 缺 5 大块内容**：事件系统、权限注册、Web 扩展（REST/页面/WS/适配器）、i18n、内置插件 | §2.5 | 插件作者只能读源码 |

### 9.2 优先级建议（按投入产出比排序）

**P0 · 必须先验证/修复（安全 + 功能正确性）**

1. **验证并修复 `JwtAuthFilter` 的优先级**——加 `@Priority(Priorities.AUTHENTICATION + 10)`，
   补一个 `JerseyTest` 端到端用例覆盖"登录 → 调 `@RequirePermission` 端点"。
   如果确认当前是坏的，这解释了为什么 REST 游戏接口在实机上不可用。
2. **`/api/mc-command` 加鉴权**——最简单方案：在 `application.yml` 增加
   `mc-command.token`，要求 `X-MC-Token` 请求头匹配；或直接复用玩家 JWT。
3. **`PlayerDataService.restorePlayer` 加表名/列名白名单**——直接复用
   `DatabaseManager` 的 `assertSafeTableName` / `assertSafeColumnName`（改成包级可见即可），
   并把 `computeChecksum` 改成 HMAC-SHA256（密钥取 `jwt.secret` 派生）。
4. **`InstallResource.reset` 的密码校验条件**从 `if (!blank(user) && !blank(hash))`
   改为"缺少配置时拒绝"，而不是跳过。
5. **`/admin/logs`、`/admin/players` 补 `@RequirePermission`**（`admin.logs.view` / `admin.status`），
   并把 `AdminWritePermissionTest` 的不变量扩成"所有 `/admin/*` 端点必须有 `@RequirePermission`（除 login/whoami/me）"。

**P1 · 结构性修复**

6. **`AdminAuthFilter.getAdminRole()` 不再无条件返回 `super_admin`**——改为
   `PermissionService.getHighestRole(getUserIdByUsername(ADMIN_USERNAME))`，配置管理员也走权限表。
7. **`PermissionService` 加用户权限缓存**（`ConcurrentHashMap<Long, CachedPerms>` + TTL 或
   角色变更时失效），把 `hasPermission` 的 3 条 SQL 降到 0~1 条。
8. **修 `removeRole` 的判断逻辑**（`PermissionService.java:508-521`）——本意应是
   "不允许移除使用户失去最后一个管理角色的角色"，而不是"只能移除最高等级角色"。
9. **`ModuleGateFilter` 的路径映射改为从 `PluginCatalog` + 注解派生**，或至少加一个
   `ModuleGateMapTest` 校验"每个 `plugins.yml` 里的模块名都至少被一条路径映射引用"，
   并把 `GameWebSocketApp.wsModuleForAction` 的映射也纳入同一张表。
10. **`PluginManager.disableSingle` 补全清理**：`CommandRegistry.unregister`、
    `ItemRegistry.unregister`、`ExplorationEventRegistry.unregister`、`SecretRealmRegistry.unregister`、
    `TitleRegistry.unregister`——需要 `PluginContext` 记录"本插件注册了哪些实例"（一个
    `List<Runnable> undoLog`），否则无法可靠反注册。

**P2 · 可维护性**

11. 拆 `AdminResource`（4 101 行）为 `AdminAuthResource` / `AdminPluginResource` /
    `AdminPermissionResource` / `AdminDbResource` / `AdminMapResource` / `AdminEconomyResource`，
    沿用 `AdminPlayerDataResource` 已有的拆分范式（保持路径与权限码不变）。
12. 拆 `GameWebSocketApp`（1 945 行）为 `WsSessionManager` + `WsRouter` + 按玩法的 handler 类。
13. 统一 REST 响应格式：新增 `ApiResult`（`code/message/key/data`），`code` 统一用
    `GameErrorCode`（成功 `0`），HTTP 状态码与 `code` 对齐；把 `AdminResource` 的
    `code:200` 逐步迁移到 `code:0`（可用一个 `@Deprecated` 兼容层）。
14. 把 `CodeGeneratorTest` / `PluginMakerSelfTest` 移到 `src/test/java`。
15. 补 `WebApplicationException` mapper，保证 401/403/404 都返回 JSON。
16. 把所有 `System.out.println` / `System.err.println` 换成 `GameLogger`。
17. `ServerConfigurator` 补 `listener.getTransport().setReadTimeout(readTimeoutMs)`，
    并给 `WebSocketAddOn` 调 `setTimeoutInSeconds`；`GameWebSocketApp` 增加未认证连接的
    定时清扫（如 30 秒未认证即 `close`）。
18. 广播改为"每连接一个有界发送队列 + 独立写线程"，慢消费者队列满即断开，避免 O(n) 串行阻塞。
19. 修 `README_PLUGIN.md` 的 5 处缺口（§2.5 表格），并修 25 处 builtin javadoc 的配置键；
    `examples/sample-plugin/pom.xml` 补 `provided` 依赖。
20. `PluginWebManager` 的 `pluginPages`（`LinkedHashMap`）、`wsHandlers` 的内层 `ArrayList`
    换成并发安全结构（`CopyOnWriteArrayList`），避免注册线程与 HTTP/WS 线程的
    `ConcurrentModificationException`。

### 9.3 值得肯定的设计

为免本文只见问题，以下几点确实是本层的亮点：

- **`PluginCatalog` + `plugins.yml` + `BuiltInModuleSetTest` 三方锁定**（`BuiltInModuleSetTest.java:101-116`），
  新增内置模块必须同时登记清单、模板开关、默认值，否则测试红——这是很扎实的"单一事实来源"实践。
- **`PluginSwitches` 的旧键迁移**（`migrateLegacyKeys`，305-352）只迁移"旧配置里确实写过的键"，
  升级不丢用户设置、全新安装不产生垃圾条目，且旧文件保留为只读兜底。设计非常克制。
- **`PluginSwitches.set()` 用 `YamlFileEditor` 行级替换**（283-288）而非整体重写 YAML，
  保留了用户注释与排版——这是绝大多数项目做不到的细节。
- **`AdminResource.loadPlugin` 的路径校验**（406-436）做了"字符黑名单 + canonical path 前缀"
  双重检查，是标准的路径穿越防护写法。
- **`DatabaseManager` 的 SQL 标识符白名单**（`assertSafeTableName` 330-356 / `assertSafeColumnName` 359-363）
  三重校验（格式 + 硬编码 45 表 + 实际存在缓存），且 `insertRow`/`updateRow`/`importData` 全部走它。
- **`AdminAuthFilter` 的管理员密钥域分离**：`admin.jwt_secret` 缺失时用
  HMAC-SHA256 从玩家密钥派生（确定性、重启有效、与玩家密钥不同），而不是简单地"回退到玩家密钥"
  或"随机生成导致重启失效"。注释把三种方案的取舍写清楚了。
- **`AdminAuthFilter.tryAcceptPlayerSelf`**（235-248）允许玩家 JWT 访问 `admin/me` / `admin/whoami`，
  但明确 `setProperty("adminAuthenticated", false)`、**不授予任何 admin 权限**——边界划得很干净。
- **`LanguageRequestFilter` + `LanguageCleanupFilter` 的成对设计**，以及
  `Accept-Language` 解析里刻意避开 `split(";")[0]` 的 AIOOBE（`LanguageRequestFilter.java:71-79`）——
  说明作者踩过坑并留下了原因注释。
- **`JwtAuthFilter.isBypassPath` 的路径段匹配**（79-84，V9 修复）修正了 `startsWith("auth/")`
  会把 `authEvil/...` 误放行的老问题。
- **代码注释的"修复史"密度**：全层有 V1/V2/V5/V6/V7/V8/V9、S1/S2/S4 等编号的修复注释，
  每条都写了"原实现有什么问题、现在为什么这么改"。这对维护者极友好，是本文能快速定位
  历史缺陷的主要原因。

---

## 附录 A · 关键文件行数速查

| 文件 | 行数 | 文件 | 行数 |
|---|---|---|---|
| `plugin/gui/CodeGenerator.java` | 1 118 | `rest/AdminAuthFilter.java` | 387 |
| `plugin/PluginManager.java` | 596 | `plugin/PluginSwitches.java` | 382 |
| `plugin/PluginContext.java` | 482 | `plugin/gui/PluginMakerGUI.java` | 437 |
| `plugin/gui/EntriesPanel.java` | 433 | `plugin/PluginWebManager.java` | 400 |
| `plugin/gui/Theme.java` | 362 | `plugin/BuiltInPluginRegistry.java` | 308 |
| `plugin/gui/MiniJson.java` | 301 | `plugin/PluginMaker.java` | 258 |
| `plugin/gui/TriggerEditorDialog.java` | 216 | `plugin/gui/BasicConfigPanel.java` | 212 |
| `plugin/event/PluginEventManager.java` | 209 | `plugin/gui/PluginConfig.java` | 197 |
| `plugin/gui/RegistrableEntry.java` | 184 | `plugin/gui/Trigger.java` | 171 |
| `plugin/gui/CodeGeneratorTest.java` | 157 | `plugin/event/PluginEvent.java` | 108 |
| `plugin/PluginCatalog.java` | 108 | `plugin/PluginMakerSelfTest.java` | 95 |
| `plugin/builtin/AdapterChannelPlugin.java` | 71 | `plugin/PluginClassLoader.java` | 67 |
| `plugin/experimental/EnergyExchangePlugin.java` | 53 | `plugin/experimental/SectWarPlugin.java` | 52 |
| `plugin/BuiltInPlugin.java` | 44 | 25 × `builtin/*Plugin.java` | 各 38 |
| `plugin/PluginInfo.java` | 31 | `plugin/PluginMeta.java` | 26 |
| `plugin/event/PluginEventHandler.java` | 24 | `plugin/Plugin.java` | 23 |
| `rest/AdminResource.java` | 4 101 | `rest/GameResource.java` | 2 962 |
| `rest/InstallResource.java` | 471 | `rest/Auth.java` | 453 |
| `rest/UnifiedRestResource.java` | 220 | `rest/OAuthResource.java` | 219 |
| `rest/ModuleGateFilter.java` | 117 | `rest/AdminPlayerDataResource.java` | 113 |
| `rest/PermissionFilter.java` | 91 | `rest/LanguageRequestFilter.java` | 90 |
| `rest/McCommandResource.java` | 89 | `rest/JwtAuthFilter.java` | 85 |
| `rest/EntityBufferFilter.java` | 41 | `rest/LanguageCleanupFilter.java` | 31 |
| `rest/GenericExceptionMapper.java` | 20 | `rest/EofExceptionMapper.java` | 18 |
| `rest/Test.java` | 16 | `websocket/GameWebSocketApp.java` | 1 945 |
| `permission/PermissionService.java` | 865 | `permission/PermissionCode.java` | 116 |
| `permission/RequirePermission.java` | 12 | `server/AdminStaticFileHandler.java` | 113 |
| `server/ServerConfigurator.java` | 107 | `server/ServerShutdownHook.java` | 66 |

## 附录 B · 端点计数方法说明

端点数由以下命令统计（`@Path` 只统计**方法级**，即带前导缩进的 `@Path("...")`）：

```powershell
# 方法级 @Path 数
(Get-Content $file) | Select-String -Pattern '^\s+@Path\("' | Measure-Object | % Count
# 方法注解数
(Get-Content $file) | Select-String -Pattern '^\s+@(GET|POST|PUT|DELETE)' | Group-Object
```

- `GameResource`：107 个方法级 `@Path`；`@GET` 42 + `@POST` 65 = 107 ✔；
  `@RequirePermission` 102 处 → **5 个端点无权限注解**（`/i18n/bundle`、`/i18n/languages`、
  `/status`、`/players/search`、`/season`）。
- `AdminResource`：100 个方法级 `@Path`；`@GET` 38 + `@POST` 46 + `@PUT` 1 + `@DELETE` 15 = 100 ✔；
  `@RequirePermission` 94 处 → **6 个端点无权限注解**（`/login`、`/plugins`、`/logs`、
  `/players`、`/whoami`、`/me`）。

## 附录 C · 本文未覆盖但相关的范围

- `com.mtxgdn.game.service`（约 3 万行）——只作为"内置模块的真正实现"被引用，未展开分析。
- `com.mtxgdn.adapter` / `onebot` / `qq` / `minecraft`——见 `03-adapters.md`。
- `com.mtxgdn.i18n`——只在与 `PluginContext.loadLang()` 交互处涉及。
- `com.mtxgdn.playerdata.PlayerDataService`（827 行）——因与 §8.2 的 SQL 注入面强相关而深入阅读，
  但未做完整分析。
- 前端 `src/main/resources/webadmin/`（`index.html` 24 865 字节 + 空 `assets/` 目录）——
  管理台前端是否完整交付存疑，未展开。
