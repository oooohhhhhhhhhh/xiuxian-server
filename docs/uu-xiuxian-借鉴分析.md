# uu-xiuxian ↔ 本工作区（Java 修仙服务端）对比与可借鉴点

> 参照项目：`E:\xiuxiangame\别人的\uu-xiuxian`（koishi-plugin-uu-xiuxian，TypeScript / Koishi QQ 机器人插件，AGPL-3.0）
> 对比对象：本仓库 `E:\xiuxiangame\main`（Java 23 + Grizzly + SQLite/MySQL 的多人修仙 MUD 服务端）
> 结论定位：**两者不是同类竞品**。参照项目在「表现层」和「玩法原子设计」上更精巧；本工作区在「系统广度、工程完备度、多端接入」上全面碾压。因此可借鉴的重点是**具体机制与工程范式**，而非整体架构。

---

## 一、两句话定位

| | uu-xiuxian | 本工作区 |
|---|---|---|
| 形态 | 单体 Koishi 插件，25 个 ts 文件 / 3902 行 | 服务端 + Web 控制台 + 4 个消息通道，486 个 Java 文件 |
| 数据 | 全部 JSON 配置驱动（traditions/items/boss/work/guides…） | 物品=Java 类，灵根=enum，境界=单份 realm_config.json |
| 玩法广度 | 1 条主线 + 2 道统 + 蛊道/悬赏/花卡/秘境/宗门/轮回/银行 | 24 个稳定玩法模块 + 2 个实验插件（农场/坊市/拍卖/秘境/宗门战/荒野分片/阵法/洞府…） |
| 表现层 | **文字 → 图片卡片自动渲染**，昼夜双主题，自动分页 | 纯文字；有窗口截图机器人但那是操作 QQ 客户端，与渲染无关 |
| 数值层 | BigInt 十进制串，上限 10^60，全整数有理数倍率链 | `long experience` + `int` 属性 + `double` 倍率 |
| 工程完备度 | 21 个测试文件、启动期 fail-fast 校验 | 36 个测试文件、RBAC/OAuth2/安装向导/插件系统/热重载/多端适配器 |

---

## 二、A 级借鉴（低成本、高收益，建议优先做）

### A1. 文本 → 图片卡片的自动渲染管线 ★最值得抄

**参照实现**：`src/render.ts` 全文 + `src/index.ts:81-101`

做法：
- 业务只返回**纯文本**（`return "道友闭关 30 分钟，修为 +1234"`），渲染层统一把文本转成 900px 宽的古风卡片图；
- `messageTemplates()`（`render.ts:53-60`）自动分页：按每行 180 字切段、每段按 29 字折行计权、每页累计 23 单位即翻页 —— 业务完全不需要关心排版；
- 键值行自动排版：行内出现「：」且位置 <14 时渲染为左标签右值的两列布局（`render.ts:60`）；
- **昼夜双主题**：按当前小时 19:00–06:00 切暗色，`render.ts:18-19`；
- 超长数字每 24 位插换行，避免 61 位修为撑破版面（`render.ts:15`）；
- **三级降级**：页数 >8 直接转文字（`index.ts:65`）→ 渲染异常回退纯文本（`index.ts:93,184`）→ 先全部渲染完再发送，避免渲染中途失败只发出半截（`index.ts:66-71`）；
- 每条指令都自动带 `-t`（文字）/ `-i`（图片）两个开关（`index.ts:35-38`），用户可自选；
- 字体本地内嵌（Noto Sans CJK，OFL 授权）。

**为什么值得抄**：QQ/群聊场景下图片卡片的可读性和传播性远胜长文本，而本工作区**所有输出都是纯文本**，`/状态`、`/背包`、`/宗门`、`/坊市` 这类长输出在群里几乎不可读。

**落到本工作区的具体位置**：
- 唯一的输出收口已经存在：`CommandContext.reply(String)`（`src/main/java/com/mtxgdn/common/command/CommandContext.java:53`）。在 `OneBotCommandContext` / `OneBotClientCommandContext` / `MinecraftCommandContext` 的 `reply()` 实现里做一次拦截即可覆盖**全部** OneBot 指令，无需改任何一个业务指令；
- 新增 `com.mtxgdn.render` 包：`CardRenderer`（接口）+ `SvgCardRenderer`（拼 SVG 字符串 → Batik 栅格化为 PNG）+ `PlainTextRenderer`（兜底）。参照项目走的是 HTML + 无头浏览器（`uu-toimg`），**Java 侧不要引入浏览器内核**，直接拼 SVG 更轻、无额外依赖、启动快；
- 分页/主题/键值行排版逻辑可直接照搬 `render.ts:53-60` 的算法（约 40 行，纯字符串与数值运算，无框架耦合）。

**成本**：2–4 天。收益：所有长输出指令的观感一次性升级。

---

### A2. 通用「忙碌状态守卫」注册表

**参照实现**：`src/service.ts:123-141`（`idleGuards` / `activity` 注册表）+ `src/work.ts:31-33`

做法：主服务不认识任何具体玩法。各玩法在启动时向 service 注册一个 `idleGuard`（"我现在占着玩家，别人别动"）；任何需要"空闲"的动作统一问一句 `requireIdle(uid)`，被占用就报错并带出**具体阻塞原因**（"正在执行悬赏令"）。

**为什么值得抄**：本工作区现在有大量可并行触发的动作（闭关 `isCultivating`、游历、秘境、组队、荒野采集、农场、PVP），互斥规则散落在各 Service 里各自 `if`，例如 `EconomyService.java:217`、`PlayerService.java:377` 都是点对点判断，新增玩法就要回头改老代码。注册表把这条规则反转成"谁占用谁声明"。

**落到本工作区**：新增 `com.mtxgdn.game.service.ActivityGuard`（SPI），`List<ActivityGuard>` 由 `ServiceRegistry` 持有；`PlayerService` 增加 `requireIdle(playerId, action)`，返回值带 `blockedBy` 原因字符串直接拼进玩家提示。现有 `isCultivating` 收敛为第一个 guard 实现。

**成本**：1 天。

---

### A3. 体力（stamina）作为统一行动货币 + 懒结算恢复

**参照实现**：`src/service.ts:228-234`（`recover()`）+ `src/config.ts:15-18`

做法：
- 体力上限 360、每分钟恢复 1；魂力同理；
- 每个动作标价：秘境 6、悬赏 1、冒险 5、Boss 20、直接突破 12、护体突破 8（`config.ts:28-32,44-48`）；
- 恢复**不用定时器**：任何一次读/写玩家前跑 `recover()`，按 `now - recoveredAt` 的整分钟数补满并前移时间戳。离线天然结算，重启不丢。

**为什么值得抄**：本工作区**完全没有体力/魂力概念**（全库 grep `stamina|体力|魂力|soul` 只有 4 处命中，且全是注释或插件 GUI 的占位文案）。有意思的是 `NewbieGuideService.java:210` 的新手引导里已经写着「挑战消耗体力，失败会受伤」—— 这是**对玩家承诺了一个并不存在的机制**，属于现成的 bug。结果是玩家可以无限刷游历/秘境，日常留存完全靠 CD 硬卡。体力是这类游戏最标准、最便宜的内容闸门，而且它是"可积累"的（离线回来有一池子可以花），体验远好于纯 CD。

**落到本工作区**：`Player` 增 `stamina` / `maxStamina` / `staminaRecoveredAt` / `soul` 四列（`src/main/java/com/mtxgdn/entity/Player.java`），`PlayerService` 增加 `recover(player, now)` 在 `getPlayerById` 后调用；各玩法入口加扣体力校验。**注意**：`recover()` 在只读路径上算出来的结果**必须丢弃不落库**，否则读接口会变成写接口。

**成本**：2–3 天（含各玩法标价与文案）。

---

### A4. 突破失败增益（pity）+ 凝实（修为上限压制）✅ 已落地

**参照实现**：`breakthroughBonus = 0.2`（`config.ts:27`）、`player.rateBonus`（`gm.ts:62` 重置处可见）、`consolidate()`（`service.ts:363-376`）

做法一（pity）：突破失败后 `rateBonus` 累加，成功率 = 基础率 + 失败次数 × 增益，成功后清零。玩家不会连续被概率劝退。

做法二（凝实）：境界圆满后**不强制突破**，可以选择「凝实」压制修为进入极境，最多 3 次。这是给"卡在瓶颈"的玩家一条横向成长线。

**为什么值得抄**：本工作区 `RealmService` 的突破/渡劫是纯概率 + 失败扣经验重伤（`RealmService.java:241,260`），**没有任何 pity 机制**（全库无 `rateBonus|pity|连续` 相关实现）。这是玩家流失最集中的痛点。

**落地情况（已完成）**：
- **pity**：`players.breakthrough_pity` 存"连续失败次数"（存次数而非加成值，调 `breakthrough.pity_bonus` 后老数据自动跟着变）。每次渡劫失败 +1，成功率加 `次数 × 20` 个百分点，成功后清零，堆叠上限 `max_pity_stack`（默认 10）。
  **刻意偏离参照实现**：uu 用的是"基础成功率的 20%"（`Math.floor(rate * 0.2)`），但本项目高境界基础率只有 10%，那样要失败 40+ 次才见效，等于没做。改成固定百分点后，最坏情况 5 次也能摸到 95% 硬顶。
- **凝实**：`players.consolidation`（0..3）。前提是**下一次是大境界渡劫**且修为圆满；每层提升 8% 基础属性（**读取时乘算**，见 `PlayerService.getFinalAttack` 等，不写库），代价是成功率按层数乘算打折（每层 -10%）且失败损失放大（每层 +30%）；渡劫成功后层数清零，加成自然消散。
  **刻意偏离参照实现**：uu 的凝实收益是"闭关修为上限 ×1.5"（`closingExpUpperLimit`），而本项目**修为没有上限**，所以把收益换成基础属性。代价那两项（成功率打折、失败损失放大）与 uu 完全一致（`service.ts:557,615`）。
  uu 的凝实还会触发被动天劫（`passiveTribulation`），本项目没有闭关 tick 可挂，未实现。
- **可见性**（比 uu 更进一步）：渡劫的 `tribulationLog` 与 `successRateBreakdown` 一直算着却**从没显示过**，玩家失败一次只看到"随机劝退"。现在 `/突破` 会把渡劫过程、成功率逐项构成（基础/灵力/灵根/天劫属性/丹药/连败增益/凝实代价）全部摊开，并新增 `/突破 预览` 不掷骰地看下次真实成功率。
- **顺带修掉两个真 bug**：
  - `RealmService` 检查与扣除突破灵石时读的是 `mtxgdn:spirit_stone` —— 这是 `CurrencyEffect.SPIRIT_STONE_OLD_KEY`，早已废弃的旧版灵石键。结果是**所有需要灵石的突破都恒报"灵石不足"**（筑基以后基本卡死）。改为走 `ItemService.getTotalSpiritStonesInLowGrade` / `removeSpiritStones`，按四档灵石的下品等值汇总与扣除。
  - `ItemService.removeSpiritStonesFromAllGrades` 在某档灵石为 0 时仍调用 `removeItem(…, 0)`，被 `validateRemoveParams` 拒绝而抛异常。"手里只有中品灵石"的玩家扣款必炸，已加 `count <= 0 → continue`。
- 测试：`BreakthroughPityTest` 16 例，全套 297 个测试通过。

**成本**：pity 半天；凝实 1–2 天（实际约 1 天，含 16 例回归测试与两个顺带修掉的 bug）。

---

### A5. 境界引导（按境界区间给玩法提示）

**参照实现**：`src/guide.ts`（全 20 行）+ `assets/data/guides.json`（每个道统 12 条）

做法：数据是 `{minLevel, maxLevel, title, content}` 数组，按玩家当前境界命中区间返回对应提示，并在加载时做一次指令改名替换（`guide.ts:12-13`）。突破成功后自动把下一阶段引导拼在结果里（`index.ts:238`）。

**为什么值得抄**：本工作区的 `NewbieGuideService`（305 行）是**线性脚本 + 位标志**（`FLAG_MARKET`/`FLAG_PVP`/…），只覆盖开局前 10 步，中期玩家彻底失去引导。uu 的做法是"每个境界都有话说"，且新增内容只改 JSON。

**落到本工作区**：新增 `src/main/resources/game/guides.json` + `GuideService`，`BreakthroughCommand` / `RealmService` 突破成功后调用；`NewbieGuideService` 保留开局主线，二者串联（开局走脚本，之后走区间表）。

**成本**：1 天 + 内容编写。

---

## 三、B 级借鉴（中等成本，玩法向）

### B1. 悬赏令（work）系统
**参照**：`src/work.ts`、`src/work-data.ts`、`assets/data/work/*.json`（任务表 25KB + 奖励表 47KB）

设计要点：
- 两层数据：任务表 `{道统:{境界段:[{name,category,level,succeed,fail}]}}` + 奖励表 `{道统:{境界段:{难度:{award,needexp,time}}}}`，按 (path, segment, level) 拼接，缺奖励的任务自动过滤；
- 成功率**由修为比推导**而非写死：修为不足则 `rate = exp/needexp×100`，超出则 100% 且耗时按 1.5 步进降至 50% 下限（`work-data.ts:57-64`）—— 天然的"越级挑战更难、碾压更快"曲线；
- 刷新按 category 各抽 1 个必须凑满 3；每日前 N 次免费，之后 `cost = max(1, 基础价/(level+1))`；
- 终止罚 `min(终止费, 现有灵石)`，不会把玩家扣成负数；
- 与其它玩法用 A2 的 idle guard 互斥。

**价值**：这是"有成本的挂机任务"，介于挂机修炼和主动玩法之间，是本工作区目前缺的一层。数据表可以直接本地化复用（47KB 奖励表是现成的数值曲线参考）。

**成本**：3–5 天。

### B2. 传承花卡（impart）抽卡收集
**参照**：`src/impart.ts`

设计要点：
- 卡=花，属性白名单 11 键、值 0–100，加成按千分位定点累加（`impart.ts:45-49`）；
- **仅首次收集计入属性**，重复卡转成闭关时间 —— 解决了收集类玩法的"重复即垃圾"问题；
- 抽卡保底：基础 0.6%，73 抽后每抽 +6%，90 抽必出（`impart.ts:71`）；保底计数**与配额同事务落库**，重启不丢；
- 图片不建映射表，直接 `images/impart/{name}.png`（`impart.ts:216`）—— 省一张表，代价是改名断图。

**价值**：本工作区有称号（多品质）但没有"可收集 + 有保底"的抽卡线。花卡素材是 120 张 3MB 的花卉图，如果版权可用，是最省事的现成内容。

**成本**：2–3 天（不含素材适配）。

### B3. 蛊道（gu）作为第二条修炼路径
**参照**：`src/gu.ts` + `assets/data/gu/*.json`

设计要点：
- 蛊虫不是独立表，而是**道具属性**（`gu_tier`/`gu_series`/`gu_unique`/`refund`）；
- 蛊窍槽位 `guSlots` 默认 1，一次性道具开到 2，每轮回限一次（`service.ts:519-521`）；
- **反向卷轴绑定**：配置里不写卷轴 id，启动时扫全道具找 `attrs['xiuxian.recipe_id']` 且 `recipe_path==='gu'` 建立映射，**缺卷轴直接启动失败**（`gu.ts:64-76`）。炼丹复用同一机制只改 path（`alchemy.ts:70-79`）；
- 材料校验在产出合并**之前**做，防"零净配方"造物刷物（`gu.ts:102`）。

**价值**：「反向卷轴绑定」这条机制本身就跟蛊道无关，是本工作区 `CraftingService`（25KB，配方驱动）可以直接受益的健壮性改进 —— 现在配方与秘籍/卷轴的关联是手工维护的，容易漏。

**成本**：蛊道整体 5–8 天；反向绑定单独做半天。

### B4. PVP 归一化快照对战
**参照**：`src/sect.ts:262-275`（宗门战）

做法：双方实力差距大时，把两人统一**归一化到弱者的修为**生成 detached 快照，三局两胜。彻底消除"氪金碾压"，让 PVP 有博弈空间。

**价值**：本工作区 `CombatService`（69KB）+ `PvpCommand` 是纯数值对撞，高境界必胜。归一化快照是一个**独立可插拔的战斗模式**，可以作为"切磋"指令新增，不影响现有 PVP。

**注意**：Java 侧必须用**完全 detached 的只读快照**，绝不回写 —— 否则会污染真实属性。

**成本**：2 天。

### B5. 宗门缺席继位 + 竞争期 ✅ 已落地
**参照**：`src/sect.ts:64-77`

做法：宗主长期不活跃时，按贡献自动继位；与次席等级差 <6 才开启 7 天竞争期，期间可被挑战。宗门不会因为宗主弃坑而永久死锁。

**价值**：本工作区 `SectService` 64KB，功能远比参照项目全（申请/任命/捐献/仓库/宗门战/排行），但**宗主弃坑的治理**是常见运营事故，值得补。

**落地情况（已完成）**：
- 本工作区的死锁比参照项目更**字面**：`leaveSect()` 直接拒绝宗主退宗，宗主**既不能退位也不能退宗**，一旦不玩，宗门永远无人接手。
- 新增 `/宗门 退位`（卸任即降副宗主，随后按贡献选出继任者，退位后即可正常退宗）；
- 继位排序用「贡献 → 境界序位 → playerId」。**没有照抄参照项目的 `level` 字段** —— 本项目的 `players.level` 不随修炼推进，用它排序会得到一批并列的 1 级，改用 `realm * 3 + subRealm`；
- 竞争期门槛 6（≈ 两个大境界）、时长 7 天，两者都可配（`sect.contest_level_gap` / `sect.contest_days`）；
- 两段式挑战（挑战者发起 → 宗主应战）+ 三局两胜 + **双方战力按境界序位归一化**（参照项目的归一化思路，但本项目属性不派生自 exp，因此改为按比例压低强者，下限 0.5）；
- 「长期不活跃」的判定依据是本轮新增的 `players.last_active_time`（所有通道通用、按小时节流写入）。**没有复用 `last_offline_time`** —— 它只有 WebSocket 断线路径写，QQ/REST/MC 玩家永远是 0；
- **升级安全**：`last_active_time = 0` 表示"尚未观测到活跃"，判定只认 `> 0` 的行，避免升级瞬间全服宗主被批量换掉；
- 全部落库走**条件更新**（`WHERE ... AND leader_absent_since = <读到的值>`），指令与扫描任务并发时只生效一次；
- 测试：`SectSuccessionTest` 16 例，全套 262 个测试通过。

**成本**：1 天（实际约 1 天，含 16 例回归测试）。

### B6. 世界 Boss：按 scope 共享血量 + 伤害分红 + 超发熔断 ✅ 已落地
**参照**：`src/boss.ts`

做法：
- Boss 按 `[platform, user|group]` 的 scope 生成，4 小时 TTL，**共享血量直接落库**（`boss.ts:110-111`）—— 无定时器、无内存态，重启即恢复；
- 奖励按伤害比例分红而非击杀独占：`灵石 = boss.stones × 伤害/maxHp`，积分同理（`boss.ts:114-118`）；
- **超发熔断**：单次击杀修为 > 1e6 时改发 `1e6/(5+rand(6))`（`boss.ts:132`）—— 一行代码防经济爆炸。

**价值**：本工作区有 PVE Boss（`Monster.createBoss`）与团队 Boss（`CombatService.raidBossFight`，`BossForm` 按血量 66%/33% 三形态推进，比参照项目强得多），但**没有「全服共享血量」的世界 Boss**（全库无 `WorldBoss`/`world_boss`）。参照项目的模式恰好补这一块，且实现成本低：一张 `(scope, expireAt)` 唯一键的表 + 乐观锁扣血 + 按伤害比例分红。

**落地情况（已完成）**：
- 新增 `WorldBossService` + `WorldBoss` / `WorldBossDamage` 实体 + `world_bosses` / `world_boss_damage` 两张表 + `/世界boss` 指令 + 4 条 REST 路由；
- **共享血量真的落库**：`world_bosses.hp` 是全服唯一的那一条血。挑战时 `pveFight` 拿当前血量当对手血量，战后 `SELECT ... FOR UPDATE` 锁行再扣，因此并发挑战既不丢更新，也不会两个人都以为自己补了最后一刀；
- **无定时器**：到期重建走读取时的惰性判断（`WorldBoss.isExpired`），与 `FarmService` / `BuffService` 的惰性结算同一路数；
- **分红**：`灵石 = stone_budget × 本次伤害 / max_hp`，修为同理；补最后一刀的人额外拿 `exp_budget × kill_bonus_ratio`（默认 20%）。因为全服累计伤害不会超过 `max_hp`，**总发放天然不超过预算** —— 这是结构性保证，不靠事后核对；
- **熔断**照抄参照项目但加强了：灵石与修为**都**过一遍 `applyOverflowBreaker`，超阈值改发 `阈值/(5+rand(6))`，触发时战报会明示"已按超发熔断折算"；
- **没有照抄的两处**，都因为本项目与参照项目的底层不同：
  - 参照项目用体力（每次挑战 20 点）限制刷取频率，本项目**没有体力系统**，于是改用**挑战冷却**（默认 300 秒，记在伤害台账的 `last_hit_at` 上，并用条件更新抢占 —— 连点两下只会有一个真正进入战斗）。若不限次，一个高境界玩家可以连着把 Boss 打死，其他人连参与的份都没有；
  - 参照项目的 `scope` 是 `[platform, user|group]`（QQ 群里各有一批 Boss）。本项目的 `CommandContext` 不携带群号，且服务器只有一个世界，因此 `scope` 固定 `global`（全服共享同一批）。列保留着，将来要按群/按分线分组只需改配置。
- 境界门槛用 `realm * 3 + subRealm`（`Player.getRealmStage()`），**不用 `level`** —— 同 B5 的理由；
- 测试：`WorldBossTest` 18 例，全套 281 个测试通过。

**成本**：3 天（实际约 1 天，含 18 例回归测试）。

### B7. 银行懒结算利息
**参照**：`src/bank.ts:26,41-44`

做法：利息 = `saved × bps × ms / (10000 × 86400000)` 全整数运算；存取时按 `settledAt` 补息，无定时任务。

**价值**：本工作区 `BankCommand` / `EconomyService` 已有灵庄（活期 + 7/30/90 天定期），但利息是 **`double` 运算后强转 `long`**：`EconomyService.java:416` 用 `Math.pow(1 + rate, elapsedDays)` 算复利、`:387,420,481,491,497` 都是 `(long)(principal * rate)`。同一笔存款在「取款」和「预览」两条路径上用的是不同公式（`:416` 复利 vs `:491` 单利），会出现预览与实际不一致；`Math.pow` 的浮点误差在高本金下会被放大成整颗灵石的偏差。

**建议**：改成参照项目的整数式 —— `interest = principal × bps × elapsedMs / (10000 × 86400000)`，全 `long`/`BigInteger` 运算，并让预览与结算**共用同一个方法**。

**成本**：半天。

---

## 四、C 级借鉴（工程范式，长期收益）

### C1. 配置启动期 fail-fast 校验器 ★
**参照**：`src/data.ts:19-42`、`src/boss.ts:16-41`、`src/adventure.ts:25-43`、`src/work-data.ts:17-46`

做法：所有 JSON 配置在**启动加载时**做严格 schema 校验，任何一条不合法直接抛错、拒绝启动：
- `levels[].index` 必须等于数组下标；`threshold` 仅允许末级为 null；门槛必须**单调递增**；
- `root_type` 全局唯一、`id` 必须匹配 `^[a-z][a-z0-9_-]*$`；
- 数组长度上限（≤1000）防 DoS；
- 递归校验嵌套对象，**拒绝 `__proto__`/`constructor`/`prototype` 键**（`data.ts:12-18`）—— Java 侧对应的是 Gson 反序列化时的字段白名单。

**为什么值得抄**：本工作区有 `GameConfigLoader` / `WildernessMapConfig` / `MapDefaults` 等多个配置加载器，但校验强度不一致。配置错误如果延迟到玩家操作时才爆，就是线上事故；挪到启动期就是一次启动失败。

**落到本工作区**：抽一个 `ConfigValidator` 工具类 + 各加载器在 `load()` 末尾调用；用 `LegacySchemaUpgradeTest` 的风格补单测固化。

**成本**：1–2 天。

### C2. 物品从 Java 类迁移到 attrs 驱动（渐进式）
**参照**：`assets/data/items.json`（249 条道具，字段仅 `id/name/desc/item_icon/attrs`）

现状对比：
- 本工作区：**115 个 Java 文件**，每个物品一个类（如 `data/mtxgdn/item/SpiritStone.java` 全文 13 行，只是调 `super(...)` 传参数），名称/描述走 i18n，但**属性硬编码在构造函数里**；
- 参照项目：249 条道具一个 JSON，`attrs` 是扁平 `xiuxian.*` 字典（实测 64 个键），行为**完全由键驱动**：`xiuxian.slot`（白名单 6 值，`service.ts:470`）、`pill_type` 前缀 `breakthrough_`（`:507`）、`gu_slot_bonus`/`death_count_reset`/`hp_buff_permanent` 在 `use()` 里逐键消费（`:513-545`）。

**建议（不要一次性重构）**：
1. 新增 `JsonItemLoader`，支持从 `data/mtxgdn/items.json` 加载 `Item` 实例，与现有 Java 类**并存**（`ItemRegistry` 已按 `fullKey` 去重，天然支持）；
2. 新物品一律走 JSON；老物品保持不动；
3. 逐步把高频改动物品（丹药、材料、种子）迁到 JSON；
4. `attrs` 用 `Map<String,Object>` + 显式窄化，**不要**用 Gson 直接反序列化到 `Object` 后强转。

**收益**：新增/调数值不再需要重新编译打包，运营侧（WebUI「数据发放」）也能直接读同一份定义。

**成本**：1 天搭框架，后续按需迁移。

### C3. 有理数倍率链 `scale(value, factors[])`
**参照**：`src/numeric.ts:19-44`

做法：把 `value × a × b × c` 全部拆成整数分子/分母（正则解析 `"1.5"` / `"1e-3"`），只在最后一次做除法，四舍五入 `(num + den/2)/den`。

**为什么值得抄**：本工作区的倍率链很长 —— `PlayerService.java:307` 注释写着「基础值 × 境界倍率 × 季节 × 地点 × 灵根 × 功法 × 称号 × 天象 × 阵法」，9 个 `double` 连乘。浮点漂移 + 顺序依赖（先乘后除 vs 先除后乘结果不同）在这里是真实风险。

**建议**：不强求全改 BigInt，但可以引入 `Rational`（分子/分母 `long`）或统一用 `BigDecimal` + 明确的 `RoundingMode`，并把倍率链收敛到一个方法里。

**成本**：1–2 天（局部替换）。

### C4. 大数修为（BigInteger + 等宽排序列）
**参照**：`src/numeric.ts:3-18` + `src/service.ts:56-58`

做法：修为上限 10^60，存十进制字符串 `string(64)`；另存一列左补零 61 位的 `expOrder`，让排行榜在任意 SQL 驱动下**按字典序即数值序**（`numeric.ts:14-18`）。

**判断**：本工作区用 `long experience`，上限 9.2×10^18 —— 对现有境界表（末级门槛 ~10^12 量级）**完全够用**。这条**不建议现在做**，除非计划加入"无限轮回/指数膨胀"玩法。但 `expOrder` 补零排序列的思路，在本工作区排行榜做 `ORDER BY experience DESC` 时是可选的性能优化（加索引即可，不必改类型）。

### C5. GM 审计日志 + 单入口操作队列
**参照**：`src/gm.ts:26-38`

做法：所有 GM 指令走统一的 `modify()` 包装，自动写 `uu_xiuxian_gm_log`（operator/uid/action/detail/时间）+ 同时打日志，再执行变更。

**价值**：本工作区 `AdminResource`（187KB）+ 一堆 `admin/*Command` 已有 RBAC 和 `PlayerActionLogDAO`，值得核对 GM 操作是否**全部**留痕。参照项目的模式（一个 `modify()` 收口）比逐个命令自己写日志可靠。

**成本**：半天核对 + 补齐。

---

## 五、明确**不建议**借鉴的点（源码里的坑）

| 项 | 原因 |
|---|---|
| `assets/data/elements.json` | **死配置**：全 `src` 无任何文件加载它；`affinity` 只由 `root.element_bonus` 初始化，之后仅 GM 可改，**没有任何公式消费**。战斗里也完全不引用 `affinity`（`battle.ts` 全文无此字段）。想要元素系统就自己设计，别照抄。 |
| "新增道统只改 JSON" | 仅对**数值门槛**成立。`consolidate` 判 `path!=='huang'`（`service.ts:366`）、`rate()` 判 `path==='gu'`（`:556`）、`passiveTribulation` 判 `path!=='huang'`（`:378`）仍是硬编码分支。 |
| `items.json` 的 `skill_behavior` | 249 条道具**没有一条**配置了该键，`behavior` 恒为 1，导致 `battle.ts:148` 的 2/3/4 分支和神通 `buff_value` 全是死代码。 |
| `skills.json` 的 `exclusive_gu` / `learn_cost` | 加载时校验但**从未使用**；注释说装备到 `sec_buff` 槽，实际写的是 `equipment.kill`（`gu.ts:143`）。 |
| Boss 的 phase / 形态 / 血量分段 | **不存在**。参照项目的 Boss 是纯数值块 + 一次性采样的 buff（`boss.ts:83`），本工作区 `BossForm` 反而更完整。 |
| 秘境的"地图/层数" | **不存在**。`rifts.json` 每条只有 5 个字段 `{id,path,name,minLevel,expPerMinute,stonesPerMinute}`，事件全硬编码（`rift.ts:143-185`）。本工作区 `SecretRealm` 框架（10 个秘境 + Boss 战 + 组队副本）明显更强。 |
| 单进程串行化并发控制 | 参照项目靠 `operation()` 单入口 + `pending≥128` 背压（`service.ts:250-277`），隔离性**委托给外部 uu-inventory**，本仓库内无法核实。本工作区已有 `FOR UPDATE` / 事务（`MySQLDialect.java:167`、`FarmService.java:35-37`、`EconomyService.java:705-709`）且是多端多进程场景，**必须继续走数据库行锁**，不要退回进程内队列。 |
| 战斗无闪避/命中 | `Fighter` 里根本没有 `dodge`/`accuracy` 字段，随机性只有 0.95–1.05 浮动和暴击。本工作区的战斗系统（69KB）比它完整得多。 |
| 血蓝由修为派生 | 参照项目 `maxHp = max(100, exp/2)`（`battle.ts:122-123`）。本工作区是独立成长线 + 装备加成，**不要改**。 |

---

## 六、本工作区已经更强、无需借鉴的部分

- **地图**：15 地点大地图 + 荒野分片（`WildernessTerrain` 22KB / `WildernessChunkStore` 13KB）vs 参照项目**完全没有地图**；
- **农场**：4–12 块田 + 8 种作物 + 季节 vs 参照项目只有"灵田等级 + 收获时长"两行；
- **经济**：坊市 + 拍卖 + 成交价历史 + 挂单到期回填 vs 参照项目无市场；
- **多端**：REST / WebSocket / OneBot / QQ 官方 / Minecraft 五通道 + 适配器热插拔 vs 参照项目只有 Koishi 的 QQ；
- **运维**：Web 管理台（20 个前端模块）、安装向导、Docker/systemd、自动迁移、RBAC + OAuth2 vs 参照项目全靠插件配置项；
- **测试**：36 个测试文件，含并发测试（`TradeServiceConcurrencyTest` 17KB、`EconomyServiceConcurrencyTest` 15KB）、安全回归（`SecurityFixRegressionTest`、`AdminWritePermissionTest`、`EndpointAlignmentTest`）。

---

## 七、建议的落地顺序

| 阶段 | 内容 | 工期 | 收益 |
|---|---|---|---|
| ✅ 已完成 | **B5 宗门缺席继位 + 竞争期**（`SectService` + `/宗门 退位`、`/宗门 挑战`，17 例回归测试） | 1 天 | 解开「宗主弃坑 → 宗门永久死锁」的运营事故 |
| ✅ 已完成 | **B6 世界 Boss**（`WorldBossService` + `/世界boss`，全服共享血量 / 伤害分红 / 超发熔断，18 例回归测试） | 1 天 | 补上「全服共同参与」这一层玩法 |
| ✅ 已完成 | **A4 突破连败增益 + 凝实**（`RealmService` + `/突破 预览`、`/突破 凝实`，16 例回归测试） | 1 天 | 把"卡瓶颈"从概率劝退变成看得见进度的努力 |
| 第一步 | **A1 图片卡片渲染**（挂在 `CommandContext.reply()` 收口） | 2–4 天 | 所有长输出观感一次性升级，最高性价比 |
| 第二步 | **A5 境界引导 + A2 idle guard** | 2 天 | 直接改善中期留存 |
| 第三步 | **A3 体力/魂力 + B1 悬赏令** | 5–8 天 | 补齐"可积累的行动货币 + 挂机任务"这一层 |
| 第四步 | **C1 配置 fail-fast 校验 + C2 JsonItemLoader + C3 有理数倍率** | 3–4 天 | 工程债偿还，为后续快速加内容铺路 |
| 第五步 | B2 花卡抽卡 / B4 归一化 PVP / B7 银行整数利息 择机 | 各 2–3 天 | 运营向增量 |

---

## 附：关键源码索引（便于复核）

| 主题 | 位置 |
|---|---|
| 渲染与分页 | `src/render.ts:15-19, 53-60`；`src/index.ts:64-101` |
| 懒结算恢复 | `src/service.ts:228-249, 318-321` |
| 守卫注册表 | `src/service.ts:123-141`；`src/work.ts:31-33` |
| 大数数值 | `src/numeric.ts` 全文；`src/service.ts:56-58` |
| 配置校验 | `src/data.ts:19-42`；`src/boss.ts:16-41` |
| 突破/凝实 | `src/service.ts:344-400, 556-590`；`src/config.ts:26-33` |
| 悬赏令数值曲线 | `src/work-data.ts:21-64`；`src/work.ts:60-130` |
| 花卡保底 | `src/impart.ts:45-88, 216` |
| 蛊道/反向绑定 | `src/gu.ts:9-45, 64-76, 91-143` |
| 宗门继位/快照对战 | `src/sect.ts:64-77, 262-275` |
| Boss 分红/熔断 | `src/boss.ts:58-132` |
| 银行懒结算 | `src/bank.ts:26, 41-44` |
| GM 审计 | `src/gm.ts:26-38` |
