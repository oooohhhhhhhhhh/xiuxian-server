# 更新日志

本文件记录 xiuxian-server-alpha 各版本的变化。

- 版本号以 `pom.xml` 的 `<version>` 为准，当前为 `0.1.0`。
- 本仓库目前**没有 git tag**，版本边界以提交信息与 `pom.xml` 为准。
- 分类约定：新增 / 变更 / 修复 / 移除 / 安全 / 测试 / 工程与仓库。
- 格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

> **关于版本号的两条版本线（重要）**
>
> 本项目当前的版本号是 `0.1.0`。它**小于**下面记录过的 `V1.3.0` ~ `V1.4.1-beta2`，
> 这不是笔误，也不是回滚：`0.1.0` 起是**新的版本线**，
> 而 `V1.x` 是**前身版本号**（沿用了早期开发阶段的历史编号）。
> 两者的先后顺序应理解为「`V1.x` 在前，`0.1.0` 在后」。
>
> 之所以不把旧编号一并改写，是因为那些条目对应真实发生过的提交与发布，
> 改写等同伪造记录；此处只作说明，历史段落一字未动。
>
> 版本号的唯一来源是 `pom.xml` 的 `<version>`，代码里通过
> `com.mtxgdn.util.BuildInfo` 读取（禁止再硬编码），详见「工程与仓库」一节。

## 版本索引

| 版本 | 说明 |
| --- | --- |
| 0.1.0 | **新版本线的起点**（承自 `V1.4.1-beta2` 之后的所有改动）。版本号改为单一来源 `BuildInfo`；农田生长与 Buff 过期改为惰性结算，移除两处每秒全表扫描 |
| 未发布（V1.x 线） | `V1.4.1-beta2` 之后的改动（坊市到期与价格历史、闭关修复、鉴权加固、地图玩法接线、地图危险度/探索/传送、野外无限分片世界（地形随种子生成、分片以文件存储）、Minecraft 玩家适配器 26.2 协议修复、工程修复） |
| V1.4.1-beta2 | 插件开关迁移、玩法接入补齐、坊市与 QQ 适配器修复 |
| V1.4.1-alpha3 | 宗门升级 + 宗主转让 + 宗门战 |
| V1.4.1-alpha2 | 黑名单系统 + 自动禁言 |
| V1.4.1-alpha1 | 插件生成器 + GUI + 事件系统 |
| V1.4.0 | 插件系统发布 |
| V1.3.0 | 战斗系统与经济系统重构 |

---

## [未发布]

`V1.4.1-beta2` 之后、尚未发版的改动。起始提交为 `1821f2a`、`d72d77b`，其后各次改动也并入本节。
本节内容**将随 `0.1.0` 一并发布**（`0.1.0` 是新版本线的起点，即「`V1.4.1-beta2` 之后的一切」）。

### 新增

- **坊市挂单到期回收**：`TradeService` 内置单守护调度器 `trade-market-sweeper`，
  到期扫描周期 60 秒（首轮延迟 20 秒），到点自动下架并把物品退回卖家。
- **成交价格历史**：新增 `trade_price_history` 表与 `/行情` 指令
  （`MarketPriceCommand`），可按物品查询近期成交价；清理周期由
  `market.price_history_cleanup_interval_hours` 控制（默认 6 小时，
  保留 `market.price_history_keep_days` 天，默认 30 天）。
- **坊市索引**：新增 `idx_trade_listings_status_expires`、
  `idx_trade_listings_seller_status`、`idx_trade_price_item_time`。
- **管理后台坊市页**：`webadmin` 新增 market 模块与侧边栏入口。
- **容器化与系统服务**：新增 `Dockerfile`（多阶段构建，用项目自带 wrapper 编译，
  无需预装 Maven）、`docker-compose.yml`、`.dockerignore`、
  `docker/entrypoint.sh` 与 `deploy/xiuxian-server.service`。
  容器内让 jar 目录与工作目录都等于 `/app`，使服务端两套路径解析基准一致
  （参见 `Main.reportDeploymentPaths()`）；`.dockerignore` 显式排除根目录
  `config/`，避免把 `jwt.secret` 等密钥烤进镜像层。
- **README 补充在线文档站入口**，并把构建指令改为使用 Maven wrapper。
- **地图数据全面入库 + 热重载**：新增 `game/config/MapDefaults`（出厂地点、连接、
  事件偏置的唯一数据源，替代原先散在 `MapService` 里的 `Object[]` 常量）；
  `map_locations` 增加 `cultivation_bonus` / `exploration_bonus` 两列；
  新增 `map_location_event_bias` 表（地界 → 探索事件权重倍率）。
  `MapService.reload()` 支持后台改库后热刷新，通用表管理改动地图表会自动触发。
- **管理台地图页**：新增 `GET /admin/map/locations`（地点总览：境界名、灵气/地界倍率、
  安全区、邻居与路程、事件偏置、区域分组）与 `POST /admin/map/locations/{id}`
  （改可调参数，保存后热重载）；`webadmin` 新增地图管理页，含地点表格、区域筛选、
  搜索、世界脉络树（可换根展开）与编辑弹窗。该页挂在 `/admin/*` 下，
  不受「地图」模块门控影响，模块停用后管理员仍能查看与调整。
- **地图危险度分级**：`map_locations` 新增 `danger_level`（0 安全 / 1 低危 / 2 中危 /
  3 高危 / 4 极危），15 个出厂地点逐一标注（青云村、苍梧镇、太虚城等 4 处安全区为 0）。
  `/地图`、`/地图 list` 与后台地图页都会显示危险度。必须维持的不变式是
  `is_safe_zone = 1 ⇒ danger_level = 0`（`MapService.loadLocations()` 发现违反时
  只告警、不静默改写数据）。
- **险地 PK 掠夺**：危险度 ≥3 的地点私斗，胜者从败者身上夺得 `5% × 危险度` 的灵石
  （3 档 15%、4 档 20%），战斗日志写明「☠ 高危之地，X 自 Y 身上夺得 N 灵石」。
  掠夺走 `removeSpiritStones` 的布尔返回值，并发 PK 不会凭空造出灵石。
  **注意 `danger_level` 不参与 PVP 开关** —— 唯一的安全区判定仍是 `is_safe_zone`，
  危险度只决定"掠夺多少"和野外资源档位。
- **探索进度与首达奖励**：新增 `player_map_exploration` 表记录首达
  （`UNIQUE(player_id, location_id)`，以 `INSERT OR IGNORE` / `INSERT IGNORE` 写入，
  不用"先查后插"，避免并发下重复发奖）。首次抵达发放灵石与经验
  （基础值 + 每档危险度增量），`/地图 list` 显示 `🧭 探索度 x/y（z%）` 与
  `✅`/`❔` 迷雾标记，走遍全部已知世界地点时额外发放全图探索奖励。
- **传送点解锁**：只有**亲自到过**的地点才能直传（`/地图 传送 <地名>`，`传送` 是
  `/地图` 的子指令，没有新增顶层指令名）。费用 = 基础价 + 每档危险度增量，
  野外目的地另有附加系数；未解锁、境界不足、灵石不足都会被拒且不改变位置。
  扣费发生在 CAS 之前、CAS 失败会退款，避免"扣了钱没传成"。
- **野外：无限分片世界（地形随种子生成、可无限扩张，分片以文件存储）**：
  新增 `src/main/resources/game/wilderness_map.json`
  （首启释放到 `config/game/wilderness_map.json`）与 `game/config/WildernessMapConfig`、
  `game/map/WildernessTerrain`、`game/map/WildernessChunk`、`game/map/WildernessChunkStore`。
  已有区域之外的部分统称**野外**，是一片按 4×4 节点一片组织的无尽荒域：
  - **地形不存储**：节点名、描述、危险度、连通关系全是
    `(worldSeed, chunkX, chunkY)` 的纯函数（两个低频 value-noise 场定地形，
    危险度 = max(地形危险度, 距中土片数/每档))，越远越险，因此
    `map_locations` / `map_connections` 里**没有任何一行属于野外**，
    数据库体积不随世界扩张而增长。
  - **只有可变状态落盘**：`data/wilderness/<cx>>6>/<cy>>6>/<cx>_<cy>.json`
    一个分片一个文件，存"谁探索过、资源剩余多少、何时补货"。分桶目录避免单目录塞几十万文件，
    写入走 `.tmp` + `ATOMIC_MOVE`。文件里带 `seedHash`，管理员改了 `worldSeed` 后
    陈旧存档会被识别并丢弃，不会挂到另一片地形上。
  - **分片懒创建、可无限扩大**：没人去过的地方磁盘上完全不存在；
    硬上限 `maxChunksFromOrigin`（默认 256 片 ≈ 1024 格）只作护栏。
  - **已探索的永不消失**：首次抵达即把分片置 `pinned`，从此**绝不回收、也没有管理员后门**；
    只有"从未被探索且长期无人涉足"的分片（默认闲置 1440 分钟）会被回收，
    重新走到时地形由种子算回来，逐字一致。
  - **资源是原地补货**：到点只把 `remaining` 补回 `total`，不换地形、不动探索记录 ——
    这就修掉了上一版"整批刷新会把玩家走过的地换掉"的问题。
  - 野外节点 id 由坐标推导（`ID_BASE + chunkKey × SLOT_STRIDE + slot`），
    可反解且**同分片内连续**（于是"这片有没有人"可以走一个范围查询）。
    `SLOT_STRIDE` 与 `chunkSize` 解耦，改分片边长不会让全部 id 平移。
  - 新增 `/野外`（显示所在分片的地形、距中土片数、固化状态、补货倒计时与可去的邻居），
    导航改为 `/地图 go <id>`（野外节点名是随机生成的，无法按名反查）。
- **管理台野外分片视图**：新增 `GET /admin/map/wilderness`（分片总览：分片数/已固化/已探索节点/资源条目）
  与 `POST /admin/map/wilderness/restock`（原地补货），均需 `admin.database.access`。
  **刻意不提供删除分片与"重刷野外"的接口** —— 已探索的内容是玩家的永久记录。
  相应地 `POST /admin/map/wilderness/refresh` 已移除。
- **管理台地图管理补齐**：新增 `POST /admin/map/locations`（新建地点，id 可留空自动分配）、
  `DELETE /admin/map/locations/{id}`（删除并把在场玩家挪回出生地）、
  `POST`/`DELETE /admin/map/connections`（连接编辑，默认双向，`oneWay` 可选单向）、
  `POST`/`DELETE /admin/map/event-bias`（事件权重偏置编辑）、
  `POST /admin/map/wilderness/refresh`（立即重刷野外），均需 `admin.database.access`。
  地图页新增概览统计（已知世界地点/连接/区域/安全区 + 野外节点 + 险地）、危险度徽标、
  🌿 野外标记、地点增删与连接/偏置编辑器，以及「➕ 新建地点」「🌿 刷新野外」按钮。
  地点改名会先校验重名 —— 地名是 `/地图 go <地名>` 唯一的解析键。
- **农田惰性结算的支撑改动**：`farm_plots` 新增 `settled_time`（BIGINT，上次结算时刻，
  老库由 `DatabaseMigration` 补列）；`player_buffs` 新增索引 `idx_player_buffs_expire`
  （过期兜底按 `expire_time` 扫描，代价只与已过期行数相关）；
  新增配置项 `buff.expire_sweep_seconds`（过期增益兜底回收间隔，默认 60 秒）。
- **宗门：宗主缺位继位 + 竞争期（解开「宗主弃坑 → 宗门永久死锁」）**：
  改造前宗主**既不能退位也不能退宗** —— `SectService.leaveSect()` 直接拒绝，
  于是一旦宗主不玩了，这个宗门就永远没人能接手，成员只能陪着烂掉。
  现在补齐了完整的权力交接链路：
  - **主动退位**：新增 `/宗门 退位`（别名 `stepdown` / `卸任`）。宗主卸任后降为**副宗主**
    并留在宗门，系统随即按贡献选出继任者；退位完成后原宗主即可正常 `/宗门 退出`。
    宗门仅剩宗主一人时拒绝退位，并提示改用 `/宗门 解散`（无人可继位时换人没有意义）。
  - **按贡献继位**：继任者排序为「贡献 → 境界序位 → playerId」。
    这里**刻意不用 `players.level`** —— 本项目里该列并不随修炼推进（只有建档与后台改库会写），
    拿它排序会得到一批并列的 1 级。改用「境界序位 = `realm * 3 + subRealm`」。
    **现任宗主不是候选人**：否则退位等于没退，会被自己重新"选"回来。
  - **竞争期**：继任者与最强竞争者（余下成员按「职位 → 境界 → 贡献」取第一名）的
    境界序位差 **小于 `sect.contest_level_gap`（默认 6 ≈ 两个大境界）** 时，
    开启 `sect.contest_days`（默认 7 天）的竞争期，该竞争者成为挑战者。
    差距过大时挑战毫无意义，直接让继任者坐稳，省掉一次无意义的弹窗骚扰。
  - **两段式争夺战**：挑战者 `/宗门 挑战` 发起（置 `contest_requested`）→ 宗主
    `/宗门 挑战 接受` 应战。**未发起不能应战**；发起但宗主不应战，则竞争期到期后
    宗主保住位置（`contest_*` 只清字段、不动 `leader_player_id`）。
    应战走**三局两胜**，且**双方战力按境界序位比例归一化**
    （强者被压低到弱者水位，下限 `MIN_CONTEST_RATIO = 0.5`），
    让胜负考校灵根 / 装备 / 属性分配这些构筑，而不是单纯比谁境界高。
    归一化只作用于内存副本，**绝不回写玩家真实属性**；胜者任宗主，败者降为副宗主。
  - **不活跃宗主自动换人**：新增 `players.last_active_time`（BIGINT，毫秒）。
    它**不是** `last_offline_time` 的替代品 —— 后者只有 WebSocket 断线路径写入
    （见 `GameWebSocketApp`），QQ / REST / Minecraft 玩家永远是 0，无法作为弃坑依据。
    `PlayerService` 在读取玩家时按**每小时一次**节流刷新它（写失败直接吞掉，
    绝不影响任何玩法），`updatePlayer()` 落库时顺带写入（那本身就是一次活跃信号）。
    守护调度器 `sect-succession-sweeper`（首轮延迟 45 秒，间隔
    `sect.succession_sweep_seconds`，默认 600 秒）把
    `last_active_time` 超过 `sect.leader_absent_days`（默认 14 天）的宗主标记为缺位并触发继位。
  - **升级安全**：`last_active_time = 0` 表示「尚未观测到活跃」，
    弃坑判定**只认 `> 0` 的行**。老库升级后该列全为 0，若把 0 当成纪元起点，
    升级瞬间会把全服宗主一次性判定成弃坑、批量换掉所有宗门的宗主 ——
    判定从各自下一次登录开始计时，不存在这个窗口。
  - **并发与幂等**：所有落库都是**条件更新** ——
    继位用 `WHERE id = ? AND leader_absent_since = <读到的值>`，
    应战用 `WHERE ... AND contest_requested = 1 AND contest_challenger_id = ? AND leader_player_id = ?`，
    受影响行数为 0 即判定"状态已被其他请求推进"并放弃本次操作。
    因此指令与扫描任务同时触发时只有一个生效，不会重复换人或重复结算。
  - **数据库**：`sects` 新增 `leader_absent_since` / `contest_until` /
    `contest_challenger_id` / `contest_requested` 四列，`players` 新增 `last_active_time`，
    老库均由 `DatabaseMigration` 补列（`sects` 四列带 DEFAULT 0，即"宗主在任、无竞争期"
    的出厂值；`players.last_active_time` 刻意回填 0 而非当前时间，理由见上一条）。
  - **新增配置**：`sect.leader_absent_days`（默认 14，0 = 关闭自动判定）、
    `sect.contest_days`（默认 7）、`sect.contest_level_gap`（默认 6）、
    `sect.succession_sweep_seconds`（默认 600，最小 30）。
  - `/宗门 info` 会显示「宗主缺位中」或「宗主竞争期至 …」，
    `/宗门` 概览会按身份给出 `/宗门 挑战` 或 `/宗门 挑战 接受` 的入口提示。
- **世界 Boss：全服共享血量 + 伤害分红 + 超发熔断**：
  此前项目里已经有两套 Boss —— `Monster.createBoss`（一次性 PVE 对手，血量只属于你这一场）
  与 `CombatService.raidBossFight`（团队 Raid，三形态推进，血量属于这一队），
  但**没有「全服共享同一条血」的世界 Boss**。本次补上这一层：
  - **共享血量直接落库**：`world_bosses.hp` 就是全服唯一的那条血。
    挑战时把当前血量交给 `pveFight` 当对手血量，战后在事务里
    `SELECT ... FOR UPDATE` 锁行再扣，因此并发挑战既不丢更新，
    也不会出现两个人都以为自己补了最后一刀。**没有任何内存态** ——
    服务重启后血条原样还在。
  - **无定时器**：整池到期（默认 4 小时）走读取时的惰性重建，
    与 `FarmService` / `BuffService` 的惰性结算同一路数，不新增调度线程。
  - **奖励按伤害分红，不是击杀独占**：每次挑战发放
    `灵石 = stone_budget × 本次伤害 / max_hp`，修为同理；
    补最后一刀的人额外拿 `exp_budget × kill_bonus_ratio`（默认 20%）。
    因为全服累计伤害不会超过 `max_hp`，**总发放天然不超过预算** ——
    这是结构性保证，不依赖事后核对账本。
  - **超发熔断**：单次奖励超过 `world_boss.max_single_reward`（默认 100 万）时，
    改发 `阈值 / (5 + rand(6))`。灵石与修为**都**过这一道闸。
    它不是日常路径（分红已被预算限死），防的是"配置里多写一个零"
    被一次击杀放大成经济崩溃；触发时战报会明示「已按超发熔断折算」。
  - **挑战冷却**：本项目**没有体力系统**，若不限次，一个高境界玩家可以连着把
    Boss 打死，其他人连参与的份都没有。因此加了每人每只 Boss 的冷却
    （`world_boss.challenge_cooldown_seconds`，默认 300 秒），记在伤害台账的
    `last_hit_at` 上，并用**条件更新抢占** —— 连点两下的并发请求
    只会有一个真正进入战斗。
  - **境界门槛**：玩家与 Boss 的「境界序位差」达到
    `world_boss.max_stage_gap`（默认 9 ≈ 三个大境界）即不可挑战。
    境界序位 = `realm * 3 + subRealm`（新增 `Player.getRealmStage()`，
    `SectService` 的同类逻辑一并改为复用它），**不用 `level`** ——
    本项目中该列不随修炼推进。
  - **Boss 生成**：名字/描述/攻防速复用 `Monster.createBossForRealm`（同一套本地化文案），
    血量按 `world_boss.hp_multiplier`（默认 12）放大，境界上限取全服最高境界（至少 3），
    三只按序号切成低/中/高三档区间。血量刻意夹到 `Integer.MAX_VALUE`：
    `Monster.getHp()` 是 `int`，库里的血条若超过它，战斗模拟就会和显示对不上。
  - **新增指令 `/世界boss`**（别名 `worldboss`）：列表（血条 / 预算 / 重建时间 /
    我的累计伤害与冷却）、`挑战 <序号|名称>`、`排行 [序号]`、
    `刷新`（管理员，`admin.database.access`）。
  - **新增 REST**：`GET /worldboss/list`、`POST /worldboss/challenge`、
    `GET /admin/world-boss`、`POST /admin/world-boss/refresh`。
  - **数据库**：新增 `world_bosses`（`UNIQUE (scope, slot)` 是并发重建的护栏）
    与 `world_boss_damage`（`UNIQUE (boss_id, player_id)`）两张表 +
    索引 `idx_world_bosses_scope_expires`。两张表都是 `CREATE TABLE IF NOT EXISTS`，
    老库启动时自动建好，不需要迁移。
  - **新增配置段** `world_boss.*`：`scope`、`boss_count`、`respawn_hours`、
    `hp_multiplier`、`max_stage_gap`、`challenge_cooldown_seconds`、
    `max_single_reward`、`kill_bonus_ratio`。
  - **新增内置插件模块 `world_boss`**（默认启用，`plugins.yml` 里可单独停用），
    内置模块由 24 个增至 25 个。

### 修复

- **systemd 下重启后服务直接停摆**：安装向导 / QQ `/重启` 触发的重启走的是
  `System.exit(0)`（干净退出），而单元文件写的是 `Restart=on-failure`（只认非零退出码
  与信号）→ systemd 视为正常停止，**不会拉起**；更糟的是自我 spawn 出来的新进程还在
  同一个 cgroup 里，主进程退出时会被默认的 `KillMode=control-group` 一并 SIGTERM 掉。
  现在服务端按环境分流：检测到 `INVOCATION_ID`（systemd）/ `/.dockerenv` /
  `container` 时**只退出**，由托管方拉起（单元文件改为 `Restart=always` 并加重启风暴
  保护）；裸跑（`java -jar` / `start.sh` / `install.bat`）仍自我 spawn。
  新增配置项 `restart.mode`（`auto` / `supervised` / `self-spawn`）可强制覆盖自动判断
  —— 面板托管（MCSM 等）且未开自动重启时应显式设 `self-spawn`。
  同时把 `RestartCommand`（QQ `/重启`）合并到同一条 `Main.restartServer()` 路径，
  并让自我 spawn 保留 `-Xmx` 等 JVM 参数（旧实现重启后堆大小会掉回默认值）。
- **重启的"就绪探测"其实在探测自己**：旧实现 spawn 后轮询
  `127.0.0.1:<port>/api/game/status` 判断新进程是否就绪，但端口此时还握在**父进程**手里，
  请求命中的是父进程自己 —— 实测 spawn 后 3.9 秒就"就绪"退出，而新进程真正 bind 是
  37 秒之后，中间一直是空的。现在父进程 spawn 完立即退出释放端口，新进程在 bind 前
  用 `waitForPortRelease` 最多等 15 秒端口释放（Windows 上父进程退出慢时不会再
  `Address already in use` 直接崩）。

- **安装向导「仅限本机」把本机也拒了（向导全新实例完全不可用）**：
  `InstallResource.isLocalRequest` 用
  `ContainerRequestContext.getProperty("org.glassfish.grizzly.http.server.Request")`
  取来源地址，但在 Jersey + Grizzly 这套容器里该属性**根本不存在**
  （实测 `ContainerRequest` 的属性表里只有 `TracingLogger`），`instanceof` 永远不成立，
  于是连 `127.0.0.1` 发来的请求也一律 403 —— 只有手工打开 `install.allow_remote`
  才能装完。现改为 `@Context` 注入 `org.glassfish.grizzly.http.server.Request`
  （由 jersey-container-grizzly2-http 的 `GrizzlyBinder` 以 RequestScoped 绑定）
  取 `getRemoteAddr()`，属性表查询仅作兜底。实测：修前 loopback `configure` 403，
  修后 200；局域网 IP 调 `/restart` 仍 403（守卫本身没被削弱）。
- **登录 / 邮箱验证码限流退化成「全服一个桶」**：`Auth.remoteAddress()` 是同一个根因
  （同样取不到 Grizzly Request），永远回退成 `"global"`，于是 `login_ip:` 与
  `send_code_ip:` 两个桶实际是全服共享 —— 20 次/60 秒是所有玩家共用的额度，
  任意一人撞库就能把全站登录打到 429。现改为注入 `Request` 后按 IP 分桶。
  实测：loopback 连续 21 次登录第 21 次 429，紧接着同一时刻从局域网 IP 登录仍 401。

- **老库升级后服务直接起不来**（`SQLITE_ERROR: no such column: expires_at`）：
  `initTable()` 里的 `CREATE TABLE IF NOT EXISTS` 对老库是**空操作**，真正补列的是随后的
  `executeMigrations()`；但坊市索引的创建却排在迁移**之前**，于是任何"升级上来的库"
  都会在启动时抛这个异常 —— 表建好了、服务却起不来。
  现改为**先迁移、后建索引**（`DatabaseSchema` 里唯一引用迁移列的索引就是
  `idx_trade_listings_status_expires`），并且索引创建失败只告警、不再中断启动：
  索引只是性能优化，不该成为"服务器起不来"的原因。
  已用真实老库副本（`testbot/xiuxian.db`，缺 `expires_at`）验证升级后正常启动、
  数据行数不变；回归测试见 `db/LegacySchemaUpgradeTest`。
- **`/地图 list` 与 `/地图 go` 是死代码**：`MapCommand` 覆写了 `execute()` 却没有
  在带参数时调用 `super.execute()`，子命令分发从未发生 —— 输入 `/地图 list` 只会打印
  当前位置，`handleList` / `handleGo` 完全不可达。这也正是那段硬编码 ASCII 全图能
  烂掉多年没人发现的原因（没人看得见）。现按项目既有写法（`BankCommand` 等 5 个命令）
  在有参数时交给基类分发。
- **世界脉络是硬编码 ASCII 图**：只画到 (10)问道峰，东海龙宫、万妖谷、灵药谷、
  天机阁、白骨岭 5 个地点与 8 条连接从不在图上，而且地图数据改了图也不会变。
  现按真实邻接表实时生成（以当前所在地为根），并单独列出与主干不连通的地点。
- **区域标题重复**：地点 id 与区域并不连续（南荒是 8、12；中原是 4~6、13~14；
  西域是 7、15），原先"区域名一变就打一次标题"会让同一区域出现多个标题。
  现按区域归并后输出。
- **路程秒数两套口径**：校验冷却用 `Math.max(5, ...)`，而展示给玩家的是连接配置原值，
  连接被配成 1~4 秒时会出现"提示路程约 3 秒、实际必须等 5 秒"。现统一走
  `effectiveTravelSeconds()`，并补测试锁定"展示值 == 实际冷却值"。
- **冷却文案与实际不符**：玩家已经到达目的地，提示却是"你正在赶路途中（还需要 N 秒
  到达）"。现改为"你刚赶完一段路，还需 N 秒才能再次动身"。
- **Minecraft 适配器不校验权限**：覆写了 `execute()` 的指令（如 `/地图`）不会走
  `Command` 基类的权限检查，而 Minecraft 分发处也没有兜底，构造时声明的权限形同虚设
  （OneBot 与 QQ 官方适配器都有这道校验）。同时补上"模块已停用"的提示，
  此前是静默无响应。
- **地图境界门槛显示错误**：`/地图 list` 把境界 id 当层数拼，问道峰（需大乘期）
  显示成"炼气+7"。现统一走 `MapService.getRealmName()`。
- **移动不是原子的**：`travel` 原先"先读后写"，并发请求会双双通过校验、
  各自移动一次并各自回复成功。注意 `SELECT ... FOR UPDATE` 在本项目的 SQLite
  方言下被降级为空串（**无锁**），所以改为条件更新（CAS）：只有位置与冷却戳
  仍是校验时读到的旧值才落库，否则判定为竞争失败。
- **位置失效导致玩家卡死**：`current_location_id` 指向已删除地点时，展示层静默
  回落到出生地、移动层却报"当前位置异常"，玩家看得见地图却走不动路。现统一回落
  并记 warn。CAS 的期望值用**库里的原值**而非回落值，否则失效位置上永远匹配不上。
- **传送符忽略目的地**：`TeleportEffect.to(String)` 直接把参数丢掉，无论配置成
  什么都传到太虚城。现按名字解析（支持"主城"等别名，无法解析时回落太虚城）。
- **后台改地图表不生效**：改完 `map_locations` 必须重启进程才生效，管理员改完
  看不到变化会以为改错了。
- **闭关脏数据把玩家永久挡住**：当 `is_cultivating = 1` 但
  `cultivation_start_time = 0` 时，原先 `/闭关` 会提示"你已经在打坐中了"，
  而 `/停止` 结算失败并强制结束，玩家必须重新闭关。
  现在仅在 `cultivation_start_time > 0` 时才判定为"已在打坐"，
  否则记录自愈日志并按重新闭关处理。
- **`cancelListing` 的 TOCTOU**：改为事务内 `getListingForUpdate`
  先抢占再执行，避免并发下重复退款/重复发货。
- **测试污染真实配置**：新增 `PluginSwitchTestSupport` 隔离 `plugins.yml`
  写入路径，测试不再改写仓库内的真实配置文件。
- **4 组指令重名，玩家指令被管理指令抢占**：`CommandRegistry.register()` 用
  `Map.put` 覆盖同名指令，而 `CommandScanner` 的扫描顺序来自 `File.listFiles()` /
  jar 条目顺序（都不保证稳定），于是"谁生效"取决于运行环境。实际存在 4 组：
  `发放`/`give`（`admin/GiveCommand` ↔ `item/ItemGiveCommand`）、
  `灵根`（`admin/SetRootCommand` ↔ `player/SpiritualRootCommand`）、
  `物品列表`（`admin/ItemsQueryCommand` ↔ `item/ItemMapCommand`）、
  `status`（`admin/StatusCommand` ↔ `player/StatusCommand`）。
  后三组是管理指令抢占玩家指令的名字 —— 一旦管理侧胜出，玩家敲 `/灵根`、
  `/物品列表`、`/status` 会收到"权限不足"而不是自己的数据。
  现让两侧名字互斥：管理侧改为 `修改灵根`、`全物品列表`、`服务器状态`，
  物品发放改为 `发放物品`/`giveitem`/`give_item`（与权限名 `admin.items.give`、
  路由 `admin/items/give` 一致），通用资源发放保留 `发放`/`give`。
  另外 `CommandRegistry.register()` 现在会对"同名被两个不同 Command 类注册"
  打 WARN 并点名两个类，避免同类问题再次静默发生。
- **`admin/GiveCommand` 权限过低（版主可凭空刷资源）**：该指令能按玩家 ID 发放
  金币/灵力/灵石/物品，却用 `admin.status` 门控，而 `PermissionService` 给
  `MODERATOR` 组的默认权限恰好包含 `admin.status` —— 等于版主拥有刷资源能力。
  现收紧为 `admin.items.give`（`ADMIN`/`SUPER_ADMIN` 拥有全部权限码，不受影响）。
  **⚠️ 行为变化**：`MODERATOR` 组将不再能使用 `/发放`。
- **`admin.status` 被当成写权限用（版主可改玩家数据、可全服广播）**：该权限码的显示名
  是「查看服务器状态」，但 `MODERATOR` 组的默认权限里包含它。而下列**写操作**却用它门控：
  `SetRootCommand`（改灵根，会重算基础属性）、`EditPlayerCommand`（直接改写等级/金币/
  灵力/境界）、`EnergyAdminCommand`（增删玩家能量）、`POST /admin/announce`（全服广播）、
  `UsersRolesCommand`（输出全部账号、角色与 QQ 绑定）。即版主可随意改玩家数据。
  现按「读用 `admin.status`、写用 `admin.users.manage`」重新划分：
  - 改玩家数据的指令改用 `admin.users.manage`，与 REST 侧既有约定一致
    （`/players/{id}/edit`、`/players/{id}/spiritual-root`、`/users` 本就是这个权限码）；
  - `EnergyAdminCommand` 读写分离：指令级与 GET 路由保持 `admin.status`（版主仍可"查看"），
    三个写路由 `admin/energy/set|add|remove` 与指令内「设置/增加/减少」分支改用
    `admin.users.manage`（`isWriteAction()` 二次校验）；
  - 全服广播改用新增权限码 `admin.announce`。
  仍用 `admin.status` 的只剩纯读取接口（服务器状态、玩家列表/详情、物品与境界字典、
  统计、系统信息）。
  **⚠️ 行为变化**：`MODERATOR` 组将不再能改玩家数据、不再能发全服公告；
  又因 `admin.users.manage` 不在 `ADMIN` 的默认权限内（`ADMIN` 默认 = 全部权限码 −
  `admin.roles.manage`、`admin.users.manage`、`admin.database.clear_players`、
  `admin.database.reset_all`），这些写操作实际只有 `SUPER_ADMIN` 可用，
  需要放给 `ADMIN` 请在管理台「权限组」页手动授权。
- **`ALLOWED_TABLES` 漏登记 `map_location_event_bias`**：该集合是管理台通用表 CRUD 的
  白名单，漏登记会让这张表在管理台报"表不存在"——而 `DatabaseManager.initTable()` 里
  其实早就把它建好了。新增表时必须同时改 `initTable()` 与 `ALLOWED_TABLES`。
- **野外刷新会让探索记录错位**：野外 id 是**循环复用的固定槽位**，
  若刷新时不清掉这些 id 上的 `player_map_exploration`，下一轮落在同一 id 的"另一个"
  地点会显示成已探索，并白送一个传送解锁。现刷新会显式删除野外 id 的探索记录
  （已知世界的探索记录保持永久）。
- **野外节点删除会把玩家留在虚空**：删节点前会先把站在该节点上的玩家挪回它的
  `entry_location_id` 并重置 `last_travel_time`，否则玩家会落在已删除的地点上。
  注意这里用的是**显式删除**而不是外键级联 —— SQLite 默认不开
  `PRAGMA foreign_keys=ON`，级联不会生效。
- **Minecraft 玩家适配器在 26.2（协议 776）上整条链路都不可用**：收发两侧都还是
  1.20.5 时代的旧布局，服务端要么直接断开、要么把私聊静默丢掉。逐项对照官方未混淆服务端
  `server-26.2.jar`（`GameProtocols` / `ConfigurationProtocols` / `LoginProtocols` 的包号
  与各包类的 `write`/`read`）后修正，涉及 `MinecraftClient` / `McProtocolMapper` /
  `MinecraftPlayerAdapter`：

  - **`/指令` 发不出去**：26.2 的 `ServerboundChatCommandPacket` 只剩一个命令字符串
    （带参数签名的另拆到 0x08），旧代码多写了时间戳、盐与签名计数 —— 多一个字节就会被
    服务端 `PacketDecoder` 判成"包比预期大"而断开。指令字符串也**不带前导 `/`**
    （真实客户端是 `ChatScreen.sendCommand(msg.substring(1))`）。
  - **自动注册/登录用错了包**：`performAutoAuth` 原先用聊天包发 `/reg`、`/l`，
    而服务端的 `handleChat` 只会把它当普通发言广播出去，永远不会执行 —— 表现为
    "机器人进服了但一直没登录"。现改走指令包。
  - **聊天包的 `LastSeenMessages.Update` 少写 4 个字节**：26.2 是
    `VarInt 偏移 + 20 位确认位图（固定 3 字节）+ 校验字节`，旧代码只写了一个 VarInt；
    服务端 `LastSeenMessagesValidator` 校验不过会以 `CHAT_VALIDATION_FAILED` 断开。
  - **私聊判定靠猜文本**：26.2 的私聊收件走 `ClientboundDisguisedChatPacket`，
    聊天类型由 `ChatType.Bound` 的 holder id 决定，而 id 顺序来自配置阶段同步的
    `minecraft:chat_type` 注册表 —— 写死 id 或靠"有没有目标名"都不成立
    （`/msg` 的接收方**没有**目标名，带目标名的是自己发出的回显）。现按注册表名精确判定：
    `msg_command_incoming` / `team_msg_command_incoming` 算私聊，
    `*_outgoing` 是自己发出的回显直接忽略，其余走公屏回调；
    只有注册表没同步到时才退化为"非本人发送的消息"。
  - **聊天组件仍按 JSON 解析**：1.20.3（协议 765）起组件在网络上是 NBT
    （`FriendlyByteBuf.writeNbt` → `NbtIo.writeAnyTag`），旧代码 `readString` 读出来的是
    乱码，正文永远为空。新增 `McNbt`：最小 NBT 读取器 + 组件转纯文本
    （`text`/`translate`/`keybind`/`selector`/`score`/`nbt` 与 `extra`），
    并保留私聊两条 translate key 的历史文案。
  - **`ClientboundPlayerChatPacket` 首字段漏读**：26.2 多了 `globalIndex`，旧解析从
    UUID 开始，整包错位。
  - **配置阶段的三个包没处理**：资源包推送与 26.2 新增的行为准则都必须回一个终态，
    否则服务端会一直挂在这个配置任务上、永远不发 Finish Configuration（表现为
    "登录后卡住"）；另外补上 PLAY 阶段的 `ping`、以及重生后必须回的
    `player_loaded`（不回则服务端一直认为客户端没加载完）。
  - **踢出原因是 NBT 不是 JSON**（PLAY / 配置阶段），旧代码按 JSON 解析，日志里只有乱码。
  - **`Inflater.inflate` 只调一次**：zlib 不保证一次填满缓冲区，会把一个完整包读成半截；
    现循环到 `finished()` 或缓冲区满，并对截断数据报错而不是静默返回残包。
  - **默认协议号写死 762**：对着 26.2 服务端时握手阶段就会以"版本过旧"被拒。默认改为
    0（Status Ping 自动检测），与 Web 管理台的默认值保持一致。
  - **配置阶段发 PLAY 包会被服务端误解析**：`MinecraftClient.awaitPlayReady()` 让适配器
    等到真正进入 PLAY 再发指令（配置阶段的 0x07 是 select_known_packs，
    收到指令字符串会直接报错断连）。

### 变更

- **安装向导「仅限本机」开关显式化**：默认模板 `src/main/resources/application.yml`
  新增 `install:` 段（`allow_remote: false`），把此前只存在于代码里的隐藏开关
  `install.allow_remote` 写进配置并附完整说明；403 提示文案补上配置文件路径
  （含 Docker 的 `/app/config/application.yml`）。README 新增「远程 / 局域网 /
  Docker 安装」小节，Docker 方式补上打开开关的两步命令 —— 容器内看到的来源是
  Docker 网关 IP 而非回环地址，按旧文档直接访问 `/install` 会被 403 拦下。
  同时修正 README 中「清空 `jwt.secret` 即可重装」的过期说法：V7 起以锁文件
  `config/.installed` 为准，重装须走 `POST /api/install/reset`（需当前管理员密码）
  或手动删除锁文件。

- **移除 quiz 遗留内容**：删除 `data/quiz/questions_template.csv`、空包目录
  `com/mtxgdn/onebot/quiz/` 与 `src/main/resources/data/quiz/`，以及 `.gitignore`
  中的 `data/quiz/questions.json` 忽略项。代码侧从未引用过题库，属纯遗留物。

- **鉴权与限流加固**：`RateLimiter`、`TokenBlacklist`（含历史记录清理）、
  `JwtUtil` 增强；`AdminAuthFilter`、`JwtAuthFilter`、`ModuleGateFilter`、
  `UnifiedRestResource` 收紧；`ServerConfigurator` 增加 WebSocket 单帧上限
  （`network.ws_max_message_size`，默认 65536）。
- **地图从"UI 壳"变成真正的玩法系统**：此前 15 个地点实际只等于"15 个修炼乘数 +
  1 个传送点"，地点描述里承诺的资源关联（落霞山产落霞草、万妖谷妖兽内丹、灵药谷
  灵植）在代码里全是空话。现在：

  - 地点灵气加成改由数据库驱动（**数值与原先硬编码表逐一相同，修炼行为无回归**）；
  - 游历事件抽取按所在地界加权：落霞山偏材料、万妖谷偏妖兽、灵药谷偏灵草与种子、
    天元古城偏遗迹与禁制、问道峰偏修士奇遇……游历日志会点明"此地以…闻名"；
  - 游历与秘境收益按地界倍率追加（与季节、天象加成的既有结算方式一致）；
  - 管理端与玩家端均可看到灵气/地界倍率。
- **安全区禁止私斗**：`is_safe_zone` 此前只是展示用的 🛡 标记，从未参与任何判定。
  现在 PVP 各入口（发起挑战、应战、实际开打前兜底）都会校验，任一方身处安全区即
  拒绝，且校验先于每日次数扣除，避免被拦下还白扣一次挑战次数。
  **⚠️ 行为变化**：新角色出生地青云村是安全区，因此新手需先离开出生地才能 PVP；
  若不希望如此，把 `map_locations` 中青云村的 `is_safe_zone` 改为 0 即可
  （改完自动热重载，无需重启）。
- **地图相关行为变化（⚠️ 玩家可见）**：`/地图 list` 的输出新增探索度、迷雾标记
  `✅`/`❔` 与危险度标记；新增 `/野外`、`/采集` 两条指令与 `/地图 传送 <地名>` 子指令；
  险地（危险度 ≥3）私斗现在会掠夺灵石。同时 `MapService.getAllLocations()` 改为
  **只返回已知世界**（野外在数据库里没有行，按分片取用 `getChunkNodes(cx, cy)`），
  使玩家侧输出与既有测试保持"出厂 15 个地点"的契约不变。
- **野外存储改由文件承载（⚠️ 覆盖上一版的数据库方案）**：上一版让野外与已知世界共用
  `map_locations` / `map_connections`、并把资源放进 `map_wilderness_resources` 表；
  现在野外**完全不进数据库**，只留分片文件。随之：
  - `map_wilderness_resources` **不再创建**（老库留下的空表无害、也刻意不去 `DROP`）；
  - `MapService.getWildernessLocations()`、`getAllLocationsIncludingWilderness()`、
    `getWildernessRefreshAt()`、`refreshWilderness()` 移除或替换；
  - 新增 `getChunkNodes(cx, cy)`、`getChunk(cx, cy)`、`isChunkPinned(cx, cy)`、
    `getNeighbors(id)`、`isLocationKnown(id)`、`getLocationByToken(token)`、
    `getWildernessStats()`、`restockWilderness()`；
  - `MapLocation.expiresAt` 复用为"本片补货时间"，`entryLocationId` 保留语义。
- **⚠️ 野外行为变化（玩家可见）**：`data/wilderness/` 为新增的世界状态目录（已加入
  `.gitignore`，与 `data/players/` 同级），备份时需一并带上；
  已探索的野外从此**永久保留**，资源改为原地补货而不再整批换地。
  `/地图 go` 现在同时接受地名与地点 id（野外节点名随机，只能按 id 导航）。
- **农田生长改为惰性结算（移除每秒全表扫描）**：`FarmService` 原有一个
  `scheduleAtFixedRate(updateAllPlots, 1, 1, SECONDS)` 的后台任务，每秒执行
  `SELECT * FROM farm_plots WHERE state IN ('PLANTED','GROWING')`，把全服正在生长的
  地块逐一推进一格 —— 代价与全服地块数成正比，而当时绝大多数玩家并不在看农田。
  现在改为**玩家访问农田时按时间差一次性结算**（`settlePlayerPlots` / `settlePlot`）：
  读取农田、种植、浇水、施肥、杀虫、清理、收获（含一键操作）前都会先把该玩家的地块
  推进到当前时刻，**不再需要任何后台线程**（`shutdownScheduler()` 保留为空实现）。
  等价性：水分同为线性衰减，枯萎时刻取精确穿零点；病虫害把"每秒掷一次骰子"折算为
  区间累计概率 `1-(1-p)^n`（统计期望一致，并顺带修正了"服务器停机期间不生病"的偏差）；
  成熟即掷定品质并立即落库（掷点仍在改写水分之前，与旧实现顺序一致）。
  并发下也安全：结算写出的是由 `(since, now)` 推导的**绝对值**而非增量，重复结算结果相同。
  `farm_plots` 新增 `settled_time` 记录上次结算时刻（老库由迁移补列，默认 0 时回退
  到 `planted_time` 作为起点）。
- **Buff 过期改为惰性 + 低频兜底（移除每秒全表扫描）**：`BuffService` 原有的
  1 秒一次 `SELECT ... FROM player_buffs WHERE expire_time <= ?` 全表扫描改为：
  活跃玩家在查/加/清 Buff、加载角色时**即时过期**该玩家的增益（`expirePlayerBuffs`）；
  离线玩家由低频兜底任务回收，间隔由新增配置 `buff.expire_sweep_seconds` 控制
  （默认 60 秒，原为 1 秒）。属性回退与行删除绑定在**同一条条件 DELETE** 上
  （`executeUpdate() == 1` 才回退），因此并发或两条路径重复触发都只回退一次；
  条件里带 `expire_time <= ?`，保证"期间重新嗑药刷新了时长"的增益不会被误删。
  兜底任务改用守护线程并隔离异常（一次失败不终止后续 tick）。

### 工程与仓库

- **版本号收敛为单一来源，并把项目版本改为 `0.1.0`**：版本号此前硬编码在 10 处
  （`AdminResource` 的后台接口、`PluginMaker` 的横幅、插件 GUI 标题、生成的插件文档、
  模板 pom 的 Maven 坐标注释……），写法还不统一（同一时间既有 `V1.4.1-beta2`
  也有 `v1.4.1-beta2`），改一次要逐一去找，漏一处就会出现「界面显示的版本与实际构建
  不一致」。现在唯一来源是 `pom.xml` 的 `<version>`，经两条链路进入程序：
  资源过滤生成 `build-info.properties`（主要来源），shade 写入 JAR manifest 的
  `Implementation-Version`（兜底），两者都由新增的 `com.mtxgdn.util.BuildInfo` 读取。
  `getVersion()` 返回纯净版本号（`0.1.0`，用于 Maven 坐标，不带 `v`），
  `getDisplayVersion()` 返回展示用（`v0.1.0`）。两者都拿不到时返回 `unknown`
  而不是谎报一个版本。
  **约定的版本顺序**：`0.1.0` 小于历史里的 `V1.3.0`~`V1.4.1-beta2`，因为
  `0.1.0` 起是新的版本线、`V1.x` 是前身版本号，详见本文件顶部说明。
- **启动脚本不再写死带版本号的 jar 名**：`start.sh` 里那个
  `elif [ -f "target/main-V1.4.1-beta2.jar" ]` 分支在版本号变更后必然失效
  （通配分支本来就能覆盖它，属于冗余），现已删除；`start-low-memory.bat`
  原先直接写死 `target\main-V1.4.1-beta2.jar`，现改为按修改时间取最新的
  `target\main-*.jar` 并排除 `original-*`，找不到时给出明确报错。
  `deploy/xiuxian-server.service` 的安装注释同步更新。Docker 侧本来就用
  `main-*.jar` 通配并重命名为 `main.jar`，无需改动。
- **修复 Maven wrapper**：补回缺失的根目录 `mvnw` / `mvnw.cmd`（此前只有
  错放在 `.mvn/` 下的老式 takari 0.5.6 脚本）；修正
  `maven-wrapper.properties` 中版本段为字面量 `3` 的无效 `distributionUrl`；
  删除 `distributionType=only-script` 下不再使用的 `maven-wrapper.jar`。
- **新增 `.gitattributes`**：钉死换行符，避免 `mvnw` / `*.sh` 被转成 CRLF
  后在 Linux 上报 `bad interpreter: /bin/bash^M`。
- **修复 `run_tests.sh` / `run_tests.bat`**：原先引用并不存在的
  `SecurityTest` 与 `ComprehensiveFunctionalTest`，执行必然失败；
  现改为运行完整测试套件。
- **`.gitignore` 补 `/config/`**：根目录 `config/` 是运行期生成的，
  内含 `jwt.secret`、`admin.password_hash`、数据库口令，此前未被忽略，
  一条 `git add -A` 就会把密钥推上远端。
- **取消跟踪 `dependency-reduced-pom.xml`**：`maven-shade-plugin`
  每次 `package` 都会重写的生成物。
- **新增 `docs/PROJECT_STRUCTURE.md`**：逐目录、逐包、逐文件的项目结构说明
  （476 个主源码文件、29 个测试文件、36 个资源文件；含启动时序、指令三端入口、
  数据库表清单、`application.yml` 配置段索引、内置插件开关对应表、扩展点速查，
  以及 115 个物品的全表附录）。

### 测试

新增 16 个测试类与 1 个测试支持类，全套 **205 个测试通过**：

- `game/service/CultivationRestartTest`、`game/service/TradeListingExpiryTest`、
  `game/service/TradeServiceConcurrencyTest`、
  `game/service/EconomyServiceConcurrencyTest`、`db/MarketIndexTest`
- `security/EndpointAlignmentTest`、`security/InputAndRateLimitTest`、
  `security/SecurityFixRegressionTest`
- `plugin/PluginSwitchTestSupport`（测试支持类）
- `game/service/MapServiceTest`（16 个用例）：加成入库与 NULL 回填、管理员改值不被
  覆盖、`reload()` 生效、地界事件偏置、境界门槛、邻接校验、赶路冷却、
  **并发移动只成功一次**、失效位置回落、传送符目的地与境界约束、安全区 PVP 拦截。
  地图此前没有任何自动化测试，这几段恰好是纯逻辑、最易回归的部分。
- `onebot/command/map/MapCommandRenderTest`（4 例）：区域标题不重复、世界脉络覆盖全部
  15 个地点、境界门槛显示境界名、灵气与安全区标记。**这组用例第一次运行就抓出了
  `/地图 list` / `/地图 go` 是死代码** —— 所谓"回归测试"的价值正在于此。
- `security/EndpointAlignmentTest` 新增 2 例：管理台地图接口存在且权限码正确、
  WebUI 地图页三处接线齐全（导航项 / tab 容器 / 模块注册）。
- `rest/AdminMapResourceTest`（6 例）：总览数据正确（境界名、倍率、邻居与路程、连接数）、
  **更新后地图缓存确实热重载**（不重启即可读到新值）、未传字段不被清空、
  非法倍率被拒且不落库、未知地点 404、空请求体被拒。
- `db/LegacySchemaUpgradeTest`（2 例）：把表结构还原成"没有 `expires_at` / 没有地图加成列"
  的老版本，再跑一遍 `initTable()` 模拟重启，钉住"先迁移、后建索引"的顺序。
  已验证它在修复前会以完全相同的异常（`no such column: expires_at`）失败。
  注意本类必须自清理夹具行：整套测试共用一个 SQLite 文件（`TestBase` 的删库在 Windows 上
  因连接池仍持有文件句柄而失效），否则会污染后续测试类。
- `common/command/CommandNameConflictTest`（4 例）：扫描全部指令后断言**没有任何名字被
  两个 `Command` 类注册**；并分别钉住 4 组历史冲突的归属（`发放` 归 `GiveCommand`、
  `发放物品` 归 `ItemGiveCommand`、`灵根` 归玩家侧 `SpiritualRootCommand`、
  `物品列表` 归 `ItemMapCommand`、`status` 归玩家侧 `StatusCommand`）、
  发放类指令必须以 `admin.items.give` 门控（不得退回 `admin.status`），
  以及被改名的管理指令仍可达。已验证：临时把 `status` 加回管理侧，
  该测试如期失败且 `CommandRegistry` 打出点名两个类的 WARN 日志。
- `security/AdminWritePermissionTest`（10 例）：`admin.status` 只读性的三条不变量 ——
  权限码为 `admin.status` 的指令路由与管理台接口**必须都是只读 GET**；
  管理台写接口必须显式声明权限码且不得复用只读码（`/login` 是唯一例外，未登录也要能调）；
  用 `admin.status` 的指令必须在显式白名单内，**新增指令若也想用它，测试会失败** ——
  强制先确认是纯读取。另钉住本次的权限归属：改玩家数据的指令用 `admin.users.manage`、
  只读管理指令仍用 `admin.status`、能量管理读写分离、`/admin/announce` 用 `admin.announce`。
  已验证：临时把 `SetRootCommand` 改回 `admin.status`，该测试如期失败。
- `game/service/MapWildernessTest`（21 例）：本轮地图新能力的回归 ——
  **危险度**（安全区 ⇒ 危险度为 0 的不变式、四档中文名、越界不返回 null）、
  **野外确定性**（同一 (种子, 坐标, 槽位) 两次生成必须逐字相同 —— 这是"分片回收后
  重现仍一致"的根基，测试会先清掉生成缓存强制真的重算）、
  **id 编码**（坐标 ⇄ id 可反解、同分片内 16 个 id 连续、不越过槽位步长）、
  **越远越险**（危险度恒在 1~4 且随距离单调不减）、**分片四邻互通**（不生成孤岛）、
  **节点自洽**（危险度 1~4、非安全区、加成由危险度推导）、
  **⚠️ 野外不进数据库**（探索前后 `map_locations` / `map_connections` /
  `player_map_exploration` 的行数一动不动，且后者里没有任何野外 id ——
  本轮"别用数据库，用文件"这条要求的落点）、
  **分片文件**（探索后落盘、分片被固化、维护不会删掉它），
  **回收**（从未探索且长期闲置的分片被回收，**已探索的绝不会**）、
  **原地补货**（资源补回上限但地形与探索记录不变）、
  **野外资源**（来自配置池、物品 key 在游戏里真实存在 —— 配置里写错 key 会让采集
  "成功"却发出空物品）、
  **首达**（只发一次奖、探索度 +1、奖励随危险度递增、走遍全图触发完成奖励）、
  **传送**（未到过则拒绝且不改变位置、到过后按配置扣费、灵石不足则拒绝、
  危险度越高越贵且野外含附加系数）、
  **采集**（已知世界拒绝、野外成功且库存减少、连续采集被冷却挡下）、
  **险地 PK 掠夺**（危险度 4 产生掠夺且败者恰好损失该数额、危险度 1 不掠夺）。
  本类踩到并修掉了三个**只有整套件运行才会暴露**的测试环境问题，值得留意：
  一是物品注册表在测试 JVM 里默认是空的（注册发生在 `Main` 启动时），
  而灵石本身就是物品，不先 `ItemScanner.scanAndRegister()` 的话所有涉及灵石的断言
  都会以"物品不存在: mtxgdn:spirit_stone_low"炸掉；
  二是必须用 `ServiceRegistry.getMapService()` 单例而非 `new MapService()`，
  因为 `CombatService.checkSafeZone` 走的是注册表里的实例，
  两边用不同实例会出现"测试里新建的地点，PVP 校验看不到"（表现为玩家站在试炼场
  却被判定在安全区）；
  三是**分片目录必须按用例隔离**（`@TempDir` + `setRootForTest`）：分片文件默认落在
  工作目录的 `data/wilderness`，上一次跑留下的"已固化"分片会让下一次的
  "未探索分片应被回收"断言凭空失败。
  另外本类造的临时地点必须在 `@AfterEach` 清掉：测试库是全套件共用的，
  否则会顶掉 `AdminMapResourceTest` 的"已知世界 15 个地点"。

Minecraft 26.2 协议修复另加 2 个测试类（17 例），全套 **221 个测试通过**：

- `minecraft/adapter/McProtocol26Test`（16 例）：把字节布局钉死在"从官方源码读出来的结论"上 ——
  指令包**只有一个命令字符串**、聊天包的 `Update` 字段恰好 5 字节、
  `DisguisedChat` / `PlayerChat`（含 `globalIndex` 与 256 字节签名）/ `SystemChat` 的字段顺序、
  NBT 组件的各种形态（裸字符串 / 列表 / `translate` + `extra`）、
  畸形 NBT 长度必须报错而不是按声明长度分配巨大数组、
  `chat_type` 注册表顺序、私聊判定（公屏不能被误判、注册表缺失时的退化），
  以及 1.21.x 仍是旧布局（26.2 的改动不得泄漏到旧协议）。
  这些用例抓出的是"少写/多写一个字节就断连"的问题，肉眼 review 很容易漏。
- `minecraft/adapter/Mc26LiveSmokeTest`（1 例，**默认跳过**，加 `-Dmc26.live=true` 才跑）：
  对着真实 26.2 服务端（离线模式，`online-mode=false`）跑完整链路 ——
  两个客户端登录 → 配置阶段（Known Packs、注册表同步、Finish）→ PLAY，
  Alice 发公屏后机器人**必须收到且不能被判成私聊**（这一条同时证明
  `chat_type` 注册表确实解析成功：注册表缺失时会退化成"别人的消息都算私聊"，测试会失败），
  `/msg XiuXianBot status` 必须落进私聊回调。
  修复后首次运行即通过，服务端日志里是 `[Not Secure] <Alice> hello everyone`，
  没有任何 chat validation 报错 —— 也就是说新布局的 `LastSeenMessages.Update`
  确实被服务端接受了。注意本机跑 surefire 时要用 `-DforkCount=0`（沙箱不允许 fork 出管道）。
- `security/EndpointAlignmentTest` 的野外接口断言同步改为
  `/admin/map/wilderness` 与 `/admin/map/wilderness/restock`
  （旧的 `/map/wilderness/refresh` 已随“已探索内容不可删”移除）。
- `game/service/FarmLazySettlementTest`（7 例）：惰性结算的核心不变量 ——
  没有后台线程也能成熟（越过 `harvest_time` 的读取即结算为 READY 且品质落库）、
  水分按 `(now - settled_time)` 折算（100 秒衰减量必须一次扣够，而不是每次只扣 0.1）、
  离线期间水分耗尽会枯萎且枯萎时刻取精确穿零点、同一秒内重复访问幂等（品质不重掷、
  已成熟水分冻结）、收获前会先结算、**只结算被访问玩家的地块**（这正是取消全表扫描的目的），
  以及"`FarmService` 不得再持有任何 `ScheduledExecutorService` 字段"的防回归锁。
- `game/service/BuffLazyExpiryTest`（6 例）：读取即回退属性并删行、重复访问只回退一次
  （不得把属性扣成负数）、刷新过时长的增益不被误判为过期、离线玩家由兜底回收、
  兜底与惰性两条路径重复触发也只回退一次，以及"回收间隔不得回到秒级"的防回归锁。

本轮惰性结算改造后全套为 **242 个测试通过**（新增 13 例）。
- `util/BuildInfoTest`（2 例）：版本号单一来源机制的回归 —— 能从构建产物解析出版本
  （退化成 `unknown` 即说明资源过滤或 manifest 注入失效）、展示版本号恰为 `v` + 纯净版本号、
  纯净版本号不得自带 `v` 前缀（它会被填进插件模板的 Maven 坐标）。**刻意不写死具体版本号**：
  否则每次升版本都会变红，反而会被当成噪音忽略。

加上版本号收敛改造的 2 例后，全套为 **244 个测试通过**。

本轮野外改造后全套为 **229 个测试通过**。

宗门宗主继位改造另加 1 个测试类（17 例），全套 **263 个测试通过**：

- `game/service/SectSuccessionTest`（17 例）：把继位链路的七条不变量钉死 ——
  **按贡献继位**（贡献最高者上位、退位者降为副宗主）、**死锁已解除**
  （退位后原宗主可正常退宗，且未退位时的拒绝文案必须指路 `/宗门 退位`，
  否则玩家无从下手）、**竞争期开启条件**（境界相同则开启且挑战者为最强竞争者、
  境界差超过门槛则不开启）、**竞争者按职位优先**（长老优先于高贡献外门弟子）、
  **两段式挑战**（未发起不能应战；挑战者自己不能应战；应战后胜者任宗主、
  败者降副宗主、竞争字段清空、缺位标记清除）、
  **幂等**（重复 `resolveSuccession` 只生效一次）、
  **升级安全**（`last_active_time = 0` 的宗主绝不能被判成弃坑 ——
  这条直接对应"升级瞬间全服宗主被批量换掉"的灾难场景）、
  **扫描语义**（不活跃宗主被换人、活跃宗主不动、竞争期到期只清字段不动宗主、
  未到期的竞争期不被误清）。
  夹具全部走裸 SQL 建行（`createSect` 按名字回查 id，不用 `getGeneratedKeys`
  以免踩驱动差异），玩家境界用 `realm` / `sub_realm` 直接落库以越过修炼流程。

世界 Boss 另加 1 个测试类（18 例），全套 **281 个测试通过**：

- `game/service/WorldBossTest`（18 例）：钉住六条不变量 ——
  **池子生命周期**（按配置数量生成且满血、TTL 内重复读取返回同一批 id、
  到期整池换成一批全新的、管理员刷新立即换一批）、
  **血量全服共享**（A 打掉的血落库，B 从 A 剩下的血量起算并继续扣，
  库里的血量恒等于最后一次挑战返回的剩余血量）、
  **伤害分红精确性**（`灵石 == 预算 × 伤害 / 最大血量` 逐位相等；
  一击必杀时恰好拿满预算 + 20% 击杀加成）、
  **超发熔断**（正常奖励原样通过、刚好等于阈值不折算、
  超阈值被折算到 `阈值/10 .. 阈值/5` 区间内）、
  **挑战冷却**（冷却期内第二次被拒且文案含"冷却"、冷却过后可再战）、
  **击杀归属与各种拒绝**（补最后一刀的人才被记为击杀者；已死、境界差距过大、
  闭关中三种情况都被拒**且不扣血**）。
  另有一条**端到端入账校验**：战报里的灵石数必须与
  `ItemService.getSpiritStoneCount` 的实际增量一致 —— 防"战报发了、钱没到账"。
  夹具会把生成出来的 Boss 数值直接改写（境界/血量/预算），
  避免断言依赖随机生成结果；`ItemScanner.scanAndRegister()` 必须在
  `@BeforeAll` 里先跑，否则所有涉及灵石的断言都会以"物品不存在"炸掉。


---

## [V1.4.1-beta2] — 插件开关迁移、玩法接入补齐、坊市与 QQ 适配器修复

### 🧘 修复：闭关脏数据把玩家挡住，必须重新闭关

---

#### 现象
线上日志（2026-07-19）里的原始序列：

```
/闭关 → 你已经在打坐中了，使用 /停止 来结束。
/停止 → 打坐状态异常，已强制结束。      ← 结算失败，闭关被强制结束
/闭关 → 开始打坐！                      ← 玩家必须重新闭关
```

#### 根因
数据库里出现**不一致状态**：`is_cultivating = 1` 但 `cultivation_start_time = 0`。
此时算不出闭关时长，结算只能拿到 **0 经验**，玩家白丢一整段闭关时间。
脏数据来自旧版本：早期 `startCultivation` 只写 `is_cultivating`、没写起始时间
（当时的代码还专门有一句"闭关状态异常，已强制结束"来兜这个底，后来那句被删掉了，
但兜底逻辑没有跟着补上）。

#### 修复
`PlayerService.beginCultivation` 现在区分两种"已在闭关中"：

| 状态 | 行为 |
|------|------|
| `start_time > 0`（正常闭关） | 仍然拒绝重复打坐，**不改写**已有起始时间（避免重置玩家进度） |
| `start_time <= 0`（脏数据） | **自愈**：直接按当前时间重新开始闭关，不再把玩家挡在外面 |

这样玩家遇到脏数据时可以直接 `/闭关` 恢复，不必先走一遍 `/停止` 白丢经验。

#### 验证
- 新增 `CultivationRestartTest`（4 个用例）：
  - 停服重启后闭关状态与起始时间**都保留**，结算包含重启前时长（**正常重启本来就是好的**，已锁死防回归）；
  - `start_time = 0` 时 `/停止` 能自愈、能重新闭关；
  - `start_time = 0` 时 `/闭关` 不再被挡住（**修复前此用例失败**）；
  - 正常闭关中重复打坐仍被拒绝，且起始时间不被改写（防自愈逻辑误伤）。
- `mvn clean test`：**146/146 全部通过**。

> 说明：日志里那句"打坐状态异常，已强制结束。"**当前代码已不会产生**
> （`game.cultivate_abnormal` 现为无人引用的遗留语言键，日志中最后出现在 2026-07-19）。
> 本次修的是同一个根因在当前代码下**仍然存在**的后果：脏数据把玩家挡住、被迫走有损的 /停止。

---

### 🏪 变更：坊市挂单时限 + 成交价格历史（定价不再靠猜）

---

#### 问题
- **挂单永不过期**：玩家挂单后一直占着坊市列表，无人购买也永不释放，
  列表被陈年僵尸挂单占满；卖家也无法通过"过期自然退回"拿回物品（只能手动撤单）。
- **定价完全靠猜**：看不到任何历史成交参考，玩家只能拍脑袋定价，
  价格体系失序（同一物品有人挂 10 灵石、有人挂 10000 灵石）。

#### 变更
- **挂单时限**（`market.listing_duration_hours`，默认 72 小时）
  - 新挂单写入 `trade_listings.expires_at`；到期由后台任务自动下架并**把物品退回卖家背包**。
  - 到期回收用"先抢占 `status: active → expired`，抢到的一方负责退回"的写法，
    与买家并发购买竞争时不会把物品**退回两次**（复制物品）。
  - **查询层自己过滤到期挂单**（不只靠定时任务）：坊市列表、我的挂单、购买、
    撤单四处 SQL 都带 `expires_at > now`，避免定时任务最长 60s 空窗期内
    买到"僵尸挂单"（钱付了、物品却已被回收退回卖家）。
  - **老库兼容**：既有库由迁移补 `expires_at` 列（保持 NULL = 永不过期，升级瞬间不清空老挂单），
    随后按 `created_at + 配置时长` 回填出真实到期时间，让时限对老数据同样生效。
- **成交价格历史**（`market.price_history_keep_days`，默认 30 天）
  - 新增 `trade_price_history` 表（`(item_key, sold_at)` 索引），每笔成交记录
    **单价**（总价 ÷ 数量）、数量、总价 —— 挂单数量不同，只有单价可比。
  - 新增指令 `/行情 <物品名或ID> [天数]` 与 REST `GET /game/market/price`：
    展示近 N 天**成交笔数 / 成交量 / 均价 / 最低 / 最高**，
    无成交时给出**当前在售最低单价**作为定价锚点。
  - 天数参数做边界收敛（`<=0` 回退默认 7 天、超大值封顶 90 天），防止一次拉全表。
  - 写入历史失败**不影响成交本身**（成交已完成，历史只是辅助信息），单独 try/catch。
- **展示**：坊市列表与我的挂单均显示剩余有效时间；REST 响应补充
  `unitPrice` / `expiresAt` / `remainingMs` / `remainingText` 字段。
- **挂单表补索引**（上线前性能加固）：`trade_listings` 此前**一个索引都没有**，
  而到期回收任务每 60 秒按 `status + expires_at` 扫一次、`/坊市` 每次按 `status` 过滤 ——
  两者都是全表扫描。现补 `(status, expires_at)` 与 `(seller_player_id, status)` 两个索引。
  用 `CREATE INDEX IF NOT EXISTS` 写在 `initTable()` 里，每次启动都会执行，
  **老库升级后自动获得索引，无需手工迁移**。测试同时断言索引存在
  与回收查询的**执行计划确实走索引**（只断言"索引存在"不够，条件一改就可能退化）。
- **补齐三通道客户端**（此前行情只在 OneBot/REST 可用，Java 客户端与管理台都是空白）
  - `GameApi.getPriceHistory(itemKey, days)`：Java 客户端此前有 5 个坊市方法，
    **唯独没有查行情的**，`/game/market/price` 全项目零调用方。
  - `DemoClient` 坊市菜单新增「[6] 成交行情」。
  - **WebUI 新增「🏪 坊市管理」页**：挂单总览（卖家/物品/数量/标价/单价/手续费/
    剩余时间，剩余 1 小时内标红）+ 成交行情查询（均价/最低/最高/在售最低价）。
    > 注意：WebUI 的 `api.js` 以 `/api/admin` 为基址，而玩家侧坊市接口挂在
    > `/api/game/market/*` 下 —— 路径空间不同，**无法直接复用玩家接口**。
    > 因此管理台另建只读接口 `GET /admin/market/listings`、`GET /admin/market/price`
    > （权限 `admin.economy`），且**不受交易插件门控影响**：即使交易玩法已停用，
    > 管理员仍能查看存量挂单与成交历史。
  - 挂单总览的卖家名改为**一次批量查询**（`WHERE id IN (...)`），
    避免逐行查库的 N+1（挂单可能有上百条）。
- **`/上架` 价格改用 long 解析**：服务端上限是 999,999,999，原先用 `Integer.parseInt`
  解析，超限输入会报"数量和价格必须是整数"这种误导性提示，而非服务端准确的超限说明。

#### 修复
- **价格历史清理任务从未被调度**：`cleanupPriceHistory()` 已实现且有测试覆盖，
  但没有任何地方调用它 —— 历史表会只增不减、无限膨胀。
  现将其排入定时器（`market.price_history_cleanup_interval_hours`，默认每 6 小时一次），
  并补调度防回归测试锁住这一缺口。
- **撤单与"到期回收 / 被买走"并发时可能重复退回物品**：`cancelListing` 是唯一在
  **事务外**读取挂单的写路径（购买早已修好）。撤单读到 `active` 快照后，回收任务抢先
  置 `expired` 并退物，撤单再把 `active` 改成 `sold` **又退一次** —— 玩家凭空多出一份物品。
  现改为与购买同一条路径：在**事务内**读取挂单并校验归属，抢占成功者才退物。
  `getListing()` 随之成为死代码，已删除。

#### 配置
```yaml
market:
  listing_duration_hours: 72                # 挂单有效时长（小时）
  price_history_keep_days: 30               # 成交价格历史保留天数
  price_history_cleanup_interval_hours: 6   # 价格历史清理任务执行间隔（小时）
```

#### 验证
- 新增 `TradeListingExpiryTest`（21 个用例）：挂单带到期时间且时长等于配置值、
  到期回收后物品**恰好退回一次**（重复扫描不复制）、到期挂单不出现在列表且不可购买、
  行情统计口径正确（均价/最低/最高/成交量）、无成交时的降级提示与在售最低价、
  未知物品返回失败而非抛异常、天数边界收敛、清理保留近期记录、
  历史挂单回填、清理任务确实被调度；
  撤单（退款退物 / 不可撤他人挂单 / 重复撤单只退一次 / 已回收挂单不可再撤）、
  手续费（默认 5% 且兜底至少 1、土金灵根减半）、
  行情隔离（不同物品互不串扰）与单价精度（总价 ÷ 数量）、短 key 自动补全。
- `TradeServiceConcurrencyTest` 扩充至 6 例：新增「撤单与抢购并发时物品与灵石守恒」
  与「并发重复撤单只退一次」，直接覆盖上述撤单竞态。
- `EndpointAlignmentTest` 扩充至 8 例：确认 `market/price` 已暴露且权限码正确、
  6 个坊市命令的 `module` 均为 `trade`（停用交易插件后不得仍可交易）、
  管理台 `/admin/market/listings` 与 `/admin/market/price` 存在且为只读 GET +
  `admin.economy` 权限、WebUI 坊市页三处接线齐全。
- 测试隔离修正：整个套件共用一个 SQLite 测试库，`trade_price_history` 会跨用例累积，
  导致 COUNT/AVG 断言被其它用例污染（此前靠用例执行顺序侥幸通过）。
  现于 `TradeListingExpiryTest` 每个用例前清空价格历史表，使统计断言确定性成立。
- WebUI 接线测试**读源码目录而非 classpath**：`target/classes` 里是上一次构建拷贝的
  静态资源，只读 classpath 时改了 `src` 下的 html/js 仍会对着旧副本通过（假绿）。
  已修正为优先读 `src/main/resources`，并逐项验证过三处接线（标题 / ADMIN_TABS /
  TAB_LOADERS）被破坏时测试确实会失败。
- `mvn test`：**142/142 全部通过**（此前长期存在 1 例红灯，原因见下）。

#### 测试基础设施修复
- **`BuiltInPluginEnableOrderTest` 长期假失败已定位并修掉**。它并非真的失败，而是
  **跨运行的状态污染**：该测试用一次性探针插件 `enable_order_probe` 验证"先置启用态再回调"，
  而内置插件的启停会把开关**持久化**到 `plugins.yml`。探针在 `finally` 里被停用，
  于是 `enable_order_probe: false` 被写进共享的测试开关文件并**留存到下一次运行** ——
  下次运行时探针在 `enableAll` 阶段就被跳过，断言随之失败。
  表现即"单独跑通过 / 全量跑失败""这轮绿下轮红"，与坊市改动无关。
  现抽出 `PluginSwitchTestSupport`（`isolate()`/`restore()`）把开关文件指向一次性临时目录，
  并应用于三个使用探针模块的测试类（`BuiltInPluginEnableOrderTest`、
  `BuiltInPluginRegistryTest`、`BuiltInPluginGateTest`），从根上消除这一整类污染。
- **`TradeListingExpiryTest` 两例手续费断言间歇失败已修掉**（本次新增用例暴露）：
  `createPlayer` 会**随机抽取灵根**，若恰好抽到土金灵根（`TRADE_FEE_HALF`）则抽成本就减半，
  使"基准抽成应为 500"随机变成 250。现显式把基准灵根固定为无减免的四象灵根。
- 补充验证：全量 `mvn test` 连续多轮 **139/139 通过**，且共享测试开关文件中
  **不再残留任何测试专用键**（`probe`/`test_module`/`shutdown_mod`/`gate_test_mod` 均为 0）。

> 排查注记：期间一度出现"改回正确代码仍泄漏"的假象，实为 **Maven 增量编译未重编**——
> 用备份文件还原 `.java` 会带回较旧的 mtime，`.class` 比 `.java` 新，Maven 遂跳过重编译、
> 继续跑旧的缺陷字节码。**验证此类修复必须 `mvn clean` 后重跑。**

---

### 🔌 变更：实验性内容插件化 + 插件开关迁移到独立配置文件 `config/plugins.yml`

---

#### 问题
- 插件开关混在主配置 `config/application.yml` 的 `built_in_plugins` 段里，改开关要在上千行主配置中翻找；
- 实验性功能（宗门战、能量转化/等价交换）另有一个 `config/experimental.yml`，与插件体系割裂：
  WebUI「插件管理」页看不到它们，无法运行时启停，`sect_war` 更是**只写了开关却从不生效**；
- 两处开关分散，新增实验性内容时容易漏配、也难以为用户找到。

#### 变更
- **实验性内容插件化**：新增 `com.mtxgdn.plugin.experimental` 包与两个内置插件
  - `experimental_sect_war`（宗门战，默认 **false**）
  - `experimental_energy_exchange`（能量转化/等价交换，默认 **true**，与旧 `experimental.yml` 默认一致）
  二者类别为 `experimental`，在 WebUI 插件列表单独显示为「内置·实验性」，可运行时启停。
- **新增独立配置文件 `config/plugins.yml`**（根节点 `plugins`）：内置玩法模块、实验性插件、
  消息通道适配器的开关全部收敛到此处，带完整注释与分组；`application.yml` 中的 `built_in_plugins` 段移除。
- **旧配置自动迁移 + 只读兜底**：升级后首次启动把 `application.yml` 的 `built_in_plugins.*`
  与 `config/experimental.yml` 的 `experimental.*` 中**实际写过的**开关迁移写入 `plugins.yml`
  （老安装里被手工关掉的模块不会在升级后被悄悄打开）；旧键仍作为读取兜底，旧文件可自行清理。
- **读写与持久化**：行级编辑写盘（保留注释与排版）抽成 `YamlFileEditor`，`AppConfig` 与新配置共用；
  WebUI 启停写回 `plugins.yml`（键 `plugins.<插件名>`），并区分/展示开关来源（新文件 / 旧配置兜底 / 默认值）。
- **补齐宗门战门控**：`sect_war` 开关过去不生效，现由插件开关统一门控 ——
  REST `POST /game/sect/war/{id}`、WebSocket `sect_war`、指令 `/宗门 宣战` 三个入口停用时一律拒绝。
- **内置插件清单集中登记**：新增 `PluginCatalog`（24 玩法模块 + 2 实验性插件 + 4 消息通道），
  `Main` 与迁移逻辑、测试共用同一份清单，新增插件只需登记一次。
- 删除 `ExperimentalConfig` 与 `src/main/resources/experimental.yml`；`PluginContext`
  的实验性查询改为走内置插件门控（`isExperimentalFeatureEnabled`）。

#### 兼容性
- 老安装的开关选择不丢：旧键会被迁移，且在迁移前仍可读取（迁移只写新文件，不删旧键）。
- 一旦 `plugins.yml` 里存在同名键，即以其为准（WebUI 改动立即落在此文件）。
- 停用实验性插件即整体关闭该内容：相关指令 / 接口 / WebSocket 动作统一返回“实验性功能未开放”。

#### 验证
- 新增 `PluginSwitchesTest`（取值优先级 / 旧键兜底 / 首次迁移 / 写回保留注释）、
  `ExperimentalPluginGateTest`（宗门战停用被拒、开启放行；等价交换与宗门战互相独立）、
  `BuiltInModuleSetTest` 扩充（清单与模板键一一对应、模板默认值与插件默认值一致）。
- `mvn test` 全绿（12 个测试类 / 63 个用例）。

---

### 🔧 变更：QQ 官方绑定按 app_id 隔离（支持同机运行多个官方机器人）

---

#### 问题
- `config/adapters.json` 可以配置多个 QQ 官方机器人实例，但绑定表 `qq_official_bindings`
  只按 `openid` / `user_id` 建唯一键，没有机器人维度。
- QQ 官方下发的 openid 是**按 AppID 隔离**的：同一个真人在不同机器人（不同 AppID）下
  openid 不同。因此：
  - 在 A 机器人绑定的玩家，在 B 机器人里一律显示**未绑定**，指令回"请先注册或绑定账号"；
  - 在 B 机器人重新 `/绑定` 会失败（`该用户已绑定QQ官方 openid`，user_id 唯一键冲突），
    玩家在 B 机器人上**完全无法登录**。

#### 变更
- `qq_official_bindings` 新增 `app_id VARCHAR(64)` 列，唯一键改为
  `UNIQUE (app_id, openid)` + `UNIQUE (app_id, user_id)`：
  同一玩家可以在每个机器人上各绑定一次，在哪个机器人上就用哪个机器人的 openid。
- `QqOfficialBindingService` 增加 AppID 作用域（`new QqOfficialBindingService(appId)`）：
  查找优先精确命中本机器人，再回落到历史兼容行；`QqOfficialAdapter` 在
  `loadConfig()` 里按自身 `app_id` 构建该服务，指令的绑定解析、注册/绑定/解绑全部按机器人隔离。
- 升级前的历史行 `app_id` 置为空字符串：由于 openid 是具体值，这些行只会在 openid 完全相同的
  机器人上命中（即当年写入它的那个机器人），**老玩家不会被登出**；并且当历史绑定属于别的机器人时，
  不再阻塞玩家在本机器人上重新绑定（否则老玩家在新机器人上永远绑不上）。
- `/解绑` 只删本机器人的绑定（无本机器人记录时退化为按 openid 删除）。
- 迁移采用"建新表 → 拷数据 → 删旧表 → 改名"（SQLite 无法删除表内唯一约束），
  拷贝完成前不触碰旧表，失败时原表与数据完整保留；拷贝期间临时关闭外键校验以容忍历史脏数据。
- 连带修复：PVP 挑战通知过去用"任意一条"QQ 官方绑定发送 openid，跨机器人必然发送失败；
  现在按当前适配器的 `app_id` 取绑定（`QqOfficialAdapter#getAppId`）。

#### 兼容性
- 升级无需手工操作，启动时自动迁移；日志会打印
  `[DB] qq_official_bindings 已升级为按 app_id 隔离（迁移 N 条历史绑定…）`。
- 若迁移失败会打印 `[DB] qq_official_bindings 升级失败，已保留原表与数据: …`，此时旧结构照旧可用。

---

### 🔧 修复：QQ 官方机器人所有回复被平台去重拒收（玩家无法注册/绑定登录）

---

#### 问题
- 玩家在 QQ 官方机器人（群聊 @ 或私聊）里发指令，机器人**有响应但消息发不到玩家**，
  表现为"完全登录不了"：注册/绑定流程走不完，反复提示未注册/未绑定/密码错误。
- 实测日志（`log/server-*.log`）里每一次回复都失败：

  ```
  QQ API 调用失败 [/v2/groups/<group_openid>/messages] HTTP 400:
      {"message":"消息被去重，请检查请求msgseq","code":40054005}
  QQ API 调用失败 [/v2/users/<openid>/messages] HTTP 400: 同上
  ```

#### 根因
1. **被动回复从未携带 `msg_seq`**。QQ 官方要求被动回复带 `msg_id` + `msg_seq`，且
   `msg_seq` 需由**本端生成、针对同一 `msg_id` 递增**（平台按它去重）。
   原实现却把 `msg_seq` 取自**入站事件**的 `msg_seq`/`seq` 字段——入站事件从来没有该字段，
   因此恒为 0，`if (last.msgSeq > 0)` 永远不成立，报文里只剩 `msg_id`/`event_id`，
   被平台判定为重复消息整批拒收。
2. **群聊发起的注册，结果发不出去**。`/注册` 可以在群里发起（提示到私聊发密码），
   但完成回执却发回群里，复用了那条 `/注册` 消息的被动回复上下文（同一 `msg_id`/`msg_seq`），
   同样被去重拒绝，玩家永远等不到注册结果；还会把账号信息暴露在群里。
3. **限流/锁定被伪装成"密码错误"**。凭据校验命中限流（同用户名 5 次/分钟）或账号锁定
   （连续 5 次错误锁 15 分钟）时直接返回 null，上层一律显示"用户名或密码错误"，
   玩家用正确密码也会一直看到"密码错误"，且不知道自己在被限流。

#### 修复
- `QqOfficialAdapter`：被动回复上下文改为**本端维护递增 `msg_seq`**（每个新 `msg_id` 从 1 重新计数），
  群聊/私聊（含按钮消息）统一携带 `msg_id` + `msg_seq`；超出被动回复窗口时明确告警，
  单聊窗口过期也补上告警（原先静默失败）。
- 同一 `msg_id` 的回复超过平台上限（5 次）时告警，提示等待玩家新消息。
- 注册完成、取消等向导回执改为**只在私聊回复**（密码本来也只能私聊输入），
  不再复用群聊里过期的被动回复上下文，也不再向群里泄露账号信息。
- 凭据校验区分「用户名或密码错误 / 尝试过于频繁 / 账号已临时锁定（并给出剩余分钟数）」，
  限流与锁定同时写入 WARN 日志便于排查。
- 私聊里发送非指令文本（例如玩家直接发用户名/密码想"登录"）不再静默丢弃，
  改为提示下一步操作（未绑定则引导 `/注册`、`/绑定`）。

#### 验证
- `mvn -o -DskipTests compile` 通过。
- 运行期自检：发一条 QQ 指令后，日志中 `[SEND]` 报文应出现 `"msg_seq":1`，
  且不再出现 `40054005`；群聊/私聊均应收到回复。

---

### 🔧 修复：MOTD 加入重定向改为"透传直达"（不再依赖客户端版本）

---

#### 问题
- 原实现依赖官方 server transfer（登录时踢出 `multiplayer.disconnect.transfer`）。
- 实测该机制只在部分 1.20.5+ 原版客户端上生效；很多客户端（旧版本/非原版/部分 1.20.5+）
  会把 `multiplayer.disconnect.transfer` **原样显示为断开提示**，不会跳转，导致无法进入
  目标服务器（如带 AuthMe 登录界面的服务器）。

#### 修复
- 配置 `motd.redirect_server` 后，**任意版本**的客户端点"加入服务器"，本服务都会建立
  **TCP 透传隧道**：把客户端原始握手+登录包原样转发给目标服务器，之后双向原样搬运字节流，
  效果等同玩家直接连接目标服务器地址（类似域名/反代直达），不再有"转移"环节：
  - 不再出现 `multiplayer.disconnect.transfer` 原文或任何断开提示；
  - 目标服务器上的 AuthMe 登录界面、正版/离线加密等流程全部由客户端与目标服务器自行完成，
    本层完全透明。
- 目标服务器不可达时给出友好踢出提示；`redirect_server` 误指向本 MOTD 自身时防呆拦截。
- 注意：透传后目标服务器看到的是本服务所在机器的 IP（如同反代/跳板入口）。
- 说明：`redirect_delay_seconds` 仅作为 `join_message` 中 `{delay}` 占位符的数值。

---

---

## [V1.4.1-alpha3] — 宗门升级 + 宗主转让 + 宗门战

---

### 🏯 宗门系统大更新

#### 宗门升级
- `/宗门 levelup`（宗主专用）—— 消耗声望提升宗门等级
- 升级消耗公式：当前等级 × 1000 声望
- 最高等级：10 级
- 每级提升成员上限 +5（基础 20 人）
- 三级接入：REST `POST /api/game/sect/levelup` / WebSocket `sect_levelup`

#### 宗主转让
- `/宗门 transfer <玩家名>`（宗主专用）—— 将宗主之位传给宗门内任意成员
- 消耗 200 灵石，旧宗主降为「长老」
- 事务性操作：同时更新 sects 和 sect_members 表
- 三级接入：REST `POST /api/game/sect/transfer/{playerId}` / WebSocket `sect_transfer`

#### 宗门战
- `/宗门 war <宗门名>`（宗主专用）—— 向其他宗门宣战
- 消耗 1000 声望 + 300 灵石
- 双方各出 5 名最强弟子（按境界→等级排序），5 场 1v1 自动模拟战斗
- 战败方 -500 声望，战胜方 +500 声望
- 模拟战斗复用灵根系统：暴击/增伤/减伤/再生 全部生效
- 战报自动入库（`sect_wars` 表）
- 三级接入：REST `POST /api/game/sect/war/{sectId}` / WebSocket `sect_war`

#### 创宗门槛提高
- 境界要求：筑基期 → **金丹期**（realm ≥ 4）
- 灵石费用：100 → **500** 灵石

---

### 🗃 数据库

- 新增 `sect_wars` 表 — 宗门战记录

---

## [V1.4.1-alpha2] — 黑名单系统 + 自动禁言

---

### 🔒 黑名单系统

#### 后台管理
- 新增 `/admin/blacklist` API —— 查看黑名单列表
- 新增 `/admin/blacklist` POST —— 添加黑名单（支持 QQ号 + 原因）
- 新增 `/admin/blacklist/{qqNumber}` DELETE —— 移除黑名单
- 黑名单作用于对应账户绑定的 QQ 账号
- 新增权限码：`admin.blacklist.view`（查看）/ `admin.blacklist.manage`（管理）

#### OneBot 群组配置
- 新增 `/admin/onebot/groups` API —— 查看所有群组配置
- 新增 `/admin/onebot/groups/{groupId}/autoMute` —— 设置自动禁言开关
- 新增 `/admin/onebot/groups/{groupId}/muteDuration` —— 设置禁言天数（1-30天，默认29天）
- 默认不开启自动禁言，需单独为每个群组开启
- 新增权限码：`admin.onebot.group.config`

#### 自动禁言逻辑
- 当黑名单用户在群组发送消息时，自动检测机器人是否为管理员
- 如果机器人是管理员，对该用户执行指定天数的禁言
- 每12小时自动续期禁言，确保黑名单用户持续被封禁
- 从黑名单移除用户即可取消禁言

---

### 📁 新增文件

| 文件 | 描述 |
|------|------|
| `onebot/Blacklist.java` | 黑名单实体类（QQ号/用户ID/原因/封禁者/时间） |
| `onebot/BlacklistService.java` | 黑名单服务（查询/添加/移除） |
| `onebot/OneBotGroupConfig.java` | 群组配置实体（自动禁言开关/禁言天数） |
| `onebot/OneBotGroupConfigService.java` | 群组配置服务（查询/保存/删除） |

---

### 🗃 数据库

- 新增 `blacklist` 表 —— 黑名单记录
- 新增 `onebot_group_config` 表 —— OneBot 群组配置

---

### 🐛 修复

- 修复：按 Enter 键无法正常关闭程序（改用 BufferedReader.readLine()）

---

## [V1.4.1-alpha1] — 插件生成器 + GUI + 事件系统

---

### 🔧 插件生成器 PluginMaker（双模式）

#### 命令行模式
- 运行 `java -jar 服务端.jar --plugin-make` 启动交互式命令行向导
- 8 步参数配置：插件名 / 版本 / 作者 / 描述 / artifactId / groupId / 主类名 / 输出目录

#### GUI 模式（新增）
- 运行 `java -jar 服务端.jar --plugin-make-gui` 启动图形化插件制作工具
- **不会启动服务端**，完全独立的工具界面
- 现代 Swing 界面，三选项卡布局：①基础配置 / ②触发器管理 / ③预览生成
- 顶部工具栏：保存配置 / 加载配置 / 刷新预览 / 生成插件项目
- 支持 JSON 格式配置持久化，下次可直接加载

#### ① 基础配置模块（GUI）
- 插件信息表单：名称、版本号、作者、描述（多行文本）
- Java 包信息：groupId / artifactId / 主类名，自动推导包名与目录结构
- 输出目录选择器：支持浏览器目录选择
- 功能开关：示例命令 / 示例物品 / 事件系统 / 示例秘境

#### ② 触发器管理模块（GUI）
- **事件类型选择**：COMMAND / PLAYER_LOGIN / PLAYER_LOGOUT / ITEM_USED / 
  COMBAT_ENDED / EXPLORATION_START / EXPLORATION_END / SCHEDULED / 
  SERVER_READY / CUSTOM
- **触发条件设置**：支持简单格式 `key=value`，多条件逗号分隔
- **事件响应动作**：发送消息 / 给予灵石 / 给予物品 / 执行 Java 代码 / 仅记录日志
- **触发器启用/禁用**：表格中复选框一键切换
- **增删改查**：新增 / 编辑（双击行）/ 删除 / 上移 / 下移
- 自定义 key 输入框（当选择 CUSTOM 事件类型时激活）
- Java 代码编辑器（当动作类型为 RUN_JAVA 时激活）

#### ③ 预览与生成
- 实时显示配置摘要：插件信息 / 功能开关状态 / 触发器列表 / 预计生成文件列表
- 一键生成：输出到指定目录
- 成功后弹出提示，并在预览区显示完整文件清单和后续步骤

#### 事件系统（服务端扩展）
- 新增 `com.mtxgdn.plugin.event.PluginEvent` —— 事件对象（类型 + 数据 Map）
- 新增 `com.mtxgdn.plugin.event.PluginEventHandler` —— 事件处理器接口
- 新增 `com.mtxgdn.plugin.event.PluginEventManager` —— 单例事件总线
- 为 PluginContext 添加 `registerHandler()` / `registerCustomHandler()` / 
  `setHandlersEnabled()` / `fireEvent()` 方法
- 插件卸载时自动清理其注册的所有事件处理器

#### 模板与生成
- 5 个代码模板：`pom.xml` / `plugin.json` / `Main.java` / 
  `HelloCommand.java` / `DemoItem.java`
- CodeGenerator 根据配置动态生成 Triggers.java 和 Realm.java
- 自动处理 Java 包名到目录结构的转换
- 自动对用户输入的字符串进行 Java 字面量转义，防止生成非法源代码

#### 配置持久化
- `PluginConfig` 与 `TriggerConfig`：完整的配置模型
- `MiniJson`：轻量级零依赖 JSON 读写工具
- 支持保存 / 加载 `.plugin.json` 文件，可在团队间共享配置

---

### 📁 新增文件

| 文件 | 描述 |
|------|------|
| `plugin/event/PluginEvent.java` | 事件对象（类型 + 数据 + 构建器） |
| `plugin/event/PluginEventHandler.java` | 事件处理器接口 |
| `plugin/event/PluginEventManager.java` | 事件总线（注册 + 分发 + 清理） |
| `plugin/gui/PluginMakerGUI.java` | GUI 主窗口（三选项卡布局） |
| `plugin/gui/BasicConfigPanel.java` | 基础配置面板 |
| `plugin/gui/TriggerPanel.java` | 触发器管理面板（表格 + 编辑器对话框） |
| `plugin/gui/PluginConfig.java` | 插件配置模型（支持 JSON 序列化） |
| `plugin/gui/TriggerConfig.java` | 触发器配置模型 |
| `plugin/gui/CodeGenerator.java` | 代码生成器（读取模板 + 写入文件） |
| `plugin/gui/MiniJson.java` | 轻量级 JSON 解析与序列化工具 |
| `plugin/gui/CodeGeneratorTest.java` | 单元测试（验证生成流程） |
| `plugin-template/*.template` | 5 个代码模板文件 |

---

## [V1.4.0] — 插件系统发布

---

### 🔌 插件系统（核心特性）

#### 插件框架
- 服务端启动时自动扫描 `./plugins/` 目录下所有 `.jar` 文件
- 实现 `com.mtxgdn.plugin.Plugin` 接口即可成为插件
- 三阶段生命周期：`onLoad(PluginContext)` → `onEnable(PluginContext)` → `onDisable(PluginContext)`
- 自定义类加载器 `PluginClassLoader`，隔离插件依赖，避免版本冲突
- 插件元数据支持两种方式：`plugin.json` 或 `@PluginMeta` 注解（二选一）
- 服务器优雅关闭时自动回调所有插件的 `onDisable`

#### PluginContext（插件与服务端交互主入口）
- `registerCommand(Command)` —— 注册自定义命令
- `registerItem(Item)` —— 注册自定义物品
- `registerExplorationEvent(ExplorationEvent)` —— 注册探索事件
- `registerSecretRealm(SecretRealm)` —— 注册秘境
- `getPlayerService()` / `getItemService()` / `getEconomyService()` 等 —— 快捷访问所有核心服务
- `getLogger()` —— 插件专属日志记录器
- `getDataFolder()` —— 插件专属数据目录（`./plugins/{名字}/`）
- `loadConfig()` / `getResource()` —— 读取配置与内部资源

#### 启动日志
- 新增"正在初始化插件系统..."阶段，位于命令扫描之后、HTTP 路由启动之前
- 插件加载结果汇总输出"成功 N 个，失败 M 个"
- 插件启动失败时打印完整异常栈，便于插件开发者调试

#### 示例插件项目
- 新增 `examples/sample-plugin/` 完整示例项目
- 示例命令：`/你好` / `/hello`（问候并赠送 100 灵石）
- 示例物品：`示例插件:demo_talisman`（演示自定义物品注册）
- 附带完整 `pom.xml` 与 `plugin.json`，可直接作为插件开发起点

#### 开发文档
- 新增 `README_PLUGIN.md`，包含 15 个章节的完整开发指南
- 内容覆盖：生命周期详解 / 从零创建插件 / 命令、物品、事件、秘境注册示例 / 所有服务 API 一览 / 命名规范 / 调试方法 / 常见问题解答

---

### 📁 新增文件

| 文件 | 描述 |
|------|------|
| `plugin/Plugin.java` | 插件接口（生命周期） |
| `plugin/PluginInfo.java` | 插件元数据封装 |
| `plugin/PluginMeta.java` | `@PluginMeta` 注解（替代 plugin.json） |
| `plugin/PluginContext.java` | 插件运行上下文（注册入口 + 服务访问） |
| `plugin/PluginClassLoader.java` | 插件专用类加载器（隔离依赖） |
| `plugin/PluginManager.java` | 插件管理器（扫描/加载/启用/停用） |
| `examples/sample-plugin/pom.xml` | 示例插件 Maven 配置 |
| `examples/sample-plugin/plugin.json` | 示例插件元数据 |
| `examples/sample-plugin/.../MyPlugin.java` | 示例插件主类 |
| `examples/sample-plugin/.../HelloCommand.java` | 示例命令 `/你好` |
| `examples/sample-plugin/.../DemoItem.java` | 示例物品 |
| `README_PLUGIN.md` | 完整插件开发文档 |

---

## [V1.3.0]

---

### ⚔ 战斗系统重构

#### 切磋确认机制
- `/挑战` 不再直接进入战斗，改为向对方发起切磋请求（30秒超时）
- 被挑战者通过私聊收到通知，回复 `/接受` 迎战或 `/拒绝` 回避
- 新增 `PendingChallenge` 机制，防止重复挑战
- 旧接口 `pvpChallenge()` 保留为兼容，WebSocket/REST 不受影响

#### 叙事化战报
- 战斗输出从"数值堆砌"改为小说风叙事：`张三 猛攻使出「火龙术 Lv.3」，暴击！——李四 受到 87 点伤害`
- 每回合标注 `-- 第X回合 --`，结尾 `🏆 胜者名 技高一筹！`
- 战报自动分段发送（每段不超过5行），避免一条消息过长

#### 战前策略预设
- 新增 `/战斗策略 <猛攻|均衡|防守>` 指令
- 猛攻：优先选择伤害最高的技能
- 防守：保留 50% MP，稳扎稳打
- 均衡：默认行为
- 发起挑战时显示双方战术

#### 其他
- Player 实体新增 `battleStrategy` 字段
- 数据库自动迁移兼容旧表

---

### 💰 经济系统重构

#### 灵石商店
- `/商店` 浏览，`/商店 <编号>` 购买
- 8 种消耗品上架（回血丹/回蓝丹/强化石/渡劫丹 等）
- 灵石售价定价，成为灵石核心回收渠道

#### 物品回收
- `/回收 <物品名> [数量]` 将无用物品回收为灵石
- 回收价为基础价的 30%

#### 灵石修炼加速
- `/修炼加速 <灵石数量>` 燃烧灵石为修炼加速
- 100 灵石 = 1 小时 ×1.5 倍收益，直接结算经验

#### 签到系统
- `/签到` 每日领取灵石奖励
- 7 天循环递进：50→80→120→160→200→260→350 灵石
- 断签重置天数，连续签到奖励递增

#### 兑换所
- `/兑换 金币 <数量>` 金币→灵石（10：1）
- `/兑换 灵石 <数量>` 灵石→金币（1：5）
- 不对称汇率鼓励灵石使用

#### 竞拍行
- `/拍卖` 浏览竞拍列表（实时竞价/剩余时间）
- `/拍卖 出售 <物品> <数量> <起价> [小时]` 上架竞价
- `/拍卖 出价 <编号> <价格>` 竞价（自动退还前一位出价人）
- `/拍卖 我的` 查看自己的拍卖
- 7% 手续费，到期自动结算：无人出价退回物品，有人出价卖方收 93% 买方收物品

#### 灵庄（银行）
- `/灵庄` 查看账户总览
- `/灵庄 存入 <活期|7天|30天|90天> <数量>` 存入灵石
- `/灵庄 取出 <编号>` 取款（活期日利 0.5% 复利，定期到期后结息）
- 定期利率：7天 3% / 30天 10% / 90天 25%
- 定期提前取出损失全部利息
- 起存 100 灵石

#### 经济面板（管理员）
- `/经济` 查看全服灵石总量、金币总量、人均灵石、24h 交易量

---

### 🗃 数据库

- 新增 `player_bank` 表 — 灵庄存款记录
- 新增 `player_economy` 表 — 签到/经济统计
- 新增 `auction_listings` / `auction_bids` 表 — 竞拍行
- `players` 表新增 `battle_strategy` 列（自动迁移）

---

### 📁 新增文件

| 文件 | 描述 |
|------|------|
| `EconomyService.java` | 经济总控（签到/回收/商店/加速/兑换/竞拍/灵庄/面板） |
| `BattleTacticCommand.java` | `/战斗策略` |
| `SignInCommand.java` | `/签到` |
| `ShopCommand.java` | `/商店` |
| `RecycleCommand.java` | `/回收` |
| `CultivateBoostCommand.java` | `/修炼加速` |
| `ExchangeCommand.java` | `/兑换` |
| `AuctionCommand.java` | `/拍卖` |
| `BankCommand.java` | `/灵庄` |
| `EconomyStatsCommand.java` | `/经济`（管理面板） |
