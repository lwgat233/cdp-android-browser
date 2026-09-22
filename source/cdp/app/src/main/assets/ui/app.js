/*
 * app.js —— 控制台界面逻辑。
 *
 * 只跟 window.CDPT.call(op, args) 打交道，不碰任何原生细节；
 * 原生侧能调的 op 全部走同一条分发（跟 HTTP 控制口是同一套），
 * 所以这里验过的行为等于电脑上 curl 也验过。
 */
(function () {
  'use strict';
  window.__cdpAppVer = '2026-09-20-实时统计-10';   // 一眼看出设备跑的是哪一版 app.js
  const $ = (id) => document.getElementById(id);
  const call = (op, args) => window.CDPT.call(op, args);
  const txt = (el, s) => { if (el) el.textContent = s; };
  const j = (o) => JSON.stringify(o, null, 1);
  /**
   * 原生推过来的东西要按"字符串也可能是 JSON"处理。
   *
   * 踩过的坑（回归脚本抓到的真缺陷）：原生侧用的是
   *   evaluateJavascript("window.__cdpPush(" + JSONObject.quote(o.toString()) + ")")
   * —— JSONObject.quote 已经把整段 JSON **变成一个 JS 字符串字面量**，
   * 所以 JS 这边收到的 o 是**字符串**，`o.t` 永远是 undefined：
   * 拾取回灌、页面状态推送这些全都静默不生效（现象是"拾取了但框里空的 / 状态行一直 -"），
   * 而两边都不报错，最难查。这里统一在入口把字符串解析回对象。
   */
  function asObj(x) {
    if (x == null) return null;
    if (typeof x === 'string') { try { return JSON.parse(x); } catch (e) { return null; } }
    return x;
  }

  // ---------------------------------------------------------------- 栏目（竖排抽屉，两级：集合 ▸ 栏目）
  // 登记表（registry.js）是唯一数据源：抽屉由它生成（不手写一排按钮），板块归属也由它决定。
  const R = window.CDP_REGISTRY;
  const TABS = R.TABS.map(function (t) { return t.key; });
  const MISSING = [];                 // 用到了但页面上没有的 id（启动时报警）
  /** 注册监听：元素不在就记一笔，不让 TypeError 把后面所有注册一起打断（踩过：一片死键） */
  function bindMaybe(id, ev, fn) {
    const el = $(id);
    if (!el) { if (MISSING.indexOf(id) < 0) MISSING.push(id); return null; }
    el.addEventListener(ev, fn);
    return el;
  }

  // ---------------------------------------------------------------- 板块登记表（统一管理）
  // 用户的要求：界面板块要有**名字**、写在**统一的地方**，一个板块只挂在**一个栏目的一块位置**；
  // 出问题时能直接说"哪个板块渲染不对"，我去复盘那一个 div，而不是满屏找。
  // 做法：HTML 里每个卡片都带 data-board（名字）/ data-tab（归属栏目）/ data-order（顺序），
  // 启动时由这里统一按登记挂载（重复 id、栏目写错都会在控制台里报出来）。
  const BOARD_REPORT = [];
  function mountBoards() {
    const boards = Array.prototype.slice.call(document.querySelectorAll('[data-board]'));
    const seen = {};
    boards.forEach(function (el) {
      const id = el.getAttribute('data-board');
      const tab = el.getAttribute('data-tab');
      const order = parseInt(el.getAttribute('data-order') || '0', 10);
      if (seen[id]) {
        BOARD_REPORT.push({ board: id, problem: '板块名重复（只能出现一次）', tab: tab });
        return;
      }
      seen[id] = true;
      const sec = document.getElementById('tab-' + tab);
      if (!sec) {
        BOARD_REPORT.push({ board: id, problem: '找不到该栏目：tab-' + tab, tab: tab });
        return;
      }
      // 按 order 插到该栏目里（同一栏目内的位置由 order 决定）
      let placed = false;
      const kids = Array.prototype.slice.call(sec.children)
        .filter(function (c) { return c.hasAttribute && c.hasAttribute('data-board'); });
      for (let i = 0; i < kids.length; i++) {
        const ko = parseInt(kids[i].getAttribute('data-order') || '0', 10);
        if (order < ko) { sec.insertBefore(el, kids[i]); placed = true; break; }
      }
      if (!placed) sec.appendChild(el);
      el.setAttribute('data-mounted', '1');
    });
    if (BOARD_REPORT.length) {
      try { console.warn('[板块登记表] 有问题：', BOARD_REPORT); } catch (e) {}
    }
    return BOARD_REPORT;
  }
  /** 诊断入口：列出每个板块落在哪个栏目、第几位、当前尺寸、有没有挂上 */
  window.__cdpBoards = function () {
    return Array.prototype.slice.call(document.querySelectorAll('[data-board]')).map(function (el) {
      const sec = el.closest ? el.closest('section.tab') : null;
      const r = el.getBoundingClientRect ? el.getBoundingClientRect() : { width: 0, height: 0 };
      return {
        board: el.getAttribute('data-board'),
        tab: sec ? sec.id.replace('tab-', '') : '(没挂到栏目)',
        order: parseInt(el.getAttribute('data-order') || '0', 10),
        w: Math.round(r.width), h: Math.round(r.height),
        visible: r.height > 0, mounted: el.getAttribute('data-mounted') === '1'
      };
    });
  };
  window.__cdpBoardReport = function () { return BOARD_REPORT; };

  function closeDrawer() { document.body.classList.remove('drawer-open'); }

  mountBoards();

  // ---------------------------------------------------------------- 抽屉：集合（7）▸ 栏目
  function openCollKeys() {
    return Array.prototype.slice.call(document.querySelectorAll('.collTabs.open'))
      .map(function (b) { return b.getAttribute('data-coll-box'); });
  }
  function buildDrawer() {
    const host = $('tabs');
    if (!host) return;
    host.innerHTML = '';
    R.COLLECTIONS.forEach(function (c) {
      const head = mk('button', 'collHead');
      head.setAttribute('data-coll', c.key);
      head.setAttribute('id', 'collhead-' + c.key);
      head.textContent = c.name + '　' + R.featuresOfColl(c.key).length + ' 个功能';
      head.addEventListener('click', function () { toggleColl(c.key); });
      host.appendChild(head);
      const box = mk('div', 'collTabs');
      box.setAttribute('data-coll-box', c.key);
      box.id = 'coll-' + c.key;
      R.tabsOf(c.key).forEach(function (t) {
        const b = mk('button', 'tabBtn');
        b.setAttribute('data-tab', t.key);
        b.setAttribute('data-coll', c.key);
        b.setAttribute('id', 'tabbtn-' + t.key);
        b.textContent = t.name + '（' + R.featuresOf(t.key).length + '）';
        b.addEventListener('click', function () { setTab(t.key); });
        box.appendChild(b);
      });
      host.appendChild(box);
    });
  }
  function toggleColl(key) {
    const box = $('coll-' + key);
    if (box) box.classList.toggle('open');
  }
  /** 抽屉里的搜索：按栏目名 / 功能名过滤（学 VS Code 命令面板的分组过滤） */
  function bindDrawerSearch() {
    const q = $('drawerSearch');
    if (!q) return;
    q.addEventListener('input', function () {
      const s = (q.value || '').trim().toLowerCase();
      Array.prototype.slice.call(document.querySelectorAll('#tabs button.tabBtn')).forEach(function (b) {
        const key = b.getAttribute('data-tab');
        const hit = !s || key.indexOf(s) >= 0 || R.tabName(key).toLowerCase().indexOf(s) >= 0 ||
          R.featuresOf(key).some(function (f) { return f.name.toLowerCase().indexOf(s) >= 0; });
        b.classList.toggle('hide', !hit);
      });
      Array.prototype.slice.call(document.querySelectorAll('.collTabs')).forEach(function (box) {
        const any = Array.prototype.slice.call(box.querySelectorAll('button.tabBtn'))
          .some(function (b) { return !b.classList.contains('hide'); });
        if (s) box.classList.toggle('open', any);
      });
      if (!s) {
        const cur = currentTab();
        Array.prototype.slice.call(document.querySelectorAll('.collTabs')).forEach(function (box) {
          box.classList.toggle('open', box.getAttribute('data-coll-box') === R.collOf(cur));
        });
      }
    });
  }
  function currentTab() {
    const sec = document.querySelector('section.tab.on');
    return sec ? sec.id.replace('tab-', '') : 'page';
  }

  // ---------------------------------------------------------------- 「更多 ▾」：第 4 个键往后一律收起来（常驻 ≤3）
  function bindMoreBoxes() {
    Array.prototype.slice.call(document.querySelectorAll('button[id$="-more"]')).forEach(function (b) {
      if (b.getAttribute('data-bound') === '1') return;
      b.setAttribute('data-bound', '1');
      const boxId = b.id.replace(/-more$/, '-morebox');
      b.addEventListener('click', function () {
        const box = document.getElementById(boxId);
        if (!box) return;
        const opening = box.classList.contains('hide');
        box.classList.toggle('hide', !opening);
        b.textContent = opening ? '收起 ▴' : '更多 ▾';
      });
    });
  }
  /** 每行可见按钮数（验收用：≤3） */
  window.__cdpButtonRows = function () {
    return Array.prototype.slice.call(document.querySelectorAll('.card .hint, .card .row')).map(function (h) {
      const btns = Array.prototype.slice.call(h.querySelectorAll('button')).filter(function (b) {
        return b.offsetParent !== null;
      });
      return { board: (h.closest('[data-board]') || {}).getAttribute ? h.closest('[data-board]').getAttribute('data-board') : '',
               buttons: btns.map(function (b) { return b.textContent.trim(); }) };
    }).filter(function (x) { return x.buttons.length > 0; });
  };

  // ---------------------------------------------------------------- 通用小窗（一行数据 → 点开固定模板小窗）
  // 动作小窗也归渲染器模块。**行本身就是入口**：不往行里塞「⋯ 操作」这种没指向功能的键；
  // 也不自动补「关掉」（抬头那个 ✕ 就是退出）。传进来的动作必须真有落点（有 fn 才摆）。
  function openActs(title, summary, acts) {
    return CDPUI.Modal.open({
      title: title, text: summary,
      extras: (acts || []).filter(function (a) { return a && (a.fn || a.run); })
        .map(function (a) { return { label: a.t || a.label, danger: a.danger, run: a.fn || a.run }; })
    });
  }
  function actsButton(row, title, summary, acts, label) {
    row.addEventListener('click', function (e) {
      if (e && e.target && e.target.tagName === 'BUTTON') return;
      if (e && e.stopPropagation) e.stopPropagation();
      openActs(title, summary, acts);
    });
    return row;
  }

  function renderRegistry() {
    const rep = R.report();
    txt($('rg-state'), (rep.ok ? '自检通过 ✓' : ('自检有问题 ✗ ' + rep.problems.length + ' 条')) +
      '　集合 ' + rep.collections + ' · 栏目 ' + rep.tabs + ' · 功能 ' + rep.features + ' · 板块 ' + rep.boards +
      (MISSING.length ? ('　⚠ 页面缺少的 id：' + MISSING.join(', ')) : ''));
    const host = hostOf('rg-list');
    if (!host) return;
    rep.byColl.forEach(function (c) {
      const row = mk('div', 'row-item');
      row.appendChild(mk('span', 'nm2', c.name));
      row.appendChild(mk('span', 'u', c.tabs + ' 个栏目 · ' + c.features + ' 个功能'));
      actsButton(row, c.name, R.featuresOfColl(c.key).map(function (f) {
        return '· ' + R.tabName(f.tab) + '：' + f.name + '（' + f.carrier + '，' + f.status + '）';
      }).join('\n'), [], '看功能');   // 空功能键（「好」）已删：没指向任何功能的键不摆
      host.appendChild(row);
    });
    (rep.problems || []).forEach(function (p) {
      host.appendChild(mk('div', 'row-item', '✗ ' + JSON.stringify(p)));
    });
    return rep;
  }
  window.__cdpRegistryUi = function () { return renderRegistry(); };
  window.__cdpMissing = function () { return MISSING.slice(); };

  /** 写值时不覆盖"用户正在输入的那个框"（自动刷新冲掉输入 = 用户反复抱怨的坑） */
  function setVal(id, v) {
    const el = $(id);
    if (!el) return;
    if (document.activeElement === el) return;   // 正在编辑，别动
    el.value = v == null ? '' : String(v);
  }

  /** 切栏目：只刷新这个栏目自己的数据（一个栏目一个刷新入口），抽屉跟着展开所属集合 */
  function setTab(name) {
    // 诊断：栏目切换为什么没生效（"列表一直是静态文字"就是这里静默 return 造成的）
    window.__setTabDbg = { name: String(name), idx: TABS.indexOf(name), tabs: TABS.length,
      why: (!name ? 'no-name' : (TABS.indexOf(name) < 0 ? 'not-in-TABS' : 'ok')) };
    if (!name || TABS.indexOf(name) < 0) return;
    Array.prototype.slice.call(document.querySelectorAll('#tabs button.tabBtn')).forEach(function (b) {
      b.classList.toggle('on', b.getAttribute('data-tab') === name);
    });
    TABS.forEach(function (t) {
      const s = $(['tab', t].join('-'));
      if (!s) return;
      if (t === name) s.classList.add('on'); else s.classList.remove('on');
    });
    const coll = R.collOf(name);
    Array.prototype.slice.call(document.querySelectorAll('.collTabs')).forEach(function (box) {
      box.classList.toggle('open', box.getAttribute('data-coll-box') === coll);
    });
    Array.prototype.slice.call(document.querySelectorAll('#tabs button.collHead')).forEach(function (h) {
      h.classList.toggle('on', h.getAttribute('data-coll') === coll);
    });
    txt($('drawerNow'), '当前：' + R.breadcrumb(name));
    const fl = R.featuresOf(name);
    txt($('drawerHint'), fl.length + ' 个功能：' + fl.map(function (f) { return f.name; }).join('；'));
    try { localStorage.setItem('cdp.tab', name); } catch (e) {}
    const L = {
      page: refreshStatus, tools: trState,
      // 进录制栏目就把脚本下拉框刷出来（以前不刷 → 回放那条一直是空的）
      rec: function () { startRecPoll(); loadRecord(); loadDots(); loadScripts(); },
      scripts: loadScripts, plugins: function () { loadRead(); }, term: function () {},
      cookies: loadCookies, vault: function () { pvState(); pvList(); }, block: function () { loadBlockRules(); },
      security: function () { loadSecurity(); loadSecurityHttp(); },
      net: function () { loadNet(); if (statsSrc() === 'net') loadStats(); }, sniff: loadSniff, downloads: loadDownloads,
      history: loadHistory, bookmarks: loadBookmarks, bundle: function () {},
      api: loadApi, ai: function () { loadApi(); loadKeepalive(); }, space: loadSpaces,
      settings: loadSettings, power: function () { loadPower(); loadBgPlay(); }, log: loadLog, intro: function () { loadIntro(); renderRegistry(); }
    };
    if (L[name]) {
      try { L[name](); } catch (e) {
        // 以前这里静默吞掉异常 → 界面上"点了没反应"，查半天。现在出错要写在抽屉提示里。
        window.__lastTabErr = name + ': ' + ((e && e.message) || e);
        txt($('drawerHint'), '这块刷新出错：' + ((e && e.message) || e));
      }
    }
    closeDrawer();
  }

  let recTimer = null;    // 录制轮询计时器（**必须先声明**：严格模式下读未声明的变量会抛 ReferenceError，
                          // 而它被 setTab 里的 try/catch 吞掉 → loadRecord 不执行 → 列表永远停在静态文字）
  function startRecPoll() {
    if (recTimer) return;                    // 已经在轮询就别叠第二份（同一列表两个刷新源＝会闪）
    stopRecPoll();
    recTimer = setInterval(function () {
      call('rec.state', {}).then(function (r) {
        txt($('r-state'), r.recording
          ? ('录制中：已 ' + (r.steps || 0) + ' 步，' + Math.round((r.elapsedMs || 0) / 1000) + ' 秒')
          : ('未开始' + (r.steps ? ('（上次录了 ' + r.steps + ' 步）') : '')));
      });
      call('rec.live', {}).then(function (r) {
        // 只走 renderRecSteps 这一个渲染入口：以前这里另写了一份"裸行"，
        // 每秒把带动作（编辑/删除/合并）的步骤行冲掉 → 用户看到的是"点了没反应"的行。
        renderRecSteps((r && r.steps) || []);
      });
    }, 1000);
  }
  function stopRecPoll() { if (recTimer) { clearInterval(recTimer); recTimer = null; } }

  // 原生侧（☰ 下拉菜单）通过这两个函数控制界面
  window.__cdpTab = setTab;
  let LAST_STATE = {};
  window.__cdpState = function (raw) {
    const s = asObj(raw);
    if (!s) return;
    LAST_STATE = s;
    txt($('p-url'), s.url || '-');
    txt($('p-title'), s.title || '-');
    txt($('p-view'), '视口 ' + (s.cssW || '?') + '×' + (s.cssH || '?') + ' css / dpr ' + (s.dpr || '?'));
    txt($('ver'), s.version || '');
  };

  bindMaybe('tabs', 'click', function (e) {
    const b = e.target.closest('button[data-tab]');
    if (b) setTab(b.getAttribute('data-tab'));
  });

  // 抽屉开合（☰ 在控制台自己的标题栏里；遮罩点一下收起来）
  bindMaybe('drawerBtn', 'click', function () {
    document.body.classList.toggle('drawer-open');
  });
  bindMaybe('scrim', 'click', closeDrawer);

  // ✕ 走原生通道真的关掉控制台（以前这里挂的是空壳，点了没反应）
  bindMaybe('close', 'click', function () {
    call('ui.close', {}).then(function (r) {
      if (txt && !r.ok) txt($('p-view'), '关闭失败：' + r.error);
    });
  });

  // ---------------------------------------------------------------- 页面
  function refreshStatus() {
    call('status', {}).then(function (s) {
      if (!s.ok) { txt($('p-url'), '状态取不到：' + (s.error || '')); return; }
      const b = s.browser || {};
      txt($('p-url'), b.url || '-');
      txt($('p-title'), b.title || '-');
      txt($('p-view'), '视口 ' + (b.cssW || '?') + '×' + (b.cssH || '?') + ' css / dpr ' + (b.dpr || '?'));
      txt($('ver'), s.version || '');
    });
  }
  function findGo(dir) {
    const q = ($('f-q') && $('f-q').value || '').trim();
    if (!q) { txt($('f-state'), '先输入要查的词'); return; }
    call('find.page', { q: q, dir: dir }).then(function (r) {
      txt($('f-state'), r.count ? ('匹配 ' + r.count + ' 处，当前第 ' + r.idx + ' 处') : '没找到');
    });
  }
  bindMaybe('f-next', 'click', function () { findGo(1); });
  bindMaybe('f-prev', 'click', function () { findGo(-1); });
  bindMaybe('f-clear', 'click', function () {
    call('find.clear', {}).then(function () { txt($('f-state'), '已清除高亮'); });
  });
  bindMaybe('f-q', 'keydown', function (e) { if (e.key === 'Enter') findGo(1); });

  bindMaybe('p-mute', 'click', function () {
    call('tab.mute', { on: !muteState }).then(function (r) {
      muteState = !!r.on;
      txt($('p-picked'), muteState ? '已静音此页（后面新打开的页面也按住）' : '已恢复声音');
      txt($('q-out'), j(r));
    });
  });
  bindMaybe('p-share', 'click', function () {
    call('share.current', {}).then(function (r) {
      txt($('p-picked'), r.ok ? ('分享：' + (r.via === 'clipboard' ? '已复制到剪贴板' : '已弹出分享面板')) : (r.error || '分享失败'));
    });
  });
  let muteState = false;

  bindMaybe('p-refresh', 'click', refreshStatus);
  bindMaybe('p-pick', 'click', function () {
    call('picker.arm', {}).then(function (r) {
      txt($('p-picked'), '拾取已就绪：控制台马上收起，请在页面上点要抓的元素');
      txt($('q-out'), j(r));
    });
  });
  bindMaybe('p-bookmark', 'click', function () {
    call('bookmark.add', {}).then(function (r) {
      txt($('q-out'), r.ok ? '已收藏：' + (r.bookmark && r.bookmark.title) : '收藏失败：' + r.error);
    });
  });

  function argOf() {
    const s = $('q-sel').value.trim(), t = $('q-text').value.trim();
    return { selector: s, text: t };
  }
  bindMaybe('q-run', 'click', function () {
    const a = argOf();
    call('page.query', a).then(function (r) { txt($('q-out'), j(r)); });
  });
  bindMaybe('q-diag', 'click', function () {
    const a = argOf();
    call('page.diag', a).then(function (r) { txt($('q-out'), j(r)); });
  });
  bindMaybe('q-click', 'click', function () {
    const a = argOf();
    call('page.click', a).then(function (r) { txt($('q-out'), j(r)); });
  });
  bindMaybe('c-run', 'click', function () {
    const x = parseFloat($('c-x').value), y = parseFloat($('c-y').value), ab = parseFloat($('c-ab').value);
    const a = {};
    if (!isNaN(x)) a.x = x;
    if (!isNaN(y)) a.y = y;
    if (!isNaN(ab)) a.anchorBottom = ab;
    call('page.click', a).then(function (r) { txt($('c-out'), j(r)); });
  });

  bindMaybe('v-state', 'click', function () {
    call('page.video', { action: 'state', selector: $('v-sel').value.trim() })
      .then(function (r) { txt($('v-out'), j(r)); });
  });
  bindMaybe('v-play', 'click', function () {
    call('page.video', { action: 'play', selector: $('v-sel').value.trim() })
      .then(function (r) { txt($('v-out'), j(r)); });
  });
  bindMaybe('v-check', 'click', function () {
    call('page.check', { cond: { type: 'videoEnded', selector: $('v-sel').value.trim() } })
      .then(function (r) { txt($('v-out'), j(r)); });
  });

  // ---------------------------------------------------------------- 监听（录制）
  function loadRecord() {
    window.__loadRec = (window.__loadRec || 0) + 1;    // 临时诊断
    call('rec.state', {}).then(function (r) {
      txt($('r-state'), r.recording
        ? ('监听中：' + (r.name || '') + '（' + ((r.mode === 'coord') ? '坐标录制' : '元素录制') + '）')
        : ('未开始　当前录制方式：' + ((($('r-mode') || {}).value === 'coord') ? '坐标录制' : '元素录制')));
    });
    // 步骤的真身在**原生那份**（rec.live）；rec.steps 是问页面里的 agent，页面没 agent（如起始页）就返回空，
    // 以前界面因此显示成"没有步骤 / 点了没反应"。
    // 只认原生那份（rec.live）。页面 agent 那份会残留旧步骤（清空时清不掉它），
    // 拿它覆盖会让"清空后列表又自己长回来"。
    call('rec.live', {}).then(function (r) { renderRecSteps((r && r.steps) || []); });
  }
  // ---------------------------------------------------------------- 小点（坐标步骤在屏幕上的落点）
  function loadDots() {
    call('rec.dotStatus', {}).then(function (r) {
      const on = !!r.on, ov = !!r.overlay;
      txt($('dot-state'), (on ? '开着' : '关着') + '　' +
        '（只画在页面里；N1 起不再用无障碍覆盖层）' +
        '　共 ' + (r.count || 0) + ' 个点');
    });
  }
  // ---------------------------------------------------------------- 接口清单 / 外部 CDP（这两个板块原来一个按键都没有）
  function epsOf(r) {                       // /api/status 的 endpoints 有可能是数组，也有可能是字符串
    let e = r && (r.endpoints || r.list);
    if (typeof e === 'string') { try { e = JSON.parse(e); } catch (x) { e = e.split(',').map(function (s) { return s.trim(); }); } }
    return Array.isArray(e) ? e : [];
  }
  bindMaybe('ep-list', 'click', function () {
    call('http.status', {}).then(function (r) {
      const eps = epsOf(r);
      txt($('a-list'), eps.join('\n'));
      txt($('ep-out'), '共 ' + eps.length + ' 个接口' + (r.bind ? '（控制口 ' + r.bind + '）' : ''));
    });
  });
  bindMaybe('ep-copy', 'click', function () {
    call('http.status', {}).then(function (r) {
      const eps = epsOf(r), s = eps.join('\n');
      try { navigator.clipboard.writeText(s); txt($('ep-out'), '已复制 ' + eps.length + ' 条到剪贴板'); }
      catch (e) { txt($('ep-out'), '剪贴板不给用，已把清单列在上面'); txt($('a-list'), s); }
    });
  });
  bindMaybe('ep-probe', 'click', function () {
    call('http.status', {}).then(function (r) {
      txt($('ep-out'), '控制口：' + ((r.running ? '运行中 ' : '没开 ') + (r.bind || '-') +
        (r.lan ? '（局域网可达）' : '（只有本机）')) + '，本机免令牌=' + (r.localFree !== false));
    });
  });
  bindMaybe('cdp-state', 'click', function () {
    call('api.cdp', {}).then(function (r) {
      txt($('cdp-out'), (r.enabled ? '调试口已开' : '调试口没开') + '　' + (r.hint || r.msg || '') +
        '　pid=' + (r.pid || '-'));
    });
  });
  bindMaybe('cdp-copy', 'click', function () {
    call('api.cdp', {}).then(function (r) {
      const line = 'adb forward tcp:9222 localabstract:webview_devtools_remote_' + (r.pid || '<pid>');
      try { navigator.clipboard.writeText(line); txt($('cdp-out'), '已复制：' + line); }
      catch (e) { txt($('cdp-out'), line); }
    });
  });

  bindMaybe('dot-toggle', 'click', function () {
    call('rec.dotStatus', {}).then(function (r) {
      call('rec.dots', { on: r.on ? '0' : '1' }).then(function () { loadDots(); loadRecord(); });
    });
  });
    // 第 24 条：原来这里有个「去开小点权限（无障碍）」的键，已按用户要求删掉（界面不再申请权限）

  /** 步骤列表：一行一步（序号 + 形态 + 摘要 + ▲▼）；其余操作全部收在"点行弹出的小窗"里 */
  let LAST_STEPS = [];                 // 界面上正在显示的步骤（原生那份）；编辑/合并都用它，别去问页面 agent
  function renderRecSteps(steps) {
    LAST_STEPS = (steps || []).slice();
    const host = hostOf('r-steps');
    if (!host) return;
    host.innerHTML = '';
    if (!steps.length) putEmpty(host, '还没有步骤');
    (steps || []).forEach(function (s, i) {
      const row = mk('div', 'row-item');
      row.setAttribute('data-kind', 'step');           // 交互行：list.js 不接管
      row.setAttribute('data-step', String(i + 1));
      row.setAttribute('data-title', '第 ' + (i + 1) + ' 步 · ' + describeStep(s));
      row.addEventListener('click', function (e) {      // 点行 = 打开这一步的小窗（可改参数，不留 ⋯ 键）
         if (e.target && e.target.tagName === 'BUTTON') return;
         editStep(i);
       });
      // 序号连续：元素步也占号（只是不画点）→ 页面上看到的小点可能是 1 2 4
      row.appendChild(mk('span', 't', String(i + 1)));
      row.appendChild(mk('span', 'kind', stepKindLabel(s)));
      row.appendChild(mk('span', 'nm2', describeStep(s)));
      // 摆出来的键都要能做事：第一步没有 ▲、最后一步没有 ▼（以前摆了但点了没反应）
      if (i > 0) {
        const up = mk('button', 'mini2', '▲');
        up.setAttribute('data-fn', 'move-up');
        up.addEventListener('click', function (e) { e.stopPropagation(); call('rec.move', { from: i, to: i - 1 }).then(loadRecord); });
        row.appendChild(up);
      }
      if (i < steps.length - 1) {
        const dn = mk('button', 'mini2', '▼');
        dn.setAttribute('data-fn', 'move-down');
        dn.addEventListener('click', function (e) { e.stopPropagation(); call('rec.move', { from: i, to: i + 1 }).then(loadRecord); });
        row.appendChild(dn);
      }
      host.appendChild(row);
    });
    // 列表最底部：一个大「＋ 添加步骤」行（一直能加，空列表时也在）
    const addRow = mk('div', 'row-item add-row');
    addRow.id = 'r-add';
    addRow.setAttribute('data-kind', 'add');
    addRow.appendChild(mk('span', 't', '＋'));
    addRow.appendChild(mk('span', 'nm2', '添加步骤'));
    addRow.addEventListener('click', function (e) { if (e) e.stopPropagation(); addStep(); });
    host.appendChild(addRow);
  }

  // 点行/点小点都走 editStep：小窗里的值就是**可改的文本框**，不再「先看信息、再点改这一步」开两层窗。
  // ---------------------------------------------------------------- 步骤的类型与可编辑字段
  // 用户说的"三种形态"其实就是：① 等待类（等几秒 / 等条件）② 播放类 ③ 点击类（坐标或元素）。
  // 每一种都是"步骤"：能加、能改（参数）、能删、能上下移、能只跑它。
  /** "3" / "2.5" / "3秒" / "300ms" 都能认；认不出来返回 null（调用处给可见提示，不静默吞） */
  function secToMs(s) {
    if (s == null) return null;
    const str = String(s).trim();
    if (!str) return null;
    if (/ms$/i.test(str) || /毫秒/.test(str)) {
      const n = parseFloat(str);
      return isFinite(n) && n > 0 ? Math.round(n) : null;
    }
    const n = parseFloat(str);
    return (isFinite(n) && n > 0) ? Math.round(n * 1000) : null;
  }

  const STEP_KINDS = [
    // 等待 / 停顿：用户习惯说"等几秒"，所以界面按**秒**填（3 / 2.5 / "3秒" 都认），内部存毫秒
    { t: 'wait', label: '等待 / 停顿几秒', make: function (v) { return { t: 'wait', ms: secToMs(v.sec) }; },
      fields: [{ key: 'sec', label: '等待几秒（可小数）', value: 3, type: 'text' }] },
    { t: 'waitFor', label: '① 等待：等条件（出现/消失/播完…）', make: function (v) {
        return { t: 'waitFor', timeoutMs: secToMs(v.sec) || 60000,
                 cond: { type: v.condType, selector: v.selector, text: v.text, value: v.value } };
      },
      fields: [
        { key: 'condType', label: '什么条件', value: 'videoEnded', type: 'select', options: [
          ['videoEnded', '视频播完'], ['videoPlaying', '视频开始播'], ['elementExists', '元素出现'],
          ['elementGone', '元素消失'], ['textAppears', '文字出现'], ['urlContains', '网址含'],
          ['urlChanged', '网址变化'] ] },
        { key: 'selector', label: '选择器（可选）', value: '' },
        { key: 'text', label: '文字（可选）', value: '' },
        { key: 'sec', label: '最多等几秒', value: 60, type: 'text' } ] },
    { t: 'playVideo', label: '② 播放：播放视频', make: function (v) {
        return { t: 'playVideo', selector: v.selector, mute: !!v.mute };
      },
      fields: [{ key: 'selector', label: '视频选择器（留空=第一个 video）', value: '' },
               { key: 'mute', label: '静音', value: '0', type: 'select', options: [['0', '不静音'], ['1', '静音']] }] },
    { t: 'click', label: '③ 点击：按坐标点', make: function (v) {
        return { t: 'click', mode: 'coord', selector: '',
                 box: { cx: Number(v.x), cy: Number(v.y), x: Number(v.x), y: Number(v.y), w: 1, h: 1 },
                 anchor: { mode: 'top', cx: Number(v.x), cy: Number(v.y), vw: v.vw || 393, vh: v.vh || 680,
                           topPx: Number(v.y), bottomPx: (v.vh || 680) - Number(v.y),
                           leftPx: Number(v.x), rightPx: (v.vw || 393) - Number(v.x),
                           ratioY: Number((Number(v.y) / (v.vh || 680)).toFixed(4)),
                           ratioX: Number((Number(v.x) / (v.vw || 393)).toFixed(4)), scrollY: 0 },
                 reps: Number(v.reps) || 1, pauseAfter: Number(v.pauseAfter) || 350 };
      },
      fields: [{ key: 'x', label: 'x 坐标（页面像素）', value: 100, type: 'number' },
               { key: 'y', label: 'y 坐标（页面像素）', value: 100, type: 'number' },
               { key: 'reps', label: '连点次数', value: 1, type: 'number' },
               { key: 'pauseAfter', label: '点完等多少毫秒', value: 350, type: 'number' }] },
    { t: 'click(el)', label: '③ 点击：按元素点', make: function (v) {
        return { t: 'click', mode: 'element', selector: v.selector,
                 target: { selector: v.selector, text: v.text, attrs: {} } };
      },
      fields: [{ key: 'selector', label: '选择器', value: '' },
               { key: 'text', label: '或按文字找', value: '' }] },
    { t: 'goto', label: '④ 打开网址', make: function (v) { return { t: 'goto', url: v.url }; },
      fields: [{ key: 'url', label: '网址', value: 'https://' }] },
    { t: 'input', label: '⑤ 输入文字', make: function (v) {
        return { t: 'input', selector: v.selector, value: v.value };
      },
      fields: [{ key: 'selector', label: '输入框选择器', value: '' },
               { key: 'value', label: '要输入的文字', value: '' }] }
  ];

  /** 每一种步骤在自己那套字段里长什么样（内部用；外面统一走下面的 fieldsForStep） */
  function fieldsForStepRaw(s) {
    const t = (s && s.t) || '?';
    if (t === 'wait') return STEP_KINDS[0].fields.map(function (f) {
      return Object.assign({}, f, { value: (Math.round((s.ms || 0)) / 1000) });
    });
    if (t === 'waitFor') {
      const c = (s.cond || {});
      return STEP_KINDS[1].fields.map(function (f) {
        if (f.key === 'condType') return Object.assign({}, f, { value: c.type || 'videoEnded' });
        if (f.key === 'selector') return Object.assign({}, f, { value: c.selector || '' });
        if (f.key === 'text') return Object.assign({}, f, { value: c.text || '' });
        if (f.key === 'sec') return Object.assign({}, f, { value: Math.round((s.timeoutMs || 60000) / 1000) });
        return f;
      });
    }
    if (t === 'playVideo') return STEP_KINDS[2].fields.map(function (f) {
      return Object.assign({}, f, f.key === 'selector' ? { value: s.selector || '' } : {});
    });
    if (t === 'clickGroup') return [
      { key: 'pts', label: '点组里有几个点', value: ((s.points || []).length || 0), editable: false },
      { key: 'reps', label: '连点次数', value: (s.reps || 1), type: 'number' },
      { key: 'pauseAfter', label: '点完等多少毫秒', value: (s.pauseAfter == null ? 350 : s.pauseAfter), type: 'number' }
    ];
    if (t === 'click') {
      const b = s.box || {};
      if (s.mode === 'coord' || (!((s.target || {}).selector) && !((s.target || {}).text))) {
        return STEP_KINDS[3].fields.map(function (f) {
          if (f.key === 'x') return Object.assign({}, f, { value: Math.round(b.cx != null ? b.cx : 0) });
          if (f.key === 'y') return Object.assign({}, f, { value: Math.round(b.cy != null ? b.cy : 0) });
          if (f.key === 'reps') return Object.assign({}, f, { value: s.reps || 1 });
          if (f.key === 'pauseAfter') return Object.assign({}, f, { value: s.pauseAfter || 350 });
          return f;
        });
      }
      return STEP_KINDS[4].fields.map(function (f) {
        if (f.key === 'selector') return Object.assign({}, f, { value: (s.target || {}).selector || s.selector || '' });
        if (f.key === 'text') return Object.assign({}, f, { value: (s.target || {}).text || '' });
        return f;
      });
    }
    if (t === 'goto') return STEP_KINDS[5].fields.map(function (f) { return Object.assign({}, f, { value: s.url || '' }); });
    if (t === 'input') return STEP_KINDS[6].fields.map(function (f) {
      return Object.assign({}, f, { value: f.key === 'value' ? (s.value || '') : (s.selector || '') });
    });
    return [];
  }

  /**
   * 小窗里摆的字段：**第一行是只读的"类型"**，其余是该步骤能改的参数。
   * 用户要的形态：能改的和不能改的**都是文本框**（只读的那种长得一样、只是改不动）——
   * 所以这里用 editable:false 标出"只读"，不再另做一套"信息窗"。
   */
  function fieldsForStep(s) {
    const kind = { key: 'kind', label: '类型', value: stepKindLabel(s), editable: false };
    return [kind].concat(fieldsForStepRaw(s));
  }

  /** 这一步前面还有没有"点"（决定小窗里要不要摆「并进上一个点」；前面没点就不摆） */
  function hasDotBefore(steps, i) {
    for (let k = 0; k < i; k++) {
      const s = steps[k] || {};
      if (s.t === 'clickGroup') return true;
      if (s.t === 'click' && (s.mode === 'coord' || (!((s.target || {}).selector) && !((s.target || {}).text)))) return true;
    }
    return false;
  }

  /** 步骤形态标签（用户说的三种形态 + 其它）：等待 / 条件 / 播放 / 点击(点) / 点击(元素) / 跳转 / 输入 / 点组 */
  function stepKindLabel(s) {
    const t = (s && s.t) || '?';
    if (t === 'wait') return '等待';
    if (t === 'waitFor') return '等条件';
    if (t === 'playVideo') return '播放';
    if (t === 'goto') return '跳转';
    if (t === 'input') return '输入';
    if (t === 'if') return '条件';
    if (t === 'clickGroup') return '点组';
    if (t === 'click') return (s.mode === 'coord' || (!((s.target || {}).selector) && !((s.target || {}).text))) ? '点击·点' : '点击·元素';
    return t;
  }

  /** 改某一步（列表里点 ⋯ → ✎ 编辑；小点单击也走这里） */
  function editStep(i) {
    const openWith = function (steps) {
      const s = (steps || [])[i];
      if (!s) { txt($('r-out') || $('s-out'), '没有第 ' + (i + 1) + ' 步'); return; }
      // 小窗里摆什么键：先放**真做事的**附加键（有个功能一个；前面没有点就不摆合并），最后才是 恢复原始值 + 确定
      const extras = [
        { label: '▶ 只执行这一步', close: false, run: function () {
            const el = $('fld-runs');
            let n = parseInt((el && el.value || '1').trim(), 10);
            if (!isFinite(n) || n < 1) n = 1;
            if (n > 50) { n = 50; if (el) el.value = '50'; }
            call('rec.runOne', { i: i, times: n }).then(function (r3) {
              const okN = (r3 && r3.okTimes != null) ? r3.okTimes : ((r3 && r3.ok) ? n : 0);
              txt($('r-out') || $('s-out'),
                '第 ' + (i + 1) + ' 步：跑 ' + n + ' 次，成功 ' + okN + ' 次\n' + j(r3));
            });
          } }
      ];
      if (hasDotBefore(steps, i)) {
        extras.push({ label: '⇤ 并进上一个点', run: function () { call('rec.merge', { index: i }).then(function () { loadRecord(); loadDots(); }); } });
      }
      extras.push({ label: '✕ 删掉这一步', danger: true, run: function () { call('rec.del', { i: i }).then(function () { loadRecord(); loadDots(); }); } });
      CDPUI.Modal.open({
        title: '第 ' + (i + 1) + ' 步 · ' + describeStep(s),
        // 「跑几次」是**执行参数**（不属于这一步的数据，所以不会进 patch）：用户要的"运行步骤也要有运行多少次"
        fields: fieldsForStep(s).concat([{ key: 'runs', label: '跑几次', value: 1, type: 'number' }]),
        extras: extras,
        onSubmit: function (v) {
          // 按类型把表单值拼成 patch（只发改动的那几个字段）
          let patch;
          if (s.t === 'wait') { if (secToMs(v.sec) == null) return; patch = { ms: secToMs(v.sec) }; }
          else if (s.t === 'waitFor') { if (secToMs(v.sec) == null) return; patch = { cond: { type: v.condType, selector: v.selector, text: v.text, value: v.value }, timeoutMs: secToMs(v.sec) }; }
          else if (s.t === 'playVideo') patch = { selector: v.selector, mute: v.mute === '1' };
          else if (s.t === 'goto') patch = { url: v.url };
          else if (s.t === 'input') patch = { selector: v.selector, value: v.value };
          else if (s.t === 'click' && (s.mode === 'coord' || !((s.target || {}).selector))) {
            patch = { x: Number(v.x), y: Number(v.y), reps: Number(v.reps), pauseAfter: Number(v.pauseAfter) };
          } else patch = { selector: v.selector, text: v.text };
          if (s.t === 'clickGroup') patch = { reps: Number(v.reps), pauseAfter: Number(v.pauseAfter) };
          call('rec.update', { i: i, patch: patch }).then(function (r2) {
            // 失败要看得见：直接把错误开成小窗（以前写到后面的状态行，被小窗盖住看不见）
            if (!r2.ok) CDPUI.Modal.open({ title: '没改成', text: String(r2.error || '未知错误') });
            loadRecord(); loadDots();
          });
        }
      });
    };
    // 先用界面上显示的那份（原生那份最靠得住）；没有才回头问页面 agent
    if (LAST_STEPS[i]) { openWith(LAST_STEPS); return; }
    call('rec.live', {}).then(function (r) { openWith((r && r.steps) || []); })
      .then(function () { if (!LAST_STEPS[i]) call('rec.steps', {}).then(function (r2) { openWith((r2 && r2.steps) || []); }); });
  }
  window.__cdpStepEditor = function (n) { editStep(n - 1); };

  /** 添加步骤（不在录制时也能加）：先选形态（可点的行，不占按键名额），再填参数 */
  function addStep() {
    CDPUI.Modal.open({
      title: '添加步骤',
      options: STEP_KINDS.map(function (k) {
        return { label: k.label, note: kindNote(k), run: function () { openKindForm(k); } };
      })
    });
  }

  /** 形态行右边那句短说明（只用在"选形态"这一屏，解释这一步是干什么的） */
  function kindNote(k) {
    if (k.t === 'wait') return '停几秒再往下';
    if (k.t === 'waitFor') return '等某个条件成立';
    if (k.t === 'playVideo') return '让页面上的视频播起来';
    if (k.t === 'goto') return '跳到某个网址';
    if (k.t === 'input') return '往输入框里打字';
    return '';
  }

  /** 选完形态填参数：值就是文本框（数字框/下拉按类型给），底部 恢复原始值 + 确定 */
  function openKindForm(k) {
    CDPUI.Modal.open({
      title: k.label,
      fields: k.fields,
      onSubmit: function (v) {
        // 参数不对就说清楚（以前是静默造一个默认值，用户以为"点了没反应"）
        const bad = function (msg) { CDPUI.Modal.open({ title: '参数不对', text: msg }); };
                if (k.t === 'wait' && secToMs(v.sec) == null) return bad('等待时间要填秒数，比如 3 或 2.5');
                if (k.t === 'waitFor' && secToMs(v.sec) == null) return bad('最多等几秒要填数字，比如 60');
                const step = k.make(v);
                if (k.t === 'playVideo') step.mute = (v.mute === '1');
                call('rec.insert', { step: step }).then(function (r) {
                  if (!r || !r.ok) return bad('没加进去：' + ((r && r.error) || '未知错误'));
                  call('rec.live', {}).then(function (r2) { renderRecSteps((r2 && r2.steps) || []); });  // 立刻刷新，不等轮询
          loadDots();
        });
      }
    });
  }
  bindMaybe('r-add', 'click', addStep);

  function describeStep(s) {
    if (!s) return '?';
    const t = s.t || '?';
    if (t === 'click') {
      const isCoord = (s.mode === 'coord') || (!((s.target || {}).selector) && !((s.target || {}).text) && s.anchor);
      if (isCoord) {
        const b = s.box || {};
        return '坐标点击 (' + Math.round(b.cx != null ? b.cx : (b.left || 0) + (b.width || 0) / 2) + ', ' +
          Math.round(b.cy != null ? b.cy : (b.top || 0) + (b.height || 0) / 2) + ')　坐标录制';
      }
      return '点击 ' + ((s.target && (s.target.selector || s.target.text)) || '?');
    }
    if (t === 'wait') return '等 ' + (s.ms || 0) + 'ms';
    if (t === 'waitFor') return '等条件 ' + ((s.cond && s.cond.type) || '?') + ' ' + ((s.cond && (s.cond.selector || s.cond.text)) || '');
    if (t === 'playVideo') return '播放视频 ' + (s.selector || '（第一个 video）');
    if (t === 'if') return '条件分支 ' + ((s.cond && s.cond.type) || '?') + ' → then ' + ((s.then || []).length) + ' 步 / else ' + ((s.else || []).length) + ' 步';
    if (t === 'goto') return '打开 ' + (s.url || '');
    return t;
  }
  bindMaybe('r-start', 'click', function () {
    const mode = ($('r-mode') && $('r-mode').value) || 'element';
    call('rec.start', { name: '录制脚本', mode: mode }).then(function (r) {
      loadRecord();
      txt($('s-out'), r.ok
        ? ('已开始监听（' + (mode === 'coord' ? '坐标录制：只记点在哪' : '元素录制：记选择器/文字') + '，可以关掉控制台去页面上点）')
        : ('开始失败：' + r.error));
    });
  });
  bindMaybe('r-mode', 'change', function () {
    // 立刻同步给原生：不然 ☰ 菜单里的「开始监听」不知道你选了坐标录制
    call('rec.mode', { mode: ($('r-mode') || {}).value });
    txt($('s-out'), '录制方式已切到：' + ($('r-mode').value === 'coord' ? '坐标录制' : '元素录制') +
      '（下一次「开始监听」按这个来；正在录的那一次不受影响）');
  });
  let PICK_SCRIPT = '';        // 刚保存的脚本 id：回放下拉框优先选它
  bindMaybe('r-stop', 'click', function () {
    call('rec.stop', {}).then(function (r) {
      PICK_SCRIPT = r.ok ? r.id : '';
      loadRecord(); loadScripts();
      txt($('s-out'), r.ok ? ('已保存为脚本，id=' + r.id + '（' + r.steps + ' 步）') : ('保存失败：' + r.error));
    });
  });
  bindMaybe('r-clear', 'click', function () {
    call('rec.clear', {}).then(function () { loadRecord(); });
  });

  function looksLikeSelector(s) {
    if (!s) return false;
    return /^[#.\[]/.test(s) || /^[a-z]+[#.\[:]/.test(s) || /^[a-z]+$/i.test(s) === false;
  }
  function clickStepFor(sel) {
    const id = sel && sel.charAt(0) === '#' ? sel.slice(1) : '';
    return { t: 'click', target: { selector: sel || '', id: id, tag: '', text: looksLikeSelector(sel) ? '' : (sel || ''), attrs: {} }, pauseAfter: 350 };
  }
  function buildStep() {
    const type = $('s-type').value, a1 = $('s-arg').value.trim(), a2 = $('s-arg2').value.trim();
    if (type === 'wait') return { t: 'wait', ms: parseInt(a1, 10) || 1000, pauseAfter: 200 };
    if (type === 'playVideo') return { t: 'playVideo', selector: looksLikeSelector(a1) ? a1 : '', muted: true, pauseAfter: 600 };
    if (type === 'click') return clickStepFor(a1);
    if (type === 'condClick') {
      const cond = looksLikeSelector(a1) ? { type: 'elementExists', selector: a1 } : { type: 'elementExists', text: a1 };
      return { t: 'if', cond: cond, then: a2 ? [clickStepFor(a2)] : [], else: [], pauseAfter: 300 };
    }
    const cond = { type: type };
    if (type === 'time') return { t: 'wait', ms: parseInt(a1, 10) || 1000, pauseAfter: 200 };
    if (type === 'urlChanged') { cond.from = location.href; }
    else if (type === 'textAppears') cond.text = a1;
    else if (looksLikeSelector(a1)) cond.selector = a1;
    else cond.text = a1;
    const timeout = (type === 'videoEnded' || type === 'videoPlaying') ? 60000 : 20000;
    return { t: 'waitFor', cond: cond, timeoutMs: timeout, intervalMs: 300, pauseAfter: 200 };
  }
  bindMaybe('s-ins', 'click', function () {
    const step = buildStep();
    call('rec.insert', { step: step }).then(function (r) {
      loadRecord();
      txt($('s-out'), r.ok ? ('已插入：' + describeStep(step) + '（当前 ' + r.count + ' 步）') : ('插入失败：' + r.error));
    });
  });

  bindMaybe('r-import-go', 'click', function () {
    let o;
    try { o = JSON.parse($('r-import').value); } catch (e) {
      txt($('r-import-out'), 'JSON 解析失败：' + e.message); return;
    }
    if (!o.steps || !o.steps.length) { txt($('r-import-out'), '这份 JSON 里没有 steps 数组'); return; }
    const body = Object.assign({ kind: 'recording', source: '导入' }, o);
    call('scripts.save', { name: body.name || '导入的录制脚本', kind: 'recording', steps: JSON.stringify(body.steps) })
      .then(function (r) {
        loadScripts();
        txt($('r-import-out'), r.ok ? ('已导入并保存，id=' + (r.id || (r.saved || {}).id) + '，共 ' + body.steps.length + ' 步') : ('导入失败：' + r.error));
      });
  });
  bindMaybe('r-import-clear', 'click', function () { $('r-import').value = ''; txt($('r-import-out'), '-'); });

  /** 脚本列表：一行一个（名字 + 状态摘要），运行/删除由行小窗收着 */
  /** 回放/执行跑几次：读 #r-times（非法值＝1 次，1..50 夹住），并把生效值写回框里 */
  function runTimes() {
    const el = $('r-times');
    let n = parseInt((el && el.value || '').trim(), 10);
    if (!isFinite(n) || n < 1) n = 1;
    if (n > 50) n = 50;
    if (el && String(el.value) !== String(n)) el.value = String(n);
    return n;
  }

  function loadScripts() {
    call('scripts.list', {}).then(function (r) {
      const list = r.list || [];
      const sel = $('r-script');
      if (sel) {
        // 这个下拉框是「回放哪一条」用的 → **只放能回放的**（录制 / 队列），按时间**从新到旧**，
        // 默认选**最新那条**。以前按登记顺序列：第一条是内置用户脚本，于是「运行脚本」跑的是它，
        // 后端回一句「脚本没有步骤」——用户看到的就是"点了运行、跑错一条、还报错"。
        const playable = list.filter(function (s) { return s.kind === 'recording' || s.kind === 'queue'; })
          .sort(function (a, b) { return (b.updated || 0) - (a.updated || 0); });
        const keep = sel.value;
        sel.innerHTML = '';
        playable.forEach(function (s) {
          const o = document.createElement('option');
          o.value = s.id;
          o.textContent = s.name + '（' + (s.steps || 0) + ' 步 · ' + fmtTime(s.updated) + '）';
          sel.appendChild(o);
        });
        // 刚「停止并保存」录的那条优先选中（"回放当前"就是刚录的这条）
        if (PICK_SCRIPT && playable.some(function (s) { return s.id === PICK_SCRIPT; })) {
          sel.value = PICK_SCRIPT; PICK_SCRIPT = '';
        } else if (keep && playable.some(function (s) { return s.id === keep; })) {
          sel.value = keep;
        }
        sel.disabled = playable.length === 0;
        const rp = $('r-replay');
        if (rp) rp.disabled = playable.length === 0;
        if (!playable.length && $('r-out')) {
          txt($('r-out'), '还没有可回放的录制脚本：先「开始监听」录一段，或按「▶ 跑当前队列」。');
        }
      }
      // 「监听与录制」里那个下拉：只列能载入的（录制/队列），新的在前 —— 用户要求
      // "载入步骤要出现在监听与录制中"（以前只在录制脚本行的小窗里，他找不到）。
      const lsel = $('r-loadsel');
      if (lsel) {
        const loadable = list.filter(function (s) { return s.kind === 'recording' || s.kind === 'queue'; })
          .sort(function (a, b) { return (b.updated || 0) - (a.updated || 0); });
        const keepL = lsel.value;
        lsel.innerHTML = '';
        loadable.forEach(function (s) {
          const o = document.createElement('option');
          o.value = s.id;
          o.textContent = s.name + '（' + (s.steps || 0) + ' 步）';
          lsel.appendChild(o);
        });
        if (keepL && loadable.some(function (s) { return s.id === keepL; })) lsel.value = keepL;
        lsel.disabled = loadable.length === 0;
      }
      // 用户要求：**录制脚本列表放在录制板块里**。所以这里分家：
      //   录制/队列 → 录制板块的 #rec-list；用户脚本 → 这个栏目的 #sc-list。
      renderRecList(list.filter(function (s) { return s.kind === 'recording' || s.kind === 'queue'; }));
      const mine = list.filter(function (s) { return s.kind !== 'recording' && s.kind !== 'queue'; });
      const host = hostOf('sc-list');
      if (!host) return;
      if (!mine.length) { putEmpty(host, '（还没有用户脚本；上面粘一个，或从网址导入）'); return; }
      mine.forEach(function (s) {
        const row = mk('div', 'row-item two');   // 两行制（用户定的行规范）
        row.appendChild(mk('span', 'nm2', s.name));
        row.appendChild(mk('span', 'u', s.kind === 'recording'
          ? ('录制 ' + (s.steps || 0) + ' 步 · 跑了 ' + (s.runs || 0) + ' 次 / 成功 ' + (s.okRuns || 0) + ' 次')
          : ('用户脚本 ' + (s.codeLen || 0) + ' 字')));
        const run = mk('button', 'mini2', '运行');
        run.addEventListener('click', function () {
          call('play.run', { id: s.id }).then(function (rr) { txt($('sc-out'), j(rr).slice(0, 800)); loadScripts(); });
        });
        const del = mk('button', 'mini2', '删除');
        del.addEventListener('click', function () {
          call('scripts.delete', { id: s.id }).then(function () { loadScripts(); });
        });
        row.appendChild(run); row.appendChild(del);
        host.appendChild(row);
      });
    });
  }
  /** 录制板块的那张表：点一行＝载入它的步骤来编辑；行上留「运行」「✕」。 */
  function renderRecList(recs) {
    const host = hostOf('rec-list');
    if (!host) return;
    host.innerHTML = '';
    if (!recs.length) { putEmpty(host, '（还没有录制脚本：上面「开始监听」录一段）'); return; }
    recs.slice().sort(function (a, b) { return (b.updated || 0) - (a.updated || 0); }).forEach(function (s) {
      const row = mk('div', 'row-item');
      row.appendChild(mk('span', 'nm2', s.name));
      row.appendChild(mk('span', 'u', (s.steps || 0) + ' 步 · ' + fmtTime(s.updated) +
        ' · 跑了 ' + (s.runs || 0) + ' 次（成功 ' + (s.okRuns || 0) + '）'));
      // 按键都放**行里**：按本项目的规矩，list.js 会把它们搬进"点行后的小窗"（行面只留摘要），
      // 所以这里既不用自己写点击层级，也符合用户要的"点行看全部操作"。
      const load = mk('button', 'mini2', '载入步骤');
      load.addEventListener('click', function () {
        call('rec.load', { id: s.id }).then(function (r) {
          if (!r || r.ok !== true) { txt($('r-out'), (r && r.error) || '载入失败'); return; }
          loadRecord();                                   // 载入完把步骤列表刷出来
          const sel = $('r-script'); if (sel) sel.value = s.id;
          txt($('r-out'), '已载入「' + r.name + '」（' + r.steps + ' 步）到步骤列表，可以改；要跑就按下面的「回放这条脚本」。');
        });
      });
      const run = mk('button', 'mini2', '运行');
      run.addEventListener('click', function () {
        call('play.run', { id: s.id }).then(function (rr) { txt($('r-out'), j(rr).slice(0, 800)); loadScripts(); });
      });
      const del = mk('button', 'mini2', '✕');
      del.title = '删除这条脚本';
      del.addEventListener('click', function () {
        call('scripts.delete', { id: s.id }).then(function () { loadScripts(); });
      });
      row.appendChild(load); row.appendChild(run); row.appendChild(del);
      row.setAttribute('data-tag', 'rec-script');
      host.appendChild(row);
    });
  }
  bindMaybe('rc-reload', 'click', loadScripts);
  /** 「监听与录制」里的载入：把选中的那条录制脚本的步骤载进**这一块的步骤列表** */
  bindMaybe('r-loadgo', 'click', function () {
    const sel = $('r-loadsel');
    const id = sel && sel.value;
    if (!id) { txt($('r-out'), '上面那条下拉里还没有可载入的录制脚本'); return; }
    call('rec.load', { id: id }).then(function (r) {
      if (!r || r.ok !== true) { txt($('r-out'), (r && r.error) || '载入失败'); return; }
      loadRecord();
      txt($('r-out'), '已把「' + r.name + '」的 ' + r.steps + ' 步载入到上面的步骤列表，可以直接改；要跑就按下面的「回放这条脚本」。');
      const box = document.querySelector('[data-board=\"rec-1\"]');
      if (box && box.scrollIntoView) box.scrollIntoView({ block: 'center' });
    });
  });
  /** 一键清空录制脚本（只删录制/队列；用户脚本不动） */
  bindMaybe('rc-clear', 'click', function () {
    showModal('清空所有录制脚本？', '只会删掉"录制"类的脚本；内置与粘贴的用户脚本不动。', null, [
      { t: '清空录制脚本', danger: true, fn: function () {
          call('scripts.clearRecordings', {}).then(function (r) {
            txt($('r-out'), '已清空 ' + ((r && r.cleared) || 0) + ' 条录制脚本');
            loadScripts();
          });
      } },
      { t: '取消', fn: function () {} }
    ]);
  });
  bindMaybe('r-reload', 'click', loadScripts);
  bindMaybe('r-runall', 'click', function () {
    const times = runTimes();
    txt($('r-out'), '按队列执行中…（' + (times > 1 ? '跑 ' + times + ' 次，' : '') + '控制台会自动收起，看得见它在页面上点）');
    call('rec.runAll', { times: times }).then(function (r) {
      const lines = [];
      const rs2 = r.runs || [];
      const okN2 = rs2.filter(function (x) { return x.ok; }).length;
      lines.push('跑 ' + (r.times || rs2.length || 1) + ' 次：成功 ' + (r.ok ? (rs2.length || 1) : okN2) + ' 次，总耗时 ' + r.ms + 'ms');
      if (rs2.length > 1) {
        rs2.forEach(function (one, i) { lines.push('第 ' + (i + 1) + ' 次：' + (one.ok ? '成功' : '有失败') + '（' + one.ms + 'ms）'); });
      }
      lines.push('最后一次逐步：');
      (r.steps || []).forEach(function (s2, i) {
        let head = (i + 1) + '. ' + (s2.type || s2.t || '?') + ' ok=' + s2.ok;
        if (s2.error) head += '｜错误 ' + s2.error;
        if (s2.via) head += '｜via ' + s2.via;
        lines.push(head);
      });
      txt($('r-out'), lines.join('\n'));
    });
  });
  bindMaybe('r-replay', 'click', function () {
    const id = $('r-script').value;
    if (!id) { txt($('r-out'), '下拉里没有可回放的脚本：先「开始监听」录一段，或按「▶ 跑当前队列」。'); return; }
    const times = runTimes();
    txt($('r-out'), times > 1 ? ('回放中…（跑 ' + times + ' 次）') : '回放中…');
    call('play.run', { id: id, times: times }).then(function (r) {
      const lines = [];
      const rs = r.runs || [];
      const okN = rs.filter(function (x) { return x.ok; }).length;
      lines.push('跑 ' + (r.times || rs.length || 1) + ' 次：成功 ' + (r.ok ? (rs.length || 1) : okN) + ' 次，总耗时 ' + r.ms + 'ms');
      if (rs.length > 1) {
        rs.forEach(function (one, i) {
          lines.push('第 ' + (i + 1) + ' 次：' + (one.ok ? '成功' : '有失败') + '（' + one.ms + 'ms）');
        });
      }
      lines.push('最后一次逐步：');
      (r.steps || []).forEach(function (s, i) {
        let head = (i + 1) + '. ' + (s.type || s.t || '?') + ' ok=' + s.ok;
        if (s.type === 'waitFor') head += '｜等 ' + s.cond + '：等了 ' + s.waitedMs + 'ms / 轮询 ' + s.polls + ' 次';
        if (s.type === 'if') head += '｜条件 ' + s.cond + '=' + s.condResult + ' → 走 ' + s.branch + ' 分支（子步骤 ' + ((s.sub || []).length) + '）';
        if (s.type === 'playVideo') head += '｜注入坐标 ' + j(s.tapPoint || {});
        if (s.via) head += '｜via ' + s.via;
        if (s.error) head += '｜错误 ' + s.error;
        lines.push(head);
        if (s.beforeCount !== undefined) lines.push('     页面计数 ' + s.beforeCount + ' → ' + s.afterCount + '，isTrusted=' + s.trusted);
        (s.sub || []).forEach(function (sub, k) {
          lines.push('     else/then 子步骤 ' + (k + 1) + ': ' + (sub.type || sub.t) + ' ok=' + sub.ok + (sub.error ? ' 错误 ' + sub.error : ''));
        });
      });
      if (r.stats) lines.push('累计：跑了 ' + r.stats.runs + ' 次 / 成功 ' + r.stats.okRuns + ' 次，每步触发 [' + (r.stats.stepFires || []).join(', ') + ']');
      txt($('r-out'), lines.join('\n'));
      loadScripts();
    });
  });

  // ---------------------------------------------------------------- 脚本
  bindMaybe('sc-reload', 'click', loadScripts);
  bindMaybe('sc-scan', 'click', function () {
    call('scripts.scanDir', {}).then(function (r) { loadScripts(); txt($('sc-out'), j(r)); });
  });
  bindMaybe('sc-clear', 'click', function () {
    call('scripts.clear', {}).then(function (r) { loadScripts(); txt($('sc-out'), j(r)); });
  });
  bindMaybe('sc-save', 'click', function () {
    call('scripts.save', {
      name: $('sc-name').value.trim() || '未命名脚本',
      match: $('sc-match').value.trim() || '*',
      kind: 'userscript',
      code: $('sc-code').value
    }).then(function (r) { loadScripts(); txt($('sc-out'), j(r)); });
  });
  bindMaybe('sc-import', 'click', function () {
    call('scripts.importUrl', { url: $('sc-url').value.trim() })
      .then(function (r) { loadScripts(); txt($('sc-out'), j(r)); });
  });

  // ---------------------------------------------------------------- 历史
  function loadHistory() { renderHistory(); }
  bindMaybe('h-reload', 'click', loadHistory);
  bindMaybe('h-clear', 'click', function () {
    call('history.clear', {}).then(loadHistory);
  });
  if ($('h-filter')) {
    bindMaybe('h-filter', 'input', loadHistory);
    bindMaybe('h-filter', 'change', loadHistory);
  }

  // ---------------------------------------------------------------- 书签
  function loadBookmarks() { renderBookmarks(); }
  bindMaybe('b-reload', 'click', loadBookmarks);
  bindMaybe('b-toggle', 'click', function () {
    call('bookmark.toggle', {}).then(function (r) { loadBookmarks(); });
  });
  bindMaybe('b-mkfolder', 'click', function () {
    const f = ($('b-newfolder').value || '').trim();
    if (!f) { txt($('b-tree'), '先填文件夹名（可以用 / 分层，如 学习通/期末）'); return; }
    // 新建文件夹 = 给一条占位书签挂到该路径？不需要：直接把当前页收进这个文件夹即可
    call('bookmark.add', { folder: f }).then(function () { $('b-newfolder').value = ''; loadBookmarks(); });
  });
  bindMaybe('b-add', 'click', function () {
    const u = ($('b-newurl').value || '').trim();
    call('bookmark.add', { url: u, folder: ($('b-newfolder').value || '').trim() }).then(function (r) {
      txt($('b-reload'), '已加书签');
      $('b-newurl').value = '';
      loadBookmarks();
    });
  });

  // ---------------------------------------------------------------- 下载（App 自己下，不走系统下载器）
  function fmtBytes(n) {
    n = n || 0;
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(2) + ' MB';
  }
  function loadDownloads() {
    call('downloads.list', {}).then(function (r) {
      const list = r.list || [];
      const host = hostOf('d-list');
      if (!host) return;
      if (!list.length) { putEmpty(host, '（还没有下载）'); return; }
      list.forEach(function (d) {
        const row = mk('div', 'row-item two');   // 两行制：标题大字 + 右列大小（完整）+ 第二行地址
        const pct = d.total ? '（' + Math.round((d.bytes || 0) * 100 / d.total) + '%）' : '';
        row.setAttribute('data-title', d.name || '下载');
        row.setAttribute('data-fields', JSON.stringify([
          ['名称', d.name || ''], ['大小', fmtBytes(d.bytes) + (d.total ? (' / ' + fmtBytes(d.total)) : '')],
          ['状态', d.state || ''], ['地址', d.url || ''], ['存放位置', d.path || d.dir || ''],
          ['时间', d.ts ? fmtTime(d.ts) : '']
        ]));
        // 用户要求：下载中要**看得见进度条**；"大小 / 比例"放**行的右侧**；结果只显示大小。
        const done = d.bytes || 0, tot = d.total || 0;
        const seg = d.segsTotal ? ((d.segsDone || 0) + '/' + d.segsTotal + ' 片') : '';
        const ratio = tot > 0 ? Math.min(100, Math.round(done * 100 / tot))
          : (d.segsTotal ? Math.round((d.segsDone || 0) * 100 / d.segsTotal) : 0);
        const active = /进行中/.test(d.state || '') || (ratio > 0 && ratio < 100 && !/失败|中断|完成/.test(d.state || ''));
        row.appendChild(mk('span', 'nm2', d.name));
        if (active) {
          const bar = mk('span', 'dl-bar');
          const fill = mk('i', '');
          fill.style.width = ratio + '%';
          bar.appendChild(fill);
          row.appendChild(bar);
        }
        const right = mk('span', 'rt dl-size', active ? (done && tot ? (ratio + '%　' + fmtBytes(done) + '/' + fmtBytes(tot)) : (seg || fmtBytes(done)))
          : fmtBytes(done) + (tot && tot !== done ? (' / ' + fmtBytes(tot)) : ''));
        right.setAttribute('data-right', '1');
        row.appendChild(right);
        row.appendChild(mk('span', 'u', (d.state || '') + (seg ? ('　' + seg) : '') + (d.url ? ('　' + d.url) : '') + (d.dir ? ('　→ ' + d.dir) : '')));
        // N6（用户要求）：行上只留**一个状态键**（正在下＝⏸ 点它暂停；已暂停＝▶ 点它继续）+ **✕ 删**。
        // 下完了就只剩一个 ✕ —— 那时⏸/▶ 已经没有意义，不摆。
        const stNow = d.state || '';
        const finished = (stNow === '完成') || /失败|中断/.test(stNow);
        if (!finished) {
          const paused = /已暂停/.test(stNow);
          // 用户要求：暂停＝**两竖杠**、继续＝**三角形**，而且必须是黑白 ——
          // ⏸(U+23F8)/▶(U+25B6) 在 Android 上会被当**彩色 emoji** 渲染（就是"橙色按键"的真凶），
          // 所以这里用纯文本字形：‖ (U+2016) / ▸ (U+25B8)，再补一个文字变体选择符兜底。
          const pb = mk('button', 'mini2', paused ? '\u25B8' : '\u2016');
          pb.setAttribute('data-state-key', '1');
          pb.title = paused ? '继续下载' : '暂停下载';
          pb.addEventListener('click', function (e) {
            if (e && e.stopPropagation) e.stopPropagation();
            call('downloads.toggle', { id: d.id }).then(function (rr) {
              if (rr && rr.ok === false) alertMsg(rr.error || '没成功');
              loadDownloads();
            });
          });
          row.appendChild(pb);
        }
        const del = mk('button', 'mini2', '✕');
        del.setAttribute('data-row-keep', '1');   // 用户要求：✕ 一直在行尾（别被搬进小窗）
        del.title = '删除这条下载';
        // 用户要求（2026-09-21）：点 ✕ 弹小窗 —— 信息是「是否删除本地」+ 一个**勾选框**，下面「是 / 否」。
        del.addEventListener('click', function (e) {
          if (e && e.stopPropagation) e.stopPropagation();
          CDPUI.Modal.open({
            title: '删除「' + (d.name || '') + '」？',
            fields: [{
              key: 'local', label: '是否删除本地文件', type: 'check', value: false
            }],
            okLabel: '是',
            cancelLabel: '否',
            onSubmit: function (v) {
              call('downloads.delete', { name: d.name, keepFile: !v.local }).then(loadDownloads);
            }
          });
        });
        row.appendChild(del);
        host.appendChild(row);
      });
    });
  }

  /** 轻提示：复用下载栏顶部的一行文字，不弹系统框 */
  function alertMsg(msg) {
    const c = $('d-url');
    if (c && c.parentElement) {
      let tip = document.getElementById('d-tip');
      if (!tip) {
        tip = mk('div', 'hint dim', '');
        tip.id = 'd-tip';
        c.parentElement.appendChild(tip);
      }
      tip.textContent = msg;
    }
  }
  bindMaybe('d-reload', 'click', loadDownloads);
  bindMaybe('d-go', 'click', function () {
    const url = $('d-url').value.trim();
    if (!url) { txt($('d-list'), '先填文件地址'); return; }
    call('download.start', { url: url, name: $('d-name').value.trim() }).then(function (r) {
      if (!r.ok) txt($('d-list'), '发起失败：' + r.error);
      setTimeout(loadDownloads, 800);
    });
  });

  // ---------------------------------------------------------------- 网络
  let netOn = true;
  function fmtBytes(n) {
    n = Number(n || 0);
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }
  function loadNet() {
    const kind = ($('n-kind') && $('n-kind').value) || 'all';
    call('net.list', { filter: ($('n-filter') && $('n-filter').value) || '', kind: kind, limit: 100 })
      .then(function (r) {
        const st = r.stats || {};
        // 用户拍板：这名数就叫**请求数**（不是"流量"）；字节那行明确写"应用自搬运字节"，
        // 因为页面内的流量 WebView 不暴露给应用，拿不到就不编（显示 0 B 是真 0，不是坏了）。
        txt($('n-state'), '请求数 ' + (st.total || 0) + '（媒体 ' + (st.media || 0) +
          '、被拦截 ' + (st.blocked || 0) + '）　应用自搬运字节 下 ' + fmtBytes(st.bytesDown) +
          ' / 上 ' + fmtBytes(st.bytesUp) +
          // 如实标状态：没开代理/自代理时字节不会再涨（页面内流量 WebView 不暴露，应用只能数自己搬运过的）
          (r.relay ? '' : ((st.bytesDown || st.bytesUp) ? '（当前没开代理）' : '（没开代理·无字节）')));
        const sel = $('n-kind');
        if (sel && r.kinds) {
          const cur = sel.value;
          sel.innerHTML = '';
          r.kinds.forEach(function (k) {
            const o = document.createElement('option');
            o.value = k; o.textContent = (k === 'all' ? '全部类型' : k);
            sel.appendChild(o);
          });
          sel.value = cur || 'all';
        }
        const host = hostOf('n-list');
        if (!host) return;
        host.innerHTML = '';
        const list = r.list || [];
        if (!list.length) { putEmpty(host, '（还没有记录；打开一个页面就会有）'); return; }
        list.forEach(function (e) {
          const row = mk('div', 'row-item two');   // 两行制：标题大字 + 右列时间/流量（完整）+ 第二行地址
          row.setAttribute('data-title', (e.kind || 'other') + ' 请求');
          row.setAttribute('data-fields', JSON.stringify([
            ['时间', fmtTime(e.ts)], ['类型', e.kind || 'other'], ['地址', e.url],
            ['命中规则', e.rule || '（没有命中拦截规则）'], ['来源页', e.page || ''], ['字节', e.bytes ? fmtBytes(e.bytes) : '']
          ]));
          // 用户定的行规范：第一行标题（大字）＝[类型] 站点；右列＝时间 + 流量（必须完整显示）；
          // 第二行小字＝完整地址；命中的规则并进第二行，不占右列。
          row.appendChild(mk('span', 'nm2', '[' + (e.kind || 'other') + '] ' + (hostOf2(e.url) || '')));
          row.appendChild(mk('span', 'rt', fmtTime(e.ts) + (e.bytes ? ('\u3000' + fmtBytes(e.bytes)) : '')));
          const a = mk('a', 'u', e.url);
          a.setAttribute('data-url', e.url);
          row.appendChild(a);
          if (e.rule) row.appendChild(mk('span', 'u', '\u3000（命中规则 ' + e.rule + '）'));
          host.appendChild(row);
        });
      });
  }
  bindMaybe('n-reload', 'click', loadNet);
  bindMaybe('n-clear', 'click', function () { call('net.clear', {}).then(loadNet); });
  bindMaybe('n-toggle', 'click', function () {
    netOn = !netOn;
    call('net.enabled', { on: netOn }).then(function () {
      txt($('n-state'), (netOn ? '记录：开' : '记录：关') + '（改动立刻生效）');
      loadNet();
    });
  });
  if ($('n-kind')) bindMaybe('n-kind', 'change', loadNet);
  function netTool(action) {
    const host = ($('t-host') && $('t-host').value || '').trim();
    const port = ($('t-port') && $('t-port').value || '').trim();
    const url = ($('t-url') && $('t-url').value || '').trim();
    txt($('t-out'), '跑 ' + action + ' …');
    call('net.tool', { action: action, host: host, port: port, url: url }).then(function (r) {
      txt($('t-out'), j(r));
    });
  }
  bindMaybe('t-resolve', 'click', function () { netTool('resolve'); });
  bindMaybe('t-cert', 'click', function () { netTool('cert'); });
  bindMaybe('t-tcp', 'click', function () { netTool('tcp'); });
  bindMaybe('t-speed', 'click', function () { netTool('speed'); });
  // 用户清单第 18 条：查这个网址的 IP + 请求头 + 响应头（在网络工具里，结果贴在同一块输出里）
  bindMaybe('t-headers', 'click', function () {
    const u = ($('t-url') && $('t-url').value || '').trim() || ($('t-host') && $('t-host').value || '').trim();
    if (!u) { txt($('t-out'), '先填一个网址（下面那个输入框），或填主机名'); return; }
    txt($('t-out'), '正在取 IP 与 header…');
    call('net.tool', { action: 'headers', url: u, host: u }).then(function (r) {
      if (!r || !r.ok) { txt($('t-out'), '取不到：' + ((r && r.error) || '?')); return; }
      const lines = [];
      lines.push('地址　' + r.url);
      lines.push('本地解析 IP　' + ((r.ips || []).join(' , ') || '（解析不到）'));
      lines.push('HTTP 状态　' + r.status);
      lines.push('');
      lines.push('我们发出去的请求头：');
      Object.keys(r.requestHeaders || {}).forEach(function (k) { lines.push('　' + k + ': ' + r.requestHeaders[k]); });
      lines.push('');
      lines.push('服务器回来的响应头（CDN 指纹在这里：Server / Via / X-Cache / CF-*）：');
      Object.keys(r.responseHeaders || {}).forEach(function (k) { lines.push('　' + k + ': ' + r.responseHeaders[k]); });
      lines.push('');
      lines.push(r.note || '');
      txt($('t-out'), lines.join('\n'));
    });
  });
  bindMaybe('t-ip', 'click', function () { netTool('ip'); });

  // ---------------------------------------------------------------- 安全
  // ---------------------------------------------------------------- 控制口安全（绑定 / 令牌 / 敏感接口）
  function loadSecurityHttp() {
    call('security.http', {}).then(function (r) {
      txt($('sh-bind'), (r.running ? '运行中　' : '没开　') + (r.bind || '-') + (r.lan ? '（局域网可达）' : '（只有本机）'));
      txt($('sh-token'), r.tokenSet ? ('要令牌 ' + (r.tokenHint || '')) : '不需要（只绑本机）');
      txt($('sh-rejected'), (r.rejected || 0) + ' 次');
      txt($('sh-verdict'), r.verdict || '-');
    });
  }
  bindMaybe('sh-show', 'click', function () {
    call('security.token', {}).then(function (r) {
      if (!r || !r.ok) { txt($('sh-verdict'), '取不到令牌：' + ((r && r.error) || '')); return; }
      openActs('控制口访问令牌', '令牌：' + r.token + '\n\n用法：http://127.0.0.1:8848/api/status?t=' + r.token +
        '\n或加请求头 X-CDP-Token: ' + r.token + '\n\n（只在本机 / 本 App 里能看到；换一把旧的立刻失效）',
        [{ t: '复制', fn: function () { try { navigator.clipboard.writeText(r.token); } catch (e) {} } }]);
    });
  });
  bindMaybe('sh-copy', 'click', function () {
    call('security.token', {}).then(function (r) {
      if (r && r.ok) { try { navigator.clipboard.writeText(r.token); txt($('sh-verdict'), '令牌已复制到剪贴板'); } catch (e) {} }
    });
  });
  bindMaybe('sh-rotate', 'click', function () {
    call('security.token.rotate', {}).then(function () { txt($('sh-verdict'), '已换一把新令牌（旧令牌立刻失效）'); loadSecurityHttp(); });
  });
  bindMaybe('sh-sens', 'click', function () {
    call('security.http', {}).then(function (r) {
      call('security.sensitive', { on: r.sensitiveLan ? '0' : '1' }).then(function (r2) {
        txt($('sh-verdict'), '敏感接口（局域网）现在是：' + (r2.sensitiveLan ? '打开' : '关闭'));
        loadSecurityHttp();
      });
    });
  });

  function loadSecurity() {
    call('security.page', {}).then(function (r) {
      txt($('s-page'), (r.url || '-') + '\n' + (r.verdict || '') +
        '\n混合内容 http 子资源：' + (r.mixedContent || 0) + ' 个　命中警告名单：' + (r.inBlockList ? '是' : '否'));
    });
  }
  bindMaybe('s-page-go', 'click', loadSecurity);
  bindMaybe('s-cert-go', 'click', function () {
    txt($('sec-out'), '正在取证书…');
    call('security.page', {}).then(function (r) {
      const h = r.host || ($('s-host') ? $('s-host').value.trim() : '');
      call('net.tool', { action: 'cert', host: h }).then(function (c) {
        txt($('sec-out'), j(c));
        const lines = [];
        const c0 = (c && (c.cert || c)) || {};
        if (c0.subject || c0.issuer || c0.notAfter) {
          if (c0.subject) lines.push('主体：' + c0.subject);
          if (c0.issuer) lines.push('签发者：' + c0.issuer);
          if (c0.notBefore) lines.push('生效：' + c0.notBefore);
          if (c0.notAfter) lines.push('到期：' + c0.notAfter);
          if (c0.serial) lines.push('序列号：' + c0.serial);
          if (c0.sigAlg) lines.push('签名算法：' + c0.sigAlg);
          if (c0.sans && c0.sans.length) lines.push('SAN：' + c0.sans.join(', '));
        } else {
          lines.push(JSON.stringify(c, null, 1).slice(0, 900));
        }
        openActs('证书：' + (h || '（当前页面）'), lines.join('\n'),
          [{ t: '复制', fn: function () { try { navigator.clipboard.writeText(lines.join('\n')); } catch (e) {} } }]);
      });
    });
  });
  bindMaybe('s-host', 'change', function () {
    call('net.tool', { action: 'cert', host: $('s-host').value.trim() }).then(function (c) { txt($('sec-out'), j(c)); });
  });

  // ---------------------------------------------------------------- 导出 / 导入
  bindMaybe('b-export', 'click', function () {
    const parts = [];
    [['b-history', 'history'], ['b-bookmarks', 'bookmarks'], ['b-scripts', 'scripts'],
     ['b-settings', 'settings'], ['b-sniff', 'sniff'], ['b-adblock', 'adblock']].forEach(function (p) {
      if ($(p[0]) && $(p[0]).checked) parts.push(p[1]);
    });
    if (!parts.length) { txt($('b-out'), '先勾一个类别'); return; }
    txt($('b-out'), '正在打包 ' + parts.join('/') + ' …');
    call('bundle.export', { parts: parts.join(',') }).then(function (r) {
      txt($('b-out'), (r.msg || r.error || '') + '');
    });
  });
  bindMaybe('b-import', 'click', function () {
    txt($('b-out'), '等你在系统文件选择器里挑一个 CDP 导出包（zip）…');
    call('bundle.import.pick', {}).then(function (r) { txt($('b-out'), r.msg || ''); });
  });

  /**
   * 小弹窗（Cookie 详情 / 行详情 / 确认框都用它）。
   *
   * 规矩（R1，用户踩过好几次）：**一个渲染入口 —— 每次都是"清空 + 按数据重建"，绝不追加节点。**
   * 以前这里把动作栏 `appendChild` 到父节点上，从不清 → 同一个动作栏被追加 N 次：
   * 现象就是用户说的"小窗按键重复多次"（点一次多一排）。现在整块 innerHTML 重建，
   * 结构固定三块（抬头 / 正文 / 动作栏），点多少次都只有一份。
   */
  // 小窗渲染器已经抽成模块（modules/modal.js）：**标题 + 键值对 + 按键（每个键带它自己的小功能）**。
  // 这里只留同名转发，老调用点一个都不用改。
  function showModal(title, text, rows, actions, pairs) {
    if (title && typeof title === 'object') return CDPUI.Modal.show(title);
    return CDPUI.Modal.show(title, text, rows, actions, pairs);
  }

  function loadKeepalive() {
    call('keepalive.state', {}).then(function (r) {
      const st = (r.state || {});
      txt($('ka-state'), st.running ? '正在后台常驻' : '没开（切后台会被系统收紧）');
    });
  }
  bindMaybe('ka-toggle', 'click', function () {
    call('keepalive.state', {}).then(function (r) {
      const on = !!((r.state || {}).running);
      call(on ? 'keepalive.stop' : 'keepalive.start', {}).then(loadKeepalive);
    });
  });

  // ---- 多窗口 / 接口目录 / 翻译 ----
  // 反馈 #7：多窗口不要一横排、不要"新建/删除"文字键，改成一个小浮窗：
  // 竖向排列、每行等高（超出用 …）、行末一个 ✕；新建用末尾的 ＋。
  // 控制台里的「多窗口」整块已按用户要求删除：新建 / 切换 / 关闭 只在浏览器地址栏后面那个「▤」图标里。
  // 原生的 win.new / win.close / win.switch / win.list 接口都还在（那个图标用的就是它们）。
    // （多窗口那三个按键已删：控制台不再提供新建/关闭窗口）

  bindMaybe('h-help', 'click', function () {
    call('api.help', {}).then(function (r) {
      const lines = ['接口目录（共 ' + ((r.common || []).length) + ' 条常用说明）：'];
      (r.common || []).forEach(function (c) {
        lines.push('　' + c.path + (c.params ? ('?' + c.params) : '') + '　→ ' + c.desc);
      });
      lines.push('', r.batch || '', r.space || '', r.privacy || '');
      txt($('h-out'), lines.join('\n'));
    });
  });
  bindMaybe('h-summary', 'click', function () {
    call('kb.summary', {}).then(function (r) { txt($('h-out'), j(r)); });
  });
  bindMaybe('h-batch', 'click', function () {
    call('api.batch', { ops: 'summary|win|scripts' }).then(function (r) {
      txt($('h-out'), '二级接口 batch 返回 ' + (r.count || 0) + ' 段：\n' + j(r.results || r));
    });
  });
  bindMaybe('h-grab', 'click', function () {
    call('page.grab', { max: 2000 }).then(function (r) {
      txt($('h-out'), (r.title || '') + '\n' + String(r.text || '').slice(0, 1200));
    });
  });
  function trState() {
    call('translate.state', {}).then(function (r) {
      txt($('tr-out'), '端点：' + (r.endpoint || '（没配）') + '\n模型目录：' + (r.modelsDir || '') +
        '　里面的文件：' + ((r.modelFiles || []).join('、') || '（空）') + '\n' + (r.note || ''));
    });
  }
  bindMaybe('tr-state', 'click', trState);
  bindMaybe('tr-save', 'click', function () {
    call('settings.set', { translateEndpoint: ($('tr-ep').value || '').trim() }).then(trState);
  });
  bindMaybe('tr-go', 'click', function () {
    const t = ($('tr-text').value || '').trim();
    if (!t) { txt($('tr-out'), '先填要翻的文本'); return; }
    call('translate.do', { text: t, from: 'auto', to: 'zh' }).then(function (r) {
      txt($('tr-out'), r.ok ? ('译：' + r.text) : ('没翻成：' + (r.error || '') + (r.howto ? ('\n' + r.howto) : '')));
    });
  });

  function loadSpaces() {
    call('space.list', {}).then(function (r) {
      txt($('sp-state'), '当前空间：' + (r.current || 'default') + '　全部：' + ((r.list || []).join('、') || 'default'));
    });
  }
  bindMaybe('sp-use', 'click', function () {
    const n = ($('sp-name').value || '').trim();
    if (!n) { txt($('sp-state'), '先填个空间名'); return; }
    call('space.use', { name: n }).then(function (r) {
      txt($('sp-state'), '已切到空间：' + (r.current || n) + '（历史/书签/脚本/设置都换成这一套）');
      loadSpaces();
    });
  });
  bindMaybe('sp-default', 'click', function () { call('space.use', { name: 'default' }).then(loadSpaces); });
  bindMaybe('sp-list', 'click', loadSpaces);
  bindMaybe('ai-ctx', 'click', function () {
    txt($('ai-out'), '取正文中…');
    call('ai.context', {}).then(function (r) {
      const t = (r.text || '');
      txt($('ai-out'), '标题：' + (r.title || '') + '\n正文字数：' + (r.textLen || 0) +
        '　链接 ' + ((r.links || []).length) + ' 条　表单 ' + ((r.forms || []).length) + ' 个\n' +
        (r.privacy || '') + '\n\n' + t.slice(0, 400) + (t.length > 400 ? '…' : ''));
    });
  });
  bindMaybe('ai-doc', 'click', function () {
    txt($('ai-out'),
      'GET /api/ai/context?space=x        → 当前页正文/链接/表单字段/按钮（不含输入值、不含 cookie）\n' +
      'GET /api/ai/act?do=click&selector=#x\n' +
      'GET /api/ai/act?do=type&selector=#q&value=hello\n' +
      'GET /api/ai/act?do=goto&url=https://…\n' +
      'GET /api/ai/act?do=search&q=关键词\n' +
      'GET /api/space                      → 当前空间与全部空间\n' +
      'GET /api/space/use?name=ai-a        → 切空间（历史/书签/脚本/设置独立）\n' +
      'GET /api/keepalive?on=1             → 后台前台化（熄屏继续跑）');
  });

  // ---------------------------------------------------------------- 省电
  // 第 10 条：排序用**两个按键**（不用下拉 —— 这台 WebView 里下拉的 change 事件不触发，
  // 查了三轮才发现；按键既好真手指点，也一眼看得懂当前按哪种排）
  let POWER_SORT = 'board';
  function setPowerSort(mode) {
    POWER_SORT = mode;
    const b = $('pw-sort-board'), h = $('pw-sort-hot');
    if (b) b.classList.toggle('on', mode === 'board');
    if (h) h.classList.toggle('on', mode === 'hot');
    loadPower();
  }
  bindMaybe('pw-sort-board', 'click', function () { setPowerSort('board'); });
  bindMaybe('pw-sort-hot', 'click', function () { setPowerSort('hot'); });

  function loadPower() {
    call('power.state', {}).then(function (r) {
      const st = r.state || {};
      const flags = st.flags || {};
      const host = hostOf('pw-list');
      if (!host) return;
      host.innerHTML = '';
      // 第 10 条：两类排序（按板块 / 按耗电排行）。排行用的是**估算权重**，行里会写明来源。
      if (st.items && st.items.length) {
        const mode = POWER_SORT || 'board';
        const items = st.items.slice().sort(function (a, b) {
          if (mode === 'hot') return (b.est || 0) - (a.est || 0);
          const g = (a.group || '').localeCompare(b.group || '');
          return g !== 0 ? g : (b.est || 0) - (a.est || 0);
        });
        items.forEach(function (it) {
          const row = mk('div', 'row-item');
          row.setAttribute('data-title', it.label || it.name);
          row.setAttribute('data-fields', JSON.stringify([
            ['板块', it.group || ''], ['状态', it.on ? '开着' : '关着'],
            ['开销', '估算 ' + (it.est || 0) + '／5　（' + (it.costSrc || '估算') + '）'],
          ]));
          row.appendChild(mk('span', 'nm2', (it.on ? '● ' : '○ ') + (it.label || it.name)));
          row.appendChild(mk('span', 'u', (it.group || '') + '　估算 ' + (it.est || 0)));
          const b = mk('button', 'mini2', it.on ? '关掉' : '打开');
          b.addEventListener('click', function () {
            call('power.set', { name: it.name, on: !it.on }).then(loadPower);
          });
          row.appendChild(b);
          host.appendChild(row);
        });
        if (window.CDPUI && CDPUI.List) CDPUI.List.compactAll();
        txt($('pw-state'), '录制中：' + (st.rec ? '是' : '否'));
        return;
      }
      ['metrics', 'sniff', 'adblock', 'scripts', 'rec'].forEach(function (k) {
        const on = !!st[k];
        const row = mk('div', 'row-item');
        row.appendChild(mk('span', 'nm2', (on ? '● ' : '○ ') + k + '：' + (on ? '开着' : '关着')));
        const b = mk('button', 'mini2', on ? '关掉' : '打开');
        b.addEventListener('click', function () {
          call('power.set', { name: k, on: !on }).then(loadPower);
        });
        row.appendChild(b);
        const tip = mk('span', 'u', flags[k] || '');
        row.appendChild(tip);
        host.appendChild(row);
      });
      txt($('pw-state'), '录制中：' + (st.rec ? '是' : '否'));
    });
  }
  // ---------------------------------------------------------------- 后台播放（熄屏继续放）
  function loadBgPlay() {
    call('keepalive.state', {}).then(function (r) {
      const st = (r.state || r || {});
      const on = !!st.media;
      txt($('bg-state'), on ? '开着：熄屏 / 切后台继续放（前台服务 mediaPlayback + 音频焦点 + 唤醒锁）'
                            : ('关着' + (st.running ? '（但后台前台化还开着：控制口/脚本继续跑）' : '（App 也不会在后台长期存活）')));
    });
  }
  bindMaybe('bg-toggle', 'click', function () {
    call('keepalive.state', {}).then(function (r) {
      const on = !!((r.state || r || {}).media);
      call('keepalive.media', { on: on ? '0' : '1' }).then(function (r2) {
        txt($('bg-state'), r2.media ? '已打开后台播放（熄屏也继续放）' : '已关闭后台播放（音频焦点与唤醒锁已还回）');
        loadBgPlay();
      });
    });
  });

  bindMaybe('pw-reload', 'click', loadPower);
  bindMaybe('pw-save', 'click', function () {
    call('power.save', {}).then(function (r) { loadPower(); txt($('pw-state'), '已一键省电'); });
  });
  bindMaybe('pw-restore', 'click', function () {
    ['metrics', 'sniff', 'adblock', 'scripts'].forEach(function (k) { call('power.set', { name: k, on: true }); });
    setTimeout(function () { loadPower(); txt($('pw-state'), '已全部打开'); }, 600);
  });

  // ---------------------------------------------------------------- 介绍
  function loadIntro() {
    call('status', {}).then(function (s) {
      const lines = [];
      lines.push('项目：CDP —— 手机上的可编程浏览器（录制 / 回放 / 用户脚本 / 对外控制口）');
      lines.push('版本：' + (s.version || '-'));
      lines.push('包名：' + (s.pkg || 'dev.cdp') + '　目标 SDK：' + (s.targetSdk || '-'));
      lines.push('对外接口：' + ((s.endpoints || []).length) + ' 个（「接口」栏目里可看全量清单）');
      lines.push('录制状态：' + (s.recording ? '录制中' : '没在录') + '　隐身：' + (s.incognito ? '开' : '关'));
      lines.push('归档：/vol1/1000/airesults/cdp/（README + 验证报告 + 七组验收存证 + 源码快照）');
      lines.push('主页已改简洁版：只剩搜索框和几个入口，「介绍」挪到了这里。');
      txt($('in-body'), lines.join('\n'));
    });
  }

  bindMaybe('in-boards', 'click', function () {
    const list = (window.__cdpBoards ? window.__cdpBoards() : []);
    const bad = (window.__cdpBoardReport ? window.__cdpBoardReport() : []);
    const host = hostOf('in-board-list');
    if (!host) return;
    host.innerHTML = '';
    list.sort(function (a, b) { return (a.tab + a.order) < (b.tab + b.order) ? -1 : 1; });
    list.forEach(function (b) {
      const row = mk('div', 'row-item');
      row.appendChild(mk('span', 'nm2', b.board));
      row.appendChild(mk('span', 't', b.tab + ' #' + b.order));
      row.appendChild(mk('span', 'u', b.w + '×' + b.h + (b.visible ? '' : '（当前不可见）') + (b.mounted ? '' : '（没挂上！）')));
      host.appendChild(row);
    });
    if (bad.length) {
      putEmpty(host, '⚠ 登记表有问题：' + JSON.stringify(bad));
    } else {
      putEmpty(host, '共 ' + list.length + ' 个板块，登记表没有重名/挂错栏目');
    }
  });

  // 密码库板块（第 4 条）
  function pvState() {
    call('vault.state', {}).then(function (r) {
      txt($('pv-out'), (r.unlocked ? ('已解锁（还剩 ' + r.secondsLeft + ' 秒），条目 ' + r.count + ' 条')
        : '锁着（看/改都要先过锁屏验证）') + '\n' + (r.note || ''));
      if (r.unlocked) pvList();
    });
  }
  // 画一条密码条目（**只在这一处画**：行里的键值走 data-fields，动作走行里的按钮 → list.js 会搬进小窗）
  function pvRow(it, after) {
    const row = mk('div', 'row-item');
    row.setAttribute('data-title', it.site || '（没记站点）');
    row.setAttribute('data-fields', JSON.stringify([
      ['站点', it.site || ''],
      ['账号', it.user || ''],
      ['密码', it.pass || '（点「改」看/改）'],
      ['时间', it.time || it.updated || it.at || ''],
    ]));
    row.appendChild(mk('span', 'nm2', it.site || '（没记站点）'));
    row.appendChild(mk('span', 'u', it.user || ''));
    [['改', function () { pvEdit(it, after); }],
     ['填这页', function () { call('page.vaultfill', { site: it.site }).then(function (x) { txt($('pv-out'), j(x)); }); }],
     ['看密码', function () { call('vault.get', { id: it.id }).then(function (g) {
        txt($('pv-out'), g.ok ? ('密码：' + (g.item.pass || '')) : (g.error || '读不到'));
     }); }],
     ['删', function () { call('vault.del', { id: it.id }).then(after || pvList); }]].forEach(function (p) {
      const b = mk('button', 'mini2', p[0]); b.addEventListener('click', p[1]); row.appendChild(b);
    });
    return row;
  }

  // 改一条：小窗里字段可编辑（密码也在里面），确定后读回生效
  function pvEdit(it, after) {
    call('vault.get', { id: it.id }).then(function (g) {
      const cur = (g && g.item) || it;
      CDPUI.Modal.open({
        title: '改这一条：' + (cur.site || ''),
        fields: [
          { key: 'site', label: '站点', value: cur.site || '', editable: true },
          { key: 'user', label: '账号', value: cur.user || '', editable: true },
          { key: 'pass', label: '密码', value: cur.pass || '', editable: true },
          { key: 'time', label: '时间', value: cur.time || cur.updated || cur.at || '', editable: false },
        ],
        onSubmit: function (v) {
          call('vault.save', { id: it.id, site: v.site, user: v.user, pass: v.pass }).then(function () {
            call('vault.get', { id: it.id }).then(function (g2) {
              txt($('pv-out'), '已保存并读回：' + j((g2 && g2.item) || {}));
              (after || pvList)();
            });
          });
        },
      });
    });
  }

  function pvList() {
    call('vault.list', {}).then(function (r) {
      const host = hostOf('pv-list');
      if (!host) return;
      host.innerHTML = '';
      if (!r.ok) { putEmpty(host, r.needUnlock ? '没解锁，条目看不到（这是设计）' : (r.error || '读不到')); return; }
      (r.list || []).forEach(function (it) { host.appendChild(pvRow(it)); });
      if (!(r.list || []).length) putEmpty(host, '还没有条目');
    });
  }

  // 隐藏的全局面板（用户第 20 条）：没解锁就只给一句"先过锁屏"，解锁了才出现
  function pvOpenAll() {
    call('vault.state', {}).then(function (st) {
      const panel = $('pv-panel');
      if (!st || !st.unlocked) {
        if (panel) panel.classList.add('hidden');
        txt($('pv-out'), '要先过锁屏验证：点上面的「解锁（锁屏验证）」过一下，再来看全部密码');
        return;
      }
      call('vault.list', {}).then(function (r) {
        const host = $('pv-all-list');
        if (!host) return;
        host.innerHTML = '';
        if (!r.ok) { putEmpty(host, r.error || '读不到'); return; }
        (r.list || []).forEach(function (it) { host.appendChild(pvRow(it, pvOpenAll)); });
        if (!(r.list || []).length) putEmpty(host, '还没有条目');
        $('pv-all-count').textContent = '共 ' + (r.list || []).length + ' 条';
        if (panel) panel.classList.remove('hidden');
      });
    });
  }
  bindMaybe('pv-all', 'click', pvOpenAll);
  bindMaybe('pv-all-close', 'click', function () { $('pv-panel').classList.add('hidden'); });
  bindMaybe('pv-state', 'click', pvState);
  bindMaybe('pv-unlock', 'click', function () {
    call('vault.unlock', {}).then(function (r) {
      txt($('pv-out'), r.prompted ? '已经叫出锁屏验证，验证通过后 5 分钟内可查看/修改'
        : ('解锁没起来：' + (r.error || '')) + (r.howto ? ('\n' + r.howto) : ''));
    });
  });
  bindMaybe('pv-lock', 'click', function () { call('vault.lock', {}).then(pvState); });
  bindMaybe('pv-save', 'click', function () {
    call('vault.save', { site: $('pv-site').value, user: $('pv-user').value, pass: $('pv-pass').value }).then(function (r) {
      txt($('pv-out'), r.ok ? ('存好了（' + r.id + '）') : (r.error || '存失败'));
      $('pv-pass').value = ''; pvList();
    });
  });
  bindMaybe('pv-frompage', 'click', function () {
    call('page.login', {}).then(function (r) {
      if (!r.ok) { txt($('pv-out'), '这一页没抓到登录框：' + (r.error || '')); return; }
      $('pv-site').value = r.site || ''; $('pv-user').value = r.user || ''; $('pv-pass').value = r.pass || '';
      txt($('pv-out'), '从这一页抓到了站点/用户名/密码，确认后点「存进密码库」（会先要锁屏验证）');
    });
  });
  bindMaybe('pv-gen', 'click', function () {
    call('vault.gen', {
      len: parseInt($('pv-len').value || '16', 10), upper: $('pv-up').checked, lower: $('pv-low').checked,
      digit: $('pv-dig').checked, sym: $('pv-sym').checked
    }).then(function (r) {
      if (!r.ok) { txt($('pv-out'), r.error || '生成失败'); return; }
      $('pv-pass').value = r.password;
      txt($('pv-out'), '随机密码（' + r.len + ' 位）：' + r.password + '\n已经填进上面的密码框，点「存进密码库」即可');
    });
  });

  // 伪终端（App 内命令解析器；Android 不允许应用 fork 系统 shell）
  const TERM_HIST = [];
  let TERM_HI = -1;
  function termRun(cmd) {
    call('term.run', { cmd: cmd }).then(function (r) {
      const box = $('tm-out');
      box.textContent += '\n' + ($('tm-cwd') && $('tm-cwd').textContent ? '' : '') + '~ $ ' + cmd + '\n' +
        String(r.out || (r.ok ? '' : '(失败)')) + '\n';
      if (r.cwd) txt($('tm-cwd'), r.cwd);
      box.scrollTop = box.scrollHeight;
      if (!TERM_HIST.length || TERM_HIST[TERM_HIST.length - 1] !== cmd) TERM_HIST.push(cmd);
      TERM_HI = -1;
    });
  }
  bindMaybe('tm-run', 'click', function () {
    const v = $('tm-in').value.trim();
    if (!v) return;
    $('tm-in').value = '';
    if (v === 'clear') { txt($('tm-out'), ''); return; }
    termRun(v);
  });
  bindMaybe('tm-in', 'keydown', function (e) {
    if (e.key === 'Enter') { $('tm-run').click(); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); if (TERM_HIST.length) { TERM_HI = TERM_HI < 0 ? TERM_HIST.length - 1 : Math.max(0, TERM_HI - 1); $('tm-in').value = TERM_HIST[TERM_HI] || ''; } }
    if (e.key === 'ArrowDown') { e.preventDefault(); if (TERM_HI >= 0) { TERM_HI = Math.min(TERM_HIST.length - 1, TERM_HI + 1); $('tm-in').value = TERM_HIST[TERM_HI] || ''; } }
  });
  bindMaybe('tm-help', 'click', function () { termRun('help'); });
  bindMaybe('tm-ls', 'click', function () { termRun('ls -l'); });

  // ---------------------------------------------------------------- 拦截与名单（一份规则库：广告域名 / 隐藏选择器 / 站点警告名单）
  const BL_KIND = { ad: '广告/统计', css: '隐藏选择器', warn: '警告名单' };
  function blKind() { return ($('bl-kind') && $('bl-kind').value) || 'ad'; }
  function blAdd(kind, v) {
    if (kind === 'ad') return call('adblock.add', { rule: v });
    if (kind === 'css') return call('adblock.cosmetic.add', { sel: v });
    return call('adblock.warn.add', { host: v });
  }
  function blRemove(kind, v) {
    if (kind === 'ad') return call('adblock.remove', { rule: v });
    if (kind === 'css') return call('adblock.cosmetic.del', { sel: v });
    return call('adblock.warn.remove', { host: v });
  }
  /** 三类规则共用同一个渲染入口（清空重建，不追加节点） */
  function loadBlockRules() {
    call('adblock.get', {}).then(function (r) {
      txt($('ab-state'), (r.enabled ? '已开启' : '已关闭') + '　广告规则 ' + (r.rules || 0) + ' 条 · 隐藏规则 ' +
        ((r.cosmetic || []).length) + ' 条 · 警告名单 ' + ((r.warn || []).length) + ' 条 · 已拦下 ' + (r.blocked || 0) + ' 个请求');
      if ($('ab-mode') && document.activeElement !== $('ab-mode')) $('ab-mode').value = r.mode || 'both';
      txt($('ab-mode-out'), r.blockedNote || '-');
      txt($('s-block-state'), '站点警告名单（' + ((r.warn || []).length) + '）：' + (((r.warn || []).join('、')) || '空') +
        '　命中只在页面顶部挂警告条，不阻断加载');
      const host = hostOf('ab-rules-box');
      if (host) {
        const k = blKind();
        const rows = [];
        if (k === 'ad') (r.ruleList || []).forEach(function (x) { rows.push(x); });
        if (k === 'css') (r.cosmetic || []).forEach(function (x) { rows.push(x); });
        if (k === 'warn') (r.warn || []).forEach(function (x) { rows.push(x); });
        if (!rows.length) putEmpty(host, '（这一类还没有规则；上面输入一条点「加进这一类」）');
        rows.slice(0, 300).forEach(function (x) {
          const row = mk('div', 'row-item');
          row.appendChild(mk('span', 'u', x));
          const rm = mk('button', 'mini2', '删除');
          rm.addEventListener('click', function () { blRemove(k, x).then(loadBlockRules); });
          const cp = mk('button', 'mini2', '复制');
          cp.addEventListener('click', function () { try { navigator.clipboard.writeText(x); } catch (e) {} });
          row.appendChild(rm); row.appendChild(cp);
          host.appendChild(row);
        });
      }
      const rh = hostOf('ab-recent');
      if (rh) {
        const rec = r.recent || [];
        if (!rec.length) putEmpty(rh, '（还没拦下什么）');
        rec.slice(0, 12).forEach(function (line) { rh.appendChild(mk('div', 'row-item', line)); });
      }
    });
  }
  bindMaybe('ab-toggle', 'click', function () {
    call('adblock.get', {}).then(function (r) {
      call('adblock.set', { enabled: !r.enabled }).then(loadBlockRules);
    });
  });
  bindMaybe('ab-mode', 'change', function () {
    const m = $('ab-mode').value;
    call('adblock.mode', { mode: m }).then(function (r) {
      txt($('ab-mode-out'), '模式=' + r.mode + (r.mode === 'hide' ? '（只隐藏不阻断，避免被 JS 探测）' : ''));
      call('cosmetic.push', {});
      loadBlockRules();
    });
  });
  bindMaybe('bl-kind', 'change', loadBlockRules);
  bindMaybe('ab-add', 'click', function () {
    const v = ($('ab-new').value || '').trim();
    if (!v) { txt($('ab-mode-out'), '先在输入框里写一条'); return; }
    blAdd(blKind(), v).then(function (r) {
      $('ab-new').value = '';
      txt($('ab-mode-out'), '已加入「' + BL_KIND[blKind()] + '」' + (r && r.ok === false ? '（这条已经在里面了）' : ''));
      if (blKind() === 'css') call('cosmetic.push', {});
      loadBlockRules();
    });
  });
  bindMaybe('ab-remove', 'click', function () {
    const v = ($('ab-new').value || '').trim();
    if (!v) { txt($('ab-mode-out'), '先在输入框里写一条'); return; }
    blRemove(blKind(), v).then(function () { $('ab-new').value = ''; loadBlockRules(); });
  });
  bindMaybe('ab-reset', 'click', function () {
    const k = blKind();
    if (k === 'css') return call('adblock.cosmetic.reset', {}).then(loadBlockRules);
    if (k === 'warn') return call('adblock.warn.clear', {}).then(loadBlockRules);
    call('adblock.reset', {}).then(loadBlockRules);
  });
  bindMaybe('ab-clear', 'click', function () {
    const k = blKind();
    if (k === 'css') return call('adblock.cosmetic', {}).then(function (r) {
      const l = r.list || [];
      l.forEach(function (x) { call('adblock.cosmetic.del', { sel: x }); });
      setTimeout(loadBlockRules, 300);
    });
    if (k === 'warn') return call('adblock.warn.clear', {}).then(loadBlockRules);
    call('adblock.clear', {}).then(loadBlockRules);
  });
  bindMaybe('ab-cos-push', 'click', function () { call('cosmetic.push', {}).then(function (r) { txt($('ab-mode-out'), j(r)); }); });

  // ---------------------------------------------------------------- 阅读 / 停留时间（反馈：单独栏目 + 排序/搜索/删除/饼图）
  function fmtDur(ms) {
    const s = Math.round((ms || 0) / 1000);
    if (s < 60) return s + ' 秒';
    const m = Math.floor(s / 60), h = Math.floor(m / 60);
    return (h > 0 ? (h + ' 小时 ' + (m % 60) + ' 分') : (m + ' 分 ' + (s % 60) + ' 秒'));
  }
  function loadRead() {
    const f = ($('rd-filter') && $('rd-filter').value || '').trim();
    const sort = ($('rd-sort') && $('rd-sort').value) || 'time';
    call('read.list', { filter: f, sort: sort, limit: 300 }).then(function (r) {
      const host = hostOf('rd-list');
      if (!host) return;
      host.innerHTML = '';
      (r.list || []).forEach(function (it) {
        // 用户定的排版（和历史/书签一致）：
        //   第一行＝标题（大字号，超出 …）+ 时长（**固定宽度**靠右）；第二行＝链接（小字号，右移一格）
        const row = mk('div', 'row-item two');   // 两行制（本来就是这个结构，补统一样式）
        const head = mk('div', 'head');
        head.appendChild(mk('span', 'nm2', (it.title || it.host || '')));
        head.appendChild(mk('span', 'when', fmtDur(it.ms)));
        row.appendChild(head);
        row.appendChild(mk('span', 'u', (it.url || '')));
        // 这两个按钮会被"行小窗"机制收进小窗里（行只留一行摘要 + ⋯）
        const open = mk('button', 'mini2', '打开');
        // 打开这一页 = 要回网页看 → 顺手把控制台收起来（用户要求："点完就跳出这个 bar、返回网页"）
    open.addEventListener('click', function () { call('nav.open', { url: it.url }).then(backToPage); });
        row.appendChild(open);
        const del = mk('button', 'mini2', '删除');
        del.addEventListener('click', function () { call('read.del', { id: it.id }).then(loadRead); });
        row.appendChild(del);
        host.appendChild(row);
      });
      if (!(r.list || []).length) putEmpty(host, f ? '没有匹配的记录' : '还没有记录（用一会儿浏览器就会出现）');
    });
    loadStats();
  }

  /** 字节给人看（统计共用，别在两处各写一份） */
  function fmtBytes(v) {
    v = v || 0;
    const u = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (i === 0 ? v : (v >= 100 ? v.toFixed(0) : v.toFixed(1))) + ' ' + u[i];
  }
  function statsSrc() { return ($('ch-src') && $('ch-src').value) || 'read'; }
  function statsRange() { return ($('ch-range') && $('ch-range').value) || 'live'; }
  function hhmmss(ms) {
    const d = new Date(ms || Date.now());
    const p = function (n) { return (n < 10 ? '0' : '') + n; };
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }
  /** 统计板块是不是正显示着（自动刷新只在看得见的时候跑，别在后台空转） */
  function statsVisible() {
    const t = $('tab-plugins');
    return !!(t && t.classList.contains('on'));
  }
  /**
   * 实时那档的"现在"：正在读什么 · 今天多久 · 这一段连续多久 · 最近几条（数据表）。
   * 用户的要求："按实时统计…统计完之后它保存下来，数据表，刷新之后保存下来，可以实时刷新统计"。
   */
  function renderLive(st, src) {
    const box = $('ch-table');
    const now = st.now || Date.now();
    if (src === 'net') {
      txt($('ch-sum'), '今天 ↓' + fmtBytes(st.todayDown || 0) + ' ↑' + fmtBytes(st.todayUp || 0) +
        '　总计 ↓' + fmtBytes(st.bytesDown) + ' ↑' + fmtBytes(st.bytesUp) +
        '　请求 ' + (st.total || 0) + ' 条　最后更新 ' + hhmmss(now));
    } else {
      const u = st.usage || {}, td = st.today || {};
      // "正在读"＝最近 2 分钟内有更新的那条（还在读的话，时长会一直涨）
      const recs = (st.recent || []).filter(function (r) { return (r.to || 0) >= now - 120000; });
      const cur = recs.length ? recs[0] : null;
      let line = '';
      if (cur) {
        line = '正在读 ' + ((cur.title || cur.host || '').slice(0, 24) || cur.url) +
          '　' + fmtDur(cur.ms) + '（+' + fmtDur(Math.max(0, now - (cur.to || now))) + '）　';
      }
      txt($('ch-sum'), line + '今天 ' + fmtDur(td.ms || 0) + '（前台 ' + fmtDur(td.msFg || 0) +
        ' / 后台 ' + fmtDur(td.msBg || 0) + '，' + (td.count || 0) + ' 条）　连续使用 ' + fmtDur(u.continuousMs || 0) +
        '　最后更新 ' + hhmmss(now));
    }
    if (!box) return;
    const rows = src === 'net' ? [] : (st.recent || []).slice(0, 10);
    if (!rows.length) { box.innerHTML = ''; return; }
    box.innerHTML = '<div class="hint dim">最近 10 条（存在本机，刷新不丢）</div>' +
      '<div class="listbox">' + rows.map(function (r) {
        return '<div class="row-item"><span class="t">' + hhmmss(r.to || r.ts) + '</span>' +
          '<span class="nm2">' + ((r.title || r.host || '').slice(0, 30)) + '</span>' +
          '<span class="u">' + fmtDur(r.ms) + '　' + (r.host || '') + '</span></div>';
      }).join('') + '</div>';
  }
  /**
   * 一块「统计」同时喂两个数据源（阅读时长 / 网络流量）——用户要的就是这个形态：
   * 采集各自管，统计与绘图交给同一个东西。所以这里只有这一个函数、只有一套图。
   */
  function loadStats(silent) {
    const src = statsSrc(), range = statsRange();
    // "实时"这档：数据还是按小时分桶（"这个小时现在多少"），但摘要换成"现在"的口径
    const q = range === 'live' ? 'hour' : range;
    call(src === 'net' ? 'net.stats' : 'read.stats', { range: q }).then(function (st) {
      st = st || {};
      const barBox = $('ch-bar'), pieBox = $('ch-pie');
      if (!barBox || !pieBox) return;
      if (src === 'net') {
        Chart.bar(barBox, {
          bars: st.bars || [],
          series: [{ key: 'value', name: '下行', color: '#4a8cff' }, { key: 'value2', name: '上行', color: '#8f7bff' }],
          fmt: Chart.fmt.bytes, fmtShort: Chart.fmt.bytesShort,
          empty: '没有可统计的字节（页面内流量拿不到；用应用内下载、或开着本地中继时才有）'
        });
        Chart.pie(pieBox, {
          slices: st.slices || [], fmt: Chart.fmt.bytes, centerLabel: '共',
          empty: '还没有字节数据'
        });
        txt($('ch-sum'), '共 ↓' + fmtBytes(st.bytesDown) + ' ↑' + fmtBytes(st.bytesUp) +
          '（其中前台 ↓' + fmtBytes(st.bytesDownFg) + ' ↑' + fmtBytes(st.bytesUpFg) + '）　请求 ' + (st.total || 0) + ' 条');
        txt($('ch-note'), st.note || '');
      } else {
        Chart.bar(barBox, {
          bars: st.bars || [],
          series: [{ key: 'value', name: '前台', color: '#4a8cff' }, { key: 'value2', name: '后台', color: '#8f7bff' }],
          fmt: Chart.fmt.ms, fmtShort: Chart.fmt.msShort,
          empty: '还没有停留时间记录（用一会儿浏览器就会出现）'
        });
        Chart.pie(pieBox, {
          slices: (st.byHost || []).map(function (h) { return { name: h.name, value: h.value, pct: h.pct }; }),
          fmt: Chart.fmt.ms, centerLabel: '合计',
          empty: '还没有记录'
        });
        const u = st.usage || {};
        txt($('ch-sum'), '共 ' + (st.count || 0) + ' 条　合计 ' + fmtDur(st.total) +
          '（前台 ' + fmtDur(st.totalFg) + ' / 后台 ' + fmtDur(st.totalBg) + '）　连续使用 ' + (u.sessions || 0) +
          ' 段，最长 ' + fmtDur(u.longestMs) + (u.continuousMs ? '（当前这段 ' + fmtDur(u.continuousMs) + '）' : ''));
        txt($('ch-note'), st.note || '');
      }
      if (range === 'live') {
        renderLive(st, src);        // 实时：正在读什么 / 今天多久 / 这一段连续多久 + 最近 10 条（数据表）
      } else {
        const tb = $('ch-table');
        if (tb) tb.innerHTML = '';
      }
    });
  }
  // 实时那档自动刷新（每 5 秒）：只在统计板块看得见的时候跑，切走就停，不在后台空转
  setInterval(function () {
    if (statsVisible() && statsRange() === 'live') loadStats(true);
  }, 5000);
  // 网络栏目里的"统计图"是**跳转入口**（同一个板块，不重复挂控件）：
  // 点它把统计的价格源切到"网络流量"，再跳到统计所在栏目
  bindMaybe('n-to-stats', 'click', function () {
    if ($('ch-src')) $('ch-src').value = 'net';
    loadStats();
    setTab('plugins');
    const b = document.querySelector('[data-board="read-chart"]');
    if (b && b.scrollIntoView) b.scrollIntoView({ block: 'center' });
  });
  bindMaybe('rd-go', 'click', loadRead);
  bindMaybe('ch-go', 'click', loadStats);
  bindMaybe('ch-src', 'change', loadStats);
  bindMaybe('ch-range', 'change', loadStats);
  bindMaybe('rd-filter', 'keydown', function (e) { if (e.key === 'Enter') loadRead(); });
  bindMaybe('rd-sort', 'change', loadRead);
  bindMaybe('rd-clear', 'click', function () {
    showModal('清空全部阅读记录？', '这些只是本机的停留时间统计，清掉不影响浏览。', null, [
      { t: '清空', danger: true, fn: function () { document.getElementById('cdp-modal').style.display = 'none'; call('read.clear', {}).then(loadRead).then(loadStats); } },
      { t: '取消', fn: function () { document.getElementById('cdp-modal').style.display = 'none'; } }
    ]);
  });

  // 视频：状态 / 信息 / 系统播放器（用户反馈 #5、#19）
  function vList() {
    call('video.list', {}).then(function (r) {
      const vs = (r && (r.videos || r.list)) || [];
      if (!vs.length) { txt($('v-out'), '这一页没识别到 <video>/<audio>（播放器可能在跨域 iframe 里）→ 看「嗅探」抓到的流'); return; }
      txt($('v-out'), vs.map(function (v, i) {
        return (i + 1) + '. ' + (v.tag || 'video') + '　时长 ' + (v.duration || '?') + 's　当前 ' + (v.currentTime || 0) +
          's　' + (v.paused ? '暂停' : '播放中') + (v.muted ? '（静音）' : '') +
          (v.inFrame ? '　[在 iframe 里：回放点不到]' : '') + '\n　　' + (v.src || '（没有 src）');
      }).join('\n'));
    });
  }
  bindMaybe('v-refresh', 'click', vList);
  bindMaybe('v-info', 'click', function () {
    const u = prompt('要查哪个视频地址？（留空就用当前页第一个视频）') || '';
    if (u) { call('video.info', { url: u }).then(function (r) { txt($('v-out'), j(r)); }); return; }
    call('video.list', {}).then(function (r) {
      const vs = (r && (r.videos || r.list)) || [];
      if (!vs.length || !vs[0].src) { txt($('v-out'), '当前页没有可查地址的视频'); return; }
      call('video.info', { url: vs[0].src }).then(function (x) { txt($('v-out'), j(x)); });
    });
  });
  bindMaybe('v-sys', 'click', function () {
    call('video.list', {}).then(function (r) {
      const vs = (r && (r.videos || r.list)) || [];
      const u = (vs[0] && vs[0].src) || '';
      if (!u) { txt($('v-out'), '先让页面里有个视频（或用嗅探到的地址），再点这个'); return; }
      call('player.system', { url: u }).then(function (x) { txt($('v-out'), j(x)); });
    });
  });
  // 下载方式
  function dlState() {
    call('settings.get', {}).then(function (r) {
      const v = ((r && r.settings) || {}).downloadVia || 'app';
      ['aria2Rpc', 'aria2Token', 'aria2Dir'].forEach(function (k) {
        const el = $('st-' + k.replace('aria2', 'aria2-').toLowerCase());
        if (el) el.value = ((r && r.settings) || {})[k] || '';
      });
      if ($('st-dl')) $('st-dl').value = v === 'system' ? 'system' : 'app';
      txt($('st-dl-out'), v === 'system' ? '当前：系统下载器（会进系统下载列表）' : '当前：应用内自己下（默认）');
    });
  }
  if ($('st-dl-apply')) bindMaybe('st-aria2-apply', 'click', function () {
   applySettings({
     aria2Rpc: $('st-aria2-rpc').value, aria2Token: $('st-aria2-token').value, aria2Dir: $('st-aria2-dir').value
   }, 'aria2 设置已保存');
 });
 bindMaybe('st-aria2-test', 'click', function () {
   txt($('st-dl-out'), '正在连 aria2…');
   call('dl.aria2check', {}).then(function (r) {
     txt($('st-dl-out'), r && r.ok ? ('aria2 通了（版本 ' + (r.version || '?') + '）') : ('连不上：' + ((r && r.error) || '?')));
   });
 });
 bindMaybe('st-dl-apply', 'click', function () {
    call('settings.set', { settings: { downloadVia: $('st-dl').value } }).then(dlState);
  });

  // 拦截规则由 loadBlockRules() 统一渲染（旧的 loadAdblock 已合并进去）
  // ---------------------------------------------------------------- 接口
  function loadApi() {
    call('status', {}).then(function (s) {
      const h = s.http || {};
      txt($('a-state'), (h.running ? '运行中 ' + (h.bind || '') + ':' + (h.port || '') : '未启动') + '，请求数 ' + (h.requests || 0));
      txt($('a-list'), (s.endpoints || []).join('\n'));
    });
  }
  bindMaybe('a-start', 'click', function () {
    call('http.start', { lan: false }).then(loadApi);
  });
  bindMaybe('a-start-lan', 'click', function () {
    call('http.start', { lan: true }).then(loadApi);
  });
  bindMaybe('a-stop', 'click', function () { call('http.stop', {}).then(loadApi); });

  // ---------------------------------------------------------------- 日志
  function loadLog() {
    call('log.tail', { n: 120 }).then(function (r) {
      txt($('l-out'), (r.lines || []).join('\n') || '（没有日志）');
    });
  }
  bindMaybe('l-reload', 'click', loadLog);
  bindMaybe('l-events', 'click', function () {
    call('events.tail', { n: 60 }).then(function (r) { txt($('l-out'), j(r.events || r)); });
  });

  // ---------------------------------------------------------------- 设置（模式 / 搜索 / 代理 / 隐身）
  function loadSettings() {
    call('settings.get', {}).then(function (r) {
      const s = (r && r.settings) || {};
      setVal('st-ua', s.uaMode || 'phone');
      setVal('st-ua-custom', s.customUa);
      setVal('st-search', s.search || 'bing');
      setVal('st-search-custom', s.customSearch);
      setVal('st-px-type', s.proxyType || 'none');
      setVal('st-px-host', s.proxyHost);
      setVal('st-px-port', s.proxyPort);
      setVal('st-px-user', s.proxyUser);
      setVal('st-px-pass', s.proxyPass);
      setVal('st-px-bypass', s.bypass);
      txt($('st-ua-now'), '当前：' + (s.uaLabel || '-') + '   ' + (s.uaNow || ''));
      txt($('st-search-now'), '当前：' + (s.searchLabel || '-') + '   ' + (r.searchTemplateNow || ''));
      txt($('st-incog'), s.incognito ? '开（不记历史，关掉时清 cookie/存储）' : '关');
      txt($('st-px-now'), '当前：' + (s.proxySummary || '不用代理（直连）'));
    });
  }

  function applySettings(patch, msg) {
    call('settings.set', { settings: patch }).then(function (r) {
      txt($('st-out'), msg + '\n' + j(r.settings || r));
      if ($('st-px-self') && r && r.settings && typeof r.settings.selfProxy !== 'undefined') {
        $('st-px-self').checked = !!r.settings.selfProxy;
      }
      if ($('st-px-self-out') && r && r.settings) {
        txt($('st-px-self-out'), '自代理：' + (r.settings.selfProxy ? '开（浏览器流量过本地中继，统计更全）' : '关'));
      }
      loadSettings();
    });
  }

  bindMaybe('st-ua-apply', 'click', function () {
    applySettings({ uaMode: $('st-ua').value, customUa: $('st-ua-custom').value }, '浏览模式已应用（当前页会刷新）');
  });
  bindMaybe('st-search-apply', 'click', function () {
    applySettings({ search: $('st-search').value, customSearch: $('st-search-custom').value }, '搜索引擎已保存');
  });
  bindMaybe('st-incog-toggle', 'click', function () {
    call('incognito.set', {}).then(function (r) {
      txt($('st-out'), '隐身模式：' + (r.incognito ? '已开启' : '已关闭（cookie/存储/无痕下载已清）'));
      loadSettings();
    });
  });
  bindMaybe('st-px-apply', 'click', function () {
    applySettings({
      proxyType: $('st-px-type').value, proxyHost: $('st-px-host').value, proxyPort: $('st-px-port').value,
      proxyUser: $('st-px-user').value, proxyPass: $('st-px-pass').value, bypass: $('st-px-bypass').value,
      selfProxy: $('st-px-self') && $('st-px-self').checked ? 1 : 0        // 第 16 条：自代理开关
    }, '代理已保存并应用（浏览器流量走本地中继）');
  });
  // 自代理开关单独也能生效（不用改上游代理）
  bindMaybe('st-px-self', 'change', function () {
    applySettings({ selfProxy: $('st-px-self').checked ? 1 : 0 },
      $('st-px-self').checked ? '自代理已开：浏览器流量过本地中继（统计更全）' : '自代理已关');
  });
  bindMaybe('st-px-off', 'click', function () {
    applySettings({ proxyType: 'none' }, '代理已关闭，浏览器直连');
  });

  // ---------------------------------------------------------------- 资源嗅探
  // ---------------------------------------------------------------- 资源嗅探（视频 / 音频 / 播放列表 / 分片 / 字幕）
  // 从多个视频网站用出来的三条经验都落在这里：
  //   ① 分片会刷屏 —— 同一路的分片由原生侧合并成一条（这里显示"分片 ×N"）；
  //   ② 有些站用 fetch/MSE，请求层看不到"人想看的那条" —— 所以有「从页面里再找一遍」；
  //   ③ 音频（mp3/m4a）要能直接下、HLS 音频要能只换容器转存成 m4a。
  let SN_GROUP = 'all';
  // N2：嗅探**按文件类型分**（不按视频/音频这种媒体分类）。下面是"是音频类"的判定，给"转存 m4a"用。
  const AUDIO_KINDS = ['mp3', 'm4a', 'aac', 'flac', 'opus', 'ogg', 'weba', 'wav'];
  function snLabel(kind, group) {
    const k = kind || group || 'other';
    return k === 'other' ? '其它' : ('.' + k);      // 分组标题就是扩展名，例如 .m3u8
  }
  function snIsAudio(it) { return AUDIO_KINDS.indexOf(it.kind || '') >= 0; }
  function fmtSize(it) { return it && it.size ? fmtBytes(it.size) : ''; }
  /**
   * 做完"要回到网页"的动作之后，把控制台浮层收起来。
   * 用户报的"播放没效果"就是这个：播放器确实打开了，但控制台还盖在上面，看到的一直是控制台。
   * （对应他的要求："有些按键点击后就会跳出这个 bar 内的页面，返回网页"。）
   */
  function backToPage() {
    try { call('ui.close', {}); } catch (e) {}
  }
  function snRow(it) {
    const d = mk('div', 'row-item two');   // 两行制（用户定的行规范）
    d.setAttribute('data-title', (it.name || it.url || '').slice(0, 60));
    d.setAttribute('data-fields', JSON.stringify([
      ['类型', snLabel(it.kind, it.group) + (it.isFrag ? ('　同一路分片 ×' + (it.segs || 1)) : '')],
      ['文件', it.name || ''], ['站点', it.host || ''], ['来自页面', it.page || ''],
      ['地址', it.isFrag ? (it.fragDir || it.url) : it.url]
    ]));
    // 第1行：文件名（大字）+ 右列类型（扩展名，完整显示）；第2行：站点 + 来自哪个页面
    d.appendChild(mk('span', 'nm2', it.name || it.url));
    d.appendChild(mk('span', 'rt', snLabel(it.kind, it.group) + (it.isFrag ? (' ×' + (it.segs || 1)) : '')));
    d.appendChild(mk('span', 'u', (it.host || '') + (it.page ? ('\u3000来自 ' + it.page.replace(/^https?:\/\//, '').slice(0, 40)) : '')));
    const url = it.isFrag ? (it.fragDir || it.url) : it.url;
    const dl = mk('button', 'mini2', '下载');
    dl.addEventListener('click', function () {
      if (it.isFrag) { txt($('sn-out'), '分片是同一路的一段，单独下意义不大；点「播放」看整路，或下它的播放列表（m3u8）'); return; }
      const op = (it.kind === 'm3u8') ? 'sniff.download' : 'download.start';
      call(op, { url: url, name: it.name || '' }).then(function (r) { txt($('sn-out'), j(r).slice(0, 300)); loadSniff(); });
    });
    const play = mk('button', 'mini2', '▶ 播放');
    play.addEventListener('click', function () {
      call('player.open', { url: url, name: it.name || '' }).then(function (r) {
        if (!r || r.ok !== true) { txt($('sn-out'), '打不开播放器：' + ((r && r.error) || '未知')); return; }
        // 关键：把控制台收起来，不然播放器在下面播、用户看到的一直是控制台（用户报的就是这个）
        backToPage();
      });
    });
    const trim = mk('button', 'mini2', (snIsAudio(it) ? '转存 m4a' : '转存 mp4'));
    trim.addEventListener('click', function () {
      const ext = snIsAudio(it) ? 'm4a' : 'mp4';
      txt($('sn-out'), '转存中（只换容器、不重编码，可能要一会儿）…');
      call('ffmpeg.remux', { url: url, name: it.name || 'media', ext: ext }).then(function (r) {
        txt($('sn-out'), j(r).slice(0, 400));
      });
    });
    const cast = mk('button', 'mini2', '投屏');
    cast.addEventListener('click', function () {
      // 以前这里调 cast.play 但**没带设备**（后端要求先搜到设备再投）→ 静默失败，看着就是"投屏没效果"。
      // 正确的入口是 cast.open：先搜设备、搜到弹出选择框。
      txt($('sn-out'), '正在搜索可投屏设备…');
      call('cast.open', { url: url, title: it.name || '' }).then(function (r) {
        txt($('sn-out'), (r && (r.note || r.error)) || '没搜到可投屏设备（同一局域网内的 DLNA/Chromecast）');
        if (r && r.ok === true) backToPage();
      });
    });
    // 「复制」按键按用户要求去掉了：小窗里的地址是文本框，**长按就能选中复制**（少一个键）
    [dl, play, trim, cast].forEach(function (b) { d.appendChild(b); });
    return d;
  }
  function loadSniff() {
    call('sniff.list', {}).then(function (r) {
      const l = (r && r.list) || [];
      const st = (r && r.stats) || {};
      const host = hostOf('sn-list');
      if (!host) return;
      const q = ($('sn-filter') && $('sn-filter').value || '').trim().toLowerCase();
      const g = SN_GROUP;
      const pageOnly = !!($('sn-pageonly') && $('sn-pageonly').checked);
      const cur = (LAST_STATE && LAST_STATE.url) || '';
      const shown = l.filter(function (it) {
        if (g !== 'all' && (it.group || '') !== g) return false;
        // 「只看当前页」：清空之后仍会被"还在播的旧页面"重新登记（实测过），所以给一个只认当前页的筛
        if (pageOnly && cur && (it.page || '') !== cur) return false;
        if (!q) return true;
        return ((it.url || '') + ' ' + (it.host || '') + ' ' + (it.name || '')).toLowerCase().indexOf(q) >= 0;
      });
      // N2：状态行也按文件类型数（.m3u8 3 条 · .ts 1 条…）
      const bk = st.byKind || {};
      const parts = Object.keys(bk).map(function (k) { return snLabel(k) + ' ' + bk[k] + ' 条'; });
      // 筛选项跟着实际类型走（列表里有什么就只给什么，不摆空分类）
      const sel = $('sn-group');
      if (sel) {
        const keep = sel.value || 'all';
        sel.innerHTML = '';
        const all = document.createElement('option'); all.value = 'all'; all.textContent = '全部';
        sel.appendChild(all);
        Object.keys(bk).sort().forEach(function (k) {
          const o = document.createElement('option'); o.value = k; o.textContent = snLabel(k) + '（' + bk[k] + '）';
          sel.appendChild(o);
        });
        if ([].some.call(sel.options, function (o) { return o.value === keep; })) sel.value = keep;
        else { sel.value = 'all'; SN_GROUP = 'all'; }
      }
      txt($('sn-stats'), '共 ' + l.length + ' 条：' + (parts.join(' · ') || '（还没有）') +
        (r && r.dir ? ('　下载目录 ' + r.dir) : ''));
      if (!shown.length) {
        putEmpty(host, l.length ? '（这一类里没有；换个筛选看看）' : '（还没有嗅到资源：页面里出现 m3u8 / mp4 / mp3 时会自动记下；也可以点「从页面里再找一遍」）');
        return;
      }
      const byHost = {};
      shown.forEach(function (it) { const h2 = it.host || '(未知站点)'; (byHost[h2] = byHost[h2] || []).push(it); });
      Object.keys(byHost).sort().forEach(function (h2) {
        host.appendChild(mk('div', 'dom', '\uD83C\uDF10 ' + h2 + '（' + byHost[h2].length + '）'));
        byHost[h2].forEach(function (it) { host.appendChild(snRow(it)); });
      });
      txt($('sn-out'), '显示 ' + shown.length + ' / ' + l.length + ' 条');
    });
  }
  bindMaybe('sn-reload', 'click', loadSniff);
  bindMaybe('sn-group', 'change', function () { SN_GROUP = $('sn-group').value || 'all'; loadSniff(); });
  bindMaybe('sn-filter', 'input', loadSniff);
  bindMaybe('sn-pageonly', 'change', loadSniff);
  bindMaybe('sn-scan', 'click', function () {
    txt($('sn-out'), '正在从页面里再找一遍…');
    call('sniff.scanPage', {}).then(function (r) { txt($('sn-out'), (r && (r.msg || r.error)) || ''); loadSniff(); });
  });
  bindMaybe('sn-clear', 'click', function () { call('sniff.clear', {}).then(loadSniff); });
  // 指定位置为视频（用户要求：并进资源嗅探里）：按下去之后自己在页面上点视频那一块
  bindMaybe('sn-pick', 'click', function () {
    call('sniff.pick', { on: 1 }).then(function (r) {
      txt($('sn-out'), r && r.ok ? '请点页面上视频那一块（20 秒内有效，点完自动加进清单）' : ('打不开选取：' + j(r)));
    });
  });
  bindMaybe('sn-add', 'click', function () {
    call('sniff.add', { url: $('sn-url').value }).then(function (r) { txt($('sn-out'), j(r)); loadSniff(); });
  });
  bindMaybe('sn-go', 'click', function () {
    call('sniff.download', { url: $('sn-url').value }).then(function (r) { txt($('sn-out'), j(r).slice(0, 300)); });
  });
  bindMaybe('sn-play', 'click', function () {
    call('player.open', { url: $('sn-url').value, name: '' }).then(function (r) { txt($('sn-out'), j(r)); });
  });
  // 内置播放器现在在放什么（以前只能"打开"，读不到状态 → 界面显示空，看着像没播）
  bindMaybe('sn-pl-state', 'click', function () {
    call('player.state', {}).then(function (r) {
      if (!r || !r.playerPage) { txt($('sn-out'), '当前页面不是内置播放器（先用「用内置播放器打开」）'); return; }
      const cur = Math.round((r.ms || 0) / 1000), dur = Math.round((r.durMs || 0) / 1000);
      txt($('sn-out'), (r.hasVideo ? (r.paused ? '暂停' : '播放中') : '页面里没有 video')
        + '　' + cur + 's / ' + dur + 's　ready=' + r.ready + (r.err ? ('　错误码 ' + r.err) : ''));
    });
  });
  bindMaybe('sn-download-all', 'click', function () {
    call('sniff.download', { all: true }).then(function (r) { txt($('sn-out'), j(r).slice(0, 300)); });
  });
  /** 一次把清单里的音频都下下来（直链一条条排队下；HLS 音频走转存 m4a） */
  bindMaybe('sn-audio-all', 'click', function () {
    call('sniff.list', {}).then(function (r) {
      const aud = ((r && r.list) || []).filter(function (it) { return snIsAudio(it); });
      if (!aud.length) { txt($('sn-out'), '清单里还没有音频（mp3 / m4a / aac 这类）'); return; }
      txt($('sn-out'), '排队下载 ' + aud.length + ' 条音频…');
      let done = 0;
      const next = function (k) {
        if (k >= aud.length) { txt($('sn-out'), '音频下完了：' + done + '/' + aud.length + '（在「下载」栏目里看进度）'); return; }
        const it = aud[k];
        const op = (it.kind === 'm3u8' || it.kind === 'mpd') ? 'ffmpeg.remux' : 'download.start';
        const args = (op === 'ffmpeg.remux') ? { url: it.url, name: it.name || 'audio', ext: 'm4a' } : { url: it.url, name: it.name || '' };
        call(op, args).then(function () { done++; next(k + 1); });
      };
      next(0);
    });
  });

  // ---------------------------------------------------------------- Cookie（合并成一块：一个列表 + 点行小窗）
  function renderCookies(list) {
    const host = hostOf('ck-list');
    if (!host) return;
    const q = ($('ck-filter') && $('ck-filter').value || '').trim().toLowerCase();
    let shown = 0;
    (list || []).forEach(function (g) {
      const cs = (g.cookies || []).filter(function (c) {
        return !q || (g.domain + ' ' + c.name).toLowerCase().indexOf(q) >= 0;
      });
      if (!cs.length) return;
      shown += cs.length;
      host.appendChild(mk('div', 'dom', '\uD83C\uDF10 ' + g.domain + '（' + cs.length + ' 条）'));
      cs.forEach(function (c) {
        const v = (c.value || '').length > 90 ? (c.value.slice(0, 90) + '…') : (c.value || '');
        const d = mk('div', 'row-item two');       // 两行制：名称大字 + 第二行值
        d.setAttribute('data-kind', 'edit');       // 可编辑行：点行开的是可编辑小窗（值就是文本框）
        d.addEventListener('click', function (e) {
          if (e && e.target && e.target.tagName === 'BUTTON') return;
          if (e && e.stopPropagation) e.stopPropagation();
          editCookie(g.domain, c);
        });
        d.setAttribute('data-title', 'Cookie：' + c.name);
        // 属性不在这里摆第二份：点这一行开的可编辑小窗里给的是**真属性**（读自 cookie 库），
        // 一份数据只有一个来源（这里以前写死过一句"系统不提供"，用户看到的"无效值"就是它）
        d.appendChild(mk('span', 'ckn', c.name));   // 第1行：名称（大字）
        d.appendChild(mk('span', 'u', v));          // 第2行：值（小字）
        host.appendChild(d);
      });
    });
    // 列表最底部：＋ 添加 cookie（用户要求：cookie 也可以添加）——形态跟步骤列表底部那个加号一致
    const addRow = mk('div', 'row-item add-row');
    addRow.id = 'ck-add';
    addRow.setAttribute('data-kind', 'add');
    addRow.appendChild(mk('span', 't', '＋'));
    addRow.appendChild(mk('span', 'nm2', '添加 cookie'));
    addRow.addEventListener('click', function (e) {
      if (e && e.stopPropagation) e.stopPropagation();
      // 默认域 = 当前页面的站点（读一次状态，别猜）
      call('status', {}).then(function (s) {
        addCookie(hostOf2(((s || {}).browser || {}).url || ''));
      });
    });
    host.appendChild(addRow);
    if (!shown) putEmpty(host, q ? '（没有匹配的 cookie）' : '（没有查到 cookie；先打开几个页面）');
  }

  /** 添加一个 cookie：值都是文本框（名字/值/路径可填，域默认当前站点） */
  function addCookie(domain) {
    CDPUI.Modal.open({
      title: '添加 cookie',
      fields: [
        { key: 'domain', label: '域（要和站点对得上）', value: domain || '' },
        { key: 'name', label: '名字', value: '' },
        { key: 'value', label: '值', value: '' },
        { key: 'path', label: '路径', value: '/' },
        { key: 'days', label: '有效期（天，0=会话期）', value: 0, type: 'number' },
        { key: 'secure', label: 'Secure', value: '0', type: 'select', options: [['0', '否'], ['1', '是']] },
        { key: 'httpOnly', label: 'HttpOnly', value: '0', type: 'select', options: [['0', '否'], ['1', '是']] }
      ],
      onSubmit: function (v) {
        call('cookie.set', {
          domain: v.domain, name: v.name, value: v.value, path: v.path,
          maxAge: Math.round((Number(v.days) || 0) * 86400), secure: v.secure === '1', httpOnly: v.httpOnly === '1'
        }).then(function (r) {
          txt($('ck-out'), r.ok ? ('已写入 ' + v.name + ' @ ' + v.domain) : ('没写进去：' + (r.note || r.error || '')));
          loadCookies();
        });
      }
    });
  }

  /** 真实过期时刻 → "还剩几天"（会话期＝0）。以前这里写死 0，用户看到的"过期时间是无效值"就是它 */
  function cookieDays(c) {
    const ms = Number(c.expiresMs || 0);
    if (!ms || !isFinite(ms)) return 0;
    return Math.max(0, Math.round((ms - Date.now()) / 86400000));
  }

  /** 改一条 cookie：可改的字段用**真值**预填，库里读回来的只读信息（过期/创建/最后使用）排在后面 */
  function editCookie(domain, c) {
    const fields = [
      { key: 'name', label: '名字', value: c.name, editable: false },
      { key: 'domain', label: '域', value: domain, editable: false },
      { key: 'value', label: '值', value: c.value || '' },
      { key: 'path', label: '路径', value: c.path || '/' },
      { key: 'days', label: '有效期（天，0=会话期）', value: cookieDays(c), type: 'number' },
      { key: 'secure', label: 'Secure', value: c.secure ? '1' : '0', type: 'select', options: [['0', '否'], ['1', '是']] },
      { key: 'httpOnly', label: 'HttpOnly', value: c.httpOnly ? '1' : '0', type: 'select', options: [['0', '否'], ['1', '是']] }
    ];
    if (c.attrMissing) {
      fields.push({ key: 'attrRd', label: '属性', value: c.attrMissing, editable: false });
    } else {
      fields.push({ key: 'expiresRd', label: '过期时间', value: c.expires || '会话期', editable: false });
      fields.push({ key: 'createdRd', label: '创建时间', value: c.created || '（库里没有）', editable: false });
      fields.push({ key: 'lastRd', label: '最后使用', value: c.lastAccess || '（库里没有）', editable: false });
    }
    CDPUI.Modal.open({
      title: 'Cookie · ' + c.name,
      fields: fields,
      onSubmit: function (v) {
        call('cookie.set', {
          domain: domain, name: c.name, value: v.value, path: v.path,
          maxAge: Math.round((Number(v.days) || 0) * 86400), secure: v.secure === '1', httpOnly: v.httpOnly === '1'
        }).then(function (r) {
          txt($('ck-out'), r.ok ? ('已更新 ' + c.name + ' @ ' + domain) : ('没写进去：' + (r.note || r.error || '')));
          loadCookies();
        });
      },
      extras: [
        { label: '✕ 删掉这个 cookie', danger: true, run: function () {
            call('cookie.delete', { domain: domain, name: c.name }).then(function () { txt($('ck-out'), '已删除 ' + c.name); loadCookies(); });
          } }
      ]
    });
  }
  /** 点域名小标题上的「只查这个域名」= 在同一个列表里过滤（不再单独一块查询框） */
  function loadCookies() {
    call('cookie.all', {}).then(function (r) { renderCookies((r && r.list) || []); });
  }
  function cookieDetail(domain, focusName) {
    call('cookie.detail', { domain: domain }).then(function (r) {
      if (!r.ok) { showModal('Cookie 详情', r.error || '取不到'); return; }
      const rows = r.list || [];
      const head = '域名：' + r.domain + '　共 ' + (r.count || 0) + ' 条\n' + (r.note || '') + '\n';
      openActs('Cookie 详情 · ' + domain, head + rows.map(function (x) { return (x.name || '') + ' = ' + (x.value || '(空)'); }).join('\n'),
        [{ t: '复制全部', fn: function () { try { navigator.clipboard.writeText(rows.map(function (x) { return (x.name || '') + '=' + (x.value || ''); }).join('\n')); } catch (e) {} } }]);
      if (focusName) {
        const line = rows.filter(function (x) { return x.name === focusName; })[0];
        if (line) txt($('ck-out'), focusName + ' = ' + line.value);
      }
    });
  }
  bindMaybe('ck-all', 'click', loadCookies);
  bindMaybe('ck-filter', 'input', loadCookies);
  bindMaybe('ck-query', 'click', function () {
    const d = ($('ck-domain').value || '').trim();
    if (!d) { txt($('ck-out'), '先填域名'); return; }
    call('cookie.domain', { domain: d }).then(function (r) {
      const l = (r && r.list) || [];
      renderCookies([{ domain: d, cookies: l }]);
      txt($('ck-out'), '只看 ' + d + '：' + l.length + ' 条');
    });
  });
  bindMaybe('ck-clear', 'click', function () {
    call('cookie.clear', {}).then(function (r) { txt($('ck-out'), '已清空全部 cookie：' + (r.ok ? '成功' : '失败')); loadCookies(); });
  });

  // ---------------------------------------------------------------- 列表渲染（统一"能向下延伸的盒子"）
  // ---------------------------------------------------------------- 行 = 一条信息（点这行就弹窗）
  // 用户反复讲的规矩，照它落：
  //   ① 信息型列表（网络 / Cookie / 嗅探 / 下载 / 历史 / 书签 / 阅读 …）的行里**不摆按键**；
  //   ② **点这一行**就弹小窗，先把这条的全部信息按「字段：值」摆出来；
  //   ③ 动作（下载 / 播放 / 投屏 / 删除 / 复制…）排在信息下面，不占行面。
  // 做法：列表渲染时给每行塞 data-fields（[["字段","值"],…] 的 JSON）；
  //       这里统一接管所有 .listbox .row-item，把行里已有的按键搬进小窗（它们只是"动作的来源"），
  //       行面只留摘要 + 一个 ⋯ 提示。
  // 列表行渲染器也抽成模块（modules/list.js）：行 = 一条信息，点行弹窗，行面不给按键。
  // 各列表只给行塞 data-title / data-fields（可选按键当动作来源），剩下的交给模块。
  const rowSheetFor = CDPUI.List.compact;
  function compactAllRows() { CDPUI.List.compactAll(); }
  // （信息行的判定见 modules/list.js：带 data-kind 的交互行由各自模块渲染）
  window.__cdpRowScan = function () { return CDPUI.List.scan(); };
  CDPUI.List.watch();                    // 接管所有列表行（含之后新出现的）

  /** 模块自检：装了哪些模块、各自接口、有没有"没走渲染器"的违规 */
  window.__cdpModules = function () {
    // 只统计"信息行"：带 data-kind 的是交互行（步骤行、列表底部加号），它们自带渲染器，不走 list.js
    const rows = CDPUI.List.scan().filter(function (r) { return !r.kind; });
    const modal = CDPUI.Modal.scan();
    const badRows = rows.filter(function (r) { return r.renderer && r.renderer !== 'list.js'; });
    const k = document.querySelector('#cdp-modal .kv-k'), v = document.querySelector('#cdp-modal .kv-v');
    return {
      modules: (CDPUI.__mods || []).map(function (m) { return m.name + '@' + m.version; }),
      count: (CDPUI.__mods || []).length,
      rows: rows.length,
      rowsWithRenderer: rows.filter(function (r) { return r.renderer === 'list.js'; }).length,
      modal: { renderer: modal.renderer, bars: modal.bars, dupes: modal.dupes, pairs: modal.pairs },
      kvFonts: (k && v) ? { k: parseFloat(getComputedStyle(k).fontSize), v: parseFloat(getComputedStyle(v).fontSize) } : null,
      // 撞 id 是一类真坑：$('x') 只拿到第一个，第二个板块就"点了没反应"或输出跑到别处
      duplicateIds: (function () {
        const seen = {}, dup = [];
        Array.prototype.slice.call(document.querySelectorAll('[id]')).forEach(function (el) {
          seen[el.id] = (seen[el.id] || 0) + 1;
        });
        Object.keys(seen).forEach(function (k) { if (seen[k] > 1) dup.push(k + '×' + seen[k]); });
        return dup;
      })(),
      violations: badRows.concat((modal.open && modal.renderer && modal.renderer !== 'modal.js')
        ? [{ where: 'modal', renderer: modal.renderer }] : [])
    };
  };

  // 小工具搬到 modules/fmt.js；这里只留别名（老代码照旧能用，实现只有一份）
  const mk = CDPUI.Fmt.mk;
  const hostOf2 = CDPUI.Fmt.host;
  const fmtTime = CDPUI.Fmt.time;
  function hostOf(id) {
    const h = $(id);
    if (h) h.innerHTML = '';
    return h;
  }
  const putEmpty = CDPUI.Fmt.putEmpty;

  // 历史：分页 + 下拉到底自动加载（"无限下拉"）
  let hOffset = 0, hTotal = 0, hLoading = false;
  /** 短日期：09-20 07:12（第二行用，别把整行撑爆） */
  function shortDate(ts) {
    const s = fmtTime(ts);
    return s.length >= 16 ? s.slice(5, 16) : s;
  }

  /** 历史一条：点行 = 打开**可编辑**小窗（标题能改；地址/时间只读） */
  function editHistory(h) {
    CDPUI.Modal.open({
      title: '历史 · ' + shortDate(h.ts),
      fields: [
        { key: 'title', label: '标题', value: h.title || '' },
        { key: 'url', label: '地址', value: h.url || '', editable: false },
        { key: 'site', label: '站点', value: hostOf2(h.url), editable: false },
        { key: 'ts', label: '访问时间', value: fmtTime(h.ts), editable: false }
      ],
      onSubmit: function (v) {
        call('history.update', { ts: h.ts, url: h.url, title: v.title }).then(function (r) {
          txt($('h-count'), r.ok ? ('已改：' + v.title) : ('没改成：' + (r.error || '')));
          loadHistory();
        });
      },
      extras: [{ label: '在浏览器里打开', run: function () { call('nav.open', { url: h.url }).then(backToPage); } }],
    });
  }

  function histRow(h) {
    const d = mk('div', 'row-item two');        // 两行：统一高度 54px、整宽
    d.setAttribute('data-kind', 'edit');        // 可编辑行：list.js 不接管，点行开的是可编辑小窗
    d.setAttribute('data-title', (h.title || h.url || '').slice(0, 60));
    d.addEventListener('click', function (e) {
      if (e && e.target && e.target.tagName === 'A') return;   // 点标题链接=在浏览器里打开
      if (e && e.stopPropagation) e.stopPropagation();
      editHistory(h);
    });
    d.setAttribute('data-fields', JSON.stringify([
      ['访问时间', fmtTime(h.ts)], ['标题', h.title || ''], ['地址', h.url || ''], ['站点', hostOf2(h.url)]
    ]));
    // 两行制：第1行标题（大字）＋右列时间（完整显示）；第2行链接（小字）
    const a = mk('a', 'lnk', h.title || h.url);
    a.setAttribute('data-url', h.url);
    d.appendChild(a);
    d.appendChild(mk('span', 'rt', shortDate(h.ts)));
    d.appendChild(mk('span', 'u', h.url || ''));
    return d;
  }
  function histPage(reset) {
    if (hLoading) return;
    hLoading = true;
    if (reset) { hOffset = 0; }
    call('history.page', { offset: hOffset, limit: 50 }).then(function (r) {
      const list = r.list || [];
      hTotal = r.total || 0;
      const host = $('h-list');
      if (reset && host) host.innerHTML = '';
      const q = ($('h-filter') && $('h-filter').value || '').trim().toLowerCase();
      (q ? list.filter(function (h) {
        return ((h.title || '') + ' ' + (h.url || '')).toLowerCase().indexOf(q) >= 0;
      }) : list).forEach(function (h) { if (host) host.appendChild(histRow(h)); });
      hOffset += list.length;
      txt($('h-count'), '共 ' + hTotal + ' 条，已显示 ' + hOffset + ' 条（往下滑自动加载更多）');
      if (!hTotal && host) putEmpty(host, '（还没有历史）');
      hLoading = false;
    });
  }
  function renderHistory() { histPage(true); }
  const hList = $('h-list');
  if (hList) {
    hList.addEventListener('scroll', function () {
      if (hList.scrollTop + hList.clientHeight >= hList.scrollHeight - 40 && hOffset < hTotal) histPage(false);
    });
  }
  bindMaybe('h-export', 'click', function () {
    call('export.history', {}).then(function (r) {
      txt($('h-count'), (r.msg || r.error || '') + '');
    });
  });
  bindMaybe('h-export-json', 'click', function () {
    call('export.historyJson', {}).then(function (r) { txt($('h-count'), (r.msg || r.error || '')); });
  });
  bindMaybe('h-del-domain-go', 'click', function () {
    const d = ($('h-del-domain').value || '').trim();
    if (!d) { txt($('h-count'), '先填域名'); return; }
    call('history.deleteDomain', { domain: d }).then(function (r) {
      txt($('h-count'), '删掉 ' + (r.removed || 0) + ' 条（' + d + '）');
      loadHistory();
    });
  });
  function delByDate(before) {
    const v = ($('h-del-date').value || '').trim();
    if (!v) { txt($('h-count'), '先选一个时间'); return; }
    const ts = new Date(v).getTime();
    call('history.deleteTime', { ts: ts, before: before }).then(function (r) {
      txt($('h-count'), '删掉 ' + (r.removed || 0) + ' 条');
      loadHistory();
    });
  }
  bindMaybe('h-del-before', 'click', function () { delByDate(true); });
  bindMaybe('h-del-after', 'click', function () { delByDate(false); });

  /** 书签一条：点行 = 可编辑小窗（标题 / 地址 / 文件夹都能改）；移动与删除排在真做事的按键里 */
  function editBookmark(b, folders) {
    CDPUI.Modal.open({
      title: '书签',
      fields: [
        { key: 'title', label: '标题', value: b.title || '' },
        { key: 'url', label: '地址', value: b.url || '' },
        { key: 'folder', label: '文件夹（可用 / 分层）', value: b.folder || '' },
        { key: 'added', label: '加入时间', value: fmtTime(b.ts), editable: false }
      ],
      onSubmit: function (v) {
        call('bookmark.update', { id: b.id, title: v.title, url: v.url, folder: v.folder }).then(function (r) {
          loadBookmarks();
        });
      },
      extras: [
        { label: '✕ 删掉这条书签', danger: true, run: function () { call('bookmark.remove', { id: b.id }).then(loadBookmarks); } }
      ]
    });
  }

  function renderBookmarks() {
    call('bookmark.list', {}).then(function (r) {
      const list = r.list || [];
      call('bookmark.folders', {}).then(function (fr) {
        const folders = fr.list || [];
        const treeHost = hostOf('b-tree');
        const listHost = hostOf('b-list');
        if (treeHost && folders.length) {
          folders.forEach(function (f) {
            const depth = f.split('/').length - 1;
            const wrap = mk('div', 'folderRow');
            wrap.style.paddingLeft = (depth * 14) + 'px';
            const n = list.filter(function (b) { return (b.folder || '') === f || (b.folder || '').indexOf(f + '/') === 0; }).length;
            wrap.appendChild(mk('span', 'folder', '\uD83D\uDCC1 ' + f.split('/').pop() + '（' + n + '）'));
            wrap.appendChild(mk('span', 'u', f));
            const del = mk('button', 'mini2', '删');
            del.addEventListener('click', function () { call('folder.delete', { name: f }).then(function () { loadBookmarks(); }); });
            wrap.appendChild(del);
            treeHost.appendChild(wrap);
          });
        } else if (treeHost) {
          putEmpty(treeHost, '（还没有文件夹；「更多 ▾」里能新建）');
        }
        if (!list.length) { putEmpty(listHost, '（还没有书签）'); return; }
        const groups = {};
        list.forEach(function (b) { const k = b.folder || ''; (groups[k] = groups[k] || []).push(b); });
        Object.keys(groups).sort().forEach(function (k) {
          listHost.appendChild(mk('div', 'dom', (k ? ('\uD83D\uDCC1 ' + k) : '（根目录）') + '（' + groups[k].length + '）'));
          groups[k].forEach(function (b) {
            const d = mk('div', 'row-item two');   // 与历史一致：两行、同高同宽
            d.setAttribute('data-kind', 'edit');   // 可编辑行：行里不摆按键，改/移/删都在点开的小窗里
            const a = mk('a', 'lnk', b.title || b.url);
            a.setAttribute('data-url', b.url);
            d.appendChild(a);
            if (b.folder) d.appendChild(mk('span', 'rt', b.folder));   // 右列：文件夹
            d.appendChild(mk('span', 'u', b.url || ''));               // 第二行：链接
            d.addEventListener('click', function (e) {
              if (e && e.target && e.target.tagName === 'A') return;   // 点标题链接=在浏览器里打开
              if (e && e.stopPropagation) e.stopPropagation();
              editBookmark(b, folders);
            });
            listHost.appendChild(d);
          });
        });
      });
    });
  }
  function reloadAllCookies() {
    call('cookie.all', {}).then(function (r) { renderCookies((r && r.list) || []); });
  }

  // 历史/书签条目：点了要「开在浏览器里」，不是把控制台自己导航走
  function bindOpenList(hostId, closeAfter) {
    const host = $(hostId);
    if (!host) return;
    host.addEventListener('click', function (e) {
      const el = e.target.closest('[data-url]');
      if (!el) return;
      if (e.preventDefault) e.preventDefault();
      const u = el.getAttribute('data-url');
      if (!u) return;
      call('nav.open', { url: u }).then(function () {
      backToPage();
        if (closeAfter) call('ui.close', {});
      });
    });
  }
  bindOpenList('h-list', true);
  bindOpenList('b-list', true);

  // 页面/录制栏打开时，主动问一次"最近拾取到的元素"——原生侧的推送是异步的（主线程排队 +
  // 控制台可见性切换），万一那一下没送到，这里补上，用户不会看到"拾取了但框里空的"。
  function pullPicked() {
    call('picker.last', {}).then(function (r) {
      // 反馈 #11：拾取到元素后要能"给它写点信息"（名字/备注），而不是点一下就完了
      const p = r && r.picked;
      if (!p || !(p.selector || p.el || p.desc)) return;
      const info = [((p.el || p.desc || '') + ''), (p.selector || '')].filter(Boolean).join('\n');
      const act = function () {
        const b = document.getElementById('cdp-modal-b');
        const esc = function (t) { return String(t).replace(/</g, '&lt;'); };
        b.innerHTML = '<div style="margin-bottom:8px;color:#9FB0BF">' + esc(info) + '</div>' +
          '<input id="pk-alias" placeholder="给它起个名字（可空）" style="width:100%;box-sizing:border-box;background:#0E1620;color:#CFE0EE;border:1px solid #24313E;border-radius:6px;padding:7px;margin-bottom:6px">' +
          '<input id="pk-note" placeholder="备注：我打算用它做什么（可空）" style="width:100%;box-sizing:border-box;background:#0E1620;color:#CFE0EE;border:1px solid #24313E;border-radius:6px;padding:7px;margin-bottom:8px">' +
          '<button id="pk-save">保存到这一步</button>';
        document.getElementById('pk-save').addEventListener('click', function () {
          const a2 = (document.getElementById('pk-alias') || {}).value || '';
          const n2 = (document.getElementById('pk-note') || {}).value || '';
          call('picker.note', { alias: a2, note: n2 }).then(function (r2) {
            txt($('p-picked'), r2 && r2.ok ? ('已记下：' + (a2 || '(没起名字)') + (n2 ? ('／' + n2) : '')) : ('记录失败：' + ((r2 && r2.error) || '')));
            const m2 = document.getElementById('cdp-modal'); if (m2) m2.style.display = 'none';
          });
        });
      };
      // 拾取到就得**填回控制台**（脚本选择器 / 参数位），这一步以前有，别丢：
      // 否则用户拾取了却看不到"填进去了"（反馈 #11 的另一半）。
      try {
        const sel0 = p.selector || '';
        const box0 = $('q-sel');
        if (sel0 && box0 && !box0.value) {
          setVal('q-sel', sel0);
          if ($('s-arg')) setVal('s-arg', sel0);
        }
        if (sel0) txt($('p-picked'), '最近拾取：' + sel0.slice(0, 70));
      } catch (e) {}
      showModal('拾取到的元素', info, null, [
        { t: '填写名字 / 备注', fn: act },
        { t: '复制选择器', fn: function () { try { navigator.clipboard.writeText(p.selector || info); } catch (e) {} } }
      ]);
    });

  }

  // 原生推过来的日志/录制步骤
  window.__cdpPush = function (raw) {
    const o = asObj(raw);
    if (!o) return;
    if (o.t === 'log') { /* 记在 App 日志里，日志页签能看到 */ }
    if (o.t === 'recStep') loadRecord();
    if (o.t === 'picked') {
      const sel = o.selector || '';
      const t = o.target || {};
      setVal('q-sel', sel);                       // 自动填进"找元素"框
      txt($('p-picked'), '刚刚拾取：' + sel + (t.text ? ('　文字：' + t.text.slice(0, 30)) : '') +
        (o.inFrame ? '　（在 iframe 里：回放时顶层点不到，只能先记着）' : ''));
      txt($('q-out'), j(o));
      if ($('s-arg')) setVal('s-arg', sel);       // 顺手也填到"插入一步"的参数里
      if ($('sn-url') && o.url && o.url.indexOf('http') === 0) { /* 不动它 */ }
      window.__cdpTab('page');
    }
    if (o.t === 'imported') {
      const r = (o.result || {});
      if (r.ok) {
        txt($('b-out'), (r.msg || '') + ((r.errors || []).length ? ('；出错：' + (r.errors || []).join('；')) : ''));
        loadHistory(); loadBookmarks(); loadScripts();
      } else {
        txt($('b-out'), '导入失败：' + (r.error || ''));
      }
    }
    if (o.t === 'userScriptChanged') loadScripts();
  };

  function boot() {
    buildDrawer();
    bindDrawerSearch();
    bindMoreBoxes();
    bindMaybe('rg-run', 'click', function () { renderRegistry(); txt($('rg-state'), ($('rg-state').textContent || '') + '　（刚跑完）'); });
    bindMaybe('rg-json', 'click', function () { openActs('功能登记表（原始 JSON）', JSON.stringify(R.report(), null, 1).slice(0, 4000), []); });
    bindMaybe('in-boards', 'click', function () { renderRegistry(); });
    compactAllRows();
    let last = 'page';
    try { last = localStorage.getItem('cdp.tab') || 'page'; } catch (e) {}
    setTab(TABS.indexOf(last) >= 0 ? last : 'page');
    refreshStatus();
    startRecPoll();          // 录制状态/步骤行每秒自己刷（以前这函数定义了却没人调用）
  }
  document.addEventListener('DOMContentLoaded', boot);
  if (document.readyState !== 'loading') { boot(); }
})();
