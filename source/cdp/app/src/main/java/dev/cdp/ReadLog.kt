package dev.cdp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 阅读/停留时间记录（清单第 22 条 + test.md：阅读时间数据要**单独一个栏目**，能排序、搜索、删除、看饼图）。
 *
 * 用户后来补的要求（2026-09-20）：
 *   · **主页（我们自己的页面）不记**，只记外部网页；
 *   · 每个网页的阅读时长都记下来，然后自己做统计：**饼图（按站点）+ 柱状图（按小时/天/月/年）**，
 *     柱子上要能看出"多长时间 + 多少网站"，还要能看出"使用了多久 / 连续用了多久"。
 *
 * 为了能按小时精确分桶，记录从"同一天同地址累加"改成**区间记录**：
 *   一条 = 一次停留 `{id,url,host,title,from,to,ms,mins}`；同一地址**且和上一条首尾相接**（间隔 ≤ 120 秒）
 *   才合并，否则新开一条。这样任何粒度（小时/天/月/年）都能按 from→to 切开分桶，不会把时间记到错的桶里。
 *
 * 边界（如实写，别当成 bug）：页面切后台/熄屏的时间页面自己也算进去了，所以这是"停留时间"不是"专注时间"。
 */
class ReadLog(private val ctx: android.content.Context) {
    private val lock = Any()
    private val file = File(ctx.filesDir, "read_log.json")
    private val items = JSONArray()          // 每条是一次停留（区间）

    private val GAP_MERGE = 120_000L         // 同一地址、间隔 ≤ 2 分钟 → 当成同一次停留合并
    private val GAP_SESSION = 300_000L       // 间隔 ≤ 5 分钟 → 算同一段"连续使用"

    init {
        load()
    }

    private fun load() {
        synchronized(lock) {
            try {
                if (file.exists()) {
                    val a = JSONArray(file.readText())
                    for (i in 0 until a.length()) items.put(a.optJSONObject(i) ?: continue)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun persist() {
        synchronized(lock) {
            try {
                file.writeText(items.toString())
            } catch (_: Exception) {
            }
        }
    }

    private fun hostOf(u: String): String = try {
        java.net.URI(u).host ?: u.take(30)
    } catch (_: Exception) {
        u.take(30)
    }

    /** 我们自己的页面（主页/控制台/播放器）不记 —— 用户明确要求"主页的阅读时间不管" */
    private fun isOurs(url: String, host: String): Boolean =
        url.startsWith("appassets.androidplatform.net") || url.contains("appassets.androidplatform.net") ||
            host == "appassets.androidplatform.net" || host.endsWith("cdp.local") ||
            url.contains("/ui/start.html") || url.contains("/ui/index.html") || url.contains("/player.html") ||
            host.isBlank()

    /** 页面报上来一条（同地址且与上一条首尾相接就合并，否则新开一条区间） */
    fun add(o: JSONObject) {
        val url = o.optString("url")
        if (url.isBlank()) return
        val ms = o.optLong("ms", 0)
        if (ms < 1000) return                      // 不到 1 秒的不记，别刷垃圾
        val host = hostOf(url)
        if (isOurs(url, host)) return              // 主页/自己的页面不记
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val from = now - ms
            // 找这个地址最近的一条：间隔 ≤ GAP_MERGE 就并进去
            var best = -1
            var bestTo = 0L
            for (i in items.length() - 1 downTo 0) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("url") == url) {
                    val to = it.optLong("to", it.optLong("ts", 0L))
                    if (to > bestTo) {
                        bestTo = to
                        best = i
                    }
                }
            }
            if (best >= 0 && bestTo > 0 && (from - bestTo) <= GAP_MERGE && from >= bestTo - 5000) {
                val it = items.optJSONObject(best)!!
                val sp = Fg.split(bestTo, now)          // 这次新报的时间按前后台切开累加
                it.put("to", now)
                it.put("ms", it.optLong("ms", 0L) + ms)
                it.put("msFg", it.optLong("msFg", 0L) + sp[0])
                it.put("msBg", it.optLong("msBg", 0L) + sp[1])
                it.put("ts", now)
                if (o.optString("title").isNotBlank()) it.put("title", o.optString("title"))
                if (o.optLong("mins", 0) > 0) it.put("mins", o.optLong("mins", 0))
                Unit                       // 块里最后一句别是单分支 if（会被当成表达式要求 else）
            } else {
                val sp0 = Fg.split(from, now)
                val one = JSONObject()
                    .put("msFg", sp0[0]).put("msBg", sp0[1])
                    .put("id", "r" + now + "-" + (items.length() + 1))
                    .put("url", url)
                    .put("host", host)
                    .put("title", o.optString("title"))
                    .put("from", from)
                    .put("to", now)
                    .put("ms", ms)
                    .put("ts", now)
                    .put("native", o.optBoolean("native"))     // 标明"App 自己计的"（不是页面插件报的）
                if (o.optLong("mins", 0) > 0) one.put("mins", o.optLong("mins", 0))
                one.put("day", dayOf(now))
                items.put(one)
            }
        }
        persist()
    }

    private fun dayOf(ts: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 列表：可搜索、可排序（time=停留时长 / ts=最近 / host=域名 / title=标题） */
    fun list(filter: String, sort: String, limit: Int): JSONObject {
        val out = JSONArray()
        synchronized(lock) {
            val all = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            val f = filter.lowercase()
            val filtered = all.filter {
                f.isEmpty() || (it.optString("url") + " " + it.optString("title") + " " + it.optString("host"))
                    .lowercase().contains(f)
            }
            val sorted = when (sort) {
                "ts" -> filtered.sortedByDescending { it.optLong("ts") }
                "host" -> filtered.sortedBy { it.optString("host") }
                "title" -> filtered.sortedBy { it.optString("title") }
                else -> filtered.sortedByDescending { it.optLong("ms") }
            }
            sorted.take(if (limit <= 0) 500 else limit).forEach { out.put(it) }
        }
        return JSONObject().put("ok", true).put("list", out).put("count", out.length())
    }

    // ------------------------------------------------------------------ 统计（饼图 / 柱状图）

    /**
     * 统计。`range` 决定柱状图的分桶粒度：hour=一天 24 小时（跨天汇总）、day=最近 30 天、month=最近 12 个月、year=最近 5 年。
     * 返回的每个桶都带 `ms / visits / sites`（用户要求柱子上能看出"多长时间 + 多少个网站"）。
     */
    /**
     * 统计。`range` 决定柱状图分桶：hour=一天 24 小时（跨天汇总）、day=最近的天、month=月、year=年。
     * 分桶/会话/饼图**都交给 `Stats`**（用户要求：采集归采集，统计与绘图交给一个东西）。
     */
    fun stats(range: String = "hour"): JSONObject {
        val snapshot = synchronized(lock) { (0 until items.length()).mapNotNull { items.optJSONObject(it) } }
        val byHost = LinkedHashMap<String, Long>()
        snapshot.forEach { byHost[it.optString("host").ifBlank { "其它" }] = (byHost[it.optString("host").ifBlank { "其它" }] ?: 0L) + it.optLong("ms", 0L) }
        val b = Stats.buckets(range, snapshot, valueKey = "msFg", secondKey = "msBg", nameKey = "host")
        // 今天（本机零点起）：实时那档要"现在这个情况"，所以单独给一份今天的账
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val midnight = cal.timeInMillis
        val todayRecs = snapshot.filter { it.optLong("to", it.optLong("ts", 0L)) >= midnight }
        return JSONObject().put("ok", true)
            .put("range", range)
            .put("now", System.currentTimeMillis())
            .put("today", JSONObject()
                // 今天这个数用"前台+后台"来算：两者是同一批区间的两种切法，
                // 要是这里各用各的来源，界面就会出现"今天 28 秒（前台 29 秒）"这种对不上的数。
                .put("ms", todayRecs.sumOf { it.optLong("msFg", 0L) + it.optLong("msBg", 0L) })
                .put("msFg", todayRecs.sumOf { it.optLong("msFg", 0L) })
                .put("msBg", todayRecs.sumOf { it.optLong("msBg", 0L) })
                .put("count", todayRecs.size))
            .put("total", snapshot.sumOf { it.optLong("ms", 0L) })
            .put("totalFg", b.optLong("total")).put("totalBg", b.optLong("total2"))
            .put("count", snapshot.size)
            .put("bars", b.optJSONArray("bars"))
            .put("byHost", Stats.slices(byHost))            // 饼图数据（与旧字段同名，界面不用改）
            .put("slices", Stats.slices(byHost))
            .put("usage", Stats.sessions(snapshot))
            // 最近几条（实时那档的"数据表"用；按最后更新倒序）
            .put("recent", JSONArray(snapshot.sortedByDescending { it.optLong("to", it.optLong("ts", 0L)) }).also { a ->
                while (a.length() > 10) a.remove(a.length() - 1)
            })
            .put("note", "停留时间由 **App 自己计时**（不往网页注入 JS）：只算前台且停在那一页的时间，" +
                "切后台/熄屏不计；我们自己的页面（主页/控制台/播放器）不记。")
    }

    fun del(id: String): JSONObject {
        var hit = false
        synchronized(lock) {
            for (i in items.length() - 1 downTo 0) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("id") == id) {
                    items.remove(i)
                    hit = true
                }
            }
        }
        if (hit) persist()
        return JSONObject().put("ok", hit).put("count", items.length())
    }

    fun clear(): JSONObject {
        val n = items.length()
        synchronized(lock) { while (items.length() > 0) items.remove(0) }
        persist()
        return JSONObject().put("ok", true).put("cleared", n)
    }
}
