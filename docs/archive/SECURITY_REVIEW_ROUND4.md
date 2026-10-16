# 修仙服务器 - 第四批次审查报告 + 玩法模块化重构

> 📦 **已归档**：本文档为历史审计产物，内容（版本号、文件路径、行号、"已修复"结论）不代表当前代码状态，
> 请勿作为开发依据。当前有效文档见项目根目录 README.md / README_PLUGIN.md / CHANGELOG.md。
> 归档索引：[docs/archive/README.md](README.md)

## 📋 概述

第四批次审查重点转向**架构层面**——评估玩法模块的耦合度，并完成**jar内玩法模块化**重构，将各玩法系统封装为可控制启用/禁用的内置插件。

---

## 🔍 第四批次审查发现

### 架构审查：模块耦合度分析

审查了 `ServiceRegistry`、`PluginContext`、`PluginManager` 及各 Service 间的依赖关系：

| 玩法模块 | 核心依赖 | 耦合度 | 是否适合内置插件 |
|---------|---------|--------|----------------|
| 探索系统 | PlayerService, ItemService | 中 | ✅ 适合 |
| 秘境系统 | PlayerService, ItemService | 中 | ✅ 适合 |
| 坊市交易 | PlayerService, ItemService | 中 | ✅ 适合 |
| 宗门系统 | PlayerService, ItemService, TradeService | 高 | ✅ 适合 |
| 农场系统 | PlayerService, ItemService | 低 | ✅ 适合 |
| 洞府系统 | PlayerService | 低 | ✅ 适合 |
| 聊天系统 | PlayerService | 低 | ✅ 适合 |
| 好友系统 | PlayerService | 低 | ✅ 适合 |
| 团队系统 | CombatService | 中 | ✅ 适合 |
| 地图系统 | PlayerService | 低 | ✅ 适合 |
| 称号系统 | PlayerService, TitleRegistry | 中 | ✅ 适合 |
| 炼丹锻造 | ItemService, RecipeRegistry | 中 | ✅ 适合 |

**结论**: 项目已具备完善的 ServiceRegistry 服务注册中心和 PluginContext 插件扩展接口，**天然支持模块化**。核心服务（玩家、物品、数据库、权限、JWT）保持在内核，各玩法系统封装为内置插件。

---

## 🛠️ 重构实施

### 新建文件

#### 1. `BuiltInPluginRegistry.java`（内置插件注册表核心）
- 统一管理所有 jar 内内置插件模块
- 通过 `built_in_plugins_yml` 配置启用/禁用
- 生命周期管理（onLoad/onEnable/onDisable）
- 状态查询、名称集合管理
- 单例模式

#### 2. `BuiltInPlugin.java`（内置插件基类）
- 扩展 `Plugin` 接口
- 强制实现 `getName()`、`getVersion()`
- 默认 onLoad/onEnable/onDisable 空实现

#### 3. `built_in_plugins.yml`（配置文件模板）
- 列出所有可配置的内置模块
- 注释说明配置方式
- 默认全部启用

#### 4. 6个内置插件实现（`plugin/builtin/` 目录）
- `ExplorationPlugin` - 探索系统
- `SecretRealmPlugin` - 秘境系统
- `TradePlugin` - 坊市交易
- `SectPlugin` - 宗门系统
- `FarmPlugin` - 农场系统
- `CavePlugin` - 洞府系统

### 修改文件

#### 5. `PluginContext.java`
- 新增 `createSharedContext()` 公开工厂方法
- 提供内置插件访问核心服务的统一入口

#### 6. `Main.java`（启动集成）
- 新增 `registerBuiltInPlugins()` 方法注册所有内置插件
- 启动流程中初始化 `BuiltInPluginRegistry`
- 关闭钩子中停用所有内置插件

---

## 🎯 重构设计

### 架构图
```
┌─────────────────────────────────────────────┐
│                 Main 启动流程                 │
│  JwtUtil → Database → Services → WebSocket  │
│                 │                           │
│                 ▼                           │
│  BuiltInPluginRegistry（内置插件注册表）      │
│    ├── ExplorationPlugin    (探索)          │
│    ├── SecretRealmPlugin    (秘境)          │
│    ├── TradePlugin          (交易)          │
│    ├── SectPlugin           (宗门)          │
│    ├── FarmPlugin           (农场)          │
│    └── CavePlugin           (洞府)          │
│                 │                           │
│                 ▼                           │
│        PluginContext（共享上下文）           │
│        ServiceRegistry（核心服务）           │
└─────────────────────────────────────────────┘
```

### 启用/禁用机制
通过 `config/application.yml` 配置控制：
```yaml
built_in_plugins:
  exploration: true   # 探索系统
  secret_realm: true  # 秘境系统
  trade: true         # 交易系统
  sect: true          # 宗门系统
  farm: true          # 农场系统
  cave: true          # 洞府系统
```
设为 `false` 即可禁用对应模块（重启后生效）。

---

## ✅ 重构成果

### 编译状态
```
BUILD SUCCESS ✅
编译文件数: 450（新增 9 个）
```

### 新增文件（9个）
1. `plugin/BuiltInPluginRegistry.java`
2. `plugin/BuiltInPlugin.java`
3. `resources/built_in_plugins.yml`
4. `plugin/builtin/ExplorationPlugin.java`
5. `plugin/builtin/SecretRealmPlugin.java`
6. `plugin/builtin/TradePlugin.java`
7. `plugin/builtin/SectPlugin.java`
8. `plugin/builtin/FarmPlugin.java`
9. `plugin/builtin/CavePlugin.java`

### 修改文件（2个）
1. `plugin/PluginContext.java` - 新增工厂方法
2. `Main.java` - 集成内置插件生命周期

---

## 📊 重构价值

### 模块化优势
- ✅ 玩法模块按需启用/禁用
- ✅ 降低模块间耦合
- ✅ 便于独立测试和扩展
- ✅ 支持未来拆分为外部插件
- ✅ 复用现有 PluginContext 生态

### 架构改进
- ✅ 核心内核保持精简
- ✅ 玩法扩展层独立
- ✅ 配置驱动模块开关
- ✅ 生命周期管理统一

---

**审查轮次**: 第4轮  
**重构内容**: jar内玩法模块化（内置插件）  
**编译状态**: ✅ BUILD SUCCESS  
**新增文件**: 9个  
**修改文件**: 2个  
**累计修复**: 13个安全问题 + 架构重构
