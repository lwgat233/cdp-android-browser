package dev.cdp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 首页小 app 网格（用户要求：像手机桌面一样的网格，**加号添加**，**图标 + 名字**）。
 *
 * 数据只有一处权威源：本机设置里的 `appsJson`（重启还在）。
 *   · 加/改/删/排序都在这里做，界面只管画；
 *   · 图标：优先用站点 favicon，取不到就用"首字母 + 按域名算的底色"（离线也好看）；
 *   · url 里可以是普通网址，也可以是 cdpctl://（控制台/历史/嗅探/省电这些自带页）。
 */
object AppsStore {
    private const val KEY = "appsJson"

    private fun newId(): String = "a" + System.nanoTime().toString(36)

    private fun app(name: String, url: String): JSONObject =
        JSONObject().put("id", newId()).put("name", name).put("url", url)

    /** 第一次打开时给几条默认入口（就是原来首页那四张卡片） */
    private fun seed(): JSONArray = JSONArray().apply {
        put(app("控制台", "cdpctl://console?tab=page"))
        put(app("历史", "cdpctl://console?tab=history"))
        put(app("资源嗅探", "cdpctl://console?tab=sniff"))
        put(app("省电", "cdpctl://console?tab=power"))
    }

    @Synchronized
    fun list(s: SettingsStore): JSONArray {
        val raw = s.get(KEY, "")
        if (raw.isBlank()) {
            val d = seed()
            s.set(KEY, d.toString())
            return d
        }
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    /** 有 id 就改，没有就加 */
    @Synchronized
    fun save(s: SettingsStore, o: JSONObject): JSONArray {
        val arr = list(s)
        val id = o.optString("id")
        val name = o.optString("name").ifBlank { "未命名" }
        val url = o.optString("url")
        if (id.isBlank()) {
            arr.put(JSONObject().put("id", newId()).put("name", name).put("url", url))
        } else {
            var hit = false
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                if (it.optString("id") == id) {
                    it.put("name", name).put("url", url)
                    hit = true
                    break
                }
            }
            if (!hit) arr.put(JSONObject().put("id", newId()).put("name", name).put("url", url))
        }
        s.set(KEY, arr.toString())
        return arr
    }

    @Synchronized
    fun remove(s: SettingsStore, id: String): JSONArray {
        val arr = list(s)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") != id) out.put(it)
        }
        s.set(KEY, out.toString())
        return out
    }

    /** 排序：dir = -1 往前、1 往后（拖动不做，先用这个把常用的挪到前面） */
    @Synchronized
    fun move(s: SettingsStore, id: String, dir: Int): JSONArray {
        val arr = list(s)
        val idx = (0 until arr.length()).firstOrNull { arr.optJSONObject(it)?.optString("id") == id } ?: return arr
        val to = idx + if (dir < 0) -1 else 1
        if (to < 0 || to >= arr.length()) return arr
        val list = (0 until arr.length()).map { arr.optJSONObject(it) ?: JSONObject() }.toMutableList()
        val tmp = list[idx]; list[idx] = list[to]; list[to] = tmp
        val out = JSONArray()
        list.forEach { out.put(it) }
        s.set(KEY, out.toString())
        return out
    }
}
