# GitHub 同类项目清单（文字修仙 / 文字 MUD 服务端）

> 对象项目：**修仙世界 · Xiuxian World**（Java 23 多人在线文字修仙 MUD 服务端，REST / WebSocket / QQ(OneBot+官方机器人) / Minecraft 多端接入）
>
> 检索方式：GitHub Search API（`sort=stars`，4 轮共 36 个关键词查询 + mudchina 组织仓库全量）
>
> 检索时间：2026-10-16　|　去重样本：866 个仓库　|　精选收录：**148** 个

## 分类说明

| 代号 | 分类 |
| --- | --- |
| A | 文字修仙 · 多人 / 服务端（与本项目最同类） |
| B | 文字修仙 · 前端 / 单机 / 放置类 |
| C | 群聊机器人修仙插件（QQ / OneBot / NoneBot / AstrBot / Telegram / Discord） |
| D | 通用 MUD / 文字多人游戏引擎与服务端框架（架构可借鉴） |
| E | MUD 客户端 / 网关 / 工具 |
| F | AI 文字游戏与叙事引擎 |
| G | 中文文字 MUD 经典服务端源码（历史项目） |
| H | 资料、命名器与周边资源 |

## 一、与本项目最接近的 10 个

> 本表按「与本项目的功能重合度」人工排序（非 Star）；Star 数据见第二节总表。

| 项目 | ★ | 语言 | 为什么最像 |
| --- | ---: | --- | --- |
| [alikatong/songjian-kouxian](https://github.com/alikatong/songjian-kouxian) | 2 | JavaScript | 功能面几乎一一对应：修炼/游历/战斗/功法/装备/炼丹炼器/坊市/社交 + 运营管理台，前后端分离 |
| [moyan-ya/xianxia-idle-game](https://github.com/moyan-ya/xianxia-idle-game) | 2 | JavaScript | 同为「服务端权威 + 离线结算」的文字修仙，且强调战斗可复算（防作弊）与灵根克制 |
| [ztkedou/moyuwang-xiuxian](https://github.com/ztkedou/moyuwang-xiuxian) | 2 | Python | 同为带完整服务端（200+ 接口）的浏览器挂机文字修仙，可直接对照接口设计 |
| [setube/vue-XiuXianGameServer](https://github.com/setube/vue-XiuXianGameServer) | 14 | JavaScript | 《我的文字修仙全靠刷》后端，与前端仓库配套，是本类目里最经典的「前后端成对」实现 |
| [setube/vue-xiuxiangame](https://github.com/setube/vue-xiuxiangame) | 1792 | JavaScript | 同类目最高 star 的文字修仙前端，玩法（刷怪/境界/资源循环）高度接近 |
| [oiuv/mud](https://github.com/oiuv/mud) | 178 | C | 中文文字 MUD 服务端：LPC/FluffOS 路线，与本项目「纯文字指令驱动世界」的形态一致 |
| [Nuiio/fluffos-xiuxian-mud](https://github.com/Nuiio/fluffos-xiuxian-mud) | 6 | C | 同样是「中文 + 修仙 + 文字 MUD + Web 客户端接入」的组合 |
| [iwooji77/fanren](https://github.com/iwooji77/fanren) | 27 | - | 群聊场景长线文字修仙（灵根/宗门/洞府/交易/斗法/副本/异步结算），与本项目的 QQ 通道形态一致 |
| [GenKnot/MischicatBot](https://github.com/GenKnot/MischicatBot) | 11 | Python | 机器人端修仙 RPG + Web 管理面板 + SQLite，多端接入思路与本项目适配器体系相近 |
| [bozimmerman/CoffeeMud](https://github.com/bozimmerman/CoffeeMud) | 235 | Java | Java 服务端同类里功能最全的老牌实现（OLC + 内建 Web/邮件），Java 技术栈可直接参考 |

## 二、总表（按 Star 降序，共 148 个）

| # | 项目 | ★ | Fork | 语言 | 分类 | 最后推送 | 说明 |
| ---: | --- | ---: | ---: | --- | :---: | --- | --- |
| 1 | [4thfever/cultivation-world-simulator](https://github.com/4thfever/cultivation-world-simulator) | **2111** | 238 | Python | A | 2026-08-16 | 基于 AI Agent 工作流的修仙世界模拟器（Python），世界规则与事件生成思路可参考 |
| 2 | [evennia/evennia](https://github.com/evennia/evennia) | **2111** | 770 | Python | D | 2026-10-09 | Python MUD/MUX/MUSH 开发系统：最成熟的文本多人游戏框架，插件化与命令体系可直接借鉴 |
| 3 | [setube/vue-xiuxiangame](https://github.com/setube/vue-xiuxiangame) | **1792** | 311 | JavaScript | B | 2026-03-02 | 《我的文字修仙全靠刷》前端（Vue），文字修仙刷子玩法的代表实现 |
| 4 | [zblcving9-gif/xianxia-AI-town-AI-](https://github.com/zblcving9-gif/xianxia-AI-town-AI-) | **1287** | 0 | JavaScript | A | 2026-03-29 | 大模型驱动 NPC 决策、NPC 与玩家平权的修仙小镇（服务端 + 前端） |
| 5 | [flizzywine/dsh-tavern](https://github.com/flizzywine/dsh-tavern) | **1008** | 63 | JavaScript | F | 2026-10-16 | 基于 DSH 的 Agent 酒馆：类酒馆文字游戏 Agent，兼容 SillyTavern 生态 |
| 6 | [Mudlet/Mudlet](https://github.com/Mudlet/Mudlet) | **916** | 388 | C++ | E | 2026-10-16 | 跨平台开源 MUD 客户端（Lua 脚本），可参考其客户端协议与脚本能力 |
| 7 | [RanvierMUD/ranviermud](https://github.com/RanvierMUD/ranviermud) | **858** | 263 | JavaScript | D | 2023-07-11 | Node.js MUD 引擎：数据驱动的房间/区域/技能配置 |
| 8 | [oestrich/ex_venture](https://github.com/oestrich/ex_venture) | **670** | 74 | Elixir | D | 2023-02-14 | Elixir 文本 MMORPG 引擎（Phoenix + WebSocket 高并发架构） |
| 9 | [fluffos/fluffos](https://github.com/fluffos/fluffos) | **463** | 225 | C++ | D | 2026-10-16 | 在维护的 LPMUD 驱动（LPC 解释器，MudOS 分支）——中文 MUD 生态的事实标准驱动 |
| 10 | [qianye60/XianTu](https://github.com/qianye60/XianTu) | **393** | 76 | TypeScript | A | 2026-10-02 | 《仙途》AI 驱动沉浸式修炼文字冒险：Vue 3 + TS + FastAPI 前后端分离 |
| 11 | [Frimkron/mud-pi](https://github.com/Frimkron/mud-pi) | **370** | 90 | Python | D | 2021-07-04 | 极简 Python MUD 服务端（教学向），适合快速理解 MUD 最小结构 |
| 12 | [YunYouJun/advjs](https://github.com/YunYouJun/advjs) | **327** | 25 | TypeScript | F | 2026-10-16 | 面向前端的文字冒险（AVG）游戏引擎 |
| 13 | [setube/vue-idle-xiuxian](https://github.com/setube/vue-idle-xiuxian) | **293** | 95 | Vue | B | 2026-01-06 | 《我的放置仙途》修仙放置游戏（Vue） |
| 14 | [Blightmud/Blightmud](https://github.com/Blightmud/Blightmud) | **278** | 71 | Rust | E | 2026-10-15 | Rust 终端 MUD 客户端 |
| 15 | [scandum/tintin](https://github.com/scandum/tintin) | **263** | 82 | C | E | 2026-09-21 | TinTin++ 控制台 MUD 客户端（可扩展、带地图） |
| 16 | [CassiopeiaCode/TenCyclesofFate](https://github.com/CassiopeiaCode/TenCyclesofFate) | **256** | 59 | Python | F | 2026-05-29 | 《浮生十梦》Web 沉浸式文字冒险，AI 动态生成人生故事 |
| 17 | [GoMudEngine/GoMud](https://github.com/GoMudEngine/GoMud) | **237** | 54 | Go | D | 2026-10-05 | Go 语言 MUD 服务端引擎 |
| 18 | [hythl0day/random_chinese_fantasy_names](https://github.com/hythl0day/random_chinese_fantasy_names) | **237** | 50 | JavaScript | H | 2024-07-25 | NPM 包：随机生成人名/法号/地名/门派/法宝名——修仙游戏命名可直接复用 |
| 19 | [bozimmerman/CoffeeMud](https://github.com/bozimmerman/CoffeeMud) | **235** | 104 | Java | D | 2026-10-15 | Java 全能 MUD 服务端：MSP/MXP/GMCP/OLC + 内建 Web 与邮件服务，Java 同类项目最完整的参照 |
| 20 | [yuanxiaosc/fan-ren-xiu-xian-zhuan](https://github.com/yuanxiaosc/fan-ren-xiu-xian-zhuan) | **235** | 37 | Python | H | 2021-12-27 | 《凡人修仙传》资源汇总（设定资料收集参考） |
| 21 | [Seifert69/DikuMUD3](https://github.com/Seifert69/DikuMUD3) | **227** | 59 | C++ | D | 2026-10-16 | DikuMUD III：HTML + WebSocket 接入的现代 Diku 系服务端 |
| 22 | [timbran-project/moor](https://github.com/timbran-project/moor) | **219** | 14 | Rust | D | 2026-10-14 | Rust 实现、兼容 LambdaMOO 的可编程在线世界系统 |
| 23 | [oestrich/kalevala](https://github.com/oestrich/kalevala) | **197** | 11 | Elixir | D | 2025-05-04 | Elixir 世界构建工具箱（与 ex_venture 同作者） |
| 24 | [wap-game/xiyou](https://github.com/wap-game/xiyou) | **187** | 117 | PHP | H | 2026-09-07 | WAP 文字游戏《幻想西游》PHP 源码（早期文字网游形态） |
| 25 | [jonradoff/lofp](https://github.com/jonradoff/lofp) | **181** | 32 | HTML | D | 2026-05-17 | 90 年代商业 MUD《Legends of Future Past》开源重现（Go） |
| 26 | [oiuv/mud](https://github.com/oiuv/mud) | **178** | 68 | C | A | 2026-10-16 | 炎黄文字 MUD（中文题材 LPMUD，需 FluffOS v2026 驱动）——仍在维护的中文文字 MUD 服务端样本 |
| 27 | [JeasonLoop/react-xiuxian-game](https://github.com/JeasonLoop/react-xiuxian-game) | **168** | 38 | TypeScript | B | 2026-09-28 | 一款文字修仙小游戏（React） |
| 28 | [MoreOutput/RockMUD](https://github.com/MoreOutput/RockMUD) | **155** | 44 | JavaScript | D | 2023-03-04 | Node + WebSocket MUD 服务端，浏览器直连范式 |
| 29 | [ArchaicQuest/ArchaicQuest-II](https://github.com/ArchaicQuest/ArchaicQuest-II) | **151** | 28 | C# | D | 2023-04-20 | C# 多人文字 RPG（浏览器可玩），含客户端与服务端 |
| 30 | [ChurchTao/Daoyou](https://github.com/ChurchTao/Daoyou) | **151** | 50 | TypeScript | A | 2026-10-16 | 《万界道友》AIGC 驱动、高自由度文字修仙开源游戏（功法/灵根/神通/法宝/奇遇） |
| 31 | [mudcoders/awesome-mud](https://github.com/mudcoders/awesome-mud) | **143** | 10 | - | H | 2026-04-07 | 文字游戏开发者的资源总表（引擎/工具/教程） |
| 32 | [pennmush/pennmush](https://github.com/pennmush/pennmush) | **129** | 55 | C | D | 2026-08-19 | 1992 年至今仍在维护的 C 语言 MUSH 服务端 |
| 33 | [bbuck/dragon-mud](https://github.com/bbuck/dragon-mud) | **122** | 16 | Go | D | 2026-10-07 | Go + Lua 脚本化的文字游戏引擎 |
| 34 | [851896022/DesktopXiuXian](https://github.com/851896022/DesktopXiuXian) | **111** | 38 | C++ | A | 2021-11-18 | 桌面修仙：放置 + 点击 + 桌面立绘 + 聊天吹水 + 运势推演 + 联网排行 |
| 35 | [fiendish/aardwolfclientpackage](https://github.com/fiendish/aardwolfclientpackage) | **103** | 38 | Lua | E | 2026-10-07 | Aardwolf MUSHclient 客户端整合包 |
| 36 | [s52047qwas/nonebot_plugin_xiuxian](https://github.com/s52047qwas/nonebot_plugin_xiuxian) | **103** | 27 | Python | C | 2023-11-28 | NoneBot 群内修仙模拟器（星标最高的 QQ 群修仙插件） |
| 37 | [duysqubix/MuOxi](https://github.com/duysqubix/MuOxi) | **101** | 13 | Rust | D | 2026-05-16 | Rust 现代 MUD 引擎 |
| 38 | [EmpireMUD/EmpireMUD-2.0-Beta](https://github.com/EmpireMUD/EmpireMUD-2.0-Beta) | **100** | 43 | C | D | 2026-10-15 | 带持久化世界地图的 MUD 服务端（CircleMUD/DikuMUD 衍生） |
| 39 | [QingMuCat/nonebot_plugin_xiuxian_2](https://github.com/QingMuCat/nonebot_plugin_xiuxian_2) | **98** | 19 | Python | C | 2024-10-09 | 基于 NoneBot2 的修仙文字游戏插件（第二代，社区常用） |
| 40 | [mmdi1/yundingxx-game](https://github.com/mmdi1/yundingxx-game) | **94** | 20 | JavaScript | B | 2020-09-01 | 《云顶修仙》游戏文档：新手引导/游戏概要/攻略/物品类目（数值与文档参考） |
| 41 | [Elixir-journey/dragons-tome](https://github.com/Elixir-journey/dragons-tome) | **93** | 4 | - | H | 2024-04-24 | 用 Elixir 构建分布式文字 MUD 的实战教程书 |
| 42 | [ingwarsw/tinyfugue](https://github.com/ingwarsw/tinyfugue) | **90** | 49 | C | E | 2026-10-11 | TinyFugue 重生版 MUD 客户端 |
| 43 | [doughsay/room.js](https://github.com/doughsay/room.js) | **88** | 18 | JavaScript | D | 2020-05-25 | Node.js MOO 服务端 |
| 44 | [e5sub/Text-Legend](https://github.com/e5sub/Text-Legend) | **87** | 37 | JavaScript | H | 2026-09-14 | 文字游戏/文字网游/开源网游/收菜游戏（中文文字网游实现） |
| 45 | [mudchina/webtelnet](https://github.com/mudchina/webtelnet) | **86** | 29 | JavaScript | E | 2016-03-14 | WebSocket → Telnet 网关：让浏览器直连 MUD 服务端（多端接入思路可借鉴） |
| 46 | [maldorne/awesome-muds](https://github.com/maldorne/awesome-muds) | **81** | 7 | - | H | 2026-08-04 | MUD 开发资源、工具与客户端精选列表 |
| 47 | [mapleobserver/wsmud-script](https://github.com/mapleobserver/wsmud-script) | **81** | 38 | JavaScript | E | 2026-10-01 | 《武神传说》文字 MUD 自动化脚本（玩家玩法视角） |
| 48 | [jasonstolaruk/CurryMUD](https://github.com/jasonstolaruk/CurryMUD) | **78** | 14 | Haskell | D | 2023-12-12 | Haskell 实现的 MUD 服务端（函数式架构参考） |
| 49 | [wowpin/dumserver](https://github.com/wowpin/dumserver) | **71** | 13 | JavaScript | D | 2023-09-25 | 现代 MU* 引擎（重写版，持续维护） |
| 50 | [shmup/miniboa](https://github.com/shmup/miniboa) | **66** | 13 | Python | D | 2026-04-13 | 异步单线程 poll 式 Telnet 服务端（Python），协议层极简范本 |
| 51 | [TalesMUD/talesmud](https://github.com/TalesMUD/talesmud) | **66** | 11 | Go | D | 2026-10-16 | Golang MUD 开发框架/平台 |
| 52 | [hongzhongx/taiyi](https://github.com/hongzhongx/taiyi) | **64** | 7 | C++ | A | 2026-01-21 | 多人模拟修仙世界虚拟小游戏（区块链自主世界基础设施） |
| 53 | [8720826/emud](https://github.com/8720826/emud) | **58** | 13 | C# | D | 2026-03-16 | netcore + SignalR + Vue 的 MUD 游戏框架（.NET 全栈） |
| 54 | [svof/svof](https://github.com/svof/svof) | **55** | 66 | Lua | E | 2026-10-13 | Achaea MUD 的 AI 辅助系统（战斗/治疗自动化） |
| 55 | [setube/yunyin-xiuxian](https://github.com/setube/yunyin-xiuxian) | **54** | 11 | TypeScript | B | 2026-10-16 | 《云隐修仙录》文字修仙放置游戏 |
| 56 | [Neop/mudmap2](https://github.com/Neop/mudmap2) | **52** | 10 | Java | E | 2024-08-28 | Java 文字游戏地图工具（MUD/MUSH/文字冒险通用） |
| 57 | [lianglllll/MMORPG-Server](https://github.com/lianglllll/MMORPG-Server) | **50** | 17 | C# | A | 2026-09-26 | 现代修仙体系 MMORPG 服务端（C#，分布式架构方向） |
| 58 | [MyXiaoNan/nonebot_plugin_xiuxian_2_pmv](https://github.com/MyXiaoNan/nonebot_plugin_xiuxian_2_pmv) | **49** | 12 | Python | C | 2026-08-03 | NoneBot2 修仙文字游戏魔改版（图片渲染玩法） |
| 59 | [darvin/Holodeck](https://github.com/darvin/Holodeck) | **46** | 2 | Jupyter Notebook | F | 2023-04-18 | MUDGPT：AI 生成虚拟世界的多人游戏实验 |
| 60 | [ZabinX/DuskRPG](https://github.com/ZabinX/DuskRPG) | **42** | 12 | Java | D | 2025-12-11 | Java 图形化 MUD（玩法同文字 MUD，界面图形化） |
| 61 | [jnharton/mud-server](https://github.com/jnharton/mud-server) | **41** | 11 | Java | D | 2026-05-12 | Java MUD 服务端：含基础 OLC 与频道聊天系统 |
| 62 | [Lizhenghe-Chen/fanren-wiki](https://github.com/Lizhenghe-Chen/fanren-wiki) | **38** | 4 | HTML | H | 2026-10-16 | 《凡人修仙传》资料图谱：人物/灵兽/丹药/法宝/境界演进 |
| 63 | [mudchina/es2-utf8](https://github.com/mudchina/es2-utf8) | **33** | 30 | JavaScript | G | 2017-01-10 | 东方故事 II MudLib（Annihilator 版，含地图，UTF-8）——中文 MUD 鼻祖的 UTF-8 分支 |
| 64 | [mudchina/xkx100](https://github.com/mudchina/xkx100) | **31** | 21 | C | G | 2016-02-21 | 侠客行 100 服务端（C，GBK） |
| 65 | [holycake/mhsj](https://github.com/holycake/mhsj) | **30** | 8 | C | A | 2025-07-22 | 基于 FluffOS 2019 的西游记题材文字 MUD 服务端（UTF-8），中文 LPC 服务端范例 |
| 66 | [mudchina/xkx2001](https://github.com/mudchina/xkx2001) | **30** | 27 | C | G | 2016-02-20 | 侠客行 2001 服务端（C，GBK） |
| 67 | [malkamius/CrimsonStainedLands](https://github.com/malkamius/CrimsonStainedLands) | **28** | 11 | C# | D | 2026-08-24 | 基于 ROM 的 C# MUD 服务端 |
| 68 | [iwooji77/fanren](https://github.com/iwooji77/fanren) | **27** | 4 | - | A | 2026-07-03 | Telegram 群聊长线文字修仙：灵根检测→闭关/宗门/洞府/交易/斗法/副本，异步结算 + 每日命令，玩法结构与本项目高度相似 |
| 69 | [mudchina/xyj2006](https://github.com/mudchina/xyj2006) | **25** | 23 | C | G | 2016-02-21 | 西游记 2006 服务端（C，GBK） |
| 70 | [boredream/FightWithoutEnd](https://github.com/boredream/FightWithoutEnd) | **24** | 11 | Java | D | 2013-03-27 | Android 文字类游戏（类似「无尽的进度条」） |
| 71 | [mudchina/es2](https://github.com/mudchina/es2) | **24** | 19 | C | G | 2016-02-21 | 东方故事 II（ES2）服务端（C，GBK）——中文 MUD 鼻祖 |
| 72 | [mudchina/xyj45](https://github.com/mudchina/xyj45) | **23** | 31 | C | G | 2016-02-21 | 西游记 4.5 服务端（C，GBK） |
| 73 | [KirisakiAria/sdxxz](https://github.com/KirisakiAria/sdxxz) | **21** | 3 | Vue | B | 2023-03-01 | 《傻屌修仙传》修仙题材网页游戏（Vue） |
| 74 | [mudchina/jy](https://github.com/mudchina/jy) | **21** | 23 | C | G | 2016-02-21 | 金庸群侠传服务端（C，GBK） |
| 75 | [thezboe/Wasteland-MUD](https://github.com/thezboe/Wasteland-MUD) | **21** | 6 | Java | D | 2011-01-25 | Java 命令行多人地牢游戏 |
| 76 | [ProjectMoon/ringmud](https://github.com/ProjectMoon/ringmud) | **20** | 6 | Java | D | 2020-08-17 | Java MUD 服务端与引擎 |
| 77 | [RahjIII/lociterm](https://github.com/RahjIII/lociterm) | **19** | 7 | C | E | 2026-02-03 | PWA 形式的 MUD Telnet 客户端 |
| 78 | [scionaltera/agony-engine](https://github.com/scionaltera/agony-engine) | **18** | 5 | Java | D | 2019-03-17 | 现代 Web 化、程序化生成的 MUD 引擎（Java） |
| 79 | [oldPeter616/astrbot_plugin_xiuxian](https://github.com/oldPeter616/astrbot_plugin_xiuxian) | **17** | 5 | Python | C | 2025-09-30 | AstrBot 框架的修仙小游戏插件 |
| 80 | [mudchina/mhxy2002](https://github.com/mudchina/mhxy2002) | **16** | 20 | C | G | 2016-02-20 | 梦幻西游 2002 服务端（C，GBK） |
| 81 | [PentaKill1/ZhiJianXiuXian](https://github.com/PentaKill1/ZhiJianXiuXian) | **16** | 3 | C# | B | 2020-06-27 | 《指尖修仙》文字挂机游戏（C#） |
| 82 | [LingTian/tao-kb](https://github.com/LingTian/tao-kb) | **15** | 2 | HTML | B | 2026-07-28 | 道教知识库：AI 驱动的修道典籍知识工程，可作世界观资料源 |
| 83 | [setube/xiuxian-world](https://github.com/setube/xiuxian-world) | **15** | 5 | TypeScript | A | 2026-01-06 | setube 系列修仙世界观仓库（TypeScript） |
| 84 | [imdong/YunDingOnlineSDK](https://github.com/imdong/YunDingOnlineSDK) | **14** | 6 | JavaScript | A | 2020-07-12 | 《云顶修仙》游戏 SDK（对接同名线上服务） |
| 85 | [SaiYing/cxxy](https://github.com/SaiYing/cxxy) | **14** | 1 | HTML | B | 2018-03-14 | H5 打包的修仙类小游戏 |
| 86 | [setube/vue-XiuXianGameServer](https://github.com/setube/vue-XiuXianGameServer) | **14** | 9 | JavaScript | A | 2024-10-13 | 《我的文字修仙全靠刷》配套后端服务，与 setube 前端仓库成对使用 |
| 87 | [mudchina/fy4](https://github.com/mudchina/fy4) | **13** | 14 | C | G | 2016-02-21 | 风云 4 服务端（C，GBK） |
| 88 | [roomsmith-games/NeoMud](https://github.com/roomsmith-games/NeoMud) | **13** | 2 | Kotlin | D | 2026-07-18 | Kotlin Multiplatform + Ktor WebSocket 服务端 + Compose 客户端 |
| 89 | [928721935/duobaoZhonghua](https://github.com/928721935/duobaoZhonghua) | **12** | 2 | Java | C | 2021-02-18 | 文字 WAP 武侠游戏《夺宝中华》（Java，聊天/网页端文字玩法） |
| 90 | [ganchengcaiyue/nonebot_plugin_simulator_xiuxian](https://github.com/ganchengcaiyue/nonebot_plugin_simulator_xiuxian) | **12** | 3 | Python | C | 2024-11-27 | 基于 NoneBot2 的修仙文字游戏插件（模拟器向） |
| 91 | [liyw0205/nonebot_plugin_xiuxian_2_pmv](https://github.com/liyw0205/nonebot_plugin_xiuxian_2_pmv) | **12** | 5 | Python | C | 2026-10-16 | 修仙插件 pmv 分支 |
| 92 | [602723113/Grus](https://github.com/602723113/Grus) | **11** | 3 | Java | C | 2023-01-20 | 基于 BukkitAPI 的修仙插件（Minecraft 服务端接入参考） |
| 93 | [GenKnot/MischicatBot](https://github.com/GenKnot/MischicatBot) | **11** | 4 | Python | A | 2026-10-13 | Discord 修仙文字 RPG 机器人：角色/境界/宗门/锻造/炼丹/赌坊/钱庄/交易坊 + Web 管理面板 + SQLite |
| 94 | [HungTeen/Immortal](https://github.com/HungTeen/Immortal) | **10** | 1 | Java | C | 2025-06-24 | Minecraft 修仙 mod（Java），跨端题材参考 |
| 95 | [sshenlian/xiuxian](https://github.com/sshenlian/xiuxian) | **10** | 2 | HTML | B | 2022-01-15 | 修仙秘境 Web 安全靶场（命名撞车，实为安全练习平台，仅作命名参考） |
| 96 | [ypyik0669/wendao](https://github.com/ypyik0669/wendao) | **10** | 6 | JavaScript | A | 2026-09-07 | 《问道》论坛修仙放置+竞技：挂机修炼/渡劫/秘境/论道/宗门/世界 BOSS/轮回 |
| 97 | [flyinglb/jyqx](https://github.com/flyinglb/jyqx) | **9** | 5 | C | G | 2019-12-06 | 金庸群侠传文字 MUD 游戏 |
| 98 | [mudchina/nitan3](https://github.com/mudchina/nitan3) | **9** | 13 | C | G | 2016-02-21 | 泥潭 3 服务端（C，GBK） |
| 99 | [rockbenben/thousand-lives](https://github.com/rockbenben/thousand-lives) | **9** | 2 | TypeScript | F | 2026-10-13 | 《千世书》AI 文字人生模拟器（含修仙剧本），纯前端 |
| 100 | [13579123/ImmortalRoadProjects](https://github.com/13579123/ImmortalRoadProjects) | **8** | 2 | TypeScript | B | 2025-12-05 | 一个挂机修仙游戏的源码（TypeScript） |
| 101 | [awakeyoyoyo/mmoRpgDemo](https://github.com/awakeyoyoyo/mmoRpgDemo) | **8** | 2 | Java | A | 2021-02-08 | 基于文字表现的 MMORPG 示例（Java），可作最小多人文字游戏骨架 |
| 102 | [SunnyYuer/Story-ConAndroid](https://github.com/SunnyYuer/Story-ConAndroid) | **8** | 4 | C++ | D | 2016-09-14 | 《Story 传奇》文字 MUD 安卓版 |
| 103 | [aosumi-rena/XiuXianBot](https://github.com/aosumi-rena/XiuXianBot) | **7** | 0 | Python | A | 2025-07-26 | 通用核心逻辑 + 多平台适配（Telegram/Discord）的文字修仙 Bot，架构参考价值高 |
| 104 | [Huu-Yuu/DeskXiuxian](https://github.com/Huu-Yuu/DeskXiuxian) | **7** | 2 | C++ | A | 2026-06-04 | 办公室摸鱼修仙：角色养成/物品/装备/副本/联网排行 |
| 105 | [liyuewen/XiuXian](https://github.com/liyuewen/XiuXian) | **7** | 0 | TypeScript | A | 2022-12-13 | 修仙游戏后端（TypeScript） |
| 106 | [mudchina/sjsh](https://github.com/mudchina/sjsh) | **7** | 12 | C | G | 2016-02-21 | 三界神话服务端（C，GBK 简体） |
| 107 | [nijakow/four](https://github.com/nijakow/four) | **7** | 0 | Java | D | 2022-09-25 | Java 实现的 MUD 引擎 |
| 108 | [bhavanki/grounds](https://github.com/bhavanki/grounds) | **6** | 1 | Java | D | 2023-07-10 | Java 可扩展 MUSH/MUD 服务端 |
| 109 | [cnsuhao/dazhuzai](https://github.com/cnsuhao/dazhuzai) | **6** | 0 | Python | A | 2017-01-22 | 《大主宰 OL》微信公众号即时回应式文字 MUD 网游（9 秒社团开源），聊天端接入玩法的早期范本 |
| 110 | [landriesnidis/MartialArtsCirclesRPG](https://github.com/landriesnidis/MartialArtsCirclesRPG) | **6** | 2 | Java | D | 2019-05-15 | 文字武侠 RPG（Java），基于层级文本菜单框架开发 |
| 111 | [landriesnidis/PyramidTextMenu-java](https://github.com/landriesnidis/PyramidTextMenu-java) | **6** | 4 | Java | D | 2022-06-17 | Java 层级文本菜单框架，适用于文字冒险游戏/问答机器人 |
| 112 | [Martinqi826/xiuxian](https://github.com/Martinqi826/xiuxian) | **6** | 2 | HTML | B | 2026-05-12 | 《凡人修仙路》单文件 HTML 修真模拟挂机游戏 |
| 113 | [mudchina/fy3](https://github.com/mudchina/fy3) | **6** | 10 | C | G | 2016-02-21 | 风云 3 服务端（C，GBK） |
| 114 | [Nuiio/fluffos-xiuxian-mud](https://github.com/Nuiio/fluffos-xiuxian-mud) | **6** | 0 | C | A | 2026-06-07 | 基于 FluffOS 的中文修仙主题 MUD，支持 Telnet 与 Web 客户端 |
| 115 | [zblcving9-gif/npc-decision-powered-by-LLM-xiuxian-Ascension](https://github.com/zblcving9-gif/npc-decision-powered-by-LLM-xiuxian-Ascension) | **6** | 1 | JavaScript | A | 2026-03-29 | 上者的 v3：2D 物理沙盒 + AI NPC 对话 + 修仙模拟 |
| 116 | [medehn/coffeemud](https://github.com/medehn/coffeemud) | **5** | 0 | Java | D | 2019-05-20 | Java 小型 MUD，同时提供 WebSocket 与 Telnet 接入 |
| 117 | [devgo-cn/xianren-xiuxian](https://github.com/devgo-cn/xianren-xiuxian) | **4** | 0 | JavaScript | B | 2026-09-21 | 闲人修仙：HTML5 水墨国风挂机放置，离线收益 |
| 118 | [DITF16/astrbot_plugin_simple_xiuxian](https://github.com/DITF16/astrbot_plugin_simple_xiuxian) | **4** | 1 | Python | C | 2025-07-10 | AstrBot 简易修仙模拟器插件 |
| 119 | [mudchina/fy2](https://github.com/mudchina/fy2) | **4** | 7 | C | G | 2016-02-21 | 风云 2 服务端（C，GBK） |
| 120 | [whouare-dot/xiantu](https://github.com/whouare-dot/xiantu) | **4** | 1 | JavaScript | B | 2026-09-13 | 纯前端文字修仙放置游戏：零依赖零构建，二十六重境界 + 轮回转世 |
| 121 | [mudchina/fluffos](https://github.com/mudchina/fluffos) | **3** | 1 | C++ | G | 2016-02-21 | MudChina 维护的 FluffOS 分支（LPC 驱动） |
| 122 | [victical/astrbot_plugin_simulation-xiuxian](https://github.com/victical/astrbot_plugin_simulation-xiuxian) | **3** | 1 | Python | C | 2025-10-15 | AstrBot 修仙模拟插件 |
| 123 | [0x0077/xiuxian-tg-bot](https://github.com/0x0077/xiuxian-tg-bot) | **2** | 4 | Python | C | 2025-03-30 | Telegram 修仙小游戏机器人（修炼/探索/渡劫） |
| 124 | [alikatong/songjian-kouxian](https://github.com/alikatong/songjian-kouxian) | **2** | 0 | JavaScript | A | 2026-07-28 | 前后端分离国风文字修仙 RPG（React + Node + PostgreSQL）：修炼/游历/战斗/功法/装备/炼丹炼器/坊市/社交 + 运营管理台，功能面对齐度最高 |
| 125 | [bestop/xiantu](https://github.com/bestop/xiantu) | **2** | 0 | TypeScript | B | 2026-10-15 | 仙途挂机：Next.js 16 + React 19，16 技能/百种妖兽/离线进度/随机词条装备 |
| 126 | [liyw0205/nonebot_plugin_xiuxian_3](https://github.com/liyw0205/nonebot_plugin_xiuxian_3) | **2** | 0 | Python | C | 2026-10-16 | 从零重写的 NoneBot 修仙插件（含功能规格/架构决策/开发路线文档） |
| 127 | [moyan-ya/xianxia-idle-game](https://github.com/moyan-ya/xianxia-idle-game) | **2** | 1 | JavaScript | A | 2026-10-01 | 服务端权威 + 种子可复算战斗 + 惰性离线结算 + 逐灵根五行克制；Vue2.6 + Node16 + MySQL |
| 128 | [SiYue2024S/xiantu](https://github.com/SiYue2024S/xiantu) | **2** | 0 | Vue | A | 2025-03-12 | 《我的放置仙途》修仙网页文字游戏，PC + H5 自适应 |
| 129 | [wangfeng0629/ZTrunk_Server](https://github.com/wangfeng0629/ZTrunk_Server) | **2** | 1 | Go | A | 2019-01-22 | 仙侠世界挂机版服务端（Go） |
| 130 | [ZergSu01/xuanjian](https://github.com/ZergSu01/xuanjian) | **2** | 1 | HTML | H | 2026-09-21 | 玄幻名词生成器：17 大类/8 种风格/关键词融合 |
| 131 | [ztkedou/moyuwang-xiuxian](https://github.com/ztkedou/moyuwang-xiuxian) | **2** | 0 | Python | A | 2026-10-14 | 《摸鱼修仙传》完整可复现源码包：React 单页 + Node/TS 后端，200+ 接口服务端 |
| 132 | [zzk159753/nonebot_plugin_xiuxian_2_pmv_lunar](https://github.com/zzk159753/nonebot_plugin_xiuxian_2_pmv_lunar) | **2** | 1 | Python | C | 2025-04-03 | pmv 魔改版的再魔改版 |
| 133 | [hanli1999/xiuxian](https://github.com/hanli1999/xiuxian) | **1** | 0 | Python | F | 2026-09-21 | 以《凡人修仙传》730 万字全文为知识库的类比检索引擎 |
| 134 | [Ke-scholar/xiuxian-bot](https://github.com/Ke-scholar/xiuxian-bot) | **1** | 1 | - | C | 2024-03-23 | 修仙文字机器人 |
| 135 | [lbwwork/wzxxl-game-logic](https://github.com/lbwwork/wzxxl-game-logic) | **1** | 0 | Java | A | 2023-11-08 | 《文字修仙录》游戏逻辑模块（Java），按模块拆分的服务端逻辑 |
| 136 | [liyw0205/nonebot_plugin_spirit_pet](https://github.com/liyw0205/nonebot_plugin_spirit_pet) | **1** | 0 | Python | C | 2026-10-16 | 灵宠修仙文字游戏，支持 OneBot V11 与 QQ 官方机器人 Markdown/按钮/蓝字 |
| 137 | [TsoiTZF/astrbot_plugin_xiuxian_wenyou](https://github.com/TsoiTZF/astrbot_plugin_xiuxian_wenyou) | **1** | 0 | Python | C | 2026-08-12 | 《修仙纪元》AstrBot 修仙文字游戏 |
| 138 | [yowasa/GO_XIUXIAN_QQ_CHANNEL_BOT](https://github.com/yowasa/GO_XIUXIAN_QQ_CHANNEL_BOT) | **1** | 1 | Go | C | 2023-01-12 | QQ 频道修仙机器人（Go）——频道场景适配参考 |
| 139 | [2528078765/RAG-Bot](https://github.com/2528078765/RAG-Bot) | **0** | 0 | Python | F | 2026-06-30 | 《凡人修仙传》RAG 问答机器人（世界观问答/检索增强参考） |
| 140 | [gugeniubu-hub/qq-xiuxian-bot](https://github.com/gugeniubu-hub/qq-xiuxian-bot) | **0** | 0 | Python | C | 2026-06-19 | QQ 群文字修仙游戏机器人骨架 |
| 141 | [HeavyFlagship/mud-game-engine](https://github.com/HeavyFlagship/mud-game-engine) | **0** | 0 | JavaScript | D | 2026-10-02 | 中文「网络文字游戏引擎」（JS，附科幻题材示例） |
| 142 | [Lan-0v0/astrbot_plugin_mud_lineage1](https://github.com/Lan-0v0/astrbot_plugin_mud_lineage1) | **0** | 0 | - | C | 2026-08-12 | AstrBot 天堂1 MUD 玩法插件（以文字还原经典 MMORPG） |
| 143 | [leo7758521/xiaoyao](https://github.com/leo7758521/xiaoyao) | **0** | 0 | - | C | 2025-07-28 | 逍遥修仙 QQ 机器人 |
| 144 | [mudchina/mudclient](https://github.com/mudchina/mudclient) | **0** | 0 | - | G | 2016-02-21 | MudChina 收录的常用 MUD 客户端集合 |
| 145 | [pplmx/xian](https://github.com/pplmx/xian) | **0** | 0 | TypeScript | B | 2026-10-16 | 《玄枢录》四界二十一境、派遣历练、后台预解算回合制战斗，离线可玩（Web/PWA/桌面） |
| 146 | [ThySummer14/xiuxianlu](https://github.com/ThySummer14/xiuxianlu) | **0** | 0 | JavaScript | B | 2026-10-07 | 《斩妖·修仙录》水墨风修仙放置（纯 Canvas 零素材） |
| 147 | [w3w4w/nbxiuxian](https://github.com/w3w4w/nbxiuxian) | **0** | 0 | Python | C | 2026-01-31 | NoneBot 修仙插件（早期实验版） |
| 148 | [win-syswow64/xiuxian.bot](https://github.com/win-syswow64/xiuxian.bot) | **0** | 2 | TypeScript | C | 2024-08-09 | 修仙机器人（TypeScript） |

## 三、分类清单

### A. 文字修仙 · 多人 / 服务端（与本项目最同类）（28）

| 项目 | ★ | 语言 | 最后推送 | 说明 |
| --- | ---: | --- | --- | --- |
| [4thfever/cultivation-world-simulator](https://github.com/4thfever/cultivation-world-simulator) | 2111 | Python | 2026-08-16 | 基于 AI Agent 工作流的修仙世界模拟器（Python），世界规则与事件生成思路可参考 |
| [zblcving9-gif/xianxia-AI-town-AI-](https://github.com/zblcving9-gif/xianxia-AI-town-AI-) | 1287 | JavaScript | 2026-03-29 | 大模型驱动 NPC 决策、NPC 与玩家平权的修仙小镇（服务端 + 前端） |
| [qianye60/XianTu](https://github.com/qianye60/XianTu) | 393 | TypeScript | 2026-10-02 | 《仙途》AI 驱动沉浸式修炼文字冒险：Vue 3 + TS + FastAPI 前后端分离 |
| [oiuv/mud](https://github.com/oiuv/mud) | 178 | C | 2026-10-16 | 炎黄文字 MUD（中文题材 LPMUD，需 FluffOS v2026 驱动）——仍在维护的中文文字 MUD 服务端样本 |
| [ChurchTao/Daoyou](https://github.com/ChurchTao/Daoyou) | 151 | TypeScript | 2026-10-16 | 《万界道友》AIGC 驱动、高自由度文字修仙开源游戏（功法/灵根/神通/法宝/奇遇） |
| [851896022/DesktopXiuXian](https://github.com/851896022/DesktopXiuXian) | 111 | C++ | 2021-11-18 | 桌面修仙：放置 + 点击 + 桌面立绘 + 聊天吹水 + 运势推演 + 联网排行 |
| [hongzhongx/taiyi](https://github.com/hongzhongx/taiyi) | 64 | C++ | 2026-01-21 | 多人模拟修仙世界虚拟小游戏（区块链自主世界基础设施） |
| [lianglllll/MMORPG-Server](https://github.com/lianglllll/MMORPG-Server) | 50 | C# | 2026-09-26 | 现代修仙体系 MMORPG 服务端（C#，分布式架构方向） |
| [holycake/mhsj](https://github.com/holycake/mhsj) | 30 | C | 2025-07-22 | 基于 FluffOS 2019 的西游记题材文字 MUD 服务端（UTF-8），中文 LPC 服务端范例 |
| [iwooji77/fanren](https://github.com/iwooji77/fanren) | 27 | - | 2026-07-03 | Telegram 群聊长线文字修仙：灵根检测→闭关/宗门/洞府/交易/斗法/副本，异步结算 + 每日命令，玩法结构与本项目高度相似 |
| [setube/xiuxian-world](https://github.com/setube/xiuxian-world) | 15 | TypeScript | 2026-01-06 | setube 系列修仙世界观仓库（TypeScript） |
| [imdong/YunDingOnlineSDK](https://github.com/imdong/YunDingOnlineSDK) | 14 | JavaScript | 2020-07-12 | 《云顶修仙》游戏 SDK（对接同名线上服务） |
| [setube/vue-XiuXianGameServer](https://github.com/setube/vue-XiuXianGameServer) | 14 | JavaScript | 2024-10-13 | 《我的文字修仙全靠刷》配套后端服务，与 setube 前端仓库成对使用 |
| [GenKnot/MischicatBot](https://github.com/GenKnot/MischicatBot) | 11 | Python | 2026-10-13 | Discord 修仙文字 RPG 机器人：角色/境界/宗门/锻造/炼丹/赌坊/钱庄/交易坊 + Web 管理面板 + SQLite |
| [ypyik0669/wendao](https://github.com/ypyik0669/wendao) | 10 | JavaScript | 2026-09-07 | 《问道》论坛修仙放置+竞技：挂机修炼/渡劫/秘境/论道/宗门/世界 BOSS/轮回 |
| [awakeyoyoyo/mmoRpgDemo](https://github.com/awakeyoyoyo/mmoRpgDemo) | 8 | Java | 2021-02-08 | 基于文字表现的 MMORPG 示例（Java），可作最小多人文字游戏骨架 |
| [aosumi-rena/XiuXianBot](https://github.com/aosumi-rena/XiuXianBot) | 7 | Python | 2025-07-26 | 通用核心逻辑 + 多平台适配（Telegram/Discord）的文字修仙 Bot，架构参考价值高 |
| [Huu-Yuu/DeskXiuxian](https://github.com/Huu-Yuu/DeskXiuxian) | 7 | C++ | 2026-06-04 | 办公室摸鱼修仙：角色养成/物品/装备/副本/联网排行 |
| [liyuewen/XiuXian](https://github.com/liyuewen/XiuXian) | 7 | TypeScript | 2022-12-13 | 修仙游戏后端（TypeScript） |
| [cnsuhao/dazhuzai](https://github.com/cnsuhao/dazhuzai) | 6 | Python | 2017-01-22 | 《大主宰 OL》微信公众号即时回应式文字 MUD 网游（9 秒社团开源），聊天端接入玩法的早期范本 |
| [Nuiio/fluffos-xiuxian-mud](https://github.com/Nuiio/fluffos-xiuxian-mud) | 6 | C | 2026-06-07 | 基于 FluffOS 的中文修仙主题 MUD，支持 Telnet 与 Web 客户端 |
| [zblcving9-gif/npc-decision-powered-by-LLM-xiuxian-Ascension](https://github.com/zblcving9-gif/npc-decision-powered-by-LLM-xiuxian-Ascension) | 6 | JavaScript | 2026-03-29 | 上者的 v3：2D 物理沙盒 + AI NPC 对话 + 修仙模拟 |
| [alikatong/songjian-kouxian](https://github.com/alikatong/songjian-kouxian) | 2 | JavaScript | 2026-07-28 | 前后端分离国风文字修仙 RPG（React + Node + PostgreSQL）：修炼/游历/战斗/功法/装备/炼丹炼器/坊市/社交 + 运营管理台，功能面对齐度最高 |
| [moyan-ya/xianxia-idle-game](https://github.com/moyan-ya/xianxia-idle-game) | 2 | JavaScript | 2026-10-01 | 服务端权威 + 种子可复算战斗 + 惰性离线结算 + 逐灵根五行克制；Vue2.6 + Node16 + MySQL |
| [SiYue2024S/xiantu](https://github.com/SiYue2024S/xiantu) | 2 | Vue | 2025-03-12 | 《我的放置仙途》修仙网页文字游戏，PC + H5 自适应 |
| [wangfeng0629/ZTrunk_Server](https://github.com/wangfeng0629/ZTrunk_Server) | 2 | Go | 2019-01-22 | 仙侠世界挂机版服务端（Go） |
| [ztkedou/moyuwang-xiuxian](https://github.com/ztkedou/moyuwang-xiuxian) | 2 | Python | 2026-10-14 | 《摸鱼修仙传》完整可复现源码包：React 单页 + Node/TS 后端，200+ 接口服务端 |
| [lbwwork/wzxxl-game-logic](https://github.com/lbwwork/wzxxl-game-logic) | 1 | Java | 2023-11-08 | 《文字修仙录》游戏逻辑模块（Java），按模块拆分的服务端逻辑 |

### B. 文字修仙 · 前端 / 单机 / 放置类（17）

| 项目 | ★ | 语言 | 最后推送 | 说明 |
| --- | ---: | --- | --- | --- |
| [setube/vue-xiuxiangame](https://github.com/setube/vue-xiuxiangame) | 1792 | JavaScript | 2026-03-02 | 《我的文字修仙全靠刷》前端（Vue），文字修仙刷子玩法的代表实现 |
| [setube/vue-idle-xiuxian](https://github.com/setube/vue-idle-xiuxian) | 293 | Vue | 2026-01-06 | 《我的放置仙途》修仙放置游戏（Vue） |
| [JeasonLoop/react-xiuxian-game](https://github.com/JeasonLoop/react-xiuxian-game) | 168 | TypeScript | 2026-09-28 | 一款文字修仙小游戏（React） |
| [mmdi1/yundingxx-game](https://github.com/mmdi1/yundingxx-game) | 94 | JavaScript | 2020-09-01 | 《云顶修仙》游戏文档：新手引导/游戏概要/攻略/物品类目（数值与文档参考） |
| [setube/yunyin-xiuxian](https://github.com/setube/yunyin-xiuxian) | 54 | TypeScript | 2026-10-16 | 《云隐修仙录》文字修仙放置游戏 |
| [KirisakiAria/sdxxz](https://github.com/KirisakiAria/sdxxz) | 21 | Vue | 2023-03-01 | 《傻屌修仙传》修仙题材网页游戏（Vue） |
| [PentaKill1/ZhiJianXiuXian](https://github.com/PentaKill1/ZhiJianXiuXian) | 16 | C# | 2020-06-27 | 《指尖修仙》文字挂机游戏（C#） |
| [LingTian/tao-kb](https://github.com/LingTian/tao-kb) | 15 | HTML | 2026-07-28 | 道教知识库：AI 驱动的修道典籍知识工程，可作世界观资料源 |
| [SaiYing/cxxy](https://github.com/SaiYing/cxxy) | 14 | HTML | 2018-03-14 | H5 打包的修仙类小游戏 |
| [sshenlian/xiuxian](https://github.com/sshenlian/xiuxian) | 10 | HTML | 2022-01-15 | 修仙秘境 Web 安全靶场（命名撞车，实为安全练习平台，仅作命名参考） |
| [13579123/ImmortalRoadProjects](https://github.com/13579123/ImmortalRoadProjects) | 8 | TypeScript | 2025-12-05 | 一个挂机修仙游戏的源码（TypeScript） |
| [Martinqi826/xiuxian](https://github.com/Martinqi826/xiuxian) | 6 | HTML | 2026-05-12 | 《凡人修仙路》单文件 HTML 修真模拟挂机游戏 |
| [devgo-cn/xianren-xiuxian](https://github.com/devgo-cn/xianren-xiuxian) | 4 | JavaScript | 2026-09-21 | 闲人修仙：HTML5 水墨国风挂机放置，离线收益 |
| [whouare-dot/xiantu](https://github.com/whouare-dot/xiantu) | 4 | JavaScript | 2026-09-13 | 纯前端文字修仙放置游戏：零依赖零构建，二十六重境界 + 轮回转世 |
| [bestop/xiantu](https://github.com/bestop/xiantu) | 2 | TypeScript | 2026-10-15 | 仙途挂机：Next.js 16 + React 19，16 技能/百种妖兽/离线进度/随机词条装备 |
| [pplmx/xian](https://github.com/pplmx/xian) | 0 | TypeScript | 2026-10-16 | 《玄枢录》四界二十一境、派遣历练、后台预解算回合制战斗，离线可玩（Web/PWA/桌面） |
| [ThySummer14/xiuxianlu](https://github.com/ThySummer14/xiuxianlu) | 0 | JavaScript | 2026-10-07 | 《斩妖·修仙录》水墨风修仙放置（纯 Canvas 零素材） |

### C. 群聊机器人修仙插件（QQ / OneBot / NoneBot / AstrBot / Telegram / Discord）（23）

| 项目 | ★ | 语言 | 最后推送 | 说明 |
| --- | ---: | --- | --- | --- |
| [s52047qwas/nonebot_plugin_xiuxian](https://github.com/s52047qwas/nonebot_plugin_xiuxian) | 103 | Python | 2023-11-28 | NoneBot 群内修仙模拟器（星标最高的 QQ 群修仙插件） |
| [QingMuCat/nonebot_plugin_xiuxian_2](https://github.com/QingMuCat/nonebot_plugin_xiuxian_2) | 98 | Python | 2024-10-09 | 基于 NoneBot2 的修仙文字游戏插件（第二代，社区常用） |
| [MyXiaoNan/nonebot_plugin_xiuxian_2_pmv](https://github.com/MyXiaoNan/nonebot_plugin_xiuxian_2_pmv) | 49 | Python | 2026-08-03 | NoneBot2 修仙文字游戏魔改版（图片渲染玩法） |
| [oldPeter616/astrbot_plugin_xiuxian](https://github.com/oldPeter616/astrbot_plugin_xiuxian) | 17 | Python | 2025-09-30 | AstrBot 框架的修仙小游戏插件 |
| [928721935/duobaoZhonghua](https://github.com/928721935/duobaoZhonghua) | 12 | Java | 2021-02-18 | 文字 WAP 武侠游戏《夺宝中华》（Java，聊天/网页端文字玩法） |
| [ganchengcaiyue/nonebot_plugin_simulator_xiuxian](https://github.com/ganchengcaiyue/nonebot_plugin_simulator_xiuxian) | 12 | Python | 2024-11-27 | 基于 NoneBot2 的修仙文字游戏插件（模拟器向） |
| [liyw0205/nonebot_plugin_xiuxian_2_pmv](https://github.com/liyw0205/nonebot_plugin_xiuxian_2_pmv) | 12 | Python | 2026-10-16 | 修仙插件 pmv 分支 |
| [602723113/Grus](https://github.com/602723113/Grus) | 11 | Java | 2023-01-20 | 基于 BukkitAPI 的修仙插件（Minecraft 服务端接入参考） |
| [HungTeen/Immortal](https://github.com/HungTeen/Immortal) | 10 | Java | 2025-06-24 | Minecraft 修仙 mod（Java），跨端题材参考 |
| [DITF16/astrbot_plugin_simple_xiuxian](https://github.com/DITF16/astrbot_plugin_simple_xiuxian) | 4 | Python | 2025-07-10 | AstrBot 简易修仙模拟器插件 |
| [victical/astrbot_plugin_simulation-xiuxian](https://github.com/victical/astrbot_plugin_simulation-xiuxian) | 3 | Python | 2025-10-15 | AstrBot 修仙模拟插件 |
| [0x0077/xiuxian-tg-bot](https://github.com/0x0077/xiuxian-tg-bot) | 2 | Python | 2025-03-30 | Telegram 修仙小游戏机器人（修炼/探索/渡劫） |
| [liyw0205/nonebot_plugin_xiuxian_3](https://github.com/liyw0205/nonebot_plugin_xiuxian_3) | 2 | Python | 2026-10-16 | 从零重写的 NoneBot 修仙插件（含功能规格/架构决策/开发路线文档） |
| [zzk159753/nonebot_plugin_xiuxian_2_pmv_lunar](https://github.com/zzk159753/nonebot_plugin_xiuxian_2_pmv_lunar) | 2 | Python | 2025-04-03 | pmv 魔改版的再魔改版 |
| [Ke-scholar/xiuxian-bot](https://github.com/Ke-scholar/xiuxian-bot) | 1 | - | 2024-03-23 | 修仙文字机器人 |
| [liyw0205/nonebot_plugin_spirit_pet](https://github.com/liyw0205/nonebot_plugin_spirit_pet) | 1 | Python | 2026-10-16 | 灵宠修仙文字游戏，支持 OneBot V11 与 QQ 官方机器人 Markdown/按钮/蓝字 |
| [TsoiTZF/astrbot_plugin_xiuxian_wenyou](https://github.com/TsoiTZF/astrbot_plugin_xiuxian_wenyou) | 1 | Python | 2026-08-12 | 《修仙纪元》AstrBot 修仙文字游戏 |
| [yowasa/GO_XIUXIAN_QQ_CHANNEL_BOT](https://github.com/yowasa/GO_XIUXIAN_QQ_CHANNEL_BOT) | 1 | Go | 2023-01-12 | QQ 频道修仙机器人（Go）——频道场景适配参考 |
| [gugeniubu-hub/qq-xiuxian-bot](https://github.com/gugeniubu-hub/qq-xiuxian-bot) | 0 | Python | 2026-06-19 | QQ 群文字修仙游戏机器人骨架 |
| [Lan-0v0/astrbot_plugin_mud_lineage1](https://github.com/Lan-0v0/astrbot_plugin_mud_lineage1) | 0 | - | 2026-08-12 | AstrBot 天堂1 MUD 玩法插件（以文字还原经典 MMORPG） |
| [leo7758521/xiaoyao](https://github.com/leo7758521/xiaoyao) | 0 | - | 2025-07-28 | 逍遥修仙 QQ 机器人 |
| [w3w4w/nbxiuxian](https://github.com/w3w4w/nbxiuxian) | 0 | Python | 2026-01-31 | NoneBot 修仙插件（早期实验版） |
| [win-syswow64/xiuxian.bot](https://github.com/win-syswow64/xiuxian.bot) | 0 | TypeScript | 2024-08-09 | 修仙机器人（TypeScript） |

### D. 通用 MUD / 文字多人游戏引擎与服务端框架（架构可借鉴）（38）

| 项目 | ★ | 语言 | 最后推送 | 说明 |
| --- | ---: | --- | --- | --- |
| [evennia/evennia](https://github.com/evennia/evennia) | 2111 | Python | 2026-10-09 | Python MUD/MUX/MUSH 开发系统：最成熟的文本多人游戏框架，插件化与命令体系可直接借鉴 |
| [RanvierMUD/ranviermud](https://github.com/RanvierMUD/ranviermud) | 858 | JavaScript | 2023-07-11 | Node.js MUD 引擎：数据驱动的房间/区域/技能配置 |
| [oestrich/ex_venture](https://github.com/oestrich/ex_venture) | 670 | Elixir | 2023-02-14 | Elixir 文本 MMORPG 引擎（Phoenix + WebSocket 高并发架构） |
| [fluffos/fluffos](https://github.com/fluffos/fluffos) | 463 | C++ | 2026-10-16 | 在维护的 LPMUD 驱动（LPC 解释器，MudOS 分支）——中文 MUD 生态的事实标准驱动 |
| [Frimkron/mud-pi](https://github.com/Frimkron/mud-pi) | 370 | Python | 2021-07-04 | 极简 Python MUD 服务端（教学向），适合快速理解 MUD 最小结构 |
| [GoMudEngine/GoMud](https://github.com/GoMudEngine/GoMud) | 237 | Go | 2026-10-05 | Go 语言 MUD 服务端引擎 |
| [bozimmerman/CoffeeMud](https://github.com/bozimmerman/CoffeeMud) | 235 | Java | 2026-10-15 | Java 全能 MUD 服务端：MSP/MXP/GMCP/OLC + 内建 Web 与邮件服务，Java 同类项目最完整的参照 |
| [Seifert69/DikuMUD3](https://github.com/Seifert69/DikuMUD3) | 227 | C++ | 2026-10-16 | DikuMUD III：HTML + WebSocket 接入的现代 Diku 系服务端 |
| [timbran-project/moor](https://github.com/timbran-project/moor) | 219 | Rust | 2026-10-14 | Rust 实现、兼容 LambdaMOO 的可编程在线世界系统 |
| [oestrich/kalevala](https://github.com/oestrich/kalevala) | 197 | Elixir | 2025-05-04 | Elixir 世界构建工具箱（与 ex_venture 同作者） |
| [jonradoff/lofp](https://github.com/jonradoff/lofp) | 181 | HTML | 2026-05-17 | 90 年代商业 MUD《Legends of Future Past》开源重现（Go） |
| [MoreOutput/RockMUD](https://github.com/MoreOutput/RockMUD) | 155 | JavaScript | 2023-03-04 | Node + WebSocket MUD 服务端，浏览器直连范式 |
| [ArchaicQuest/ArchaicQuest-II](https://github.com/ArchaicQuest/ArchaicQuest-II) | 151 | C# | 2023-04-20 | C# 多人文字 RPG（浏览器可玩），含客户端与服务端 |
| [pennmush/pennmush](https://github.com/pennmush/pennmush) | 129 | C | 2026-08-19 | 1992 年至今仍在维护的 C 语言 MUSH 服务端 |
| [bbuck/dragon-mud](https://github.com/bbuck/dragon-mud) | 122 | Go | 2026-10-07 | Go + Lua 脚本化的文字游戏引擎 |
| [duysqubix/MuOxi](https://github.com/duysqubix/MuOxi) | 101 | Rust | 2026-05-16 | Rust 现代 MUD 引擎 |
| [EmpireMUD/EmpireMUD-2.0-Beta](https://github.com/EmpireMUD/EmpireMUD-2.0-Beta) | 100 | C | 2026-10-15 | 带持久化世界地图的 MUD 服务端（CircleMUD/DikuMUD 衍生） |
| [doughsay/room.js](https://github.com/doughsay/room.js) | 88 | JavaScript | 2020-05-25 | Node.js MOO 服务端 |
| [jasonstolaruk/CurryMUD](https://github.com/jasonstolaruk/CurryMUD) | 78 | Haskell | 2023-12-12 | Haskell 实现的 MUD 服务端（函数式架构参考） |
| [wowpin/dumserver](https://github.com/wowpin/dumserver) | 71 | JavaScript | 2023-09-25 | 现代 MU* 引擎（重写版，持续维护） |
| [shmup/miniboa](https://github.com/shmup/miniboa) | 66 | Python | 2026-04-13 | 异步单线程 poll 式 Telnet 服务端（Python），协议层极简范本 |
| [TalesMUD/talesmud](https://github.com/TalesMUD/talesmud) | 66 | Go | 2026-10-16 | Golang MUD 开发框架/平台 |
| [8720826/emud](https://github.com/8720826/emud) | 58 | C# | 2026-03-16 | netcore + SignalR + Vue 的 MUD 游戏框架（.NET 全栈） |
| [ZabinX/DuskRPG](https://github.com/ZabinX/DuskRPG) | 42 | Java | 2025-12-11 | Java 图形化 MUD（玩法同文字 MUD，界面图形化） |
| [jnharton/mud-server](https://github.com/jnharton/mud-server) | 41 | Java | 2026-05-12 | Java MUD 服务端：含基础 OLC 与频道聊天系统 |
| [malkamius/CrimsonStainedLands](https://github.com/malkamius/CrimsonStainedLands) | 28 | C# | 2026-08-24 | 基于 ROM 的 C# MUD 服务端 |
| [boredream/FightWithoutEnd](https://github.com/boredream/FightWithoutEnd) | 24 | Java | 2013-03-27 | Android 文字类游戏（类似「无尽的进度条」） |
| [thezboe/Wasteland-MUD](https://github.com/thezboe/Wasteland-MUD) | 21 | Java | 2011-01-25 | Java 命令行多人地牢游戏 |
| [ProjectMoon/ringmud](https://github.com/ProjectMoon/ringmud) | 20 | Java | 2020-08-17 | Java MUD 服务端与引擎 |
| [scionaltera/agony-engine](https://github.com/scionaltera/agony-engine) | 18 | Java | 2019-03-17 | 现代 Web 化、程序化生成的 MUD 引擎（Java） |
| [roomsmith-games/NeoMud](https://github.com/roomsmith-games/NeoMud) | 13 | Kotlin | 2026-07-18 | Kotlin Multiplatform + Ktor WebSocket 服务端 + Compose 客户端 |
| [SunnyYuer/Story-ConAndroid](https://github.com/SunnyYuer/Story-ConAndroid) | 8 | C++ | 2016-09-14 | 《Story 传奇》文字 MUD 安卓版 |
| [nijakow/four](https://github.com/nijakow/four) | 7 | Java | 2022-09-25 | Java 实现的 MUD 引擎 |
| [bhavanki/grounds](https://github.com/bhavanki/grounds) | 6 | Java | 2023-07-10 | Java 可扩展 MUSH/MUD 服务端 |
| [landriesnidis/MartialArtsCirclesRPG](https://github.com/landriesnidis/MartialArtsCirclesRPG) | 6 | Java | 2019-05-15 | 文字武侠 RPG（Java），基于层级文本菜单框架开发 |
| [landriesnidis/PyramidTextMenu-java](https://github.com/landriesnidis/PyramidTextMenu-java) | 6 | Java | 2022-06-17 | Java 层级文本菜单框架，适用于文字冒险游戏/问答机器人 |
| [medehn/coffeemud](https://github.com/medehn/coffeemud) | 5 | Java | 2019-05-20 | Java 小型 MUD，同时提供 WebSocket 与 Telnet 接入 |
| [HeavyFlagship/mud-game-engine](https://github.com/HeavyFlagship/mud-game-engine) | 0 | JavaScript | 2026-10-02 | 中文「网络文字游戏引擎」（JS，附科幻题材示例） |

### E. MUD 客户端 / 网关 / 工具（10）

| 项目 | ★ | 语言 | 最后推送 | 说明 |
| --- | ---: | --- | --- | --- |
| [Mudlet/Mudlet](https://github.com/Mudlet/Mudlet) | 916 | C++ | 2026-10-16 | 跨平台开源 MUD 客户端（Lua 脚本），可参考其客户端协议与脚本能力 |
| [Blightmud/Blightmud](https://github.com/Blightmud/Blightmud) | 278 | Rust | 2026-10-15 | Rust 终端 MUD 客户端 |
| [scandum/tintin](https://github.com/scandum/tintin) | 263 | C | 2026-09-21 | TinTin++ 控制台 MUD 客户端（可扩展、带地图） |
| [fiendish/aardwolfclientpackage](https://github.com/fiendish/aardwolfclientpackage) | 103 | Lua | 2026-10-07 | Aardwolf MUSHclient 客户端整合包 |
| [ingwarsw/tinyfugue](https://github.com/ingwarsw/tinyfugue) | 90 | C | 2026-10-11 | TinyFugue 重生版 MUD 客户端 |
| [mudchina/webtelnet](https://github.com/mudchina/webtelnet) | 86 | JavaScript | 2016-03-14 | WebSocket → Telnet 网关：让浏览器直连 MUD 服务端（多端接入思路可借鉴） |
| [mapleobserver/wsmud-script](https://github.com/mapleobserver/wsmud-script) | 81 | JavaScript | 2026-10-01 | 《武神传说》文字 MUD 自动化脚本（玩家玩法视角） |
| [svof/svof](https://github.com/svof/svof) | 55 | Lua | 2026-10-13 | Achaea MUD 的 AI 辅助系统（战斗/治疗自动化） |
| [Neop/mudmap2](https://github.com/Neop/mudmap2) | 52 | Java | 2024-08-28 | Java 文字游戏地图工具（MUD/MUSH/文字冒险通用） |
| [RahjIII/lociterm](https://github.com/RahjIII/lociterm) | 19 | C | 2026-02-03 | PWA 形式的 MUD Telnet 客户端 |

### F. AI 文字游戏与叙事引擎（7）

| 项目 | ★ | 语言 | 最后推送 | 说明 |
| --- | ---: | --- | --- | --- |
| [flizzywine/dsh-tavern](https://github.com/flizzywine/dsh-tavern) | 1008 | JavaScript | 2026-10-16 | 基于 DSH 的 Agent 酒馆：类酒馆文字游戏 Agent，兼容 SillyTavern 生态 |
| [YunYouJun/advjs](https://github.com/YunYouJun/advjs) | 327 | TypeScript | 2026-10-16 | 面向前端的文字冒险（AVG）游戏引擎 |
| [CassiopeiaCode/TenCyclesofFate](https://github.com/CassiopeiaCode/TenCyclesofFate) | 256 | Python | 2026-05-29 | 《浮生十梦》Web 沉浸式文字冒险，AI 动态生成人生故事 |
| [darvin/Holodeck](https://github.com/darvin/Holodeck) | 46 | Jupyter Notebook | 2023-04-18 | MUDGPT：AI 生成虚拟世界的多人游戏实验 |
| [rockbenben/thousand-lives](https://github.com/rockbenben/thousand-lives) | 9 | TypeScript | 2026-10-13 | 《千世书》AI 文字人生模拟器（含修仙剧本），纯前端 |
| [hanli1999/xiuxian](https://github.com/hanli1999/xiuxian) | 1 | Python | 2026-09-21 | 以《凡人修仙传》730 万字全文为知识库的类比检索引擎 |
| [2528078765/RAG-Bot](https://github.com/2528078765/RAG-Bot) | 0 | Python | 2026-06-30 | 《凡人修仙传》RAG 问答机器人（世界观问答/检索增强参考） |

### G. 中文文字 MUD 经典服务端源码（历史项目）（16）

| 项目 | ★ | 语言 | 最后推送 | 说明 |
| --- | ---: | --- | --- | --- |
| [mudchina/es2-utf8](https://github.com/mudchina/es2-utf8) | 33 | JavaScript | 2017-01-10 | 东方故事 II MudLib（Annihilator 版，含地图，UTF-8）——中文 MUD 鼻祖的 UTF-8 分支 |
| [mudchina/xkx100](https://github.com/mudchina/xkx100) | 31 | C | 2016-02-21 | 侠客行 100 服务端（C，GBK） |
| [mudchina/xkx2001](https://github.com/mudchina/xkx2001) | 30 | C | 2016-02-20 | 侠客行 2001 服务端（C，GBK） |
| [mudchina/xyj2006](https://github.com/mudchina/xyj2006) | 25 | C | 2016-02-21 | 西游记 2006 服务端（C，GBK） |
| [mudchina/es2](https://github.com/mudchina/es2) | 24 | C | 2016-02-21 | 东方故事 II（ES2）服务端（C，GBK）——中文 MUD 鼻祖 |
| [mudchina/xyj45](https://github.com/mudchina/xyj45) | 23 | C | 2016-02-21 | 西游记 4.5 服务端（C，GBK） |
| [mudchina/jy](https://github.com/mudchina/jy) | 21 | C | 2016-02-21 | 金庸群侠传服务端（C，GBK） |
| [mudchina/mhxy2002](https://github.com/mudchina/mhxy2002) | 16 | C | 2016-02-20 | 梦幻西游 2002 服务端（C，GBK） |
| [mudchina/fy4](https://github.com/mudchina/fy4) | 13 | C | 2016-02-21 | 风云 4 服务端（C，GBK） |
| [flyinglb/jyqx](https://github.com/flyinglb/jyqx) | 9 | C | 2019-12-06 | 金庸群侠传文字 MUD 游戏 |
| [mudchina/nitan3](https://github.com/mudchina/nitan3) | 9 | C | 2016-02-21 | 泥潭 3 服务端（C，GBK） |
| [mudchina/sjsh](https://github.com/mudchina/sjsh) | 7 | C | 2016-02-21 | 三界神话服务端（C，GBK 简体） |
| [mudchina/fy3](https://github.com/mudchina/fy3) | 6 | C | 2016-02-21 | 风云 3 服务端（C，GBK） |
| [mudchina/fy2](https://github.com/mudchina/fy2) | 4 | C | 2016-02-21 | 风云 2 服务端（C，GBK） |
| [mudchina/fluffos](https://github.com/mudchina/fluffos) | 3 | C++ | 2016-02-21 | MudChina 维护的 FluffOS 分支（LPC 驱动） |
| [mudchina/mudclient](https://github.com/mudchina/mudclient) | 0 | - | 2016-02-21 | MudChina 收录的常用 MUD 客户端集合 |

### H. 资料、命名器与周边资源（9）

| 项目 | ★ | 语言 | 最后推送 | 说明 |
| --- | ---: | --- | --- | --- |
| [hythl0day/random_chinese_fantasy_names](https://github.com/hythl0day/random_chinese_fantasy_names) | 237 | JavaScript | 2024-07-25 | NPM 包：随机生成人名/法号/地名/门派/法宝名——修仙游戏命名可直接复用 |
| [yuanxiaosc/fan-ren-xiu-xian-zhuan](https://github.com/yuanxiaosc/fan-ren-xiu-xian-zhuan) | 235 | Python | 2021-12-27 | 《凡人修仙传》资源汇总（设定资料收集参考） |
| [wap-game/xiyou](https://github.com/wap-game/xiyou) | 187 | PHP | 2026-09-07 | WAP 文字游戏《幻想西游》PHP 源码（早期文字网游形态） |
| [mudcoders/awesome-mud](https://github.com/mudcoders/awesome-mud) | 143 | - | 2026-04-07 | 文字游戏开发者的资源总表（引擎/工具/教程） |
| [Elixir-journey/dragons-tome](https://github.com/Elixir-journey/dragons-tome) | 93 | - | 2024-04-24 | 用 Elixir 构建分布式文字 MUD 的实战教程书 |
| [e5sub/Text-Legend](https://github.com/e5sub/Text-Legend) | 87 | JavaScript | 2026-09-14 | 文字游戏/文字网游/开源网游/收菜游戏（中文文字网游实现） |
| [maldorne/awesome-muds](https://github.com/maldorne/awesome-muds) | 81 | - | 2026-08-04 | MUD 开发资源、工具与客户端精选列表 |
| [Lizhenghe-Chen/fanren-wiki](https://github.com/Lizhenghe-Chen/fanren-wiki) | 38 | HTML | 2026-10-16 | 《凡人修仙传》资料图谱：人物/灵兽/丹药/法宝/境界演进 |
| [ZergSu01/xuanjian](https://github.com/ZergSu01/xuanjian) | 2 | HTML | 2026-09-21 | 玄幻名词生成器：17 大类/8 种风格/关键词融合 |

## 四、检索方法与原始数据

1. 关键词（GitHub Search API，`sort=stars&order=desc&per_page=50`）：
   - 轮次 1（泛搜）：`修仙`、`xiuxian`、`修真`、`仙侠`、`文字修仙`、`文字游戏 mud`、`cultivation game`、`xianxia`、`topic:mud`
   - 轮次 2（精确）：`xiuxian in:name,description`、`修仙 in:name,description`、`topic:xiuxian`、`topic:cultivation-game`、`mud in:name,description language:Java`、`文字修仙 in:name,description`、`文字游戏 in:name,description`、`xianxia in:name,description`、`mud server in:name,description`
   - 轮次 3（定向）：`修仙 in:name`、`仙侠 in:name,description`、`nonebot 修仙`、`修仙 机器人`、`cultivation in:name,description`、`mud in:name language:Java`、`wuxia in:name,description`、`放置修仙 in:name,description`、`text-based mmorpg in:name,description`
   - 轮次 4（服务端向）：`文字网游`、`文字mud`、`mud 服务端`、`修仙 服务端`、`挂机 修仙`、`群聊 修仙 游戏`、`仙侠 服务端`、`文字冒险 服务端`、`文字游戏 in:name,description language:Java`
   - 补充：`mudchina` 组织仓库全量（中文文字 MUD 经典服务端）
2. 去噪：剔除 README 全文命中导致的无关项目（如 C++ 网络库 muduo、MudBlazor 组件库、农业 `cultivation` 类、政治类仓库等）。
3. 原始数据与脚本：
   - `raw-search.json` / `raw-search2.json` / `raw-search3.json` / `raw-search4.json`：各轮检索原始结果
   - `mudchina-org.json`：mudchina 组织仓库
   - `merged.json`：四轮去重合并（全量）
   - `candidates.json`：关键词过滤后的候选集
   - `curation.json`：人工精选条目（分类 + 中文说明）
   - `gen-report.mjs`：本文件生成脚本（可重跑刷新 Star 数）

4. 说明：Star / Fork / 推送时间为 2026-10-16 抓取值；重跑 `node docs/similar-projects/gen-report.mjs` 可刷新。
