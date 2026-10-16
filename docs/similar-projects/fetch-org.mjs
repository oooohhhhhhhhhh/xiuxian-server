// Scratch script: save the mudchina organization repo list (extra data source).
import fs from 'node:fs';
import https from 'node:https';
import path from 'node:path';

const DIR = path.join(process.cwd(), 'docs', 'similar-projects');

function get(url) {
  return new Promise((resolve, reject) => {
    https
      .get(url, { headers: { 'User-Agent': 'dsh-research', Accept: 'application/vnd.github+json' } }, (r) => {
        let b = '';
        r.on('data', (c) => (b += c));
        r.on('end', () => resolve({ s: r.statusCode, b }));
      })
      .on('error', reject);
  });
}

const items = [];
for (let page = 1; page <= 3; page++) {
  const r = await get(`https://api.github.com/orgs/mudchina/repos?per_page=100&page=${page}`);
  if (r.s !== 200) {
    console.log('FAIL', r.s, r.b.slice(0, 200));
    break;
  }
  const pageItems = JSON.parse(r.b);
  if (!pageItems.length) break;
  for (const it of pageItems) {
    items.push({
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
    });
  }
}

fs.writeFileSync(
  path.join(DIR, 'mudchina-org.json'),
  JSON.stringify({ meta: { fetchedAt: new Date().toISOString(), org: 'mudchina' }, items }, null, 2),
  'utf8',
);
console.log(`WROTE ${items.length} repos from mudchina`);
