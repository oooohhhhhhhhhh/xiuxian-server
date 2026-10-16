// Scratch script: focused views over merged results.
import fs from 'node:fs';
import path from 'node:path';

const DIR = path.join(process.cwd(), 'docs', 'similar-projects');
const all = JSON.parse(fs.readFileSync(path.join(DIR, 'merged.json'), 'utf8'));

const view = process.argv[2];

function show(list, title) {
  console.log(`\n########## ${title} (${list.length}) ##########`);
  for (const it of list) {
    const desc = (it.description || '').replace(/\s+/g, ' ').slice(0, 150);
    console.log(`${String(it.stars).padStart(5)} | ${(it.language || '-').padEnd(11)} | ${it.full_name.padEnd(44)} | ${desc}`);
  }
}

if (view === 'xiuxian') {
  const terms = ['修仙', '修真', '仙侠', 'xiuxian', 'xianxia', 'cultivation', '洪荒', '仙途', '仙录', '道友', '太乙', '玄幻'];
  const hit = all.filter((it) => {
    const hay = `${it.full_name} ${it.description || ''}`.toLowerCase();
    return terms.some((t) => hay.includes(t.toLowerCase()));
  });
  show(hit, 'XIUXIAN / CULTIVATION MATCHES');
} else if (view === 'mud') {
  const hit = all.filter((it) => {
    const hay = `${it.full_name} ${it.description || ''} ${(it.topics || []).join(' ')}`.toLowerCase();
    if (/(muduo|mudblazor|muddy|mudge)/.test(hay)) return false;
    return /\bmud\b|mud-|mud_|\/mud|mush|moo\b|lpmud|dikumud|circle ?mud|lpmoo/.test(hay);
  });
  show(hit, 'MUD / MOO / MUSH ENGINES, SERVERS & CLIENTS');
} else if (view === 'bots') {
  const hit = all.filter((it) => {
    const hay = `${it.full_name} ${it.description || ''}`.toLowerCase();
    return /nonebot|onebot|koishi|qq机器人|qq bot|群聊|群内/.test(hay);
  });
  show(hit, 'QQ / CHAT-BOT GAME PLUGINS');
} else {
  console.log('usage: node focused.mjs <xiuxian|mud|bots>');
}
