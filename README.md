# 修仙世界 · Xiuxian World

> 一个基于 Java 23 的多人在线**文字修仙（MUD）游戏服务端**：修炼突破、探索战斗、社交宗门、生产贸易，并通过 REST / WebSocket / QQ（OneBot / 官方机器人）/ Minecraft 等多端接入。

![Java](https://img.shields.io/badge/Java-23-orange) ![License](https://img.shields.io/badge/License-MIT-blue) ![Version](https://img.shields.io/badge/Version-v0.1.0-green)

---

## ✨ 特性总览

### 角色与成长

- **灵根系统**：鸿蒙 / 正 / 天 / 异 / 双 / 三 / 四 / 杂 8 档共 15+ 种灵根，创建角色时随机抽取；攻击、暴击、回血、减伤、游历 CD、坊市手续费、熟练度等独特加成深入各玩法
- **修炼与境界**：凡人 → 金仙共 11 大境界、29 个修炼阶段（大境界内细分初 / 中 / 后期）；挂机修炼 + **离线收益**（50% 效率、上限 8 小时）；修炼有概率触发**心魔**修为倒退
- **渡劫突破**：大境界突破触发天劫（雷劫 / 心魔劫 / 风火劫 / 水劫 / 五行劫 / 阴阳劫 / 轮回劫），成功率受灵根、灵力与渡劫丹影响；失败扣除经验并重伤。**连败增益（pity）**：每失败一次下次成功率 +20%，成功后清零，不会连续被概率劝退；**凝实（极境）**：修为圆满时可不去赌，改为压制修为淬体，最多 3 层，每层 +8% 基础属性但渡劫成功率按层数打折、失败损失放大。`/突破` 会把成功率逐项构成全部摊开，`/突破 预览` 可在不掷骰的情况下看下次的真实成功率
- **转世重修**：金仙圆满后转世，保留部分修为与家当开启多周目，获得「逆天者」称号

### 探索与战斗

- **游历探索**：13 种加权随机事件（奇遇 / 商人 / 妖兽 / 宝藏 / 陷阱 / 灵泉…）
- **秘境探索**：10 个境界分级的秘境区域，随机事件 + 专属 Boss 战；支持**组队进入副本型秘境**
- **地图旅行**：15 个地点的大地图，移动耗时、地点灵气加成与地界收益倍率全部由数据库驱动，后台改库可热重载（管理台「地图管理」页可视化编辑，含世界脉络树）；游历事件按所在地界加权（落霞山产落霞草、万妖谷多妖兽、灵药谷多灵植），安全区内禁止私斗；支持 `/地图` 浏览与移动
- **PVE / PVP**：完整回合制战斗（技能、暴击、灵根特效全生效）；PVE 含秘境 Boss，PVP 支持挑战与战斗策略，安全区（🛡）内禁止私斗
- **技能与装备**：技能熟练度升级、功法（3 门同时运转）、装备穿戴与强化（+15，含失败降级/碎裂）、Buff 增益
- **阵法与洞府**：阵法布置与加成；洞府灵力收集与升级

### 生产与经济

- **炼丹炼器**：配方驱动制造，可产出丹药 / 装备 / 消耗品 / 种子
- **农场系统**：4–12 块农田、8 种作物，浇水 / 施肥 / 病虫害防治 / 一键收获，受**全局季节**影响
- **坊市与拍卖**：玩家间物品交易（灵石结算、5% 手续费）；竞拍系统；灵庄存款、灵石提纯、签到、修炼加速
- **能量转化**：物品 ↔ 能量等价互转（支持插件注册自定义物品能量）
- **兑换码 / 每日系统**：兑换码发奖；晨修·紫气东来、今日机缘、灵根共鸣

### 社交与运营

- **宗门系统**：创建 / 申请 / 任命 / 捐献 / 仓库 / 宗门战 / 排行，5 级职位体系；**宗主缺位继位 + 竞争期** —— 宗主可主动退位（卸任即降副宗主，系统按贡献选出继任者，退位后即可正常退宗），宗主连续不活跃 14 天自动换人，继任者与最强竞争者境界接近时开启 7 天竞争期，期内可发起三局两胜、双方战力归一化的宗主争夺战，解开「宗主弃坑 → 宗门死锁」
- **世界 Boss**：全服共享同一条血的 Boss（默认 3 只、4 小时整池重建）。挑战直接复用 `pveFight` 的完整战斗（技能 / 功法 / 阵法 / 灵根 / 天象），**血量直接落库**、无内存态无定时器，重启后血条原样还在；奖励按**伤害比例分红**（`预算 × 本次伤害 / 最大血量`）而非击杀独占，因此总发放天然不超过预算；补最后一刀额外拿 20% 修为；单次奖励超 100 万自动**熔断**折算；每人每只 Boss 有挑战冷却与境界门槛。`/世界boss` 查看 / 挑战 / 排行
- **好友 / 聊天**：好友申请与每日拜访；世界频道与私聊（消息持久化 + WebSocket 实时推送）
- **称号与排行**：多品质预设称号（普通 / 稀有 / 史诗 / 传说…，含活动限定）；境界榜 / 战力榜 / 财富榜
- **全局天象与季节**：全服每日天象影响修炼 / 游历 / 战斗 / 掉落倍率；季节作为"天道时序"按真实月份（calendar）或小时循环（cycle）更替并广播

### 平台能力

- **多端消息通道（插件化适配器）**：OneBot QQ、QQ 官方机器人、Minecraft 内嵌服务器 / 玩家直连通道，实例配置在 `config/adapters.json`，可运行时热启停
- **指令即路由**：一套 `Command` 同时注册为 QQ 指令与 REST 路由（`addRoute` / `registerSub`）
- **指令全中文支持**：QQ 指令支持中文名与英文别名，物品可按中文名 / key 混用
- **Web 管理控制台**：修仙风主题面板（概览 / 日志 / 玩家 / 用户角色 / 数据发放 / 插件 / 适配器 / OAuth2 配置等），内置**安装向导**（`/install`）
- **玩家数据文件化**：一用户一 JSON（`data/players/<playerId>.json`），变更自动落盘，后台一键导出 / 还原
- **RBAC 权限 + OAuth2**：角色权限码分层授权；独立 OAuth2 令牌端点（client_credentials）与受保护资源示例
- **内置玩法模块化**：24 个稳定玩法系统 + 2 个实验性插件 + 4 个消息通道，全部以"内置插件"形式运行，开关集中在 `config/plugins.yml`（`plugins.*`），可在 WebUI「插件管理」页运行时独立启停
- **实验性内容插件化**：宗门战、能量转化（等价交换）等实验性玩法单独成组（`plugins.experimental_*`），与稳定玩法开关分离，可随时整体关闭
- **插件系统**：第三方 jar 扩展命令 / 物品 / 事件 / 秘境 / 权限 / REST / Web UI / WebSocket（详见 [插件开发文档](README_PLUGIN.md)）
- **i18n**：JSON 语言文件（当前 zh_cn），物品 / 秘境 / 事件 / 系统消息可整体翻译
- **低内存友好**：小内存设备可用 `start-low-memory.bat` / `XIUXIAN_MEM` 控制 JVM 内存

---

## 🧰 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 23（Maven 构建） |
| HTTP / REST | Jersey 4.0.2 + Grizzly 4.0.2 |
| WebSocket | Grizzly WebSocket |
| 数据库 | SQLite（默认，零依赖） / MySQL，HikariCP 连接池，方言自动适配 |
| 认证 | JWT（jjwt 0.11.5）+ BCrypt（jbcrypt 0.4），双令牌刷新 |
| JSON / 配置 | Gson 2.14 / SnakeYAML 2.4 |
| 邮件 | Jakarta Mail（Angus Mail 2.0.3，注册验证码） |
| 测试 | JUnit 5 + Mockito |

---

## 🚀 快速开始

### 环境要求

- **JDK 23+**（[Adoptium](https://adoptium.net/)）
- **Maven 3.8+**（仅构建需要）
- 数据库：默认 **SQLite 零配置**；如需 MySQL，请准备 MySQL 8+ 并创建数据库 `xiuxian`

### 方式一：一键安装（推荐）

```bash
# Linux / macOS
./install.sh

# Windows
install.bat
```

脚本自动检测 Java → 缺失 jar 时自动 `mvn package` → 启动服务器并打印地址。
支持参数：`--nogui`（无交互后台运行）、`--build`（强制重新构建）。

### 方式二：手动构建运行

```bash
git clone https://github.com/oooohhhhhhhhhh/xiuxian-server-alpha.git
cd xiuxian-server-alpha

# 用项目自带的 Maven wrapper，无需本机预装 Maven
./mvnw clean package -DskipTests          # Windows: mvnw.cmd clean package -DskipTests
java -jar target/main-0.1.0.jar
```

> 建议从 JAR 所在目录启动，确保 `config/`、`data/`、`log/`、`plugins/` 等相对路径正确。

### 方式三：Docker

```bash
docker compose up -d
```

数据（`config/`、`data/`、`log/`、`plugins/`、`xiuxian.db`）持久化在命名卷里。

⚠️ 容器里完成安装向导需要先打开「远程安装」开关：宿主机浏览器访问映射端口时，容器内看到的来源是 Docker 网关 IP 而不是回环地址，向导默认会返回 403。首次启动后执行：

```bash
docker compose exec xiuxian sh -c "printf '\ninstall:\n  allow_remote: true\n' >> /app/config/application.yml"
docker compose restart
```

然后访问 **http://127.0.0.1:8080/install** 完成安装。装好后 `config/.installed` 会关闭向导接口；如需更稳妥，安装完成后把 `allow_remote` 改回 `false` 再重启。

详见 [Dockerfile](Dockerfile) 与 [docker-compose.yml](docker-compose.yml)。

### 方式四：systemd（Linux 服务器）

见 [deploy/xiuxian-server.service](deploy/xiuxian-server.service)，文件顶部有完整安装步骤。
其中 `WorkingDirectory` 已按服务端的路径规则钉在 jar 同目录，避免数据落到意料之外的位置。

单元文件用的是 `Restart=always`：安装向导和 QQ `/重启` 触发的重启都是**干净退出**（exit 0），
靠 systemd 拉起；服务端检测到 `INVOCATION_ID`（systemd 注入的环境变量）时只退出、不自我 spawn
—— 否则新进程会被 cgroup 清理连带杀掉。若你自定义了 unit，请务必保留 `Restart=always`。

用面板（如 MCSM）托管且**没开**「自动重启」时，把 `restart.mode` 设为 `self-spawn`
（默认 `auto` 会按环境判断，面板托管通常走自我 spawn；面板开了自动重启则建议 `supervised`）。

### 首次启动：安装向导

首次启动（未配置 JWT 密钥）时，浏览器打开 **http://127.0.0.1:8080/install** 完成一步式安装：

1. 服务器端口
2. 数据库模式（SQLite 零配置 / MySQL）
3. 管理员账号密码
4. JWT 密钥（留空自动生成，重启后玩家需重新登录）

提交后服务器**自动重启**生效；安装成功会写入锁文件 `config/.installed`，`/install/configure` 随之永久关闭。若要重装，请调用 `POST /api/install/reset`（需当前管理员密码），或停止服务后手动删除 `config/.installed` 再启动 —— 仅清空 `jwt.secret` 已不再能重开安装向导（V7 起以锁文件为准）。

> ⚠️ 默认监听 `127.0.0.1`；改为公网绑定前请务必修改默认管理员凭据（admin/admin123）与 JWT 密钥。

#### 远程 / 局域网 / Docker 安装

安装向导的三个写操作接口（`/api/install/configure`、`/reset`、`/restart`）**默认只接受本机回环地址**（`127.0.0.1` / `::1` / `localhost`）的请求，其余来源返回 403：

> 安装向导仅允许从服务器本机访问。如需远程安装，请设置 install.allow_remote=true（务必确认网络边界安全）。

这是刻意的安全设计：未完成安装时还没有管理员密码，向导却能改写数据库连接串、管理员账号与 JWT 密钥；只允许本机访问可以彻底消除「服务器已暴露在网络上、但尚未安装」这个高危窗口。注意 `GET /api/install/status` 不受限制，所以「页面能打开、提交才被拦」是正常现象。

需要远程安装时，在 **jar 同目录**的 `config/application.yml` 中加（模板里已预留该段，默认 `false`）：

```yaml
install:
  allow_remote: true
```

重启后生效；安装成功后锁文件 `config/.installed` 会关闭 `/configure`，这个「开放窗口」随之消失。

典型场景：

- **同一台机器、但用局域网 IP 访问**（如 `http://192.168.1.10:8080/install`）：来源是局域网 IP，同样被拦 —— 改用 `127.0.0.1` 即可，不必打开开关；
- **Docker**：宿主机浏览器访问映射端口时，容器内看到的来源是 Docker 网关 IP 而非回环地址，**必须**打开本开关（见「方式三：Docker」）；
- **反向代理**：来源会变成代理地址，只有代理与服务器同机（走回环）才免开；代码不读取 `X-Forwarded-For`，跨机反代同样需要打开本开关。

### 启动参数

| 参数 | 说明 |
|------|------|
| `--nogui` | 无交互模式，按 Ctrl+C 停止 |
| `--demo` | 启动交互式演示客户端（服务就绪后自动退出） |
| `--plugin-make` | 交互式插件项目生成向导 |
| `--plugin-make-gui` | 图形化插件生成器 |

### 访问入口

| 服务 | 地址 |
|------|------|
| 安装向导（首次） | `http://127.0.0.1:8080/install` |
| REST API | `http://127.0.0.1:8080/api/` |
| WebSocket | `ws://127.0.0.1:8080` |
| Web 管理控制台 | `http://127.0.0.1:8080/admin/` |
| OneBot QQ 接入 | `ws://127.0.0.1:6700/onebot`（默认 `config/adapters.json`） |
| Minecraft MOTD / 通道 | `127.0.0.1:25565`（默认） |

> 端口、监听地址、数据库等均可通过运行配置修改，见下文「运行配置」。

### 升级与回滚

升级只需替换 JAR 后重启，**数据库结构变更会在启动时自动完成，无需手工执行 SQL**。

**升级前务必先备份**（表结构会被改动）：

```bash
# 1. 停服
# 2. 备份数据库与配置（按 config/application.yml 里实际的数据库类型选择）
cp xiuxian.db  xiuxian.db.bak-$(date +%F)      # SQLite
cp -r config   config.bak-$(date +%F)
```

**升级步骤**

```bash
# 3. 用新 JAR 覆盖旧 JAR（config/、data/、plugins/ 原地保留，不要删）
# 4. 启动
java -jar main-0.1.0.jar --nogui
# 5. 确认日志出现「所有数据库表创建完成」，且无迁移报错
```

**自动迁移做了什么**（全部幂等，每次启动都会执行一遍，重复执行无副作用）：

| 变更 | 说明 |
|------|------|
| `trade_listings` 新增 `expires_at` 列 | 既有行保持 `NULL`（= 不过期），随后由服务端回填真实到期时间 |
| 新增 `trade_price_history` 表 | 成交价格历史 |
| 新增 3 个索引 | `trade_price_history(item_key, sold_at)`、`trade_listings(status, expires_at)`、`trade_listings(seller_player_id, status)` |

> **首次启动的行为，请提前知悉**：服务端会按 `created_at + 72 小时` 为**历史挂单**回填到期时间。
> 因此**挂出已超过 72 小时的老挂单会在启动后立刻到期**，物品自动退回卖家背包。
> 这是预期行为（挂单时限对老数据同样生效），但会在启动后短时间内集中出现一批退回，
> 属正常现象；若希望老挂单保留更久，可临时调大 `market.listing_duration_hours` 后再启动。

**配置无需手工修改**：新增的 `market.*` 键会自动从 JAR 内置模板继承
（外部 `config/application.yml` 作为覆盖层，未写的键回落到内置默认值）。
若要显式调参，再往 `config/application.yml` 里补写即可：

```yaml
market:
  listing_duration_hours: 72               # 挂单有效时长（小时）
  price_history_keep_days: 30              # 成交价格历史保留天数
  price_history_cleanup_interval_hours: 6  # 历史清理任务执行间隔（小时）
```

**回滚**：直接换回旧 JAR 重启即可。新增的列、表、索引对旧版本**完全无害**
（旧版代码不读它们），因此回滚不需要还原数据库；只有当你确认要放弃新功能时，
才需要从备份恢复数据库。

**升级后建议核对**

- 日志无迁移/回滚异常，服务正常监听端口。
- 后台「坊市管理」页能打开并列出在售挂单（需要 `admin.economy` 权限，`ADMIN` / `SUPER_ADMIN` 默认具备）。
- 游戏内 `/坊市` 列表显示剩余时间，`/行情 <物品>` 能返回成交统计。
- 确认老挂单的集中退回符合预期。

---

## 📁 项目结构

```
src/main/java/
├── com/mtxgdn/                      # 服务端主程序
│   ├── Main.java                    # 入口：启动装配、扫描注册、生命周期
│   ├── server/                      # HTTP 服务装配（静态资源 / 停机钩子）
│   ├── rest/                        # REST 层：Auth / Game / Admin / OAuth / Install + 过滤器
│   ├── websocket/                   # WebSocket 游戏服务
│   ├── service/                     # 用户 / 验证码 / OAuth 服务
│   ├── db/                          # 数据库（SQLite/MySQL 方言、迁移、DAO）
│   ├── entity/                      # Player / User
│   ├── common/                      # 统一响应 / 错误码 / 指令路由框架（扫描注册）
│   ├── permission/                  # RBAC 权限（权限码 / 注解）
│   ├── game/                        # 玩法核心
│   │   ├── service/                 # 30 个玩法服务（修炼/境界/战斗/秘境/宗门/农场/洞府/阵法/地图…）
│   │   ├── item/                    # 物品框架（扫描注册 + 效果组件）
│   │   ├── explorationevent/        # 游历事件框架
│   │   ├── secretrealm/             # 秘境框架
│   │   ├── config/ · title/         # 境界配置加载 / 称号注册表
│   │   └── entity/                  # 玩法实体（作物/地块/怪物/宗门…）
│   ├── plugin/                      # 插件系统（Plugin/PluginContext/事件总线/Web UI）
│   │   └── builtin/                 # 25 个内置玩法模块 + 4 个消息通道模块（可启停）
│   ├── adapter/                     # 消息通道适配器框架（config/adapters.json）
│   ├── onebot/                      # OneBot QQ 接入（WebSocket/截图 + 分模块指令体系）
│   ├── qq/                          # QQ 官方机器人通道（webhook / 按钮）
│   ├── minecraft/                   # Minecraft MOTD + 内嵌服务器适配 + Bukkit 桥接插件
│   ├── playerdata/                  # 玩家数据文件化（JSON 落盘 / 导出还原）
│   ├── client/                      # 外部客户端库（REST + WebSocket）
│   ├── demo/                        # 命令行交互式演示客户端
│   └── util/                        # 工具（JWT/邮件/日志/翻译/限流/统计）
└── data/mtxgdn/                     # 游戏数据定义（启动时自动扫描注册）
    ├── item/                        # 115 种物品
    ├── secretrealm/                 # 10 个秘境
    ├── explorationevent/            # 13 种游历事件
    └── lang/zh_cn.json              # 简体中文翻译

src/main/resources/
├── application.yml                  # 打包默认配置（运行时释放到 config/）
├── plugins.yml                      # 插件开关（内置玩法模块 / 实验性插件 / 消息通道）
├── game/realm_config.json           # 境界数据（11 大境界 / 29 个阶段 + 修炼速率）
├── webadmin/                        # Web 管理控制台前端
├── install/                         # 安装向导页面
└── config/                          # 黑名单 / 群组配置默认值
```

---

## ⚙️ 运行配置（config/ 目录）

首次启动时在 JAR 同级的 `config/` 目录释放/生成配置文件（**优先于打包内默认值**）：

| 文件 | 说明 |
|------|------|
| `config/application.yml` | 主配置：端口、数据库、JWT、管理员、SMTP、季节模式等 |
| `config/plugins.yml` | **插件开关**：内置玩法模块、实验性插件、消息通道的启停（`plugins.<插件名>: true/false`） |
| `config/adapters.json` | 消息通道实例配置（OneBot / QQ 官方 / Minecraft…），支持热重载 |
| `config/blacklist.yml` | QQ 黑名单（支持自动禁言策略） |
| `config/onebot_group_config.yml` | OneBot 群组级自动禁言配置 |
| `config/realm_config.json` | 境界表（可由游戏配置加载器按需释放） |

### 插件开关（config/plugins.yml）

内置插件（玩法模块 / 实验性插件 / 消息通道）的启停集中在 `config/plugins.yml`，根节点为 `plugins`：

```yaml
plugins:
  farm: true                    # 稳定玩法模块（缺省 true）
  experimental_sect_war: false  # 实验性插件：宗门战（缺省 false）
  experimental_energy_exchange: true  # 实验性插件：能量转化/等价交换（缺省 true）
  onebot: true                  # 消息通道类型级总开关
```

- **取值优先级**：`config/plugins.yml` → jar 内模板默认值 → 插件自身默认值；
  旧版本写在 `application.yml` 的 `built_in_plugins.*` 与 `config/experimental.yml` 的
  `experimental.*` 作为**只读兜底**保留，升级后首次启动会自动迁移到 `plugins.yml`（旧文件可自行清理）。
- **运行时启停**：WebUI「插件管理」页的启用/停用会立即写回 `config/plugins.yml`（保留注释），重启后依然生效。
- **门控范围**：插件停用后其指令、REST 接口、WebSocket 动作、后台定时任务一并失效（`ModuleGateFilter` / 命令注册表 / 服务层门控）。

其他运行时目录：

| 目录 | 说明 |
|------|------|
| `data/players/` | 玩家数据文件（一用户一 JSON） |
| `plugins/` | 第三方插件 jar（自动加载） |
| `log/` | 运行日志 |
| `mc_server/` | Minecraft 内嵌服务器工作目录（启用时） |

---

## 🎮 接入与开发

### 消息通道（适配器）

服务器通过 `config/adapters.json` 管理消息通道实例，默认启用 OneBot 通道：

| 通道 | 说明 |
|------|------|
| **OneBot QQ** | `ws_server` 模式（默认 6700 端口），支持 Lagrange / LLOneBot 等实现连接；聊天中 `/` 开头的全部指令（中英文别名），含私聊注册绑定、群聊玩法、自动禁言黑名单 |
| **QQ 官方机器人** | 官方 Webhook + 按钮交互通道（需在 `adapters.json` 添加实例并配置） |
| **Minecraft** | 内嵌服务器进程管理 / 玩家直连通道 / MOTD 预览；`build-mc-plugin.bat` 可编译 Bukkit 桥接插件（`XiuxianBridgePlugin`，指令前缀 `/xiuxian`） |

> 每个通道类型对应一个内置插件（`plugins.<type>`），可在 WebUI「插件管理」页或 `config/plugins.yml` 独立启停；
> 类型级开关关闭时该类型下全部实例停止运行，实例级 `enabled` 仍由 `config/adapters.json` 控制。

### 外部客户端

- **REST + WebSocket 客户端库**：`com.mtxgdn.client`（`ApiClient` / `GameApi` / `AuthApi` / `GameWebSocketClient` / `ApiConfig`）
- **交互式演示客户端**：`java -jar main-*.jar --demo`

### 二次开发

- **玩法数据**：在 `data/mtxgdn/` 下继承 `Item` / `ExplorationEvent` / `SecretRealm` 即自动注册；`zh_cn.json` 补翻译即可显示中文
- **指令**：在 `com.mtxgdn.onebot.command` 下新建 `XxxCommand extends Command`，构造函数 `addRoute` / `registerSub` 自动成为 QQ 指令 + REST 路由
- **插件**：参见 [README_PLUGIN.md](README_PLUGIN.md)（生命周期、PluginContext API、事件总线、Web UI 注册）与 [examples/sample-plugin](examples/sample-plugin) 示例工程
- **测试**：`./mvnw test`（Windows 用 `mvnw.cmd test`，或直接跑 `run_tests.sh` / `run_tests.bat`）

---

## 📚 相关文档

- **[在线文档站（Wiki）](https://oooohhhhhhhhhh.github.io/xiuxian-wiki/)** — 安装运维 / 玩家玩法 / 开发者文档 / 数据参考，共 49 页（[源码仓库](https://github.com/oooohhhhhhhhhh/xiuxian-wiki)）
- [插件开发文档](README_PLUGIN.md) — 插件生命周期 / PluginContext 完整 API / 事件总线 / Web UI
- [examples/sample-plugin](examples/sample-plugin) — 可编译的插件示例工程
- [CHANGELOG.md](CHANGELOG.md) — 版本变更记录
- [docs/archive](docs/archive/README.md) — 归档的历史审计文档（代码审查 / 安全审查 / 修复总结，仅供参考）

## 许可

[MIT License](LICENSE) © 2026 oooohhhhhhhhhh
