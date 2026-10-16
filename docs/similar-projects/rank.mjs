// Scratch script: merge rounds, dedupe, rank by stars, optionally filter to relevant candidates.
import fs from 'node:fs';
import path from 'node:path';

const DIR = path.join(process.cwd(), 'docs', 'similar-projects');
const files = ['raw-search.json', 'raw-search2.json', 'raw-search3.json'];

const byName = new Map();
for (const f of files) {
  const raw = JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8'));
  for (const [q, items] of Object.entries(raw.results)) {
    for (const it of items) {
      if (!byName.has(it.full_name)) byName.set(it.full_name, { ...it, matched: [] });
      const rec = byName.get(it.full_name);
      if (!rec.matched.includes(q)) rec.matched.push(q);
    }
  }
}

const all = [...byName.values()].sort((a, b) => b.stars - a.stars);
fs.writeFileSync(path.join(DIR, 'merged.json'), JSON.stringify(all, null, 2), 'utf8');

const KEYWORDS = [
  '修仙', '修真', '仙侠', '玄幻', '洪荒', 'xiuxian', 'xianxia', 'cultivation',
  '文字游戏', '文字冒险', '文字 mud', 'mud', '江湖', '武侠', 'wuxia', '放置',
  'immortal', 'text-based', 'text based', 'mmorpg', '群聊', 'qq', 'onebot', 'nonebot',
];

const NOISE = ['muduo', 'muddy', 'mudge', 'common', 'cimumud', 'hcmud'];

const relevant = all.filter((it) => {
  const hay = `${it.full_name} ${it.description || ''} ${(it.topics || []).join(' ')}`.toLowerCase();
  if (NOISE.some((n) => it.full_name.toLowerCase().includes(n))) return false;
  return KEYWORDS.some((k) => hay.includes(k.toLowerCase()));
});

fs.writeFileSync(path.join(DIR, 'candidates.json'), JSON.stringify(relevant, null, 2), 'utf8');

const mode = process.argv[2] || 'all';
if (mode === 'cand') {
  const limit = Number(process.argv[3] || 80);
  const from = Number(process.argv[4] || 0);
  for (const it of relevant.slice(from, from + limit)) {
    const desc = (it.description || '').replace(/\s+/g, ' ').slice(0, 120);
    console.log(
      [String(it.stars).padStart(5), (it.language || '-').padEnd(11), it.full_name.padEnd(46), desc].join(' | '),
    );
  }
  console.log(`\nRELEVANT: ${relevant.length} / TOTAL ${all.length}`);
} else {
  const limit = Number(process.argv[3] || 120);
  const from = Number(process.argv[4] || 0);
  for (const it of all.slice(from, from + limit)) {
    const desc = (it.description || '').replace(/\s+/g, ' ').slice(0, 120);
    console.log(
      [String(it.stars).padStart(5), (it.language || '-').padEnd(11), it.full_name.padEnd(46), desc].join(' | '),
    );
  }
  console.log(`\nTOTAL UNIQUE: ${all.length}`);
}
