package dev.cdp

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * PageTimer —— **在 App 自己这边**给每个页面计时（不再往网页里注入 JS 来计时）。
 *
 * 用户的要求（2026-09-20）："不要嵌入 js 到页面……对于什么阅读时长和 ai，把它放在（我们自己的界面）里。"
 * 以前阅读时长靠一个注入到每个页面的用户脚本（在自己页面里跑 setInterval 计时再回传），
 * 那条路有三个毛病：① 要求注入成功（CSP/无 body 的页就废）；② 改动页面（多一个徽标）；
 * ③ 用户删掉那个脚本或停用它，统计就静默变空。
 *
 * 现在的做法：**只靠 App 自己知道的事**——
 *   · 当前页面地址/标题（WebView 的页面回调里给进来）；
 *   · App 是不是在前台（`Fg`，Activity 生命周期）；
 *   · 自己的页面（主页/控制台/播放器）不算（`ReadLog` 里已有同一个判断，这里先挡一道）。
 * 每 5 秒醒一次把"前台且在这一页"的时间累起来，每 15 秒**增量上报**给 ReadLog
 * （ReadLog 会把同一地址、间隔 ≤2 分钟的段落合并成一条停留 → 统计里时长会实时往上涨）。
 *
 * 边界（如实写）：这就是"停留时间"——页面在后台/熄屏时不计；App 被系统杀掉时，
 * 最后不足一个周期的零头会丢（最多 15 秒），宁可少算，不编数据。
 */
class PageTimer(
    private val readlog: ReadLog,
    private val log: (String) -> Unit
) {
    @Volatile private var url: String = ""
    @Volatile private var title: String = ""
    private val pending = AtomicLong(0L)          // 还没报上去的毫秒
    private var lastTick = 0L
    private var lastFlush = 0L
    private var running = false
    private var thread: Thread? = null

    private val TICK = 5_000L                     // 醒来看看
    private val FLUSH = 15_000L                   // 报一次（和以前的插件一样的节奏，界面"实时"就靠它）

    /** 换页：先把上一页攒的时间结算掉，再开始给新页面计时 */
    fun onPage(u: String, t: String) {
        if (u.isBlank()) return
        if (u != url) {
            flush(true)
            url = u
            title = t
            lastTick = System.currentTimeMillis()
        } else if (t.isNotBlank() && t != title) {
            title = t
        }
    }

    fun start() {
        if (running) return
        running = true
        lastTick = System.currentTimeMillis()
        lastFlush = lastTick
        thread = Thread {
            while (running) {
                try {
                    Thread.sleep(TICK)
                    tick()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    try { log("计时出错：" + (e.message ?: "?")) } catch (_: Exception) {}
                }
            }
        }.apply { isDaemon = true; name = "cdp-pagetimer"; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dt = (now - lastTick).coerceIn(0L, 30_000L)     // 被挂起过就别把这段时间一次算进去
        lastTick = now
        if (Fg.isForeground && url.isNotBlank() && !isOurs(url)) {
            pending.addAndGet(dt)
        }
        if (now - lastFlush >= FLUSH) {
            flush(false)
            lastFlush = now                      // ← 别忘了推：不然之后每个 tick 都满足"距上次 ≥15 秒"，变成 5 秒一冲
        }
    }

    /** 把攒下的时间报给 ReadLog（`force`＝换页/进后台时也报，哪怕还没到一个周期） */
    fun flush(force: Boolean) {
        lastFlush = System.currentTimeMillis()
        val ms = pending.getAndSet(0L)
        if (ms < 1000) return
        if (url.isBlank() || isOurs(url)) return
        try {
            readlog.add(
                JSONObject()
                    .put("url", url)
                    .put("title", title)
                    .put("ms", ms)
                    .put("native", true)          // 标明"这是 App 自己计的"，方便和旧的注入上报区分
            )
        } catch (e: Exception) {
            try { log("报阅读时间失败：" + (e.message ?: "?")) } catch (_: Exception) {}
        }
    }

    /** 我们自己的页面不记（和 ReadLog 里同一个判断，这里先挡一道，避免白攒） */
    private fun isOurs(u: String): Boolean =
        u.contains("appassets.androidplatform.net") || u.contains("cdp-event.local") ||
            u.contains("cdp-meta.local") || u.startsWith("about:") || u.startsWith("data:")

    fun snapshot(): JSONObject = JSONObject()
        .put("url", url).put("title", title)
        .put("pendingMs", pending.get())
        .put("foreground", Fg.isForeground)
        .put("note", "App 自己计时（不往网页注入 JS）：前台且在这一页才累加，每 15 秒增量上报一次")
}
