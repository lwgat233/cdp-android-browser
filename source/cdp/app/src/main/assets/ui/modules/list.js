/**
 * modules/list.js —— 列表行渲染器（"信息型列表长什么样、点了干什么"只在这里决定）
 *
 * 用户的规矩（照它落，别再散回各个渲染函数里）：
 *   · 信息型列表（网络 / Cookie / 嗅探 / 下载 / 历史 / 书签 / 阅读 …）**行里不摆按键**；
 *   · **点这一行**（或长按 450ms）就弹小窗，先把这条的信息按「字段：值」摆出来；
 *   · 动作（下载 / 播放 / 投屏 / 删除 / 复制…）排在信息下面。
 *
 * 怎么用（渲染函数只管塞数据，不管长相）：
 *   row.setAttribute('data-title', '第 3 章');
 *   row.setAttribute('data-fields', JSON.stringify([['时间','…'],['地址','…']]));
 *   host.appendChild(row);
 *   // 行里原有的 <button> 不用改：这里会把它们**搬进小窗**当动作，行面只留摘要 + ⋯
 *
 * 接口：
 *   List.compact(row)      按上面的规矩处理一行（幂等：data-sheeted 打标）
 *   List.compactAll()      处理当前页面上所有行
 *   List.watch()           挂 MutationObserver，新出现的行自动接管
 *   List.scan()            验收入口：每行有没有可见按键 / 能不能点开 / 有没有字段
 *
 * 依赖：CDPUI.Fmt、CDPUI.KV、CDPUI.Modal
 */
(function () {
  'use strict';
  const CDPUI = (window.CDPUI = window.CDPUI || {});
  const mk = (t, c, x) => CDPUI.Fmt.mk(t, c, x);
  const MARK = 'list.js';

  function compact(row) {
    if (!row || row.getAttribute('data-sheeted') === '1') return;
    // 步骤行是**交互行**（自带 ▲ ▼ ⋯，点行就是改这一步），不归"信息行"那套管：
    // 以前这里把它当信息行，把 ▲▼⋯ 全搬进小窗 → 用户看到"点了没什么可改的"
    if (row.getAttribute('data-kind')) return;   // step（步骤行）/ add（列表底部加号）都是交互行，不接管
    row.setAttribute('data-sheeted', '1');
    row.setAttribute('data-renderer', MARK);

    // 行里原有的按键：**搬走收藏**（保留它们的点击功能），行面不给按键。
    // 例外（用户 2026-09-21）：带 `data-row-keep` / `data-state-key` 的键**留在行上**：
    //   · 状态键（‖ / ▸）—— 一眼看到"在不在下、能不能停"，它描述状态，不该藏进小窗；
    //   · ✕ 删除 —— 用户明确要求"下载后面应该有 ✕"，也留在行上。
    const btns = Array.prototype.slice.call(row.querySelectorAll('button')).filter(function (b) {
      return !b.hasAttribute('data-state-key') && !b.hasAttribute('data-row-keep');
    });
    btns.forEach(function (b) { b.remove(); });
    // 行上不加任何提示符（用户要求：信息行不要出现 ⋯ 这种"可展开"标记，动作都在点开后的小窗里）

    const titleOf = function () {
      const t = row.getAttribute('data-title');
      if (t) return t;
      const el = row.querySelector('.nm2, .nm, .ckn, .lnk, .kind, .t');
      return (((el ? String(el.textContent || '').trim() : '') || '这一条')).slice(0, 60);
    };

    const openIt = function (e) {
      if (e && e.stopPropagation) e.stopPropagation();
      const seen = {};
      const acts = [];
      btns.forEach(function (b) {
        const label = String(b.textContent || '操作').trim();
        if (!label || seen[label]) return;
        seen[label] = 1;
        // 关键：把**这个真元素**一起交出去（modal.js 会直接把它摆进小窗）。
        // 以前只交一个"点它会转发一次"的闭包，实测在小窗里会静默失效。
        if (!b.getAttribute('data-fn')) b.setAttribute('data-fn', 'row-action');
        b.setAttribute('data-action', label);
        // 只给元素与文案，**不再给 run/fn**：动作由这个真元素的原始监听完成（不重复执行），
        // 小窗那边只负责"点完把它收起"。
        acts.push({
          el: b,
          label: label, t: label, danger: /删|清空|清除/.test(label)
        });
      });
      // 用户要求：**不要"复制"这个按键** —— 值都能长按选中（CSS 里已开 user-select）,
      // 系统自带的复制菜单就够了。少一个键，小窗也更干净。
      const pairs = pairsOf();
      const summary = String(row.textContent || '').replace(/\s*⋯\s*$/, '').trim();
      const board = row.closest('[data-board]');
      const boardName = board ? (board.getAttribute('data-board') || '') : '';
      // 有关键值对就按「键小字在上 / 值大字在下 / 横线分隔」排；没有就退回纯文本摘要
      CDPUI.Modal.show({
        title: titleOf(),
        text: pairs.length ? '' : ((boardName ? '来自板块：' + boardName + '\n' : '') + '摘要：' + summary),
        pairs: pairs.length ? pairs : null,
        buttons: acts
      });
    };
    const pairsOf = function () {
      const f = CDPUI.KV.fromRow(row);
      return CDPUI.KV.clean(f).map(function (p) { return [String(p[0]), String(p[1])]; });
    };

    row.addEventListener('click', openIt);
    let lp = null;
    row.addEventListener('touchstart', function (e) { lp = setTimeout(function () { openIt(e); }, 450); }, { passive: true });
    ['touchend', 'touchmove', 'touchcancel'].forEach(function (ev) {
      row.addEventListener(ev, function () { if (lp) { clearTimeout(lp); lp = null; } }, { passive: true });
    });
    row.addEventListener('contextmenu', function (e) { e.preventDefault(); openIt(e); });
  }

  function compactAll() {
    Array.prototype.slice.call(document.querySelectorAll('.listbox .row-item')).forEach(compact);
  }

  function watch() {
    try {
      new MutationObserver(function () { compactAll(); }).observe(document.body, { childList: true, subtree: true });
    } catch (e) {}
    compactAll();
  }

  /** 验收入口：行里有没有可见按键 / 能不能点开 / 有没有字段 / 谁渲染的 */
  function scan() {
    return Array.prototype.slice.call(document.querySelectorAll('.listbox .row-item')).map(function (r) {
      const btns = Array.prototype.slice.call(r.querySelectorAll('button')).filter(function (b) {
        return b.offsetParent !== null;
      });
      const board = r.closest('[data-board]');
      return {
        board: board ? (board.getAttribute('data-board') || '') : '',
        text: String(r.textContent || '').trim().slice(0, 46),
        visibleButtons: btns.map(function (b) { return String(b.textContent || '').trim(); }),
        hasFields: CDPUI.KV.fromRow(r).length > 0,
        clickable: r.getAttribute('data-sheeted') === '1',
        kind: r.getAttribute('data-kind') || '',      // step / add = 交互行（不归 list.js 管）
        renderer: r.getAttribute('data-renderer') || ''
      };
    });
  }

  CDPUI.List = { compact, compactAll, watch, scan };
  CDPUI.__mods = (CDPUI.__mods || []).concat([{
    name: 'list', file: 'modules/list.js', version: '1.0',
    api: ['compact', 'compactAll', 'watch', 'scan'], dom: true
  }]);
})();
