package dev.cdp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 资源嗅探：把页面里出现过的媒体地址记下来（视频 / 音频 / 播放列表 / 分片 / 字幕）。
 *
 * 抓取点有两处：
 *  ① WebView 的 shouldInterceptRequest —— 页面里的每个子资源请求都会经过那里（只登记、不改动）；
 *  ② 页面里再扫一遍（performance 资源 + video/audio/source 标签）——
 *     有些站用 fetch/XHR 拿清单、或者把地址写在 JS 里，请求层看不到"人想看的那一条"。
 *
 * 本轮的改进（多个视频网站实测出来的需求）：
 *  - 扩展名认得更全：hls(m3u8) / dash(mpd) / 视频(mp4/m4v/m4s/fmp4/cmfv/f4v/webm/mkv/flv) /
 *    音频(mp3/m4a/aac/flac/opus/ogg/oga/weba/wav) / 字幕(vtt/srt/ass)；
 *  - **分片不再刷屏**：一个 HLS 频道会有成百上千个 .ts/.m4s 分片，全列出来等于把清单冲掉。
 *    现在同一路（同一个目录）的分片合并成一条，只记"分片 ×N + 最近一片的地址"；
 *  - 每条带上 host / 文件名 / 扩展名 / 归类，界面能按"只看音频""只看播放列表"筛。
 */
class Sniffer(private val ctx: Context) {
    /** 省电面板可以整体关掉嗅探（关掉后就不再登记请求） */
    @Volatile
    private var enabled = true

    fun isEnabled(): Boolean = enabled

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) clear()
    }

    private val file = File(ctx.filesDir, "sniff.json")
    private val lock = Any()
    private val items = LinkedHashMap<String, JSONObject>()
    private val MAX = 400

    init {
        load()
    }

    private fun load() {
        synchronized(lock) {
            try {
                if (file.exists()) {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val u = o.optString("url")
                        if (u.isNotEmpty()) items[o.optString("key").ifBlank { u }] = o
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            synchronized(lock) { items.values.forEach { arr.put(it) } }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ 认类型

    /** 扩展名 → 类型名；不是想抓的资源返回 null */
    fun kindOf(url: String): String? {
        val u = url.lowercase()
        if (u.startsWith("data:") || u.startsWith("blob:") || u.startsWith("javascript:")) return null
        val path = u.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") -> "m3u8"
            path.endsWith(".mpd") -> "mpd"
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".f4v") -> "mp4"
            path.endsWith(".m4s") || path.endsWith(".cmfv") || path.endsWith(".fmp4") -> "m4s"
            path.endsWith(".webm") -> "webm"
            path.endsWith(".mkv") -> "mkv"
            path.endsWith(".flv") -> "flv"
            path.endsWith(".ts") -> "ts"
            path.endsWith(".mp3") -> "mp3"
            path.endsWith(".m4a") || path.endsWith(".m4b") -> "m4a"
            path.endsWith(".aac") -> "aac"
            path.endsWith(".flac") -> "flac"
            path.endsWith(".opus") -> "opus"
            path.endsWith(".ogg") || path.endsWith(".oga") -> "ogg"
            path.endsWith(".weba") -> "weba"
            path.endsWith(".wav") -> "wav"
            path.endsWith(".vtt") -> "vtt"
            path.endsWith(".srt") -> "srt"
            path.endsWith(".ass") -> "ass"
            else -> null
        }
    }

    /**
     * 归类：**按文件类型（扩展名）分**（用户 N2：不要按视频/音频这种媒体分类，直接按文件来）。
     * 所以 group 就是 kind 本身（m3u8 / mp4 / ts / jpg …），界面拿它当分组标题与筛选项。
     */
    fun groupOf(kind: String): String = kind.ifBlank { "other" }

    /** 排序用的大类（只影响列表里的先后：播放列表 → 视频 → 音频 → 图片 → 其它） */
    fun orderOf(kind: String): Int = when (kind) {
        "m3u8", "mpd" -> 0
        "mp4", "m4v", "webm", "mkv", "flv" -> 1
        "mp3", "m4a", "aac", "flac", "opus", "ogg", "weba", "wav" -> 2
        "ts", "m4s" -> 3
        "vtt", "srt", "ass" -> 4
        else -> 5
    }

    /** 是不是"某一路的分片"（HLS/DASH 的分片一个频道几百个，必须合并） */
    private fun fragKeyOf(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val file = path.substringAfterLast('/')
        if (file.isEmpty()) return null
        val looksSeg = Regex("""(^|[^a-z])(seg|segment|frag|chunk)?[-_]?\d{1,6}\.(ts|m4s|cmfv|fmp4|aac|mp4)$""", RegexOption.IGNORE_CASE).containsMatchIn(file) ||
            file.matches(Regex("""\d{1,6}\.(ts|m4s|aac)""", RegexOption.IGNORE_CASE))
        if (!looksSeg) return null
        val dir = path.substringBeforeLast('/', path)
        return dir.ifEmpty { null }
    }

    private fun hostOf(url: String): String = try {
        android.net.Uri.parse(url).host?.lowercase() ?: ""
    } catch (_: Exception) {
        ""
    }

    // ------------------------------------------------------------------ 登记

    /** 登记一条；同一条地址只记一次；分片按"同一路"合并计数。
     *  `from`/`via` 给"手动指定位置"用：记下它是怎么进来的（来源 + 那一块的元素） */
    fun add(url: String, page: String = "", force: Boolean = false, from: String = "", via: String = ""): Boolean {
        if (url.isBlank()) return false
        val u = url.trim()
        if (u.startsWith("blob:") || u.startsWith("data:")) return false
        val kind = kindOf(u) ?: if (force) (if (from.isNotBlank()) "手动指定" else "other") else return false
        val group = groupOf(kind)
        val fragKey = if (kind == "ts" || kind == "m4s") fragKeyOf(u) else null
        val key = if (fragKey != null) "frag|$fragKey" else u
        val name = u.substringBefore('?').substringAfterLast('/').take(120)
        synchronized(lock) {
            val exist = items[key]
            if (exist != null) {
                if (fragKey != null) {
                    exist.put("segs", exist.optInt("segs", 1) + 1)
                    exist.put("lastSeg", u)
                    exist.put("lastTs", System.currentTimeMillis())
                }
                return false
            }
            val o = JSONObject()
                .put("key", key)
                .put("url", u)
                .put("kind", kind)
                .put("group", group)
                .put("ext", kind)
                .put("host", hostOf(u))
                .put("name", name)
                .put("page", page)
                .put("ts", System.currentTimeMillis())
            if (from.isNotBlank()) o.put("from", from)
            if (via.isNotBlank()) o.put("via", via)
            if (fragKey != null) {
                o.put("segs", 1).put("fragDir", fragKey).put("isFrag", true)
            }
            items[key] = o
            while (items.size > MAX) {
                val k = items.keys.firstOrNull() ?: break
                items.remove(k)
            }
        }
        persist()
        return true
    }

    /** 界面顺序：播放列表 → 音频 → 视频 → 字幕 → 分片；同类按时间倒序（最近看到的在前） */
    fun list(): JSONArray {
        // 排序：先按大类（播放列表 → 视频 → 音频 → 分片 → 字幕 → 其它），同类里按时间新到旧
        val order = HashMap<String, Int>()
        items.values.forEach { val k = it.optString("kind"); if (k.isNotBlank()) order[k] = orderOf(k) }
        val all = synchronized(lock) { items.values.map { JSONObject(it.toString()) } }
        val sorted = all.sortedWith(
            compareBy({ order[it.optString("group")] ?: 9 }, { -it.optLong("ts", 0L) })
        )
        val arr = JSONArray()
        sorted.forEach { arr.put(it) }
        return arr
    }

    fun count(): Int = synchronized(lock) { items.size }

    /** 按**文件类型**数一遍（界面状态文字用它：m3u8 x 条 · mp4 y 条 · ts z 条…） */
    fun stats(): JSONObject {
        val m = HashMap<String, Int>()
        synchronized(lock) {
            items.values.forEach { val k = it.optString("kind").ifBlank { "other" }; m[k] = (m[k] ?: 0) + 1 }
        }
        val byKind = JSONObject()
        m.entries.sortedByDescending { it.value }.forEach { (k, v) -> byKind.put(k, v) }
        return JSONObject().put("total", count()).put("byKind", byKind)
    }

    fun clear() {
        synchronized(lock) { items.clear() }
        persist()
    }

    // ---------------------------------------------------------------- 最近请求缓冲（给"指定位置为视频"找候选）
    // 平时只按扩展名登记，**无扩展名的地址会被丢掉** —— 这正是"有些网页解析不出视频"的来源之一
    // （例如 /media/stream?id=7 这种）。这里留一份只保留最近 60 秒的请求流水（含未知类型，
    // 但不含图片/字体/脚本这类噪音），专门给"指定块"时找候选用：不进清单、不落盘、界面看不到，
    // 所以不会污染嗅探列表。
    private val recent = ArrayDeque<JSONObject>()
    private val recentLock = Any()

    /** 每一个 http(s) 请求都过一下这里（认得出的走正常登记，认不出的留个流水） */
    fun noteRequest(url: String, page: String = "") {
        if (!enabled) return
        if (kindOf(url) != null) return                       // 认得出的已经在 add() 里了
        if (page.isNotBlank() && url == page) return           // 页面自己那条不算候选
        val u = url.lowercase()
        if (u.startsWith("data:") || u.startsWith("blob:") || u.startsWith("javascript:")) return
        val path = u.substringBefore('?')
        if (NOISE.containsMatchIn(path)) return                // 图片/字体/脚本/样式这些别占位置
        // 页面文档本身（path 与当前页 path 一样）也别当候选
        try {
            val pu = android.net.Uri.parse(page)
            val ppath = pu.path.orEmpty()
            if (ppath.isNotBlank() && path.substringAfter(pu.host.orEmpty()) == ppath.lowercase()) return
        } catch (_: Exception) {}
        synchronized(recentLock) {
            recent.addLast(
                JSONObject().put("url", url).put("host", hostOf(url)).put("page", page)
                    .put("ts", System.currentTimeMillis())
            )
            while (recent.size > 400) recent.removeFirst()
        }
    }

    /** 最近 windowMs 内的请求（可按 host / 页面过滤），新的在前 */
    fun recentRequests(windowMs: Long = 60_000L, host: String = "", pageFilter: String = ""): JSONArray {
        val now = System.currentTimeMillis()
        val out = JSONArray()
        synchronized(recentLock) {
            while (recent.isNotEmpty() && now - recent.first().optLong("ts") > windowMs) recent.removeFirst()
            recent.toList().asReversed().forEach { o ->
                if (host.isNotBlank() && !o.optString("host").equals(host, true)) return@forEach
                // 只在**同一个页面**发过的请求里找候选：不然会把上一个页面的视频串进来
                if (pageFilter.isNotBlank() && o.optString("page") != pageFilter) return@forEach
                out.put(JSONObject(o.toString()))
            }
        }
        return out
    }

    private val NOISE = Regex("\\.(png|jpe?g|gif|webp|svg|ico|woff2?|ttf|otf|css|js|mjs|json|map|txt|html?)$")
}
