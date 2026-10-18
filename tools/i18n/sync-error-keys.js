#!/usr/bin/env node
/**
 * 把 GameErrorCode 的 (key, message) 灌进语言包。
 *
 * 用法: node tools/i18n/sync-error-keys.js
 *
 * - zh_cn.json: 缺的 error.* key 补进去（已有的一律不动，避免手改被覆盖）
 * - en_us.json : 只植入 error.* key，其余留空由回退链兜到中文
 *               （骨架阶段刻意不机翻，文案必须人译）
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const ENUM = path.join(ROOT, 'src/main/java/com/mtxgdn/common/GameErrorCode.java');
const ZH = path.join(ROOT, 'src/main/java/data/mtxgdn/lang/zh_cn.json');
const EN = path.join(ROOT, 'src/main/java/data/mtxgdn/lang/en_us.json');

const src = fs.readFileSync(ENUM, 'utf8');
const re = /^\s*([A-Z][A-Z0-9_]*)\s*\(\s*(\d+)\s*,\s*"([a-z0-9_.]+)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)/gm;

const codes = {};
let m;
while ((m = re.exec(src)) !== null) {
  codes[m[3]] = m[4];
}
const keys = Object.keys(codes);
console.log('GameErrorCode 解析出 ' + keys.length + ' 个 key');

// --- zh_cn.json ---
const zh = JSON.parse(fs.readFileSync(ZH, 'utf8'));
let zhAdded = 0;
for (const k of keys) {
  if (!(k in zh)) {
    zh[k] = codes[k];
    zhAdded++;
  }
}
fs.writeFileSync(ZH, JSON.stringify(zh, null, 2) + '\n', 'utf8');
console.log('zh_cn.json: 新增 ' + zhAdded + ' 条，总计 ' + Object.keys(zh).length + ' 条');

// --- en_us.json ---
let en = {};
try {
  en = JSON.parse(fs.readFileSync(EN, 'utf8'));
} catch {
  en = {};
}
let enAdded = 0;
for (const k of keys) {
  if (!(k in en)) {
    en[k] = codes[k]; // 骨架期先填中文占位，回退链保证不会露 key
    enAdded++;
  }
}
fs.writeFileSync(EN, JSON.stringify(en, null, 2) + '\n', 'utf8');
console.log('en_us.json: 新增 ' + enAdded + ' 条待人工翻译，总计 ' + Object.keys(en).length + ' 条');

// --- 一致性校验 ---
const zhKeys = new Set(Object.keys(zh));
const missing = keys.filter(k => !zhKeys.has(k));
if (missing.length) {
  console.error('仍缺失: ' + missing.join(', '));
  process.exit(1);
}
console.log('一致性校验通过');
