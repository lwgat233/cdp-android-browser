package dev.cdp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cookie 的**属性**（反馈 #3：HttpOnly / Secure / Path / 创建时间 / 最后使用时间）。
 *
 * 背景：Android 的 `CookieManager` 只给「名称=值」，属性一概不给；但 WebView 自己那份
 * Chromium cookie 库就在**应用私有目录**里（`app_webview/Default/Cookies`），不用 root 也能读。
 *
 * 做法：把库**复制一份**再以只读方式打开（不碰正在用的那份，避免锁/写坏），
 * 查 `cookies` 表的属性列，和 CookieManager 的「名称=值」按 (host, name) 合并。
 *
 * **一次快照**（B-84 的教训）：读库 + `flush()` 都很贵，而界面是"每个域名都要一份属性"。
 * 以前每域名读一遍库、还每域名 flush 一遍、没命中就每域名等 600ms —— 21 个书签域名时
 * `/api/cookies` 从 0.3s 涨到 **17s**，而这活是在 UI 线程上跑的 → 整个界面卡死。
 * 现在：一次请求只读一次库（`snapshot(force = true)`），其余都复用同一份快照。
 *
 * 如实说明（会写进返回里）：
 *  · 值仍来自 CookieManager（Chromium 在库里把 value 加密存，密钥在系统 Keystore 里，应用读不出明文）；
 *  · 时间列是 Chromium 自己的纪元（1601-01-01 起的微秒），这里换算成可读时间；
 *  · 库里只有 WebView 见过的 cookie，别的 App 的看不到（也读不到）。
 */
class CookieDb(private val ctx: Context) {

    /** 快照缓存：TTL 内复用，避免"每个域名读一遍库" */
    @Volatile
    private var cached: JSONArray? = null

    @Volatile
    private var cachedAt = 0L

    private val lock = Any()

    private val ttlMs = 3000L

    private fun dbFile(): File? {
        val cands = listOf(
            File(ctx.dataDir, "app_webview/Default/Cookies"),
            File(ctx.dataDir, "app_webview/Cookies"),
            File(ctx.dataDir, "app_webview/Default/Network/Cookies")
        )
        return cands.firstOrNull { it.exists() && it.length() > 0 }
    }

    /** Chromium 时间：1601-01-01 起的微秒 → 可读字符串（0 表示"会话期"） */
    private fun chromiumTime(v: Long): String {
        if (v <= 0) return "会话期（关掉浏览器就失效）"
        // 1601-01-01 → 1970-01-01 相差 11644473600 秒
        val ms = v / 1000 - 11644473600000L
        if (ms <= 0) return "会话期"
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date(ms))
    }

    /**
     * 属性快照：`[{host, name, path, secure, httpOnly, created, lastAccess, expires, expiresMs}]`。
     * `force = true` 才真的重新读库（一次请求用一次）；否则 TTL 内直接复用上一份。
     */
    fun snapshot(force: Boolean = false): JSONArray {
        val now = System.currentTimeMillis()
        val c = cached
        if (!force && c != null && now - cachedAt < ttlMs) return c
        synchronized(lock) {
            val c2 = cached
            val now2 = System.currentTimeMillis()
            if (!force && c2 != null && now2 - cachedAt < ttlMs) return c2
            var arr = readOnce()
            // 一整个库都读不到（首次启动/库还没生成）就等一拍再读一次，最多 2 × 150ms。
            // 注意：这个等待只在**整库为空**时发生，不是"每个域名都等"。
            var tries = 0
            while (arr.length() == 0 && tries < 2) {
                try { Thread.sleep(150) } catch (_: InterruptedException) {}
                arr = readOnce()
                tries++
            }
            cached = arr
            cachedAt = System.currentTimeMillis()
            return arr
        }
    }

    /** 真的读一次库：先 flush（Chromium 批量提交，不 flush 时刚写的 cookie 还没落盘），复制副本再查 */
    private fun readOnce(): JSONArray {
        // 先写盘：实测页面刚写的 cookie 库里 3 秒内 0 行、约 60 秒才有；flush 后 1 秒内全在。
        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Throwable) {}
        val out = JSONArray()
        val src = dbFile() ?: run {
            android.util.Log.w("cdp", "cookie 库还没生成（WebView 首次写 cookie 后才有）")
            return out
        }
        var tmp: File? = null
        var db: SQLiteDatabase? = null
        try {
            // 复制一份再读：不干扰正在使用的库
            val dir = File(ctx.cacheDir, "cookiedb").apply { mkdirs() }
            tmp = File(dir, "Cookies.copy")
            src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            // 顺带把 -wal/-journal 也带上，否则最新写入的 cookie 可能不在主库里
            listOf("-wal", "-journal").forEach { suf ->
                val extra = File(src.absolutePath + suf)
                if (extra.exists()) extra.inputStream().use { i ->
                    File(tmp!!.absolutePath + suf).outputStream().use { o -> i.copyTo(o) }
                }
            }
            db = SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.rawQuery(
                "SELECT host_key, name, path, is_secure, is_httponly, creation_utc, last_access_utc, expires_utc " +
                    "FROM cookies ORDER BY last_access_utc DESC LIMIT 500", null
            ).use { c ->
                while (c.moveToNext()) {
                    out.put(
                        JSONObject()
                            .put("host", c.getString(0))
                            .put("name", c.getString(1))
                            .put("path", c.getString(2))
                            .put("secure", c.getInt(3) == 1)
                            .put("httpOnly", c.getInt(4) == 1)
                            .put("created", chromiumTime(c.getLong(5)))
                            .put("lastAccess", chromiumTime(c.getLong(6)))
                            .put("expires", chromiumTime(c.getLong(7)))
                            // 同时给出机器可读的过期时刻（毫秒，0 = 会话期）：界面要按它算"有效期还剩几天"，
                            // 不能靠解析那句中文
                            .put("expiresMs", maxOf(0L, c.getLong(7) / 1000 - 11644473600000L))
                    )
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("cdp", "读 cookie 属性失败：" + e.message)
        } finally {
            try { db?.close() } catch (e: Throwable) {}
            // 副本连同 -wal/-journal 一起删：以前只删主文件，cache/cookiedb 里留了一堆垃圾
            try { tmp?.delete() } catch (e: Throwable) {}
            try { File(tmp!!.absolutePath + "-wal").delete() } catch (e: Throwable) {}
            try { File(tmp!!.absolutePath + "-journal").delete() } catch (e: Throwable) {}
        }
        return out
    }

    /**
     * 把属性并到「名称=值」上。
     * `attrs` 传了就用传进来的那份（一次请求共用一份快照），没传就取当前快照（TTL 内不重读）。
     */
    fun merge(list: JSONArray, attrs: JSONArray = snapshot()): JSONArray {
        if (attrs.length() == 0) return list
        val idx = HashMap<String, JSONObject>()
        for (i in 0 until attrs.length()) {
            val o = attrs.optJSONObject(i) ?: continue
            idx[(o.optString("host") + "|" + o.optString("name")).lowercase()] = o
        }
        // 退一步只按名字找用的表（域名写法带点/不带点都可能）——只在简单匹配落空时才查
        val byName = HashMap<String, JSONObject>()
        for (i in 0 until attrs.length()) {
            val o = attrs.optJSONObject(i) ?: continue
            val n = o.optString("name").lowercase()
            if (n.isNotEmpty() && !byName.containsKey(n)) byName[n] = o
        }
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val key = (c.optString("domain") + "|" + c.optString("name")).lowercase()
            val hit = idx[key] ?: byName[c.optString("name").lowercase()]
            if (hit != null) {
                c.put("attrs", hit)
                c.put("path", hit.optString("path"))
                c.put("secure", hit.optBoolean("secure"))
                c.put("httpOnly", hit.optBoolean("httpOnly"))
                c.put("created", hit.optString("created"))
                c.put("lastAccess", hit.optString("lastAccess"))
                c.put("expires", hit.optString("expires"))
                c.put("expiresMs", hit.optLong("expiresMs"))
                c.put("attrFrom", "WebView 自己的 cookie 库（应用私有目录，只读复制一份再查）")
            } else {
                // 如实说清：这条在库里还没有（刚写入的，或只被别的来源写过）——不要假装属性是"否"
                c.put("attrMissing", "库里还没有这条的属性（刚写入的要稍等一下）")
            }
        }
        return list
    }

    /** 库里真有 cookie 的主机名（去重；"登录了哪些网站"的权威来源，比历史/书签域名准且省） */
    fun hosts(attrs: JSONArray = snapshot()): List<String> {
        val out = LinkedHashSet<String>()
        for (i in 0 until attrs.length()) {
            val h = attrs.optJSONObject(i)?.optString("host").orEmpty().trim().removePrefix(".")
            if (h.isNotEmpty()) out.add(h)
        }
        return out.toList()
    }
}
