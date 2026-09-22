package dev.cdp

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : Activity() {

    companion object {
        @Volatile var INSTANCE: MainActivity? = null
        const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
        const val EVENT_ORIGIN = "https://cdp-event.local"
        const val META_ORIGIN = "https://cdp-meta.local"
        /** 油猴 GM_* 子集：顺序就是注入时传参的顺序（不要出现重名，否则整段注入直接语法错） */
        val GM_PARAMS = listOf(
            "GM_setValue", "GM_getValue", "GM_deleteValue", "GM_listValues", "GM_addStyle",
            "GM_log", "GM_notification", "GM_openInTab", "GM_registerMenuCommand", "GM_xmlhttpRequest",
            "unsafeWindow"
        )
    }

    lateinit var browser: WebView
        private set
    lateinit var console: WebView
        private set
    private lateinit var urlBar: EditText
    /** ☰ 视图本身 —— 下拉菜单要锚在它身上（锚在整屏根视图上菜单会跑到屏幕左下角） */
    private var menuAnchor: View? = null
    /** 应用内下载记录（内存 + files/downloads.json 落盘，最多留 50 条） */
    private val downloads = java.util.Collections.synchronizedList(ArrayList<JSONObject>())
    private val downloadsFile by lazy { java.io.File(filesDir, "downloads.json") }
    /** 视口尺寸轮询的 Runnable：onDestroy 要把它撤掉，否则旧实例会一直调已销毁的 WebView */
    private var metricsTick: Runnable? = null
    private lateinit var overlay: FrameLayout
    private lateinit var recDot: TextView
    /** 隐身模式指示（🕶） */
    private lateinit var incogDot: TextView
    private lateinit var progress: ProgressBar
    private lateinit var prefs: SharedPreferences

    /** 当前页面地址的缓存值（主线程更新；别的线程只读，别去碰 WebView） */
    @Volatile
    private var lastPageUrl: String = ""

    lateinit var bridge: Bridge
        private set
    lateinit var store: ScriptStore
        private set
    lateinit var nav: NavStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var sniff: Sniffer
        private set
    lateinit var hls: HlsDownloader
        private set
    private lateinit var proxy: ProxyRelay
    /** 只用来验证代理链路的「上游代理」（debug 才注册入口） */
    lateinit var testUp: TestUpstream
        private set
    /** 投屏（DLNA） */
    lateinit var cast: Cast
        private set
    /** 只用来验证投屏链路的"假电视"（debug 才注册入口） */
    lateinit var renderer: TestRenderer
        private set
    private lateinit var http: HttpControl

    private val main = Handler(Looper.getMainLooper())
    private var agentSource = ""
    private var docStartHandlers = mutableListOf<androidx.webkit.ScriptHandler>()
    private var docStartOk = false
    private var agentInjected = false
    /** 已注入的脚本内容哈希：内容没变就不重复注入（注入只能加不能删，见 registerDocumentStart） */
    private val injectedHashes = HashMap<String, String>()
    private var scriptGen = 0

    /** 页面 CSS 视口尺寸（用来把 CSS 坐标换算成 View 像素，做真实触摸注入） */
    @Volatile private var cssW = 0.0
    @Volatile private var cssH = 0.0
    /** 主线程维护的界面状态快照（WebView 的读接口只能在主线程调，外部线程统一读这份缓存） */
    @Volatile private var stateCache: JSONObject = JSONObject()
    @Volatile var busy = false

    enum class Engine(val label: String, val key: String, val tpl: String) {
        BING("必应", "bing", "https://cn.bing.com/search?q=%s"),
        BAIDU("百度", "baidu", "https://www.baidu.com/s?wd=%s"),
        GOOGLE("谷歌", "google", "https://www.google.com/search?q=%s"),
        SOGOU("搜狗", "sogou", "https://www.sogou.com/web?query=%s"),
        SO360("360", "so360", "https://www.so.com/s?q=%s"),
        CUSTOM("自定义", "custom", "");
    }

    // ------------------------------------------------------------------ 设置（UA / 搜索 / 隐身 / 代理）

    /**
     * 首页（新标签页 / ⌂ / 新窗口）的统一地址。
     *
     * **必须优先用本机控制口那份**：内置资源是 https 源，混合内容策略会拦掉它对
     * `http://127.0.0.1:8848/api/apps/list` 的 fetch —— 现象是"首页在、不报错、但小 app 永远渲染不出来"
     * （用户 2026-09-21 报的就是这个）。控制口没起来才退回内置资源那份（同一个文件，只是读不到列表）。
     */
    fun startPageUrl(): String =
        if (ctrlListening()) "http://127.0.0.1:8848/ui/start.html" else "$ASSET_ORIGIN/ui/start.html"

    /** WebView 在屏幕上的位置与宽度（真手指验收换算坐标用；返回 0 表示还量不到） */
    fun webViewBoxOnScreen(out: IntArray): Int {
        return try {
            val loc = IntArray(2)
            browser.getLocationOnScreen(loc)
            out[0] = loc[0]
            out[1] = loc[1]
            browser.width
        } catch (_: Exception) {
            0
        }
    }

    /** 当前 CSS 视口宽度（页面报回来的那个） */
    fun cssWidthNow(): Int = if (cssW > 0.5) cssW.toInt() else 0      // 页面报回来的 CSS 视口宽

    /** 控制口在本机是否真的在听（首页优先走它） */
    private fun ctrlListening(): Boolean = try {
        java.net.Socket("127.0.0.1", 8848).use { true }
    } catch (_: Exception) {
        false
    }

    /** 当前生效的 UA（null = WebView 默认的手机 UA） */
    fun applyUserAgent() {
        main.post {
            try {
                val ua = settings.userAgent()
                browser.settings.userAgentString = ua            // null = 恢复默认
                val mode = settings.get("uaMode", "phone")
                bridge.log("浏览模式=${if (mode == "desktop") "电脑" else if (mode == "custom") "自定义" else "手机"}；UA=${browser.settings.userAgentString?.take(90)}")
                if (!browser.url.isNullOrEmpty()) browser.reload()
            } catch (e: Exception) {
                bridge.log("设置 UA 失败: ${e.message}")
            }
        }
    }

    /** 隐身模式：不记历史；退出时清 cookie/存储/缓存与无痕下载 */
    fun setIncognito(on: Boolean) {
        settings.set("incognito", on)
        main.post {
            incogDot.visibility = if (on) View.VISIBLE else View.GONE
            if (!on) {
                // Android 上无法做到"只清无痕的 cookie"（CookieManager 是全局的），
                // 所以退出无痕就是清干净 —— 这一点在文档里明确写着。
                try { android.webkit.CookieManager.getInstance().removeAllCookies(null) } catch (_: Exception) {}
                try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) {}
                try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
                try { browser.clearCache(true) } catch (_: Exception) {}
                clearIncognitoDownloads()
            }
        }
        bridge.log(if (on) "已开启隐身模式（不记历史、退出时清 cookie/存储/无痕下载）" else "已关闭隐身模式：cookie/存储/无痕下载已清空")
    }

    private fun clearIncognitoDownloads() {
        val gone = ArrayList<String>()
        synchronized(downloads) {
            val it = downloads.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (r.optBoolean("incognito")) { gone.add(r.optString("name")); it.remove() }
            }
        }
        gone.forEach { n ->
            try { java.io.File(java.io.File(filesDir, "downloads"), n).delete() } catch (_: Exception) {}
        }
        persistDownloads()
    }

    // ------------------------------------------------------------------ Cookie

    private fun cookieUrl(domain: String): String =
        if (domain.startsWith("http://") || domain.startsWith("https://")) domain else "https://$domain"

    /** 问某个域名下的 cookie（Android 只能按域名问，没有"列出全部"的接口）；
     *  `attrs` 传了就用传进来的那份快照（一次请求共用一份，别再每域名读一遍库） */
    fun cookiesFor(domain: String, attrs: org.json.JSONArray? = null): org.json.JSONArray {
        val arr = org.json.JSONArray()
        val raw = try { android.webkit.CookieManager.getInstance().getCookie(cookieUrl(domain)) } catch (_: Exception) { null }
        (raw ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { kv ->
            arr.put(
                JSONObject().put("name", kv.substringBefore('=')).put("value", kv.substringAfter('=', ""))
                    .put("domain", domain)
            )
        }
        // 反馈 #3：属性（HttpOnly / Secure / Path / 创建时间 / 最后使用）从 WebView 自己的库补上
        return try { cookieDb.merge(arr, attrs ?: cookieDb.snapshot()) } catch (e: Throwable) { arr }
    }

    /**
     * 汇总"哪些站点有 cookie"。
     *
     * 域名来源是**库里真有 cookie 的站点**（`CookieDb.hosts`），不再是"历史 + 书签里出现过的所有域名"：
     * 后者对没有 cookie 的域名也要问一次 CookieManager、还要按域名读一遍库 —— 21 个书签域名时
     * `/api/cookies` 要 **17 秒**，而这段是在 UI 线程上跑的（整界面卡死，登记 B-84）。
     * 现在整轮只读一次库（`snapshot(force = true)`），并对域名数设上限。
     */
    fun cookiesAll(): org.json.JSONArray {
        var attrs = try { cookieDb.snapshot(force = true) } catch (_: Throwable) { org.json.JSONArray() }
        val domains = LinkedHashSet<String>()
        try { cookieDb.hosts(attrs).forEach { domains.add(it) } } catch (_: Throwable) {}
        // 当前页的域名也带上（可能刚写、库里还没落盘）
        val curHost = try { android.net.Uri.parse(browser.url ?: "").host } catch (_: Exception) { null }
        if (!curHost.isNullOrBlank()) domains.add(curHost)
        // 刚写的 cookie 可能还差一拍才落盘：**当前页**确实有 cookie、而快照里一条都对不上时，
        // 等 200ms 再读一次（整轮只等这一次）。别的域名不参与等待 —— 以前是每个域名都等，
        // 21 个域名就 17 秒（B-84）。
        if (!curHost.isNullOrBlank()) {
            val raw = try { android.webkit.CookieManager.getInstance().getCookie(cookieUrl(curHost)) } catch (_: Exception) { null }
            val names = (raw ?: "").split(';').map { it.substringBefore('=').trim().lowercase() }.filter { it.isNotEmpty() }
            val known = HashSet<String>()
            for (i in 0 until attrs.length()) known.add(attrs.optJSONObject(i)?.optString("name").orEmpty().lowercase())
            if (names.isNotEmpty() && names.none { known.contains(it) }) {
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                attrs = try { cookieDb.snapshot(force = true) } catch (_: Throwable) { org.json.JSONArray() }
            }
        }
        val out = org.json.JSONArray()
        var n = 0
        for (d in domains) {
            if (d.isBlank()) continue
            if (n >= 300) break                    // 上限保护：别让上千个域名把 UI 线程按住
            n++
            val c = cookiesFor(d, attrs)
            if (c.length() > 0) out.put(JSONObject().put("domain", d).put("cookies", c))
        }
        return out
    }

    fun deleteCookie(domain: String, name: String): Boolean = try {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setCookie(cookieUrl(domain), "$name=; Max-Age=0; path=/")
        cm.flush()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 写一个 cookie（用户要求：cookie 也要能**添加 / 改属性**）。
     *
     * 走 `CookieManager.setCookie(url, "name=value; Path=/; Max-Age=…; Secure; HttpOnly")`。
     * 如实说明：Android 上 **Domain 必须和地址对得上**，对不上浏览器会直接把这条丢掉——
     * 所以这里用 domain（没有就给地址的域）拼 url，写完**读回来确认**，没写进去就如实说原因。
     */
    fun setCookie(domain: String, name: String, value: String, path: String,
                  maxAgeSec: Long, secure: Boolean, httpOnly: Boolean): JSONObject {
        val d = domain.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (name.isBlank()) return JSONObject().put("ok", false).put("error", "cookie 名字不能空")
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            val sb = StringBuilder()
            sb.append(name.trim()).append('=').append(value)
            sb.append("; Path=").append(if (path.isBlank()) "/" else path.trim())
            if (maxAgeSec > 0) sb.append("; Max-Age=").append(maxAgeSec)
            if (secure) sb.append("; Secure")
            if (httpOnly) sb.append("; HttpOnly")
            val url = cookieUrl(d)
            cm.setCookie(url, sb.toString())
            cm.flush()
            val back = cookiesFor(d)
            var found = false
            for (i in 0 until back.length()) {
                if (back.getJSONObject(i).optString("name") == name.trim()) found = true
            }
            JSONObject().put("ok", found).put("url", url).put("sent", sb.toString()).put("list", back)
                .put("note", if (found) "已写入并读回确认"
                    else "浏览器没接受这条 cookie（常见原因：Domain/Path 和地址对不上、名字非法）")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.toString())
        }
    }

    fun clearCookies(): Boolean = try {
        val cm = android.webkit.CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        true
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------ 代理（HTTP / HTTPS / SOCKS5）

    /**
     * 应用代理设置：先起本地中继，再把 WebView 的流量指到中继上。
     *
     * 为什么绕一层：WebView 只能设 HTTP/HTTPS 代理、且没地方填账号密码
     * （ProxyController 不带认证），所以 SOCKS5 与「要认证的代理」都得靠本地中继。
     */
    fun applyProxy() {
        val type = settings.proxyType()
        val host = settings.proxyHost()
        // 自代理模式（用户第 16 条）：proxyType=none 但用户勾了"用自代理统计更全" → 不清代理，继续起中继（上游直连）
        val wantSelf = settings.selfProxy()
        if ((type == "none" || host.isBlank() || settings.proxyPort() <= 0) && !wantSelf) {
            try { proxy.stop() } catch (_: Exception) {}
            try {
                androidx.webkit.ProxyController.getInstance()
                    .clearProxyOverride(java.util.concurrent.Executors.newSingleThreadExecutor()) { }
            } catch (e: Exception) {
                bridge.log("清代理失败: ${e.message}")
            }
            bridge.log("代理：不用（直连）")
            return
        }
        // 自代理模式：proxyType = none 但用户要求"用自代理统计更全" → 照样起中继（上游直连）
        val selfOnly = settings.selfProxy()
        val p = proxy.start()
        if (p <= 0) {
            bridge.log("代理没生效：本地中继没绑定成功，仍走直连")
            return
        }
        try {
            val b = androidx.webkit.ProxyConfig.Builder().addProxyRule("127.0.0.1:$p")
            settings.bypassRules().forEach { r -> try { b.addBypassRule(r) } catch (_: Exception) {} }
            androidx.webkit.ProxyController.getInstance().setProxyOverride(
                b.build(), java.util.concurrent.Executors.newSingleThreadExecutor()
            ) {
                bridge.log(
                    "代理已生效：浏览器 → 本地中继 127.0.0.1:$p → " +
                        (if (type == "none") "直连（自代理模式，只为统计口径更全）"
                        else "$type ${host}:${settings.proxyPort()}") +
                        "；直连规则 ${settings.bypassRules().size} 条"
                )
            }
            // 让代理立刻对当前页面生效
            if (!browser.url.isNullOrEmpty()) browser.reload()
        } catch (e: Exception) {
            bridge.log("应用代理失败: ${e.message}")
        }
    }

    /** 本地中继端口（0 = 没开） */
    fun proxyPortNow(): Int = proxy.port

    /** 本地中继在不在跑（没跑 = 字节统计一定是 0，界面要如实标出来） */
    fun proxyRunningNow(): Boolean = try { proxy.isRunning } catch (_: Throwable) { false }

    /** 下载资源时带上 WebView 的 cookie（登录态的资源也能下） */
    fun cookieForDownload(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ m3u8（HLS）

    /** 下载 m3u8：播放列表 + 分片按顺序存进 downloads/<名字>.hls/（不转码不合并） */
    /**
     * 我们自己的虚拟/本地通道不算"页面的请求"：不进嗅探清单，也不进网络时间线。
     * 用户实测看到 `cdp-meta.local/meta?...` 和本机控制口轮询混在时间线里 —— 那是噪声，会把真实请求淹掉。
     */
    private fun isInternalUrl(u: String): Boolean =
        u.contains("appassets.androidplatform.net") || u.contains("cdp-event.local") ||
            u.contains("cdp-meta.local") || u.contains("127.0.0.1:") || u.contains("localhost:")

    fun downloadHls(url: String, nameHint: String): JSONObject {
        val name = nameHint.ifBlank {
            url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifBlank { "hls" }
        }
        val rec = JSONObject()
            .put("name", name).put("url", url).put("kind", "hls").put("bytes", 0L)
            .put("state", "进行中").put("ts", System.currentTimeMillis())
            .put("incognito", settings.incognito())
            .put("page", lastPageUrl)          // 记录「从哪个网页下的」——流量饼图按网页归集
        synchronized(downloads) {
            downloads.add(rec)
            while (downloads.size > 50) downloads.removeAt(0)
        }
        persistDownloads()
        val isPlaylist = hls.looksLikePlaylist(url)
        bridge.log(if (isPlaylist) "开始下载 m3u8：$url" else "开始下载文件（直链）：$url")
        Thread {
            try {
                var lastBytes = 0L
                var lastTick2 = System.currentTimeMillis()
                val dlHost = try { android.net.Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
                val onProg: (Int, Int, Long) -> Unit = { done, total, bytes ->
                    // 注意别用 total/done 这两个名字：下载行里 `total` 一直表示"字节总数"，会算错百分比
                    rec.put("segsDone", done).put("segsTotal", total).put("bytes", bytes)
                        .put("state", "进行中 " + done + "/" + total + " 片")
                    // 分片字节也进流量统计（按来源页归集 → 饼图才答得上"哪个网页用了多少流量"）
                    val delta = bytes - lastBytes
                    if (delta > 0) {
                        val now2 = System.currentTimeMillis()
                        netlog.addBytes(delta, 0L, dlHost, rec.optString("page"), lastTick2, now2)
                        lastBytes = bytes
                        lastTick2 = now2
                    }
                    persistDownloads()
                }
                val r = if (isPlaylist) hls.download(url, name, onProg) else hls.downloadFile(url, name)
                rec.put("state", if (r.ok) r.stateLabel() else "失败")
                    .put("bytes", r.bytes)
                    .put("segments", r.segments)
                    .put("dir", r.dir)
                // N3：成品发布到系统"下载"目录（合并出来的那个文件），列表里就能看到系统路径
                if (r.ok) {
                    val local = runCatching { java.io.File(r.dir, name) }.getOrNull()
                    if (local != null && local.isFile) {
                        val pub = publishToSystemDownloads(local, name, "video/mp4")
                        if (pub.isNotBlank()) rec.put("path", pub).put("publicPath", pub)
                    }
                }
                if (r.error != null) rec.put("error", r.error)
            } catch (e: Exception) {
                rec.put("state", "失败").put("error", e.message ?: "未知错误")
            }
            persistDownloads()
        }.apply { setName("cdp-hls"); start() }
        return rec
    }

    private fun HlsDownloader.Result.stateLabel(): String =
        if (ok) "完成（$segments 个分片）" else "失败"

    /** 下载目录（嗅探清单里显示给用户看）。**成品会再发布一份到系统"下载"目录**（见 publishToSystemDownloads） */
    fun downloadDir(): String = java.io.File(filesDir, "downloads").absolutePath

    /**
     * 把下好的文件**发布到系统"下载"目录**（用户 N3：下载要下到系统目录，不是应用目录）。
     *
     * Android 10+ 的分区存储下不能直接往 /sdcard/Download 写文件，所以走 MediaStore.Downloads 插入：
     * 插入一条（RELATIVE_PATH = Download/cdp）→ 打开它的输出流把我们下好的字节写进去 → 去掉 pending 标记。
     * 返回系统里的相对路径（如 Download/cdp/xxx.mp4）；失败返回空串（调用方据此如实告诉用户）。
     */
    fun publishToSystemDownloads(local: java.io.File, name: String, mime: String): String {
        return try {
            if (!local.isFile || local.length() <= 0) return ""
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/cdp")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
            ) ?: return ""
            contentResolver.openOutputStream(uri)?.use { out ->
                local.inputStream().use { it.copyTo(out) }
            }
            cv.clear()
            cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)
            "Download/cdp/$name"
        } catch (e: Exception) {
            bridge.log("发布到系统下载目录失败（${e.message}）——文件仍在应用目录：${local.absolutePath}")
            ""
        }
    }

    /** 升级后清一次应用目录里的旧下载（用户 N3：现在下到系统目录了，旧的清掉） */
    private fun purgeOldAppDownloadsOnce() {
        if (settings.getBool("purgedOldDownloads", false)) return
        try {
            val dir = java.io.File(filesDir, "downloads")
            val n = dir.list()?.size ?: 0
            if (n > 0) {
                dir.listFiles()?.forEach { runCatching { it.delete() } }
                bridge.log("已清空应用目录里的旧下载 $n 个（现在默认下到系统「下载/cdp」目录）")
            }
        } catch (_: Exception) {
        }
        settings.set("purgedOldDownloads", true)
    }

    /**
     * 离线 HLS 测试源：由 App 自己的控制口提供，用来验证「嗅探 → 下载 m3u8 → 落分片」
     * 整条链路（不用联网、也不依赖模拟器能不能访问宿主机）。
     */
    /** 大文件测试源（默认 60MB，随机字节、缓存起来；验收"下载中"的进度条用，不联网） */
    fun testBigFile(mb: Int): java.io.File {
        val n = mb.coerceIn(1, 512)
        val f = java.io.File(filesDir, "big_${n}mb.bin")
        if (f.exists() && f.length() == n.toLong() * 1024 * 1024) return f
        val buf = ByteArray(1024 * 1024)
        val rnd = java.util.Random(7)
        java.io.FileOutputStream(f).use { os ->
            for (i in 1..n) {
                rnd.nextBytes(buf)
                os.write(buf)
            }
        }
        return f
    }

    /** 测速用的测试文件（2MB 随机字节，缓存在私有目录；验收不联网） */
    fun testSpeedFile(): java.io.File {
        val f = java.io.File(filesDir, "speed.bin")
        if (!f.exists() || f.length() < 1024 * 1024) {
            val b = ByteArray(2 * 1024 * 1024)
            java.util.Random(42).nextBytes(b)
            f.writeBytes(b)
        }
        return f
    }

    /** 真 TS 的第一个字节一定是 0x47（同步字节）；占位数据不是。 */
    private fun isRealTs(f: java.io.File): Boolean {
        if (!f.exists() || f.length() < 376) return false
        return try {
            f.inputStream().use { it.read() } == 0x47
        } catch (e: Throwable) {
            false
        }
    }

    /** 离线音频测试源：把打包进来的 sample.mp3 复制到私有目录，由控制口按真 http 提供 */
    fun testAudioFile(): java.io.File {
        val dir = java.io.File(filesDir, "testaud").apply { mkdirs() }
        val f = java.io.File(dir, "sample.mp3")
        try {
            if (!f.exists() || f.length() < 1000) {
                assets.open("test/sample.mp3").use { ins -> f.outputStream().use { out -> ins.copyTo(out) } }
            }
        } catch (e: Throwable) {
            bridge.log("音频测试源准备失败：" + e.message)
        }
        return f
    }

    fun testHlsFile(name: String): java.io.File {
        val dir = java.io.File(filesDir, "testhls").apply { mkdirs() }
        val f = java.io.File(dir, name)
        // 分片用内置 ffmpeg 现生成**真的** mpegts 数据（以前这里是占位文本，
        // 结果"用 ffmpeg 把 m3u8 拉成 MP4"根本没法验——ffprobe 会如实报 Invalid data）。
        // 第一次调用生成一次，之后走缓存；生成失败就退回占位（那时测试会如实报错）。
        if (name.matches(Regex("seg\\d+\\.ts")) && !isRealTs(f)) {
            val made = ffmpeg.run(
                "-y -f lavfi -i testsrc=size=160x90:rate=10:duration=2 " +
                    "-f lavfi -i sine=frequency=440:duration=2 " +
                    "-c:v mpeg4 -b:v 200k -c:a aac -b:a 64k -t 2 -f mpegts " + f.absolutePath, 90
            ).optBoolean("ok")
            if (!made || !f.exists() || f.length() < 4096) {
                if (f.exists()) f.delete()
            }
        }
        when {
            name == "index.m3u8" -> f.writeText(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                    "#EXTINF:2.0,\nseg1.ts\n#EXTINF:2.0,\nseg2.ts\n#EXTINF:2.0,\nseg3.ts\n#EXT-X-ENDLIST\n"
            )
            name == "master.m3u8" -> f.writeText(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=320x180\nindex.m3u8\n"
            )
            name == "page.html" -> f.writeText(
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>HLS 测试页</title></head><body>" +
                    "<h1>HLS 测试页</h1><pre id=\"o\">取播放列表中…</pre>" +
                    // 顺手写一条 cookie：用来验证「按域名查 cookie / 全局 cookie / 删单条」
                    "<script>try{document.cookie='cdp_test=1;path=/';}catch(e){}" +
                    "fetch('/api/_test/hls/index.m3u8').then(function(r){return r.text();}).then(function(t){" +
                    "document.getElementById('o').textContent='已取到 ' + t.length + ' 字节' + String.fromCharCode(10) + t;" +
                    // 再像 hls.js 一样把分片都取一遍：这样"分片合并"才有得验（一个频道几百片不该刷屏）
                    "var segs=t.split(String.fromCharCode(10)).filter(function(x){return /\\.ts$/.test(x.trim());});" +
                    "var done=0,bytes=0;window.__CDP_HLS={segs:segs.length,done:0,bytes:0};" +
                    "segs.forEach(function(s,i){fetch(s.trim()).then(function(r2){return r2.arrayBuffer();}).then(function(b){" +
                    "done++;bytes+=b.byteLength;window.__CDP_HLS={segs:segs.length,done:done,bytes:bytes};" +
                    "document.getElementById('o').textContent='取片 '+done+'/'+segs.length+'，共 '+bytes+' 字节';" +
                    "}).catch(function(e){window.__CDP_HLS={segs:segs.length,done:done,error:String(e),bytes:bytes};});});" +
                    "}).catch(function(e){document.getElementById('o').textContent='失败 '+e;});</script>" +
                    "</body></html>"
            )
            name == "link.html" -> f.writeText(
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>外部协议测试页</title></head><body>" +
                    "<h1>外部协议测试页</h1>" +
                    "<a id=\"ext\" href=\"cdptest://open/page?a=1&amp;sessionId=abc123\">外部协议链接</a><br>" +
                    "<a id=\"keep\" href=\"?keep=1\">普通链接</a>" +
                    "</body></html>"
            )
            name.startsWith("seg") -> {
                // 只有"不是真 TS"时才写占位（以前这里无条件覆盖，把上面 ffmpeg 生成的
                // 真分片又盖回假数据了——测试里表现为 ffprobe 报 Invalid data）。
                if (!isRealTs(f)) {
                    // 每个分片内容可判定（第 N 片 = N KB 的同字符填充），方便对账
                    val n = name.removePrefix("seg").substringBefore('.').toIntOrNull() ?: 1
                    f.writeBytes(ByteArray(1024 * n) { ((n + it) % 251).toByte() })
                }
            }
            else -> f.writeText("")
        }
        return f
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        INSTANCE = this
        prefs = getSharedPreferences("cdp", MODE_PRIVATE)
        store = ScriptStore(this)
        nav = NavStore(this)
        settings = SettingsStore(this)
        sniff = Sniffer(this)
        adblock = AdBlock(this)
        migrateWarnHostsOnce()
        vault = PassVault(this)
        cookieDb = CookieDb(this)
        ffmpeg = Ffmpeg(this)
        terminal = Terminal(this, ffmpeg)
        readlog = ReadLog(this)
        netlog = NetLog(this)
        if (wins.isEmpty()) wins.add(WinTab("", "", false))   // 名字留给"页面标题"来填（用户不要"窗口 1"这种名字）
        // 用户报"主页还是那个问题"：把存成内置资源源的那个 home 直接改写成同源那份（一次修好，不用他手动弄）
        try {
            val h = prefs.getString("home", null)
            if (h != null && h.contains("/ui/start.html") && ctrlListening()) {
                prefs.edit().putString("home", startPageUrl()).apply()
                bridge.log("首页地址已从内置资源源换成同源那份（否则读不到小 app 列表）")
            }
        } catch (_: Throwable) {}
        restoreWins()                                        // 第 28 条：窗口列表持久保存（重开还在、不预加载）
        purgeOldAppDownloadsOnce()                           // N3：下到系统目录了，旧的清一次
        // 内置插件（阅读时间 / 纯文本）：只有第一次启动装，之后用户删了就不自动装
        try {
            val seeded = store.seedBundled(this)
            if (seeded > 0) bridge.log("已装入内置插件 $seeded 个（纯文本阅读），可在「脚本」栏里关掉或删除")
            if (store.lastRetired.isNotEmpty()) {
                bridge.log("已停用内置插件：" + store.lastRetired.joinToString("、") +
                    "（按用户要求不再往页面注入 JS；阅读时长改由 App 自己计时）")
            }
        } catch (_: Exception) {
        }
        // 阅读时长：**App 自己计时**（不往网页注入 JS）——用户 2026-09-20 的要求
        pageTimer = PageTimer(readlog) { m -> bridge.log(m) }
        pageTimer.start()
        hls = HlsDownloader(this, { u -> cookieForDownload(u) }, { m -> bridge.log(m) })
        proxy = ProxyRelay(settings, { m -> bridge.log(m) })
        // 中继转发过的字节 → 流量统计（这是唯一能反映"页面真实流量"的口径：开着代理时才算得到）
        proxy.onBytes = { down, up, host, fromMs ->
            try {
                netlog.addProxyBytes(down, up, host, fromMs, System.currentTimeMillis())
            } catch (_: Exception) {
            }
        }
        testUp = TestUpstream({ m -> bridge.log(m) })
        cast = Cast({ m -> bridge.log(m) })
        renderer = TestRenderer({ m -> bridge.log(m) })
        http = HttpControl(this)
        bridge = Bridge(this, store, http)
        http.bridge = bridge
        agentSource = loadAsset("inject/cdp-agent.js")

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)

        buildUi()
        // N1：无障碍覆盖层已删（用户要求），小点只画在页面里 —— 点小点开小窗由页面那边回调，
        // 这里不再需要 DotBus 的 hook（原来那套是给无障碍服务推屏幕坐标用的）。
        setupBrowser()
        applyUserAgent()
        applyProxy()
        setupConsole()
        loadDownloads()
        bridge.log("CDP ${BuildConfig.VERSION_NAME} 启动；页面脚本 ${agentSource.length} 字节")
        bridge.log("脚本导入目录: ${store.importDir.absolutePath}")

        registerDocumentStart()
        loadUrl(startPage())
        browserStateJson()   // 先把状态快照填上，外部接口从第一秒起就能读到东西
        // 控制口随应用启动就开（只绑 127.0.0.1），这样外部脚本不用先「叫醒」它
        try {
            val st = http.start(false)
            bridge.log("控制口已启动: http://127.0.0.1:${st.optInt("port")}/api/status")
        } catch (e: Exception) {
            bridge.log("控制口启动失败: ${e.message}")
        }

        intent?.let { handleIntent(it) }
        startMetricsPoller()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(it: Intent) {
        val u = it.dataString
        if (!u.isNullOrEmpty() && (u.startsWith("http"))) loadUrl(u)
    }

    override fun onDestroy() {
        INSTANCE = null
        try { metricsTick?.let { main.removeCallbacks(it) } } catch (_: Exception) {}
        try { proxy.stop() } catch (_: Exception) {}
        try { http.stop() } catch (_: Exception) {}
        // 主动释放 WebView：安装覆盖/任务重启的时序里，不退场的 WebView 会在进程里留成
        // 「隐藏的同名页面」，之后用 CDP 连进来很容易连错那一个
        try {
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.destroy()
        } catch (_: Exception) {}
        try {
            (console.parent as? ViewGroup)?.removeView(console)
            console.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 界面

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#161C22"))
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(46)))

        fun icon(label: String, size: Int, on: (View) -> Unit): TextView {
            val t = TextView(this)
            t.text = label
            t.setTextColor(Color.parseColor("#E6EDF3"))
            t.textSize = size.toFloat()
            t.gravity = Gravity.CENTER
            t.setPadding(dp(8), 0, dp(8), 0)
            // 触摸目标别太小：88px 宽在手机上只有 32dp，边缘的点击很容易落空
            t.minimumWidth = dp(44)
            t.minimumHeight = dp(44)
            t.gravity = Gravity.CENTER
            t.setOnClickListener(on)
            return t
        }

        // 用户反馈：工具栏要极简。前进/后退收进 ☰，这里只留刷新。
        reloadBtn = icon("⟳", 17) { reloadBrowser() }
        bar.addView(reloadBtn, LinearLayout.LayoutParams(-2, -1))

        urlBar = EditText(this)
        urlBar.setSingleLine(true)
        urlBar.hint = "网址或搜索词"
        urlBar.setTextColor(Color.parseColor("#E6EDF3"))
        urlBar.setHintTextColor(Color.parseColor("#7A8794"))
        urlBar.setTextSize(14f)
        urlBar.setBackgroundColor(Color.parseColor("#0E1318"))
        urlBar.setPadding(dp(10), 0, dp(10), 0)
        urlBar.inputType = InputType.TYPE_TEXT_VARIATION_URI
        urlBar.imeOptions = EditorInfo.IME_ACTION_GO
        // 触摸聚焦要显式打开：实测程序化创建的 EditText 在这个主题下点上去不拿焦点，
        // 表现就是「地址栏点了没反应、键盘不弹、打字进不去」。
        urlBar.isFocusable = true
        urlBar.isFocusableInTouchMode = true
        urlBar.isClickable = true
        // 拿到焦点就全选 —— 这是地址栏的标准行为，也是唯一可靠的做法：
        // 在点击回调里补 selectAll() 会被随后的光标定位覆盖（实测打字仍插在 URL 中间）
        urlBar.setSelectAllOnFocus(true)
        urlBar.setOnClickListener {
            urlBar.requestFocus()
            try {
                val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.showSoftInput(urlBar, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {
            }
        }
        // 反馈 #33：点地址栏 -> 其他按键让位、地址栏占满一条、输入法弹出；失焦就回到原状
        urlBar.setOnFocusChangeListener { _, hasFocus ->
            val others = listOfNotNull(
                menuAnchor, homeBtn, muteBtn, winBtn, reloadBtn, recDot, incogDot
            )
            if (hasFocus) {
                // 顺序很关键：**先记录**谁可见，再隐藏。
                // 反过来写就是"先全隐藏、再记录"，记下来的全是不可见 → 失焦后工具栏再也回不来（实测踩过）
                BarState.wasVisible = others.map { it.visibility == View.VISIBLE }
                others.forEach { it.visibility = View.GONE }
                try {
                    val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    imm?.showSoftInput(urlBar, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } catch (_: Exception) {
                }
            } else {
                others.forEachIndexed { i, v ->
                    v.visibility = if (BarState.wasVisible.getOrElse(i) { true } &&
                        !(v === incogDot && !settings.incognito()) && !(v === recDot && !bridge.recording)
                    ) View.VISIBLE else View.GONE
                }
            }
        }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateSmart(urlBar.text.toString())
                // 导航完就收起焦点：地址栏聚焦态会隐藏其它按键（反馈 #33），
                // 不主动收起来的话，用户/自动化都会"看着按钮不见了"
                main.postDelayed({
                    try {
                        urlBar.clearFocus()
                        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        imm?.hideSoftInputFromWindow(urlBar.windowToken, 0)
                    } catch (_: Exception) {
                    }
                }, 250)
                true
            } else false
        }
        bar.addView(urlBar, LinearLayout.LayoutParams(0, -1, 1f).apply {
            leftMargin = dp(4); rightMargin = dp(4)
        })

        recDot = TextView(this)
        recDot.text = "●"
        recDot.setTextColor(Color.parseColor("#FF4444"))
        recDot.textSize = 14f
        recDot.visibility = View.GONE
        bar.addView(recDot, LinearLayout.LayoutParams(-2, -1))

        incogDot = TextView(this)
        incogDot.text = "◑"
        incogDot.textSize = 13f
        incogDot.visibility = if (settings.incognito()) View.VISIBLE else View.GONE
        bar.addView(incogDot, LinearLayout.LayoutParams(-2, -1))

        // 多窗口切换（放工具栏，理由同上：菜单项不能太多）
        winBtn = icon("▤", 18) { showWinMenu() }
        bar.addView(winBtn, LinearLayout.LayoutParams(-2, -1))
        // 静音键要有反馈（用户反馈 #13）：图标随状态变，点一下还会在左下角说一句
        muteBtn = icon("♪", 18) { toggleMute() }
        bar.addView(muteBtn, LinearLayout.LayoutParams(-2, -1))
        // 主页入口放工具栏（不放 ☰ 菜单：菜单项一多，第一项会被挤出屏幕，实测会点错）
        // ⌂ = 首页（手机桌面式的小 app 网格）。从**本机控制口**取（同源 → 页面能直接读写列表，也不用往页面注入东西）；
        // 控制口万一没起来，退回内置资源那份（同一个文件，只是读不到列表）。
        homeBtn = icon("⌂", 18) { loadUrl(startPageUrl()) }
        bar.addView(homeBtn, LinearLayout.LayoutParams(-2, -1))
        menuAnchor = icon("☰", 18) { showMainMenu() }
        bar.addView(menuAnchor, LinearLayout.LayoutParams(-2, -1))

        // 用户明确要求：工具栏下面**不要**那条「窗口1 ✕ 窗口2 ✕ ＋」的窗口栏；
        // 新建 / 切换 / 关闭 全部收进地址栏后面那个「▤」图标里（showWinMenu），窗口按页面标题叫名字。

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        col.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

        // 屏蔽名单命中时挂的警告条（在 WebView 上面，默认隐藏）
        warnBar = TextView(this)
        warnBar.textSize = 12f
        warnBar.setTextColor(0xFFFFD7A3.toInt())
        warnBar.setBackgroundColor(0xFF3A2A12.toInt())
        warnBar.setPadding(dp(10), dp(6), dp(10), dp(6))
        warnBar.visibility = View.GONE
        col.addView(warnBar, LinearLayout.LayoutParams(-1, -2))
        // 登录信息保存提示条（第 4 条：密码登录时提示用户是否保存）
        vaultBar = TextView(this)
        vaultBar.textSize = 12f
        vaultBar.setTextColor(0xFFBEE1FF.toInt())
        vaultBar.setBackgroundColor(0xFF16324B.toInt())
        vaultBar.setPadding(dp(10), dp(6), dp(10), dp(6))
        vaultBar.visibility = View.GONE
        vaultBar.setOnClickListener { savePageLogin() }
        col.addView(vaultBar, LinearLayout.LayoutParams(-1, -2))

        browser = WebView(this)
        col.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))

        // 左下角"连接过程"提示：一行、实时刷新、最多一行不占地（反馈 #28）
        connBar = TextView(this)
        connBar!!.textSize = 11.5f
        connBar!!.setTextColor(Color.parseColor("#9FD3FF"))
        connBar!!.setBackgroundColor(0xCC0A0F14.toInt())
        connBar!!.setPadding(dp(6), dp(2), dp(6), dp(2))
        connBar!!.isSingleLine = true
        connBar!!.ellipsize = android.text.TextUtils.TruncateAt.END
        connBar!!.visibility = View.GONE
        connBar!!.setOnClickListener { connBar!!.visibility = View.GONE }
        col.addView(connBar, LinearLayout.LayoutParams(-1, -2))

        // 控制台（浮层，从上方盖住，露出一点页面方便边看边操作）
        overlay = FrameLayout(this)
        overlay.visibility = View.GONE
        val scrim = View(this)
        scrim.setBackgroundColor(Color.parseColor("#99000000"))
        scrim.setOnClickListener { closeConsole() }
        overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        console = WebView(this)
        // 控制台页面是 APK 内的 assets：WebView **默认会缓存**它，于是换了构件（重装 APK）后
        // 仍可能执行上一版的 app.js/index.html —— 表现就是"改了界面却看不出变化"、验收读到旧行为。
        // 这里对控制台一律不吃缓存，并在首次创建时清一次缓存。
        runCatching {
            console.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            console.settings.domStorageEnabled = true
            console.settings.javaScriptEnabled = true
            console.settings.mediaPlaybackRequiresUserGesture = false
            console.clearCache(true)      // 实例方法：把旧构件留在缓存里的 assets 清掉
        }
        val cp = FrameLayout.LayoutParams(-1, -1)
        cp.topMargin = 0
        overlay.addView(console, cp)
        // 浮层从工具栏**下面**开始：以前它是全屏的，打开控制台后上面那排键
        // （◀ ▶ ⟳ 地址栏 ☰）全被浮层盖住 —— 点了要么没反应，要么把控制台关掉。
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(46) })

        setContentView(root)
        // 前后台分段跟踪（阅读/流量的柱状图要"下面前台、上面后台"两色）
        try { application.registerActivityLifecycleCallbacks(FgWatcher) } catch (_: Exception) {}
        // 启动时**不要**让地址栏抢焦点：否则一进 App 就弹键盘，而且"聚焦态隐藏其它按键"会让工具栏看着少了一半
        try {
            root.isFocusableInTouchMode = true
            root.requestFocus()
            urlBar.clearFocus()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ 下拉菜单（☰）

    /**
     * ☰ 打开的是下拉菜单，不是直接开控制台：
     * 「开始监听」这类常用动作放在第一层，控制台/历史/书签/脚本/接口都能从这一层直达。
     */
    fun showMainMenu() {
        // 锚点必须是 ☰ 自己：以前锚在 android.R.id.content（整屏）上，
        // 菜单被算到屏幕左下角、压成一条 33px 高的细条，点不到任何一项。
        val anchor = menuAnchor ?: findViewById<View>(android.R.id.content) ?: return
        val popup = android.widget.PopupMenu(this, anchor)
        // 用户反馈：图标要极简（不用彩色 emoji）；前进/后退/AI/纯文本/网络都收进这里
        val st = try { netStatsJson() } catch (e: Exception) { JSONObject() }
        // 反馈 #10：以前只统计"应用自己搬的字节"，用户看视频时它一动不动（像没在监听）。
        // 现在用系统按 UID 的累计流量（WebView/媒体流的字节都算在这个 UID 上），减去启动时的基线。
        val rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        val tx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        val rxD = if (rx >= 0) (rx - trafficBaseRx).coerceAtLeast(0) else st.optLong("bytesDown", 0)
        val txD = if (tx >= 0) (tx - trafficBaseTx).coerceAtLeast(0) else st.optLong("bytesUp", 0)
        val netLine = "⇄ 网络　↑" + humanBytes(txD) + " ↓" + humanBytes(rxD) +
            "　（系统按 UID 统计，启动以来）"
        // 菜单这一条也要**跟着控制台里选的录制方式**：以前这里固定不带 mode，Bridge 就退回默认 element，
        // 于是"在控制台选了坐标录制，再用 ☰ 菜单开始监听"录下来的还是元素步 —— 用户报的"坐标录制没效果"就是这个。
        val recModeNow = if (bridge.recordModeNow() == "coord") "coord" else "element"
        popup.menu.add(
            0, 1, 0,
            if (bridge.recording) "■ 停止监听并保存"
            else ("● 开始监听（" + (if (recModeNow == "coord") "坐标录制" else "元素录制") + "）")
        )
        popup.menu.add(0, 2, 1, "▤ 控制台 / 状态")
        popup.menu.add(0, 3, 2, "◀ 后退")
        popup.menu.add(0, 4, 3, "▶ 前进")
        popup.menu.add(0, 5, 4, "≡ 纯文本视图（插件）")
        popup.menu.add(0, 6, 5, "✦ AI 助手面板（插件）")
        popup.menu.add(0, 7, 6, netLine)
        popup.menu.add(0, 8, 7, "↗ 分享本页")
        popup.menu.add(0, 9, 8, "◎ 拾取元素（不用看源码）")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (bridge.recording) {
                        main.post { bridge.dispatch("rec.stop", JSONObject()) { r -> bridge.log("停止录制: ${r.toString().take(200)}") } }
                    } else {
                        main.post {
                            bridge.dispatch(
                                "rec.start",
                                JSONObject().put("name", "录制脚本").put("mode", recModeNow)
                            ) { r ->
                                bridge.log("开始录制: ${r.toString().take(200)}")
                            }
                        }
                    }
                }
                2 -> openConsole()
                3 -> backIfPossible()
                4 -> forwardIfPossible()
                5 -> main.post { clickPageButton("cdp-pt-btn") }
                6 -> main.post { clickPageButton("cdp-assist-btn") }
                7 -> openConsole("net")
                8 -> main.post { shareCurrent() }
                9 -> main.post {
                    bridge.dispatch("picker.arm", JSONObject()) { r ->
                        bridge.log("拾取元素: ${r.toString().take(120)}")
                    }
                }
            }
            true
        }
        popup.show()
    }

    /** 点页面上某个插件按钮（纯文本 / AI 面板这些"藏在 ☰ 里"的入口，反馈 #30） */
    private fun clickPageButton(id: String) {
        browser.evaluateJavascript(
            "(function(){var b=document.getElementById(${JSONObject.quote(id)});" +
                "if(!b)return '缺插件';b.click();return 'ok';})()", null
        )
        connStep("已触发页面里的插件按钮：" + id)
    }

    private fun humanBytes(n: Long): String = when {
        n >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1fM", n / 1048576.0)
        n >= 1024 -> String.format(java.util.Locale.US, "%.0fK", n / 1024.0)
        else -> n.toString() + "B"
    }

    fun openConsole(tab: String? = null) {
        overlay.visibility = View.VISIBLE
        val st = JSONObject.quote(browserStateJson().toString())
        console.evaluateJavascript("window.__cdpState && window.__cdpState($st)", null)
        if (!tab.isNullOrBlank()) switchConsoleTab(tab)
    }

    fun switchConsoleTab(tab: String) {
        main.post {
            console.evaluateJavascript("window.__cdpTab && window.__cdpTab(${JSONObject.quote(tab)})", null)
        }
    }

    fun closeConsole() { overlay.visibility = View.GONE }

    fun pushConsoleLine(s: String) {
        main.post {
            console.evaluateJavascript("window.__cdpLog && window.__cdpLog(${JSONObject.quote(s)})", null)
        }
    }

    fun onPageClickEvent(o: JSONObject) {
        main.post {
            console.evaluateJavascript("window.__cdpEvent && window.__cdpEvent(${JSONObject.quote(o.toString())})", null)
        }
    }

    fun onRecordStep(o: JSONObject) {
        main.post {
            console.evaluateJavascript("window.__cdpRecordStep && window.__cdpRecordStep(${JSONObject.quote(o.toString())})", null)
        }
    }

    fun onPageNav(u: String) {
        main.post {
            urlBar.setText(u)
            console.evaluateJavascript("window.__cdpState && window.__cdpState(${JSONObject.quote(browserStateJson().toString())})", null)
        }
    }

    // ------------------------------------------------------------------ 浏览器

    private fun startPage(): String {
        val last = prefs.getString("home", null)?.takeIf { it.isNotBlank() }
        // 用户报"主页还是那个问题"的真因：以前把**内置资源源**的 start.html 存成了 home，
        // 启动就打开它 —— 那份与设备上的控制口**不同源**，小 app 列表读不出来（用户看到的就是空白）。
        // 控制口在听时，一律把它换成同源那份。
        if (last != null && last.contains("/ui/start.html") && ctrlListening()) return startPageUrl()
        return last ?: startPageUrl()
    }

    private fun setupBrowser() {
        val s = browser.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setGeolocationEnabled(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // 加固：不允许文件/内容访问，混合内容一律不加载
        s.allowFileAccess = false
        s.allowContentAccess = false
        if (Build.VERSION.SDK_INT >= 30) s.setAllowFileAccessFromFileURLs(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= 29) s.forceDark = WebSettings.FORCE_DARK_OFF

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // 首页（start.html）就加载在**这个** WebView 里，原生桥必须也挂上：
        // 只挂控制台那个 WebView 的话，首页里 window.cdpNative 不存在，
        // 小 app 网格会静默拿不到数据（议题 #1）。
        attachNativeBridge(browser)

        // 下载：长按链接后菜单里的「下载链接」走这个回调。
        // 按用户要求**不交给系统下载器**：App 自己拉、自己存到私有目录，
        // 不弹系统通知、不申请存储权限、不依赖外部 App；进度与结果都写进 App 日志。
        browser.setDownloadListener { url, _userAgent, contentDisposition, mimeType, size ->
            startDownload(url, contentDisposition, mimeType, size)
        }

        browser.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                intercept(req.url.toString())

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (u.startsWith(EVENT_ORIGIN)) return true
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file:") ||
                    u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:") ||
                    u.startsWith("appassets.")
                ) return false
                // 非 http(s) 协议一律拦下自己处理。交给 WebView 加载的话它不认识这些协议，
                // 会直接跳 chrome-error://chromewebdata/「Webpage not available」——
                // 表现就是「点了链接就报错」（B 站的 bilibili://…h5awaken=…sessionId=… 就是这种，
                // 报错里那串 sessionId 只是深链参数，不是会话失效）。
                return handleExternalScheme(u)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                // 用户报的"首页那个 APP 添加了不渲染"（第二次报）：内置资源源 `https://appassets…/ui/start.html`
                // 与**本机控制口不同源**，混合内容策略会把 fetch 拦掉 → 页面在、不报错、列表永远空。
                // 这个来源的页面本来就拿不到原生桥（桥只给控制台自己的页面，安全设计），所以**在这里把它导到同源那份**。
                if (url.contains("$ASSET_ORIGIN/ui/start.html") && ctrlListening() && url != startPageUrl()) {
                    connStep("首页换成同源入口（才读得到小 app 列表）")
                    view.post { runCatching { view.loadUrl(startPageUrl()) } }
                    return
                }
                connStep("打开 " + url.replace("https://", "").replace("http://", "").take(48))
                lastPageUrl = url
                try { pageTimer.onPage(url, "") } catch (_: Exception) {}
                if (!docStartOk) {
                    // 低版本 WebView 没有 document-start 注入，退而求其次（时机偏晚，但能用）
                    view.evaluateJavascript(agentSource, null)
                }
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                connStep("完成 " + url.replace("https://", "").replace("http://", "").take(48))
                progress.visibility = View.GONE
                urlBar.setText(url)
                refreshMetrics()
                // 记导航历史（跳过 about:blank 与虚拟源由 NavStore 负责）
                // 记导航历史（跳过 about:blank 与虚拟源由 NavStore 负责）；隐身模式不记
                lastPageUrl = url
                lastTitle = view.title ?: ""
                try { pageTimer.onPage(url, lastTitle) } catch (_: Exception) {}
                // 窗口的名字＝**页面标题**（用户要求：不能一律叫"窗口一"）。
                // 标题一变就记到当前窗口上，这样菜单/接口/以后任何入口看到的都是真实标题。
                synchronized(wins) {
                    if (activeWin in 0 until wins.size) {
                        wins[activeWin].title = lastTitle
                        if (url.isNotBlank()) wins[activeWin].url = url
                    }
                }
                updateWinIndicator()
                updateWarnBar(url)
                pushCosmetic(view)
                // 静音开着：新页面自动出声之前先按住（页面自己会放声音，只能按）
                if (muted) {
                    try {
                        view.evaluateJavascript("window.__CDP && window.__CDP.cmd({op:'mute',on:true})", null)
                    } catch (_: Exception) {
                    }
                }
                if (!settings.incognito()) {
                    try { nav.addHistory(url, view.title ?: "") } catch (_: Exception) {}
                }
                main.post { console.evaluateJavascript("window.__cdpState && window.__cdpState(${JSONObject.quote(browserStateJson().toString())})", null) }
            }
        }

        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                // 只把页面里的错误/警告转进日志，普通 log 不进（免得刷屏）
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    bridge.log("页面错误: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                }
                return true
            }

            /** target=_blank 之类的弹窗：不真开新窗口，直接在同一个 WebView 里打开 */
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                // 长按菜单的「在新标签页打开」/页面的 window.open 会走到这里。
                // 本 App 是单页面壳子，所以「新窗口」= 在同一个 WebView 里打开，
                // 但要**用完就销毁**那个临时 WebView，否则每点一次就漏一个隐藏的 WebView。
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                        val u = req.url.toString()
                        bridge.log("新窗口请求 → 在当前页打开: $u")
                        loadUrl(u)
                        main.post { try { v.destroy() } catch (_: Exception) {} }
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = tmp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView, filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean = false
        }
    }

    // ------------------------------------------------------------------ 应用内下载
    //
    // 刻意**不用系统下载器**（用户明确要求）：不弹系统通知、不申请存储权限、不依赖外部 App。
    // 自己用 HttpURLConnection 拉，存到 filesDir/downloads/<文件名>，记录落盘 files/downloads.json。
    // 带上 WebView 的 cookie，所以登录态页面里的文件也能下。

    private fun loadDownloads() {
        try {
            if (downloadsFile.exists()) {
                val arr = org.json.JSONArray(downloadsFile.readText())
                synchronized(downloads) {
                    downloads.clear()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { downloads.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        reapStaleDownloads()   // 上次被杀留下的"进行中"标成中断
    }

    /** 启动时收拾僵尸记录：上次被杀留下的"进行中"标成中断（否则界面上永远显示在转） */
    private fun reapStaleDownloads() {
        var n = 0
        synchronized(downloads) {
            downloads.forEach { o ->
                if (o.optString("state") == "进行中") {
                    o.put("state", "中断（应用重启，未完成）")
                    n++
                }
            }
        }
        if (n > 0) { persistDownloads(); logMain("清理了 $n 条上次没下完的记录（标成中断）") }
    }

    private fun persistDownloads() {
        try {
            val arr = org.json.JSONArray()
            synchronized(downloads) { downloads.forEach { arr.put(it) } }
            downloadsFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    /** 下载入口：网页里的下载链接、控制台「下载」页签、HTTP 接口都走这一条 */
    fun startDownload(
        url: String,
        contentDisposition: String? = null,
        mimeType: String? = null,
        declaredSize: Long = 0L,
        nameOverride: String? = null
    ) {
        if (url.isBlank()) return
        // App 自己的虚拟源（appassets/cdp-event/cdp-meta）只活在 WebView 的请求回调里，
        // 外部下载器（HttpURLConnection）解析不了这个主机名 —— 直接给一句人话，
        // 别让用户看到 UnknownHostException 还以为是网络坏了（本轮实测踩到过）。
        if (url.contains("appassets.androidplatform.net") || url.contains("cdp-event.local") || url.contains("cdp-meta.local")) {
            bridge.log("下载：这是一个「App 内部虚拟地址」(" + url.take(60) + "…)，外部下载器取不到 —— 请用真实的 http(s) 地址（本机测试源用 http://127.0.0.1:8848/...）")
            return
        }
        // log.md 第 6 条："提供 aria2 等等下载方式" —— 配了 aria2 就把地址交给它（记得把 gid 存进记录，别假装自己在下）
        if (settings.downloadVia() == "aria2") {
            // 注意：这里必须**开线程去提交**。Bridge/界面调用是在主线程上的，
            // 直接在主线程发 HTTP 会被 Android 拦下（NetworkOnMainThreadException，实测踩到：
            // 日志里显示"aria2 没接"，然后悄悄退回应用内下载）。
            val rec = JSONObject()
                .put("name", nameOverride ?: url.substringAfterLast('/').take(60))
                .put("url", url).put("bytes", 0L).put("total", declaredSize)
                .put("state", "正在交给 aria2…")
                .put("ts", System.currentTimeMillis()).put("page", lastPageUrl).put("via", "aria2")
            synchronized(downloads) {
                downloads.add(rec)
                while (downloads.size > 300) downloads.removeAt(0)
            }
            persistDownloads()
            Thread {
                val r = Aria2.submit(
                    settings.aria2Rpc(), settings.aria2Token(), url, settings.aria2Dir(),
                    nameOverride ?: ""
                )
                synchronized(downloads) {
                    if (r.optBoolean("ok")) {
                        rec.put("state", "已交给 aria2（gid " + r.optString("gid") + "）")
                            .put("gid", r.optString("gid"))
                    } else {
                        rec.put("state", "aria2 没接：" + r.optString("error"))
                    }
                }
                persistDownloads()
                bridge.log(
                    if (r.optBoolean("ok")) "已交给 aria2：gid " + r.optString("gid") + "（" + url.take(70) + "）"
                    else "aria2 没接（" + r.optString("error") + "）"
                )
            }.apply { name = "cdp-aria2-submit"; isDaemon = true }.start()
            return
        }
        // 用户反馈 #20：下载器可以选"系统下载器"；默认仍是应用内自己下（log.md 第 6 条要求）
        if (settings.downloadViaSystem()) {
            try {
                val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, nameOverride ?: "")
                val dm = getSystemService(android.app.DownloadManager::class.java)
                dm?.enqueue(req)
                bridge.log("已交给系统下载器：${url.take(80)}")
                return
            } catch (e: Exception) {
                bridge.log("系统下载器用不了（${e.message}），改回应用内下载")
            }
        }
        val name = if (!nameOverride.isNullOrBlank()) nameOverride else try {
            android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (_: Exception) {
            "download.bin"
        }
        val rec = JSONObject()
            .put("id", "d" + System.currentTimeMillis() + "-" + (System.nanoTime() % 997))
            .put("name", name).put("url", url).put("bytes", 0L).put("total", declaredSize)
            .put("state", "进行中").put("ts", System.currentTimeMillis())
            .put("incognito", settings.incognito())
            .put("page", lastPageUrl)          // 同上：普通下载也记来源页
        synchronized(downloads) {
            downloads.add(rec)
            while (downloads.size > 50) downloads.removeAt(0)
        }
        persistDownloads()
        bridge.log("下载请求: $url（$mimeType，声明大小 ${if (declaredSize > 0) "$declaredSize 字节" else "未知"}）→ $name")

        startDownloadThread(rec, url, name, mimeType, declaredSize, 0L)
    }

    /** N6：被暂停的下载 id（暂停＝留住已下的字节、线程退出；点 ▶ 再按 Range 接着下） */
    private val dlPaused = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * N6：一个状态键 —— 「正在下」点一下＝暂停，「已暂停」点一下＝继续。
     * 用户要求：暂停和继续**合并成一个位置**，它只描述状态；✕ 才是删。
     */
    fun toggleDownloadPause(id: String): JSONObject {
        val rec = synchronized(downloads) { downloads.firstOrNull { it.optString("id") == id } }
            ?: return JSONObject().put("ok", false).put("error", "没有这条下载")
        val st = rec.optString("state")
        if (st == "完成" || st.contains("失败")) {
            return JSONObject().put("ok", false).put("error", "这条已经结束了，只能删")
        }
        if (dlPaused.contains(id)) {
            dlPaused.remove(id)
            rec.put("state", "进行中")
            persistDownloads()
            val at = rec.optLong("bytes")
            logMain("继续下载：${rec.optString("name")}（从 $at 字节接着下）")
            startDownloadThread(rec, rec.optString("url"), rec.optString("name"), null, rec.optLong("total"), at)
            return JSONObject().put("ok", true).put("paused", false).put("state", "进行中")
        }
        dlPaused.add(id)
        rec.put("state", "已暂停")
        persistDownloads()
        return JSONObject().put("ok", true).put("paused", true).put("state", "已暂停")
    }

    /** 起一个下载线程（startAt > 0 表示续传） */
    private fun startDownloadThread(rec: JSONObject, url: String, name: String, mimeType: String?, declaredSize: Long, startAt: Long) {
        Thread {
            val dir = java.io.File(filesDir, "downloads").apply { mkdirs() }
            val out = java.io.File(dir, name)
            // 先下到临时文件，成功后再改名 —— 直接写目标文件会在开写时就把它截断：
            // ① 中途失败会毁掉上一份；② 「把文件下到它自己身上」（重名）时，服务端读到一半就没了
            // （实测 ProtocolException: unexpected end of stream）。
            val tmp = java.io.File(dir, "$name.part")
            var conn: java.net.HttpURLConnection? = null
            // N6：续传 —— 已下多少就从多少接着要（Range）。服务器不认就从头来，并如实记一笔。
            var got = if (startAt > 0 && tmp.exists()) tmp.length() else 0L
            try {
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    try {
                        android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                            setRequestProperty("Cookie", it)
                        }
                    } catch (_: Exception) {
                    }
                    setRequestProperty("User-Agent", "CDP-Android")
                    if (got > 0) setRequestProperty("Range", "bytes=$got-")
                }
                var code = conn.responseCode
                if (got > 0 && code == 200) {
                    // 服务器不支持 Range：从头下（不许假装续传成功）
                    runCatching { tmp.delete() }
                    got = 0
                    rec.put("bytes", 0L)
                    logMain("这台服务器不支持续传，从头下：$url")
                }
                if (code !in 200..299 && code != 206) {
                    rec.put("state", "失败").put("error", "HTTP $code")
                    persistDownloads()
                    logMain("下载失败：HTTP $code　$url")
                    return@Thread
                }
                // N6：**续传时 Content-Length 是"还剩多少"，不是文件总长** —— 拿它当总长会误判"长度不符"。
                // 只有从头下（got==0）时才用 Content-Length 认总长；续传时总长沿用记录里原来那个。
                val total = if (got == 0L && conn.contentLengthLong > 0) conn.contentLengthLong
                            else rec.optLong("total").takeIf { it > 0 } ?: declaredSize
                if (total > 0) rec.put("total", total)
                var lastTick = 0L
                conn.inputStream.use { input ->
                    // 续传时**追加**写入，否则会把自己截断
                    (if (got > 0) java.io.FileOutputStream(tmp, true) else tmp.outputStream()).use { os ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            // N6：暂停 —— 留住已下的字节、把状态写成「已暂停」，线程退出；点 ▶ 再接着下
                            if (dlPaused.contains(rec.optString("id"))) {
                                runCatching { os.flush() }
                                rec.put("state", "已暂停").put("bytes", got)
                                persistDownloads()
                                logMain("已暂停：$name（已下 $got 字节，点 ▶ 接着下）")
                                return@Thread
                            }
                            val n = input.read(buf)
                            if (n > 0) netlog.addBytes(n.toLong(), 0L, try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }, rec.optString("page"), System.currentTimeMillis() - 2000, System.currentTimeMillis())
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            got += n
                            rec.put("bytes", got)
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 1000) {   // 进度别写太勤，1 秒一次
                                lastTick = now
                                persistDownloads()
                            }
                        }
                    }
                }
                // 长度对不上就当失败（半截文件比失败更糟：看着像下好了）
                if (total > 0 && got != total) {
                    tmp.delete()
                    rec.put("state", "失败").put("error", "长度不符：收到 $got / 预期 $total")
                    persistDownloads()
                    logMain("下载失败：长度不符（收到 $got / 预期 $total）")
                    return@Thread
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    // 改名失败（少见）：退而求其次拷过去，别让用户拿不到文件
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                // 发布到系统"下载"目录（N3）：拿到系统路径就记它，拿不到就如实记应用内路径
            val pub = publishToSystemDownloads(out, name, mimeType ?: "")
            // 发布成功后**默认不留应用内副本**（用户 N3："下载要下到系统目录，而不是应用目录"）。
            // 想留副本的人可以在设置里打开 keepLocalCopy。
            if (pub.isNotBlank() && !settings.getBool("keepLocalCopy", false)) {
                runCatching { out.delete() }
                runCatching { java.io.File(dir, "$name.part").delete() }
            }
            rec.put("bytes", got).put("state", "完成")
                .put("path", if (pub.isNotBlank()) pub else out.absolutePath)
                .put("publicPath", pub)
                    .put("ts", System.currentTimeMillis())
                persistDownloads()
                logMain("下载完成：$name（$got 字节${if (total > 0) " / 预期 $total" else ""}）→ ${out.absolutePath}")
            } catch (e: Exception) {
                try { tmp.delete() } catch (_: Exception) {}
                rec.put("state", "失败").put("error", "${e.javaClass.simpleName}: ${e.message}")
                persistDownloads()
                logMain("下载失败：${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; setName("cdp-download") }.start()
    }

    /** 删除一条下载（连文件一起删） */
    fun deleteDownload(name: String, keepFile: Boolean = false): Boolean {
        var hit = false
        synchronized(downloads) {
            val it = downloads.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (r.optString("name") == name) { it.remove(); hit = true }
            }
        }
        if (hit) {
            // keepFile=true 只删记录，文件留着（用户要"删历史但保留文件"）
            if (!keepFile) {
                try { java.io.File(java.io.File(filesDir, "downloads"), name).delete() } catch (_: Exception) {}
                // 半路暂停留下的 .part 也要清（不然会一直占着磁盘）
                try { java.io.File(java.io.File(filesDir, "downloads"), "$name.part").delete() } catch (_: Exception) {}
                // N3 之后成品是**发布到系统「下载/cdp」**的那份 —— 用户勾了"删除本地文件"就必须把它删掉。
                // （以前只删应用内那份，而那份发布后已经删了 → 看着"删了但文件还在"，实测抓到）
                try {
                    val sel = android.provider.MediaStore.Downloads.DISPLAY_NAME + "=? AND " +
                        android.provider.MediaStore.Downloads.RELATIVE_PATH + "=?"
                    contentResolver.delete(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel,
                        arrayOf(name, "Download/cdp/")
                    )
                    bridge.log("已删除系统下载目录里的文件：Download/cdp/$name")
                } catch (e: Throwable) {
                    bridge.log("删系统下载目录里的文件失败：${e.javaClass.simpleName}: ${e.message}")
                }
            }
            persistDownloads()
        }
        return hit
    }

    private fun logMain(s: String) {
        main.post { try { bridge.log(s) } catch (_: Exception) {} }
    }

    /** 应用内下载记录（最新的在前） */
    fun downloadsJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        val snap = synchronized(downloads) { ArrayList(downloads) }
        for (i in (snap.size - 1) downTo 0) {
            if (arr.length() >= 50) break
            arr.put(snap[i])
        }
        return arr
    }

    /** 已下载文件（供控制口读取/取回）；找不到返回 null */
    fun downloadFile(name: String): java.io.File? {
        val f = java.io.File(java.io.File(filesDir, "downloads"), name)
        return if (f.isFile) f else null
    }

    fun loadUrl(u: String) {
        main.post {
            try {
                browser.loadUrl(u)
            } catch (e: Exception) {
                bridge.log("打开失败: ${e.message}")
            }
        }
    }

    fun searchUrl(q: String): String = settings.searchUrl(q)

    /** 输入框内容判定：像网址就开，否则当搜索词 */
    fun navigateSmart(input: String) {
        val v = input.trim()
        if (v.isEmpty()) return
        when {
            v.startsWith("http://") || v.startsWith("https://") -> loadUrl(v)
            v.startsWith("about:") || v.startsWith("data:") -> loadUrl(v)
            Regex("^[\\w.-]+\\.[a-zA-Z]{2,}(/.*)?$").matches(v) -> loadUrl("https://$v")
            else -> loadUrl(searchUrl(v))
        }
    }

    fun backIfPossible() {
        if (browser.canGoBack()) browser.goBack() else logMain("没有上一页了")
    }

    fun forwardIfPossible() { if (browser.canGoForward()) browser.goForward() }

    fun reloadBrowser() { browser.reload() }

    fun setRecordingUi(on: Boolean) {
        main.post { recDot.visibility = if (on) View.VISIBLE else View.GONE }
    }

    // ------------------------------------------------------------------ 资源与事件通道

    private val mime = mapOf(
        "html" to "text/html", "js" to "application/javascript", "css" to "text/css",
        "json" to "application/json", "svg" to "image/svg+xml", "png" to "image/png",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "ico" to "image/x-icon",
        "txt" to "text/plain", "webmanifest" to "application/manifest+json",
        "woff2" to "font/woff2", "woff" to "font/woff"
    )

    /**
     * 外部协议（bilibili:// / sinaweibo:// / market:// / intent:// …）不交给 WebView 加载：
     * 有 App 能接管就唤起，没有就忽略并记日志；`intent://` 带 browser_fallback_url 时走回退地址。
     * @return 永远 true（表示"这次导航我们自己处理了"）
     */
    private fun handleExternalScheme(raw: String): Boolean {
        // 我们自己的控制协议（内置播放器的按钮就走它）
        if (raw.startsWith("cdpctl://", ignoreCase = true)) {
            try {
                val uri = android.net.Uri.parse(raw)
                val action = uri.host ?: ""
                val u = uri.getQueryParameter("u") ?: ""
                val n = uri.getQueryParameter("n") ?: ""
                val t = uri.getQueryParameter("t") ?: ""
                when (action) {
                    "download" -> startDownload(u, null, "*/*", 0L, n.ifBlank { null })
                    "hls" -> downloadHls(u, n)
                    "cast" -> castToDevice(u, t)
                    "copy" -> copyText(u)
                    "open" -> if (u.startsWith("http")) loadUrl(u)
                    "search" -> {
                        val q = uri.getQueryParameter("q") ?: ""
                        if (q.isNotBlank()) loadUrl(settings.searchUrl(q))
                    }
                    "console" -> openConsole(uri.getQueryParameter("tab") ?: "")
                    "menu" -> main.post { showMainMenu() }
                    "mute" -> main.post { bridge.dispatch("tab.mute", JSONObject().put("on", uri.getQueryParameter("on") != "0")) { } }
                    "find" -> main.post { bridge.dispatch("find.page", JSONObject().put("q", uri.getQueryParameter("q") ?: "")) { } }
                    else -> bridge.log("未知的 cdpctl 动作：$action")
                }
            } catch (e: Exception) {
                bridge.log("cdpctl 处理失败: ${e.message}")
            }
            return true
        }
        var url = raw
        if (raw.startsWith("intent:", ignoreCase = true)) {
            val frag = raw.substringAfter('#', "")
            val fb = frag.split(';').firstOrNull { it.startsWith("S.browser_fallback_url=") }?.substringAfter('=')
            if (!fb.isNullOrBlank()) {
                val decoded = try { java.net.URLDecoder.decode(fb, "UTF-8") } catch (_: Exception) { fb }
                bridge.log("外部协议 intent:// → 走回退地址 ${decoded.take(120)}")
                if (decoded.startsWith("http")) loadUrl(decoded)
                return true
            }
            val scheme = frag.split(';').firstOrNull { it.startsWith("scheme=") }?.substringAfter('=')
            if (!scheme.isNullOrBlank()) {
                url = "$scheme:${raw.removePrefix("intent:").substringBefore('#')}"
            }
        }
        return try {
            val uri = android.net.Uri.parse(url)
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW, uri
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // 只有"确实有 App 能接管"才唤起；否则交给系统可能弹出选择器/把桌面拉到前台，
            // 浏览器窗口就被抢走了（同样表现为"点了链接之后界面不对了"）。
            val handlers = packageManager.queryIntentActivities(intent, 0)
            if (handlers.isEmpty()) {
                bridge.log("拦下外部协议（没有 App 能接，已忽略，页面不会跳错误页）：${url.take(120)}")
                true
            } else {
                // 用户要求：**主动提示"由哪个软件打开"，并且要用户确认**，不自动跳走。
                // 单选列表点哪个才开哪个；点取消/返回键什么都不做（页面留在原地）。
                main.post {
                    try {
                        val labels = handlers.map {
                            val ai = it.activityInfo
                            val lbl = it.loadLabel(packageManager).toString()
                            "$lbl（${ai.packageName}）"
                        }
                        android.app.AlertDialog.Builder(this)
                            .setTitle("用哪个 App 打开？")
                            .setMessage(url.take(200))
                            .setItems(labels.toTypedArray()) { _, which ->
                                try {
                                    val target = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                        .setClassName(
                                            handlers[which].activityInfo.packageName,
                                            handlers[which].activityInfo.name
                                        )
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(target)
                                    bridge.log("用户确认后交给外部 App：${labels[which]} ← ${url.take(100)}")
                                } catch (e: Exception) {
                                    bridge.log("打开外部 App 失败：${e.message}")
                                }
                            }
                            .setNegativeButton("取消") { _, _ ->
                                bridge.log("用户取消了外部打开（页面留在原地）：${url.take(100)}")
                            }
                            .show()
                        bridge.log("已弹出「用哪个 App 打开」等你确认（${handlers.size} 个候选）：${url.take(100)}")
                    } catch (e: Exception) {
                        bridge.log("弹确认框失败，改为不打开：${e.message}")
                    }
                }
                true
            }
        } catch (e: Exception) {
            bridge.log("拦下外部协议（没有 App 能接，已忽略，页面不会跳错误页）：${url.take(120)}")
            true
        }
    }

    /** Cookie 详情：按域名把 cookie 拆成 name=value；属性（HttpOnly/Secure/Path/过期）
     *  读自 WebView 自己的 cookie 库（应用私有目录，只读复制一份），读之前会先 flush 一次 —— 
     *  Chromium 是批量提交，不 flush 时刚写的 cookie 库里一行都没有（实测）。 */
    fun cookieDetail(domain: String): JSONObject {
        val d = domain.trim()
        if (d.isEmpty()) return JSONObject().put("ok", false).put("error", "要一个域名")
        val raw = try {
            android.webkit.CookieManager.getInstance().getCookie(d) ?: ""
        } catch (_: Exception) {
            ""
        }
        val arr = org.json.JSONArray()
        if (raw.isNotBlank()) {
            for (part in raw.split(';')) {
                val kv = part.trim()
                if (kv.isEmpty()) continue
                val i = kv.indexOf('=')
                if (i <= 0) continue
                arr.put(JSONObject().put("name", kv.substring(0, i)).put("value", kv.substring(i + 1)))
            }
        }
        return JSONObject().put("ok", true).put("domain", d).put("count", arr.length()).put("list", arr)
            .put(
                "note",
                "属性和列表一样：HttpOnly / Secure / Path / 过期时间读自 WebView 自己的 cookie 库" +
                    "（读之前先 flush；刚写入的若库里还没有，会如实标注「还没有这条」）。"
            )
    }

    // ------------------------------------------------------------------ 一键导出 / 导入
    // 导出：勾选的类别打成一个 zip（写到系统「下载」目录）；导入：从文件读回来按类别合并。
    // 合并规则：历史/书签按地址去重（已有的不重复加），脚本/设置按内容覆盖，嗅探/拦截规则只增不减。
    fun exportBundle(partsCsv: String): Pair<Boolean, String> {
        val parts = partsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false to "一个类别都没选"
        return try {
            val bos = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bos).use { z ->
                fun put(name: String, text: String) {
                    z.putNextEntry(java.util.zip.ZipEntry(name))
                    z.write(text.toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                put(
                    "meta.txt",
                    "CDP 导出包\n导出时间: $stamp\n包含类别: " + parts.joinToString(",") +
                        "\n说明: history/bookmarks/scripts/settings/sniff/adblock（脚本与设置含敏感内容，分享前自己看一眼）\n"
                )
                if (parts.contains("history")) put("history.json", nav.exportHistoryJson())
                if (parts.contains("bookmarks")) put("bookmarks.json", nav.bookmarksJson().toString())
                if (parts.contains("scripts")) put("scripts.json", store.all().toString())
                if (parts.contains("settings")) put("settings.json", settings.json().toString())
                if (parts.contains("sniff")) put("sniff.json", sniff.list().toString())
                if (parts.contains("adblock")) put("adblock.json", org.json.JSONArray(adblock.ruleList()).toString())
            }
            val name = "cdp-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + ".zip"
            val r = exportBytesToDownloads(name, bos.toByteArray(), "application/zip")
            // 同时在 App 私有目录留一份：方便"导出完立刻导入回去"这种自检（不依赖外部文件选择器）
            try {
                val dir = java.io.File(filesDir, "exports").apply { mkdirs() }
                java.io.File(dir, name).writeBytes(bos.toByteArray())
            } catch (_: Exception) {
            }
            bridge.log("一键导出（${parts.joinToString("/")}）：${r.second}")
            r
        } catch (e: Exception) {
            bridge.log("一键导出失败：${e.message}")
            false to ("导出失败: ${e.message}")
        }
    }

    /** 读 zip 里的一个条目（注意：ZipInputStream.readBytes() 会一路读到流末尾，要自己按条目读） */
    private fun readEntry(zin: java.util.zip.ZipInputStream): String {
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zin.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
        }
        return String(bos.toByteArray(), Charsets.UTF_8)
    }

    /** 把导入包应用进去（调用方负责把 bytes 读进来） */
    fun applyBundle(bytes: ByteArray): JSONObject {
        val applied = org.json.JSONArray()
        val errors = org.json.JSONArray()
        return try {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val name = e.name
                    try {
                        val text = readEntry(zin)
                        when (name) {
                            "history.json" -> {
                                val n = nav.importHistory(org.json.JSONArray(text))
                                applied.put("历史 +$n 条")
                            }
                            "bookmarks.json" -> {
                                val n = nav.importBookmarks(org.json.JSONArray(text))
                                applied.put("书签 +$n 条")
                            }
                            "scripts.json" -> {
                                val arr = org.json.JSONArray(text)
                                var n = 0
                                for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    if (o.optString("code").isBlank()) continue
                                    store.save(o); n++
                                }
                                applied.put("脚本 $n 个（同 id 覆盖）")
                            }
                            "settings.json" -> {
                                settings.update(org.json.JSONObject(text))
                                applied.put("设置已覆盖")
                            }
                            "sniff.json" -> {
                                val arr = org.json.JSONArray(text)
                                var n = 0
                                for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    if (sniff.add(o.optString("url"), o.optString("page"), force = true)) n++
                                }
                                applied.put("嗅探 +$n 条")
                            }
                            "adblock.json" -> {
                                val arr = org.json.JSONArray(text)
                                var n = 0
                                for (i in 0 until arr.length()) if (adblock.addRule(arr.optString(i))) n++
                                applied.put("拦截规则 +$n 条")
                            }
                            else -> {}
                        }
                    } catch (ex: Exception) {
                        errors.put("$name: ${ex.message}")
                    }
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
            // 注意：JSONArray 没有 joinToString（那是集合/数组的）——先转成 Kotlin 列表再拼
            val appliedList = (0 until applied.length()).map { applied.optString(it) }
            val msg = if (appliedList.isEmpty()) "导入完成：没有可识别的条目" else "导入完成：" + appliedList.joinToString("、")
            bridge.log(msg + if (errors.length() > 0) "（有 ${errors.length()} 项出错）" else "")
            JSONObject().put("ok", true).put("applied", applied).put("errors", errors).put("msg", msg)
        } catch (ex: Exception) {
            bridge.log("导入失败：${ex.message}")
            JSONObject().put("ok", false).put("error", ex.message ?: "导入失败")
        }
    }

    /** 弹系统文件选择器挑一个 zip 导入（用户必须自己确认选了哪个文件） */
    /** 自检用：把 App 私有目录里某个导出包原地导入回去（不经过系统文件选择器） */
    fun applyBundleFromExports(name: String): JSONObject {
        val f = java.io.File(java.io.File(filesDir, "exports"), name)
        if (!f.exists()) return JSONObject().put("ok", false).put("error", "私有目录里没有这个包：$name")
        return applyBundle(f.readBytes())
    }

    fun listExports(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        val dir = java.io.File(filesDir, "exports")
        dir.listFiles()?.sortedBy { it.name }?.forEach { if (it.isFile) arr.put(it.name) }
        return arr
    }

    fun pickBundleToImport() {
        main.post {
            try {
                val it = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
                startActivityForResult(it, REQ_IMPORT)
                bridge.log("等你选一个 CDP 导出包（zip）来导入")
            } catch (e: Exception) {
                bridge.log("打不开文件选择器：${e.message}")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PassVault.REQ) {
            val okV = vault.onUnlockResult(resultCode)
            bridge.log(if (okV) "密码库：锁屏验证通过（5 分钟内可查看/修改）" else "密码库：锁屏验证没过")
            return
        }
        if (requestCode != REQ_IMPORT) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            bridge.log("导入被取消（没选文件）")
            return
        }
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            val r = applyBundle(bytes)
            main.post {
                try {
                    console.evaluateJavascript(
                        "window.__cdpPush && window.__cdpPush(" + JSONObject.quote(
                            JSONObject().put("t", "imported").put("result", r).toString()
                        ) + ")", null
                    )
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            bridge.log("读取导入文件失败：${e.message}")
        }
    }

    /** 导出文件到系统"下载"目录（API29+ 用 MediaStore，不需要存储权限）；失败则退回 App 私有目录 */
    fun exportToDownloads(name: String, content: String, mime: String = "text/plain"): Pair<Boolean, String> {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) return fallbackExport(name, content)
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                bridge.log("已导出到系统下载目录：$name")
                true to "已导出到「下载」目录：$name"
            } else {
                fallbackExport(name, content)
            }
        } catch (e: Exception) {
            bridge.log("导出到系统下载目录失败（${e.message}），改存 App 私有目录")
            fallbackExport(name, content)
        }
    }

    private fun fallbackExport(name: String, content: String): Pair<Boolean, String> {
        return try {
            val dir = java.io.File(filesDir, "exports").apply { mkdirs() }
            val f = java.io.File(dir, name)
            f.writeText(content)
            true to "已导出到 App 私有目录：${f.absolutePath}"
        } catch (e: Exception) {
            false to ("导出失败: ${e.message}")
        }
    }

    /** 把 App 下好的文件导出到系统"下载"目录（MediaStore，字节流） */
    fun exportFileToDownloads(name: String, file: java.io.File, mime: String = "application/octet-stream"): Pair<Boolean, String> {
        return try {
            if (!file.exists()) return false to "文件不存在：$name"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false to "系统拒绝写入下载目录"
                file.inputStream().use { ins ->
                    contentResolver.openOutputStream(uri)?.use { os -> ins.copyTo(os) }
                }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                bridge.log("已把《$name》导出到系统下载目录")
                true to "已导出到「下载」目录：$name"
            } else {
                val dir = java.io.File(filesDir, "exports").apply { mkdirs() }
                val dst = java.io.File(dir, name)
                file.copyTo(dst, overwrite = true)
                true to "（系统版本较低）已导出到 App 私有目录：${dst.absolutePath}"
            }
        } catch (e: Exception) {
            false to ("导出失败: ${e.message}")
        }
    }

    fun exportDir(): String = java.io.File(filesDir, "exports").absolutePath

    // ------------------------------------------------------------------ 内置播放器 / 投屏

    /** 用内置播放器打开一个媒体地址（播放器页面自带下载键与投屏键） */
    fun openPlayer(url: String, name: String = "") {
        if (url.isBlank()) return
        try {
            val u = ASSET_ORIGIN + "/player.html?u=" + java.net.URLEncoder.encode(url, "UTF-8") +
                "&n=" + java.net.URLEncoder.encode(name, "UTF-8")
            closeConsole()
            main.post { try { browser.loadUrl(u) } catch (_: Exception) {} }
            bridge.log("内置播放器已打开：${url.take(90)}")
        } catch (e: Exception) {
            bridge.log("打开内置播放器失败: ${e.message}")
        }
    }


    /**
     * 系统播放器打开（用户反馈 #19：视频播放可以选择系统播放器）。
     * 老实说清：能不能播取决于你手机上装了什么播放器；没有能接的 App 时会明确告诉你。
     */
    fun openWithSystemPlayer(url: String, mime: String = "video/*"): JSONObject {
        return try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            i.setDataAndType(android.net.Uri.parse(url), mime)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val can = packageManager.resolveActivity(i, 0)
            if (can == null) {
                JSONObject().put("ok", false).put("error", "系统里没有能接 $mime 的播放器（装一个再试，或继续用内置播放器）")
            } else {
                startActivity(i)
                bridge.log("已交给系统播放器：" + (can.activityInfo?.packageName ?: "?") + " → " + url.take(80))
                JSONObject().put("ok", true).put("via", can.activityInfo?.packageName ?: "").put("url", url)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "交给系统播放器失败：" + (e.message ?: ""))
        }
    }

    /**
     * 视频信息（用户反馈 #5「通过 ffmpeg 可以查看视频信息」）。
     * 诚实说明：本工程**没有内置 ffmpeg**，这里用的是 Android 自带的媒体解析器
     * （MediaMetadataRetriever）：能读到时长/分辨率/码率/编码这些它就有的字段；
     * 加密 HLS、需要鉴权的流、分片 m3u8 往往读不到——读不到就如实说读不到，不编。
     */
    /**
     * 从页面里再扫一遍媒体地址（嗅探的第二条路）。
     *
     * 为什么需要：有些站不是"页面请求一个 .mp4/.m3u8"这么直白 —— 清单是 fetch/XHR 拿的、
     * 地址写在 JS 里、或者用 MSE（blob:）。请求层能看到的分片/清单，浏览器自己记在
     * performance 里；video/audio 标签的 currentSrc 也能直接读。两条都扫，广度才够。
     * 对 blob:(MSE) 这种拿不到直链的情况，如实回报，不假装抓到了。
     */
    fun scanPageMedia(cb: (JSONObject) -> Unit) {
        val js = ("(function(){var out={urls:[],tags:[],mse:false,note:''};" +
            "try{var re=/\\.(m3u8|mpd|mp4|m4v|m4s|cmfv|fmp4|f4v|webm|mkv|flv|ts|mp3|m4a|m4b|aac|flac|opus|ogg|oga|weba|wav|vtt|srt|ass)(\\?|#|$)/i;" +
            "performance.getEntriesByType('resource').forEach(function(e){if(e&&e.name&&re.test(e.name))out.urls.push(e.name);});}catch(e){}" +
            "try{Array.prototype.slice.call(document.querySelectorAll('video,audio,source')).forEach(function(v){" +
            "var s=v.currentSrc||v.src||'';if(s)out.tags.push(s);" +
            "if(v.querySelectorAll){Array.prototype.slice.call(v.querySelectorAll('source')).forEach(function(x){if(x.src)out.tags.push(x.src);});}});}catch(e){}" +
            "try{var v=document.querySelector('video');if(v){var cs=v.currentSrc||'';if(cs.indexOf('blob:')===0)out.mse=true;}}" +
            "catch(e){}return JSON.stringify(out);})()")
        evalInBrowser(js) { raw ->
            val o = try { JSONObject(unquote(raw)) } catch (e: Exception) { null }
            if (o == null) {
                cb(JSONObject().put("ok", false).put("error", "页面没回结果（可能是没加载完）"))
                return@evalInBrowser
            }
            var added = 0
            val seen = HashMap<String, Int>()
            val urls = o.optJSONArray("urls") ?: JSONArray()
            val tags = o.optJSONArray("tags") ?: JSONArray()
            val page = lastPageUrl
            for (i in 0 until urls.length()) {
                val u = urls.optString(i)
                if (u.isNotEmpty() && sniff.add(u, page)) added++
            }
            for (i in 0 until tags.length()) {
                val u = tags.optString(i)
                if (u.isNotEmpty() && sniff.add(u, page, force = true)) added++
            }
            val st = sniff.stats()
            val msg = StringBuilder()
            msg.append("从页面里又扫了一遍：新增 ").append(added).append(" 条")
            if (o.optBoolean("mse")) msg.append("；这一页用的是 MSE（blob:），直链不在网络层 —— 清单/分片仍可在下面找到，或换一个不是 MSE 的站试试")
            cb(JSONObject().put("ok", true).put("added", added).put("scanned", urls.length() + tags.length())
                .put("mse", o.optBoolean("mse")).put("msg", msg.toString()).put("stats", st))
        }
    }

    fun videoInfo(url: String): JSONObject {
        if (url.isBlank()) return JSONObject().put("ok", false).put("error", "没给地址")
        val out = JSONObject().put("ok", true).put("url", url).put("engine", "Android MediaMetadataRetriever（不是 ffmpeg）")
        var mmr: android.media.MediaMetadataRetriever? = null
        try {
            mmr = android.media.MediaMetadataRetriever()
            if (url.startsWith(ASSET_ORIGIN)) {
                // appassets 是虚拟源：系统媒体解析器走自己的网络栈够不到它（实测会一直卡住直到超时）。
                // 所以先把资产（或已下载到私有目录的同名文件）落地成临时文件，再解析。
                val rel = url.removePrefix(ASSET_ORIGIN).trimStart('/')
                val tmp = java.io.File(cacheDir, rel.replace('/', '_'))
                val local = java.io.File(filesDir, "downloads/" + rel.substringAfterLast('/'))
                if (local.exists()) {
                    mmr.setDataSource(local.absolutePath)
                } else {
                    assets.open(rel).use { ins -> tmp.outputStream().use { outs -> ins.copyTo(outs) } }
                    mmr.setDataSource(tmp.absolutePath)
                }
                out.put("note", "appassets 的媒体是先落地成临时文件再解析的（系统解析器够不到虚拟源）")
            } else if (url.startsWith("http")) {
                // 远端地址：解析器可能卡很久，放到线程里等最多 8 秒，超时就如实说读不到
                var done = false
                val t = Thread {
                    try {
                        mmr.setDataSource(url, HashMap<String, String>())
                        done = true
                    } catch (_: Exception) {
                    }
                }
                t.isDaemon = true
                t.start()
                t.join(8000)
                if (!done) {
                    try { mmr.release() } catch (_: Exception) {}
                    return JSONObject().put("ok", false)
                        .put("error", "读不到媒体信息：这个远端地址 8 秒内没解析出来（加密/分片/需要鉴权，或者系统解析器就是不支持）")
                        .put("engine", "Android MediaMetadataRetriever（不是 ffmpeg）")
                }
            } else if (url.startsWith("file://")) {
                mmr.setDataSource(android.net.Uri.parse(url).path ?: url)
            } else {
                val f = java.io.File(android.net.Uri.parse(url).path ?: url)
                if (!f.exists()) return JSONObject().put("ok", false).put("error", "本地文件不存在：${f.name}")
                mmr.setDataSource(f.absolutePath)
            }
            fun field(key: Int, name: String) {
                val v = try { mmr.extractMetadata(key) } catch (e: Exception) { null }
                if (!v.isNullOrBlank()) out.put(name, v)
            }
            field(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "durationMs")
            field(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "width")
            field(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "height")
            field(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE, "bitrate")
            field(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, "frames")
            field(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE, "mime")
            field(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "hasVideo")
            field(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "hasAudio")
            if (out.optString("durationMs").isBlank()) {
                out.put("ok", false).put("error", "这个地址的媒体信息读不出来（加密/分片/需要鉴权，或系统解析器不支持）")
            }
        } catch (e: Exception) {
            return JSONObject().put("ok", false)
                .put("error", "读不到媒体信息：" + (e.message ?: "") + "（如实说明：这不是 ffmpeg，很多流行格式它读不出来）")
        } finally {
            try { mmr?.release() } catch (_: Exception) {}
        }
        return out
    }

    /** 当前页面里所有视频的状态（反馈 #5：要能看到播放状态/有没有被识别出来） */
    fun pageVideos(): JSONObject {
        return try {
            val r = org.json.JSONObject("""{"__pending":true}""")
            // 同步拿不到回调结果，用 Bridge 的 pageCmd 通道（见 video.list 命令）
            r.put("ok", false).put("note", "用 bridge 的 video.list 命令")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message)
        }
    }

    /** 投屏：搜 DLNA 设备 → 弹选择框 → 把地址交给它并开始播 */
    fun castToDevice(url: String, title: String = "") {
        if (url.isBlank()) return
        bridge.log("开始搜索局域网投屏设备（DLNA）…")
        Thread {
            val devs = try { cast.discover(2600) } catch (e: Exception) { emptyList() }
            main.post {
                if (devs.isEmpty()) {
                    alert("没搜到投屏设备", "确认电视/盒子跟手机在同一个 Wi-Fi，并且支持 DLNA。\n（投屏 App 的私有协议不在支持范围）")
                    return@post
                }
                val names = devs.map { it.name }.toTypedArray()
                try {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("投到哪台设备？")
                        .setItems(names) { _, i ->
                            val d = devs[i]
                            bridge.log("投屏到「${d.name}」：${url.take(80)}")
                            Thread {
                                val r = try { cast.play(d, url, title) } catch (e: Exception) { false to (e.message ?: "失败") }
                                main.post {
                                    bridge.log("投屏结果：${r.second}")
                                    alert(if (r.first) "已投到「${d.name}」" else "投屏失败", r.second)
                                }
                            }.apply { setName("cdp-cast-play"); start() }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } catch (e: Exception) {
                    bridge.log("弹投屏选择框失败: ${e.message}")
                }
            }
        }.apply { setName("cdp-cast-scan"); start() }
    }

    private fun alert(title: String, msg: String) {
        try {
            android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("知道了", null).show()
        } catch (_: Exception) {
        }
    }

    private fun copyText(s: String) {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("CDP", s))
            bridge.log("已复制到剪贴板：${s.take(80)}")
        } catch (e: Exception) {
            bridge.log("复制失败: ${e.message}")
        }
    }

    // 页面 → 原生 的事件通道 / 元信息通道都要带这个口令：随机、每次启动换，
    // 口令只出现在**我们自己注入的**脚本里。别的网页就算知道 cdp-event.local 这个虚拟源，
    // 没有口令也只能拿到 403 —— 这就是"除了我们自己的页面，别的网页操作不了软件"。
    private val chanToken = java.util.UUID.randomUUID().toString().replace("-", "")

    /** 注入给页面的 agent 源码：口令包在**外层闭包**里（局部变量，不挂 window，
     *  页面自己的脚本读不到），agent 里用 __CDP_CHAN 取。 */
    private val agentWithToken: String
        get() = "(function(){var __CDP_CHAN=\"$chanToken\";\n" + agentSource + "\n})();"

    /** 事件/元信息请求是否带了正确口令 */
    private fun chanOk(url: String): Boolean = url.contains("k=$chanToken")

    /** 导入时用的请求码 */
    private val REQ_IMPORT = 4321

    /** 把字节写成文件放到系统「下载」目录（zip 这类二进制用它，不能走文本那条） */
    fun exportBytesToDownloads(name: String, bytes: ByteArray, mime: String = "application/octet-stream"): Pair<Boolean, String> {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false to "系统拒绝写入下载目录"
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                // 反馈 #24：导出也走下载器线路 —— 记进下载列表，用户能在下载栏里看到/管理
                try {
                    synchronized(downloads) {
                        downloads.add(
                            JSONObject().put("name", name).put("url", "export://$name")
                                .put("bytes", bytes.size).put("total", bytes.size)
                                .put("state", "已导出到系统「下载」目录")
                                .put("ts", System.currentTimeMillis()).put("incognito", false)
                                .put("path", "系统下载目录/$name")
                        )
                        persistDownloads()
                    }
                } catch (_: Exception) {
                }
                true to "已导出到「下载」目录：$name（${bytes.size} 字节）"
            } else {
                val dir = java.io.File(filesDir, "exports").apply { mkdirs() }
                java.io.File(dir, name).writeBytes(bytes)
                true to "已导出到 App 私有目录：${java.io.File(dir, name).absolutePath}"
            }
        } catch (e: Exception) {
            false to ("导出失败: ${e.message}")
        }
    }

    private fun chanDenied(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun intercept(url: String): WebResourceResponse? {
        // 广告/统计拦截：命中黑名单就直接回 204 空响应（省流量、页面也不会因为广告卡住）。
        // 放在最前面，拦下的请求连嗅探都不必走。
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastConnStep > 400) {
                    lastConnStep = nowMs
                    connStep("请求 " + url.substringAfter("://").take(48))
                }
                if (adblock.mode() == "hide") {
                    // 只隐藏不阻断：命中也不回空响应，让页面照常拿到资源（免得被 JS 探测），
                    // 真正的隐藏由注入的 CSS 完成（见 pushCosmetic）
                    adblock.match(url)?.let { rule ->
                        netlog.note(url, lastPageUrl, "hide", false, rule)
                    }
                } else {
                    adblock.shouldBlock(url)?.let { rule ->
                        adblock.noteBlocked(url, rule)
                        netlog.note(url, lastPageUrl, "blocked", true, rule)
                        bridge.log("拦下广告/统计请求（$rule）：${url.take(100)}")
                        return WebResourceResponse(
                            "text/plain", "utf-8", 204, "No Content", emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        // 资源嗅探：只登记、不改动（返回 null 让 WebView 照常处理）。
        // 页面里的每个子资源都会经过这里，所以 m3u8/mp4/mp3 这类地址会被顺手记下来。
        // 注意：这里跑在 WebView 的线程上，绝不能直接读 browser.url（只能主线程调，
        // 异常被吞掉就会表现成"嗅探永远为空"）—— 用主线程维护的缓存值。
        if ((url.startsWith("http://") || url.startsWith("https://")) && !isInternalUrl(url)) {
            try {
                val k = sniff.kindOf(url)
                if (sniff.isEnabled() && k != null) sniff.add(url, lastPageUrl)
                // 「指定位置为视频」要找候选：认不出的地址（无扩展名那种）也留一份最近 60 秒的流水，
                // 不进清单、不落盘，所以不会污染嗅探列表（见 Sniffer.noteRequest）
                if (sniff.isEnabled()) sniff.noteRequest(url, lastPageUrl)
                // 网络面板：每个 http(s) 请求都记一条（媒体/其它由 kind 区分）
                netlog.note(url, lastPageUrl, k ?: "other", false)
            } catch (_: Exception) {
            }
        }
        if (url.startsWith("$META_ORIGIN/meta")) {
            // 用户脚本清单：页面在 document-start 时同步取，用来判断某次注入是否还有效
            // 口令不对就不给 —— 页面脚本清单是内部信息，不给外人
            if (!chanOk(url)) {
                bridge.log("拦下没有口令的元信息请求（不是我们自己的页面）：${url.take(80)}")
                return chanDenied()
            }
            val bytes = scriptMetaJson().toByteArray(Charsets.UTF_8)
            return WebResourceResponse(
                "application/json", "utf-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
                ByteArrayInputStream(bytes)
            )
        }
        if (url.startsWith("$EVENT_ORIGIN/ev")) {
            // 页面 → 原生 的事件通道：走一个虚拟源的 GET，页面无需暴露任何原生对象。
            // **必须带口令**：没有口令的（别的网页伪造的）一律 403，不进 onPageEvent。
            if (!chanOk(url)) {
                bridge.log("拦下没有口令的页面事件（可能是别的网页在伪造）：${url.take(80)}")
                return chanDenied()
            }
            val i = url.indexOf("?d=")
            if (i >= 0) {
                val raw = try { URLDecoder.decode(url.substring(i + 3), "UTF-8") } catch (_: Exception) { "" }
                if (raw.isNotEmpty()) {
                    try {
                        val o = JSONObject(raw)
                        if (o.optString("t") == "chunk") {
                            val id = o.optString("id")
                            val i2 = o.optInt("i"); val n = o.optInt("n")
                            val parts = chunks.getOrPut(id) { arrayOfNulls(n) }
                            if (i2 in parts.indices) parts[i2] = o.optString("part")
                            if (parts.all { it != null }) {
                                chunks.remove(id)
                                main.post { bridge.onPageEvent(JSONObject(parts.joinToString(""))) }
                            }
                        } else {
                            main.post { bridge.onPageEvent(o) }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            return WebResourceResponse(
                "text/plain", "utf-8", 204, "No Content",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
                ByteArrayInputStream(ByteArray(0))
            )
        }
        if (url.startsWith(ASSET_ORIGIN)) {
            val path = url.removePrefix(ASSET_ORIGIN).trimStart('/').substringBefore('?').ifEmpty { "ui/index.html" }
            return try {
                val bytes = assets.open(path).readBytes()
                val ext = path.substringAfterLast('.', "").lowercase()
                WebResourceResponse(mime[ext] ?: "application/octet-stream", "utf-8", 200, "OK",
                    mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
                    ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream("404 $path".toByteArray()))
            }
        }
        return null
    }

    private val chunks = HashMap<String, Array<String?>>()

    private fun loadAsset(path: String): String = try {
        assets.open(path).readBytes().toString(Charsets.UTF_8)
    } catch (e: Exception) {
        "/* 缺少 $path */"
    }

    // ------------------------------------------------------------------ 控制台 WebView

    /**
     * 给一个 WebView 挂原生桥（`window.cdpNative`）。
     *
     * **两个 WebView 都要挂**（议题 #1「主页小 app 无法显示」的真因）：
     * 首页 `start.html` 跑在 `browser` 里、控制台跑在 `console` 里；
     * 桥只挂在 `console` 时，首页里 `window.cdpNative` 是 `undefined` →
     * 前端静默退化成"演示假数据"（transport.js 的 demo 分支）→
     * 小 app 网格一个都不渲染，只剩一个「＋ 添加应用」和一行"原生桥没响应"。
     * 允许来源限定为 assets 源与本机控制口源，其它站点拿不到这个对象。
     */
    private fun attachNativeBridge(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(
            wv, "cdpNative",
            setOf(ASSET_ORIGIN, "http://127.0.0.1:" + runCatching { http.status().optInt("port", 8848) }.getOrDefault(8848)),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView, message: WebMessageCompat, sourceOrigin: Uri,
                    isMainFrame: Boolean, replyProxy: androidx.webkit.JavaScriptReplyProxy
                ) {
                    val data = message.data ?: return
                    try {
                        val o = JSONObject(data)
                        val id = o.optString("id")
                        val op = o.optString("op")
                        val args = o.optJSONObject("args") ?: JSONObject()
                        main.post {
                            bridge.dispatch(op, args) { r ->
                                r.put("id", id)
                                replyProxy.postMessage(r.toString())
                            }
                        }
                    } catch (e: Exception) {
                        replyProxy.postMessage(JSONObject().put("ok", false).put("error", "坏消息: ${e.message}").toString())
                    }
                }
            })
    }

    private fun setupConsole() {
        val s = console.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        console.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                intercept(req.url.toString())
        }
        attachNativeBridge(console)
        runCatching { console.clearCache(true) }   // 建控制台前再清一次（换了构件就不会跑到旧 UI）
        // 再给 URL 带一个"构建戳"：assets 会被 WebView 缓存，戳变了脚本 URL 就变，旧副本不会被命中
        val stamp = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        }.getOrElse { System.currentTimeMillis().toString() }
        // 优先走**本机控制口**：它带 Cache-Control: no-store，WebView 不会缓存 assets，
        // 换构件后不会出现"跑的还是旧界面"。控制口没开就退回 assets 源（极少数情况）。
        runCatching { if (!http.status().optBoolean("running")) http.start(false) }   // 控制台要从这个口取页面
        val hs = runCatching { http.status() }.getOrNull()
        val uiBase = if (hs != null && hs.optBoolean("running")) {
            "http://127.0.0.1:" + hs.optInt("port", 8848)
        } else ASSET_ORIGIN
        console.loadUrl("$uiBase/ui/index.html?v=$stamp")
    }

    // ------------------------------------------------------------------ 用户脚本注入

    lateinit var adblock: AdBlock
    lateinit var vault: PassVault
    lateinit var terminal: Terminal
    lateinit var readlog: ReadLog
    lateinit var ffmpeg: Ffmpeg
        private set

    // ------------------------------------------------------------------ 多窗口
    // 诚实说明实现方式：**共用一个 WebView**（"逻辑窗口"）——每个窗口各记 URL / 标题 / 静音状态，
    // 切换时恢复它的地址。真并行多个 WebView 在内存小的机器/模拟器上很容易被系统杀掉，
    // 所以这里选了省内存的做法，并在界面里写明。
    data class WinTab(
        var url: String,
        var title: String,
        var muted: Boolean,
        var lastUsed: Long = 0L,      // 最近一次切到这个窗口的时间（LRU 用）
        var kept: Boolean = true,     // 保活：最近用过的 ≤ 10 个才是 true
        var scrollY: Int = 0,         // 保活窗口记一下滚动位置，切回来还在（被清掉的就从头上）
    )

    /** 保活上限：用户要求"只保活最近打开/使用的若干（上限 10），超出的把最久没用的清掉缓存" */
    private val KEEP_MAX = 10

    private val wins = java.util.Collections.synchronizedList(mutableListOf<WinTab>())

    /** 窗口列表持久化（用户要求：重开 App 窗口列表还在；**不预加载**，只存地址/标题/静音/时间） */
    private fun saveWins() {
        val arr = org.json.JSONArray()
        synchronized(wins) {
            wins.forEach { w ->
                arr.put(
                    JSONObject().put("url", w.url).put("title", w.title).put("muted", w.muted)
                        .put("lastUsed", w.lastUsed)
                )
            }
        }
        settings.set("winsJson", arr.toString())
    }

    /** 启动时把窗口列表读回来（地址/标题还在；页面等切过去才加载 = 不预加载） */
    fun restoreWins() {
        val raw = settings.get("winsJson", "")
        if (raw.isBlank()) return
        try {
            val arr = org.json.JSONArray(raw)
            synchronized(wins) {
                wins.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    // 用户报过"首页那个 APP 添加了不渲染"：老标签页里存的可能是**内置资源源**的 start.html，
                    // 那个来源 fetch 本机控制口会被混合内容策略拦掉。启动时统一换成能用的那份。
                    val u0 = o.optString("url")
                    val u = if (u0.contains("$ASSET_ORIGIN/ui/start.html") || u0.endsWith("/ui/start.html")) startPageUrl() else u0
                    wins.add(
                        WinTab(
                            u, o.optString("title"), o.optBoolean("muted"),
                            o.optLong("lastUsed")
                        )
                    )
                }
            }
            rebalanceKept()
            rebuildWinStrip()
            restoreClosedWins()
            bridge.log("窗口列表已恢复：${wins.size} 个（不预加载，切过去才加载）；最近关闭 ${closedWins.size} 条")
        } catch (_: Exception) {
        }
    }

    /** 最近使用的 ≤ KEEP_MAX 个标 kept=true，其余 false（false 的切回去要重新加载，地址/标题仍保留） */
    private fun rebalanceKept() {
        synchronized(wins) {
            val order = wins.indices.sortedByDescending { wins[it].lastUsed }
            order.forEachIndexed { rank, idx -> wins[idx].kept = rank < KEEP_MAX }
        }
        saveWins()
    }

    @Volatile
    private var activeWin = 0

    fun winListJson(): JSONObject {
        val arr = org.json.JSONArray()
        synchronized(wins) {
            wins.forEachIndexed { i, w ->
                // 当前窗口直接用**实时**地址/标题（别拿可能过期的缓存，用户看到的就是这个）
                val live = i == activeWin
                arr.put(
                    JSONObject().put("id", i)
                        .put("url", if (live && lastPageUrl.isNotBlank()) lastPageUrl else w.url)
                        .put("title", if (live && lastTitle.isNotBlank()) lastTitle else w.title)
                        .put("muted", w.muted).put("active", live)
                        .put("kept", w.kept).put("lastUsed", w.lastUsed)
                )
            }
        }
        return JSONObject().put("ok", true)
            .put("active", activeWin).put("count", wins.size).put("list", arr)
            .put("keptMax", KEEP_MAX)
            .put("keptCount", synchronized(wins) { wins.count { it.kept } })
            .put(
                "note",
                "共用同一个 WebView 的逻辑窗口（省内存）：各自记地址/标题/静音，切换即恢复地址；" +
                    "窗口列表持久保存（重开还在、不预加载）；只保活最近使用的 $KEEP_MAX 个，超出的切回去要重新加载"
            )
    }

    fun winNew(url: String = ""): JSONObject {
        val u = if (url.isBlank()) startPageUrl() else url
        synchronized(wins) {
            wins.add(WinTab(u, "新窗口", false, System.currentTimeMillis()))
        }
        main.post { winSwitch(wins.size - 1) }
        rebalanceKept()
        rebuildWinStrip()
        bridge.log("新窗口 #${wins.size}（共 ${wins.size} 个）：$u")
        return winListJson()
    }

    fun winSwitch(id: Int): JSONObject {
        val n = wins.size
        if (n == 0) return JSONObject().put("ok", false).put("error", "没有窗口")
        val target = id.coerceIn(0, n - 1)
        // 切走前：把当前窗口的地址/标题/滚动位置记下（保活窗口的位置切回来才恢复）
        if (activeWin in 0 until wins.size && activeWin != target) {
            val y = runCatching { browser.evaluateJavascript("String(window.scrollY)") { } }.isSuccess
            if (y) {
                // 异步取；取不到也不影响（下次切回来只是从头显示）
                try {
                    browser.evaluateJavascript("window.scrollY") { v ->
                        val n = v.trim('"').toDoubleOrNull()?.toInt() ?: 0
                        synchronized(wins) { if (activeWin in 0 until wins.size) wins[activeWin].scrollY = n }
                    }
                } catch (_: Exception) {
                }
            }
        }
        synchronized(wins) {
            // 先把当前窗口的状态存回去，再切
            if (activeWin in 0 until wins.size) {
                val cur = wins[activeWin]
                cur.url = lastPageUrl.ifBlank { cur.url }
                cur.title = lastTitle.ifBlank { cur.title }
                cur.muted = muted
            }
            activeWin = target
            wins[target].lastUsed = System.currentTimeMillis()      // LRU：这才是"最近用过"
            wins[target].kept = true
        }
        rebalanceKept()
        val w = synchronized(wins) { wins[target] }
        main.post {
            muted = w.muted
            if (w.url.isNotBlank()) loadUrl(w.url)
            // 保活窗口：等页面起完把滚动位置放回去（被清掉的那些就不放，用户看到"重新加载"才对）
            if (w.kept && w.scrollY > 0) {
                browser.postDelayed({
                    runCatching {
                        browser.evaluateJavascript("window.scrollTo(0,${w.scrollY})", null)
                    }
                }, 900)
            }
            updateWinIndicator()
        }
        rebuildWinStrip()
        bridge.log("切到窗口 #${target + 1}：${w.url.take(80)}")
        return winListJson()
    }

    fun winClose(id: Int): JSONObject {
        var closed: WinTab? = null
        synchronized(wins) {
            if (wins.size <= 1) return JSONObject().put("ok", false).put("error", "只剩一个窗口，不能关")
            val k = id.coerceIn(0, wins.size - 1)
            closed = wins.removeAt(k)
            if (activeWin >= wins.size) activeWin = wins.size - 1
        }
        // 关掉的进「最近关闭」（用户 2026-09-22：什么表示"历史下来的"，参考 Firefox）
        closed?.let {
            val t = if (it.title.isNotBlank()) it.title else try { android.net.Uri.parse(it.url).host ?: it.url } catch (_: Exception) { it.url }
            synchronized(closedWins) {
                closedWins.add(0, WinTab(it.url, t, it.muted, System.currentTimeMillis()))
                while (closedWins.size > CLOSED_MAX) closedWins.removeAt(closedWins.size - 1)
            }
            saveClosedWins()
        }
        rebalanceKept()
        main.post { winSwitch(activeWin) }
        rebuildWinStrip()
        bridge.log("关掉一个窗口，剩 ${wins.size} 个（已记进「最近关闭」）")
        return winListJson()
    }

    // ---------------------------------------------------------------- 最近关闭（"历史下来的"那一类）
    private val CLOSED_MAX = 10
    private val closedWins = mutableListOf<WinTab>()

    private fun saveClosedWins() {
        val arr = org.json.JSONArray()
        synchronized(closedWins) {
            closedWins.forEach { w ->
                arr.put(JSONObject().put("url", w.url).put("title", w.title).put("closedAt", w.lastUsed))
            }
        }
        settings.set("winsClosedJson", arr.toString())
    }

    private fun restoreClosedWins() {
        val raw = settings.get("winsClosedJson", "")
        if (raw.isBlank()) return
        try {
            val arr = org.json.JSONArray(raw)
            synchronized(closedWins) {
                closedWins.clear()
                var i = 0
                while (i < arr.length() && closedWins.size < CLOSED_MAX) {
                    val o = arr.optJSONObject(i)
                    i++
                    if (o == null) continue
                    closedWins.add(WinTab(o.optString("url"), o.optString("title"), false, o.optLong("closedAt")))
                }
            }
        } catch (_: Exception) {
        }
    }

    fun winRecentJson(): JSONObject {
        val arr = org.json.JSONArray()
        synchronized(closedWins) {
            closedWins.forEachIndexed { i, w ->
                arr.put(
                    JSONObject().put("i", i).put("url", w.url).put("title", w.title).put("closedAt", w.lastUsed)
                )
            }
        }
        return JSONObject().put("ok", true).put("count", synchronized(closedWins) { closedWins.size })
            .put("max", CLOSED_MAX).put("list", arr)
    }

    /** 把「最近关闭」里的第 i 条重新开成窗口（点一下恢复） */
    fun winReopen(i: Int): JSONObject {
        val w = synchronized(closedWins) { if (i in closedWins.indices) closedWins.removeAt(i) else null }
            ?: return JSONObject().put("ok", false).put("error", "最近关闭里没有第 ${i + 1} 条")
        saveClosedWins()
        val u = if (w.url.isBlank()) startPageUrl() else w.url
        synchronized(wins) { wins.add(WinTab(u, w.title.ifBlank { "新窗口" }, false, System.currentTimeMillis())) }
        rebalanceKept()
        main.post { winSwitch(wins.size - 1) }
        bridge.log("从「最近关闭」恢复：${u.take(70)}")
        return winListJson()
    }

    private var winBtn: TextView? = null
    /** 阅读时长的计时器（App 自己计时，不注入页面） */
    lateinit var pageTimer: PageTimer
    // 窗口栏（winStrip）已按用户要求删除：新建 / 切换 / 关闭 只在地址栏后面那个「▤」菜单里，
    // 窗口按**页面标题**叫名字。rebuildWinStrip 保留成空壳，调用处不用改（只刷新图标指示）。
    fun rebuildWinStrip() {
        runOnUiThread { updateWinIndicator() }
    }

    /** 工具栏 ⧉：列窗口、开新窗口、关当前窗口 */
    /** 窗口叫什么：**优先用页面标题**（用户要求：不能一律叫"窗口一"），标题空就退回主机名/地址/新窗口 */
    private fun winLabel(i: Int, w: WinTab): String {
        val t = if (i == activeWin) lastTitle.ifBlank { w.title } else w.title
        if (t.isNotBlank() && t != "新标签页") return t
        val host = try { android.net.Uri.parse(w.url).host ?: "" } catch (_: Exception) { "" }
        if (host.isNotBlank()) return host
        if (w.url.isNotBlank()) return w.url.takeLast(24)
        return "新窗口"
    }

    /**
     * 工具栏 ▤：列窗口 + 开新窗口。
     * 用户要求：**每一行用 ✕ 关掉那一个窗口，不要文字**（原来是一个 PopupMenu 的文字项，
     * 只能"关掉当前窗口"，关不了指定的那一个）。
     */
    fun showWinMenu() {
        val snapshot = synchronized(wins) { wins.mapIndexed { i, w -> i to w } }
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        val dlg = android.app.AlertDialog.Builder(this).setTitle("窗口").setView(scroll).create()

        snapshot.forEach { (i, w) ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(6), dp(4))
                minimumHeight = dp(46)
            }
            // 状态一眼看懂（用户要求参考 Firefox）：
            //   ● 实心 = 当前（还有一行底色）/ ○ 空心 = 后台（状态保活中）
            //   ○ + 标题变暗 + ⟳ = 被回收过的（切回去要重新加载）
            val isActive = i == activeWin
            if (isActive) row.setBackgroundColor(0xFF14304A.toInt())
            val tv = TextView(this).apply {
                text = (if (isActive) "● " else "○ ") + winLabel(i, w)
                setTextColor(if (w.kept) 0xFFEAF2FA.toInt() else 0xFF8A98A6.toInt())
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            }
            // 被回收过的：右侧一个"要重载"标记（不写说明文字，一眼能认）
            val reload = TextView(this).apply {
                text = if (w.kept) "" else "⟳"
                setTextColor(0xFFB7C4D2.toInt())
                textSize = 15f
                setPadding(dp(6), 0, dp(6), 0)
                contentDescription = "切回去要重新加载"
            }
            // 每行自己的 ✕：关掉**这一个**窗口（只剩一个时后端会回"不能关"，这里如实提示）
            val x = TextView(this).apply {
                text = "✕"
                setTextColor(0xFFFFC9C9.toInt())
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                contentDescription = "关掉这个窗口"
                setOnClickListener {
                    dlg.dismiss()
                    val r = winClose(i)
                    if (!r.optBoolean("ok")) bridge.log(r.optString("error"))
                    updateWinIndicator()
                }
            }
            // 用户要求：**点行本身就＝选这个窗口**（不是只有标题那一截能点）；行右边那个 ✕ 才是关掉它
            row.isClickable = true
            row.setOnClickListener { dlg.dismiss(); winSwitch(i) }
            row.addView(tv); row.addView(reload); row.addView(x)
            box.addView(row)
        }
        val add = TextView(this).apply {
            text = "＋ 新窗口"
            setTextColor(0xFF9FD3FF.toInt())
            textSize = 15f
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { dlg.dismiss(); winNew() }
        }
        box.addView(add)
        // 「最近关闭」（"历史下来的"那一类）：点一下恢复成窗口（Firefox 的"最近关闭的标签"口径）
        val recent = synchronized(closedWins) { closedWins.toList() }
        if (recent.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "最近关闭"
                setTextColor(0xFF8A98A6.toInt())
                textSize = 13f
                setPadding(dp(12), dp(14), dp(12), dp(6))
            })
            recent.forEachIndexed { idx, w ->
                val row = TextView(this).apply {
                    text = "↺ " + (if (w.title.isNotBlank()) w.title else w.url)
                    setTextColor(0xFF9FD3FF.toInt())
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    setOnClickListener {
                        dlg.dismiss()
                        val r = winReopen(idx)
                        if (!r.optBoolean("ok")) bridge.log(r.optString("error"))
                        updateWinIndicator()
                    }
                }
                box.addView(row)
            }
        }
        dlg.show()
    }

    /** 工具栏上的窗口指示：只有一个窗口时就是「▤」；多窗口时带个数量（名称在弹出来的列表里按标题看） */
    private fun updateWinIndicator() {
        try {
            val n = wins.size
            winBtn?.text = if (n > 1) "▤" + n else "▤"
        } catch (_: Exception) {
        }
    }

    /** 组合接口：一屏拿到"状态 + 各类数据条数" */
    /** 控制台浮层是不是开着（真状态：View 的可见性）——验收读它判断"点完有没有回到网页" */
    fun consoleOpen(): Boolean = try { overlay.visibility == View.VISIBLE } catch (_: Exception) { false }

    fun summaryJson(): JSONObject = JSONObject()
        .put("ok", true)
        .put("url", lastPageUrl).put("title", lastTitle)
        .put("recording", bridge.recording)
        .put("space", Spaces.current)
        .put("history", nav.historyJson().length())
        .put("bookmarks", nav.bookmarksJson().length())
        .put("scripts", store.count())
        .put("sniff", sniff.count())
        .put("adblockRules", adblock.ruleList().size)
        .put("windows", wins.size).put("activeWindow", activeWin)
        // 代理中继端口（0 = 没开）：验收与外部脚本要能一眼看到"自代理到底起没起"
        .put("proxyPort", proxyPortNow())
        .put("selfProxy", settings.selfProxy())
        .put("incognito", settings.incognito())
        .put("netTotal", netlog.stats().optLong("total"))

    /** 抓一段文本（默认整页正文；给了选择器就抓那个元素）——给别的软件/LLM 用的二级接口 */
    fun grabText(selector: String, max: Int): JSONObject {
        val js = if (selector.isBlank()) {
            "({ok:true, title: document.title, text: (document.body ? document.body.innerText : '').slice(0, ${max.coerceIn(500, 60000)})})"
        } else {
            "(function(){var e=document.querySelector(${JSONObject.quote(selector)});" +
                "return e?{ok:true,title:document.title,text:(e.innerText||'').slice(0,${max.coerceIn(500, 60000)})}:{ok:false,error:'没找到元素'};})()"
        }
        // 同步取（截图式的一次调用）：用 evaluateJavascript 的回调版本在别处，这里给的是"尽快返回"
        return JSONObject().put("ok", true).put("js", js)
            .put("note", "内部用：真正取内容走 page.grab（异步）")
    }

    /** 给 AI 接口用的搜索地址（就是把设置里的搜索引擎拿过来） */
    fun searchUrlFor(q: String): String = settings.searchUrl(q)

    /** 当前页的安全情况：协议、是否明文、是否命中屏蔽名单、有没有混合内容（http 子资源） */

    /** 页面检测到登录框：弹一条"要保存吗"（只显示站点和用户名，不显示密码） */
    fun onLoginSeen(o: org.json.JSONObject) {
        pendingLogin = o
        runOnUiThread {
            vaultBar.text = "\uD83D\uDCBE " + (o.optString("site").ifEmpty { "这个站点" }) +
                " 有登录信息" + (if (o.optString("user").isNotEmpty()) "（" + o.optString("user") + "）" else "") +
                "，点这里存进密码库（会先要锁屏验证）"
            vaultBar.visibility = View.VISIBLE
        }
    }

    /** 用户点了提示条：抓页面上的账号密码 → 交给密码库（未解锁会先叫锁屏验证） */
    private fun savePageLogin() {
        val o = pendingLogin ?: return
        vaultBar.visibility = View.GONE
        evalInBrowser("(() => JSON.stringify(window.__CDP && __CDP.loginValues ? __CDP.loginValues() : {ok:false,error:'页面脚本不支持'}))()") { raw ->
            val t = unquote(raw)
            val j = try { org.json.JSONObject(t) } catch (e: Exception) { null }
            if (j == null || !j.optBoolean("ok")) {
                bridge.log("密码库：抓不到页面上的登录信息")
                return@evalInBrowser
            }
            o.put("user", j.optString("user")).put("pass", j.optString("pass"))
            val r = vault.capture(o)
            bridge.log(
                if (r.optBoolean("pending")) "密码库：已挂起 " + o.optString("site") + "，等锁屏验证通过后落盘"
                else "密码库：已保存 " + o.optString("site") + "（" + r.optString("id") + "）"
            )
        }
    }

    fun unquote(raw: String): String {
        var t = raw.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length - 1)
                .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return t
    }


    /** 把"屏蔽不渲染"的选择器推给页面（注入一个 <style>，元素被藏起来但请求照常发生） */
    fun pushCosmetic(view: android.webkit.WebView) {
        try {
            val sels = adblock.cosmeticList()
            // 注意：这里是**多条独立规则**，必须用换行/直接拼接；
            // 用逗号拼成 "a{x},b{y}" 在 CSS 里是语法错误，解析器只会认第一条（踩过）
            val css = if (sels.isEmpty()) "" else sels.joinToString("\n") { "$it{display:none !important}" }
            view.evaluateJavascript(
                "window.__CDP && window.__CDP.cosmetic && window.__CDP.cosmetic(" +
                    org.json.JSONObject.quote(css) + ")",
                null
            )
        } catch (_: Exception) {
        }
    }

    fun pageSecurityJson(): JSONObject {
        val url = lastPageUrl
        val u = try {
            android.net.Uri.parse(url)
        } catch (_: Exception) {
            null
        }
        val host = u?.host ?: ""
        val scheme = u?.scheme ?: ""
        val inList = adblock.matchWarn(url) != null
        var mixed = 0
        val tl = netlog.list(limit = 300)
        for (i in 0 until tl.length()) {
            val e = tl.optJSONObject(i) ?: continue
            if (e.optString("page") == url && e.optString("url").startsWith("http://")) mixed++
        }
        val plain = scheme == "http"
        return JSONObject()
            .put("ok", true).put("url", url).put("host", host).put("scheme", scheme)
            .put("plaintext", plain)
            .put("inBlockList", inList)
            .put("mixedContent", mixed)
            .put(
                "verdict",
                when {
                    plain -> "明文 http：内容可能被人看到/改动，别在这里登录"
                    inList -> "命中你的屏蔽名单：内容照常看，但别输入密码/支付信息"
                    mixed > 0 -> "页面是 https，但混了 $mixed 个 http 子资源（混合内容）"
                    else -> "https 且没混 http 子资源（点「看当前站证书」再看签发者与有效期）"
                }
            )
    }

    /** 基础屏蔽名单的警告条（命中名单的站点，页面顶部挂一条红字，不阻断加载） */
    private lateinit var warnBar: TextView
    private lateinit var muteBtn: TextView
    private lateinit var reloadBtn: TextView
    private lateinit var homeBtn: TextView
    private var connBar: TextView? = null
    private val trafficBaseRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
    private val trafficBaseTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
    lateinit var cookieDb: CookieDb
    @Volatile private var lastConnStep = 0L
    private val connHide = Runnable { connBar?.visibility = View.GONE }

    /** 地址栏聚焦前的可见性（失焦时按原样恢复） */
    private object BarState {
        var wasVisible: List<Boolean> = emptyList()
    }
    private lateinit var vaultBar: TextView

    @Volatile private var pendingLogin: org.json.JSONObject? = null

    /**
     * 老的"基础屏蔽名单"存在 settings 的 blockHosts 里、并且另有一套后缀匹配代码。
     * 本轮并进 AdBlock 的同一份规则库（一个存储 + 一个匹配器 + 一套 op）。
     * 这里只做一次迁移：迁完就把 settings 里那条清掉，避免两份数据打架。
     */
    private fun migrateWarnHostsOnce() {
        try {
            val raw = settings.get("blockHosts")
            if (raw.isBlank()) return
            var moved = 0
            raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach {
                if (adblock.addWarn(it)) moved++
            }
            settings.set("blockHosts", "")
            bridge.log("屏蔽名单已并进拦截规则库：迁移 $moved 条（老的 settings 项已清空）")
        } catch (_: Throwable) {
        }
    }

    fun blockHosts(): List<String> = adblock.warnList()

    fun addBlockHost(h: String): Boolean {
        val ok = adblock.addWarn(h)
        if (ok) bridge.log("已加入警告名单：${h.trim().lowercase()}（命中时页面顶部会挂警告条，不阻断加载）")
        return ok
    }

    fun removeBlockHost(h: String): Boolean = adblock.removeWarn(h)

    fun clearBlockHosts(): Int = adblock.clearWarn()

    /** 当前页命中屏蔽名单就挂警告条（不阻断——用户要的是"基础屏蔽"和知情） */
    private fun updateWarnBar(url: String) {
        try {
            val hit = adblock.matchWarn(url)
            if (hit != null) {
                warnBar.text = "⚠ 这个站点在你的屏蔽名单里（$hit）：内容照常显示，但别在这儿输入密码/支付信息"
                warnBar.visibility = View.VISIBLE
                bridge.log("当前站点命中屏蔽名单：$hit")
            } else {
                warnBar.visibility = View.GONE
            }
        } catch (_: Exception) {
        }
    }

    /** 网络面板的数据源（请求时间线 + 统计） */
    lateinit var netlog: NetLog
        private set

    // ------------------------------------------------------------------ 网络面板

    fun netStatsJson(range: String = "hour"): JSONObject = netlog.stats(range).put("localIps", NetTools.localIps())

    fun netTimeline(filter: String, kind: String, limit: Int): JSONObject = JSONObject()
        .put("ok", true)
        .put("list", netlog.list(filter, kind, limit))
        .put("kinds", netlog.kinds())
        .put("stats", netlog.stats())

    /** 网络测试：解析 / 证书 / 通路 / 测速（都在这里，界面只传名字） */
    fun netToolJson(action: String, host: String, port: Int, url: String): JSONObject = when (action) {
        "ip" -> JSONObject().put("ok", true).put("local", NetTools.localIps())
        "resolve" -> NetTools.resolve(host)
        "cert" -> NetTools.cert(host, if (port > 0) port else 443)
        "tcp" -> NetTools.tcpTest(host, if (port > 0) port else 443)
        // 用户清单第 18 条：查这个网址的 IP + 请求头 + 响应头（CDN 指纹就在响应头里）
        "headers" -> NetTools.headers(url.ifBlank { host }, settings.userAgent() ?: "", lastPageUrl)
        "speed" -> NetTools.speedTest(url.ifBlank { "http://10.0.2.2:8848/api/_test/hls/speed.bin" })
        else -> JSONObject().put("ok", false).put("error", "没有这个测试：$action")
    }

    // ------------------------------------------------------------------ 省电面板
    // 把"在后台悄悄烧 CPU/流量"的东西列出来，逐项开关，另给一个一键省电。
    // 诚实说清代价：关掉视口轮询后，触摸注入用的视口尺寸会用到上一次的值（页面改过缩放就可能偏）。
    @Volatile
    private var metricsEnabled = true

    @Volatile
    private var userScriptsEnabled = true

    fun powerStateJson(): JSONObject = JSONObject()
        // 第 10 条要"两类排序"：**按板块** 与 **按耗电排行**。
        // 老实说：每项真实耗电 App 测不到 → 这里给的是"按已知开销权重排的估算值"，字段名就叫 est，
        // 界面里也必须标明是估算（不编数字，也不假装是实测）。
        .put(
            "items", org.json.JSONArray().apply {
                fun item(k: String, label: String, group: String, on: Boolean, est: Int) {
                    put(
                        JSONObject().put("name", k).put("label", label).put("group", group)
                            .put("on", on).put("est", est).put("costSrc", "估算（按设计开销权重）")
                    )
                }
                item("metrics", "视口尺寸轮询（1.2 秒一次）", "页面", metricsEnabled, 3)
                item("sniff", "资源嗅探", "网络", sniff.isEnabled(), 2)
                item("adblock", "广告拦截", "隐私与拦截", adblock.isEnabled(), 1)
                item("scripts", "用户脚本注入", "自动化", userScriptsEnabled, 2)
                item("rec", "页面录制", "自动化", bridge.recording, 5)
                item("bgplay", "后台播放（熄屏继续放）", "媒体", KeepAliveService.mediaMode, 4)
            }
        )
        .put("metrics", metricsEnabled)
        .put("sniff", sniff.isEnabled())
        .put("bgplay", KeepAliveService.mediaMode)
        .put("adblock", adblock.isEnabled())
        .put("scripts", userScriptsEnabled)
        .put("rec", bridge.recording)
        .put("flags", JSONObject()
            .put("metrics", "视口尺寸轮询（每 1.2 秒；关掉后触摸坐标换算会用旧值）")
            .put("sniff", "资源嗅探（每个请求都要过一遍登记）")
            .put("adblock", "广告拦截（关掉省一点 CPU，但会多下广告流量）")
            .put("scripts", "用户脚本注入（关掉后新开页面不注入油猴脚本）")
            .put("rec", "页面录制中（录制时点击要逐个描述元素，最费）"))

    fun setPowerItem(name: String, on: Boolean): JSONObject {
        when (name) {
            "metrics" -> {
                metricsEnabled = on
                if (!on) metricsTick?.let { main.removeCallbacks(it) } else startMetricsPoller()
            }
            "sniff" -> sniff.setEnabled(on)
            "adblock" -> adblock.setEnabled(on)
            "scripts" -> {
                userScriptsEnabled = on
                if (on) registerDocumentStart()
            }
            "rec" -> if (!on && bridge.recording) main.post { bridge.dispatch("rec.stop", JSONObject()) { } }
            else -> return JSONObject().put("ok", false).put("error", "没有这一项：$name")
        }
        bridge.log("省电面板：$name 已" + (if (on) "打开" else "关闭"))
        return JSONObject().put("ok", true).put("state", powerStateJson())
    }

    /** 一键省电：把纯消耗项关掉（广告拦截留着 —— 它反而是省流量的） */
    fun powerSaveAll(): JSONObject {
        if (bridge.recording) main.post { bridge.dispatch("rec.stop", JSONObject()) { } }
        setPowerItem("metrics", false)
        setPowerItem("sniff", false)
        setPowerItem("scripts", false)
        bridge.log("一键省电：已关掉 视口轮询 / 资源嗅探 / 用户脚本，并停止录制（广告拦截保留）")
        return JSONObject().put("ok", true).put("state", powerStateJson())
    }

    /** 每窗口静音开关（新页面加载后自动再按一次） */
    @Volatile
    private var muted = false

    /** 当前页标题（主线程更新，别的线程要读就用它，别直接读 browser.title） */
    @Volatile
    private var lastTitle = ""

    /** 每窗口静音：记住状态，换页/新链接自动再按一次（页面自己会放声音，只能按） */
    fun setMuted(on: Boolean) {
        muted = on
        bridge.log("静音状态：" + (if (on) "开" else "关"))
    }


    /** 反馈 #13：静音要有明确反馈——图标变、左下角还说一句 */
    private fun toggleMute() {
        val on = !muted
        main.post {
            bridge.dispatch("tab.mute", JSONObject().put("on", on)) { r ->
                bridge.log("静音切换: ${r.toString().take(120)}")
            }
        }
        runOnUiThread {
            muteBtn.text = if (on) "♪✕" else "♪"
            muteBtn.setTextColor(if (on) Color.parseColor("#FFC46B") else Color.parseColor("#CFE0EE"))
            // 反馈 #8：左下角那条只在"真的有请求"时出现，静音这种状态改由图标 + 控制台文字表达
            logMain(if (on) "已静音这一页（页面自己会出声，是靠按住，不是系统级静音）" else "已取消静音")
        }
    }

    /** 左下角的连接过程提示（反馈 #28）：一行、实时刷新、自动消失 */
    fun connStep(text: String) {
        runOnUiThread {
            val v = connBar ?: return@runOnUiThread
            v.text = "…" + text.take(60)
            v.visibility = View.VISIBLE
            v.removeCallbacks(connHide)
            v.postDelayed(connHide, 4000)
        }
    }
    /** 下载时用的"来源页面"（用来做 Referer） */
    fun setDownloadPageHint(page: String) {
        try { hls.pageHint = page } catch (e: Throwable) {}
    }

    /** 当前页地址（给 ffmpeg 当 Referer 用） */
    fun currentUrl(): String = lastPageUrl

    /**
     * 给拉流/下载用的 Referer：只取"协议://域名/"，**不带路径**。
     * 实测 B 站 CDN 只认域名根（带 /video/BV… 这种完整路径会被 403）。
     */
    /**
     * 给拉流/下载用的 Referer：只取"协议://域名/"、**不带路径**。
     * 实测：B 站只认站点域名根（带完整页面路径 403，给 CDN 自己的域名也 403，空 Referer 更 403）。
     * 优先用传进来的"来源页面"（嗅探记录里那条最可靠），拿不到再退到当前页。
     */
    fun refererFor(url: String): String = originOf(url.ifBlank { lastPageUrl })

    /** 把一个地址削成 "协议://域名/"；不是 http(s) 或解析不出来就返回空串 */
    fun originOf(any: String): String {
        if (any.isBlank()) return ""
        return try {
            val u = java.net.URI(any)
            val sch = u.scheme ?: return ""
            val h = u.host ?: return ""
            if ((sch != "http" && sch != "https") || h.isBlank()) "" else "$sch://$h/"
        } catch (e: Throwable) { "" }
    }

    /** 某个地址所在域名的 cookie（名称=值 拼起来），拿不到就空串 */
    fun cookiesForHost(url: String): String {
        return try {
            val h = android.net.Uri.parse(url).host ?: return ""
            val arr = cookiesFor(h)
            val parts = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                parts.add(o.optString("name") + "=" + o.optString("value"))
            }
            parts.joinToString("; ")
        } catch (e: Throwable) { "" }
    }

    fun isMuted(): Boolean = muted

    /** 分享本页：优先分享"标题 + 地址"，没有就分享传进来的文本 */
    fun shareCurrent(text: String = ""): JSONObject {
        return try {
            val url = lastPageUrl
            val prefix = if (lastTitle.isNotBlank()) lastTitle + " " else ""
            val body = when {
                text.isNotBlank() -> text
                url.isBlank() -> return JSONObject().put("ok", false).put("error", "当前没有可分享的地址")
                else -> prefix + url
            }
            val it = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, body)
                putExtra(android.content.Intent.EXTRA_SUBJECT, lastTitle)
            }
            // 没有 App 能接就转成"复制"（也别让用户点了没反应）
            if (it.resolveActivity(packageManager) != null) {
                startActivity(android.content.Intent.createChooser(it, "分享本页"))
                bridge.log("已唤起分享面板：${body.take(80)}")
                JSONObject().put("ok", true).put("via", "chooser").put("text", body)
            } else {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("CDP", body))
                bridge.log("设备上没有可分享的 App，已复制到剪贴板：${body.take(80)}")
                JSONObject().put("ok", true).put("via", "clipboard").put("text", body)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "分享失败")
        }
    }

    /** 元素拾取器抓到元素后：推给控制台并把它打开（用户马上就能用这个选择器） */
    fun onPickedElement(o: JSONObject) {
        main.post {
            try {
                console.evaluateJavascript(
                    "window.__cdpPush && window.__cdpPush(${JSONObject.quote(o.toString())})", null
                )
            } catch (_: Exception) {
            }
            openConsole("page")
        }
    }

    fun onScriptsChanged() {
        main.post { registerDocumentStart() }
    }

    private fun registerDocumentStart() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            docStartOk = false
            return
        }
        try {
            // agent 只注入一次，之后不再动它
            if (!agentInjected) {
                docStartHandlers.add(WebViewCompat.addDocumentStartJavaScript(browser, agentWithToken, setOf("*")))
                agentInjected = true
            }
            scriptGen++
            // 注入只增不减：ScriptHandler.remove() 在这个 WebView 版本上会原生崩溃（SIGSEGV），
            // 所以旧注入不删，靠「内容哈希」在页面里自查失效（见 agent 的 _gm.active）。
            // 内容没变的脚本不用重复注入（避免handler 无限堆积）。
            if (!userScriptsEnabled) {
                docStartOk = true   // agent 已经注入成功，只是省电模式下不注入用户脚本
                return
            }
            val us = store.byKind("userscript")
            for (i in 0 until us.length()) {
                val o = us.getJSONObject(i)
                val code = o.optString("code")
                if (code.isBlank()) continue
                val id = o.optString("id")
                val h = hashOf(code + "|" + o.optString("match"))
                if (injectedHashes[id] == h) continue
                try {
                    docStartHandlers.add(WebViewCompat.addDocumentStartJavaScript(browser, buildUserScript(o, h), setOf("*")))
                    injectedHashes[id] = h
                } catch (e: Exception) {
                    bridge.log("脚本《${o.optString("name")}》注入失败: ${e.message}")
                }
            }
            docStartOk = true
        } catch (e: Exception) {
            docStartOk = false
            bridge.log("document-start 注入不可用: ${e.message}")
        }
    }

    private fun hashOf(s: String): String {
        var h = 1125899906842597L
        for (c in s) h = 31 * h + c.code
        return java.lang.Long.toHexString(h)
    }

    /** 当前「有效的用户脚本清单」：页面在 document-start 时同步取这个（含内容哈希），用来判断旧注入是否还有效 */
    fun scriptMetaJson(): String {
        val scripts = JSONObject()
        val us = store.byKind("userscript")
        for (i in 0 until us.length()) {
            val o = us.getJSONObject(i)
            val id = o.optString("id")
            if (id.isEmpty()) continue
            scripts.put(id, hashOf(o.optString("code") + "|" + o.optString("match")))
        }
        return JSONObject().put("gen", scriptGen).put("scripts", scripts)
            // 录制状态也放进来：子 frame（比如 iframe 里的播放器）拿不到顶层文档的 rec 变量，
            // 靠这个镜像知道自己也该记 —— 否则「录制对视频播放器没效果」（播放器在 iframe 里）。
            .put("recording", bridge.recording)
            .put("recName", bridge.recordNameForPage())
            .put("recMode", bridge.recordModeForPage())
            .toString()
    }

    /** 单个油猴脚本的注入包装：内容哈希对上才跑、命中站点才跑、按 @run-at 选时机、GM_* 按脚本分命名空间 */
    private fun buildUserScript(o: JSONObject, hash: String): String {
        val id = o.optString("id")
        val name = o.optString("name")
        val match = o.optString("match").ifBlank { "*" }
        val code = o.optString("code")
        val at = Regex("""@run-at\s+(\S+)""").find(code)?.groupValues?.get(1) ?: "document-idle"
        val args = GM_PARAMS.joinToString(", ") { "A.$it" }
        val sb = StringBuilder()
        sb.append("(function(){try{\n")
        sb.append("var U=window.__CDP&&window.__CDP._gm; if(!U) return;\n")
        sb.append("if(!U.active(").append(JSONObject.quote(id)).append(", ").append(JSONObject.quote(hash)).append(")) return;\n")
        sb.append("if(!U.match(").append(JSONObject.quote(match)).append(", location.href)) return;\n")
        sb.append("var A=U.api(").append(JSONObject.quote(id)).append(", ").append(JSONObject.quote(name)).append(");\n")
        sb.append("U.run(").append(JSONObject.quote(at)).append(", function(){\n")
        sb.append("try{\n")
        sb.append("(function(").append(GM_PARAMS.joinToString(", ")).append("){\n")
        sb.append(code).append("\n})(").append(args).append(");\n")
        sb.append("U.log(").append(JSONObject.quote("脚本已执行: $name")).append(");\n")
        sb.append("}catch(e){ U.log(").append(JSONObject.quote("脚本报错 $name: ")).append("+ (e&&e.message)); }\n")
        sb.append("});\n")
        sb.append("}catch(e){}})();")
        return sb.toString()
    }

    // ------------------------------------------------------------------ 页面 ↔ 原生

    fun evalInBrowser(js: String, cb: (String) -> Unit) {
        main.post {
            try {
                browser.evaluateJavascript(js) { v -> cb(v ?: "null") }
            } catch (e: Exception) {
                cb("\"__EVAL_ERROR__ ${e.message}\"")
            }
        }
    }

    fun browserStateJson(): JSONObject {
        val o = JSONObject()
            .put("url", browser.url ?: "")
            .put("title", browser.title ?: "")
            .put("canGoBack", browser.canGoBack())
            .put("canGoForward", browser.canGoForward())
            .put("progress", browser.progress)
            .put("cssW", cssW).put("cssH", cssH)
            .put("viewW", browser.width).put("viewH", browser.height)
            .put("recording", bridge.recording)
        stateCache = o
        return o
    }

    /**
     * 只读的缓存快照：WebView 的 getUrl()/canGoBack() 等只能在主线程调用，
     * 外部 HTTP 控制口的处理线程直接读缓存（否则会抛异常且被吞掉，表现为「请求无响应」）。
     */
    fun stateCached(): JSONObject = stateCache

    fun cdpSocketHint(): String =
        "webview_devtools_remote_${android.os.Process.myPid()}  （adb forward tcp:9222 localabstract:webview_devtools_remote_${android.os.Process.myPid()}）"

    /** 把 CSS 坐标换算成 View 像素并注入一次真实触摸（isTrusted=true 的那条路） */
    fun injectTap(cssX: Double, cssY: Double): Boolean {
        val w = browser
        if (cssW <= 0.5) {
            // 视口尺寸还没取到（刚加载完那一下最容易撞上）：主动刷新，随后补一次同样的注入
            refreshMetrics()
            main.postDelayed({
                if (cssW <= 0.5) bridge.log("触摸注入失败：拿不到页面视口宽度（cssW=0）")
                else injectTapNow(cssX, cssY)
            }, 160)
            return true
        }
        return injectTapNow(cssX, cssY)
    }

    private fun injectTapNow(cssX: Double, cssY: Double): Boolean {
        val w = browser
        val cw = cssW
        if (cw <= 0.5) return false
        val ratio = w.width.toDouble() / cw
        val x = (cssX * ratio).toFloat()
        val y = (cssY * ratio).toFloat()
        if (x < 1f || y < 1f || x > w.width - 1 || y > w.height - 1) {
            bridge.log("注入点超出 WebView: css=(${cssX.toInt()},${cssY.toInt()}) → px=(${x.toInt()},${y.toInt()}) 视口 ${w.width}x${w.height}")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
        w.requestFocus()
        w.dispatchTouchEvent(down)
        main.postDelayed({ w.dispatchTouchEvent(up) }, 60)
        return true
    }

    private fun refreshMetrics() {
        evalInBrowser("window.__CDP ? window.__CDP.metrics() : 'null'") { raw ->
            try {
                val s = if (raw.startsWith("\"")) org.json.JSONTokener(raw).nextValue() as String else raw
                if (s != "null" && s.startsWith("{")) {
                    val o = JSONObject(s)
                    cssW = o.optDouble("vw", 0.0)
                    cssH = o.optDouble("vh", 0.0)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun startMetricsPoller() {
        val tick = object : Runnable {
            override fun run() {
                // 实例已经销毁就别再碰 WebView 了（不然 logcat 里会刷
                // 「Application attempted to call on a destroyed WebView」）
                if (isFinishing || isDestroyed) return
                if (!metricsEnabled) return   // 省电：关掉视口轮询就彻底不采样（触摸换算会用上一次的值）
                try {
                    // 一直保持「页面视口尺寸」新鲜：触摸注入要把 CSS 坐标换算成 View 像素，
                    // 这个值一旧，注入点就会偏。忙的时候（回放/录制）加密采样。
                    if (overlay.visibility == View.VISIBLE || bridge.recording || busy || !browser.url.isNullOrEmpty()) {
                        refreshMetrics()
                    }
                } catch (_: Exception) {
                }
                main.postDelayed(this, if (busy) 300L else 1200L)
            }
        }
        metricsTick = tick
        main.postDelayed(tick, 800)
    }

    // ------------------------------------------------------------------ 返回键

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (overlay.visibility == View.VISIBLE) { closeConsole(); return true }
            if (browser.canGoBack()) { browser.goBack(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- 坐标录制（原生层）
    private var coordDownX = 0f
    private var coordDownY = 0f
    private var coordDownT = 0L

    // 长按检测（"把这里当视频"的入口之一）
    private var lpX = 0f
    private var lpY = 0f
    private var lpDown = 0L
    private var lpMoved = false

    /**
     * 坐标录制在**这一层**抓触摸。
     *
     * 为什么不在页面 JS 里抓（原来那套）：学习通这类播放器把视频放在**跨域 iframe**或**原生视频表面**里，
     * 页内脚本收不到 pointerdown —— 用户报的"我点其他地方都能录坐标，点播放视频就录不了"就是这个。
     * Activity.dispatchTouchEvent 在派发之前就能看到所有触摸（谁最后消费掉都不影响），
     * 把窗口坐标换算成页面 CSS 坐标后交给 Bridge 记成坐标步（回放走的是同一套锚点，不用改）。
     *
     * 同一个位置还承担另外两件事：
     *  · **"指定哪一块是视频"**（用户要求：有些页面自动解析不出）—— 选取期间这一下**吞掉**，不传给页面；
     *  · **长按页面任意处**弹菜单，里面也能选"把这里当视频"（用户要求的第二个入口）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        try {
            if (bridge.pickMode) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        coordDownX = ev.x; coordDownY = ev.y; coordDownT = SystemClock.uptimeMillis()
                    }
                    MotionEvent.ACTION_UP -> {
                        val dt = SystemClock.uptimeMillis() - coordDownT
                        val moved = Math.abs(ev.x - coordDownX) + Math.abs(ev.y - coordDownY)
                        coordDownT = 0L
                        if (dt < 900 && moved < 40) {
                            val css = cssFromWindow(ev.x, ev.y)
                            if (css != null) bridge.pickAt(css.first, css.second, null)
                            else bridge.log("指定位置：点在页面之外，跳过")
                        }
                        return true              // 吞掉这一下：页面别也跟着响应（相当于一次"指位置"手势）
                    }
                    MotionEvent.ACTION_CANCEL -> { coordDownT = 0L; return true }
                }
                return true
            }
            if (bridge.recording && bridge.recordModeNow() == "coord") {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        coordDownX = ev.x; coordDownY = ev.y; coordDownT = SystemClock.uptimeMillis()
                    }
                    MotionEvent.ACTION_UP -> {
                        val dt = SystemClock.uptimeMillis() - coordDownT
                        val moved = Math.abs(ev.x - coordDownX) + Math.abs(ev.y - coordDownY)
                        // 只记"戳一下"：滑动/长按不算点击（长按常常是播放器的操作手势）
                        if (coordDownT > 0 && dt < 900 && moved < 40) {
                            val css = cssFromWindow(ev.x, ev.y)
                            if (css != null) bridge.addNativeCoordStep(css.first, css.second)
                            else bridge.log("坐标录制（原生）：点数在页面之外，跳过 (${ev.x.toInt()}, ${ev.y.toInt()})")
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> coordDownT = 0L
                }
            } else if (sniff.isEnabled() && !bridge.recording && !busy) {
                // 长按 600ms 且没怎么移动 → 弹一个菜单，里面可以"把这里当视频"
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lpX = ev.x; lpY = ev.y; lpDown = SystemClock.uptimeMillis(); lpMoved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(ev.x - lpX) + Math.abs(ev.y - lpY) > 40) lpMoved = true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dt = SystemClock.uptimeMillis() - lpDown
                        if (lpDown > 0 && !lpMoved && dt >= 600) showPickMenu(lpX, lpY)
                        lpDown = 0L
                    }
                    MotionEvent.ACTION_CANCEL -> lpDown = 0L
                }
            }
        } catch (t: Throwable) {
            bridge.log("坐标录制（原生）出错：" + t.message)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 长按弹的小菜单（用户要求"长按页面任意处也能把这里当视频"） */
    private fun showPickMenu(x: Float, y: Float) {
        try {
            val css = cssFromWindow(x, y) ?: return
            bridge.log("长按页面：菜单（把这里当视频 / 取消）")
            android.app.AlertDialog.Builder(this)
                .setItems(arrayOf("把这里当视频", "取消")) { _, which ->
                    if (which == 0) bridge.pickAt(css.first, css.second, null)
                }
                .show()
        } catch (t: Throwable) {
            bridge.log("长按菜单出错：" + t.message)
        }
    }

    /** 页面 CSS 坐标 → 屏幕坐标（无障碍覆盖层的小点要用屏幕坐标画） */
    fun screenFromCss(cx: Double, cy: Double): Pair<Float, Float>? {
        val w = browser
        if (cssW <= 0.5 || w.width <= 0) return null
        val ratio = w.width.toDouble() / cssW
        val loc = IntArray(2)
        w.getLocationInWindow(loc)
        return Pair((loc[0] + cx * ratio).toFloat(), (loc[1] + cy * ratio).toFloat())
    }

    /** 几何：WebView 在屏幕上的位置 + 页面视口 + 像素比（小点坐标换算、自动化点准位置都要用） */
    fun geomJson(): org.json.JSONObject {
        val w = browser
        val loc = IntArray(2)
        w.getLocationInWindow(loc)
        val ratio = if (cssW > 0.5) (w.width.toDouble() / cssW) else 0.0
        return org.json.JSONObject()
            .put("webviewX", loc[0]).put("webviewY", loc[1])
            .put("webviewW", w.width).put("webviewH", w.height)
            .put("cssW", cssW).put("cssH", cssH).put("ratio", ratio)
            .put("density", resources.displayMetrics.density.toDouble())
    }

    /** 打开系统的无障碍设置页（小点覆盖层要在那里手动开一次） */
    /** 让小点单击 → 控制台里弹出这一步的编辑小窗（控制台没开就顺手打开） */
    fun openStepEditorInConsole(n: Int) {
        main.post {
            val opened = overlay.visibility == View.VISIBLE
            if (!opened) openConsole("rec")
            // 控制台刚被叫出来时，页面里的 __cdpStepEditor 可能还没定义 —— 以前这里只试一次就静默过去了，
            // 表现为"点小点没反应"。改成轮询重试：页面一就绪就把这一步的小窗开出来。
            val js = "(function(){if(!window.__cdpStepEditor)return 'retry';" +
                "window.__cdpStepEditor($n);return 'ok';})()"
            var tries = 0
            val attempt = object : Runnable {
                override fun run() {
                    tries++
                    runCatching {
                        console.evaluateJavascript(js) { res ->
                            if (res != null && res.contains("retry") && tries < 25) {
                                main.postDelayed(this, 400)
                            }
                        }
                    }
                }
            }
            main.postDelayed(attempt, if (opened) 150 else 900)
        }
    }

    /** 窗口坐标 → 页面 CSS 坐标（和注入触摸用的那套换算一致：WebView 宽度 / 页面视口宽度） */
    private fun cssFromWindow(wx: Float, wy: Float): Pair<Double, Double>? {
        val w = browser
        if (cssW <= 0.5 || w.width <= 0) return null
        val loc = IntArray(2)
        w.getLocationInWindow(loc)
        val lx = wx - loc[0]
        val ly = wy - loc[1]
        if (lx < 0 || ly < 0 || lx > w.width || ly > w.height) return null
        val ratio = w.width.toDouble() / cssW
        return Pair(lx / ratio, ly / ratio)
    }

    override fun onPause() {
        // 退后台/熄屏：把这一小段结算掉（不足 15 秒的零头也不会丢）
        try { pageTimer.flush(true) } catch (_: Exception) {}
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        refreshMetrics()
    }
}
