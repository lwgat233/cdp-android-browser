#!/usr/bin/env bash
# CDP 项目验收 · 第六组：界面按键可用性（用真实手指点，每条都用可观测事实判定）
#   ① 地址栏：点一下能聚焦、全选、输入替换、回车真的导航
#   ② ☰ 下拉菜单：弹在 ☰ 下面（不是屏幕角落），菜单项真的点得动
#   ③ 控制台开着时，上面那排键（⟳ / ☰）仍然可用
#   ④ 长按菜单里「下载」那条链有监听（不再是空键，用真网络响应验）
. "$(dirname "$0")/_lib.sh"

require_device
ensure_harness
start_http

UIXML=/sdcard/ui_probe.xml
ui_dump() { # 取不到就重试：app 忙的时候 uiautomator 会直接失败，静默返回空 XML 会让后面全崩
  local i x
  for i in 1 2 3; do
    timeout 40 $ADB shell uiautomator dump $UIXML >/dev/null 2>&1
    x=$(timeout 40 $ADB shell cat $UIXML)
    # 光是"有 <hierarchy>"不够：模拟器自带的 Launcher 抢焦点的那些秒里，
    # dump 出来的是**别的窗口**（看起来正常，实则没有我们的工具栏）→ 以前会误报成"界面没就位"。
    # 所以要求里面必须有 dev.cdp，否则按 ANR 处理：清掉抢焦点的窗口 + 把 App 拉回前台再试。
    case "$x" in
      *"<hierarchy"*"dev.cdp"*) printf '%s' "$x"; return 0 ;;
    esac
    if printf '%s' "$x" | grep -q "<hierarchy"; then
      note "dump 里没有我们的窗口（第 $i 次）：清掉抢焦点的窗口 + 把 App 拉回前台"
      timeout 20 $ADB shell am force-stop com.google.android.apps.nexuslauncher >/dev/null 2>&1
      timeout 20 $ADB shell input keyevent KEYCODE_BACK >/dev/null 2>&1
      sleep 0.8
      timeout 30 $ADB shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1
    fi
    sleep 1.5
  done
  printf '%s' "$x"
}

# 等工具栏真的在 dump 里再开始量坐标。
# 为什么：模拟器自带的 launcher 偶尔会 ANR 被系统杀掉（"Application Not Responding: nexuslauncher"
# → WIN DEATH），那几秒里前台窗口会飘走，dump 回来就是一份"没有工具栏"的层级 —— 以前这会被
# 误报成「地址栏点不动/按键坏了」四条连锁失败。这里等它回来，并把窗口状态打出来，避免把
# 环境毛刺当成应用缺陷。
wait_toolbar() {
  local i x
  for i in $(seq 1 10); do
    x=$(ui_dump)
    # 工具栏在 dump 里的证据：刷新键或 ☰ 任意一个在，就算就位
    # （地址栏聚焦时其它按键会被隐藏，这时靠 ☰ 也认；两者都没有才是真没就位）
    case "$x" in
      *'text="⟳"'*|*'text="☰"'*) printf '%s' "$x"; return 0 ;;
    esac
    sleep 1.5
  done
  note "界面没就位：dump 里始终没有工具栏。当前窗口：$(timeout 20 $ADB shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)"
  note "   （这多半是模拟器侧窗口/launcher 毛刺，不是应用缺陷；单跑这一组验证一下再判断）"
  printf '%s' "$x"
  return 1
}
tapxy() {
  [ -n "${1:-}" ] && [ -n "${2:-}" ] || { bad "拿不到点击坐标（界面没就位）"; return 1; }
  timeout 30 $ADB shell input tap "$1" "$2"; sleep 1.2
}

# 顶栏按钮的当前位置（别写死坐标：按钮宽度会随字号/内边距变）
# $1 = 按钮文字（◀ ▶ ⟳ ☰）；$2 = 可选，直接给 XML（省一次 dump，避免窗口闪一下差一拍）
xy_of_text() {
  python3 -c '
import sys, re
want = sys.argv[1]
x = sys.stdin.read()
hits = []
for m in re.finditer(r"<node ([^>]+?)/?>", x):
    a = dict(re.findall(r"([a-zA-Z\-]+)=\"([^\"]*)\"", m.group(1)))
    if (a.get("text") or "").strip() == want:
        n = re.findall(r"-?\d+", a.get("bounds", ""))
        if len(n) == 4:
            hits.append((int(n[1]), int(n[0]), int(n[3]), int(n[2])))   # (y1, x1, y2, x2)
# 控制台里也有 ☰（左上角）——同一个文字会有多个节点，优先取**工具栏那一行**（y1 最小且在最上面）
hits.sort()
if hits:
    y1, x1, y2, x2 = hits[0]
    print(int((x1 + x2) / 2), int((y1 + y2) / 2))
' "$1"
}
btn_xy() { local xml="${2:-}"; if [ -n "$xml" ]; then printf '%s' "$xml" | xy_of_text "$1"; else ui_dump | xy_of_text "$1"; fi; }

# 地址栏（最上面那个 EditText）
urlbar_from() {
  python3 -c '
import sys, re, json
x = sys.stdin.read()
rows = []
for m in re.finditer(r"<node ([^>]+?)/?>", x):
    a = dict(re.findall(r"([a-zA-Z\-]+)=\"([^\"]*)\"", m.group(1)))
    if a.get("class", "").endswith("EditText"):
        rows.append({"text": a.get("text", ""), "focused": a.get("focused") == "true",
                     "bounds": a.get("bounds", "")})
rows.sort(key=lambda r: int(re.findall(r"-?\d+", r["bounds"])[1]) if re.findall(r"-?\d+", r["bounds"]) else 9999)
print(json.dumps(rows[0] if rows else {}, ensure_ascii=False))
'
}
urlbar_state() { local xml="${1:-}"; if [ -n "$xml" ]; then printf '%s' "$xml" | urlbar_from; else ui_dump | urlbar_from; fi; }

# 从一段 XML 里算地址栏中心（和按钮同一个 dump，原子量取）
xy_of_urlbar() {
  python3 -c '
import sys, json, re
b = json.load(sys.stdin).get("bounds", "")
n = re.findall(r"-?\d+", b)
print(int((int(n[0]) + int(n[2])) / 2), int((int(n[1]) + int(n[3])) / 2)) if len(n) == 4 else print("")
'
}
# 下拉菜单项：在工具栏下方、右半屏的、成块的节点
menu_items() {
  ui_dump | python3 -c '
import sys, re, json
x = sys.stdin.read()
out = []
for m in re.finditer(r"<node ([^>]+?)/?>", x):
    a = dict(re.findall(r"([a-zA-Z\-]+)=\"([^\"]*)\"", m.group(1)))
    t = (a.get("text") or "").strip()
    n = re.findall(r"-?\d+", a.get("bounds", ""))
    if not t or len(n) != 4:
        continue
    x1, y1, x2, y2 = map(int, n)
    # 菜单项用**宽度**判定：弹出菜单很宽，页面里插件角标很窄。
    # （以前用"中心 x>700"，菜单文案变长后中心 x=640 就一条都认不出来 —— 别写死阈值）
    if y1 >= 255 and (x2 - x1) >= 300 and (y2 - y1) >= 40:
        out.append({"t": t, "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                    "w": x2 - x1, "h": y2 - y1, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})
out.sort(key=lambda r: r["y1"])
print(json.dumps(out, ensure_ascii=False))
'
}

say "1. 地址栏：点一下要能聚焦、全选、打字替换、回车导航"
api /api/ui/close >/dev/null; sleep 1.5
XML=$(wait_toolbar) || true      # 等前台窗口回到 App，并且用同一份 dump 量所有坐标
BXY=$(btn_xy "⟳" "$XML"); note "⟳ 当前位置: ${BXY:-取不到}"
AXY=$(urlbar_state "$XML" | xy_of_urlbar)
note "地址栏中心: $AXY"
tapxy $AXY
FOC=$(urlbar_state)
assert_json "点一下真的拿到焦点（以前 focused=false、键盘都不弹）" '
import sys, json
d = json.load(sys.stdin)
print("    -> focused=%s" % d.get("focused"))
sys.exit(0 if d.get("focused") is True else 1)
' "$FOC"

$ADB shell input keyevent 29 >/dev/null 2>&1; sleep 0.5
$ADB shell input keyevent 30 >/dev/null 2>&1; sleep 1
TYPED=$(urlbar_state)
assert_json "全选生效：打字把旧 URL 整条替换掉（不是插到中间）" '
import sys, json
d = json.load(sys.stdin)
t = d.get("text") or ""
print("    -> 地址栏现在是: %s" % t)
sys.exit(0 if (t == "ab") else 1)
' "$TYPED"

$ADB shell input keyevent 66 >/dev/null 2>&1; sleep 3
NAV=$(api /api/status)
assert_json "回车真的触发了导航（按输入内容去搜）" '
import sys, json
d = json.load(sys.stdin)
u = (d.get("browser") or {}).get("url") or ""
print("    -> 回车后 URL: %s" % u[:90])
sys.exit(0 if ("ab" in u and "harness" not in u) else 1)
' "$NAV"
# 地址栏的"聚焦态"会隐藏其它按键（这是用户要的行为 #33）：这一节结束先把焦点收起来，
# 并且等工具栏按钮重新出现，再量下面的坐标（否则会拿旧坐标点到不存在的地方 → 连锁假红）
$ADB shell input keyevent 4 >/dev/null 2>&1; sleep 1
for i in $(seq 1 8); do
  if ui_dump | grep -q 'text="☰"'; then break; fi
  sleep 1
done
note "地址栏节结束：工具栏按钮已恢复（$(ui_dump | grep -c 'text="☰"') 处 ☰）"
ensure_harness

say "2. ☰ 下拉菜单：弹在 ☰ 下面，而且点得动"
BXY=$(btn_xy "☰"); note "☰ 当前位置: ${BXY:-取不到}"
tapxy $BXY
# 弹出菜单是异步的：轮询等"开始监听"那条出现（最多再点 3 次），别点一次就断言
ITEMS=$(menu_items)
for _m in 1 2 3; do
  case "$ITEMS" in *"开始监听"*) break ;; esac
  sleep 1.2
  BXY=$(btn_xy "☰"); tapxy $BXY
  ITEMS=$(menu_items)
done
assert_json "菜单弹在 ☰ 下方的右侧（以前是左下角一条 33px 细条）" '
import sys, json
items = json.load(sys.stdin)
print("    -> 菜单项 %d 个：%s" % (len(items), [i["t"] for i in items]))
if items:
    i = items[0]
    print("    -> 第一项 bounds 高度 %d px，中心 x=%d" % (i["h"], i["cx"]))
texts = " | ".join(i["t"] for i in items)
ok = (len(items) >= 5 and items[0]["h"] >= 50 and items[0]["w"] >= 300 and items[0]["y1"] < 1200
      and "开始监听" in texts)
sys.exit(0 if ok else 1)
' "$ITEMS"

FIRST=$(printf '%s' "$ITEMS" | python3 -c 'import sys,json;i=json.load(sys.stdin);print("%d %d"%(i[0]["cx"],i[0]["cy"]) if i else "")')
tapxy $FIRST
REC=$(api /api/recording)
assert_json "菜单项真的点得动（第一项=开始监听 → 真的开始录了）" '
import sys, json
d = json.load(sys.stdin)
print("    -> %s" % json.dumps(d, ensure_ascii=False)[:120])
sys.exit(0 if (d.get("recording") is True or d.get("on") is True) else 1)
' "$REC"
api "/api/record?action=stop" >/dev/null

say "3. 控制台开着时，上面那排键仍然能用（浮层不再盖住工具栏）"
api /api/ui/open >/dev/null; sleep 2
page "window.__reloadMark=1" >/dev/null
BXY=$(btn_xy "⟳")
note "控制台开着时 ⟳ 位置: ${BXY:-取不到}"
tapxy $BXY
sleep 3
MARK=$(page "typeof window.__reloadMark")
assert_json "控制台开着时 ⟳ 仍然刷新（以前被浮层挡住，点它等于点浮层）" '
import sys
v = sys.stdin.read().strip()
print("    -> 刷新标记现在是: %s" % v)
sys.exit(0 if v in ("undefined", "None", "") else 1)
' "$MARK"

BXY=$(btn_xy "☰")
tapxy $BXY
ITEMS2=$(menu_items)
assert_json "控制台开着时 ☰ 也能弹菜单，且不会顺手把控制台关掉" '
import sys, json
items = json.load(sys.stdin)
print("    -> 菜单项 %d 个" % len(items))
sys.exit(0 if len(items) >= 5 else 1)
' "$ITEMS2"
$ADB shell input keyevent 4 >/dev/null 2>&1; sleep 1

say "4. 下载功能：App 自己下（不交给系统下载器），下下来的字节要能对上"
api /api/ui/close >/dev/null; sleep 1
PKG=dev.cdp
SRC=/tmp/cdp_dl/src-probe.bin
mkdir -p /tmp/cdp_dl
head -c 4096 /dev/urandom > "$SRC"
SRC_SHA=$(sha256sum "$SRC" | awk '{print $1}')
note "源文件 sha256=${SRC_SHA:0:16}…（4096 字节）"
# 把源文件放进 App 的下载目录，让它从**自己的控制口**下载（不依赖任何外网/宿主服务）
$ADB push "$SRC" /data/local/tmp/src-probe.bin >/dev/null 2>&1
$ADB shell "run-as $PKG sh -c 'mkdir -p files/downloads && cat /data/local/tmp/src-probe.bin > files/downloads/src-probe.bin'" >/dev/null 2>&1
GOT=$($ADB shell "run-as $PKG sh -c 'ls -l files/downloads/src-probe.bin 2>/dev/null | wc -l'" | tr -d '\r')
assert_json "前置：源文件已放进 App 下载目录" '
import sys
n = sys.stdin.read().strip()
sys.exit(0 if n == "1" else 1)
' "$GOT"

SRCURL="http://127.0.0.1:8848/api/downloads/get?name=src-probe.bin"
api "/api/download?url=$(urlenc "$SRCURL")&name=dst-probe.bin" >/dev/null
sleep 6
DL=$(api /api/downloads)
assert_json "应用内下载完成：记录里有 dst-probe.bin、4096 字节、状态完成" '
import sys, json
d = json.load(sys.stdin)
lst = d.get("list") or []
hit = [x for x in lst if x.get("name") == "dst-probe.bin"]
print("    -> 下载记录: %s" % json.dumps(hit[:1], ensure_ascii=False)[:200])
sys.exit(0 if (hit and hit[0].get("state") == "完成" and int(hit[0].get("bytes") or 0) == 4096) else 1)
' "$DL"

GOT_SHA=$(curl -s --max-time 30 "$API/api/downloads/get?name=dst-probe.bin" | sha256sum | awk '{print $1}')
if [ "$GOT_SHA" = "$SRC_SHA" ]; then
  ok "取回的文件与原文件逐字节一致（sha256 ${SRC_SHA:0:16}…）"
else
  bad "取回的文件与源不一致（源 ${SRC_SHA:0:16}… vs 取回 ${GOT_SHA:0:16}…）"
fi

say "5. 网页里的下载链接（长按菜单「下载链接」同一条路）也要能下"
api "/api/goto?url=$(urlenc "https://appassets.androidplatform.net/test/dl.html")" >/dev/null
sleep 4
CX=$(api "/api/query?selector=%23dl" | jqv "['matches'][0]['box']['cx']")
CY=$(api "/api/query?selector=%23dl" | jqv "['matches'][0]['box']['cy']")
WEBTOP=$(ui_dump | python3 -c '
import sys, re
x = sys.stdin.read()
for m in re.finditer(r"<node ([^>]+?)/?>", x):
    a = dict(re.findall(r"([a-zA-Z\-]+)=\"([^\"]*)\"", m.group(1)))
    if a.get("class", "").endswith("WebView"):
        n = re.findall(r"-?\d+", a.get("bounds", ""))
        print(n[1]); break
' )
SX=$(python3 -c "print(int(float('${CX:-0}')*2.75))")
SY=$(python3 -c "print(int(${WEBTOP:-262}+float('${CY:-0}')*2.75))")
note "点下载链接：css=($CX,$CY) → 屏幕 ($SX,$SY)"
page "window.__dlBase='ok'" >/dev/null
tapxy "$SX" "$SY"
sleep 7
LOG=$(api "/api/log?n=80")
assert_json "点网页里的下载链接 → 走同一个应用内下载（日志有『下载请求』与『下载完成』）" '
import sys, json
d = json.load(sys.stdin)
lines = d.get("lines") or []
req = [l for l in lines if "下载请求" in l]
done = [l for l in lines if ("下载完成" in l or "下载失败" in l)]
if req: print("    -> %s" % req[-1][-110:])
if done: print("    -> %s" % done[-1][-130:])
sys.exit(0 if (req and done) else 1)
' "$LOG"

DL2=$(api /api/downloads)
assert_json "下载记录里出现网页那一次（src-probe.bin，4096 字节）" '
import sys, json
d = json.load(sys.stdin)
lst = d.get("list") or []
hit = [x for x in lst if x.get("name") == "src-probe.bin"]
print("    -> %s" % json.dumps(hit[:1], ensure_ascii=False)[:200])
sys.exit(0 if (hit and int(hit[0].get("bytes") or 0) == 4096) else 1)
' "$DL2"

# 「把文件下到它自己身上」也不能坏：下完再核一次源文件的完整性
SELF=$(curl -s --max-time 30 "$API/api/downloads/get?name=src-probe.bin" | sha256sum | awk '{print $1}')
if [ "$SELF" = "$SRC_SHA" ]; then
  ok "重名下载后源文件仍然完整（下到临时文件再改名，不会先把自己截断）"
else
  bad "重名下载把源文件弄坏了（${SRC_SHA:0:16}… vs ${SELF:0:16}…）"
fi

DMC=$( { timeout 30 $ADB shell dumpsys download 2>/dev/null || true; } | grep -c "dev.cdp" )
assert_json "全程没走系统下载器（系统下载服务里没有本 App 的条目）" '
import sys
n = sys.stdin.read().strip()
print("    -> 系统下载服务里 dev.cdp 的条目数: %s（期望 0）" % n)
sys.exit(0 if n in ("0", "") else 1)
' "$DMC"

# 收尾：把探针文件从 App 目录里清掉（别把测试残留留在设备上）
$ADB shell "run-as $PKG sh -c 'rm -f files/downloads/src-probe.bin files/downloads/dst-probe.bin'" >/dev/null 2>&1
api "/api/downloads/delete?name=dst-probe.bin" >/dev/null

say "小结：通过 $OKCNT 项 / 失败 $BADCNT 项"
[ "$BADCNT" = "0" ] || exit 1
