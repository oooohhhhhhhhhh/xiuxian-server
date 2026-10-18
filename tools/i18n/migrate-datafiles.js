/**
 * 给 realm_config.json / wilderness_map.json 补 nameKey / descKey(s)。
 *
 * 刻意做"按行插入"而不是 JSON.parse → JSON.stringify：
 * 这两个文件是手写的，带大量 _comment 说明、空行分组和刻意对齐的数组。
 * 整份重排版会把 700+ 行diff噪音混进这次改动里，评审时根本看不出
 * "到底加了哪几个字段"。所以这里只往该插的位置插一行，其余原样保留。
 *
 * 老字段（name / description / descriptions）一律保留不动，它们是
 * 语言包查不到时的兜底——即使有人只拷了数据文件没带语言包，
 * 界面依然是完整中文，而不是一堆裸 key。
 *
 * 词条本身由本脚本一并写入 zh_cn.json（已存在则跳过，不覆盖人工译文）。
 *
 * 用法: node tools/i18n/migrate-datafiles.js [--write]
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const RES = path.join(ROOT, 'src/main/resources/game');
const LANG = path.join(ROOT, 'src/main/java/data/mtxgdn/lang/zh_cn.json');
const WRITE = process.argv.includes('--write');

const newKeys = {};

// ---------- realm_config.json ----------
// 对象内字段顺序是 id → name → subRealm → description，
// 所以走到 description 那一行时，id 与 subRealm 都已经读到了。
function patchRealm() {
  const file = path.join(RES, 'realm_config.json');
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const out = [];
  let curId = null;
  let curSub = null;
  let count = 0;

  for (const line of lines) {
    let m;
    if ((m = line.match(/^\s*"id":\s*(-?\d+)\s*,\s*$/))) {
      curId = m[1];
      curSub = null;
    } else if ((m = line.match(/^\s*"subRealm":\s*(-?\d+)\s*,\s*$/))) {
      curSub = m[1];
    } else if ((m = line.match(/^\s*"description":\s*"(.*)"\s*,\s*$/)) && curId !== null && curSub !== null) {
      // 顺手把 name 也取到：往回找最近的一条 "name"
      let nameLine = null;
      for (let i = out.length - 1; i >= 0; i--) {
        if (/^\s*"name":/.test(out[i])) { nameLine = out[i]; break; }
      }
      const name = nameLine ? JSON.parse('{' + nameLine.trim().replace(/,\s*$/, '') + '}').name : '';
      const desc = JSON.parse('{"d":"' + m[1].replace(/(?<!\\)"/g, '\\"') + '"}').d;

      const base = 'realm.' + curId + '.' + curSub;
      const nameKey = base + '.name';
      const descKey = base + '.desc';
      newKeys[nameKey] = name;
      newKeys[descKey] = desc;

      const indent = line.match(/^\s*/)[0];
      out.push(line);
      out.push(indent + '"nameKey": "' + nameKey + '",');
      out.push(indent + '"descKey": "' + descKey + '",');
      count++;
      continue;
    }
    out.push(line);
  }

  if (WRITE) fs.writeFileSync(file, out.join('\n'), 'utf8');
  return count;
}

// ---------- wilderness_map.json ----------
// biome 对象以 "key": "marsh", 开头，名字在 "name"，描述数组在 "descriptions"。
// descKeys 插在 descriptions 之前（JSON 里字段顺序无关，插前面更好定位）。
function patchWilderness() {
  const file = path.join(RES, 'wilderness_map.json');
  const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  const biomeKeys = new Set(parsed.biomes.map(b => b.key));
  const descCount = new Map(parsed.biomes.map(b => [b.key, (b.descriptions || []).length]));
  const names = new Map(parsed.biomes.map(b => [b.key, b.name]));
  const descs = new Map(parsed.biomes.map(b => [b.key, b.descriptions || []]));

  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const out = [];
  let cur = null;
  let count = 0;

  for (const line of lines) {
    let m;
    if ((m = line.match(/^\s*"key":\s*"([A-Za-z0-9_]+)"\s*,\s*$/)) && biomeKeys.has(m[1])) {
      cur = m[1];
      out.push(line);
      continue;
    }
    if (cur && /^\s*"name":/.test(line)) {
      const indent = line.match(/^\s*/)[0];
      out.push(line);
      const nameKey = 'wilderness.biome.' + cur + '.name';
      out.push(indent + '"nameKey": "' + nameKey + '",');
      newKeys[nameKey] = names.get(cur);
      count++;
      continue;
    }
    if (cur && /^\s*"descriptions":\s*\[/.test(line)) {
      const indent = line.match(/^\s*/)[0];
      const n = descCount.get(cur) || 0;
      const keys = [];
      for (let i = 0; i < n; i++) {
        const k = 'wilderness.biome.' + cur + '.desc.' + i;
        keys.push(k);
        newKeys[k] = descs.get(cur)[i];
      }
      out.push(indent + '"descKeys": [' + keys.map(k => '"' + k + '"').join(', ') + '],');
      out.push(line);
      cur = null;
      continue;
    }
    out.push(line);
  }

  if (WRITE) fs.writeFileSync(file, out.join('\n'), 'utf8');
  return count;
}

const realmCount = patchRealm();
const biomeCount = patchWilderness();

console.log('realm 条目: ' + realmCount + '，biome 条目: ' + biomeCount);
console.log('词条数: ' + Object.keys(newKeys).length);

if (!WRITE) {
  console.log('（演练，未写盘；加 --write 生效）');
  Object.entries(newKeys).slice(0, 6).forEach(([k, v]) => console.log('  ' + k + ' = ' + v));
  process.exit(0);
}

let zh = JSON.parse(fs.readFileSync(LANG, 'utf8'));
let added = 0, skipped = 0;
for (const [k, v] of Object.entries(newKeys)) {
  if (k in zh) { skipped++; continue; }
  zh[k] = v;
  added++;
}
const sorted = {};
for (const k of Object.keys(zh).sort()) sorted[k] = zh[k];
fs.writeFileSync(LANG, JSON.stringify(sorted, null, 2) + '\n', 'utf8');
console.log('zh_cn.json: 新增 ' + added + '，跳过已存在 ' + skipped + '，总计 ' + Object.keys(zh).length);
