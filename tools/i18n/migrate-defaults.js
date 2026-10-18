/**
 * 把 ctx.reply((String) result.getOrDefault("message", "购买失败"))
 * 里的兜底中文换成 I18n.t(key)。
 *
 * 这类兜底是"服务端没给消息时显示的话"，虽然只在异常路径出现，
 * 但它是玩家确实会看到的文案，一样要能本地化。
 * getOrDefault 的语义不变：仍然是从 result 里取，取不到才用 I18n 的兜底。
 *
 * 用法: node tools/i18n/migrate-defaults.js <map.json> [--write]
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const mapPath = process.argv[2];
const WRITE = process.argv.includes('--write');
const MAP = JSON.parse(fs.readFileSync(mapPath, 'utf8'));

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, e.name);
    if (e.isDirectory()) walk(full);
    else if (e.name.endsWith('.java')) files.push(full);
  }
})(path.join(ROOT, 'src/main/java'));

let total = 0;
const touched = [];
const missing = [];

for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  let src = fs.readFileSync(file, 'utf8');
  let count = 0;

  src = src.replace(/getOrDefault\(\s*"message"\s*,\s*"([^"]+)"\s*\)/g, (whole, text) => {
    const key = MAP[text];
    if (!key) { missing.push(rel + '  ' + text); return whole; }
    count++;
    return whole.replace('"' + text + '"', 'I18n.t("' + key + '")');
  });

  if (!count) continue;
  total += count;
  touched.push(rel + ' ×' + count);

  if (!WRITE) continue;
  if (!/^import\s+com\.mtxgdn\.i18n\.I18n\s*;/m.test(src)) {
    const im = src.match(/^import\s+[^;]*;$/gm);
    if (im) {
      const last = im[im.length - 1];
      src = src.replace(last, last + '\nimport com.mtxgdn.i18n.I18n;');
    }
  }
  fs.writeFileSync(file, src, 'utf8');
}

console.log((WRITE ? '已替换 ' : '可替换 ') + total + ' 处，涉及 ' + touched.length + ' 个文件');
touched.forEach(t => console.log('  ' + t));
if (missing.length) console.log('未映射:\n  ' + [...new Set(missing)].join('\n  '));
if (!WRITE) console.log('（演练，未写盘；加 --write 生效）');
