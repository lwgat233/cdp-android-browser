/*
 * cdp-agent.js —— 注入到每个网页里的「页面内 agent」。
 *
 * 它负责三件事，全部通过 window.__CDP 暴露给原生层（原生用 evaluateJavascript 调它）：
 *   1. 定位：按 DOM 指纹（id/结构路径/文字/属性）或「相对底部/顶部的位置锚点」找到目标元素；
 *   2. 录制：把用户在页面上的每次点按记成一步（位置锚点 + DOM 指纹 + 可点击性诊断）；
 *   3. 回放兜底：真实触摸事件打不动那种「不是 <a>、点不动」的按钮时，合成 pointer/mouse/touch 事件链。
 *
 * 和原生层通信：不往页面里挂任何原生对象（安全），改用 fetch 打一个虚拟源
 * https://cdp-event.local/ev?d=<json>，由 App 的 shouldInterceptRequest 接住。
 */
(function () {
  'use strict';
  if (window.__CDP && window.__CDP.__v) return;

  var CH = 'https://cdp-event.local/ev';
  // 口令由原生注入时包在外层闭包里给出（见 MainActivity.agentWithToken）：
  // 它是**函数作用域里的局部变量**，不挂到 window 上，页面自己的脚本读不到，
  // 所以别的网页伪造不了事件、也拿不到元信息通道。
  var TOK = (typeof __CDP_CHAN !== 'undefined' ? __CDP_CHAN : '');
  var KQ = TOK ? ('&k=' + TOK) : '';
  var chunkSeq = 0;

  function send(o) {
    try {
      var s = JSON.stringify(o);
      if (s.length <= 3200) {
        fetch(CH + '?d=' + encodeURIComponent(s) + KQ, { cache: 'no-store' }).catch(function () {});
        return;
      }
      var id = 'c' + (++chunkSeq);
      var n = Math.ceil(s.length / 3000);
      for (var i = 0; i < n; i++) {
        var part = { t: 'chunk', id: id, i: i, n: n, part: s.substr(i * 3000, 3000) };
        fetch(CH + '?d=' + encodeURIComponent(JSON.stringify(part)) + KQ, { cache: 'no-store' }).catch(function () {});
      }
    } catch (e) {}
  }

  function log(msg) { send({ t: 'log', msg: String(msg).slice(0, 500) }); }

  // ------------------------------------------------------------------ 视频与条件判断
  //
  // 这些是「等待类步骤」的地基：脚本里可以先点播放，然后等它真的播完，再点下一个。
  // 判断本身是**同步的**（agent 的 cmd 是同步返回），由原生侧按间隔轮询 —— 好处是每一步
  // 都能报「等了多久、轮询了几次、当时页面是什么状态」，比一个笼统的 sleep 强得多。

  function videosIn(sel) {
    try {
      var q = sel ? document.querySelectorAll(sel) : document.querySelectorAll('video');
      return Array.prototype.slice.call(q);
    } catch (e) { return []; }
  }

  /** 收集同源 iframe 里的视频（跨域的读不到内容，只能记个数，如实标出来） */
  function videosInFrames() {
    var out = [];
    var frames = document.querySelectorAll('iframe,frame');
    for (var i = 0; i < frames.length; i++) {
      try {
        var d = frames[i].contentDocument;
        if (!d) { out.push({ el: null, inFrame: true, frameUrl: frames[i].src || '', crossOrigin: true }); continue; }
        var vs = d.querySelectorAll('video,audio');
        for (var j = 0; j < vs.length; j++) out.push({ el: vs[j], inFrame: true, frameUrl: (frames[i].src || '') });
      } catch (e) {
        out.push({ el: null, inFrame: true, frameUrl: frames[i].src || '', crossOrigin: true });
      }
    }
    return out;
  }
  function videoState(sel) {
    var list = videosIn(sel).map(function (v) {
      try { v.__cdpInFrame = false; } catch (e) {}
      return v;
    });
    // 同源 iframe 里的也算"识别得出来"（反馈 #5：有的播放器在 iframe 里，以前完全看不到）
    var f = [];
    videosInFrames().forEach(function (o) {
      if (o.el) { try { o.el.__cdpInFrame = true; o.el.__cdpFrameUrl = o.frameUrl; } catch (e) {} f.push(o.el); }
      else f.push(o);   // 跨域：没有元素，只留一条"这里有个跨域 frame，读不到"
    });
    var all = [];
    function push(v) {
      if (v && v.tagName) { all.push(v); return; }
      if (v && v.crossOrigin) {
        all.push({ __cross: true, src: v.frameUrl });
      }
    }
    list.forEach(push);
    f.forEach(push);
    return all.map(function (v) {
      if (v.__cross) {
        return { src: String(v.src || ''), crossOrigin: true, inFrame: true, paused: null, currentTime: 0, duration: -1,
                 note: '跨域 iframe：内容读不到（浏览器安全边界），只能记下这个 frame 的地址' };
      }
      var dur = (typeof v.duration === 'number' && isFinite(v.duration)) ? v.duration : -1;
      return {
        src: String(v.currentSrc || v.src || '').slice(0, 120),
        paused: !!v.paused, ended: !!v.ended, muted: !!v.muted,
        currentTime: +(v.currentTime || 0), duration: dur,
        readyState: v.readyState, networkState: v.networkState,
        nearlyEnded: (dur > 0 && v.currentTime >= dur - 0.25),
        inFrame: !!v.__cdpInFrame, frameUrl: v.__cdpFrameUrl || ''
      };
    });
  }
  function rectOf(el) {
    return ensureInView(el);
  }

  /** 对页面上的视频做动作。传 action=play 时同时给出坐标，让原生注入真实触摸去点播放键 */
  function videoCmd(o) {
    var vs = videosIn(o.selector);
    if (!vs.length) return { ok: false, error: '页面上没有 <video> 元素' };
    var v = vs[0];
    var before = videoState(o.selector)[0];
    var note = '';
    try {
      if (o.action === 'play') {
        v.muted = o.muted === undefined ? true : !!o.muted;   // 静音才能自动播
        var p = v.play();
        if (p && p.catch) p.catch(function (e) { log('视频 play() 被拒: ' + (e && e.message)); });
        note = '调了 play()（并同时返回坐标，原生会再注入一次真实触摸点播放键）';
      } else if (o.action === 'pause') { v.pause(); }
      else if (o.action === 'mute') { v.muted = true; }
      else if (o.action === 'unmute') { v.muted = false; }
      else if (o.action === 'seek') { try { v.currentTime = +(o.time || 0); } catch (e) {} }
    } catch (e) { note = '出错: ' + (e && e.message); }
    return {
      ok: true, action: o.action || 'state', note: note,
      rect: (o.action === 'play' ? rectOf(v) : boxOf(v)), before: before, after: videoState(o.selector)[0],
      videos: videoState(o.selector)
    };
  }

  /** 条件判断：等待类步骤与「如果…就…否则…」都靠它 */
  function checkCond(c) {
    c = c || {};
    var t = c.type || 'always';
    try {
      if (t === 'always') return { ok: true, result: true, type: t };
      if (t === 'videoEnded') {
        var st = videoState(c.selector);
        if (!st.length) return { ok: true, result: false, type: t, detail: { error: '页面上还没有 <video>' } };
        // 只认真正的 ended：以前写成 ended || nearlyEnded（快结束就算完），
        // 会导致"播完→点下一个"那类脚本提前往下走，后面的元素还没出现就判定不存在。
        var done = st.some(function (s) { return !!s.ended; });
        return { ok: true, result: done, type: t, detail: { videos: st } };
      }
      if (t === 'videoPlaying') {
        var st2 = videoState(c.selector);
        var playing = st2.some(function (s) { return !s.paused && !s.ended && s.currentTime > 0; });
        return { ok: true, result: playing, type: t, detail: { videos: st2 } };
      }
      if (t === 'elementExists' || t === 'elementGone') {
        var found = null;
        if (c.selector) { try { found = document.querySelector(c.selector); } catch (e) {} }
        if (!found && c.text) { var cands = candidatesByText(c.text, null); found = cands.length ? cands[0].el : null; }
        var exists = !!found && isVisible(found);
        return {
          ok: true, result: (t === 'elementExists') ? exists : !exists, type: t,
          detail: { found: found ? describe(found) : null, selector: c.selector || '', text: c.text || '' }
        };
      }
      if (t === 'textAppears') {
        var body = norm((document.body && document.body.innerText) || '');
        return { ok: true, result: norm(c.text || '') !== '' && body.indexOf(norm(c.text)) >= 0, type: t, detail: { text: c.text } };
      }
      if (t === 'urlContains') return { ok: true, result: String(location.href).indexOf(c.value || '') >= 0, type: t, detail: { url: location.href } };
      if (t === 'urlChanged') return { ok: true, result: String(location.href) !== String(c.from || ''), type: t, detail: { url: location.href, from: c.from } };
      return { ok: false, error: '不认识的条件类型: ' + t };
    } catch (e) { return { ok: false, error: '条件判断异常: ' + (e && e.message) }; }
  }

  // ------------------------------------------------------------------ 视频与条件判断
  //
  // 这些是「等待类步骤」的地基：脚本里可以先点播放，然后等它真的播完，再点下一个。
  // 判断本身是**同步的**（agent 的 cmd 是同步返回），由原生侧按间隔轮询 —— 好处是每一步
  // 都能报「等了多久、轮询了几次」，失败时也说得清是「没等到」还是「页面不答话」。
  //
  // 支持的条件：videoEnded / videoPlaying / elementExists / elementGone / textAppears /
  //              urlContains / urlChanged / always / time

  function norm(s) {
    return String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
  }

  function textOf(el) {
    if (!el || el.nodeType !== 1) return '';
    var t = el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('data-title'));
    if (t) return norm(t).slice(0, 80);
    if (el.value !== undefined && el.value !== '' && el.tagName !== 'DIV') return norm(el.value).slice(0, 80);
    var s = el.innerText || el.textContent || '';
    return norm(s).slice(0, 80);
  }

  function esc(s) { return String(s).replace(/([^\w-])/g, '\\$1'); }

  function cssPath(el) {
    try {
      if (!el || el.nodeType !== 1) return '';
      var parts = [], n = el, guard = 0;
      while (n && n.nodeType === 1 && n !== document.documentElement && guard++ < 12) {
        if (n.id && document.querySelectorAll('#' + esc(n.id)).length === 1) {
          parts.unshift('#' + esc(n.id));
          break;
        }
        var sel = n.tagName.toLowerCase();
        var p = n.parentElement;
        if (p) {
          var all = Array.prototype.slice.call(p.children);
          if (all.filter(function (c) { return c.tagName === n.tagName; }).length > 1) {
            sel += ':nth-child(' + (all.indexOf(n) + 1) + ')';
          }
        }
        parts.unshift(sel);
        n = n.parentElement;
      }
      return parts.join('>');
    } catch (e) { return ''; }
  }

  function isVisible(el) {
    try {
      if (!el || !el.getBoundingClientRect) return false;
      var r = el.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) return false;
      var st = getComputedStyle(el);
      if (st.visibility === 'hidden' || st.display === 'none') return false;
      if (parseFloat(st.opacity || '1') < 0.06) return false;
      return true;
    } catch (e) { return false; }
  }

  function boxOf(el) {
    var r = el.getBoundingClientRect();
    return {
      x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height),
      cx: Math.round(r.left + r.width / 2), cy: Math.round(r.top + r.height / 2)
    };
  }

  /**
   * 元素不在视口里就先滚进来再量尺寸。
   *
   * 为什么必须这样：boxOf 给的是**视口坐标**，元素在折叠线以下时 cy 会是好几千；
   * 原生触摸注入的 y 超过 WebView 高度就会被直接丢弃（injectTapNow 会返回 false 并记一条日志），
   * 于是流程悄悄退化成 JS 合成点击 —— 页面上看着"点到了"，但 isTrusted=false，
   * 需要真实触摸的站点（学习通那类）就不认。踩过：回放时「下一个」在视口外，nextTrusted=false。
   */
  function ensureInView(el) {
    var b = boxOf(el);
    var vw = window.innerWidth, vh = window.innerHeight;
    var inside = b.w > 0 && b.h > 0 && b.x >= 0 && b.y >= 0 && (b.x + b.w) <= vw && (b.y + b.h) <= vh;
    if (!inside) {
      try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) { try { el.scrollIntoView(); } catch (e2) {} }
      b = boxOf(el);
    }
    return b;
  }

  /**
   * 点击前专用：把目标滚进视野，并给出**要注入的坐标**（视口坐标系 CSS 像素）。
   * 只有这条路径会滚动页面 —— 定位/诊断/录制都不该动页面。
   */
  function pointFor(sel, step) {
    var el = null;
    try { if (sel) el = document.querySelector(sel); } catch (e) {}
    if (!el && step) {
      try { var l = locate(step); if (l && l.ok) el = queryFromDescribe(l.el); } catch (e) {}
    }
    if (!el) return { ok: false, error: '找不到元素' };
    var before = Math.round(window.scrollY);
    var b = ensureInView(el);
    return {
      ok: true, box: b, x: b.cx, y: b.cy,
      scrolled: Math.round(window.scrollY) !== before,
      scrollY: Math.round(window.scrollY),
      inViewport: b.w > 0 && b.h > 0 && b.x >= 0 && b.y >= 0 &&
        (b.x + b.w) <= window.innerWidth && (b.y + b.h) <= window.innerHeight
    };
  }

  function describe(el) {
    if (!el) return null;
    var b;
    // 这里**故意不滚动**：describe 被诊断、候选列表、以及**录制**全程调用，
    // 一旦在这里滚动，录制的每一下点击都会把页面跳一下（踩过：加上滚动后"录制看着没效果"）。
    // 要滚动请用 pointFor()（专门给"马上要注入触摸"用的）。
    try { b = boxOf(el); } catch (e) { b = { x: -1, y: -1, w: 0, h: 0, cx: -1, cy: -1 }; }
    var cls = '';
    try { cls = (typeof el.className === 'string' ? el.className : (el.className && el.className.baseVal) || ''); } catch (e) {}
    return {
      tag: (el.tagName || '').toLowerCase(),
      id: el.id || '',
      cls: String(cls).slice(0, 120),
      text: textOf(el),
      selector: cssPath(el),
      box: b,
      visible: isVisible(el)
    };
  }

  /** 可点击性诊断：不是 <a>、被盖住、只有某种事件处理器 —— 这几类「点不动」要能说清原因 */
  function diagOf(el) {
    var d = { tag: (el.tagName || '').toLowerCase(), isAnchor: el.tagName === 'A' };
    try { d.hasOnclickAttr = el.hasAttribute('onclick'); } catch (e) {}
    try { d.onclickProp = typeof el.onclick === 'function'; } catch (e) {}
    try { d.role = el.getAttribute('role') || ''; } catch (e) {}
    try { d.tabindex = el.getAttribute('tabindex') || ''; } catch (e) {}
    try { d.pointerEvents = getComputedStyle(el).pointerEvents; } catch (e) {}
    try { d.cursor = getComputedStyle(el).cursor; } catch (e) {}
    try { d.type = el.getAttribute('type') || ''; } catch (e) {}
    try { d.href = el.getAttribute('href') || ''; } catch (e) {}
    var b = boxOf(el);
    try {
      var hit = document.elementFromPoint(clampX(b.cx), clampY(b.cy));
      var top = hit;
      d.hitSelf = hit === el;
      var up = 0;
      while (hit && hit !== el && up++ < 6) hit = hit.parentElement;
      d.coveredBy = d.hitSelf ? '' : (top ? tagLabel(top) : '');
      d.hitAncestorOfTarget = up <= 6 && !!hit;
    } catch (e) {}
    // 往上找第一个「看着可点」的祖先：很多站点把处理器挂在包装层上
    var n = el, hop = 0;
    while (n && hop++ < 5) {
      var ok = false;
      try {
        var st = getComputedStyle(n);
        if (n.getAttribute && (n.getAttribute('role') === 'button' || n.hasAttribute('onclick') || n.getAttribute('tabindex') === '0')) ok = true;
        if (st.cursor === 'pointer') ok = true;
        if (n.tagName === 'A' || n.tagName === 'BUTTON') ok = true;
      } catch (e) {}
      if (ok) { d.clickableAncestor = (n.tagName || '').toLowerCase() + (n.id ? '#' + n.id : '') + (n === el ? '' : ' (上级 ' + hop + ' 层)'); break; }
      n = n.parentElement;
    }
    return d;
  }

  function clampX(x) { return Math.max(0, Math.min(window.innerWidth - 1, x)); }
  function clampY(y) { return Math.max(0, Math.min(window.innerHeight - 1, y)); }

  /** 元素的「一眼认得出」标签：div#cover.overlay —— 诊断遮挡/命中时比光看 class 有用得多 */
  function tagLabel(el) {
    if (!el) return '';
    var t = (el.tagName || '').toLowerCase();
    if (el.id) t += '#' + el.id;
    var c = '';
    try { c = (typeof el.className === 'string' ? el.className : (el.className && el.className.baseVal) || ''); } catch (e) {}
    c = String(c).trim().split(/\s+/).slice(0, 2).join('.');
    if (c) t += '.' + c;
    return t;
  }

  /** 位置锚点：把点击位置换算成「距底部/顶部/左右各多少」，同时给比例，视口变了也能还原 */
  function anchorOf(box, scrollY) {
    var vw = window.innerWidth, vh = window.innerHeight;
    var cx = box.cx, cy = box.cy;
    var dTop = cy, dBottom = vh - cy, dLeft = cx, dRight = vw - cx;
    var mode = dBottom <= dTop ? 'bottom' : 'top';
    return {
      mode: mode,
      topPx: Math.round(dTop), bottomPx: Math.round(dBottom),
      leftPx: Math.round(dLeft), rightPx: Math.round(dRight),
      cx: cx, cy: cy,
      vw: vw, vh: vh,
      ratioY: +(cy / vh).toFixed(4), ratioX: +(cx / vw).toFixed(4),
      ratioBottom: +(dBottom / vh).toFixed(4), ratioTop: +(dTop / vh).toFixed(4),
      scrollY: Math.round(scrollY || 0),
      docH: Math.round((document.documentElement && document.documentElement.scrollHeight) || 0)
    };
  }

  // ------------------------------------------------------------------ 点击统计（用来验证「真的触发了」）

  var stats = { clicks: 0, trustedClicks: 0, lastTrusted: false, last: null, pointerdowns: 0 };

  document.addEventListener('click', function (e) {
    try {
      stats.clicks++;
      if (e.isTrusted) stats.trustedClicks++;
      stats.lastTrusted = !!e.isTrusted;
      stats.last = {
        tag: e.target && e.target.tagName ? e.target.tagName.toLowerCase() : '',
        text: textOf(e.target), x: Math.round(e.clientX), y: Math.round(e.clientY),
        trusted: !!e.isTrusted, ts: Date.now()
      };
      send({ t: 'click', trusted: !!e.isTrusted, tag: stats.last.tag, text: stats.last.text, x: stats.last.x, y: stats.last.y });
    } catch (err) {}
  }, true);

  document.addEventListener('pointerdown', function (e) {
    try { stats.pointerdowns++; } catch (err) {}
  }, true);

  // ------------------------------------------------------------------ 录制

  var rec = { on: false, steps: [], name: '', startUrl: '', t0: 0, lastTs: 0, badge: null };

  function badge(show) {
    try {
      if (show) {
        if (rec.badge) { rec.badge.parentNode && rec.badge.parentNode.removeChild(rec.badge); }
        var d = document.createElement('div');
        d.textContent = '● CDP 录制中';
        d.setAttribute('style',
          'position:fixed;left:50%;transform:translateX(-50%);top:8px;z-index:2147483647;' +
          'background:rgba(220,40,40,.92);color:#fff;font:12px/1.6 -apple-system,sans-serif;' +
          'padding:2px 10px;border-radius:10px;pointer-events:none');
        (document.body || document.documentElement).appendChild(d);
        rec.badge = d;
      } else if (rec.badge) {
        rec.badge.parentNode && rec.badge.parentNode.removeChild(rec.badge);
        rec.badge = null;
      }
    } catch (e) {}
  }

  function flash(el) {
    try {
      var r = el.getBoundingClientRect();
      var d = document.createElement('div');
      d.setAttribute('style',
        'position:fixed;left:' + r.left + 'px;top:' + r.top + 'px;width:' + r.width + 'px;height:' + r.height + 'px;' +
        'border:2px solid #3BA7F0;border-radius:4px;z-index:2147483646;pointer-events:none;transition:opacity .45s;opacity:1');
      (document.body || document.documentElement).appendChild(d);
      setTimeout(function () { d.style.opacity = '0'; }, 260);
      setTimeout(function () { d.parentNode && d.parentNode.removeChild(d); }, 900);
    } catch (e) {}
  }

  // ------------------------------------------------------------------ 页内查询（find in page）
  // 把命中处包成 <mark data-cdp-hit>（看得见），并把"当前那一个"滚进视野。
  // 清掉上一轮时会还原成纯文本节点（不留痕迹）。
  var findState = { q: '', hits: [], idx: -1 };

  function findClear() {
    try {
      var old = document.querySelectorAll('mark[data-cdp-hit]');
      for (var i = 0; i < old.length; i++) {
        var m = old[i], p = m.parentNode;
        if (!p) continue;
        p.replaceChild(document.createTextNode(m.textContent), m);
        p.normalize();
      }
    } catch (e) {}
    findState = { q: '', hits: [], idx: -1 };
    return { ok: true, count: 0, idx: -1 };
  }

  function findInPage(q, dir) {
    q = String(q || '');
    if (!q) return findClear();
    if (q !== findState.q) {
      findClear();
      var lower = q.toLowerCase();
      var hits = [];
      try {
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
          acceptNode: function (n) {
            if (!n.nodeValue || !n.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
            var p = n.parentNode;
            if (!p) return NodeFilter.FILTER_REJECT;
            var tag = (p.tagName || '').toLowerCase();
            if (tag === 'script' || tag === 'style' || tag === 'noscript' || tag === 'textarea' || tag === 'mark') return NodeFilter.FILTER_REJECT;
            return n.nodeValue.toLowerCase().indexOf(lower) >= 0 ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
          }
        });
        var n;
        while ((n = walker.nextNode())) {
          var text = n.nodeValue, low = text.toLowerCase(), from = 0, at;
          var frag = document.createDocumentFragment(), last = 0;
          while ((at = low.indexOf(lower, from)) >= 0) {
            if (at > last) frag.appendChild(document.createTextNode(text.slice(last, at)));
            var mk = document.createElement('mark');
            mk.setAttribute('data-cdp-hit', '1');
            mk.textContent = text.slice(at, at + q.length);
            frag.appendChild(mk);
            hits.push(mk);
            last = at + q.length;
            from = last;
          }
          if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
          try { n.parentNode.replaceChild(frag, n); } catch (e2) {}
          if (hits.length > 800) break;   // 页面上匹配太多就别继续包了，免得卡
        }
      } catch (e) {}
      findState = { q: q, hits: hits, idx: -1 };
    }
    var st = findState;
    if (!st.hits.length) return { ok: true, count: 0, idx: -1, q: q };
    var step = (dir === -1 || dir === 'prev') ? -1 : 1;
    st.idx = (st.idx + step + st.hits.length) % st.hits.length;
    for (var k = 0; k < st.hits.length; k++) {
      try { st.hits[k].style.background = (k === st.idx) ? '#ffb400' : '#ffe9a8'; } catch (e3) {}
    }
    try { st.hits[st.idx].scrollIntoView({ block: 'center' }); } catch (e4) {}
    return { ok: true, count: st.hits.length, idx: st.idx + 1, q: q };
  }

  // ------------------------------------------------------------------ 静音此页
  // 有的站点一进来就自动放声音。Android 的 WebView 没有公开的"整页静音"接口，
  // 能做到的是：把现在所有媒体静音 + 暂停，并挂住后续的 play/自动播放，让它出声就被压住。
  function muteAll(on) {
    var n = 0;
    function mute(el) {
      try {
        if (on) { el.muted = true; if (!el.paused) { el.pause(); } el.setAttribute('data-cdp-muted', '1'); }
        else if (el.getAttribute('data-cdp-muted') === '1') { el.muted = false; el.removeAttribute('data-cdp-muted'); }
        n++;
      } catch (e) {}
    }
    var all = document.querySelectorAll('video,audio');
    for (var i = 0; i < all.length; i++) mute(all[i]);
    try {
      if (on && !window.__cdpMuteHooked) {
        window.__cdpMuteHooked = true;
        document.addEventListener('play', function (e) {
          if (window.__cdpMuted && e.target && e.target.muted !== undefined) {
            try { e.target.muted = true; } catch (x) {}
          }
        }, true);
        // 页面换了新节点也按住
        setInterval(function () {
          if (!window.__cdpMuted) return;
          var els = document.querySelectorAll('video:not([data-cdp-muted]),audio:not([data-cdp-muted])');
          for (var i = 0; i < els.length; i++) { try { els[i].muted = true; els[i].setAttribute('data-cdp-muted', '1'); } catch (x) {} }
        }, 1500);
      }
    } catch (e) {}
    window.__cdpMuted = !!on;
    return { ok: true, muted: (document.querySelectorAll('[data-cdp-muted]').length), mediaSeen: n, on: !!on };
  }

  // 「不用看源码也能设定」：点一下拾取，页面上点哪就抓到哪（选择器 + 文字 + 可点击性诊断），
  // 由原生侧回灌到控制台的输入框里。拾取期间点击只抓元素、不触发页面本身的行为。
  var pickMode = { on: false };

  function pickArm() {
    pickMode.on = true;
    try { document.documentElement.style.cursor = 'crosshair'; } catch (e) {}
    return { ok: true, note: '拾取已就绪：在页面上点要抓的元素' };
  }

  document.addEventListener('pointerdown', function (e) {
    if (!pickMode.on) return;
    pickMode.on = false;
    try { document.documentElement.style.cursor = ''; } catch (e2) {}
    try { e.preventDefault(); e.stopPropagation(); } catch (e3) {}
    try {
      var el = e.target;
      if (!el || el.nodeType !== 1) return;
      flash(el);
      var inFrame = (function () { try { return window.top !== window; } catch (x) { return true; } })();
      send({
        t: 'picked',
        selector: cssPath(el),
        target: describe(el),
        diag: diagOf(el),
        attrs: attrsOf(el),
        url: location.href,
        inFrame: inFrame,
        frame: inFrame ? location.href : ''
      });
    } catch (err) { log('拾取出错: ' + err.message); }
  }, true);

  // 兜底：同源 iframe 里的点击，顶层也直接记一份（标 inFrame=true）。
  // 为什么：子 frame 自己上报那条链路（mirror + XHR）偶发会晚/丢（实测两次跑结果不同），
  // 顶层能看到的同源 frame 就顺手记下来，避免整条"iframe 录制"用例偶发假红。
  function hookSameOriginFrames() {
    if (window.top !== window) return;
    var frames = document.querySelectorAll('iframe,frame');
    for (var i = 0; i < frames.length; i++) {
      try {
        var d = frames[i].contentDocument;
        if (!d || d.__cdpRecHooked) continue;
        d.__cdpRecHooked = true;
        d.addEventListener('pointerdown', function (ev) {
          // 和顶层一样：点下去这一刻先同步问一次原生侧"是不是在录"。
          // 只靠定时镜像（1.2s 一次）会漏——测试里就出现过"偶尔没报到顶层"。
          if (!rec.on && recMirror.on) { rec.on = true; rec.name = recMirror.name || rec.name; rec.mode = recMirror.mode === 'coord' ? 'coord' : (rec.mode || 'element'); rec.nativeCoord = (rec.mode === 'coord'); }
          if (!rec.on) {
            try {
              var x = new XMLHttpRequest();
              x.open('GET', 'https://cdp-meta.local/meta' + (KQ ? ('?k=' + TOK) : ''), false);
              x.send(null);
              if (x.status === 200 || x.status === 0) {
                var m = JSON.parse(x.responseText);
                if (m && m.recording) { rec.on = true; rec.name = m.recName || rec.name; rec.mode = m.recMode === 'coord' ? 'coord' : (rec.mode || 'element'); recMirror.on = true; recMirror.mode = rec.mode; rec.nativeCoord = (rec.mode === 'coord'); }
              }
            } catch (errF) {}
          }
          if (!rec.on) return;
          try {
            var el = ev.target;
            if (!el || el.nodeType !== 1) return;
            var now = Date.now();
            if (now - (d.__cdpLastTs || 0) < 280) return;
            d.__cdpLastTs = now;
            var box = boxOf(el);
            send({
              t: 'recStep', index: -1, step: {
                t: 'click', ts: now, url: d.location.href, title: d.title || '',
                selector: cssPath(el), x: box.cx, y: box.cy,
                text: String(el.innerText || el.value || '').slice(0, 40),
                tag: el.tagName ? el.tagName.toLowerCase() : '',
                inFrame: true, frame: d.location.href, fromTop: true
              }
            });
          } catch (e2) {}
        }, true);
      } catch (e) {}
    }
  }

  try { setInterval(hookSameOriginFrames, 1500); setTimeout(hookSameOriginFrames, 300); } catch (e) {}

  function onRecPointerDown(e) {
    // 顶层用 rec.on；子 frame（iframe 里的播放器等）靠 recMirror —— 原生侧把"正在录制"塞在 meta 里
    if (!rec.on && recMirror.on) { rec.on = true; rec.name = recMirror.name || rec.name; rec.mode = recMirror.mode === 'coord' ? 'coord' : (rec.mode || 'element'); }
    if (!rec.on) {
      // 兜底：定时器在子 frame 里可能被节流，那就"点下去这一刻"同步问一次原生侧。
      // 代价极低（虚拟源，本进程直接回），换来的是 iframe 里的点击不会漏记。
      try {
        var x = new XMLHttpRequest();
        x.open('GET', 'https://cdp-meta.local/meta' + (KQ ? ('?k=' + TOK) : ''), false);
        x.send(null);
        if (x.status === 200 || x.status === 0) {
          var m = JSON.parse(x.responseText);
          if (m && m.recording) { rec.on = true; rec.name = m.recName || rec.name; rec.mode = m.recMode === 'coord' ? 'coord' : (rec.mode || 'element'); recMirror.on = true; recMirror.mode = rec.mode; rec.nativeCoord = (rec.mode === 'coord'); }
        }
      } catch (err2) {}
    }
    if (!rec.on) return;
    try {
      var el = e.target;
      if (!el || el.nodeType !== 1) return;
      var now = Date.now();
      if (now - rec.lastTs < 280) return;           // 连击去重
      rec.lastTs = now;
      var box = boxOf(el);
      var inFrame = (function () { try { return window.top !== window; } catch (e) { return true; } })();
      var coordStep = function () {
        // 坐标录制：不记选择器 / 指纹 / 诊断，只记"点在哪"。
        // 回放时定位链没有可匹配的东西，会直接走位置锚点 → 在那一处注入真实触摸。
        var cstep = {
          t: 'click', ts: now, url: location.href, title: document.title, mode: 'coord',
          selector: '',
          target: { selector: '', id: '', tag: el.tagName ? el.tagName.toLowerCase() : '', text: '', attrs: {}, box: box },
          box: box, anchor: anchorOf(box, window.scrollY),
          frame: inFrame ? location.href : '', inFrame: inFrame,
          note: '坐标录制（只看点在哪，不看元素）', pauseAfter: 350
        };
        rec.steps.push(cstep);
        flash(el);
        send({ t: 'recStep', index: rec.steps.length - 1, step: cstep });
      };
      if (isOwnUiPage()) return;                       // 操作自己的界面，不算"录目标页"
      if (rec.mode === 'coord') {
        // 坐标步**一律由原生层记**（Activity/Bridge.addNativeCoordStep + op anchorAt）：
        // 页内这套在跨域 iframe / 视频表面 / 原生表面覆盖时都可能漏，而且两套都记会重复。
        // 这里直接不记，保证"一次真实点击 = 一条坐标步"。
        return;
      }
      var step = {
        t: 'click',
        ts: now,
        url: location.href,
        title: document.title,
        mode: 'element',
        selector: cssPath(el),
        target: describe(el),
        box: box,
        anchor: anchorOf(box, window.scrollY),
        diag: diagOf(el),
        // 记下"这一步发生在哪个文档"：iframe 里的点击在回放时不能直接照搬（跨域 frame 顶层点不到），
        // 但至少要让人一眼看出它是子 frame 里的步骤
        frame: inFrame ? location.href : '',
        inFrame: inFrame,
        pauseAfter: 350
      };
      rec.steps.push(step);
      flash(el);
      send({ t: 'recStep', index: rec.steps.length - 1, step: step });
    } catch (err) { log('录制出错: ' + err.message); }
  }

  // ------------------------------------------------------------------ 定位

  /**
   * 文字相似度打分。注意「包含」这一档：候选元素的文字比目标长很多时，多半是**包住目标的容器
   * 或旁边的兄弟节点**（例如两个按钮的文案互相包含），给低分让它别抢走目标 ——
   * 真正该走的是位置锚点那一路。实测踩过：一个包含目标整串文字的新 div 把目标抢走，点了个空。
   */
  function textScore(el, want) {
    if (!want) return 0;
    var t = textOf(el);
    if (t === want) return 100;
    if (t.indexOf(want) >= 0) {
      var ratio = t.length / Math.max(1, want.length);
      return ratio <= 1.6 ? 70 : 30;
    }
    if (want.indexOf(t) >= 0 && t.length > 1) return 40;
    return 0;
  }

  function candidatesByText(want, tagHint) {
    var out = [], all = document.querySelectorAll(tagHint || 'a,button,div,span,li,i,em,strong,p,td,input,label,section,article');
    for (var i = 0; i < all.length && out.length < 40; i++) {
      var el = all[i];
      var s = textScore(el, want);
      if (s <= 0) continue;
      if (!isVisible(el)) s -= 30;
      out.push({ el: el, score: s });
    }
    // 精确文字优先、面积小的优先（避免点到包住整页的大容器）
    out.sort(function (a, b) {
      if (b.score !== a.score) return b.score - a.score;
      var ra = a.el.getBoundingClientRect(), rb = b.el.getBoundingClientRect();
      return ra.width * ra.height - rb.width * rb.height;
    });
    return out;
  }

  function candidatesByAttrs(fp) {
    var keys = ['name', 'aria-label', 'title', 'data-name', 'data-title', 'placeholder', 'value', 'alt'];
    var out = [];
    for (var i = 0; i < keys.length; i++) {
      var v = fp.attrs && fp.attrs[keys[i]];
      if (!v) continue;
      try {
        var els = document.querySelectorAll('[' + keys[i] + '="' + String(v).replace(/"/g, '\\"') + '"]');
        for (var j = 0; j < els.length && j < 6; j++) out.push({ el: els[j], score: 60 });
      } catch (e) {}
    }
    return out;
  }

  function attrsOf(el) {
    var o = {}, names = ['name', 'aria-label', 'title', 'data-name', 'data-title', 'placeholder', 'value', 'alt', 'type', 'href', 'role'];
    for (var i = 0; i < names.length; i++) {
      try { var v = el.getAttribute(names[i]); if (v) o[names[i]] = String(v).slice(0, 80); } catch (e) {}
    }
    return o;
  }

  function pickBest(cands, anchor) {
    if (!cands.length) return null;
    if (!anchor) return cands[0];
    var best = cands[0], bestD = 1e9;
    for (var i = 0; i < Math.min(cands.length, 8); i++) {
      var c = cands[i];
      var b = c.el.getBoundingClientRect();
      var dx = (b.left + b.width / 2) - anchor.cx, dy = (b.top + b.height / 2) - anchor.cy;
      var d = Math.sqrt(dx * dx + dy * dy) - c.score * 2;
      if (d < bestD) { bestD = d; best = c; }
    }
    return best;
  }

  /** 核心：多策略定位，返回命中的元素、所用策略、以及它在不在可视区内 */
  function locate(step) {
    var fp = step.target || {};
    var anchor = step.anchor || null;
    var tried = [];

    // ① id 唯一
    if (fp.id) {
      try {
        var byId = document.getElementById(fp.id);
        tried.push('id');
        if (byId && (!fp.tag || byId.tagName.toLowerCase() === fp.tag) && textScore(byId, fp.text) >= 0) {
          return hit(byId, 'id', 100, tried, anchor);
        }
      } catch (e) {}
    }
    // ② 结构路径
    if (fp.selector) {
      try {
        var byPath = document.querySelector(fp.selector);
        tried.push('path');
        if (byPath) {
          var tagOk = !fp.tag || byPath.tagName.toLowerCase() === fp.tag;
          var tOk = !fp.text || textScore(byPath, fp.text) > 0;
          if (tagOk && tOk) return hit(byPath, 'path', 90, tried, anchor);
        }
      } catch (e) {}
    }
    // ③ 属性指纹
    var byAttr = candidatesByAttrs(fp);
    tried.push('attrs');
    if (byAttr.length) {
      var bestA = pickBest(byAttr, anchor);
      if (bestA) return hit(bestA.el, 'attrs', bestA.score, tried, anchor);
    }
    // ④ 文字（同标签优先）。只有「够像」的文字匹配才认（≥70），
    //    否则宁可继续往下走位置锚点 —— 别让一个只是「包含这段文字」的元素把目标抢走
    tried.push('text');
    var byText = candidatesByText(fp.text, null).filter(function (c) { return c.score >= 70; });
    if (byText.length) {
      var sameTag = byText.filter(function (c) { return !fp.tag || c.el.tagName.toLowerCase() === fp.tag; });
      var bestT = pickBest(sameTag.length ? sameTag : byText, anchor);
      if (bestT) return hit(bestT.el, sameTag.length ? 'text+tag' : 'text', bestT.score, tried, anchor);
    }
    // ⑤ 只剩位置锚点：把锚点换算成当前视口坐标，看那个点上（以及它下面）有没有对得上指纹的元素
    if (anchor) {
      tried.push('anchor');
      var p = pointFromAnchor(anchor);
      if (p.ok) {
        try {
          var stack = document.elementsFromPoint
            ? document.elementsFromPoint(clampX(p.x), clampY(p.y))
            : [document.elementFromPoint(clampX(p.x), clampY(p.y))];
          var best = null, bestScore = -99, why = [];
          for (var i = 0; i < stack.length && i < 8; i++) {
            var e = stack[i];
            if (!e || e.nodeType !== 1) continue;
            var sc = 0, tags = [];
            var tag = (e.tagName || '').toLowerCase();
            if (fp.id && e.id === fp.id) { sc += 5; tags.push('id'); }
            if (fp.tag && tag === fp.tag) { sc += 2; tags.push('tag'); }
            if (fp.text && textScore(e, fp.text) > 0) { sc += 3; tags.push('text'); }
            try {
              var b = e.getBoundingClientRect();
              // 用「录制时的框」和「当前元素的框」的交叠度打分 —— 位置锚点的语义就是「它应该还在这一带」。
              // 只比宽高容易误判（文字变了宽度就变），交叠度对「同一个目标但内容变了」更宽容，
              // 对「同一位置换成别的元素」更严格。
              if (fp.box && fp.box.w > 0 && fp.box.h > 0) {
                var rw = fp.box.w, rh = fp.box.h;
                var rl = p.x - rw / 2, rt = p.y - rh / 2;
                var ox = Math.max(0, Math.min(rl + rw, b.right) - Math.max(rl, b.left));
                var oy = Math.max(0, Math.min(rt + rh, b.bottom) - Math.max(rt, b.top));
                var inter = ox * oy;
                var uni = rw * rh + b.width * b.height - inter;
                var iou = uni > 0 ? inter / uni : 0;
                sc += iou * 4;
                if (iou > 0.3) tags.push('位置重合');
              }
              // 包住大半屏的容器不要当目标（这正是「点到容器而不是按钮」的成因）
              if (b.width * b.height > window.innerWidth * window.innerHeight * 0.8) { sc -= 5; tags.push('太大'); }
            } catch (e2) {}
            if (sc > bestScore) { bestScore = sc; best = e; why = tags; }
          }
          // 坐标录制的步骤：**只认位置**，不做"像不像"校验。
          // （原生层录的那条没带文字/属性，只有 tag，按相似度算永远"不够像"→ 被拒；
          //  而坐标录制的语义本来就是"就点在这儿，不管那儿现在是什么"。）
          var coordStep = (step.mode === 'coord') || (step.via === 'native-coord');
          var hasFp = !coordStep && !!(fp.id || fp.selector || fp.text || fp.tag);
          if ((!hasFp || coordStep) && stack.length) {
            var l0 = hit(stack[0], coordStep ? 'anchor-coord' : 'anchor-only', 40, tried, anchor, p);
            if (coordStep) l0.matchedBy = ['坐标录制：只认位置'];
            return l0;
          }
          if (best && why.length && bestScore >= 4) {
            var l = hit(best, 'anchor-point', 40 + Math.round(bestScore), tried, anchor, p);
            l.matchedBy = why;
            l.weak = bestScore < 6;    // 只勉强对上时标出来，调用方心里有数
            return l;
          }
          if (best) {
            // 只有「标签都是 div」这种程度的相似 —— 不点。宁可报失败，也不要假装点到了别的元素
            return {
              ok: false, tried: tried, anchorUsed: p,
              error: '锚点位置上最像的是 ' + tagLabel(best) + '（相似度 ' + bestScore + ' 分，只对得上 ' + (why.join('/') || '无') + '），不够像，不猜着点',
              stack: stack.slice(0, 5).map(tagLabel)
            };
          }
        } catch (e) {}
      }
    }
    return { ok: false, error: '五种策略都没找到目标', tried: tried, fp: fp };
  }

  function hit(el, strategy, score, tried, anchor, p) {
    var d = describe(el);
    var vw = window.innerWidth, vh = window.innerHeight;
    var inView = d.box.x >= 0 && d.box.y >= 0 && (d.box.x + d.box.w) <= vw && (d.box.y + d.box.h) <= vh;
    var partial = d.box.x < vw && d.box.y < vh && (d.box.x + d.box.w) > 0 && (d.box.y + d.box.h) > 0;
    return {
      ok: true, strategy: strategy, score: score, tried: tried,
      el: d, inViewport: inView, partiallyVisible: partial,
      anchorUsed: p || null,
      diag: diagOf(el)
    };
  }

  /** 把录制时的「相对底部/顶部」锚点，换算成当前视口里的点（先尽力还原滚动位置） */
  function pointFromAnchor(a) {
    if (!a) return { ok: false, error: '没有锚点' };
    try {
      if (a.scrollY != null && document.documentElement) {
        var cur = window.scrollY;
        var dh = document.documentElement.scrollHeight;
        if (Math.abs(cur - a.scrollY) > 4 && (a.docH == null || Math.abs(dh - a.docH) < dh * 0.3)) {
          window.scrollTo(0, a.scrollY);
        }
      }
      var vw = window.innerWidth, vh = window.innerHeight;
      var x, y;
      var vhChanged = Math.abs(vh - (a.vh || vh)) > 40;
      var vwChanged = Math.abs(vw - (a.vw || vw)) > 20;
      y = vhChanged ? Math.round((a.ratioY || 0.5) * vh)
                    : (a.mode === 'bottom' ? vh - a.bottomPx : a.topPx);
      x = vwChanged ? Math.round((a.ratioX || 0.5) * vw)
                    : (a.leftPx == null ? Math.round(vw / 2) : a.leftPx);
      x = clampX(x); y = clampY(y);
      var el = document.elementFromPoint(x, y);
      return {
        ok: true, x: x, y: y, mode: a.mode,
        viewportChanged: { w: vwChanged, h: vhChanged },
        el: describe(el)
      };
    } catch (e) { return { ok: false, error: '锚点换算失败: ' + e.message }; }
  }

  // ------------------------------------------------------------------ 合成事件（点不动的按钮走这里）

  function fire(target, type, opts) {
    var o = Object.assign({ bubbles: true, cancelable: true, composed: true, view: window }, opts || {});
    var ev;
    if (type.indexOf('pointer') === 0 && window.PointerEvent) {
      ev = new PointerEvent(type, o);
    } else if (type.indexOf('touch') === 0 && window.TouchEvent) {
      try {
        var t = new Touch({ identifier: 1, target: target, clientX: o.clientX || 0, clientY: o.clientY || 0, pageX: o.clientX || 0, pageY: o.clientY || 0 });
        ev = new TouchEvent(type, { bubbles: true, cancelable: true, composed: true, touches: type === 'touchend' ? [] : [t], targetTouches: type === 'touchend' ? [] : [t], changedTouches: [t] });
      } catch (e) { ev = null; }
    }
    if (!ev) {
      if (type.indexOf('touch') === 0) return false;
      ev = new MouseEvent(type, o);
    }
    try { target.dispatchEvent(ev); return true; } catch (e) { return false; }
  }

  function synthFull(el, pt) {
    var r = el.getBoundingClientRect();
    var x = pt ? pt.x : r.left + r.width / 2;
    var y = pt ? pt.y : r.top + r.height / 2;
    var base = { clientX: x, clientY: y, screenX: x, screenY: y, button: 0, buttons: 1, pointerId: 1, pointerType: 'touch', isPrimary: true };
    // 触摸事件链（很多移动端框架只认 touch）
    fire(el, 'touchstart', base); fire(el, 'touchend', base);
    // 指针链
    fire(el, 'pointerover', base); fire(el, 'pointerenter', base); fire(el, 'pointerdown', base);
    fire(el, 'mouseover', base); fire(el, 'mousedown', base);
    fire(el, 'pointerup', Object.assign({}, base, { buttons: 0 }));
    fire(el, 'mouseup', Object.assign({}, base, { buttons: 0 }));
    fire(el, 'click', Object.assign({}, base, { buttons: 0, detail: 1 }));
    return { x: x, y: y };
  }

  /** 兜底链：合成事件 → el.click() → 逐级往上找可点祖先（最多 4 层） */
  function forceClick(sel, step) {
    var el = null;
    try { if (sel) el = document.querySelector(sel); } catch (e) {}
    if (!el && step) { var l = locate(step); if (l.ok) el = queryFromDescribe(l.el); }
    if (!el) return { ok: false, via: 'none', error: '找不到元素' };

    var before = stamp();
    var n, levels = [el], up = el.parentElement;
    for (var i = 0; i < 4 && up; i++) { levels.push(up); up = up.parentElement; }

    for (var k = 0; k < levels.length; k++) {
      var cand = levels[k];
      var pt = synthFull(cand);
      if (stamp() !== before) {
        return { ok: true, via: k === 0 ? 'js-pointer' : 'js-ancestor+' + k, at: pt, changed: true };
      }
      try {
        if (cand.click) { cand.click(); }
        if (stamp() !== before) return { ok: true, via: k === 0 ? 'js-click' : 'js-ancestor-click+' + k, at: pt, changed: true };
      } catch (e) {}
    }
    return { ok: false, via: 'none', error: '合成事件与 click() 都没能让页面产生变化', before: before, after: stamp() };
  }

  function queryFromDescribe(d) {
    try {
      if (d.selector) { var q = document.querySelector(d.selector); if (q) return q; }
      if (d.id) { var q2 = document.getElementById(d.id); if (q2) return q2; }
      var cands = candidatesByText(d.text, null);
      return cands.length ? cands[0].el : null;
    } catch (e) { return null; }
  }

  /** 页面「指纹」：用来判断一次点击有没有真的改变页面（哪怕只是换了个 class） */
  function stamp() {
    try {
      var t = (document.body && (document.body.innerText || '').length) || 0;
      var n = document.querySelectorAll('*').length;
      var h = 0, s = String(location.href) + '|' + t + '|' + n + '|' + Math.round(window.scrollY);
      for (var i = 0; i < s.length; i++) { h = (h * 31 + s.charCodeAt(i)) | 0; }
      return h;
    } catch (e) { return 0; }
  }

  // ------------------------------------------------------------------ 用户脚本（油猴子集）
  //
  // 执行方式：由原生侧用 WebViewCompat.addDocumentStartJavaScript 逐个脚本注入（见 MainActivity.buildUserScript），
  // 不在页面里用 eval/new Function 跑脚本。原因（都是实测踩出来的）：
  //   ① new Function/eval 会撞页面 CSP（script-src 不含 unsafe-eval 时直接被拒）；
  //   ② 全塞进一个函数体，任何一个脚本括号不配就连累整块注入。
  //
  // 另一个坑：WebView 的 ScriptHandler.remove() 会**原生崩溃**（SIGSEGV in WebView，
  // 崩在反射进去的 remove 上）。所以注入只增不减，旧注入靠「版本号 + 内容哈希」自查失效：
  // 每个注入块开头都会问 _gm.active(id, hash)，对不上就直接 return。

  /** 同步取「当前有效脚本清单」（原生侧拦截这个虚拟地址，返回 {gen, scripts:{id:hash}}） */
  var META = (function () {
    try {
      var x = new XMLHttpRequest();
      x.open('GET', 'https://cdp-meta.local/meta' + (KQ ? ('?k=' + TOK) : ''), false);
      x.send(null);
      if (x.status === 200 || x.status === 0) return JSON.parse(x.responseText);
    } catch (e) {}
    return { gen: 0, scripts: {} };
  })();

  // 录制状态的镜像。子 frame（iframe 里的播放器就是这种）拿不到顶层文档的 rec，
  // 所以每隔一会儿问一次原生侧「现在在录吗」—— 顶层正常用 rec.on，子 frame 靠这个。
  var recMirror = { on: false, name: '' };
  function syncRecMirror() {
    try {
      if (META && typeof META.recording === 'boolean') {
        recMirror.on = !!META.recording;
        recMirror.name = META.recName || '';
      }
    } catch (e) {}
    try {
      var x = new XMLHttpRequest();
      x.open('GET', 'https://cdp-meta.local/meta' + (KQ ? ('?k=' + TOK) : ''), false);
      x.send(null);
      if (x.status === 200 || x.status === 0) {
        var m = JSON.parse(x.responseText);
        recMirror.on = !!m.recording;
        recMirror.name = m.recName || '';
      }
    } catch (e) {}
  }
  setTimeout(syncRecMirror, 300);
  setInterval(syncRecMirror, 1200);

  var GM_IMPL = {
    GM_setValue: function (k, v) { try { localStorage.setItem(this.__ns + k, JSON.stringify(v)); } catch (e) {} },
    GM_getValue: function (k, dflt) {
      try { var v = localStorage.getItem(this.__ns + k); return v === null ? dflt : JSON.parse(v); } catch (e) { return dflt; }
    },
    GM_deleteValue: function (k) { try { localStorage.removeItem(this.__ns + k); } catch (e) {} },
    GM_listValues: function () {
      var out = [];
      try { for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); if (k.indexOf(this.__ns) === 0) out.push(k.slice(this.__ns.length)); } } catch (e) {}
      return out;
    },
    GM_addStyle: function (css) {
      try {
        var st = document.createElement('style');
        st.textContent = css;
        (document.head || document.documentElement).appendChild(st);
        return st;
      } catch (e) { return null; }
    },
    GM_log: function () { log('[脚本 ' + this.__name + '] ' + Array.prototype.join.call(arguments, ' ')); },
    GM_notification: function (o) { log('[通知] ' + (typeof o === 'string' ? o : (o && o.text) || '')); },
    GM_openInTab: function (u) { try { window.open(u, '_blank'); } catch (e) {} },
    GM_registerMenuCommand: function (n, f) { (window.__CDP_MENU = window.__CDP_MENU || []).push({ name: n, fn: f }); },
    GM_xmlhttpRequest: function (o) {
      try {
        fetch(o.url, { method: o.method || 'GET', headers: o.headers || {}, body: o.data })
          .then(function (r) { return r.text().then(function (t) { o.onload && o.onload({ status: r.status, responseText: t, finalUrl: r.url }); }); })
          .catch(function (e) { o.onerror && o.onerror(e); });
      } catch (e) { o.onerror && o.onerror(e); }
    },
    unsafeWindow: window
  };

  /** 给一个脚本 id/名字绑定一套 GM_*（id 用来给 setValue 分命名空间） */
  function gmApi(id, name) {
    var ns = '__cdp_gm_' + id + '_';
    var out = {};
    for (var k in GM_IMPL) {
      if (typeof GM_IMPL[k] !== 'function') { out[k] = GM_IMPL[k]; continue; }
      out[k] = GM_IMPL[k].bind({ __ns: ns, __name: name });
    }
    return out;
  }

  /** @match/@include 命中判断（支持 * 通配；*:// 这种写法也能对上 http/https） */
  function matchesAny(pattern, url) {
    if (!pattern || pattern === '*') return true;
    var ps = String(pattern).split(',');
    for (var i = 0; i < ps.length; i++) {
      var p = ps[i].trim();
      if (!p) continue;
      var re = new RegExp('^' + p.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$', 'i');
      if (re.test(url)) return true;
    }
    return false;
  }

  /** 按 @run-at 决定执行时机（原生侧把每个脚本包成 U.run(...) 注入） */
  function runAt(at, fn) {
    try {
      if (at === 'document-start') { fn(); return; }
      if (at === 'document-end' || at === 'document-body') {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
        return;
      }
      if (document.readyState === 'complete') fn();
      else window.addEventListener('load', fn);
    } catch (e) { log('脚本执行时机处理出错: ' + e.message); }
  }

  function registerScripts(arr) { return (arr || []).length; }

  /** 这个脚本 id 当前的注入是否仍然有效（内容变了/被删了 → 旧的注入块自动失效） */
  function stillActive(id, hash) {
    try {
      var m = META && META.scripts;
      return !!(m && m[id] === hash);
    } catch (e) { return false; }
  }

  // ------------------------------------------------------------------ 命令入口

  function cmd(o) {
    try {
      if (typeof o === 'string') o = JSON.parse(o);
      var op = o.op;
      switch (op) {
        case 'ping': return J({ ok: true, ver: VERSION, url: location.href, title: document.title, vw: window.innerWidth, vh: window.innerHeight, dpr: window.devicePixelRatio });
        case 'metrics': return J({ ok: true, vw: window.innerWidth, vh: window.innerHeight, dpr: window.devicePixelRatio, url: location.href, title: document.title, scrollY: Math.round(window.scrollY), docH: document.documentElement ? document.documentElement.scrollHeight : 0 });
        case 'query': return J(queryCmd(o));
        case 'diag': return J(diagCmd(o));
        case 'elementAt': return J({ ok: true, stack: stackAt(o.x, o.y) });
        case 'locate': return J(locate(o.step || {}));
        case 'pointFor': return J(pointFor(o.sel || '', o.step || null));
        case 'pickArm': return J(pickArm());
        case 'mute': return J(muteAll(!!o.on));
        case 'find': return J(findInPage(o.q || '', o.dir));
        case 'findClear': return J(findClear());
        case 'pickCancel': pickMode.on = false; try { document.documentElement.style.cursor = ''; } catch (e) {}
          return J({ ok: true });
        case 'pointFromAnchor': return J(pointFromAnchor(o.anchor));
        /**
         * anchorAt{x,y} —— 给**原生层**的坐标录制用。
         *
         * 为什么要有它：跨域 iframe（学习通播放器）和原生视频 surface 里，页面 JS 根本收不到 pointerdown，
         * 所以页内那套坐标录制"点视频录不到"。原生层在 Activity 这一层能拿到**所有**真实触摸，
         * 拿到之后再回到顶层页面问"这个点是什么/锚点在哪"，两边口径就一致了。
         */
        case 'anchorAt': {
          var ax = Number(o.x) || 0, ay = Number(o.y) || 0;
          var elAt = null;
          try { elAt = document.elementFromPoint(ax, ay); } catch (e) { elAt = null; }
          var box = { cx: ax, cy: ay, x: ax, y: ay, w: 1, h: 1 };
          return J({
            ok: true, url: location.href, title: document.title,
            scrollY: Math.round(window.scrollY), vw: window.innerWidth, vh: window.innerHeight,
            anchor: anchorOf(box, window.scrollY),
            el: elAt ? { tag: (elAt.tagName || '').toLowerCase(), id: elAt.id || '',
                         text: String(elAt.textContent || '').trim().slice(0, 40),
                         isFrame: (elAt.tagName || '').toLowerCase() === 'iframe' } : null
          });
        }
        case 'scrollIntoView': return J(scrollIntoViewCmd(o.step || {}));
        case 'clickStats': return J({ ok: true, clicks: stats.clicks, trustedClicks: stats.trustedClicks, pointerdowns: stats.pointerdowns, lastTrusted: stats.lastTrusted, last: stats.last });
        case 'stamp': return J({ ok: true, stamp: stamp(), url: location.href, title: document.title, docH: document.documentElement ? document.documentElement.scrollHeight : 0 });
        case 'forceClick': return J(forceClick(o.sel || '', o.step || null));
        case 'scrollTo': window.scrollTo(0, o.y || 0); return J({ ok: true, scrollY: Math.round(window.scrollY) });
        case 'setValue': return J(setValueCmd(o));
        case 'recordStart': return J(recordStart(o.name || '录制脚本', o.mode || 'element'));
        case 'recordStop': return J(recordStop());
        case 'recordSteps': return J({ ok: true, steps: rec.steps, on: rec.on, mode: rec.mode || 'element' });
        case 'recordClear': rec.steps = []; return J({ ok: true, count: 0 });
        case 'dotsSet': return J(dotsSet(o));
        case 'mediaAt': return J(mediaAt(o));
        case 'pickHint': return J(pickHint(o));
        case 'dotsState': return J({ ok: true, on: DOTS.on, count: DOTS.list.length,
          dots: DOTS.list, layer: !!DOTS.layer,
          nodes: (DOTS.layer ? DOTS.layer.querySelectorAll('.cdp-dot').length : 0) });
        case 'readTime': {
          // 阅读/停留时间上报（清单第 22 条）：只报地址、标题、停留毫秒，别的一律不带
          send({ t: 'readTime', url: o.url || location.href, title: o.title || document.title,
                 ms: o.ms || 0, mins: o.mins || 0 });
          return J({ ok: true });
        }
        case 'insertStep': return J(insertStep(o.step));
        case 'highlight': return J(highlightCmd(o));
        case 'check': return J(checkCond(o.cond));
        case 'video': return J(videoCmd(o));
        case 'videoState': return J({ ok: true, videos: videoState(o.selector) });
        case 'check': return J(checkCond(o.cond));
        case 'video': return J(videoCmd(o));
        case 'videoState': return J({ ok: true, videos: videoState(o.selector) });
        case 'scripts': {
          var m = (window.__CDP && window.__CDP._meta) || META || { scripts: {} };
          var ks = Object.keys(m.scripts || {});
          return J({ ok: true, count: ks.length, ids: ks, gen: m.gen });
        }
        default: return J({ ok: false, error: 'agent 不认识的命令: ' + op });
      }
    } catch (e) {
      return J({ ok: false, error: 'agent 异常: ' + (e && e.message) });
    }
  }


  // ---------------------------------------------------------------- 页面上的小点（坐标步骤的落点）
  // 照 clicker 那套做：每个**坐标步骤**在页面上画一个编号小圆点，
  //   · 编号 = 它在步骤队列里的序号（元素步不画点，但序号照占，所以页面上可能是 1 2 4）；
  //   · 状态色：待执行 / 当前 / 已执行（跟 clicker 一样三态）；
  //   · 点一下小点 = "让它自己点这一下"（回放这一步）；
  //   · 同一个点多下 → 角标显示 ×N（连点次数）。
  // 用 fixed 覆盖层 + pointer-events 只在点上打开，页面自己收不到点以外的触摸。
  var DOTS = { layer: null, on: false, list: [], locked: false, progress: null };

  function dotsLayer() {
    if (DOTS.layer && DOTS.layer.parentNode) return DOTS.layer;
    var L = document.createElement('div');
    L.id = 'cdp-dots';
    L.setAttribute('data-renderer', 'agent.dots');
    L.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483000;pointer-events:none';
    (document.body || document.documentElement).appendChild(L);
    DOTS.layer = L;
    return L;
  }

  function dotsRender() {
    var L = dotsLayer();
    L.innerHTML = '';
    L.style.display = DOTS.on ? 'block' : 'none';
    if (!DOTS.on) return;
    DOTS.list.forEach(function (d) {
      var el = document.createElement('div');
      el.className = 'cdp-dot';
      el.id = 'cdp-dot-' + d.n;
      el.setAttribute('data-n', String(d.n));
      el.setAttribute('data-state', d.state || 'idle');
      el.setAttribute('data-x', String(Math.round(d.x)));
      el.setAttribute('data-y', String(Math.round(d.y)));
      el.textContent = String(d.n) + (d.reps > 1 ? '×' + d.reps : '');
      var bg = d.state === 'current' ? '#f0883e' : (d.state === 'done' ? '#2ea043' : '#1f6feb');
      el.style.cssText = 'position:fixed;left:' + Math.round(d.x) + 'px;top:' + Math.round(d.y) + 'px;' +
        'transform:translate(-50%,-50%);min-width:28px;height:28px;padding:0 6px;border-radius:14px;' +
        'background:' + bg + ';color:#fff;font:700 13px/28px system-ui,sans-serif;text-align:center;' +
        'box-shadow:0 1px 6px rgba(0,0,0,.5);border:2px solid rgba(255,255,255,.75);' +
        // DOTS.locked（回放/演示期间）= 点只用来"看"，不接触摸：注入的真实触摸打到点上会把这一步又点一遍（成环，B-09）
        'pointer-events:' + (DOTS.locked ? 'none' : 'auto') + ';' +
        'cursor:' + (DOTS.locked ? 'default' : 'pointer') + ';user-select:none';
      el.addEventListener('click', function (ev) {
        ev.stopPropagation(); ev.preventDefault();
        // "让它自己点"：把这一下报给原生层，由它按同一套锚点注入真实触摸
        send({ t: 'dotTap', n: d.n, x: d.x, y: d.y });
      });
      L.appendChild(el);
    });
  }

  // 回放进度那一行小字（用户要求："显示出正在执行，包括点，包括执行那一步"）。
  // 非交互元素（pointer-events:none），画在页面顶部中间：`回放 第 3/8 步 · 点「下一章」`
  function progressRender() {
    var p = DOTS.progress;
    var el = document.getElementById('cdp-replay-progress');
    if (!p) { if (el && el.parentNode) el.parentNode.removeChild(el); return; }
    if (!el) {
      el = document.createElement('div');
      el.id = 'cdp-replay-progress';
      el.setAttribute('data-renderer', 'agent.dots');
      el.style.cssText = 'position:fixed;left:50%;top:8px;transform:translateX(-50%);z-index:2147483001;' +
        'max-width:92vw;padding:6px 12px;border-radius:16px;background:rgba(0,0,0,.78);color:#fff;' +
        'font:600 13px/18px system-ui,sans-serif;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;' +
        'pointer-events:none;user-select:none';
      (document.body || document.documentElement).appendChild(el);
    }
    el.textContent = '回放 第 ' + p.i + '/' + p.n + ' 步 · ' + (p.label || '');
  }

  function dotsSet(o) {
    DOTS.on = !!o.on;
    // 回放/演示期间锁定：小点只显示状态，不接触摸（防成环，见上面 pointer-events 那句）
    DOTS.locked = !!o.locked;
    DOTS.progress = o.progress || null;
    DOTS.list = (o.dots || []).map(function (d) {
      return { n: d.n, x: d.x, y: d.y, state: d.state || 'idle', reps: d.reps || 1 };
    });
    dotsRender();
    progressRender();
    return { ok: true, on: DOTS.on, locked: DOTS.locked, count: DOTS.list.length, progress: DOTS.progress };
  }

  // ---------------------------------------------------------------- "指定哪一块是视频"
  // 用户：「浏览，页面与元素的那个页面的视频元素，对于学习通没效果。如果指定哪个块是视频元素就好了。」
  // 学习通的视频在**跨域 iframe** 里，顶层脚本读不到它的 <video>；这里只做一件事：
  // 把用户点的那一处（元素 + 祖先链 + 后代）里能拿到的媒体地址**全部列出来**交回原生层，
  // 一个都没有（blob/MSE 播放）就由原生层退回"最近的真实请求"里找候选。
  function mediaAt(o) {
    var x = +o.x, y = +o.y;
    var el = document.elementFromPoint(x, y);
    var out = { ok: true, x: x, y: y, candidates: [], el: null, iframe: null };
    if (!el) { out.ok = false; out.error = '这一处没有元素（点太靠边了）'; return out; }
    out.el = {
      tag: (el.tagName || '').toLowerCase(),
      text: String(el.textContent || '').trim().slice(0, 30),
      rect: boxOf(el)
    };
    var seen = {};
    function push(u, label) {
      if (!u) return;
      u = String(u);
      if (/^(data:|blob:|javascript:)/i.test(u)) return;
      if (seen[u]) return;
      seen[u] = 1;
      out.candidates.push({ url: u, label: label });
    }
    function scan(e) {
      if (!e || !e.tagName) return;
      var t = e.tagName.toUpperCase();
      if (t === 'VIDEO' || t === 'AUDIO') {
        push(e.currentSrc, '正在播的地址');
        push(e.getAttribute('src'), '元素 src');
        try {
          Array.prototype.forEach.call(e.querySelectorAll('source'), function (s) {
            push(s.getAttribute('src'), 'source');
          });
        } catch (err) {}
      }
      if (t === 'IFRAME' || t === 'FRAME') {
        var src = e.getAttribute('src') || '';
        var host = '';
        try { host = new URL(src, location.href).host; } catch (err) {}
        if (!out.iframe) out.iframe = { src: src, host: host, rect: boxOf(e) };
      }
      if (t === 'A') push(e.href, '链接');
      ['data-src', 'data-url', 'data-video', 'data-play'].forEach(function (k) {
        var v = e.getAttribute && e.getAttribute(k);
        if (v) push(v, k);
      });
    }
    var n = el, i = 0;
    while (n && i < 6) { scan(n); n = n.parentElement; i++; }
    try {
      Array.prototype.forEach.call(el.querySelectorAll('video,audio,iframe,source,a[href]'), scan);
    } catch (err) {}
    // 页面上正在播的 media（点在大容器/浮层上时救命）
    try {
      Array.prototype.forEach.call(document.querySelectorAll('video,audio'), function (v) {
        if (!v.paused || v.currentTime > 0) push(v.currentSrc, '页面里正在播的 media');
      });
    } catch (err) {}
    // "这一块附近到底有没有媒体"：有才允许退回"最近请求"里找候选，
    // 否则点空白处也会硬凑一个地址出来（用户会以为找错了）。
    var near = false;
    try {
      Array.prototype.forEach.call(document.querySelectorAll('video,audio,iframe'), function (v) {
        if (near) return;
        if (v === el || v.contains(el) || el.contains(v)) { near = true; return; }
        try {
          var a = v.getBoundingClientRect(), b = el.getBoundingClientRect();
          if (a.width > 0 && b.width > 0 && a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) near = true;
        } catch (err) {}
      });
    } catch (err) {}
    out.near = near;
    return out;
  }

  /** 选取提示（一行浮字，不接触摸）；用户按下去后自己会去点页面 */
  function pickHint(o) {
    var on = !!o.on;
    var el = document.getElementById('cdp-pick-hint');
    if (!on) {
      if (el && el.parentNode) el.parentNode.removeChild(el);
      return { ok: true, on: false };
    }
    if (!el) {
      el = document.createElement('div');
      el.id = 'cdp-pick-hint';
      el.setAttribute('data-renderer', 'agent.pick');
      el.style.cssText = 'position:fixed;left:50%;top:8px;transform:translateX(-50%);z-index:2147483002;' +
        'padding:8px 14px;border-radius:18px;background:rgba(240,136,62,.95);color:#101820;' +
        'font:700 14px/18px system-ui,sans-serif;box-shadow:0 2px 10px rgba(0,0,0,.5);' +
        'pointer-events:none;user-select:none';
      el.textContent = '点视频那一块';
      (document.body || document.documentElement).appendChild(el);
    }
    return { ok: true, on: true };
  }

  function J(o) { try { return JSON.stringify(o); } catch (e) { return '{"ok":false,"error":"序列化失败"}'; } }

  function queryCmd(o) {
    var out = [];
    try {
      if (o.selector) {
        var els = document.querySelectorAll(o.selector);
        for (var i = 0; i < els.length && i < 50; i++) out.push(describe(els[i]));
      } else if (o.text) {
        var cands = candidatesByText(o.text, null);
        for (var j = 0; j < cands.length && j < 50; j++) out.push(Object.assign(describe(cands[j].el), { score: cands[j].score }));
      } else if (o.x >= 0 && o.y >= 0) {
        out = stackAt(o.x, o.y);
      }
      return { ok: true, count: out.length, matches: out };
    } catch (e) { return { ok: false, error: e.message }; }
  }

  function diagCmd(o) {
    var el = null;
    try {
      if (o.selector) el = document.querySelector(o.selector);
      else if (o.text) { var c = candidatesByText(o.text, null); if (c.length) el = c[0].el; }
      else if (o.x >= 0 && o.y >= 0) el = document.elementFromPoint(clampX(o.x), clampY(o.y));
    } catch (e) {}
    if (!el) return { ok: false, error: '没找到元素' };
    return { ok: true, el: describe(el), diag: diagOf(el), attrs: attrsOf(el), inViewport: (function () { var b = boxOf(el); return b.x >= 0 && b.y >= 0 && b.x + b.w <= window.innerWidth && b.y + b.h <= window.innerHeight; })() };
  }

  function stackAt(x, y) {
    try {
      var list = document.elementsFromPoint ? document.elementsFromPoint(clampX(x), clampY(y)) : [document.elementFromPoint(clampX(x), clampY(y))];
      return list.filter(Boolean).slice(0, 6).map(function (e) { return describe(e); });
    } catch (e) { return []; }
  }

  function scrollIntoViewCmd(step) {
    var l = locate(step);
    if (!l.ok) return l;
    var el = queryFromDescribe(l.el);
    if (!el) return { ok: false, error: '滚不动：元素已消失' };
    try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) { try { el.scrollIntoView(); } catch (e2) {} }
    var d = describe(el);
    return { ok: true, box: d.box, el: d, strategy: l.strategy };
  }

  function setValueCmd(o) {
    try {
      var el = o.selector ? document.querySelector(o.selector) : null;
      if (!el) return { ok: false, error: '没找到输入框' };
      el.focus();
      el.value = o.text == null ? '' : String(o.text);
      fire(el, 'input', {});
      fire(el, 'change', {});
      fire(el, 'keyup', {});
      return { ok: true, value: el.value };
    } catch (e) { return { ok: false, error: e.message }; }
  }

  function highlightCmd(o) {
    var el = o.selector ? document.querySelector(o.selector) : null;
    if (!el && o.text) { var c = candidatesByText(o.text, null); if (c.length) el = c[0].el; }
    if (!el) return { ok: false, error: '没找到元素' };
    flash(el);
    return { ok: true, el: describe(el), diag: diagOf(el) };
  }

  /**
   * 开始录制。mode：
   *   'element' —— 元素录制：记 DOM 指纹（选择器/路径/属性/文字）+ 位置锚点，回放时指纹优先（元素动了也能找回来）
   *   'coord'   —— 坐标录制：**不看元素**，只记点在哪（视口坐标 + 距顶/距底/纵向比例），回放时就在那一处注入真实触摸。
   *                适合"页面上就是一块 canvas / 播放器 / 每次重绘都换节点"，或用户明说"我就要点那个位置"。
   */
  /** 是不是 App 自己的界面页（控制台 / 助手 / 播放器…）：这些页上的点击**不该**被录成步骤 ——
   *  用户是在操作控制台，不是在操作目标页面（实测：点助手悬浮面板会被录成一步，很困惑）。 */
  function isOwnUiPage() {
    try { return /\/ui\//.test(location.pathname) && /appassets\.androidplatform\.net$/.test(location.host); }
    catch (e) { return false; }
  }

  function recordStart(name, mode, nativeCoord) {
    rec.on = true; if (!rec.steps) rec.steps = [];
    rec.name = name || '录制脚本';
    rec.mode = (mode === 'coord') ? 'coord' : 'element';
    // 坐标步由**原生层**统一记（Activity 那一层能看到所有真实触摸，跨域 iframe / 原生视频表面都不漏），
    // 页内这套就不再记坐标步了 —— 否则同一次点击会记两条（实测重复）。
    rec.nativeCoord = (mode === 'coord');
    rec.startUrl = location.href; rec.t0 = Date.now();
    badge(true);
    send({ t: 'log', msg: '录制开始（' + (rec.mode === 'coord' ? '坐标录制' : '元素录制') + '）于 ' + location.href });
    return { ok: true, on: true, mode: rec.mode, url: location.href };
  }

  /** 往当前录制里插一步（等视频播完 / 等元素出现 / 条件判断…）—— 录制与手写步骤混着用 */
  function insertStep(step) {
    // 反馈 #13：不在录制时也允许往队列里插步骤（用户就是要手动加"等视频播完"这类条件）
    if (!rec.on) {
      if (!step || typeof step !== 'object') return { ok: false, error: '步骤内容为空' };
      step.inserted = true;
      send({ t: 'recStep', index: -1, step: step });
      return { ok: true, count: -1, note: '已插入队列（当前没在录制，仍会进队列）' };
    }
    if (!step || typeof step !== 'object') return { ok: false, error: '步骤内容为空' };
    step.inserted = true;
    rec.steps.push(step);
    send({ t: 'recStep', index: rec.steps.length - 1, step: step });
    return { ok: true, count: rec.steps.length };
  }

  function recordStop() {
    rec.on = false;
    badge(false);
    return { ok: true, steps: rec.steps, startUrl: rec.startUrl, ms: Date.now() - rec.t0 };
  }

  // ------------------------------------------------------------------ 装配

  var VERSION = '0.1.0';

  // ---------------- 密码库（第 4 条） ----------------
  // 找这一页的登录框：密码框 + 它前面那个文本/邮箱框
  function loginInputs() {
    function vis(el) {
      if (!el) return false;
      var r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    }
    var pws = Array.prototype.slice.call(document.querySelectorAll('input[type="password"]')).filter(vis);
    if (!pws.length) return null;
    var p = pws[0];
    var form = p.form || (p.closest ? p.closest('form') : null) || document.body;
    var us = Array.prototype.slice.call(
      form.querySelectorAll('input[type="text"],input[type="email"],input:not([type]),input[type="tel"]')
    ).filter(vis).filter(function (x) { return x !== p; });
    return { pass: p, user: us[0] || null, form: form };
  }
  function loginValues() {
    var L = loginInputs();
    if (!L) return { ok: false, error: '这一页没看到密码框' };
    return {
      ok: true,
      site: location.host || location.href,
      url: location.href,
      user: L.user ? String(L.user.value || '') : '',
      pass: String(L.pass.value || '')
    };
  }
  // 看见登录框就报给 App（**只报站点和用户名，不报密码**）；同一站点一轮只提一次
  function reportLoginSeen() {
    var L = loginInputs();
    if (!L) return;
    var key = 'cdpLoginSeen:' + (location.host || 'x');
    try {
      if (sessionStorage.getItem(key)) return;
      sessionStorage.setItem(key, '1');
    } catch (e) {}
    send('credSeen', {
      site: location.host || location.href,
      url: location.href,
      user: L.user ? String(L.user.value || '') : ''
    });
  }
  document.addEventListener('submit', reportLoginSeen, true);
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t) return;
    var txt = String((t.innerText || t.value || t.getAttribute && t.getAttribute('aria-label')) || '').slice(0, 20);
    if (/登\s*录|登\s*入|log\s*in|sign\s*in/i.test(txt)) reportLoginSeen();
  }, true);

  function fillLogin(user, pass) {
    var L = loginInputs();
    if (!L) return { ok: false, error: '这一页没看到密码框' };
    function put(el, v) {
      if (!el) return false;
      el.focus();
      el.value = v;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    }
    var a = L.user ? put(L.user, String(user || '')) : false;
    var b = put(L.pass, String(pass || ''));
    return { ok: b, userFilled: a, passFilled: b, site: location.host || location.href };
  }
  // 屏蔽不渲染：注入一段 CSS 把广告元素藏起来（请求照常发生，JS 探测不到"被拦"）
  function cosmetic(css) {
    try {
      var id = '__cdp_cosmetic';
      var el = document.getElementById(id);
      if (!css) { if (el) el.remove(); return { ok: true, count: 0 }; }
      if (!el) {
        el = document.createElement('style');
        el.id = id;
        (document.head || document.documentElement).appendChild(el);
      }
      el.textContent = css;
      // 数一下当前页面被藏起来的元素（给验收用）
      var hidden = 0;
      try {
        var sels = String(css).split(/[\n;]/).map(function (x) { return x.split('{')[0].trim(); }).filter(Boolean);
        sels.forEach(function (sel) {
          try { hidden += document.querySelectorAll(sel).length; } catch (e) {}
        });
      } catch (e) {}
      return { ok: true, rules: sels.length, hidden: hidden };
    } catch (e) {
      return { ok: false, error: String(e) };
    }
  }
  window.__CDP = {
    cosmetic: cosmetic,
    fillLogin: fillLogin,
    loginValues: loginValues,
    loginInputs: loginInputs,
    reportLoginSeen: reportLoginSeen,
    __v: VERSION,
    cmd: cmd,
    metrics: function () { return cmd({ op: 'metrics' }); },
    registerScripts: registerScripts,
    _gm: { match: matchesAny, run: runAt, api: gmApi, log: log, active: stillActive },
    _meta: META,
    _mirror: recMirror,
    get stats() { return stats; },
    get steps() { return rec.steps; }
  };

  document.addEventListener('pointerdown', onRecPointerDown, true);

  function boot() {
    send({ t: 'ready', url: location.href, title: document.title, vw: window.innerWidth, vh: window.innerHeight, ver: VERSION });
    try { if (window.__CDP_PENDING_SCRIPTS) registerScripts(window.__CDP_PENDING_SCRIPTS); } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
