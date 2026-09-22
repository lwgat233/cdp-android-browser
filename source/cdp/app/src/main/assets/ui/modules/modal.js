/**
 * modules/modal.js —— 小窗渲染器（**唯一**造小窗的地方）
 *
 * 用户定的形态（就这一种，别再拆出第二种）：
 *   · 一行信息 = 键（小字，在上）+ 值（**文本框**，在下，每对之间一条横线）；
 *   · 值可能是**可改的文本框**，也可能是**只读的文本框**（长得一样，只是改不动）；
 *   · 底部：〔恢复原始值〕〔确定〕——「恢复原始值」把值退回刚打开时的样子；
 *   · 抬头右侧一个「×」＝不保存退出（这就是"取消"，不再另加一个"关闭"文字键）。
 *
 * 按键规矩（用户点过两次：不要没功能的按键）：
 *   · **不自动补任何按键**（以前自动补的「关掉」和抬头「×」重复 → 已删）；
 *   · 只有"点了真会做一件事"的键才摆上来；调用方没给就不摆（例如只读信息窗没有「确定」）；
 *   · 同一个功能不摆两个键（"上移/下移"只留在行上的 ▲▼，小窗里不再来一份）；
 *   · 每个键都打 `data-fn`（ok / reset / close / extra），验收脚本据此断言"每个可见键都有落点"。
 *
 * 接口：
 *   Modal.open({
 *     title,                                    标题
 *     text,                                     一段正文（报错/提示用；**不要拿它当常驻说明**）
 *     fields: [{key,label,value,editable,type,options,placeholder}],
 *                                               editable:false ⇒ 只读文本框；type:'select' ⇒ 下拉；'number' ⇒ 数字框
 *     options: [{label,note,run,danger}],       把"选一个"摆成可点的行（不占按键名额）
 *     onSubmit(values),                         按「确定」时收到 {key: 值}
 *     extras: [{label,run,danger,close}],       真有功能的附加按键
 *     onClose()                                 关窗回调
 *   })
 *   Modal.show({title,text,pairs,buttons})      老签名兼容：pairs → 只读字段，buttons → extras
 *   Modal.scan() / Modal.close() / Modal.reset()
 *
 * 规矩 R1（踩过痛的那条）：**一个渲染入口 —— 每次清空 + 按数据重建，绝不追加节点。**
 * 以前动作栏是 appendChild 到父节点、从不清理，于是"同一个按键被挂 N 次"（用户看到「按键重复多次」）。
 *
 * 依赖：CDPUI.Fmt.mk
 */
(function () {
  'use strict';
  const CDPUI = (window.CDPUI = window.CDPUI || {});
  const mk = (t, c, x) => CDPUI.Fmt.mk(t, c, x);
  const MARK = 'modal.js';

  let CFG = null;        // 当前窗口配置（关掉就清空，防止回调串台）
  let INPUTS = {};       // key → 输入元素（可改的与只读的都在这里）
  let SNAP = {};         // 打开那一刻的值串（「恢复原始值」用）
  let EDITABLE = [];     // 可改字段的 key 列表

  function ensure() {
    let m = document.getElementById('cdp-modal');
    if (!m) {
      m = mk('div', '', '');
      m.id = 'cdp-modal';
      document.body.appendChild(m);
    }
    m.setAttribute('data-renderer', MARK);        // 自检用：小窗只该由这个模块造
    m.style.cssText = 'position:fixed;left:12px;right:12px;top:60px;bottom:60px;z-index:99;background:#0E1620;' +
      'border:1px solid #23486B;border-radius:12px;padding:12px;color:#E6EDF3;display:flex;flex-direction:column';
    return m;
  }

  function close() {
    const m = document.getElementById('cdp-modal');
    if (m) m.style.display = 'none';
    const cb = CFG && CFG.onClose;
    CFG = null; INPUTS = {}; SNAP = {}; EDITABLE = [];
    if (cb) { try { cb(); } catch (e) {} }
  }

  /** 「恢复原始值」：把每个文本框退回打开时的值（只读的不动） */
  function reset() {
    EDITABLE.forEach(function (k) {
      if (INPUTS[k]) INPUTS[k].value = (SNAP[k] === undefined ? '' : SNAP[k]);
    });
    return readAll();
  }

  /** 收值：数字框给数字（空串保持空串，交给 onSubmit 的校验去报错） */
  function readAll() {
    const v = {};
    Object.keys(INPUTS).forEach(function (k) {
      const el = INPUTS[k];
      if (el.tagName === 'SELECT') { v[k] = el.value; return; }
      if (el.type === 'checkbox') { v[k] = !!el.checked; return; }
      v[k] = (el.type === 'number')
        ? (String(el.value).trim() === '' ? '' : Number(el.value))
        : el.value;
    });
    return v;
  }

  const BOX = 'width:100%;box-sizing:border-box;background:#0B1118;color:#E6EDF3;border:1px solid #23486B;' +
    'border-radius:8px;padding:8px;font-size:15px';
  const BOX_RO = BOX + ';opacity:.72;border-style:dashed';

  /** 一个字段 = 一个 .kv（键小字在上 / 值文本框在下） */
  function fieldRow(f) {
    const one = mk('div', 'kv');
    one.setAttribute('data-key', String(f.key || ''));
    one.appendChild(mk('div', 'kv-k', String(f.label == null ? f.key : f.label)));
    let el;
    const ro = (f.editable === false) || f.type === 'readonly';
    if (!ro && f.type === 'check') {
      // 勾选框（用户要的「是否删除本地」就是它）：小字标签在上、方框在下，跟其它字段一个样子
      el = mk('input', 'kv-v kv-check');
      el.type = 'checkbox';
      el.checked = !!f.value;
      el.style.cssText = 'width:26px;height:26px;accent-color:#E4E4E4';
      INPUTS[f.key] = el;
      SNAP[f.key] = String(!!f.value);
      if (!ro) EDITABLE.push(f.key);
      one.appendChild(el);
      return one;
    }
    if (!ro && f.type === 'select') {
      el = mk('select', 'kv-v');
      (f.options || []).forEach(function (o) {
        const op = document.createElement('option');
        op.value = o[0]; op.textContent = o[1];
        el.appendChild(op);
      });
      el.value = String(f.value == null ? '' : f.value);
    } else {
      el = mk('input', 'kv-v');
      el.type = (!ro && f.type === 'number') ? 'number' : 'text';
      el.value = (f.value == null ? '' : String(f.value));
      if (ro) { el.readOnly = true; el.tabIndex = -1; }
      else if (f.placeholder) el.placeholder = f.placeholder;
      if (f.suffix) el.setAttribute('data-suffix', String(f.suffix));
    }
    el.id = 'fld-' + f.key;
    el.setAttribute('data-field', String(f.key || ''));
    el.setAttribute('data-readonly', ro ? '1' : '0');
    el.style.cssText = ro ? BOX_RO : BOX;
    INPUTS[f.key] = el;
    SNAP[f.key] = String(f.value == null ? '' : f.value);
    if (!ro) EDITABLE.push(f.key);
    one.appendChild(el);
    return one;
  }

  /** 可点的行（"选一个"用它，不占按键名额） */
  function optionRow(o) {
    const row = mk('div', 'row-item' + (o.danger ? ' danger' : ''));
    row.setAttribute('data-kind', 'option');
    row.setAttribute('data-option', String(o.label || ''));
    row.appendChild(mk('span', 'nm2', String(o.label || '')));
    if (o.note) row.appendChild(mk('span', 'u', String(o.note)));
    row.addEventListener('click', function (e) {
      if (e && e.stopPropagation) e.stopPropagation();
      if (o.run) o.run();
    });
    return row;
  }

  function btns(labels) { return labels; }

  function open(cfg) {
    const c = cfg || {};
    const m = ensure();
    m.innerHTML = '';                                   // ← 清空重建（R1）
    m.style.display = 'flex';
    CFG = c; INPUTS = {}; SNAP = {}; EDITABLE = [];

    // ---- 抬头：标题 + ×（不保存退出）
    const head = mk('div', 'sheet-head');
    head.style.cssText = 'display:flex;justify-content:space-between;align-items:center;margin-bottom:8px';
    head.appendChild(mk('b', '', c.title || ''));
    const x = mk('button', '', '✕');
    x.id = 'cdp-modal-x';
    x.setAttribute('data-fn', 'close');
    x.setAttribute('aria-label', '关闭');
    x.addEventListener('click', function () { close(); });
    head.appendChild(x);
    m.appendChild(head);

    // ---- 正文：正文文字 + 字段（键值对文本框）+ 可点行
    const body = mk('div', 'sheet-body');
    body.id = 'cdp-modal-b';
    body.style.cssText = 'flex:1;overflow:auto;font-size:13px';
    if (c.text) {
      const t = mk('div', '', String(c.text));
      t.id = 'cdp-modal-text';
      t.style.cssText = 'white-space:pre-wrap';
      body.appendChild(t);
    }
    const fields = c.fields || [];
    if (fields.length) {
      const box = mk('div', 'kv-list');
      fields.forEach(function (f) { box.appendChild(fieldRow(f)); });
      body.appendChild(box);
    }
    const others = c.rows || (c.items ? c.items.map(function (r) {
      return { label: r.name, note: String(r.value == null ? '' : r.value) };
    }) : []);
    if (others.length) {
      const host = mk('div', 'listbox');
      others.forEach(function (r) { host.appendChild(optionRow({ label: r.name, note: r.value })); });
      body.appendChild(host);
    }
    if (c.options && c.options.length) {
      const host2 = mk('div', 'listbox');
      c.options.forEach(function (o) { host2.appendChild(optionRow(o)); });
      body.appendChild(host2);
    }
    m.appendChild(body);

    // ---- 动作栏：真有功能的键才摆（extras → 恢复原始值 → 确定）
    const bar = mk('div', 'sheet-actions');
    (c.extras || c.buttons || c.actions || []).forEach(function (a) {
      const label = a.label || a.t || '';
      if (!label) return;
      // 如果动作给了**真按键元素**（list.js 把行里那个按键搬过来），就直接摆它：
      // 它的监听是原装的，点它就是一次正常的 DOM 点击 —— 不再依赖"点一个已脱离 DOM 的节点"那套代理，
      // 那种代理在真机/真手指下会静默失效（用户报的"小窗里的按键都没用"就是这么来的）。
      const btn = a.el && a.el.tagName === 'BUTTON' ? a.el : mk('button', a.danger ? 'danger' : '', label);
      btn.setAttribute('data-action', label);
      btn.setAttribute('data-fn', 'extra');
      btn.addEventListener('click', function (ev) {
        if (ev && ev.stopPropagation) ev.stopPropagation();
        let r = null;
        // **踩过的大坑（2026-09-20）**：list.js 传进来的动作是 `{t, danger, fn}`，
        // 而这里以前只认 `{label, run}` / `{op,args}` —— 于是**所有信息行小窗里的动作键都是死的**
        // （下载 / 播放 / 投屏 / 打开 / 删除 / 导出…点了只是把窗关掉）。用户报的"小窗里的按键都没用"就是这个。
        // 现在三种写法都认：run（新）/ fn（list.js 老写法）/ op（直接给 op 名）。
        if (a.run) { r = a.run(ev); }
        else if (a.fn) { r = a.fn(ev); }
        else if (a.op && window.CDPT) {
          r = window.CDPT.call(a.op, a.args || {}).then(function (res) {
            if (a.reportTo) {
              const el = document.getElementById(a.reportTo);
              if (el) el.textContent = (res && (res.msg || res.error)) ? (res.msg || res.error) : JSON.stringify(res).slice(0, 160);
            }
            return res;
          });
        }
        if (a.close !== false) close();
        return r;
      });
      bar.appendChild(btn);
    });
    if (EDITABLE.length) {
      const rs = mk('button', '', '恢复原始值');
      rs.id = 'cdp-modal-reset';
      rs.setAttribute('data-fn', 'reset');
      rs.addEventListener('click', function (ev) {
        if (ev && ev.stopPropagation) ev.stopPropagation();
        reset();
      });
      bar.appendChild(rs);
    }
    if (c.onSubmit || EDITABLE.length || c.text) {
      const ok = mk('button', 'primary', c.okLabel || '确定');   // 用户要求：这种确认小窗的键就叫「是 / 否」
      ok.id = 'cdp-modal-ok';
      ok.setAttribute('data-fn', 'ok');
      ok.addEventListener('click', function (ev) {
        if (ev && ev.stopPropagation) ev.stopPropagation();
        const v = readAll();
        const sub = c.onSubmit;
        close();
        if (sub) sub(v);
      });
      bar.appendChild(ok);
      // 用户要求（2026-09-21）：这种确认小窗下面要「是 / 否」两个键（否＝什么都不做就退出）
      if (c.cancelLabel) {
        const no = mk('button', '', c.cancelLabel);
        no.id = 'cdp-modal-no';
        no.setAttribute('data-fn', 'cancel');
        no.addEventListener('click', function (ev) {
          if (ev && ev.stopPropagation) ev.stopPropagation();
          close();
        });
        bar.appendChild(no);
      }
    }
    if (bar.children.length) m.appendChild(bar);
    return m;
  }

  /** 老签名兼容：show({title,text,pairs,buttons}) 或 show(title,text,rows,actions,pairs) */
  function show(a, b, c2, d, e) {
    let c;
    if (a && typeof a === 'object') c = a;
    else c = { title: a, text: b, rows: c2, buttons: d, pairs: e };
    const fields = (c.fields || []).slice();
    (c.pairs || []).forEach(function (p) {
      if (!p) return;
      fields.push({ key: 'p' + fields.length, label: String(p[0] == null ? '' : p[0]),
                    value: p[1] == null ? '' : p[1], editable: false });
    });
    return open({
      title: c.title, text: c.text, fields: fields, rows: c.rows || null,
      options: c.options || null, extras: c.buttons || c.actions || null,
      onSubmit: c.onSubmit || null, onClose: c.onClose || null, okLabel: c.okLabel || null, cancelLabel: c.cancelLabel || null, okLabel: c.okLabel || null
    });
  }

  /** 验收入口：这个窗口摆了什么、每个键的落点是什么、有没有重复文案 */
  function scan() {
    const m = document.getElementById('cdp-modal');
    if (!m) return { open: false, bars: 0, buttons: [], dupes: [], renderer: '' };
    const list = Array.prototype.slice.call(m.querySelectorAll('.sheet-actions button, .sheet-head button'));
    const labels = list.map(function (b) { return String(b.textContent || '').trim(); });
    const seen = {}, dupes = [];
    labels.forEach(function (l) { if (seen[l]) dupes.push(l); seen[l] = 1; });
    const els = Array.prototype.slice.call(m.querySelectorAll('[data-field]'));
    return {
      open: m.style.display !== 'none',
      bars: m.querySelectorAll('.sheet-actions').length,
      buttons: labels,
      fns: list.map(function (b) { return b.getAttribute('data-fn') || ''; }),
      dupes: dupes,
      renderer: m.getAttribute('data-renderer') || '',
      fields: els.length,
      readonly: els.filter(function (e) { return e.getAttribute('data-readonly') === '1'; }).length,
      editable: els.filter(function (e) { return e.getAttribute('data-readonly') === '0'; }).length,
      textboxes: els.filter(function (e) { return e.tagName === 'INPUT' || e.tagName === 'SELECT'; }).length,
      options: m.querySelectorAll('[data-option]').length,
      values: els.reduce(function (o, e) { o[e.getAttribute('data-field')] = e.value; return o; }, {})
    };
  }

  CDPUI.Modal = { open, show, close, reset, scan };
  CDPUI.__mods = (CDPUI.__mods || []).concat([{
    name: 'modal', file: 'modules/modal.js', version: '2.0',
    api: ['open', 'show', 'close', 'reset', 'scan'], dom: true
  }]);
})();
