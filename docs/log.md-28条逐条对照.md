# /vol1/1000/mydoc/log.md 28 条逐条对照（每条都有代码/接口证据）

> 做法：不看印象，只看**代码里到底有没有**。证据 = op 名 / HTTP 路由 / 界面板块 id / 原生文件 / 脚本。
> 判定三档：**已做**（有实现且能读到证据）｜**部分**（主体在，缺口子）｜**没做**。
> 维护：每次改完对应条目，回来更新这一行（状态 + 证据 + 缺什么）。

## 汇总

| # | 条目（log.md 原话） | 状态 | 证据 | 缺什么 |
|---|---|---|---|---|
| 1 | cookies 查看登录网站 / httponly / 每个 cookie 的标记信息（小窗） | 已做 | `cookie.all` `cookie.domain` `cookie.delete`、板块 `cookie-main`、CookieDb.kt | — |
| 2 | 历史无限下拉 + 时间 + 导出 + 从前/从后删 + 按域名删 | 已做 | `nav.history` `history.delete` `history.clear`、板块 `history-list`、导出在 `bundle-export` | — |
| 3 | 广告拦截（规则可导入）+ 结合录制屏蔽 + 站点黑名单 | 部分 | `adblock.*`、板块 `block-rules`（隐藏选择器/站点名单/拦截统计） | **「结合录制把某些广告屏蔽掉」没做**（现在是规则式拦截） |
| 4 | 密码存储（锁屏才能看/改）+ 随机密码 + 自定义字符集 + 登录提示保存 | 已做 | `vault.*`（state/unlock/lock/list/get/save/gen/capture）、PassVault.kt、板块 `vault-main` | — |
| 5 | 分享功能；嗅探到视频资源要有「是否发现对应资源」的按键 | 部分 | `share.current`（系统分享/剪贴板）、`#p-share` | **嗅探资源的分享键没做**（分享只分享当前页） |
| 6 | 下载栏下拉 + 删历史问是否删文件 + 下到 download 目录 + aria2 等下载方式 | **已做** | 板块 `downloads-list`/`downloads-mode`、`dl.*` `deleteDownload(keepFile)`、DownloadDir；**aria2**：Aria2.kt（JSON-RPC addUri/tellStatus/getVersion）+ 设置里 RPC/secret/目录 + `/api/dl/aria2*`；验收 `verify_aria2.py` **16/16**（含假 aria2 两边对账与反例） | — |
| 7 | 内置 ffmpeg + 伪终端界面 | 已做 | Ffmpeg.kt、板块 `term-main`、`term.*` | — |
| 8 | 用 ffmpeg 强制下载视频（在资源嗅探栏目） | 已做 | `sniff` + `dl.ffmpeg`、`Ffmpeg.kt` 走 m3u8 合并 | — |
| 9 | 网络：最近发的包/协议 + 筛选 + 上下行 + 本地IP/服务器IP + 网速/通路/报文内容与二进制（按 field） | 部分 | `net.timeline` `net.stats` `net.tool`（resolve/cert/tcp/speed/headers）、板块 `net-timeline`/`net-tools` | **报文内容「按 field 查看」没做**（目前只有响应头清单） |
| 10 | 省电板块：两类排序（耗电排行 / 按板块）+ 点按键开关 | **基本做**（排序已通、点开关那条验收待稳） | `power.state` 新增 items（板块/估算开销/来源）、界面两个排序键（按板块 / 按耗电排行）、开销在点开的小窗里；`verify_power_sort.py` 排序与展示部分通过 | 排序用**按键**不用下拉（这台 WebView 下拉 change 不触发，查了三轮）；「点小窗里的开关」那条真手指断言在本机还偶发丢点击 |
| 11 | AI 接口：给端口，默认不给 cookies 等隐私 | 已做 | `ai.context`、`/api/ai/*`、ApiCatalog 里写明默认不给隐私 | — |
| 12 | config 模块：AI 只调自己的板块，做数据隔离 | 部分 | `space.*`（Spaces 隔离 + `/api/space/use`） | **「config 模块」形态没做**（目前是 space 切换） |
| 13 | 多窗口 | 已做 | `win.list/new/switch/close` + `/api/win/*`、持久 + 保活 ≤10（verify_windows 10/10） | — |
| 14 | 插件监听每个网页的阅读/查看时间 | 已做 | PageTimer.kt（App 侧计时，不注入）、`read.timer`、`read.stats` | — |
| 15 | 前台后台化：熄屏后部分操作仍在执行 | 已做 | `keepalive.*`、KeepAliveService.kt、`#ka-toggle` | — |
| 16 | 每个窗口静音 + 屏蔽访问一些数据 | 部分 | 每窗口 `muted`（窗口级静音 ✓） | **「屏蔽访问数据」按窗口粒度没做**（拦截是全局的） |
| 17 | 其他软件打开：提示从哪个软件打开，用户主动确认 | 已做 | MainActivity 外部协议处理：`queryIntentActivities` + 「用哪个 App 打开？」单选对话框（点取消什么都不做） | — |
| 18 | 翻译功能；能不能用 Firefox 的离线翻译 | 部分 | `tools-translate` 板块 + `translate.*`（在线 endpoint 可配） | **离线翻译（Firefox 模型/本地引擎）没做** |
| 19 | 插件把网页提取成纯文本 + 可改颜色/波浪线等 + 里面还有图片 | 已做 | `assets/scripts/plaintext.user.js`（字号/文字颜色/底色/波浪线/只留正文，保留图片） | — |
| 20 | 主页不再是介绍，介绍藏进「介绍」栏目 | 已做 | `ui/start.html`（搜索栏 + 小 app 网格）、板块 `intro-about` | — |
| 21 | 基本上所有都支持一键导出 | 已做 | `export.*`、板块 `bundle-export` | — |
| 22 | 伪装 + 与豆包式 AI 交互的简洁界面 + 基本 markdown 渲染 | 部分 | `api-ai` 板块、伪装走 UA/设置 | **markdown 渲染没做**；**「豆包式简洁界面」形态没做** |
| 23 | 为 CDP 的 CDP 提供大量接口 + 基于基础接口的二级接口 | 已做 | 路由 240 条 / op 202 条、`/api/catalog`、`/api/batch`、`/api/summary`、`/api/grab` | — |
| 24 | 一键导出：选文件导出；也支持导入 | 已做 | `export.pick` `import.*`、板块 `bundle-export` | — |
| 25 | 探测网站安全性（证书等）并做基础屏蔽 | 已做 | `NetTools.cert`、板块 `security-page`、`/api/net/tool?action=cert` | — |
| 26 | 除了开发页面，其他网页不能操作软件；访问开发网页达成访问 | 已做 | `space` 隔离 + `cdpctl://` 只有我们自己的页面能触发 + 控制口令牌 | — |
| 27 | 页内查询 | 已做 | `find`（页内查找）、`#p-find` | — |
| 28 | 把屏幕录制这些功能看成一个插件 | 部分 | 板块 `rec-screen`（屏幕录制可用） | **「当成插件」（脚本/插件形态）没做** |

## 真正缺的 8 个口子（按大小排序，这就是接下来的活）

1. **#6 aria2 等外部下载方式** —— 下载方式下拉里加 aria2（JSON-RPC 提交），要能真提交并读回 gid。
2. **#10 省电两类排序** —— 「按耗电排行」与「按板块」两种排法；每个项要标清是**实测**还是**估算**，别编数字。
3. **#22 AI 面板 markdown 渲染 + 豆包式简洁界面**（伪装那半已由 UA/设置覆盖）。
4. **#28 屏幕录制当成插件**（做成可开关的插件形态，而不是一个常驻板块）。
5. **#3 「结合录制把广告屏蔽掉」**（把录制到的选择器变成隐藏规则，一次点击）。
6. **#5 嗅探资源的分享键**（分享的是那条 m3u8/mp4，不是当前页）。
7. **#9 报文内容「按 field 查看」**（按头部字段展开 + 二进制预览）。
8. **#18 离线翻译**（Firefox 的离线模型 / 本地引擎；不做就得在界面里写明是走在线 endpoint）。
9. （#12 config 模块、#16 按窗口屏蔽数据 = 形态问题，排在后面）

**这一轮清掉的口子**：#6（aria2 外部下载，端到端 16/16）、#10（省电两类排序：按板块 / 按耗电排行，估算值明标来源）。

**已做但我一开始误判的**：#17（外部打开确认）、#19（纯文本阅读含颜色/波浪线）—— 这两条其实早就有实现，
第一次审计只做关键词匹配漏掉了，这份表已按代码证据改正。
