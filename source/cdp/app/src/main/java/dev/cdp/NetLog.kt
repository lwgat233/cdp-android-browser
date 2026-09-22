package dev.cdp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 网络面板的数据源：把页面发出的 http(s) 请求记成一条时间线，并统计。
 *
 * 能力边界（界面上也会写）：
 *  - WebView **不**把响应状态码/响应头/响应体大小给 App，所以这里只能记
 *    「什么时候请求了哪个地址、是媒体还是被广告拦截掐掉的」这类**请求侧**信息。
 *  - 上/下行字节只统计**应用自己搬运的**（应用内下载、m3u8 分片、代理中继），
 *    页面内的流量拿不到——不编数字。
 */
class NetLog(private val ctx: Context) {

    private val lock = Any()
    private val ring = ArrayDeque<JSONObject>()
    private val file = File(ctx.filesDir, "netlog.json")

    @Volatile
    private var total = 0L

    @Volatile
    private var blocked = 0L

    @Volatile
    private var media = 0L

    /** 应用自己搬运的字节（下载 / m3u8 分片 / 中继转发） */
    private var bytesDown = 0L
    private var bytesUp = 0L
    private var bytesDownFg = 0L      // 其中"应用在前台"时搬的（其余为后台搬的）
    private var bytesUpFg = 0L

    /**
     * 字节**事件**：每次搬运记一条 {from,to,down,up,host,page}。
     * 为什么要带时间：用户要求"0~24 小时内每小时的上行/下行柱状图 + 按站点饼图"，
     * 只有累计值算不出分桶。分桶/汇总统一交给 `Stats`（同一个统计器，阅读时长也用那个）。
     */
    private val byteEvents = ArrayDeque<JSONObject>()

    private var enabled = true

    fun isEnabled(): Boolean = enabled

    fun setEnabled(on: Boolean) {
        enabled = on
    }

    fun note(url: String, page: String, kind: String, isBlocked: Boolean, rule: String = "") {
        if (!enabled) return
        total++
        if (isBlocked) blocked++
        if (kind.isNotBlank() && kind != "other") media++
        val host = try {
            android.net.Uri.parse(url).host ?: ""
        } catch (_: Exception) {
            ""
        }
        val e = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("url", url.take(300))
            .put("host", host)
            .put("page", page.take(200))
            .put("kind", if (isBlocked) "blocked" else kind.ifBlank { "other" })
            .put("rule", rule)
        synchronized(lock) {
            ring.addFirst(e)
            while (ring.size > 300) ring.removeLast()
        }
    }

    /** 老签名：没有来源信息时（例如分片下载的中间进度）也记一条事件，时间取当前时刻起算 */
    /** 自代理累计字节（只增不减，重启清零） */
    @Volatile
    private var proxyDown: Long = 0
    @Volatile
    private var proxyUp: Long = 0

    fun addBytes(down: Long, up: Long) {
        addBytes(down, up, "", "", System.currentTimeMillis(), System.currentTimeMillis())
    }

    /**
     * 记一次字节搬运。`host` 用来画"按站点饼图"，`page` 用来回答"哪个网页用的流量"。
     * `fromMs/toMs` 给分桶用（代理中继一次连接可能持续几秒~几分钟）。
     */
    /** 来自**自代理**（本地中继）的字节：用户第 16 条要求统计里能单独看到这一项 */
    fun addProxyBytes(down: Long, up: Long, host: String, fromMs: Long, toMs: Long) {
        synchronized(lock) {
            proxyDown += down
            proxyUp += up
        }
        addBytes(down, up, host, "", fromMs, toMs)
    }

    fun addBytes(down: Long, up: Long, host: String, page: String, fromMs: Long, toMs: Long) {
        if (down <= 0 && up <= 0) return
        synchronized(lock) {
            bytesDown += down
            bytesUp += up
            // 前台/后台分开记（用户要求：柱状图下面前台、上面后台）
            val to2 = if (toMs > fromMs) toMs else fromMs + 1
            val sp = Fg.split(fromMs, to2)
            val span = (to2 - fromMs).coerceAtLeast(1L)
            val dFg = Math.round(down.toDouble() * sp[0] / span)
            val uFg = Math.round(up.toDouble() * sp[0] / span)
            bytesDownFg += dFg
            bytesUpFg += uFg
            byteEvents.addLast(
                JSONObject().put("from", fromMs).put("to", to2)
                    .put("down", down).put("up", up)
                    .put("downFg", dFg).put("downBg", down - dFg)
                    .put("upFg", uFg).put("upBg", up - uFg)
                    .put("host", host.ifBlank { pageHost(page) })
                    .put("page", page.take(200))
            )
            while (byteEvents.size > 2000) byteEvents.removeFirst()
        }
    }

    private fun midnight(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun pageHost(u: String): String = try {
        android.net.Uri.parse(u).host ?: "其它"
    } catch (_: Exception) {
        "其它"
    }

    fun list(filter: String = "", kind: String = "", limit: Int = 100): JSONArray {
        val out = JSONArray()
        val f = filter.trim().lowercase()
        synchronized(lock) {
            for (e in ring) {
                if (out.length() >= limit) break
                if (kind.isNotBlank() && kind != "all" && e.optString("kind") != kind) continue
                if (f.isNotEmpty() &&
                    !e.optString("url").lowercase().contains(f) &&
                    !e.optString("host").lowercase().contains(f)
                ) continue
                out.put(e)
            }
        }
        return out
    }

    /** 时间线里用到的各种类型（给界面做筛选下拉） */
    fun kinds(): JSONArray {
        val set = linkedSetOf("all", "other", "blocked", "m3u8", "mp4", "webm", "mp3", "m4a", "ts", "mpd")
        synchronized(lock) {
            for (e in ring) set.add(e.optString("kind"))
        }
        return JSONArray(set.toList())
    }

    /**
     * 统计。`range` = hour / day / month / year（柱状图的时间粒度）。
     * 分桶与汇总都走 `Stats`（与阅读时长同一个统计器）：
     *   bars   = 每个桶的下行/上行字节（柱状图；value=下, value2=上）
     *   slices = 按站点汇总（饼图）
     */
    fun stats(range: String = "hour"): JSONObject {
        val events = synchronized(lock) { byteEvents.toList() }
        // 自代理字节（用户第 16 条：统计里要能单独看到"来自自代理的字节"）
        val pxDown = proxyDown
        val pxUp = proxyUp
        val byHost = LinkedHashMap<String, Long>()
        events.forEach { e ->
            val h = e.optString("host").ifBlank { "其它" }
            byHost[h] = (byHost[h] ?: 0L) + e.optLong("down") + e.optLong("up")
        }
        val b = Stats.buckets(range, events, valueKey = "down", secondKey = "up", nameKey = "host")
        return JSONObject()
            .put("enabled", enabled)
            .put("range", range)
            .put("total", total)
            .put("blocked", blocked)
            .put("media", media)
            .put("bytesDown", bytesDown)
            .put("bytesUp", bytesUp)
            .put("bytesDownFg", bytesDownFg).put("bytesDownBg", bytesDown - bytesDownFg)
            .put("bytesUpFg", bytesUpFg).put("bytesUpBg", bytesUp - bytesUpFg)
            // 第 16 条：单独给出"来自自代理的字节"（本地中继转发的那部分）
            .put("proxyDown", pxDown).put("proxyUp", pxUp)
            .put("bars", b.optJSONArray("bars"))
            .put("barTotal", b.optLong("total"))
            .put("barTotal2", b.optLong("total2"))
            .put("slices", Stats.slices(byHost))
            // 今天的账（实时那档要"现在什么情况"）
            .put("todayDown", events.filter { it.optLong("to") >= midnight() }.sumOf { it.optLong("down") })
            .put("todayUp", events.filter { it.optLong("to") >= midnight() }.sumOf { it.optLong("up") })
            .put("now", System.currentTimeMillis())
            .put("events", events.size)
            .put("note", "上/下行只统计**应用自己搬运**的字节：应用内下载、m3u8 分片、以及本地中继转发的流量" +
                "（所以开着代理时这份数据才接近「整机浏览流量」）；页面内的流量 WebView 不暴露给应用，拿不到就不编。")
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            byteEvents.clear()
            total = 0; blocked = 0; media = 0; bytesDown = 0; bytesUp = 0
            bytesDownFg = 0; bytesUpFg = 0
        }
    }

    fun persist() {
        try {
            file.writeText(JSONObject().put("list", list(limit = 200)).toString())
        } catch (_: Exception) {
        }
    }
}
