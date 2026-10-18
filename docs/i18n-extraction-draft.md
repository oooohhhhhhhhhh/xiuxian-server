# A 类中文字符串抽取草稿

> 本文件由 `node tools/i18n/extract-draft.js --write` 生成，**仅作草稿**。
> `key` 一律为 `null`：键名必须人审后填写，自动命名只会产生垃圾键。
> 确认后把 `key` + `text` 写入 `src/main/java/data/mtxgdn/lang/zh_cn.json`，
> 再把源码里的中文字面量替换为 `I18n.t("key")`。

共 **40** 个模块、998 条待迁移字面量（已按文本去重）。

| 类型 | 数量 | 说明 |
| --- | --- | --- |
| reply | 329 | 聊天平台指令回复 |
| result | 384 | Service 返回给玩家的 message |
| response | 245 | REST/WS 响应 message |
| error | 40 | GameErrorCode |

## admin

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `清除失败:` | `com/mtxgdn/onebot/command/admin/ClearPlayersDbCommand.java:25` |
| _待填_ | `请先绑定账号。` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:32` |
| _待填_ | `权限不足，你无权使用此功能。` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:36` |
| _待填_ | `未知子命令:` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:60` |
| _待填_ | `\n使用 /debug help 查看帮助` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:60` |
| _待填_ | `用法: /debug test <指令名> [参数]\n示例: /debug test status` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:100` |
| _待填_ | `未找到指令:` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:113` |
| _待填_ | `\n使用 /debug list 查看所有指令` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:113` |
| _待填_ | `正在测试指令:` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:117` |
| _待填_ | `参数:` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:118` |
| _待填_ | `(无)` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:118` |
| _待填_ | `注意: 测试操作不会修改真实数据（事务已回滚）` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:119` |
| _待填_ | `测试失败: 未绑定QQ` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:124` |
| _待填_ | `测试失败: 未找到玩家角色` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:130` |
| _待填_ | `指令测试完成（事务已回滚）` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:145` |
| _待填_ | `用法: /编辑玩家 <玩家ID> <属性>=<值> ...\n` | `com/mtxgdn/onebot/command/admin/EditPlayerCommand.java:33` |
| _待填_ | `参数不足。用法: /编辑玩家 <玩家ID> <属性>=<值> ...` | `com/mtxgdn/onebot/command/admin/EditPlayerCommand.java:41` |
| _待填_ | `玩家ID必须为数字。` | `com/mtxgdn/onebot/command/admin/EditPlayerCommand.java:49` |
| _待填_ | `未找到玩家 ID:` | `com/mtxgdn/onebot/command/admin/EditPlayerCommand.java:56` |
| _待填_ | `能量转化系统暂未开放，请在 config/plugins.yml 中将 experimental_energy_exchange 设为 true（或在 WebUI「插件管理」页启用该实验性插件）。` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:47` |
| _待填_ | `用法: /能量管理 <玩家ID> <设置\|增加\|减少\|查看> [数值]\n` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:53` |
| _待填_ | `参数不足。用法: /能量管理 <玩家ID> <设置\|增加\|减少\|查看> [数值]` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:64` |
| _待填_ | `权限不足：修改玩家能量需要 admin.users.manage 权限（仅超级管理员）。` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:91` |
| _待填_ | `玩家` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:99` |
| _待填_ | `) 当前能量:` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:99` |
| _待填_ | `请提供要设置的能量值。用法: /能量管理` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:105` |
| _待填_ | `设置 <数值>` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:105` |
| _待填_ | `数值必须为整数。` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:112` |
| _待填_ | `能量值不能为负数。` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:116` |
| _待填_ | `已将玩家` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:120` |
| _待填_ | `) 的能量设置为` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:120` |
| _待填_ | `请提供要增加的能量值。用法: /能量管理` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:126` |
| _待填_ | `增加 <数值>` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:126` |
| _待填_ | `增加量必须大于0。` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:137` |
| _待填_ | `已为玩家` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:141` |
| _待填_ | `) 增加` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:141` |
| _待填_ | `能量\n当前能量:` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:141` |
| _待填_ | `请提供要减少的能量值。用法: /能量管理` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:147` |
| _待填_ | `减少 <数值>` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:147` |
| _待填_ | `减少量必须大于0。` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:158` |
| _待填_ | `能量不足，无法减少。玩家当前能量:` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:163` |
| _待填_ | `，尝试减少:` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:163` |
| _待填_ | `已从玩家` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:167` |
| _待填_ | `) 减少` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:167` |
| _待填_ | `未知操作:` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:171` |
| _待填_ | `\n可用操作: 查看, 设置, 增加, 减少` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:171` |
| _待填_ | `正在检查并修复装备数据一致性，请稍候...` | `com/mtxgdn/onebot/command/admin/FixEquipmentCommand.java:27` |
| _待填_ | `装备数据修复完成！` | `com/mtxgdn/onebot/command/admin/FixEquipmentCommand.java:32` |
| _待填_ | `用法: /发放 <玩家ID> 金币=<数量> 灵力=<数量> 灵石=<数量> [物品key]=<数量>\n示例: /发放 1 金币=1000 灵石=500 SpiritStone=50` | `com/mtxgdn/onebot/command/admin/GiveCommand.java:37` |
| _待填_ | `参数不足。用法: /发放 <玩家ID> 金币=<数量> 灵力=<数量> 灵石=<数量> [物品key]=<数量>` | `com/mtxgdn/onebot/command/admin/GiveCommand.java:43` |
| _待填_ | `暂无日志。` | `com/mtxgdn/onebot/command/admin/LogsCommand.java:51` |
| _待填_ | `用法: /玩家详情 <玩家ID或角色名>` | `com/mtxgdn/onebot/command/admin/PlayerDetailCommand.java:36` |
| _待填_ | `未找到玩家:` | `com/mtxgdn/onebot/command/admin/PlayerDetailCommand.java:50` |
| _待填_ | `搜索 \"` | `com/mtxgdn/onebot/command/admin/PlayersListCommand.java:50` |
| _待填_ | `\" 结果` | `com/mtxgdn/onebot/command/admin/PlayersListCommand.java:50` |
| _待填_ | `重置失败:` | `com/mtxgdn/onebot/command/admin/ResetAllDbCommand.java:25` |
| _待填_ | `权限不足，仅 SUPER_ADMIN 可以执行此操作。` | `com/mtxgdn/onebot/command/admin/RestartCommand.java:38` |
| _待填_ | `服务器正在重启...` | `com/mtxgdn/onebot/command/admin/RestartCommand.java:41` |
| _待填_ | `用法: /修改灵根 <玩家ID> <灵根名称>\n` | `com/mtxgdn/onebot/command/admin/SetRootCommand.java:34` |
| _待填_ | `无效的灵根名称:` | `com/mtxgdn/onebot/command/admin/SetRootCommand.java:73` |
| _待填_ | `服务器正在关闭...` | `com/mtxgdn/onebot/command/admin/ShutdownCommand.java:29` |
| _待填_ | `请先绑定账号。\n私聊使用 /bind` | `com/mtxgdn/onebot/command/admin/TraceCommand.java:25` |
| _待填_ | `权限不足，你只能查看自己的轨迹。\n使用 /轨迹 查看自己的操作记录。` | `com/mtxgdn/onebot/command/admin/TraceCommand.java:48` |
| _待填_ | `权限不足，无法查看该用户的操作轨迹。` | `com/mtxgdn/onebot/command/admin/TraceCommand.java:71` |
| _待填_ | `暂无操作轨迹记录。` | `com/mtxgdn/onebot/command/admin/TraceCommand.java:102` |

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `command 参数不能为空` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:243` |
| _待填_ | `未找到指令:` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:254` |
| _待填_ | `需要登录用户才能测试指令` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:261` |
| _待填_ | `未找到玩家角色` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:268` |
| _待填_ | `指令测试完成` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:279` |
| _待填_ | `指令测试异常:` | `com/mtxgdn/onebot/command/admin/DebugCommand.java:286` |
| _待填_ | `能量转化系统暂未开放` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:190` |
| _待填_ | `玩家不存在:` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:209` |
| _待填_ | `服务器错误:` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:222` |
| _待填_ | `能量值不能为负数` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:242` |
| _待填_ | `设置成功` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:258` |
| _待填_ | `增加量必须大于0` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:284` |
| _待填_ | `增加成功` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:300` |
| _待填_ | `减少量必须大于0` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:327` |
| _待填_ | `能量不足，当前` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:343` |
| _待填_ | `，尝试减少` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:343` |
| _待填_ | `减少成功` | `com/mtxgdn/onebot/command/admin/EnergyAdminCommand.java:350` |

## admin_resource

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `登录尝试过于频繁，请稍后再试` | `com/mtxgdn/rest/AdminResource.java:110` |
| _待填_ | `用户名和密码不能为空` | `com/mtxgdn/rest/AdminResource.java:121` |
| _待填_ | `该账号登录尝试过于频繁，请稍后再试` | `com/mtxgdn/rest/AdminResource.java:129` |
| _待填_ | `用户名或密码错误` | `com/mtxgdn/rest/AdminResource.java:175` |
| _待填_ | `无管理后台权限，请联系超级管理员分配角色` | `com/mtxgdn/rest/AdminResource.java:182` |
| _待填_ | `插件已重载:` | `com/mtxgdn/rest/AdminResource.java:339` |
| _待填_ | `插件不存在:` | `com/mtxgdn/rest/AdminResource.java:339` |
| _待填_ | `插件已卸载:` | `com/mtxgdn/rest/AdminResource.java:351` |
| _待填_ | `插件已启用:` | `com/mtxgdn/rest/AdminResource.java:363` |
| _待填_ | `启用失败:` | `com/mtxgdn/rest/AdminResource.java:363` |
| _待填_ | `插件已停用:` | `com/mtxgdn/rest/AdminResource.java:375` |
| _待填_ | `停用失败:` | `com/mtxgdn/rest/AdminResource.java:375` |
| _待填_ | `jarName 不能为空` | `com/mtxgdn/rest/AdminResource.java:407` |
| _待填_ | `非法的插件文件名（仅允许 plugins/ 目录下的 .jar 文件，禁止路径穿越）` | `com/mtxgdn/rest/AdminResource.java:414` |
| _待填_ | `非法的插件路径` | `com/mtxgdn/rest/AdminResource.java:425` |
| _待填_ | `路径解析失败` | `com/mtxgdn/rest/AdminResource.java:431` |
| _待填_ | `插件文件不存在:` | `com/mtxgdn/rest/AdminResource.java:437` |
| _待填_ | `插件加载成功:` | `com/mtxgdn/rest/AdminResource.java:443` |
| _待填_ | `插件加载失败:` | `com/mtxgdn/rest/AdminResource.java:443` |
| _待填_ | `文件监听已启动` | `com/mtxgdn/rest/AdminResource.java:455` |
| _待填_ | `文件监听已停止` | `com/mtxgdn/rest/AdminResource.java:467` |
| _待填_ | `服务器正在关闭...` | `com/mtxgdn/rest/AdminResource.java:517` |
| _待填_ | `权限组名称不能为空` | `com/mtxgdn/rest/AdminResource.java:652` |
| _待填_ | `权限组创建成功` | `com/mtxgdn/rest/AdminResource.java:660` |
| _待填_ | `权限组更新成功` | `com/mtxgdn/rest/AdminResource.java:689` |
| _待填_ | `权限组已删除` | `com/mtxgdn/rest/AdminResource.java:708` |
| _待填_ | `权限码不能为空` | `com/mtxgdn/rest/AdminResource.java:730` |
| _待填_ | `权限已添加到权限组` | `com/mtxgdn/rest/AdminResource.java:738` |
| _待填_ | `权限已从权限组移除` | `com/mtxgdn/rest/AdminResource.java:759` |
| _待填_ | `权限组权限已更新` | `com/mtxgdn/rest/AdminResource.java:787` |
| _待填_ | `无效的角色名` | `com/mtxgdn/rest/AdminResource.java:855` |
| _待填_ | `角色分配成功` | `com/mtxgdn/rest/AdminResource.java:863` |
| _待填_ | `角色移除成功` | `com/mtxgdn/rest/AdminResource.java:887` |
| _待填_ | `用户名不能为空` | `com/mtxgdn/rest/AdminResource.java:905` |
| _待填_ | `密码长度不能少于6位` | `com/mtxgdn/rest/AdminResource.java:911` |
| _待填_ | `用户名已存在` | `com/mtxgdn/rest/AdminResource.java:929` |
| _待填_ | `管理员账号创建成功` | `com/mtxgdn/rest/AdminResource.java:948` |
| _待填_ | `创建失败:` | `com/mtxgdn/rest/AdminResource.java:959` |
| _待填_ | `创建失败` | `com/mtxgdn/rest/AdminResource.java:965` |
| _待填_ | `用户不存在` | `com/mtxgdn/rest/AdminResource.java:995` |
| _待填_ | `密码修改成功` | `com/mtxgdn/rest/AdminResource.java:1001` |
| _待填_ | `修改失败:` | `com/mtxgdn/rest/AdminResource.java:1006` |
| _待填_ | `用户删除成功` | `com/mtxgdn/rest/AdminResource.java:1043` |
| _待填_ | `删除失败:` | `com/mtxgdn/rest/AdminResource.java:1054` |
| _待填_ | `无效的权限码:` | `com/mtxgdn/rest/AdminResource.java:1105` |
| _待填_ | `权限分配成功` | `com/mtxgdn/rest/AdminResource.java:1113` |
| _待填_ | `分配失败:` | `com/mtxgdn/rest/AdminResource.java:1118` |
| _待填_ | `权限移除成功` | `com/mtxgdn/rest/AdminResource.java:1132` |
| _待填_ | `移除失败:` | `com/mtxgdn/rest/AdminResource.java:1137` |
| _待填_ | `玩家数据已清除` | `com/mtxgdn/rest/AdminResource.java:1154` |
| _待填_ | `全部数据已重置，默认数据已重新初始化` | `com/mtxgdn/rest/AdminResource.java:1182` |
| _待填_ | `玩家不存在` | `com/mtxgdn/rest/AdminResource.java:1256` |
| _待填_ | `已发放:` | `com/mtxgdn/rest/AdminResource.java:1347` |
| _待填_ | `没有可发放的内容` | `com/mtxgdn/rest/AdminResource.java:1347` |
| _待填_ | `编辑成功` | `com/mtxgdn/rest/AdminResource.java:1385` |
| _待填_ | `删除成功` | `com/mtxgdn/rest/AdminResource.java:1406` |
| _待填_ | `删除失败` | `com/mtxgdn/rest/AdminResource.java:1411` |
| _待填_ | `名称不能为空` | `com/mtxgdn/rest/AdminResource.java:1436` |
| _待填_ | `名称修改成功` | `com/mtxgdn/rest/AdminResource.java:1446` |
| _待填_ | `修改失败` | `com/mtxgdn/rest/AdminResource.java:1453` |
| _待填_ | `请提供灵根名称` | `com/mtxgdn/rest/AdminResource.java:1477` |
| _待填_ | `灵根已修改为` | `com/mtxgdn/rest/AdminResource.java:1488` |
| _待填_ | `（` | `com/mtxgdn/rest/AdminResource.java:1488` |
| _待填_ | `）` | `com/mtxgdn/rest/AdminResource.java:1488` |
| _待填_ | `无效的灵根名称:` | `com/mtxgdn/rest/AdminResource.java:1493` |
| _待填_ | `，可用灵根:` | `com/mtxgdn/rest/AdminResource.java:1493` |
| _待填_ | `称号key不能为空` | `com/mtxgdn/rest/AdminResource.java:1660` |
| _待填_ | `获取表列表失败:` | `com/mtxgdn/rest/AdminResource.java:1752` |
| _待填_ | `查询失败:` | `com/mtxgdn/rest/AdminResource.java:1812` |
| _待填_ | `记录不存在` | `com/mtxgdn/rest/AdminResource.java:1829` |
| _待填_ | `新增成功` | `com/mtxgdn/rest/AdminResource.java:1893` |
| _待填_ | `新增失败:` | `com/mtxgdn/rest/AdminResource.java:1904` |
| _待填_ | `更新成功` | `com/mtxgdn/rest/AdminResource.java:1942` |
| _待填_ | `记录不存在或未变更` | `com/mtxgdn/rest/AdminResource.java:1945` |
| _待填_ | `更新失败:` | `com/mtxgdn/rest/AdminResource.java:1956` |
| _待填_ | `备份失败:` | `com/mtxgdn/rest/AdminResource.java:2012` |
| _待填_ | `数据导入成功` | `com/mtxgdn/rest/AdminResource.java:2033` |
| _待填_ | `导入失败:` | `com/mtxgdn/rest/AdminResource.java:2050` |
| _待填_ | `兑换码不能为空` | `com/mtxgdn/rest/AdminResource.java:2097` |
| _待填_ | `兑换码创建成功` | `com/mtxgdn/rest/AdminResource.java:2114` |
| _待填_ | `已兑换或已过期的兑换码不允许编辑，只能删除` | `com/mtxgdn/rest/AdminResource.java:2137` |
| _待填_ | `兑换码更新成功` | `com/mtxgdn/rest/AdminResource.java:2162` |
| _待填_ | `兑换码已删除` | `com/mtxgdn/rest/AdminResource.java:2181` |
| _待填_ | `qqNumber 和 userId 必须填写其中一个` | `com/mtxgdn/rest/AdminResource.java:2290` |
| _待填_ | `qqNumber 和 userId 只能二选一` | `com/mtxgdn/rest/AdminResource.java:2296` |
| _待填_ | `已通过用户ID添加到黑名单` | `com/mtxgdn/rest/AdminResource.java:2306` |
| _待填_ | `已通过QQ号添加到黑名单` | `com/mtxgdn/rest/AdminResource.java:2306` |
| _待填_ | `已从黑名单移除` | `com/mtxgdn/rest/AdminResource.java:2326` |
| _待填_ | `已启用自动禁言` | `com/mtxgdn/rest/AdminResource.java:2398` |
| _待填_ | `已关闭自动禁言` | `com/mtxgdn/rest/AdminResource.java:2398` |
| _待填_ | `禁言天数必须在1-30之间` | `com/mtxgdn/rest/AdminResource.java:2414` |
| _待填_ | `禁言天数已设置为` | `com/mtxgdn/rest/AdminResource.java:2423` |
| _待填_ | `天` | `com/mtxgdn/rest/AdminResource.java:2423` |
| _待填_ | `群组配置已删除` | `com/mtxgdn/rest/AdminResource.java:2437` |
| _待填_ | `请提供称号键(titleKey)` | `com/mtxgdn/rest/AdminResource.java:2460` |
| _待填_ | `新人奖励配置已保存` | `com/mtxgdn/rest/AdminResource.java:2594` |
| _待填_ | `保存失败:` | `com/mtxgdn/rest/AdminResource.java:2599` |
| _待填_ | `未登录` | `com/mtxgdn/rest/AdminResource.java:2651` |
| _待填_ | `获取用户信息失败` | `com/mtxgdn/rest/AdminResource.java:2694` |
| _待填_ | `玩家角色不存在` | `com/mtxgdn/rest/AdminResource.java:2744` |
| _待填_ | `获取玩家信息失败:` | `com/mtxgdn/rest/AdminResource.java:2788` |
| _待填_ | `公告内容不能为空` | `com/mtxgdn/rest/AdminResource.java:2805` |
| _待填_ | `公告已发送` | `com/mtxgdn/rest/AdminResource.java:2817` |
| _待填_ | `QQ号和权限等级不能为空` | `com/mtxgdn/rest/AdminResource.java:2834` |
| _待填_ | `无效的QQ号` | `com/mtxgdn/rest/AdminResource.java:2844` |
| _待填_ | `权限设置成功` | `com/mtxgdn/rest/AdminResource.java:2852` |
| _待填_ | `设置失败:` | `com/mtxgdn/rest/AdminResource.java:2857` |
| _待填_ | `无效的适配器配置` | `com/mtxgdn/rest/AdminResource.java:2921` |
| _待填_ | `适配器配置已更新并重载` | `com/mtxgdn/rest/AdminResource.java:2932` |
| _待填_ | `适配器配置已重载` | `com/mtxgdn/rest/AdminResource.java:2951` |
| _待填_ | `重载失败:` | `com/mtxgdn/rest/AdminResource.java:2956` |
| _待填_ | `已导出` | `com/mtxgdn/rest/AdminResource.java:2985` |
| _待填_ | `名玩家数据，失败` | `com/mtxgdn/rest/AdminResource.java:2985` |
| _待填_ | `个` | `com/mtxgdn/rest/AdminResource.java:2985` |
| _待填_ | `更新 OAuth2 配置失败:` | `com/mtxgdn/rest/AdminResource.java:3067` |
| _待填_ | `获取地图数据失败:` | `com/mtxgdn/rest/AdminResource.java:3171` |
| _待填_ | `地点不存在:` | `com/mtxgdn/rest/AdminResource.java:3267` |
| _待填_ | `已更新，地图缓存已热重载` | `com/mtxgdn/rest/AdminResource.java:3277` |
| _待填_ | `更新地点失败:` | `com/mtxgdn/rest/AdminResource.java:3284` |
| _待填_ | `已新建地点「` | `com/mtxgdn/rest/AdminResource.java:3391` |
| _待填_ | `」(id=` | `com/mtxgdn/rest/AdminResource.java:3391` |
| _待填_ | `新建地点失败:` | `com/mtxgdn/rest/AdminResource.java:3399` |
| _待填_ | `已删除地点及其连接/偏置，地图缓存已热重载` | `com/mtxgdn/rest/AdminResource.java:3472` |
| _待填_ | `删除地点失败:` | `com/mtxgdn/rest/AdminResource.java:3479` |
| _待填_ | `保存连接失败:` | `com/mtxgdn/rest/AdminResource.java:3535` |
| _待填_ | `连接不存在:` | `com/mtxgdn/rest/AdminResource.java:3567` |
| _待填_ | `已删除` | `com/mtxgdn/rest/AdminResource.java:3576` |
| _待填_ | `条连接，地图缓存已热重载` | `com/mtxgdn/rest/AdminResource.java:3576` |
| _待填_ | `删除连接失败:` | `com/mtxgdn/rest/AdminResource.java:3583` |
| _待填_ | `已保存事件偏置，地图缓存已热重载` | `com/mtxgdn/rest/AdminResource.java:3636` |
| _待填_ | `保存事件偏置失败:` | `com/mtxgdn/rest/AdminResource.java:3643` |
| _待填_ | `该地点没有这条事件偏置` | `com/mtxgdn/rest/AdminResource.java:3670` |
| _待填_ | `已删除事件偏置，地图缓存已热重载` | `com/mtxgdn/rest/AdminResource.java:3679` |
| _待填_ | `删除事件偏置失败:` | `com/mtxgdn/rest/AdminResource.java:3686` |
| _待填_ | `已给` | `com/mtxgdn/rest/AdminResource.java:3706` |
| _待填_ | `个野外分片原地补货（地形与探索记录均未变动）` | `com/mtxgdn/rest/AdminResource.java:3706` |
| _待填_ | `野外补货失败:` | `com/mtxgdn/rest/AdminResource.java:3712` |
| _待填_ | `读取野外分片失败:` | `com/mtxgdn/rest/AdminResource.java:3760` |
| _待填_ | `获取坊市挂单失败:` | `com/mtxgdn/rest/AdminResource.java:3960` |
| _待填_ | `请提供 itemKey 查询参数` | `com/mtxgdn/rest/AdminResource.java:3982` |
| _待填_ | `查询行情失败:` | `com/mtxgdn/rest/AdminResource.java:4009` |

## auth

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `邮箱不能为空` | `com/mtxgdn/rest/Auth.java:184` |
| _待填_ | `邮箱格式不正确` | `com/mtxgdn/rest/Auth.java:192` |
| _待填_ | `验证码已发送，请查收邮件` | `com/mtxgdn/rest/Auth.java:207` |
| _待填_ | `发送验证码失败，请稍后重试` | `com/mtxgdn/rest/Auth.java:213` |
| _待填_ | `发送验证码失败：` | `com/mtxgdn/rest/Auth.java:219` |
| _待填_ | `登录尝试过于频繁，请稍后再试` | `com/mtxgdn/rest/Auth.java:248` |
| _待填_ | `请先登录` | `com/mtxgdn/rest/Auth.java:276` |
| _待填_ | `已安全登出` | `com/mtxgdn/rest/Auth.java:298` |
| _待填_ | `验证码不能为空` | `com/mtxgdn/rest/Auth.java:374` |
| _待填_ | `新密码长度不能少于6位` | `com/mtxgdn/rest/Auth.java:381` |
| _待填_ | `验证码错误次数过多，已锁定，请 5 分钟后再试` | `com/mtxgdn/rest/Auth.java:389` |
| _待填_ | `验证码错误或已过期（连续错误 3 次将锁定）` | `com/mtxgdn/rest/Auth.java:395` |
| _待填_ | `注销账号不可恢复，请输入当前密码以确认` | `com/mtxgdn/rest/Auth.java:434` |
| _待填_ | `密码错误，注销被拒绝` | `com/mtxgdn/rest/Auth.java:441` |

## cave

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `你还没有洞府。\n使用 /洞府 create [名称] 开辟洞府（需要金丹期+300灵石）` | `com/mtxgdn/onebot/command/cave/CaveCommand.java:176` |

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `你还没有洞府，请先开辟洞府（需要金丹期 + 300 灵石）` | `com/mtxgdn/onebot/command/cave/CaveCommand.java:45` |
| _待填_ | `服务器错误:` | `com/mtxgdn/onebot/command/cave/CaveCommand.java:65` |

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/CaveService.java:58` |
| _待填_ | `境界不足，需要达到金丹期以上才能开辟洞府（当前：` | `com/mtxgdn/game/service/CaveService.java:66` |
| _待填_ | `）` | `com/mtxgdn/game/service/CaveService.java:66` |
| _待填_ | `你已经拥有洞府了` | `com/mtxgdn/game/service/CaveService.java:72` |
| _待填_ | `灵石不足，开辟洞府需要` | `com/mtxgdn/game/service/CaveService.java:79` |
| _待填_ | `灵石（你目前有` | `com/mtxgdn/game/service/CaveService.java:79` |
| _待填_ | `灵石）` | `com/mtxgdn/game/service/CaveService.java:79` |
| _待填_ | `恭喜【` | `com/mtxgdn/game/service/CaveService.java:118` |
| _待填_ | `】开辟洞府【` | `com/mtxgdn/game/service/CaveService.java:118` |
| _待填_ | `】！消耗灵石` | `com/mtxgdn/game/service/CaveService.java:118` |
| _待填_ | `开辟洞府失败` | `com/mtxgdn/game/service/CaveService.java:123` |
| _待填_ | `你还没有洞府` | `com/mtxgdn/game/service/CaveService.java:147` |
| _待填_ | `灵气尚未汇聚，还需等待` | `com/mtxgdn/game/service/CaveService.java:198` |
| _待填_ | `秒` | `com/mtxgdn/game/service/CaveService.java:198` |
| _待填_ | `洞府灵气不足，需要等待灵气汇聚` | `com/mtxgdn/game/service/CaveService.java:201` |
| _待填_ | `收集洞府灵气获得` | `com/mtxgdn/game/service/CaveService.java:207` |
| _待填_ | `灵石！` | `com/mtxgdn/game/service/CaveService.java:207` |
| _待填_ | `你还没有洞府，无法在此修炼` | `com/mtxgdn/game/service/CaveService.java:219` |
| _待填_ | `洞府已达到最高等级` | `com/mtxgdn/game/service/CaveService.java:287` |
| _待填_ | `级` | `com/mtxgdn/game/service/CaveService.java:287` |
| _待填_ | `灵石不足，升级需要` | `com/mtxgdn/game/service/CaveService.java:295` |
| _待填_ | `洞府【` | `com/mtxgdn/game/service/CaveService.java:319` |
| _待填_ | `】晋升为` | `com/mtxgdn/game/service/CaveService.java:319` |
| _待填_ | `级！\n` | `com/mtxgdn/game/service/CaveService.java:319` |

## combat

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `未知策略:` | `com/mtxgdn/onebot/command/combat/BattleTacticCommand.java:36` |
| _待填_ | `\n可用策略: 猛攻 / 均衡 / 防守` | `com/mtxgdn/onebot/command/combat/BattleTacticCommand.java:36` |
| _待填_ | `战斗策略已变更：\n` | `com/mtxgdn/onebot/command/combat/BattleTacticCommand.java:48` |
| _待填_ | `当前战斗策略：\n` | `com/mtxgdn/onebot/command/combat/BattleTacticCommand.java:62` |
| _待填_ | `\n\n修改: /战斗策略 <猛攻\|均衡\|防守>` | `com/mtxgdn/onebot/command/combat/BattleTacticCommand.java:62` |
| _待填_ | `用法: /学习 <技能名称或ID>\n先用 /技能 查看可用技能列表。` | `com/mtxgdn/onebot/command/combat/LearnSkillCommand.java:23` |
| _待填_ | `找不到技能:` | `com/mtxgdn/onebot/command/combat/LearnSkillCommand.java:32` |
| _待填_ | `\n请使用 /技能 查看可用技能列表。` | `com/mtxgdn/onebot/command/combat/LearnSkillCommand.java:32` |
| _待填_ | `学习成功！\n学会了【` | `com/mtxgdn/onebot/command/combat/LearnSkillCommand.java:42` |
| _待填_ | `】！` | `com/mtxgdn/onebot/command/combat/LearnSkillCommand.java:42` |
| _待填_ | `学习失败:` | `com/mtxgdn/onebot/command/combat/LearnSkillCommand.java:44` |
| _待填_ | `没有待接受的挑战` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:35` |
| _待填_ | `你拒绝了【` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:37` |
| _待填_ | `】的切磋挑战` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:37` |
| _待填_ | `用法: /挑战 <角色名>\n` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:50` |
| _待填_ | `\n💡 也可用 /挑战 接受 或 /挑战 拒绝 回应切磋` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:50` |
| _待填_ | `找不到玩家:` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:64` |
| _待填_ | `不能挑战自己。` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:66` |
| _待填_ | `⚔ 你向【` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:78` |
| _待填_ | `】发起了切磋挑战！` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:78` |
| _待填_ | `\n等待对方回应中（30秒）...` | `com/mtxgdn/onebot/command/combat/PvpCommand.java:78` |
| _待填_ | `暂无可用技能。` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:29` |
| _待填_ | `用法: /技能 升级 <技能ID>` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:45` |
| _待填_ | `技能ID无效，请输入数字` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:53` |
| _待填_ | `你尚未学习该技能，无法升级` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:60` |
| _待填_ | `技能【` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:67` |
| _待填_ | `】已达满级` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:67` |
| _待填_ | `，无法继续升级` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:67` |
| _待填_ | `熟练度不足！技能【` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:75` |
| _待填_ | `】当前等级` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:75` |
| _待填_ | `升级失败:` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:93` |
| _待填_ | `升级成功！技能【` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:97` |
| _待填_ | `】从` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:97` |
| _待填_ | `级升至` | `com/mtxgdn/onebot/command/combat/SkillsCommand.java:97` |

## command

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `权限不足` | `com/mtxgdn/common/command/Command.java:154` |
| _待填_ | `用法:` | `com/mtxgdn/common/command/Command.java:180` |
| _待填_ | `未知子命令:` | `com/mtxgdn/common/command/Command.java:186` |
| _待填_ | `\n用法:` | `com/mtxgdn/common/command/Command.java:186` |

## crafting

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `用法: /合成 配方 <分类>\n分类: PILL(丹药) / EQUIPMENT(装备) / CONSUMABLE(消耗品) / SEED(种子)` | `com/mtxgdn/onebot/command/crafting/CraftCommand.java:94` |
| _待填_ | `无效分类:` | `com/mtxgdn/onebot/command/crafting/CraftCommand.java:100` |
| _待填_ | `\n可用: PILL / EQUIPMENT / CONSUMABLE / SEED` | `com/mtxgdn/onebot/command/crafting/CraftCommand.java:100` |
| _待填_ | `用法: /合成 制造 <配方ID>\n先用 /合成 列表 查看配方` | `com/mtxgdn/onebot/command/crafting/CraftCommand.java:122` |
| _待填_ | `配方ID无效` | `com/mtxgdn/onebot/command/crafting/CraftCommand.java:124` |

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `无效的配方分类:` | `com/mtxgdn/onebot/command/crafting/CraftCommand.java:180` |
| _待填_ | `服务器错误:` | `com/mtxgdn/onebot/command/crafting/CraftCommand.java:211` |
| _待填_ | `请提供有效的配方ID(recipeId)` | `com/mtxgdn/onebot/command/crafting/CraftCommand.java:223` |

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `配方不存在` | `com/mtxgdn/game/service/CraftingService.java:61` |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/CraftingService.java:66` |
| _待填_ | `境界不足，需要更高境界才能使用此配方` | `com/mtxgdn/game/service/CraftingService.java:69` |
| _待填_ | `材料不足：需要` | `com/mtxgdn/game/service/CraftingService.java:82` |
| _待填_ | `，拥有` | `com/mtxgdn/game/service/CraftingService.java:82` |
| _待填_ | `金币不足，制造需要` | `com/mtxgdn/game/service/CraftingService.java:88` |
| _待填_ | `金币` | `com/mtxgdn/game/service/CraftingService.java:88` |
| _待填_ | `灵石不足，制造需要` | `com/mtxgdn/game/service/CraftingService.java:92` |
| _待填_ | `灵石` | `com/mtxgdn/game/service/CraftingService.java:92` |
| _待填_ | `制造失败...材料已消耗，但参悟中获得了` | `com/mtxgdn/game/service/CraftingService.java:170` |
| _待填_ | `点经验` | `com/mtxgdn/game/service/CraftingService.java:170` |
| _待填_ | `制造失败:` | `com/mtxgdn/game/service/CraftingService.java:179` |

## cultivation

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `打坐时间太短，未获得灵力。` | `com/mtxgdn/onebot/command/cultivation/CultivateStopCommand.java:47` |
| _待填_ | `角色不存在` | `com/mtxgdn/onebot/command/cultivation/ReincarnateCommand.java:46` |
| _待填_ | `转世需在【金仙】圆满之后方可进行（当前境界不足）` | `com/mtxgdn/onebot/command/cultivation/ReincarnateCommand.java:51` |
| _待填_ | `转世失败，请稍后重试` | `com/mtxgdn/onebot/command/cultivation/ReincarnateCommand.java:92` |
| _待填_ | `═══ 轮回转世 ═══\n` | `com/mtxgdn/onebot/command/cultivation/ReincarnateCommand.java:102` |

## daily

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/DailyService.java:51` |
| _待填_ | `晨修须在卯时至巳时（5:00~11:00）进行，此时紫气东来，天地灵气最为纯净。现在是` | `com/mtxgdn/game/service/DailyService.java:61` |
| _待填_ | `时，非晨修之时，待日出再试。` | `com/mtxgdn/game/service/DailyService.java:61` |
| _待填_ | `今日已晨修，紫气已收入体内，明日再来吧` | `com/mtxgdn/game/service/DailyService.java:68` |
| _待填_ | `晨曦初现，紫气东来！你盘膝而坐，运转功法，获得` | `com/mtxgdn/game/service/DailyService.java:137` |
| _待填_ | `经验和` | `com/mtxgdn/game/service/DailyService.java:137` |
| _待填_ | `灵石` | `com/mtxgdn/game/service/DailyService.java:137` |

## economy

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `用法: /拍卖 出售 <物品名> <数量> <起价> [小时默认24]` | `com/mtxgdn/onebot/command/economy/AuctionCommand.java:81` |
| _待填_ | `数量和价格无效` | `com/mtxgdn/onebot/command/economy/AuctionCommand.java:89` |
| _待填_ | `用法: /拍卖 出价 <编号> <价格>` | `com/mtxgdn/onebot/command/economy/AuctionCommand.java:102` |
| _待填_ | `编号和价格无效` | `com/mtxgdn/onebot/command/economy/AuctionCommand.java:109` |
| _待填_ | `用法: /灵庄 存入 <活期\|7天\|30天\|90天> <数量>\n例: /灵庄 存入 30天 1000` | `com/mtxgdn/onebot/command/economy/BankCommand.java:92` |
| _待填_ | `存款类型: 活期 / 7天 / 30天 / 90天\n例: /灵庄 存入 30天 1000` | `com/mtxgdn/onebot/command/economy/BankCommand.java:104` |
| _待填_ | `数量无效` | `com/mtxgdn/onebot/command/economy/BankCommand.java:110` |
| _待填_ | `用法: /灵庄 取出 <编号>\n先用 /灵庄 查看存款编号` | `com/mtxgdn/onebot/command/economy/BankCommand.java:120` |
| _待填_ | `编号无效` | `com/mtxgdn/onebot/command/economy/BankCommand.java:126` |
| _待填_ | `⚡ 灵石修炼加速\n` | `com/mtxgdn/onebot/command/economy/CultivateBoostCommand.java:30` |
| _待填_ | `灵石数量必须大于 0` | `com/mtxgdn/onebot/command/economy/CultivateBoostCommand.java:41` |
| _待填_ | `请输入有效的灵石数量` | `com/mtxgdn/onebot/command/economy/CultivateBoostCommand.java:48` |
| _待填_ | `请先创建角色` | `com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:36` |
| _待填_ | `用法: /货币兑换 金币 <数量> 或 /货币兑换 灵石 <数量>` | `com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:46` |
| _待填_ | `数量必须是正整数` | `com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:54` |
| _待填_ | `数量必须大于 0` | `com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:58` |
| _待填_ | `方向只能是: 金币（换灵石）或 灵石（换金币）` | `com/mtxgdn/onebot/command/economy/CurrencyExchangeCommand.java:69` |
| _待填_ | `能量转化系统暂未开放。` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:41` |
| _待填_ | `数量必须为整数。` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:62` |
| _待填_ | `数量必须大于0。` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:67` |
| _待填_ | `物品不存在:` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:80` |
| _待填_ | `\n使用 /转化 列表 查看可转化的物品` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:80` |
| _待填_ | `背包中【` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:87` |
| _待填_ | `】数量不足，需要` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:87` |
| _待填_ | `个，当前拥有` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:87` |
| _待填_ | `个。` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:87` |
| _待填_ | `【` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:93` |
| _待填_ | `】没有转化价值，无法转化为能量。` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:93` |
| _待填_ | `转化成功！\n` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:102` |
| _待填_ | `用法: /转化 兑换 <物品> [数量]\n示例: /转化 兑换 mtxgdn:spirit_stone 10` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:115` |
| _待填_ | `\n使用 /转化 列表 查看可兑换的物品` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:139` |
| _待填_ | `】没有转化价值，无法用能量兑换。` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:145` |
| _待填_ | `能量不足！需要` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:153` |
| _待填_ | `能量，当前只有` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:153` |
| _待填_ | `能量。` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:153` |
| _待填_ | `能量扣除失败，请稍后重试。` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:158` |
| _待填_ | `兑换成功！\n` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:164` |
| _待填_ | `当前没有可转化的物品。` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:178` |
| _待填_ | `=== 能量转化系统 ===\n` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:216` |
| _待填_ | `无效的灵石等级:` | `com/mtxgdn/onebot/command/economy/PurifyCommand.java:50` |
| _待填_ | `（可选: 下品/中品/上品）\n` | `com/mtxgdn/onebot/command/economy/PurifyCommand.java:50` |
| _待填_ | `用法: /回收 <物品名> [数量]\n提示: 回收价为原价的 30%` | `com/mtxgdn/onebot/command/economy/RecycleCommand.java:31` |
| _待填_ | `物品不存在，请使用 /物品列表 查看可回收物品` | `com/mtxgdn/onebot/command/economy/RecycleCommand.java:45` |
| _待填_ | `请输入商品编号，如：/商店 1（/商店 查看全部商品）` | `com/mtxgdn/onebot/command/economy/ShopCommand.java:49` |
| _待填_ | `用法: /灵石兑换 [数量] <从> <到>\n示例: /灵石兑换 1 极品 下品` | `com/mtxgdn/onebot/command/economy/StoneExchangeCommand.java:39` |
| _待填_ | `数量必须是数字` | `com/mtxgdn/onebot/command/economy/StoneExchangeCommand.java:47` |
| _待填_ | `无效的来源灵石等级:` | `com/mtxgdn/onebot/command/economy/StoneExchangeCommand.java:55` |
| _待填_ | `（可选: 下品/中品/上品/极品）` | `com/mtxgdn/onebot/command/economy/StoneExchangeCommand.java:55` |
| _待填_ | `无效的目标灵石等级:` | `com/mtxgdn/onebot/command/economy/StoneExchangeCommand.java:59` |

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `服务器错误:` | `com/mtxgdn/onebot/command/economy/AuctionCommand.java:159` |
| _待填_ | `请提供物品键(itemKey)` | `com/mtxgdn/onebot/command/economy/AuctionCommand.java:198` |
| _待填_ | `请提供有效的拍卖编号(listingId)和出价金额(amount)` | `com/mtxgdn/onebot/command/economy/AuctionCommand.java:222` |
| _待填_ | `请提供存款类型(type: current/fixed_7/fixed_30/fixed_90)和金额(amount)` | `com/mtxgdn/onebot/command/economy/BankCommand.java:194` |
| _待填_ | `请提供有效的存款编号(depositId)` | `com/mtxgdn/onebot/command/economy/BankCommand.java:216` |
| _待填_ | `请提供有效的灵石数量(stones)` | `com/mtxgdn/onebot/command/economy/CultivateBoostCommand.java:61` |
| _待填_ | `能量转化系统暂未开放` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:237` |
| _待填_ | `数量必须大于0` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:252` |
| _待填_ | `物品不存在:` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:263` |
| _待填_ | `物品数量不足，需要` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:271` |
| _待填_ | `个，拥有` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:271` |
| _待填_ | `个` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:271` |
| _待填_ | `该物品没有转化价值` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:278` |
| _待填_ | `转化成功` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:287` |
| _待填_ | `该物品没有转化价值，无法兑换` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:342` |
| _待填_ | `能量不足，需要` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:351` |
| _待填_ | `，当前` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:351` |
| _待填_ | `能量扣除失败` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:357` |
| _待填_ | `兑换成功` | `com/mtxgdn/onebot/command/economy/ExchangeCommand.java:364` |
| _待填_ | `请提供有效的 grade（0=下品→中品，1=中品→上品，2=上品→极品）` | `com/mtxgdn/onebot/command/economy/PurifyCommand.java:77` |
| _待填_ | `数量必须大于 0` | `com/mtxgdn/onebot/command/economy/RecycleCommand.java:68` |
| _待填_ | `缺少商品编号 id` | `com/mtxgdn/onebot/command/economy/ShopCommand.java:93` |
| _待填_ | `购买失败，请稍后重试` | `com/mtxgdn/onebot/command/economy/ShopCommand.java:105` |

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/EconomyService.java:38` |
| _待填_ | `今日已签到，明日再来吧` | `com/mtxgdn/game/service/EconomyService.java:45` |
| _待填_ | `签到成功！第` | `com/mtxgdn/game/service/EconomyService.java:92` |
| _待填_ | `天\n获得：` | `com/mtxgdn/game/service/EconomyService.java:92` |
| _待填_ | `签到失败:` | `com/mtxgdn/game/service/EconomyService.java:95` |
| _待填_ | `物品不存在` | `com/mtxgdn/game/service/EconomyService.java:114` |
| _待填_ | `灵石本身就是货币，无需回收` | `com/mtxgdn/game/service/EconomyService.java:118` |
| _待填_ | `该物品不可交易，无法回收` | `com/mtxgdn/game/service/EconomyService.java:121` |
| _待填_ | `背包中只有` | `com/mtxgdn/game/service/EconomyService.java:126` |
| _待填_ | `个【` | `com/mtxgdn/game/service/EconomyService.java:126` |
| _待填_ | `】` | `com/mtxgdn/game/service/EconomyService.java:126` |
| _待填_ | `回收了【` | `com/mtxgdn/game/service/EconomyService.java:143` |
| _待填_ | `】x` | `com/mtxgdn/game/service/EconomyService.java:143` |
| _待填_ | `，获得` | `com/mtxgdn/game/service/EconomyService.java:143` |
| _待填_ | `下品灵石` | `com/mtxgdn/game/service/EconomyService.java:143` |
| _待填_ | `商品不存在（1~` | `com/mtxgdn/game/service/EconomyService.java:176` |
| _待填_ | `）` | `com/mtxgdn/game/service/EconomyService.java:176` |
| _待填_ | `灵石不足，需要` | `com/mtxgdn/game/service/EconomyService.java:187` |
| _待填_ | `下品灵石（当前:` | `com/mtxgdn/game/service/EconomyService.java:187` |
| _待填_ | `花费` | `com/mtxgdn/game/service/EconomyService.java:200` |
| _待填_ | `下品灵石，购得【` | `com/mtxgdn/game/service/EconomyService.java:200` |
| _待填_ | `请先开始修炼（/修炼），才能使用灵石加速` | `com/mtxgdn/game/service/EconomyService.java:218` |
| _待填_ | `至少需要` | `com/mtxgdn/game/service/EconomyService.java:221` |
| _待填_ | `下品灵石才能加速修炼` | `com/mtxgdn/game/service/EconomyService.java:221` |
| _待填_ | `，当前仅有` | `com/mtxgdn/game/service/EconomyService.java:226` |
| _待填_ | `你燃烧` | `com/mtxgdn/game/service/EconomyService.java:250` |
| _待填_ | `下品灵石化作灵气洪流！\n修炼加速` | `com/mtxgdn/game/service/EconomyService.java:250` |
| _待填_ | `小时（×` | `com/mtxgdn/game/service/EconomyService.java:250` |
| _待填_ | `）\n额外获得` | `com/mtxgdn/game/service/EconomyService.java:250` |
| _待填_ | `经验` | `com/mtxgdn/game/service/EconomyService.java:250` |
| _待填_ | `金币才能兑换灵石` | `com/mtxgdn/game/service/EconomyService.java:265` |
| _待填_ | `金币不足，需要` | `com/mtxgdn/game/service/EconomyService.java:268` |
| _待填_ | `（当前:` | `com/mtxgdn/game/service/EconomyService.java:268` |
| _待填_ | `兑换成功！` | `com/mtxgdn/game/service/EconomyService.java:281` |
| _待填_ | `金币 →` | `com/mtxgdn/game/service/EconomyService.java:281` |
| _待填_ | `至少需要 1 下品灵石才能兑换金币` | `com/mtxgdn/game/service/EconomyService.java:290` |
| _待填_ | `下品灵石 →` | `com/mtxgdn/game/service/EconomyService.java:306` |
| _待填_ | `金币` | `com/mtxgdn/game/service/EconomyService.java:306` |
| _待填_ | `灵庄起存 100 下品灵石` | `com/mtxgdn/game/service/EconomyService.java:331` |
| _待填_ | `未知存款类型:` | `com/mtxgdn/game/service/EconomyService.java:347` |
| _待填_ | `（可选: current/fixed_7/fixed_30/fixed_90）` | `com/mtxgdn/game/service/EconomyService.java:347` |
| _待填_ | `已将` | `com/mtxgdn/game/service/EconomyService.java:385` |
| _待填_ | `下品灵石存入灵庄（活期，日利` | `com/mtxgdn/game/service/EconomyService.java:385` |
| _待填_ | `%）。随时可取，利息按日结算。\n编号: #` | `com/mtxgdn/game/service/EconomyService.java:385` |
| _待填_ | `下品灵石存入灵庄【` | `com/mtxgdn/game/service/EconomyService.java:388` |
| _待填_ | `】（` | `com/mtxgdn/game/service/EconomyService.java:388` |
| _待填_ | `天，到期利息` | `com/mtxgdn/game/service/EconomyService.java:388` |
| _待填_ | `下品灵石）。到期前取出将损失全部利息！\n编号: #` | `com/mtxgdn/game/service/EconomyService.java:388` |
| _待填_ | `该存款不存在或不属于你` | `com/mtxgdn/game/service/EconomyService.java:402` |
| _待填_ | `该存款已取出` | `com/mtxgdn/game/service/EconomyService.java:405` |
| _待填_ | `该存款已取出，请勿重复操作` | `com/mtxgdn/game/service/EconomyService.java:440` |
| _待填_ | `灵庄取款成功！本金` | `com/mtxgdn/game/service/EconomyService.java:450` |
| _待填_ | `+ 利息` | `com/mtxgdn/game/service/EconomyService.java:450` |
| _待填_ | `= 共` | `com/mtxgdn/game/service/EconomyService.java:450` |
| _待填_ | `数量和起拍价必须大于 0` | `com/mtxgdn/game/service/EconomyService.java:595` |
| _待填_ | `背包中没有足够的【` | `com/mtxgdn/game/service/EconomyService.java:599` |
| _待填_ | `已将【` | `com/mtxgdn/game/service/EconomyService.java:626` |
| _待填_ | `】×` | `com/mtxgdn/game/service/EconomyService.java:626` |
| _待填_ | `上架竞拍行，起拍价` | `com/mtxgdn/game/service/EconomyService.java:626` |
| _待填_ | `下品灵石，` | `com/mtxgdn/game/service/EconomyService.java:626` |
| _待填_ | `小时后截止` | `com/mtxgdn/game/service/EconomyService.java:626` |
| _待填_ | `出价必须大于 0` | `com/mtxgdn/game/service/EconomyService.java:635` |
| _待填_ | `出价失败:` | `com/mtxgdn/game/service/EconomyService.java:687` |
| _待填_ | `出价成功！以` | `com/mtxgdn/game/service/EconomyService.java:692` |
| _待填_ | `下品灵石竞拍 #` | `com/mtxgdn/game/service/EconomyService.java:692` |
| _待填_ | `无效的灵石等级，只能提纯 下品/中品/上品 灵石` | `com/mtxgdn/game/service/EconomyService.java:866` |
| _待填_ | `境界不足，提纯` | `com/mtxgdn/game/service/EconomyService.java:881` |
| _待填_ | `需要` | `com/mtxgdn/game/service/EconomyService.java:881` |
| _待填_ | `以上（当前：` | `com/mtxgdn/game/service/EconomyService.java:881` |
| _待填_ | `不足，提纯一次需要` | `com/mtxgdn/game/service/EconomyService.java:889` |
| _待填_ | `块` | `com/mtxgdn/game/service/EconomyService.java:889` |
| _待填_ | `提纯灵石需要借助洞府灵气，请先开辟洞府` | `com/mtxgdn/game/service/EconomyService.java:900` |
| _待填_ | `洞府灵气不足，提纯` | `com/mtxgdn/game/service/EconomyService.java:905` |
| _待填_ | `点灵气（当前：` | `com/mtxgdn/game/service/EconomyService.java:905` |
| _待填_ | `提纯成功！` | `com/mtxgdn/game/service/EconomyService.java:950` |
| _待填_ | `灵气 → 1 块` | `com/mtxgdn/game/service/EconomyService.java:950` |
| _待填_ | `提纯失败……` | `com/mtxgdn/game/service/EconomyService.java:953` |
| _待填_ | `与` | `com/mtxgdn/game/service/EconomyService.java:953` |

## enhance

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `强化失败，系统异常:` | `com/mtxgdn/game/service/EnhanceService.java:189` |
| _待填_ | `强化成功！` | `com/mtxgdn/game/service/EnhanceService.java:222` |
| _待填_ | `提升至 +` | `com/mtxgdn/game/service/EnhanceService.java:222` |
| _待填_ | `强化失败！` | `com/mtxgdn/game/service/EnhanceService.java:229` |
| _待填_ | `承受不住力量，碎裂了...` | `com/mtxgdn/game/service/EnhanceService.java:229` |
| _待填_ | `强化失败！但护身符生效，装备免于碎裂（消耗 1 张护身符）` | `com/mtxgdn/game/service/EnhanceService.java:235` |
| _待填_ | `降级至 +` | `com/mtxgdn/game/service/EnhanceService.java:242` |
| _待填_ | `强化失败！材料消耗但装备未受影响` | `com/mtxgdn/game/service/EnhanceService.java:247` |

## error

### error

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `token无效或已过期` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `请先登录` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `用户名已存在` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `邮箱已被注册` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `用户名或密码错误` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `缺少必要参数` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `参数格式错误` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `角色不存在，请先创建角色` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `角色已存在` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `物品不存在` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `物品数量不足` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `使用物品失败` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `境界突破失败` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `经验不足` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `灵石不足` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `已达最高境界` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `未在修炼状态` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `技能不存在` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `技能已学习` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `境界不足，无法学习该技能` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `学习技能失败` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `对手不存在` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `不能挑战自己` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `秘境不存在` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `境界不足，无法进入该秘境` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `秘境冷却中，请稍后再试` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `生命值不足，无法进入秘境` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `游历冷却中` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `暂无可用游历事件` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `接收者不存在` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `不能给自己发消息` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `好友不存在` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `已经是好友或已发送过申请` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `好友申请不存在` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `不能添加自己为好友` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `未知的消息类型` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `消息格式错误` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `操作太频繁，请稍后再试` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `服务器内部错误` | `com/mtxgdn/common/GameErrorCode.java` |
| _待填_ | `网络请求失败` | `com/mtxgdn/common/GameErrorCode.java` |

## exploration

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `当前没有可用的秘境。` | `com/mtxgdn/onebot/command/exploration/SecretAreasCommand.java:35` |
| _待填_ | `用法: /进入秘境 <秘境名称>\n使用 /秘境 查看可用秘境` | `com/mtxgdn/onebot/command/exploration/SecretEnterCommand.java:33` |

## formation

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `请指定阵法名，如: /阵法 place 聚灵阵` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:199` |
| _待填_ | `未知阵法:` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:206` |
| _待填_ | `\n可用阵法: 聚灵阵, 护山大阵, 迷踪阵, 净化阵, 时间加速阵` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:206` |
| _待填_ | `阵法等级必须在 1-` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:215` |
| _待填_ | `之间` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:215` |
| _待填_ | `无效的等级` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:219` |
| _待填_ | `你还没有布置阵法` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:231` |

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `你还没有布置阵法` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:48` |
| _待填_ | `服务器错误:` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:67` |
| _待填_ | `请提供有效的阵法(formationKey 或 name)，可选:` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:120` |
| _待填_ | `阵法等级必须在 1-` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:131` |
| _待填_ | `之间` | `com/mtxgdn/onebot/command/formation/FormationCommand.java:131` |

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/FormationService.java:64` |
| _待填_ | `你还没有洞府，无法布置阵法` | `com/mtxgdn/game/service/FormationService.java:70` |
| _待填_ | `你已经布置了一个阵法，需先拆除` | `com/mtxgdn/game/service/FormationService.java:76` |
| _待填_ | `灵石不足，布置阵法需要` | `com/mtxgdn/game/service/FormationService.java:84` |
| _待填_ | `灵石（你目前有` | `com/mtxgdn/game/service/FormationService.java:84` |
| _待填_ | `灵石）` | `com/mtxgdn/game/service/FormationService.java:84` |
| _待填_ | `成功布置【` | `com/mtxgdn/game/service/FormationService.java:116` |
| _待填_ | `】！消耗` | `com/mtxgdn/game/service/FormationService.java:116` |
| _待填_ | `灵石\n` | `com/mtxgdn/game/service/FormationService.java:116` |
| _待填_ | `你还没有布置阵法` | `com/mtxgdn/game/service/FormationService.java:130` |
| _待填_ | `阵法已达到最高等级` | `com/mtxgdn/game/service/FormationService.java:136` |
| _待填_ | `级` | `com/mtxgdn/game/service/FormationService.java:136` |
| _待填_ | `灵石不足，升级需要` | `com/mtxgdn/game/service/FormationService.java:145` |
| _待填_ | `晋升为` | `com/mtxgdn/game/service/FormationService.java:169` |
| _待填_ | `级！消耗` | `com/mtxgdn/game/service/FormationService.java:169` |
| _待填_ | `灵石` | `com/mtxgdn/game/service/FormationService.java:169` |
| _待填_ | `已拆除【` | `com/mtxgdn/game/service/FormationService.java:195` |
| _待填_ | `】` | `com/mtxgdn/game/service/FormationService.java:195` |
| _待填_ | `这不是可布置阵法的阵图` | `com/mtxgdn/game/service/FormationService.java:263` |
| _待填_ | `使用阵图失败:` | `com/mtxgdn/game/service/FormationService.java:315` |
| _待填_ | `使用阵图布置【` | `com/mtxgdn/game/service/FormationService.java:320` |
| _待填_ | `】成功！(消耗阵图 1 张，无需灵石)\n` | `com/mtxgdn/game/service/FormationService.java:320` |

## friend

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `不能拜访自己` | `com/mtxgdn/game/service/FriendService.java:188` |
| _待填_ | `你们不是好友，无法拜访` | `com/mtxgdn/game/service/FriendService.java:193` |
| _待填_ | `今日已拜访过该好友，明日再来吧` | `com/mtxgdn/game/service/FriendService.java:200` |
| _待填_ | `玩家数据异常` | `com/mtxgdn/game/service/FriendService.java:208` |
| _待填_ | `你拜访了【` | `com/mtxgdn/game/service/FriendService.java:225` |
| _待填_ | `】，论道一番，双方各有所悟！\n` | `com/mtxgdn/game/service/FriendService.java:225` |

## game_resource

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `请先创建角色` | `com/mtxgdn/rest/GameResource.java:392` |

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `type 只能是 goldToStones 或 stonesToGold` | `com/mtxgdn/rest/GameResource.java:1631` |

## game_web_socket_app

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `连接成功，欢迎进入修仙世界！` | `com/mtxgdn/websocket/GameWebSocketApp.java:213` |

## install_resource

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `安装向导仅允许从服务器本机访问。如需远程安装，请在 config/application.yml 中设置` | `com/mtxgdn/rest/InstallResource.java:173` |
| _待填_ | `服务器已完成安装。如需重置，请使用 /api/install/reset 接口。` | `com/mtxgdn/rest/InstallResource.java:230` |
| _待填_ | `配置已保存` | `com/mtxgdn/rest/InstallResource.java:288` |
| _待填_ | `（` | `com/mtxgdn/rest/InstallResource.java:288` |
| _待填_ | `）` | `com/mtxgdn/rest/InstallResource.java:288` |
| _待填_ | `，正在重启服务器…` | `com/mtxgdn/rest/InstallResource.java:288` |
| _待填_ | `配置已清除，服务器即将重启，请重新运行安装向导…` | `com/mtxgdn/rest/InstallResource.java:354` |
| _待填_ | `服务器即将重启，请稍候…（页面将自动刷新）` | `com/mtxgdn/rest/InstallResource.java:384` |

## item

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `用法: /强化 <装备栏位>\n栏位: weapon(武器) \| armor(防具) \| accessory(饰品)` | `com/mtxgdn/onebot/command/item/EnhanceCommand.java:30` |
| _待填_ | `无效的装备栏位:` | `com/mtxgdn/onebot/command/item/EnhanceCommand.java:41` |
| _待填_ | `\n栏位: weapon(武器) \| armor(防具) \| accessory(饰品)` | `com/mtxgdn/onebot/command/item/EnhanceCommand.java:41` |
| _待填_ | `用法: /装备 <物品名称或key> <部位>\n部位: weapon(武器) \| armor(防具) \| accessory(饰品)\n物品名称或key 可在 /背包 或 /物品列表 中查看\n例如: /装备 铁剑 weapon` | `com/mtxgdn/onebot/command/item/EquipCommand.java:22` |
| _待填_ | `用法: /发放物品 <玩家名> <物品Key> [数量=1]\n例: /发放物品 李白 spirit_stone 100` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:36` |
| _待填_ | `参数不足。用法: /发放物品 <玩家名> <物品Key> [数量]` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:42` |
| _待填_ | `数量无效` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:53` |
| _待填_ | `数量必须大于 0` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:58` |
| _待填_ | `未找到玩家:` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:66` |
| _待填_ | `物品不存在:` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:74` |
| _待填_ | `\n使用 /物品列表 查看可发放的物品` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:74` |
| _待填_ | `已向` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:82` |
| _待填_ | `发放` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:82` |
| _待填_ | `物品列表为空。` | `com/mtxgdn/onebot/command/item/ItemMapCommand.java:15` |
| _待填_ | `用法: /使用 <物品名称或key>\n可在 /背包 或 /物品列表 中查看你的物品\n例如: /使用 回血丹` | `com/mtxgdn/onebot/command/item/ItemUseCommand.java:24` |
| _待填_ | `兑换系统暂时不可用，请稍后重试。` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:43` |
| _待填_ | `请先绑定账号。` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:52` |
| _待填_ | `权限不足，你无权使用此功能。` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:56` |
| _待填_ | `请先创建角色。` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:62` |
| _待填_ | `用法: /兑换 <兑换码>\n请输入要使用的兑换码。` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:68` |
| _待填_ | `兑换成功！` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:74` |
| _待填_ | `兑换成功！获得:` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:77` |
| _待填_ | `用法: /卸下 <部位>\n部位: weapon \| armor \| accessory` | `com/mtxgdn/onebot/command/item/UnequipCommand.java:21` |

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `请提供玩家名(playerName)和物品键(itemKey)` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:97` |
| _待填_ | `数量必须大于0` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:102` |
| _待填_ | `未找到玩家:` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:111` |
| _待填_ | `物品不存在:` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:119` |
| _待填_ | `成功向` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:127` |
| _待填_ | `发放` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:127` |
| _待填_ | `服务器错误:` | `com/mtxgdn/onebot/command/item/ItemGiveCommand.java:133` |
| _待填_ | `请求体不能为空` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:90` |
| _待填_ | `请提供兑换码` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:98` |
| _待填_ | `请先进入游戏` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:105` |
| _待填_ | `兑换成功！` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:112` |
| _待填_ | `兑换成功！获得:` | `com/mtxgdn/onebot/command/item/RedeemCommand.java:116` |

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `无效的灵石等级` | `com/mtxgdn/game/service/ItemService.java:552` |
| _待填_ | `只能将高等级灵石兑换为低等级灵石` | `com/mtxgdn/game/service/ItemService.java:557` |
| _待填_ | `兑换数量必须大于0` | `com/mtxgdn/game/service/ItemService.java:562` |
| _待填_ | `灵石不足` | `com/mtxgdn/game/service/ItemService.java:569` |
| _待填_ | `兑换成功！%d %s → %d %s` | `com/mtxgdn/game/service/ItemService.java:587` |
| _待填_ | `兑换失败:` | `com/mtxgdn/game/service/ItemService.java:591` |
| _待填_ | `物品不存在，请使用 /物品列表 查看可用物品` | `com/mtxgdn/game/service/ItemService.java:617` |
| _待填_ | `背包中没有该物品` | `com/mtxgdn/game/service/ItemService.java:628` |
| _待填_ | `已装备该物品` | `com/mtxgdn/game/service/ItemService.java:635` |
| _待填_ | `装备失败:` | `com/mtxgdn/game/service/ItemService.java:715` |
| _待填_ | `该装备栏位为空` | `com/mtxgdn/game/service/ItemService.java:733` |
| _待填_ | `物品数据异常` | `com/mtxgdn/game/service/ItemService.java:739` |
| _待填_ | `卸下装备失败:` | `com/mtxgdn/game/service/ItemService.java:793` |
| _待填_ | `物品不存在` | `com/mtxgdn/game/service/ItemService.java:848` |
| _待填_ | `使用物品失败:` | `com/mtxgdn/game/service/ItemService.java:925` |

## main

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `【天道更替】天地灵气流转，` | `com/mtxgdn/Main.java:241` |
| _待填_ | `已至！` | `com/mtxgdn/Main.java:241` |

## map

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `请先创建角色` | `com/mtxgdn/onebot/command/map/GatherCommand.java:35` |
| _待填_ | `获取地图信息失败` | `com/mtxgdn/onebot/command/map/MapCommand.java:53` |
| _待填_ | `用法: /地图 传送 <地名>（需已到过该地）` | `com/mtxgdn/onebot/command/map/MapCommand.java:164` |
| _待填_ | `找不到地点【` | `com/mtxgdn/onebot/command/map/MapCommand.java:169` |
| _待填_ | `】，试试 /地图 list 查看全图` | `com/mtxgdn/onebot/command/map/MapCommand.java:169` |
| _待填_ | `用法: /地图 go <地名>` | `com/mtxgdn/onebot/command/map/MapCommand.java:180` |
| _待填_ | `未找到区域【` | `com/mtxgdn/onebot/command/map/MapCommand.java:196` |
| _待填_ | `】` | `com/mtxgdn/onebot/command/map/MapCommand.java:196` |
| _待填_ | `野外地图当前已关闭（可在 config/game/wilderness_map.json 里把 enabled 改为 true）` | `com/mtxgdn/onebot/command/map/WildernessCommand.java:49` |
| _待填_ | `你所在的位置已不在世界边界之内，请联系管理员` | `com/mtxgdn/onebot/command/map/WildernessCommand.java:80` |

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `野外地图当前已关闭（可在 config/game/wilderness_map.json 里把 enabled 改为 true）` | `com/mtxgdn/onebot/command/map/WildernessCommand.java:195` |
| _待填_ | `角色不存在` | `com/mtxgdn/onebot/command/map/WildernessCommand.java:203` |
| _待填_ | `你正站在已知世界，可从 gateways 里的出境口踏入荒域` | `com/mtxgdn/onebot/command/map/WildernessCommand.java:239` |
| _待填_ | `你所在的位置已不在世界边界之内，请联系管理员` | `com/mtxgdn/onebot/command/map/WildernessCommand.java:247` |
| _待填_ | `用 POST /api/game/map/travel/{locationId} 移动，POST /api/game/map/gather 采集` | `com/mtxgdn/onebot/command/map/WildernessCommand.java:309` |

## market

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `用法: /购买 <挂单ID>\n先使用 /坊市 查看坊市中的挂单ID` | `com/mtxgdn/onebot/command/market/BuyItemCommand.java:15` |
| _待填_ | `挂单ID必须是数字，请使用 /坊市 查看` | `com/mtxgdn/onebot/command/market/BuyItemCommand.java:18` |
| _待填_ | `购买失败` | `com/mtxgdn/onebot/command/market/BuyItemCommand.java:20` |
| _待填_ | `用法: /撤单 <挂单ID>\n使用 /我的挂单 查看你的挂单` | `com/mtxgdn/onebot/command/market/CancelListingCommand.java:15` |
| _待填_ | `挂单ID必须是数字` | `com/mtxgdn/onebot/command/market/CancelListingCommand.java:18` |
| _待填_ | `撤单失败` | `com/mtxgdn/onebot/command/market/CancelListingCommand.java:20` |
| _待填_ | `用法: /上架 <物品名称或key> <数量> <灵石价格>\n物品key可在 /背包 或 /物品列表 中查看\n例如: /上架 回血丹 5 100` | `com/mtxgdn/onebot/command/market/ListItemCommand.java:19` |
| _待填_ | `数量必须是整数` | `com/mtxgdn/onebot/command/market/ListItemCommand.java:26` |
| _待填_ | `价格必须是整数（单位：灵石）` | `com/mtxgdn/onebot/command/market/ListItemCommand.java:28` |
| _待填_ | `上架失败` | `com/mtxgdn/onebot/command/market/ListItemCommand.java:30` |
| _待填_ | `===== 坊市 =====\n坊市上空空荡荡，还没有人挂单。\n使用 /上架 <物品key> <数量> <灵石价格> 来卖东西吧！` | `com/mtxgdn/onebot/command/market/MarketCommand.java:19` |
| _待填_ | `用法: /行情 <物品名或ID> [天数]\n例如: /行情 灵草  或  /行情 灵草 30\n天数默认 7 天，最多 90 天` | `com/mtxgdn/onebot/command/market/MarketPriceCommand.java:38` |
| _待填_ | `请指定物品名或物品ID` | `com/mtxgdn/onebot/command/market/MarketPriceCommand.java:56` |
| _待填_ | `查询失败` | `com/mtxgdn/onebot/command/market/MarketPriceCommand.java:62` |
| _待填_ | `===== 我的挂单 =====\n你还没有在坊市挂单。\n使用 /上架 <key> <数量> <灵石> 上架吧！` | `com/mtxgdn/onebot/command/market/MyListingsCommand.java:17` |

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `请提供 itemKey 查询参数` | `com/mtxgdn/onebot/command/market/MarketPriceCommand.java:99` |
| _待填_ | `服务器错误:` | `com/mtxgdn/onebot/command/market/MarketPriceCommand.java:124` |

## oauth

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `OAuth2 配置已更新` | `com/mtxgdn/service/OAuthService.java:122` |
| _待填_ | `（` | `com/mtxgdn/service/OAuthService.java:122` |
| _待填_ | `）` | `com/mtxgdn/service/OAuthService.java:122` |

## oauth_resource

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `你正在使用 OAuth 2.0 访问受保护资源（scope:` | `com/mtxgdn/rest/OAuthResource.java:126` |
| _待填_ | `）` | `com/mtxgdn/rest/OAuthResource.java:126` |
| _待填_ | `授权码（authorization code）流程未实现 —— 本 OAuth 2.0 支持仅提供 client_credentials 令牌端点与受保护资源端点` | `com/mtxgdn/rest/OAuthResource.java:202` |

## one_bot_web_socket_server

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `操作太频繁，请稍后再试` | `com/mtxgdn/onebot/OneBotWebSocketServer.java:358` |
| _待填_ | `请私聊机器人使用此指令，保护您的账户安全。` | `com/mtxgdn/onebot/OneBotWebSocketServer.java:364` |
| _待填_ | `当前没有待取消的操作。` | `com/mtxgdn/onebot/OneBotWebSocketServer.java:377` |
| _待填_ | `该玩法系统已停用，暂时无法使用。` | `com/mtxgdn/onebot/OneBotWebSocketServer.java:386` |
| _待填_ | `权限不足，你无权使用此功能。` | `com/mtxgdn/onebot/OneBotWebSocketServer.java:400` |
| _待填_ | `角色名不能超过16个字` | `com/mtxgdn/onebot/OneBotWebSocketServer.java:563` |
| _待填_ | `角色名已被占用，请换一个。` | `com/mtxgdn/onebot/OneBotWebSocketServer.java:567` |
| _待填_ | `已取消。` | `com/mtxgdn/onebot/OneBotWebSocketServer.java:641` |

## player

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `你的生命值已满，无需治疗。` | `com/mtxgdn/onebot/command/player/HealCommand.java:15` |
| _待填_ | `疗伤失败` | `com/mtxgdn/onebot/command/player/HealCommand.java:17` |
| _待填_ | `你尚未觉醒灵根，请先创建角色。` | `com/mtxgdn/onebot/command/player/SpiritualRootCommand.java:44` |
| _待填_ | `灵根重铸需要达到【金丹期】及以上境界，你当前境界不足以重铸灵根。` | `com/mtxgdn/onebot/command/player/SpiritualRootCommand.java:78` |
| _待填_ | `灵根重铸需要消耗道具【乾坤造化丹】x1，你的背包中没有该物品。` | `com/mtxgdn/onebot/command/player/SpiritualRootCommand.java:84` |
| _待填_ | `灵根重铸需要消耗` | `com/mtxgdn/onebot/command/player/SpiritualRootCommand.java:90` |
| _待填_ | `灵石（等值下品灵石），你当前仅有` | `com/mtxgdn/onebot/command/player/SpiritualRootCommand.java:90` |
| _待填_ | `。` | `com/mtxgdn/onebot/command/player/SpiritualRootCommand.java:90` |
| _待填_ | `用法: /称号 装备 <名称>\n先用 /称号 查看你拥有的称号` | `com/mtxgdn/onebot/command/player/TitleCommand.java:106` |
| _待填_ | `未找到称号「` | `com/mtxgdn/onebot/command/player/TitleCommand.java:124` |
| _待填_ | `」，请确认你是否拥有该称号。\n使用 /称号 查看已拥有的称号。` | `com/mtxgdn/onebot/command/player/TitleCommand.java:124` |

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/PlayerService.java:417` |
| _待填_ | `你已在闭关中，无需重复打坐（使用停止修炼来结算）` | `com/mtxgdn/game/service/PlayerService.java:428` |
| _待填_ | `你已重伤倒地，无法闭关修炼，请先疗伤恢复` | `com/mtxgdn/game/service/PlayerService.java:435` |
| _待填_ | `开始闭关修炼` | `com/mtxgdn/game/service/PlayerService.java:451` |
| _待填_ | `你并未在闭关中，无需停止修炼` | `com/mtxgdn/game/service/PlayerService.java:468` |
| _待填_ | `修炼结算正在处理中，请勿重复操作` | `com/mtxgdn/game/service/PlayerService.java:477` |
| _待填_ | `结束闭关修炼` | `com/mtxgdn/game/service/PlayerService.java:495` |
| _待填_ | `你的生命值已满，无需治疗。` | `com/mtxgdn/game/service/PlayerService.java:598` |
| _待填_ | `灵石不足！疗伤需要` | `com/mtxgdn/game/service/PlayerService.java:607` |
| _待填_ | `灵石，你只有` | `com/mtxgdn/game/service/PlayerService.java:607` |
| _待填_ | `灵石。\n` | `com/mtxgdn/game/service/PlayerService.java:607` |
| _待填_ | `扣除灵石失败，请重试。` | `com/mtxgdn/game/service/PlayerService.java:616` |
| _待填_ | `消耗` | `com/mtxgdn/game/service/PlayerService.java:634` |
| _待填_ | `灵石，伤势痊愈！\n生命值:` | `com/mtxgdn/game/service/PlayerService.java:634` |
| _待填_ | `（法力也一并恢复）` | `com/mtxgdn/game/service/PlayerService.java:634` |

## player_data

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `玩家数据文件化未启用` | `com/mtxgdn/playerdata/PlayerDataService.java:124` |
| _待填_ | `玩家不存在:` | `com/mtxgdn/playerdata/PlayerDataService.java:131` |
| _待填_ | `写入玩家数据文件失败:` | `com/mtxgdn/playerdata/PlayerDataService.java:143` |
| _待填_ | `玩家` | `com/mtxgdn/playerdata/PlayerDataService.java:148` |
| _待填_ | `（#` | `com/mtxgdn/playerdata/PlayerDataService.java:148` |
| _待填_ | `）数据已导出至` | `com/mtxgdn/playerdata/PlayerDataService.java:148` |
| _待填_ | `玩家数据 JSON 解析失败:` | `com/mtxgdn/playerdata/PlayerDataService.java:346` |
| _待填_ | `玩家数据为空或格式不正确` | `com/mtxgdn/playerdata/PlayerDataService.java:351` |
| _待填_ | `数据文件中的玩家 #` | `com/mtxgdn/playerdata/PlayerDataService.java:356` |
| _待填_ | `与当前玩家 #` | `com/mtxgdn/playerdata/PlayerDataService.java:356` |
| _待填_ | `不一致，拒绝还原` | `com/mtxgdn/playerdata/PlayerDataService.java:356` |
| _待填_ | `还原失败，已回滚:` | `com/mtxgdn/playerdata/PlayerDataService.java:394` |
| _待填_ | `还原失败:` | `com/mtxgdn/playerdata/PlayerDataService.java:399` |
| _待填_ | `玩家 #` | `com/mtxgdn/playerdata/PlayerDataService.java:404` |
| _待填_ | `数据还原成功，共恢复` | `com/mtxgdn/playerdata/PlayerDataService.java:404` |
| _待填_ | `行数据` | `com/mtxgdn/playerdata/PlayerDataService.java:404` |
| _待填_ | `玩家数据文件不存在:` | `com/mtxgdn/playerdata/PlayerDataService.java:414` |
| _待填_ | `读取玩家数据文件失败:` | `com/mtxgdn/playerdata/PlayerDataService.java:422` |

## pvp

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `用法：\n` | `com/mtxgdn/onebot/command/pvp/PvpCommand.java:62` |

## qq

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `消息交互按钮仅支持 QQ 官方机器人（当前接入方式不可用）` | `com/mtxgdn/onebot/command/qq/QqButtonCommand.java:49` |
| _待填_ | `请先创建角色` | `com/mtxgdn/onebot/command/qq/QqButtonCommand.java:56` |

## qq_official_adapter

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `操作太频繁，请稍后再试` | `com/mtxgdn/qq/QqOfficialAdapter.java:965` |
| _待填_ | `请私聊机器人使用此指令，保护您的账户安全。` | `com/mtxgdn/qq/QqOfficialAdapter.java:971` |
| _待填_ | `当前没有待取消的操作。` | `com/mtxgdn/qq/QqOfficialAdapter.java:985` |
| _待填_ | `该玩法系统已停用，暂时无法使用。` | `com/mtxgdn/qq/QqOfficialAdapter.java:994` |
| _待填_ | `权限不足，你无权使用此功能。` | `com/mtxgdn/qq/QqOfficialAdapter.java:1008` |
| _待填_ | `角色名不能超过16个字` | `com/mtxgdn/qq/QqOfficialAdapter.java:1477` |
| _待填_ | `角色名已被占用，请换一个。` | `com/mtxgdn/qq/QqOfficialAdapter.java:1481` |

## sect

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `用法: /宗门 create <宗门名> [描述]` | `com/mtxgdn/onebot/command/sect/SectCommand.java:82` |
| _待填_ | `用法: /宗门 join <宗门名>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:89` |
| _待填_ | `找不到宗门【` | `com/mtxgdn/onebot/command/sect/SectCommand.java:91` |
| _待填_ | `】` | `com/mtxgdn/onebot/command/sect/SectCommand.java:91` |
| _待填_ | `天下尚无宗门。\n使用 /宗门 create <名称> 开创一个宗门！` | `com/mtxgdn/onebot/command/sect/SectCommand.java:99` |
| _待填_ | `宗门不存在或你尚未加入宗门。\n使用 /宗门 list 查看所有宗门` | `com/mtxgdn/onebot/command/sect/SectCommand.java:117` |
| _待填_ | `你还没有加入宗门` | `com/mtxgdn/onebot/command/sect/SectCommand.java:143` |
| _待填_ | `用法: /宗门 apply <宗门名>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:163` |
| _待填_ | `只有宗主、副宗主和长老才能查看申请列表` | `com/mtxgdn/onebot/command/sect/SectCommand.java:173` |
| _待填_ | `目前没有待处理的入宗申请` | `com/mtxgdn/onebot/command/sect/SectCommand.java:176` |
| _待填_ | `用法: /宗门 approve <玩家名>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:189` |
| _待填_ | `找不到玩家【` | `com/mtxgdn/onebot/command/sect/SectCommand.java:198` |
| _待填_ | `】的申请，请确认名字无误` | `com/mtxgdn/onebot/command/sect/SectCommand.java:198` |
| _待填_ | `用法: /宗门 reject <玩家名>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:205` |
| _待填_ | `】的申请` | `com/mtxgdn/onebot/command/sect/SectCommand.java:214` |
| _待填_ | `用法: /宗门 kick <玩家名>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:226` |
| _待填_ | `用法: /宗门 appoint <玩家名> <副宗主\|长老\|内门弟子\|外门弟子>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:243` |
| _待填_ | `用法: /宗门 donate <物品key> <数量>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:265` |
| _待填_ | `数量必须是数字` | `com/mtxgdn/onebot/command/sect/SectCommand.java:269` |
| _待填_ | `宗门仓库空空如也。\n使用 /宗门 donate <物品key> <数量> 捐献物品` | `com/mtxgdn/onebot/command/sect/SectCommand.java:281` |
| _待填_ | `用法: /宗门 take <物品key> <数量>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:310` |
| _待填_ | `天下尚无宗门榜单。快去开创第一个宗门！` | `com/mtxgdn/onebot/command/sect/SectCommand.java:327` |
| _待填_ | `用法: /宗门 transfer <玩家名>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:345` |
| _待填_ | `用法: /宗门 挑战        挑战者发起 / 宗主查看竞争期\n` | `com/mtxgdn/onebot/command/sect/SectCommand.java:382` |
| _待填_ | `用法: /宗门 war <宗门名>` | `com/mtxgdn/onebot/command/sect/SectCommand.java:398` |

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `宗门名称需要 2-8 个字符` | `com/mtxgdn/game/service/SectService.java:185` |
| _待填_ | `宗门描述最多 100 个字符` | `com/mtxgdn/game/service/SectService.java:191` |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/SectService.java:198` |
| _待填_ | `境界不足，需要达到元婴期以上才能创建宗门（当前：` | `com/mtxgdn/game/service/SectService.java:206` |
| _待填_ | `）` | `com/mtxgdn/game/service/SectService.java:206` |
| _待填_ | `你已经有宗门了，需要先退出才能创建新宗门` | `com/mtxgdn/game/service/SectService.java:212` |
| _待填_ | `宗门名称【` | `com/mtxgdn/game/service/SectService.java:218` |
| _待填_ | `】已被使用` | `com/mtxgdn/game/service/SectService.java:218` |
| _待填_ | `灵石不足，创建宗门需要` | `com/mtxgdn/game/service/SectService.java:225` |
| _待填_ | `灵石（你目前有` | `com/mtxgdn/game/service/SectService.java:225` |
| _待填_ | `灵石）` | `com/mtxgdn/game/service/SectService.java:225` |
| _待填_ | `恭贺【` | `com/mtxgdn/game/service/SectService.java:274` |
| _待填_ | `】开创宗门【` | `com/mtxgdn/game/service/SectService.java:274` |
| _待填_ | `】！消耗灵石` | `com/mtxgdn/game/service/SectService.java:274` |
| _待填_ | `创建宗门失败` | `com/mtxgdn/game/service/SectService.java:278` |
| _待填_ | `宗门不存在` | `com/mtxgdn/game/service/SectService.java:341` |
| _待填_ | `你已经有了宗门，需要先退出才能加入新宗门` | `com/mtxgdn/game/service/SectService.java:345` |
| _待填_ | `【` | `com/mtxgdn/game/service/SectService.java:352` |
| _待填_ | `】成员已满（` | `com/mtxgdn/game/service/SectService.java:352` |
| _待填_ | `你已经向该宗门提交过申请，请等待审批` | `com/mtxgdn/game/service/SectService.java:364` |
| _待填_ | `已向【` | `com/mtxgdn/game/service/SectService.java:380` |
| _待填_ | `】提交加入申请，请等待宗主或长老审批` | `com/mtxgdn/game/service/SectService.java:380` |
| _待填_ | `申请不存在或已处理` | `com/mtxgdn/game/service/SectService.java:403` |
| _待填_ | `你无权处理宗门申请` | `com/mtxgdn/game/service/SectService.java:413` |
| _待填_ | `宗门成员已满` | `com/mtxgdn/game/service/SectService.java:423` |
| _待填_ | `你没有加入任何宗门` | `com/mtxgdn/game/service/SectService.java:480` |
| _待填_ | `身为一宗之主，不可随意离开。\n` | `com/mtxgdn/game/service/SectService.java:496` |
| _待填_ | `你已解散宗门【` | `com/mtxgdn/game/service/SectService.java:527` |
| _待填_ | `】，从此云游天下` | `com/mtxgdn/game/service/SectService.java:527` |
| _待填_ | `已离开宗门【` | `com/mtxgdn/game/service/SectService.java:530` |
| _待填_ | `】` | `com/mtxgdn/game/service/SectService.java:530` |
| _待填_ | `只有宗主、副宗主和长老才能踢人` | `com/mtxgdn/game/service/SectService.java:542` |
| _待填_ | `该玩家不在你的宗门中` | `com/mtxgdn/game/service/SectService.java:547` |
| _待填_ | `不能踢出宗主` | `com/mtxgdn/game/service/SectService.java:550` |
| _待填_ | `只有宗主才能踢出副宗主` | `com/mtxgdn/game/service/SectService.java:553` |
| _待填_ | `不能踢出同级或更高级别的成员` | `com/mtxgdn/game/service/SectService.java:556` |
| _待填_ | `已将` | `com/mtxgdn/game/service/SectService.java:561` |
| _待填_ | `逐出宗门` | `com/mtxgdn/game/service/SectService.java:561` |
| _待填_ | `只有宗主、副宗主和长老才能任命宗门职位` | `com/mtxgdn/game/service/SectService.java:569` |
| _待填_ | `无效的职位，可用：副宗主/长老/内门弟子/外门弟子` | `com/mtxgdn/game/service/SectService.java:581` |
| _待填_ | `长老只能任命内门弟子和外门弟子` | `com/mtxgdn/game/service/SectService.java:589` |
| _待填_ | `副宗主不能任命副宗主，只有宗主可以` | `com/mtxgdn/game/service/SectService.java:594` |
| _待填_ | `不能改变宗主的职位` | `com/mtxgdn/game/service/SectService.java:602` |
| _待填_ | `只有宗主才能改变副宗主的职位` | `com/mtxgdn/game/service/SectService.java:605` |
| _待填_ | `长老不能操作同级` | `com/mtxgdn/game/service/SectService.java:608` |
| _待填_ | `已经是` | `com/mtxgdn/game/service/SectService.java:613` |
| _待填_ | `任命为` | `com/mtxgdn/game/service/SectService.java:626` |
| _待填_ | `（原为` | `com/mtxgdn/game/service/SectService.java:626` |
| _待填_ | `贡献值已更新` | `com/mtxgdn/game/service/SectService.java:661` |
| _待填_ | `更新失败，该玩家不在宗门中` | `com/mtxgdn/game/service/SectService.java:664` |
| _待填_ | `你不是该宗门成员` | `com/mtxgdn/game/service/SectService.java:691` |
| _待填_ | `物品不存在` | `com/mtxgdn/game/service/SectService.java:696` |
| _待填_ | `数量必须大于 0` | `com/mtxgdn/game/service/SectService.java:700` |
| _待填_ | `背包中【` | `com/mtxgdn/game/service/SectService.java:703` |
| _待填_ | `】数量不足` | `com/mtxgdn/game/service/SectService.java:703` |
| _待填_ | `已向宗门仓库捐献【` | `com/mtxgdn/game/service/SectService.java:755` |
| _待填_ | `】×` | `com/mtxgdn/game/service/SectService.java:755` |
| _待填_ | `，获得` | `com/mtxgdn/game/service/SectService.java:758` |
| _待填_ | `贡献值` | `com/mtxgdn/game/service/SectService.java:758` |
| _待填_ | `宗门仓库中【` | `com/mtxgdn/game/service/SectService.java:783` |
| _待填_ | `】数量不足（当前：` | `com/mtxgdn/game/service/SectService.java:783` |
| _待填_ | `贡献值不足，取出【` | `com/mtxgdn/game/service/SectService.java:792` |
| _待填_ | `取出失败：` | `com/mtxgdn/game/service/SectService.java:832` |
| _待填_ | `未知错误` | `com/mtxgdn/game/service/SectService.java:832` |
| _待填_ | `只有宗主才能升级宗门` | `com/mtxgdn/game/service/SectService.java:876` |
| _待填_ | `宗门已达到最高等级` | `com/mtxgdn/game/service/SectService.java:885` |
| _待填_ | `级` | `com/mtxgdn/game/service/SectService.java:885` |
| _待填_ | `声望不足，升级需要` | `com/mtxgdn/game/service/SectService.java:891` |
| _待填_ | `声望（当前：` | `com/mtxgdn/game/service/SectService.java:891` |
| _待填_ | `宗门【` | `com/mtxgdn/game/service/SectService.java:908` |
| _待填_ | `】晋升为` | `com/mtxgdn/game/service/SectService.java:908` |
| _待填_ | `级宗门！消耗` | `com/mtxgdn/game/service/SectService.java:908` |
| _待填_ | `声望，成员上限提升至` | `com/mtxgdn/game/service/SectService.java:908` |
| _待填_ | `人` | `com/mtxgdn/game/service/SectService.java:908` |
| _待填_ | `只有宗主才能转让` | `com/mtxgdn/game/service/SectService.java:918` |
| _待填_ | `对方不在你的宗门中` | `com/mtxgdn/game/service/SectService.java:928` |
| _待填_ | `对方已经是宗主了` | `com/mtxgdn/game/service/SectService.java:931` |
| _待填_ | `接收者角色不存在` | `com/mtxgdn/game/service/SectService.java:936` |
| _待填_ | `接收者境界不足，需要达到元婴期以上才能成为宗主（当前：` | `com/mtxgdn/game/service/SectService.java:942` |
| _待填_ | `灵石不足，转让宗主之位需要` | `com/mtxgdn/game/service/SectService.java:949` |
| _待填_ | `已将宗主之位传于` | `com/mtxgdn/game/service/SectService.java:980` |
| _待填_ | `宗门战为实验性内容，当前未开放（可在 config/plugins.yml 中启用 experimental_sect_war）` | `com/mtxgdn/game/service/SectService.java:995` |
| _待填_ | `只有宗主才能发起宗门战` | `com/mtxgdn/game/service/SectService.java:1000` |
| _待填_ | `你的宗门不存在` | `com/mtxgdn/game/service/SectService.java:1005` |
| _待填_ | `不能对自己的宗门宣战` | `com/mtxgdn/game/service/SectService.java:1008` |
| _待填_ | `目标宗门不存在` | `com/mtxgdn/game/service/SectService.java:1013` |
| _待填_ | `目标宗门没有成员` | `com/mtxgdn/game/service/SectService.java:1018` |
| _待填_ | `目标宗门刚经历过宗门战，还需等待` | `com/mtxgdn/game/service/SectService.java:1033` |
| _待填_ | `宗门声望不足，宣战需要` | `com/mtxgdn/game/service/SectService.java:1043` |
| _待填_ | `灵石不足，宣战需要` | `com/mtxgdn/game/service/SectService.java:1050` |
| _待填_ | `宣战失败:` | `com/mtxgdn/game/service/SectService.java:1069` |
| _待填_ | `对` | `com/mtxgdn/game/service/SectService.java:1151` |
| _待填_ | `发起宗门战！\n结果：` | `com/mtxgdn/game/service/SectService.java:1151` |
| _待填_ | `获胜！\n比分：` | `com/mtxgdn/game/service/SectService.java:1151` |
| _待填_ | `只有宗主才能退位` | `com/mtxgdn/game/service/SectService.java:1326` |
| _待填_ | `你是宗门仅剩的成员，无人可以继任。\n若已无意经营，请使用 /宗门 解散` | `com/mtxgdn/game/service/SectService.java:1338` |
| _待填_ | `当前没有进行中的宗主竞争期` | `com/mtxgdn/game/service/SectService.java:1496` |
| _待填_ | `只有本宗宗主或竞争期挑战者可以操作` | `com/mtxgdn/game/service/SectService.java:1504` |
| _待填_ | `你是挑战者，只需发起挑战；应战由宗主操作` | `com/mtxgdn/game/service/SectService.java:1511` |
| _待填_ | `你已发起挑战，请等待宗主使用 /宗门 挑战 接受 应战` | `com/mtxgdn/game/service/SectService.java:1572` |
| _待填_ | `竞争期已结束或状态已变化，挑战未发起` | `com/mtxgdn/game/service/SectService.java:1589` |
| _待填_ | `你已向宗主发起挑战！\n竞争期截止：` | `com/mtxgdn/game/service/SectService.java:1593` |
| _待填_ | `挑战者尚未发起挑战，暂时无法应战` | `com/mtxgdn/game/service/SectService.java:1609` |
| _待填_ | `对决双方角色不存在，竞争期已作废` | `com/mtxgdn/game/service/SectService.java:1619` |
| _待填_ | `挑战状态已变化（可能已结算或竞争期已结束），本次应战未生效` | `com/mtxgdn/game/service/SectService.java:1683` |
| _待填_ | `宗主争夺战结束：` | `com/mtxgdn/game/service/SectService.java:1697` |
| _待填_ | `继任宗主` | `com/mtxgdn/game/service/SectService.java:1697` |
| _待填_ | `只有宗主才能解散宗门` | `com/mtxgdn/game/service/SectService.java:1906` |
| _待填_ | `】已解散，从此江湖再无此门` | `com/mtxgdn/game/service/SectService.java:1931` |

## skill

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `技能不存在` | `com/mtxgdn/game/service/SkillService.java:130` |
| _待填_ | `你已经学会了【` | `com/mtxgdn/game/service/SkillService.java:137` |
| _待填_ | `】` | `com/mtxgdn/game/service/SkillService.java:137` |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/SkillService.java:145` |
| _待填_ | `境界不足，学习【` | `com/mtxgdn/game/service/SkillService.java:152` |
| _待填_ | `】需要达到更高境界` | `com/mtxgdn/game/service/SkillService.java:152` |
| _待填_ | `金币不足，学习【` | `com/mtxgdn/game/service/SkillService.java:159` |
| _待填_ | `】需要` | `com/mtxgdn/game/service/SkillService.java:159` |
| _待填_ | `金币` | `com/mtxgdn/game/service/SkillService.java:159` |
| _待填_ | `灵石不足，学习【` | `com/mtxgdn/game/service/SkillService.java:168` |
| _待填_ | `灵石` | `com/mtxgdn/game/service/SkillService.java:168` |
| _待填_ | `学习技能失败:` | `com/mtxgdn/game/service/SkillService.java:196` |
| _待填_ | `成功学会了【` | `com/mtxgdn/game/service/SkillService.java:202` |
| _待填_ | `】！` | `com/mtxgdn/game/service/SkillService.java:202` |
| _待填_ | `秘籍记载的技能已经失传了...` | `com/mtxgdn/game/service/SkillService.java:220` |
| _待填_ | `境界不足，无法参悟【` | `com/mtxgdn/game/service/SkillService.java:233` |
| _待填_ | `】，需要达到更高境界` | `com/mtxgdn/game/service/SkillService.java:233` |
| _待填_ | `【` | `com/mtxgdn/game/service/SkillService.java:243` |
| _待填_ | `】已达满级` | `com/mtxgdn/game/service/SkillService.java:243` |
| _待填_ | `级，这本秘籍对你已无用处` | `com/mtxgdn/game/service/SkillService.java:243` |
| _待填_ | `参悟成功！【` | `com/mtxgdn/game/service/SkillService.java:258` |
| _待填_ | `】升至` | `com/mtxgdn/game/service/SkillService.java:258` |
| _待填_ | `级！` | `com/mtxgdn/game/service/SkillService.java:258` |
| _待填_ | `参悟成功！学会了【` | `com/mtxgdn/game/service/SkillService.java:275` |

## social

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `用法: /好友 add <玩家名>` | `com/mtxgdn/onebot/command/social/FriendCommand.java:51` |
| _待填_ | `玩家【` | `com/mtxgdn/onebot/command/social/FriendCommand.java:56` |
| _待填_ | `】不存在` | `com/mtxgdn/onebot/command/social/FriendCommand.java:56` |
| _待填_ | `不能添加自己为好友` | `com/mtxgdn/onebot/command/social/FriendCommand.java:61` |
| _待填_ | `✅ 好友申请已发送给【` | `com/mtxgdn/onebot/command/social/FriendCommand.java:66` |
| _待填_ | `】，等待对方接受` | `com/mtxgdn/onebot/command/social/FriendCommand.java:66` |
| _待填_ | `添加失败：` | `com/mtxgdn/onebot/command/social/FriendCommand.java:68` |
| _待填_ | `用法: /好友 accept <玩家名>` | `com/mtxgdn/onebot/command/social/FriendCommand.java:73` |
| _待填_ | `✅ 你与【` | `com/mtxgdn/onebot/command/social/FriendCommand.java:83` |
| _待填_ | `】已成为好友！` | `com/mtxgdn/onebot/command/social/FriendCommand.java:83` |
| _待填_ | `接受失败：对方未向你发送过好友申请` | `com/mtxgdn/onebot/command/social/FriendCommand.java:85` |
| _待填_ | `用法: /好友 reject <玩家名>` | `com/mtxgdn/onebot/command/social/FriendCommand.java:90` |
| _待填_ | `已拒绝【` | `com/mtxgdn/onebot/command/social/FriendCommand.java:100` |
| _待填_ | `】的好友申请` | `com/mtxgdn/onebot/command/social/FriendCommand.java:100` |
| _待填_ | `拒绝失败：对方未向你发送过好友申请` | `com/mtxgdn/onebot/command/social/FriendCommand.java:102` |
| _待填_ | `用法: /好友 remove <玩家名>` | `com/mtxgdn/onebot/command/social/FriendCommand.java:107` |
| _待填_ | `已删除好友【` | `com/mtxgdn/onebot/command/social/FriendCommand.java:117` |
| _待填_ | `】` | `com/mtxgdn/onebot/command/social/FriendCommand.java:117` |
| _待填_ | `删除失败：你们不是好友` | `com/mtxgdn/onebot/command/social/FriendCommand.java:119` |
| _待填_ | `用法: /好友 visit <玩家名>` | `com/mtxgdn/onebot/command/social/FriendCommand.java:124` |
| _待填_ | `你还没有好友。\n添加: /好友 add <玩家名>` | `com/mtxgdn/onebot/command/social/FriendCommand.java:143` |
| _待填_ | `没有待处理的好友申请` | `com/mtxgdn/onebot/command/social/FriendCommand.java:157` |
| _待填_ | `用法: /查人 <名称>\n例: /查人 李白` | `com/mtxgdn/onebot/command/social/PlayerSearchCommand.java:34` |
| _待填_ | `未找到名称包含「` | `com/mtxgdn/onebot/command/social/PlayerSearchCommand.java:42` |
| _待填_ | `」的玩家。` | `com/mtxgdn/onebot/command/social/PlayerSearchCommand.java:42` |
| _待填_ | `用法: /私聊 <玩家名> <消息内容>` | `com/mtxgdn/onebot/command/social/PrivateMessageCommand.java:33` |
| _待填_ | `找不到玩家:` | `com/mtxgdn/onebot/command/social/PrivateMessageCommand.java:48` |
| _待填_ | `不能给自己发私聊消息。` | `com/mtxgdn/onebot/command/social/PrivateMessageCommand.java:54` |
| _待填_ | `私聊消息已发送给` | `com/mtxgdn/onebot/command/social/PrivateMessageCommand.java:60` |

## team

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `创建队伍失败：你可能已在队伍中` | `com/mtxgdn/onebot/command/team/TeamCommand.java:51` |
| _待填_ | `✅ 队伍创建成功！队伍ID:` | `com/mtxgdn/onebot/command/team/TeamCommand.java:53` |
| _待填_ | `用法: /组队 邀请 <玩家名>` | `com/mtxgdn/onebot/command/team/TeamCommand.java:59` |
| _待填_ | `玩家【` | `com/mtxgdn/onebot/command/team/TeamCommand.java:65` |
| _待填_ | `】不存在` | `com/mtxgdn/onebot/command/team/TeamCommand.java:65` |
| _待填_ | `不能邀请自己` | `com/mtxgdn/onebot/command/team/TeamCommand.java:70` |
| _待填_ | `✅ 已向【` | `com/mtxgdn/onebot/command/team/TeamCommand.java:74` |
| _待填_ | `】发出组队邀请，等待对方 /组队 接受` | `com/mtxgdn/onebot/command/team/TeamCommand.java:74` |
| _待填_ | `邀请失败：请确认你是队长、队伍未满员且对方不在其他队伍中` | `com/mtxgdn/onebot/command/team/TeamCommand.java:76` |
| _待填_ | `没有待处理的组队邀请` | `com/mtxgdn/onebot/command/team/TeamCommand.java:82` |
| _待填_ | `✅ 你已加入队伍（队伍ID:` | `com/mtxgdn/onebot/command/team/TeamCommand.java:84` |
| _待填_ | `），队长可发起副本挑战` | `com/mtxgdn/onebot/command/team/TeamCommand.java:84` |
| _待填_ | `已拒绝组队邀请` | `com/mtxgdn/onebot/command/team/TeamCommand.java:89` |
| _待填_ | `你已退出队伍` | `com/mtxgdn/onebot/command/team/TeamCommand.java:93` |
| _待填_ | `你不在任何队伍中` | `com/mtxgdn/onebot/command/team/TeamCommand.java:95` |
| _待填_ | `你不在任何队伍中。\n创建: /组队 创建\n加入: 等队长邀请后 /组队 接受` | `com/mtxgdn/onebot/command/team/TeamCommand.java:101` |
| _待填_ | `用法: /组队 副本 <秘境名>（如：/组队 副本 暗黑森林）` | `com/mtxgdn/onebot/command/team/TeamCommand.java:117` |

## technique

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `用法: /功法 学习 <功法ID>\n先用 /功法 列表 查看功法ID` | `com/mtxgdn/onebot/command/technique/TechniqueCommand.java:74` |
| _待填_ | `功法ID无效` | `com/mtxgdn/onebot/command/technique/TechniqueCommand.java:76` |
| _待填_ | `用法: /功法 装备 <功法ID>` | `com/mtxgdn/onebot/command/technique/TechniqueCommand.java:84` |
| _待填_ | `用法: /功法 卸下 <功法ID>` | `com/mtxgdn/onebot/command/technique/TechniqueCommand.java:94` |
| _待填_ | `用法: /功法 升级 <功法ID>` | `com/mtxgdn/onebot/command/technique/TechniqueCommand.java:104` |

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `服务器错误:` | `com/mtxgdn/onebot/command/technique/TechniqueCommand.java:158` |
| _待填_ | `请提供有效的功法ID(techniqueId)` | `com/mtxgdn/onebot/command/technique/TechniqueCommand.java:196` |

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `功法不存在` | `com/mtxgdn/game/service/TechniqueService.java:140` |
| _待填_ | `你已经学会了【` | `com/mtxgdn/game/service/TechniqueService.java:143` |
| _待填_ | `】` | `com/mtxgdn/game/service/TechniqueService.java:143` |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/TechniqueService.java:147` |
| _待填_ | `境界不足，学习【` | `com/mtxgdn/game/service/TechniqueService.java:150` |
| _待填_ | `】需更高境界` | `com/mtxgdn/game/service/TechniqueService.java:150` |
| _待填_ | `金币不足，需要` | `com/mtxgdn/game/service/TechniqueService.java:153` |
| _待填_ | `金币` | `com/mtxgdn/game/service/TechniqueService.java:153` |
| _待填_ | `灵石不足，需要` | `com/mtxgdn/game/service/TechniqueService.java:157` |
| _待填_ | `灵石` | `com/mtxgdn/game/service/TechniqueService.java:157` |
| _待填_ | `学习功法失败:` | `com/mtxgdn/game/service/TechniqueService.java:177` |
| _待填_ | `成功学会功法【` | `com/mtxgdn/game/service/TechniqueService.java:184` |
| _待填_ | `】！装备后获得属性加成` | `com/mtxgdn/game/service/TechniqueService.java:184` |
| _待填_ | `你尚未学会此功法` | `com/mtxgdn/game/service/TechniqueService.java:192` |
| _待填_ | `【` | `com/mtxgdn/game/service/TechniqueService.java:196` |
| _待填_ | `】已在运转中` | `com/mtxgdn/game/service/TechniqueService.java:196` |
| _待填_ | `最多只能同时运转` | `com/mtxgdn/game/service/TechniqueService.java:200` |
| _待填_ | `门功法，请先卸下一门` | `com/mtxgdn/game/service/TechniqueService.java:200` |
| _待填_ | `开始运转功法【` | `com/mtxgdn/game/service/TechniqueService.java:215` |
| _待填_ | `】并未在运转中` | `com/mtxgdn/game/service/TechniqueService.java:226` |
| _待填_ | `停止运转功法【` | `com/mtxgdn/game/service/TechniqueService.java:242` |
| _待填_ | `】已达最高等级` | `com/mtxgdn/game/service/TechniqueService.java:253` |
| _待填_ | `金币不足，升级需要` | `com/mtxgdn/game/service/TechniqueService.java:261` |
| _待填_ | `灵石不足，升级需要` | `com/mtxgdn/game/service/TechniqueService.java:265` |
| _待填_ | `升级功法失败:` | `com/mtxgdn/game/service/TechniqueService.java:285` |
| _待填_ | `功法【` | `com/mtxgdn/game/service/TechniqueService.java:298` |
| _待填_ | `】升至 Lv.` | `com/mtxgdn/game/service/TechniqueService.java:298` |

## trade

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `物品不存在，请使用 /物品列表 查看可用物品` | `com/mtxgdn/game/service/TradeService.java:436` |
| _待填_ | `近` | `com/mtxgdn/game/service/TradeService.java:477` |
| _待填_ | `天成交` | `com/mtxgdn/game/service/TradeService.java:477` |
| _待填_ | `笔，` | `com/mtxgdn/game/service/TradeService.java:477` |
| _待填_ | `天没有成交记录，` | `com/mtxgdn/game/service/TradeService.java:480` |

## unified_rest_resource

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `服务器内部错误，请稍后重试` | `com/mtxgdn/rest/UnifiedRestResource.java:161` |

## user

### response

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `密码修改成功` | `com/mtxgdn/service/UserService.java:403` |
| _待填_ | `账户注销成功` | `com/mtxgdn/service/UserService.java:451` |
| _待填_ | `密码重置成功，请使用新密码登录` | `com/mtxgdn/service/UserService.java:479` |

## world_boss

### result

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `找不到这只世界Boss。\n使用 /世界boss 查看当前可挑战的列表` | `com/mtxgdn/game/service/WorldBossService.java:382` |
| _待填_ | `【` | `com/mtxgdn/game/service/WorldBossService.java:387` |
| _待填_ | `】已被击杀。\n` | `com/mtxgdn/game/service/WorldBossService.java:387` |
| _待填_ | `角色不存在` | `com/mtxgdn/game/service/WorldBossService.java:395` |
| _待填_ | `你的生命值不足，无法挑战世界Boss` | `com/mtxgdn/game/service/WorldBossService.java:400` |
| _待填_ | `闭关中无法挑战世界Boss，请先 /停止 结束打坐` | `com/mtxgdn/game/service/WorldBossService.java:405` |
| _待填_ | `境界差距过大，不可挑战（需境界序位差小于` | `com/mtxgdn/game/service/WorldBossService.java:412` |
| _待填_ | `挑战冷却中，还需等待` | `com/mtxgdn/game/service/WorldBossService.java:421` |
| _待填_ | `挑战冷却中（或有一次挑战正在进行），请稍后再试` | `com/mtxgdn/game/service/WorldBossService.java:426` |
| _待填_ | `战斗未能开始` | `com/mtxgdn/game/service/WorldBossService.java:434` |

## worldboss

### reply

| key（待填） | 中文文本 | 首次出现位置 |
| --- | --- | --- |
| _待填_ | `用法: /世界boss 挑战 <序号\|名称>\n先用 /世界boss 查看当前有哪些 Boss` | `com/mtxgdn/onebot/command/worldboss/WorldBossCommand.java:81` |
| _待填_ | `找不到这只世界Boss，先用 /世界boss 查看列表` | `com/mtxgdn/onebot/command/worldboss/WorldBossCommand.java:106` |
| _待填_ | `已重建世界Boss池（共` | `com/mtxgdn/onebot/command/worldboss/WorldBossCommand.java:117` |
| _待填_ | `只）：\n\n` | `com/mtxgdn/onebot/command/worldboss/WorldBossCommand.java:117` |

