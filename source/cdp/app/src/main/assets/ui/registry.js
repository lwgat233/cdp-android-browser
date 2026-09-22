/*
 * registry.js —— 功能登记表（唯一数据源）。
 *
 * 用户要求的那套做法（见 skill: feature-registry-ui-architecture）：
 *   ① 功能先在**后端做全**、再列成**扁平的二维行表**；
 *   ② 行表再**归类**成若干集合（一个功能只属于一个集合）；
 *   ③ 集合之间要交叉就放**跳转入口**，不重复挂载控件；
 *   ④ 每个功能对应一个**载体**（栏目 / 折叠区块 / 列表行+点开的小窗口 / 一行状态文字）。
 *
 * 这份表是机器可读的形态：
 *   - 抽屉（哪 7 个集合、每个集合有哪些栏目）由它生成，不再手写一排按钮；
 *   - 板块（data-board）由它决定挂到哪个栏目、第几位；
 *   - 启动时自检（重名 / 没归属 / 栏目不存在 / 有卡片没登记），结果给「介绍 ▸ 功能登记表」看。
 *
 * 一条记录 = 一个功能：
 *   id 稳定标识 · name 人看的名字 · coll 归类集合 · tab 界面栏目（载体所在栏目）
 *   board HTML 里的板块名（data-board，可为空=纯状态文字） · order 栏目内顺序
 *   carrier 载体形态 · entry 入口 · testid 自动化定位用 · status 实现状态
 */
(function () {
  'use strict';

  // 归类集合（7 个，名字用用户的语言；一个功能只进一个集合）
  var COLLECTIONS = [
    { key: 'browse', name: '浏览', note: '看网页这件事本身：页面 / 窗口 / 页面工具' },
    { key: 'auto',   name: '自动化', note: '让它自己动：录制回放 / 脚本 / 插件 / 终端与 ffmpeg' },
    { key: 'privacy', name: '隐私与拦截', note: 'Cookie / 密码库 / 拦截规则与名单 / 安全与隐身' },
    { key: 'media',  name: '网络与媒体', note: '请求时间线 / 网络工具 / 嗅探 / 下载' },
    { key: 'records', name: '记录与归档', note: '历史 / 书签 / 一键导出导入' },
    { key: 'api',    name: '接口与隔离', note: 'HTTP 控制口 / 外部 CDP / 给 AI 的接口 / 配置空间' },
    { key: 'system', name: '设置与系统', note: '浏览模式 / 搜索 / 代理 / 省电与后台 / 日志 / 介绍' }
  ];

  // 栏目（载体的容器）：每个栏目只属于一个集合
  var TABS = [
    { key: 'page',      coll: 'browse',  name: '页面与元素' },
    { key: 'tools',     coll: 'browse',  name: '页面工具' },

    { key: 'rec',       coll: 'auto',    name: '录制与回放' },
    { key: 'scripts',   coll: 'auto',    name: '脚本' },
    { key: 'plugins',   coll: 'auto',    name: '插件' },
    { key: 'term',      coll: 'auto',    name: '终端与 ffmpeg' },

    { key: 'cookies',   coll: 'privacy', name: 'Cookie' },
    { key: 'vault',     coll: 'privacy', name: '密码库' },
    { key: 'block',     coll: 'privacy', name: '拦截与名单' },
    { key: 'security',  coll: 'privacy', name: '安全与隐身' },

    { key: 'net',       coll: 'media',   name: '网络' },
    { key: 'sniff',     coll: 'media',   name: '资源嗅探' },
    { key: 'downloads', coll: 'media',   name: '下载' },

    { key: 'history',   coll: 'records', name: '历史' },
    { key: 'bookmarks', coll: 'records', name: '书签' },
    { key: 'bundle',    coll: 'records', name: '一键导出' },

    { key: 'api',       coll: 'api',     name: '控制口' },
    { key: 'ai',        coll: 'api',     name: '给 AI / 别的软件' },
    { key: 'space',     coll: 'api',     name: '配置空间' },

    { key: 'settings',  coll: 'system',  name: '设置' },
    { key: 'power',     coll: 'system',  name: '省电与后台' },
    { key: 'log',       coll: 'system',  name: '日志' },
    { key: 'intro',     coll: 'system',  name: '介绍与登记表' }
  ];

  // 扁平行表（先做全、再归类；这里的顺序就是界面里的顺序）
  var FEATURES = [
    // ---------------- 浏览 ----------------
    { id: 'page.status',  name: '当前页面状态', coll: 'browse', tab: 'page', board: 'page-status', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 浏览 ▸ 页面与元素', testid: 'page-status', status: '已实现' },
    { id: 'page.query',   name: '找元素（选择器 / 文字）', coll: 'browse', tab: 'page', board: 'page-query', order: 20, carrier: '折叠区块', entry: '同上', testid: 'page-query', status: '已实现' },
    { id: 'page.clickxy', name: '按坐标点 / 距底部点（真实触摸）', coll: 'browse', tab: 'page', board: 'page-click-xy', order: 30, carrier: '折叠区块', entry: '同上', testid: 'page-click-xy', status: '已实现' },
    { id: 'page.video',   name: '视频状态 / 条件检查', coll: 'browse', tab: 'page', board: 'page-video-cond', order: 40, carrier: '折叠区块', entry: '同上', testid: 'page-video-cond', status: '已实现' },
    { id: 'tools.translate', name: '翻译（端点可配；没配就说没配）', coll: 'browse', tab: 'tools', board: 'tools-translate', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 浏览 ▸ 页面工具', testid: 'tools-translate', status: '已实现' },

    // ---------------- 自动化 ----------------
    { id: 'rec.dots', name: '小点（坐标步骤的落点·画在页面里）', coll: 'auto', tab: 'rec', board: 'rec-dots', order: 15, carrier: '折叠区块', entry: '抽屉 ▸ 自动化 ▸ 录制与回放', testid: 'rec-dots', status: '已实现（本轮新增）' },
    { id: 'rec.record',   name: '监听（录制）', coll: 'auto', tab: 'rec', board: 'rec-1', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 自动化 ▸ 录制与回放', testid: 'rec-1', status: '已实现' },
    { id: 'rec.insert',   name: '添加步骤（等待 / 播放 / 点击 / 跳转 / 输入）', coll: 'auto', tab: 'rec', board: 'rec-1', order: 20, carrier: '列表底部加号', entry: '抽屉 ▸ 录制与回放 ▸ 监听与录制（步骤列表最下面的「＋ 添加步骤」）', testid: 'r-add', status: '已实现' },
    { id: 'rec.import',   name: '导入录制脚本（粘贴 JSON）', coll: 'auto', tab: 'scripts', board: 'rec-import', order: 5, carrier: '折叠区块', entry: '抽屉 ▸ 录制与回放 ▸ 脚本', testid: 'rec-import', status: '已实现（挪到「脚本」栏）' },
    { id: 'rec.list',     name: '录制脚本列表 + 回放（N5 合并成一块：选脚本 / 回放 / 跑当前队列 / 刷新 / 清空；点行载入或删除）', coll: 'auto', tab: 'rec', board: 'rec-list', order: 35, carrier: '列表行+小窗', entry: '抽屉 ▸ 自动化 ▸ 录制与回放（步骤框下面）', testid: 'rec-list', status: '已实现（N5 重排）' },

    { id: 'scripts.list', name: '用户脚本列表（内置 / 粘贴的；录制脚本已搬去录制板块）', coll: 'auto', tab: 'scripts', board: 'scripts-list', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 自动化 ▸ 脚本', testid: 'scripts-list', status: '已实现' },
    { id: 'scripts.add',  name: '粘贴 / 从网址导入脚本', coll: 'auto', tab: 'scripts', board: 'scripts-paste', order: 20, carrier: '折叠区块', entry: '同上', testid: 'scripts-paste', status: '已实现' },

    { id: 'plugin.read',     name: '阅读与停留时间（App 自己计时，不注入页面；排序 / 搜索 / 删除）', coll: 'auto', tab: 'plugins', board: 'read-list', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 自动化 ▸ 插件', testid: 'read-list', status: '已实现' },
    { id: 'stats.read',  name: '统计·阅读时长（前台/后台双色柱 + 按站点饼图 + 连续使用）', coll: 'auto', tab: 'plugins', board: 'read-chart', order: 20, carrier: '折叠区块', entry: '抽屉 ▸ 自动化 ▸ 插件 ▸ 统计', testid: 'ch-bar', status: '已实现' },
    { id: 'stats.net',   name: '统计·网络流量（上/下行双色柱 + 按站点饼图）', coll: 'auto', tab: 'plugins', board: 'read-chart', order: 21, carrier: '折叠区块', entry: '同上（数据源选「网络流量」；网络栏目有跳转入口）', testid: 'ch-pie', status: '已实现' },


    { id: 'term.main',   name: '伪终端 + 内置 ffmpeg', coll: 'auto', tab: 'term', board: 'term-main', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 自动化 ▸ 终端与 ffmpeg', testid: 'term-main', status: '已实现' },

    // ---------------- 隐私与拦截 ----------------
    { id: 'cookie.list', name: 'Cookie（一个列表：查站点 / 全局 / 删单条 / 清空）', coll: 'privacy', tab: 'cookies', board: 'cookie-main', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 隐私与拦截 ▸ Cookie', testid: 'cookie-main', status: '已实现（本轮把原来三块合并成一块）' },

    { id: 'vault.main',  name: '密码库（锁屏验证后才看/改）', coll: 'privacy', tab: 'vault', board: 'vault-main', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 隐私与拦截 ▸ 密码库', testid: 'vault-main', status: '已实现（本轮从「录制」栏移到隐私）' },

    { id: 'block.rules', name: '拦截与名单（广告规则 / 隐藏选择器 / 站点警告名单）', coll: 'privacy', tab: 'block', board: 'block-rules', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 隐私与拦截 ▸ 拦截与名单', testid: 'block-rules', status: '已实现（本轮把广告拦截与屏蔽名单合成一份规则库）' },

    { id: 'security.ctl', name: '控制口安全（绑定范围 / 令牌 / 敏感接口开关）', coll: 'privacy', tab: 'security', board: 'security-http', order: 5, carrier: '折叠区块', entry: '抽屉 ▸ 隐私与拦截 ▸ 安全与隐身', testid: 'security-http', status: '已实现（本轮新增）' },
    { id: 'security.page',     name: '网站安全（协议 / 证书 / 混合内容）', coll: 'privacy', tab: 'security', board: 'security-page', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 隐私与拦截 ▸ 安全与隐身', testid: 'security-page', status: '已实现' },
    { id: 'security.incognito', name: '隐身模式（无痕）', coll: 'privacy', tab: 'security', board: 'security-incognito', order: 20, carrier: '状态文字', entry: '同上', testid: 'security-incognito', status: '已实现（本轮从「设置」归到安全与隐身）' },

    // ---------------- 网络与媒体 ----------------
    { id: 'net.timeline', name: '请求时间线 + 上下行统计', coll: 'media', tab: 'net', board: 'net-timeline', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 网络与媒体 ▸ 网络', testid: 'net-timeline', status: '已实现' },
    { id: 'net.tools',    name: '网络工具（DNS / 证书 / 通路 / 测速 / 本机 IP）', coll: 'media', tab: 'net', board: 'net-tools', order: 20, carrier: '折叠区块', entry: '同上', testid: 'net-tools', status: '已实现' },

    { id: 'sniff.list', name: '嗅探到的媒体资源', coll: 'media', tab: 'sniff', board: 'sniff-list', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 网络与媒体 ▸ 资源嗅探', testid: 'sniff-list', status: '已实现' },
    { id: 'sniff.add',  name: '手动补地址 / 下载 m3u8 / 播放', coll: 'media', tab: 'sniff', board: 'sniff-add', order: 20, carrier: '折叠区块', entry: '同上', testid: 'sniff-add', status: '已实现' },
    { id: 'sniff.note', name: '嗅探说明（边界）', coll: 'media', tab: 'sniff', board: 'sniff-note', order: 30, carrier: '状态文字', entry: '同上', testid: 'sniff-note', status: '已实现' },

    { id: 'download.list', name: '下载（App 内自己下 + 记录）', coll: 'media', tab: 'downloads', board: 'downloads-list', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 网络与媒体 ▸ 下载', testid: 'downloads-list', status: '已实现' },
    { id: 'download.mode', name: '下载方式（应用内 / 系统下载器）', coll: 'media', tab: 'downloads', board: 'downloads-mode', order: 20, carrier: '折叠区块', entry: '同上', testid: 'downloads-mode', status: '已实现（本轮从「设置」归到下载）' },

    // ---------------- 记录与归档 ----------------
    { id: 'history.list',  name: '历史（无限下拉 / 按域名按时间删 / 导出）', coll: 'records', tab: 'history', board: 'history-list', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 记录与归档 ▸ 历史', testid: 'history-list', status: '已实现' },
    { id: 'bookmark.tree', name: '书签（树形文件夹）', coll: 'records', tab: 'bookmarks', board: 'bookmark-tree', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 记录与归档 ▸ 书签', testid: 'bookmark-tree', status: '已实现' },
    { id: 'bundle.export', name: '一键导出 / 导入（zip）', coll: 'records', tab: 'bundle', board: 'bundle-export', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 记录与归档 ▸ 一键导出', testid: 'bundle-export', status: '已实现' },

    // ---------------- 接口与隔离 ----------------
    { id: 'api.http',      name: '本机 / 局域网 HTTP 控制口开关', coll: 'api', tab: 'api', board: 'api-http', order: 10, carrier: '状态文字', entry: '抽屉 ▸ 接口与隔离 ▸ 控制口', testid: 'api-http', status: '已实现' },
    { id: 'api.cdp',       name: '外部 CDP 调试端口提示', coll: 'api', tab: 'api', board: 'api-cdp', order: 20, carrier: '状态文字', entry: '同上', testid: 'api-cdp', status: '已实现（debug 构件）' },
    { id: 'api.endpoints', name: '接口清单', coll: 'api', tab: 'api', board: 'api-endpoints', order: 30, carrier: '折叠区块', entry: '同上', testid: 'api-endpoints', status: '已实现' },
    { id: 'api.catalog',   name: '接口目录与二级接口（batch）', coll: 'api', tab: 'api', board: 'api-catalog', order: 40, carrier: '折叠区块', entry: '同上', testid: 'api-catalog', status: '已实现' },

    { id: 'ai.ports',  name: '给别的软件 / AI 的端口与示例（取正文默认剔隐私字段）', coll: 'api', tab: 'ai', board: 'api-ai', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 接口与隔离 ▸ 给 AI / 别的软件', testid: 'api-ai', status: '已实现' },

    { id: 'space.iso', name: '配置空间（历史/书签/脚本/设置 各一套）', coll: 'api', tab: 'space', board: 'api-space', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 接口与隔离 ▸ 配置空间', testid: 'api-space', status: '已实现' },

    // ---------------- 设置与系统 ----------------
    { id: 'set.mode',   name: '浏览模式（手机 / 电脑 / 自定义 UA）', coll: 'system', tab: 'settings', board: 'settings-mode', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 设置与系统 ▸ 设置', testid: 'settings-mode', status: '已实现' },
    { id: 'set.search', name: '搜索引擎', coll: 'system', tab: 'settings', board: 'settings-search', order: 20, carrier: '折叠区块', entry: '同上', testid: 'settings-search', status: '已实现' },
    { id: 'set.proxy',  name: '代理（HTTP / HTTPS / SOCKS5 + 白名单）', coll: 'system', tab: 'settings', board: 'settings-proxy', order: 30, carrier: '折叠区块', entry: '同上', testid: 'settings-proxy', status: '已实现' },

    { id: 'sys.power',     name: '省电（逐项开关 + 一键省电）', coll: 'system', tab: 'power', board: 'power-list', order: 10, carrier: '列表行+小窗', entry: '抽屉 ▸ 设置与系统 ▸ 省电与后台', testid: 'power-list', status: '已实现' },

    { id: 'sys.bgplay', name: '后台播放（熄屏 / 切后台继续放）', coll: 'system', tab: 'power', board: 'bgplay', order: 20, carrier: '折叠区块', entry: '抽屉 ▸ 设置与系统 ▸ 省电与后台', testid: 'bgplay', status: '已实现（本轮新增）' },
    { id: 'sys.log',       name: 'App 日志与页面事件', coll: 'system', tab: 'log', board: 'log-tail', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 设置与系统 ▸ 日志', testid: 'log-tail', status: '已实现' },

    { id: 'sys.about',     name: '介绍（项目 / 版本）', coll: 'system', tab: 'intro', board: 'intro-about', order: 10, carrier: '折叠区块', entry: '抽屉 ▸ 设置与系统 ▸ 介绍与登记表', testid: 'intro-about', status: '已实现' },
    { id: 'sys.howto',     name: '怎么用（三句话）', coll: 'system', tab: 'intro', board: 'intro-howto', order: 20, carrier: '状态文字', entry: '同上', testid: 'intro-howto', status: '已实现' },
    { id: 'sys.registry',  name: '功能登记表（自检：归属 / 重名 / 没挂上）', coll: 'system', tab: 'intro', board: 'sys-registry', order: 30, carrier: '列表行+小窗', entry: '同上', testid: 'sys-registry', status: '已实现（本轮新增）' }
  ];

  function collOf(tabKey) {
    for (var i = 0; i < TABS.length; i++) if (TABS[i].key === tabKey) return TABS[i].coll;
    return '';
  }
  function collName(key) {
    for (var i = 0; i < COLLECTIONS.length; i++) if (COLLECTIONS[i].key === key) return COLLECTIONS[i].name;
    return key;
  }
  function tabName(key) {
    for (var i = 0; i < TABS.length; i++) if (TABS[i].key === key) return TABS[i].name;
    return key;
  }
  function tabsOf(coll) { return TABS.filter(function (t) { return t.coll === coll; }); }
  function featuresOf(tab) {
    return FEATURES.filter(function (f) { return f.tab === tab; })
      .sort(function (a, b) { return (a.order || 0) - (b.order || 0); });
  }
  function featuresOfColl(coll) {
    return FEATURES.filter(function (f) { return f.coll === coll; });
  }
  /** 一行文字：集合 ▸ 栏目（给抽屉顶部和状态行用） */
  function breadcrumb(tab) {
    var c = collOf(tab);
    return (c ? collName(c) + ' › ' : '') + tabName(tab);
  }
  /**
   * 自检：把"登记表 vs 界面"的差集算出来。
   * problems 非空就是缺陷（重名 / 栏目写错 / 有卡片没登记 / 登记的卡片在 HTML 里找不到）。
   */
  function report() {
    var problems = [], seen = {}, dup = [];
    FEATURES.forEach(function (f) {
      if (seen[f.id]) dup.push(f.id);
      seen[f.id] = 1;
      if (!collOf(f.tab)) problems.push({ id: f.id, problem: '栏目不在这张表里：' + f.tab });
      else if (collOf(f.tab) !== f.coll) problems.push({ id: f.id, problem: '归类集合对不上：登记的 ' + f.coll + '，栏目的 ' + collOf(f.tab) });
    });
    if (dup.length) problems.push({ id: dup.join(','), problem: '功能 id 重复' });
    var boardSeen = {};
    FEATURES.forEach(function (f) {
      if (!f.board) return;
      // 一个板块可以承载多个功能点（比如"监听与录制"板块里既有录制、也有列表底部的添加步骤）——
      // 唯一性要求是**功能只属于一个集合**、功能 id / testid 不重复，不是"一个板块只能一个功能"。
      if (boardSeen[f.board]) boardSeen[f.board] += 1;
      else boardSeen[f.board] = 1;
      boardSeen[f.board] = 1;
      if (!document.getElementById(f.board) && !document.querySelector('[data-board="' + f.board + '"]')) {
        problems.push({ board: f.board, problem: '登记了但 HTML 里没有这个板块' });
      }
    });
    if (typeof document !== 'undefined' && document.querySelectorAll) {
      var inDom = Array.prototype.slice.call(document.querySelectorAll('[data-board]'));
      var domSeen = {};
      inDom.forEach(function (el) {
        var b = el.getAttribute('data-board');
        if (domSeen[b]) problems.push({ board: b, problem: 'HTML 里板块名重复' });
        domSeen[b] = 1;
        if (!boardSeen[b]) problems.push({ board: b, problem: 'HTML 里有这个板块，但登记表里没有' });
      });
    }
    // 集合/栏目本身的健全性
    COLLECTIONS.forEach(function (c) {
      var ts = tabsOf(c.key);
      if (!ts.length) problems.push({ coll: c.key, problem: '集合下没有任何栏目' });
      ts.forEach(function (t) {
        if (!featuresOf(t.key).length) problems.push({ tab: t.key, problem: '栏目下没有任何功能' });
      });
    });
    return {
      ok: problems.length === 0,
      collections: COLLECTIONS.length,
      tabs: TABS.length,
      features: FEATURES.length,
      boards: Object.keys(boardSeen).length,
      byColl: COLLECTIONS.map(function (c) {
        return { key: c.key, name: c.name, tabs: tabsOf(c.key).length, features: featuresOfColl(c.key).length };
      }),
      problems: problems
    };
  }

  window.CDP_REGISTRY = {
    COLLECTIONS: COLLECTIONS,
    TABS: TABS,
    FEATURES: FEATURES,
    collOf: collOf,
    collName: collName,
    tabName: tabName,
    tabsOf: tabsOf,
    featuresOf: featuresOf,
    featuresOfColl: featuresOfColl,
    breadcrumb: breadcrumb,
    report: report
  };
  window.__cdpRegistry = function () { return report(); };
})();
