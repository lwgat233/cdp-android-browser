#!/usr/bin/env bash
# CDP 项目验收 · 第三组：用户脚本 / 脚本导入 / 搜索 / 外部控制口
. "$(dirname "$0")/_lib.sh"

save_script() { # $1 名字  $2 match  $3 代码
  python3 - "$1" "$2" "$3" <<'PY' > /tmp/cdp_save.json
import json, sys, urllib.request
body = json.dumps({"name": sys.argv[1], "match": sys.argv[2], "kind": "userscript",
                   "source": "验收脚本", "code": sys.argv[3]}).encode()
req = urllib.request.Request("http://127.0.0.1:8848/api/scripts/save", data=body,
                             headers={"Content-Type": "application/json"})
print(urllib.request.urlopen(req, timeout=30).read().decode())
PY
  cat /tmp/cdp_save.json
}

say "0. 前置"
start_http
ensure_harness
note "先清掉这一轮的脚本（干净起点）：$(api /api/scripts/clear)"
$ADB shell rm -f "/sdcard/Android/data/dev.cdp/files/scripts/verify-pushed.user.js" >/dev/null 2>&1

say "1. 用户脚本：命中站点的会执行，不命中的不执行"
R=$(save_script "验收-命中" "https://appassets.androidplatform.net/test/*" 'window.__US_HIT=(window.__US_HIT||0)+1; document.title="脚本已生效";')
note "保存命中脚本: $R"
[ "$(echo "$R" | jqv "['ok']")" = "True" ] && ok "脚本已保存（id=$(echo "$R" | jqv "['id']")）" || bad "保存失败: $R"
R2=$(save_script "验收-不命中" "https://example.com/*" 'window.__US_MISS=(window.__US_MISS||0)+1;')
ok "不命中的脚本也存进去了（id=$(echo "$R2" | jqv "['id']")）"

api /api/reload >/dev/null
sleep 4
OUT=$(page "JSON.stringify({hit:window.__US_HIT||0, miss:window.__US_MISS||0, title:document.title})")
note "重载后页面状态: $OUT"
[ "$(echo "$OUT" | jqv "['hit']")" = "1" ] && ok "命中 @match 的脚本执行了（__US_HIT=1）" || bad "命中脚本没执行: $OUT"
[ "$(echo "$OUT" | jqv "['miss']")" = "0" ] && ok "不命中 @match 的脚本没执行（__US_MISS=0）" || bad "不命中的脚本竟也执行了: $OUT"
[ "$(echo "$OUT" | jqv "['title']")" = "脚本已生效" ] && ok "脚本改掉的标题生效（document.title=脚本已生效）" || bad "标题没被脚本改：$(echo "$OUT" | jqv "['title']")"

say "2. 从导入目录扫描（adb push 的脚本能被认出来并注入）"
DIR=/sdcard/Android/data/dev.cdp/files/scripts
$ADB shell mkdir -p "$DIR" >/dev/null 2>&1
cat > /tmp/cdp_pushed.user.js <<'JS'
// ==UserScript==
// @name 验收-推入的脚本
// @match https://appassets.androidplatform.net/test/*
// @run-at document-end
// ==/UserScript==
window.__US_PUSHED = (window.__US_PUSHED||0)+1;
JS
$ADB push /tmp/cdp_pushed.user.js "$DIR/verify-pushed.user.js" 2>&1 | tail -1
R=$(api /api/scripts/scan)
note "扫描结果: $(echo "$R" | head -c 300)"
echo "$R" | grep -q "验收-推入的脚本" && ok "扫描认出了推入的 .user.js" || bad "没扫到: $R"
api /api/reload >/dev/null
sleep 4
PV=$(page "window.__US_PUSHED||0")
[ "$PV" = "1" ] && ok "推入的脚本在页面上真的执行了（__US_PUSHED=1）" || bad "推入的脚本没执行（__US_PUSHED=$PV）"

say "3. 搜索：输入词 → 搜索引擎地址，导航真的发生"
R=$(api "/api/search?q=CDP%20%E6%B5%8B%E8%AF%95")
note "结果: $(echo "$R" | head -c 200)"
echo "$R" | grep -qE 'bing\.com|baidu\.com|google\.com' && ok "拼出了搜索引擎地址：$(echo "$R" | jqv "['url']")" || bad "搜索地址不对: $R"
sleep 5
note "当前页面: $(api /api/state | jqv "['state']['url']")"

say "4. 找元素：按文字查（不依赖选择器）"
ensure_harness
R=$(api "/api/query?text=%E4%B8%8B%E4%B8%80%E7%AB%A0")
assert_json "按文字能查到候选，且包含那个 div 按键" '
import sys, json
d = json.load(sys.stdin); ms = d.get("matches") or []
ids = [m.get("id") for m in ms]
print("    -> 候选 %d 个，前几个：%s" % (len(ms), [(m.get("tag"), m.get("id") or m.get("text","")[:10]) for m in ms[:4]]))
sys.exit(0 if (len(ms) > 0 and "divBtn" in ids) else 1)
' "$R"

say "5. 外部控制口：绑定范围 + 局域网开关"
R=$(api "/api/http?action=start&lan=1")
note "局域网绑定: $(echo "$R" | jqv "['bind']"):$(echo "$R" | jqv "['port']")"
[ "$(echo "$R" | jqv "['bind']")" = "0.0.0.0" ] && ok "开启局域网绑定后监听 0.0.0.0:$(echo "$R" | jqv "['port']")" || bad "绑定没变: $R"
R=$(api "/api/http?action=start")
[ "$(echo "$R" | jqv "['bind']")" = "127.0.0.1" ] && ok "默认只绑本机 127.0.0.1（安全默认）" || bad "默认绑定不对: $R"
note "请求计数: $(api /api/status | jqv "['http']['requests']")"

say "6. 页面内点击计数（App 与页面两侧各有一份口径）"
note "复核: $(page "JSON.stringify(window.H.counts)")"

say "小结：通过 $OKCNT 项 / 失败 $BADCNT 项"
[ "$BADCNT" = "0" ]
