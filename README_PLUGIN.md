# 修仙游戏 · 插件开发文档

## 一、概述

本项目自带**插件系统**，服务端会在启动时自动扫描 `./plugins` 目录下所有的 `.jar` 文件，
并加载实现了 `com.mtxgdn.plugin.Plugin` 接口的插件。插件开发者可通过插件系统：

- ✅ **注册自定义命令** —— 在 OneBot 聊天中响应新的指令（如 `/你好`、/抽卡`）
- ✅ **注册自定义物品** —— 往物品注册表添加新的道具、丹药、法宝
- ✅ **注册探索事件** —— 丰富游历玩法中可能触发的随机事件
- ✅ **注册秘境** —— 新增专属副本玩法
- ✅ **访问所有服务端服务** —— 经济、角色、技能、社交等服务均对外开放

核心包路径：`com.mtxgdn.plugin`（包含在服务端主项目中，插件只需依赖服务端 jar）。

---

## 二、插件生命周期

每个插件都需要实现 `Plugin` 接口，该接口有三个默认空实现的回调：

```text
onLoad()   ← 加载阶段：准备数据、读取配置（还没有注册任何内容）
   ↓
onEnable() ← 启用阶段：在此注册命令、物品、事件、秘境（核心）
   ↓
  服务器运行中 ...
   ↓
onDisable()← 停用阶段：释放资源、保存持久数据
```

插件**不应该在构造函数**中做耗时操作，全部放在生命周期方法里。

---

## 三、创建你的第一个插件

### 3.1 目录结构

推荐使用 Maven 构建，目录结构如下（可直接参考 `examples/sample-plugin`）：

```
my-plugin/
├── pom.xml
├── plugin.json
└── src/
    └── main/
        └── java/
            └── com/
                └── yourname/
                    └── myplugin/
                        ├── MyPlugin.java
                        ├── command/
                        │   └── XxxCommand.java
                        └── item/
                            └── XxxItem.java
```

### 3.2 plugin.json（插件元数据）

放在 jar 根目录（或 `META-INF/plugin.json`），结构：

```json
{
  "name": "我的插件",
  "version": "1.0.0",
  "author": "你的名字",
  "description": "一句话描述插件做什么",
  "main": "com.yourname.myplugin.MyPlugin"
}
```

如果**没有**这个文件，服务端会自动扫描 jar 中所有实现 `Plugin` 接口的类，
并且会尝试读取 `@PluginMeta` 注解作为元数据。

### 3.3 用注解声明元数据（可选）

也可以完全不用 `plugin.json`，直接使用注解：

```java
import com.mtxgdn.plugin.Plugin;
import com.mtxgdn.plugin.PluginContext;
import com.mtxgdn.plugin.PluginMeta;

@PluginMeta(name = "我的插件", version = "1.0.0", author = "你的名字",
        description = "示例插件")
public class MyPlugin implements Plugin {

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("我的插件已启用！");
    }
}
```

### 3.4 依赖服务端

在你的 `pom.xml` 里需要把**服务端主 jar** 加入 classpath，两种方式：

**方式 A（推荐）：安装到本地 Maven 仓库**
```bash
mvn install:install-file -Dfile=你的服务端.jar -DgroupId=com.mtxgdn \
    -DartifactId=main -Dversion=1.0 -Dpackaging=jar
```
然后在插件 pom 中：
```xml
<dependency>
    <groupId>com.mtxgdn</groupId>
    <artifactId>main</artifactId>
    <version>1.0</version>
    <scope>provided</scope>
</dependency>
```

**方式 B：system 依赖本地 jar**
```xml
<dependency>
    <groupId>com.mtxgdn</groupId>
    <artifactId>main</artifactId>
    <version>1.0</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/../../target/main-*.jar</systemPath>
</dependency>
```

### 3.5 打包插件

```bash
mvn package
```

把生成的 `target/my-plugin-1.0.0.jar` 复制到服务端的
`./plugins/` 目录（若不存在会在首次启动时自动创建）。

---

## 四、插件上下文（PluginContext）

`PluginContext` 是插件与服务端交互的唯一入口。

```java
@Override
public void onEnable(PluginContext context) {
    // 1) 获取插件元数据
    PluginInfo info = context.getInfo();
    context.getLogger().info("加载插件: " + info.getName() + " v" + info.getVersion());

    // 2) 获取插件专属数据目录（./plugins/我的插件/）
    java.io.File dataFolder = context.getDataFolder();

    // 3) 读取配置文件
    Properties props = context.loadConfig("config.properties");

    // 4) 访问服务端服务（完整列表见下文）
    long playerId = ...;
    ServiceRegistry.getEconomyService().getBankInfo(playerId);
}
```

---

## 五、注册命令（Command）

### 5.1 编写命令类

```java
package com.yourname.myplugin.command;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

/**
 * 玩家输入 /抽奖 或 /lottery 时会触发此命令。
 */
public class LotteryCommand extends Command {

    public LotteryCommand() {
        super(
            new String[]{"抽奖", "lottery"},   // 命令别名（小写匹配）
            "抽奖送灵石",                        // 描述（用于帮助信息）
            "/抽奖",                             // 用法
            "经济"                               // 分类（与服务端保持一致：账号/我的角色/修炼/战斗/背包/探索/坊市/宗门/经济/社交/管理）
        );
    }

    @Override
    public void execute(CommandContext ctx) {
        // 基础框架：绑定校验 + 玩家校验
        Long userId = ctx.requireBinding();
        if (userId == null) return;            // 未绑定 QQ 时已自动回复提示

        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;                 // 未创建角色时自动回复提示

        // 业务逻辑：随机给 1~100 灵石
        long reward = (long) (Math.random() * 100) + 1;
        ServiceRegistry.getItemService().addSpiritStones(p.getId(), reward);

        ctx.reply("🎉 道友抽到 " + reward + " 灵石！");
    }
}
```

### 5.2 注册到服务端

```java
// 在 onEnable() 中：
context.registerCommand(new LotteryCommand());
```

### 5.3 权限控制（可选）

构造时传入第 5 个参数 —— 权限码：
```java
super(new String[]{"admincmd"}, "管理指令", "/admincmd", "管理", "game.admin.something");
```
当玩家没有该权限时会被拒绝。

### 5.4 子命令（进阶）

复杂指令可以使用 `registerSub` 注册子命令：

```java
public class SectCommand extends Command {

    public SectCommand() {
        super(new String[]{"宗门", "sect"}, "宗门系统", "/宗门 [创建|加入|查看]", "宗门");

        // 注册子命令处理器
        registerSub("创建", (ctx, p, parts) -> doCreate(ctx, p, parts));
        registerSub("加入", (ctx, p, parts) -> doJoin(ctx, p, parts));
        registerSub("查看", (ctx, p, parts) -> doView(ctx, p, parts));
    }

    // 无参数时的默认行为（覆写 onDefault）
    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        ctx.reply("宗门系统\n用法: /宗门 创建|加入|查看");
    }
}
```

---

## 六、注册物品（Item）

### 6.1 创建物品类

```java
package com.yourname.myplugin.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

/**
 * 一颗来自插件的神秘丹药。
 */
public class MysticPill extends Item {

    public MysticPill() {
        super(
            "我的插件",            // 命名空间（强烈建议使用插件名，避免冲突）
            "mystic_pill",         // 物品 key
            ItemType.CONSUMABLE,   // 类型：CONSUMABLE / MATERIAL / EQUIPMENT / SKILL ...
            ItemRarity.EPIC,       // 稀有度：COMMON / UNCOMMON / RARE / EPIC / LEGENDARY
            1,                     // 最大堆叠
            1000,                  // 价格
            true,                  // 是否可交易
            5,                     // 所需境界
            EmptyEffect.INSTANCE   // 物品效果（可自定义 ItemEffect）
        );
    }

    @Override
    public String use(long playerId) {
        // 这里写玩家"使用"物品时的逻辑
        return "✨ 你服下了神秘丹药，感到功力大涨！";
    }
}
```

### 6.2 自定义物品效果

如果你希望物品有复杂的战斗/数值效果，实现 `ItemEffect` 接口：

```java
import com.mtxgdn.game.item.ItemEffect;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class HealEffect extends ItemEffect {
    @Override
    public String execute(long playerId, PlayerService ps, ItemService is) {
        PlayerInfo p = ps.getPlayerByUserId(playerId);
        if (p != null) {
            p.setHp(p.getMaxHp());
            return "💚 生命已完全恢复！";
        }
        return "使用失败";
    }
}
```

### 6.3 注册物品

```java
// 在 onEnable() 中：
context.registerItem(new MysticPill());
```

---

## 七、注册探索事件（ExplorationEvent）

```java
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

/**
 * 一个示例探索事件：神秘仙人赠送礼物
 */
public class ImmortalEvent extends ExplorationEvent {

    public ImmortalEvent() {
        super("我的插件", "immortal_gift", 5);  // 最后一个参数是权重（越大越容易触发）
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                        ItemService itemService, Random random,
                        ExplorationResult result, List<String> log) {
        // 给玩家 500 灵石
        itemService.addSpiritStones(player.getId(), 500);
        log.add("⚡ 你遇到了一位云游仙人，他笑着赠送了 500 灵石！");
    }
}
```

注册：
```java
context.registerExplorationEvent(new ImmortalEvent());
```

---

## 八、注册秘境（SecretRealm）

```java
import com.mtxgdn.game.secretrealm.SecretRealm;

public class DragonLairRealm extends SecretRealm {

    public DragonLairRealm() {
        super("我的插件",   // 命名空间
              "dragon_lair", // key
              10,            // 需要境界
              3600000L       // 冷却时间（毫秒，此处为 1 小时）
        );
    }
}
```

注册：
```java
context.registerSecretRealm(new DragonLairRealm());
```

---

## 九、服务端服务一览

通过 `ServiceRegistry` 可访问全部核心服务（也可通过 `PluginContext` 的快捷方法）：

| 服务 | 获取方式 | 主要用途 |
|------|---------|---------|
| `PlayerService` | `context.getPlayerService()` | 玩家信息、属性、状态管理 |
| `ItemService` | `context.getItemService()` | 物品发放、灵石管理、背包操作 |
| `EconomyService` | `context.getEconomyService()` | 银行存取、利息、交易 |
| `SkillService` | `context.getSkillService()` | 技能学习、升级 |
| `CombatService` | `context.getCombatService()` | 战斗计算、PVP/PVE |
| `DailyService` | `context.getDailyService()` | 每日签到、晨修 |
| `ExplorationService` | `context.getExplorationService()` | 游历/探索 |
| `SecretRealmService` | `context.getSecretRealmService()` | 秘境副本 |
| `TechniqueService` | `context.getTechniqueService()` | 功法系统 |
| `CraftingService` | `context.getCraftingService()` | 合成/炼制 |
| `EnhanceService` | `context.getEnhanceService()` | 装备强化 |
| `ChatService` | `context.getChatService()` | 聊天消息 |
| `FriendService` | `context.getFriendService()` | 好友系统 |
| `HeartDemonService` | `context.getHeartDemonService()` | 心魔系统 |
| `TradeService` | `context.getTradeService()` | 市场交易 |
| `SectService` | `context.getSectService()` | 宗门系统 |
| `RealmService` | `context.getRealmService()` | 境界突破 |
| `GuideService` | `context.getGuideService()` | 新手引导 |

---

## 十、日志与资源

插件**不应该**直接使用 `System.out.println`，请使用上下文提供的 logger：

```java
context.getLogger().info("普通信息");
context.getLogger().warn("警告信息");
context.getLogger().error("错误信息", throwable);  // 带异常栈
```

读取 jar 内部资源（例如你自己写的配置、贴图路径等）：

```java
try (InputStream is = context.getResource("my_config.json")) {
    // ...
}
```

---

## 十一、命名规范与避免冲突

| 内容 | 推荐做法 | 示例 |
|------|---------|------|
| 命令别名 | 中文优先，附一个英文别名 | `抽奖`、`lottery` |
| 物品命名空间 | 使用插件名 | `我的插件:mystic_pill` |
| 事件命名空间 | 使用插件名 | `我的插件:immortal_gift` |
| 秘境命名空间 | 使用插件名 | `我的插件:dragon_lair` |
| 插件 jar 名 | `<名字>-<版本>.jar` | `my-plugin-1.0.0.jar` |

---

## 十二、调试与日志

启动服务端时，你会在控制台看到如下日志：

```
正在初始化插件系统...
发现插件: 示例插件 v1.0.0 (作者: 开发者)
示例插件正在加载...
示例插件启用成功！
插件加载结果: 成功 1 个，失败 0 个
```

如果你的插件加载失败，会打印异常栈供你调试。

---

## 十三、插件代码骨架（最简模板）

最后，把最常用的三行代码贴在这里：

```java
package com.yourname.myplugin;

import com.mtxgdn.plugin.*;

@PluginMeta(name = "我的插件", version = "1.0.0", author = "你的名字")
public class MyPlugin implements Plugin {
    @Override
    public void onEnable(PluginContext context) {
        // 在此注册你的命令 / 物品 / 事件 / 秘境
        context.getLogger().info("我的插件已启用");
    }
}
```

然后：
1. `mvn package` 打包
2. 把 `target/*.jar` 复制到服务端的 `./plugins/`
3. 重启服务端即可生效 🎉

---

## 十四、完整示例

请参考项目根目录的 `examples/sample-plugin/`，里面包含了：
- 完整的 `pom.xml`
- `plugin.json`
- 示例命令 `HelloCommand`（`/你好` / `/hello`）
- 示例物品 `DemoItem`

可以直接复制该目录，修改包名和内容作为你的插件起点。

---

## 十五、常见问题

**Q: 我的插件放到 plugins 目录了，但没有被加载？**
- A: 检查插件的 `main` 类是否**在 jar 中实际存在**，且类名与 `plugin.json` 或注解中的名字完全一致。

**Q: 如何在插件中读取/写入文件？**
- A: 使用 `context.getDataFolder()` 获取插件专属目录（不会与其它插件冲突），所有读写都放在这里。

**Q: 我的物品/命令与其它插件冲突怎么办？**
- A: 为物品/事件使用**插件名作为命名空间**（例如 `我的插件:xxxxx`），这能有效避免冲突。

**Q: 插件能访问数据库吗？**
- A: 可以。通过 `ServiceRegistry` 提供的服务间接操作，或直接使用 `com.mtxgdn.db.DatabaseManager`。

---

*插件系统 v1.0 · 与服务端同时发布*
