# i18n key 映射表（自动生成）

由 `tools/i18n/migrate-literals.js --write` 生成。
key 形如 `<模块>.h<8位内容哈希>`：用内容哈希作名字，是为了让
「改动一段无关文案」不会导致整批 key 漂移——已发布客户端的
语言包缓存才不会因此整体失效。

想改成语义化名字时，先改这张表再重跑即可（脚本对已存在的 key
一律跳过，不会覆盖人已经审过的译文）。

| key | 原文（zh_cn） | 出现位置 |
| --- | --- | --- |
| `cave.h13067628` | 洞府灵气不足，需要等待灵气汇聚 | src/main/java/com/mtxgdn/game/service/CaveService.java:201 |
| `cave.h4e9943c4` | 你还没有洞府，无法在此修炼 | src/main/java/com/mtxgdn/game/service/CaveService.java:219 |
| `cave.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/CaveService.java:226<br>src/main/java/com/mtxgdn/game/service/CaveService.java:58 |
| `cave.h6e2e686c` | 开辟洞府失败 | src/main/java/com/mtxgdn/game/service/CaveService.java:123 |
| `cave.h7b28cff2` | 你还没有洞府 | src/main/java/com/mtxgdn/game/service/CaveService.java:147<br>src/main/java/com/mtxgdn/game/service/CaveService.java:281 |
| `cave.hcf358280` | 你已经拥有洞府了 | src/main/java/com/mtxgdn/game/service/CaveService.java:72 |
| `command_context.h3f4f055e` | 请先绑定账号。\n私聊使用 /bind | src/main/java/com/mtxgdn/common/command/CommandContext.java:121 |
| `command_context.ha43d576a` | 权限不足，你无权使用此功能。 | src/main/java/com/mtxgdn/common/command/CommandContext.java:112<br>src/main/java/com/mtxgdn/common/command/CommandContext.java:125 |
| `crafting.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/CraftingService.java:66 |
| `crafting.h944f430b` | 境界不足，需要更高境界才能使用此配方 | src/main/java/com/mtxgdn/game/service/CraftingService.java:69 |
| `crafting.he6cc13cc` | 配方不存在 | src/main/java/com/mtxgdn/game/service/CraftingService.java:61 |
| `daily.h131eebf4` | 今日已晨修，紫气已收入体内，明日再来吧 | src/main/java/com/mtxgdn/game/service/DailyService.java:68 |
| `daily.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/DailyService.java:51 |
| `economy.h098a1c50` | 数量和起拍价必须大于 0 | src/main/java/com/mtxgdn/game/service/EconomyService.java:595 |
| `economy.h1198f420` | 至少需要 1 下品灵石才能兑换金币 | src/main/java/com/mtxgdn/game/service/EconomyService.java:290 |
| `economy.h11c4d3b4` | 该存款已取出，请勿重复操作 | src/main/java/com/mtxgdn/game/service/EconomyService.java:440 |
| `economy.h329d76f0` | 灵庄起存 100 下品灵石 | src/main/java/com/mtxgdn/game/service/EconomyService.java:331 |
| `economy.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/EconomyService.java:216<br>src/main/java/com/mtxgdn/game/service/EconomyService.java:263<br>src/main/java/com/mtxgdn/game/service/EconomyService.java:325 等 5 处 |
| `economy.h680f40ec` | 该存款不存在或不属于你 | src/main/java/com/mtxgdn/game/service/EconomyService.java:402 |
| `economy.h6a5dc11e` | 该存款已取出 | src/main/java/com/mtxgdn/game/service/EconomyService.java:405 |
| `economy.h75e3b260` | 该物品不可交易，无法回收 | src/main/java/com/mtxgdn/game/service/EconomyService.java:121 |
| `economy.h79438c15` | 无效的灵石等级，只能提纯 下品/中品/上品 灵石 | src/main/java/com/mtxgdn/game/service/EconomyService.java:866 |
| `economy.h994336a4` | 提纯灵石需要借助洞府灵气，请先开辟洞府 | src/main/java/com/mtxgdn/game/service/EconomyService.java:900 |
| `economy.ha74a96f8` | 今日已签到，明日再来吧 | src/main/java/com/mtxgdn/game/service/EconomyService.java:45<br>src/main/java/com/mtxgdn/game/service/EconomyService.java:74 |
| `economy.hadf213b8` | 物品不存在 | src/main/java/com/mtxgdn/game/service/EconomyService.java:114<br>src/main/java/com/mtxgdn/game/service/EconomyService.java:594 |
| `economy.haf3ab8d8` | 请先开始修炼（/修炼），才能使用灵石加速 | src/main/java/com/mtxgdn/game/service/EconomyService.java:218 |
| `economy.hbeb35148` | 出价必须大于 0 | src/main/java/com/mtxgdn/game/service/EconomyService.java:635 |
| `economy.he4b7991c` | 灵石本身就是货币，无需回收 | src/main/java/com/mtxgdn/game/service/EconomyService.java:118 |
| `enhance.h0b13308c` | 强化失败！材料消耗但装备未受影响 | src/main/java/com/mtxgdn/game/service/EnhanceService.java:247 |
| `enhance.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/EnhanceService.java:123 |
| `enhance.h81c08460` | 该装备栏没有装备 | src/main/java/com/mtxgdn/game/service/EnhanceService.java:105 |
| `enhance.h9a90a2a4` | 强化失败！但护身符生效，装备免于碎裂（消耗 1 张护身符） | src/main/java/com/mtxgdn/game/service/EnhanceService.java:235 |
| `enhance.had7a5178` | 装备数据异常 | src/main/java/com/mtxgdn/game/service/EnhanceService.java:110 |
| `farm.h0c3d0718` | 恢复作物失败 | src/main/java/com/mtxgdn/game/service/FarmService.java:234 |
| `farm.h10de2898` | 作物还未成熟 | src/main/java/com/mtxgdn/game/service/FarmService.java:298 |
| `farm.h121b7308` | 施肥失败 | src/main/java/com/mtxgdn/game/service/FarmService.java:521 |
| `farm.h1cca0008` | 地块已是空地 | src/main/java/com/mtxgdn/game/service/FarmService.java:357 |
| `farm.h21e7e68c` | 已清理地块 | src/main/java/com/mtxgdn/game/service/FarmService.java:361 |
| `farm.h231f62ed` | 没有需要施肥的地块 | src/main/java/com/mtxgdn/game/service/FarmService.java:496 |
| `farm.h24d616f8` | 收获失败 | src/main/java/com/mtxgdn/game/service/FarmService.java:553 |
| `farm.h36b84c89` | 杀虫剂不足 | src/main/java/com/mtxgdn/game/service/FarmService.java:368 |
| `farm.h47064598` | 查询地块失败 | src/main/java/com/mtxgdn/game/service/FarmService.java:492 |
| `farm.h49d89808` | 地块没有病虫害 | src/main/java/com/mtxgdn/game/service/FarmService.java:379 |
| `farm.h4b6fa38c` | 未知作物 | src/main/java/com/mtxgdn/game/service/FarmService.java:303 |
| `farm.h5433c430` | 作物已枯萎过久，无法恢复，请清理地块重新种植 | src/main/java/com/mtxgdn/game/service/FarmService.java:224 |
| `farm.h634a5aa8` | 无效的肥料类型 | src/main/java/com/mtxgdn/game/service/FarmService.java:248<br>src/main/java/com/mtxgdn/game/service/FarmService.java:474 |
| `farm.h88221924` | 浇水成功！枯萎作物已恢复，水分: 50% | src/main/java/com/mtxgdn/game/service/FarmService.java:236 |
| `farm.h88a44f1f` | 没有可收获的作物 | src/main/java/com/mtxgdn/game/service/FarmService.java:557 |
| `farm.h915aaf14` | 空地无需浇水 | src/main/java/com/mtxgdn/game/service/FarmService.java:203 |
| `farm.h920b6224` | 地块已被占用 | src/main/java/com/mtxgdn/game/service/FarmService.java:163 |
| `farm.h95625008` | 已使用杀虫剂，病虫害已清除 | src/main/java/com/mtxgdn/game/service/FarmService.java:385 |
| `farm.h9778e920` | 没有需要浇水的地块 | src/main/java/com/mtxgdn/game/service/FarmService.java:460 |
| `farm.hb16af8bc` | 未知种子 | src/main/java/com/mtxgdn/game/service/FarmService.java:148 |
| `farm.hbd6a293c` | 水分已充足 | src/main/java/com/mtxgdn/game/service/FarmService.java:211 |
| `farm.hd8a8fe7c` | 地块不存在 | src/main/java/com/mtxgdn/game/service/FarmService.java:160<br>src/main/java/com/mtxgdn/game/service/FarmService.java:199<br>src/main/java/com/mtxgdn/game/service/FarmService.java:259 等 6 处 |
| `farm.he10fe06e` | 浇水失败 | src/main/java/com/mtxgdn/game/service/FarmService.java:447 |
| `farm.he9f89b8c` | 空地无需施肥 | src/main/java/com/mtxgdn/game/service/FarmService.java:263 |
| `farm.hf20b7301` | 肥力已充足 | src/main/java/com/mtxgdn/game/service/FarmService.java:267 |
| `farm.hf5b0e6f0` | 已达到最大地块数 | src/main/java/com/mtxgdn/game/service/FarmService.java:394 |
| `formation.h28e14f54` | 你还没有洞府，无法布置阵法 | src/main/java/com/mtxgdn/game/service/FormationService.java:268<br>src/main/java/com/mtxgdn/game/service/FormationService.java:70 |
| `formation.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/FormationService.java:64 |
| `formation.h98b5a0c2` | 这不是可布置阵法的阵图 | src/main/java/com/mtxgdn/game/service/FormationService.java:263 |
| `formation.ha5ec7f40` | 你已经布置了一个阵法，需先拆除 | src/main/java/com/mtxgdn/game/service/FormationService.java:273<br>src/main/java/com/mtxgdn/game/service/FormationService.java:76 |
| `formation.hcd888a30` | 你还没有布置阵法 | src/main/java/com/mtxgdn/game/service/FormationService.java:130<br>src/main/java/com/mtxgdn/game/service/FormationService.java:181 |
| `friend.h5fca123a` | 玩家数据异常 | src/main/java/com/mtxgdn/game/service/FriendService.java:208 |
| `friend.hc0ed9cb8` | 你们不是好友，无法拜访 | src/main/java/com/mtxgdn/game/service/FriendService.java:193 |
| `friend.hc7efe258` | 不能拜访自己 | src/main/java/com/mtxgdn/game/service/FriendService.java:188 |
| `friend.hd893e16c` | 今日已拜访过该好友，明日再来吧 | src/main/java/com/mtxgdn/game/service/FriendService.java:200 |
| `game.ha2b13c10` | type 只能是 goldToStones 或 stonesToGold | src/main/java/com/mtxgdn/rest/GameResource.java:1760 |
| `item.h058f56b2` | 已装备该物品 | src/main/java/com/mtxgdn/game/service/ItemService.java:635 |
| `item.h275bc0df` | 背包中没有该物品 | src/main/java/com/mtxgdn/game/service/ItemService.java:628<br>src/main/java/com/mtxgdn/game/service/ItemService.java:872 |
| `item.h3b242f48` | 该装备栏位为空 | src/main/java/com/mtxgdn/game/service/ItemService.java:733 |
| `item.h7132a04c` | 物品数据异常 | src/main/java/com/mtxgdn/game/service/ItemService.java:739 |
| `item.h855d6fe8` | 兑换数量必须大于0 | src/main/java/com/mtxgdn/game/service/ItemService.java:562 |
| `item.ha1976553` | 物品不存在，请使用 /物品列表 查看可用物品 | src/main/java/com/mtxgdn/game/service/ItemService.java:617 |
| `item.ha68b3ef0` | 无效的灵石等级 | src/main/java/com/mtxgdn/game/service/ItemService.java:552 |
| `item.hadf213b8` | 物品不存在 | src/main/java/com/mtxgdn/game/service/ItemService.java:848 |
| `item.hc1802354` | 只能将高等级灵石兑换为低等级灵石 | src/main/java/com/mtxgdn/game/service/ItemService.java:557 |
| `item.hf663041d` | 灵石不足 | src/main/java/com/mtxgdn/game/service/ItemService.java:569 |
| `map.h0ce2a85a` | 手慢了，此地的资源刚被采空 | src/main/java/com/mtxgdn/game/service/MapService.java:1791 |
| `map.h41614d08` | 当前位置异常，请联系管理员 | src/main/java/com/mtxgdn/game/service/MapService.java:1763<br>src/main/java/com/mtxgdn/game/service/MapService.java:810 |
| `map.h574940a0` | 传送功能已关闭 | src/main/java/com/mtxgdn/game/service/MapService.java:1339 |
| `map.h775899f0` | 此地的资源已被采空，等下次补货吧 | src/main/java/com/mtxgdn/game/service/MapService.java:1781<br>src/main/java/com/mtxgdn/game/service/MapService.java:1786 |
| `map.h796f4b70` | 玩家不存在 | src/main/java/com/mtxgdn/game/service/MapService.java:1348<br>src/main/java/com/mtxgdn/game/service/MapService.java:1755<br>src/main/java/com/mtxgdn/game/service/MapService.java:791 等 4 处 |
| `map.h7f1cf394` | 这里不是野外，没有可采集的资源（用 /野外 查看野外） | src/main/java/com/mtxgdn/game/service/MapService.java:1759 |
| `map.h88947944` | 该地点不存在 | src/main/java/com/mtxgdn/game/service/MapService.java:1344<br>src/main/java/com/mtxgdn/game/service/MapService.java:786<br>src/main/java/com/mtxgdn/game/service/MapService.java:914 |
| `map.h9a627ec0` | 采集失败：物品未能放入背包（背包可能已满） | src/main/java/com/mtxgdn/game/service/MapService.java:1801 |
| `map.hb37f0c87` | 野外功能已关闭 | src/main/java/com/mtxgdn/game/service/MapService.java:1750 |
| `map.hcf096c23` | 地图数据尚未就绪，请稍后再试 | src/main/java/com/mtxgdn/game/service/MapService.java:963 |
| `map.he04326c4` | 你的位置刚刚被其它操作改变，灵石已退还，请稍候再试 | src/main/java/com/mtxgdn/game/service/MapService.java:1384 |
| `map.he3affb16` | 扣除灵石失败，传送已取消 | src/main/java/com/mtxgdn/game/service/MapService.java:1373 |
| `map.heb20a82a` | 你的位置刚刚被其它操作改变，请稍候再试 | src/main/java/com/mtxgdn/game/service/MapService.java:833 |
| `player.h00836942` | 结束闭关修炼 | src/main/java/com/mtxgdn/game/service/PlayerService.java:544 |
| `player.h05d87a90` | 你已重伤倒地，无法闭关修炼，请先疗伤恢复 | src/main/java/com/mtxgdn/game/service/PlayerService.java:484 |
| `player.h2a0575e8` | 开始闭关修炼 | src/main/java/com/mtxgdn/game/service/PlayerService.java:500 |
| `player.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/PlayerService.java:466<br>src/main/java/com/mtxgdn/game/service/PlayerService.java:512<br>src/main/java/com/mtxgdn/game/service/PlayerService.java:642 |
| `player.h7a0f95e1` | 你已在闭关中，无需重复打坐（使用停止修炼来结算） | src/main/java/com/mtxgdn/game/service/PlayerService.java:477 |
| `player.hafb562c8` | 你并未在闭关中，无需停止修炼 | src/main/java/com/mtxgdn/game/service/PlayerService.java:517 |
| `player.hc5d64260` | 你的生命值已满，无需治疗。 | src/main/java/com/mtxgdn/game/service/PlayerService.java:647 |
| `player.hdb66b7ae` | 扣除灵石失败，请重试。 | src/main/java/com/mtxgdn/game/service/PlayerService.java:665 |
| `player.hf5581950` | 修炼结算正在处理中，请勿重复操作 | src/main/java/com/mtxgdn/game/service/PlayerService.java:526 |
| `playerdata.h9573c3bc` | 玩家数据文件化未启用 | src/main/java/com/mtxgdn/playerdata/PlayerDataService.java:124<br>src/main/java/com/mtxgdn/playerdata/PlayerDataService.java:338 |
| `playerdata.haaee0960` | 玩家数据为空或格式不正确 | src/main/java/com/mtxgdn/playerdata/PlayerDataService.java:351 |
| `sect.h0349c594` | 只有宗主才能退位 | src/main/java/com/mtxgdn/game/service/SectService.java:1326 |
| `sect.h07a48c10` | 只有宗主才能发起宗门战 | src/main/java/com/mtxgdn/game/service/SectService.java:1000 |
| `sect.h07ebe188` | 对决双方角色不存在，竞争期已作废 | src/main/java/com/mtxgdn/game/service/SectService.java:1619 |
| `sect.h084db6a4` | 宗门成员已满 | src/main/java/com/mtxgdn/game/service/SectService.java:423 |
| `sect.h091dc05c` | 目标宗门不存在 | src/main/java/com/mtxgdn/game/service/SectService.java:1013 |
| `sect.h0c4f82a8` | 你已经向该宗门提交过申请，请等待审批 | src/main/java/com/mtxgdn/game/service/SectService.java:364 |
| `sect.h12f51fdc` | 不能对自己的宗门宣战 | src/main/java/com/mtxgdn/game/service/SectService.java:1008 |
| `sect.h20bb459f` | 只有宗主才能改变副宗主的职位 | src/main/java/com/mtxgdn/game/service/SectService.java:605 |
| `sect.h2a6b3b5e` | 你是宗门仅剩的成员，无人可以继任。\n若已无意经营，请使用 /宗门 解散 | src/main/java/com/mtxgdn/game/service/SectService.java:1338 |
| `sect.h30ecd258` | 只有宗主才能踢出副宗主 | src/main/java/com/mtxgdn/game/service/SectService.java:553 |
| `sect.h32acc4f3` | 该玩家不在你的宗门中 | src/main/java/com/mtxgdn/game/service/SectService.java:547<br>src/main/java/com/mtxgdn/game/service/SectService.java:599 |
| `sect.h33d54619` | 你无权处理宗门申请 | src/main/java/com/mtxgdn/game/service/SectService.java:413 |
| `sect.h3e4690e4` | 你是挑战者，只需发起挑战；应战由宗主操作 | src/main/java/com/mtxgdn/game/service/SectService.java:1511 |
| `sect.h490fc096` | 宗门描述最多 100 个字符 | src/main/java/com/mtxgdn/game/service/SectService.java:191 |
| `sect.h50602204` | 目标宗门没有成员 | src/main/java/com/mtxgdn/game/service/SectService.java:1018 |
| `sect.h5378f0dc` | 更新失败，该玩家不在宗门中 | src/main/java/com/mtxgdn/game/service/SectService.java:664 |
| `sect.h53eadc4b` | 不能改变宗主的职位 | src/main/java/com/mtxgdn/game/service/SectService.java:602 |
| `sect.h582e9a70` | 贡献值已更新 | src/main/java/com/mtxgdn/game/service/SectService.java:661 |
| `sect.h587f8ffc` | 不能踢出宗主 | src/main/java/com/mtxgdn/game/service/SectService.java:550 |
| `sect.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/SectService.java:198<br>src/main/java/com/mtxgdn/game/service/SectService.java:336 |
| `sect.h5e1d7370` | 长老只能任命内门弟子和外门弟子 | src/main/java/com/mtxgdn/game/service/SectService.java:589 |
| `sect.h6052ac88` | 你已经有了宗门，需要先退出才能加入新宗门 | src/main/java/com/mtxgdn/game/service/SectService.java:345 |
| `sect.h616b0664` | 你已经有宗门了，需要先退出才能创建新宗门 | src/main/java/com/mtxgdn/game/service/SectService.java:212 |
| `sect.h6b4e3074` | 你没有加入任何宗门 | src/main/java/com/mtxgdn/game/service/SectService.java:1321<br>src/main/java/com/mtxgdn/game/service/SectService.java:1489<br>src/main/java/com/mtxgdn/game/service/SectService.java:480 等 4 处 |
| `sect.h6ee9c207` | 对方不在你的宗门中 | src/main/java/com/mtxgdn/game/service/SectService.java:928 |
| `sect.h71d5bb28` | 申请不存在或已处理 | src/main/java/com/mtxgdn/game/service/SectService.java:403 |
| `sect.h72d25ae4` | 只有宗主才能转让 | src/main/java/com/mtxgdn/game/service/SectService.java:918 |
| `sect.h893a9a8c` | 数量必须大于 0 | src/main/java/com/mtxgdn/game/service/SectService.java:700<br>src/main/java/com/mtxgdn/game/service/SectService.java:771 |
| `sect.h8ebfac78` | 长老不能操作同级 | src/main/java/com/mtxgdn/game/service/SectService.java:608 |
| `sect.h937adf7c` | 你已发起挑战，请等待宗主使用 /宗门 挑战 接受 应战 | src/main/java/com/mtxgdn/game/service/SectService.java:1572 |
| `sect.h96ac1870` | 创建宗门失败 | src/main/java/com/mtxgdn/game/service/SectService.java:278 |
| `sect.h9e5d5d34` | 你不是该宗门成员 | src/main/java/com/mtxgdn/game/service/SectService.java:691<br>src/main/java/com/mtxgdn/game/service/SectService.java:768 |
| `sect.ha2ad4da4` | 接收者角色不存在 | src/main/java/com/mtxgdn/game/service/SectService.java:936 |
| `sect.ha5ef1b70` | 你的宗门不存在 | src/main/java/com/mtxgdn/game/service/SectService.java:1005 |
| `sect.haa4398a4` | 只有宗主、副宗主和长老才能任命宗门职位 | src/main/java/com/mtxgdn/game/service/SectService.java:569 |
| `sect.hadf213b8` | 物品不存在 | src/main/java/com/mtxgdn/game/service/SectService.java:696<br>src/main/java/com/mtxgdn/game/service/SectService.java:776 |
| `sect.hb01e0fa0` | 不能踢出同级或更高级别的成员 | src/main/java/com/mtxgdn/game/service/SectService.java:556 |
| `sect.hb12ade10` | 挑战者尚未发起挑战，暂时无法应战 | src/main/java/com/mtxgdn/game/service/SectService.java:1609 |
| `sect.hb527d929` | 对方已经是宗主了 | src/main/java/com/mtxgdn/game/service/SectService.java:931 |
| `sect.hb77b5854` | 竞争期已结束或状态已变化，挑战未发起 | src/main/java/com/mtxgdn/game/service/SectService.java:1589 |
| `sect.hbffa2528` | 宗门不存在 | src/main/java/com/mtxgdn/game/service/SectService.java:1333<br>src/main/java/com/mtxgdn/game/service/SectService.java:1911<br>src/main/java/com/mtxgdn/game/service/SectService.java:341 等 7 处 |
| `sect.hcbedcf90` | 只有宗主、副宗主和长老才能踢人 | src/main/java/com/mtxgdn/game/service/SectService.java:542 |
| `sect.hd5b6e8a8` | 副宗主不能任命副宗主，只有宗主可以 | src/main/java/com/mtxgdn/game/service/SectService.java:594 |
| `sect.hd8088058` | 当前没有进行中的宗主竞争期 | src/main/java/com/mtxgdn/game/service/SectService.java:1496 |
| `sect.hda58b418` | 无效的职位，可用：副宗主/长老/内门弟子/外门弟子 | src/main/java/com/mtxgdn/game/service/SectService.java:581 |
| `sect.he21dc790` | 只有本宗宗主或竞争期挑战者可以操作 | src/main/java/com/mtxgdn/game/service/SectService.java:1504 |
| `sect.he343e014` | 只有宗主才能解散宗门 | src/main/java/com/mtxgdn/game/service/SectService.java:1906 |
| `sect.hefebafae` | 宗门名称需要 2-8 个字符 | src/main/java/com/mtxgdn/game/service/SectService.java:185 |
| `sect.hf951c000` | 只有宗主才能升级宗门 | src/main/java/com/mtxgdn/game/service/SectService.java:876 |
| `sect.hfad55360` | 挑战状态已变化（可能已结算或竞争期已结束），本次应战未生效 | src/main/java/com/mtxgdn/game/service/SectService.java:1683 |
| `sect.hfe4ed1bc` | 宗门战为实验性内容，当前未开放（可在 config/plugins.yml 中启用 experimental_sect_war） | src/main/java/com/mtxgdn/game/service/SectService.java:995 |
| `skill.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/SkillService.java:145<br>src/main/java/com/mtxgdn/game/service/SkillService.java:227 |
| `skill.ha299b65a` | 秘籍记载的技能已经失传了... | src/main/java/com/mtxgdn/game/service/SkillService.java:220 |
| `skill.ha5b097e0` | 技能不存在 | src/main/java/com/mtxgdn/game/service/SkillService.java:130 |
| `technique.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/TechniqueService.java:147 |
| `technique.ha59e2658` | 功法不存在 | src/main/java/com/mtxgdn/game/service/TechniqueService.java:140 |
| `technique.hec1e8dc4` | 你尚未学会此功法 | src/main/java/com/mtxgdn/game/service/TechniqueService.java:192<br>src/main/java/com/mtxgdn/game/service/TechniqueService.java:222<br>src/main/java/com/mtxgdn/game/service/TechniqueService.java:249 |
| `title.h3a7cd8ac` | 你未拥有该称号 | src/main/java/com/mtxgdn/game/service/TitleService.java:114 |
| `title.h4f740e50` | 玩家未拥有该称号 | src/main/java/com/mtxgdn/game/service/TitleService.java:99 |
| `title.h56c44c8c` | 已拥有该称号 | src/main/java/com/mtxgdn/game/service/TitleService.java:26 |
| `world_boss.h0803df94` | 挑战冷却中（或有一次挑战正在进行），请稍后再试 | src/main/java/com/mtxgdn/game/service/WorldBossService.java:426 |
| `world_boss.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/game/service/WorldBossService.java:395 |
| `world_boss.h95538220` | 你的生命值不足，无法挑战世界Boss | src/main/java/com/mtxgdn/game/service/WorldBossService.java:400 |
| `world_boss.ha32bc604` | 找不到这只世界Boss。\n使用 /世界boss 查看当前可挑战的列表 | src/main/java/com/mtxgdn/game/service/WorldBossService.java:382 |
| `world_boss.hfd4aae38` | 闭关中无法挑战世界Boss，请先 /停止 结束打坐 | src/main/java/com/mtxgdn/game/service/WorldBossService.java:405 |
