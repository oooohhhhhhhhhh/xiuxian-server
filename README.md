# 修仙世界 (Xiuxian World)

一个基于 Java 的多人在线修仙游戏服务端，提供 REST API、WebSocket 实时通信、QQ 机器人（OneBot）等接入方式。

---

## 项目简介

**修仙世界**是一款文字 MUD 风格的修仙主题游戏后端。玩家创建角色后可以通过修炼、突破境界、探索秘境、游历世界、PVP 对战、坊市交易等方式在修仙世界中成长。

### 核心特性

- **灵根系统**：15 种灵根（天/异/双/三/四/杂 6 个等级），创建角色时随机抽取，每种灵根拥有独特属性加成和特殊效果（如暴击率、回血、蓝耗降低、暴击伤害、技能伤害、游历 CD 缩减、修炼效率、坊市手续费减半、灵石掉落、熟练度加成、后期觉醒等）
- **修炼系统**：挂机修炼，根据境界获得经验倍率加成；修炼中可能触发**心魔**——有概率修为倒退（轻微/中等/严重三种程度，灵力越高越能抵抗）
- **境界突破**：11 大境界（凡人→炼气→筑基→金丹→元婴→化神→合体→大乘→渡劫→真仙→金仙），每境含初/中/后三期。小境界 100% 成功，大境界突破触发**天劫**（7 种天劫按境界匹配：雷劫/心魔劫/风火劫/水劫/五行劫/阴阳劫/轮回劫），成功率随境界递减（80%→10%），受灵根等级、灵力属性、天劫对应属性影响，可服用渡劫丹 +10%，上限 95%；失败则损失 1/3 经验并重伤
- **功法/心法系统**：11 种内功心法（4 种类型：攻击/防御/修炼/辅助），可学习/装备/升级，最多同时运转 3 门，每级属性 +12%，装备即获被动加成（HP/MP/攻/防/速/灵力/修炼速度/经验/伤害/减伤）
- **制造系统**：炼丹和炼器，11 种配方（丹药/装备/消耗品），最多 3 种材料，成功率 + 失败安慰经验，可制造回血丹、回蓝丹、培元丹、灵剑、强化石、渡劫丹等
- **装备强化**：穿戴装备可强化至 +15，成功率逐级递减（95%→3%），+5 以上可能降级或碎装备，强化增加攻击/防御/速度/灵力加成
- **天象系统**：全服每日随机天象（紫气东来/星辰耀天/血月当空/灵潮涌动/枯荣交替/万籁俱寂），影响修炼/游历/战斗/灵石掉落倍率
- **晨修·紫气东来**：每日一次的修炼收益加成，连续天数越多紫气越精纯
- **今日机缘**：嵌入游戏行为的每日目标（游历3次/PvP1次/秘境1次/学技能1次），完成后当场发放奖励，无需手动领取
- **灵根共鸣**：连续活跃 7 天和 30 天时触发，永久提升属性
- **游历探索**：随机事件系统，权重抽奖机制，巽风灵根可缩减冷却时间
- **秘境探索**：8 个秘境区域，随机遭遇宝藏/妖兽/Boss/灵草/陷阱/古修/遗迹等事件
- **坊市交易**：玩家间物品交易，使用灵石结算，5% 手续费（土金灵根减半），挂单/购买/撤单完整流程
- **宗门系统**：创建宗门/申请加入/审批/任命/捐献/仓库存取/踢出/解散，等级≥3 消耗 100 灵石创建，声望排行，宗门仓库共享物品
- **物品系统**：7 种类型、6 种稀有度，组件化效果（经验/回血/货币/buff/技能书），支持按中文名称/key 使用物品
- **技能系统**：攻击技能和辅助技能，熟练度升级（使用获得熟练度，技能书 +1 级），等级越高蓝耗越大，金水木灵根熟练度 +30%
- **装备系统**：装备穿戴/卸下，属性加成
- **聊天系统**：世界频道（全服广播）+ 私聊（点对点消息），消息持久化存储，WebSocket 实时推送
- **排行榜**：境界榜/战力榜/财富榜，支持 REST/WebSocket/QQ 三种方式查看
- **好友系统**：好友申请/接受/删除/列表，双向确认机制，支持跨端操作
- **重伤疗伤**：战败重伤（HP≤0）后可用灵石瞬间疗伤（境界越高花费越多）+ 法力一并恢复；也可服用回血丹等消耗品回复；离线自然恢复兜底
- **PVP 对战**：回合制玩家对战，支持技能施放、暴击、灵根特效（回血/增伤/减伤/暴击/MP减免等）
- **PVE 战斗**：游历和秘境中随机遭遇妖兽，完整回合制战斗（技能、暴击、灵根特效全生效）；秘境含 Boss 战，各秘境专属守护者
- **离线收益**：断线后修炼继续进行（50% 效率，上限 8 小时），上线时自动结算经验、HP/MP 恢复，心魔判定降频
- **用户系统**：邮箱注册/验证码、JWT 双令牌认证、BCrypt 密码加密
- **权限管理**：RBAC 角色权限系统，新增 `game.*`/`qq.*` 前缀权限码自动授予 PLAYER 角色，无需逐个加入角色定义
- **QQ 机器人**：OneBot 协议集成，支持私聊和群聊指令操作
- **指令即路由**：一套 API 搞定双端 — `addRoute(RouteDefinition.onebotOnly("xxx", handler))` 仅 OneBot，`addRoute(RouteDefinition.get("path", handler))` 仅 HTTP，`registerSub` + `addRoute` 双端注册；UnifiedRestResource 自动发现并分发 HTTP 请求
- **Minecraft MOTD**：模拟 Minecraft 服务器 ping 响应（端口 25565），显示在线人数
- **国际化 (i18n)**：JSON 语言的本地化系统，物品/秘境/事件/系统消息完整翻译，当前支持中文 (zh_cn)
- **数据库**：MySQL / SQLite 双支持，HikariCP 连接池，一行配置切换
- **Web 管理控制台**：修仙风主题面板，侧边栏导航，实时日志，玩家管理，用户角色管理，数据发放

---

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 23 |
| 构建工具 | Maven |
| HTTP 框架 | Jersey 4.0.2 + Grizzly |
| WebSocket | Grizzly WebSocket |
| 数据库 | MySQL 9.7 / SQLite 3.49 + HikariCP 连接池 |
| 认证 | JWT (jjwt 0.11.5) |
| JSON | Gson 2.14.0 |
| 配置 | SnakeYAML 2.4 |
| 密码加密 | jBCrypt 0.4 |
| 邮件 | Jakarta Mail (Angus Mail 2.0.3) |

---

## 项目结构

```
src/main/java/com/mtxgdn/
├── Main.java                    # 程序入口，服务器启动和组件初始化
│
├── client/                      # API 客户端（供外部客户端调用）
│   ├── ApiClient.java           # HTTP REST 客户端
│   ├── ApiConfig.java           # 客户端配置
│   ├── AuthApi.java             # 认证 API
│   ├── GameApi.java             # 游戏 API
│   └── GameWebSocketClient.java # WebSocket 客户端
│
├── common/                      # 通用类型与基础设施
│   ├── ApiResponse.java         # API 统一响应
│   ├── GameErrorCode.java       # 游戏错误码枚举
│   ├── GameMessage.java         # WebSocket 消息协议
│   ├── command/                 # 指令框架
│   │   ├── Command.java         # 指令基类（自注册 + 权限 + 桶式分发 + REST 路由统管）
│   │   ├── CommandContext.java  # 指令上下文（回复/权限校验/翻译名支持）
│   │   ├── CommandRegistry.java # 指令注册中心
│   │   ├── CommandScanner.java  # 指令扫描器（classpath 递归扫描）
│   │   └── RouteDefinition.java # 路由定义（onebotOnly / GET / POST，附 RestContext + matchPath）
│   └── service/
│       └── ServiceRegistry.java # 服务统一注册中心
│
├── db/
│   └── DatabaseManager.java     # 数据库连接管理、表结构初始化（MySQL/SQLite 双适配）
│
├── demo/
│   └── DemoClient.java          # 命令行交互式演示客户端
│
├── entity/
│   ├── Player.java              # 玩家数据实体
│   └── User.java                # 用户账户实体
│
├── game/
│   ├── config/
│   │   └── GameConfigLoader.java    # 境界配置加载器
│   ├── entity/                     # 游戏实体
│   │   ├── CelestialPhenomenon.java
│   │   ├── ExplorationResult.java
│   │   ├── Monster.java              # 怪物/Boss 实体（属性、掉落表、Boss 工厂）
│   │   ├── PlayerInfo.java
│   │   ├── PveCombatResult.java      # PVE 战斗结果
│   │   ├── RealmBreakthroughResult.java
│   │   ├── RealmConfig.java
│   │   ├── RealmConfigFile.java
│   │   ├── Recipe.java               # 制造配方实体
│   │   ├── SecretRealmResult.java
│   │   ├── Skill.java
│   │   ├── SpiritualRoot.java
│   │   ├── Friend.java               # 好友实体
│   │   ├── ChatMessage.java          # 聊天消息实体
│   │   ├── Technique.java            # 功法实体
│   │   ├── Sect.java                 # 宗门实体
│   │   ├── SectMember.java           # 宗门成员实体
│   │   ├── SectApplication.java      # 入宗申请实体
│   │   └── SectWarehouseItem.java    # 宗门仓库物品实体
│   ├── explorationevent/           # 游历事件系统
│   │   ├── ExplorationEvent.java       # 事件抽象基类
│   │   ├── ExplorationEventRegistry.java  # 事件注册中心
│   │   └── ExplorationEventScanner.java   # 事件扫描器
│   ├── item/                       # 物品系统
│   │   ├── Item.java               # 物品基类（集成 LangManager 翻译）
│   │   ├── ItemEffect.java         # 物品效果接口
│   │   ├── ItemRarity.java         # 稀有度枚举
│   │   ├── ItemRegistry.java       # 物品注册中心（支持 key + 中文名 resolve）
│   │   ├── ItemScanner.java        # 物品扫描器
│   │   ├── ItemType.java           # 物品类型枚举
│   │   ├── BuffEffect.java         # Buff 效果
│   │   ├── CurrencyEffect.java     # 货币效果
│   │   ├── EmptyEffect.java        # 空效果
│   │   ├── ExpEffect.java          # 经验效果
│   │   ├── HealEffect.java         # 治疗效果
│   │   └── LearnSkillEffect.java   # 学习技能效果
│   ├── secretrealm/                # 秘境系统
│   │   ├── SecretRealm.java        # 秘境基类（集成 LangManager 翻译）
│   │   ├── SecretRealmRegistry.java
│   │   └── SecretRealmScanner.java
│   └── service/                    # 游戏服务层
│       ├── CombatService.java      # 战斗系统（含灵根特效）
│       ├── CraftingService.java    # 制造系统（炼丹/炼器）
│       ├── DailyService.java       # 每日系统（晨修/机缘/天象/灵根共鸣）
│       ├── EnhanceService.java     # 装备强化系统
│       ├── ExplorationService.java # 游历探索（含灵根 CD 缩减）
│       ├── HeartDemonService.java  # 心魔系统
│       ├── ItemService.java        # 物品管理
│       ├── NewbieGuideService.java # 新手引导
│       ├── OfflineRewardService.java # 离线收益（修炼/HP恢复/降频心魔）
│       ├── PlayerService.java      # 玩家管理
│       ├── RealmService.java       # 境界突破（含天劫）
│       ├── SecretRealmService.java # 秘境探索
│       ├── SkillService.java       # 技能管理（含熟练度/灵根加成）
│       ├── TechniqueService.java   # 功法/心法系统
│       ├── ChatService.java        # 聊天系统（世界频道+私聊）
│       ├── FriendService.java      # 好友系统
│       ├── TradeService.java       # 坊市交易
│       └── SectService.java        # 宗门系统
│
├── minecraft/
│   ├── MinecraftMotdServer.java    # Minecraft MOTD 服务器
│   └── VarInt.java                 # Minecraft VarInt 编解码
│
├── onebot/                         # QQ 机器人集成
│   ├── OneBotAccountFlow.java      # OneBot 账号流程（注册/绑定/解绑）
│   ├── OneBotWebSocketServer.java  # OneBot WebSocket 服务端 + 消息发送
│   ├── OneBotMessageSender.java    # OneBot 消息发送接口
│   ├── QqBinding.java              # QQ 绑定实体
│   ├── QqBindingService.java       # QQ 绑定服务
│   └── command/                    # QQ 机器人指令（按功能模块化）
│       ├── OneBotCommandContext.java  # OneBot 指令上下文
│       ├── account/                   # 账号指令（Help/Register/Bind/Unbind）
│       ├── cultivation/               # 修炼指令（Cultivate/CultivateStop/Breakthrough）
│       ├── exploration/               # 探索指令（Explore/SecretAreas/SecretEnter）
│       ├── item/                      # 物品指令（Backpack/ItemUse/ItemMap/Equip/Unequip/Equipped）
│       ├── market/                    # 坊市指令（Market/ListItem/BuyItem/CancelListing/MyListings）
│       ├── social/                    # 社交指令（Friend/PrivateMessage/Rank）
│       ├── combat/                    # 战斗指令（Pvp/Skills/LearnSkill）
│       ├── daily/                     # 每日指令（Daily/Morning）
│       ├── player/                    # 角色指令（Status/Heal）
│       ├── sect/                      # 宗门指令（SectCommand）
│       └── admin/                     # 管理指令（ClearPlayersDb/ResetAllDb/Give/EditPlayer/Trace...）
│
├── permission/                     # 权限系统
│   ├── PermissionCode.java         # 权限码定义
│   ├── PermissionService.java      # 权限服务
│   └── RequirePermission.java      # 权限注解
│
├── rest/                           # REST API 层
│   ├── AdminAuthFilter.java        # 管理员认证过滤器
│   ├── AdminResource.java          # 管理后台 API
│   ├── Auth.java                   # 用户认证 API
│   ├── EntityBufferFilter.java     # 响应缓冲过滤器
│   ├── EofExceptionMapper.java     # 异常映射
│   ├── GameResource.java           # 游戏 API（显式路径）
│   ├── JwtAuthFilter.java          # JWT 认证过滤器
│   ├── PermissionFilter.java       # 权限过滤器
│   └── UnifiedRestResource.java    # 统一 REST 分发器（Command 内联路由自动注册）
│
├── service/                        # 通用服务
│   ├── UserService.java            # 用户服务
│   └── VerificationCodeService.java # 验证码服务
│
├── util/                           # 工具类
│   ├── AppConfig.java              # 配置文件读取
│   ├── EmailService.java           # 邮件发送
│   ├── GameLogger.java             # 游戏日志
│   ├── JwtUtil.java                # JWT 工具
│   ├── LangManager.java            # 多语言管理器（JSON 翻译文件加载）
│   ├── MySqlLauncher.java          # MySQL 启动器
│   ├── OneBotLogger.java           # OneBot 日志
│   └── PlayerActionLogger.java     # 玩家行为日志
│
└── websocket/
    └── GameWebSocketApp.java       # WebSocket 游戏服务

src/main/java/data/mtxgdn/          # 游戏数据定义
├── explorationevent/               # 10 种游历事件实体
│   ├── CultivatorEvent.java
│   ├── HerbEvent.java
│   ├── MerchantEvent.java
│   ├── MonsterEvent.java
│   ├── NothingEvent.java
│   ├── RuinsEvent.java
│   ├── SpiritSpringEvent.java
│   ├── StrangeTowerEvent.java
│   ├── TrapEvent.java
│   └── TreasureEvent.java
├── item/                           # 28 种物品实体
│   ├── BasicSwordManual.java
│   ├── BeastCore.java
│   ├── CultivationElixir.java
│   ├── DragonBloodCrystal.java
│   ├── EnhanceStone.java
│   ├── FireDragonArt.java
│   ├── GoldBag.java
│   ├── GuardianJade.java
│   ├── HealingPill.java
│   ├── HeavenlyJade.java
│   ├── HeavenPill.java
│   ├── IronOre.java
│   ├── JadeArmor.java
│   ├── ManaPill.java
│   ├── PowerBuffPill.java
│   ├── ProtectCharm.java
│   ├── ScripturePage.java
│   ├── SpeedTalisman.java
│   ├── SpiritGrass.java
│   ├── SpiritRecoveryPill.java
│   ├── SpiritSpringWater.java
│   ├── SpiritStone.java
│   ├── SpiritStonePouch.java
│   ├── SpiritSword.java
│   ├── ThunderBoltTalisman.java
│   └── TribulationPill.java
├── lang/                           # 多语言翻译文件
│   └── zh_cn.json                  # 简体中文翻译（物品/秘境/事件/系统消息）
└── secretrealm/                    # 8 个秘境区域
    ├── AncientBattlefield.java
    ├── AncientRuins.java
    ├── BeastMountain.java
    ├── DarkForest.java
    ├── ImmortalCave.java
    ├── NineHeavensPlatform.java
    ├── ThunderValley.java
    └── WildGrassland.java
```

---

## 快速开始

### 环境要求

- **JDK 23** 或更高
- **Maven 3.8+**
- **MySQL 8.0+**（使用 MySQL 模式时，需创建数据库 `xiuxian`）
- **SQLite 3.x**（使用 SQLite 模式时零配置，驱动已内置）

### 1. 克隆仓库

```bash
git clone https://github.com/oooohhhhhhhhhh/xiuxiangame.git
```

### 2. 选择数据库模式

#### MySQL 模式（默认）

```sql
CREATE DATABASE IF NOT EXISTS xiuxian CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

编辑 `src/main/resources/application.yml`：

```yaml
database:
  type: mysql
  url: jdbc:mysql://localhost:3306/xiuxian?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci
  username: root
  password: 你的密码
```

#### SQLite 模式（零依赖）

编辑 `src/main/resources/application.yml`，只需修改 `type` 即可：

```yaml
database:
  type: sqlite
  sqlite_path: xiuxian.db
```

> 切换 `type` 字段即可在 MySQL 和 SQLite 之间切换，无需改动任何代码。SQLite 模式下无需安装任何数据库软件。

### 3. 构建

```bash
cd xiuxian-server
mvn clean package -DskipTests
```

### 4. 运行

```bash
java -jar target/main-V0.0.0-alpha.jar
```

启动后会自动初始化数据库表结构、扫描并注册游戏数据。

#### 启动参数

| 参数 | 说明 |
|------|------|
| `--demo` | 启动交互式演示客户端，服务关闭时自动退出 |
| `--nogui` | 无 GUI 模式，按 Enter 关闭服务器 |

### 4. 访问

| 服务 | 地址 |
|------|------|
| REST API | `http://127.0.0.1:8080/api/` |
| WebSocket | `ws://127.0.0.1:8080` |
| 管理控制台 | `http://127.0.0.1:8080/admin/` |
| OneBot QQ | `ws://127.0.0.1:6700/onebot` |
| Minecraft MOTD | `127.0.0.1:25565` |

管理后台需在 `application.yml` 中配置 `admin.username` / `admin.password` 后才能登录。

---

## REST API 概览

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/login` | 用户登录（返回 access_token） |
| POST | `/api/auth/refresh` | 刷新令牌 |
| POST | `/api/auth/send-code` | 发送邮箱验证码 |

### 游戏 API（需携带 JWT Token）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/game/player` | `game.player.info` | 获取玩家信息（含灵根详情和离线收益） |
| POST | `/api/game/player/create` | `game.player.create` | 创建角色（灵根自动抽取） |
| POST | `/api/game/realm/breakthrough` | `game.realm.breakthrough` | 境界突破（含天劫） |
| GET | `/api/game/realm/config` | `game.realm.config` | 查看境界配置 |
| POST | `/api/game/cultivate/start` | `game.cultivate` | 开始修炼 |
| POST | `/api/game/cultivate/stop` | `game.cultivate` | 停止修炼（含心魔判定） |
| POST | `/api/game/exploration` | `game.explore` | 游历探索 |
| POST | `/api/game/heal` | `game.player.info` | 灵石疗伤（HP≤0可用，境界越高花费越多） |
| GET | `/api/game/secret_realm/areas` | `game.secret_realm` | 查看可用秘境 |
| POST | `/api/game/secret_realm/enter` | `game.secret_realm` | 进入秘境 |
| GET | `/api/game/inventory` | `game.inventory.view` | 查看背包 |
| POST | `/api/game/item/use` | `game.item.use` | 使用物品（支持名称/key） |
| GET | `/api/game/item/registry` | `game.item.registry` | 物品图鉴 |
| POST | `/api/game/item/add` | `game.item.add` | 添加物品 |
| GET | `/api/game/skills` | `game.item.registry` | 技能列表 |
| GET | `/api/game/skill/my` | `game.inventory.view` | 我的技能 |
| POST | `/api/game/skill/learn` | `game.skill.learn` | 学习技能 |
| POST | `/api/game/pvp/challenge` | `game.pvp.challenge` | PVP 挑战 |
| GET | `/api/game/equipment` | `game.inventory.view` | 查看已装备 |
| POST | `/api/game/equipment/equip` | `game.equipment.equip` | 装备物品 |
| POST | `/api/game/equipment/unequip` | `game.equipment.equip` | 卸下装备 |
| POST | `/api/game/equipment/enhance` | `game.equipment.enhance` | 装备强化 |
| GET | `/api/game/chat/world` | `game.chat.world` | 世界聊天记录 |
| POST | `/api/game/chat/world` | `game.chat.world` | 发送世界消息 |
| GET | `/api/game/chat/private/{targetId}` | `game.chat.private` | 私聊记录 |
| POST | `/api/game/chat/private` | `game.chat.private` | 发送私聊消息 |
| GET | `/api/game/rank` | `game.rank.view` | 排行榜（?type=realm\|power\|wealth） |
| POST | `/api/game/friend/add` | `game.friend.manage` | 发送好友申请 |
| POST | `/api/game/friend/accept` | `game.friend.manage` | 接受好友申请 |
| POST | `/api/game/friend/remove` | `game.friend.manage` | 删除好友 |
| GET | `/api/game/friend/list` | `game.friend.manage` | 好友列表 |
| GET | `/api/game/friend/pending` | `game.friend.manage` | 待处理的好友申请 |
| GET | `/api/game/techniques` | `game.technique.learn` | 功法列表 |
| GET | `/api/game/technique/my` | `game.technique.learn` | 我的功法 |
| POST | `/api/game/technique/learn` | `game.technique.learn` | 学习功法 |
| POST | `/api/game/technique/equip` | `game.technique.equip` | 装备功法 |
| POST | `/api/game/technique/unequip` | `game.technique.equip` | 卸下功法 |
| POST | `/api/game/technique/upgrade` | `game.technique.upgrade` | 升级功法 |
| GET | `/api/game/crafting/recipes` | `game.crafting.recipes` | 查看配方（?category=PILL） |
| POST | `/api/game/crafting/craft` | `game.crafting.craft` | 制造物品 |
| GET | `/api/game/spiritual_roots` | `game.player.info` | 灵根图鉴 |
| POST | `/api/game/daily/morning_cultivation` | `game.daily` | 晨修 |
| GET | `/api/game/daily` | `game.daily` | 今日天象与机缘进度 |
| GET | `/api/game/market` | `game.market.view` | 坊市挂单列表 |
| POST | `/api/game/market/list` | `game.market.list` | 上架物品 |
| POST | `/api/game/market/buy` | `game.market.buy` | 购买物品 |
| POST | `/api/game/market/cancel` | `game.market.cancel` | 撤单 |
| GET | `/api/game/market/my_listings` | `game.market.view` | 我的挂单 |
| GET | `/api/game/sect/list` | `game.sect.manage` | 宗门列表 |
| GET | `/api/game/sect/info` | `game.sect.manage` | 我的宗门信息 |
| GET | `/api/game/sect/info/{sectId}` | `game.sect.manage` | 查看指定宗门 |
| POST | `/api/game/sect/create` | `game.sect.manage` | 创建宗门 |
| POST | `/api/game/sect/apply` | `game.sect.manage` | 申请加入宗门 |
| POST | `/api/game/sect/approve` | `game.sect.manage` | 审批入宗申请 |
| POST | `/api/game/sect/reject` | `game.sect.manage` | 拒绝入宗申请 |
| POST | `/api/game/sect/leave` | `game.sect.manage` | 退出宗门 |
| POST | `/api/game/sect/kick` | `game.sect.manage` | 踢出成员 |
| POST | `/api/game/sect/appoint` | `game.sect.manage` | 任命宗门职位 |
| POST | `/api/game/sect/donate` | `game.sect.donate` | 宗门捐献 |
| POST | `/api/game/sect/warehouse/deposit` | `game.sect.warehouse` | 宗门仓库存入 |
| POST | `/api/game/sect/warehouse/withdraw` | `game.sect.warehouse` | 宗门仓库取出 |
| POST | `/api/game/sect/disband` | `game.sect.manage` | 解散宗门 |
| GET | `/api/game/sect/top` | `game.sect.manage` | 宗门排行 |
| GET | `/api/game/sect/pending` | `game.sect.manage` | 待处理的入宗申请 |
| GET | `/api/game/status` | - | 服务器状态 |
| GET | `/api/game/players` | - | 玩家排行榜 |
| GET | `/api/game/players/search` | - | 搜索玩家 |

### 管理后台 API（需管理员 Token）

管理后台使用独立的管理员 JWT 认证，需先在 `application.yml` 中配置 `admin.username` / `admin.password`。

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/admin/login` | `admin.login` | 管理员登录 |
| GET | `/api/admin/status` | `admin.status` | 服务器运行状态 |
| GET | `/api/admin/logs` | `admin.logs.view` | 查看实时日志 |
| POST | `/api/admin/shutdown` | `admin.shutdown` | 关闭服务器 |
| GET | `/api/admin/roles` | `admin.roles.manage` | 查看所有角色及权限 |
| GET | `/api/admin/permissions` | `admin.roles.manage` | 查看所有权限码 |
| GET | `/api/admin/users` | `admin.users.manage` | 查看所有用户及角色 |
| POST | `/api/admin/user/{userId}/role` | `admin.users.manage` | 给用户分配角色 |
| DELETE | `/api/admin/user/{userId}/role/{roleName}` | `admin.users.manage` | 移除用户角色 |
| POST | `/api/admin/database/clear_players` | `admin.database.clear_players` | 清除所有玩家数据 |
| POST | `/api/admin/database/reset_all` | `admin.database.reset_all` | 重置全部数据（含熔断初始化） |

---

## 权限系统

项目采用 **RBAC + 细粒度权限码** 的双层权限模型。

### 角色层级

| 角色 | 级别 | 说明 |
|------|------|------|
| `SUPER_ADMIN` | 100 | 超级管理员，拥有所有权限 |
| `ADMIN` | 80 | 管理员，除用户/权限管理外的所有功能 |
| `MODERATOR` | 50 | 协管，游戏功能 + 管理后台查看 |
| `PLAYER` | 10 | 普通玩家，全部游戏功能 + QQ 绑定 |
| `GUEST` | 0 | 游客，仅 QQ 基本查询 |

- 高等级角色自动拥有低等级角色的全部权限
- 用户可同时拥有多个角色，权限取并集
- 新注册用户自动获得 `PLAYER` 角色
- 可通过管理后台 API 动态调整用户角色

### 权限码分类

| 分类 | 前缀 | 数量 | 示例 |
|------|------|------|------|
| 游戏功能 | `game.*` | 29+ | `game.cultivate`, `game.explore`, `game.technique.learn`, `game.chat.world`, `game.rank.view`, `game.friend.manage` |
| QQ 指令 | `qq.*` | 5 | `qq.bind`, `qq.command.admin` |
| 管理后台 | `admin.*` | 8 | `admin.shutdown`, `admin.database.reset_all` |

---

## WebSocket 协议

客户端连接后需先发送认证消息：

```json
{"type":"auth","msgId":1,"data":{"token":"<jwt_access_token>"}}
```

认证成功返回 `welcome` 消息，包含用户信息和离线收益（如有）：

```json
{
  "type":"welcome",
  "success":true,
  "data":{
    "userId":1001,
    "username":"player1",
    "message":"连接成功，欢迎进入修仙世界！",
    "offlineReward":{
      "offlineSeconds":3600,
      "offlineMinutes":60,
      "expGained":450,
      "hpRecovered":120
    }
  }
}
```

支持的 `type`：

| type | 说明 |
|------|------|
| `chat` | 世界聊天 |
| `chat_private` | 私聊消息 |
| `chat_history` | 世界聊天记录 |
| `player_info` | 获取玩家信息 |
| `cultivate_start` | 开始修炼 |
| `cultivate_stop` | 停止修炼（含心魔判定） |
| `breakthrough` | 境界突破（含天劫） |
| `inventory` | 查看背包 |
| `item_use` | 使用物品 |
| `item_registry` | 物品图鉴 |
| `heartbeat` | 心跳（返回 pong） |
| `secret_realm_areas` | 查看可用秘境 |
| `secret_realm_enter` | 进入秘境 |
| `exploration` | 游历探索 |
| `heal` | 灵石疗伤 |
| `techniques` | 功法列表 |
| `my_techniques` | 我的功法 |
| `technique_learn` | 学习功法 |
| `technique_equip` | 装备功法 |
| `technique_unequip` | 卸下功法 |
| `technique_upgrade` | 升级功法 |
| `crafting_recipes` | 查看配方（可选 category） |
| `crafting_craft` | 制造物品 |
| `equipment_enhance` | 装备强化 |
| `rank` | 排行榜（可选 type=realm\|power\|wealth） |
| `friend_add` | 发送好友申请 |
| `friend_accept` | 接受好友申请 |
| `friend_remove` | 删除好友 |
| `friend_list` | 好友列表 |
| `friend_pending` | 待处理的好友申请 |

---

## QQ 机器人指令

支持 OneBot 协议（如 Lagrange、LLOneBot），私聊或群聊中以 `/` 开头使用指令。所有涉及物品的指令均支持**中文名**和 **key** 混用（如 `/使用 回血丹` 和 `/使用 healing_pill` 等价）。

### 账号与系统

| 指令 | 权限 | 说明 |
|------|------|------|
| `/help` / `/帮助` | - | 查看帮助（按权限分级显示） |
| `/register <角色名>` / `/注册` | `qq.bind` | 注册角色并绑定QQ（群聊可用，密码私聊发送） |
| `/bind` / `/绑定` | `qq.bind` | 绑定已有游戏账号（仅私聊） |
| `/unbind` / `/解绑` | `qq.bind` | 解除QQ绑定（仅私聊） |

### 角色与修炼

| 指令 | 权限 | 说明 |
|------|------|------|
| `/status` / `/状态` | `game.player.info` | 查看修炼状态（含HP/MP/攻防速/金币灵石） |
| `/cultivate` / `/修炼` / `/闭关` | `game.cultivate` | 开始修炼 |
| `/stop` / `/停止` | `game.cultivate` | 停止修炼（可能触发心魔） |
| `/breakthrough` / `/突破` | `game.realm.breakthrough` | 境界突破（可能触发天劫） |
| `/heal` / `/疗伤` | `game.player.info` | 灵石疗伤（消耗灵石回满HP+MP） |

### 探索

| 指令 | 权限 | 说明 |
|------|------|------|
| `/explore` / `/游历` | `game.explore` | 游历探索 |
| `/secret` / `/秘境` | `game.secret_realm` | 查看可用秘境（含境界/冷却/描述） |
| `/secret_enter <名称>` / `/进入秘境` | `game.secret_realm` | 进入秘境（支持中文名，如 /进入秘境 远古战场） |

### 物品与装备

| 指令 | 权限 | 说明 |
|------|------|------|
| `/backpack` / `/背包` | `game.inventory.view` | 查看背包（物品名 + key） |
| `/itemuse <物品>` / `/使用` | `game.item.use` | 使用物品（支持中文名/key） |
| `/itemmap` / `/物品列表` | `game.player.info` | 查看物品中文名与 key 的映射表 |
| `/equip <物品> <部位>` / `/装备` | `game.equipment.equip` | 装备物品（部位：weapon/armor/accessory） |
| `/unequip <部位>` / `/卸下` | `game.equipment.equip` | 卸下装备 |
| `/equipped` / `/已装备` | `game.inventory.view` | 查看已装备的物品 |

### 技能

| 指令 | 权限 | 说明 |
|------|------|------|
| `/skills` / `/技能` | `game.player.info` | 查看技能列表（含境界/金币要求） |
| `/learn <技能ID或名称>` / `/学习` | `game.skill.learn` | 学习技能（支持中文名和ID） |

### 坊市交易

| 指令 | 权限 | 说明 |
|------|------|------|
| `/market` / `/坊市` | `game.player.info` | 查看坊市挂单（最多15条） |
| `/list <物品> <数量> <灵石>` / `/上架` | `game.market.list` | 上架物品 |
| `/buy <挂单ID>` / `/购买` | `game.market.buy` | 购买坊市物品 |
| `/cancel <挂单ID>` / `/撤单` | `game.market.cancel` | 撤单 |
| `/mylistings` / `/我的挂单` | `game.player.info` | 查看我的挂单 |

### 宗门

| 指令 | 权限 | 说明 |
|------|------|------|
| `/sect` / `/宗门` | `game.sect.manage` | 宗门系统主指令（无参数显示概览） |
| `/宗门 create <名称> <简介>` | `game.sect.manage` | 创建宗门（≥3级，100灵石） |
| `/宗门 join <宗门ID>` | `game.sect.manage` | 申请加入宗门 |
| `/宗门 list` / `列表` | `game.sect.manage` | 查看所有宗门 |
| `/宗门 info [宗门ID]` / `信息` | `game.sect.manage` | 查看宗门详情 |
| `/宗门 members` / `成员` | `game.sect.manage` | 查看本宗成员 |
| `/宗门 apply <宗门ID>` / `申请` | `game.sect.manage` | 提交入宗申请 |
| `/宗门 approve <成员名>` / `通过` | `game.sect.manage` | 批准入宗申请（宗主/副宗主） |
| `/宗门 reject <成员名>` / `拒绝` | `game.sect.manage` | 拒绝入宗申请 |
| `/宗门 leave` / `退出` | `game.sect.manage` | 退出宗门 |
| `/宗门 kick <成员名>` / `踢出` | `game.sect.manage` | 踢出成员（宗主/副宗主） |
| `/宗门 appoint <成员名> <职位>` / `任命` | `game.sect.manage` | 任命副宗主/护法（宗主） |
| `/宗门 donate <物品key> <数量>` / `捐献` | `game.sect.donate` | 向宗门仓库捐献物品 |
| `/宗门 warehouse` / `仓库` | `game.sect.warehouse` | 查看宗门仓库 |
| `/宗门 take <物品key> <数量>` / `取出` | `game.sect.warehouse` | 从仓库取出物品 |
| `/宗门 disband` / `解散` | `game.sect.manage` | 解散宗门（宗主） |
| `/宗门 top` / `排行` | `game.sect.manage` | 宗门声望排行榜 |
| `/宗门 pending` / `申请列表` | `game.sect.manage` | 查看待审批的入宗申请 |

### 社交与排行

| 指令 | 权限 | 说明 |
|------|------|------|
| `/rank [类型]` / `/排行` | `game.rank.view` | 查看排行榜（realm/power/wealth） |
| `/好友 <add\|accept\|remove\|list>` | `game.friend.manage` | 好友管理 |
| `/msg <玩家名> <内容>` / `/私聊` | `game.chat.private` | 发送私聊消息 |
| `/pvp <角色名>` / `/挑战` | `game.pvp.challenge` | PVP 挑战其他修士 |

### 每日

| 指令 | 权限 | 说明 |
|------|------|------|
| `/daily` / `/天象` | `game.player.info` | 查看今日天象与机缘 |
| `/morning` / `/晨修` | `game.player.info` | 每日晨修（获取修炼加成） |

### 管理（SUPER_ADMIN，仅私聊）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/cleardb_players` / `/清除玩家数据` | `admin.database.clear_players` | 清除所有玩家数据 |
| `/cleardb_all` / `/重置全部数据` | `admin.database.reset_all` | 重置全部数据并重新初始化 |

---

## 配置说明

配置文件位于 `src/main/resources/application.yml`：

```yaml
app:
  name: 修仙世界          # 应用名称

server:
  host: 127.0.0.1         # API 监听地址
  port: 8080              # API 监听端口

database:
  type: mysql             # 数据库类型: mysql | sqlite
  url: jdbc:mysql://...   # MySQL 连接地址（type=mysql 时使用）
  username: root          # MySQL 用户名
  password: root          # MySQL 密码
  sqlite_path: xiuxian.db # SQLite 文件路径（type=sqlite 时使用）

jwt:
  secret: ""              # JWT 签名密钥（至少256位，留空则每次启动自动生成随机密钥）

admin:
  username: ""            # 管理后台账号（必须配置）
  password: ""            # 管理后台密码（必须配置）

smtp:
  host: smtp.qq.com       # SMTP 服务器
  port: 587
  username: ""            # 发件邮箱
  password: ""            # 授权码
  from: ""
  auth: true
  starttls: true

onebot:
  enabled: true           # 是否启用 QQ 机器人
  port: 6700              # OneBot WebSocket 端口

logging:
  level: DEBUG            # 日志级别
  dir: log                # 日志目录
  color: true             # 彩色日志
```

---

## 游戏系统详解

### 🔮 灵根系统

创建角色时自动随机抽取灵根。共 15 种，分为 6 个等级：

| 等级 | 抽取概率 | 灵根 | 属性加成 | 特殊效果 |
|:--:|:--:|------|------|------|
| **天灵根** | 15%（各 3%） | 太乙金灵根 | 攻击+15% 灵力+5% | 暴击率 +5% |
| | | 青帝木灵根 | 生命+15% 速度+5% | 每回合恢复 3% 生命 |
| | | 玄冥水灵根 | 法力+15% 防御+5% | 技能蓝耗 -20% |
| | | 离火灵根 | 攻击+10% 灵力+5% 速度+5% | 伤害 +10% |
| | | 厚土灵根 | 防御+15% 生命+10% | 受到伤害 -15% |
| **异灵根** | 15%（各 5%） | 巽风灵根 | 攻击+5% 速度+15% | 游历CD -30% |
| | | 惊雷灵根 | 攻击+5% 灵力+15% | 暴击伤害 +30% |
| | | 玄冰灵根 | 法力+10% 防御+10% | 技能伤害 +15% |
| **双灵根** | 30%（各 10%） | 金火灵根 | 攻击+8% 灵力+4% | 怪物经验 +25% |
| | | 木水灵根 | 生命+8% 法力+8% | 修炼效率 +15% |
| | | 土金灵根 | 攻击+4% 防御+8% | 坊市手续费减半 |
| **三灵根** | 20%（各 10%） | 火土木灵根 | 全属性+2% | 灵石掉落 +20% |
| | | 金水木灵根 | 法力+2% 灵力+2% 速度+2% | 熟练度 +30% |
| **四灵根** | 14% | 四象灵根 | 全属性+2% | 无 |
| **杂灵根** | 6% | 混沌灵根 | 无初始加成 | 突破时额外全属性+5 |

> 特殊效果已嵌入战斗、修炼、游历、秘境、坊市、技能熟练度等各系统。

---

### ⚡ 渡劫系统

**小境界**（同一大境界内）：100% 成功，无天劫。

**大境界突破**：触发天劫（7 种，按境界匹配：雷劫/心魔劫/风火劫/水劫/五行劫/阴阳劫/轮回劫）。

| 突破 | 基础成功率 |
|------|:--:|
| 凡人 → 炼气 | 80% |
| 炼气 → 筑基 | 70% |
| 筑基 → 金丹 | 60% |
| 金丹 → 元婴 | 50% |
| 元婴 → 化神 | 40% |
| 化神 → 合体 | 30% |
| 合体 → 大乘 | 20% |
| 大乘 → 渡劫 | 15% |
| 渡劫 → 真仙 | 10% |

**成功率修正**：天灵根 +15% | 异灵根 +10% | 双灵根 +5% | 灵力每 50 点 +1%（上限 15%） | 天劫对应属性加成 | 渡劫丹 +10% | 最终上限 95%

**失败惩罚**：扣除该境界 1/3 经验 + HP 降为 1（混沌灵根失败也小幅成长）

---

### 😈 心魔系统

修炼结束时概率触发，修为倒退。

| 参数 | 值 |
|------|------|
| 基础触发率 | 每小时修炼 8%（上限 40%） |
| 灵力抵抗 | 每 15 灵力 -1%（上限 -8%） |
| 天灵根/异灵根抵抗 | -3~-4% |
| 杂灵根 | +2%（混沌觉醒后反转） |

**心魔程度**：轻微（50%，损失 20-40%） | 中等（35%，损失 50-70%） | 严重（15%，损失 80-100%）

---

### 🌌 天象系统

全服每日随机天象，影响全局倍率：

| 天象 | 修炼 | 游历 | 战斗 | 灵石 |
|------|:---:|:---:|:---:|:---:|
| 紫气东来 | +30% | | | |
| 星辰耀天 | | +20% | +20% | |
| 血月当空 | | | +30% | |
| 灵潮涌动 | | | | +50% |
| 枯荣交替 | 恢复翻倍 | | | |
| 万籁俱寂 | +5% | +5% | +5% | +5% |

---

### 🏪 坊市交易

玩家间物品交易，灵石结算。

| 规则 | 说明 |
|------|------|
| 交易货币 | 灵石 |
| 手续费 | 5%（土金灵根减半至 2.5%） |
| 撤单 | 物品退还，手续费不退还 |
| 自买 | 禁止 |

---

### 📅 每日系统

- **晨修·紫气东来**：每日一次，获得经验+灵石，连续天数越多收益越高
- **今日机缘**：游历3次 / PvP1次 / 秘境1次 / 学技能1次，完成后当场发放奖励
- **灵根共鸣**：连续活跃 7 天 → 属性小幅提升；连续活跃 30 天 → 属性大幅提升

---

### ⚔️ 技能熟练度

- 战斗中使用技能获得熟练度
- 熟练度达到当前等级 ×100 后自动升级
- 使用同名技能书直接 +1 级
- 等级越高蓝耗越高（每级 +15%）
- 金水木灵根熟练度获取 +30%

---

### 🐉 PVE 战斗系统

游历和秘境中遭遇妖兽时，触发完整回合制 PVE 战斗。

**战斗机制**：
- 回合制，速度高者先手，最多 30 回合
- 自动选择可用的攻击技能，消耗 MP
- 暴击率、暴击伤害、技能伤害、伤害加成/减免 → 全部灵根特效生效
- 击败妖兽获得经验、金币、灵石，有概率掉落物品
- **金火灵根**怪物经验 +25%，**火土木灵根**灵石掉落 +20%

**秘境 Boss**（10% 遭遇概率）：

| 秘境 | 需求境界 | Boss | 描述 |
|------|:--:|------|------|
| 荒野草原 | 凡人 | 草原霸主·奔雷兽 | 雷光闪烁，刀枪不入 |
| 暗黑森林 | 炼气 | 暗影领主·噬魂蛛皇 | 毒液腐蚀护体灵气 |
| 百兽山脉 | 筑基 | 兽王·金翼裂天雕 | 翅展遮天，爪裂金石 |
| 雷渊谷 | 金丹 | 雷渊守护者·雷霆巨蟒 | 万雷淬体，电光如龙 |
| 古修遗迹 | 金丹 | 遗迹守卫·青铜巨像 | 拳风摧山岳 |
| 九霄台 | 元婴 | 九霄试炼官·天罡战神 | 九天之力，镇压苍穹 |
| 古战场 | 元婴 | 战场英魂·不灭战将 | 战意不灭，杀伐冲天 |
| 仙人洞府 | 化神 | 洞府守护灵·九霄剑魂 | 剑意化形，一剑光寒 |

Boss 拥有 3 倍以上属性，更高掉落率和更丰富的稀有物品掉落。

---

### ⏳ 离线修炼收益

玩家断线后修炼继续进行，无需保持在线。

| 参数 | 值 |
|------|------|
| 离线修炼效率 | **50%**（在线的一半） |
| 最大离线时间 | **8 小时**（超过不计） |
| 最短触发时间 | 30 秒（短暂断触不触发） |
| HP/MP 恢复 | 每分钟 2% 至满 |
| 离线心魔概率 | 在线模式的 **50%** |
| 天象加成 | ✅ 生效（紫气东来等） |
| 灵根加成 | ✅ 生效（木水灵根 +15%） |

> 重新上线时（WebSocket 连接或 REST 接口查询），自动结算离线收益并注入响应数据。

---

### 📖 新手引导（主线 + 自由探索）

创建角色后自动开启，采用**双轨引导**设计：

| 序号 | 触发 | 主线任务 | 💡 自由探索提示 |
|:--:|------|------|------|
| 1 | 创建角色 | /修炼 | 灵根介绍、系统概览 |
| 2 | 开始修炼 | 等一会儿后 /停止 | 试试 /状态、/灵根 |
| 3 | 停止修炼 | /游历 | 看看 /背包、去 /坊市 |
| 4 | 游历 | /秘境 | /技能、/晨修 |
| 5 | 查看秘境 | /进入秘境 | 秘境冷却机制 |
| 6 | 进入秘境 | /突破 | 天劫预警 |
| 7 | 突破 | /晨修 | /坊市、技能书收集 |
| 8 | 晨修 | 🎉 出师！ | 自由探索全系统 |

**自由探索提示**：首次访问以下功能时会弹出一次性说明：
- 🔮 /灵根：灵根加成与影响详解
- 🛒 /坊市：玩家交易市场说明
- 📜 /技能：技能学习与熟练度
- 🌅 /晨修：每日天象加成
- 📅 /日常：今日机缘与任务
- 🎒 /背包：物品管理与使用
- 📖 /帮助：全部指令速览

> 主线每个阶段的 💡 指引都可以自由选择先做主线还是先探索支线。

---

### 🌐 国际化 (i18n)

项目内置多语言支持系统，基于 JSON 翻译文件：

- **翻译文件位置**：`src/main/java/data/mtxgdn/lang/zh_cn.json`
- **覆盖范围**：物品名称/描述、秘境名称/描述、游历事件名称/描述、系统提示消息
- **翻译键格式**：`item.<key>.name` / `item.<key>.desc` / `secretrealm.<key>.name` 等
- **物品使用**：玩家可以通过**中文名称**使用物品（如 `/使用 回血丹`），系统自动解析到对应物品
- **添加语言**：在 `data/mtxgdn/lang/` 下新建 `<lang>.json`，调用 `LangManager.setLanguage("new_lang")` 即可切换

---

## 游戏扩展

项目采用**扫描注册**机制，方便扩展游戏内容：

- **添加新物品**：在 `data/mtxgdn/item/` 下继承 `Item` 类即可自动注册
- **添加新游历事件**：在 `data/mtxgdn/explorationevent/` 下继承 `ExplorationEvent` 并设置权重
- **添加新秘境**：在 `data/mtxgdn/secretrealm/` 下继承 `SecretRealm` 并指定需求境界和冷却时间
- **添加翻译**：在 `zh_cn.json` 中添加对应的翻译键，新物品/事件/秘境即可获得中文显示
- **添加游戏指令（+ 可选 HTTP API）**：在 `onebot/command/` 下新建 `XxxCommand extends Command`，构造函数内 `registerSub` 或 `addRoute` 即可自动注册为 OneBot 指令 + HTTP 路由，无需修改 `GameResource`
- **添加权限码**：在 `PermissionCode` enum 加一行即可
- **添加分类**：在 `Command.CATEGORY_ORDER` 加一行即可控制 `/帮助` 排序

---

## 数据库表

| 表名 | 说明 |
|------|------|
| `users` | 用户账户（用户名、密码哈希） |
| `verification_codes` | 邮箱验证码 |
| `players` | 玩家角色（属性、境界、修炼状态、灵根、引导进度、离线时间） |
| `players_items` | 玩家背包物品（物品 key + 数量） |
| `players_equipment` | 玩家装备槽位（weapon/armor/accessory，含 enhance_level 强化等级） |
| `players_skills` | 玩家技能（等级、熟练度） |
| `players_techniques` | 玩家功法（功法 ID、等级、熟练度、是否装备） |
| `techniques` | 功法定义（名称、类型、属性加成、最大等级等） |
| `recipes` | 制造配方（名称、分类、材料、产出、成功率等） |
| `chat_messages` | 聊天消息（世界/私聊，发送者/接收者，内容） |
| `friends` | 好友关系（申请者/接收者，状态：pending/accepted） |
| `skills` | 技能定义（名称、伤害、蓝耗、境界要求等） |
| `trade_listings` | 坊市挂单（卖家、物品、数量、价格、状态） |
| `sects` | 宗门（名称、简介、宗主、等级、声望、成员上限） |
| `sect_members` | 宗门成员（宗门ID、玩家ID、职位、贡献度） |
| `sect_applications` | 入宗申请（宗门ID、玩家ID、状态） |
| `sect_warehouse_items` | 宗门仓库物品（宗门ID、物品种类、数量） |
| `player_daily` | 每日数据（晨修时间、机缘进度、活跃天数） |
| `qq_bindings` | QQ 与游戏账号绑定 |
| `roles` | 角色定义（名称、显示名、等级） |
| `permissions` | 权限码定义 |
| `role_permissions` | 角色-权限关联 |
| `user_roles` | 用户-角色关联 |

> SQLite 模式下，`AUTO_INCREMENT` 和 `FOREIGN KEY` 会被自动适配为 SQLite 兼容语法。

---

## 许可

MIT License
