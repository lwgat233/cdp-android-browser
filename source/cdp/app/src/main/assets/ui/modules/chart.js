/*
 * modules/chart.js —— 统计与绘图的**唯一渲染器**。
 *
 * 用户定的结构（2026-09-20）：
 *   "它是两个功能，但实质上其实就是收集数据，然后交由一个东西进行统计、绘画。"
 * 所以这里只管画，不认识业务：给什么数据画什么图。采集方（阅读时长 / 网络流量）把数据凑成
 * 同一形状丢进来，两处共用这一份代码 —— 各画一套必然会两边不一致。
 *
 * 形状（和后端 Stats.kt 的输出对应）：
 *   柱状图  bar(el, { bars:[{key,label,value,value2,visits,sites}], series:[{key,name,color}, …],
 *                    fmt(v), unit, annot  })
 *      · 双色**堆叠**：series[0] 在下（前台 / 下行），series[1] 在上（后台 / 上行）
 *   饼状图  pie(el, { slices:[{name,value,pct}], fmt, unit })
 *
 * 规矩（沿用界面铁规）：不写说明文字、不摆装饰；数据为空就写一句人话说明为什么空；
 * 图里能读出来的信息（时长、网站数）不另起一段文字复述。
 */
(function (root) {
  'use strict';

  var NS = 'http://www.w3.org/2000/svg';
  var PAD_L = 40, PAD_B = 16, PAD_T = 12;

  function el(tag, attrs, text) {
    var e = document.createElementNS(NS, tag);
    if (attrs) for (var k in attrs) if (attrs[k] != null) e.setAttribute(k, attrs[k]);
    if (text != null) e.textContent = text;
    return e;
  }

  function esc(s) { return String(s == null ? '' : s); }

  // ------------------------------------------------------------------ 数值格式（人看的）

  /** 毫秒 → "1 时 20 分" / "12 分" / "45 秒" */
  function fmtMs(v) {
    v = Math.max(0, Math.round(v || 0));
    var s = Math.round(v / 1000);
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    if (h > 0) return h + ' 时' + (m > 0 ? m + ' 分' : '');
    if (m > 0) return m + ' 分' + (sec > 0 && m < 10 ? sec + ' 秒' : '');
    return sec + ' 秒';
  }

  /** 短的（坐标轴用）：1.2h / 20m / 8s */
  function fmtMsShort(v) {
    var s = Math.round((v || 0) / 1000);
    if (s >= 3600) return (s / 3600).toFixed(s >= 36000 ? 0 : 1) + 'h';
    if (s >= 60) return Math.round(s / 60) + 'm';
    return s + 's';
  }

  /** 字节 → 1.2 MB */
  function fmtBytes(v) {
    v = v || 0;
    var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (i === 0 ? v : (v >= 100 ? v.toFixed(0) : v.toFixed(1))) + ' ' + u[i];
  }

  function fmtBytesShort(v) { return fmtBytes(v).replace(' ', ''); }

  var FMT = { ms: fmtMs, msShort: fmtMsShort, bytes: fmtBytes, bytesShort: fmtBytesShort, count: function (v) { return String(v || 0); } };

  // ------------------------------------------------------------------ 柱状图（双色堆叠）

  /**
   * @param host 容器元素
   * @param opt  { bars, series:[{key,name,color}], fmt, unit, annotate(bool), title }
   */
  function bar(host, opt) {
    opt = opt || {};
    var bars = opt.bars || [];
    var series = (opt.series || []).slice(0, 2);
    var fmt = opt.fmt || fmtMs;
    host.innerHTML = '';
    if (!bars.length || bars.every(function (b) { return !(b.value || 0) && !(b.value2 || 0); })) {
      var d = document.createElement('div');
      d.className = 'chart-empty';
      d.textContent = opt.empty || '还没有数据';
      host.appendChild(d);
      return;
    }
    var W = host.clientWidth || 340;
    var H = opt.height || 132;
    var max = 1;
    bars.forEach(function (b) {
      var t = 0;
      series.forEach(function (s) { t += (b[s.key] || 0); });
      if (t > max) max = t;
    });
    var plotW = W - PAD_L - 4, plotH = H - PAD_B - PAD_T;
    var slot = plotW / bars.length;
    var bw = Math.max(2, Math.min(26, slot * 0.68));
    var svg = el('svg', { width: '100%', height: H, viewBox: '0 0 ' + W + ' ' + H, class: 'chart' });

    // 三条参考线 + 刻数
    for (var g = 0; g <= 2; g++) {
      var y = PAD_T + plotH - (plotH * g / 2);
      svg.appendChild(el('line', { x1: PAD_L, y1: y, x2: W - 2, y2: y, stroke: 'rgba(255,255,255,.10)', 'stroke-width': 1 }));
      svg.appendChild(el('text', { x: PAD_L - 4, y: y + 3, 'text-anchor': 'end', class: 'chart-tick' },
        (opt.fmtShort || fmtMsShort)(max * g / 2)));
    }

    var annotate = opt.annotate !== false && bars.length <= 14;
    bars.forEach(function (b, i) {
      var x = PAD_L + slot * i + (slot - bw) / 2;
      var yBottom = PAD_T + plotH;
      var v0 = b[series[0] ? series[0].key : 'value'] || 0;
      var v1 = series[1] ? (b[series[1].key] || 0) : 0;
      var h0 = plotH * v0 / max, h1 = plotH * v1 / max;
      var rects = [];
      if (h0 > 0.4) rects.push(el('rect', { x: x, y: yBottom - h0, width: bw, height: h0, rx: 1.5, fill: (series[0] && series[0].color) || '#4a8cff' }));
      if (h1 > 0.4) rects.push(el('rect', { x: x, y: yBottom - h0 - h1, width: bw, height: h1, rx: 1.5, fill: (series[1] && series[1].color) || '#6b6b86' }));
      var g1 = el('g', null);
      rects.forEach(function (r) { g1.appendChild(r); });
      // 悬停/长按看明细：SVG 原生 title，不用自己写浮层
      var t = el('title', null, b.label + '　' + fmt(v0 + v1) +
        (b.visits ? '　' + b.visits + ' 次' : '') + (b.sites ? '　' + b.sites + ' 个网站' : ''));
      g1.appendChild(t);
      svg.appendChild(g1);
      if (annotate && (v0 + v1) > 0) {
        // 柱子上标一眼能看的量（用户要求：柱状图上标一下多长时间、多少个网站）
        svg.appendChild(el('text', {
          x: PAD_L + slot * i + slot / 2, y: yBottom - h0 - h1 - 3, 'text-anchor': 'middle', class: 'chart-annot'
        }, (opt.fmtShort || fmtMsShort)(v0 + v1) + (b.sites ? '·' + b.sites : '')));
      }
      // 横轴刻度：24 小时全标太挤，隔一个标一次
      var every = bars.length > 16 ? 3 : (bars.length > 8 ? 2 : 1);
      if (i % every === 0) {
        svg.appendChild(el('text', { x: PAD_L + slot * i + slot / 2, y: H - 3, 'text-anchor': 'middle', class: 'chart-x' }, b.label));
      }
    });
    host.appendChild(svg);

    // 图例（两种颜色的意思）——只有两种颜色必须让它们各自是什么一目了然
    if (series.length === 2) {
      var lg = document.createElement('div');
      lg.className = 'chart-legend';
      lg.innerHTML = series.map(function (s) {
        return '<span class="cl-item"><i style="background:' + s.color + '"></i>' + esc(s.name) + '</span>';
      }).join('');
      host.appendChild(lg);
    }
  }

  // ------------------------------------------------------------------ 饼状图

  /** @param opt { slices:[{name,value,pct}], fmt, unit, top } */
  function pie(host, opt) {
    opt = opt || {};
    var slices = (opt.slices || []).filter(function (s) { return (s.value || 0) > 0; });
    var fmt = opt.fmt || fmtMs;
    host.innerHTML = '';
    if (!slices.length) {
      var d = document.createElement('div');
      d.className = 'chart-empty';
      d.textContent = opt.empty || '还没有数据';
      host.appendChild(d);
      return;
    }
    var top = slices.slice(0, opt.top || 8);
    var rest = slices.slice(opt.top || 8);
    if (rest.length) {
      var other = 0;
      rest.forEach(function (s) { other += s.value; });
      var tot = slices.reduce(function (a, s) { return a + s.value; }, 0);
      top.push({ name: '其它 ' + rest.length + ' 个', value: other, pct: tot > 0 ? Math.round(other * 1000 / tot) / 10 : 0 });
    }
    var total = top.reduce(function (a, s) { return a + s.value; }, 0) || 1;
    var COLORS = ['#4a8cff', '#38c172', '#f6a623', '#e0568a', '#8f7bff', '#2bb3c0', '#c0873c', '#7a8b99', '#5f6b7a'];
    var S = 96, R = S / 2, r = R * 0.58, cx = R, cy = R;
    var wrap = document.createElement('div');
    wrap.className = 'pie-wrap';
    var svg = el('svg', { width: S, height: S, viewBox: '0 0 ' + S + ' ' + S, class: 'chart pie' });
    var start = -Math.PI / 2;
    top.forEach(function (s, i) {
      var frac = s.value / total;
      var end = start + frac * Math.PI * 2;
      var large = frac > 0.5 ? 1 : 0;
      var x1 = cx + Math.cos(start) * R, y1 = cy + Math.sin(start) * R;
      var x2 = cx + Math.cos(end) * R, y2 = cy + Math.sin(end) * R;
      var ix1 = cx + Math.cos(end) * r, iy1 = cy + Math.sin(end) * r;
      var ix2 = cx + Math.cos(start) * r, iy2 = cy + Math.sin(start) * r;
      var d = frac >= 0.999
        ? 'M ' + cx + ' ' + (cy - R) + ' a ' + R + ' ' + R + ' 0 1 1 -0.01 0 Z'
        : ['M ' + x1 + ' ' + y1, 'A ' + R + ' ' + R + ' 0 ' + large + ' 1 ' + x2 + ' ' + y2,
          'L ' + ix1 + ' ' + iy1, 'A ' + r + ' ' + r + ' 0 ' + large + ' 0 ' + ix2 + ' ' + iy2, 'Z'].join(' ');
      var p = el('path', { d: d, fill: COLORS[i % COLORS.length], stroke: 'rgba(0,0,0,.25)', 'stroke-width': 0.5 });
      p.appendChild(el('title', null, s.name + '　' + fmt(s.value) + '　' + s.pct + '%'));
      svg.appendChild(p);
      start = end;
    });
    svg.appendChild(el('text', { x: cx, y: cy - 2, 'text-anchor': 'middle', class: 'pie-center-1' }, (opt.centerLabel || '合计')));
    svg.appendChild(el('text', { x: cx, y: cy + 12, 'text-anchor': 'middle', class: 'pie-center-2' }, (opt.fmtShort || fmtMsShort)(total)));
    wrap.appendChild(svg);

    var lg = document.createElement('div');
    lg.className = 'pie-legend';
    top.forEach(function (s, i) {
      var row = document.createElement('div');
      row.className = 'pl-row';
      row.innerHTML = '<i style="background:' + COLORS[i % COLORS.length] + '"></i>' +
        '<span class="pl-name">' + esc(s.name) + '</span>' +
        '<span class="pl-val">' + esc(fmt(s.value)) + '　' + s.pct + '%</span>';
      lg.appendChild(row);
    });
    wrap.appendChild(lg);
    host.appendChild(wrap);
  }

  root.Chart = { bar: bar, pie: pie, fmt: FMT };
})(window);
