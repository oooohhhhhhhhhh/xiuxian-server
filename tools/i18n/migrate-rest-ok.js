/**
 * 把 GameMessage.restOk("固定文案", …) 里的中文字面量换成 I18n.t(key)。
 *
 * 这类调用形态是 restOk("获取成功", data)——第一个参数就是整条文案，
 * 后面还有别的参数，所以 migrate-literals.js 那个"右引号必须紧跟 )"
 * 的规则接不了它。而 count.js 的 A 类口径（reply/createResult/put("message")…）
 * 也没覆盖它，于是这几十处一直藏在统计外面。
 *
 * 只替换"第一个实参是纯中文字面量"的调用；任何带 + 拼接或非字面量的
 * 一律不碰，交给人看。
 *
 * 用法: node tools/i18n/migrate-rest-ok.js [--write]
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const WRITE = process.argv.includes('--write');

// key 固定映射：同一句文案在几十个端点里复用，就该是同一个 key
const MAP = {
  '获取成功': 'common.rest.ok',
  '导出成功': 'common.rest.export_ok',
  '语言包已重载': 'admin.i18n.reload_ok',
};

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

for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  let src = fs.readFileSync(file, 'utf8');
  let count = 0;

  src = src.replace(/restOk\(\s*"([^"]+)"\s*,/g, (whole, text) => {
    const key = MAP[text];
    if (!key) return whole;          // 不在映射表里就原样留下
    count++;
    return whole.replace('"' + text + '"', 'I18n.t("' + key + '")');
  });

  if (!count) continue;
  total += count;
  touched.push(rel + ' ×' + count);

  if (!WRITE) continue;

  // 补 import
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
if (!WRITE) console.log('（演练，未写盘；加 --write 生效）');
