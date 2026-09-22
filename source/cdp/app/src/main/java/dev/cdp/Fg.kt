package dev.cdp

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Fg —— App 处于**前台还是后台**的时间段记录（只为统计服务）。
 *
 * 用户的补充要求（2026-09-20）：数据采集要分前台/后台，柱状图要**双色堆叠**：
 * 下面是前台、上面是后台。也就是"这个小时里有多少时间是人在看，有多少是我在后台跑"。
 *
 * 做法：只记**时间段**，不做别的。`on()`/`off()` 由 Activity 的生命周期回调驱动
 * （onResume = 前台，onPause = 后台），`split(from,to)` 把任意一个区间切成 [前台毫秒, 后台毫秒]。
 * 采集方（阅读时长）在**记录当时**就切好存进去，所以历史记录不需要回放这张表。
 *
 * 边界：只按 Activity 前后台算，**熄屏**也算后台（onPause 会先走）；息屏期间应用若被冻结，
 * 也不会有人来记时间——所以后台时长只会偏少，不会凭空多出来（宁可少算，不编数据）。
 */
object Fg {

    private val lock = Any()
    private val spans = ArrayDeque<LongArray>()      // 每条 = [start, end]（end=0 表示现在还在前台）
    private const val CAP = 500

    @Volatile
    var isForeground: Boolean = false
        private set

    /** 进入前台（onResume / 界面可见） */
    fun on() {
        synchronized(lock) {
            if (isForeground) return
            isForeground = true
            spans.addLast(longArrayOf(System.currentTimeMillis(), 0L))
            while (spans.size > CAP) spans.removeFirst()
        }
    }

    /** 退到后台（onPause / 熄屏） */
    fun off() {
        synchronized(lock) {
            if (!isForeground) return
            isForeground = false
            val last = spans.lastOrNull() ?: return
            if (last[1] == 0L) last[1] = System.currentTimeMillis()
        }
    }

    /**
     * 把区间 [from, to] 切成 [前台毫秒, 后台毫秒]。
     * 从来没见过任何前后台记录时（例如刚启动还没走过生命周期回调），整段按"前台"算。
     */
    fun split(from: Long, to: Long): LongArray {
        if (to <= from) return longArrayOf(0L, 0L)
        val list = synchronized(lock) { spans.toList() }
        if (list.isEmpty()) return longArrayOf(to - from, 0L)
        var fg = 0L
        var covered = 0L
        list.forEach { s ->
            val a = maxOf(from, s[0])
            val b = minOf(to, if (s[1] == 0L) to else s[1])
            if (b > a) {
                fg += (b - a)
                covered += (b - a)
            }
        }
        // 有记录但这段完全落在记录之外（比如应用刚起、页面还在计时）→ 剩下的算后台，别虚报前台
        val total = to - from
        val bg = (total - covered).coerceAtLeast(0L)
        return longArrayOf(fg, bg)
    }

    fun clear() {
        synchronized(lock) { spans.clear() }
    }
}

/**
 * 把前后台跟踪挂到 Activity 生命周期上（不用每处手动调）。
 * 放在 Application 里注册：任何 Activity 的 onResume/onPause 都能反映"这个应用在前台"。
 */
object FgWatcher : Application.ActivityLifecycleCallbacks {
    private var resumed = 0
    override fun onActivityResumed(activity: Activity) {
        resumed++
        Fg.on()
    }

    override fun onActivityPaused(activity: Activity) {
        resumed = (resumed - 1).coerceAtLeast(0)
        if (resumed == 0) Fg.off()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
