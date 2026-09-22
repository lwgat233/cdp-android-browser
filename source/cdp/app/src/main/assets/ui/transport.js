/*
 * transport.js —— 前端与原生之间的唯一一层。
 * 两条通道，界面代码一行不用改：
 *   ① 原生桥（App 内的 WebView 注入对象 cdpNative）
 *   ② 演示模式（桌面浏览器直接打开这个页面时用假数据，方便调界面）
 */
(function () {
  'use strict';
  const P = new Map();
  let seq = 0;

  const native = (typeof window.cdpNative !== 'undefined' && window.cdpNative) ? window.cdpNative : null;
  if (window.cdpNative === undefined) {
    document.addEventListener('DOMContentLoaded', function () {
      document.getElementById('banner').classList.remove('hide');
    });
  }

  // 关键：探测到原生对象就立刻挂 onmessage，否则回包会静默丢失
  if (native) {
    native.onmessage = function (e) {
      let o;
      try { o = JSON.parse(e.data); } catch (err) { return; }
      const id = o.id;
      if (id && P.has(id)) {
        const cb = P.get(id); P.delete(id); cb(o);
      }
    };
  }

  function call(op, args) {
    return new Promise(function (resolve) {
      if (!native) { resolve(demo(op, args)); return; }
      const id = 'q' + (++seq);
      P.set(id, resolve);
      try {
        native.postMessage(JSON.stringify({ id: id, op: op, args: args || {} }));
      } catch (e) {
        P.delete(id);
        resolve({ ok: false, error: '发布失败: ' + e.message });
      }
      setTimeout(function () {
        if (P.has(id)) { P.delete(id); resolve({ ok: false, error: '超时：原生层没有回包' }); }
      }, 25000);
    });
  }

  function demo(op, args) {
    if (op === 'scripts.list') return { ok: true, count: 2, list: [
      { id: 'd1', name: '学习通-下一章（示例）', kind: 'recording', steps: 3, source: '本机录制', updated: Date.now() },
      { id: 'd2', name: '去广告示例', kind: 'userscript', steps: 0, source: 'URL 导入', updated: Date.now() }
    ] };
    if (op === 'status') return { ok: true, app: 'CDP', version: '演示', browser: { url: 'https://example.com/', title: '示例页' }, scripts: 2 };
    if (op === 'http.status') return { ok: true, running: false, port: 8848, bind: '127.0.0.1' };
    if (op === 'log.tail') return { ok: true, lines: ['[演示] 没有原生通道，这是假日志'] };
    return { ok: true, demo: true, op: op, args: args };
  }

  window.CDPT = { call: call, hasNative: !!native };
})();
