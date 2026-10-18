/**
 * 统计每个源文件里"A 类玩家可见中文字面量"的条数，用于评估迁移工作量。
 *
 * A 类定义见 docs/i18n-design.md：
 *   1. reply("...") / replyPrivate("...")
 *   2. createResult(false|true, "...") / result.put("message", "...")
 *   3. addProperty("message", "...")
 * 日志（LOG.*）与注释不算。
 *
 * 用法: node tools/i18n/count.js [dir]
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const SRC = path.resolve(ROOT, process.argv[2] || 'src/main/java');

const CN = /[\u4e00-\u9fff]/;
// 只统计"整段是中文"的字符串字面量，避免把含中文的日志也算进来
const PATTERNS = [
  /\.(?:reply|replyPrivate)\(\s*"([^"\\]*[\u4e00-\u9fff][^"\\]*)"/g,
  /createResult\(\s*(?:true|false)\s*,\s*"([^"\\]*[\u4e00-\u9fff][^"\\]*)"/g,
  /\.put\(\s*"message"\s*,\s*"([^"\\]*[\u4e00-\u9fff][^"\\]*)"/g,
  /addProperty\(\s*"message"\s*,\s*"([^"\\]*[\u4e00-\u9fff][^"\\]*)"/g,
];

const rows = [];
const seenText = new Map();

function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) {
      walk(full);
    } else if (e.name.endsWith('.java')) {
      const src = fs.readFileSync(full, 'utf8');
      let n = 0;
      for (const re of PATTERNS) {
        let m;
        while ((m = re.exec(src)) !== null) {
          n++;
          seenText.set(m[1], (seenText.get(m[1]) || 0) + 1);
        }
      }
      if (n > 0) {
        rows.push({ file: path.relative(ROOT, full).replace(/\\/g, '/'), n });
      }
    }
  }
}

walk(SRC);
rows.sort((a, b) => b.n - a.n);

console.log('文件数: ' + rows.length);
console.log('A 类字面量总数: ' + rows.reduce((s, r) => s + r.n, 0));
console.log('去重后文本数: ' + seenText.size);
console.log('');
console.log('--- Top 30 文件 ---');
for (const r of rows.slice(0, 30)) {
  console.log(String(r.n).padStart(5) + '  ' + r.file);
}

console.log('');
console.log('--- 出现最多的 30 段文本（优先迁，重复收益最高） ---');
const dupes = [...seenText.entries()].sort((a, b) => b[1] - a[1]).slice(0, 30);
for (const [t, c] of dupes) {
  console.log(String(c).padStart(4) + '  ' + t.replace(/\s+/g, ' '));
}
