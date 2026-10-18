/**
 * 列出仍未迁移的 A 类字面量（含位置与跳过原因）。
 *
 * 用法: node tools/i18n/list-remaining.js
 *
 * 复用的是迁移工具自己的扫描逻辑（findSites），
 * 所以"工具不愿意碰"的条目会连同原因一起打出来——
 * 这些正是需要人工判断的那批。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, e.name);
    if (e.isDirectory()) walk(full);
    else if (e.name.endsWith('.java')) files.push(full);
  }
})(path.join(ROOT, 'src/main/java'));

// 与 migrate-literals.js 保持一致的判定：CJK + 落在五个出口形态之一
const CJK = /[\u4e00-\u9fff]/;
const SKIP_CALLS = /registerSub|registerCommand|new String\[\]/;

let total = 0;
for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);

  // 把多行调用拼成逻辑行，这样跨行拼接也能看到全貌
  const logical = [];
  let buf = '', depth = 0, started = false;
  lines.forEach((line, i) => {
    const effective = line.replace(/"[^"]*"/g, m => ' '.repeat(m.length));
    if (!started && !CJK.test(line)) return;
    if (!started && !/\(\s*"|"message"\s*,|\+\s*"|\"\s*\+/.test(line)) {
      // 只有看起来像消息出口调用的行才纳入
      if (!/reply\(|replyPrivate\(|createResult\(|addProperty\(|put\(|restOk\(|restError\(/.test(line)) return;
    }
    started = true;
    depth += (effective.match(/\(/g) || []).length - (effective.match(/\)/g) || []).length;
    buf += (buf ? '\n' : '') + (i + 1) + '| ' + line;
    if (depth <= 0) {
      logical.push(buf);
      buf = ''; depth = 0; started = false;
    }
  });
  if (buf) logical.push(buf);

  const hits = logical.filter(t => CJK.test(t) && !SKIP_CALLS.test(t));
  if (!hits.length) continue;
  console.log('\n### ' + rel + '  (' + hits.length + ')');
  for (const h of hits) {
    total++;
    console.log(h.split('\n').slice(0, 5).join('\n'));
    console.log('   --');
  }
}

console.log('\n合计 " + total + " 处');
