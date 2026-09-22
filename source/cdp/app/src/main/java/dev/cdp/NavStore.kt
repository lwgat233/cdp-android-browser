package dev.cdp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 导航历史 + 书签（落盘在 App 私有目录 files/nav.json）。
 *
 * 规则（都是为了「列表里别出现垃圾」）：
 *  - 历史跳过 about:blank / data: / 空 URL / 我们自己的虚拟源（cdp-event.local、cdp-meta.local）；
 *  - 历史按 URL 去重，同一页刷新只留最新一条，最多留 300 条（新→旧）；
 *  - 书签按 URL 去重 —— 已收藏的再收藏一次就是「已经收藏过了」，不制造第二条。
 */
class NavStore(private val ctx: Context) {
    private val ctxRef = ctx
    private var file: File = Spaces.file(ctx, "nav.json")
    private val lock = Any()
    private var history = JSONArray()
    private var bookmarks = JSONArray()

    init {
        load()
        // 配置隔离：切空间就换文件重新加载（清单第 12 条）
        Spaces.onSwitch {
            file = Spaces.file(ctxRef, "nav.json")
            history = JSONArray()
            bookmarks = JSONArray()
            load()
        }
    }

    private fun load() {
        synchronized(lock) {
            try {
                if (file.exists()) {
                    val o = JSONObject(file.readText())
                    history = o.optJSONArray("history") ?: JSONArray()
                    bookmarks = o.optJSONArray("bookmarks") ?: JSONArray()
                }
            } catch (_: Exception) {
                history = JSONArray()
                bookmarks = JSONArray()
            }
        }
    }

    private fun persist() {
        try {
            file.writeText(
                JSONObject().put("history", history).put("bookmarks", bookmarks).toString()
            )
        } catch (_: Exception) {
        }
    }

    private fun junk(url: String): Boolean {
        if (url.isBlank()) return true
        if (url.startsWith("about:")) return true
        if (url.startsWith("data:")) return true
        if (url.contains("cdp-event.local")) return true
        if (url.contains("cdp-meta.local")) return true
        return false
    }

    /** 记一条历史；返回是否是新条目（同 URL 只置顶，不算新增） */
    fun addHistory(url: String, title: String): Boolean {
        if (junk(url)) return false
        synchronized(lock) {
            val arr = JSONArray()
            arr.put(
                JSONObject().put("url", url)
                    .put("title", title.ifBlank { url })
                    .put("ts", System.currentTimeMillis())
            )
            var fresh = true
            for (i in 0 until history.length()) {
                val it = history.optJSONObject(i) ?: continue
                if (it.optString("url") == url) {
                    fresh = false
                    continue
                }
                if (arr.length() >= MAX_HISTORY) break
                arr.put(it)
            }
            history = arr
            persist()
            return fresh
        }
    }

    fun historyJson(): JSONArray = synchronized(lock) { JSONArray(history.toString()) }

    /** 分页取历史（控制台"无限下拉"用） */
    fun historyPage(offset: Int, limit: Int): JSONArray {
        val out = JSONArray()
        synchronized(lock) {
            var i = offset.coerceAtLeast(0)
            var n = 0
            while (i < history.length() && n < limit.coerceIn(1, 500)) {
                history.optJSONObject(i)?.let { out.put(it) }
                i++; n++
            }
        }
        return out
    }

    /** 按域名删历史；domain 支持子串匹配（如 chaoxing.com） */
    fun deleteHistoryByDomain(domain: String): Int = synchronized(lock) {
        val d = domain.trim().lowercase()
        if (d.isEmpty()) return 0
        val arr = JSONArray()
        var removed = 0
        for (i in 0 until history.length()) {
            val it = history.optJSONObject(i) ?: continue
            val host = try { android.net.Uri.parse(it.optString("url")).host ?: "" } catch (_: Exception) { "" }
            if (host.lowercase().contains(d)) removed++ else arr.put(it)
        }
        history = arr
        if (removed > 0) persist()
        removed
    }

    /** 按时间删历史：before=true 删早于 ts 的，false 删晚于 ts 的 */
    fun deleteHistoryByTime(ts: Long, before: Boolean): Int = synchronized(lock) {
        val arr = JSONArray()
        var removed = 0
        for (i in 0 until history.length()) {
            val it = history.optJSONObject(i) ?: continue
            val t = it.optLong("ts", 0L)
            val hit = if (before) t in 1 until ts else t > ts
            if (hit) removed++ else arr.put(it)
        }
        history = arr
        if (removed > 0) persist()
        removed
    }

    /** 导出历史成文本（时间 / 标题 / 地址） */
    fun exportHistoryText(): String = synchronized(lock) {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val sb = StringBuilder()
        sb.append("# CDP 浏览历史导出（共 ").append(history.length()).append(" 条，最近在前）\n")
        for (i in 0 until history.length()) {
            val it = history.optJSONObject(i) ?: continue
            sb.append(f.format(java.util.Date(it.optLong("ts", 0L))))
                .append('\t').append(it.optString("title"))
                .append('\t').append(it.optString("url")).append('\n')
        }
        sb.toString()
    }

    /** 导出 JSON 版 */
    fun exportHistoryJson(): String = synchronized(lock) { history.toString() }

    /** 导出书签成文本（文件夹 / 标题 / 地址） */
    fun exportBookmarksText(): String = synchronized(lock) {
        val sb = StringBuilder()
        sb.append("# CDP 书签导出（共 ").append(bookmarks.length()).append(" 条）\n")
        for (i in 0 until bookmarks.length()) {
            val it = bookmarks.optJSONObject(i) ?: continue
            val folder = it.optString("folder")
            sb.append(if (folder.isBlank()) "（根）" else folder).append('\t')
                .append(it.optString("title")).append('\t')
                .append(it.optString("url")).append('\n')
        }
        sb.toString()
    }

    /** 导入历史：按地址去重（已有的不重复加），返回真正加进去的条数 */
    fun importHistory(arr: JSONArray): Int = synchronized(lock) {
        var n = 0
        val known = HashSet<String>()
        for (i in 0 until history.length()) history.optJSONObject(i)?.let { known.add(it.optString("url")) }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isBlank() || known.contains(u)) continue
            history.put(
                JSONObject().put("url", u).put("title", o.optString("title"))
                    .put("ts", o.optLong("ts", System.currentTimeMillis()))
            )
            known.add(u)
            n++
        }
        while (history.length() > MAX_HISTORY) history.remove(history.length() - 1)
        if (n > 0) persist()
        n
    }

    /** 导入书签：按地址去重 */
    fun importBookmarks(arr: JSONArray): Int = synchronized(lock) {
        var n = 0
        val known = HashSet<String>()
        for (i in 0 until bookmarks.length()) bookmarks.optJSONObject(i)?.let { known.add(it.optString("url")) }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isBlank() || known.contains(u)) continue
            bookmarks.put(
                JSONObject().put("id", "b" + System.currentTimeMillis() + "_" + i)
                    .put("url", u).put("title", o.optString("title"))
                    .put("folder", o.optString("folder"))
                    .put("ts", System.currentTimeMillis())
            )
            known.add(u)
            n++
        }
        if (n > 0) persist()
        n
    }

    fun clearHistory(): Int = synchronized(lock) {
        val n = history.length()
        history = JSONArray()
        persist()
        n
    }

    fun bookmarksJson(): JSONArray = synchronized(lock) { JSONArray(bookmarks.toString()) }

    /**
     * 书签文件夹：书签上挂一个 folder 字段（形如 "学习通/期末"，用 / 分层）。
     * 界面按层画成树 —— 不建单独的文件夹表，路径写全就行，简单且不会出现"空文件夹"的残渣。
     */
    fun foldersJson(): JSONArray {
        val set = LinkedHashSet<String>()
        synchronized(lock) {
            for (i in 0 until bookmarks.length()) {
                val f = bookmarks.optJSONObject(i)?.optString("folder") ?: ""
                if (f.isBlank()) continue
                val parts = f.split('/').filter { it.isNotBlank() }
                var acc = ""
                for (p in parts) {
                    acc = if (acc.isEmpty()) p else "$acc/$p"
                    set.add(acc)
                }
            }
        }
        val arr = JSONArray()
        set.sorted().forEach { arr.put(it) }
        return arr
    }

    /** 把书签移到某个文件夹（folder 传空串 = 移到根） */
    fun setBookmarkFolder(id: String, folder: String): Boolean = synchronized(lock) {
        var hit = false
        for (i in 0 until bookmarks.length()) {
            val it = bookmarks.optJSONObject(i) ?: continue
            if (it.optString("id") == id) {
                it.put("folder", folder.trim().trim('/'))
                hit = true
            }
        }
        if (hit) persist()
        hit
    }

    /** 重命名文件夹（连同它的子路径一起改） */
    fun renameFolder(from: String, to: String): Int = synchronized(lock) {
        val f = from.trim().trim('/')
        val t = to.trim().trim('/')
        var n = 0
        for (i in 0 until bookmarks.length()) {
            val it = bookmarks.optJSONObject(i) ?: continue
            val cur = it.optString("folder")
            if (cur == f || cur.startsWith("$f/")) {
                it.put("folder", if (t.isEmpty()) cur.removePrefix(f).trimStart('/') else t + cur.removePrefix(f))
                n++
            }
        }
        if (n > 0) persist()
        n
    }

    /** 删除文件夹（里面的书签移到根，不连带删书签） */
    fun deleteFolder(from: String): Int = renameFolder(from, "")

    /** 加书签（同 URL 已存在则返回那一条，不重复加） */
    fun addBookmark(url: String, title: String, folder: String = ""): JSONObject = synchronized(lock) {
        for (i in 0 until bookmarks.length()) {
            val it = bookmarks.optJSONObject(i) ?: continue
            if (it.optString("url") == url) return@synchronized it
        }
        val b = JSONObject()
            .put("id", "b" + System.currentTimeMillis())
            .put("url", url)
            .put("title", title.ifBlank { url })
            .put("folder", folder.trim().trim('/'))
            .put("ts", System.currentTimeMillis())
        bookmarks.put(b)
        persist()
        b
    }

    /** 改书签属性（标题 / 地址 / 文件夹）——用户要求：书签的属性应该能编辑 */
    fun updateBookmark(id: String, title: String?, url: String?, folder: String?): JSONObject? = synchronized(lock) {
        for (i in 0 until bookmarks.length()) {
            val it = bookmarks.optJSONObject(i) ?: continue
            if (it.optString("id") == id) {
                if (title != null) it.put("title", title)
                if (url != null && url.isNotBlank()) it.put("url", url.trim())
                if (folder != null) it.put("folder", folder.trim().trim('/'))
                it.put("editedAt", System.currentTimeMillis())
                persist()
                return@synchronized it
            }
        }
        null
    }

    /** 改历史一条的属性（标题；按 ts + url 定位，因为历史没有 id）——用户要求：历史的属性也能编辑 */
    fun updateHistory(ts: Long, url: String, title: String): Boolean = synchronized(lock) {
        for (i in 0 until history.length()) {
            val it = history.optJSONObject(i) ?: continue
            if (it.optLong("ts") == ts && it.optString("url") == url) {
                it.put("title", title)
                it.put("editedAt", System.currentTimeMillis())
                persist()
                return@synchronized true
            }
        }
        false
    }

    fun removeBookmark(id: String): Boolean = synchronized(lock) {
        var hit = false
        val arr = JSONArray()
        for (i in 0 until bookmarks.length()) {
            val it = bookmarks.optJSONObject(i) ?: continue
            if (it.optString("id") == id) {
                hit = true
                continue
            }
            arr.put(it)
        }
        bookmarks = arr
        if (hit) persist()
        hit
    }

    /** 按 URL 找书签（没有就返回 null） */
    fun findBookmarkByUrl(url: String): JSONObject? = synchronized(lock) {
        for (i in 0 until bookmarks.length()) {
            val it = bookmarks.optJSONObject(i) ?: continue
            if (it.optString("url") == url) return@synchronized it
        }
        null
    }

    companion object {
        private const val MAX_HISTORY = 5000
    }
}
