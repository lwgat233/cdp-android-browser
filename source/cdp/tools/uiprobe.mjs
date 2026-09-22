#!/usr/bin/env node
/**
 * tools/uiprobe.mjs —— 专治"读控制台界面状态"这件事的极简 CDP 探针。
 *
 * 为什么不直接用 cdp.mjs：cdp.mjs 的 pickTarget 会对每个候选 target 做一次"验活"探测
 * （为了避开上个进程留下的僵尸 target，这个设计是对的），但放在**自动化脚本里反复调用**时，
 * 偶尔会卡在一次探测上（实测：一轮验收里卡两三次，每次都吃掉几十秒，最后整轮超时）。
 * 这里只做一件事：按 url 找 target → 连上去 → 跑一条表达式 → 打印结果 → 退出，全程硬超时。
 *
 * 用法：
 *   node tools/uiprobe.mjs "document.title"
 *   node tools/uiprobe.mjs --port 9222 --timeout 8000 "JSON.stringify(window.__cdpRegistry().problems)"
 */
const args = process.argv.slice(2);
const get = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const PORT = parseInt(get('--port', '9222'), 10);
const MS = parseInt(get('--timeout', '8000'), 10);
const MATCH = get('--match', 'ui/index.html');
const EXPR = args.filter(a => !a.startsWith('--') && args[args.indexOf(a) - 1] !== '--port'
    && args[args.indexOf(a) - 1] !== '--timeout' && args[args.indexOf(a) - 1] !== '--match').join(' ');

const bail = (msg, code) => { console.error(String(msg).slice(0, 300)); process.exit(code || 1); };

(async () => {
  if (!EXPR) bail('用法：uiprobe.mjs [--port 9222] [--timeout 8000] "表达式"', 2);
  const ctl = new AbortController();
  const t0 = setTimeout(() => ctl.abort(), MS);
  let list;
  try {
    const r = await fetch(`http://127.0.0.1:${PORT}/json/list`, { signal: ctl.signal });
    list = await r.json();
  } catch (e) { clearTimeout(t0); bail('取 target 列表失败: ' + e.message, 3); }
  const t = (list || []).find(x => (x.url || '').indexOf(MATCH) >= 0);
  if (!t) { clearTimeout(t0); bail('没找到 target（url 含 ' + MATCH + '）', 4); }

  const ws = new WebSocket(t.webSocketDebuggerUrl);
  const done = (out, code) => { try { ws.close(); } catch (e) {} clearTimeout(t0); console.log(out); process.exit(code || 0); };
  const killer = setTimeout(() => { console.error('探针超时 ' + MS + 'ms'); process.exit(5); }, MS);
  ws.onopen = () => {
    ws.send(JSON.stringify({
      id: 1, method: 'Runtime.evaluate',
      params: { expression: EXPR, returnByValue: true, awaitPromise: false }
    }));
  };
  ws.onmessage = (ev) => {
    let m; try { m = JSON.parse(String(ev.data)); } catch (e) { return; }
    if (m.id !== 1) return;
    clearTimeout(killer);
    if (m.error) return done('ERR ' + JSON.stringify(m.error), 6);
    const r = m.result || {};
    if (r.exceptionDetails) return done('EXC ' + JSON.stringify(r.exceptionDetails).slice(0, 300), 7);
    const v = r.result || {};
    const out = (v.type === 'object' && v.value !== undefined) ? JSON.stringify(v.value)
      : (v.value !== undefined ? String(v.value) : (v.description || '(no value)'));
    done(out, 0);
  };
  ws.onerror = (e) => { clearTimeout(killer); bail('连接失败: ' + (e && e.message), 8); };
})();
