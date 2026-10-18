#!/usr/bin/env node
/**
 * 把新 key 追加进语言包。
 *
 * 用法: node tools/i18n/add-keys.js <patch.json> [--lang zh_cn,en_us]
 *
 * patch.json 形如：
 *   { "trade.list.quantity_invalid": "数量必须大于 0", ... }
 *
 * 已存在的 key 一律跳过，绝不覆盖人已经改过的译文。
 * 缺 --lang 时默认只写 zh_cn。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const LANG_DIR = path.join(ROOT, 'src/main/java/data/mtxgdn/lang');

const patchFile = process.argv[2];
if (!patchFile) {
  console.error('用法: node tools/i18n/add-keys.js <patch.json> [--lang zh_cn,en_us]');
  process.exit(1);
}
const patch = JSON.parse(fs.readFileSync(patchFile, 'utf8'));

let langs = ['zh_cn'];
const li = process.argv.indexOf('--lang');
if (li >= 0 && process.argv[li + 1]) {
  langs = process.argv[li + 1].split(',').map(s => s.trim()).filter(Boolean);
}

for (const lang of langs) {
  const file = path.join(LANG_DIR, lang + '.json');
  let data = {};
  try {
    data = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    data = {};
  }
  let added = 0;
  let skipped = 0;
  for (const [k, v] of Object.entries(patch)) {
    if (k in data) {
      skipped++;
      continue;
    }
    data[k] = v;
    added++;
  }
  fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n', 'utf8');
  console.log(lang + ': 新增 ' + added + '，跳过已存在 ' + skipped + '，总计 ' + Object.keys(data).length);
}
