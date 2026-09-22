// ==UserScript==
// @name        纯文本阅读
// @match       *://*/*
// @run-at      document-idle
// @grant       GM_addStyle
// @grant       GM_getValue
// @grant       GM_setValue
// ==/UserScript==
//
// 用户需求（清单第 19 条）："提供一个插件可以将网页提取成纯文本，并可以修改如文本颜色、
// 波浪线等等的文本功能。当然里面还是有图片的。"
// 做法：右下角一个按钮 → 打开一层覆盖层，里面是本页正文的纯文本（图片保留成 [图] 标记并**给出原图链接**），
// 提供：字号、文字颜色、底色、波浪线（标出这里原本是链接/重点）、以及"只留正文"开关。
(function () {
  'use strict';
  var ID = 'cdp-pt';
  if (document.getElementById(ID)) return;
  var cur = { size: 17, color: '#E6EDF3', bg: '#0B0F13', wave: false, onlyMain: true };

  GM_addStyle(
    '#cdp-pt-btn{position:fixed;right:8px;bottom:52px;z-index:2147483000;background:#16324B;color:#BEE1FF;' +
    'border:1px solid #23486B;border-radius:10px;padding:6px 9px;font:12px/1.5 -apple-system,sans-serif;cursor:pointer}' +
    '#cdp-pt{position:fixed;inset:0;z-index:2147483001;display:none;flex-direction:column}' +
    '#cdp-pt .bar{display:flex;gap:6px;align-items:center;flex-wrap:wrap;padding:8px;background:#101821;border-bottom:1px solid #1C2836;font:12px/1.5 -apple-system,sans-serif;color:#BEE1FF}' +
    '#cdp-pt .bar button,#cdp-pt .bar input{font:12px/1.4 -apple-system,sans-serif;border-radius:6px;border:1px solid #23486B;background:#0B0F13;color:#BEE1FF;padding:3px 7px}' +
    '#cdp-pt .body{flex:1;overflow:auto;padding:14px 12px 60px;white-space:pre-wrap;word-break:break-word}' +
    '#cdp-pt .body a{color:#6FC3FF} .cdp-pt-wave{border-bottom:2px wavy #ffb400;text-decoration:none}' +
    '#cdp-pt .body .imgs{margin:8px 0;padding:6px 8px;border-left:3px solid #23486B;color:#7A8794}'
  );

  var btn = document.createElement('div');
  btn.id = 'cdp-pt-btn';
  btn.textContent = '📄 纯文本';
  document.body.appendChild(btn);

  var root = document.createElement('div');
  root.id = ID;
  root.innerHTML =
    '<div class="bar">' +
    '<b>纯文本</b>' +
    '<button data-a="close">关闭</button>' +
    '<button data-a="smaller">A-</button><button data-a="bigger">A+</button>' +
    '<input type="color" data-a="color" value="#E6EDF3" title="文字颜色">' +
    '<input type="color" data-a="bg" value="#0B0F13" title="背景色">' +
    '<button data-a="wave">波浪线：关</button>' +
    '<button data-a="main">只留正文：开</button>' +
    '<button data-a="copy">复制全文</button>' +
    '<span data-out></span>' +
    '</div><div class="body"></div>';
  document.body.appendChild(root);
  var out = root.querySelector('.body');
  var bar = root.querySelector('.bar');

  function pick() {
    if (!cur.onlyMain) return document.body;
    var c = document.querySelectorAll('article, main, [role=main], .article, .content, #content');
    var best = null, bestLen = 0;
    for (var i = 0; i < c.length; i++) {
      var t = c[i].innerText || '';
      if (t.length > bestLen) { bestLen = t.length; best = c[i]; }
    }
    return (best && bestLen > 400) ? best : document.body;
  }

  function render() {
    out.innerHTML = '';
    var nodes = pick().childNodes;
    var frag = document.createDocumentFragment();
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (!n) continue;
      if (n.nodeType === 3) {
        frag.appendChild(document.createTextNode(n.nodeValue || ''));
      } else if (n.nodeType === 1) {
        var tag = (n.tagName || '').toLowerCase();
        if (tag === 'script' || tag === 'style' || tag === 'noscript' || tag === 'svg') continue;
        if (tag === 'img') {
          var box = document.createElement('div');
          box.className = 'imgs';
          box.textContent = '[图] ' + (n.getAttribute('alt') || '') + ' → ' + (n.currentSrc || n.src || '');
          frag.appendChild(box);
        } else if (tag === 'a') {
          var a = document.createElement('a');
          a.href = n.getAttribute('href') || '#';
          a.textContent = (n.innerText || n.textContent || '').trim();
          if (cur.wave) a.className = 'cdp-pt-wave';
          frag.appendChild(a);
        } else if (tag === 'br') {
          frag.appendChild(document.createTextNode('\n'));
        } else {
          // 其余容器：递归塞进去（保留块级换行）
          frag.appendChild(document.createTextNode('\n'));
          var sub = n.cloneNode(false);
          var txt = document.createTextNode((n.innerText || '').trim() ? (n.innerText || '') : ((n.textContent || '').trim()));
          if (txt.nodeValue) sub.appendChild(txt);
          var links = n.querySelectorAll ? n.querySelectorAll('a') : [];
          for (var k = 0; k < Math.min(links.length, 5); k++) {
            var la = document.createElement('a');
            la.href = links[k].href;
            la.textContent = ' [' + (links[k].innerText || links[k].href).trim().slice(0, 30) + ']';
            if (cur.wave) la.className = 'cdp-pt-wave';
            sub.appendChild(la);
          }
          frag.appendChild(sub);
          frag.appendChild(document.createTextNode('\n'));
        }
      }
    }
    out.appendChild(frag);
    out.querySelectorAll('a').forEach(function (a) {
      if (cur.wave) a.className = 'cdp-pt-wave';
    });
    out.style.fontSize = cur.size + 'px';
    out.style.color = cur.color;
    out.style.background = cur.bg;
    root.style.background = cur.bg;
    var chars = (out.innerText || '').length;
    bar.querySelector('[data-out]').textContent = '　' + chars + ' 字';
  }

  bar.addEventListener('click', function (e) {
    var a = e.target && e.target.getAttribute && e.target.getAttribute('data-a');
    if (!a) return;
    if (a === 'close') root.style.display = 'none';
    if (a === 'smaller') cur.size = Math.max(12, cur.size - 1), render();
    if (a === 'bigger') cur.size = Math.min(28, cur.size + 1), render();
    if (a === 'wave') { cur.wave = !cur.wave; e.target.textContent = '波浪线：' + (cur.wave ? '开' : '关'); render(); }
    if (a === 'main') { cur.onlyMain = !cur.onlyMain; e.target.textContent = '只留正文：' + (cur.onlyMain ? '开' : '关'); render(); }
    if (a === 'copy') {
      try {
        var ta = document.createElement('textarea');
        ta.value = out.innerText;
        document.body.appendChild(ta); ta.select();
        document.execCommand('copy'); ta.remove();
        bar.querySelector('[data-out]').textContent = '　已复制';
      } catch (_) {}
    }
  });
  bar.addEventListener('input', function (e) {
    var a = e.target.getAttribute && e.target.getAttribute('data-a');
    if (a === 'color') { cur.color = e.target.value; render(); }
    if (a === 'bg') { cur.bg = e.target.value; render(); }
  });
  btn.addEventListener('click', function () {
    root.style.display = 'flex';
    render();
    try {
      window.__CDP && window.__CDP.cmd({ op: 'log', msg: '[纯文本] 已提取：' + location.href.slice(0, 100) });
    } catch (_) {}
  });
})();
