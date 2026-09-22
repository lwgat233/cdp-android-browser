/**
 * modules/appgrid.js —— 首页「小 app 网格」渲染器（**唯一**画这个网格的地方）
 *
 * 用户要求（清单第 26 条 + 口述）：像手机桌面一样的小 app 网格；**加号添加**；**图标 + 名字**；
 * 点一个就打开；长按（或小窗里的 ✕）能改名/改网址/删掉。
 *
 * 数据在 App 里（`appsJson`），页面从**本机控制口**读写（同源，不需要往页面注入任何东西）。
 * 图标：先试站点 favicon，取不到就用「名字首字 + 按名字算出来的底色」——离线也一眼认得出。
 *
 * 规矩：一个渲染入口（每次清空重建，绝不往容器里追加）；常驻按键只有右下那个「＋」。
 */
(function () {
  'use strict';
  const CDPUI = (window.CDPUI = window.CDPUI || {});
  const MARK = 'appgrid.js';
  const $ = (id) => document.getElementById(id);

  /**
   * 取数：**优先走原生桥**（`window.CDPT.call`）。
   * 为什么必须这样（用户实测报的 bug）：从内置资源源 `https://appassets.androidplatform.net/ui/start.html`
   * 打开的页面用 `fetch('http://127.0.0.1:8848/...')` 会被**混合内容策略**拦掉 ——
   * 现象就是"页面在、不报错、添加了小 app 也不渲染"。原生桥是 WebView 注入对象，与页面来源无关。
   * 只有在没有桥的时候（桌面浏览器调界面）才退回 fetch。
   */
  function viaFetch(name, args) {
    const q = Object.keys(args || {}).map((k) => encodeURIComponent(k) + '=' + encodeURIComponent(args[k])).join('&');
    return fetch('/api/' + name.replace('.', '/') + (q ? ('?' + q) : ''), { cache: 'no-store' })
      .then((r) => r.json()).catch(() => null);
  }
  function viaBridge(name, args) {
    if (window.CDPT && typeof window.CDPT.call === 'function') return Promise.resolve(window.CDPT.call(name, args || {}));
    return Promise.resolve(null);
  }
  /**
   * 取数：同源那份（控制口提供的页面）**优先 fetch**；不同源的入口只能试桥。
   * 关键一条（踩过）：`transport.js` 在没有原生桥时会返回**演示假数据**（`{ok:true, demo:true}`，
   * 里面**没有 apps**）—— 直接拿它当"空列表"就会画成空白、还不报错，
   * 看起来就是用户说的"**添加了小 app 也不渲染**"。所以 `demo:true` 一律当**失败**，继续换另一条路。
   */
  function op(name, args) {
    const sameOrigin = (location.protocol === 'http:' || location.protocol === 'https:') &&
      /(^|\/\/)127\.0\.0\.1/.test(location.host ? ('//' + location.host) : '');
    const first = sameOrigin ? viaFetch : viaBridge;
    const second = sameOrigin ? viaBridge : viaFetch;
    const ok = (r) => r && r.ok === true && r.demo !== true;
    return Promise.resolve(first(name, args)).then(function (r) {
      if (ok(r)) return r;
      return Promise.resolve(second(name, args)).then(function (r2) { return ok(r2) ? r2 : null; });
    }).catch(function () { return null; });
  }
  function list() { return op('apps.list', {}); }
  function save(o) { return op('apps.save', o); }
  function remove(id) { return op('apps.remove', { id: id }); }

  function color(name) {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
    return 'hsl(' + h + ',45%,34%)';
  }

  function iconUrl(url) {
    try {
      const u = new URL(url);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
      return u.origin + '/favicon.ico';
    } catch (e) {
      return '';
    }
  }

  // 长按（600ms）＝ 打开编辑小窗；短按＝打开这个 app。触屏和鼠标都认。
  function bindPress(el, shortFn, longFn) {
    let timer = null, long = false;
    const start = function () {
      long = false;
      timer = setTimeout(function () { long = true; longFn(); }, 600);
    };
    const end = function () {
      if (timer) { clearTimeout(timer); timer = null; }
      if (!long) shortFn();
    };
    el.addEventListener('touchstart', start, { passive: true });
    el.addEventListener('touchend', end);
    el.addEventListener('mousedown', start);
    el.addEventListener('mouseup', end);
    el.addEventListener('touchmove', function () { if (timer) { clearTimeout(timer); timer = null; } });
  }

  function editSheet(app, after) {
    const isNew = !app;
    const cur = app || { name: '', url: '' };
    const cfg = {
      title: isNew ? '添加小 app' : cur.name,
      fields: [
        { key: 'name', label: '名字', value: cur.name, editable: true, placeholder: '例如 学习通' },
        { key: 'url', label: '网址（也可填 cdpctl://console?tab=page 这种内置入口）', value: cur.url, editable: true },
      ],
      onSubmit: function (v) {
        const name = (v.name || '').trim();
        const url = (v.url || '').trim();
        if (!name || !url) { toast('名字和网址都要有'); return; }
        save({ id: cur.id || '', name: name, url: url }).then(function (r) {
          if (r && r.ok === false) { toast(r.error || '没存进去'); return; }
          after();
        });
      },
    };
    if (!isNew) {
      cfg.extras = [{
        label: '删除', danger: true, close: true,
        run: function () { remove(cur.id).then(function () { after(); }); },
      }];
    }
    if (CDPUI.Modal && CDPUI.Modal.open) CDPUI.Modal.open(cfg);
  }

  function toast(msg) {
    let t = $('appgrid-toast');
    if (!t) {
      t = document.createElement('div');
      t.id = 'appgrid-toast';
      t.style.cssText = 'position:fixed;left:0;right:0;bottom:26px;text-align:center;color:#BEE1FF;font-size:13px';
      document.body.appendChild(t);
    }
    t.textContent = msg;
    setTimeout(function () { if (t) t.textContent = ''; }, 2500);
  }

  function draw(apps) {
    const box = $('apps');
    if (!box) return;
    box.innerHTML = '';                                     // 一个渲染入口：清空重建
    box.setAttribute('data-renderer', MARK);
    (apps || []).forEach(function (a) {
      const tile = document.createElement('a');
      tile.className = 'app';
      tile.setAttribute('data-id', a.id || '');
      // 用**真链接**（href）而不是 location.href：cdpctl:// 这类自有协议只有走链接接管路径
      // 才会被原生接住（程序化 location.href 到非 http 协议在 WebView 里会被丢，实测跳不动）。
      tile.setAttribute('href', a.url || '#');
      const ico = document.createElement('span');
      ico.className = 'ico';
      ico.style.background = color(a.name || '?');
      const img = iconUrl(a.url || '');
      if (img) {
        const im = document.createElement('img');
        im.src = img;
        im.alt = '';
        im.onerror = function () { im.remove(); };            // 取不到图标就露出首字母
        ico.appendChild(im);
      }
      ico.appendChild(document.createTextNode((a.name || '?').slice(0, 1)));
      const nm = document.createElement('b');
      nm.textContent = a.name || '';
      tile.appendChild(ico);
      tile.appendChild(nm);
      bindPress(tile,
        function () { tile.click(); },                          // 短按＝打开（真链接的点击）
        function () { editSheet(a, load); });                  // 长按＝改名/改网址/删
      box.appendChild(tile);
    });
    const add = document.createElement('a');
    add.className = 'app add';
    add.id = 'app-add';
    add.innerHTML = '<span class="ico">＋</span><b>添加应用</b>';   // 覆盖层演示用的改动（推上去即可，不重建 APK）
    add.addEventListener('click', function (e) { e.preventDefault(); editSheet(null, load); });
    box.appendChild(add);
  }

  function load() {
    list().then(function (r) {
      if (!r || !r.ok) {
        // 读不到就**明说**（不许出现"页面在、但加什么都不显示"）
        draw([]);
        note('读不到小 app 列表：' + ((r && r.error) || '原生桥没响应') + '（点工具栏 ⌂ 重开首页）');
        return;
      }
      draw(r.apps || []);
      note('');
    });
  }

  function note(msg) {
    const box = $('apps');
    if (!box) return;
    let el = $('appgrid-note');
    if (!msg) { if (el) el.remove(); return; }
    if (!el) {
      el = document.createElement('div');
      el.id = 'appgrid-note';
      el.style.cssText = 'grid-column:1/-1;color:#8195A8;font-size:12px;padding:6px 2px';
      box.appendChild(el);
    }
    el.textContent = msg;
  }

  CDPUI.AppGrid = { draw: draw, load: load, _mark: MARK };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', load);
  else load();
})();
