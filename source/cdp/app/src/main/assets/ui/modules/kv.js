/**
 * modules/kv.js —— 键值对渲染器（只管"一条信息怎么摆"）
 *
 * 用户指定的排版（别再改回去）：
 *   · 键和值**不在一行**：键是 11px 灰蓝小字在**上**，值是 15px 亮色大字在**下**；
 *   · 每对之间一条横线（CSS 里 .kv + .kv 的 border-top）；
 *   · 值的颜色比键亮（层次）。
 *
 * 接口：
 *   KV.list(pairs)        pairs = [["时间","2026-…"], …] → 一个 .kv-list 元素（没有有效对时返回 null）
 *   KV.clean(pairs)       过滤掉空值，返回能用的数组
 *   KV.fromRow(row)       行上没有 data-fields 时的兜底：按行内几段文字猜出字段名
 *   KV.toText(pairs)      "键：值" 多行文本（复制/纯文本视图用）
 *
 * 依赖：CDPUI.Fmt.mk
 */
(function () {
  'use strict';
  const CDPUI = (window.CDPUI = window.CDPUI || {});
  const mk = (t, c, x) => CDPUI.Fmt.mk(t, c, x);

  /** 行里那几段文字分别是什么（没写 data-fields 的列表靠这个兜底） */
  const SPAN_LABEL = {
    t: '时间', nm2: '内容', nm: '名称', u: '地址/附注', ckv: '值',
    kind: '类型', lnk: '链接', folder: '文件夹', dom: '分组'
  };

  function clean(pairs) {
    return (pairs || []).filter(function (p) {
      return p && p[1] !== undefined && p[1] !== null && String(p[1]) !== '';
    });
  }

  function fromRow(row) {
    let f = null;
    try { f = JSON.parse(row.getAttribute('data-fields') || 'null'); } catch (e) { f = null; }
    if (f && f.length) return f;
    const parts = [];
    Array.prototype.slice.call(row.children).forEach(function (el) {
      if (el.classList && el.classList.contains('more-cue')) return;
      const cls = (el.classList && el.classList[0]) || '';
      const t = String(el.textContent || '').trim();
      if (!t) return;
      parts.push([SPAN_LABEL[cls] || (el.tagName === 'A' ? '链接' : '信息'), t]);
    });
    return parts;
  }

  function list(pairs) {
    const use = clean(pairs);
    if (!use.length) return null;
    const box = mk('div', 'kv-list');
    use.forEach(function (p) {
      const one = mk('div', 'kv');
      one.appendChild(mk('div', 'kv-k', String(p[0])));   // 键：小字、在上
      one.appendChild(mk('div', 'kv-v', String(p[1])));   // 值：大字、在下
      box.appendChild(one);
    });
    return box;
  }

  function toText(pairs) {
    return clean(pairs).map(function (p) { return String(p[0]) + '：' + String(p[1]); }).join('\n');
  }

  CDPUI.KV = { list, clean, fromRow, toText, SPAN_LABEL };
  CDPUI.__mods = (CDPUI.__mods || []).concat([{
    name: 'kv', file: 'modules/kv.js', version: '1.0',
    api: ['list', 'clean', 'fromRow', 'toText'], dom: true
  }]);
})();
