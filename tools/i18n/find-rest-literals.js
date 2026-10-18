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

const seen = new Map();
for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  lines.forEach((line, i) => {
    const re = /(restOk|restError|restErrorKeyed|errorKeyed|okKeyed)\(\s*"([^"]+)"/g;
    let m;
    while ((m = re.exec(line)) !== null) {
      if (!CJK.test(m[2])) continue;
      const k = m[2];
      if (!seen.has(k)) seen.set(k, []);
      seen.get(k).push(rel + ':' + (i + 1));
    }
  });
}

console.log('--- restOk / restError 里剩余的中文第一个实参 ---');
for (const [text, locs] of [...seen.entries()].sort((a, b) => b[1].length - a[1].length)) {
  console.log('(' + locs.length + ') ' + text + '   ← ' + locs.slice(0, 3).join(', '));
}
console.log('合计 ' + seen.size + ' 种 / ' + [...seen.values()].reduce((s, v) => s + v.length, 0) + ' 处');
