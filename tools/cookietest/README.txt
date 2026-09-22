Cookie 属性的验收工具（本项目自带，跟证据 47/48 配套）
=====================================================

serve.py          本地测试页（宿主跑）：一次下发 5 条不同属性的 cookie，用来验"属性读得对不对"
                    hocookie    = 有 Max-Age + HttpOnly + SameSite=Lax
                    sec_cookie  = Secure（http 页面上浏览器本来就不收，用于确认这一点）
                    plain       = 只有 Max-Age
                    session_only= 无 Max-Age（会话 cookie）
                    plus_attr   = HttpOnly + Secure + SameSite=None
                  重跑：  python3 tools/cookietest/serve.py         # 监听 0.0.0.0:8899

cdp_cookies.mjs   零依赖 CDP 探针（node 内置 WebSocket）：调 Network.getAllCookies 并按域过滤打印。
                  用法： node tools/cookietest/cdp_cookies.mjs <wsUrl> [域过滤]
                  ws 地址先从 /json/version（browser）或 /json/list（page）拿。
                  **必须在 page target 上调** —— browser target 上调同一个方法返回 0 条（实测踩过）。
                  adb forward tcp:9222 localabstract:webview_devtools_remote_$PID

验收脚本（在 source/cdp/tools/ 下，跟别的 verify_* 一套）：
    python3 source/cdp/tools/verify_cookie_attrs.py        # 10 PASS / 0 FAIL

设备侧要点
  · 测试页地址要写 10.0.2.2:8899（模拟器里回环 127.0.0.1 指设备自己；页面走 http 才能看到"Secure 不收"的真实行为）
  · cookie 库在应用私有目录：app_webview/Default/Cookies（伴生 -journal，不是 -wal）
  · 取库里那份做对照：
      adb -s emulator-5554 exec-out run-as dev.cdp cat app_webview/Default/Cookies > /tmp/real.db
      python3 -c "import sqlite3;c=sqlite3.connect('/tmp/real.db');print(list(c.execute(\"select name,is_httponly,has_expires,expires_utc from cookies\")))"

结论（为什么要有这套工具）：见 evidence/47-（实验：库里那份滞后 3 秒 = 0 行、flush 后 1 秒全在；
CookieManager 其实**会**返回 HttpOnly 的值）与 evidence/48-（修复后的 10/10 验收）。
