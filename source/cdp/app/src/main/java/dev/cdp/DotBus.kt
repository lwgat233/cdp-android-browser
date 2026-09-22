package dev.cdp

import org.json.JSONArray

/**
 * 小点（坐标步骤的落点）的状态。
 *
 * **N1（用户 2026-09-21）之后不再有"无障碍覆盖层"**：那个无障碍服务与权限已经删掉，
 * 小点只画在**页面里**（`pageCmd(op=dotsSet)`），盖不到视频表面之上 —— 这是产品决定，不是缺陷。
 *
 * 这个对象现在只做两件事：① 让状态查询能回答"现在有几个点、都是什么状态"；
 * ② 记录"正在演示/播放小点"的 busy 状态（避免和手动编辑打架）。
 */
object DotBus {

    data class Dot(val n: Int, val x: Float, val y: Float, val state: String, val reps: Int)

    private val dots = ArrayList<Dot>()

    @Volatile
    private var busy = false

    fun set(list: List<Dot>, on: Boolean) {
        synchronized(this) {
            dots.clear()
            if (on) dots.addAll(list)
        }
    }

    fun count(): Int = synchronized(this) { dots.size }

    fun snapshot(): JSONArray = synchronized(this) {
        JSONArray().also { arr ->
            dots.forEach { d ->
                arr.put(
                    org.json.JSONObject().put("n", d.n).put("x", d.x).put("y", d.y)
                        .put("state", d.state).put("reps", d.reps)
                )
            }
        }
    }

    /** 覆盖层是否可用：**永远 false**（已经不用无障碍了，小点只在页面里） */
    fun isReady(): Boolean = false

    fun isBusy(): Boolean = busy

    fun setBusy(b: Boolean) {
        busy = b
    }
}
