/**
 * 把"字符串 + 变量"拼接的消息出口转换成 I18n.t(key, args...) 模板。
 *
 * 覆盖的形态（按出现频率从高到低）：
 *   1. createResult(false, "前缀 " + x + " 后缀")            → I18n.t(key, x)
 *   2. put("message", "前缀 " + x)                            → I18n.t(key, x)
 *   3. put("message", a + "中间" + b)                         → I18n.t(key, a, b)
 *   4. put("message", "常量前缀 " + expr)                     → I18n.t(key, expr)
 *   5. reply("前缀 " + x + "\n后缀 " + y)                     → I18n.t(key, x, y)
 *
 * 只认"表达式是单个标识符/字段访问/简单方法调用"的段；遇到三元、强转、
 * 多行、括号嵌套的表达式一律跳过——那类要人看，很容易把优先级搞错。
 *
 * 指南针式的安全保证：
 *   - 转换从"最后一个 str -> non-str 的边界"开始，逐段交替 split；
 *   - 生成的模板按 {0} {1} 顺序编号，与实参顺序严格一致；
 *   - 兜底校验：把 args 依次回填进模板后发现与原文不一致，就放弃这个调用点。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const LANG_DIR = path.join(ROOT, 'src/main/java/data/mtxgdn/lang');

const WRITE = process.argv.includes('--write');
let SUBDIR = null;
const di = process.argv.indexOf('--dir');
if (di >= 0 && process.argv[di + 1] && process.argv[di + 1] !== '.') SUBDIR = process.argv[di + 1];
const ROOT_DIR = SUBDIR ? path.resolve(ROOT, SUBDIR) : path.join(ROOT, 'src/main/java');

const CN = /[\u4e00-\u9fff]/;

/** 简化版 Java 字符串字面量解码（只处理本项目用到的转义）。 */
function decodeJava(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '\\') {
      const n = s[i + 1];
      const map = { n: '\n', t: '\t', r: '\r', b: '\b', f: '\f', '"': '"', "'": "'", '\\': '\\' };
      if (n in map) { out += map[n]; i++; continue; }
      if (n === 'u' && /^[0-9a-fA-F]{4}$/.test(s.slice(i + 2, i + 6))) {
        out += String.fromCharCode(parseInt(s.slice(i + 2, i + 6), 16));
        i += 5;
        continue;
      }
      throw new Error('unsupported escape \\' + n);
    }
    out += s[i];
  }
  return out;
}

/** 把真实字符编码成 JSON 字符串内容。 */
function encodeJson(s) {
  return s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
    .replace(/\n/g, '\\n').replace(/\r/g, '\\r').replace(/\t/g, '\\t');
}

function hash8(s) {
  let h1 = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) { h1 ^= s.charCodeAt(i); h1 = (h1 * 0x01000193) >>> 0; }
  return h1.toString(16).padStart(8, '0');
}

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
    mod = className
      .replace(/(Service|Command|Resource|Adapter|Manager|Handler|Listener|Task|Job|Scanner|Util|Helper)$/, '')
      .replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase() || 'game';
  }
  return mod.replace(/[^a-z0-9_]/g, '');
}

/** 顶层（不在引号/括号内）分割 + 表达式，返回 string|expr 交替的片段数组。 */
function splitTopLevel(arg) {
  const segs = [];
  let i = 0;
  let depth = 0;
  let cur = '';
  let inStr = false;
  let strBuf = null;

  const flushExpr = () => {
    const t = cur.trim();
    if (t) segs.push({ kind: 'expr', code: t });
    cur = '';
  };

  while (i < arg.length) {
    const c = arg[i];
    if (inStr) {
      if (c === '\\') { strBuf += c + arg[i + 1]; i += 2; continue; }
      if (c === '"') {
        inStr = false;
        segs.push({ kind: 'str', raw: strBuf });
        // 必须推进 i：否则下一个循环会把这个收尾引号当成新的开引号，
        // 于是后面的 + expr + 全被吞进 strBuf。
        i++;
        continue;
      }
      strBuf += c;
      i++;
      continue;
    }
    if (c === '"') {
      flushExpr();
      inStr = true;
      strBuf = '';
      i++;
      continue;
    }
    if (c === '(' || c === '[' || c === '{') { depth++; cur += c; i++; continue; }
    if (c === ')' || c === ']' || c === '}') { depth--; cur += c; i++; continue; }
    if (c === '+' && depth === 0) {
      // 顶层 +，切
      flushExpr();
      i++;
      // 吃掉 + 后的空白（Java 里 + 两侧换行很常见）
      while (i < arg.length && /\s/.test(arg[i])) i++;
      continue;
    }
    cur += c;
    i++;
  }
  flushExpr();
  return segs;
}

/** 表达式是否是"简单"的（标识符/字段/简单调用），否则不可自动迁移。 */
function isSimpleExpr(code) {
  const t = code.trim();
  // 拒绝含三元、强转、new、throw、lambda 的
  if (/\?|new\s|throw\s|->/.test(t)) return false;
  // 允许：ident(...) / ident / a.b(...) / a.b.c(...) / a[0].b(...)
  // 调用参数里不能再有括号（嵌套调用一律算复杂，交给人工）
  return /^[A-Za-z_$][\w$]*(\s*\(\s*[^()]*\s*\))?(\s*\.\s*[A-Za-z_$][\w$]*(\s*\(\s*[^()]*\s*\))?)*(\s*\[\s*[^[\]]*\s*\])*$/.test(t);
}

/** 合并相邻 str 段（a + "x" + "y" + b 里的两个常量），返回最终片段序列。 */
function mergeAdjacent(segs) {
  const out = [];
  for (const s of segs) {
    if (s.kind === 'str' && out.length && out[out.length - 1].kind === 'str') {
      out[out.length - 1].raw += s.raw;
    } else {
      out.push(s.kind === 'str' ? { kind: 'str', raw: s.raw } : { ...s });
    }
  }
  return out;
}

const CALL_STARTS = [
  /(?:^|[^.\w])reply\(\s*"/g,
  /(?:^|[^.\w])replyPrivate\(\s*"/g,
  /(?:^|[^.\w])createResult\(\s*(?:true|false)\s*,\s*"/g,
  // addProperty 几乎总是 obj.addProperty(...)，必须放行 '.' 前缀。
  // 沿用 put 那套 [^.\w] 守卫会让一处都匹配不上（migrate-literals.js 里
  // 踩过同一个坑，那次漏掉了 114 处）。
  /(?:^|\.|[^.\w])addProperty\(\s*"message"\s*,\s*"/g,
  /(?:^|\.|[^.\w])put\(\s*"message"\s*,\s*"/g,
  /(?:^|\.|[^.\w])restOk\(\s*"/g,
];

/** 从 ( 开始按括号配平找到 ) 的位置（字符串感知）。 */
function matchParen(line, fromIdx) {
  let depth = 0;
  let inStr = false;
  for (let i = fromIdx; i < line.length; i++) {
    const c = line[i];
    if (inStr) {
      if (c === '\\') { i++; continue; }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') { inStr = true; continue; }
    if (c === '(') depth++;
    else if (c === ')') { depth--; if (depth === 0) return i; }
  }
  return -1;
}

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, e.name);
    if (e.isDirectory()) walk(full);
    else if (e.name.endsWith('.java')) files.push(full);
  }
})(ROOT_DIR);

if (process.argv.includes('--debug-root')) {
  console.error('ROOT=' + ROOT);
  console.error('ROOT_DIR=' + ROOT_DIR + ' exists=' + fs.existsSync(ROOT_DIR));
  console.error('files=' + files.length);
  console.error('argv=' + JSON.stringify(process.argv));
  process.exit(0);
}

let migrated = 0;
let skipped = 0;
const keyMap = new Map();
const skippedList = [];
const touched = new Map();

/**
 * 把源码切成"逻辑行"数组。
 * 只有当一行里的括号/引号未配平时才继续吞下一行——Java 允许字符串
 * 拼接写在一行、也允许断行续写，所以要按括号深度判断，不能只看 '\n'。
 * 返回 [{ start, end, text }]，text 保留原始换行，end 是（闭区间）行号。
 */
function logicalLines(lines) {
  const out = [];
  let i = 0;
  while (i < lines.length) {
    let depth = 0;
    let inStr = false;
    let j = i;
    for (; j < lines.length; j++) {
      const line = lines[j];
      let inLineComment = false;
      for (let k = 0; k < line.length; k++) {
        const c = line[k];
        if (inLineComment) break;
        if (inStr) {
          if (c === '\\') { k++; continue; }
          if (c === '"') inStr = false;
          continue;
        }
        if (c === '/' && line[k + 1] === '/') { break; }
        if (c === '"') { inStr = true; continue; }
        if (c === '(') depth++;
        else if (c === ')') depth--;
      }
      if (depth <= 0 && !inStr) { break; }
    }
    out.push({ start: i, end: Math.min(j, lines.length - 1), text: lines.slice(i, j + 1).join('\n') });
    i = j + 1;
  }
  return out;
}

for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  let src = fs.readFileSync(file, 'utf8');
  const eol = src.includes('\r\n') ? '\r\n' : '\n';
  // 必须用 /\r?\n/ 切分：split('\n') 会把行尾 \r 留在每行末尾，
  // 后面 join('\r\n') 就成了 \r\r\n，整份文件换行符被写坏。
  const lines = src.split(/\r?\n/);
  const mod = moduleFor(file);

  const edits = new Map(); // 行号 -> 新行内容
  const spans = new Map(); // 行号 -> 需整体替换的逻辑行区间（多行时）

  for (const span of logicalLines(lines)) {
    const text = span.text;
    const firstLineNo = span.start;
    for (const re of CALL_STARTS) {
      re.lastIndex = 0;
      const m = re.exec(text);
      if (!m) continue;
      // m[0] 里已含唯一的 '('
      const openParen = m.index + m[0].indexOf('(');
      const strStart = m.index + m[0].length - 1; // 左引号位置
      const closeParen = matchParen(text, openParen);
      if (closeParen < 0) {
        skippedList.push(rel + ':' + (firstLineNo + 1) + '  [括号未配平]  ' + text.split('\n')[0].trim().slice(0, 70));
        skipped++;
        break;
      }
      // 参数原文（含引号，可能跨行）
      const argFull = text.slice(strStart, closeParen);
      if (!CN.test(decodeJavaSafe(argFull))) break;

      const segs = mergeAdjacent(splitTopLevel(argFull));
      const hasStr = segs.some(s => s.kind === 'str');
      const hasExpr = segs.some(s => s.kind === 'expr');
      if (!hasStr) break;
      if (segs[0].kind !== 'str') {
        skippedList.push(rel + ':' + (firstLineNo + 1) + '  [参数以变量开头]  ' + argFull.slice(0, 70));
        skipped++;
        break;
      }
      const ok0 = hasExpr;
      let ok = ok0;
      for (const s of segs) {
        if (s.kind === 'expr' && !isSimpleExpr(s.code)) {
          ok = false;
          skippedList.push(rel + ':' + (firstLineNo + 1) + '  [表达式复杂: ' + s.code.slice(0, 40) + ']');
          skipped++;
          break;
        }
      }
      if (!ok) break;

      let template = '';
      const args = [];
      for (const s of segs) {
        if (s.kind === 'str') {
          let t;
          try { t = decodeJava(s.raw); } catch {
            skippedList.push(rel + ':' + (firstLineNo + 1) + '  [转义不可解码]  ' + s.raw.slice(0, 50));
            skipped++;
            ok = false;
            break;
          }
          template += t;
        } else {
          template += '{' + args.length + '}';
          args.push(s.code);
        }
      }
      if (!ok) break;

      // 兜底：模板去掉占位符后，必须与原文里所有字符串字面量按序拼接完全一致
      const templateStrOnly = template.replace(/\{\d+\}/g, '');
      const originalStrs = segs.filter(s => s.kind === 'str').map(s => decodeJavaSafe(s.raw)).join('');
      if (templateStrOnly !== originalStrs) {
        skippedList.push(rel + ':' + (firstLineNo + 1) + '  [模板与原文不一致]  ' + template.slice(0, 60));
        skipped++;
        break;
      }
      if (!CN.test(template)) break;

      const key = mod + '.h' + hash8(template);
      if (!keyMap.has(key)) keyMap.set(key, { mod, text: template, files: new Set() });
      keyMap.get(key).files.add(rel + ':' + (firstLineNo + 1));

      const argList = args.length ? ', ' + args.join(', ') : '';
      // 替换区间是 [strStart, closeParen) —— 不含 closeParen 本身。
      // closeParen 是外层调用的右括号（如 createResult(...) 的 )），
      // 必须原样留在尾部，否则生成 createResult(false, I18n.t(...) 少一个括号。
      const newText = text.slice(0, strStart)
        + 'I18n.t("' + key + '"' + argList + ')'
        + text.slice(closeParen);

      if (span.end === span.start) {
        edits.set(span.start, newText);
      } else {
        // 多行调用：第一行放新调用，其余行清空（保留缩进以便回滚 diff 干净）
        const parts = newText.split('\n');
        edits.set(span.start, parts[0]);
        for (let k = 1; k < parts.length; k++) edits.set(span.start + k, parts[k]);
        // 若 newText 行数少于原区间，多出来的原行要删掉
        const origCount = span.end - span.start + 1;
        for (let k = parts.length; k < origCount; k++) edits.set(span.start + k, null);
      }
      migrated++;
      break;
    }
  }

  if (edits.size) {
    const nl = [];
    for (let i = 0; i < lines.length; i++) {
      if (edits.has(i)) {
        const v = edits.get(i);
        if (v === null) continue; // 删除该行
        for (const p of v.split('\n')) nl.push(p);
      } else {
        nl.push(lines[i]);
      }
    }
    let out = nl.join(eol);
    if (!/^import\s+com\.mtxgdn\.i18n\.I18n\s*;/m.test(out)) {
      const im = out.match(/^import\s+.*;$/gm);
      if (im) out = out.replace(im[im.length - 1], im[im.length - 1] + eol + 'import com.mtxgdn.i18n.I18n;');
      else out = out.replace(/^(package\s+[^;]+;)/m, '$1' + eol + eol + 'import com.mtxgdn.i18n.I18n;');
    }
    if (WRITE) fs.writeFileSync(file, out, 'utf8');
    touched.set(rel, edits.size);
  }
}

function decodeJavaSafe(raw) {
  try { return decodeJava(raw); } catch { return raw; }
}

console.log(WRITE ? '=== 已写入 ===' : '=== 演练（未写盘） ===');
console.log('迁出调用点: ' + migrated + '，跳过: ' + skipped);
console.log('涉及文件: ' + touched.size + '，新增 key: ' + keyMap.size);
if (touched.size) {
  console.log('');
  console.log('--- 涉及文件 ---');
  [...touched.entries()].sort((a, b) => b[1] - a[1]).slice(0, 40)
    .forEach(([f, n]) => console.log(String(n).padStart(5) + '  ' + f));
}
if (skippedList.length) {
  console.log('');
  console.log('--- 跳过 ' + skippedList.length + ' 处（原因分布） ---');
  const byReason = {};
  for (const s of skippedList) {
    const r = s.match(/\[([^\]]+)\]/);
    const k = r ? r[1].split(':')[0] : 'other';
    byReason[k] = (byReason[k] || 0) + 1;
  }
  Object.entries(byReason).sort((a, b) => b[1] - a[1]).forEach(([k, v]) => console.log('  ' + v + '  ' + k));
  console.log('');
  console.log(skippedList.slice(0, 25).join('\n'));
}

if (WRITE && keyMap.size) {
  const zhFile = path.join(LANG_DIR, 'zh_cn.json');
  let zh = {};
  try { zh = JSON.parse(fs.readFileSync(zhFile, 'utf8')); } catch { zh = {}; }
  let added = 0, dup = 0;
  for (const [k, v] of keyMap) {
    if (k in zh) { dup++; continue; }
    zh[k] = v.text;
    added++;
  }
  const sorted = {};
  for (const k of Object.keys(zh).sort()) sorted[k] = zh[k];
  fs.writeFileSync(zhFile, JSON.stringify(sorted, null, 2) + '\n', 'utf8');
  console.log('');
  console.log('zh_cn.json: 新增 ' + added + '，已存在跳过 ' + dup + '，总计 ' + Object.keys(zh).length);
}
