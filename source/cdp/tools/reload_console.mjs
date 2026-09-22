#!/usr/bin/env node
// 让控制台页面重载（开发覆盖层推完文件后必须做的一步；页面里已加载的 JS/HTML 不会自己变）
import { execFileSync } from 'node:child_process';
try {
  const out = execFileSync('node', ['tools/uiprobe.mjs', '--timeout', '12000', '--match', 'ui/index.html',
    "(function(){setTimeout(function(){location.reload()},80);return 'reloading'})()"],
    { encoding: 'utf8', timeout: 30000 });
  process.stdout.write(out);
} catch (e) { process.exit(1); }
