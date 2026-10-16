// Scratch script: render the final curated report (star-ranked) from collected data.
// Run: node docs/similar-projects/gen-report.mjs
import fs from 'node:fs';
import path from 'node:path';

const DIR = path.join(process.cwd(), 'docs', 'similar-projects');
const OUT = path.join(DIR, 'GitHub-同类项目清单.md');

const readJson = (f) => JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8'));

// ---- data sources -----------------------------------------------------------
const lookup = new Map();
const add = (it, source) => {
  const key = it.full_name.toLowerCase();
  const prev = lookup.get(key);
  if (!prev) lookup.set(key, { ...it, sources: [source] });
  else if (!prev.sources.includes(source)) prev.sources.push(source);
};

for (const f of ['raw-search.json', 'raw-search2.json', 'raw-search3.json', 'raw-search4.json']) {
  const raw = readJson(f);
  for (const items of Object.values(raw.results)) for (const it of items) add(it, f);
}
for (const it of readJson('mudchina-org.json').items) add(it, 'mudchina-org.json');

const curation = readJson('curation.json');
const CATS = curation.categories;

// ---- resolve entries -------------------------------------------------------
const entries = [];
const missing = [];
for (const e of curation.entries) {
  const it = lookup.get(e.repo.toLowerCase());
  if (!it) {
    missing.push(e.repo);
    entries.push({ repo: e.repo, cat: e.cat, note: e.note, stars: 0, forks: 0, language: '-', pushed_at: null, html_url: `https://github.com/${e.repo}` });
    continue;
  }
  entries.push({ repo: it.full_name, cat: e.cat, note: e.note, stars: it.stars, forks: it.forks, language: it.language || '-', pushed_at: it.pushed_at, html_url: it.html_url });
}

entries.sort((a, b) => b.stars - a.stars || a.repo.localeCompare(b.repo));
if (missing.length) console.log('MISSING (rendered with 0 stars):\n  ' + missing.join('\n  '));

const closest = [
  'alikatong/songjian-kouxian',
  'moyan-ya/xianxia-idle-game',
  'ztkedou/moyuwang-xiuxian',
  'setube/vue-XiuXianGameServer',
  'setube/vue-xiuxiangame',
  'oiuv/mud',
  'Nuiio/fluffos-xiuxian-mud',
  'iwooji77/fanren',
  'GenKnot/MischicatBot',
  'bozimmerman/CoffeeMud',
].map((n) => entries.find((e) => e.repo.toLowerCase() === n.toLowerCase())).filter(Boolean);

const closestWhy = {
  'alikatong/songjian-kouxian': '功能面几乎一一对应：修炼/游历/战斗/功法/装备/炼丹炼器/坊市/社交 + 运营管理台，前后端分离',
  'moyan-ya/xianxia-idle-game': '同为「服务端权威 + 离线结算」的文字修仙，且强调战斗可复算（防作弊）与灵根克制',
  'ztkedou/moyuwang-xiuxian': '同为带完整服务端（200+ 接口）的浏览器挂机文字修仙，可直接对照接口设计',
  'setube/vue-XiuXianGameServer': '《我的文字修仙全靠刷》后端，与前端仓库配套，是本类目里最经典的「前后端成对」实现',
  'setube/vue-xiuxiangame': '同类目最高 star 的文字修仙前端，玩法（刷怪/境界/资源循环）高度接近',
  'oiuv/mud': '中文文字 MUD 服务端：LPC/FluffOS 路线，与本项目「纯文字指令驱动世界」的形态一致',
  'Nuiio/fluffos-xiuxian-mud': '同样是「中文 + 修仙 + 文字 MUD + Web 客户端接入」的组合',
  'iwooji77/fanren': '群聊场景长线文字修仙（灵根/宗门/洞府/交易/斗法/副本/异步结算），与本项目的 QQ 通道形态一致',
  'GenKnot/MischicatBot': '机器人端修仙 RPG + Web 管理面板 + SQLite，多端接入思路与本项目适配器体系相近',
  'bozimmerman/CoffeeMud': 'Java 服务端同类里功能最全的老牌实现（OLC + 内建 Web/邮件），Java 技术栈可直接参考',
};

const fmtDate = (d) => (d ? d.slice(0, 10) : '—');
const esc = (s) => (s || '').replace(/\|/g, '\\|');

let md = '';
md += '# GitHub 同类项目清单（文字修仙 / 文字 MUD 服务端）\n\n';
md += `> 对象项目：**修仙世界 · Xiuxian World**（Java 23 多人在线文字修仙 MUD 服务端，REST / WebSocket / QQ(OneBot+官方机器人) / Minecraft 多端接入）\n>\n`;
md += `> 检索方式：GitHub Search API（\`sort=stars\`，4 轮共 36 个关键词查询 + mudchina 组织仓库全量）\n>\n`;
md += `> 检索时间：2026-10-16　|　去重样本：${lookup.size} 个仓库　|　精选收录：**${entries.length}** 个\n\n`;

md += '## 分类说明\n\n';
md += '| 代号 | 分类 |\n| --- | --- |\n';
for (const [k, v] of Object.entries(CATS)) md += `| ${k} | ${v} |\n`;
md += '\n';

md += '## 一、与本项目最接近的 10 个\n\n';
md += '| 项目 | ★ | 语言 | 为什么最像 |\n| --- | ---: | --- | --- |\n';
for (const e of closest) {
  md += `| [${e.repo}](${e.html_url}) | ${e.stars} | ${e.language} | ${esc(closestWhy[e.repo] || e.note)} |\n`;
}
md += '\n';

md += `## 二、总表（按 Star 降序，共 ${entries.length} 个）\n\n`;
md += '| # | 项目 | ★ | Fork | 语言 | 分类 | 最后推送 | 说明 |\n';
md += '| ---: | --- | ---: | ---: | --- | :---: | --- | --- |\n';
entries.forEach((e, i) => {
  md += `| ${i + 1} | [${e.repo}](${e.html_url}) | **${e.stars}** | ${e.forks} | ${e.language} | ${e.cat} | ${fmtDate(e.pushed_at)} | ${esc(e.note)} |\n`;
});
md += '\n';

md += '## 三、分类清单\n\n';
for (const [k, v] of Object.entries(CATS)) {
  const list = entries.filter((e) => e.cat === k);
  if (!list.length) continue;
  md += `### ${k}. ${v}（${list.length}）\n\n`;
  md += '| 项目 | ★ | 语言 | 最后推送 | 说明 |\n| --- | ---: | --- | --- | --- |\n';
  for (const e of list) {
    md += `| [${e.repo}](${e.html_url}) | ${e.stars} | ${e.language} | ${fmtDate(e.pushed_at)} | ${esc(e.note)} |\n`;
  }
  md += '\n';
}

md += '## 四、检索方法与原始数据\n\n';
md += '1. 关键词（GitHub Search API，`sort=stars&order=desc&per_page=50`）：\n';
md += '   - 轮次 1（泛搜）：`修仙`、`xiuxian`、`修真`、`仙侠`、`文字修仙`、`文字游戏 mud`、`cultivation game`、`xianxia`、`topic:mud`\n';
md += '   - 轮次 2（精确）：`xiuxian in:name,description`、`修仙 in:name,description`、`topic:xiuxian`、`topic:cultivation-game`、`mud in:name,description language:Java`、`文字修仙 in:name,description`、`文字游戏 in:name,description`、`xianxia in:name,description`、`mud server in:name,description`\n';
md += '   - 轮次 3（定向）：`修仙 in:name`、`仙侠 in:name,description`、`nonebot 修仙`、`修仙 机器人`、`cultivation in:name,description`、`mud in:name language:Java`、`wuxia in:name,description`、`放置修仙 in:name,description`、`text-based mmorpg in:name,description`\n';
md += '   - 轮次 4（服务端向）：`文字网游`、`文字mud`、`mud 服务端`、`修仙 服务端`、`挂机 修仙`、`群聊 修仙 游戏`、`仙侠 服务端`、`文字冒险 服务端`、`文字游戏 in:name,description language:Java`\n';
md += '   - 补充：`mudchina` 组织仓库全量（中文文字 MUD 经典服务端）\n';
md += '2. 去噪：剔除 README 全文命中导致的无关项目（如 C++ 网络库 muduo、MudBlazor 组件库、农业 `cultivation` 类、政治类仓库等）。\n';
md += '3. 原始数据与脚本：\n';
md += '   - `raw-search.json` / `raw-search2.json` / `raw-search3.json` / `raw-search4.json`：各轮检索原始结果\n';
md += '   - `mudchina-org.json`：mudchina 组织仓库\n';
md += '   - `merged.json`：四轮去重合并（全量）\n';
md += '   - `candidates.json`：关键词过滤后的候选集\n';
md += '   - `curation.json`：人工精选条目（分类 + 中文说明）\n';
md += '   - `gen-report.mjs`：本文件生成脚本（可重跑刷新 Star 数）\n\n';
md += '4. 说明：Star / Fork / 推送时间为 2026-10-16 抓取值；重跑 `node docs/similar-projects/gen-report.mjs` 可刷新。\n';

fs.writeFileSync(OUT, md, 'utf8');
console.log(`WROTE ${OUT}\nentries: ${entries.length}, categories: ${Object.keys(CATS).length}`);
