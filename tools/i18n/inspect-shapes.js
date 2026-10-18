const fs = require('fs');
const path = require('path');

const file = process.argv[2];
const src = fs.readFileSync(file, 'utf8').split('\n');
const CJK = /[\u4e00-\u9fff]/;

// 找出所有"调用里出现中文字面量"的行，并归类成调用形状
const shapes = new Map();
src.forEach((line, i) => {
  if (!CJK.test(line)) return;
  // 去掉纯注释行
  const trimmed = line.trim();
  if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) return;
  // 抓出所有调用名
  const calls = [...line.matchAll(/([A-Za-z_$][\w$]*)\s*\(/g)].map(m => m[1]);
  const shape = calls.length ? calls.join('>') : '(无调用)';
  if (!shapes.has(shape)) shapes.set(shape, []);
  shapes.get(shape).push((i + 1) + ': ' + trimmed.slice(0, 120));
});

const sorted = [...shapes.entries()].sort((a, b) => b[1].length - a[1].length);
for (const [shape, lines] of sorted) {
  console.log('\n### ' + shape + '  ×' + lines.length);
  lines.slice(0, 3).forEach(l => console.log('   ' + l));
}
