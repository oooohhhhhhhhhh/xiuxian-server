#!/usr/bin/env node
/**
 * A 类中文字符串抽取草稿生成器。
 *
 * 用法:
 *   node tools/i18n/extract-draft.js            # 生成草稿到 stdout
 *   node tools/i18n/extract-draft.js --write    # 合并写入 docs/i18n-extraction-draft.md
 *
 * 目标: 把「用户可见、面向玩家」的中文字面量找出来，按模块归并去重，
 *      生成一份可人审的草稿。key 命名必须人审，本脚本刻意不自动取 key 名
 *      ——自动取只会得到 msg1 / msg2 这种垃圾键。
 *
 * 扫描范围（A 类）:
 *   1. ctx.reply / replyPrivate / sendTellRaw / sendGroupMsg / sendPrivateMsg / sendBroadcast 的中文参数
 *   2. Service 里 result.put("message", "...") / put("successMessage", ...) 等返回值里的中文
 *   3. REST/WS 响应里的 message 字段
 *   4. GameErrorCode 的 message
 *
 * 明确不扫（B 类/C 类）:
 *   - System.err.println / System.out.println / GameLogger 的日志文本
 *   - new RuntimeException("...") / new IllegalStateException(...) 内部异常
 *   - 注释
 *   - 玩家输入、宗门名、聊天内容
 */

const fs = require('fs');
const path = require('path');

const SRC = path.resolve(__dirname, '../../src/main/java');

/** 需要扫描的「消息出口」模式 */
const MESSAGE_EXIT = [
  /(?:ctx|context|ctxBase)\.replyPrivate?\s*\(/,
  /\.sendTellRaw\s*\(/,
  /\.sendGroupMsg\s*\(/,
  /\.sendPrivateMsg\s*\(/,
  /\.sendBroadcast\s*\(/,
  /replyToSource\s*\(/,
  /\.reply\s*\(/,
];

/** Service 返回值里承载玩家可见文案的字段 */
const RESULT_MESSAGE_FIELDS = [
  'message', 'messageDetail', 'successMessage', 'errorMessage',
  'rewardText', 'detail', 'displayText', 'reason',
];

/** REST/WS 响应里的 message 键 */
const RESPONSE_MESSAGE_KEYS = [
  'message', 'messageKey', 'error', 'reason', 'hint', 'tip', 'msg',
];

/** 中文检测：含 CJK 统一表意文字或全角标点 */
function hasCjk(s) {
  return /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef\u3000-\u303f]/.test(s);
}

/** 提取 Java 双引号字符串字面量（处理转义） */
function extractJavaStrings(line) {
  const out = [];
  let i = 0;
  while (i < line.length) {
    if (line[i] !== '"') { i++; continue; }
    // 跳过 Java 的 unicode 转义 \uXXXX，它们可能出现在字符串外（如 CharSet 名）
    let j = i + 1;
    let buf = '';
    let ok = true;
    while (j < line.length) {
      const c = line[j];
      if (c === '\\') { buf += line.slice(j, j + 2); j += 2; continue; }
      if (c === '"') break;
      buf += c; j++;
      if (j - i > 4000) { ok = false; break; }
    }
    if (ok && j < line.length) { out.push(buf); i = j + 1; }
    else { i++; }
  }
  return out;
}

/** 拆掉字符串拼接 a + "中文" + b，把每段都当独立候选 */
function flattenConcatenation(s) {
  const parts = [];
  let i = 0;
  while (i < s.length) {
    const ch = s[i];
    if (ch === '\\' && i + 1 < s.length) { i += 2; continue; }
    if (ch === '"') {
      // 已在外层处理，不会到这
      i++; continue;
    }
    parts.push(ch); i++;
  }
  return parts.join('');
}

/** 判断一行是否是「消息出口」调用 */
function isMessageExit(line) {
  return MESSAGE_EXIT.some(re => re.test(line));
}

/** 判断一行是否是 Service 结果里的 message 字段 */
function isResultMessage(line) {
  return RESULT_MESSAGE_FIELDS.some(f =>
    new RegExp('\\.put\\s*\\(\\s*"' + f + '"\\s*,').test(line)
  );
}

/** 判断一行是否是 REST/WS 响应的 message 键 */
function isResponseMessage(line) {
  return RESPONSE_MESSAGE_KEYS.some(k =>
    new RegExp('addProperty\\s*\\(\\s*"' + k + '"').test(line)
  );
}

/** 从文件路径推导模块前缀，如 onebot/command/sect/SectCommand.java -> sect */
function modulePrefix(rel) {
  const noExt = rel.replace(/\.java$/, '');
  const segs = noExt.split('/');
  // 去掉包名前缀 com/mtxgdn
  const idx = segs.indexOf('mtxgdn');
  const tail = segs.slice(idx + 1);
  const file = tail.pop() || 'misc';
  if (file === 'GameErrorCode') return 'error';
  if (/Service$/.test(file)) {
    return snake(file.replace(/Service$/, ''));
  }
  if (/Command$/.test(file)) {
    // 目录名优先（更贴近玩家认知）：sect / economy / item / admin ...
    const pkgDir = tail[tail.length - 1];
    if (pkgDir && pkgDir !== 'command' && pkgDir !== 'onebot') return pkgDir;
    const m = file.replace(/Command$/, '');
    return m ? snake(m) : 'command';
  }
  return snake(file);
}

/** 大驼峰转下划线小写：SectCommand -> sect_command */
function snake(s) {
  return s.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

/** GameErrorCode 单独解析：每一行 ENUM(code, "中文") */
function parseErrorCodes(file, rel) {
  const out = [];
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const re = /^\s*([A-Z][A-Z0-9_]*)\s*\(\s*(\d+)\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)/;
  for (const line of lines) {
    const m = line.match(re);
    if (!m) continue;
    if (!hasCjk(m[3])) continue;
    out.push({
      file: rel,
      kind: 'error',
      module: 'error',
      key: 'error.' + m[1].toLowerCase(),
      text: m[3],
    });
  }
  return out;
}

const files = walk(SRC);
const entries = [];
const seen = new Set();

for (const f of files) {
  const rel = path.relative(SRC, f).replace(/\\/g, '/');
  const mod = modulePrefix(rel);
  const lines = fs.readFileSync(f, 'utf8').split(/\r?\n/);

  if (rel.endsWith('common/GameErrorCode.java')) {
    for (const e of parseErrorCodes(f, rel)) {
      const id = e.kind + '|' + e.text;
      if (!seen.has(id)) { seen.add(id); entries.push(e); }
    }
    continue;
  }

  lines.forEach((line, i) => {
    const exitLine = isMessageExit(line);
    const resultLine = isResultMessage(line);
    const respLine = isResponseMessage(line);
    if (!exitLine && !resultLine && !respLine) return;

    const kind = exitLine ? 'reply' : resultLine ? 'result' : 'response';
    // 该行所有中文字符串字面量
    for (const lit of extractJavaStrings(line)) {
      if (!hasCjk(lit)) continue;
      // 拼接场景：把相邻的多段中文拆开，各自成为候选
      const chunks = lit.split(/"\s*\+\s*"/).filter(Boolean);
      for (const chunk of chunks) {
        const text = chunk.trim();
        if (!text || !hasCjk(text)) continue;
        // 太短且不含中文（纯标点）的跳过
        const id = kind + '|' + mod + '|' + text;
        if (seen.has(id)) continue;
        seen.add(id);
        entries.push({
          file: rel + ':' + (i + 1),
          kind,
          module: mod,
          key: null, // 刻意不自动生成：必须人审
          text,
        });
      }
    }
  });
}

// 按 module + kind 分组输出，便于按模块逐个处理
const grouped = {};
for (const e of entries) {
  const g = grouped[e.module] = grouped[e.module] || {};
  const k = g[e.kind] = g[e.kind] || [];
  if (!k.some(x => x.text === e.text)) k.push(e);
}

let md = '# A 类中文字符串抽取草稿\n\n';
md += '> 本文件由 `node tools/i18n/extract-draft.js --write` 生成，**仅作草稿**。\n';
md += '> `key` 一律为 `null`：键名必须人审后填写，自动命名只会产生垃圾键。\n';
md += '> 确认后把 `key` + `text` 写入 `src/main/java/data/mtxgdn/lang/zh_cn.json`，\n';
md += '> 再把源码里的中文字面量替换为 `I18n.t("key")`。\n\n';

md += `共 **${Object.keys(grouped).length}** 个模块、` +
  `${entries.length} 条待迁移字面量（已按文本去重）。\n\n`;

const totalByKind = {};
for (const e of entries) totalByKind[e.kind] = (totalByKind[e.kind] || 0) + 1;

md += '| 类型 | 数量 | 说明 |\n| --- | --- | --- |\n';
md += `| reply | ${totalByKind.reply || 0} | 聊天平台指令回复 |\n`;
md += `| result | ${totalByKind.result || 0} | Service 返回给玩家的 message |\n`;
md += `| response | ${totalByKind.response || 0} | REST/WS 响应 message |\n`;
md += `| error | ${totalByKind.error || 0} | GameErrorCode |\n\n`;

for (const mod of Object.keys(grouped).sort()) {
  md += `## ${mod}\n\n`;
  const kinds = grouped[mod];
  for (const kind of Object.keys(kinds).sort()) {
    md += `### ${kind}\n\n`;
    md += '| key（待填） | 中文文本 | 首次出现位置 |\n| --- | --- | --- |\n';
    for (const e of kinds[kind]) {
      const escaped = e.text.replace(/\|/g, '\\|').replace(/\n/g, '\\n');
      md += `| _待填_ | \`${escaped}\` | \`${e.file}\` |\n`;
    }
    md += '\n';
  }
}

if (process.argv.includes('--write')) {
  const target = path.resolve(__dirname, '../../docs/i18n-extraction-draft.md');
  fs.writeFileSync(target, md, 'utf8');
  console.log('已写入 ' + target);
  console.log('模块数 ' + Object.keys(grouped).length + '，字面量 ' + entries.length);
} else {
  console.log(md);
}
