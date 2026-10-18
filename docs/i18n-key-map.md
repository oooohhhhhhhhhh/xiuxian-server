# i18n key 映射表（自动生成）

由 `tools/i18n/migrate-literals.js --write` 生成。
key 形如 `<模块>.h<8位内容哈希>`：用内容哈希作名字，是为了让
「改动一段无关文案」不会导致整批 key 漂移——已发布客户端的
语言包缓存才不会因此整体失效。

想改成语义化名字时，先改这张表再重跑即可（脚本对已存在的 key
一律跳过，不会覆盖人已经审过的译文）。

| key | 原文（zh_cn） | 出现位置 |
| --- | --- | --- |
| `admin.h10d4d6b8` | 权限不足，你只能查看自己的轨迹。\n使用 /轨迹 查看自己的操作记录。 | src/main/java/com/mtxgdn/onebot/command/admin/TraceCommand.java:48 |
| `admin.h16c07754` | 参数不足。用法: /能量管理 <玩家ID> <设置\|增加\|减少\|查看> [数值] | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:65 |
| `admin.h204cc940` | 权限不足：修改玩家能量需要 admin.users.manage 权限（仅超级管理员）。 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:92 |
| `admin.h3059219c` | 服务器正在重启... | src/main/java/com/mtxgdn/onebot/command/admin/RestartCommand.java:41 |
| `admin.h31437f0c` | 数值必须为整数。 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:113<br>src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:134<br>src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:155 |
| `admin.h32b669a4` | 测试失败: 未绑定QQ | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:125 |
| `admin.h3bcc1334` | 注意: 测试操作不会修改真实数据（事务已回滚） | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:120 |
| `admin.h3f4f055e` | 请先绑定账号。\n私聊使用 /bind | src/main/java/com/mtxgdn/onebot/command/admin/TraceCommand.java:25 |
| `admin.h41e3bb7c` | 参数不足。用法: /编辑玩家 <玩家ID> <属性>=<值> ... | src/main/java/com/mtxgdn/onebot/command/admin/EditPlayerCommand.java:41 |
| `admin.h55cf8430` | 用法: /发放 <玩家ID> 金币=<数量> 灵力=<数量> 灵石=<数量> [物品key]=<数量>\n示例: /发放 1 金币=1000 灵石=500 SpiritStone=50 | src/main/java/com/mtxgdn/onebot/command/admin/GiveCommand.java:37 |
| `admin.h5c19d9d2` | 正在检查并修复装备数据一致性，请稍候... | src/main/java/com/mtxgdn/onebot/command/admin/FixEquipmentCommand.java:27 |
| `admin.h61120d94` | 测试失败: 未找到玩家角色 | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:131 |
| `admin.h66f05890` | 参数不足。用法: /发放 <玩家ID> 金币=<数量> 灵力=<数量> 灵石=<数量> [物品key]=<数量> | src/main/java/com/mtxgdn/onebot/command/admin/GiveCommand.java:43 |
| `admin.h7a4c9c12` | 用法: /玩家详情 <玩家ID或角色名> | src/main/java/com/mtxgdn/onebot/command/admin/PlayerDetailCommand.java:36 |
| `admin.h85ed1b46` | 权限不足，无法查看该用户的操作轨迹。 | src/main/java/com/mtxgdn/onebot/command/admin/TraceCommand.java:71 |
| `admin.h8cce6bb4` | 服务器正在关闭... | src/main/java/com/mtxgdn/onebot/command/admin/ShutdownCommand.java:29 |
| `admin.h9149f304` | 暂无操作轨迹记录。 | src/main/java/com/mtxgdn/onebot/command/admin/TraceCommand.java:102 |
| `admin.h9ff0b1e0` | 装备数据修复完成！ | src/main/java/com/mtxgdn/onebot/command/admin/FixEquipmentCommand.java:32 |
| `admin.ha43d576a` | 权限不足，你无权使用此功能。 | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:37<br>src/main/java/com/mtxgdn/onebot/command/admin/EditPlayerCommand.java:27<br>src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:44 等 12 处 |
| `admin.ha5a93f5d` | 用法: /debug test <指令名> [参数]\n示例: /debug test status | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:101 |
| `admin.ha5fc6272` | 能量转化系统暂未开放，请在 config/plugins.yml 中将 experimental_energy_exchange 设为 true（或在 WebUI「插件管理」页启用该实验性插件）。 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:48 |
| `admin.haed2b682` | 玩家ID必须为数字。 | src/main/java/com/mtxgdn/onebot/command/admin/EditPlayerCommand.java:49<br>src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:73<br>src/main/java/com/mtxgdn/onebot/command/admin/GiveCommand.java:49 等 4 处 |
| `admin.hbcdb26c6` | 暂无日志。 | src/main/java/com/mtxgdn/onebot/command/admin/LogsCommand.java:51 |
| `admin.hbf23b840` | 减少量必须大于0。 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:159 |
| `admin.hd16507e2` | 能量值不能为负数。 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:117 |
| `admin.hd852a51e` | 请先绑定账号。 | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:33<br>src/main/java/com/mtxgdn/onebot/command/admin/EditPlayerCommand.java:23<br>src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:40 等 14 处 |
| `admin.hdbaa383c` | 指令测试完成（事务已回滚） | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:146 |
| `admin.hde258710` | 增加量必须大于0。 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:138 |
| `admin.hea8fd688` | 权限不足，仅 SUPER_ADMIN 可以执行此操作。 | src/main/java/com/mtxgdn/onebot/command/admin/RestartCommand.java:38<br>src/main/java/com/mtxgdn/onebot/command/admin/ShutdownCommand.java:26 |
| `cave.h8db42343` | 你还没有洞府。\n使用 /洞府 create [名称] 开辟洞府（需要金丹期+300灵石） | src/main/java/com/mtxgdn/onebot/command/cave/CaveCommand.java:177 |
| `combat.h04cb7f2a` | 用法: /学习 <技能名称或ID>\n先用 /技能 查看可用技能列表。 | src/main/java/com/mtxgdn/onebot/command/combat/LearnSkillCommand.java:23 |
| `combat.h22695b44` | 没有待接受的挑战 | src/main/java/com/mtxgdn/onebot/command/combat/PvpCommand.java:35 |
| `combat.h5ff9c1a2` | 用法: /技能 升级 <技能ID> | src/main/java/com/mtxgdn/onebot/command/combat/SkillsCommand.java:45 |
| `combat.h940932f1` | 你尚未学习该技能，无法升级 | src/main/java/com/mtxgdn/onebot/command/combat/SkillsCommand.java:60 |
| `combat.h96cde842` | 不能挑战自己。 | src/main/java/com/mtxgdn/onebot/command/combat/PvpCommand.java:66 |
| `combat.hc0c642ae` | 暂无可用技能。 | src/main/java/com/mtxgdn/onebot/command/combat/SkillsCommand.java:29 |
| `combat.hc411208c` | 技能ID无效，请输入数字 | src/main/java/com/mtxgdn/onebot/command/combat/SkillsCommand.java:53 |
| `crafting.h033e510c` | 配方ID无效 | src/main/java/com/mtxgdn/onebot/command/crafting/CraftCommand.java:125 |
| `crafting.h573a2304` | 用法: /合成 配方 <分类>\n分类: PILL(丹药) / EQUIPMENT(装备) / CONSUMABLE(消耗品) / SEED(种子) | src/main/java/com/mtxgdn/onebot/command/crafting/CraftCommand.java:95 |
| `crafting.h706b5630` | 用法: /合成 制造 <配方ID>\n先用 /合成 列表 查看配方 | src/main/java/com/mtxgdn/onebot/command/crafting/CraftCommand.java:123 |
| `cultivation.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/onebot/command/cultivation/ReincarnateCommand.java:46 |
| `cultivation.hcacade64` | 转世需在【金仙】圆满之后方可进行（当前境界不足） | src/main/java/com/mtxgdn/onebot/command/cultivation/ReincarnateCommand.java:51 |
| `cultivation.hdba9eaae` | 转世失败，请稍后重试 | src/main/java/com/mtxgdn/onebot/command/cultivation/ReincarnateCommand.java:92 |
| `cultivation.hf53b1cd0` | 打坐时间太短，未获得灵力。 | src/main/java/com/mtxgdn/onebot/command/cultivation/CultivateStopCommand.java:47 |
| `economy.h076e926e` | 数量必须为整数。 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:126<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:63 |
| `economy.h14554240` | 用法: /灵庄 取出 <编号>\n先用 /灵庄 查看存款编号 | src/main/java/com/mtxgdn/onebot/command/economy/BankCommand.java:121 |
| `economy.h1d142008` | 数量和价格无效 | src/main/java/com/mtxgdn/onebot/command/economy/AuctionCommand.java:90 |
| `economy.h2380a39c` | 用法: /灵石兑换 [数量] <从> <到>\n示例: /灵石兑换 1 极品 下品 | src/main/java/com/mtxgdn/onebot/command/economy/StoneExchangeCommand.java:39 |
| `economy.h2ce09654` | 请输入商品编号，如：/商店 1（/商店 查看全部商品） | src/main/java/com/mtxgdn/onebot/command/economy/ShopCommand.java:50 |
| `economy.h35568224` | 存款类型: 活期 / 7天 / 30天 / 90天\n例: /灵庄 存入 30天 1000 | src/main/java/com/mtxgdn/onebot/command/economy/BankCommand.java:105 |
| `economy.h3cf7f1c0` | 物品不存在，请使用 /物品列表 查看可回收物品 | src/main/java/com/mtxgdn/onebot/command/economy/RecycleCommand.java:46 |
| `economy.h4baccbe0` | 用法: /拍卖 出价 <编号> <价格> | src/main/java/com/mtxgdn/onebot/command/economy/AuctionCommand.java:103 |
| `economy.h4bf6cef4` | 用法: /拍卖 出售 <物品名> <数量> <起价> [小时默认24] | src/main/java/com/mtxgdn/onebot/command/economy/AuctionCommand.java:82 |
| `economy.h5afa12b2` | 数量必须大于0。 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:131<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:68 |
| `economy.h610cfa8c` | 方向只能是: 金币（换灵石）或 灵石（换金币） | src/main/java/com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:69 |
| `economy.h64014920` | 能量扣除失败，请稍后重试。 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:159 |
| `economy.h6ac429d0` | 能量转化系统暂未开放。 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:112<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:174<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:211 等 4 处 |
| `economy.h843c9af0` | 编号无效 | src/main/java/com/mtxgdn/onebot/command/economy/BankCommand.java:127 |
| `economy.h85187339` | 请输入有效的灵石数量 | src/main/java/com/mtxgdn/onebot/command/economy/CultivateBoostCommand.java:49 |
| `economy.h893a9a8c` | 数量必须大于 0 | src/main/java/com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:58<br>src/main/java/com/mtxgdn/onebot/command/economy/RecycleCommand.java:43 |
| `economy.h8c313f70` | 数量必须是正整数 | src/main/java/com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:54 |
| `economy.h8cde9058` | 灵石数量必须大于 0 | src/main/java/com/mtxgdn/onebot/command/economy/CultivateBoostCommand.java:42 |
| `economy.h91157bd0` | 用法: /回收 <物品名> [数量]\n提示: 回收价为原价的 30% | src/main/java/com/mtxgdn/onebot/command/economy/RecycleCommand.java:32 |
| `economy.ha8696148` | 用法: /灵庄 存入 <活期\|7天\|30天\|90天> <数量>\n例: /灵庄 存入 30天 1000 | src/main/java/com/mtxgdn/onebot/command/economy/BankCommand.java:93 |
| `economy.hbe6f07f0` | 用法: /货币兑换 金币 <数量> 或 /货币兑换 灵石 <数量> | src/main/java/com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:46 |
| `economy.hd8c78f9c` | 数量必须是数字 | src/main/java/com/mtxgdn/onebot/command/economy/StoneExchangeCommand.java:47 |
| `economy.hde5e9ffe` | 请先创建角色 | src/main/java/com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:36<br>src/main/java/com/mtxgdn/onebot/command/economy/PurifyCommand.java:40<br>src/main/java/com/mtxgdn/onebot/command/economy/StoneExchangeCommand.java:29 |
| `economy.he12b3244` | 编号和价格无效 | src/main/java/com/mtxgdn/onebot/command/economy/AuctionCommand.java:110 |
| `economy.he44ee76c` | 数量无效 | src/main/java/com/mtxgdn/onebot/command/economy/BankCommand.java:111<br>src/main/java/com/mtxgdn/onebot/command/economy/RecycleCommand.java:40 |
| `economy.he88d4f66` | 当前没有可转化的物品。 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:179<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:200 |
| `economy.hf8f3e890` | 用法: /转化 兑换 <物品> [数量]\n示例: /转化 兑换 mtxgdn:spirit_stone 10 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:116 |
| `enhance.h4c614190` | 用法: /强化 <装备栏位>\n栏位: weapon(武器) \| armor(防具) \| accessory(饰品) | src/main/java/com/mtxgdn/onebot/command/item/EnhanceCommand.java:30 |
| `equip.h738097b6` | 用法: /装备 <物品名称或key> <部位>\n部位: weapon(武器) \| armor(防具) \| accessory(饰品)\n物品名称或key 可在 /背包 或 /物品列表 中查看\n例如: /装备 铁剑 weapon | src/main/java/com/mtxgdn/onebot/command/item/EquipCommand.java:22 |
| `exploration.h06fd7070` | 当前没有可用的秘境。 | src/main/java/com/mtxgdn/onebot/command/exploration/SecretAreasCommand.java:35 |
| `exploration.h2b83a10c` | 用法: /进入秘境 <秘境名称>\n使用 /秘境 查看可用秘境 | src/main/java/com/mtxgdn/onebot/command/exploration/SecretEnterCommand.java:33 |
| `formation.h169acfb8` | 请指定阵法名，如: /阵法 place 聚灵阵 | src/main/java/com/mtxgdn/onebot/command/formation/FormationCommand.java:200 |
| `formation.h5522cfdc` | 无效的等级 | src/main/java/com/mtxgdn/onebot/command/formation/FormationCommand.java:220 |
| `formation.hcd888a30` | 你还没有布置阵法 | src/main/java/com/mtxgdn/onebot/command/formation/FormationCommand.java:232 |
| `game.hb36f9631` | 权限不足 | src/main/java/com/mtxgdn/common/command/Command.java:154 |
| `item_give.h893a9a8c` | 数量必须大于 0 | src/main/java/com/mtxgdn/onebot/command/item/ItemGiveCommand.java:59 |
| `item_give.hb795d1ec` | 用法: /发放物品 <玩家名> <物品Key> [数量=1]\n例: /发放物品 李白 spirit_stone 100 | src/main/java/com/mtxgdn/onebot/command/item/ItemGiveCommand.java:37 |
| `item_give.he44ee76c` | 数量无效 | src/main/java/com/mtxgdn/onebot/command/item/ItemGiveCommand.java:54 |
| `item_give.he94b4574` | 参数不足。用法: /发放物品 <玩家名> <物品Key> [数量] | src/main/java/com/mtxgdn/onebot/command/item/ItemGiveCommand.java:43 |
| `item_map.h0e294c36` | 物品列表为空。 | src/main/java/com/mtxgdn/onebot/command/item/ItemMapCommand.java:15 |
| `item_use.he8eb5bf0` | 用法: /使用 <物品名称或key>\n可在 /背包 或 /物品列表 中查看你的物品\n例如: /使用 回血丹 | src/main/java/com/mtxgdn/onebot/command/item/ItemUseCommand.java:24 |
| `map.h763af7b7` | 用法: /地图 传送 <地名>（需已到过该地） | src/main/java/com/mtxgdn/onebot/command/map/MapCommand.java:164 |
| `map.h9a54ad7b` | 野外地图当前已关闭（可在 config/game/wilderness_map.json 里把 enabled 改为 true） | src/main/java/com/mtxgdn/onebot/command/map/WildernessCommand.java:50 |
| `map.ha31577e4` | 用法: /地图 go <地名> | src/main/java/com/mtxgdn/onebot/command/map/MapCommand.java:180 |
| `map.ha47c4bc0` | 获取地图信息失败 | src/main/java/com/mtxgdn/onebot/command/map/MapCommand.java:53 |
| `map.hbef067da` | 你所在的位置已不在世界边界之内，请联系管理员 | src/main/java/com/mtxgdn/onebot/command/map/WildernessCommand.java:81 |
| `map.hde5e9ffe` | 请先创建角色 | src/main/java/com/mtxgdn/onebot/command/map/GatherCommand.java:35<br>src/main/java/com/mtxgdn/onebot/command/map/MapCommand.java:162<br>src/main/java/com/mtxgdn/onebot/command/map/MapCommand.java:178 等 5 处 |
| `market.h0beb331c` | 用法: /撤单 <挂单ID>\n使用 /我的挂单 查看你的挂单 | src/main/java/com/mtxgdn/onebot/command/market/CancelListingCommand.java:15 |
| `market.h0d78dba4` | 用法: /上架 <物品名称或key> <数量> <灵石价格>\n物品key可在 /背包 或 /物品列表 中查看\n例如: /上架 回血丹 5 100 | src/main/java/com/mtxgdn/onebot/command/market/ListItemCommand.java:19 |
| `market.h102b9b7c` | 价格必须是整数（单位：灵石） | src/main/java/com/mtxgdn/onebot/command/market/ListItemCommand.java:28 |
| `market.h321fe128` | 挂单ID必须是数字，请使用 /坊市 查看 | src/main/java/com/mtxgdn/onebot/command/market/BuyItemCommand.java:18 |
| `market.h42513430` | ===== 我的挂单 =====\n你还没有在坊市挂单。\n使用 /上架 <key> <数量> <灵石> 上架吧！ | src/main/java/com/mtxgdn/onebot/command/market/MyListingsCommand.java:17 |
| `market.h5a2a41c0` | 用法: /行情 <物品名或ID> [天数]\n例如: /行情 灵草  或  /行情 灵草 30\n天数默认 7 天，最多 90 天 | src/main/java/com/mtxgdn/onebot/command/market/MarketPriceCommand.java:39 |
| `market.h6a23d51c` | 用法: /购买 <挂单ID>\n先使用 /坊市 查看坊市中的挂单ID | src/main/java/com/mtxgdn/onebot/command/market/BuyItemCommand.java:15 |
| `market.h6e25fb8c` | 挂单ID必须是数字 | src/main/java/com/mtxgdn/onebot/command/market/CancelListingCommand.java:18 |
| `market.h71c082aa` | 请指定物品名或物品ID | src/main/java/com/mtxgdn/onebot/command/market/MarketPriceCommand.java:57 |
| `market.h7e9a27ac` | ===== 坊市 =====\n坊市上空空荡荡，还没有人挂单。\n使用 /上架 <物品key> <数量> <灵石价格> 来卖东西吧！ | src/main/java/com/mtxgdn/onebot/command/market/MarketCommand.java:19 |
| `market.he5b5db70` | 数量必须是整数 | src/main/java/com/mtxgdn/onebot/command/market/ListItemCommand.java:26 |
| `player.h1a5cbaf8` | 灵根重铸需要达到【金丹期】及以上境界，你当前境界不足以重铸灵根。 | src/main/java/com/mtxgdn/onebot/command/player/SpiritualRootCommand.java:78 |
| `player.h1a736b70` | 用法: /称号 装备 <名称>\n先用 /称号 查看你拥有的称号 | src/main/java/com/mtxgdn/onebot/command/player/TitleCommand.java:106 |
| `player.haeeb08e0` | 灵根重铸需要消耗道具【乾坤造化丹】x1，你的背包中没有该物品。 | src/main/java/com/mtxgdn/onebot/command/player/SpiritualRootCommand.java:84 |
| `player.hc5d64260` | 你的生命值已满，无需治疗。 | src/main/java/com/mtxgdn/onebot/command/player/HealCommand.java:15 |
| `player.hcf171b6a` | 你尚未觉醒灵根，请先创建角色。 | src/main/java/com/mtxgdn/onebot/command/player/SpiritualRootCommand.java:44 |
| `qq.h3e5241b0` | 消息交互按钮仅支持 QQ 官方机器人（当前接入方式不可用） | src/main/java/com/mtxgdn/onebot/command/qq/QqButtonCommand.java:49 |
| `qq.hde5e9ffe` | 请先创建角色 | src/main/java/com/mtxgdn/onebot/command/qq/QqButtonCommand.java:56 |
| `redeem.h0b0f09b4` | 请先创建角色。 | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:63 |
| `redeem.h2128a290` | 兑换成功！ | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:75 |
| `redeem.h4943b29e` | 兑换系统暂时不可用，请稍后重试。 | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:44 |
| `redeem.h5468348e` | 用法: /兑换 <兑换码>\n请输入要使用的兑换码。 | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:69 |
| `redeem.ha43d576a` | 权限不足，你无权使用此功能。 | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:57 |
| `redeem.hd852a51e` | 请先绑定账号。 | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:53 |
| `sect.h000a7f98` | 只有宗主、副宗主和长老才能查看申请列表 | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:173 |
| `sect.h06c1312a` | 用法: /宗门 reject <玩家名> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:205 |
| `sect.h25643d07` | 天下尚无宗门。\n使用 /宗门 create <名称> 开创一个宗门！ | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:99 |
| `sect.h25a87ba0` | 用法: /宗门 approve <玩家名> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:189 |
| `sect.h2be532b2` | 用法: /宗门 take <物品key> <数量> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:310 |
| `sect.h3fa32bc8` | 用法: /宗门 join <宗门名> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:89 |
| `sect.h46187c66` | 目前没有待处理的入宗申请 | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:176 |
| `sect.h55a1ebe4` | 用法: /宗门 transfer <玩家名> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:345 |
| `sect.h5a172b5a` | 用法: /宗门 war <宗门名> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:398 |
| `sect.h61277cc0` | 天下尚无宗门榜单。快去开创第一个宗门！ | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:327 |
| `sect.h630f6478` | 用法: /宗门 appoint <玩家名> <副宗主\|长老\|内门弟子\|外门弟子> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:243 |
| `sect.h712473e0` | 用法: /宗门 kick <玩家名> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:226 |
| `sect.h7dcc0998` | 用法: /宗门 donate <物品key> <数量> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:265 |
| `sect.haf25efcc` | 用法: /宗门 apply <宗门名> | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:163 |
| `sect.hc958bfb8` | 宗门仓库空空如也。\n使用 /宗门 donate <物品key> <数量> 捐献物品 | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:281 |
| `sect.hcd7ddb74` | 用法: /宗门 create <宗门名> [描述] | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:82 |
| `sect.hd3fd3ad4` | 你还没有加入宗门 | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:143<br>src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:172<br>src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:191 等 10 处 |
| `sect.hd8c78f9c` | 数量必须是数字 | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:269<br>src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:314 |
| `sect.hfb3213ac` | 宗门不存在或你尚未加入宗门。\n使用 /宗门 list 查看所有宗门 | src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java:117 |
| `social.h025df9b0` | 用法: /好友 visit <玩家名> | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:124 |
| `social.h204e35c0` | 用法: /好友 reject <玩家名> | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:90 |
| `social.h25b52960` | 接受失败：对方未向你发送过好友申请 | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:85 |
| `social.h27f35dd8` | 用法: /查人 <名称>\n例: /查人 李白 | src/main/java/com/mtxgdn/onebot/command/social/PlayerSearchCommand.java:34 |
| `social.h3eb53952` | 用法: /私聊 <玩家名> <消息内容> | src/main/java/com/mtxgdn/onebot/command/social/PrivateMessageCommand.java:33<br>src/main/java/com/mtxgdn/onebot/command/social/PrivateMessageCommand.java:38 |
| `social.h567a54ef` | 用法: /好友 remove <玩家名> | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:107 |
| `social.h5693bf98` | 删除失败：你们不是好友 | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:119 |
| `social.h6ac102f8` | 用法: /好友 accept <玩家名> | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:73 |
| `social.h91fac865` | 拒绝失败：对方未向你发送过好友申请 | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:102 |
| `social.h93f88000` | 用法: /好友 add <玩家名> | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:51 |
| `social.hae604775` | 没有待处理的好友申请 | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:157 |
| `social.hb61ed708` | 不能给自己发私聊消息。 | src/main/java/com/mtxgdn/onebot/command/social/PrivateMessageCommand.java:54 |
| `social.hdc5c17d4` | 不能添加自己为好友 | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:61 |
| `social.hdd0adaaa` | 你还没有好友。\n添加: /好友 add <玩家名> | src/main/java/com/mtxgdn/onebot/command/social/FriendCommand.java:143 |
| `team.h08e42790` | 邀请失败：请确认你是队长、队伍未满员且对方不在其他队伍中 | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:76 |
| `team.h585dd414` | 创建队伍失败：你可能已在队伍中 | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:51 |
| `team.h5ce1c1b0` | 你已退出队伍 | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:93 |
| `team.h65714a14` | 用法: /组队 邀请 <玩家名> | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:59 |
| `team.ha732136d` | 你不在任何队伍中。\n创建: /组队 创建\n加入: 等队长邀请后 /组队 接受 | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:101 |
| `team.hbd4c6317` | 用法: /组队 副本 <秘境名>（如：/组队 副本 暗黑森林） | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:117 |
| `team.hbe8ba070` | 你不在任何队伍中 | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:95 |
| `team.hcbccd8bd` | 不能邀请自己 | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:70 |
| `team.hd2c6b3d0` | 已拒绝组队邀请 | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:89 |
| `team.hd4e90c90` | 没有待处理的组队邀请 | src/main/java/com/mtxgdn/onebot/command/team/TeamCommand.java:82 |
| `technique.h32f7d28c` | 用法: /功法 学习 <功法ID>\n先用 /功法 列表 查看功法ID | src/main/java/com/mtxgdn/onebot/command/technique/TechniqueCommand.java:75 |
| `technique.h5731328a` | 用法: /功法 升级 <功法ID> | src/main/java/com/mtxgdn/onebot/command/technique/TechniqueCommand.java:105 |
| `technique.h886e6000` | 功法ID无效 | src/main/java/com/mtxgdn/onebot/command/technique/TechniqueCommand.java:107<br>src/main/java/com/mtxgdn/onebot/command/technique/TechniqueCommand.java:77<br>src/main/java/com/mtxgdn/onebot/command/technique/TechniqueCommand.java:87 等 4 处 |
| `technique.h9af56d32` | 用法: /功法 装备 <功法ID> | src/main/java/com/mtxgdn/onebot/command/technique/TechniqueCommand.java:85 |
| `technique.hbd19bbc8` | 用法: /功法 卸下 <功法ID> | src/main/java/com/mtxgdn/onebot/command/technique/TechniqueCommand.java:95 |
| `unequip.h05c4ec00` | 用法: /卸下 <部位>\n部位: weapon \| armor \| accessory | src/main/java/com/mtxgdn/onebot/command/item/UnequipCommand.java:21 |
| `worldboss.h535cf9d3` | 找不到这只世界Boss，先用 /世界boss 查看列表 | src/main/java/com/mtxgdn/onebot/command/worldboss/WorldBossCommand.java:106 |
| `worldboss.hb2977150` | 用法: /世界boss 挑战 <序号\|名称>\n先用 /世界boss 查看当前有哪些 Boss | src/main/java/com/mtxgdn/onebot/command/worldboss/WorldBossCommand.java:81 |
