/**
 * modules/fmt.js —— 小工具模块（纯函数，不碰 DOM 以外的任何东西）
 *
 * 为什么单独一个文件：这些小工具以前散在 app.js 各处，谁都能改、改一处影响一片。
 * 现在统一在这里，**接口固定**：
 *   Fmt.mk(tag, cls, text)  造一个元素
 *   Fmt.time(ts)            时间戳 → "2026-09-20 09:12"
 *   Fmt.host(url)           取主机名（没有协议也能认）
 *   Fmt.bytes(n)            1024 进制的人类可读大小
 *   Fmt.short(s, n)         截断长文本
 *   Fmt.putEmpty(host, msg) 往空列表里放一句提示
 * 依赖：无（可单独跑）
 */
(function () {
  'use strict';
  const CDPUI = (window.CDPUI = window.CDPUI || {});

  function mk(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function time(ts) {
    if (!ts) return '';
    const d = new Date(ts), p = (n) => (n < 10 ? '0' + n : '' + n);
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
      ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  function host(u) {
    try {
      return String(u || '').replace(/^[a-z]+:\/\//i, '').split('/')[0];
    } catch (e) { return ''; }
  }

  function bytes(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / 1048576).toFixed(1) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }

  function short(s, n) {
    s = String(s == null ? '' : s);
    return s.length > (n || 60) ? s.slice(0, n || 60) + '…' : s;
  }

  function putEmpty(hostEl, msg) {
    if (hostEl) hostEl.appendChild(mk('div', 'dim2', msg));
  }

  CDPUI.Fmt = { mk, time, host, bytes, short, putEmpty };
  CDPUI.__mods = (CDPUI.__mods || []).concat([{
    name: 'fmt', file: 'modules/fmt.js', version: '1.0',
    api: ['mk', 'time', 'host', 'bytes', 'short', 'putEmpty'], dom: false
  }]);
})();
