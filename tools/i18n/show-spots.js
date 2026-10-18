const fs = require('fs');
const path = require('path');

const spots = [
  ['src/main/java/com/mtxgdn/game/service/FormationService.java', 166],
  ['src/main/java/com/mtxgdn/game/service/FormationService.java', 320],
  ['src/main/java/com/mtxgdn/game/service/EconomyService.java', 892],
  ['src/main/java/com/mtxgdn/game/service/FarmService.java', 500],
  ['src/main/java/com/mtxgdn/game/service/ItemService.java', 586],
  ['src/main/java/com/mtxgdn/onebot/command/formation/FormationCommand.java', 119],
  ['src/main/java/com/mtxgdn/rest/InstallResource.java', 171],
  ['src/main/java/com/mtxgdn/rest/GameResource.java', 216],
];
for (const [rel, ln] of spots) {
  console.log('--- ' + rel.split('/').pop() + ':' + ln + ' ---');
  const lines = fs.readFileSync(rel, 'utf8').split(/\r?\n/);
  for (let i = ln - 2; i <= ln + 3 && i < lines.length; i++) {
    if (i < 0) continue;
    const t = lines[i].trim();
    if (t) console.log('  ' + (i + 1) + '| ' + t);
  }
}
