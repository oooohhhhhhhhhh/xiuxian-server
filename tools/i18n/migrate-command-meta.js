/**
 * 给每个命令注册处补 setI18nKeys("cmd.<name>.desc", "cmd.<name>.usage")。
 *
 * 为什么值得做：指令的 description / usage 是玩家看得见的——
 * 它们经 ctx.reply(getUsage()) 直达用户、也出现在 /help 列表和
 * WebSocket 的命令清单里。但它们过去只是 super(...) 的第二、第三个参数字面量，
 * 于是成了 i18n 的盲区。
 *
 * 处理规则：
 *   1. 只认 super(new String[]{...}, "描述", "用法", ...) 这个形状，
 *      且第二、第三参必须是纯中文字面量；
 *   2. 把 names[0]（主命令名，所有人都能打的英文/中文名）做 key 前缀；
 *   3. 若子类里已经有 setI18nKeys(...) 就跳过，不重复注入；
 *   4. 词条写进 zh_cn.json，key 已存在则跳过——绝不覆盖人工译文。
 *
 * 注意只迁 description/usage，不碰 names：names 是玩家输入用的别名，
 * 翻译了玩家反而不知道打什么。中英文别名本来就该并存于 names 数组
 * （如 {"阵法", "formation"}），那是"命令匹配"而不是"消息文案"。
 *
 * 用法: node tools/i18n/migrate-command-meta.js [--write]
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const WRITE = process.argv.includes('--write');
const CJK = /[\u4e00-\u9fff]/;

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, e.name);
    if (e.isDirectory()) walk(full);
    else if (e.name.endsWith('.java')) files.push(full);
  }
})(path.join(ROOT, 'src/main/java'));

const newKeys = {};

/** 用一个可读的 ASCII 前缀：优先 names[0] 的拼音没有，就用类名 snake。 */
function keyPrefix(className, names) {
  // names[0] 可能是中文（"宗门"）；中文不能进 key，退回类名
  const first = (names[0] || '').replace(/[^A-Za-z0-9_]/g, '');
  const fromName = first && /^[A-Za-z]/.test(first) ? first.toLowerCase() : '';
  let base = className.replace(/(Command|SubCommand)$/, '');
  base = base.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();
  return 'cmd.' + (fromName || base);
}

for (const file of files) {
  let src = fs.readFileSync(file, 'utf8');
  const original = src;

  // 类名
  const clsMatch = src.match(/public\s+class\s+(\w+)/);
  if (!clsMatch) continue;
  const className = clsMatch[1];

  // 已经有 setI18nKeys 就不再注入（避免重复/覆盖）
  if (/setI18nKeys\s*\(/.test(src)) continue;

  // super(new String[]{...}, "描述", "用法", ...)
  // 允许跨行：把整个 super(...) 用括号配平抓出来
  const re = /super\(\s*new\s+String\[\]\s*\{([^}]*)\}\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"/g;
  let m;
  let injected = null;
  while ((m = re.exec(src)) !== null) {
    const names = m[1].split(',').map(s => s.trim().replace(/^"|"$/g, '')).filter(Boolean);
    const desc = m[2], usage = m[3];
    if (!CJK.test(desc) && !CJK.test(usage)) continue;

    const prefix = keyPrefix(className, names);
    const descKey = prefix + '.desc';
    const usageKey = prefix + '.usage';

    // 第二/第三参原地换成 I18n.t 调用，保持它们是"构建时拿到的原文"即可，
    // 实际渲染走 getDescription()/getUsage() 的关键字解析；
    // 所以这里不动参数字面量，只在构造函数体末尾注入 setI18nKeys(...)。
    // 只给"真的是中文"的那一项注入 key。
    // 用法常常是 "/绑定" 这种纯命令名，本来就不该翻译——
    // 先前无条件给两项都注入，导致 cmd.cleardb_all.usage 这类
    // 指向不存在词条的裸 key（渲染时虽会回落，但测试会抓到）。
    const hasDesc = CJK.test(desc);
    const hasUsage = CJK.test(usage);
    if (!hasDesc && !hasUsage) continue;

    const descArg = hasDesc ? '"' + descKey + '"' : 'null';
    const usageArg = hasUsage ? '"' + usageKey + '"' : 'null';

    injected = { descKey, usageKey, desc, usage, descArg, usageArg };
    if (hasDesc) newKeys[descKey] = desc;
    if (hasUsage) newKeys[usageKey] = usage;
    break;
  }
  if (!injected) continue;

  // 必须插在 super(...) 调用**之后**：Java 要求 super 是构造函数体第一条语句，
  // 先前插在构造函数的第一个 '{' 后面，于是 setI18nKeys 跑到 super 前面，
  // 编译期直接报"无法在构造器调用之前引用"。
  // 用括号配平找到本次匹配到的那个 super(...) 的收尾 ')'。
  const ctor = src.match(/public\s+\w+\s*\(\s*\)\s*\{/);
  if (!ctor) continue;

  // 从 super( 起点开始配平
  const superStart = m.index;
  let depth = 0, inStr = false, closeIdx = -1;
  for (let i = superStart; i < src.length; i++) {
    const c = src[i];
    if (inStr) { if (c === '\\') { i++; continue; } if (c === '"') inStr = false; continue; }
    if (c === '"') { inStr = true; continue; }
    if (c === '(') depth++;
    else if (c === ')') { depth--; if (depth === 0) { closeIdx = i; break; } }
  }
  if (closeIdx < 0) continue;

  // 插在 super 语句结束之后。注意 super(...) 后面本来可能带 ';'，
  // 直接 closeIdx+1 会把新语句塞到 ')' 和 ';' 中间，既吞掉分号又留下双分号。
  let at = closeIdx + 1;
  while (at < src.length && /[\s;]/.test(src[at])) at++;
  src = src.slice(0, at)
    + '\n        setI18nKeys(' + injected.descArg + ', ' + injected.usageArg + ');\n    '
    + src.slice(at);

  if (WRITE) fs.writeFileSync(file, src, 'utf8');
}

console.log('待注入命令: ' + Object.keys(newKeys).filter(k => k.endsWith('.desc')).length
  + '；新增词条 ' + Object.keys(newKeys).length);
Object.entries(newKeys).slice(0, 6).forEach(([k, v]) => console.log('  ' + k + ' = ' + v.slice(0, 40)));

if (!WRITE) { console.log('（演练，未写盘；加 --write 生效）'); process.exit(0); }

const zhPath = path.join(ROOT, 'src/main/java/data/mtxgdn/lang/zh_cn.json');
const zh = JSON.parse(fs.readFileSync(zhPath, 'utf8'));
let added = 0;
for (const [k, v] of Object.entries(newKeys)) { if (k in zh) continue; zh[k] = v; added++; }
const sorted = {};
for (const k of Object.keys(zh).sort()) sorted[k] = zh[k];
fs.writeFileSync(zhPath, JSON.stringify(sorted, null, 2) + '\n', 'utf8');
console.log('zh_cn 新增 ' + added + ' 条，总计 ' + Object.keys(zh).length);
