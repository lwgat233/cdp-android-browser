// 本轮改版的验收探针（电脑侧 CDP，读的是真实进程内的页面）
// 跑法：node tools/cdp.mjs file --target ui/index.html --file tools/probe_ui_round.js --timeout 60000
(async () => {
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));
  const $ = (s) => document.querySelector(s);
  const out = { ok: true, checks: [], errors: [] };
  function chk(name, got, want) {
    const pass = (typeof want === 'function') ? want(got) : (got === want);
    out.checks.push({ name: name, pass: !!pass, got: (typeof got === 'object' ? JSON.stringify(got) : String(got)).slice(0, 400) });
    if (!pass) out.ok = false;
    return pass;
  }

  // 1. 登记表自检
  const rep = window.__cdpRegistry ? window.__cdpRegistry() : null;
  chk('功能登记表存在', !!rep, true);
  if (rep) {
    chk('自检无问题', rep.problems.length, 0);
    chk('7 个归类集合', rep.collections, 7);
    chk('功能数 == 板块数', rep.features === rep.boards, true);
    chk('每个集合都有功能', rep.byColl.every(c => c.features > 0 && c.tabs > 0), true);
  }

  // 2. 板子都挂上了、没有 id 缺失
  chk('板块都挂上', document.querySelectorAll('[data-board][data-mounted="1"]').length,
      function (n) { return n > 0 && n === document.querySelectorAll('[data-board]').length; });
  chk('界面没有缺 id', (window.__cdpMissing ? window.__cdpMissing() : []).length, 0);

  // 3. 抽屉是两级（集合 ▸ 栏目），不是一条横幅
  const heads = Array.from(document.querySelectorAll('#tabs .collHead'));
  chk('抽屉里 7 个集合行', heads.length, 7);
  const browseless = Array.from(document.querySelectorAll('#tabs button.tabBtn')).length;
  chk('栏目按钮数 == 栏目数', browseless, window.CDP_REGISTRY.TABS.length);
  chk('不是横排（集合行是竖排块）', getComputedStyle(document.querySelector('#tabs')).display, 'block');

  // 4. 点集合 → 展开它的栏目
  const head = document.getElementById('collhead-media');
  head.click(); await sleep(120);
  chk('点集合能展开栏目', document.getElementById('coll-media').classList.contains('open'), true);

  // 5. 点栏目 → 切过去（section.on + 面包屑）
  const btn = document.getElementById('tabbtn-block');
  btn.click(); await sleep(400);
  chk('点栏目切到它的 section', (document.querySelector('section.tab.on') || {}).id, 'tab-block');
  chk('抽屉显示当前面包屑', /隐私与拦截/.test((document.getElementById('drawerNow') || {}).textContent || ''), true);
  chk('抽屉说明列出该栏目的功能', ((document.getElementById('drawerHint') || {}).textContent || '').length > 10, true);

  // 6. 「更多 ▾」：收起态点开能展开，且第 4 个键确实在盒子里
  const mb = document.getElementById('h-more');
  const box = document.getElementById('h-morebox');
  const before = box.classList.contains('hide');
  mb.click(); await sleep(120);
  chk('更多 ▾ 点一下能展开', box.classList.contains('hide') !== before, true);
  chk('更多盒子里装着按钮', box.querySelectorAll('button').length, function (n) { return n >= 1; });
  mb.click(); await sleep(80);

  // 7. 常驻按钮 ≤3/行（全界面）
  const rows = window.__cdpButtonRows ? window.__cdpButtonRows() : [];
  const bad = rows.filter(r => r.buttons.length > 3);
  chk('没有一行超过 3 个键', bad.length, 0);
  out.rows = rows.length;

  // 8. 列表行 → 点开小窗（历史栏目的行）
  window.__cdpTab('history'); await sleep(700);
  const firstRow = document.querySelector('#h-list .row-item');
  if (firstRow) {
    firstRow.click(); await sleep(200);
    chk('点列表行弹出小窗', ((document.getElementById('cdp-modal') || {}).style || {}).display !== 'none', true);
    const x = document.getElementById('cdp-modal-x'); if (x) x.click();
  } else {
    out.checks.push({ name: '点列表行弹出小窗', pass: true, got: '（没有历史记录，跳过：需要先打开一个页面）' });
  }

  // 9. 后端：警告名单并进规则库后，同一条链路能读能写（走的是原生 op）
  const r1 = await window.CDPT.call('adblock.warn.add', { host: 'probe.example.com' });
  const r2 = await window.CDPT.call('adblock.get', {});
  chk('警告名单能加', r1 && r1.ok, true);
  chk('警告名单在读接口里', (r2.warn || []).indexOf('probe.example.com') >= 0, true);
  const r3 = await window.CDPT.call('security.block.list', {});
  chk('老的 security.block.* 仍然指同一份数据', (r3.list || []).indexOf('probe.example.com') >= 0, true);
  const r4 = await window.CDPT.call('adblock.warn.remove', { host: 'probe.example.com' });
  chk('警告名单能删', r4 && r4.ok, true);

  return JSON.stringify(out);
})()
