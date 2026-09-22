#!/usr/bin/env node
/**
 * tools/cdp.mjs —— 零依赖 CDP 探针（Node 22+ 自带 WebSocket）
 *
 * 用途：从电脑侧连上 App 里 debug 版 WebView 的 devtools socket，读/驱动**真实进程内**的页面。
 *   adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
 *
 * 为什么要做「验活」：WebView 的 devtools 列表里会残留**上一个进程留下的僵尸 target**
 * （页面 URL 和标题都还在），按 URL 匹配很容易选中它们 —— 现象是「导航没有任何反应」，
 * 而且看起来像是被测应用坏了。所以这里的 pickTarget 按 URL 匹配出候选后，逐个连接并
 * 发一条 evaluate 探针，只认真正回话的那个。
 *
 * 用法：
 *   node tools/cdp.mjs list
 *   node tools/cdp.mjs eval --target harness --expr "document.title"
 *   node tools/cdp.mjs file --target harness --file probe.js
 *   node tools/cdp.mjs nav  --target harness --url "https://..."
 *   node tools/cdp.mjs tap  --target harness --x 120 --y 300      # 真实 tap（pointerdown→up→click）
 *   node tools/cdp.mjs mouse --target harness --x 120 --y 300     # 完整鼠标事件序列
 *   node tools/cdp.mjs key  --target harness --key Enter --code Enter --vk 13
 *   node tools/cdp.mjs listeners --target ui/index.html [--selector "button,select,input"]
 *       死键扫描：枚举控件并查监听器（含事件委托的祖先），listeners=0 的就是"点了不会响"的死键。
 */
const args = process.argv.slice(2);
const get = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const PORT = parseInt(get('--port', '9222'), 10);
const TIMEOUT = parseInt(get('--timeout', '25000'), 10);
const TARGET = get('--target', null);
const CMD = args[0];

let seq = 0;
const pending = new Map();
let ws = null;

function wire(sock) {
  sock.onmessage = (ev) => {
    const s = (ev && ev.data !== undefined) ? String(ev.data) : String(ev);
    let m; try { m = JSON.parse(s); } catch (e) { return; }
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id); pending.delete(m.id);
      m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
    }
  };
}

function send(method, params = {}, timeout = TIMEOUT) {
  return new Promise((res, rej) => {
    const id = ++seq;
    pending.set(id, { res, rej });
    ws.send(JSON.stringify({ id, method, params }));
    setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('CDP 超时: ' + method)); } }, timeout);
  });
}

const listTargets = async () => (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json());

/**
 * 连接 + 验活 + 优先选「可见」的那个 target。
 *
 * 为什么要这样：安装/重启的时序会在同一个 App 进程里留下**隐藏的 WebView**（同样 URL、同样标题），
 * 按 URL 匹配很容易选中隐藏的那个 —— 现象是「导航/点击都没反应」，看起来像被测应用坏了。
 * 真实用户看得到的那个页面，document.visibilityState 是 visible，用它当第一优先级最稳。
 */
async function connectLive(cands) {
  const errors = [];
  let fallback = null;
  for (const t of cands) {
    let sock;
    try {
      sock = new WebSocket(t.webSocketDebuggerUrl);
      await new Promise((res, rej) => {
        const to = setTimeout(() => rej(new Error('连接超时')), 4000);
        sock.onopen = () => { clearTimeout(to); res(); };
        sock.onerror = () => { clearTimeout(to); rej(new Error('连接被拒')); };
      });
      const probe = await new Promise((res) => {
        const to = setTimeout(() => res(null), 9000);
        sock.onmessage = (ev) => {
          let m; try { m = JSON.parse(String(ev.data)); } catch (e) { return; }
          if (m.id === 999999) { clearTimeout(to); res(m); }
        };
        sock.send(JSON.stringify({
          id: 999999, method: 'Runtime.evaluate',
          params: {
            expression: "JSON.stringify({v:(function(){try{return document.visibilityState}catch(e){return '?'}})(),t:1+1})",
            returnByValue: true
          }
        }));
      });
      let info = null;
      try { info = JSON.parse(probe.result.result.value); } catch (e) {}
      if (info && info.t === 2) {
        if (info.v === 'visible') { ws = sock; wire(sock); return t; }
        if (!fallback) fallback = { sock, t };
        try { sock.close(); } catch (e) {}
        errors.push(`${t.url} → 隐藏（不优先）`);
        continue;
      }
      errors.push(`${t.url} → 不回话（僵尸 target）`);
      try { sock.close(); } catch (e) {}
    } catch (e) {
      errors.push(`${t.url} → ${e.message}`);
      try { sock && sock.close(); } catch (e2) {}
    }
  }
  if (fallback) { ws = fallback.sock; wire(fallback.sock); return fallback.t; }
  throw new Error('没有活着的 target：\n    ' + errors.join('\n    '));
}

(async () => {
  if (!CMD) { console.error('用法见文件头注释'); process.exit(2); }

  if (CMD === 'list') {
    const all = await listTargets();
    for (const t of all) {
      let live = false;
      try { await connectLive([t]); live = true; } catch (e) {}
      console.log(`[${t.type}] ${live ? '活 ' : '僵尸'} ${t.title}\n    ${t.url}\n    ${t.webSocketDebuggerUrl}`);
      ws = null;
    }
    return;
  }

  const all = await listTargets();
  let cands = all.filter((t) => t.type === 'page');
  if (TARGET) cands = cands.filter((t) => (t.url || '').includes(TARGET) || (t.title || '').includes(TARGET));
  if (!cands.length) {
    console.error(`没有匹配 "${TARGET}" 的 target。现有：`);
    for (const t of all) console.error(`  [${t.type}] ${t.url}  (${t.title})`);
    process.exit(3);
  }
  const target = await connectLive(cands);
  console.error(`[cdp] target: ${target.title}  url=${target.url}`);
  await send('Runtime.enable', {}, 8000).catch(() => {});

  const x = parseFloat(get('--x', '0'));
  const y = parseFloat(get('--y', '0'));

  if (CMD === 'eval') {
    const v = await evaluate(args[args.indexOf('--expr') + 1]);
    console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 1));
  } else if (CMD === 'file') {
    const fs = await import('node:fs');
    const v = await evaluate(fs.readFileSync(get('--file'), 'utf8'));
    console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 1));
  } else if (CMD === 'nav') {
    await send('Page.navigate', { url: get('--url') });
    console.log('navigated');
  } else if (CMD === 'tap') {
    const r = await send('Input.synthesizeTapGesture', { x, y, duration: 60, tapCount: 1, gestureSourceType: 'touch' });
    console.log(JSON.stringify(r || { ok: true }));
  } else if (CMD === 'mouse') {
    const base = { x, y, button: 'left', buttons: 1, clickCount: 1 };
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, buttons: 0 });
    await send('Input.dispatchMouseEvent', Object.assign({ type: 'mousePressed' }, base));
    await send('Input.dispatchMouseEvent', Object.assign({ type: 'mouseReleased' }, base, { buttons: 0 }));
    console.log('mouse seq sent');
  } else if (CMD === 'listeners') {
    // 死键扫描：枚举页面里的按钮/下拉框，查它们（或它们的祖先，用于事件委托）有没有监听器。
    // 没有任何监听器 = 点了不会响的死键 —— 这是用户反复踩的那类问题，做成可自动发现的检查。
    const sel = get('--selector', 'button,[role=button],a[href],a[data-url],[data-url],select,input,textarea');
    const setup = await send('Runtime.evaluate', {
      expression: `window.__cdpScan = Array.from(document.querySelectorAll(${JSON.stringify(sel)}))`,
      objectGroup: 'cdpscan',
    });
    const arrId = setup.result && setup.result.objectId;
    if (!arrId) { console.error('选择器没匹配到元素: ' + sel); process.exit(4); }
    // 只要数组自己的数字下标（ownProperties=false 会把原型上的方法也带出来，
    // 那些东西不是元素 —— 会变成一堆 "|undefined||" 的假死键）
    const props = await send('Runtime.getProperties', { objectId: arrId, ownProperties: true });
    const ids = (props.result || [])
      .filter((p) => /^\d+$/.test(p.name) && p.value && p.value.objectId)
      .map((p) => p.value.objectId);
    const rows = [];
    const FIELD = /^(INPUT|SELECT|TEXTAREA)$/;
    for (const oid of ids.slice(0, 300)) {
      // 注意：只能用 this 取元素本身。曾经写成 (function(){ ... this ... })() ，
      // 立即执行函数里的 this 不是元素 —— 祖先永远返回 null，导致所有靠事件委托的
      // 按钮被误报成死键（"验过了"但验的方法是坏的）。
      const info = await send('Runtime.callFunctionOn', {
        objectId: oid,
        functionDeclaration: `function(){
          var el = this;
          try { return (el.id || '') + '|' + el.tagName + '|' + (el.textContent || '').trim().slice(0, 16) + '|' + (el.type || ''); } catch (e) { return '?'; }
        }`,
        returnByValue: true,
      });
      const label = (info.result && info.result.value) || '';
      const tag = (label.split('|')[1] || '').toUpperCase();
      const own = await send('DOMDebugger.getEventListeners', { objectId: oid }).catch(() => ({ listeners: [] }));
      let n = ((own && own.listeners) || []).length;
      let via = n > 0 ? 'self' : '';
      if (n === 0) {
        // 事件委托：往上听 1~3 层（this 必须是元素本身，不能包 IIFE）
        for (let k = 1; k <= 3 && n === 0; k++) {
          const anc = await send('Runtime.callFunctionOn', {
            objectId: oid,
            functionDeclaration: `function(){var e=this;for(var i=0;i<${k};i++){e=e.parentElement;if(!e)return null;}return e;}`,
          }).catch(() => null);
          const aid = anc && anc.result && anc.result.objectId;
          if (!aid) break;
          const al = await send('DOMDebugger.getEventListeners', { objectId: aid }).catch(() => ({ listeners: [] }));
          const m = ((al && al.listeners) || []).length;
          if (m > 0) { n = m; via = 'parent+' + k; }
        }
      }
      rows.push({ label, kind: FIELD.test(tag) ? 'field' : 'click', listeners: n, via: via || 'none' });
    }
    const dead = rows.filter((r) => r.kind === 'click' && r.listeners === 0);
    const fields = rows.filter((r) => r.kind === 'field' && r.listeners === 0);
    console.log(JSON.stringify({
      selector: sel, total: rows.length, dead: dead.length,
      deadKeys: dead.map((d) => d.label),
      fieldsWithoutListener: fields.map((f) => f.label),
      rows,
    }, null, 1));
    await send('Runtime.releaseObjectGroup', { objectGroup: 'cdpscan' }).catch(() => {});
  } else if (CMD === 'key') {
    const k = get('--key', 'Enter'), code = get('--code', k), vk = parseInt(get('--vk', '13'), 10);
    await send('Input.dispatchKeyEvent', { type: 'keyDown', key: k, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk });
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk });
    console.log('key sent');
  } else {
    console.error('未知命令 ' + CMD);
    process.exit(2);
  }

  async function evaluate(expr) {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true, userGesture: true });
    if (r.exceptionDetails) throw new Error('页面异常: ' + JSON.stringify(r.exceptionDetails.exception || r.exceptionDetails));
    return r.result && r.result.value;
  }

  try { ws.close(); } catch (e) {}
})().catch((e) => { console.error('失败: ' + e.message); process.exit(1); });
