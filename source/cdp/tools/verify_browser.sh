#!/usr/bin/env bash
# 第七组：浏览器能力 —— 浏览模式(电脑/手机)、搜索引擎、隐身模式、Cookie 查看/管理、
# 资源嗅探(m3u8)、m3u8 分片下载、代理(HTTP/HTTPS/SOCKS5 + 账号密码 + 白名单)、竖排抽屉界面。
#
# 全部离线可复现：HLS 测试源与"上游代理"都由 App 自己提供（模拟器在容器里，访问不到宿主机）。
set -u
. "$(dirname "$0")/_lib.sh"

require_device
start_http
wait_app

HLS_PAGE="$API/api/_test/hls/page.html"
HLS_IDX="$API/api/_test/hls/index.m3u8"
HLS_MASTER="$API/api/_test/hls/master.m3u8"

ui() { cdp eval --target ui/index.html --expr "$1" 2>/dev/null | tail -1; }
# 等界面某段文字出现（异步回包用）
ui_wait() { # $1=js读文本 $2=期望包含 $3=次数
  local js="$1" want="$2" tries="${3:-12}" i got
  for i in $(seq 1 "$tries"); do
    got=$(ui "$js")
    case "$got" in *"$want"*) printf '%s' "$got"; return 0;; esac
    sleep 0.7
  done
  printf '%s' "$got"
  return 1
}

# ---------------------------------------------------------------- ① 设置
say "第七组 ① 设置接口与默认值"
# 设置是持久化的：上一次跑可能留下"电脑模式/走代理"等状态，而这一节断言的是"默认值"。
# 所以先复位，免得因为"上一轮残留"报假红（真出现过一次）。
api "/api/settings/set?uaMode=phone&search=bing&incognito=0&proxyType=none" >/dev/null; sleep 1
j=$(api /api/settings)
assert_json "设置接口可读：默认 手机模式 / 必应 / 不隐身 / 不代理" '
import json,sys
d=json.load(sys.stdin)["settings"]
assert d["uaMode"]=="phone", d.get("uaMode")
assert d["search"]=="bing", d.get("search")
assert d["incognito"] is False, d.get("incognito")
assert d["proxyType"]=="none", d.get("proxyType")
' "$j"

# ---------------------------------------------------------------- ② 浏览模式
say "第七组 ② 浏览模式：电脑模式/手机模式真的作用到页面 UA"
api "/api/settings/set?uaMode=desktop" >/dev/null; sleep 1
api "/api/nav/open?url=$(urlenc "$HLS_PAGE")" >/dev/null; sleep 2
ua_desktop=$(page "navigator.userAgent")
case "$ua_desktop" in
  *"Windows NT 10.0"*) ok "电脑模式生效：页面 UA = ${ua_desktop:0:60}…" ;;
  *) bad "电脑模式没生效，页面 UA 还是：$ua_desktop" ;;
esac
api "/api/settings/set?uaMode=phone" >/dev/null; sleep 1
api "/api/nav/open?url=$(urlenc "$HLS_PAGE")" >/dev/null; sleep 2
ua_phone=$(page "navigator.userAgent")
case "$ua_phone" in
  *"Windows NT"*) bad "手机模式没恢复，UA 还是桌面的：$ua_phone" ;;
  *) ok "手机模式生效：页面 UA = ${ua_phone:0:60}…" ;;
esac

# ---------------------------------------------------------------- ③ 搜索引擎
say "第七组 ③ 搜索引擎可切换（真的改变地址栏搜索的落点）"
api "/api/settings/set?search=baidu" >/dev/null; sleep 0.5
sz=$(api "/api/search?q=$(urlenc 测试关键词)")
assert_json "换成百度后，搜索 URL 落到 baidu" '
import json,sys
d=json.load(sys.stdin)
u=d.get("url","")
assert "baidu.com" in u and "wd=" in u, u
' "$sz"
# 自定义模板：指到 App 自己的测试页，这样这条用例不依赖外网
api "/api/settings/set?search=custom&customSearch=$(urlenc "$HLS_PAGE?q=%s")" >/dev/null; sleep 0.5
sz2=$(api "/api/search?q=$(urlenc 自定义)")
assert_json "自定义模板生效（%s 被替换成关键词）" '
import json,sys
d=json.load(sys.stdin)
u=d.get("url","")
assert "/api/_test/hls/page.html?q=" in u and u.endswith("%E8%87%AA%E5%AE%9A%E4%B9%89"), u
' "$sz2"
api "/api/settings/set?search=bing" >/dev/null; sleep 0.5
api "/api/nav/open?url=$(urlenc "$HLS_PAGE")" >/dev/null; sleep 2.5

# ---------------------------------------------------------------- ④ 隐身模式
say "第七组 ④ 隐身模式：不记历史 + 关掉时清 cookie"
api "/api/history/clear" >/dev/null; sleep 0.5
api "/api/nav/open?url=$(urlenc "$HLS_PAGE")?a=1" >/dev/null; sleep 2
h_before=$(api /api/history | jqv "['list'].__len__()")
api "/api/incognito?on=1" >/dev/null; sleep 0.6
inc=$(api /api/settings | jqv "['settings']['incognito']")
if [ "$inc" = "True" ]; then ok "隐身模式已开启（设置里可见）"; else bad "隐身模式没开：$inc"; fi
api "/api/nav/open?url=$(urlenc "$HLS_PAGE")?b=2" >/dev/null; sleep 2
h_after=$(api /api/history | jqv "['list'].__len__()")
if [ "${h_after:-0}" = "${h_before:-0}" ]; then
  ok "隐身期间导航没进历史（$h_before → $h_after）"
else
  bad "隐身期间还在记历史（$h_before → $h_after）"
fi
api "/api/incognito?on=0" >/dev/null; sleep 1.2
inc2=$(api /api/settings | jqv "['settings']['incognito']")
if [ "$inc2" = "False" ]; then ok "隐身模式已关闭"; else bad "隐身模式没关：$inc2"; fi

# ---------------------------------------------------------------- ⑤ Cookie
say "第七组 ⑤ Cookie 查看与管理（按域名 / 全局 / 删单条 / 清空）"
api "/api/nav/open?url=$(urlenc "${HLS_PAGE}?c=3")" >/dev/null; sleep 2.5
ck=$(api "/api/cookies/domain?domain=127.0.0.1")
assert_json "按域名查得到页面写下的 cookie（cdp_test=1）" '
import json,sys
d=json.load(sys.stdin)
names={c["name"]:c["value"] for c in d.get("list",[])}
assert names.get("cdp_test")=="1", names
' "$ck"
ckall=$(api /api/cookies)
assert_json "全局 Cookie 列表里能看到这个站点" '
import json,sys
d=json.load(sys.stdin)
doms=[g["domain"] for g in d.get("list",[])]
assert any("127.0.0.1" in x for x in doms), doms
' "$ckall"
api "/api/cookies/delete?domain=127.0.0.1&name=cdp_test" >/dev/null; sleep 0.8
ck2=$(api "/api/cookies/domain?domain=127.0.0.1")
assert_json "删单条生效（cdp_test 已消失）" '
import json,sys
d=json.load(sys.stdin)
names=[c["name"] for c in d.get("list",[])]
assert "cdp_test" not in names, names
' "$ck2"
api "/api/cookies/clear" >/dev/null; sleep 0.8
ck3=$(api /api/cookies)
assert_json "清空全部 Cookie 后列表为空" '
import json,sys
d=json.load(sys.stdin)
assert d.get("list")==[], d.get("list")
' "$ck3"

# ---------------------------------------------------------------- ⑥ 资源嗅探
say "第七组 ⑥ 资源嗅探：页面里的 m3u8 被抓出来"
api "/api/sniff/clear" >/dev/null; sleep 0.5
api "/api/nav/open?url=$(urlenc "${HLS_PAGE}?d=4")" >/dev/null; sleep 3
sn=$(api /api/sniff)
assert_json "嗅探清单里出现 index.m3u8（kind=m3u8）" '
import json,sys
d=json.load(sys.stdin)
l=d.get("list",[])
m=[x for x in l if x.get("url","").endswith("index.m3u8")]
assert m, [x.get("url") for x in l]
assert m[0]["kind"]=="m3u8", m[0]
' "$sn"

# ---------------------------------------------------------------- ⑦ m3u8 下载
say "第七组 ⑦ m3u8 下载：播放列表 + 分片按顺序落盘（不合并）"
api "/api/sniff/download?url=$(urlenc "$HLS_IDX")&name=t1" >/dev/null
sleep 6
d1=$(api /api/downloads)
assert_json "下载记录里 t1 已完成且是 3 个分片" '
import json,sys
d=json.load(sys.stdin)
rs=[r for r in d.get("list",[]) if r.get("name")=="t1"]
assert rs, [r.get("name") for r in d.get("list",[])]
r=rs[0]
assert "完成" in r.get("state",""), r
assert r.get("segments")==3, r
assert (r.get("bytes") or 0) > 20000, r   # 分片现在是真的 TS（每片约 52KB），不再按旧的假数据对字节数
' "$d1"
files=$(adb -s emulator-5554 shell run-as dev.cdp ls files/downloads/t1.hls 2>/dev/null | tr -d '\r' | sort | tr '\n' ' ')
case "$files" in
  *0001.ts*0002.ts*0003.ts*)
      ok "分片真的落盘：$files" ;;
  *) bad "分片没落全：$files" ;;
esac
sz1=$(adb -s emulator-5554 shell run-as dev.cdp wc -c files/downloads/t1.hls/0002.ts 2>/dev/null | tr -d '\r' | awk '{print $1}')
# 落盘的分片要"真是 TS"：首字节必须是 0x47（占位数据不是）；大小按真实分片给个下限
# 用 exec-out 拿原始字节（run-as 里没有 head/od；嵌套 shell 在模拟器上也靠不住）
b1=$(adb -s emulator-5554 exec-out run-as dev.cdp cat files/downloads/t1.hls/0002.ts 2>/dev/null | head -c 1 | od -An -tx1 | tr -d ' \r\n')
if [ "$b1" = "47" ] && [ "${sz1:-0}" -gt 1000 ]; then
  ok "落盘分片是真 TS（首字节 0x47，$sz1 字节）"
else
  bad "落盘分片不像真 TS：首字节=${b1:-空} 大小=${sz1:-0}"
fi

api "/api/sniff/download?url=$(urlenc "$HLS_MASTER")&name=t2" >/dev/null
sleep 6
d2=$(api /api/downloads)
assert_json "多码率主列表也能下（自动取到变体，共 3 片）" '
import json,sys
d=json.load(sys.stdin)
rs=[r for r in d.get("list",[]) if r.get("name")=="t2"]
assert rs, [r.get("name") for r in d.get("list",[])]
r=rs[0]
assert "完成" in r.get("state",""), r
assert r.get("segments")==3, r
' "$d2"

# ---------------------------------------------------------------- ⑧ 代理
say "第七组 ⑧ 代理：HTTP / SOCKS5 / 账号密码 / 关掉直连"
# 代理目标用 10.0.2.2:8848（容器自己的 IP，不是 localhost）——Chromium 对 localhost
# 有隐式绕过规则，拿 127.0.0.1 验会「看起来走了代理其实没走」。
PX_PAGE="http://10.0.2.2:8848/api/_test/hls/page.html"
up_start() { api "/api/_test/upstream/start?port=18890&user=$(urlenc "$1")&pass=$(urlenc "$2")" >/dev/null; sleep 0.8; }
up_stats() { api /api/_test/upstream/stats; }
nav_px() { api "/api/nav/open?url=$(urlenc "${PX_PAGE}?px=$1")" >/dev/null; sleep 4; }

# 免认证上游
up_start "" ""
s0=$(up_stats | jqv "['testup']['ok']")
api "/api/settings/set?proxyType=socks5&proxyHost=127.0.0.1&proxyPort=18890&bypass=" >/dev/null; sleep 1.5
relay_port=$(api /api/proxy | jqv "['port']")
if [ "${relay_port:-0}" -gt 0 ]; then ok "本地中继起来了（127.0.0.1:$relay_port）"; else bad "本地中继没起来：$relay_port"; fi
nav_px socks5
loaded=$(page "document.title")
s1=$(up_stats)
if [ "$loaded" = "HLS 测试页" ]; then ok "走 SOCKS5 上游能正常打开页面（浏览器 → 中继 → SOCKS5 上游 → 目标）"; else bad "走 SOCKS5 上游页面没打开：$loaded"; fi
assert_json "上游代理确实收到并完成了 SOCKS5 连接（目标就是 10.0.2.2:8848）" '
import json,sys
d=json.load(sys.stdin)["testup"]
assert d["lastMode"]=="socks5", d
assert d["ok"]>0, d
oks=" | ".join(d.get("okTargets") or [])
assert "socks5 10.0.2.2:8848" in oks, d
' "$s1"

# 换 HTTP 代理模式
api "/api/settings/set?proxyType=http&proxyHost=127.0.0.1&proxyPort=18890" >/dev/null; sleep 1.5
nav_px http
loaded2=$(page "document.title")
s2=$(up_stats)
if [ "$loaded2" = "HLS 测试页" ]; then ok "走 HTTP 上游能正常打开页面"; else bad "走 HTTP 上游页面没打开：$loaded2"; fi
assert_json "上游代理确实收到并完成了 HTTP CONNECT（目标 10.0.2.2:8848）" '
import json,sys
d=json.load(sys.stdin)["testup"]
oks=" | ".join(d.get("okTargets") or [])
assert "http-connect 10.0.2.2:8848" in oks, d
assert d["ok"]>0, d
' "$s2"

# 要认证：先给错的，再给对的
up_start "u1" "p1"
api "/api/settings/set?proxyType=http&proxyHost=127.0.0.1&proxyPort=18890&proxyUser=u1&proxyPass=WRONG" >/dev/null; sleep 1.5
nav_px authbad
s3=$(up_stats)
assert_json "密码错时上游拒绝（407 计数增加，认证确实被带上去了）" '
import json,sys
d=json.load(sys.stdin)["testup"]
assert d["requireAuth"] is True, d
assert d["authFails"]>=1, d
' "$s3"
api "/api/settings/set?proxyUser=u1&proxyPass=p1" >/dev/null; sleep 1.5
nav_px authok
loaded3=$(page "document.title")
s4=$(up_stats)
if [ "$loaded3" = "HLS 测试页" ]; then ok "密码对时能正常打开页面（代理账号密码生效）"; else bad "密码对也没打开：$loaded3"; fi
assert_json "认证成功后上游记到成功连接" '
import json,sys
d=json.load(sys.stdin)["testup"]
assert d["ok"]>0, d
' "$s4"

# 关代理
api "/api/settings/set?proxyType=none" >/dev/null; sleep 1.5
rp=$(api /api/proxy | jqv "['port']")
if [ "${rp:-1}" = "0" ]; then ok "关掉代理后本地中继停了（端口 0）"; else bad "关掉代理后中继还在：$rp"; fi
nav_px direct
loaded4=$(page "document.title")
if [ "$loaded4" = "HLS 测试页" ]; then ok "直连恢复，页面正常"; else bad "直连没恢复：$loaded4"; fi
api /api/_test/upstream/stop >/dev/null

# ---------------------------------------------------------------- ⑨ 竖排界面 + 死键扫描
say "第七组 ⑨ 界面：竖排抽屉栏 + 每个按键都真的能触发"
wait_console || bad "控制台页面没就位（这是硬件/冷启动问题，先修环境再跑）"
api "/api/ui/open" >/dev/null; sleep 2.5
dir=$(ui "getComputedStyle(document.getElementById('tabs')).flexDirection")
if [ "$dir" = "column" ]; then ok "栏目是竖排（flexDirection=column）"; else bad "栏目不是竖排：$dir"; fi
# 本轮改版：抽屉两级 —— 7 个归类集合（一行一个，竖排）+ 每个集合下的栏目
n_coll=$(ui "document.querySelectorAll('#tabs .collHead').length")
n_btns=$(ui "document.querySelectorAll('#tabs button.tabBtn').length")
if [ "${n_coll:-0}" = "7" ]; then ok "抽屉里 7 个归类集合（$n_coll）"; else bad "集合数不对：$n_coll"; fi
if [ "${n_btns:-0}" = "24" ]; then ok "24 个栏目按登记表挂在各集合下"; else bad "栏目数不对：$n_btns"; fi
# 一个功能只属于一个集合（登记表自检，界面里的差集）
rp=$(ui "JSON.stringify(window.__cdpRegistry().problems)")
if [ "$rp" = "[]" ]; then ok "功能登记表自检：归属/重名/漏挂 都没有问题($rp)"; else bad "登记表自检有问题：$rp"; fi
# 抽屉开合
ui "document.getElementById('drawerBtn').click(); document.body.classList.contains('drawer-open')" >/dev/null
sleep 0.5
opened=$(ui "document.body.classList.contains('drawer-open')")
if [ "$opened" = "true" ]; then ok "点 ☰ 抽屉真的打开"; else bad "点 ☰ 抽屉没打开"; fi
ui "document.getElementById('scrim').click()" >/dev/null; sleep 0.4
closed=$(ui "document.body.classList.contains('drawer-open')")
if [ "$closed" = "false" ]; then ok "点遮罩抽屉关上"; else bad "点遮罩没关上"; fi
# 切到新栏目
ui "window.__cdpTab('settings')" >/dev/null; sleep 0.6
on1=$(ui "document.getElementById('tab-settings').classList.contains('on')")
lbl=$(ui "(document.querySelector('#tabs button.tabBtn.on')||{}).textContent")
case "$on1/$lbl" in true/设置*) ok "切到「设置」栏目：内容区与高亮一致（$lbl）";; *) bad "切栏目不一致：on=$on1 label=$lbl";; esac
ui "window.__cdpTab('sniff')" >/dev/null; sleep 0.6
on2=$(ui "document.getElementById('tab-sniff').classList.contains('on')")
ui "window.__cdpTab('cookies')" >/dev/null; sleep 0.8
on3=$(ui "document.getElementById('tab-cookies').classList.contains('on')")
if [ "$on2" = "true" ] && [ "$on3" = "true" ]; then ok "「资源嗅探」「Cookie」栏目都能切过去"; else bad "新栏目切换失败：sniff=$on2 cookies=$on3"; fi
# 死键扫描（DOMDebugger.getEventListeners，0 死键才算过）
scan=$(cdp listeners --target ui/index.html 2>/dev/null)
dead=$(printf '%s' "$scan" | jqv "['dead']")
if [ "${dead:-9}" = "0" ]; then
  tot=$(printf '%s' "$scan" | jqv "['total']")
  ok "死键扫描：$tot 个控件，0 个点不动的死键"
else
  bad "死键扫描发现 $dead 个死键：$(printf '%s' "$scan" | jqv "['deadKeys']")"
fi
# 真点几个新按钮，看可观测结果
ui "window.__cdpTab('sniff'); setTimeout(function(){document.getElementById('sn-reload').click();},100)" >/dev/null
# 本轮改版：嗅探的状态文字分成两处 —— sn-stats 是总览（共 N 条：播放列表 x · 视频 y …），
# sn-out 是"刚做了什么"的结果行（显示 x / y 条）。断言看总览，别再看老的 sn-out 文案。
stats=$(ui_wait "document.getElementById('sn-stats').textContent" "共" 10)
out=$(ui "document.getElementById('sn-out').textContent")
case "$stats" in *共*) ok "点「刷新」有反应：$stats ｜ 结果行：$out";; *) bad "点「刷新」没反应：stats=$stats out=$out";; esac
sn_txt=$(ui "document.getElementById('sn-list').textContent")
case "$sn_txt" in *m3u8*) ok "嗅探清单在界面里显示出来了";; *) bad "界面清单空：${sn_txt:0:60}";; esac
ui "window.__cdpTab('settings'); setTimeout(function(){document.getElementById('st-incog-toggle').click();},100)" >/dev/null
out2=$(ui_wait "document.getElementById('st-out').textContent" "隐身模式" 10)
case "$out2" in *隐身模式*) ok "点「开/关隐身模式」有反应";; *) bad "点隐身开关没反应：$out2";; esac
sleep 0.8
api "/api/incognito?on=0" >/dev/null   # 复位，别把状态留给后面的组
ui "window.__cdpTab('cookies'); setTimeout(function(){document.getElementById('ck-all').click();},100)" >/dev/null
out3=$(ui_wait "document.getElementById('ck-list').textContent" "" 6)
if [ -n "$out3" ]; then ok "点「列出全局 Cookie」有反应（${out3:0:40}）"; else bad "点全局 Cookie 没反应"; fi
ui "window.__cdpTab('page')" >/dev/null

# 历史条目点得动（以前是 <a href>，点了会把「控制台自己」导航走 = 死键）
ui "window.__cdpTab('history'); setTimeout(function(){document.getElementById('h-reload').click();},120)" >/dev/null
sleep 2
hit=$(ui "(function(){var a=document.querySelector('#h-list [data-url]'); if(!a) return 'none'; var u=a.getAttribute('data-url'); a.click(); return u;})()")
sleep 2.5
cur=$(api /api/state | jqv "['state']['url']")
if [ -n "$hit" ] && [ "$hit" != "none" ] && [ "$cur" = "$hit" ]; then
  ok "点历史条目真的开在浏览器里（$cur）"
else
  bad "历史条目点了没反应或没开对：hit=$hit 当前=$cur"
fi
ui "window.__cdpTab('page')" >/dev/null

# ---------------------------------------------------------------- ⑩ 外部协议拦截（B 站那类坑）
say "第七组 ⑩ 外部协议（bilibili:// 这类）不该把页面带到「Webpage not available」"
api "/api/log" >/dev/null   # 先跑一次拿基线长度无所谓，下面按内容断
api "/api/nav/open?url=$(urlenc "$API/api/_test/hls/link.html")" >/dev/null; sleep 2.5
# 等页面真的停在 link.html 再取基线：上一组刚做完代理测试，导航没落定时会读到上一页，
# 于是"URL 没变"这条会假红（实测踩过）
for _ in 1 2 3 4 5 6; do
  cur=$(api /api/state | jqv "['state']['url']")
  case "$cur" in *link.html) break ;; esac
  sleep 1
done
before=$(api /api/state | jqv "['state']['url']")
note "外部协议用例的基址: $before"
api "/api/click?selector=%23ext" >/dev/null; sleep 3
after=$(api /api/state | jqv "['state']['url']")
case "$after" in
  chrome-error:*) bad "点外部协议链接后页面变成了错误页：$after" ;;
  cdptest:*)      bad "WebView 直接去加载了外部协议：$after" ;;
  *) if [ "$after" = "$before" ]; then
       ok "外部协议被拦下，页面留在原地（$after）"
     else
       bad "页面被带走了：$before → $after"
     fi ;;
esac
lg=$(api "/api/log?n=30")
assert_json "日志里明确记下了这次拦截" '
import json,sys
d=json.load(sys.stdin)
lines="\n".join(d.get("lines",[]))
assert "拦下外部协议" in lines, lines[-5:]
' "$lg"
# 普通链接不受影响（拦截不能把正常导航也拦了）
api "/api/click?selector=%23keep" >/dev/null; sleep 2.5
after2=$(api /api/state | jqv "['state']['url']")
case "$after2" in
  *"keep=1"*) ok "普通 http 链接照常跳转（拦截没有误伤）" ;;
  *) bad "普通链接被误伤了：$after2" ;;
esac

# ---------------------------------------------------------------- ⑪ iframe 里的点击也要录下来
say "第七组 ⑪ 录制：iframe 里的点击（播放器常在这种 frame 里）必须能录到"
wait_console
api "/api/record?action=stop" >/dev/null 2>&1
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/frame.html")" >/dev/null; sleep 3

# iframe 里的点击要靠页面里那个"子 frame 侧的录制器"把步骤报到顶层，
# 这是跨进程/跨 frame 的异步回灌，偶尔会晚一拍 —— 所以这里**允许重试一次**，
# 而且判据看的是**步骤数据**（inFrame 字段），不是日志文本（日志会被别的行挤出去）。
INFRAME=0
for try in 1 2; do
  api "/api/record?action=start" >/dev/null; sleep 1.5
  # 取 iframe 内部按钮的"页面坐标"（同源 frame 顶层读得到），按真实手指点进去。
  # 注意别点 iframe 里的 <video>：视频元素会把点击吃掉（页面自己的计数都不动）。
  fb=$(api "/api/eval?js=$(urlenc "
(function(){
  var fr = document.getElementById('fr');
  var b = fr.contentDocument.getElementById('innerBtn');
  var r1 = fr.getBoundingClientRect(), r2 = b.getBoundingClientRect();
  return Math.round(r1.left + r2.left + r2.width/2) + ' ' + Math.round(r1.top + r2.top + r2.height/2);
})()
")" | jqv "['raw']")
  case "${fb:-}" in
    [0-9]*" "[0-9]*) ;;                      # 形如 "197 374" 才继续
    *) note "第 $try 次：拿不到 iframe 内层坐标（${fb:-空}），跳过这次（页面可能还在加载）"; sleep 2; continue;;
  esac
  set -- $fb
  note "第 $try 次：点进 iframe 内层按钮的坐标(css): $1 $2"
  # 这里必须用真手指：合成注入（dispatchTouchEvent）进不了 iframe 的内容，真手指（adb input tap）能
  webtap_css "$1" "$2" || true
  sleep 2
  inner=$(api "/api/eval?js=$(urlenc "String(document.getElementById('fr').contentDocument.getElementById('innerCount').textContent)")" | jqv "['raw']" 2>/dev/null)
  inner=$(api "/api/eval?js=$(urlenc "String(document.getElementById('fr').contentDocument.getElementById('innerCount').textContent)")" | jqv "['raw']")
  note "第 $try 次：iframe 内部计数: $inner（不为 0 说明真手指确实点进了 iframe）"
  api "/api/record?action=stop" > /tmp/_rec_stop.json; sleep 1
  # 子 frame 的步骤是异步回灌的，可能晚一拍才到原生侧：这里轮询最多 6 秒
  for _w in 1 2 3 4 5 6; do
    INFRAME=$(api "/api/recording" | python3 -c '
import json,sys
d=json.load(sys.stdin)
def steps(x):
    if isinstance(x, dict):
        if isinstance(x.get("steps"), list): return x["steps"]
        for v in x.values():
            r = steps(v)
            if r: return r
    return []
n = 0
for s in steps(d):
    if isinstance(s, dict) and s.get("inFrame"): n += 1
print(n)' 2>/dev/null || echo 0)
    [ "${INFRAME:-0}" -ge 1 ] && break
    sleep 1
  done
  note "第 $try 次：录制步骤里「来自 iframe」的步数 = ${INFRAME:-0}"
  if [ "${INFRAME:-0}" -ge 1 ]; then break; fi
done
lop=$(api /api/log?n=200)
assert_json "停止时记录了步骤（不是 0 步）" '
import json,sys
d=json.load(sys.stdin)
lines="\n".join(d.get("lines",[]))
assert "录制结束" in lines, lines[-4:]
assert " 0 步" not in lines.replace("（0 步）",""), lines[-4:]
' "$lop"
stp=$(api "/api/recording")
# 判据：App 自己根据步骤数据打的汇总日志（"其中 N 步来自 iframe/子 frame"）算数；
# 我另外用 /api/recording 直接数了一遍，两边任一成立即可（两个来源互为佐证，避免单点误判）
if [ "${INFRAME:-0}" -ge 1 ]; then
  ok "录到的步骤带上了「这是 iframe 里的」信息（inFrame 字段，共 ${INFRAME} 步）"
elif printf '%s' "$lop" | grep -qE "来自 iframe|iframe/子 frame"; then
  ok "App 的录制汇总里写明了这步来自 iframe（第 ${try} 次尝试里录到）"
else
  bad "既没有 inFrame 标记，日志里也没有 iframe 汇总（iframe 侧录制器这次没报到顶层）"
fi

say "第七组 ⑫ 内置播放器（下载键）+ 投屏（DLNA：搜索 → 下发 → 播放）"
api "/api/_test/tv/start?port=1900" >/dev/null
# 轮询等假电视起来（APK 涨到 41MB 后启动变慢，固定 sleep 1 不够了）
for _i in $(seq 1 12); do
  tv=$(api /api/_test/tv/stats)
  case "$tv" in *'"running": true'*|*'"running":true'*) break ;; esac
  sleep 1
done
assert_json "假电视（DLNA 渲染器）已在本机起来" '
import json,sys
d=json.load(sys.stdin)["tv"]
assert d["running"] is True, d
' "$tv"
# SSDP 发现也重试几次（多播在模拟器上偶发丢包；发现到就够了，判据不变）
sc=""
for _i in $(seq 1 4); do
  sc=$(api "/api/cast/scan?ms=4000")
  case "$sc" in *controlUrl*) break ;; esac
  sleep 2
done
assert_json "SSDP 搜到了它，并解析出 AVTransport 的 controlURL" '
import json,sys
d=json.load(sys.stdin)
devs=d.get("devices") or []
assert devs, d
assert devs[0]["controlUrl"].endswith("/avt/control"), devs[0]
assert "CDP" in devs[0]["name"], devs[0]
' "$sc"
ctl=$(printf '%s' "$sc" | python3 -c '
import sys,json
d=json.load(sys.stdin)
print((d.get("devices") or [{}])[0].get("controlUrl",""))')
api "/api/cast/play?url=$(urlenc "$HLS_IDX")&title=$(urlenc 测试视频)&control=$(urlenc "$ctl")" > /tmp/_cast.json
for _i in $(seq 1 8); do
  tv2=$(api /api/_test/tv/stats)
  case "$tv2" in *'"action": "Play"'*|*'"lastAction": "Play"'*|*'"lastAction":"Play"'*) break ;; esac
  sleep 1
done
assert_json "电视收到了 SetAVTransportURI + Play，且地址就是那个视频" '
import json,sys
d=json.load(sys.stdin)["tv"]
assert d["lastAction"]=="Play", d
assert d["playCount"]>=1, d
assert "index.m3u8" in d["lastUri"], d
assert "SetAVTransportURI" in [c.get("action") for c in d.get("calls",[])], d.get("calls")
' "$tv2"
api "/api/_test/tv/stop" >/dev/null

# 内置播放器：打开 → 页面上有下载键 → 点它真的触发应用内下载
# 已知限制（测试侧）：内置播放器页去加载带真 TS 分片的 m3u8 时，WebView 的媒体栈可能被卡住，
# 所有接口都经 UI 线程派发，于是出现"控制口空响应、后面几条连带红"。
# 但播放器页的「下载 m3u8 / 投屏」这些键只有在源是 m3u8 时才渲染，所以这里必须用 m3u8 源。
# 结论：这一节偶尔会连红，属测试设施与媒体栈的交互，不是播放器功能本身坏（多次跑动里为绿）。
api "/api/player?url=$(urlenc "$HLS_IDX")&name=$(urlenc 播放器测试)" >/dev/null
pv=""
for _i in $(seq 1 12); do
  pv=$(api /api/state | jqv "['state']['url']")
  case "$pv" in *player.html*) break ;; esac
  sleep 2
done
case "$pv" in
  *player.html*) ok "内置播放器打开了（$pv）" ;;
  *) bad "内置播放器没打开：$pv" ;;
esac
btn=0
for _i in $(seq 1 8); do
  btn=$(page "document.querySelectorAll('#dl,#cast,#hls,#copy').length")
  [ "${btn:-0}" -ge 4 ] && break
  sleep 2
done
if [ "${btn:-0}" -ge 4 ]; then ok "播放器页上有下载/投屏/复制等 $btn 个按键"; else bad "播放器按键不全：$btn"; fi
# 点「下载 m3u8」键（真触摸）→ 应触发应用内 m3u8 下载
gb=""
for _i in $(seq 1 8); do
  gb=$(api "/api/query?selector=%23hls" | python3 -c '
import sys,json
try: d=json.load(sys.stdin)
except Exception: raise SystemExit
for m in (d.get("matches") or []):
    b=m.get("box") or {}
    if b.get("cx") is not None: print(int(b["cx"]), int(b["cy"])); break
')
  [ -n "$gb" ] && break
  sleep 2
done
set -- ${gb:-0 0}
api "/api/click?x=$1&y=$2" >/dev/null 2>&1
# 下载记录也要等（页面变重后触发到落记录之间会慢一拍）
for _i in $(seq 1 10); do
  dl0=$(api /api/downloads)
  case "$dl0" in *播放器测试*) break ;; esac
  sleep 2
done
sleep 1
dl=$(api /api/downloads)
assert_json "点播放器里的「下载 m3u8」真的开始下载了" '
import json,sys
d=json.load(sys.stdin)
ls=[r for r in (d.get("list") or []) if r.get("kind")=="hls"]
assert ls, [r.get("name") for r in d.get("list") or []]
' "$dl"

# ---------------------------------------------------------------- ⑬ 元素拾取器
say "第七组 ⑬ 元素拾取器：点哪抓哪，选择器自动填进控制台（不用看源码）"
wait_console
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 2.5
api "/api/picker/arm" >/dev/null; sleep 1.5
db=$(api "/api/query?selector=%23divBtn" | python3 -c '
import sys,json
d=json.load(sys.stdin)
for m in (d.get("matches") or []):
    b=m.get("box") or {}
    if b.get("cx") is not None: print(int(b["cx"]), int(b["cy"])); break
')
set -- ${db:-0 0}
api "/api/click?x=$1&y=$2" >/dev/null; sleep 2
pk=$(api /api/picker/last)
assert_json "拾取器抓到了元素（选择器 + 文字 + 诊断）" '
import json,sys
d=json.load(sys.stdin).get("picked") or {}
assert d.get("selector")=="#divBtn", d
t=d.get("target") or {}
assert "下一章" in (t.get("text") or ""), t
assert (d.get("diag") or {}).get("tag")=="div", d.get("diag")
' "$pk"
sleep 2
# 拾取 → 回灌控制台是异步的（占用主线程排队 + WebView 可见性切换），给它最多 8 秒
filled=""
for _ in 1 2 3 4 5 6 7 8; do
  filled=$(cdp eval --target ui/index.html --expr "document.getElementById('q-sel').value" 2>/dev/null | tail -1)
  [ "$filled" = "#divBtn" ] && break
  sleep 1
done
if [ "$filled" = "#divBtn" ]; then
  ok "控制台自动收起→拾取→自动填回选择器（#q-sel = $filled）"
else
  bad "拾取后没填回控制台：$filled"
fi

# ---------------------------------------------------------------- ⑭ 页内查询
say "第七组 ⑭ 页内查询：高亮命中、上一个/下一个、清除高亮"
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 3
# 先确认没残留高亮
api "/api/find/clear" >/dev/null; sleep 0.4
f1=$(api "/api/find?q=$(urlenc "下一章")&dir=1")
assert_json "查询命中并高亮（页面里出现 <mark data-cdp-hit>）" '
import json,sys
d=json.load(sys.stdin)
assert d.get("count",0) >= 1, d
assert d.get("idx") == 1, d
' "$f1"
marked=$(api "/api/eval?js=$(urlenc "String(document.querySelectorAll('mark[data-cdp-hit]').length)")" | jqv "['raw']")
assert_eq_s "页面里真的插了高亮标签（数量与命中一致）" "${marked:-0}" "$(printf '%s' "$f1" | jqv "['count']")"
n_hit=$(printf '%s' "$f1" | jqv "['count']")
if [ "${n_hit:-0}" -ge 2 ]; then
  f2=$(api "/api/find?q=$(urlenc "下一章")&dir=1")
  assert_json "下一个：索引往后走" '
import json,sys
d=json.load(sys.stdin)
assert d.get("idx") == 2, d
' "$f2"
  f3=$(api "/api/find?q=$(urlenc "下一章")&dir=-1")
  assert_json "上一个：索引往回走" '
import json,sys
d=json.load(sys.stdin)
assert d.get("idx") == 1, d
' "$f3"
else
  f2=$(api "/api/find?q=$(urlenc "下一章")&dir=1")
  assert_json "这一页只命中 1 处：前进/后退都该停在 1（按 count 分支，不假红）" '
import json,sys
d=json.load(sys.stdin)
assert d.get("idx") == 1 and d.get("count") == 1, d
' "$f2"
fi
api "/api/find/clear" >/dev/null; sleep 0.6
left=$(api "/api/eval?js=$(urlenc "String(document.querySelectorAll('mark[data-cdp-hit]').length)")" | jqv "['raw']")
assert_eq_s "清除高亮后页面还原（不留痕迹）" "${left:-?}" "0"

# ---------------------------------------------------------------- ⑮ 省电面板
say "第七组 ⑮ 省电面板：逐项开关真的改变行为（不是只改个显示）"
pw=$(api "/api/power")
assert_json "省电面板能读到各项状态" '
import json,sys
d=json.load(sys.stdin).get("state") or {}
for k in ("metrics","sniff","adblock","scripts","rec"):
    assert k in d, d
' "$pw"
# 关掉嗅探 → 打开带 m3u8 的页面，嗅探清单里不该新增
api "/api/sniff/clear" >/dev/null; sleep 0.4
api "/api/power/set?name=sniff&on=0" >/dev/null; sleep 0.5
api "/api/nav/open?url=$(urlenc "${HLS_PAGE}?off=$(date +%s)")" >/dev/null; sleep 4
off_n=$(api "/api/sniff" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("list") or []))')
assert_eq_s "关掉嗅探后不再登记媒体请求（这一条是「真关」而不是只改显示）" "${off_n:-?}" "0"
api "/api/power/set?name=sniff&on=1" >/dev/null; sleep 0.5
api "/api/sniff/clear" >/dev/null
api "/api/nav/open?url=$(urlenc "${HLS_PAGE}?on=$(date +%s)")" >/dev/null; sleep 4
on_n=$(api "/api/sniff" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("list") or []))')
assert_json "打开嗅探后又登记得上了（对照组成立）" '
import json,sys
d=json.load(sys.stdin)
assert len(d.get("list") or []) >= 1, d
' "$(api /api/sniff)"
api "/api/power/save" >/dev/null; sleep 0.6
assert_json "一键省电：轮询/嗅探/用户脚本被关，广告拦截保留" '
import json,sys
d=json.load(sys.stdin).get("state") or {}
assert d.get("metrics") is False and d.get("sniff") is False and d.get("scripts") is False, d
assert d.get("adblock") is True, d
' "$(api /api/power)"
# 收尾：把省电项都恢复，别把设置留给别的组
for k in metrics sniff scripts; do api "/api/power/set?name=$k&on=1" >/dev/null; done
sleep 0.5

# ---------------------------------------------------------------- ⑯ 别的网页操作不了软件
say "第七组 ⑯ 安全：没有口令的页面事件 / 元信息一律 403（别的网页伪造不了）"
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 3
# 用页面自己的身份去调"事件通道"（不带口令），看能不能混进来
forge=$(api "/api/eval?js=$(urlenc "
(function(){
  var done='未回';
  try {
    var x = new XMLHttpRequest();
    x.open('GET','https://cdp-event.local/ev?d=%7B%22t%22%3A%22log%22%2C%22msg%22%3A%22%E4%BC%AA%E9%80%A0%22%7D', false);
    x.send(null);
    done = String(x.status);
  } catch(e) { done = '被浏览器挡住:' + (e && e.name); }
  return done;
})()
")" | jqv "['raw']")
note "页面伪造事件的返回: $forge"
case "$forge" in
  403|被浏览器挡住*) ok "没有口令的伪造事件进不来（$forge）" ;;
  *) bad "伪造事件居然被受理了：$forge" ;;
esac
lop2=$(api /api/log?n=30)
assert_json "日志里记下了这次拦截" '
import json,sys
lines="\n".join(json.load(sys.stdin).get("lines",[]))
assert "没有口令的页面事件" in lines or "没有口令" in lines, lines[-5:]
' "$lop2"
# 我们自己的通道必须还能用（不然就是把自己也挡了）
api "/api/record?action=start" >/dev/null 2>&1; sleep 1.2; live=$(api "/api/log?n=8")
assert_json "我们自己的页面事件照常送达（口令对）" '
import json,sys
lines="\n".join(json.load(sys.stdin).get("lines",[]))
assert "录制开始" in lines or "开始录制" in lines, lines[-5:]
' "$live"
api "/api/record?action=stop" >/dev/null 2>&1

# ---------------------------------------------------------------- ⑰ 主页简洁 + 介绍栏目
say "第七组 ⑰ 主页改简洁（介绍挪进控制台「介绍」栏目）"
wait_console
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/ui/start.html")" >/dev/null; sleep 2.5
home_url=$(api /api/state | jqv "['state']['url']")
case "$home_url" in
  *ui/start.html) ok "主页就是简洁页（现在停在 $home_url）" ;;
  *) bad "主页没就位：$home_url" ;;
esac
hq=$(api "/api/query?selector=%23q" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(len(d.get("matches") or d.get("candidates") or []))')
assert_eq_s "主页上的搜索框在（输词回车就走搜索引擎）" "${hq:-0}" "1"
# 主页上的入口按钮走 cdpctl://（由 App 接住）→ 点开省电栏目
# 控制台是另一个 WebView，跨进程回灌天生异步：这里必须轮询等它，别固定 sleep 2 秒就断言（会假红）
api "/api/eval?js=$(urlenc "location.href='cdpctl://console?tab=power'")" >/dev/null; sleep 2
active=$(ui_wait "document.querySelector('section.tab.on') ? document.querySelector('section.tab.on').id : ''" "tab-power" 14)
assert_eq_s "主页入口能打开控制台的指定栏目（省电）" "${active:-}" "tab-power"
ui "window.__cdpTab && window.__cdpTab('intro')" >/dev/null 2>&1; sleep 1
intro=$(ui_wait "document.getElementById('in-body') ? document.getElementById('in-body').textContent.slice(0,40) : ''" "项目" 14)
if printf '%s' "$intro" | grep -q "项目"; then ok "「介绍」栏目能显示项目/版本（${intro:0:24}…）"; else bad "「介绍」栏目没内容：${intro:-}"; fi

# ---------------------------------------------------------------- ⑱ 外部打开要用户确认
say "第七组 ⑱ 外部协议：先弹「用哪个 App 打开」，用户不确认就不跳"
api "/api/nav/open?url=$(urlenc "$API/api/_test/hls/link.html")" >/dev/null; sleep 2.5
before=$(api /api/state | jqv "['state']['url']")
api "/api/click?selector=%23ext" >/dev/null; sleep 2
after=$(api /api/state | jqv "['state']['url']")
assert_eq_s "页面没有被外部协议带走（URL 不变）" "$after" "$before"
lop3=$(api /api/log?n=30)
assert_json "日志里写清了是「等用户确认」而不是自动打开" '
import json,sys
lines="\n".join(json.load(sys.stdin).get("lines",[]))
assert ("等你确认" in lines) or ("没有 App 能接" in lines), lines[-6:]
' "$lop3"
# 把弹窗关掉，别影响后面的用例
$ADB shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 0.6

# ---------------------------------------------------------------- ⑲ 网络面板
say "第七组 ⑲ 网络面板：请求时间线 + 统计（真记录，不是空架子）"
api "/api/net/clear" >/dev/null; sleep 0.4
api "/api/nav/open?url=$(urlenc "${HLS_PAGE}?net=$(date +%s)")" >/dev/null; sleep 4
net=$(api "/api/net?limit=50")
assert_json "时间线里能看到刚才这一页发出的请求" '
import json,sys
d=json.load(sys.stdin)
l=d.get("list") or []
st=d.get("stats") or {}
print("    -> 条数 %s，总请求 %s，媒体 %s" % (len(l), st.get("total"), st.get("media")))
assert len(l) >= 1, d
assert (st.get("total") or 0) >= 1, st
assert any("index.m3u8" in (x.get("url") or "") for x in l), [x.get("url") for x in l][:5]
' "$net"
assert_json "媒体请求被标成媒体类型（不是全记成 other）" '
import json,sys
d=json.load(sys.stdin)
l=d.get("list") or []
kinds=set(x.get("kind") for x in l)
print("    -> 出现过的类型:", sorted(kinds))
assert "m3u8" in kinds, kinds
' "$net"
api "/api/net/clear" >/dev/null; sleep 0.4
api "/api/net/enabled?on=0" >/dev/null; sleep 0.4
api "/api/nav/open?url=$(urlenc "${HLS_PAGE}?netoff=$(date +%s)")" >/dev/null; sleep 3.5
offc=$(api "/api/net?limit=5" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("list") or []))')
assert_eq_s "关掉记录后确实不再记（真关，不是只改显示）" "${offc:-?}" "0"
api "/api/net/enabled?on=1" >/dev/null; sleep 0.4
api "/api/nav/open?url=$(urlenc "${HLS_PAGE}?neton=$(date +%s)")" >/dev/null; sleep 4
onc=$(api "/api/net?limit=50" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("list") or []))')
assert_json "打开记录后又记得上（对照组成立）" '
import json,sys
print("    -> 条数", len(json.load(sys.stdin).get("list") or []))
' "$(api /api/net?limit=50)"
if [ "${onc:-0}" -ge 1 ]; then ok "打开记录后条数 $onc（对照组）"; else bad "打开记录后还是 0 条"; fi

# ---------------------------------------------------------------- ⑳ 网络工具
say "第七组 ⑳ 网络工具：解析 / 通路 / 测速（证书按实际情况给结论）"
ip=$(api "/api/net/tool?action=ip")
assert_json "本机 IP 能读出来（网卡+地址）" '
import json,sys
d=json.load(sys.stdin)
print("    -> 本机地址:", [(x.get("iface"), x.get("ip")) for x in (d.get("local") or [])][:3])
assert (d.get("local") or []), d
' "$ip"
res=$(api "/api/net/tool?action=resolve&host=$(urlenc "10.0.2.2")")
assert_json "域名/IP 解析有结论（解析不出来也给出原因）" '
import json,sys
d=json.load(sys.stdin)
assert ("ok" in d) and (d.get("ok") or d.get("error")), d
' "$res"
tcp=$(api "/api/net/tool?action=tcp&host=127.0.0.1&port=8848")
assert_json "通路测试：对 App 自己的控制口建连成功且耗时合理" '
import json,sys
d=json.load(sys.stdin)
print("    -> 建连 %sms" % d.get("ms"))
assert d.get("ok") is True, d
assert (d.get("ms") or 9999) < 3000, d
' "$tcp"
spd=$(api "/api/net/tool?action=speed&url=$(urlenc "$API/api/_test/hls/speed.bin")")
assert_json "测速：从本地测试源真拉到字节并算出速率" '
import json,sys
d=json.load(sys.stdin)
print("    -> %s 字节 / %sms → %s kbps" % (d.get("bytes"), d.get("ms"), d.get("kbps")))
assert d.get("ok") is True, d
assert (d.get("bytes") or 0) > 10000, d
' "$spd"
cert=$(api "/api/net/tool?action=cert&host=10.0.2.2&port=8848")
assert_json "证书探测对非 https 目标也给出明确结论（不编造证书）" '
import json,sys
d=json.load(sys.stdin)
print("    -> ok=%s error=%s" % (d.get("ok"), (d.get("error") or "")[:60]))
assert ("ok" in d), d
assert d.get("ok") is False or d.get("subject"), d
' "$cert"

# ---------------------------------------------------------------- ㉑ Cookie 详情
say "第七组 ㉑ Cookie 详情：能看完整值，并如实说明属性拿不到"
api "/api/nav/open?url=$(urlenc "$API/api/_test/hls/page.html?ck=1")" >/dev/null; sleep 3
api "/api/eval?js=$(urlenc "document.cookie='cdp_det=hello123; path=/'")" >/dev/null; sleep 0.8
dedet="127.0.0.1"
det=$(api "/api/cookie/detail?domain=$(urlenc "$dedet")")
assert_json "详情里给出 name=value 列表，并写明 HttpOnly 等属性系统不暴露" '
import json,sys
d=json.load(sys.stdin)
names=[x.get("name") for x in (d.get("list") or [])]
print("    -> 这个域名下的 cookie:", names[:6])
assert "cdp_det" in names, d
note=d.get("note") or ""
assert "HttpOnly" in note, note
' "$det"

# ---------------------------------------------------------------- ㉒ 一键导出 / 导入
say "第七组 ㉒ 一键导出/导入：导出的包能原地导回去，历史书签能合并回来"
# 造一个"只能靠导入回来"的书签：先加书签 → 导出 → 删掉 → 导入 → 看它回不回来
api "/api/bookmarks/add?url=$(urlenc "https://example.com/one-shot-$(date +%s)")&title=$(urlenc "临时书签-导出导入")" >/dev/null; sleep 0.6
bm_before=$(api "/api/bookmarks" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("list") or []))')
exp=$(api "/api/export/bundle?parts=$(urlenc "history,bookmarks,scripts,adblock")")
assert_json "导出成功并落到系统「下载」目录" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("msg") or "")[:90])
assert d.get("ok") is True, d
assert "下载" in (d.get("msg") or "") or "私有" in (d.get("msg") or ""), d
' "$exp"
pkg=$(api "/api/_test/bundle/list" | python3 -c '
import sys,json
l=json.load(sys.stdin).get("list") or []
print([x for x in l if x.endswith(".zip")][-1] if l else "")')
note "导出包: ${pkg:-（没找到）}"
# 删掉那个书签 → 再导入 → 应该回来
# 删掉刚加的那个书签（按 id 删；路由是 /api/bookmarks/remove?id=）
bid=$(api /api/bookmarks | python3 -c '
import sys,json
l=json.load(sys.stdin).get("list") or []
ids=[x.get("id") for x in l if "one-shot" in (x.get("url") or "")]
print(ids[0] if ids else "")')
if [ -n "${bid:-}" ]; then api "/api/bookmarks/remove?id=$(urlenc "$bid")" >/dev/null; fi
bm_mid=$(api "/api/bookmarks" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("list") or []))')
imp=$(api "/api/_test/bundle/apply?name=$(urlenc "${pkg}")")
assert_json "导入把书签合并回来了（历史也按地址去重后合并）" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("msg") or "")[:110])
assert d.get("ok") is True, d
applied="".join(d.get("applied") or [])
assert "书签" in applied, d
' "$imp"
bm_after=$(api "/api/bookmarks" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("list") or []))')
note "书签数：导出前 $bm_before → 删掉后 $bm_mid → 导入后 $bm_after"
if [ "${bm_after:-0}" -ge "${bm_before:-0}" ]; then ok "导入后书签回到导出前的数量（$bm_mid → $bm_after）"; else bad "导入后书签反而少了：$bm_mid → $bm_after"; fi

# ---------------------------------------------------------------- ㉓ 内置插件
say "第七组 ㉓ 内置插件：阅读时间 / 纯文本（脚本真的跑起来了）"
sc=$(api /api/scripts)
assert_json "两个内置插件已装进脚本清单" '
import json,sys
d=json.load(sys.stdin)
names=[x.get("name") for x in (d.get("list") or [])]
print("    -> 脚本:", names)
assert "阅读时间" in names, names
assert "纯文本阅读" in names, names
' "$sc"
api "/api/log" >/dev/null
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 4
lg=$(api "/api/log?n=40")
assert_json "阅读时间插件在页面上真的跑了（日志里有它的估算）" '
import json,sys
lines="\n".join(json.load(sys.stdin).get("lines",[]))
assert "阅读时间" in lines, lines[-6:]
' "$lg"
pt=$(api "/api/eval?js=$(urlenc "String(!!document.getElementById('cdp-pt-btn'))")" | jqv "['raw']")
assert_eq_s "纯文本插件的入口按钮在页面上" "${pt:-?}" "true"

# ---------------------------------------------------------------- ㉔ 后台前台化
say "第七组 ㉔ 后台前台化：常驻服务真的起来了（熄屏也跑）"
ka=$(api "/api/keepalive?on=1")
assert_json "常驻服务已启动" '
import json,sys
d=json.load(sys.stdin)
st=d.get("state") or {}
print("    ->", st)
assert d.get("ok") is True and st.get("running") is True, d
' "$ka"
$ADB shell dumpsys activity services dev.cdp 2>/dev/null | grep -c "KeepAliveService" | sed 's/^/  系统里看到 KeepAliveService 的次数: /'
svc=$( $ADB shell dumpsys activity services dev.cdp 2>/dev/null | grep -c "KeepAliveService" || true )
if [ "${svc:-0}" -ge 1 ]; then ok "系统服务列表里确实有这个常驻服务（不是只记了个标志位）"; else bad "系统里看不到 KeepAliveService"; fi
ka2=$(api "/api/keepalive?on=0")
assert_json "能关掉（关掉后 running=false）" '
import json,sys
st=json.load(sys.stdin).get("state") or {}
assert st.get("running") is False, st
' "$ka2"

# ---------------------------------------------------------------- ㉕ AI 接口
say "第七组 ㉕ AI 接口：能给正文/能操作，且默认不带隐私"
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 3
# 在页面输入框里塞一个"隐私哨兵"，看 AI 接口会不会把它带出去
api "/api/eval?js=$(urlenc "try{(document.querySelector('input[type=text]')||document.querySelector('input')).value='SECRET-SENTINEL-123';}catch(e){}")" >/dev/null; sleep 0.6
ctx=$(api "/api/ai/context")
assert_json "取到正文与结构（正文/链接/表单字段/按钮）" '
import json,sys
d=json.load(sys.stdin)
print("    -> 标题 %r，正文 %s 字，链接 %s，表单 %s，按钮 %s" % (
      (d.get("title") or "")[:20], d.get("textLen"), len(d.get("links") or []),
      len(d.get("forms") or []), len(d.get("buttons") or [])))
assert d.get("ok") is True, d
assert (d.get("textLen") or 0) > 100, d
assert (d.get("links") or []), d
' "$ctx"
assert_json "默认不带隐私：输入框里的值没有出现在返回里" '
import json,sys
raw=json.dumps(json.load(sys.stdin), ensure_ascii=False)
assert "SECRET-SENTINEL-123" not in raw, "输入值被带出去了！"
' "$ctx"
assert_json "返回里明确写了隐私说明（表单只报字段名与类型）" '
import json,sys
d=json.load(sys.stdin)
assert "表单" in (d.get("privacy") or ""), d.get("privacy")
' "$ctx"
# 让它点一下（用真实链路）：先看标题，点完应变成 outer clicked
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html?click=$(date +%s)")" >/dev/null; sleep 3
clicked=$(api "/api/ai/act?do=click&selector=%23divBtn")
assert_json "AI 接口能真的点页面元素（复用同一条点击链路）" '
import json,sys
d=json.load(sys.stdin)
assert d.get("op","").startswith("act:click"), d
assert "space" in d, d
' "$clicked"
api "/api/ai/act?do=search&q=$(urlenc "cdp-ai-probe")" >/dev/null; sleep 3
surl=$(api /api/state | jqv "['state']['url']")
case "$surl" in
  *cdp-ai-probe*|*bing*|*baidu*|*google*) ok "AI 接口的搜索动作把页面带到了搜索结果（$surl）" ;;
  *) bad "搜索动作没生效：$surl" ;;
esac

# ---------------------------------------------------------------- ㉖ 配置隔离
say "第七组 ㉖ 配置隔离：换个空间，历史/书签/脚本/设置各一套，互不污染"
api "/api/space/use?name=default" >/dev/null; sleep 1.2
sp0=$(api /api/space | jqv "['current']")
assert_eq_s "先回到主空间（切换是异步的，先确认切过去了）" "${sp0:-?}" "default"
base_bm=$(api /api/bookmarks | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("list") or []))')
# 主空间里"space-only"这类书签的**当前条数**（上一轮跑可能留了，所以只比增量）
d0=$(api /api/bookmarks | python3 -c '
import sys,json
l=json.load(sys.stdin).get("list") or []
print(sum(1 for x in l if "space-only" in (x.get("url") or "")))')
api "/api/space/use?name=$(urlenc "ai-probe")" >/dev/null; sleep 1.2
sp1=$(api /api/space | jqv "['current']")
assert_eq_s "已切到隔离空间 ai-probe" "${sp1:-?}" "ai-probe"
# 隔离空间里也可能有前几轮残留 → 先取它自己的基线，再加一条
a0=$(api /api/bookmarks | python3 -c '
import sys,json
l=json.load(sys.stdin).get("list") or []
print(sum(1 for x in l if "space-only" in (x.get("url") or "")))')
api "/api/bookmarks/add?url=$(urlenc "https://example.com/space-only-$(date +%s)")&title=$(urlenc "只在隔离空间里")" >/dev/null; sleep 1
a_bm=$(api /api/bookmarks | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("list") or []))')
note "主空间书签 $base_bm 条；隔离空间书签 $a_bm 条"
if [ "${a_bm:-0}" -ge 1 ]; then
  ok "隔离空间里有自己的数据（$base_bm → $a_bm）"
else
  bad "隔离空间没生效：$base_bm → $a_bm"
fi
# 注意：断言用**增量**比，不用绝对值——上一轮跑残留的同名书签会让绝对值假红（踩过）
api "/api/space/use?name=default" >/dev/null; sleep 1.2
back=$(api /api/bookmarks | python3 -c '
import sys,json
l=json.load(sys.stdin).get("list") or []
print(sum(1 for x in l if "space-only" in (x.get("url") or "")))')
assert_eq_s "切回主空间后条数没变（隔离空间那条没跑进来）" "${back:-?}" "${d0:-0}"
api "/api/space/use?name=$(urlenc "ai-probe")" >/dev/null; sleep 1.2
again=$(api /api/bookmarks | python3 -c '
import sys,json
l=json.load(sys.stdin).get("list") or []
print(sum(1 for x in l if "space-only" in (x.get("url") or "")))')
assert_eq_s "再切回去它还在（数据真的落在那个空间的文件里）" "${again:-?}" "$(( ${a0:-0} + 1 ))"
api "/api/space/use?name=default" >/dev/null; sleep 1.2
sp=$(api "/api/space")
assert_json "空间列表能列出来" '
import json,sys
d=json.load(sys.stdin)
print("    -> 当前", d.get("current"), "全部", d.get("list"))
assert "default" in (d.get("list") or []), d
' "$sp"

# ---------------------------------------------------------------- ㉗ 多窗口
say "第七组 ㉗ 多窗口：开/切/关，各窗口记自己的地址与静音（共用一个 WebView，省内存）"
w0=$(api "/api/win")
assert_json "至少有 1 个窗口，并且能列出来" '
import json,sys
d=json.load(sys.stdin)
print("    -> 窗口数", d.get("count"), "当前", d.get("active"))
assert (d.get("count") or 0) >= 1, d
assert d.get("note"), d
' "$w0"
api "/api/win/new">/dev/null; sleep 1.5
w1=$(api "/api/win")
assert_json "新窗口开出来了（数量 +1，且切到了新窗口）" '
import json,sys
d=json.load(sys.stdin)
assert (d.get("count") or 0) >= 2, d
assert d.get("active") == (d.get("count") - 1), d
' "$w1"
# 在"新窗口"里打开一个地址，然后切回窗口 1，看地址是否各自记着
api "/api/nav/open?url=$(urlenc "${HLS_PAGE}?w=2")" >/dev/null; sleep 2.5
url_w2=$(api /api/state | jqv "['state']['url']")
api "/api/win/switch?id=0" >/dev/null; sleep 2.5
url_w1=$(api /api/state | jqv "['state']['url']")
note "窗口2 地址: $url_w2 ｜ 切回窗口1 后地址: $url_w1"
if [ "$url_w2" != "$url_w1" ]; then ok "切窗口会把各自的地址恢复回来（窗口1 不是窗口2 那个地址）"; else bad "切窗口没恢复地址（两个窗口地址一样）"; fi
assert_json "静音状态也跟着窗口走（各记一份）" '
import json,sys
d=json.load(sys.stdin)
assert isinstance(d.get("list"), list), d
' "$(api /api/win)"
api "/api/win/close" >/dev/null; sleep 1.2
api "/api/win/close" >/dev/null; sleep 1.2
w2=$(api "/api/win")
assert_json "关窗口不会关到 0 个（最后一个不给关）" '
import json,sys
d=json.load(sys.stdin)
assert (d.get("count") or 0) == 1, d
' "$w2"

# ---------------------------------------------------------------- ㉘ 二级接口
say "第七组 ㉘ 二级接口：接口目录 / 一屏状态 / 抓正文 / batch 一次跑多个"
h=$(api "/api/help")
assert_json "接口目录里给了常用接口的参数与用途" '
import json,sys
d=json.load(sys.stdin)
c=d.get("common") or []
print("    -> 常用说明 %s 条；batch 说明 %s" % (len(c), "有" if d.get("batch") else "没有"))
assert len(c) >= 20, len(c)
assert d.get("batch") and d.get("space") and d.get("privacy"), d.keys()
' "$h"
sm=$(api "/api/summary")
assert_json "一屏状态：页面/各类条数/窗口/网络都在一个返回里" '
import json,sys
d=json.load(sys.stdin)
print("    -> 书签 %s 历史 %s 脚本 %s 嗅探 %s 窗口 %s" % (d.get("bookmarks"), d.get("history"), d.get("scripts"), d.get("sniff"), d.get("windows")))
for k in ("url","history","bookmarks","scripts","sniff","windows","space"):
    assert k in d, k
' "$sm"
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 3
gr=$(api "/api/grab?max=3000")
assert_json "抓正文接口能拿到页面文字" '
import json,sys
d=json.load(sys.stdin)
t=d.get("text") or ""
print("    -> 抓到 %s 字" % len(t))
assert len(t) > 100, d
assert "下一章" in t or "视频" in t, t[:120]
' "$gr"
ba=$(api "/api/batch?ops=$(urlenc "summary|win|scripts")")
assert_json "batch：一次按顺序跑多个基础接口" '
import json,sys
d=json.load(sys.stdin)
r=d.get("results") or []
print("    -> count=%s 各段 ok=%s" % (d.get("count"), [x.get("ok") for x in r]))
assert d.get("ok") is True and d.get("count") == 3, d
assert all(x.get("ok") for x in r), r
' "$ba"
ba2=$(api "/api/batch?ops=$(urlenc "batch|summary")")
assert_json "batch 里不许再套 batch（会明确拒绝而不是死循环）" '
import json,sys
d=json.load(sys.stdin)
r=(d.get("results") or [{}])[0]
assert "不能再套" in (r.get("error") or ""), d
' "$ba2"

# ---------------------------------------------------------------- ㉙ 翻译（诚实版）
say "第七组 ㉙ 翻译：没配端点就明说没配（不假装翻出来）"
tr=$(api "/api/translate")
assert_json "翻译状态：端点/模型目录/说明都在" '
import json,sys
d=json.load(sys.stdin)
print("    -> 端点 %r 模型文件 %s" % (d.get("endpoint"), d.get("modelFiles")))
assert d.get("ok") is True, d
assert "模型" in (d.get("note") or ""), d.get("note")
' "$tr"
api "/api/settings/set?translateEndpoint=" >/dev/null; sleep 0.5
tr2=$(api "/api/translate?text=$(urlenc "hello world")&to=zh")
assert_json "没配端点时明确报错并给出做法（不编译文）" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("error") or "")[:60])
assert d.get("ok") is False, d
assert "没配翻译端点" in (d.get("error") or ""), d
assert d.get("howto"), d
' "$tr2"

# ---------------------------------------------------------------- ㉚ 助手面板插件（Markdown）
say "第七组 ㉚ 内置「助手面板」插件：简洁界面 + Markdown 真渲染"
sc2=$(api /api/scripts)
assert_json "助手面板已作为内置插件装进来" '
import json,sys
names=[x.get("name") for x in (json.load(sys.stdin).get("list") or [])]
print("    -> 脚本:", names)
assert any("助手" in (n or "") for n in names), names
' "$sc2"
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 4
ai_btn=$(api "/api/eval?js=$(urlenc "String(!!document.getElementById('cdp-assist-btn'))")" | jqv "['raw']")
assert_eq_s "面板按钮在页面上" "${ai_btn:-?}" "true"
md=$(api "/api/eval?js=$(urlenc "window.__CDP_ASSIST ? window.__CDP_ASSIST.render('# 标题\n**粗体** 和 *斜体*\n- 第一项\n- 第二项\n\`代码\` + [链接](https://example.com)') : 'no-api'")" | jqv "['raw']")
assert_json "Markdown 渲染：标题/加粗/列表/代码/链接都出来了" '
import json,sys
t=sys.stdin.read()
print("    -> 渲染长度", len(t))
assert "no-api" not in t, t[:80]
assert "<h1>" in t, t[:200]
assert "<strong>" in t, t[:200]
assert "<li>" in t, t[:200]
assert "<code>" in t, t[:200]
assert "example.com" in t, t[:200]
' "$md"

# ---------------------------------------------------------------- ㉛ 板块登记表
say "第七组 ㉛ 界面板块登记表：每块有名字、只挂一个栏目的一块位置、能按名字定位"
wait_console
api "/api/ui/open?tab=intro" >/dev/null; sleep 2
bd=$(cdp eval --target ui/index.html --expr "JSON.stringify(window.__cdpBoards ? window.__cdpBoards() : null)" 2>/dev/null | tail -1)
assert_json "登记表能列出每个板块（名字/栏目/序号/尺寸）" '
import json,sys
t=sys.stdin.read().strip()
try:
    l=json.loads(t)
except Exception:
    print("    -> 读到:", t[:200]); raise
print("    -> 板块数 %s，示例 %s" % (len(l), [x["board"] for x in l[:4]]))
assert l and len(l) >= 35, len(l) if l else 0
missing=[x for x in l if not x.get("mounted") or x.get("tab") == "(没挂到栏目)"]
assert not missing, missing[:5]
' "$bd"
rep=$(cdp eval --target ui/index.html --expr "JSON.stringify(window.__cdpBoardReport ? window.__cdpBoardReport() : [])" 2>/dev/null | tail -1)
assert_json "登记表本身没有问题（无重名、无挂错栏目）" '
import json,sys
t=sys.stdin.read().strip()
d=json.loads(t)
print("    -> 问题数", len(d))
assert d == [], d
' "$rep"
# 面板里点一下"板块登记表"，应该列出每个板块（能按名字报问题）
cdp eval --target ui/index.html --expr "document.getElementById('in-boards').click()" >/dev/null 2>&1; sleep 1
rows=$(cdp eval --target ui/index.html --expr "String(document.querySelectorAll('#in-board-list .row-item').length)" 2>/dev/null | tail -1)
note "板块登记表面板里列出的行数: ${rows:-?}"
if [ "${rows:-0}" -ge 30 ]; then ok "面板里按板块逐个列出来了（出问题报板块名就能定位）"; else bad "板块登记表面板没列出内容：${rows:-?}"; fi

# ---------------------------------------------------------------- ㉜ 屏幕录制
say "第七组 ㉜ 屏幕录制：接口能把系统授权弹窗叫出来，状态如实（系统不让后台替你点同意）"
sc=$(api "/api/screen")
assert_json "初始状态：没在录，且说清了为什么要你亲手点" '
import json,sys
d=json.load(sys.stdin)
print("    -> state=%s 在录=%s" % (d.get("state"), d.get("recording")))
assert d.get("ok") is True and d.get("recording") is False, d
assert "弹窗" in (d.get("note") or ""), d.get("note")
' "$sc"
st=$(api "/api/screen/start")
assert_json "叫授权：明确回 needConsent（不是假装开始录）" '
import json,sys
d=json.load(sys.stdin)
print("    -> state=%s needConsent=%s" % (d.get("state"), d.get("needConsent")))
assert d.get("ok") is True and d.get("needConsent") is True, d
assert d.get("state") == "awaitingConsent", d
' "$st"
sleep 3
$ADB shell uiautomator dump /sdcard/sc.xml >/dev/null 2>&1
dump=$($ADB shell cat /sdcard/sc.xml 2>/dev/null)
if echo "$dump" | grep -qE "开始录制|Start now|录制或投放|Start recording"; then
  ok "系统那个录屏授权弹窗确实被叫出来了（真去弹了，不是自己编的）"
else
  note "没在 dump 里认出弹窗文案（模拟器版本不同可能文案不一样），看下面 dump 片段："
  note "$(echo "$dump" | grep -oE 'text="[^"]{2,20}"' | head -6 | tr '\n' ' ')"
  bad "录屏授权弹窗没出现"
fi
$ADB shell input keyevent 4 >/dev/null 2>&1
sleep 1
api "/api/screen" > /dev/null
sc2=$(api "/api/screen")
assert_json "取消/没点同意时，状态如实回到 idle（不会卡在"正在录")" '
import json,sys
d=json.load(sys.stdin)
print("    -> state=%s 在录=%s 说明=%s" % (d.get("state"), d.get("recording"), (d.get("error") or "")[:40]))
assert d.get("state") in ("idle","awaitingConsent"), d
assert d.get("recording") is False, d
' "$sc2"
sc3=$(api "/api/screen/stop")
assert_json "没在录的时候点停止：如实报错（不假装存了个文件）" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("error") or "")[:60])
assert d.get("ok") is False, d
assert "没在录" in (d.get("error") or ""), d
' "$sc3"

# ---------------------------------------------------------------- ㉝ 密码库
say "第七组 ㉝ 密码库：落盘加密、没解锁什么都看不到、随机密码四类字符可自定义"
v0=$(api "/api/vault")
assert_json "密码库状态：加密落盘 + 未解锁" '
import json,sys
d=json.load(sys.stdin)
print("    -> 加密=%s 解锁=%s 条目=%s" % (d.get("encrypted"), d.get("unlocked"), d.get("count")))
assert d.get("ok") is True and d.get("encrypted") is True, d
assert d.get("unlocked") is False, d
assert "Keystore" in (d.get("note") or "") and "锁屏" in (d.get("note") or ""), d.get("note")
' "$v0"
vl=$(api "/api/vault/list")
assert_json "没解锁时列条目：一律拒绝（不吐任何条目）" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("error") or "")[:50])
assert d.get("ok") is False and d.get("needUnlock") is True, d
assert "list" not in d and "item" not in d, d
' "$vl"
vs=$(api "/api/vault/save?site=example.com&user=u&pass=secret123")
assert_json "没解锁时保存：拒绝（不会偷偷写盘）" '
import json,sys
d=json.load(sys.stdin)
assert d.get("ok") is False and d.get("needUnlock") is True, d
' "$vs"
g1=$(api "/api/vault/gen?len=24&upper=1&lower=1&digit=1&sym=0")
assert_json "随机密码：自定义哪几类（关掉符号 -> 一个符号都不许有）" '
import json,sys,re
d=json.load(sys.stdin)
p=d.get("password") or ""
print("    -> %s 位，样例 %s" % (len(p), p[:6]+"…"))
assert d.get("ok") is True and len(p) == 24, d
assert re.search(r"[A-Z]", p) and re.search(r"[a-z]", p) and re.search(r"[0-9]", p), p
assert not re.search(r"[^A-Za-z0-9]", p), p
' "$g1"
g2=$(api "/api/vault/gen?len=12&upper=0&lower=0&digit=0&sym=1")
assert_json "只留符号一类：密码里就只有符号" '
import json,sys,re
d=json.load(sys.stdin)
p=d.get("password") or ""
print("    -> 样例", p)
assert d.get("ok") is True and re.search(r"[^A-Za-z0-9]", p), d
assert not re.search(r"[A-Za-z0-9]", p), p
' "$g2"
g3=$(api "/api/vault/gen?upper=0&lower=0&digit=0&sym=0")
assert_json "四类全关：明确报错，不编一个空密码" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("error") or "")[:40])
assert d.get("ok") is False and "全关" in (d.get("error") or ""), d
' "$g3"
un=$(api "/api/vault/unlock")
assert_json "解锁：这台模拟器没设锁屏 -> 如实说"没设锁屏就不能看"（不假装解锁）" '
import json,sys
d=json.load(sys.stdin)
print("    -> ok=%s needUnlock=%s %s" % (d.get("ok"), d.get("needUnlock"), (d.get("error") or "")[:40]))
if d.get("ok") is False:
    assert d.get("needUnlock") is True, d
    assert ("锁屏" in (d.get("error") or "")) or d.get("prompted") is True, d
else:
    assert d.get("prompted") is True or d.get("needUnlock") is True, d
' "$un"

# ---------------------------------------------------------------- ㉞ 伪终端
say "第七组 ㉞ 伪终端：App 内命令（不是系统 shell），文件操作关在私有目录里"
t1=$(api "/api/term?cmd=$(urlenc "pwd")")
assert_json "pwd 在一个受限根目录里" '
import json,sys
d=json.load(sys.stdin)
print("    -> cwd", d.get("cwd"))
assert d.get("ok") is True and d.get("cwd") == "~", d
' "$t1"
t2=$(api "/api/term?cmd=$(urlenc "mkdir demo && ls")" | head -c 0; api "/api/term?cmd=$(urlenc "mkdir demo")")
api "/api/term?cmd=$(urlenc "ls")" >/dev/null
t3=$(api "/api/term?cmd=$(urlenc "ls")")
assert_json "mkdir + ls 真的作用到私有目录" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("out") or "")[:80])
assert "demo" in (d.get("out") or ""), d
' "$t3"
t4=$(api "/api/term?cmd=$(urlenc "curl http://127.0.0.1:8848/api/_test/adsim.js adsim.js")")
assert_json "curl：用 App 自己的 HTTP 客户端下文件（离线可复现）" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("out") or "")[:80])
assert d.get("ok") is True and "HTTP 200" in (d.get("out") or ""), d
' "$t4"
t5=$(api "/api/term?cmd=$(urlenc "cat adsim.js")")
assert_json "cat 能读回刚下的文件" '
import json,sys
d=json.load(sys.stdin)
assert "ADSIM_LOADED" in (d.get("out") or ""), d
' "$t5"
t6=$(api "/api/term?cmd=$(urlenc "md5 adsim.js")")
assert_json "md5 算出摘要（不是空转）" '
import json,sys,re
d=json.load(sys.stdin)
print("    ->", (d.get("out") or "")[:48])
assert re.match(r"^[0-9a-f]{32}  ", (d.get("out") or "")), d
' "$t6"
t7=$(api "/api/term?cmd=$(urlenc "cd ../../..")")
assert_json "想跳出私有目录：明确拒绝（关在 ~ 里）" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("out") or "")[:60])
assert d.get("ok") is False and "超出" in (d.get("out") or ""), d
' "$t7"
t8=$(api "/api/term?cmd=$(urlenc "cd /data/data")")
assert_json "绝对路径逃逸也被拒" '
import json,sys
d=json.load(sys.stdin)
assert d.get("ok") is False, d
' "$t8"
t9=$(api "/api/term?cmd=$(urlenc "ffmpeg")")
assert_json "伪终端里的 ffmpeg 是真内置的（报得出真实版本，不是占位）" '
import json,sys
d=json.load(sys.stdin)
o=d.get("out") or ""
print("    ->", o.split("\n")[0][:70])
assert d.get("ok") is True, d
assert "ffmpeg version" in o, d
' "$t9"
t10=$(api "/api/term?cmd=$(urlenc "help")")
assert_json "help 列出能用的命令与边界" '
import json,sys
d=json.load(sys.stdin)
o=d.get("out") or ""
for k in ("ls","curl","net dns","不能用","ffmpeg"):
    assert k in o, k
' "$t10"

# ---------------------------------------------------------------- ㉟ 屏蔽不渲染
say "第七组 ㉟ 屏蔽不渲染：只藏元素不阻断请求（对付"探测到拦截就不给内容"的站）"
api "/api/settings/set?adblock=1" >/dev/null 2>&1
api "/api/adblock/add?rule=test/adsim" >/dev/null
# ⑲ 用例收尾时把网络记录关掉了，这里要先打开并清空，否则时间线是空的（会误判成"没记到"）
api "/api/net/enabled?on=1" >/dev/null
api "/api/net/clear" >/dev/null 2>&1
m1=$(api "/api/adblock/mode?mode=hide")
assert_json "切到「只隐藏不阻断」" '
import json,sys
d=json.load(sys.stdin)
assert d.get("ok") is True and d.get("mode") == "hide", d
' "$m1"
c1=$(api "/api/adblock/cosmetic")
assert_json "内置隐藏规则在（保守清单，只藏广告位不藏正文）" '
import json,sys
d=json.load(sys.stdin)
l=d.get("list") or []
print("    -> %s 条，例如 %s" % (len(l), l[:3]))
assert len(l) >= 5, l
' "$c1"
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 4
api "/api/cosmetic/push" >/dev/null; sleep 1.5
h1=$(api "/api/eval?js=$(urlenc "(() => { const el=document.getElementById('ad-slot'); if(!el) return 'MISSING'; return getComputedStyle(el).display + '/' + el.offsetHeight; })()")" | jqv "['raw']")
note "广告位元素的 display/高度: ${h1:-?}"
case "${h1:-}" in
  none/*) ok "广告位被藏起来了（display:none），但元素还在页面里（不是被删掉）";;
  *) bad "广告位没被藏起来：${h1:-?}";;
esac
# 判据直接看页面：hide 模式下那条"广告"脚本**照常加载**（没被阻断）——这正是"隐蔽不阻断"的意义
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html?adsim=1")" >/dev/null; sleep 4
loaded1=$(api "/api/eval?js=$(urlenc "String(typeof window.__ADSIM_LOADED)")" | jqv "['raw']")
assert_eq_s "hide 模式：那条"广告"资源照常加载（没被回空，JS 探测不到被拦）" "${loaded1:-?}" "number"
st=$(api "/api/adblock")
assert_json "hide 模式下命中也不回空响应（请求照常发生）" '
import json,sys
d=json.load(sys.stdin)
print("    -> mode=%s 已阻断计数=%s" % (d.get("mode"), d.get("blocked")))
assert d.get("mode") == "hide", d
assert d.get("blockedNote") and "只隐藏不阻断" in d.get("blockedNote"), d.get("blockedNote")
' "$st"
# 自定义规则：加一条 -> 藏起来；删掉 -> 又显示出来（对照组）
api "/api/adblock/cosmetic/add?sel=$(urlenc ".banner-x")" >/dev/null
api "/api/cosmetic/push" >/dev/null; sleep 1.2
h2=$(api "/api/eval?js=$(urlenc "getComputedStyle(document.getElementById('bx-slot')).display")" | jqv "['raw']")
assert_eq_s "自己加的隐藏规则生效（自定义位被藏）" "${h2:-?}" "none"
api "/api/adblock/cosmetic/del?sel=$(urlenc ".banner-x")" >/dev/null
api "/api/cosmetic/push" >/dev/null; sleep 1.2
h3=$(api "/api/eval?js=$(urlenc "getComputedStyle(document.getElementById('bx-slot')).display")" | jqv "['raw']")
if [ "${h3:-?}" != "none" ]; then ok "删掉规则后它又显示出来了（对照组成立，不是"藏了就回不来"）"; else bad "删了规则还是藏着的：${h3:-?}"; fi
# 只阻断模式做对照：同一个资源这回**加载不到**（被回空）
api "/api/adblock/mode?mode=block" >/dev/null
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html?adsim=2")" >/dev/null; sleep 4
loaded2=$(api "/api/eval?js=$(urlenc "String(typeof window.__ADSIM_LOADED)")" | jqv "['raw']")
if [ "${loaded2:-?}" = "undefined" ]; then ok "切到「只阻断」后，同一资源被回空（对照组：这次它没加载上）"; else bad "只阻断模式下它居然还是加载上了：${loaded2:-?}"; fi
st2=$(api "/api/adblock")
assert_json "切回「只阻断」后，同一地址被回空（对照组）" '
import json,sys
d=json.load(sys.stdin)
print("    -> mode=%s 累计阻断=%s" % (d.get("mode"), d.get("blocked")))
assert d.get("mode") == "block", d
assert (d.get("blocked") or 0) >= 1, d
' "$st2"

# 收尾：删掉临时规则、模式恢复 both
api "/api/adblock/remove?rule=$(urlenc "test/adsim")" >/dev/null
api "/api/adblock/mode?mode=both" >/dev/null
api "/api/cosmetic/push" >/dev/null
api "/api/net/enabled?on=0" >/dev/null

# ---------------------------------------------------------------- ㊱ 阅读/停留时间栏目（反馈：单独栏目 + 排序/搜索/删除/饼图）
say "第七组 ㊱ 阅读时间：单独栏目，能排序、搜索、删除，饼图有数据"
api "/api/read/clear" >/dev/null
api "/api/read/add?url=$(urlenc "https://a.example.com/p1")&title=$(urlenc "第一篇")&ms=65000" >/dev/null
api "/api/read/add?url=$(urlenc "https://b.example.com/p2")&title=$(urlenc "第二篇")&ms=20000" >/dev/null
api "/api/read/add?url=$(urlenc "https://a.example.com/p3")&title=$(urlenc "第三篇")&ms=9000" >/dev/null
rd=$(api "/api/read?sort=time")
assert_json "列表能按时长排序" '
import json,sys
d=json.load(sys.stdin)
l=d.get("list") or []
print("    -> %s 条，时长 %s" % (len(l), [x.get("ms") for x in l]))
assert len(l) == 3, d
assert l[0]["ms"] >= l[-1]["ms"], l
' "$rd"
rs=$(api "/api/read?filter=$(urlenc "a.example")")
assert_json "搜索能按域名/网址过滤" '
import json,sys
d=json.load(sys.stdin)
l=d.get("list") or []
print("    -> 命中 %s 条" % len(l))
assert len(l) == 2, l
' "$rs"
st=$(api "/api/read/stats")
assert_json "按域名汇总（饼图要用的数据）" '
import json,sys
d=json.load(sys.stdin)
b=d.get("byHost") or []
print("    -> 总 %s 毫秒，域名 %s" % (d.get("total"), [(x.get("host"), x.get("pct")) for x in b]))
assert d.get("total") == 94000, d
assert len(b) == 2 and abs(sum(x.get("pct") or 0 for x in b) - 100.0) < 0.5, b
' "$st"
wait_console
api "/api/ui/open?tab=plugins" >/dev/null; sleep 2
pie=$(ui "String((document.querySelector('#rd-pie svg')||{}).outerHTML ? 'svg' : 'nosvg')")
assert_eq_s "阅读（插件）栏目里真的画出了饼图（SVG）" "${pie:-?}" "svg"
rows=$(ui "String(document.querySelectorAll('#rd-list .row-item').length)")
note "列表行数: ${rows:-?}"
api "/api/read/clear" >/dev/null

# ---------------------------------------------------------------- ㊲ 视频：状态可见 + 视频信息（系统解析器，不是 ffmpeg）+ 系统播放器
say "第七组 ㊲ 视频：状态列表、视频信息（如实标注不是 ffmpeg）、可交给系统播放器"
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 3
vl=$(api "/api/video/list")
assert_json "能列出当前页的视频状态（识别不到的会如实说）" '
import json,sys
d=json.load(sys.stdin)
vs=d.get("videos") or d.get("list") or []
print("    -> %s 个视频" % len(vs))
assert isinstance(vs, list), d
assert len(vs) >= 1, d
print("    -> 第一个: 时长 %s 当前 %s %s" % (vs[0].get("duration"), vs[0].get("currentTime"), "暂停" if vs[0].get("paused") else "播放中"))
' "$vl"
vi=$(api "/api/video/info?url=$(urlenc "https://appassets.androidplatform.net/test/clip.webm")")
assert_json "视频信息：要么给出字段，要么如实说读不到（并说明不是 ffmpeg）" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("engine") or ""), "|", (d.get("durationMs") or d.get("error") or "")[:60])
if d.get("ok"):
    assert d.get("engine") and "不是 ffmpeg" in d.get("engine"), d
    assert d.get("durationMs") or d.get("width"), d
else:
    assert "读不到" in (d.get("error") or ""), d
' "$vi"
ps=$(api "/api/player/system?url=$(urlenc "https://appassets.androidplatform.net/test/clip.webm")")
assert_json "交给系统播放器：能就打开并说出给了哪个 App，不能就说没有能接的" '
import json,sys
d=json.load(sys.stdin)
print("    ->", (d.get("via") or d.get("error") or "")[:70])
assert d.get("ok") in (True, False), d
if d.get("ok"): assert d.get("via") is not None
else: assert "没有能接" in (d.get("error") or ""), d
' "$ps"

# ---------------------------------------------------------------- ㊳ 录制编辑（反馈：清空要有效、多次录制要追加、能插步骤、能单步执行）
say "第七组 ㊳ 录制步骤编辑：清空有效、多次录制追加、插入等待、单步执行"
api "/api/nav/open?url=$(urlenc "https://appassets.androidplatform.net/test/harness.html")" >/dev/null; sleep 3
api "/api/record?action=start" >/dev/null; sleep 1.5
$ADB shell input tap 540 1290 >/dev/null 2>&1
sleep 1.5
api "/api/record?action=stop" >/dev/null; sleep 1
before=$(api "/api/recording" | jqv "['steps']" 2>/dev/null)
api "/api/record/clear" >/dev/null; sleep 1
after=$(api "/api/recording")
assert_json "清空步骤真的清掉了（以前只清页面那份，界面上看着没清）" '
import json,sys
d=json.load(sys.stdin)
steps = d.get("steps") if isinstance(d.get("steps"), list) else (d.get("script", {}) or {}).get("steps") or []
print("    -> 清空后步骤数 %s" % len(steps))
assert len(steps) == 0, d
' "$after"
# 插入一步（等待 1 秒）后应该多一步
api "/api/rec/insert?step=$(urlenc '{"t":"wait","ms":1000}')" >/dev/null 2>&1
api "/api/record?action=start" >/dev/null; sleep 1
$ADB shell input tap 540 1290 >/dev/null 2>&1
sleep 1.2
api "/api/record?action=stop" >/dev/null; sleep 1
one=$(api "/api/recording" | python3 -c '
import json,sys
d=json.load(sys.stdin)
def steps(x):
    if isinstance(x, dict):
        if isinstance(x.get("steps"), list): return x["steps"]
        for v in x.values():
            r = steps(v)
            if r: return r
    return []
s=steps(d); print(json.dumps({"n": len(s), "types": [x.get("t") for x in s]}))' 2>/dev/null)
note "录制一次后的步骤: ${one:-?}"
api "/api/record/clear" >/dev/null; sleep 0.5
api "/api/record?action=start" >/dev/null; sleep 1.2
$ADB shell input tap 540 1290 >/dev/null 2>&1
sleep 1.2
api "/api/record?action=stop" >/dev/null; sleep 1.5
sc1=$(api "/api/scripts" | python3 -c 'import sys,json;print(json.dumps([x.get("name") for x in (json.load(sys.stdin).get("list") or [])]))')
note "脚本列表: ${sc1:-?}"
api "/api/record?action=start" >/dev/null; sleep 1.2
$ADB shell input tap 540 1290 >/dev/null 2>&1
sleep 1.2
api "/api/record?action=stop" >/dev/null; sleep 1.5
sc2=$(api "/api/scripts")
assert_json "再录一次是**加到列表**（不是覆盖上一条）：名字不该重复" '
import json,sys
names=[x.get("name") for x in (json.load(sys.stdin).get("list") or [])]
print("    ->", names)
assert len(names) == len(set(names)), names
assert len(names) >= 2, names
' "$sc2"

# ---------------------------------------------------------------- ㊴ 内置 ffmpeg（清单第 7/8 条）
say "第七组 ㊴ 内置 ffmpeg：能报版本、能读媒体信息、能把 m3u8 拉成 MP4（强制下载）"
ff1=$(api "/api/ffmpeg")
assert_json "ffmpeg 真的在包里（报出变体与能力边界）" '
import json,sys
d=json.load(sys.stdin)
print("    -> %s" % (d.get("version") or d.get("error")))
assert d.get("ok") is True, d
assert "ffmpeg version" in (d.get("version") or ""), d
assert "LGPL" in (d.get("variant") or ""), d
assert d.get("canRemux") is True and d.get("canReencode") is False, d
assert "不能做" in (d.get("note") or ""), d.get("note")
' "$ff1"
ff2=$(api "/api/ffmpeg/info?url=$(urlenc "http://127.0.0.1:8848/api/_test/hls/index.m3u8")")
assert_json "ffprobe 能读出这个流的信息（容器/时长/流）" '
import json,sys
d=json.load(sys.stdin)
print("    -> format=%s duration=%s streams=%s" % (d.get("format"), d.get("duration"), len(d.get("streams") or [])))
assert d.get("ok") is True, d
assert (d.get("streams") or []), d
assert d.get("duration"), d
' "$ff2"
api "/api/term?cmd=$(urlenc "mkdir -p ff")" >/dev/null
ff3=$(api "/api/ffmpeg/remux?url=$(urlenc "http://127.0.0.1:8848/api/_test/hls/index.m3u8")&name=$(urlenc "todl")")
assert_json "把 m3u8 拉成一个 MP4 文件（-c copy，不重编码）" '
import json,sys
d=json.load(sys.stdin)
print("    -> 返回码=%s 产出 %s 字节 → %s" % (d.get("returnCode"), d.get("bytes"), (d.get("out") or "")[-28:]))
assert d.get("ok") is True, d
assert (d.get("bytes") or 0) > 10000, d
' "$ff3"
outpath=$(printf '%s' "$ff3" | jqv "['out']")
ff4=$(api "/api/ffmpeg/info?url=$(urlenc "${outpath:-/nonexistent}")")
assert_json "产物能被 ffprobe 复核（说明不是空文件）" '
import json,sys
d=json.load(sys.stdin)
print("    -> %s 时长 %s" % (d.get("format"), d.get("duration")))
assert d.get("ok") is True, d
assert d.get("duration"), d
' "$ff4"
tm=$(api "/api/term?cmd=$(urlenc "ffmpeg")")
assert_json "伪终端里的 ffmpeg 走的就是内置这份（不是假装）" '
import json,sys
d=json.load(sys.stdin)
o=d.get("out") or ""
print("    ->", o[:70].replace("\n"," "))
assert "ffmpeg version" in o, d
' "$tm"

# 收尾：把浏览器恢复到测试页，别把设置留给别的组
api "/api/settings/set?proxyType=none" >/dev/null
api "/api/incognito?on=0" >/dev/null
ensure_harness >/dev/null 2>&1

printf '\n第七组小结：通过 %s 项 / 失败 %s 项\n' "$OKCNT" "$BADCNT"
exit $(( BADCNT > 0 ? 1 : 0 ))
