// Scratch script: search GitHub for projects similar to a text-based xiuxian (cultivation) MUD server.
// Run with: node --use-system-ca docs/similar-projects/fetch-github.mjs
import fs from 'node:fs';
import path from 'node:path';

const QUERIES = [
  '修仙',
  'xiuxian',
  '修真',
  '仙侠',
  '文字修仙',
  '文字游戏 mud',
  'cultivation game',
  'xianxia',
  'topic:mud',
];

const OUT = path.join(process.cwd(), 'docs', 'similar-projects', 'raw-search.json');

function get(url) {
  return new Promise((resolve, reject) => {
    httpsGet(url, resolve, reject);
  });
}

import https from 'node:https';

function httpsGet(url, resolve, reject) {
  https
    .get(url, { headers: { 'User-Agent': 'dsh-research', Accept: 'application/vnd.github+json' } }, (r) => {
      let body = '';
      r.on('data', (c) => (body += c));
      r.on('end', () => {
        resolve({ status: r.statusCode, headers: r.headers, body });
      });
    })
    .on('error', reject);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = {};
const meta = { fetchedAt: new Date().toISOString(), queries: QUERIES, errors: [] };

for (let i = 0; i < QUERIES.length; i++) {
  const q = QUERIES[i];
  const url =
    'https://api.github.com/search/repositories?q=' +
    encodeURIComponent(q) +
    '&sort=stars&order=desc&per_page=50';
  let attempt = 0;
  for (;;) {
    attempt++;
    const res = await get(url);
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
      console.log(`OK   ${q} -> ${results[q].length} items (total ${json.total_count})`);
      break;
    }
    if (attempt > 4) {
      meta.errors.push({ q, status: res.status, body: res.body.slice(0, 300) });
      console.log(`FAIL ${q} status=${res.status} ${res.body.slice(0, 160)}`);
      break;
    }
    console.log(`WAIT ${q} status=${res.status} attempt=${attempt}`);
    await sleep(30000);
  }
  // search API: 10 requests/minute for unauthenticated clients
  if (i < QUERIES.length - 1) await sleep(7500);
}

fs.writeFileSync(OUT, JSON.stringify({ meta, results }, null, 2), 'utf8');
console.log(`WROTE ${OUT}`);
