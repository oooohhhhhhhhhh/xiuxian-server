/**
 * 把 restOk / restError 第一个实参里的中文字面量换成 I18n.t(key)。
 *
 * 与 migrate-rest-ok.js 的区别：那个只处理固定的几句话，
 * 本脚本处理"一批任意文案 → 各自一个 key"的通用替换。
 *
 * 关键约束：
 *   1. 只替换"第一个实参是纯中文字面量"的调用；
 *   2. 同一个中文文案映射到同一个 key——几十个端点共用"注册成功"
 *      就该共用一条词条，否则翻译要改几十遍；
 *   3. 已有对应 key 就复用，绝不另起一个（见 patch 文件）。
 *
 * 用法: node tools/i18n/migrate-rest-map.js <map.json> [--write]
 *
 * map.json 形如 { "注册成功": "auth.register_success", ... }
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
const missing = [];
const touched = [];

for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  let src = fs.readFileSync(file, 'utf8');
  let count = 0;

  src = src.replace(/(restOk|restError)\(\s*"([^"]+)"/g, (whole, fn, text) => {
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

// 把词条写进 zh_cn（key =j 的才是要写的；缺失的报出来）
if (WRITE) {
  const zhPath = path.join(ROOT, 'src/main/java/data/mtxgdn/lang/zh_cn.json');
  const zh = JSON.parse(fs.readFileSync(zhPath, 'utf8'));
  let added = 0;
  for (const [text, key] of Object.entries(MAP)) {
    if (key in zh) continue;
    zh[key] = text;
    added++;
  }
  const sorted = {};
  for (const k of Object.keys(zh).sort()) sorted[k] = zh[k];
  fs.writeFileSync(zhPath, JSON.stringify(sorted, null, 2) + '\n', 'utf8');
  console.log('zh_cn 新增 ' + added + ' 条，总计 ' + Object.keys(zh).length);
}

console.log((WRITE ? '已替换 ' : '可替换 ') + total + ' 处，涉及 ' + touched.length + ' 个文件');
touched.forEach(t => console.log('  ' + t));
if (missing.length) {
  console.log('\n未映射（保持原样）:');
  [...new Set(missing)].forEach(t => console.log('  ' + t));
}
if (!WRITE) console.log('（演练，未写盘；加 --write 生效）');
