const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '../..');
const CJK = /[\u4e00-\u9fff]/;
// 只圈定"玩家可见的消息出口"：指令注册元数据（命令名、别名、帮助标题）
// 必须留在代码里，不算未完成项。
const EXIT = /reply\(|replyPrivate\(|createResult\(|put\("message"|addProperty\("message"|restOk\(|restError\(/;

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, e.name);
    if (e.isDirectory()) walk(full);
    else if (e.name.endsWith('.java')) files.push(full);
  }
})(path.join(ROOT, 'src/main/java'));

let n = 0;
for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  fs.readFileSync(file, 'utf8').split(/\r?\n/).forEach((line, i) => {
    if (!CJK.test(line) || !EXIT.test(line)) return;
    n++;
    console.log(rel + ':' + (i + 1) + '  ' + line.trim().slice(0, 110));
  });
}
console.log('\n消息出口里剩余的中文: ' + n + ' 处');
