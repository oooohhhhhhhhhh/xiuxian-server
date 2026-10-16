// Scratch script: diff round 4 against rounds 1-3, and merge everything.
import fs from 'node:fs';
import path from 'node:path';

const DIR = path.join(process.cwd(), 'docs', 'similar-projects');
const load = (f) => JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8'));

const byName = new Map();
for (const f of ['raw-search.json', 'raw-search2.json', 'raw-search3.json', 'raw-search4.json']) {
  const raw = load(f);
  for (const [q, items] of Object.entries(raw.results)) {
    for (const it of items) {
      if (!byName.has(it.full_name)) byName.set(it.full_name, { ...it, matched: [] });
      byName.get(it.full_name).matched.push(q);
    }
  }
}
const all = [...byName.values()].sort((a, b) => b.stars - a.stars);
fs.writeFileSync(path.join(DIR, 'merged.json'), JSON.stringify(all, null, 2), 'utf8');

const old = new Set();
for (const f of ['raw-search.json', 'raw-search2.json', 'raw-search3.json']) {
  for (const items of Object.values(load(f).results)) for (const it of items) old.add(it.full_name);
}

const fresh = all.filter((it) => !old.has(it.full_name) && it.stars >= 3);
console.log(`NEW FROM ROUND 4 (stars>=3): ${fresh.length}\n`);
for (const it of fresh) {
  const desc = (it.description || '').replace(/\s+/g, ' ').slice(0, 120);
  console.log(`${String(it.stars).padStart(5)} | ${(it.language || '-').padEnd(11)} | ${it.full_name.padEnd(44)} | ${desc}`);
}
