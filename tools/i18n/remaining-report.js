/**
 * 汇总 "消息出口里仍硬编码中文" 的全部文本，按出现次数排序。
 *
 * 为什么不直接用 count.js：count.js 的 A 类口径只圈了
 * reply / replyPrivate / createResult / put("message") / addProperty("message")，
 * 漏掉了 restError(int, "中文")、ctx.reply 的多行帮助文本、
 * restOk("文案", data) 的第一参等形态。本脚本按调用点覆盖面写全。
 *
 * 指令注册元数据（super(new String[]{…}, "描述", "用法", …)、registerSub 的别名）
 * 刻意不算——命令名和别名是玩家手打的，翻译了反而搜不到。
 *
 * 用法: node tools/i18n/remaining-report.js
 */
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '../..');
const CJK = /[\u4e00-\u9fff]/;

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, e.name);
    if (e.isDirectory()) walk(full);
    else if (e.name.endsWith('.java')) files.push(full);
  }
})(path.join(ROOT, 'src/main/java'));

const texts = new Map();

// 逐行收集；多行调用靠 joinLogical 拼回
for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const buf = [];
  let depth = 0, started = false, startLine = 0;

  const flush = () => {
    if (!started) return;
    const joined = buf.join('\n');
    // 整段里所有 "…中文…" 字面量
    for (const m of joined.matchAll(/"([^"\\]*(?:\\.[^"\\]*)*)"/g)) {
      const lit = m[1];
      if (!CJK.test(lit)) continue;
      // 命令注册元数据：super(...) / registerSub / registerCommand
      if (/super\(|registerSub\(|registerCommand\(/.test(joined)) {
        if (!/^( Usage|用法|提示|说明)/.test(lit)) continue;
      }
      if (!texts.has(lit)) texts.set(lit, []);
      texts.get(lit).push(rel + ':' + (startLine + 1));
    }
    buf.length = 0; depth = 0; started = false;
  };

  lines.forEach((line, i) => {
    const effective = line.replace(/"(?:[^"\\]|\\.)*"/g, m => ' '.repeat(m.length));
    if (!started) {
      if (!/reply\(|replyPrivate\(|createResult\(|put\("message"|addProperty\("message"|restOk\(|restError\(/.test(line)) return;
      started = true; startLine = i;
    }
    depth += (effective.match(/\(/g) || []).length - (effective.match(/\)/g) || []).length;
    buf.push(line);
    if (depth <= 0) flush();
  });
  flush();
}

const sorted = [...texts.entries()].sort((a, b) => b[1].length - a[1].length);
for (const [text, locs] of sorted) {
  console.log('(' + locs.length + ') ' + JSON.stringify(text) + '   ← ' + locs.slice(0, 2).join(', '));
}
console.log('\n合计 ' + sorted.length + ' 种文案 / ' + [...texts.values()].reduce((s, v) => s + v.length, 0) + ' 处');
