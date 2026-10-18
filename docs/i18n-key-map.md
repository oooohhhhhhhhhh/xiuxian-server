# i18n key 映射表（自动生成）

由 `tools/i18n/migrate-literals.js --write` 生成。
key 形如 `<模块>.h<8位内容哈希>`：用内容哈希作名字，是为了让
「改动一段无关文案」不会导致整批 key 漂移——已发布客户端的
语言包缓存才不会因此整体失效。

想改成语义化名字时，先改这张表再重跑即可（脚本对已存在的 key
一律跳过，不会覆盖人已经审过的译文）。

| key | 原文（zh_cn） | 出现位置 |
| --- | --- | --- |
| `admin.h00567a14` | 无管理后台权限，请联系超级管理员分配角色 | src/main/java/com/mtxgdn/rest/AdminResource.java:184 |
| `admin.h04a3a43c` | 权限组已删除 | src/main/java/com/mtxgdn/rest/AdminResource.java:710 |
| `admin.h05227a8c` | 已删除事件偏置，地图缓存已热重载 | src/main/java/com/mtxgdn/rest/AdminResource.java:3774 |
| `admin.h0865fea7` | 无效的适配器配置 | src/main/java/com/mtxgdn/rest/AdminResource.java:2923 |
| `admin.h09fd94fc` | 编辑成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:1387 |
| `admin.h0ae42a94` | 已从黑名单移除 | src/main/java/com/mtxgdn/rest/AdminResource.java:2328<br>src/main/java/com/mtxgdn/rest/AdminResource.java:2348 |
| `admin.h0ba3ecc8` | 权限已添加到权限组 | src/main/java/com/mtxgdn/rest/AdminResource.java:740 |
| `admin.h0d43f684` | 权限组创建成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:662 |
| `admin.h0eba6300` | 无效的角色名 | src/main/java/com/mtxgdn/rest/AdminResource.java:857<br>src/main/java/com/mtxgdn/rest/AdminResource.java:877<br>src/main/java/com/mtxgdn/rest/AdminResource.java:919 |
| `admin.h1237ba08` | 文件监听已启动 | src/main/java/com/mtxgdn/rest/AdminResource.java:457 |
| `admin.h1a5ea364` | 请提供灵根名称 | src/main/java/com/mtxgdn/rest/AdminResource.java:1479 |
| `admin.h1a6a52b8` | 删除失败 | src/main/java/com/mtxgdn/rest/AdminResource.java:1413 |
| `admin.h2bff2074` | 兑换码已删除 | src/main/java/com/mtxgdn/rest/AdminResource.java:2183 |
| `admin.h2d6880bc` | 记录不存在 | src/main/java/com/mtxgdn/rest/AdminResource.java:1831<br>src/main/java/com/mtxgdn/rest/AdminResource.java:1982 |
| `admin.h2df30215` | 权限组更新成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:691 |
| `admin.h308c401c` | 增加成功 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:300 |
| `admin.h3284ab10` | 指令测试完成 | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:279 |
| `admin.h34ac6234` | 设置成功 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:258 |
| `admin.h34edd3d4` | 新增成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:1895 |
| `admin.h34f00dc0` | 用户名和密码不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:123 |
| `admin.h35fbfdd4` | 能量值不能为负数 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:242 |
| `admin.h3ab62198` | 公告内容不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:2807 |
| `admin.h3cc04960` | 名称修改成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:1448 |
| `admin.h4011186c` | 请提供 itemKey 查询参数 | src/main/java/com/mtxgdn/rest/AdminResource.java:4077 |
| `admin.h460e03c8` | jarName 不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:409 |
| `admin.h46172edd` | 兑换码创建成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:2116 |
| `admin.h49113c80` | 更新成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:1944 |
| `admin.h49c7b7c8` | 权限码不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:1100<br>src/main/java/com/mtxgdn/rest/AdminResource.java:732 |
| `admin.h4aa0a0f2` | 能量转化系统暂未开放 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:190 |
| `admin.h4d604ddc` | 兑换码更新成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:2164 |
| `admin.h514bf7e1` | 角色分配成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:865 |
| `admin.h53593588` | 减少量必须大于0 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:327 |
| `admin.h564bba40` | 用户名不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:907 |
| `admin.h58665cac` | 管理员账号创建成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:950 |
| `admin.h5af66540` | 需要登录用户才能测试指令 | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:261 |
| `admin.h5df85b3d` | 密码长度不能少于6位 | src/main/java/com/mtxgdn/rest/AdminResource.java:913<br>src/main/java/com/mtxgdn/rest/AdminResource.java:983 |
| `admin.h5f4782cc` | 用户删除成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:1045 |
| `admin.h5f4d6aa0` | 公告已发送 | src/main/java/com/mtxgdn/rest/AdminResource.java:2819 |
| `admin.h6a8f4654` | 权限已从权限组移除 | src/main/java/com/mtxgdn/rest/AdminResource.java:761 |
| `admin.h6c0eef00` | 兑换码不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:2099 |
| `admin.h6e816fc4` | 获取用户信息失败 | src/main/java/com/mtxgdn/rest/AdminResource.java:2696 |
| `admin.h6e950058` | 减少成功 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:350 |
| `admin.h7443b43e` | qqNumber 和 userId 必须填写其中一个 | src/main/java/com/mtxgdn/rest/AdminResource.java:2292 |
| `admin.h7503790c` | 数据导入成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:2035 |
| `admin.h77706204` | 已保存事件偏置，地图缓存已热重载 | src/main/java/com/mtxgdn/rest/AdminResource.java:3731 |
| `admin.h796f4b70` | 玩家不存在 | src/main/java/com/mtxgdn/rest/AdminResource.java:1258<br>src/main/java/com/mtxgdn/rest/AdminResource.java:1314<br>src/main/java/com/mtxgdn/rest/AdminResource.java:1363 等 8 处 |
| `admin.h7c8dde64` | QQ号和权限等级不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:2836 |
| `admin.h7d2e5a9c` | 权限设置成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:2854 |
| `admin.h7e7ba6b0` | 删除成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:1408<br>src/main/java/com/mtxgdn/rest/AdminResource.java:1979 |
| `admin.h8768d477` | 修改失败 | src/main/java/com/mtxgdn/rest/AdminResource.java:1455 |
| `admin.h8889c2a8` | 已更新，地图缓存已热重载 | src/main/java/com/mtxgdn/rest/AdminResource.java:3372 |
| `admin.h89776548` | 角色移除成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:889 |
| `admin.h8cce6bb4` | 服务器正在关闭... | src/main/java/com/mtxgdn/rest/AdminResource.java:519 |
| `admin.h90e20fd8` | 该账号登录尝试过于频繁，请稍后再试 | src/main/java/com/mtxgdn/rest/AdminResource.java:131 |
| `admin.h963c8950` | 未找到玩家角色 | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:268 |
| `admin.h9b99bb43` | 创建失败 | src/main/java/com/mtxgdn/rest/AdminResource.java:967 |
| `admin.h9c54f628` | 登录尝试过于频繁，请稍后再试 | src/main/java/com/mtxgdn/rest/AdminResource.java:112 |
| `admin.h9d7e3c90` | 权限分配成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:1115 |
| `admin.ha80419fc` | 无效的QQ号 | src/main/java/com/mtxgdn/rest/AdminResource.java:2846 |
| `admin.ha9c62e68` | 新人奖励配置已保存 | src/main/java/com/mtxgdn/rest/AdminResource.java:2596 |
| `admin.hab204d78` | 增加量必须大于0 | src/main/java/com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:284 |
| `admin.hab63e3d4` | 用户不存在 | src/main/java/com/mtxgdn/rest/AdminResource.java:1037<br>src/main/java/com/mtxgdn/rest/AdminResource.java:997 |
| `admin.hacbe32d4` | qqNumber 和 userId 只能二选一 | src/main/java/com/mtxgdn/rest/AdminResource.java:2298 |
| `admin.hb05a7090` | 玩家角色不存在 | src/main/java/com/mtxgdn/rest/AdminResource.java:2746 |
| `admin.hbaa88444` | 未登录 | src/main/java/com/mtxgdn/rest/AdminResource.java:2653<br>src/main/java/com/mtxgdn/rest/AdminResource.java:2701<br>src/main/java/com/mtxgdn/rest/AdminResource.java:2715 等 4 处 |
| `admin.hbb03c877` | 名称不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:1438 |
| `admin.hbd2c7468` | 已删除地点及其连接/偏置，地图缓存已热重载 | src/main/java/com/mtxgdn/rest/AdminResource.java:3567 |
| `admin.hbd4d9090` | 权限组名称不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:654 |
| `admin.hbe2acdf0` | 路径解析失败 | src/main/java/com/mtxgdn/rest/AdminResource.java:433 |
| `admin.hc44dbf28` | 文件监听已停止 | src/main/java/com/mtxgdn/rest/AdminResource.java:469 |
| `admin.hc5532e10` | 禁言天数必须在1-30之间 | src/main/java/com/mtxgdn/rest/AdminResource.java:2416 |
| `admin.hcb640ff0` | command 参数不能为空 | src/main/java/com/mtxgdn/onebot/command/admin/DebugCommand.java:243 |
| `admin.hd0c6ad88` | 密码修改成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:1003 |
| `admin.hd2524528` | 记录不存在或未变更 | src/main/java/com/mtxgdn/rest/AdminResource.java:1947 |
| `admin.hd589e27c` | 已兑换或已过期的兑换码不允许编辑，只能删除 | src/main/java/com/mtxgdn/rest/AdminResource.java:2139 |
| `admin.hd6ef4b84` | 玩家数据已清除 | src/main/java/com/mtxgdn/rest/AdminResource.java:1156 |
| `admin.hd7921d08` | 请提供称号键(titleKey) | src/main/java/com/mtxgdn/rest/AdminResource.java:2462 |
| `admin.hd952c78c` | 群组配置已删除 | src/main/java/com/mtxgdn/rest/AdminResource.java:2439 |
| `admin.hda558f5c` | 用户名或密码错误 | src/main/java/com/mtxgdn/rest/AdminResource.java:177<br>src/main/java/com/mtxgdn/rest/AdminResource.java:214 |
| `admin.hdd8fbc40` | 用户名已存在 | src/main/java/com/mtxgdn/rest/AdminResource.java:931 |
| `admin.he0c1aa7c` | 全部数据已重置，默认数据已重新初始化 | src/main/java/com/mtxgdn/rest/AdminResource.java:1184 |
| `admin.he27be784` | 权限组权限已更新 | src/main/java/com/mtxgdn/rest/AdminResource.java:789 |
| `admin.he43121b0` | 权限移除成功 | src/main/java/com/mtxgdn/rest/AdminResource.java:1134 |
| `admin.he8f4ef00` | 称号key不能为空 | src/main/java/com/mtxgdn/rest/AdminResource.java:1662 |
| `admin.hed9893a3` | 适配器配置已更新并重载 | src/main/java/com/mtxgdn/rest/AdminResource.java:2934 |
| `admin.hf05cf8ec` | 非法的插件路径 | src/main/java/com/mtxgdn/rest/AdminResource.java:427 |
| `admin.hf651e4d3` | 非法的插件文件名（仅允许 plugins/ 目录下的 .jar 文件，禁止路径穿越） | src/main/java/com/mtxgdn/rest/AdminResource.java:416 |
| `admin.hf7bba770` | 适配器配置已重载 | src/main/java/com/mtxgdn/rest/AdminResource.java:2953 |
| `admin.hfc3d7aa4` | 该地点没有这条事件偏置 | src/main/java/com/mtxgdn/rest/AdminResource.java:3765 |
| `auth.h21c46d78` | 邮箱格式不正确 | src/main/java/com/mtxgdn/rest/Auth.java:192<br>src/main/java/com/mtxgdn/rest/Auth.java:322 |
| `auth.h45338de2` | 邮箱不能为空 | src/main/java/com/mtxgdn/rest/Auth.java:184<br>src/main/java/com/mtxgdn/rest/Auth.java:314<br>src/main/java/com/mtxgdn/rest/Auth.java:367 |
| `auth.h70c7a34a` | 验证码已发送，请查收邮件 | src/main/java/com/mtxgdn/rest/Auth.java:207<br>src/main/java/com/mtxgdn/rest/Auth.java:337 |
| `auth.h88dedbd9` | 验证码错误或已过期（连续错误 3 次将锁定） | src/main/java/com/mtxgdn/rest/Auth.java:395 |
| `auth.h9c54f628` | 登录尝试过于频繁，请稍后再试 | src/main/java/com/mtxgdn/rest/Auth.java:248<br>src/main/java/com/mtxgdn/rest/Auth.java:257 |
| `auth.ha9aa782f` | 验证码错误次数过多，已锁定，请 5 分钟后再试 | src/main/java/com/mtxgdn/rest/Auth.java:389 |
| `auth.hb273bcee` | 已安全登出 | src/main/java/com/mtxgdn/rest/Auth.java:298 |
| `auth.hb543df00` | 注销账号不可恢复，请输入当前密码以确认 | src/main/java/com/mtxgdn/rest/Auth.java:434 |
| `auth.hd2e81c54` | 发送验证码失败，请稍后重试 | src/main/java/com/mtxgdn/rest/Auth.java:213<br>src/main/java/com/mtxgdn/rest/Auth.java:343 |
| `auth.hd8155b98` | 验证码不能为空 | src/main/java/com/mtxgdn/rest/Auth.java:374 |
| `auth.hdb26d337` | 密码错误，注销被拒绝 | src/main/java/com/mtxgdn/rest/Auth.java:441 |
| `auth.he5c24b00` | 请先登录 | src/main/java/com/mtxgdn/rest/Auth.java:276<br>src/main/java/com/mtxgdn/rest/Auth.java:412 |
| `auth.hfbe8536f` | 新密码长度不能少于6位 | src/main/java/com/mtxgdn/rest/Auth.java:381 |
| `cave.h632647d8` | 你还没有洞府，请先开辟洞府（需要金丹期 + 300 灵石） | src/main/java/com/mtxgdn/onebot/command/cave/CaveCommand.java:45 |
| `crafting.ha0a6b46c` | 请提供有效的配方ID(recipeId) | src/main/java/com/mtxgdn/onebot/command/crafting/CraftCommand.java:223 |
| `economy.h3a017ec4` | 该物品没有转化价值 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:278 |
| `economy.h3c5e72d8` | 请提供有效的拍卖编号(listingId)和出价金额(amount) | src/main/java/com/mtxgdn/onebot/command/economy/AuctionCommand.java:222 |
| `economy.h41e94abc` | 请提供有效的 grade（0=下品→中品，1=中品→上品，2=上品→极品） | src/main/java/com/mtxgdn/onebot/command/economy/PurifyCommand.java:77 |
| `economy.h429191c6` | 购买失败，请稍后重试 | src/main/java/com/mtxgdn/onebot/command/economy/ShopCommand.java:105 |
| `economy.h4aa0a0f2` | 能量转化系统暂未开放 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:237<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:309<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:386 等 4 处 |
| `economy.h648cf5ac` | 请提供有效的灵石数量(stones) | src/main/java/com/mtxgdn/onebot/command/economy/CultivateBoostCommand.java:61 |
| `economy.h66110c74` | 能量扣除失败 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:357 |
| `economy.h84ddfb30` | 请提供存款类型(type: current/fixed_7/fixed_30/fixed_90)和金额(amount) | src/main/java/com/mtxgdn/onebot/command/economy/BankCommand.java:194 |
| `economy.h863a8dc5` | 转化成功 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:287 |
| `economy.h88ec9794` | 兑换成功 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:364 |
| `economy.h893a9a8c` | 数量必须大于 0 | src/main/java/com/mtxgdn/onebot/command/economy/RecycleCommand.java:68 |
| `economy.h9498536c` | 请提供有效的存款编号(depositId) | src/main/java/com/mtxgdn/onebot/command/economy/BankCommand.java:216 |
| `economy.h99e42a98` | 该物品没有转化价值，无法兑换 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:342 |
| `economy.hb69140ac` | 缺少商品编号 id | src/main/java/com/mtxgdn/onebot/command/economy/ShopCommand.java:93 |
| `economy.hc0d8a9c4` | 数量必须大于0 | src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:252<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:324 |
| `economy.he3b69f3c` | 请提供物品键(itemKey) | src/main/java/com/mtxgdn/onebot/command/economy/AuctionCommand.java:198<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:247<br>src/main/java/com/mtxgdn/onebot/command/economy/ExchangeCommand.java:319 等 4 处 |
| `formation.hcd888a30` | 你还没有布置阵法 | src/main/java/com/mtxgdn/onebot/command/formation/FormationCommand.java:48 |
| `game.hde5e9ffe` | 请先创建角色 | src/main/java/com/mtxgdn/rest/GameResource.java:522 |
| `install.h034956c0` | 服务器已完成安装。如需重置，请使用 /api/install/reset 接口。 | src/main/java/com/mtxgdn/rest/InstallResource.java:230 |
| `install.ha8545a2c` | 服务器即将重启，请稍候…（页面将自动刷新） | src/main/java/com/mtxgdn/rest/InstallResource.java:384 |
| `install.hddbe5808` | 配置已清除，服务器即将重启，请重新运行安装向导… | src/main/java/com/mtxgdn/rest/InstallResource.java:354 |
| `item_give.hb22bfab6` | 请提供玩家名(playerName)和物品键(itemKey) | src/main/java/com/mtxgdn/onebot/command/item/ItemGiveCommand.java:97 |
| `item_give.hc0d8a9c4` | 数量必须大于0 | src/main/java/com/mtxgdn/onebot/command/item/ItemGiveCommand.java:102 |
| `map.h4ee257a2` | 用 POST /api/game/map/travel/{locationId} 移动，POST /api/game/map/gather 采集 | src/main/java/com/mtxgdn/onebot/command/map/WildernessCommand.java:309 |
| `map.h59a76b2c` | 角色不存在 | src/main/java/com/mtxgdn/onebot/command/map/WildernessCommand.java:203 |
| `map.h9a54ad7b` | 野外地图当前已关闭（可在 config/game/wilderness_map.json 里把 enabled 改为 true） | src/main/java/com/mtxgdn/onebot/command/map/WildernessCommand.java:195 |
| `map.hbef067da` | 你所在的位置已不在世界边界之内，请联系管理员 | src/main/java/com/mtxgdn/onebot/command/map/WildernessCommand.java:247 |
| `map.hd682f747` | 你正站在已知世界，可从 gateways 里的出境口踏入荒域 | src/main/java/com/mtxgdn/onebot/command/map/WildernessCommand.java:239 |
| `market.h4011186c` | 请提供 itemKey 查询参数 | src/main/java/com/mtxgdn/onebot/command/market/MarketPriceCommand.java:99 |
| `oauth.h6419f8f8` | 授权码（authorization code）流程未实现 —— 本 OAuth 2.0 支持仅提供 client_credentials 令牌端点与受保护资源端点 | src/main/java/com/mtxgdn/rest/OAuthResource.java:202 |
| `redeem.h2128a290` | 兑换成功！ | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:112 |
| `redeem.h3a7bedb4` | 请求体不能为空 | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:90 |
| `redeem.h4616a2ab` | 请提供兑换码 | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:98 |
| `redeem.h9e82cff4` | 请先进入游戏 | src/main/java/com/mtxgdn/onebot/command/item/RedeemCommand.java:105 |
| `technique.hfc59992c` | 请提供有效的功法ID(techniqueId) | src/main/java/com/mtxgdn/onebot/command/technique/TechniqueCommand.java:196<br>src/main/java/com/mtxgdn/onebot/command/technique/TechniqueCommand.java:230 |
| `unified_rest.h2177df77` | 服务器内部错误，请稍后重试 | src/main/java/com/mtxgdn/rest/UnifiedRestResource.java:161 |
| `user.h536a6d04` | 密码重置成功，请使用新密码登录 | src/main/java/com/mtxgdn/service/UserService.java:479 |
| `user.h723a7388` | 账户注销成功 | src/main/java/com/mtxgdn/service/UserService.java:451 |
| `user.hd0c6ad88` | 密码修改成功 | src/main/java/com/mtxgdn/service/UserService.java:403 |
| `websocket.h39800675` | 连接成功，欢迎进入修仙世界！ | src/main/java/com/mtxgdn/websocket/GameWebSocketApp.java:213 |
