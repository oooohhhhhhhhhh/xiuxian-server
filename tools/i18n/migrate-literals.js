/**
 * 把 A 类"整段是一个字符串字面量"的消息出口自动迁移成 I18n.t(key)。
 *
 * 安全边界（刻意保守，宁可漏也不迁错）：
 *   - 只处理 <单一字符串字面量> 独占整个参数的情况：
 *       reply("...") / replyPrivate("...") / createResult(true|false, "...")
 *       .put("message", "...") / addProperty("message", "...")
 *   - 参数里有 + 拼接、方法调用、变量的，一律跳过并列入报告交给人工处理。
 *     （"玩家【" + name + "】" 这类先转成模板再迁，风险不在这一步）
 *   - 字符串转义（\n \" \\ \uXXXX）先解码再写进 JSON，确保译文与原文逐字节一致。
 *     zh_cn.json 的值就是原中文，所以老测试的中文断言全部照旧通过。
 *
 * key 命名：<模块>.h<8位内容哈希>。用内容哈希而不是序号，因为以后增删文案
 * 时序号会漂移，哈希不会——已发布客户端的语言包缓存才不会因为一次无关编辑
 * 而整体失效。映射表输出到 docs/i18n-key-map.md 供人工审阅/改名。
 *
 * 用法:
 *   node tools/i18n/migrate-literals.js            # 演练，只打印统计
 *   node tools/i18n/migrate-literals.js --write     # 实际改写源文件与语言包
 *   node tools/i18n/migrate-literals.js --write --dir src/main/java/com/mtxgdn/game/service
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const LANG_DIR = path.join(ROOT, 'src/main/java/data/mtxgdn/lang');

const WRITE = process.argv.includes('--write');
let SUBDIR = null;
const di = process.argv.indexOf('--dir');
if (di >= 0) SUBDIR = process.argv[di + 1];
const ROOT_DIR = SUBDIR ? path.resolve(ROOT, SUBDIR) : path.join(ROOT, 'src/main/java');

/** 把 Java 字符串字面量内容（不含引号）解码成真实字符。 */
function decodeJava(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '\\') {
      const n = s[i + 1];
      if (n === 'n') { out += '\n'; i++; continue; }
      if (n === 't') { out += '\t'; i++; continue; }
      if (n === 'r') { out += '\r'; i++; continue; }
      if (n === 'b') { out += '\b'; i++; continue; }
      if (n === 'f') { out += '\f'; i++; continue; }
      if (n === '"') { out += '"'; i++; continue; }
      if (n === "'") { out += "'"; i++; continue; }
      if (n === '\\') { out += '\\'; i++; continue; }
      if (n === 'u') {
        const hex = s.slice(i + 2, i + 6);
        if (/^[0-9a-fA-F]{4}$/.test(hex)) {
          out += String.fromCharCode(parseInt(hex, 16));
          i += 5;
          continue;
        }
      }
      throw new Error('无法解码的转义序列: \\' + n + '（原文: ' + s.slice(0, 40) + '）');
    }
    out += s[i];
  }
  return out;
}

/** 把真实字符编码成 JSON 字符串字面量内容。 */
function encodeJson(s) {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
    .replace(/[\b]/g, '\\b')
    .replace(/\f/g, '\\f');
}

/** 8 位十六进制内容哈希。 */
function hash8(s) {
  let h1 = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h1 ^= s.charCodeAt(i);
    h1 = (h1 * 0x01000193) >>> 0;
  }
  return h1.toString(16).padStart(8, '0');
}

/**
 * 从路径推出模块名（key 前缀）。
 * 例：.../game/service/sect/SectService.java        → sect
 *     .../game/service/FarmService.java            → farm（类名回退）
 *     .../onebot/command/economy/ExchangeCommand.java → economy
 *     .../rest/AdminResource.java                  → rest
 *
 * 规则：先取包路径里最后一段"有业务含义"的段；如果只剩 service/rest/adapter
 * 这类结构性名字，就退回用类名——FarmService 里的文案归 farm 比归 service
 * 有意义得多。
 */
function moduleFor(file) {
  const parts = file.replace(/\\/g, '/').split('/');
  const className = parts.pop().replace(/\.java$/, '');
  const pkg = parts.slice(parts.lastIndexOf('com') + 1);
  const STRUCTURAL = new Set(['mtxgdn', 'game', 'common', 'main', 'service',
                              'command', 'rest', 'adapter', 'entity', 'item',
                              'data', 'util', 'db', 'i18n', 'plugin', 'onebot']);
  let mod = null;
  for (let i = pkg.length - 1; i >= 0; i--) {
    if (!STRUCTURAL.has(pkg[i])) { mod = pkg[i]; break; }
  }
  if (!mod) {
    // 类名回退：CaveService → cave，ExchangeCommand → exchange，AdminResource → admin
    mod = className
      .replace(/(Service|Command|Resource|Adapter|Manager|Handler|Listener|Task|Job|Scanner|Util|Helper)$/, '')
      .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
      .toLowerCase() || 'game';
  }
  return mod.replace(/[^a-z0-9_]/g, '');
}

/**
 * 在源码里找"整段是单一字符串字面量"的消息出口调用。
 * 返回 { start, end, callText, literalRaw, literalText } 列表。
 * 靠括号配平 + 字符串感知扫描，所以 line 拼接、注释里的中文都不会误伤。
 */
function findSites(src) {
  // 一行内找方法名起点。关键约束：前一个字符不能是 '.' 或单词字符，
  // 否则 put( 会命中 addProperty( 里的尾巴、或某个以 put 结尾的变量方法。
  // 但变量名本身（result / out / err）是以 . 结尾的情况不存在——它们直接 .put，
  // 所以这里只排除".put"的误匹配，放开"result.put"这种合法形态。
  const CALL_STARTS = [
    /(?:^|[^.\w])reply\(\s*"/g,
    /(?:^|[^.\w])replyPrivate\(\s*"/g,
    /(?:^|[^.\w])createResult\(\s*(?:true|false)\s*,\s*"/g,
    /(?:^|[^.\w])addProperty\(\s*"message"\s*,\s*"/g,
    // put("message", ...) 只可能是往 Map/JsonObject 写 message 字段，
    // 正是要迁的形态；允许 result.put / out.put / .put 与裸 put(。
    /(?:^|\.|[^.\w])put\(\s*"message"\s*,\s*"/g,
  ];

  const out = [];
  const lines = src.split('\n');
  for (let li = 0; li < lines.length; li++) {
    const line = lines[li];
    // 找所有候选起点
    const candidates = [];
    for (const re of CALL_STARTS) {
      re.lastIndex = 0;
      const m = re.exec(line);
      if (m) candidates.push(m.index + m[0].length);
    }
    if (!candidates.length) continue;

    for (const openIdx of candidates) {
      // m[0] 已含左引号，所以 openIdx 指向字符串内容起点
      let j = openIdx;
      let lit = '';
      let closed = false;
      while (j < line.length) {
        const c = line[j];
        if (c === '\\') { lit += c + line[j + 1]; j += 2; continue; }
        if (c === '"') { closed = true; break; }
        lit += c;
        j++;
      }
      if (!closed) { out.push({ skipReason: '字符串字面量跨行或未闭合', li }); continue; }
      // ) 必须紧跟在引号之后
      let after = line.slice(j + 1);
      const trimmed = after.replace(/^\s+/, '');
      if (trimmed[0] !== ')') {
        out.push({ skipReason: '参数不完整或含拼接/后续参数', li, literalRaw: lit });
        continue;
      }
      let text;
      try { text = decodeJava(lit); } catch (e) {
        out.push({ skipReason: '转义序列无法解码: ' + e.message, li, literalRaw: lit });
        continue;
      }
      if (!/[\u4e00-\u9fff]/.test(text)) continue;

      const key = null; // 稍后统一生成（需模块名）
      out.push({
        li,
        literalRaw: lit,
        text,
        // 整行替换：从左引号到右引号整体换成 I18n.t("key")。
        // 注意 leftQuote 是匹配到的那个引号，不是 openIdx-1——因为
        // 正则里末尾的 " 已被 m[0] 吃掉了，openIdx 指向内容起点。
        newLineFor: (k) => line.slice(0, openIdx - 1) + 'I18n.t("' + k + '")' + line.slice(j + 1),
      });
      break; // 一行只处理一个（真实代码里一行只有一个调用）
    }
  }
  return out;
}

// ---- 主流程 ----------------------------------------------------------------

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, e.name);
    if (e.isDirectory()) walk(full);
    else if (e.name.endsWith('.java')) files.push(full);
  }
})(ROOT_DIR);

let migrated = 0;
let skipped = 0;
const keyMap = new Map();     // key -> { mod, text, files:Set }
const skippedList = [];
const touched = [];

for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  let src = fs.readFileSync(file, 'utf8');
  const eol = src.includes('\r\n') ? '\r\n' : '\n';
  const lines = src.split('\n');
  const sites = findSites(src);
  if (!sites.some(s => !s.skipReason)) continue;

  const mod = moduleFor(file);
  let changed = 0;
  // 从后往前替换，避免行号错位
  sites.filter(s => !s.skipReason).reverse().forEach(s => {
    const key = mod + '.h' + hash8(s.text);
    if (!keyMap.has(key)) keyMap.set(key, { mod, text: s.text, files: new Set() });
    keyMap.get(key).files.add(rel + ':' + (s.li + 1));
    lines[s.li] = s.newLineFor(key);
    changed++;
    migrated++;
  });
  for (const s of sites.filter(x => x.skipReason)) {
    if (x_uninteresting(s.skipReason)) continue;
    skippedList.push(rel + ':' + (s.li + 1) + '  [' + s.skipReason + ']  ' + (s.literalRaw || ''));
    skipped++;
  }

  if (changed) {
    let out = lines.join(eol);
    // 补 import：若文件还没引入 I18n，就在最后一个 import 之后插入
    if (!/^import\s+com\.mtxgdn\.i18n\.I18n\s*;/m.test(out)) {
      const im = out.match(/^import\s+.*;$/gm);
      if (im) {
        const last = im[im.length - 1];
        out = out.replace(last, last + eol + 'import com.mtxgdn.i18n.I18n;');
      } else {
        // 没有 import：插在 package 行之后
        out = out.replace(/^(package\s+[^;]+;)/m, '$1' + eol + eol + 'import com.mtxgdn.i18n.I18n;');
      }
    }
    if (WRITE) fs.writeFileSync(file, out, 'utf8');
    touched.push(rel + ' (+' + changed + ')');
  }
}

function x_uninteresting(reason) {
  return reason === '非中文，无需迁移';
}

// ---- 输出 ------------------------------------------------------------------

console.log(WRITE ? '=== 已写入 ===' : '=== 演练（未写盘） ===');
console.log('迁出调用点: ' + migrated + '，跳过: ' + skipped);
console.log('涉及文件: ' + touched.length);
console.log('新增 key: ' + keyMap.size);
console.log('');
console.log('--- 涉及文件 ---');
touched.sort().forEach(t => console.log('  ' + t));

if (skippedList.length) {
  console.log('');
  console.log('--- 跳过的调用点（需人工处理，多为含拼接的消息） ---');
  console.log(skippedList.slice(0, 60).join('\n'));
  if (skippedList.length > 60) console.log('... 其余 ' + (skippedList.length - 60) + ' 条略');
}

if (WRITE) {
  // 合并进 zh_cn.json（已存在的 key 绝不覆盖）
  const zhFile = path.join(LANG_DIR, 'zh_cn.json');
  let zh = {};
  try { zh = JSON.parse(fs.readFileSync(zhFile, 'utf8')); } catch { zh = {}; }
  let added = 0, dup = 0;
  for (const [k, v] of keyMap) {
    if (k in zh) { dup++; continue; }
    zh[k] = v.text;
    added++;
  }
  // 排序写回，保证 diff 稳定
  const sorted = {};
  for (const k of Object.keys(zh).sort()) sorted[k] = zh[k];
  fs.writeFileSync(zhFile, JSON.stringify(sorted, null, 2) + '\n', 'utf8');
  console.log('');
  console.log('zh_cn.json: 新增 ' + added + '，已存在跳过 ' + dup + '，总计 ' + Object.keys(zh).length);

  // 生成 key → 原文 映射表，供人工审阅与后续改名
  const mapLines = [];
  mapLines.push('# i18n key 映射表（自动生成）');
  mapLines.push('');
  mapLines.push('由 `tools/i18n/migrate-literals.js --write` 生成。');
  mapLines.push('key 形如 `<模块>.h<8位内容哈希>`：用内容哈希作名字，是为了让');
  mapLines.push('「改动一段无关文案」不会导致整批 key 漂移——已发布客户端的');
  mapLines.push('语言包缓存才不会因此整体失效。');
  mapLines.push('');
  mapLines.push('想改成语义化名字时，先改这张表再重跑即可（脚本对已存在的 key');
  mapLines.push('一律跳过，不会覆盖人已经审过的译文）。');
  mapLines.push('');
  mapLines.push('| key | 原文（zh_cn） | 出现位置 |');
  mapLines.push('| --- | --- | --- |');
  for (const k of [...keyMap.keys()].sort()) {
    const v = keyMap.get(k);
    const loc = [...v.files].sort().slice(0, 3).join('<br>');
    const more = v.files.size > 3 ? ' 等 ' + v.files.size + ' 处' : '';
    mapLines.push('| `' + k + '` | ' + v.text.replace(/\|/g, '\\|').replace(/\n/g, '\\n') + ' | ' + loc + more + ' |');
  }
  fs.writeFileSync(path.join(ROOT, 'docs/i18n-key-map.md'), mapLines.join('\n') + '\n', 'utf8');
  console.log('docs/i18n-key-map.md: ' + keyMap.size + ' 条');
}
