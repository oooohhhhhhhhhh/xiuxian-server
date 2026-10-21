# i18n 多语言改造设计

## 1. 现状与目标

### 现状

| 层 | 规模 | 状态 |
| --- | --- | --- |
| `util/LangManager.java` | 90 行，`Map<String,String>` 扁平 JSON，`static currentLang` | 只有 6 个调用方，覆盖物品名/秘境名/游历事件/灵根特效 |
| Java 硬编码中文 | **2933 处字面量 / 486 文件** | 完全不经过翻译层 |
| 数据文件中文 | `resources/game/realm_config.json`（每境界的 `name`/`description` 内联中文）、`game/wilderness_map.json` | 直接内联，无 key 概念 |
| 语言协商 | 不存在 | 无 `Accept-Language` 解析，无 `?lang`，`LangManager.setLanguage()` **全仓库无人调用** |
| 用户偏好 | 无字段 | `players` 表无 lang 列 |

结论：翻译管道是死的（只有一份 zh_cn，`currentLang` 恒定）。要做多语言，缺的不是"再加几种语言文件"，而是**协商链路 + 请求级语言绑定 + 消息出口的 key 化**三件事。

### 已验证的关键事实

1. `LangManager` 的 `get(key)` 缺 key 时**返回 key 本身**（`getOrDefault(key, defaultValue)`，defaultValue 又默认是 key）。这既是兜底也是坑：漏翻译会直接把 key 露给客户端。
2. `PluginContext.loadLang()` 会把插件 jar 内 `lang/<lang>.json` 合并进 `cache`，**只合并当前语言**，且 `merge` 后不清 key（无法"卸载插件"）。
3. `realm_config.json` 走 `GameConfigLoader`：先查 `config/game/realm_config.json`（外部），没有再回退 classpath 内置。**外部覆盖优先**这个模式已经确立，语言包沿用同一模式。
4. `DatabaseMigration.executeMigrations` 是幂等迁移模式（可重复执行），追加迁移就在 `buildSteps()` 里加一条并登记版本号。注意它只把"列已存在"当作预期错误，其余错误会记进 `schema_migrations` 并打 ERROR。
5. `players` 表已有 `battle_strategy VARCHAR(16) DEFAULT 'balanced'` 这种"玩家偏好单列"先例——语言列照此办理。

## 2. 语言模型

### 语言代码

用 **Java `Locale` 规范的 `language[_COUNTRY]` 小写下划线形式**，与现有 `zh_cn.json` 命名一致。

```
zh_cn  简体中文（默认 / 回退源）
en_us  英语（美国）
```

首个迭代只交付 zh_cn 与 en_us 的骨架；`MessageBundle` 支持任意数量，新增语言 = 加一个 JSON 文件 + 注册表里加一行。

### 回退链

每个语言一个 `ResourceBundle` 风格的回退层级，**不回退到"显示 key"**：

```
en_us → zh_cn → （字面量 key 原文，并打 WARN 日志）
```

即：en_us 缺某个 key 时用 zh_cn 的值顶上，仍缺才返回 key 本身并记录一条可检索的 `[i18n] missing key` 日志。这样"发一半翻译"是可用状态，而不是把 `game.xxx` 暴露给玩家。

## 3. 协商优先级

从高到低，第一个命中的生效：

| # | 来源 | 说明 |
| --- | --- | --- |
| 1 | 请求参数 `?lang=en_us` | 最短逃生口，调试与显式切换 |
| 2 | HTTP 头 `X-Lang: en_us` | 比 `Accept-Language` 更适合 API（不被浏览器伪装） |
| 3 | 头 `Accept-Language` | 标准浏览器通路，取首个可解析语言 |
| 4 | 玩家持久化偏好 `players.lang` | 已登录且有记录时 |
| 5 | 服务端默认 `i18n.default-language`（默认 `zh_cn`） | |

**写入偏好的优先级单独处理**：`?lang=` / `X-Lang` 只在本次请求生效（不落库），只有显式调用 `POST /game/i18n/lang` 才持久化到 `players.lang`。理由：防止一个旧客户端的残缺请求把用户偏好永久改成别的语言。

未知/非法语言代码**静默落到下一级**，不报错、不抛 400（语言协商失败不该让业务请求失败）。

## 4. 加载与覆写

沿用 `GameConfigLoader` 已确立的「外部优先」模式。优先级从高到低：

```
1. 插件 jar 内 lang/<lang>.json       （LangManager.merge 原有能力保留）
2. 外部 config/lang/<lang>.json       （运维可改，重启生效）
3. classpath data/mtxgdn/lang/<lang>.json  （jar 内置基线）
```

- 外部目录 `config/lang/` 在首次启动时**不自动释放**（避免把几百个 key 的中文文件倾倒给用户）；提供 `POST /admin/i18n/export?lang=` 导出当前语言包。
- 提供 `I18nManager.reload()` 与 `GET /admin/i18n/status`，列出每种语言加载了多少 key、哪些 key 缺失。
- **不提供热重载**：语言包变更需重启或调 `reload`，与 `GameConfigLoader` 行为一致。

## 5. 消息出口契约：键 + 值

`GameMessage` 增加 `key` 字段（可空），`message` 字段语义不变（仍是当前语言译后文本）。

```json
{
  "code": 0,
  "key": "realm.breakthrough.success",   // 新增，可为 null
  "message": "突破成功！",               // 仍是译文，客户端零改动可用
  "data": { ... }
}
```

规则：

1. `key` 只在**有对应 i18n key**的消息上出现；纯动态拼接的消息（如 `"获得 1234 灵石"`）`key` 为 `null`。
2. `key` 一律用**点分层级 + 小写下划线**，如 `sect.leave.success`、`trade.buy.insufficient_funds`。
3. 客户端可以只读 `message`（老客户端），也可以读 `key` 自行本地化（新客户端）。
4. `GameErrorCode` 增加 `key` 字段，构造器扩展为 `(code, key, zh_cn_default)`；既有 `(code, message)` 两参构造器保留，key 取 `"error." + name().toLowerCase()`。

WS 侧 `GameMessage.error(0, "error", GameErrorCode.XXX)` 自动带上 key，无需逐个改。

## 6. 请求上下文绑定

新增 `LanguageRequestFilter`（`@Provider`，`Priorities.AUTHENTICATION + 10`，排在 JwtAuthFilter 之后）：

```java
requestContext.setProperty("lang", Locale);
```

`I18n.target()` 读取当前请求的 `ContainerRequestContext`（ThreadLocal 由过滤器注入），service 层无需感知 HTTP：

```java
String msg = I18n.t("sect.leave.success");
String fmt = I18n.t("trade.buy.paid", amount, itemName);  // {} 占位符
```

**非 REST 通路**（OneBot / QQ / Minecraft 指令）没有 `ContainerRequestContext`，`I18n.target()` 回退到按 `senderId` 查绑定 → 玩家偏好 → 服务端默认。这使聊天指令在"未登录/未绑定"时也用默认语言（合理：他没偏好可依）。

`CommandContext` 增加 `protected Locale lang()`，子类各自覆写为从 `senderId` 解析（统一走一个 `LanguageResolver`，避免四个适配器各写一遍）。

## 7. 存量迁移策略

**不做全量机械替换。** 2933 处里绝大部分是调试信息、日志、内部异常文本，翻译它们没有价值且会把 zh_cn.json 撑到不可维护。分三类处理：

### A 类：必须迁移（用户可见 + 面向玩家）

- 三个适配器 `ctx.reply(...)` 的中文（OneBotWebSocketServer / QqOfficialAdapter / MinecraftPlayerAdapter / MinecraftAdapter）
- Service 层写进**返回给玩家的 message 字段**的中文（`EconomyService.signIn` 的 `result.put("message", ...)`、战斗日志 `BattleLogGenerator` 等）
- REST/WS 返回体里的 `message`
- `GameErrorCode` 的 message
- `reference` 数据里玩家可见的 `name`/`description`（realm_config、wilderness_map、秘境、物品）

### B 类：暂不迁移（运维/调试）

- `System.err.println` / `GameLogger` 日志文本
- 内部 `RuntimeException("...")` 消息
- 注释

### C 类：不可能迁移

- 玩家输入的内容、玩家自建宗门名、聊天消息（属于用户数据不是界面文本）

### 迁移的机械部分

写一个**抽取脚本**（Node 或 Python，最后一次性的）：扫 A 类文件的双引号中文串，生成 `key = 中文值` 草稿，人工按模块前缀归并去重后写入 zh_cn.json，再回头把源码里的字面量换成 `I18n.t("key")`。脚本只做搬运与冲突检测，**不自动决定 key 名**（key 命名必须人审，否则会出现 `msg1`/`msg2` 这种垃圾键）。

## 8. 与 `LangManager` 的关系

保留 `LangManager` 作为 `I18nManager` 的兼容门面，但标记 `@Deprecated`：

```java
public static String get(String key) { return I18n.t(key, /* en */ false); }
```

理由：`Item`/`SecretRealm`/`ExplorationEvent` 等实体类被广泛引用，一次全改收益低、风险高。新代码一律用 `I18n`，存量实体类在 A 类迁移时顺带改掉。

## 9. 测试影响与处置

基线：304 tests，0 failures，1 skipped（`Mc26LiveSmokeTest`）。

现有测试有 **1522 处**断言直接比对中文 message。迁移后这些断言会失败。处置原则：

1. **优先改断言为 key 比对**：`assertEquals("trade.buy.success", result.get("key"))`。这比断言译文稳（译文以后会变）。
2. 少数无法拿 key 的场景（直接拼字符串的结果）保留译文断言，但在注释里标明。
3. 新增测试：协商优先级、回退链、外部覆写、`reload`、并发切换互不串味（这是 `static currentLang` 现有设计的明确缺陷，必须回归覆盖）。

## 10. 交付顺序

| 阶段 | 内容 | 可独立验证 |
| --- | --- | --- |
| P1 | `I18nManager`/`I18n`/`Locale` 枚举 + 加载与回退 + 外部覆写 | 单测 |
| P2 | `GameMessage.key` + `GameErrorCode.key` + WS 接入 | 单测 + 现有测试仍绿 |
| P3 | `LanguageRequestFilter` + `I18n.target()` + `CommandContext.lang()` | 集成测试 |
| P4 | `/game/i18n/bundle`、`/game/i18n/lang`（读写偏好）+ DB 迁移 | 端点测试 |
| P5 | A 类存量迁移（先 service，再 command，再 REST，最后资源文件） | 逐批回归 |
| P6 | 测试断言改 key + 新增 i18n 测试 | 全量 304+ 绿 |

P1–P4 完成后即可交付"架构可用"，P5/P6 是填词工作，可分批合入。

## 11. 已知风险

| 风险 | 处置 |
| --- | --- |
| `static currentLang` 并发串味 | P3 起改为请求级/发送者级绑定；`LangManager.setLanguage` 保留但仅用于单测 |
| 漏翻译把 key 露给玩家 | 回退链（§2）+ 缺失 key WARN 日志 + `GET /admin/i18n/status` 可查 |
| 迁移中途服务不可用 | 每阶段独立提交、独立可回滚；`GameMessage.message` 始终有值，老客户端无感 |
| 插件合并污染 | `merge` 保持"只增不删"，插件停用时不清（与现有一致）；在 wiki 记录此限制 |
| `realm_config.json` 结构变更影响存档 | 只**新增** `nameKey`/`descKey` 字段，旧 `name`/`description` 保留作为 zh_cn 兜底；外部文件缺新字段时按旧字段处理 |

## 12. 明确不做

- 不做 plural / gender 规则（中文无，英文复数用 `{}count` + 数字后缀 key 手工处理，如 `item.count.one`/`item.count.other`）
- 不做日期/数字本地化格式化（如需要另立需求，用 `DateTimeFormatter.ofLocalized*`）
- 不做从右到左语言（ar 等）
- 不自动翻译（无机器翻译接入）
