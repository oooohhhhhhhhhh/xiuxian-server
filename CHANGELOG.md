# V1.4.1-alpha1 更新日志（插件生成器 + GUI + 事件系统）

---

## 🔧 插件生成器 PluginMaker（双模式）

### 命令行模式
- 运行 `java -jar 服务端.jar --plugin-make` 启动交互式命令行向导
- 8 步参数配置：插件名 / 版本 / 作者 / 描述 / artifactId / groupId / 主类名 / 输出目录

### GUI 模式（新增）
- 运行 `java -jar 服务端.jar --plugin-make-gui` 启动图形化插件制作工具
- **不会启动服务端**，完全独立的工具界面
- 现代 Swing 界面，三选项卡布局：①基础配置 / ②触发器管理 / ③预览生成
- 顶部工具栏：保存配置 / 加载配置 / 刷新预览 / 生成插件项目
- 支持 JSON 格式配置持久化，下次可直接加载

### ① 基础配置模块（GUI）
- 插件信息表单：名称、版本号、作者、描述（多行文本）
- Java 包信息：groupId / artifactId / 主类名，自动推导包名与目录结构
- 输出目录选择器：支持浏览器目录选择
- 功能开关：示例命令 / 示例物品 / 事件系统 / 示例秘境

### ② 触发器管理模块（GUI）
- **事件类型选择**：COMMAND / PLAYER_LOGIN / PLAYER_LOGOUT / ITEM_USED / 
  COMBAT_ENDED / EXPLORATION_START / EXPLORATION_END / SCHEDULED / 
  SERVER_READY / CUSTOM
- **触发条件设置**：支持简单格式 `key=value`，多条件逗号分隔
- **事件响应动作**：发送消息 / 给予灵石 / 给予物品 / 执行 Java 代码 / 仅记录日志
- **触发器启用/禁用**：表格中复选框一键切换
- **增删改查**：新增 / 编辑（双击行）/ 删除 / 上移 / 下移
- 自定义 key 输入框（当选择 CUSTOM 事件类型时激活）
- Java 代码编辑器（当动作类型为 RUN_JAVA 时激活）

### ③ 预览与生成
- 实时显示配置摘要：插件信息 / 功能开关状态 / 触发器列表 / 预计生成文件列表
- 一键生成：输出到指定目录
- 成功后弹出提示，并在预览区显示完整文件清单和后续步骤

### 事件系统（服务端扩展）
- 新增 `com.mtxgdn.plugin.event.PluginEvent` —— 事件对象（类型 + 数据 Map）
- 新增 `com.mtxgdn.plugin.event.PluginEventHandler` —— 事件处理器接口
- 新增 `com.mtxgdn.plugin.event.PluginEventManager` —— 单例事件总线
- 为 PluginContext 添加 `registerHandler()` / `registerCustomHandler()` / 
  `setHandlersEnabled()` / `fireEvent()` 方法
- 插件卸载时自动清理其注册的所有事件处理器

### 模板与生成
- 5 个代码模板：`pom.xml` / `plugin.json` / `Main.java` / 
  `HelloCommand.java` / `DemoItem.java`
- CodeGenerator 根据配置动态生成 Triggers.java 和 Realm.java
- 自动处理 Java 包名到目录结构的转换
- 自动对用户输入的字符串进行 Java 字面量转义，防止生成非法源代码

### 配置持久化
- `PluginConfig` 与 `TriggerConfig`：完整的配置模型
- `MiniJson`：轻量级零依赖 JSON 读写工具
- 支持保存 / 加载 `.plugin.json` 文件，可在团队间共享配置

---

## 📁 新增文件

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

# V1.4.0 更新日志（插件系统发布）

---

## 🔌 插件系统（核心特性）

### 插件框架
- 服务端启动时自动扫描 `./plugins/` 目录下所有 `.jar` 文件
- 实现 `com.mtxgdn.plugin.Plugin` 接口即可成为插件
- 三阶段生命周期：`onLoad(PluginContext)` → `onEnable(PluginContext)` → `onDisable(PluginContext)`
- 自定义类加载器 `PluginClassLoader`，隔离插件依赖，避免版本冲突
- 插件元数据支持两种方式：`plugin.json` 或 `@PluginMeta` 注解（二选一）
- 服务器优雅关闭时自动回调所有插件的 `onDisable`

### PluginContext（插件与服务端交互主入口）
- `registerCommand(Command)` —— 注册自定义命令
- `registerItem(Item)` —— 注册自定义物品
- `registerExplorationEvent(ExplorationEvent)` —— 注册探索事件
- `registerSecretRealm(SecretRealm)` —— 注册秘境
- `getPlayerService()` / `getItemService()` / `getEconomyService()` 等 —— 快捷访问所有核心服务
- `getLogger()` —— 插件专属日志记录器
- `getDataFolder()` —— 插件专属数据目录（`./plugins/{名字}/`）
- `loadConfig()` / `getResource()` —— 读取配置与内部资源

### 启动日志
- 新增"正在初始化插件系统..."阶段，位于命令扫描之后、HTTP 路由启动之前
- 插件加载结果汇总输出"成功 N 个，失败 M 个"
- 插件启动失败时打印完整异常栈，便于插件开发者调试

### 示例插件项目
- 新增 `examples/sample-plugin/` 完整示例项目
- 示例命令：`/你好` / `/hello`（问候并赠送 100 灵石）
- 示例物品：`示例插件:demo_talisman`（演示自定义物品注册）
- 附带完整 `pom.xml` 与 `plugin.json`，可直接作为插件开发起点

### 开发文档
- 新增 `README_PLUGIN.md`，包含 15 个章节的完整开发指南
- 内容覆盖：生命周期详解 / 从零创建插件 / 命令、物品、事件、秘境注册示例 / 所有服务 API 一览 / 命名规范 / 调试方法 / 常见问题解答

---

## 📁 新增文件

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

# V1.3.0 更新日志

---

## ⚔ 战斗系统重构

### 切磋确认机制
- `/挑战` 不再直接进入战斗，改为向对方发起切磋请求（30秒超时）
- 被挑战者通过私聊收到通知，回复 `/接受` 迎战或 `/拒绝` 回避
- 新增 `PendingChallenge` 机制，防止重复挑战
- 旧接口 `pvpChallenge()` 保留为兼容，WebSocket/REST 不受影响

### 叙事化战报
- 战斗输出从"数值堆砌"改为小说风叙事：`张三 猛攻使出「火龙术 Lv.3」，暴击！——李四 受到 87 点伤害`
- 每回合标注 `-- 第X回合 --`，结尾 `🏆 胜者名 技高一筹！`
- 战报自动分段发送（每段不超过5行），避免一条消息过长

### 战前策略预设
- 新增 `/战斗策略 <猛攻|均衡|防守>` 指令
- 猛攻：优先选择伤害最高的技能
- 防守：保留 50% MP，稳扎稳打
- 均衡：默认行为
- 发起挑战时显示双方战术

### 其他
- Player 实体新增 `battleStrategy` 字段
- 数据库自动迁移兼容旧表

---

## 💰 经济系统重构

### 灵石商店
- `/商店` 浏览，`/商店 <编号>` 购买
- 8 种消耗品上架（回血丹/回蓝丹/强化石/渡劫丹 等）
- 灵石售价定价，成为灵石核心回收渠道

### 物品回收
- `/回收 <物品名> [数量]` 将无用物品回收为灵石
- 回收价为基础价的 30%

### 灵石修炼加速
- `/修炼加速 <灵石数量>` 燃烧灵石为修炼加速
- 100 灵石 = 1 小时 ×1.5 倍收益，直接结算经验

### 签到系统
- `/签到` 每日领取灵石奖励
- 7 天循环递进：50→80→120→160→200→260→350 灵石
- 断签重置天数，连续签到奖励递增

### 兑换所
- `/兑换 金币 <数量>` 金币→灵石（10：1）
- `/兑换 灵石 <数量>` 灵石→金币（1：5）
- 不对称汇率鼓励灵石使用

### 竞拍行
- `/拍卖` 浏览竞拍列表（实时竞价/剩余时间）
- `/拍卖 出售 <物品> <数量> <起价> [小时]` 上架竞价
- `/拍卖 出价 <编号> <价格>` 竞价（自动退还前一位出价人）
- `/拍卖 我的` 查看自己的拍卖
- 7% 手续费，到期自动结算：无人出价退回物品，有人出价卖方收 93% 买方收物品

### 灵庄（银行）
- `/灵庄` 查看账户总览
- `/灵庄 存入 <活期|7天|30天|90天> <数量>` 存入灵石
- `/灵庄 取出 <编号>` 取款（活期日利 0.5% 复利，定期到期后结息）
- 定期利率：7天 3% / 30天 10% / 90天 25%
- 定期提前取出损失全部利息
- 起存 100 灵石

### 经济面板（管理员）
- `/经济` 查看全服灵石总量、金币总量、人均灵石、24h 交易量

---

## 🗃 数据库

- 新增 `player_bank` 表 — 灵庄存款记录
- 新增 `player_economy` 表 — 签到/经济统计
- 新增 `auction_listings` / `auction_bids` 表 — 竞拍行
- `players` 表新增 `battle_strategy` 列（自动迁移）

---

## 📁 新增文件

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
