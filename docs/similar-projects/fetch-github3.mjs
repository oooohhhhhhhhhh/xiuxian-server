// Scratch script: round 3 — targeted queries for Chinese cultivation/MUD game projects.
import fs from 'node:fs';
import https from 'node:https';
import path from 'node:path';

const QUERIES = [
  '修仙 in:name',
  '仙侠 in:name,description',
  'nonebot 修仙',
  '修仙 机器人',
  'cultivation in:name,description',
  'mud in:name language:Java',
  'wuxia in:name,description',
  '放置修仙 in:name,description',
  'text-based mmorpg in:name,description',
];

const DIR = path.join(process.cwd(), 'docs', 'similar-projects');
const OUT = path.join(DIR, 'raw-search3.json');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function httpsGet(url) {
  return new Promise((resolve, reject) => {
    https
      .get(url, { headers: { 'User-Agent': 'dsh-research', Accept: 'application/vnd.github+json' } }, (r) => {
        let body = '';
        r.on('data', (c) => (body += c));
        r.on('end', () => resolve({ status: r.statusCode, body }));
      })
      .on('error', reject);
  });
}

const results = {};
for (let i = 0; i < QUERIES.length; i++) {
  const q = QUERIES[i];
  const url =
    'https://api.github.com/search/repositories?q=' +
    encodeURIComponent(q) +
    '&sort=stars&order=desc&per_page=50';
  for (let attempt = 1; ; attempt++) {
    const res = await httpsGet(url);
    if (res.status === 200) {
      const json = JSON.parse(res.body);
      results[q] = (json.items || []).map((it) => ({
        full_name: it.full_name,
        html_url: it.html_url,
        description: it.description,
        stars: it.stargazers_count,
        forks: it.forks_count,
        language: it.language,
        topics: it.topics || [],
        license: it.license ? it.license.spdx_id : null,
        archived: it.archived,
        pushed_at: it.pushed_at,
        created_at: it.created_at,
        homepage: it.homepage,
        open_issues: it.open_issues_count,
        size_kb: it.size,
      }));
      console.log(`OK   ${q} -> ${results[q].length} (total ${json.total_count})`);
      break;
    }
    if (attempt > 4) {
      console.log(`FAIL ${q} status=${res.status} ${res.body.slice(0, 160)}`);
      break;
    }
    console.log(`WAIT ${q} status=${res.status}`);
    await sleep(30000);
  }
  if (i < QUERIES.length - 1) await sleep(7500);
}

fs.writeFileSync(OUT, JSON.stringify({ meta: { fetchedAt: new Date().toISOString(), queries: QUERIES }, results }, null, 2), 'utf8');
console.log(`WROTE ${OUT}`);
