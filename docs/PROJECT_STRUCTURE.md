# 修仙世界（xiuxian-server-alpha）· 项目结构说明

> 本文档按「目录 → 包 → 单个文件」的粒度描述整个仓库，用于快速定位代码与判断改动落点。
>
> - 项目版本：`0.1.0`（唯一来源为 `pom.xml` 的 `<version>`；运行时由 `com.mtxgdn.util.BuildInfo` 读取，见 `build-info.properties`）
> - 构建：Maven（JDK 23）；产物 `target/main-0.1.0.jar`
> - 统计口径：`src/main/java` 486 个 `.java`（另有 1 个 `data/mtxgdn/lang/zh_cn.json` 语言包）、`src/test/java` 36 个 `.java`，主源码约 7.0 万行（70087 非空白行）；`src` 下共 560 个文件
> - 文中「行数」为该文件的物理行数，用于判断文件体量

---

## 0. 阅读指南

| 你想做的事 | 应该看的文件 / 目录 |
|---|---|
| 了解服务怎么起来 | `src/main/java/com/mtxgdn/Main.java` |
| 加一条新指令 | `src/main/java/com/mtxgdn/onebot/command/<分类>/` + `common/command/Command.java` |
| 加一个物品 | `src/main/java/data/mtxgdn/item/` + `game/item/ItemEffect.java` |
| 加一个游历事件 | `src/main/java/data/mtxgdn/explorationevent/` |
| 加一个秘境 | `src/main/java/data/mtxgdn/secretrealm/` |
| 改游戏数值 | `src/main/resources/application.yml`、`src/main/resources/game/realm_config.json` |
| 改野外分片世界 / 探索与传送数值 | `src/main/resources/game/wilderness_map.json`（运行期 `config/game/wilderness_map.json`）、`game/service/MapService.java` |
| 改野外地形的生成规则 | `game/map/WildernessTerrain.java`（纯函数，无存储依赖）；分片状态模型见 `game/map/WildernessChunk.java`，落盘见 `game/map/WildernessChunkStore.java`（`data/wilderness/...`） |
| 开关玩法模块 | `src/main/resources/plugins.yml`（运行期落在 `config/plugins.yml`） |
| 改管理后台前端 | `src/main/resources/webadmin/` |
| 改 REST 接口 | `src/main/java/com/mtxgdn/rest/` |
| 写第三方插件 | `README_PLUGIN.md`、`examples/sample-plugin/`、`src/main/resources/plugin-template/` |

---

## 1. 项目概览

一个基于 Java 23 的多人在线**文字修仙（MUD）游戏服务端**：修炼突破、探索战斗、社交宗门、生产贸易，并通过 REST / WebSocket / QQ（OneBot 与官方机器人）/ Minecraft 等多端接入。

**技术栈**（`pom.xml`）

| 组件 | 技术 |
|---|---|
| 语言 / 构建 | Java 23、Maven（含 wrapper `mvnw` / `mvnw.cmd`）、maven-shade-plugin 打可执行 fat-jar（主类 `com.mtxgdn.Main`） |
| HTTP / REST | Jersey 4.0.2 + HK2 + Grizzly 4.0.2（`jersey-container-grizzly2-http`） |
| WebSocket | Grizzly WebSocket（`grizzly-websockets` + `grizzly-http-servlet`） |
| 数据库 | SQLite（`sqlite-jdbc` 3.49.1.0，默认）/ MySQL（`mysql-connector-j` 9.7.0），HikariCP 5.0.1 连接池，方言自动适配 |
| 认证 | JWT（jjwt 0.11.5）+ BCrypt（jbcrypt 0.4），双令牌刷新，Token 黑名单 |
| JSON / YAML | Gson 2.14.0 / SnakeYAML 2.4 |
| 邮件 | Jakarta Mail（Angus Mail 2.0.3），注册邮箱验证码 |
| 测试 | JUnit 5.10.1 + Mockito 5.8.0（Surefire 3.2.2，仅匹配 `**/*Test.java`） |

**构建期特殊处理**

- `maven-compiler-plugin` 通过 `<excludes>**/minecraft/plugin/**</excludes>` **排除** MC 辅助插件源码——它依赖 Bukkit API，由 `build-mc-plugin.bat` 单独编译。
- `src/main/java` 下的 `**/*.json` 被声明为资源，会打进 jar（如 `data/mtxgdn/lang/zh_cn.json`）。
- Surefire 注入系统属性 `xiuxian.plugins.config=${project.build.directory}/test-config/plugins.yml`，让测试的插件开关读写不污染真实 `config/plugins.yml`。

---

## 2. 仓库顶层结构

### 2.1 目录一览

| 目录 | 是否入库 | 说明 |
|---|---|---|
| `src/` | ✅ | 全部源码与资源（见第 4、5、6、7 节） |
| `docs/archive/` | ✅ | 18 份历史分析与评审报告（架构分析、代码问题、四轮安全评审、测试总结等），属归档资料，不代表当前实现 |
| `examples/sample-plugin/` | ✅ | 第三方插件示例工程（`plugin.json` + `pom.xml` + 3 个 Java 类） |
| `deploy/` | ✅ | Linux 部署资产（systemd 单元） |
| `docker/` | ✅ | 容器入口脚本 |
| `.github/ISSUE_TEMPLATE/` | ✅ | 4 个 Issue 模板（`config.yml`、`bug_report.yml`、`feature_request.yml`、`balance_feedback.yml`）；**没有** CI workflow |
| `.mvn/wrapper/` | ✅ | Maven wrapper 配置（`distributionType=only-script`，Maven 3.9.9） |
| `.idea/` | 部分 | IntelliJ 工程配置：`.gitignore`、`encodings.xml`、`misc.xml`、`dictionaries/project.xml` |
| `config/` | ❌（`/config/`） | **运行期生成**，含 `jwt.secret`、`admin.password_hash`、数据库口令、`adapters.json`、`plugins.yml`。已按仓库根锚定忽略 |
| `data/players/` | ❌ | 运行期玩家数据快照，一用户一个 JSON（当前工作区有 74 个 `1.json`–`74.json`） |
| `log/` | ❌ | 运行日志 `server-<yyyyMMdd-HHmmss>.log` |
| `plugins/` | ❌ | 外部插件 jar 目录，**启动时按需创建**，当前工作区不存在 |
| `target/` | ❌ | Maven 构建产物（`classes/`、`test-classes/`、`surefire-reports/`、`test-config/`、`main-0.1.0.jar`、`original-main-0.1.0.jar`） |

### 2.2 根文件逐个说明

| 文件 | 行数/大小 | 职责 |
|---|---|---|
| `pom.xml` | 253 行 | Maven 工程定义：依赖、编译器（release 23、排除 MC 插件包）、Surefire、Shade（fat-jar + `META-INF/services` 合并） |
| `README.md` | ~19.7 KB | 项目主文档：特性总览、技术栈、四种部署方式、配置说明 |
| `README_PLUGIN.md` | ~15.4 KB | 插件开发文档（15 节：生命周期、`plugin.json`、`PluginContext`、注册命令/物品/事件/秘境、服务一览、常见问题） |
| `CHANGELOG.md` | ~49.6 KB | 更新日志，含「版本索引」，最新为 `[0.1.0]`（此前 `V1.x` 为前身版本线） |
| `LICENSE` | MIT | 许可证 |
| `Dockerfile` | 2 阶段 | `eclipse-temurin:23-jdk` 构建（先 `dependency:go-offline` 分层缓存）→ `23-jre` 运行；jar 放 `/opt/xiuxian/main.jar`，默认 `JAVA_OPTS=-Xmx512m -XX:MaxMetaspaceSize=256m` |
| `docker-compose.yml` | ~3.5 KB | 单服务编排：端口 `8080:8080`、命名卷 `xiuxian-state:/app`、`restart: unless-stopped`（安装向导触发的自重启靠它拉起） |
| `docker/entrypoint.sh` | 1.3 KB | 把镜像内 jar 复制到 `/app`、补齐 `config|data|log|plugins` 目录、`cd /app` 后 `exec java`。目的：统一「jar 目录」与「工作目录」两套路径基准 |
| `deploy/xiuxian-server.service` | 4.2 KB | systemd 单元（文件头有完整安装/升级步骤）。核心约定 `WorkingDirectory` 必须等于 jar 所在目录 |
| `.dockerignore` | 739 B | 排除 `config/`（含密钥）、`data/`、`log/`、`plugins/`、`*.db`、`target/`、`docs/`、`*.md`（保留 README）等 |
| `.gitignore` | 1.5 KB | 忽略 `target/`、`/config/`、`data/players/`、`*.db*`、`log/`、`dependency-reduced-pom.xml`、IDE 产物 |
| `.gitattributes` | 739 B | 换行符与文本属性规范 |
| `install.sh` / `install.bat` | 3.2 / 3.8 KB | 一键安装：检测 Java → 缺 jar 时 `mvn package` → 启动并打印地址。支持 `--nogui`、`--build`、`--help` |
| `start.sh` | 1.4 KB | Linux/macOS 启动：自动定位 jar（排除 `original-*`），默认 `-Xms128m -Xmx512m`，可用 `XIUXIAN_MEM` / `JAVA_OPTS` 覆盖 |
| `start-low-memory.bat` | 622 B | Windows 低内存启动：`-Xms128m -Xmx256m -XX:+UseSerialGC -XX:MaxDirectMemorySize=32m` |
| `run_tests.sh` / `run_tests.bat` | 2.3 / 2.1 KB | 测试入口：先清理 `test_combat.db`、`test_full.db`、`xiuxian_test.db`（含 `-shm`/`-wal`），再跑全套测试；Windows 版设 `MAVEN_OPTS=-Xmx384m` |
| `mvnw` / `mvnw.cmd` / `mvnwDebug` / `mvnwDebug.cmd` | — | Maven wrapper（`only-script` 模式，无 `maven-wrapper.jar`） |
| `build-mc-plugin.bat` | 1.6 KB | 单独编译 `src/main/java/com/mtxgdn/minecraft/plugin/`：用 `mc_server/server.jar` 提供 Bukkit API，产物 `mc_server/plugins/XiuxianBridge.jar`（内含 `plugin.yml`） |
| `dependency-reduced-pom.xml` | 5.7 KB | maven-shade-plugin 生成物，已忽略入库 |
| `legacy_test.db` / `test_combat.db` / `test_full.db` / `xiuxian_test.db`(+`-shm`/`-wal`) | — | 测试遗留的 SQLite 数据库文件（`*.db` 已在 `.gitignore` 中） |

---

## 3. 源码总览

### 3.1 主源码包分布（`src/main/java`，480 个文件）

| 包 | 文件数 | 定位 |
|---|---:|---|
| `com.mtxgdn` | 1 | 进程入口 |
| `com.mtxgdn.adapter` | 6 | 多端适配器统一生命周期 |
| `com.mtxgdn.client` | 5 | 内置 Java 客户端 SDK（REST/WS 客户端） |
| `com.mtxgdn.common` | 3 | 通用返回体、错误码、消息封装 |
| `com.mtxgdn.common.command` | 6 | 指令即路由的核心抽象 |
| `com.mtxgdn.common.service` | 1 | 服务定位器（ServiceRegistry） |
| `com.mtxgdn.db` | 7 | 数据库连接、方言、建表、迁移、DAO |
| `com.mtxgdn.demo` | 1 | 交互式演示客户端 |
| `com.mtxgdn.entity` | 2 | 根层实体（`Player` 精简模型、`User`） |
| `com.mtxgdn.game.config` | 4 | 配置文件释放、地图出厂数据、野外随机地图定义、新手奖励配置 |
| `com.mtxgdn.game.entity` | 31 | 玩法实体/DTO/枚举 |
| `com.mtxgdn.game.explorationevent` | 3 | 游历事件框架与注册表 |
| `com.mtxgdn.game.item` | 22 | 物品框架（模型、注册表、扫描器、19 种效果） |
| `com.mtxgdn.game.map` | 3 | 野外分片世界：确定性地形生成、分片状态模型、分片文件存储 |
| `com.mtxgdn.game.secretrealm` | 3 | 秘境框架与注册表 |
| `com.mtxgdn.game.service` | 30 | 玩法服务层（业务逻辑主体） |
| `com.mtxgdn.game.service.combat` | 1 | 战斗日志生成器 |
| `com.mtxgdn.game.title` | 1 | 称号注册表 |
| `com.mtxgdn.minecraft` | 2 | MOTD 服务器、VarInt 编解码 |
| `com.mtxgdn.minecraft.adapter` | 12 | MC 协议客户端、玩家通道、绑定、认证、NBT 组件解析 |
| `com.mtxgdn.minecraft.agent` | 1 | Java Agent（注入 Brigadier 注册 `/xiuxian`） |
| `com.mtxgdn.minecraft.plugin` | 1 | Bukkit 辅助插件（构建时排除，单独打包） |
| `com.mtxgdn.onebot` | 14 | OneBot 通道、绑定、黑名单、群配置、截图机器人 |
| `com.mtxgdn.onebot.command` | 86 | 全部玩家/管理指令（22 个子分类目录） |
| `com.mtxgdn.permission` | 3 | RBAC 权限码、权限服务、注解 |
| `com.mtxgdn.playerdata` | 2 | 玩家数据文件化（导出/还原/自动落盘） |
| `com.mtxgdn.plugin` | 13 | 插件系统内核（加载器、上下文、清单、开关、Web 管理） |
| `com.mtxgdn.plugin.builtin` | 26 | 25 个稳定玩法模块 + 适配器通道插件 |
| `com.mtxgdn.plugin.event` | 3 | 插件事件总线 |
| `com.mtxgdn.plugin.experimental` | 2 | 实验性插件（宗门战、能量转化） |
| `com.mtxgdn.plugin.gui` | 13 | 插件制作器 GUI 与代码生成器 |
| `com.mtxgdn.qq` | 3 | QQ 官方开放平台适配器与 Webhook |
| `com.mtxgdn.rest` | 15 | JAX-RS 资源与过滤器 |
| `com.mtxgdn.server` | 3 | Grizzly 配置、静态文件处理、关机钩子 |
| `com.mtxgdn.service` | 3 | 账号体系服务（用户、OAuth2、验证码） |
| `com.mtxgdn.util` | 13 | 配置、日志、JWT、限流、YAML 编辑等工具 |
| `com.mtxgdn.websocket` | 1 | 游戏主 WebSocket 应用 |
| `data.mtxgdn.explorationevent` | 13 | 13 个游历事件实现（内容数据包） |
| `data.mtxgdn.item` | 115 | 115 个物品定义（内容数据包） |
| `data.mtxgdn.secretrealm` | 10 | 10 个秘境定义（内容数据包） |
| `data.mtxgdn.lang` | — | `zh_cn.json` 语言文件（非 Java） |

> 空目录：`com/mtxgdn/plugin/api/` 当前无文件。

### 3.2 测试源码包分布（`src/test/java`，34 个文件）

见第 7 节。

---

## 4. 主源码逐文件清单

### 4.1 进程入口

| 文件 | 行数 | 职责 |
|---|---:|---|
| `com/mtxgdn/Main.java` | 511 | 进程入口。`--plugin-make-gui` / `--plugin-make` 提前分流；`registerShutdownHook()` 注册 7 项关停任务（适配器、WS、MOTD、坊市任务、玩家数据落盘、内置模块停用、数据库池）；`doMain()` 依次完成：JWT 初始化 → 数据库初始化 → 默认技能/功法/配方 → 物品扫描 → 装备一致性修复 → 秘境扫描 → 游历事件扫描 → 指令扫描 → 外部插件加载 → 内置模块注册与启用 → Grizzly 启动 → 适配器启动 → `/admin` 静态挂载 → 首启挂载 `/install` → 定时清理限流窗口与 Token 黑名单 → `SERVER_READY` 事件 → MOTD 服务器；随后进入 `--demo` / `--nogui` / 交互三种模式之一。另含 `restartServer()`（安装向导完成后自重启并轮询新端口就绪）、`reportDeploymentPaths()`、`initializeDatabase()`、`releaseConfigs()` |

### 4.2 通用层

**`com/mtxgdn/common`（3）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `ApiResponse.java` | 62 | 统一 REST 返回体 |
| `GameErrorCode.java` | 56 | 游戏错误码枚举 |
| `GameMessage.java` | 103 | WebSocket/多端统一消息封装（含 `ok(code, type, data)`） |

**`com/mtxgdn/common/command`（6）— 「指令即路由」核心**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `Command.java` | 295 | 指令基类。构造即自动注册到 `CommandRegistry`；持有分类排序表（账号/我的角色/修炼/战斗/背包/探索/坊市/宗门/经济/社交/管理/实用功能）；`addRoute()` / `registerSub()` 同时登记 OneBot 子命令与 HTTP 路由；`setModule()` + `isModuleEnabled()` 实现内置模块门控；默认 `execute()` 做权限校验与子命令分发 |
| `CommandContext.java` | 142 | 平台无关的指令上下文抽象；平台感知地解析发送者绑定（OneBot `qq_bindings` / QQ官方 `qq_official_bindings`） |
| `CommandRegistry.java` | 67 | 指令注册表（`LinkedHashMap`，按名索引）。`get()` 在模块停用时返回 `null`，使 OneBot/QQ/MC 三端统一门控；`getRaw()` 绕过门控用于提示「该玩法已停用」；注册时对「同一个名字被两个不同 `Command` 类注册」打 WARN（见第 13 节注意点 2） |
| `CommandScanner.java` | 130 | 扫描包（`com.mtxgdn.onebot.command`）反射实例化所有 `Command` 子类，返回注册/跳过/失败统计 |
| `CommandUtils.java` | 58 | 指令侧公共小工具 |
| `RouteDefinition.java` | 133 | 路由定义。提供 `onebotOnly` / `httpOnlyGet` / `httpOnlyPost` / `get` / `post` 五种注册形态，是「一条指令同时是 QQ 指令与 REST 路由」的载体 |

**`com/mtxgdn/common/service`（1）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `ServiceRegistry.java` | 335 | 服务定位器：27 个玩法服务的双检锁懒加载单例访问点（`PlayerService`、`RealmService`、`ItemService`、`SecretRealmService`、`ExplorationService`、`NewbieGuideService`、`SkillService`、`CombatService`、`DailyService`、`TradeService`、`HeartDemonService`、`ChatService`、`FriendService`、`EnhanceService`、`CraftingService`、`TechniqueService`、`SectService`、`EconomyService`、`EnergyService`、`TitleService`、`CaveService`、`FormationService`、`BuffService`、`FarmService`、`MapService`、`WorldBossService`、`MinecraftPlayerBindingService`） |

### 4.3 数据库层 `com/mtxgdn/db`（7）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `DatabaseManager.java` | 614 | HikariCP 连接池初始化、SQLite/MySQL 分支、`shutdown()` |
| `DatabaseSchema.java` | 811 | 全部 `CREATE TABLE` / `CREATE INDEX` 语句（表清单见第 10 节） |
| `DatabaseMigration.java` | 363 | 老库结构升级（如给 `map_locations` 补 `cultivation_bonus` / `exploration_bonus`、给 `farm_plots` 补 `settled_time`） |
| `DatabaseDialect.java` | 43 | 方言接口（`FOR UPDATE` 子句、自增列等） |
| `SQLiteDialect.java` | 149 | SQLite 方言实现 |
| `MySQLDialect.java` | 138 | MySQL 方言实现 |
| `PlayerActionLogDAO.java` | 124 | 玩家行为日志读写（供管理台「轨迹」页与 `PlayerActionLogger`） |

### 4.4 根层实体 `com/mtxgdn/entity`（2）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `Player.java` | 230 | 精简玩家模型（id/userId/name/灵根/等级/经验/境界/子境界/HP/MP/攻防速/灵力/金币/修炼状态/各玩法时间戳/战绩等），供游历事件等内容代码使用 |
| `User.java` | 60 | 账号实体（用户名、密码、邮箱、失败次数、锁定时间、创建时间） |

### 4.5 游戏配置 `com/mtxgdn/game/config`（4）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `GameConfigLoader.java` | 212 | 把 jar 内配置文件释放到 jar 所在目录的 `config/`，供用户修改 |
| `MapDefaults.java` | 115 | 地图**出厂数据唯一来源**：15 个地点（id/名称/描述/区域/最低境界/安全区/修炼加成/游历加成/**危险度**）、17 条无向连通关系、23 条「地界 → 事件权重偏置」。纯数据类，不依赖其他包 |
| `WildernessMapConfig.java` | 298 | **野外分片世界的定义**（`game/wilderness_map.json` 的 POJO 与加载器）：`worldSeed`、`chunkSize`（默认 4x4 节点/片）、`maxChunksFromOrigin`（硬上限）、地形池（`biomes`，由海拔/湿度两个噪声场选定）、资源池、补货与回收周期、首达奖励、传送与采集参数。读盘失败时逐项兜底而不是抛异常，配置文件写错不会让服务起不来 |
| `NewbieRewardConfig.java` | 61 | 新手奖励配置（管理台可读写） |

### 4.5.1 野外分片世界 `com/mtxgdn/game/map`（3）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `WildernessTerrain.java` | 449 | **确定性地形生成器**（纯函数，无任何 IO / 存储依赖）。`(worldSeed, chunkX, chunkY)` -> 一片分片的全部节点（名称 / 描述 / 地形 / 危险度 / 境界门槛 / 倍率）与边（片内边用 Kruskal 生成树保证连通再加额外边；跨片边只由西、北一侧生成，保证每条边只被算一次）。两个低频 value-noise 场（海拔 / 湿度）让地形连成片而不是散点；危险度 = `max(地形危险度, 距中土片数/每档)`，所以越远越险。节点 id 由坐标推导：`ID_BASE + chunkKey x SLOT_STRIDE + slot`（可反解、同分片内连续；`SLOT_STRIDE` 与 `chunkSize` 解耦，改分片边长不会让全部 id 平移）。带生成结果 LRU 缓存，签名为「种子 + 分片边长」，任一变化整体失效 |
| `WildernessChunk.java` | 200 | **一个分片的持久化状态**（对应磁盘上一个 JSON）。刻意只存可变状态：谁探索过（`exploredBy`）、资源剩余（`resources`）、补货时间、`pinned` / `gateway` 标记。**地形一概不存** —— 它是种子的纯函数，存一份反而会与生成器走样。`consume()` / `markExplored()` 是 `synchronized` 的：分片对象全进程共享同一份实例，在分片粒度上互斥，替代了数据库版 `UPDATE ... WHERE remaining >= ?` 的条件更新 |
| `WildernessChunkStore.java` | 291 | **分片文件存储**：`data/wilderness/<cx>>6>/<cy>>6>/<cx>_<cy>.json`，每 64x64 片一个分桶目录（避免单目录塞几十万文件）。写入走 `.tmp` + `ATOMIC_MOVE`（与 `PlayerDataService` 同一套约定）。`load()` 是「缓存 -> 文件 -> 新建」三级：野外无限大，绝大多数分片从未被访问过、磁盘上根本不存在。文件里带 `seedHash`，管理员改过 `worldSeed` 后陈旧存档会被识别并丢弃（否则旧记录会挂到另一片地形上）。另提供 `listChunks()`（回收与统计用，要遍历目录树，调用方需限频）与 `delete()`（仅用于回收从未被探索的分片） |

### 4.6 玩法实体 `com/mtxgdn/game/entity`（31）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `PlayerInfo.java` | 189 | 玩家完整信息 DTO（对外返回的主载体） |
| `SpiritualRoot.java` | 199 | 灵根枚举：鸿蒙/正五行/太乙金/青帝木/玄冥水/离火/后土/巽风/惊雷/玄冰/双灵根/三灵根/四灵根/杂灵根 + 17 种效果键 |
| `Monster.java` | 150 | 怪物模型 |
| `CropConfig.java` | 140 | 作物配置 |
| `SecretRealmResult.java` | 117 | 秘境探索结果 |
| `ExplorationResult.java` | 110 | 游历结果（事件类型/描述/掉落/经验/金币/灵石） |
| `Formation.java` | 110 | 阵法 |
| `Skill.java` | 109 | 技能 |
| `RealmConfig.java` | 106 | 境界配置项 |
| `FarmPlot.java` | 106 | 农田地块（含 `settledTime`：上次惰性结算时刻） |
| `BossForm.java` | 89 | 秘境 Boss 形态 |
| `Technique.java` | 85 | 功法 |
| `RealmBreakthroughResult.java` | 74 | 突破结果（天劫类型/成功率/是否成功） |
| `SectMember.java` | 72 | 宗门成员（5 级职位） |
| `Title.java` | 72 | 称号 |
| `RedeemCode.java` | 71 | 兑换码 |
| `Recipe.java` | 70 | 炼制配方 |
| `Friend.java` | 68 | 好友关系 |
| `ChatMessage.java` | 61 | 聊天消息 |
| `MapLocation.java` | 80 | 地图地点实体 |
| `PveCombatResult.java` | 54 | PVE 战斗结果 |
| `Season.java` | 51 | 季节枚举 |
| `Sect.java` | 50 | 宗门 |
| `Cave.java` | 48 | 洞府 |
| `RaidCombatResult.java` | 42 | 团队副本战斗结果 |
| `RealmConfigFile.java` | 36 | `realm_config.json` 顶层结构 |
| `CelestialPhenomenon.java` | 35 | 天象枚举 |
| `SectApplication.java` | 24 | 入宗申请 |
| `SectWarehouseItem.java` | 24 | 宗门仓库条目 |
| `WorldBoss.java` | 115 | 世界 Boss：全服共享血量 `hp`/`maxHp`、奖励预算、到期时刻、击杀归属 |
| `WorldBossDamage.java` | 56 | 世界 Boss 伤害台账：累计伤害 / 次数 / 上次挑战时刻（同时是挑战冷却的依据） |

### 4.7 物品框架 `com/mtxgdn/game/item`（22）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `Item.java` | 93 | 物品基类（构造即注册；字段：命名空间、key、类型、品质、堆叠上限、价格、可交易、等级、效果） |
| `ItemScanner.java` | 159 | 反射扫描 `data.mtxgdn.item` 包，实例化所有 public 非抽象、含无参构造的 `Item` 子类并注册；支持目录与 jar 两种加载方式 |
| `ItemRegistry.java` | 93 | 物品注册表（按 key 检索） |
| `ItemType.java` | 18 | 类型枚举：`CONSUMABLE` / `MATERIAL` / `SEED` / `CURRENCY` / `SKILL_BOOK` / `TREASURE` / `QUEST` |
| `ItemRarity.java` | 21 | 品质枚举：`COMMON` / `UNCOMMON` / `RARE` / `EPIC` / `LEGENDARY` / `MYTHIC` |
| `ItemEffect.java` | 9 | 效果抽象基类 |
| `EmptyEffect.java` | 10 | 空效果（占位，`INSTANCE` 单例） |
| `HealEffect.java` | 47 | 回血（`HealEffect.hp(n)`） |
| `BuffEffect.java` | 128 | 属性增益/减益 |
| `CurrencyEffect.java` | 104 | 货币（金币/灵石）产出 |
| `ExpEffect.java` | 25 | 经验产出 |
| `LearnSkillEffect.java` | 27 | 学习技能 |
| `RealmBreakEffect.java` | 24 | 境界突破辅助 |
| `RebirthEffect.java` | 36 | 转世重修 |
| `InventoryCapacityEffect.java` | 24 | 扩容背包 |
| `ExpMultiplierEffect.java` | 32 | 经验翻倍丹：+50% 经验获取，300 秒 |
| `ReflectionBuffEffect.java` | 32 | 伤害反弹丹：受击反弹 +30%，120 秒 |
| `AttributeResetEffect.java` | 22 | 洗髓丹：清除全部活跃 Buff 并回退加成，恢复满 HP/MP |
| `DebuffCleanEffect.java` | 28 | 驱散负面状态 |
| `PurificationEffect.java` | 22 | 净化丹：清除「重伤」并回血 |
| `TribulationPillEffect.java` | 16 | 渡劫丹：突破时 +10% 成功率（服用时提示） |
| `TeleportEffect.java` | 62 | 传送符：无视路径直达指定地点（保留境界校验） |

### 4.8 游历事件框架 `com/mtxgdn/game/explorationevent`（3）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `ExplorationEvent.java` | 58 | 事件抽象基类（命名空间、key、权重；`execute(player, playerService, itemService, random, result, log)`） |
| `ExplorationEventRegistry.java` | 92 | 事件注册表；按权重随机抽取，并按地界偏置调整权重 |
| `ExplorationEventScanner.java` | 149 | 反射扫描 `data.mtxgdn.explorationevent` 包并注册 |

### 4.9 秘境框架 `com/mtxgdn/game/secretrealm`（3）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `SecretRealm.java` | 64 | 秘境基类（key、最低境界、冷却毫秒） |
| `SecretRealmRegistry.java` | 104 | 秘境注册表 |
| `SecretRealmScanner.java` | 149 | 反射扫描 `data.mtxgdn.secretrealm` 包并注册 |

### 4.10 称号 `com/mtxgdn/game/title`（1）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `TitleRegistry.java` | 98 | 预设称号注册表（多品质，含活动限定） |

### 4.11 玩法服务层 `com/mtxgdn/game/service`（30 + 1）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `CombatService.java` | 1220 | 回合制战斗核心：技能、暴击、灵根特效、PVE Boss、PVP 决斗 |
| `PlayerService.java` | 1153 | 玩家主服务：创建角色、属性、经验、金币、状态、在线/离线、改名等 |
| `SectService.java` | 1142 | 宗门：创建/申请/审批/任命/捐献/仓库/踢人/解散/升级/转让/宗门战/排行 |
| `WorldBossService.java` | 796 | 世界 Boss：全服共享血量（落库，无内存态无定时器）、惰性整池重建、伤害分红、超发熔断、挑战冷却 |
| `EconomyService.java` | 906 | 经济：竞拍行（限时竞价）、灵庄（活期/定期+利息）、商店、签到、灵石提纯、回收 |
| `ItemService.java` | 829 | 背包与物品：upsert 式添加、使用、装备/卸下、灵石流转、容量 |
| `FarmService.java` | 882 | 农场：4–12 块地、8 种作物、浇水/施肥/病虫害、一键操作，受季节影响。**生长改为惰性结算**：无后台线程，玩家访问农田时按 `now - settled_time` 一次性推进（水分按时间差衰减、病虫害按区间累计概率 `1-(1-p)^n`、成熟即掷定品质并落库） |
| `MapService.java` | 1719 | 地图：地点与路线、移动耗时与并发 CAS、安全区与危险度判定、缓存热重载、**野外分片世界**（`getLocation()` 对野外 id 现算节点、`getNeighbors()` 现算邻接、首达写入分片文件并固化、原地补货与回收未探索分片）。**野外不进数据库**：`map_locations` 只装已知世界 |
| `TradeService.java` | 602 | 坊市：挂单（默认 72 小时）、成交（5% 手续费）、到期回收、成交价格历史与清理任务 |
| `SecretRealmService.java` | 596 | 秘境探索、Boss 战、组队副本 |
| `RealmService.java` | 515 | 境界与突破：天劫类型、成功率、失败惩罚、转世 |
| `TechniqueService.java` | 416 | 功法：学习/装备（3 门同时运转）/升级 |
| `DailyService.java` | 393 | 每日玩法：天象、晨修·紫气东来、今日机缘、灵根共鸣 |
| `SkillService.java` | 365 | 技能：默认技能初始化、学习、熟练度升级 |
| `CraftingService.java` | 345 | 炼丹炼器：默认配方初始化、配方驱动制造 |
| `BuffService.java` | 444 | Buff 生命周期与属性加成计算。**过期改为惰性 + 低频兜底**：访问 Buff 时按玩家即时回退，离线玩家由低频任务回收（`buff.expire_sweep_seconds`，默认 60 秒），回退与删行绑定在同一条条件 DELETE 上（幂等认领，不重复扣减） |
| `CaveService.java` | 314 | 洞府：灵力收集、升级 |
| `FormationService.java` | 311 | 阵法：布置、加成、升级、移除 |
| `RedeemCodeService.java` | 307 | 兑换码生成与核销 |
| `FriendService.java` | 260 | 好友：申请/接受/拒绝/删除、每日拜访 |
| `NewbieGuideService.java` | 260 | 新手引导步骤与奖励 |
| `EnhanceService.java` | 236 | 装备强化（+15，含失败降级/碎裂） |
| `TitleService.java` | 225 | 集中检查并自动授予「玩法可获得」称号（幂等） |
| `SeasonService.java` | 214 | 全局季节：`calendar`（按真实月份）或 `cycle`（按小时）更替，更替时全服广播 |
| `ExplorationService.java` | 211 | 游历：解析玩家所在地点、抽事件、结算收益 |
| `EquipmentFixService.java` | 184 | 启动时修复装备数据一致性 |
| `TeamService.java` | 178 | 组队：创建/邀请/接受/退出，进入团队副本 |
| `EnergyService.java` | 165 | 能量转化：物品 ↔ 能量等价互转，支持插件注册自定义物品能量值 |
| `ChatService.java` | 158 | 聊天：世界频道与私聊，消息持久化 |
| `OfflineRewardService.java` | 122 | 离线收益（50% 效率，上限 8 小时） |
| `HeartDemonService.java` | 110 | 心魔：修炼时概率触发修为倒退 |
| `combat/BattleLogGenerator.java` | 124 | 战斗过程日志/文案生成 |

### 4.12 适配器层 `com/mtxgdn/adapter`（6）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `AdapterManager.java` | 370 | 统一适配器生命周期：按 `config/adapters.json` 启停、`setGameWebSocketApp()` 注入广播通道、`stopAll()` |
| `AdapterConfigManager.java` | 111 | `adapters.json` 的加载、保存、热重载 |
| `AdapterConfig.java` | 72 | 单个适配器配置项（id/type/enabled/config 映射） |
| `AdaptersConfig.java` | 28 | `adapters.json` 顶层结构 |
| `AdapterFactory.java` | 13 | 第三方适配器工厂 SPI（插件在 `onEnable` 注册） |
| `ManagedAdapter.java` | 15 | 第三方适配器实例接口 |

### 4.13 插件系统 `com/mtxgdn/plugin`（13）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `Plugin.java` | 19 | 插件基接口（`onLoad`/`onEnable`/`onDisable` 等） |
| `PluginManager.java` | 538 | 扫描 `./plugins` 目录、加载 jar、初始化与生命周期管理、文件热替换监听 |
| `PluginContext.java` | 419 | 插件运行上下文：注册扩展内容（命令/物品/事件/秘境/称号/权限/REST/Web UI/WS）、访问服务、读写插件数据目录 |
| `PluginWebManager.java` | 353 | 插件 Web 管理器（WebUI「插件管理」页后端） |
| `PluginSwitches.java` | 344 | 插件开关配置：独立文件 `config/plugins.yml`，含模板释放与旧键迁移 `migrateLegacyKeys()` |
| `BuiltInPluginRegistry.java` | 280 | 内置插件注册表：注册、`init()`、`enableAll()`、`disableAll()`、运行时单模块启停、`isEnabledOrUnknown()` 门控判定 |
| `PluginMaker.java` | 201 | 插件生成器：按内置模板生成可编译的插件项目骨架（CLI 入口 `--plugin-make`） |
| `PluginCatalog.java` | 108 | **内置插件清单唯一登记处**：25 个稳定玩法模块 + 2 个实验性插件 + 4 个消息通道适配器 |
| `PluginInfo.java` | 27 | 插件元数据（由 `plugin.json` 或 `@PluginMeta` 解析） |
| `PluginMeta.java` | 24 | 主类注解式元数据声明 |
| `PluginClassLoader.java` | 59 | 插件专用 `URLClassLoader` |
| `BuiltInPlugin.java` | 37 | 内置插件基类（jar 内玩法模块继承此类） |
| `PluginMakerSelfTest.java` | 81 | 插件模板加载/替换的独立自测程序（不依赖 Grizzly） |

**`com/mtxgdn/plugin/builtin`（26）— 25 个玩法模块 + 适配器通道**

每个玩法模块约 31 行，职责为「声明模块名 + 默认开关 + 绑定的服务/指令集合」：

| 文件 | 模块名 | 说明 |
|---|---|---|
| `ExplorationPlugin.java` | `exploration` | 探索/游历系统 |
| `SecretRealmPlugin.java` | `secret_realm` | 秘境系统 |
| `TradePlugin.java` | `trade` | 坊市交易 |
| `SectPlugin.java` | `sect` | 宗门系统 |
| `FarmPlugin.java` | `farm` | 农场系统 |
| `CavePlugin.java` | `cave` | 洞府系统 |
| `ChatPlugin.java` | `chat` | 聊天系统 |
| `FriendPlugin.java` | `friend` | 好友系统 |
| `RankPlugin.java` | `rank` | 排行系统 |
| `SearchPlugin.java` | `search` | 玩家搜索 |
| `MapPlugin.java` | `map` | 地图系统 |
| `TeamPlugin.java` | `team` | 团队系统 |
| `TitlePlugin.java` | `title` | 称号系统 |
| `PlayerPlugin.java` | `player` | 角色基础 |
| `CultivationPlugin.java` | `cultivation` | 修炼/突破/转世/天象 |
| `SkillPlugin.java` | `skill` | 技能系统 |
| `PvpPlugin.java` | `pvp` | 战斗系统 |
| `WorldBossPlugin.java` | `world_boss` | 世界 Boss（全服共享血量、伤害分红、贡献榜） |
| `InventoryPlugin.java` | `inventory` | 背包/装备 |
| `EconomyPlugin.java` | `economy` | 经济系统 |
| `EnergyPlugin.java` | `energy` | 能量系统 |
| `TechniquePlugin.java` | `technique` | 功法系统 |
| `CraftingPlugin.java` | `crafting` | 炼丹锻造 |
| `BuffPlugin.java` | `buff` | Buff 系统 |
| `FormationPlugin.java` | `formation` | 阵法系统 |
| `AdapterChannelPlugin.java` | `onebot`/`qq`/`minecraft`/`minecraft-player` | 把 `adapters.json` 中的通道类型注册为内置插件（类型级总开关），60 行 |

**`com/mtxgdn/plugin/experimental`（2）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `SectWarPlugin.java` | 43 | 实验性：宗门战（默认关闭，`experimental_sect_war`） |
| `EnergyExchangePlugin.java` | 44 | 实验性：能量转化/等价交换（默认开启，`experimental_energy_exchange`） |

**`com/mtxgdn/plugin/event`（3）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `PluginEventManager.java` | 181 | 插件事件总线（单例），含 `fireQuietly()` |
| `PluginEvent.java` | 95 | 事件基类与 `builder()`（`Type.SERVER_READY` 等） |
| `PluginEventHandler.java` | 22 | 事件处理器接口 |

**`com/mtxgdn/plugin/gui`（13）— 插件制作器**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `PluginMakerGUI.java` | 362 | 插件制作工具主窗口（入口 `--plugin-make-gui`） |
| `CodeGenerator.java` | 554 | 把 `PluginConfig` 转换为完整插件工程文件 |
| `CodeGeneratorTest.java` | 94 | 生成流程测试（不依赖 GUI） |
| `EntriesPanel.java` | 355 | 通用注册项编辑面板（物品/事件/秘境/指令） |
| `TriggerPanel.java` | 250 | 触发器管理面板 |
| `TriggerEditorDialog.java` | 176 | 触发器编辑对话框 |
| `BasicConfigPanel.java` | 188 | 基础信息面板（名称、版本、作者、GroupId、ArtifactId、主类、输出目录） |
| `Trigger.java` | 167 | 单个触发器 |
| `TriggerConfig.java` | 106 | 事件触发器配置条目 |
| `RegistrableEntry.java` | 150 | 可注册项模型 |
| `PluginConfig.java` | 143 | 用户在 GUI 中设置的全部内容 |
| `Theme.java` | 324 | 主题系统 |
| `MiniJson.java` | 275 | 轻量 JSON 读写（不依赖第三方库） |

### 4.14 多端接入

**`com/mtxgdn/onebot`（14）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `OneBotWebSocketServer.java` | 950 | OneBot WebSocket 服务端（正向/反向 WS）与 HTTP 模式 |
| `OneBotScreenshotBot.java` | 471 | 截图 + Robot 键鼠模拟模式（控制 QQ 窗口，实现真人操作效果） |
| `BlacklistService.java` | 222 | 黑名单服务（自动禁言联动） |
| `QqOfficialBindingService.java` | 222 | QQ 官方平台绑定服务（独立于 OneBot 绑定表） |
| `OneBotGroupConfigService.java` | 173 | 群组配置服务（自动禁言开关、禁言天数） |
| `QqBindingService.java` | 102 | 跨平台绑定查询（先 OneBot 表，再 QQ官方表），仅用于展示 |
| `QqBinding.java` | 45 | OneBot 绑定实体 |
| `Blacklist.java` | 78 | 黑名单条目（`qqNumber` 与 `userId` 二选一） |
| `OneBotGroupConfig.java` | 54 | 群组配置条目 |
| `OneBotSession.java` | 23 | OneBot 连接抽象接口，解耦传输层（WS/HTTP） |
| `OneBotAccountFlow.java` | 9 | 账号流程接口 |
| `OneBotAccountFlowClient.java` | 11 | 基于 `OneBotSession` 的账号流程接口（不依赖 Grizzly） |
| `OneBotMessageSender.java` | 7 | 消息发送接口 |
| `OneBotMessageSenderClient.java` | 9 | 基于 `OneBotSession` 的消息发送接口 |

**`com/mtxgdn/qq`（3）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `QqOfficialAdapter.java` | 1736 | QQ 官方开放平台（QQ 机器人）适配器，实现 `OneBotSession` / `OneBotMessageSenderClient` / `OneBotAccountFlowClient` |
| `QqWebhookServer.java` | 201 | Webhook 回调服务 |
| `QqButton.java` | 50 | 「消息交互」按钮（Keyboard Button）模型 |

**`com/mtxgdn/minecraft`（2）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `MinecraftMotdServer.java` | 575 | MOTD 服务器（服务器列表预览、在线人数、跳转提示），配置段 `motd.*` |
| `VarInt.java` | 61 | Minecraft 协议 VarInt 编解码 |

**`com/mtxgdn/minecraft/adapter`（12）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `MinecraftClient.java` | 1098 | Minecraft 协议客户端，以玩家身份连接 MC 服务器（仅离线模式）；含 26.2 的包编解码与可单测的纯函数 |
| `MinecraftPlayerAdapter.java` | 414 | 玩家直连通道适配器 |
| `McProtocolMapper.java` | 398 | 协议版本映射器（含 26.2 / 协议 776 的包号与布局开关） |
| `MinecraftServerProcess.java` | 337 | 内嵌 MC 服务端进程管理（jar 路径、内存、就绪超时、额外 JVM 参数） |
| `MinecraftAdapter.java` | 251 | 内嵌服务器通道适配器（消息收发 + 指令处理） |
| `McNbt.java` | 222 | 最小 NBT 读取器 + 聊天组件转纯文本（1.20.3+ 组件在网络上是 NBT 而非 JSON） |
| `MinecraftAuthService.java` | 204 | MC 侧认证/绑定校验 |
| `MinecraftPlayerBindingService.java` | 94 | MC 玩家与游戏账号绑定 |
| `MinecraftCommandContext.java` | 63 | MC 指令上下文 |
| `MinecraftPlayerBinding.java` | 25 | 绑定实体 |
| `MinecraftMessageSender.java` | 23 | 消息发送接口 |
| `McCommandResult.java` | 20 | MC 指令处理结果 |

**`com/mtxgdn/minecraft/agent`（1）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `XiuxianAgent.java` | 258 | Java Agent：MC 启动时注入，反射注册 `/xiuxian` 到 Brigadier `CommandDispatcher` |

**`com/mtxgdn/minecraft/plugin`（1，构建时排除）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `XiuxianBridgePlugin.java` | 101 | Bukkit 辅助插件主类：转发指令到服务端 `McCommandResource`。由 `build-mc-plugin.bat` 单独编译打包 |

### 4.15 指令层 `com/mtxgdn/onebot/command`（86）

**上下文（2）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `OneBotCommandContext.java` | 55 | 服务端 Grizzly 会话下的指令上下文 |
| `OneBotClientCommandContext.java` | 69 | 基于 `OneBotSession` 的上下文（WS 客户端 / HTTP 模式） |

**`account/`（6）**

| 文件 | 行数 | 指令名 | 职责 |
|---|---:|---|---|
| `RegisterCommand.java` | 36 | `注册` / `register` | 账号注册（邮箱验证码） |
| `BindCommand.java` | 31 | `绑定` / `bind` | 游戏账号与 QQ 绑定 |
| `UnbindCommand.java` | 30 | `解绑` / `unbind` | 解绑 |
| `ChangePasswordCommand.java` | 27 | `改密` / `changepassword` / `changepwd` | 修改密码 |
| `DeleteAccountCommand.java` | 27 | `注销` / `deleteaccount` | 注销账号 |
| `HelpCommand.java` | 69 | `帮助` / `help` | 分类帮助（受模块门控与权限过滤） |

**`admin/`（18）** — 均需管理权限

| 文件 | 行数 | 指令名 | 职责 |
|---|---:|---|---|
| `DebugCommand.java` | 274 | `debug` / `调试` | 调试工具（含 `admin/debug/list`、`admin/debug/test` 路由） |
| `EnergyAdminCommand.java` | 318 | `能量管理` / `energyadmin` / `energymanage` | 能量管理（`admin/energy/*` 路由；查看用 `admin.status`，增删改用 `admin.users.manage`） |
| `EditPlayerCommand.java` | 121 | `编辑玩家` / `editplayer` / `修改玩家` | 编辑玩家数据（权限 `admin.users.manage`） |
| `TraceCommand.java` | 116 | `轨迹` / `trace` / `玩家轨迹` | 玩家行为轨迹 |
| `RestartCommand.java` | 110 | `重启` / `restart` | 重启服务端 |
| `GiveCommand.java` | 101 | `发放` / `give` | 按玩家ID发放金币/灵力/灵石/物品（仅私聊；权限 `admin.items.give`） |
| `SetRootCommand.java` | 91 | `修改灵根` / `setroot` / `设置灵根` | 设置灵根（权限 `admin.users.manage`） |
| `PlayersListCommand.java` | 84 | `玩家列表` / `players` / `playerslist` | 玩家列表 |
| `PlayerDetailCommand.java` | 83 | `玩家详情` / `playerinfo` / `playerdetail` | 玩家详情 |
| `ItemsQueryCommand.java` | 58 | `全物品列表` / `items` / `allitems` | 物品清单查询（分页） |
| `UsersRolesCommand.java` | 57 | `用户列表` / `users` / `userlist` | 用户与角色（权限 `admin.users.manage`） |
| `LogsCommand.java` | 55 | `日志` / `logs` / `服务器日志` | 查看日志 |
| `StatusCommand.java` | 50 | `服务器状态` / `serverstatus` | 服务器状态 |
| `EconomyStatsCommand.java` | 40 | `经济` / `economy` | 全服经济数据面板 |
| `ShutdownCommand.java` | 37 | `关闭服务端` / `shutdown` / `关闭服务器` | 关闭服务端 |
| `FixEquipmentCommand.java` | 30 | `修复装备` / `fixequip` | 修复装备数据 |
| `ClearPlayersDbCommand.java` | 25 | `cleardb_players` / `清除玩家数据` | 清空玩家数据（HTTP `cleardb_players`） |
| `ResetAllDbCommand.java` | 25 | `cleardb_all` / `重置全部数据` | 重置全部数据（HTTP `cleardb_all`） |

**`player/`（4）** — 模块 `player` / `title`

| 文件 | 行数 | 指令名 | 职责 |
|---|---:|---|---|
| `SpiritualRootCommand.java` | 119 | `灵根` / `spiritualroot` / `talent` | 查看灵根与效果 |
| `TitleCommand.java` | 115 | `称号` / `title` / `chenghao` | 称号查看与装备（模块 `title`） |
| `StatusCommand.java` | 50 | `状态` / `信息` / `status` / `info` | 查看角色状态 |
| `HealCommand.java` | 17 | `疗伤` / `heal` | 疗伤 |

**`cultivation/`（4）** 与 **`daily/`（2）** — 模块 `cultivation`

| 文件 | 行数 | 指令名 | 职责 |
|---|---:|---|---|
| `ReincarnateCommand.java` | 101 | `转世` / `reincarnate` | 金仙满级后自碎道基、转世重修 |
| `CultivateStopCommand.java` | 69 | `停止` / `stop` | 停止闭关 |
| `BreakthroughCommand.java` | 48 | `突破` / `breakthrough` | 突破（触发天劫） |
| `CultivateCommand.java` | 47 | `打坐` / `闭关` / `meditate` / `cultivate` | 开始闭关修炼 |
| `MorningCommand.java` | 24 | `晨修` / `morning` | 晨修·紫气东来 |
| `DailyCommand.java` | 24 | `天象` / `daily` | 今日天象 |

**`combat/`（4）** — 模块 `skill` / `pvp`

| 文件 | 行数 | 指令名 | 职责 |
|---|---:|---|---|
| `PvpCommand.java` | 151 | `挑战` / `pvp` | 挑战（模块 `pvp`） |
| `SkillsCommand.java` | 88 | `技能` / `skills` | 技能列表（模块 `skill`） |
| `BattleTacticCommand.java` | 59 | `战斗策略` / `战术` / `tactic` | 战斗策略设置（模块 `skill`） |
| `LearnSkillCommand.java` | 45 | `学习` / `learn` | 学习技能（模块 `skill`） |

**`pvp/`（1）** — 模块 `pvp`

| 文件 | 行数 | 指令名 | 职责 |
|---|---:|---|---|
| `PvpCommand.java` | 185 | `决斗` / `切磋` / `fight` / `战斗` / `讨伐` / `boss` | PVP 与 Boss 讨伐（含安全区限制） |

**`item/`（10）** — 模块 `inventory` / `buff`，另有两个无模块项

| 文件 | 行数 | 指令名 | 职责 |
|---|---:|---|---|
| `ItemGiveCommand.java` | 117 | `发放物品` / `giveitem` / `give_item` | 按玩家名发放物品（权限 `admin.items.give`；HTTP `admin/items/give`） |
| `RedeemCommand.java` | 112 | `兑换` / `redeem` | 兑换码核销（HTTP `redeem`） |
| `BuffCommand.java` | 49 | `增益` / `buff` | 查看 Buff（模块 `buff`） |
| `EquippedCommand.java` | 49 | `已装备` / `equipped` | 查看已装备 |
| `BackpackCommand.java` | 43 | `背包` / `backpack` | 背包 |
| `ItemMapCommand.java` | 39 | `物品列表` / `itemmap` | 物品图鉴 |
| `ItemUseCommand.java` | 35 | `使用` / `itemuse` | 使用物品 |
| `EquipCommand.java` | 28 | `装备` / `equip` | 穿戴 |
| `UnequipCommand.java` | 25 | `卸下` / `unequip` | 卸下 |
| `EnhanceCommand.java` | 62 | `强化` / `enhance` | 装备强化 |

**`economy/`（10）** — 模块 `economy`（`ExchangeCommand` 属 `energy`）

| 文件 | 行数 | 指令名 | REST 路由 |
|---|---:|---|---|
| `ExchangeCommand.java` | 378 | `转化` / `exchange` / `convert` | `energy/convert`、`energy/exchange`、`energy/list`、`energy/status` |
| `AuctionCommand.java` | 211 | `拍卖` / `auction` | `economy/auction/items`、`/my`、`/create`、`/bid` |
| `BankCommand.java` | 205 | `灵庄` / `bank` | `economy/bank/info`、`/deposit`、`/withdraw` |
| `ShopCommand.java` | 99 | `商店` / `shop` | `economy/shop`、`economy/shop/buy` |
| `PurifyCommand.java` | 83 | `提纯` / `purify` / `灵石提纯` | `economy/purify` |
| `StoneExchangeCommand.java` | 80 | `灵石兑换` / `石兑` | — |
| `CurrencyExchangeCommand.java` | 79 | `货币兑换` / `币兑` / `currency_exchange` | — |
| `RecycleCommand.java` | 79 | `回收` / `recycle` | `economy/recycle` |
| `CultivateBoostCommand.java` | 66 | `修炼加速` / `boost` | `economy/cultivate-boost` |
| `SignInCommand.java` | 58 | `签到` / `signin` | `economy/signin` |

**`market/`（6）** — 模块 `trade`

| 文件 | 行数 | 指令名 | REST 路由 |
|---|---:|---|---|
| `MarketPriceCommand.java` | 117 | `行情` / `price` | `market/price` |
| `MarketCommand.java` | 51 | `坊市` / `market` | — |
| `MyListingsCommand.java` | 43 | `我的挂单` / `mylistings` | — |
| `ListItemCommand.java` | 30 | `上架` / `list` | — |
| `BuyItemCommand.java` | 20 | `购买` / `buy` | — |
| `CancelListingCommand.java` | 20 | `撤单` / `cancellisting` | — |

**`exploration/`（3）** — 模块 `exploration` / `secret_realm`

| 文件 | 行数 | 指令名 | 职责 |
|---|---:|---|---|
| `ExploreCommand.java` | 73 | `游历` / `explore` | 游历探索（模块 `exploration`） |
| `SecretEnterCommand.java` | 52 | `进入秘境` / `secret_enter` | 进入秘境（模块 `secret_realm`） |
| `SecretAreasCommand.java` | 46 | `秘境` / `secret` | 秘境列表（模块 `secret_realm`） |

**其余单文件模块**

| 文件 | 行数 | 指令名 | 模块 | REST 路由 |
|---|---:|---|---|---|
| `sect/SectCommand.java` | 440 | `宗门` / `sect` | `sect` | `sect/list`、`sect/info`、`sect/info/{sectId}` |
| `worldboss/WorldBossCommand.java` | 353 | `世界boss` / `worldboss` | `world_boss` | `worldboss/list`、`worldboss/challenge`、`admin/world-boss`、`admin/world-boss/refresh` |
| `farm/FarmCommand.java` | 273 | `种田` / `farm` | `farm` | — |
| `formation/FormationCommand.java` | 273 | `阵法` / `formation` | `formation` | `formation/info`、`/list`、`/place`、`/upgrade`、`/remove` |
| `cave/CaveCommand.java` | 227 | `洞府` / `cave` | `cave` | `cave/info`、`/create`、`/collect`、`/meditate`、`/levelup` |
| `technique/TechniqueCommand.java` | 227 | `功法` / `technique` / `tj` | `technique` | `technique/list`、`/my`、`/learn`、`/equip`、`/unequip`、`/upgrade` |
| `crafting/CraftCommand.java` | 220 | `合成` / `炼制` / `craft` | `crafting` | `crafting/recipes`、`crafting/craft` |
| `map/MapCommand.java` | 279 | `地图` / `map` | `map` | — |
| `map/WildernessCommand.java` | 151 | `野外` / `wilderness` / `wild` | `map` | — |
| `map/GatherCommand.java` | 30 | `采集` / `gather` / `collect` | `map` | — |
| `social/FriendCommand.java` | 166 | `好友` / `friend` | `friend` | — |
| `team/TeamCommand.java` | 129 | `组队` / `team` | `team` | — |
| `social/RankCommand.java` | 61 | `排行` / `排行榜` / `rank` / `rank2` | `rank` | — |
| `social/PlayerSearchCommand.java` | 59 | `查人` / `查找` / `search` / `playersearch` | `search` | — |
| `social/PrivateMessageCommand.java` | 52 | `私聊` / `msg` | `chat` | — |
| `qq/QqButtonCommand.java` | 81 | `按钮` / `button` / `互动按钮` | — | — |

### 4.16 REST 层 `com/mtxgdn/rest`（15）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `AdminResource.java` | 3695 | 管理后台 API（`@Path("/admin")`，100 个方法级 `@Path` 映射）：登录/状态、插件管理（list/reload/unload/enable/disable/load/watcher）、日志、关机、角色与权限组 CRUD、用户管理、玩家管理（列表/详情/发放/编辑/改名/灵根/称号）、物品与境界字典、轨迹、通用表 CRUD（`db/tables/*`）、备份与导入、兑换码、统计、黑名单、OneBot 群配置、新手奖励配置、系统信息、公告、适配器管理、玩家数据导出、OAuth 配置、地图管理（`map/locations` 列表与改名、`POST/DELETE map/locations`、`map/connections`、`map/event-bias`，均需 `admin.database.access`）、**野外分片视图（`GET map/wilderness`、`POST map/wilderness/restock`，只补货不删除）**、坊市挂单与行情 |
| `GameResource.java` | 2494 | 玩家侧 REST API（`@Path("/game")`）：角色、突破、修炼、秘境、游历、疗伤、物品与背包、装备、技能、PVP、状态、玩家搜索、灵根、每日/天象、坊市、功法、炼制、聊天、排行、好友、宗门、地图、称号、组队、副本、Buff、农场、季节、玩家数据导出/还原 |
| `Auth.java` | 384 | 账号 REST（`@Path("/auth")`）：注册、发验证码、登录、改密、登出、忘记密码（发码/重置）、注销账号。含 V5 修复的完整登录校验 |
| `InstallResource.java` | 388 | 安装向导 API（`@Path("/install")`）：`status`、`configure`、`reset`、`restart` |
| `AdminAuthFilter.java` | 353 | 管理台 JWT 校验过滤器 |
| `UnifiedRestResource.java` | 187 | 指令统一 REST 分发器：挂在 `/game/{path:.*}`，把 `CommandRegistry` 收集的 `RouteDefinition` 映射为 HTTP 端点 |
| `OAuthResource.java` | 194 | OAuth2（`@Path("/auth/oauth")`）：`token`、`introspect`、`me`、`protected/status` |
| `ModuleGateFilter.java` | 105 | 内置玩法模块的 HTTP 接口门控（与 `Command.isModuleEnabled()` 同源判定） |
| `PermissionFilter.java` | 77 | `@RequirePermission` 权限过滤器 |
| `JwtAuthFilter.java` | 72 | 玩家 JWT 校验过滤器（V9：按路径段匹配白名单前缀） |
| `McCommandResource.java` | 75 | MC 辅助插件转发指令的 REST 入口（`@Path("/mc-command")`） |
| `EntityBufferFilter.java` | 35 | 请求体缓冲过滤器 |
| `GenericExceptionMapper.java` | 16 | 通用异常 → 统一错误响应 |
| `EofExceptionMapper.java` | 14 | `EOFException` → 静默响应（连接中断） |
| `Test.java` | 14 | `@Path("/test")` 探活端点 |

### 4.17 服务器与账号服务

**`com/mtxgdn/server`（3）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `AdminStaticFileHandler.java` | 98 | 静态文件处理器（挂 `/admin` → `webadmin/`，`/install` → `install/`） |
| `ServerConfigurator.java` | 89 | Grizzly 调优：IO/工作线程数、WebSocket 单帧上限（`network.ws_max_message_size`） |
| `ServerShutdownHook.java` | 52 | 关机钩子容器（任务按注册顺序执行） |

**`com/mtxgdn/service`（3）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `UserService.java` | 435 | 账号服务：注册、登录、密码、锁定策略、邮箱验证 |
| `OAuthService.java` | 225 | OAuth2 `client_credentials` 支持 |
| `VerificationCodeService.java` | 136 | 邮箱验证码生成/校验/限流 |

**`com/mtxgdn/websocket`（1）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `GameWebSocketApp.java` | 1761 | 游戏主 WebSocket 应用：注册在 `/`，处理登录鉴权、指令分发、广播、在线人数、会话管理；单帧上限默认 64KB 可配 |

### 4.18 工具层 `com/mtxgdn/util`（14）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `BuildInfo.java` | 68 | **版本号的唯一读取入口**：从 `build-info.properties`（Maven 资源过滤注入，主来源）或 JAR manifest 的 `Implementation-Version`（兜底）解析 `pom.xml` 的 `<version>`；`getVersion()` 纯净版本号（Maven 坐标用），`getDisplayVersion()` 补 `v` 前缀（展示用）；都读不到时返回 `unknown` 而不谎报 |
| `AppConfig.java` | 196 | 配置读取（`config/application.yml`），含 `getJarDir()`、`update()`（支持补建缺失父节） |
| `MySqlLauncher.java` | 431 | 本地 MySQL 自动拉起（`database.url` 指向本机时） |
| `YamlFileEditor.java` | 349 | YAML 行级编辑器：只改值，保留注释、空行与缩进 |
| `GameLogger.java` | 220 | 分级日志、着色、文件输出、内存环形缓冲（管理台日志页） |
| `TokenBlacklist.java` | 209 | JWT 吊销名单（内存 + 数据库，支持 `loadFromDatabase()`、`cleanupExpired()`） |
| `PlayerActionLogger.java` | 177 | 玩家行为日志写入 |
| `JwtUtil.java` | 160 | JWT 签发/校验；安装模式下使用临时密钥并 `warnIfAutoGenerated()` |
| `StatsCollector.java` | 140 | 消息与指令调用统计（内存） |
| `RateLimiter.java` | 124 | 轻量内存滑动窗口限流器（含 `cleanupExpired()`） |
| `LangManager.java` | 76 | i18n 语言文件加载与合并（插件可注入翻译） |
| `EmailService.java` | 41 | 邮件发送（Jakarta Mail） |
| `OneBotLogger.java` | 42 | OneBot 通道专用日志 |
| `TextSanitizer.java` | 37 | 输入净化（防注入/越权字符） |

### 4.19 客户端 SDK 与演示

**`com/mtxgdn/client`（5）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `GameApi.java` | 343 | 游戏接口客户端封装 |
| `GameWebSocketClient.java` | 134 | WebSocket 客户端 |
| `ApiClient.java` | 115 | HTTP 客户端基类（JDK HttpClient，持有 token） |
| `ApiConfig.java` | 48 | 客户端配置（服务端地址等） |
| `AuthApi.java` | 35 | 认证接口客户端 |

**`com/mtxgdn/demo`（1）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `DemoClient.java` | 1156 | 交互式演示客户端（`--demo` 模式调用，服务就绪后启动） |

### 4.20 权限与玩家数据

**`com/mtxgdn/permission`（3）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `PermissionService.java` | 791 | RBAC：权限组（角色）与权限分配/校验，`hasPermission()` |
| `PermissionCode.java` | 100 | 权限码枚举（71 个内置权限码，含 `admin.items.give`、`admin.announce`） |
| `RequirePermission.java` | 10 | 方法级权限注解（配合 `PermissionFilter`） |

**`com/mtxgdn/playerdata`（2）**

| 文件 | 行数 | 职责 |
|---|---:|---|
| `PlayerDataService.java` | 472 | 玩家数据文件化：一用户一个 JSON、变更定期落盘（`auto-sync-seconds`）、导出/还原、`shutdown()` 收尾 |
| `PlayerData.java` | 30 | 单个玩家的完整数据快照 |

### 4.21 内容数据包 `data.mtxgdn.*`

这三个包是**纯内容数据**（命名空间统一为 `mtxgdn`），启动时由对应 Scanner 反射注册。

**`data/mtxgdn/item`（115）** — 每个文件 10–21 行，形如：

```java
public class HealingPill extends Item {
    public HealingPill() {
        super("mtxgdn", "healing_pill", ItemType.CONSUMABLE, ItemRarity.COMMON,
             99, 10, true, 0, HealEffect.hp(50));
    }
}
```

| 类型 | 数量 | 说明 |
|---|---:|---|
| `CONSUMABLE` | 23 | 丹药/符箓类消耗品 |
| `MATERIAL` | 61 | 材料（其中 8 个是「以 MATERIAL 登记」的种子，如 `dragon_fruit_seed`） |
| `SEED` | 8 | 种子 |
| `TREASURE` | 11 | 装备/法宝（含 3 个阵法道具） |
| `QUEST` | 4 | 任务道具 |
| `CURRENCY` | 4 | 灵石四档 |
| `SKILL_BOOK` | 3 | 技能书 |
| 基类 | 1 | `FormationItem.java`（21 行）——**无无参构造**，会被 `ItemScanner` 记入「失败：缺少无参构造」，属正常现象 |

> 完整物品清单（文件名 / key / 类型 / 品质）见**附录 A**。

**`data/mtxgdn/explorationevent`（13）** — 每个文件 26–81 行，`super("mtxgdn", "<key>", <权重>)`

| 文件 | key | 权重 | 文件 | key | 权重 |
|---|---|---:|---|---|---:|
| `TreasureEvent.java` | `treasure` | 25 | `SeedEvent.java` | `seed` | 12 |
| `MonsterEvent.java` | `monster` | 25 | `MaterialEvent.java` | `material` | 10 |
| `HerbEvent.java` | `herb` | 15 | `MerchantEvent.java` | `merchant` | 10 |
| `CultivatorEvent.java` | `cultivator` | 13 | `RuinsEvent.java` | `ruins` | 10 |
| `MysteryEvent.java` | `mystery` | 8 | `SpiritSpringEvent.java` | `spirit_spring` | 8 |
| `TrapEvent.java` | `trap` | 7 | `NothingEvent.java` | `nothing` | 5 |
| `StrangeTowerEvent.java` | `strange_tower` | 4 | | | |

**`data/mtxgdn/secretrealm`（10）** — 每个文件 7–8 行，`super("mtxgdn", "<key>", <最低境界>, <冷却毫秒>)`

| 文件 | key | 最低境界 | 冷却 | 文件 | key | 最低境界 | 冷却 |
|---|---|---:|---:|---|---|---:|---:|
| `WildGrassland.java` | `wild_grassland` | 0 | 120 s | `ImmortalCave.java` | `immortal_cave` | 5 | 600 s |
| `DarkForest.java` | `dark_forest` | 1 | 180 s | `HunyuanMansion.java` | `hunyuan_mansion` | 6 | 900 s |
| `BeastMountain.java` | `beast_mountain` | 2 | 240 s | `TaiXuPalace.java` | `taixu_palace` | 7 | 1200 s |
| `AncientRuins.java` | `ancient_ruins` | 3 | 300 s | `NineHeavensPlatform.java` | `nine_heavens_platform` | 4 | 480 s |
| `ThunderValley.java` | `thunder_valley` | 3 | 300 s | `AncientBattlefield.java` | `ancient_battlefield` | 4 | 420 s |

**`data/mtxgdn/lang/zh_cn.json`（21 KB）** — 语言文件，键空间：

| 前缀 | 内容 |
|---|---|
| `item.<key>.name` / `.desc` | 全部物品的名称与描述 |
| `secretrealm.<key>.name` / `.desc` | 10 个秘境 |
| `explorationevent.<key>.name` / `.desc` | 游历事件（当前覆盖 treasure/monster/cultivator/ruins/herb/trap/nothing） |
| `game.*` | 系统消息（未授权、未绑定、无角色、闭关状态等） |
| `realm.*` | 境界名（qi_refining … immortal） |
| `spiritualroot.*` / `spiritualroot.effect.*` | 灵根名与效果名（17 种效果） |

---

## 5. 资源文件 `src/main/resources`（37 个文件）

### 5.1 配置

| 文件 | 大小 | 职责 |
|---|---:|---|
| `build-info.properties` | 3 行 | **构建期生成**（`filtering=true` 注入 `pom.xml` 的 `<version>`），不要手工改；由 `com.mtxgdn.util.BuildInfo` 读取，是版本号的主来源 |
| `application.yml` | 16.9 KB | **主配置模板**（首启释放到 `config/application.yml`）。段：`app`、`server`、`database`、`jwt`、`season`、`oauth2`、`player-data`、`admin`、`smtp`、`verify_code`、`onebot`、`minecraft`、`logging`、`inventory`、`market`、`buff`、`sect`、`performance`、`network`、`motd`（详见第 10 节） |
| `plugins.yml` | 3.7 KB | **内置插件开关模板**（释放到 `config/plugins.yml`）：25 个稳定模块 + 2 个实验性插件 + 4 个消息通道 |
| `built_in_plugins.yml` | 1.2 KB | 旧版内置模块开关（已全部注释），仅作只读兜底，由 `PluginSwitches.migrateLegacyKeys()` 迁移 |
| `config/blacklist.yml` | 757 B | 黑名单模板（`qqNumber` 或 `userId` + 原因 + 执行人 + 时间） |
| `config/onebot_group_config.yml` | 706 B | OneBot 群配置模板（`groupId` / `autoMuteEnabled` / `muteDurationDays`） |
| `plugin.yml` | 314 B | Bukkit 插件描述（`build-mc-plugin.bat` 打包进 `XiuxianBridge.jar`） |
| `game/realm_config.json` | 11.8 KB | 境界配置：`realms` 数组（29 个阶段，凡人 → 炼气期 → … → 金仙，含中期/后期细分）与 `cultivationPerSecond`（`baseValue=1`，`realmMultiplier` 为 11 档：1/1.5/2.5/4/6/10/18/32/55/90/150） |
| `game/wilderness_map.json` | 8.4 KB | **野外随机地图定义**（首启释放到 `config/game/wilderness_map.json`）：`nodeCount`/`refreshIntervalMinutes`/`idBase`(1000)/`entryLocationIds`(5·7·8·12·15)、`regions`（东荒/南泽/西漠/北寒/幽冥，含危险度区间与权重）、命名前后缀与描述模板、按危险度分档的 `resourcePools`（引用真实物品 key）、`firstVisitReward`/`teleport`/`gather` 参数。**这是"野外资源随机"的唯一开关** |

### 5.2 安装向导前端

| 文件 | 大小 | 职责 |
|---|---:|---|
| `install/index.html` | 13.5 KB | 安装向导单页（管理员账号、数据库、JWT 密钥配置），由 `AdminStaticFileHandler` 挂在 `/install` |

### 5.3 管理后台前端 `webadmin/`（22 个文件）

| 文件 | 大小 | 职责 |
|---|---:|---|
| `index.html` | 23.8 KB | 控制台单页外壳，16 个导航页签：`overview`、`players`、`map`、`statsMessages`、`statsCommands`、`database`、`users`、`logs`、`traces`、`redeemCodes`、`titles`、`market`、`adapters`、`plugins`、`oauth`、`myProfile`（默认隐藏） |
| `assets/css/styles.css` | 24.1 KB | 修仙风主题样式 |
| `assets/js/app.js` | 2.3 KB | 应用引导与路由 |
| `assets/js/api.js` | 4.1 KB | 后端 API 封装（含 JWT 头、错误处理） |
| `assets/js/utils.js` | 6.9 KB | 通用工具（格式化、DOM、提示） |
| `assets/js/modules/overview.js` | 1.1 KB | 概览页 |
| `assets/js/modules/login.js` | 2.3 KB | 登录页 |
| `assets/js/modules/layout.js` | 6.3 KB | 布局/导航 |
| `assets/js/modules/players.js` | 11.2 KB | 玩家管理 |
| `assets/js/modules/database.js` | 11.9 KB | 通用表 CRUD |
| `assets/js/modules/map.js` | 22.6 KB | 地图管理：概览统计（已知世界地点/连接/区域/安全区 + 野外节点 + 险地）、地点表格（危险度徽标、🌿 野外标记）、世界脉络树、地点增删改、连接与事件偏置编辑器、野外刷新 |
| `assets/js/modules/market.js` | 7.0 KB | 坊市挂单与行情 |
| `assets/js/modules/redeemCodes.js` | 12.3 KB | 兑换码 |
| `assets/js/modules/plugins.js` | 8.1 KB | 插件管理（内置模块启停） |
| `assets/js/modules/adapters.js` | 8.2 KB | 适配器管理 |
| `assets/js/modules/users.js` | 7.6 KB | 用户与角色 |
| `assets/js/modules/oauth.js` | 6.2 KB | OAuth2 配置 |
| `assets/js/modules/titles.js` | 5.5 KB | 称号管理 |
| `assets/js/modules/stats.js` | 5.3 KB | 统计（消息/指令） |
| `assets/js/modules/traces.js` | 3.0 KB | 玩家轨迹 |
| `assets/js/modules/logs.js` | 2.8 KB | 日志查看 |
| `assets/js/modules/myProfile.js` | 3.9 KB | 我的资料 |

### 5.4 插件模板 `plugin-template/`（5）

| 文件 | 大小 | 职责 |
|---|---:|---|
| `Main.java.template` | 1.8 KB | 插件主类模板 |
| `HelloCommand.java.template` | 1.5 KB | 示例指令模板 |
| `DemoItem.java.template` | 1.3 KB | 示例物品模板 |
| `plugin.json.template` | 155 B | 插件元数据模板 |
| `pom.xml.template` | 2.0 KB | 插件工程 Maven 模板 |

> 由 `PluginMaker` / `CodeGenerator` 读取并做占位符替换，生成可编译的插件骨架。

---

## 6. 测试源码 `src/test/java`（34 个文件）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `TestBase.java` | 21 | 测试基类：初始化共享 SQLite 测试库与 JWT |
| `common/command/CommandNameConflictTest.java` | 144 | 指令名唯一性回归：4 组历史冲突（`发放`/`give`、`灵根`、`物品列表`、`status`）的归属，以及发放类指令的权限码 |
| `security/AdminWritePermissionTest.java` | 263 | `admin.status` 只读性回归：读/写权限划分的 3 条不变量 + 具体指令与路由的权限归属 |
| `MultiPlayerFullTest.java` | 607 | 全面集成测试（SQLite，模拟 5 名玩家完整交互） |
| `game/service/TradeListingExpiryTest.java` | 552 | 坊市挂单时限 + 成交价格历史回归 |
| `game/service/WorldBossTest.java` | 475 | 世界 Boss 回归：池子生命周期（生成数量 / TTL 内稳定 / 到期换批 / 刷新换批）、**血量全服共享**（A 打完落库、B 从剩余血量起算）、**伤害分红精确性**（`灵石 == 预算 × 伤害 / 最大血量` 逐位相等）、**超发熔断**（阈值内原样、超阈值折算到 `阈值/10..阈值/5`）、**挑战冷却**（期内被拒 / 期后可再战）、击杀归属与三种拒绝（已死 / 境界差距 / 闭关）**均不扣血**，外加战报灵石数与实际入账一致性校验 |
| `game/service/FarmLazySettlementTest.java` | 262 | 农田惰性结算回归：无后台线程也能成熟、水分按时间差衰减、离线枯萎取精确穿零点、重复访问幂等、收获前先结算、只结算被访问玩家，以及"不得再引入后台调度器"的防回归锁 |
| `game/service/BuffLazyExpiryTest.java` | 184 | Buff 惰性过期回归：读取即回退属性、重复访问只回退一次、刷新时长的增益不被误删、离线玩家由低频兜底回收、回收间隔不得回到秒级 |
| `game/service/TradeServiceConcurrencyTest.java` | 286 | 坊市并发回归 |
| `game/service/MapServiceTest.java` | 309 | 地图系统测试 |
| `game/service/MapWildernessTest.java` | 679 | 地图新能力测试：危险度分级与安全区不变式、探索进度与首达奖励、传送点解锁与计价、采集与冷却、险地 PK 掠夺，以及野外分片世界——**地形确定性**、**id 编码往返**、**分片四邻互通**、**野外不进数据库**（行数不变式）、**分片文件落盘与固化**、**回收放过已探索分片**、**原地补货不动地形与探索记录** |
| `game/service/EconomyServiceConcurrencyTest.java` | 265 | 竞拍行并发回归 |
| `game/service/CultivationRestartTest.java` | 170 | 闭关状态在停服/重启后的行为回归 |
| `game/service/CombatServiceTest.java` | 116 | 战斗服务单元测试（SQLite） |
| `game/service/ItemServiceTest.java` | 47 | 物品服务 |
| `game/service/PlayerServiceTest.java` | 45 | 玩家服务 |
| `security/EndpointAlignmentTest.java` | 325 | 三通道（OneBot/QQ官方/MC）端点对齐回归 |
| `security/SecurityFixRegressionTest.java` | 237 | 安全修复回归（V1–V13） |
| `security/InputAndRateLimitTest.java` | 138 | 输入净化与多层限流语义（V2/V3/V10） |
| `plugin/PluginSwitchesTest.java` | 161 | `config/plugins.yml` 迁移与读写 |
| `plugin/BuiltInModuleSetTest.java` | 126 | 校验 `PluginCatalog` 与插件开关配置一致 |
| `plugin/BuiltInPluginRegistryTest.java` | 124 | 内置插件运行时启停（WebUI 启停语义） |
| `plugin/ExperimentalPluginGateTest.java` | 114 | 实验性插件门控 |
| `plugin/BuiltInPluginEnableOrderTest.java` | 81 | 启用内置插件时须先置启用态再调 `onLoad`/`onEnable` |
| `plugin/BuiltInPluginGateTest.java` | 70 | 模块停用后其指令被注册表门控 |
| `plugin/PluginSwitchTestSupport.java` | 61 | 插件开关测试的配置隔离工具 |
| `db/LegacySchemaUpgradeTest.java` | 127 | 老库升级回归 |
| `db/MarketIndexTest.java` | 101 | 坊市挂单表索引回归 |
| `adapter/AdapterManagerFactoryTest.java` | 88 | 第三方适配器 SPI（工厂注册 → `startOne` → `stopOne` → 注销） |
| `onebot/command/map/MapCommandRenderTest.java` | 102 | `/地图 list` 渲染回归 |
| `rest/AdminMapResourceTest.java` | 142 | 管理台地图接口行为 |
| `util/AppConfigInsertTest.java` | 73 | `AppConfig.update` 在父节缺失时补建祖先节 |
| `util/BuildInfoTest.java` | 41 | 版本号「单一来源」机制回归：能从构建产物解析出版本（退化成 `unknown` 即说明资源过滤/manifest 注入失效）、展示版本号恰为 `v` + 纯净版本号、纯净版本号不得自带 `v` 前缀（会被填进插件模板的 Maven 坐标）。刻意不写死具体版本号，避免每次升版本都变红 |
| `minecraft/adapter/McProtocol26Test.java` | 335 | MC 26.2（协议 776）字节布局回归：指令包只剩命令字符串、`LastSeenMessages.Update` 恰 5 字节、`DisguisedChat`/`PlayerChat`/`SystemChat` 字段顺序、NBT 组件形态、`chat_type` 注册表与私聊判定、旧协议不被改动 |
| `minecraft/adapter/Mc26LiveSmokeTest.java` | 90 | 对真实 26.2 服务端的端到端冒烟（默认跳过，`-Dmc26.live=true` 才跑）：登录 → 配置阶段 → PLAY、公屏不被误判成私聊、`/msg` 落进私聊回调 |

---

## 7. 运行时目录与数据文件

| 路径 | 生成时机 | 说明 |
|---|---|---|
| `config/application.yml` | 首启由 `AppConfig` 释放 | 主配置（含密钥，不入库） |
| `config/plugins.yml` | 首启由 `PluginSwitches` 释放 | 插件开关 |
| `config/adapters.json` | 首启由 `AdapterConfigManager` 释放 | 适配器实例配置（当前工作区含 `onebot-default`(启用, 端口 6700, `ws_server`, `text`) 与 `minecraft-default`(禁用)） |
| `config/realm_config.json` | 首启由 `GameConfigLoader.extractConfigs()` 释放 | 境界配置 |
| `config/game/wilderness_map.json` | 首启由 `GameConfigLoader.extractWildernessMapConfig()` 释放 | 野外分片世界定义（**只释放不覆盖**：改这里即可调参，删掉才会重新释放出厂值）。改 `worldSeed` 会让既有分片存档失效（会被识别并按新地形重建） |
| `config/blacklist.yml`、`config/onebot_group_config.yml` | 首启释放 | 黑名单、群配置 |
| `data/players/<playerId>.json` | 运行期 | 一用户一个玩家数据快照（当前工作区 74 个） |
| `log/server-<yyyyMMdd-HHmmss>.log` | 每次启动 | 服务端日志 |
| `plugins/*.jar` | 手动投放 | 外部插件（目录启动时创建） |
| `xiuxian.db`（+`-shm`/`-wal`） | 首启 | SQLite 数据库（默认 `database.type=sqlite`） |
| `mc_server/` | MC 通道启用时 | 内嵌 MC 服务端工作目录（jar、`plugins/XiuxianBridge.jar`） |

---

## 8. 关键运行链路

### 8.1 启动顺序（`Main.doMain`）

```
参数分流 --plugin-make-gui / --plugin-make
  ↓
注册关机钩子（7 项）
  ↓
JwtUtil.init(installMode)          ← 未安装则用临时密钥
  ↓
initializeDatabase()               ← SQLite 直连 / MySQL（本机自动拉起）
   └ TokenBlacklist.loadFromDatabase()
  ↓
SkillService.insertDefaultSkills()
TechniqueService.insertDefaultTechniques()
CraftingService.insertDefaultRecipes()
  ↓
ItemScanner.scanAndRegister()            → data.mtxgdn.item
EquipmentFixService.fixEquipmentData()
SecretRealmScanner.scanAndRegister()     → data.mtxgdn.secretrealm
ExplorationEventScanner.scanAndRegister()→ data.mtxgdn.explorationevent
CommandScanner.scanAndRegister("com.mtxgdn.onebot.command")
  ↓
PluginManager.init(new File("plugins")) → loadPlugins()   ← 外部插件
BuiltInPluginRegistry.registerAll(PluginCatalog.createAll()) → init() → enableAll(ctx)
  ↓
Grizzly HttpServer (server.host:server.port, /api/)
PluginWebManager.init() → PluginManager.enablePlugins()
  ↓
ServerConfigurator.configure()     ← 线程数、WS 单帧上限
GameWebSocketApp 注册到 "/"        ← WebSocket
SeasonService.setChangeListener(...) → 全服季节广播
  ↓
AdapterManager.setGameWebSocketApp() → startAll()   ← config/adapters.json
/admin 静态挂载；（未安装时）/install 静态挂载
  ↓
定时清理任务（限流窗口 1h / Token 黑名单 24h，每 5 分钟）
PluginEventManager.fireQuietly(SERVER_READY)
MinecraftMotdServer.start()
  ↓
--demo → DemoClient；--nogui → 阻塞等待；默认 → 按 Enter 关闭
```

### 8.2 一条指令的三端入口

```
OneBot WS/HTTP ─┐
QQ 官方 Webhook ─┼→ CommandContext ─→ CommandRegistry.get(name) ─→ Command.execute(ctx)
MC 指令转发 ────┘                          │
                                    isModuleEnabled() 门控
                                           ↓
                              subIndex 子命令分发 → RouteDefinition.getBotHandler()
```

HTTP 侧由 `UnifiedRestResource`（`/game/{path:.*}`）把同一批 `RouteDefinition` 映射为 REST 端点，`ModuleGateFilter` 复用同一门控判定。

### 8.3 内容注册（新增内容为何无需改工厂）

| 内容 | 数据包 | 扫描器 | 注册表 |
|---|---|---|---|
| 物品 | `data.mtxgdn.item` | `ItemScanner` | `ItemRegistry` |
| 游历事件 | `data.mtxgdn.explorationevent` | `ExplorationEventScanner` | `ExplorationEventRegistry` |
| 秘境 | `data.mtxgdn.secretrealm` | `SecretRealmScanner` | `SecretRealmRegistry` |
| 指令 | `com.mtxgdn.onebot.command` | `CommandScanner` | `CommandRegistry`（构造即注册） |
| 称号 | — | — | `TitleRegistry` |

---

## 9. 数据库表清单（`DatabaseSchema.java`）

**账号与权限**：`users`、`verification_codes`、`roles`、`permissions`、`role_permissions`、`user_roles`、`token_blacklist`

**角色与养成**：`players`、`players_items`、`players_equipment`、`skills`、`players_skills`、`techniques`、`players_techniques`、`recipes`、`player_daily`、`player_titles`、`player_buffs`、`player_action_logs`

**经济与交易**：`trade_listings`、`trade_price_history`、`player_economy`、`player_bank`、`auction_listings`、`auction_bids`、`player_energy`、`redeem_codes`、`redeemed_codes`

**玩法**：`sects`、`sect_members`、`sect_applications`、`sect_warehouse`、`sect_wars`、`caves`、`formations`、`farm_plots`

**世界 Boss**：`world_bosses`（**全服共享的那一条血** `hp`/`max_hp`、奖励预算 `stone_budget`/`exp_budget`、整池重建时刻 `expires_at`、击杀归属 `killed_at`/`killer_player_id`，`UNIQUE(scope, slot)` 是并发重建的护栏）、`world_boss_damage`（伤害台账 `damage`/`hits`/`last_hit_at`，`UNIQUE(boss_id, player_id)`；`last_hit_at` 同时是挑战冷却的唯一依据）。两张表都**没有**内存态与定时器，到期重建走读取时的惰性判断

**地图**：`map_locations`（`danger_level` 危险度 0–4、`is_wilderness` 野外标记、`expires_at` 到期时间、`entry_location_id` 入口地点）、`map_connections`、`map_location_event_bias`、`player_map_exploration`（**已知世界的**首达记录，`UNIQUE(player_id, location_id)`）

> ⚠️ **野外不在此列**：野外没有 `map_locations` 行、没有 `map_connections` 行、探索记录也不在
> `player_map_exploration`。地形由 `game/map/WildernessTerrain` 按 `(worldSeed, 分片坐标)` 现算，
> 只有"谁探索过 / 资源剩余"落在 **`data/wilderness/<cx>>6>/<cy>>6>/<cx>_<cy>.json`**。
> `map_wilderness_resources` 表已废弃（新库不再创建；老库留下的空表无害、也刻意不去 `DROP`）。

**社交**：`friends`、`chat_messages`

**多端绑定**：`qq_bindings`、`mc_bindings`（另有 `createBindingsTable(tableName)` 动态建表）

**索引**：`idx_trade_price_item_time`、`idx_trade_listings_status_expires`、`idx_trade_listings_seller_status`、`idx_player_buffs_expire`（Buff 过期兜底回收按 `expire_time` 扫描）

---

## 10. 配置项索引（`application.yml`）

| 段 | 关键项 |
|---|---|
| `app` | `name` |
| `server` | `host`（默认 `127.0.0.1`）、`port`（8080） |
| `database` | `type`（`sqlite` 默认 / `mysql`）、`url`、`username`、`password`、`sqlite_path`（`xiuxian.db`）、`pool_max` |
| `jwt` | `secret`（空 = 未安装，走安装向导） |
| `season` | `mode`（`calendar` / `cycle`）、`cycle_hours`（6） |
| `oauth2` | `enabled`、`client_id`、`client_secret`、`scopes`、`token_ttl_seconds` |
| `player-data` | `enabled`、`dir`（`data/players`）、`auto-sync-seconds`（30）、`self-restore`（false） |
| `admin` | `username`、`password_hash` |
| `smtp` | `host`、`port`、`username`、`password`、`from`、`auth`、`starttls` |
| `verify_code` | `enabled`、`allowed_domains`、`rate_limit_seconds` |
| `onebot` | `enabled`、`send_mode`、`screenshot.*` |
| `minecraft` | `jar_path`、`server_dir`、`java_path`、`min_memory_mb`、`max_memory_mb`、`ready_timeout_seconds`、`extra_jvm_args`、`online_mode`、`command_prefix` |
| `logging` | `level`、`dir`、`color` |
| `inventory` | `base_capacity`（30）、`capacity_per_level`（5） |
| `market` | `listing_duration_hours`（72）、`price_history_keep_days`（30）、`price_history_cleanup_interval_hours`（6） |
| `buff` | `expire_sweep_seconds`（60，过期增益兜底回收间隔；活跃玩家走访问时的惰性过期，最小 10 秒） |
| `sect` | `leader_absent_days`（14，宗主连续不活跃多少天判定弃坑并自动继位；0 = 关闭）、`contest_days`（7，继位后宗主竞争期时长）、`contest_level_gap`（6，继任者与最强竞争者的境界序位差小于该值才开竞争期）、`succession_sweep_seconds`（600，继位维护扫描间隔，最小 30） |
| `world_boss` | `scope`（`global`，Boss 池归属键；单世界服务器全服共享同一批）、`boss_count`（3，池内 Boss 数，1–10）、`respawn_hours`（4，整池重建周期；到期走读取时的惰性重建，无定时器）、`hp_multiplier`（12，相对同境界普通 Boss 的血量倍率）、`max_stage_gap`（9，境界序位差达到该值即不可挑战）、`challenge_cooldown_seconds`（300，每人每只 Boss 的挑战冷却；0 = 不限制）、`max_single_reward`（1000000，单次奖励熔断阈值，超过改发 `阈值/(5+rand(6))`）、`kill_bonus_ratio`（0.2，补最后一刀者额外获得的修为比例） |
| `performance` | `low_memory`、`grizzly_io_threads`、`grizzly_worker_cores`、`grizzly_worker_max`、`memory_log_entries` |
| `network` | `connection_timeout_ms`、`read_timeout_ms`、`ws_max_message_size` |
| `motd` | `port`（25565）、`max_players`、`version_name`、`protocol_version`、`line1/2/3`、`join_message`、`redirect_server`、`redirect_delay_seconds` |
| `plugins` | `hot_swap`（是否启用插件目录监听） |

---

## 11. 内置插件 ↔ 开关 ↔ 代码 对应表

| 开关键（`plugins.<name>`） | 插件类 | 默认 | 关联指令/模块名 |
|---|---|---:|---|
| `exploration` | `ExplorationPlugin` | ✅ | `exploration` |
| `secret_realm` | `SecretRealmPlugin` | ✅ | `secret_realm` |
| `trade` | `TradePlugin` | ✅ | `trade` |
| `sect` | `SectPlugin` | ✅ | `sect` |
| `farm` | `FarmPlugin` | ✅ | `farm` |
| `cave` | `CavePlugin` | ✅ | `cave` |
| `chat` | `ChatPlugin` | ✅ | `chat` |
| `friend` | `FriendPlugin` | ✅ | `friend` |
| `rank` | `RankPlugin` | ✅ | `rank` |
| `search` | `SearchPlugin` | ✅ | `search` |
| `map` | `MapPlugin` | ✅ | `map` |
| `team` | `TeamPlugin` | ✅ | `team` |
| `title` | `TitlePlugin` | ✅ | `title` |
| `player` | `PlayerPlugin` | ✅ | `player` |
| `cultivation` | `CultivationPlugin` | ✅ | `cultivation` |
| `skill` | `SkillPlugin` | ✅ | `skill` |
| `pvp` | `PvpPlugin` | ✅ | `pvp` |
| `world_boss` | `WorldBossPlugin` | ✅ | `world_boss` |
| `inventory` | `InventoryPlugin` | ✅ | `inventory` |
| `economy` | `EconomyPlugin` | ✅ | `economy` |
| `energy` | `EnergyPlugin` | ✅ | `energy` |
| `technique` | `TechniquePlugin` | ✅ | `technique` |
| `crafting` | `CraftingPlugin` | ✅ | `crafting` |
| `buff` | `BuffPlugin` | ✅ | `buff` |
| `formation` | `FormationPlugin` | ✅ | `formation` |
| `experimental_sect_war` | `SectWarPlugin` | ❌ | 实验性：宗门战 |
| `experimental_energy_exchange` | `EnergyExchangePlugin` | ✅ | 实验性：能量转化 |
| `onebot` | `AdapterChannelPlugin("onebot", …)` | ✅ | OneBot 通道（类型级） |
| `qq` | `AdapterChannelPlugin("qq", …)` | ✅ | QQ 官方通道（类型级） |
| `minecraft` | `AdapterChannelPlugin("minecraft", …)` | ✅ | MC 内嵌服务器通道 |
| `minecraft-player` | `AdapterChannelPlugin("minecraft-player", …)` | ✅ | MC 玩家直连通道 |

取值优先级：`config/plugins.yml` > jar 内 `plugins.yml` 模板 > 旧配置只读兜底（`application.yml` 的 `built_in_plugins.*`、`config/experimental.yml` 的 `experimental.*`，由 `PluginSwitches.migrateLegacyKeys()` 自动迁移）。

---

## 12. 扩展点速查

| 想加的东西 | 落点 | 是否需要改其他文件 |
|---|---|---|
| 新指令 | `src/main/java/com/mtxgdn/onebot/command/<分类>/XxxCommand.java` | 不需要——`CommandScanner` 自动扫描；如需模块门控加 `setModule("...")` |
| 新物品 | `src/main/java/data/mtxgdn/item/Xxx.java` + 语言键 | 不需要——`ItemScanner` 自动注册；效果复用/新增 `game/item/*Effect.java` |
| 新物品效果 | `src/main/java/com/mtxgdn/game/item/XxxEffect.java` | 在物品构造里引用 |
| 新游历事件 | `src/main/java/data/mtxgdn/explorationevent/XxxEvent.java` | 不需要——自动扫描；可在 `MapDefaults.EVENT_BIAS` 加地界偏置 |
| 新秘境 | `src/main/java/data/mtxgdn/secretrealm/Xxx.java` | 不需要——自动扫描 |
| 新地图地点 | `MapDefaults.LOCATIONS` / `CONNECTIONS` / `EVENT_BIAS` | 新库自动建；老库由 `DatabaseMigration` 补齐。**id 发布后不可变更**；也可用管理台 `POST /admin/map/locations` 热加（id 留空则自动分配） |
| 调整野外分片世界 | `src/main/resources/game/wilderness_map.json`（运行期改 `config/game/wilderness_map.json`） | 不需要改代码——改 `worldSeed` / `chunkSize` / `biomes` / `resourcePools` 后重启，或调 `POST /admin/map/wilderness/restock` 补货。**物品 key 必须真实存在**，否则采集会发出空物品。⚠️ 改 `worldSeed` 或 `chunkSize` 会让既有分片存档失效（按新地形重建，探索记录随之丢失） |
| 新玩法模块 | `plugin/builtin/XxxPlugin.java` + 登记到 `PluginCatalog.createAll()` + `plugins.yml` 加键 | 需改 3 处（`PluginCatalog` 是唯一登记处，`BuiltInModuleSetTest` 会校验一致性） |
| 新实验性玩法 | `plugin/experimental/XxxPlugin.java` + 登记 `PluginCatalog` + `plugins.yml` 加 `experimental_*` 键 | 同上 |
| 新 REST 接口 | `rest/XxxResource.java`（`@Path`）或给 `Command` 加 `RouteDefinition.post/get` | 前者靠 `ResourceConfig.packages("com.mtxgdn.rest")` 自动扫描 |
| 新管理后台页面 | `webadmin/index.html` 加 `nav-item` + `webadmin/assets/js/modules/xxx.js` + `AdminResource` 端点 | 需改 3 处 |
| 第三方插件 | 见 `README_PLUGIN.md`；模板在 `resources/plugin-template/`，示例在 `examples/sample-plugin/` | 也可用 `--plugin-make-gui` 生成 |
| 新适配器类型 | 实现 `AdapterFactory` + `ManagedAdapter`，在插件 `onEnable` 注册 | `AdapterManagerFactoryTest` 覆盖该链路 |

---

## 13. 已知注意点（阅读代码时值得留意）

1. **`config/` 双基准路径**：`config/application.yml`、`config/plugins.yml` 按 **jar 所在目录** 解析；`config/adapters.json`、`xiuxian.db`、`data/`、`log/`、`plugins/` 按 **工作目录** 解析。两者不一致时 `Main.reportDeploymentPaths()` 会打印告警。Docker/systemd 部署已刻意把两者统一到同一目录。
2. **指令名唯一性（已修复，勿回退）**：修复前有 4 组重名——`发放`/`give`（`admin/GiveCommand` vs `item/ItemGiveCommand`）、`灵根`（`admin/SetRootCommand` vs `player/SpiritualRootCommand`）、`物品列表`（`admin/ItemsQueryCommand` vs `item/ItemMapCommand`）、`status`（`admin/StatusCommand` vs `player/StatusCommand`）。`CommandRegistry.register()` 用 `Map.put` 覆盖，而 `CommandScanner` 的扫描顺序来自 `File.listFiles()` / jar 条目顺序（都不保证稳定），所以**谁生效取决于运行环境**；更糟的是后三组是管理指令抢占了玩家指令的名字——玩家敲 `/灵根`、`/物品列表`、`/status` 会收到「权限不足」。现已让两侧名字互斥（管理侧改为 `修改灵根`、`全物品列表`、`服务器状态`、`发放物品`），并在 `CommandRegistry.register()` 增加冲突 WARN 日志、由 `common/command/CommandNameConflictTest` 锁定。新增指令前请先确认名字没被占用。
3. **两个 PvpCommand**：`onebot/command/combat/PvpCommand`（`挑战`/`pvp`）与 `onebot/command/pvp/PvpCommand`（`决斗`/`切磋`/`fight`/`战斗`/`讨伐`/`boss`），同属 `pvp` 模块但指令名不重叠。
4. **`FormationItem` 无无参构造**：`ItemScanner` 只接受无参构造，`FormationItem` 会被记入启动日志的「失败：缺少无参构造」，其 3 个子类（`SpiritGatheringFormation`、`MountainProtectionFormation`、`PurificationFormation`）正常注册。
5. **MC 插件源码不参与主构建**：`com/mtxgdn/minecraft/plugin/**` 被 compiler 排除，必须用 `build-mc-plugin.bat` 单独编译（依赖 `mc_server/server.jar` 提供 Bukkit API）。
6. **测试用插件开关隔离**：Surefire 通过系统属性 `xiuxian.plugins.config` 指向 `target/test-config/plugins.yml`，改动 `PluginSwitches` 相关逻辑时不要绕过该属性。
7. **空包目录**：`com/mtxgdn/plugin/api/` 当前无文件，属预留位置。
8. **无 CI**：`.github/` 下只有 Issue 模板，没有 workflow，构建/测试需本地执行 `run_tests.sh` / `run_tests.bat`。
9. **`admin.status` 只用于只读接口（已修复，勿回退）**：`admin.status` 的显示名是「查看服务器状态」，但 `PermissionService` 给 `MODERATOR` 组的默认权限里包含它（MODERATOR = PLAYER + `admin.login`、`admin.status`、`admin.logs.view`、`qq.command.admin`、`admin.blacklist.view`）。此前多处以它门控**写操作**，等于版主能随意改玩家数据。现已按「读用 `admin.status`、写用 `admin.users.manage`」划分：
    - `SetRootCommand`（改灵根）、`EditPlayerCommand`（编辑玩家属性）、`UsersRolesCommand`（用户/角色列表）→ `admin.users.manage`（与 REST 侧 `/players/{id}/edit`、`/players/{id}/spiritual-root`、`/users` 一致，该码不在 ADMIN 默认权限内，仅 SUPER_ADMIN 持有）；
    - `EnergyAdminCommand` → 指令级与 GET 路由保持 `admin.status`（版主可「查看」），三个写路由 `admin/energy/set|add|remove` 与指令内 `设置/增加/减少` 分支改用 `admin.users.manage`（`isWriteAction()` 二次校验）；
    - `/admin/announce`（全服广播）→ 新增权限码 `admin.announce`。
    仍用 `admin.status` 的只剩纯读取：`admin/StatusCommand`、`PlayersListCommand`、`PlayerDetailCommand`、`ItemsQueryCommand`，以及管理台 `/status`、`/players/{id}`、`/spiritual-roots`、`/items`、`/realms`、`/titles`、`/stats/*`、`/system/info`。
    由 `security/AdminWritePermissionTest` 锁定三条不变量：`admin.status` 只能挂在 GET 上、写端点必须声明非只读权限、用 `admin.status` 的指令必须在白名单内（新增指令会失败，需先确认是纯读取）。
    注意 `admin.users.manage` 与 `admin.announce` 都**不在** ADMIN 的默认权限里（ADMIN 默认 = 全部权限码 − `admin.roles.manage`、`admin.users.manage`、`admin.database.clear_players`、`admin.database.reset_all`），因此这两项实际只有 SUPER_ADMIN 可用；需要放给 ADMIN 请在管理台「权限组」页手动授权。
10. **野外是一片无限分片世界，且完全不进数据库**：地形是 `(worldSeed, chunkX, chunkY)` 的纯函数，
    `map_locations` / `map_connections` / `player_map_exploration` 里没有任何一行属于野外；
    `MapService.getLocation()` 与 `getNeighbors()` 对野外 id 现算节点与邻接。
    真正需要落盘的只有可变状态，存在 `data/wilderness/...` 的分片文件里。
    这样做的好处是**数据库体积不随世界扩张而增长**（无限世界里"已生成的区域"可以无限大），
    分片文件可读可备份可手工修，而且地形没有第二份副本，永远不会与存档走样。
    相应地 `getAllLocations()` 只返回已知世界（`/地图 list`、世界树与既有测试都依赖
    "出厂 15 个地点"这个契约），野外按分片取用 `getChunkNodes(cx, cy)`。
11. **已探索的分片永久固化，没有任何删除入口**：首次抵达即置 `pinned`，从此不回收；
    管理台也刻意不提供"删除分片"或"重刷野外"（旧的 `/admin/map/wilderness/refresh` 已移除），
    只剩 `POST /admin/map/wilderness/restock` 做**原地补货**（只把 `remaining` 补回 `total`，
    不动地形、不动探索记录）。只有"从未被探索且闲置超过 `evictAfterMinutes`"的分片会被回收，
    且回收前会确认没有玩家站在该分片范围内 —— 这靠"同分片 id 连续"走一个范围查询完成。
    回收掉的只是从未有人涉足的地方，重新走到时地形由种子算回来，逐字一致。
12. **改 `worldSeed` / `chunkSize` 会让既有分片存档失效**：文件里带 `seedHash`，
    对不上就丢弃该文件并按新地形重建（探索记录与资源库存随之丢失）。这比"旧记录挂到另一片
    地形上"要好，但仍属破坏性操作，改之前请先备份 `data/wilderness/`。
13. **`danger_level` 不参与 PVP 开关**：唯一的安全区判定是 `is_safe_zone`，`danger_level` 只驱动险地掠夺（危险度 ≥3 才掠夺，比例 `5% × 危险度`，即 3 档 15%、4 档 20%）与野外资源档位。必须维持的不变式是 `is_safe_zone = 1 ⇒ danger_level = 0`（`MapService.loadLocations()` 发现违反时只告警、不静默改写数据）。掠夺走 `removeSpiritStones` 的布尔返回值，因此并发 PK 不会凭空造出灵石。
14. **`ALLOWED_TABLES` 曾漏登记 `map_location_event_bias`（已修复）**：该集合是管理台通用表 CRUD 的白名单，漏登记会让这张表在管理台报"表不存在"；新增表时必须同时改 `DatabaseManager.initTable()` 与 `ALLOWED_TABLES`。
15. **`DatabaseMigration` 的列补齐靠"捕获异常当作已存在"**：每个 `addXxxColumn()` 都是 `try { ALTER TABLE ... } catch (SQLException ignored) {}`。这是有意为之（SQLite/MySQL 对"列已存在"报错不一致），代价是**写错列名会被静默吞掉**。配合 `MapService.repairDefaultData()` 的 `columnsOf(conn, table)` 动态列名方案（按库里实际存在的列拼 INSERT），老库缺新列时是"功能降级"而不是启动失败。

---

## 附录 A：物品全表（115 个文件，`src/main/java/data/mtxgdn/item/`）

> 列：文件名 | 物品 key | 类型 | 品质。全部物品命名空间均为 `mtxgdn`，由 `ItemScanner` 反射扫描注册。

### 消耗品（CONSUMABLE），23 个

| 文件名 | 物品 key | 类型 | 品质 |
|---|---|---|---|
| `AttributeResetPill.java` | `attribute_reset_pill` | CONSUMABLE | RARE |
| `CultivationElixir.java` | `cultivation_elixir` | CONSUMABLE | RARE |
| `DamageReflectionPill.java` | `damage_reflection_pill` | CONSUMABLE | UNCOMMON |
| `ExpMultiplierPill.java` | `exp_multiplier_pill` | CONSUMABLE | RARE |
| `GoldBag.java` | `gold_bag` | CONSUMABLE | COMMON |
| `HealingPill.java` | `healing_pill` | CONSUMABLE | COMMON |
| `HeavenPill.java` | `heaven_pill` | CONSUMABLE | LEGENDARY |
| `ImmortalPill.java` | `immortal_pill` | CONSUMABLE | LEGENDARY |
| `ManaPill.java` | `mana_pill` | CONSUMABLE | COMMON |
| `PowerBuffPill.java` | `power_buff_pill` | CONSUMABLE | UNCOMMON |
| `ProtectCharm.java` | `protect_charm` | CONSUMABLE | RARE |
| `PurificationPill.java` | `purification_pill` | CONSUMABLE | UNCOMMON |
| `QiankunZaohuaDan.java` | `qiankun_zaohua_dan` | CONSUMABLE | LEGENDARY |
| `RebirthPill.java` | `rebirth_pill` | CONSUMABLE | EPIC |
| `SpeedTalisman.java` | `speed_talisman` | CONSUMABLE | UNCOMMON |
| `SpiritBoostPill.java` | `spirit_boost_pill` | CONSUMABLE | RARE |
| `SpiritRecoveryPill.java` | `spirit_recovery_pill` | CONSUMABLE | UNCOMMON |
| `SpiritStonePouch.java` | `spirit_stone_pouch` | CONSUMABLE | COMMON |
| `SuperHealingPill.java` | `super_healing_pill` | CONSUMABLE | RARE |
| `SuperManaPill.java` | `super_mana_pill` | CONSUMABLE | RARE |
| `TeleportTalisman.java` | `teleport_talisman` | CONSUMABLE | UNCOMMON |
| `TribulationPill.java` | `tribulation_pill` | CONSUMABLE | EPIC |
| `UltimatePowerPill.java` | `ultimate_power_pill` | CONSUMABLE | EPIC |

### 货币（CURRENCY），4 个

| 文件名 | 物品 key | 类型 | 品质 |
|---|---|---|---|
| `HighSpiritStone.java` | `spirit_stone_high` | CURRENCY | RARE |
| `MiddleSpiritStone.java` | `spirit_stone_mid` | CURRENCY | UNCOMMON |
| `SpiritStone.java` | `spirit_stone_low` | CURRENCY | COMMON |
| `SupremeSpiritStone.java` | `spirit_stone_supreme` | CURRENCY | EPIC |

### 技能书（SKILL_BOOK），3 个

| 文件名 | 物品 key | 类型 | 品质 |
|---|---|---|---|
| `BasicSwordManual.java` | `basic_sword_manual` | SKILL_BOOK | COMMON |
| `FireDragonArt.java` | `fire_dragon_art` | SKILL_BOOK | EPIC |
| `ThunderBoltTalisman.java` | `thunder_bolt_talisman` | SKILL_BOOK | LEGENDARY |

### 法宝 / 装备 / 阵法（TREASURE），11 个

| 文件名 | 物品 key | 类型 | 品质 |
|---|---|---|---|
| `DragonSword.java` | `dragon_sword` | TREASURE | LEGENDARY |
| `GuardianJade.java` | `guardian_jade` | TREASURE | EPIC |
| `JadeArmor.java` | `jade_armor` | TREASURE | LEGENDARY |
| `MountainProtectionFormation.java` | `mountain_protection_formation` | TREASURE | EPIC |
| `PhoenixArmor.java` | `phoenix_armor` | TREASURE | LEGENDARY |
| `PurificationFormation.java` | `purification_formation` | TREASURE | RARE |
| `QilinAmulet.java` | `qilin_amulet` | TREASURE | MYTHIC |
| `SpaceRing.java` | `space_ring` | TREASURE | RARE |
| `SpiritGatheringFormation.java` | `spirit_gathering_formation` | TREASURE | RARE |
| `SpiritSword.java` | `spirit_sword` | TREASURE | RARE |
| `StarRobes.java` | `star_robes` | TREASURE | EPIC |

### 种子（SEED），8 个

| 文件名 | 物品 key | 类型 | 品质 |
|---|---|---|---|
| `BloodLingzhiSeed.java` | `blood_lingzhi_seed` | SEED | RARE |
| `DarkIceGrassSeed.java` | `dark_ice_grass_seed` | SEED | UNCOMMON |
| `FireVineSeed.java` | `fire_vine_seed` | SEED | UNCOMMON |
| `NetherFlowerSeed.java` | `nether_flower_seed` | SEED | RARE |
| `SpiritGrassSeed.java` | `spirit_grass_seed` | SEED | COMMON |
| `StarGrassSeed.java` | `star_grass_seed` | SEED | EPIC |
| `ThousandYearGinsengSeed.java` | `thousand_year_ginseng_seed` | SEED | RARE |
| `TianshanSnowLotusSeed.java` | `tianshan_snow_lotus_seed` | SEED | LEGENDARY |

### 材料（MATERIAL），61 个

| 文件名 | 物品 key | 类型 | 品质 |
|---|---|---|---|
| `BeastCore.java` | `beast_core` | MATERIAL | UNCOMMON |
| `BloodJadeMarrow.java` | `blood_jade_marrow` | MATERIAL | EPIC |
| `BloodLingzhi.java` | `blood_lingzhi` | MATERIAL | RARE |
| `DarkIceGrass.java` | `dark_ice_grass` | MATERIAL | UNCOMMON |
| `DeepSeaCoral.java` | `deep_sea_coral` | MATERIAL | EPIC |
| `DemonCore.java` | `demon_core` | MATERIAL | UNCOMMON |
| `DragonBloodCrystal.java` | `dragon_blood_crystal` | MATERIAL | EPIC |
| `DragonBone.java` | `dragon_bone` | MATERIAL | EPIC |
| `DragonFruit.java` | `dragon_fruit` | MATERIAL | RARE |
| `DragonFruitSeed.java` | `dragon_fruit_seed` | MATERIAL | RARE |
| `DragonHeart.java` | `dragon_heart` | MATERIAL | LEGENDARY |
| `DragonScale.java` | `dragon_scale` | MATERIAL | LEGENDARY |
| `EnhanceStone.java` | `enhance_stone` | MATERIAL | COMMON |
| `FireVine.java` | `fire_vine` | MATERIAL | UNCOMMON |
| `FiveColoredDivineStone.java` | `five_colored_divine_stone` | MATERIAL | LEGENDARY |
| `GhostFireEssence.java` | `ghost_fire_essence` | MATERIAL | RARE |
| `GinsengFruit.java` | `ginseng_fruit` | MATERIAL | LEGENDARY |
| `GinsengFruitSeed.java` | `ginseng_fruit_seed` | MATERIAL | LEGENDARY |
| `GoldOre.java` | `gold_ore` | MATERIAL | RARE |
| `HeavenFireStone.java` | `heaven_fire_stone` | MATERIAL | RARE |
| `HeavenlyJade.java` | `heavenly_jade` | MATERIAL | EPIC |
| `HeShouWuSeed.java` | `he_shou_wu_seed` | MATERIAL | RARE |
| `HighGradeFertilizer.java` | `high_grade_fertilizer` | MATERIAL | RARE |
| `IceCrystal.java` | `ice_crystal` | MATERIAL | RARE |
| `ImmortalDate.java` | `immortal_date` | MATERIAL | EPIC |
| `ImmortalDateSeed.java` | `immortal_date_seed` | MATERIAL | EPIC |
| `IronOre.java` | `iron_ore` | MATERIAL | COMMON |
| `LowGradeFertilizer.java` | `low_grade_fertilizer` | MATERIAL | COMMON |
| `MidGradeFertilizer.java` | `mid_grade_fertilizer` | MATERIAL | UNCOMMON |
| `MoonEssence.java` | `moon_essence` | MATERIAL | EPIC |
| `MythrilOre.java` | `mythril_ore` | MATERIAL | EPIC |
| `NetherFlower.java` | `nether_flower` | MATERIAL | RARE |
| `NineTurnGrass.java` | `nine_turn_grass` | MATERIAL | MYTHIC |
| `NineTurnGrassSeed.java` | `nine_turn_grass_seed` | MATERIAL | MYTHIC |
| `Peach.java` | `peach` | MATERIAL | UNCOMMON |
| `PeachSeed.java` | `peach_seed` | MATERIAL | UNCOMMON |
| `Pesticide.java` | `pesticide` | MATERIAL | COMMON |
| `PhoenixFeather.java` | `phoenix_feather` | MATERIAL | LEGENDARY |
| `PhoenixGallbladder.java` | `phoenix_gallbladder` | MATERIAL | LEGENDARY |
| `PurpleRiverCart.java` | `purple_river_cart` | MATERIAL | EPIC |
| `PurpleRiverCartSeed.java` | `purple_river_cart_seed` | MATERIAL | EPIC |
| `QilinBlood.java` | `qilin_blood` | MATERIAL | MYTHIC |
| `QilinHorn.java` | `qilin_horn` | MATERIAL | LEGENDARY |
| `ScripturePage.java` | `scripture_page` | MATERIAL | UNCOMMON |
| `SilverOre.java` | `silver_ore` | MATERIAL | UNCOMMON |
| `SkySilk.java` | `sky_silk` | MATERIAL | EPIC |
| `SnakeVenom.java` | `snake_venom` | MATERIAL | RARE |
| `SpiritGrass.java` | `spirit_grass` | MATERIAL | COMMON |
| `SpiritPearl.java` | `spirit_pearl` | MATERIAL | EPIC |
| `SpiritSpringWater.java` | `spirit_spring_water` | MATERIAL | UNCOMMON |
| `SpiritWood.java` | `spirit_wood` | MATERIAL | COMMON |
| `StarGrass.java` | `star_grass` | MATERIAL | EPIC |
| `StarSand.java` | `star_sand` | MATERIAL | EPIC |
| `SunEssence.java` | `sun_essence` | MATERIAL | LEGENDARY |
| `TenThousandYearLingzhi.java` | `ten_thousand_year_lingzhi` | MATERIAL | EPIC |
| `ThousandYearGinseng.java` | `thousand_year_ginseng` | MATERIAL | RARE |
| `ThousandYearHeshouwu.java` | `thousand_year_heshouwu` | MATERIAL | RARE |
| `TianshanSnowLotus.java` | `tianshan_snow_lotus` | MATERIAL | EPIC |
| `TigerFang.java` | `tiger_fang` | MATERIAL | UNCOMMON |
| `WanLingGrass.java` | `wan_ling_grass` | MATERIAL | COMMON |
| `WanLingGrassSeed.java` | `wan_ling_grass_seed` | MATERIAL | COMMON |

### 任务道具（QUEST），4 个

| 文件名 | 物品 key | 类型 | 品质 |
|---|---|---|---|
| `FragmentScroll.java` | `fragment_scroll` | QUEST | UNCOMMON |
| `MysteryToken.java` | `mystery_token` | QUEST | RARE |
| `QuestLetter.java` | `quest_letter` | QUEST | COMMON |
| `QuestScroll.java` | `quest_scroll` | QUEST | EPIC |

### 基类，1 个

| 文件名 | 物品 key | 类型 | 品质 |
|---|---|---|---|
| `FormationItem.java` | `（基类，无自身 key）` | BASE | 由子类传入 |
