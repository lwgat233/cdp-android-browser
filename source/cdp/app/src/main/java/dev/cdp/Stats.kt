package dev.cdp

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Stats —— **统计与分桶只在这一处**（用户的要求："两个功能实质上就是收集数据，然后交由一个东西进行统计、绘画"）。
 *
 * 采集方（阅读时长 / 网络流量）只负责把"带时间的一小段数据"存下来，然后都调这里：
 *   · `buckets()`  —— 按 **小时 / 天 / 月 / 年** 分桶（区间跨桶会精确切开，时间不会记到错的桶里）
 *   · `sessions()` —— 相邻区间间隔 ≤ gap 的合成"一段连续使用"，给出段数 / 最长一段 / 当前这一段
 *   · `slices()`   —— 按名字汇总成饼图数据（带百分比）
 *
 * 输出形状统一，前端一个 `chart.js` 就能画（柱状图 + 饼图），不必两边各写一套：
 *   { range, total, bars:[{key,label,value,value2,visits,sites}], slices:[{name,value,pct}], usage:{…}, note }
 */
object Stats {

    const val GAP_SESSION = 300_000L      // 间隔 ≤ 5 分钟 → 同一段"连续使用"
    private const val STEP = 60_000L      // 分桶时一分钟一档地走（区间跨桶就切开）

    /** 一个桶的标签：按 range 取 小时(0-23) / 天 / 月 / 年 */
    fun label(range: String, ts: Long): Pair<String, Long> {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return when (range) {
            "day" -> {
                val k = String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
                k to ts
            }
            "month" -> {
                val k = String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
                k to ts
            }
            "year" -> c.get(Calendar.YEAR).toString() to ts
            else -> {
                val h = c.get(Calendar.HOUR_OF_DAY)
                String.format(Locale.US, "%02d", h) to h.toLong()
            }
        }
    }

    /** 把同一小时/天/月/年排好序（hour 固定 24 档，其它按出现顺序排时间） */
    fun orderOf(range: String, key: String, ts: Long): Long =
        if (range == "hour") key.toLongOrNull() ?: 0L else ts

    /**
     * 按区间分桶。`records` 每条要有 from/to/value（value = 毫秒数或字节数）；
     * `secondOf` 用来取第二条数值（流量用：下行/上行），没有就给 0。
     */
    fun buckets(
        range: String,
        records: List<JSONObject>,
        fromKey: String = "from",
        toKey: String = "to",
        valueKey: String = "ms",
        secondKey: String? = null,
        nameKey: String = "host"
    ): JSONObject {
        data class Bucket(val key: String, val label: String, val order: Long)

        val acc = LinkedHashMap<String, LongArray>()        // key → [value, value2, visits]
        val meta = LinkedHashMap<String, Bucket>()
        val names = HashMap<String, MutableSet<String>>()

        records.forEach { rec ->
            val from = rec.optLong(fromKey, rec.optLong("ts", 0L))
            val to = rec.optLong(toKey, rec.optLong("ts", 0L))
            if (to <= 0 || to <= from) return@forEach
            val v = rec.optLong(valueKey, 0L)
            val v2 = if (secondKey != null) rec.optLong(secondKey, 0L) else 0L
            val name = rec.optString(nameKey).ifBlank { "其它" }
            val span = (to - from).coerceAtLeast(1L)
            var t = from
            var first = true
            while (t < to) {
                val segEnd = minOf(to, t + STEP)
                val (key, _) = label(range, t)
                meta.putIfAbsent(key, Bucket(key, key, orderOf(range, key, t)))
                val arr = acc.getOrPut(key) { LongArray(3) }
                // value（第一条数值）按时间占比分摊；第二条（如上行）同样分摊
                val share = (segEnd - t).toDouble() / span
                arr[0] = arr[0] + Math.round(v * share)
                arr[1] = arr[1] + Math.round(v2 * share)
                if (first) {
                    arr[2] = arr[2] + 1
                    first = false
                }
                names.getOrPut(key) { mutableSetOf() }.add(name)
                t = segEnd
            }
        }

        val bars = JSONArray()
        val keys = if (range == "hour") (0..23).map { String.format(Locale.US, "%02d", it) } else meta.keys.toList()
        keys.sortedBy { k -> meta[k]?.order ?: 0L }.forEach { k ->
            val arr = acc[k] ?: LongArray(3)
            bars.put(
                JSONObject().put("key", k).put("label", k)
                    .put("value", arr[0]).put("value2", arr[1])
                    .put("visits", arr[2]).put("sites", (names[k]?.size) ?: 0)
            )
        }
        return JSONObject().put("bars", bars)
            .put("total", records.sumOf { it.optLong(valueKey, 0L) })
            .put("total2", if (secondKey != null) records.sumOf { it.optLong(secondKey, 0L) } else 0L)
    }

    /** 饼图数据：按名字汇总 + 百分比 */
    fun slices(map: Map<String, Long>, unitTop: Int = 12): JSONArray {
        val total = map.values.sum()
        val out = JSONArray()
        map.entries.sortedByDescending { it.value }.take(unitTop).forEach { (name, v) ->
            out.put(
                JSONObject().put("name", name).put("value", v)
                    .put("pct", if (total > 0) Math.round(v * 1000.0 / total) / 10.0 else 0.0)
            )
        }
        return out
    }

    /** 连续使用：把区间按 gap 合成"段"，给段数 / 最长一段 / 当前这一段有多久 */
    fun sessions(records: List<JSONObject>): JSONObject {
        val sorted = records.filter { it.optLong("to", 0) > 0 }
            .sortedBy { it.optLong("from", it.optLong("ts", 0L)) }
        var curFrom = 0L
        var curTo = 0L
        var longest = 0L
        var count = 0
        var totalSpan = 0L
        sorted.forEach { rec ->
            val f = rec.optLong("from", rec.optLong("ts", 0L))
            val t = rec.optLong("to", rec.optLong("ts", 0L))
            if (curFrom == 0L) {
                curFrom = f; curTo = t
            } else if (f - curTo <= GAP_SESSION) {
                if (t > curTo) curTo = t
            } else {
                longest = maxOf(longest, curTo - curFrom)
                totalSpan += curTo - curFrom
                count++
                curFrom = f; curTo = t
            }
        }
        if (curFrom > 0) {
            longest = maxOf(longest, curTo - curFrom)
            totalSpan += curTo - curFrom
            count++
        }
        val now = System.currentTimeMillis()
        val continuous = if (count > 0 && now - curTo <= GAP_SESSION) (now - curFrom) else 0L
        return JSONObject().put("sessions", count).put("longestMs", longest)
            .put("spanMs", totalSpan).put("continuousMs", continuous)
            .put("lastAt", if (sorted.isEmpty()) 0L else sorted.last().optLong("to", 0L))
    }
}
