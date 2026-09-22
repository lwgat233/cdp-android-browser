package dev.cdp

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 极简 HTTP 控制服务：给「电脑/局域网里的脚本」一个直接驱动这个 App 的入口。
 *
 * 和 WebView 自带的 CDP 调试端口（debug 版，adb forward tcp:9222）分工不同：
 *  - CDP 端口：标准协议，能驱动**网页**（DOM/Input/Network 全都有），但需要 adb 转发；
 *  - 这个 HTTP 口：本 App 自己的语义（搜索 / 找元素 / 点 / 录制 / 回放 / 脚本管理），
 *    局域网直连即可，手机上也能 curl。
 *
 * 默认只绑 127.0.0.1（安全），要局域网访问得显式传 lan=1。
 */
class HttpControl(private val act: MainActivity) {

    /** 后置绑定：Bridge 与 HttpControl 互相引用，谁先建都不合适，就把这层关系显式化 */
    @Volatile var bridge: Bridge? = null

    @Volatile private var server: java.net.ServerSocket? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    var port = 8848
        private set
    var lanEnabled = false
        private set
    var requestCount = 0
        private set

    // ---------------------------------------------------------------- 访问控制
    // 默认只绑 127.0.0.1，本机调用免令牌（既有自动化不受影响）；
    // **一旦绑到局域网**，非本机调用就必须带令牌，而且敏感接口默认关闭（要显式打开）。
    // 令牌每次启动随机生成（不给固定口令，重启即失效）。
    @Volatile private var token: String = newToken()

    /** 局域网下是否允许敏感接口（默认关；开之前先想清楚：它等于把这个 App 的隐私数据交出去） */
    @Volatile private var sensitiveLan = false

    /** 被拒次数（安全面板上能看到"有人试过没有"） */
    @Volatile var rejected: Int = 0
        private set

    private fun newToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(24)

    /** 敏感接口前缀：局域网下默认一律拒绝，要显式打开才放行 */
    private val SENSITIVE = listOf(
        "/api/vault", "/api/cookies", "/api/downloads/get", "/api/downloads/delete",
        "/api/history/clear", "/api/history/delete", "/api/settings/set", "/api/eval",
        "/api/export", "/api/space/use", "/api/http", "/api/ai/context", "/api/grab",
        "/api/security/token"
    )

    fun rotateToken(): JSONObject {
        token = newToken()
        return securityStatus()
    }

    fun setSensitiveLan(on: Boolean): JSONObject {
        sensitiveLan = on
        try { bridge?.log(if (on) "控制口：局域网敏感接口已打开（vault/cookie/Eval 等）" else "控制口：局域网敏感接口已关闭") } catch (_: Exception) {}
        return securityStatus()
    }

    fun tokenForLocalOnly(): String = token

    /** 安全面板要的字段（不含令牌本身；令牌只在本机接口与界面里给） */
    fun securityStatus(): JSONObject = JSONObject()
        .put("running", running)
        .put("bind", if (lanEnabled) "0.0.0.0" else "127.0.0.1")
        .put("lan", lanEnabled)
        .put("tokenSet", token.isNotBlank())
        .put("tokenHint", if (token.length > 4) ("…" + token.takeLast(4)) else "")
        .put("sensitiveLan", sensitiveLan)
        .put("rejected", rejected)
        .put(
            "verdict",
            when {
                !running -> "控制口没开"
                !lanEnabled -> "只绑本机：局域网里的设备连不上（最安全）"
                sensitiveLan -> "绑了局域网，且敏感接口已打开 —— 局域网里的任何设备只要拿到令牌就能读 vault/cookie"
                else -> "绑了局域网：带令牌能调普通接口；敏感接口默认关闭"
            }
        )

    /** 这个请求放不放行？放行返回 null，否则返回要回给调用方的错误 */
    private fun authCheck(rawPath: String, isLocal: Boolean, tok: String): JSONObject? {
        val path = rawPath.substringBefore('?')
        if (isLocal) return null                       // 本机（127.0.0.1/::1）免令牌
        if (!lanEnabled) return JSONObject().put("ok", false)
            .put("error", "控制口只绑在本机（127.0.0.1），外部地址到不了这里")
        if (tok.isBlank() || tok != token) return JSONObject().put("ok", false)
            .put("error", "需要的访问令牌没给或不对：URL 上加 ?t=<token>，或加请求头 X-CDP-Token；" +
                "令牌在 App 的「安全与隐身 ▸ 控制口安全」里，也可以在本机 GET /api/security/token 取")
        if (SENSITIVE.any { path.startsWith(it) } && !sensitiveLan) return JSONObject().put("ok", false)
            .put("error", "这是敏感接口（${path}），局域网下默认关闭：在 App 里打开「控制口安全 ▸ 允许局域网调敏感接口」，" +
                "或本机请求 /api/security/sensitive?on=1")
        return null
    }

    fun status(): JSONObject {
        val o = JSONObject()
        o.put("running", running)
        o.put("port", port)
        o.put("bind", if (lanEnabled) "0.0.0.0" else "127.0.0.1")
        o.put("url", "http://127.0.0.1:$port/api/status")
        o.put("requests", requestCount)
        o.put("lan", lanEnabled)
        o.put("tokenRequired", lanEnabled)
        o.put("sensitiveLan", sensitiveLan)
        o.put("rejected", rejected)
        // 界面上的「接口清单」直接吃这份（以前只有 HTTP 路由带 endpoints，控制台里的 op 没有 → 清单是空的）
        o.put("endpoints", ENDPOINTS)
        o.put("endpointCount", ENDPOINTS.size)
        return o
    }

    /**
     * 绑定端口。刚 stop() 完立刻重绑同一端口时，没设 SO_REUSEADDR 会因端口还没释放而失败 ——
     * 现象是「控制口刚起来又没了、过一会儿才好」。这里设上 reuseAddress 并重试几次。
     */
    private fun bind(bindLan: Boolean, wantedPort: Int): java.net.ServerSocket {
        var lastErr: Exception? = null
        for (attempt in 1..5) {
            try {
                val s = java.net.ServerSocket()
                s.reuseAddress = true
                s.bind(
                    java.net.InetSocketAddress(
                        java.net.InetAddress.getByName(if (bindLan) "0.0.0.0" else "127.0.0.1"),
                        wantedPort
                    ), 16
                )
                return s
            } catch (e: Exception) {
                lastErr = e
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            }
        }
        throw lastErr ?: Exception("绑定 $wantedPort 失败")
    }

    fun start(bindLan: Boolean, wantedPort: Int = 8848): JSONObject {
        // 已经在跑的时候：只有「绑定范围 / 端口」真的变了才重启服务。
        // 早先这里是 `if (running) return status()` —— 于是「勾上局域网」永远不生效，
        // 状态里显示的还是 127.0.0.1，看起来像开关坏了。
        if (running) {
            if (bindLan == lanEnabled && port == wantedPort) return status()
            stop()
        }
        lanEnabled = bindLan
        val ss = try {
            bind(bindLan, wantedPort)
        } catch (e: Exception) {
            return JSONObject().put("error", "无法监听 $wantedPort: ${e.message}")
        }
        port = ss.localPort
        server = ss
        running = true
        thread = Thread {
            while (running) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                Thread { handle(c) }.start()
            }
        }.also { it.isDaemon = true; it.name = "cdp-http"; it.start() }
        return status()
    }

    fun stop(): JSONObject {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        return status()
    }

    private fun handle(c: java.net.Socket) {
        try {
            c.soTimeout = 15000
            val inStr = c.getInputStream()

            // 先按**字节**把请求头读到空行为止。
            // 关键：一定要按字节读 —— 按字符读（BufferedReader + Content-Length）时，
            // 请求体里只要有中文这种多字节字符，「字节长度 > 字符数」就会让读取一直等下去，
            // 直到 socket 超时，表现成「POST 没任何回包、curl 报 empty reply」。这个坑实测踩过。
            val headBuf = java.io.ByteArrayOutputStream()
            var b: Int
            var tail = 0
            while (true) {
                b = inStr.read()
                if (b < 0) break
                headBuf.write(b)
                tail = when {
                    tail == 0 && b == '\r'.code -> 1
                    tail == 0 && b == '\n'.code -> 2
                    tail == 1 && b == '\n'.code -> 2
                    tail == 2 && b == '\r'.code -> 3
                    tail == 2 && b == '\n'.code -> 4
                    tail == 3 && b == '\n'.code -> 4
                    else -> 0
                }
                if (tail == 4) break
            }
            val headText = String(headBuf.toByteArray(), Charsets.UTF_8)
            val lines = headText.split("\r\n", "\n")
            if (lines.isEmpty()) return
            val parts = lines[0].split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            var contentLength = 0
            for (i in 1 until lines.size) {
                val lower = lines[i].lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body: String = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val n = try { inStr.read(bytes, off, contentLength - off) } catch (e: Exception) { -1 }
                    if (n <= 0) break
                    off += n
                }
                String(bytes, 0, off, Charsets.UTF_8)
            } else ""

            requestCount++
            val os = c.getOutputStream()

            // ---- 访问控制（本机免令牌；绑局域网后必须带令牌，敏感接口还要显式打开）----
            val remote = try { c.inetAddress?.hostAddress ?: "" } catch (_: Exception) { "" }
            val isLocal = remote == "127.0.0.1" || remote == "0:0:0:0:0:0:0:1" || remote == "::1" || remote == "localhost"
            var tok = ""
            try {
                tok = path.substringAfter('?', "").split("&").firstOrNull { it.startsWith("t=") }
                    ?.substringAfter("t=")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                if (tok.isBlank()) {
                    for (i in 1 until lines.size) {
                        val lower = lines[i].lowercase()
                        if (lower.startsWith("x-cdp-token:")) tok = lines[i].substringAfter(":").trim()
                    }
                }
            } catch (_: Exception) {
            }
            val deny = authCheck(path, isLocal, tok)
            if (deny != null) {
                rejected++
                try { bridge?.log("控制口拒绝了一个请求：remote=$remote path=${path.take(60)}（累计被拒 $rejected）") } catch (_: Exception) {}
                writeJson(os, 403, deny)
                return
            }
            // 令牌只在本机取：外部（哪怕带对了令牌）也不回令牌本身，免得令牌被"转发"出去
            if (path.substringBefore('?').startsWith("/api/security/token")) {
                if (!isLocal) { rejected++; writeJson(os, 403, JSONObject().put("ok", false).put("error", "令牌只能在本机取")); return }
                writeJson(os, 200, JSONObject().put("ok", true).put("token", token).put("hint", "URL 上加 ?t= 或加请求头 X-CDP-Token"))
                return
            }

            // ---- 控制台页面由本机控制口提供（Cache-Control: no-store）----
            // 为什么不让 WebView 直接读 assets：APK 内的 assets 会被 WebView **缓存**，
            // 换了构件（重装 APK）后 <script src="app.js"> 还可能命中旧副本 —— 表现就是
            // "界面代码明明改了，跑起来却没变化"。走这个口子 + no-store 就彻底不吃缓存。
            val qp = path.substringBefore('?')
            if (qp == "/" || qp.startsWith("/ui/")) {
                val rel = if (qp == "/") "ui/index.html" else qp.trimStart('/')
                if (!rel.contains("..")) {
                    try {
                        // ---- 开发覆盖层（改一行 UI 不用重建 APK）----
                        // tools/devpush.sh 把改动过的 ui 文件推到 /sdcard/cdp-dev/ 下；
                        // 这里优先读它，并把来源写进 X-CDP-Source 头 → 验收能证明"跑的是哪一份"。
                        // 注意：不能放 /sdcard/cdp-dev —— Android 11+ 分区存储下应用读不了那里（实测请求直接失败）。
                        // 用应用自己的外部目录，adb push 写得进去、应用也读得到。
                        val devRoot = java.io.File(act.getExternalFilesDir(null), "cdp-dev")
                        val devFile = java.io.File(devRoot, rel)
                        val useDev = devFile.isFile
                        val bytes = if (useDev) devFile.readBytes() else act.assets.open(rel).use { it.readBytes() }
                        val mime = when (rel.substringAfterLast('.', "").lowercase()) {
                            "html" -> "text/html; charset=utf-8"
                            "js" -> "application/javascript; charset=utf-8"
                            "css" -> "text/css; charset=utf-8"
                            "json" -> "application/json; charset=utf-8"
                            "png" -> "image/png"
                            "svg" -> "image/svg+xml"
                            "ico" -> "image/x-icon"
                            "woff2" -> "font/woff2"
                            "ttf" -> "font/ttf"
                            else -> "application/octet-stream"
                        }
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\n" +
                            "Cache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\n" +
                            // 哪一份在生效：dev = 设备上推的那份（刚改的）｜apk = 打进构件的
                            "X-CDP-Source: " + (if (useDev) "dev" else "apk") + "\r\n" +
                            "X-Content-Type-Options: nosniff\r\nContent-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        os.write(head.toByteArray(Charsets.UTF_8))
                        os.write(bytes)
                        os.flush()
                        return
                    } catch (_: Exception) {
                        // 没有这个文件就落回 JSON（下面统一处理）
                    }
                }
            }

            val res = route(path, body)
            if (res.has("__file")) {
                // 直接回文件字节。
                // 注意：以前这里一律发 application/octet-stream + attachment，于是浏览器
                // 对 HTML 也只会「下载」而不是「渲染」——页面 JS 不跑，cookie 写不上、
                // 嗅探抓不到、标题也不变（第八组就是这么抓出来的）。按扩展名给类型，
                // 只有明确要附件的接口（取回下载文件）才加 attachment。
                val f = java.io.File(res.optString("__file"))
                val name = res.optString("__filename")
                val ctype = res.optString("__ctype").ifBlank { ctypeOf(name) }
                val attach = res.optBoolean("__attach", false)
                val head = "HTTP/1.1 200 OK\r\nContent-Type: $ctype\r\n" +
                    (if (attach) "Content-Disposition: attachment; filename=\"$name\"\r\n" else "") +
                    "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                    "Access-Control-Allow-Origin: *\r\nContent-Length: ${f.length()}\r\nConnection: close\r\n\r\n"
                os.write(head.toByteArray(Charsets.UTF_8))
                f.inputStream().use { it.copyTo(os, 64 * 1024) }
                os.flush()
                return
            }
            val bytes = res.toString().toByteArray(Charsets.UTF_8)
            val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                "Access-Control-Allow-Origin: *\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            os.write(resp.toByteArray(Charsets.UTF_8))
            os.write(bytes)
            os.flush()
        } catch (e: Throwable) {
            // 以前这里只 catch Exception 且什么都不记，接口出问题只能靠猜
            try { bridge?.log("HTTP 处理异常: ${e.javaClass.simpleName}: ${e.message}") } catch (_: Exception) {}
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    /** 回一个 JSON 响应（带状态码）：403 这类就用它，别再用 200 包着错误 */
    private fun writeJson(os: java.io.OutputStream, code: Int, o: JSONObject) {
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        val resp = "HTTP/1.1 $code " + (if (code == 403) "Forbidden" else "OK") + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
            "Access-Control-Allow-Origin: *\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        os.write(resp.toByteArray(Charsets.UTF_8))
        os.write(bytes)
        os.flush()
    }

    /** 按扩展名给 Content-Type（回文件时用） */
    private fun ctypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "txt", "md" -> "text/plain; charset=utf-8"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "ts" -> "video/mp2t"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    /** 路由：所有会碰 WebView 的操作都切回主线程，用闩同步等结果（手机那边也不会被这口卡住） */
    private fun route(rawPath: String, body: String): JSONObject {
        val q = rawPath.substringAfter('?', "")
        val path = rawPath.substringBefore('?')
        val p = HashMap<String, String>()
        if (q.isNotEmpty()) for (kv in q.split("&")) {
            if (kv.isEmpty()) continue
            val k = kv.substringBefore('=')
            val v = if (kv.contains('=')) kv.substringAfter('=') else ""
            p[k] = try { java.net.URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v }
        }
        if (body.isNotBlank()) {
            try {
                val b = JSONObject(body)
                for (k in b.keys()) p[k] = b.get(k).toString()
            } catch (_: Exception) {}
        }
        // 二级接口：一次跑多个基础接口（顺序执行）。用 | 分隔，每段可以带自己的查询串。
        if (path == "/api/batch") {
            val arr = org.json.JSONArray()
            val ops = p["ops"] ?: ""
            for (op in ops.split('|')) {
                val o = op.trim()
                if (o.isEmpty()) continue
                if (o == "batch" || o == "/api/batch" || o.contains("api/batch")) {
                    arr.put(JSONObject().put("ok", false).put("error", "batch 里不能再套 batch"))
                    continue
                }
                val clean = if (o.startsWith("/")) o else "/api/" + o
                arr.put(route(clean, ""))
            }
            return JSONObject().put("ok", true).put("count", arr.length()).put("results", arr)
        }
        // 配置隔离（清单 12）：本次请求带了 space 就临时切过去，这一批调用都走那个命名空间
        val sp = p["space"]?.trim() ?: ""
        if (sp.isNotEmpty()) {
            try {
                Spaces.use(sp)
            } catch (_: Exception) {
            }
        }
        return when (path) {
            "/api/help" -> call("api.help", JSONObject())
            "/api/summary" -> call("kb.summary", JSONObject())
            "/api/grab" -> call(
                "page.grab",
                JSONObject().put("selector", p["selector"] ?: "").put("max", (p["max"] ?: "8000").toIntOrNull() ?: 8000)
            )
            "/api/win" -> call("win.list", JSONObject())
            "/api/win/menu" -> call("win.menu", JSONObject())
            "/api/win/recent" -> call("win.recent", JSONObject())
            "/api/win/reopen" -> call("win.reopen", JSONObject().put("i", (p["i"] ?: "0").toIntOrNull() ?: 0))
            "/api/win/new" -> call("win.new", JSONObject().put("url", p["url"] ?: ""))
            "/api/win/switch" -> call("win.switch", JSONObject().put("id", (p["id"] ?: "0").toIntOrNull() ?: 0))
            "/api/win/close" -> call("win.close", JSONObject().put("id", (p["id"] ?: "-1").toIntOrNull() ?: -1))
            "/api/translate" -> if (p["text"] == null) {
                call("translate.state", JSONObject())
            } else {
                call(
                    "translate.do",
                    JSONObject().put("text", p["text"] ?: "").put("from", p["from"] ?: "auto")
                        .put("to", p["to"] ?: "zh")
                )
            }
            "/api/space" -> call(
                "space.list",
                JSONObject()
            )
            "/api/space/use" -> call("space.use", JSONObject().put("name", p["name"] ?: ""))
            "/api/keepalive/media" -> call("keepalive.media", JSONObject().put("on", p["on"] ?: ""))
            "/api/keepalive" -> call(
                if (p["on"] == null) "keepalive.state" else if (p["on"] != "0") "keepalive.start" else "keepalive.stop",
                JSONObject()
            )
            "/api/ai/context" -> call("ai.context", JSONObject())
            "/api/ai/act" -> call(
                "ai.act",
                JSONObject()
                    .put("do", p["do"] ?: "")
                    .put("selector", p["selector"] ?: "")
                    .put("text", p["text"] ?: "")
                    .put("value", p["value"] ?: "")
                    .put("url", p["url"] ?: "")
                    .put("q", p["q"] ?: "")
            )
            "/api/status", "/" -> {
                val b = bridge
                if (b == null) return JSONObject().put("ok", false).put("error", "桥未就绪")
                val o = b.statusJson()
                o.put("consoleOpen", try { act.consoleOpen() } catch (_: Exception) { false })
                // 代理中继端口（0 = 没开）与自代理开关：验收与外部脚本据此判断"自代理到底起没起"
                o.put("proxyPort", try { act.proxyPortNow() } catch (_: Exception) { 0 })
                o.put("http", status())
                o.put("endpoints", ENDPOINTS)
                o
            }
            "/api/search" -> call("browser.search", JSONObject().put("q", p["q"] ?: ""))
            "/api/goto" -> call("browser.goto", JSONObject().put("url", p["url"] ?: p["q"] ?: ""))
            "/api/back" -> call("browser.back", JSONObject())
            "/api/reload" -> call("browser.reload", JSONObject())
            "/api/state" -> call("browser.state", JSONObject())
            "/api/eval" -> call("page.eval", JSONObject().put("js", p["js"] ?: ""))
            "/api/query" -> call("page.query", JSONObject().put("selector", p["selector"] ?: "").put("text", p["text"] ?: ""))
            "/api/diag" -> call("page.diag", JSONObject().put("selector", p["selector"] ?: "").put("text", p["text"] ?: ""))
            "/api/click" -> call(
                "page.click",
                JSONObject()
                    .put("selector", p["selector"] ?: "")
                    .put("text", p["text"] ?: "")
                    .put("x", p["x"]?.toDoubleOrNull() ?: -1.0)
                    .put("y", p["y"]?.toDoubleOrNull() ?: -1.0)
                    .put("anchorBottom", p["anchorBottom"]?.toDoubleOrNull() ?: -1.0)
            )
            "/api/scripts" -> call("scripts.list", JSONObject())
            "/api/scripts/get" -> call("scripts.get", JSONObject().put("id", p["id"] ?: ""))
            "/api/scripts/scan" -> call("scripts.scanDir", JSONObject())
            "/api/scripts/clear" -> call("scripts.clear", JSONObject())
            "/api/recording" -> call(if (p["stop"] == "1") "rec.stop" else "rec.state", JSONObject())
            "/api/record/mode" -> call("rec.mode", JSONObject().put("mode", p["mode"] ?: "element"))
            "/api/record" -> call(
                if (p["action"] == "stop") "rec.stop"
                else "rec.start",
                JSONObject().put("name", p["name"] ?: "录制脚本").put("mode", p["mode"] ?: "element")
            )
            "/api/scripts/save" -> call(
                "scripts.save", JSONObject().put("name", p["name"] ?: "未命名")
                    .put("code", p["code"] ?: body).put("kind", p["kind"] ?: "userscript")
                    .put("match", p["match"] ?: "*")
                    .put("id", p["id"] ?: "")
                    .put("steps", parseSteps(p["steps"]))
            )
            // 界面/历史/书签/视频/条件
            "/api/ui/close" -> call("ui.close", JSONObject())
            "/api/ui/open" -> call("ui.open", JSONObject().put("tab", p["tab"] ?: ""))
            "/api/ui/menu" -> call("ui.menu", JSONObject())
            "/api/history" -> call("history.list", JSONObject())
            "/api/downloads" -> call("downloads.list", JSONObject())
            "/api/download" -> call(
                "download.start",
                JSONObject().put("url", p["url"] ?: "").put("name", p["name"] ?: "")
            )
            "/api/downloads/delete" -> call("downloads.delete", JSONObject().put("name", p["name"] ?: ""))
            // N6：下载列表上的状态键（暂停/继续合一）
            "/api/downloads/toggle" -> call("downloads.toggle", JSONObject().put("id", p["id"] ?: ""))
            // 设置 / 浏览模式 / 隐身 / Cookie
            // 接口自述目录：路由清单 + 人看的说明合成一份（tools/gen_api_doc.py 就是吃这份生成接口文档）
            "/api/catalog" -> {
                val list = org.json.JSONArray()
                ApiCatalog.common().let { arr ->
                    for (i in 0 until arr.length()) list.put(arr.optJSONObject(i) ?: continue)
                }
                JSONObject().put("ok", true).put("list", list)
                    .put("endpoints", org.json.JSONArray(ENDPOINTS))
                    .put("endpointCount", ENDPOINTS.size)
                    .put("note", "list = 给人看的说明（ApiCatalog）；endpoints = 实际路由")
            }
            "/api/settings" -> call("settings.get", JSONObject())
            "/api/settings/set" -> {
                val s = JSONObject()
                listOf(
                    "uaMode", "customUa", "search", "customSearch",
                    "proxyType", "proxyHost", "proxyPort", "proxyUser", "proxyPass", "bypass",
                    "selfProxy",                       // 第 16 条：自代理开关（漏了它 → 接口点了不生效）
                    "downloadVia", "translateEndpoint",
                    "aria2Rpc", "aria2Token", "aria2Dir"      // 第 6 条：aria2 外部下载的三个设置
                ).forEach { k -> if (p.containsKey(k)) s.put(k, p[k]) }
                if (p.containsKey("incognito")) {
                    s.put("incognito", p["incognito"] == "1" || p["incognito"] == "true")
                }
                call("settings.set", JSONObject().put("settings", s))
            }
            "/api/incognito" -> call(
                "incognito.set",
                JSONObject().put("on", p["on"] == "1" || p["on"] == "true")
            )
            "/api/cookies" -> call("cookie.all", JSONObject())
            "/api/cookies/domain" -> call("cookie.domain", JSONObject().put("domain", p["domain"] ?: ""))
            "/api/cookies/delete" -> call(
                "cookie.delete",
                JSONObject().put("domain", p["domain"] ?: "").put("name", p["name"] ?: "")
            )
            "/api/cookies/clear" -> call("cookie.clear", JSONObject())
            // 资源嗅探 / 代理
            "/api/sniff" -> call("sniff.list", JSONObject())
            "/api/sniff/add" -> call("sniff.add", JSONObject().put("url", p["url"] ?: ""))
            "/api/sniff/clear" -> call("sniff.clear", JSONObject())
            "/api/sniff/scan" -> call("sniff.scanPage", JSONObject())
            // "指定哪一块是视频"（并进资源嗅探）：on=1 进入选取（点页面那一块）；on=0 取消
            "/api/sniff/pick" -> call("sniff.pick", JSONObject().put("on", (p["on"] ?: "1").toIntOrNull() ?: 1))
            // 直接给页面 CSS 坐标（真手指那条路是走 MainActivity.dispatchTouchEvent → 同一个 pickAt）
            "/api/sniff/pickAt" -> call(
                "sniff.pickAt",
                JSONObject().put("x", p["x"]?.toDoubleOrNull() ?: -1.0).put("y", p["y"]?.toDoubleOrNull() ?: -1.0)
            )
            "/api/sniff/stats" -> call("sniff.stats", JSONObject())
            "/api/sniff/download" -> call(
                "sniff.download",
                JSONObject().put("url", p["url"] ?: "").put("all", p["all"] == "1").put("name", p["name"] ?: "")
            )
            "/api/proxy" -> call("proxy.apply", JSONObject())
            "/api/_test/upstream/start" -> call(
                "testup.start",
                JSONObject().put("port", (p["port"] ?: "18890").toIntOrNull() ?: 18890)
                    .put("user", p["user"] ?: "").put("pass", p["pass"] ?: "")
            )
            "/api/_test/upstream/stats" -> call("testup.stats", JSONObject())
            "/api/_test/upstream/stop" -> call("testup.stop", JSONObject())
            // 内置播放器 / 投屏（DLNA）与"假电视"（离线验证用）
            "/api/player" -> if ((p["url"] ?: "").isBlank()) call("player.state", JSONObject())
            else call("player.open", JSONObject().put("url", p["url"] ?: "").put("name", p["name"] ?: ""))
            "/api/player/state" -> call("player.state", JSONObject())
            "/api/cast/scan" -> call("cast.scan", JSONObject().put("ms", (p["ms"] ?: "2000").toIntOrNull() ?: 2000))
            "/api/cast/play" -> call(
                "cast.play",
                JSONObject().put("url", p["url"] ?: "").put("title", p["title"] ?: "")
                    .put("control", p["control"] ?: "").put("name", p["name"] ?: "").put("location", p["location"] ?: "")
            )
            "/api/cast" -> call(
                "cast.open",
                JSONObject().put("url", p["url"] ?: "").put("title", p["title"] ?: "")
            )
            "/api/_test/tv/start" -> call("testtv.start", JSONObject().put("port", (p["port"] ?: "1900").toIntOrNull() ?: 1900))
            "/api/_test/tv/stats" -> call("testtv.stats", JSONObject())
            "/api/_test/tv/stop" -> call("testtv.stop", JSONObject())
            // 离线 HLS 测试源（验证嗅探 + m3u8 下载整条链路，不走外网）
            "/api/_test/adsim.js" -> JSONObject()
                .put("__t", "text/javascript")
                .put("__body", "window.__ADSIM_LOADED=1;console.log('ad sim loaded');")
            // 离线音频测试源：真 http + 真字节，用来验"音频嗅探 + 下载对账"（不走外网）
            "/api/_test/audio/sample.mp3" -> JSONObject()
                .put("__file", act.testAudioFile().absolutePath).put("__filename", "sample.mp3")
            "/api/_test/hls/index.m3u8" -> JSONObject()
                .put("__file", act.testHlsFile("index.m3u8").absolutePath).put("__filename", "index.m3u8")
            // 大文件测试源：用来在**不联网**的情况下看到"下载中"的进度条
            // （2MB 的 speed.bin 在 localhost 上一瞬间就下完，抓不到那一帧）。
            // 注意：这个路由只认"返回 __file 交给外层流式发送"，不能在分支里直接往 socket 写。
            "/api/_test/hls/big.bin" -> JSONObject()
                .put("__file", act.testBigFile((p["mb"] ?: "60").toIntOrNull() ?: 60).absolutePath)
                .put("__filename", "big.bin")
            "/api/_test/hls/speed.bin" -> JSONObject()
                .put("__file", act.testSpeedFile().absolutePath).put("__filename", "speed.bin")
            "/api/_test/hls/master.m3u8" -> JSONObject()
                .put("__file", act.testHlsFile("master.m3u8").absolutePath).put("__filename", "master.m3u8")
            "/api/_test/hls/page.html" -> JSONObject()
                .put("__file", act.testHlsFile("page.html").absolutePath).put("__filename", "page.html")
            "/api/_test/hls/link.html" -> JSONObject()
                .put("__file", act.testHlsFile("link.html").absolutePath).put("__filename", "link.html")
            "/api/_test/hls/seg1.ts" -> JSONObject()
                .put("__file", act.testHlsFile("seg1.ts").absolutePath).put("__filename", "seg1.ts")
            "/api/_test/hls/seg2.ts" -> JSONObject()
                .put("__file", act.testHlsFile("seg2.ts").absolutePath).put("__filename", "seg2.ts")
            "/api/_test/hls/seg3.ts" -> JSONObject()
                .put("__file", act.testHlsFile("seg3.ts").absolutePath).put("__filename", "seg3.ts")
            // 取回已下载的文件：电脑侧脚本把 App 下好的东西拿走（附带 attachment 头，浏览器/下载器拿到的名字是对的）
            "/api/downloads/get" -> {
                val name = p["name"] ?: ""
                val file = act.downloadFile(name)
                if (file == null) JSONObject().put("ok", false).put("error", "没有这个文件: $name")
                else JSONObject().put("__file", file.absolutePath).put("__filename", name).put("__attach", true)
            }
            "/api/history/clear" -> call("history.clear", JSONObject())
            "/api/history/page" -> call(
                "history.page",
                JSONObject().put("offset", (p["offset"] ?: "0").toIntOrNull() ?: 0)
                    .put("limit", (p["limit"] ?: "50").toIntOrNull() ?: 50)
            )
            "/api/history/delete-domain" -> call("history.deleteDomain", JSONObject().put("domain", p["domain"] ?: ""))
            "/api/history/delete-time" -> call(
                "history.deleteTime",
                JSONObject().put("ts", (p["ts"] ?: "").toLongOrNull() ?: System.currentTimeMillis())
                    .put("before", p["before"] != "0")
            )
            "/api/export/history" -> call("export.history", JSONObject())
            "/api/export/history-json" -> call("export.historyJson", JSONObject())
            "/api/export/bookmarks" -> call("export.bookmarks", JSONObject())
            "/api/downloads/export" -> call("downloads.export", JSONObject().put("name", p["name"] ?: ""))
            "/api/adblock" -> call("adblock.get", JSONObject())
            "/api/ffmpeg" -> call("ffmpeg.state", JSONObject())
            "/api/ffmpeg/state" -> call("ffmpeg.state", JSONObject())
            "/api/ffmpeg/info" -> call("ffmpeg.info", JSONObject().put("url", p["url"] ?: ""))
            "/api/ffmpeg/remux" -> call(
                "ffmpeg.remux",
                JSONObject().put("url", p["url"] ?: "").put("name", p["name"] ?: "video").put("ext", p["ext"] ?: "mp4").put("referer", p["referer"] ?: "")
            )
            "/api/ffmpeg/run" -> call(
                "ffmpeg.run",
                JSONObject().put("args", p["args"] ?: "").put("timeoutSec", (p["timeoutSec"] ?: "120").toLongOrNull() ?: 120)
            )
            "/api/video/info" -> call("video.info", JSONObject().put("url", p["url"] ?: ""))
            "/api/player/system" -> call(
                "player.system",
                JSONObject().put("url", p["url"] ?: "").put("mime", p["mime"] ?: "video/*")
            )
            "/api/video/list" -> call("video.list", JSONObject())
            "/api/read" -> call(
                "read.list",
                JSONObject().put("filter", p["filter"] ?: "").put("sort", p["sort"] ?: "time")
                    .put("limit", (p["limit"] ?: "300").toIntOrNull() ?: 300)
            )
            "/api/read/timer" -> call("timer.state", JSONObject())
            // 首页小 app 网格（控制台页面同源读，不往页面注入任何东西）
            // 开发覆盖层状态：开着几个文件、目录在哪（验收靠它证明"跑的是推上去那份"）
            "/api/dev/assets" -> {
                val root = java.io.File(act.getExternalFilesDir(null), "cdp-dev")
                val dir = java.io.File(root, "ui")
                val names: List<String> = dir.list()?.sorted() ?: emptyList()
                JSONObject().put("ok", true).put("dir", root.absolutePath)
                    .put("enabled", dir.isDirectory).put("files", org.json.JSONArray(names))
            }
            // 多窗口（第 28 条）：列表/新建/切换/关闭，脚本与验收都走这里
            // 第 6 条：直接把一个地址交给 aria2（验收与脚本用；和界面走同一条路）
            "/api/dl/aria2" -> Aria2.submit(
                act.settings.aria2Rpc(), act.settings.aria2Token(),
                p["url"] ?: "", act.settings.aria2Dir(), p["name"] ?: ""
            )
            "/api/dl/aria2/check" -> Aria2.version(act.settings.aria2Rpc(), act.settings.aria2Token())
            "/api/dl/aria2/status" -> Aria2.tellStatus(
                act.settings.aria2Rpc(), act.settings.aria2Token(), p["gid"] ?: ""
            )
            // WebView 在屏幕上的位置（含 CSS→屏幕缩放）：真手指验收靠它换算点击坐标，
            // 不用再去 uiautomator dump（页面一直在动时 dump 会失败 → 以前会静默点到空处）
            "/api/view/box" -> {
                val loc = IntArray(2)
                val w = act.webViewBoxOnScreen(loc)
                JSONObject().put("ok", w > 0)
                    .put("x", loc[0]).put("y", loc[1]).put("w", w)
                    .put("cssW", act.cssWidthNow())
                    .put("scale", if (act.cssWidthNow() > 0) (w.toDouble() / act.cssWidthNow()) else 0.0)
            }
            "/api/win/list" -> call("win.list", JSONObject())
            "/api/win/menu" -> call("win.menu", JSONObject())
            "/api/win/recent" -> call("win.recent", JSONObject())
            "/api/win/reopen" -> call("win.reopen", JSONObject().put("i", (p["i"] ?: "0").toIntOrNull() ?: 0))
            "/api/win/new" -> call("win.new", JSONObject().put("url", p["url"] ?: ""))
            "/api/win/switch" -> call("win.switch", JSONObject().put("id", (p["id"] ?: "0").toIntOrNull() ?: 0))
            "/api/win/close" -> call("win.close", JSONObject().put("id", (p["id"] ?: "0").toIntOrNull() ?: 0))
            "/api/apps/list" -> call("apps.list", JSONObject())
            "/api/apps/save" -> call(
                "apps.save",
                JSONObject().put("id", p["id"] ?: "").put("name", p["name"] ?: "").put("url", p["url"] ?: "")
            )
            "/api/apps/remove" -> call("apps.remove", JSONObject().put("id", p["id"] ?: ""))
            "/api/apps/move" -> call(
                "apps.move",
                JSONObject().put("id", p["id"] ?: "").put("dir", (p["dir"] ?: "1").toIntOrNull() ?: 1)
            )
            "/api/read/stats" -> call("read.stats", JSONObject().put("range", p["range"] ?: "hour"))
            "/api/read/add" -> call(
                "read.add",
                JSONObject().put("url", p["url"] ?: "").put("title", p["title"] ?: "")
                    .put("ms", (p["ms"] ?: "0").toLongOrNull() ?: 0L).put("mins", (p["mins"] ?: "0").toLongOrNull() ?: 0L)
            )
            "/api/read/del" -> call("read.del", JSONObject().put("id", p["id"] ?: ""))
            "/api/read/clear" -> call("read.clear", JSONObject())
            "/api/term" -> call("term.run", JSONObject().put("cmd", p["cmd"] ?: ""))
            "/api/adblock/mode" -> call("adblock.mode", if (p["mode"] != null) JSONObject().put("mode", p["mode"]) else JSONObject())
            "/api/adblock/cosmetic" -> call("adblock.cosmetic", JSONObject())
            "/api/adblock/cosmetic/add" -> call("adblock.cosmetic.add", JSONObject().put("sel", p["sel"] ?: ""))
            "/api/adblock/cosmetic/del" -> call("adblock.cosmetic.del", JSONObject().put("sel", p["sel"] ?: ""))
            "/api/adblock/cosmetic/reset" -> call("adblock.cosmetic.reset", JSONObject())
            "/api/cosmetic/push" -> call("cosmetic.push", JSONObject())
            "/api/vault" -> call("vault.state", JSONObject())
            "/api/vault/state" -> call("vault.state", JSONObject())
            "/api/vault/unlock" -> call("vault.unlock", JSONObject())
            "/api/vault/lock" -> call("vault.lock", JSONObject())
            "/api/vault/list" -> call("vault.list", JSONObject())
            "/api/vault/get" -> call("vault.get", JSONObject().put("id", p["id"] ?: ""))
            "/api/vault/save" -> call(
                "vault.save",
                JSONObject().put("site", p["site"] ?: "").put("user", p["user"] ?: "")
                    .put("pass", p["pass"] ?: "").put("note", p["note"] ?: "")
            )
            "/api/vault/del" -> call("vault.del", JSONObject().put("id", p["id"] ?: ""))
            "/api/vault/gen" -> call(
                "vault.gen",
                JSONObject().put("len", (p["len"] ?: "16").toIntOrNull() ?: 16)
                    .put("upper", (p["upper"] ?: "1") != "0").put("lower", (p["lower"] ?: "1") != "0")
                    .put("digit", (p["digit"] ?: "1") != "0").put("sym", (p["sym"] ?: "1") != "0")
            )
            "/api/adblock/set" -> call("adblock.set", JSONObject().put("enabled", p["enabled"] != "0"))
            "/api/adblock/add" -> call("adblock.add", JSONObject().put("rule", p["rule"] ?: ""))
            "/api/adblock/remove" -> call("adblock.remove", JSONObject().put("rule", p["rule"] ?: ""))
            "/api/adblock/clear" -> call("adblock.clear", JSONObject())
            "/api/adblock/reset" -> call("adblock.reset", JSONObject())
            "/api/adblock/warn" -> call("adblock.warn.list", JSONObject())
            "/api/adblock/warn/add" -> call("adblock.warn.add", JSONObject().put("host", p["host"] ?: ""))
            "/api/adblock/warn/remove" -> call("adblock.warn.remove", JSONObject().put("host", p["host"] ?: ""))
            "/api/adblock/warn/clear" -> call("adblock.warn.clear", JSONObject())
            "/api/bookmarks" -> call("bookmark.list", JSONObject())
            "/api/bookmarks/folders" -> call("bookmark.folders", JSONObject())
            "/api/bookmarks/folder" -> call(
                "bookmark.folder",
                JSONObject().put("id", p["id"] ?: "").put("folder", p["folder"] ?: "")
            )
            "/api/folders/rename" -> call(
                "folder.rename",
                JSONObject().put("from", p["from"] ?: "").put("to", p["to"] ?: "")
            )
            "/api/folders/delete" -> call("folder.delete", JSONObject().put("name", p["name"] ?: ""))
            "/api/record/live" -> call("rec.live", JSONObject())
            "/api/geom" -> call("sys.geom", JSONObject())
            "/api/record/dots" -> call("rec.dots", JSONObject().put("on", p["on"] ?: "1"))
            "/api/record/dots/status" -> call("rec.dotStatus", JSONObject())
            // 注意：这里的 key 是**不含查询串的路径**（`when (path)`），参数从 p 里取
            "/api/record/dots/play" -> call("rec.dotPlay", JSONObject().put("n", p["n"] ?: "0"))
            "/api/record/dots/move" -> call(
                "rec.dotMove",
                JSONObject().put("n", p["n"] ?: "0").put("x", p["x"] ?: "0").put("y", p["y"] ?: "0")
            )
            "/api/record/merge" -> call("rec.merge", JSONObject().put("index", p["index"] ?: "-1"))
            "/api/picker/arm" -> call("picker.arm", JSONObject())
            "/api/picker/last" -> call("picker.last", JSONObject())
            "/api/picker/cancel" -> call("picker.cancel", JSONObject())
            // 自检口（debug 包）：导出包在私有目录留了一份，可以原地导入回去验证合并逻辑
            "/api/_test/bundle/list" -> call("bundle.list", JSONObject())
            "/api/_test/bundle/apply" -> call("bundle.apply", JSONObject().put("name", p["name"] ?: ""))
            "/api/security/page" -> call("security.page", JSONObject())
            "/api/security" -> call("security.http", JSONObject())
            "/api/security/sensitive" -> call("security.sensitive", JSONObject().put("on", p["on"] ?: ""))
            "/api/security/block" -> call("security.block.list", JSONObject())
            "/api/security/block/add" -> call("security.block.add", JSONObject().put("host", p["host"] ?: ""))
            "/api/security/block/remove" -> call("security.block.remove", JSONObject().put("host", p["host"] ?: ""))
            "/api/security/block/clear" -> call("security.block.clear", JSONObject())
            "/api/net" -> call(
                "net.list",
                JSONObject().put("filter", p["filter"] ?: "").put("kind", p["kind"] ?: "")
                    .put("limit", (p["limit"] ?: "100").toIntOrNull() ?: 100)
            )
            "/api/net/enabled" -> call("net.enabled", JSONObject().put("on", p["on"] != "0"))
            "/api/net/stats" -> call("net.stats", JSONObject().put("range", p["range"] ?: "hour"))
            "/api/net/clear" -> call("net.clear", JSONObject())
            "/api/net/tool" -> call(
                "net.tool",
                JSONObject().put("action", p["action"] ?: "").put("host", p["host"] ?: "")
                    .put("port", (p["port"] ?: "0").toIntOrNull() ?: 0).put("url", p["url"] ?: "")
            )
            "/api/export/bundle" -> call("bundle.export", JSONObject().put("parts", p["parts"] ?: ""))
            "/api/import/pick" -> call("bundle.import.pick", JSONObject())
            "/api/cookie/detail" -> call("cookie.detail", JSONObject().put("domain", p["domain"] ?: ""))
            "/api/power" -> call("power.state", JSONObject())
            "/api/power/set" -> call(
                "power.set",
                JSONObject().put("name", p["name"] ?: "").put("on", p["on"] != "0")
            )
            "/api/power/save" -> call("power.save", JSONObject())
            "/api/find" -> call(
                "find.page",
                JSONObject().put("q", p["q"] ?: "").put("dir", (p["dir"] ?: "1").toIntOrNull() ?: 1)
            )
            "/api/find/clear" -> call("find.clear", JSONObject())
            "/api/tab/mute" -> call("tab.mute", JSONObject().put("on", p["on"] != "0"))
            "/api/share" -> call("share.current", JSONObject().put("text", p["text"] ?: ""))
            "/api/bookmarks/add" -> call("bookmark.add", JSONObject().put("url", p["url"] ?: "").put("title", p["title"] ?: ""))
            "/api/bookmarks/remove" -> call("bookmark.remove", JSONObject().put("id", p["id"] ?: ""))
            "/api/bookmarks/toggle" -> call("bookmark.toggle", JSONObject())
            "/api/check" -> call(
                "page.check",
                JSONObject().put(
                    "cond",
                    JSONObject().put("type", p["type"] ?: "always")
                        .put("selector", p["selector"] ?: "").put("text", p["text"] ?: "")
                        .put("value", p["value"] ?: "").put("from", p["from"] ?: "")
                )
            )
            "/api/video" -> call(
                "page.video",
                JSONObject().put("action", p["action"] ?: "state").put("selector", p["selector"] ?: "")
            )
            "/api/record/insert" -> call("rec.insert", JSONObject().put("step", parseObj(p["step"])))
            "/api/record/clear" -> call("rec.clear", JSONObject())
            // 可编辑的数据：cookie 能加/改，书签与历史的属性也能改
            "/api/cookie/set" -> call("cookie.set", JSONObject()
                .put("domain", p["domain"] ?: "").put("name", p["name"] ?: "").put("value", p["value"] ?: "")
                .put("path", p["path"] ?: "/").put("maxAge", (p["maxAge"] ?: "0").toLongOrNull() ?: 0L)
                .put("secure", p["secure"] == "1").put("httpOnly", p["httpOnly"] == "1"))
            "/api/bookmark/update" -> call("bookmark.update", JSONObject()
                .put("id", p["id"] ?: "")
                .also { if (p["title"] != null) it.put("title", p["title"]) }
                .also { if (p["url"] != null) it.put("url", p["url"]) }
                .also { if (p["folder"] != null) it.put("folder", p["folder"]) })
            "/api/history/update" -> call("history.update", JSONObject()
                .put("ts", (p["ts"] ?: "-1").toLongOrNull() ?: -1L)
                .put("url", p["url"] ?: "").put("title", p["title"] ?: ""))
            "/api/nav/open" -> call("nav.open", JSONObject().put("url", p["url"] ?: ""))
            "/api/scripts/import" -> call("scripts.importUrl", JSONObject().put("url", p["url"] ?: ""))
            "/api/replay" -> call("play.run", JSONObject().put("id", p["script"] ?: p["id"] ?: "")
                .put("times", (p["times"] ?: "1").toIntOrNull() ?: 1),
                ((p["waitSec"] ?: "240").toLongOrNull() ?: 240))
            // 跑队列 / 单步执行 也开一条外部入口（同一套 op，脚本与验收都读得到同步回包）
            "/api/run" -> call(if (p["what"] == "one") "rec.runOne" else "rec.runAll",
                JSONObject().put("i", (p["i"] ?: "-1").toIntOrNull() ?: -1)
                    .put("times", (p["times"] ?: "1").toIntOrNull() ?: 1),
                ((p["waitSec"] ?: "240").toLongOrNull() ?: 240))
            "/api/play/last" -> call("play.last", JSONObject())
            "/api/log" -> call("log.tail", JSONObject().put("n", p["n"]?.toIntOrNull() ?: 50))
            "/api/http" -> if (p["action"] == "start") call("http.start", JSONObject().put("lan", p["lan"] == "1"))
            else call("http.stop", JSONObject())
            "/api/events" -> call("events.tail", JSONObject().put("n", p["n"]?.toIntOrNull() ?: 50))
            else -> JSONObject().put("ok", false).put("error", "未知接口 $path").put("endpoints", ENDPOINTS)
        }
    }

    /** steps 用 JSON 字符串（或 JSON 数组字符串）传进来：录制脚本导入时用 */
    private fun parseSteps(s: String?): org.json.JSONArray? {
        if (s.isNullOrBlank()) return null
        return try {
            org.json.JSONArray(s)
        } catch (_: Exception) {
            try {
                org.json.JSONObject(s).optJSONArray("steps")
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 单个 JSON 对象字符串（例如插入一步的 step） */
    private fun parseObj(s: String?): JSONObject {
        if (s.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(s)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /**
     * 把 op 派发给 Bridge 并等它回话。
     * timeoutSec：默认 20 秒；**回放/跑队列这类会跑很久的 op 要显式给大一点**——
     * 以前一律 20 秒，于是"跑 50 次"这种明明在跑的活，接口先回一句「超时」，看着像坏了。
     */
    private fun call(op: String, args: JSONObject, timeoutSec: Long = 20): JSONObject {
        val b = bridge ?: return JSONObject().put("ok", false).put("error", "桥未就绪")
        val latch = CountDownLatch(1)
        var out = JSONObject().put("ok", false).put("error", "超时")
        act.runOnUiThread { b.dispatch(op, args) { r -> out = r; latch.countDown() } }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return out
    }

    companion object {
        val ENDPOINTS = listOf(
            "/api/status", "/api/search?q=", "/api/goto?url=", "/api/back", "/api/reload", "/api/state",
            "/api/eval?js=", "/api/query?selector=|text=", "/api/diag?selector=|text=",
            "/api/click?selector=|text=|x=&y=|anchorBottom=",
            "/api/scripts", "/api/scripts/save?name=&code=", "/api/scripts/import?url=",
            "/api/record?action=start|stop", "/api/recording", "/api/replay?script=&times=N",
            "/api/run?what=one|queue&i=&times=N", "/api/play/last",
            "/api/cookie/set?domain=&name=&value=&path=&maxAge=&secure=&httpOnly=",
            "/api/bookmark/update?id=&title=&url=&folder=", "/api/history/update?ts=&url=&title=",
            "/api/events?n=", "/api/log?n=", "/api/http?action=start|stop&lan=0|1",
            "/api/ui/close", "/api/ui/open?tab=", "/api/ui/menu",
            "/api/history", "/api/history/clear", "/api/bookmarks", "/api/bookmarks/add?url=&title=",
            "/api/bookmarks/remove?id=", "/api/bookmarks/toggle",
            "/api/check?type=videoEnded|videoPlaying|elementExists|elementGone|textAppears|urlContains|urlChanged&selector=&text=&value=",
            "/api/video?action=state|play|pause|mute&selector=",
            "/api/record/insert?step=<json>", "/api/record/clear", "/api/nav/open?url=",
            "/api/download?url=", "/api/downloads", "/api/downloads/delete?name=",
            "/api/settings", "/api/settings/set?uaMode=phone|desktop|custom&search=bing|baidu|google|sogou|so360|custom&customUa=&customSearch=&proxyType=none|http|socks5&proxyHost=&proxyPort=&proxyUser=&proxyPass=&bypass=&incognito=0|1",
            "/api/keepalive/media?on=0|1", "/api/incognito?on=0|1", "/api/cookies", "/api/cookies/domain?domain=",
            "/api/cookies/delete?domain=&name=", "/api/cookies/clear",
            "/api/sniff", "/api/sniff/add?url=", "/api/sniff/clear", "/api/sniff/scan", "/api/sniff/stats", "/api/sniff/pick?on=0|1", "/api/sniff/pickAt?x=&y=",
            "/api/security", "/api/security/token", "/api/security/sensitive?on=0|1",
            "/api/sniff/download?url=&all=1&name=", "/api/proxy",
            "/api/player?url=&name=", "/api/cast/scan?ms=", "/api/cast/play?url=&title=&control=&name=&location=",
            "/api/cast?url=&title=",
            "/api/bookmarks/folders", "/api/bookmarks/folder?id=&folder=",
            "/api/folders/rename?from=&to=", "/api/folders/delete?name=", "/api/record/live",
            "/api/adblock", "/api/adblock/warn", "/api/adblock/warn/add?host=", "/api/adblock/warn/remove?host=",
            "/api/adblock/warn/clear"
        )
    }
}
