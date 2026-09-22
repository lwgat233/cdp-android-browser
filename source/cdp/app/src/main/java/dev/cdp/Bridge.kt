package dev.cdp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/**
 * 命令总线：控制台界面（App 内 WebView）和外部 HTTP 口共用同一套 op，
 * 保证「手机上点的」和「电脑上 curl 的」跑的是同一条代码路径 —— 一条路径验证过就等于两条都验证过。
 */
class Bridge(private val act: MainActivity, val store: ScriptStore, val http: HttpControl) {

    private val main = Handler(Looper.getMainLooper())
    private val logs = ArrayDeque<String>()
    private val events = ArrayDeque<JSONObject>()

    @Volatile var recording = false
        private set
    @Volatile private var recordName = ""
    /** 录制方式：element（记 DOM 指纹）/ coord（只记点在哪）—— 页面侧的 iframe 镜像也要跟着 */
    @Volatile private var recordMode = "element"

    /** 录制期间收到的步骤（含子 frame 的），stop 时优先用它 */
    private val recordedSteps = JSONArray()

    /** 录制开始时间（给界面显示已经录了多久） */
    @Volatile private var recordT0 = 0L

    fun recordNameForPage(): String = recordName

    fun recordModeForPage(): String = recordMode

    /** 当前录制方式（给 ☰ 菜单用：菜单里那条"开始监听"必须按用户选的方式录） */
    fun recordModeNow(): String = recordMode

    fun recordedCount(): Int = synchronized(recordedSteps) { recordedSteps.length() }
    private var recStartIndex = 0

    fun recordedStepsJson(): JSONArray = synchronized(recordedSteps) { JSONArray(recordedSteps.toString()) }

    fun recordElapsedMs(): Long = if (recordT0 <= 0) 0L else System.currentTimeMillis() - recordT0

    /** 元素拾取器最近抓到的那一个（控制台用它填充输入框 / 加为步骤） */
    @Volatile
    private var lastPicked: JSONObject? = null

    fun lastPickedJson(): JSONObject = lastPicked ?: JSONObject()
    private var lastRun: JSONObject? = null

    // ---------------------------------------------------------------- 日志 / 事件

    fun log(s: String) {
        synchronized(logs) {
            logs.addLast("[${nowStamp()}] $s")
            while (logs.size > 400) logs.removeFirst()
        }
        act.pushConsoleLine(s)
    }

    /**
     * 原生层抓到的坐标（MainActivity.dispatchTouchEvent 送进来）。
     *
     * 为什么放原生层：跨域 iframe（学习通播放器）与原生视频表面里的触摸，页面 JS 收不到 ——
     * 用户报的"点视频录不到坐标"就是这个。Activity 在派发之前就能看到**所有**触摸，
     * 拿到 css 坐标后再回顶层页面问锚点（anchorAt），步骤格式与页内录的完全一致，回放不用改。
     */
    fun addNativeCoordStep(cssX: Double, cssY: Double) {
        if (!recording) return
        if (recordMode != "coord") return
        pageCmd(JSONObject().put("op", "anchorAt").put("x", cssX).put("y", cssY)) { r ->
            if (!r.optBoolean("ok")) { log("坐标录制（原生）：问锚点失败 ${r.optString("error")}"); return@pageCmd }
            val now = System.currentTimeMillis()
            // 和上一条坐标步靠得太近就当同一次点击（页内那套可能也记了一次 → 去重）
            val last = synchronized(recordedSteps) {
                if (recordedSteps.length() > 0) recordedSteps.optJSONObject(recordedSteps.length() - 1) else null
            }
            // 同一下点击里，页内/frame 里可能已经先记了一条（比如跨域 frame 的 agent 按过期的
            // 镜像态记成了元素步）—— 坐标模式下**以原生这条为准**，把先记的那条换掉。
            // （为什么不能用"跳过原生"的写法：原生这条才是唯一不会漏的；实测 frame 里的镜像态会滞后。）
            // 同一个点 1.5 秒内又点了一下 → 合成一个点，连点次数 +1（点上显示 ×N，照 clicker 的语义）
            if (last != null && last.optString("via") == "native-coord") {
                val lt = last.optLong("ts")
                val lb = last.optJSONObject("box") ?: JSONObject()
                if (now - lt < 1500 &&
                    Math.abs(lb.optDouble("cx") - cssX) < 12 && Math.abs(lb.optDouble("cy") - cssY) < 12
                ) {
                    last.put("reps", last.optInt("reps", 1) + 1)
                    last.put("ts", now)
                    log("坐标录制（原生）：同一处连点 → 合成一个点（×${last.optInt("reps", 1)}）")
                    pushDots()
                    return@pageCmd
                }
            }
            if (last != null && last.optString("via") != "native-coord") {
                val lt = last.optLong("ts")
                val lb = last.optJSONObject("box") ?: JSONObject()
                if (now - lt < 1200 &&
                    Math.abs(lb.optDouble("cx") - cssX) < 12 && Math.abs(lb.optDouble("cy") - cssY) < 12
                ) {
                    synchronized(recordedSteps) { recordedSteps.remove(recordedSteps.length() - 1) }
                    log("坐标录制（原生）：同一处页内也记了一条（多是 frame 里的过期镜像态），已用原生这条替换")
                }
            }
            val el = r.optJSONObject("el")
            val step = JSONObject()
                .put("t", "click")
                .put("ts", now)
                .put("url", r.optString("url"))
                .put("title", r.optString("title"))
                .put("mode", "coord")
                .put("selector", "")
                .put(
                    "target",
                    JSONObject().put("selector", "").put("id", "").put("tag", el?.optString("tag") ?: "")
                        .put("text", "").put("attrs", JSONObject())
                        .put("box", JSONObject().put("cx", cssX).put("cy", cssY).put("x", cssX).put("y", cssY).put("w", 1).put("h", 1))
                )
                .put("box", JSONObject().put("cx", cssX).put("cy", cssY).put("x", cssX).put("y", cssY).put("w", 1).put("h", 1))
                .put("anchor", r.optJSONObject("anchor") ?: JSONObject())
                .put("via", "native-coord")
                .put("pauseAfter", 350)
                .put(
                    "note",
                    if (el != null && el.optBoolean("isFrame")) "坐标录制（原生层抓的：点在 iframe/视频区域上，页内 JS 收不到）"
                    else "坐标录制（原生层抓的真实触摸）"
                )
            synchronized(recordedSteps) { recordedSteps.put(step) }
            pushDots()
            log("坐标录制（原生）：(${cssX.toInt()}, ${cssY.toInt()}) 页面元素=" + (el?.optString("tag") ?: "?") +
                (if (el != null && el.optBoolean("isFrame")) "（iframe：页内那套抓不到，靠这一层）" else ""))
            act.onRecordStep(JSONObject().put("step", step).put("index", recordedCount() - 1))
        }
    }

    // ---------------------------------------------------------------- 小点（坐标步骤的落点）
    // 用户要的形态（照 clicker）：坐标步骤在页面上画成**编号小点**，点一下=让它自己点那里；
    // 元素步不画点，但序号照占（所以页面上可能是 1 2 4）；同一个点记了多次 → 角标 ×N。
    @Volatile var dotsOn = true
    private val dotState = HashMap<Int, String>()      // 步序号(0-based) → idle/current/done

    /** 回放进度：{i, n, label}；null = 不显示（用户要求"显示出正在执行，包括点，包括执行那一步"） */
    @Volatile private var replayProgress: JSONObject? = null

    /** 这一步在干什么（给顶部小字与日志用）：坐标步给坐标，元素步优先给它的文字 */
    private fun stepLabel(s: JSONObject): String {
        if (s.optString("mode") == "coord" || s.optString("t") == "clickGroup") {
            val b = s.optJSONObject("box") ?: s.optJSONObject("anchor")
            val x = if (b?.has("cx") == true) b.optDouble("cx") else b?.optDouble("x", 0.0) ?: 0.0
            val y = if (b?.has("cy") == true) b.optDouble("cy") else b?.optDouble("y", 0.0) ?: 0.0
            val reps = s.optInt("reps", 1)
            return "坐标 ${x.toInt()},${y.toInt()}" + (if (reps > 1) "×$reps" else "")
        }
        val t = s.optJSONObject("target")
        val txt = (t?.optString("text") ?: s.optString("text")).trim()
        if (txt.isNotEmpty()) return "点「" + txt.take(18) + "」"
        val sel = t?.optString("selector").orEmpty().trim()
        if (sel.isNotEmpty()) return "点 " + sel.take(28)
        val u = s.optString("url").trim()
        if (u.isNotEmpty()) return u.take(36)
        return s.optString("t").ifBlank { "一步" }
    }

    /** 把当前队列里的坐标步骤推给：① 页面里的点（页面内兜底）② 无障碍覆盖层（能盖在视频上面） */
    fun pushDots() {
        val steps = recordedStepsJson()
        val pageArr = JSONArray()
        val overlay = ArrayList<DotBus.Dot>()
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: continue
            if (s.optString("mode") != "coord" && s.optString("t") != "clickGroup") continue
            val b = s.optJSONObject("box") ?: s.optJSONObject("anchor") ?: continue
            val cx = if (b.has("cx")) b.optDouble("cx") else b.optDouble("x", 0.0)
            val cy = if (b.has("cy")) b.optDouble("cy") else b.optDouble("y", 0.0)
            val reps = s.optInt("reps", 1)
            val st = dotState[i] ?: "idle"
            pageArr.put(
                JSONObject().put("n", i + 1).put("x", cx).put("y", cy)
                    .put("state", st).put("reps", reps)
            )
            act.screenFromCss(cx, cy)?.let { p ->
                overlay.add(DotBus.Dot(i + 1, p.first, p.second, st, reps))
            }
        }
        // 回放期间页面里那份点**照样显示**（用户要求：看得见正在执行哪一步），但锁住不接触摸
        // —— 注入的真实触摸打到点上的话，这一步会被点第二遍（成环，B-09 实测过）
        pageCmd(
            JSONObject().put("op", "dotsSet").put("on", dotsOn)
                .put("locked", DotBus.isBusy())
                .put("progress", replayProgress ?: JSONObject.NULL)
                .put("dots", pageArr)
        ) { }
        DotBus.set(overlay, dotsOn)
    }

    /** 页面上那个点在页面里被点了（页面内兜底那条路） */
    private fun playDot(n: Int) = runOneStep(n - 1)

    // ---------------------------------------------------------------- "指定哪一块是视频"（并进资源嗅探）
    // 用户原话：「浏览，页面与元素的那个页面的视频元素，对于学习通没效果。如果指定哪个块是视频元素就好了。
    //            还有我觉得这个还是融合到资源嗅探中比较好。」
    // 学习通的视频在**跨域 iframe** 里（有的站还是原生视频表面），顶层页面的脚本读不到 → 自动嗅探也认不出。
    // 做法：用户在页面上点那一块 → 先问页面这一块里有什么（video/audio/source/iframe/data-src），
    //       一个都拿不到（blob/MSE 那种）就退回**最近 60 秒真实发出过的请求**里找候选（含无扩展名的地址），
    //       找到的写进嗅探清单并标注来源=手动指定。失败就如实说"这一块没找到"，不假装。
    @Volatile var pickMode = false
    private var pickTimer: Runnable? = null

    fun startPick(on: Boolean) {
        pickMode = on
        try { pageCmd(JSONObject().put("op", "pickHint").put("on", on)) { } } catch (_: Throwable) {}
        pickTimer?.let { try { main.removeCallbacks(it) } catch (_: Throwable) {} }
        if (on) {
            act.closeConsole()                       // 让用户看得见页面（回放也是这个口径）
            act.connStep("点页面上视频那一块（20 秒内有效）")
            log("指定位置为视频：请在页面上点视频所在的那一块")
            val t = Runnable {
                if (pickMode) {
                    pickMode = false
                    pageCmd(JSONObject().put("op", "pickHint").put("on", false)) { }
                    act.connStep("指定位置：超时，已取消")
                    log("指定位置为视频：20 秒没点，自动取消")
                }
            }
            pickTimer = t
            main.postDelayed(t, 20_000L)
        } else {
            act.connStep("已取消指定位置")
        }
    }

    /** 用户在页面上点了一处（CSS 坐标）→ 找出这一块里的媒体地址，写进嗅探清单 */
    fun pickAt(cssX: Double, cssY: Double, reply: ((JSONObject) -> Unit)? = null) {
        if (cssX < 0 || cssY < 0) {
            reply?.invoke(err("没拿到点击位置"))
            return
        }
        pickMode = false
        pickTimer?.let { try { main.removeCallbacks(it) } catch (_: Throwable) {} }
        pageCmd(JSONObject().put("op", "pickHint").put("on", false)) { }
        val pageUrl = try { act.stateCached().optString("url") } catch (_: Throwable) { "" }
        pageCmd(JSONObject().put("op", "mediaAt").put("x", cssX).put("y", cssY)) { r ->
            val out = JSONObject().put("ok", true).put("x", cssX).put("y", cssY)
            val el = r.optJSONObject("el")
            val frame = r.optJSONObject("iframe")
            val via = ((el?.optString("tag") ?: "") +
                (if (el != null && el.optString("text").isNotBlank()) "「" + el.optString("text").take(18) + "」" else "")).trim()
            val tried = JSONArray()
            var added = 0
            // ① 元素自带的地址优先（直链 / 跨域 iframe 的 src / data-src）
            val cands = r.optJSONArray("candidates") ?: JSONArray()
            for (i in 0 until cands.length()) {
                val c = cands.optJSONObject(i) ?: continue
                val u = c.optString("url")
                if (u.isBlank()) continue
                tried.put(u)
                if (act.sniff.add(u, pageUrl, force = true, from = "手动指定",
                        via = (via + " · " + c.optString("label")).trim(' ', '·'))) added++
            }
            // ② 一点地址都没有（跨域 iframe 里的 blob/MSE 播放）→ 从最近的真实请求里找候选。
            //    只有"这一块附近确实有媒体"（near）或点到了 iframe 上才这么做 ——
            //    否则点空白处也会硬凑出地址来（如实说"没找到"才对）。
            var fromRecent = 0
            if (added == 0 && (frame != null || r.optBoolean("near"))) {
                val host = frame?.optString("host").orEmpty()
                    .ifBlank { try { android.net.Uri.parse(pageUrl).host ?: "" } catch (_: Exception) { "" } }
                val rec = act.sniff.recentRequests(60_000L, host, pageUrl)
                val take = if (rec.length() > 0) rec else act.sniff.recentRequests(60_000L, "", pageUrl)
                for (i in 0 until take.length()) {
                    val u = take.optJSONObject(i)?.optString("url").orEmpty()
                    if (u.isBlank()) continue
                    if (act.sniff.add(u, pageUrl, force = true, from = "手动指定",
                            via = "最近请求（可能不是正片）")) {
                        added++
                        fromRecent++
                        tried.put(u)
                    }
                    if (fromRecent >= 3) break
                }
            }
            out.put("added", added).put("tried", tried)
                .put("el", el ?: JSONObject.NULL).put("iframe", frame ?: JSONObject.NULL)
            if (added == 0) {
                out.put("ok", false).put("error", "这一块没找到媒体地址（元素上没有，最近也没有）")
                act.connStep("这一块没找到媒体地址")
            } else {
                act.connStep("已加进嗅探清单：" + added + " 条")
            }
            log("指定位置为视频：(${cssX.toInt()},${cssY.toInt()}) " +
                (if (added > 0) "加进 $added 条" else "没找到") + " 元素=" + (el?.optString("tag") ?: "-") +
                " 候选=" + tried.toString().take(200))
            reply?.invoke(out)
        }
    }

    /** 点一下小点 = 让"它自己点"这一步：只跑这一步，跑完把点标成已执行 */
    private fun runOneStep(index: Int) {
        val steps = recordedStepsJson()
        if (index < 0 || index >= steps.length()) { log("小点回放：没有第 ${index + 1} 步"); return }
        val step = steps.optJSONObject(index) ?: return
        dotState[index] = "current"
        replayProgress = JSONObject().put("i", index + 1).put("n", steps.length()).put("label", stepLabel(step))
        DotBus.setBusy(true)
        pushDots()
        log("小点：自己点第 ${index + 1} 步（${if (step.optString("mode") == "coord") "坐标" else step.optString("t")}）")
        runStep(step) { r ->
            DotBus.setBusy(false)
            replayProgress = null
            dotState[index] = if (r.optBoolean("ok")) "done" else "idle"
            log("小点第 ${index + 1} 步：" + (if (r.optBoolean("ok")) "成功" else ("失败 " + r.optString("error"))))
            pushDots()
        }
    }

    /**
     * 合并（用户要的"跟 clicker 一样把它合并起来"）：
     *  · mode=prev：把第 index 步并进**前一个坐标步**
     *      - 两处离得很近（≤16 css px）→ 合成一个点，连点次数相加（点上显示 ×N）
     *      - 离得远 → 合成一个**点组步骤**（t=clickGroup，按顺序把几个点各点一遍），仍然是"一步"
     */
    private fun mergeDot(index: Int, mode: String, reply: (JSONObject) -> Unit) {
        val steps = recordedSteps
        synchronized(steps) {
            if (index <= 0 || index >= steps.length()) {
                reply(err("没有可合并的前一个点（第 ${index + 1} 步）")); return
            }
            val cur = steps.optJSONObject(index) ?: run { reply(err("步骤为空")); return }
            var prevIdx = index - 1
            while (prevIdx >= 0) {
                val p = steps.optJSONObject(prevIdx)
                if (p != null && (p.optString("mode") == "coord" || p.optString("t") == "clickGroup")) break
                prevIdx--
            }
            if (prevIdx < 0) { reply(err("前面没有坐标步可合并")); return }
            val prev = steps.getJSONObject(prevIdx)
            val pb = prev.optJSONObject("box") ?: JSONObject()
            val cb = cur.optJSONObject("box") ?: JSONObject()
            val near = Math.abs(pb.optDouble("cx") - cb.optDouble("cx")) <= 16 &&
                Math.abs(pb.optDouble("cy") - cb.optDouble("cy")) <= 16
            if (near) {
                prev.put("reps", prev.optInt("reps", 1) + cur.optInt("reps", 1))
                log("合并：第 ${index + 1} 步并进第 ${prevIdx + 1} 步（同一处，连点 ×${prev.optInt("reps", 1)}）")
            } else {
                val pts = prev.optJSONArray("points") ?: JSONArray().also { arr ->
                    arr.put(
                        JSONObject().put("cx", pb.optDouble("cx")).put("cy", pb.optDouble("cy"))
                            .put("reps", prev.optInt("reps", 1))
                            .put("anchor", prev.optJSONObject("anchor") ?: JSONObject())
                    )
                }
                pts.put(
                    JSONObject().put("cx", cb.optDouble("cx")).put("cy", cb.optDouble("cy"))
                        .put("reps", cur.optInt("reps", 1))
                        .put("anchor", cur.optJSONObject("anchor") ?: JSONObject())
                )
                prev.put("t", "clickGroup").put("mode", "coord").put("points", pts)
                    .put("selector", "").put("reps", 1)
                log("合并：第 ${index + 1} 步并进第 ${prevIdx + 1} 步（不同处 → 合成一个点组，共 ${pts.length()} 个点）")
            }
            steps.remove(index)
        }
        pushDots()
        reply(JSONObject().put("ok", true).put("steps", recordedCount()).put("dots", DotBus.count()))
    }

    /** 拖动小点 = 直接改这一步的坐标（页面 CSS 坐标） */
    private fun moveDot(n: Int, cssX: Double, cssY: Double) {
        val steps = recordedSteps
        val i = n - 1
        synchronized(steps) {
            if (i < 0 || i >= steps.length()) return
            val s = steps.optJSONObject(i) ?: return
            val box = s.optJSONObject("box") ?: JSONObject()
            box.put("cx", cssX).put("cy", cssY).put("x", cssX).put("y", cssY)
            s.put("box", box)
            s.optJSONObject("anchor")?.apply { put("cx", cssX).put("cy", cssY) }
        }
        log("小点：第 $n 步挪到 (${cssX.toInt()}, ${cssY.toInt()})")
        pushDots()
    }

    /** 步骤摘要（日志与界面共用的一套说法） */
    fun stepSummary(s: JSONObject): String = when (s.optString("t")) {
        "clickGroup" -> "点组（${s.optJSONArray("points")?.length() ?: 0} 个点）"
        "click" -> if (s.optString("mode") == "coord")
            "坐标点击 (${s.optJSONObject("box")?.optInt("cx") ?: 0}, ${s.optJSONObject("box")?.optInt("cy") ?: 0})" +
                (if (s.optInt("reps", 1) > 1) " 连点×${s.optInt("reps", 1)}" else "")
        else "点击 " + (s.optJSONObject("target")?.optString("selector").orEmpty().ifBlank { s.optString("selector") })
        "wait" -> "等待 ${s.optLong("ms", 1000)} ms"
        "waitFor" -> "等条件：" + (s.optJSONObject("cond")?.optString("type") ?: "?") +
            " " + (s.optJSONObject("cond")?.optString("selector").orEmpty())
        "playVideo" -> "播放视频 " + s.optString("selector").ifBlank { "（第一个 video）" }
        "goto" -> "打开 " + s.optString("url")
        "input" -> "输入 " + s.optString("selector") + " = " + s.optString("value")
        "if" -> "条件分支 " + (s.optJSONObject("cond")?.optString("type") ?: "?")
        else -> s.optString("t")
    }

    /** 让小点单击后回到控制台打开"这一步"的编辑小窗 */
    fun openStepEditor(n: Int) {
        act.openStepEditorInConsole(n)
    }

    fun onPageEvent(o: JSONObject) {
        synchronized(events) {
            events.addLast(o)
            while (events.size > 300) events.removeFirst()
        }
        val t = o.optString("t")
        when (t) {
            "click" -> {
                val trusted = o.optBoolean("trusted")
                log("页面点击 tag=${o.optString("tag")} text=${o.optString("text").take(24)} trusted=$trusted")
                act.onPageClickEvent(o)
            }
            "dotTap" -> {
                // 页面里那个小点被点了（覆盖层没开时的兜底路径）
                playDot(o.optInt("n", 0))
            }
            "recStep" -> {
                // 跨 frame 汇总：iframe 里的点击（视频播放器常常在 iframe 里）也会送到这里，
                // 而 /api/record?action=stop 只能读到顶层文档的步骤 —— 所以原生侧自己攒一份。
                val st = o.optJSONObject("step")
                if (st != null) {
                    // 坐标模式下 click 步**只认原生那条**：跨域 frame 里的 agent 可能按过期的镜像态
                    // 记成元素步（实测：一条点击变两条）。非 click 的步（输入/等待/跳转）照收。
                    if (st.optString("t") == "click" && recordMode == "coord") {
                        log("坐标录制：丢掉页面侧的 click 步（坐标模式只认原生那条）")
                        return
                    }
                    synchronized(recordedSteps) { recordedSteps.put(st) }
                    pushDots()
                }
                act.onRecordStep(o)
            }
            "ready" -> log("页面脚本就绪: ${o.optString("url").take(80)}")
            "credSeen" -> { act.onLoginSeen(o) }
            "readTime" -> { act.readlog.add(o) }
            "picked" -> {
                // 元素拾取器抓到的元素：存下来 + 回灌控制台（用户可以一键填到输入框/加为步骤）
                lastPicked = o
                val t = o.optJSONObject("target") ?: JSONObject()
                log(
                    "拾取到元素：${o.optString("selector")}" +
                        (if (t.optString("text").isNotBlank()) "（文字：${t.optString("text").take(24)}）" else "") +
                        (if (o.optBoolean("inFrame")) "（在 iframe 里，回放时顶层点不到）" else "")
                )
                act.onPickedElement(o)
            }
            "log" -> log("页面日志: ${o.optString("msg").take(300)}")
            "nav" -> act.onPageNav(o.optString("url"))
        }
    }

    private fun nowStamp(): String {
        val f = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        return f.format(java.util.Date())
    }

    // ---------------------------------------------------------------- 对外状态

    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("ok", true)
        o.put("app", "CDP")
        o.put("version", BuildConfig.VERSION_NAME)
        o.put("debug", BuildConfig.DEBUG)
        o.put("browser", act.stateCached())
        o.put("recording", recording)
        o.put("recordName", recordName)
        o.put("recordMode", recordMode)
        o.put("scripts", store.count())
        o.put("cdpSocket", act.cdpSocketHint())
        o.put("lastRun", lastRun ?: JSONObject.NULL)
        return o
    }

    // ---------------------------------------------------------------- 页面通道

    private fun unquote(raw: String): String = try {
        if (raw.startsWith("\"")) JSONTokener(raw).nextValue() as String else raw
    } catch (_: Exception) {
        raw
    }

    /** 页面内 agent 的命令通道：window.__CDP.cmd(json) → json 字符串 */
    fun pageCmd(o: JSONObject, cb: (JSONObject) -> Unit) = pageCmd(o, 1, cb)

    private fun pageCmd(o: JSONObject, retriesLeft: Int, cb: (JSONObject) -> Unit) {
        act.evalInBrowser("window.__CDP ? window.__CDP.cmd(${JSONObject.quote(o.toString())}) : '__NO_AGENT__'") { raw ->
            val s = unquote(raw)
            // evaluateJavascript 偶发会回 null（脚本没跑成，页面侧异常或 JS 上下文刚建好），
            // 不重试的话回放里就会表现成「某一步悄悄没生效」，而且看不出原因
            if ((s == "null" || s == "__NO_AGENT__") && retriesLeft > 0) {
                main.postDelayed({ pageCmd(o, retriesLeft - 1, cb) }, 120)
                return@evalInBrowser
            }
            if (s == "__NO_AGENT__" || s == "null") {
                cb(JSONObject().put("ok", false).put("error", "页面内 agent 未注入或本次调用没跑成（可能是 about:blank / 注入被拒 / 页面 JS 异常）"))
                return@evalInBrowser
            }
            try {
                cb(JSONObject(s))
            } catch (e: Exception) {
                cb(JSONObject().put("ok", false).put("error", "agent 返回无法解析: ${s.take(200)}"))
            }
        }
    }

    // ---------------------------------------------------------------- 命令分发

    fun dispatch(op: String, a: JSONObject, reply: (JSONObject) -> Unit) {
        try {
            when (op) {
                "status" -> reply(statusJson())
                "browser.state" -> reply(JSONObject().put("ok", true).put("state", act.browserStateJson()))
                "browser.goto" -> {
                    val u = a.optString("url").trim()
                    if (u.isEmpty()) reply(err("url 为空")) else {
                        act.navigateSmart(u); reply(JSONObject().put("ok", true).put("nav", u))
                    }
                }
                "browser.search" -> {
                    val q = a.optString("q").trim()
                    if (q.isEmpty()) reply(err("q 为空")) else {
                        val u = act.searchUrl(q); act.loadUrl(u)
                        reply(JSONObject().put("ok", true).put("q", q).put("url", u))
                    }
                }
                "browser.back" -> { act.backIfPossible(); reply(JSONObject().put("ok", true)) }
                "browser.forward" -> { act.forwardIfPossible(); reply(JSONObject().put("ok", true)) }
                "browser.reload" -> { act.reloadBrowser(); reply(JSONObject().put("ok", true)) }
                "browser.stop" -> { act.browser.stopLoading(); reply(JSONObject().put("ok", true)) }

                "page.eval" -> act.evalInBrowser(a.optString("js")) { raw ->
                    val s = unquote(raw)
                    val o = JSONObject().put("ok", true).put("raw", s)
                    if (s == "null") o.put("note", "表达式返回了 null：可能是页面里这行 JS 抛异常了（evaluateJavascript 分不清「返回 null」和「执行出错」）")
                    reply(o)
                }
                "page.query" -> pageCmd(opt(a, "query")) { reply(it) }
                "page.diag" -> pageCmd(opt(a, "diag")) { reply(it) }
                "page.elementAt" -> pageCmd(opt(a, "elementAt")) { reply(it) }
                "page.stamp" -> pageCmd(JSONObject().put("op", "stamp")) { reply(it) }
                "page.click" -> clickNow(a) { reply(it) }
                "page.check" -> pageCmd(JSONObject().put("op", "check").put("cond", a.optJSONObject("cond") ?: JSONObject())) { reply(it) }
                "page.video" -> pageCmd(
                    JSONObject().put("op", "video").put("action", a.optString("action", "state"))
                        .put("selector", a.optString("selector")).put("muted", a.optBoolean("muted", true))
                ) { reply(it) }

                // 界面控制（控制台的 ✕、☰ 下拉菜单都走这里，避免再开一条通道）
                "ui.close" -> { act.closeConsole(); log("控制台已关闭（✕ 生效）"); reply(JSONObject().put("ok", true)) }
                "ui.open" -> { act.openConsole(a.optString("tab").ifBlank { null }); reply(JSONObject().put("ok", true)) }
                "ui.tab" -> { act.switchConsoleTab(a.optString("tab")); reply(JSONObject().put("ok", true)) }
                "ui.menu" -> { act.showMainMenu(); reply(JSONObject().put("ok", true)) }

                // 历史与书签
                "history.list" -> reply(JSONObject().put("ok", true).put("list", act.nav.historyJson()))
                "downloads.list" -> reply(JSONObject().put("ok", true).put("list", act.downloadsJson()))
                // N6：下载列表上那个"状态键"——正在下＝暂停，已暂停＝继续（一个位置只描述状态）
                "downloads.toggle" -> reply(act.toggleDownloadPause(a.optString("id")))
                "download.start" -> {
                    val u = a.optString("url")
                    if (u.isBlank()) reply(err("url 为空"))
                    else {
                        act.startDownload(u, nameOverride = a.optString("name").ifBlank { null })
                        reply(JSONObject().put("ok", true).put("url", u))
                    }
                }
                // 广告拦截
                "adblock.get" -> reply(act.adblock.stats())
                "ffmpeg.state" -> reply(act.ffmpeg.state())
                "ffmpeg.info" -> {
                    val u = a.optString("url")
                    Thread { reply(act.ffmpeg.info(u)) }.start()
                }
                "ffmpeg.remux" -> {
                    val u = a.optString("url"); val n = a.optString("name")
                    // Referer 的来源：优先"这条链接是从哪个页面嗅探到的"（最可靠），
                    // 其次才是当前页。实测 B 站只认站点域名根，取错就 403。
                    var pageHint = ""
                    try {
                        val arr = act.sniff.list()
                        pageHint = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                            .firstOrNull { it.optString("url") == u }?.optString("page") ?: ""
                    } catch (e: Throwable) {}
                    val extra = a.optJSONArray("args")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    } ?: emptyList()
                    Thread { reply(act.ffmpeg.remux(u, n, extra, a.optString("ext", "mp4"), a.optString("referer").ifBlank {
                            act.originOf(pageHint.ifBlank { act.currentUrl() }).ifBlank { act.originOf(u) }
                        }, act.cookiesForHost(u))) }.start()
                }
                "ffmpeg.run" -> {
                    val args = a.optString("args")
                    Thread { reply(act.ffmpeg.run(args, a.optLong("timeoutSec", 120))) }.start()
                }
                "video.info" -> reply(act.videoInfo(a.optString("url")))
                "player.system" -> reply(act.openWithSystemPlayer(a.optString("url"), a.optString("mime", "video/*")))
                // 内置播放器**现在在放什么**：以前只能"打开"，读不到状态（界面显示空，看着像没播）
                "player.state" -> act.evalInBrowser(
                    "(function(){var v=document.querySelector('video');var u=location.href;" +
                    "return JSON.stringify({playerPage:u.indexOf('player.html')>=0,url:u,hasVideo:!!v," +
                    "paused:v?v.paused:null,ms:v?Math.round(v.currentTime*1000):null," +
                    "durMs:(v&&isFinite(v.duration))?Math.round(v.duration*1000):null," +
                    "ready:v?v.readyState:null,err:(v&&v.error)?v.error.code:null});})()"
                ) { raw ->
                    val o = try { JSONObject(unquote(raw)) } catch (e: Exception) { JSONObject() }
                    reply(o.put("ok", true))
                }
                "video.list" -> pageCmd(JSONObject().put("op", "videoState")) { r -> reply(r) }
                "read.add" -> {
                    act.readlog.add(a)
                    reply(JSONObject().put("ok", true))
                }
                "read.list" -> reply(
                    act.readlog.list(a.optString("filter"), a.optString("sort", "time"), a.optInt("limit", 300))
                )
                "read.stats" -> reply(act.readlog.stats(a.optString("range", "hour")))
                "read.del" -> reply(act.readlog.del(a.optString("id")))
                "read.clear" -> reply(act.readlog.clear())
                "term.run" -> {
                    // curl 这类要联网，必须离开主线程（否则 NetworkOnMainThreadException）
                    val cmd = a.optString("cmd")
                    Thread { reply(act.terminal.run(cmd)) }.start()
                }
                "adblock.mode" -> reply(
                    if (a.has("mode")) JSONObject().put("ok", true).put("mode", act.adblock.setMode(a.optString("mode")))
                    else JSONObject().put("ok", true).put("mode", act.adblock.mode())
                )
                "adblock.cosmetic" -> reply(
                    JSONObject().put("ok", true).put("list", org.json.JSONArray(act.adblock.cosmeticList()))
                )
                "adblock.cosmetic.add" -> reply(
                    JSONObject().put("ok", act.adblock.addCosmetic(a.optString("sel")))
                )
                "adblock.cosmetic.del" -> reply(
                    JSONObject().put("ok", act.adblock.removeCosmetic(a.optString("sel")))
                )
                "adblock.cosmetic.reset" -> reply(
                    JSONObject().put("ok", true).put("count", act.adblock.resetCosmetic())
                )
                "cosmetic.push" -> {
                    act.runOnUiThread { act.pushCosmetic(act.browser) }
                    reply(JSONObject().put("ok", true).put("note", "已把隐藏规则推给当前页面"))
                }
                "vault.state" -> reply(act.vault.state())
                "vault.unlock" -> reply(act.vault.requestUnlock())
                "vault.lock" -> reply(act.vault.lock())
                "vault.list" -> reply(act.vault.list())
                "vault.get" -> reply(act.vault.get(a.optString("id")))
                "vault.save" -> reply(
                    act.vault.save(
                        JSONObject().put("site", a.optString("site")).put("user", a.optString("user"))
                            .put("pass", a.optString("pass")).put("note", a.optString("note"))
                    )
                )
                "vault.capture" -> reply(
                    act.vault.capture(
                        JSONObject().put("site", a.optString("site")).put("user", a.optString("user"))
                            .put("pass", a.optString("pass")).put("note", a.optString("fromPage"))
                    )
                )
                "vault.del" -> reply(act.vault.del(a.optString("id")))
                "vault.gen" -> reply(
                    act.vault.gen(
                        a.optInt("len", 16), a.optBoolean("upper", true), a.optBoolean("lower", true),
                        a.optBoolean("digit", true), a.optBoolean("sym", true)
                    )
                )
                "adblock.set" -> {
                    act.adblock.setEnabled(a.optBoolean("enabled", true))
                    reply(JSONObject().put("ok", true).put("enabled", act.adblock.isEnabled()))
                }
                "adblock.add" -> reply(JSONObject().put("ok", act.adblock.addRule(a.optString("rule"))))
                "adblock.remove" -> reply(JSONObject().put("ok", act.adblock.removeRule(a.optString("rule"))))
                "adblock.clear" -> reply(JSONObject().put("ok", true).put("removed", act.adblock.clearRules()))
                // 站点警告名单：和广告规则同一份规则库（adblock.json），只是 kind 不同
                "adblock.warn.list" -> reply(
                    JSONObject().put("ok", true).put("list", org.json.JSONArray(act.adblock.warnList()))
                )
                "adblock.warn.add" -> reply(JSONObject().put("ok", act.adblock.addWarn(a.optString("host"))))
                "adblock.warn.remove" -> reply(JSONObject().put("ok", act.adblock.removeWarn(a.optString("host"))))
                "adblock.warn.clear" -> reply(JSONObject().put("ok", true).put("removed", act.adblock.clearWarn()))
                "adblock.reset" -> reply(JSONObject().put("ok", true).put("rules", act.adblock.resetDefault()))
                "downloads.delete" -> reply(
                    JSONObject().put(
                        "ok",
                        act.deleteDownload(a.optString("name"), a.optBoolean("keepFile", false))
                    )
                )

                // 首页小 app 网格（用户清单第 26 条）：加号添加、图标+名字、点开、长按改/删
                "apps.list" -> reply(JSONObject().put("ok", true).put("apps", AppsStore.list(act.settings)))
                "apps.save" -> reply(JSONObject().put("ok", true).put("apps", AppsStore.save(act.settings, a)))
                "apps.remove" -> reply(
                    JSONObject().put("ok", true)
                        .put("apps", AppsStore.remove(act.settings, a.optString("id")))
                )
                "apps.move" -> reply(
                    JSONObject().put("ok", true)
                        .put("apps", AppsStore.move(act.settings, a.optString("id"), a.optInt("dir", 1)))
                )

                // 设置 / 浏览模式 / 隐身 / Cookie
                "settings.get" -> {
                    val o = act.settings.jsonForUi(act.proxyPortNow())
                    o.put("engines", org.json.JSONArray(SettingsStore.ENGINE_NAMES.keys.toList()))
                    o.put("engineNames", JSONObject(SettingsStore.ENGINE_NAMES as Map<*, *>))
                    o.put("uaDesktop", SettingsStore.DESKTOP_UA)
                    o.put("searchTemplateNow", act.settings.searchTemplate())
                    reply(JSONObject().put("ok", true).put("settings", o))
                }
                "settings.set" -> {
                    val s = a.optJSONObject("settings") ?: JSONObject()
                    act.settings.update(s)
                    if (s.has("uaMode") || s.has("customUa")) act.applyUserAgent()
                    if (s.has("incognito")) act.setIncognito(act.settings.incognito())
                    // 代理：只要碰到跟代理有关的任何字段就重新应用（改了端口/账号/白名单也算）
                    // 注意：selfProxy（第 16 条自代理开关）也必须在这里 —— 漏了它，
                    // 开关写进设置里但**从没应用**（现象：勾了没反应，日志只有"中继已停止"）
                    val proxyKeys = listOf(
                        "proxyType", "proxyHost", "proxyPort", "proxyUser", "proxyPass", "bypass", "selfProxy"
                    )
                    if (proxyKeys.any { s.has(it) }) act.applyProxy()
                    reply(JSONObject().put("ok", true).put("settings", act.settings.jsonForUi(act.proxyPortNow())))
                }
                "proxy.apply" -> {
                    act.applyProxy()
                    reply(JSONObject().put("ok", true).put("port", act.proxyPortNow()))
                }
                "testup.start" -> {
                    val p = act.testUp.start(a.optInt("port", 18890), a.optString("user"), a.optString("pass"))
                    reply(JSONObject().put("ok", p > 0).put("testup", act.testUp.statsJson()))
                }
                "testup.stats" -> reply(JSONObject().put("ok", true).put("testup", act.testUp.statsJson()))
                "testup.stop" -> {
                    act.testUp.stop()
                    reply(JSONObject().put("ok", true))
                }
                // 元素拾取器：点「拾取」→ 收起控制台 → 在页面上点一下 → 抓到元素回到控制台
                "picker.arm" -> {
                    pageCmd(JSONObject().put("op", "pickArm")) { r ->
                        if (r.optBoolean("ok")) {
                            act.closeConsole()
                            log("拾取已就绪：控制台已收起，请在页面上点要抓的元素")
                        }
                        reply(r)
                    }
                }
                "picker.last" -> reply(JSONObject().put("ok", true).put("picked", lastPickedJson()))
                // 反馈 #11：拾取到元素后要能"给它写点信息"（名字/备注），不然点了只是点了一下
                "picker.note" -> {
                    val lp = lastPicked
                    if (lp == null) {
                        reply(err("还没有拾取到元素（先点「拾取元素」再去页面上点一下）"))
                    } else {
                        lp.put("note", a.optString("note"))
                        lp.put("alias", a.optString("alias"))
                        reply(JSONObject().put("ok", true).put("picked", lp))
                    }
                }
                // 网络面板（请求时间线 + 统计 + 网络工具）
                "net.list" -> reply(
                    act.netTimeline(
                        a.optString("filter"), a.optString("kind"), a.optInt("limit", 100)
                    ).put("relay", act.proxyRunningNow()).put("proxyPort", act.proxyPortNow())
                )
                "net.stats" -> reply(act.netlog.stats(a.optString("range", "hour")).put("ok", true))
                "net.clear" -> {
                    act.netlog.clear()
                    reply(JSONObject().put("ok", true))
                }
                "net.enabled" -> {
                    val on = a.optBoolean("on", true)
                    act.netlog.setEnabled(on)
                    reply(JSONObject().put("ok", true).put("enabled", on))
                }
                // 解析/证书/建连/测速都是阻塞网络调用：**必须放后台线程**，
                // 否则主线程直接抛 NetworkOnMainThreadException（实测踩过：报"连不上"，其实是没敢连）
                "net.tool" -> {
                    val action = a.optString("action")
                    val host = a.optString("host")
                    val port = a.optInt("port", 0)
                    val url = a.optString("url")
                    Thread {
                        val r = try {
                            act.netToolJson(action, host, port, url)
                        } catch (e: Exception) {
                            JSONObject().put("ok", false)
                                .put("error", (e.javaClass.simpleName + ": " + (e.message ?: "")))
                        }
                        reply(r)
                    }.start()
                }
                // ---- AI 接口（清单 11/12）：不需要交谈，给端口；默认不带隐私数据 ----
                "ai.context" -> act.evalInBrowser(AiApi.EXTRACT_JS) { raw ->
                    val s = unquote(raw)
                    try {
                        val o = JSONObject(s)
                        o.put("space", Spaces.current)
                        reply(o)
                    } catch (e: Exception) {
                        reply(
                            JSONObject().put("ok", false).put("error", "页面提取失败: ${e.message}")
                                .put("raw", s.take(400))
                        )
                    }
                }
                "ai.act" -> {
                    val doWhat = a.optString("do")
                    // 注意：回放步骤用的字段是 **t**（不是 type）——早先写过 type，结果报"未知步骤类型"
                    val step = JSONObject()
                    when (doWhat) {
                        "click" -> step.put("t", "click")
                            .put("selector", a.optString("selector"))
                            .put("text", a.optString("text"))
                        "type", "fill" -> step.put("t", "input")
                            .put("selector", a.optString("selector"))
                            .put("value", a.optString("value"))
                        "goto" -> step.put("t", "goto").put("url", a.optString("url"))
                        "search" -> step.put("t", "goto").put("url", act.searchUrlFor(a.optString("q")))
                        else -> {
                            reply(JSONObject().put("ok", false).put("error", "不认识的动作：$doWhat（支持 click/type/goto/search）"))
                            return
                        }
                    }
                    log("AI 接口动作：$doWhat")
                    runStep(step) { reply(AiApi.result(it, "act:" + doWhat, Spaces.current)) }
                }
                // ---- 接口目录 / 组合接口 / 抓内容（二级接口的另一半）----
                "api.help" -> reply(ApiCatalog.help())
                "kb.summary" -> reply(act.summaryJson())
                "page.login" -> act.evalInBrowser(
                    "(() => JSON.stringify(window.__CDP && __CDP.loginValues ? __CDP.loginValues() : {ok:false,error:'页面脚本不支持'}))()"
                ) { raw ->
                    val t = act.unquote(raw)
                    try { reply(JSONObject(t)) } catch (e: Exception) {
                        reply(JSONObject().put("ok", false).put("error", "页面没回可解析的登录信息"))
                    }
                }
                "page.vaultfill" -> {
                    // 按站点找密码库里的条目（未解锁会拒），然后填到页面登录框
                    val want = a.optString("site")
                    val listed = act.vault.list()
                    if (!listed.optBoolean("ok")) {
                        reply(listed)
                    } else {
                        var pick: JSONObject? = null
                        val l = listed.optJSONArray("list") ?: org.json.JSONArray()
                        for (i in 0 until l.length()) {
                            val it = l.optJSONObject(i) ?: continue
                            if (want.isEmpty() || it.optString("site").contains(want)) { pick = it; break }
                        }
                        if (pick == null) {
                            reply(JSONObject().put("ok", false).put("error", "密码库里没有匹配 $want 的条目"))
                        } else {
                            val full = act.vault.get(pick.optString("id"))
                            val item = full.optJSONObject("item")
                            val user = item?.optString("user") ?: ""
                            val pass = item?.optString("pass") ?: ""
                            act.evalInBrowser(
                                "(() => JSON.stringify(window.__CDP && __CDP.fillLogin ? __CDP.fillLogin(" +
                                    JSONObject.quote(user) + "," + JSONObject.quote(pass) +
                                    ") : {ok:false,error:'页面脚本不支持'}))()"
                            ) { raw ->
                                val t = act.unquote(raw)
                                try { reply(JSONObject(t)) } catch (e: Exception) {
                                    reply(JSONObject().put("ok", false).put("error", "填不进去"))
                                }
                            }
                        }
                    }
                }
                "page.grab" -> act.evalInBrowser(
                    act.grabText(a.optString("selector"), a.optInt("max", 8000)).optString("js")
                ) { raw ->
                    val txt = unquote(raw)
                    try {
                        reply(JSONObject(txt))
                    } catch (e: Exception) {
                        reply(JSONObject().put("ok", true).put("text", txt.take(60000)))
                    }
                }
                // ---- 多窗口（逻辑窗口：共用一个 WebView，省内存）----
                "win.list" -> reply(act.winListJson())
                // 打开工具栏那个 ▤ 窗口菜单（点图标做的事情；给验收/外部脚本也能"看一眼"）
                "win.menu" -> {
                    act.runOnUiThread { act.showWinMenu() }
                    reply(JSONObject().put("ok", true))
                }
                "win.recent" -> reply(act.winRecentJson())
                "win.reopen" -> reply(act.winReopen(a.optInt("i", 0)))
                "win.new" -> reply(act.winNew(a.optString("url")))
                "win.switch" -> reply(act.winSwitch(a.optInt("id", 0)))
                "win.close" -> reply(
                    act.winClose(if (a.has("id")) a.optInt("id") else -1)
                )
                // ---- 翻译（没配端点就如实报错，不假装翻出来）----
                "translate.state" -> reply(Translate.state(act.settings))
                "translate.do" -> {
                    val text = a.optString("text")
                    val from = a.optString("from", "auto")
                    val to = a.optString("to", "zh")
                    Thread { reply(Translate.doTranslate(act.settings, text, from, to)) }.start()
                }
                // ---- 配置隔离 ----
                "space.list" -> reply(
                    JSONObject().put("ok", true).put("current", Spaces.current)
                        .put("list", org.json.JSONArray(Spaces.list(act)))
                )
                "space.use" -> reply(JSONObject().put("ok", true).put("current", Spaces.use(a.optString("name"))))
                // ---- 后台前台化 ----
                // 后台播放（熄屏继续放）：前台服务换 mediaPlayback 类型 + 拿音频焦点 + 唤醒锁
                "keepalive.media" -> {
                    val on = when (a.optString("on")) {
                        "1", "true", "on" -> true
                        "0", "false", "off" -> false
                        else -> !KeepAliveService.mediaMode
                    }
                    val ctx = act
                    if (on) KeepAliveService.startMedia(ctx) else KeepAliveService.stopMedia(ctx)
                    reply(JSONObject().put("ok", true).put("media", on).put("state", KeepAliveService.state()))
                }
                "keepalive.start" -> {
                    KeepAliveService.start(act)
                    // 服务是异步起来的：等一小会儿再回包，否则调用方拿到的是"还没起"的旧状态（实测踩过）
                    Thread {
                        var waited = 0
                        while (!KeepAliveService.running && waited < 20) {
                            Thread.sleep(100)
                            waited++
                        }
                        reply(
                            JSONObject().put("ok", true).put("state", KeepAliveService.state())
                                .put("waitedMs", waited * 100)
                        )
                    }.start()
                }
                "keepalive.stop" -> {
                    KeepAliveService.stop(act)
                    Thread {
                        var waited = 0
                        while (KeepAliveService.running && waited < 20) {
                            Thread.sleep(100)
                            waited++
                        }
                        reply(JSONObject().put("ok", true).put("state", KeepAliveService.state()))
                    }.start()
                }
                "keepalive.state" -> reply(JSONObject().put("ok", true).put("state", KeepAliveService.state()))
                // 一键导出 / 导入
                "bundle.export" -> {
                    val r = act.exportBundle(a.optString("parts"))
                    reply(JSONObject().put("ok", r.first).put("msg", r.second))
                }
                "bundle.apply" -> reply(act.applyBundleFromExports(a.optString("name")))
                "bundle.list" -> reply(JSONObject().put("ok", true).put("list", act.listExports()))
                "bundle.import.pick" -> {
                    act.pickBundleToImport()
                    reply(JSONObject().put("ok", true).put("msg", "等你在系统文件选择器里挑一个 zip"))
                }
                // Cookie 详情：Android 只给 name=value，属性（httponly 等）拿不到，要如实说
                "cookie.detail" -> reply(act.cookieDetail(a.optString("domain")))
                // 网站安全的"基础屏蔽名单"（命中挂警告条，不阻断加载）
                "security.block.list" -> reply(
                    JSONObject().put("ok", true).put("list", org.json.JSONArray(act.blockHosts()))
                )
                "security.block.add" -> reply(JSONObject().put("ok", act.addBlockHost(a.optString("host"))))
                "security.block.remove" -> reply(JSONObject().put("ok", act.removeBlockHost(a.optString("host"))))
                "security.block.clear" -> reply(JSONObject().put("ok", true).put("removed", act.clearBlockHosts()))
                "security.page" -> reply(act.pageSecurityJson())
                // 省电面板
                // 第 6 条：aria2 试连通 / 提交 / 查进度
                "dl.aria2check" -> reply(Aria2.version(act.settings.aria2Rpc(), act.settings.aria2Token()))
                "dl.aria2" -> reply(
                    Aria2.submit(
                        act.settings.aria2Rpc(), act.settings.aria2Token(),
                        a.optString("url"), act.settings.aria2Dir(), a.optString("name")
                    )
                )
                "power.state" -> reply(JSONObject().put("ok", true).put("state", act.powerStateJson()))
                "power.set" -> reply(act.setPowerItem(a.optString("name"), a.optBoolean("on", false)))
                "power.save" -> reply(act.powerSaveAll())
                // 页内查询（find in page）：高亮 + 滚进视野，支持上一个/下一个
                "find.page" -> pageCmd(
                    JSONObject().put("op", "find").put("q", a.optString("q")).put("dir", a.optInt("dir", 1))
                ) { reply(it) }
                "find.clear" -> pageCmd(JSONObject().put("op", "findClear")) { reply(it) }
                // 静音此页（给"开一个窗口放视频、别吵"用）
                "tab.mute" -> {
                    val on = a.optBoolean("on", true)
                    pageCmd(JSONObject().put("op", "mute").put("on", on)) { r ->
                        act.setMuted(on)
                        log("静音此页：" + (if (on) "已静音" else "已恢复") + "（页面媒体 ${r.optInt("muted")} 个）")
                        reply(JSONObject().put("ok", r.optBoolean("ok", true)).put("on", on).put("page", r))
                    }
                }
                // 分享本页（系统分享面板；不依赖任何外部 App 也能用）
                "share.current" -> reply(act.shareCurrent(a.optString("text")))
                "picker.cancel" -> pageCmd(JSONObject().put("op", "pickCancel")) { reply(it) }
                "player.open" -> {
                    act.openPlayer(a.optString("url"), a.optString("name"))
                    reply(JSONObject().put("ok", true).put("url", a.optString("url")))
                }
                "cast.open" -> {
                    act.castToDevice(a.optString("url"), a.optString("title"))
                    reply(JSONObject().put("ok", true).put("note", "已在搜索设备，搜到会弹出选择框"))
                }
                // 给自动化/测试用的同步版：扫设备、直接对某台设备下发（不弹框）
                "cast.scan" -> {
                    val t = Thread {
                        val devs = try { act.cast.discover(a.optInt("ms", 2000)) } catch (e: Exception) { emptyList() }
                        val arr = org.json.JSONArray()
                        devs.forEach { d ->
                            arr.put(
                                JSONObject().put("name", d.name)
                                    .put("controlUrl", d.controlUrl).put("location", d.location)
                            )
                        }
                        reply(JSONObject().put("ok", true).put("devices", arr).put("count", arr.length()))
                    }
                    t.setName("cdp-cast-scan-api"); t.isDaemon = true; t.start()
                }
                "cast.play" -> {
                    val t = Thread {
                        try {
                            val d = Cast.Device(
                                a.optString("name", "设备"), a.optString("control"), a.optString("location")
                            )
                            val r = act.cast.play(d, a.optString("url"), a.optString("title"))
                            reply(JSONObject().put("ok", r.first).put("msg", r.second))
                        } catch (e: Exception) {
                            reply(JSONObject().put("ok", false).put("error", e.message ?: "失败"))
                        }
                    }
                    t.setName("cdp-cast-play-api"); t.isDaemon = true; t.start()
                }
                "testtv.start" -> reply(
                    JSONObject().put("ok", act.renderer.start(a.optInt("port", 1900))).put("tv", act.renderer.statsJson())
                )
                "testtv.stats" -> reply(JSONObject().put("ok", true).put("tv", act.renderer.statsJson()))
                "testtv.stop" -> {
                    act.renderer.stop()
                    reply(JSONObject().put("ok", true))
                }
                "incognito.set" -> {
                    act.setIncognito(a.optBoolean("on", !act.settings.incognito()))
                    reply(JSONObject().put("ok", true).put("incognito", act.settings.incognito()))
                }
                "cookie.all" -> reply(JSONObject().put("ok", true).put("list", act.cookiesAll()))
                "cookie.domain" -> reply(
                    JSONObject().put("ok", true).put("domain", a.optString("domain"))
                        .put("list", act.cookiesFor(a.optString("domain")))
                )
                "cookie.delete" -> reply(
                    JSONObject().put("ok", act.deleteCookie(a.optString("domain"), a.optString("name")))
                        .put("list", act.cookiesFor(a.optString("domain")))
                )
                "cookie.clear" -> reply(JSONObject().put("ok", act.clearCookies()))
                // 添加 / 改 cookie 的属性（用户要求）。写完读回来确认，没进就说清原因。
                "cookie.set" -> reply(
                    act.setCookie(
                        a.optString("domain"), a.optString("name"), a.optString("value"),
                        a.optString("path"), a.optLong("maxAge", 0L),
                        a.optBoolean("secure", false), a.optBoolean("httpOnly", false)
                    )
                )

                // 资源嗅探 / m3u8 下载
                "sniff.list" -> reply(
                    JSONObject().put("ok", true).put("list", act.sniff.list())
                        .put("count", act.sniff.count())
                        // 用户 N3：给人看的"下载目录"＝**系统下载目录**（下好的文件真在那儿）；
                        // 应用自己的暂存目录另给一个字段，不拿它糊弄人。
                        .put("dir", "Download/cdp")
                        .put("localDir", act.downloadDir())
                        .put("stats", act.sniff.stats())
                )
                "sniff.add" -> {
                    val u = a.optString("url")
                    val added = act.sniff.add(u, act.browser.url ?: "", true)
                    reply(JSONObject().put("ok", added).put("url", u).put("count", act.sniff.count()))
                }
                "sniff.scanPage" -> act.scanPageMedia { r -> reply(r) }
                // "指定哪一块是视频"（用户：有些页面自动解析不出 → 我要能指定位置；
                // 并进资源嗅探里，不再放"页面与元素"）
                "sniff.pick" -> {
                    val on = a.optInt("on", 1) != 0
                    startPick(on)
                    reply(JSONObject().put("ok", true).put("picking", pickMode))
                }
                // 外部/测试入口：直接给页面 CSS 坐标（真手指那条路走 MainActivity.dispatchTouchEvent）
                "sniff.pickAt" -> pickAt(a.optDouble("x", -1.0), a.optDouble("y", -1.0)) { r -> reply(r) }
                "sniff.stats" -> reply(JSONObject().put("ok", true).put("stats", act.sniff.stats()))
                "sniff.clear" -> {
                    act.sniff.clear()
                    reply(JSONObject().put("ok", true).put("count", 0))
                }
                "sniff.download" -> {
                    if (a.optBoolean("all")) {
                        val urls = ArrayList<String>()
                        val arr = act.sniff.list()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            if (o.optString("kind") == "m3u8") urls.add(o.optString("url"))
                        }
                        // 嗅探清单里每条都记了"来自哪个页面"——下载时拿它当 Referer（B 站这类 CDN 只认站点域名根）
                        try {
                            val first = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                                .firstOrNull { it.optString("kind") == "m3u8" }
                            act.setDownloadPageHint(first?.optString("page") ?: "")
                        } catch (e: Throwable) {}
                        if (urls.isEmpty()) {
                            reply(JSONObject().put("ok", false).put("error", "清单里没有 m3u8"))
                        } else {
                            urls.forEach { u -> act.downloadHls(u, "") }
                            reply(JSONObject().put("ok", true).put("started", urls.size))
                        }
                    } else {
                        val u = a.optString("url")
                        // 单条下载：把"这条资源来自哪个页面"传下去当 Referer
                        try {
                            val arr0 = act.sniff.list()
                            val hit = (0 until arr0.length()).mapNotNull { arr0.optJSONObject(it) }
                                .firstOrNull { it.optString("url") == a.optString("url") }
                            act.setDownloadPageHint(hit?.optString("page") ?: "")
                        } catch (e: Throwable) {}
                        if (u.isBlank()) reply(JSONObject().put("ok", false).put("error", "没给地址"))
                        else reply(
                            JSONObject().put("ok", true)
                                .put("record", act.downloadHls(u, a.optString("name")))
                        )
                    }
                }
                "history.clear" -> reply(JSONObject().put("ok", true).put("removed", act.nav.clearHistory()))
                // 历史：分页 / 按域名删 / 按时间删 / 导出
                "history.page" -> reply(
                    JSONObject().put("ok", true)
                        .put("list", act.nav.historyPage(a.optInt("offset", 0), a.optInt("limit", 50)))
                        .put("total", act.nav.historyJson().length())
                )
                "history.deleteDomain" -> reply(
                    JSONObject().put("ok", true).put("removed", act.nav.deleteHistoryByDomain(a.optString("domain")))
                        .put("total", act.nav.historyJson().length())
                )
                "history.deleteTime" -> reply(
                    JSONObject().put("ok", true).put(
                        "removed",
                        act.nav.deleteHistoryByTime(
                            a.optLong("ts", System.currentTimeMillis()), a.optBoolean("before", true)
                        )
                    ).put("total", act.nav.historyJson().length())
                )
                "export.history" -> {
                    val r = act.exportToDownloads("cdp-history.txt", act.nav.exportHistoryText())
                    reply(JSONObject().put("ok", r.first).put("msg", r.second))
                }
                "export.historyJson" -> {
                    val r = act.exportToDownloads("cdp-history.json", act.nav.exportHistoryJson(), "application/json")
                    reply(JSONObject().put("ok", r.first).put("msg", r.second))
                }
                "export.bookmarks" -> {
                    val r = act.exportToDownloads("cdp-bookmarks.txt", act.nav.exportBookmarksText())
                    reply(JSONObject().put("ok", r.first).put("msg", r.second))
                }
                "downloads.export" -> {
                    val f = act.downloadFile(a.optString("name"))
                    if (f == null) reply(JSONObject().put("ok", false).put("error", "没有这个文件"))
                    else {
                        val r = act.exportFileToDownloads(f.name, f)
                        reply(JSONObject().put("ok", r.first).put("msg", r.second))
                    }
                }
                "bookmark.list" -> reply(JSONObject().put("ok", true).put("list", act.nav.bookmarksJson()))
                "bookmark.folders" -> reply(JSONObject().put("ok", true).put("list", act.nav.foldersJson()))
                "bookmark.folder" -> reply(
                    JSONObject().put("ok", act.nav.setBookmarkFolder(a.optString("id"), a.optString("folder")))
                        .put("list", act.nav.bookmarksJson()).put("folders", act.nav.foldersJson())
                )
                "folder.rename" -> reply(
                    JSONObject().put("ok", true)
                        .put("changed", act.nav.renameFolder(a.optString("from"), a.optString("to")))
                        .put("folders", act.nav.foldersJson()).put("list", act.nav.bookmarksJson())
                )
                "folder.delete" -> reply(
                    JSONObject().put("ok", true)
                        .put("changed", act.nav.deleteFolder(a.optString("name")))
                        .put("folders", act.nav.foldersJson()).put("list", act.nav.bookmarksJson())
                )
                "bookmark.add" -> {
                    val st = act.stateCached()
                    val url = if (a.optString("url").isNotBlank()) a.optString("url") else st.optString("url")
                    val title = if (a.optString("title").isNotBlank()) a.optString("title") else st.optString("title")
                    val b = act.nav.addBookmark(url, title)
                    reply(JSONObject().put("ok", true).put("bookmark", b))
                }
                "bookmark.remove" -> reply(JSONObject().put("ok", act.nav.removeBookmark(a.optString("id"))))
                // 改书签属性（标题 / 地址 / 文件夹）：只改传进来的那几项，没传的保持原样
                "bookmark.update" -> {
                    val b = act.nav.updateBookmark(
                        a.optString("id"),
                        if (a.has("title")) a.optString("title") else null,
                        if (a.has("url")) a.optString("url") else null,
                        if (a.has("folder")) a.optString("folder") else null
                    )
                    reply(if (b == null) JSONObject().put("ok", false).put("error", "没有这个书签 id=" + a.optString("id"))
                    else JSONObject().put("ok", true).put("bookmark", b).put("list", act.nav.bookmarksJson()))
                }
                // 改历史一条的属性（标题）：按 ts + url 定位
                "history.update" -> {
                    val ts = a.optLong("ts", -1L)
                    val ok2 = act.nav.updateHistory(ts, a.optString("url"), a.optString("title"))
                    reply(JSONObject().put("ok", ok2)
                        .put("error", if (ok2) "" else "没有这条历史（ts/url 对不上）")
                        .put("list", act.nav.historyJson()))
                }
                "bookmark.toggle" -> {
                    val st = act.stateCached()
                    val url = st.optString("url")
                    var removed = JSONObject.NULL
                    val list = act.nav.bookmarksJson()
                    for (i in 0 until list.length()) {
                        if (list.getJSONObject(i).optString("url") == url) {
                            removed = list.getJSONObject(i)
                            act.nav.removeBookmark(removed.optString("id"))
                            break
                        }
                    }
                    if (removed === JSONObject.NULL) {
                        val b = act.nav.addBookmark(url, st.optString("title"))
                        reply(JSONObject().put("ok", true).put("added", true).put("bookmark", b))
                    } else {
                        reply(JSONObject().put("ok", true).put("added", false).put("removed", removed))
                    }
                }

                "scripts.list" -> reply(JSONObject().put("ok", true).put("list", store.listLight()).put("count", store.count()))
                // 把一条已保存的录制脚本**载入步骤编辑区**（录制板块的列表点一条就用它）
                "rec.load" -> {
                    val s = store.get(a.optString("id"))
                    val steps = s?.optJSONArray("steps")
                    if (s == null) {
                        reply(err("没有这个脚本"))
                    } else if (steps == null) {
                        reply(err("这条不是录制脚本（没有步骤）"))
                    } else {
                        val n = synchronized(recordedSteps) {
                            while (recordedSteps.length() > 0) recordedSteps.remove(0)
                            for (i in 0 until steps.length()) {
                                val o = steps.optJSONObject(i) ?: continue
                                recordedSteps.put(JSONObject(o.toString()))     // 深拷贝：改步骤不会动脚本库里的原件
                            }
                            recordedSteps.length()
                        }
                        recordName = s.optString("name")
                        reply(JSONObject().put("ok", true).put("name", s.optString("name")).put("steps", n))
                    }
                }
                // App 自己计时（阅读时长）的状态：验收读它，界面也能显示"正在计时哪一页、攒了多少"
                "timer.state" -> reply(JSONObject().put("ok", true).put("timer", act.pageTimer.snapshot()))
                // 只清"录制类"脚本（录制 / 队列）：用户要的"一键清空录制脚本"，
                // 不能用 scripts.clear（那是清全部，会连内置/粘贴的用户脚本一起删掉）。
                "scripts.clearRecordings" -> {
                    reply(JSONObject().put("ok", true).put("cleared", store.clearRecordings()))
                }
                "scripts.get" -> {
                    val s = store.get(a.optString("id"))
                    reply(if (s == null) err("没有这个脚本") else JSONObject().put("ok", true).put("script", s))
                }
                "scripts.save" -> {
                    val o = JSONObject()
                        .put("id", a.optString("id"))
                        .put("name", a.optString("name", "未命名"))
                        .put("kind", a.optString("kind", "userscript"))
                        .put("code", a.optString("code"))
                        .put("source", a.optString("source", "手动"))
                    if (a.has("match")) o.put("match", a.optString("match"))
                    if (a.has("steps")) o.put("steps", a.optJSONArray("steps"))
                    val saved = store.save(o)
                    act.onScriptsChanged()
                    reply(JSONObject().put("ok", true).put("id", saved.optString("id")))
                }
                "scripts.delete" -> {
                    val ok = store.delete(a.optString("id"))
                    act.onScriptsChanged()
                    reply(JSONObject().put("ok", ok))
                }
                "scripts.clear" -> {
                    val n = store.clearAll()
                    act.onScriptsChanged()
                    reply(JSONObject().put("ok", true).put("removed", n))
                }
                "scripts.scanDir" -> {
                    val added = store.scanImportDir()
                    act.onScriptsChanged()
                    reply(
                        JSONObject().put("ok", true).put("added", added)
                            .put("dir", store.importDir.absolutePath)
                    )
                }
                "scripts.importUrl" -> importUrl(a.optString("url"), reply)
                "scripts.eval" -> act.evalInBrowser(a.optString("code")) { raw ->
                    reply(JSONObject().put("ok", true).put("raw", unquote(raw)))
                }

                "rec.start" -> startRecording(a.optString("name", "录制脚本"), a.optString("mode", "element"), reply)
                "rec.stop" -> stopRecording(reply)
                // 控制台里选完录制方式就同步过来：这样 ☰ 菜单那条"开始监听"才知道该按哪种方式录
                // 小点：开关 / 点一下自己点 / 拖动改坐标 / 合并
                "rec.dots" -> {
                    dotsOn = a.optString("on", "1") != "0"
                    reply(JSONObject().put("ok", true).put("on", dotsOn).put("overlay", DotBus.isReady())
                        .put("count", DotBus.count()))
                    pushDots()
                }
                // 跳到系统「无障碍」设置页（小点覆盖层要在那里手动开一次）
                // 外部 CDP 调试口：把"真的能用来连"的信息给界面（pid 决定 socket 名）
                "api.cdp" -> reply(
                    JSONObject().put("ok", true)
                        .put("enabled", BuildConfig.DEBUG)
                        .put("pid", android.os.Process.myPid())
                        .put("hint", if (BuildConfig.DEBUG)
                            "adb forward tcp:9222 localabstract:webview_devtools_remote_" + android.os.Process.myPid()
                        else "只有 debug 构件才有调试口")
                )
                "sys.geom" -> reply(JSONObject().put("ok", true).put("geom", act.geomJson()))
                "rec.dotStatus" -> reply(
                    JSONObject().put("ok", true).put("on", dotsOn)
                        .put("overlay", false)                 // N1：不再用无障碍覆盖层，只画在页面里
                        .put("count", DotBus.count())
                        .put("accessibility", false)
                        .put("screen", DotBus.snapshot())      // 每个点在屏幕上的实际位置
                )
                // 改某一步的参数（坐标 / 等待毫秒 / 条件 / 连点次数 / 选择器…）——用户在列表或小点的小窗里编辑
                "rec.update" -> {
                    val i = a.optInt("i", -1)
                    val patchObj = a.optJSONObject("patch") ?: JSONObject()
                    val out = JSONObject()
                    synchronized(recordedSteps) {
                        if (i < 0 || i >= recordedSteps.length()) {
                            out.put("ok", false).put("error", "没有第 ${i + 1} 步")
                        } else {
                            val s = recordedSteps.getJSONObject(i)
                            for (k in patchObj.keys()) {
                                val v = patchObj.get(k)
                                if (k == "x" || k == "y" || k == "cx" || k == "cy") {
                                    // 坐标：同时更新 box 与 anchor（回放只认锚点）
                                    val box = s.optJSONObject("box") ?: JSONObject()
                                    val ax = if (k == "x" || k == "cx") v else box.opt("cx") ?: v
                                    val ay = if (k == "y" || k == "cy") v else box.opt("cy") ?: v
                                    box.put("cx", ax).put("cy", ay).put("x", ax).put("y", ay)
                                    s.put("box", box)
                                    val anc = s.optJSONObject("anchor") ?: JSONObject()
                                    anc.put("cx", ax).put("cy", ay)
                                    s.put("anchor", anc)
                                } else if (k == "selector") {
                                    s.put("selector", v)
                                    val t = s.optJSONObject("target") ?: JSONObject()
                                    t.put("selector", v)
                                    s.put("target", t)
                                } else {
                                    s.put(k, v)
                                }
                            }
                            // 坐标类步骤改成手输坐标时，把指纹清掉（它就是"点在这儿"，别再按元素找）
                            if (patchObj.has("x") || patchObj.has("y") || patchObj.has("cx") || patchObj.has("cy")) {
                                s.put("mode", "coord")
                                s.put("selector", "")
                                s.optJSONObject("target")?.put("selector", "")?.put("text", "")
                            }
                            out.put("ok", true).put("step", s).put("summary", stepSummary(s))
                        }
                    }
                    pushDots()
                    log("改步骤：第 ${i + 1} 步 → " + out.optString("summary"))
                    reply(out)
                }
                "rec.dotPlay" -> {
                    val n = a.optInt("n", 0)
                    reply(JSONObject().put("ok", true).put("n", n).put("queued", true))
                    runOneStep(n - 1)
                }
                "rec.dotMove" -> {
                    moveDot(a.optInt("n", 0), a.optDouble("x", -1.0), a.optDouble("y", -1.0))
                    reply(JSONObject().put("ok", true))
                }
                "rec.merge" -> mergeDot(a.optInt("index", -1), a.optString("mode", "prev"), reply)
                "rec.mode" -> {
                    recordMode = if (a.optString("mode") == "coord") "coord" else "element"
                    reply(JSONObject().put("ok", true).put("mode", recordMode))
                }
                "rec.state" -> reply(
                    JSONObject().put("ok", true).put("recording", recording).put("name", recordName)
                        .put("mode", recordMode)
                        .put("steps", recordedCount()).put("elapsedMs", recordElapsedMs())
                )
                "rec.live" -> reply(
                    JSONObject().put("ok", true).put("recording", recording).put("name", recordName)
                        .put("mode", recordMode)
                        .put("elapsedMs", recordElapsedMs()).put("steps", recordedStepsJson())
                )
                "rec.steps" -> pageCmd(JSONObject().put("op", "recordSteps")) { reply(it) }
                // 插入一步：**必须写原生那份**（rec.live / 小点 / 回放读的都是它）。
                // 以前这里 pageCmd 去问页面里的 agent —— 控制台开着空白起始页时根本没有 agent，
                // 于是「＋ 添加步骤」填完参数点保存，步骤哪里都没落，用户看到的就是"列表里什么都没有"。
                "rec.insert" -> {
                    val step = a.optJSONObject("step") ?: JSONObject()
                    val n = synchronized(recordedSteps) {
                        recordedSteps.put(JSONObject(step.toString()))
                        recordedSteps.length()
                    }
                    dotState.clear()
                    pushDots()          // 插了步骤就把小点重画一遍（不然覆盖层还是旧的）
                    // 注意：**不要再往页面 agent 转发一次** —— 页面那份会把同一步回灌到原生，
                    // 结果每条步骤都被加了两遍（实测）。
                    reply(JSONObject().put("ok", true).put("count", n))
                }
                "rec.clear" -> {
                    // 原生这份才是回放用的"权威"数据；以前只清了页面那份，所以界面上点了"清空步骤"没效果
                    val n = synchronized(recordedSteps) {
                        val c = recordedSteps.length()
                        while (recordedSteps.length() > 0) recordedSteps.remove(0)
                        c
                    }
                    recordT0 = if (recording) System.currentTimeMillis() else 0L
                    dotState.clear()
                    pushDots()          // 清空后必须重画：不然残留的小点会把后面的点击吃掉（实测）
                    pageCmd(JSONObject().put("op", "recordClear")) { r ->
                        reply(JSONObject().put("ok", true).put("clearedNative", n).put("page", r))
                    }
                }
                // 步骤编辑（用户反馈 #9/#10）：上移/下移/删除/在指定位置插入/单步执行
                "rec.move" -> {
                    val from = a.optInt("from", -1); val to = a.optInt("to", -1)
                    val r = synchronized(recordedSteps) {
                        if (from < 0 || to < 0 || from >= recordedSteps.length() || to >= recordedSteps.length())
                            JSONObject().put("ok", false).put("error", "位置不对")
                        else {
                            val item = recordedSteps.remove(from)
                            recordedSteps.put(to.coerceAtMost(recordedSteps.length()), JSONObject(item.toString()))
                            JSONObject().put("ok", true).put("count", recordedSteps.length())
                        }
                    }
                    dotState.clear(); pushDots()
                    reply(r)
                }
                "rec.del" -> {
                    val i = a.optInt("i", -1)
                    val r = synchronized(recordedSteps) {
                        if (i < 0 || i >= recordedSteps.length()) JSONObject().put("ok", false).put("error", "位置不对")
                        else {
                            recordedSteps.remove(i)
                            JSONObject().put("ok", true).put("count", recordedSteps.length())
                        }
                    }
                    dotState.clear(); pushDots()
                    reply(r)
                }
                "rec.insertAt" -> {
                    val i = a.optInt("i", -1)
                    val step = a.optJSONObject("step") ?: JSONObject()
                    val r = synchronized(recordedSteps) {
                        if (i < 0 || i > recordedSteps.length()) JSONObject().put("ok", false).put("error", "位置不对")
                        else {
                            recordedSteps.put(i, JSONObject(step.put("inserted", true).toString()))
                            JSONObject().put("ok", true).put("count", recordedSteps.length())
                        }
                    }
                    reply(r)
                }
                "rec.runOne" -> {
                    val i = a.optInt("i", -1)
                    val times = a.optInt("times", 1).coerceIn(1, 50)   // 用户要的「运行步骤也要有运行多少次」
                    val steps = recordedStepsJson()
                    if (i < 0 || i >= steps.length()) reply(JSONObject().put("ok", false).put("error", "位置不对"))
                    else {
                        act.closeConsole()          // 单步执行也退回网页，看得见效果（用户反馈 #6）
                        act.connStep("单步执行第 " + (i + 1) + " 步" + (if (times > 1) "（跑 $times 次）" else "") + "…")
                        val one = steps.optJSONObject(i) ?: JSONObject()
                        val runs = JSONArray()
                        val t0 = System.currentTimeMillis()
                        fun pass(n: Int) {
                            val tN = System.currentTimeMillis()
                            runStep(one) { r ->
                                runs.put(JSONObject().put("index", n)
                                    .put("ok", r.optBoolean("ok"))
                                    .put("ms", System.currentTimeMillis() - tN)
                                    .put("error", if (r.has("error")) r.opt("error") else JSONObject.NULL))
                                if (n + 1 < times) main.postDelayed({ pass(n + 1) }, one.optLong("pauseAfter", 350))
                                else {
                                    val okAll = (0 until runs.length()).all { runs.getJSONObject(it).optBoolean("ok") }
                                    val okN = (0 until runs.length()).count { runs.getJSONObject(it).optBoolean("ok") }
                                    act.connStep("第 " + (i + 1) + " 步：跑 " + runs.length() + " 次，成功 " + okN + " 次")
                                    val outOne = JSONObject().put("ok", okAll).put("index", i)
                                        .put("scriptId", "").put("name", "单步执行第 " + (i + 1) + " 步")
                                        .put("finishedAt", System.currentTimeMillis())
                                        .put("times", runs.length()).put("okTimes", okN)
                                        .put("ms", System.currentTimeMillis() - t0)
                                        .put("runs", runs).put("steps", JSONArray().put(r))
                                        .put("detail", r)
                                    lastRun = outOne          // 「上次运行」统一能查到（外部入口/验收都读它）
                                    reply(outOne)
                                }
                            }
                        }
                        pass(0)
                    }
                }
                "nav.open" -> {
                    val u = a.optString("url")
                    if (u.isBlank()) reply(err("url 为空")) else { act.navigateSmart(u); reply(JSONObject().put("ok", true).put("url", u)) }
                }

                "play.run" -> {
                    act.closeConsole()               // 反馈 #6：回放要退回网页、看得见过程
                    val times = a.optInt("times", 1).coerceIn(1, 50)
                    act.connStep("开始回放脚本" + (if (times > 1) "（跑 $times 次）" else "") + "…")
                    runRecording(a.optString("id"), times) { r ->
                        act.connStep("回放结束：" + (if (r.optBoolean("ok")) "成功" else "有失败步骤") +
                            "（" + r.optInt("ms", 0) + "ms）")
                        reply(r)
                    }
                }
                "rec.runAll" -> {
                    val arr = synchronized(recordedSteps) { JSONArray(recordedSteps.toString()) }
                    if (arr.length() == 0) {
                        reply(err("队列里还没有步骤"))
                    } else {
                        act.closeConsole()      // 反馈 #14：运行队列时退回网页，看得见它按次序在点
                        act.connStep("开始按队列执行（共 ${arr.length()} 步）…")
                        val tmp = store.save(
                            JSONObject().put("name", "当前队列（运行时自动覆盖）")
                                .put("kind", "queue").put("source", "控制台队列")
                                .put("startUrl", "").put("steps", arr)
                        )
                        act.onScriptsChanged()
                        runRecording(tmp.optString("id"), a.optInt("times", 1).coerceIn(1, 50)) { r ->
                            act.connStep("队列执行结束：" + (if (r.optBoolean("ok")) "全部成功" else "有失败步骤") +
                                "（" + r.optInt("ms", 0) + "ms）")
                            reply(r)
                        }
                    }
                }
                "play.last" -> reply(JSONObject().put("ok", true).put("run", lastRun ?: JSONObject.NULL))

                "http.start" -> reply(http.start(a.optBoolean("lan", false)))
                "http.stop" -> reply(http.stop())
                "http.status" -> reply(http.status())
                // 控制口安全：状态 / 换令牌 / 局域网敏感接口开关（敏感开关本身也受令牌与绑定范围约束）
                "security.http" -> reply(http.securityStatus())
                "security.sensitive" -> {
                    val on = when (a.optString("on")) {
                        "1", "true", "on" -> true
                        "0", "false", "off" -> false
                        else -> !http.securityStatus().optBoolean("sensitiveLan")
                    }
                    reply(http.setSensitiveLan(on))
                }
                "security.token.rotate" -> reply(http.rotateToken())
                // 令牌给"App 自己"看（界面里的「控制口安全」板块）——这条只走 App 内通道，
                // HTTP 通道上取令牌只能从本机拿（外部一律 403）
                "security.token" -> reply(
                    JSONObject().put("ok", true).put("token", http.tokenForLocalOnly())
                        .put("note", "只在本机 / 本 App 内可见；换一把旧的立刻失效")
                )

                "log.tail" -> {
                    val n = a.optInt("n", 50)
                    val arr = JSONArray()
                    synchronized(logs) { logs.toList().takeLast(n).forEach { arr.put(it) } }
                    reply(JSONObject().put("ok", true).put("lines", arr))
                }
                "events.tail" -> {
                    val n = a.optInt("n", 50)
                    val arr = JSONArray()
                    synchronized(events) { events.toList().takeLast(n).forEach { arr.put(it) } }
                    reply(JSONObject().put("ok", true).put("events", arr))
                }
                else -> reply(err("未知命令 $op"))
            }
        } catch (e: Exception) {
            reply(err("执行异常: ${e.message}"))
        }
    }

    private fun opt(a: JSONObject, op: String): JSONObject = JSONObject()
        .put("op", op)
        .put("selector", a.optString("selector"))
        .put("text", a.optString("text"))
        .put("x", a.optDouble("x", -1.0))
        .put("y", a.optDouble("y", -1.0))
        .put("anchorBottom", a.optDouble("anchorBottom", -1.0))

    private fun err(s: String): JSONObject = JSONObject().put("ok", false).put("error", s)

    // ---------------------------------------------------------------- 导入（URL）

    private fun importUrl(url: String, reply: (JSONObject) -> Unit) {
        if (!url.startsWith("http")) { reply(err("url 需要 http(s)")); return }
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 20000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) CDP")
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                val text = conn.inputStream.bufferedReader().readText()
                if (code !in 200..299 || text.isBlank()) {
                    main.post { reply(err("下载失败 HTTP $code")) }
                    return@Thread
                }
                val m = ScriptStore.parseMeta(text, "name") ?: url.substringAfterLast('/')
                val o = JSONObject()
                    .put("name", m)
                    .put("kind", "userscript")
                    .put("source", "URL 导入")
                    .put("code", text)
                    .put("match", ScriptStore.parseMeta(text, "match") ?: ScriptStore.parseMeta(text, "include") ?: "*")
                    .put("from", url)
                val saved = store.save(o)
                main.post {
                    act.onScriptsChanged()
                    reply(JSONObject().put("ok", true).put("id", saved.optString("id")).put("name", m).put("bytes", text.length))
                }
            } catch (e: Exception) {
                main.post { reply(err("下载异常: ${e.message}")) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- 录制

    private fun startRecording(name: String, mode: String, reply: (JSONObject) -> Unit) {
        val m = if (mode == "coord") "coord" else "element"
        pageCmd(
            JSONObject().put("op", "recordStart").put("name", name).put("mode", m)
                // 坐标录制由原生层统一抓（Activity.dispatchTouchEvent），页内那套不要再记一遍
                .put("nativeCoord", m == "coord")
        ) { r ->
            if (r.optBoolean("ok")) {
                recording = true; recordName = name; recordMode = m
                recordT0 = System.currentTimeMillis()
                // 反馈 #13：录制**不再清空**原来的队列（以前一开录就把队列抹了，用户看到"覆盖"）。
                // 记下本次从第几步开始，停的时候好报"本次新增 N 步"。
                recStartIndex = recordedCount()
                act.setRecordingUi(true)
                // 控制台是盖在页面上的浮层：不关掉的话，用户接着在"页面上"点，其实全点在控制台上
                // —— 现象就是"录制没效果"（尤其想点视频播放器的时候）。所以开始录制就把它收起来。
                act.closeConsole()
                pushDots()
                log("开始录制：$name（控制台已收起，请在页面上操作；要停止就点 ☰ → 打开控制台 → 停止并保存）")
            }
            reply(r)
        }
    }

    private fun stopRecording(reply: (JSONObject) -> Unit) {
        pageCmd(JSONObject().put("op", "recordStop")) { r ->
            recording = false
            act.setRecordingUi(false)
            recordT0 = 0L
            val pageSteps = r.optJSONArray("steps") ?: JSONArray()
            // 原生侧攒的那份更全（含 iframe 里的点击）；页面那份留作兜底
            val steps = if (recordedCount() > 0) recordedStepsJson() else pageSteps
            if (steps.length() == 0) {
                reply(JSONObject().put("ok", false).put("error", "没有录到任何步骤（录制期间需要在页面上操作）"))
                return@pageCmd
            }
            var inFrame = 0
            for (i in 0 until steps.length()) {
                if (steps.optJSONObject(i)?.optBoolean("inFrame") == true) inFrame++
            }
            // 用户反馈 #6：多次录制应该是**加到列表**，不是覆盖上一条
            var nm = recordName.ifEmpty { "录制脚本" }
            val existing = mutableSetOf<String>()
            run {
                val arr = store.all()
                for (i in 0 until arr.length()) existing.add(arr.optJSONObject(i)?.optString("name") ?: "")
            }
            if (existing.contains(nm)) {
                var k = 2
                while (existing.contains("$nm $k")) k++
                nm = "$nm $k"
            }
            val o = JSONObject()
                .put("name", nm)
                .put("kind", "recording")
                .put("source", "本机录制")
                .put("startUrl", r.optString("startUrl"))
                .put("steps", steps)
            val saved = store.save(o)
            act.onScriptsChanged()
            log(
                "录制结束：${steps.length()} 步" +
                    (if (inFrame > 0) "（其中 $inFrame 步来自 iframe/子 frame：回放时会按顶层页面的元素找，跨域 frame 里的那几步可能点不到）" else "") +
                    " → 已存为《${saved.optString("name")}》"
            )
            val total = recordedCount()
            pushDots()
            val added = if (recStartIndex in 1 until total) (total - recStartIndex) else steps.length()
            log("本次新增 $added 步；当前队列共 $total 步（没清空过——想从头来过用「清空步骤」）")
            reply(
                JSONObject().put("ok", true).put("id", saved.optString("id"))
                    .put("steps", steps.length()).put("inFrame", inFrame)
                    .put("added", added).put("queueTotal", total)
            )
        }
    }

    // ---------------------------------------------------------------- 回放

    /** 回放一个录制脚本：逐步定位 → 注入真实事件 → 校验真的生效 */
    /**
     * 回放一条脚本。`times` = 跑几次（1..50，超出就夹住）。
     *
     * 用户要的：「回放脚本应该有运行多少次的功能」。
     * 结构：最外层 onePass(n) 负责"第 n 次"；里面 step(i) 还是老的逐步执行。
     * 返回里既有**每一次**的结果（runs[]），也保留 `steps` 字段（＝最后一次的逐步结果）给旧界面用。
     */
    fun runRecording(id: String, times: Int, reply: (JSONObject) -> Unit) {
        val script = store.get(id)
        if (script == null) { reply(err("没有这个脚本 id=$id")); return }
        val steps = script.optJSONArray("steps") ?: JSONArray()
        if (steps.length() == 0) {
            // 错误要能指向：以前一律回「脚本没有步骤」，用户按了"运行脚本"只看到这句话，
            // 根本不知道跑错了对象（下拉框默认选中内置用户脚本 → 它是没有步骤的）。
            val nm = script.optString("name")
            val kind = script.optString("kind")
            reply(err(if (kind == "userscript")
                "「$nm」是用户脚本（不是录制脚本），没有可回放的步骤——回放只跑录制脚本；用户脚本请在「脚本」栏目按它自己的「运行」。"
            else "「$nm」是个空脚本（没有步骤），没法回放：先「开始监听」录一段，或按「▶ 跑当前队列」。"))
            return
        }
        val total = times.coerceIn(1, 50)          // 跑几次：夹在 1..50（防手滑填 999 把设备点疯）
        val tAll = System.currentTimeMillis()
        val runs = JSONArray()
        act.busy = true
        DotBus.setBusy(true)
        dotState.clear()                   // 上一轮的颜色别留到这一轮（每轮都从"待执行"开始）
        pushDots()

        fun finishAll() {
            act.busy = false
            DotBus.setBusy(false)
            replayProgress = null          // 顶部那行进度小字收掉（小点保留"已执行"的绿色，一眼看得出哪儿跑过）
            pushDots()
            val okAll = (0 until runs.length()).all { runs.getJSONObject(it).optBoolean("ok") }
            val last = if (runs.length() > 0) runs.getJSONObject(runs.length() - 1) else JSONObject()
            val out = JSONObject()
                .put("scriptId", id).put("name", script.optString("name"))
                .put("finishedAt", System.currentTimeMillis())
                .put("times", runs.length()).put("ms", System.currentTimeMillis() - tAll)
                .put("runs", runs)
                .put("steps", last.optJSONArray("steps") ?: JSONArray())   // 兼容旧界面：最后一次的逐步结果
                .put("ok", okAll)
            // 累计计数：这条脚本一共跑了多少次、成功多少次、每一步分别被触发过多少次
            val stats = script.optJSONObject("stats") ?: JSONObject()
            var runsN = stats.optInt("runs")
            var okN = stats.optInt("okRuns")
            val fires = stats.optJSONArray("stepFires") ?: JSONArray()
            for (k in 0 until runs.length()) {
                val one = runs.getJSONObject(k)
                runsN += 1
                if (one.optBoolean("ok")) okN += 1
                val ss = one.optJSONArray("steps") ?: JSONArray()
                for (i in 0 until ss.length()) {
                    val fired = ss.getJSONObject(i).optBoolean("ok")
                    val cur = if (i < fires.length()) fires.optInt(i) else 0
                    val next = cur + if (fired) 1 else 0
                    if (i < fires.length()) fires.put(i, next) else fires.put(next)
                }
                stats.put("lastMs", one.optLong("ms"))
                stats.put("lastAt", System.currentTimeMillis())
                stats.put("lastOk", one.optBoolean("ok"))
            }
            stats.put("runs", runsN)
            stats.put("okRuns", okN)
            stats.put("stepFires", fires)
            script.put("stats", stats)
            store.save(script)
            out.put("stats", stats)
            lastRun = out
            val firesTxt = (0 until (stats.optJSONArray("stepFires")?.length() ?: 0))
                .joinToString(" ") { "步骤${it + 1}=${stats.optJSONArray("stepFires")!!.optInt(it)}次" }
            val okRuns = (0 until runs.length()).count { runs.getJSONObject(it).optBoolean("ok") }
            log("回放结束：《${script.optString("name")}》跑 ${runs.length()} 次，成功 $okRuns 次" +
                "（总耗时 ${out.optLong("ms")}ms）；累计第 $runsN 次运行（$firesTxt）")
            reply(out)
        }

        /** 第 n 次（0 起）：跑完这一遍再决定要不要下一遍 */
        fun onePass(n: Int) {
            val t0 = System.currentTimeMillis()
            val results = JSONArray()
            fun end() {
                val okOne = (0 until results.length()).all { results.getJSONObject(it).optBoolean("ok") }
                runs.put(JSONObject().put("index", n).put("ok", okOne)
                    .put("ms", System.currentTimeMillis() - t0).put("steps", results))
                if (n + 1 < total) {
                    act.connStep("第 ${n + 2}/$total 次回放…")
                    main.postDelayed({ onePass(n + 1) }, 400)
                } else finishAll()
            }
            fun step(i: Int) {
                if (i >= steps.length()) { end(); return }
                val cur = steps.getJSONObject(i)
                // 用户要求：回放时要看得见"正在执行、点在哪儿、执行到哪一步" ——
                // ① 页面上小点变色（当前=橙 / 已过=绿）② 页面顶部一行小字 ③ 页面下那条回执（控制台收起也看得见）
                dotState[i] = "current"
                replayProgress = JSONObject().put("i", i + 1).put("n", steps.length()).put("label", stepLabel(cur))
                pushDots()
                act.connStep("回放 第 ${i + 1}/${steps.length()} 步 · " + stepLabel(cur))
                runStep(cur) { r ->
                    r.put("index", i)
                    r.put("type", cur.optString("t"))
                    results.put(r)
                    dotState[i] = if (r.optBoolean("ok")) "done" else "idle"
                    pushDots()
                    log("回放第 ${i + 1}/${steps.length()} 步 " + stepLabel(cur) +
                        " → " + (if (r.optBoolean("ok")) "成功" else "失败:" + r.optString("error")))
                    main.postDelayed({ step(i + 1) }, cur.optLong("pauseAfter", 350))
                }
            }
            if (total > 1) act.connStep("第 ${n + 1}/$total 次回放（每次 ${steps.length()} 步）…")
            step(0)
        }

        log("开始回放《${script.optString("name")}》共 ${steps.length()} 步" + (if (total > 1) "，跑 $total 次" else ""))
        onePass(0)
    }

    // ---------------------------------------------------------------- 单次点击（控制台/外部接口按需点一下）

    /** 一次性点击：给选择器/文字/坐标/「距底部多少 px」四种入口，都走同一条「真实事件 + 兜底」的路径 */
    private fun clickNow(a: JSONObject, reply: (JSONObject) -> Unit) {
        val out = JSONObject()
        val x = a.optDouble("x", -1.0)
        val y = a.optDouble("y", -1.0)
        val anchorBottom = a.optDouble("anchorBottom", -1.0)
        val selector = a.optString("selector").trim()
        val text = a.optString("text").trim()

        if (x >= 0 && y >= 0) {
            doTap(JSONObject(), JSONObject().put("strategy", "coord"), x, y, out, reply)
            return
        }
        if (anchorBottom >= 0) {
            val anchor = JSONObject().put("mode", "bottom").put("bottomPx", anchorBottom).put("leftPx", JSONObject.NULL)
            pageCmd(JSONObject().put("op", "pointFromAnchor").put("anchor", anchor)) { p ->
                if (!p.optBoolean("ok")) { reply(out.put("ok", false).put("error", p.optString("error"))); return@pageCmd }
                doTap(JSONObject(), JSONObject().put("strategy", "anchor:bottom").put("el", p.optJSONObject("el")),
                    p.optDouble("x"), p.optDouble("y"), out, reply)
            }
            return
        }
        if (selector.isEmpty() && text.isEmpty()) {
            reply(out.put("ok", false).put("error", "要给我选择器、文字、坐标或距底部像素之一"))
            return
        }
        val id = if (selector.startsWith("#")) selector.substring(1).substringBefore(' ').substringBefore('>') else ""
        val step = JSONObject().put(
            "target", JSONObject().put("selector", selector).put("text", text).put("id", id)
                .put("tag", "").put("attrs", JSONObject())
        )
        pageCmd(JSONObject().put("op", "locate").put("step", step)) { loc ->
            out.put("locate", loc)
            if (!loc.optBoolean("ok")) { reply(out.put("ok", false).put("error", "没定位到目标")); return@pageCmd }
            // 反馈 #6「回放要做演示」：先把目标元素高亮一下再点，用户看得见这一步点了哪儿
            try {
                step.optJSONObject("target")?.optString("selector")?.takeIf { it.isNotBlank() }?.let { sel ->
                    pageCmd(JSONObject().put("op", "highlight").put("selector", sel)) { }
                }
            } catch (_: Exception) {
            }
            val box = loc.optJSONObject("el")?.optJSONObject("box")
            if (!loc.optBoolean("inViewport")) {
                // 目标在屏幕外：先滚进视野再取新坐标（否则注入点会被边界挡掉，只能退到 JS 兜底）
                pageCmd(JSONObject().put("op", "scrollIntoView").put("step", step)) { s ->
                    val b2 = s.optJSONObject("box") ?: box
                    out.put("scrolled", true)
                    doTap(step, loc, b2?.optDouble("cx") ?: -1.0, b2?.optDouble("cy") ?: -1.0, out, reply)
                }
            } else {
                doTap(step, loc, box?.optDouble("cx") ?: -1.0, box?.optDouble("cy") ?: -1.0, out, reply)
            }
        }
    }

    /** 单个步骤：定位 → 必要时滚进视野 → 注入触摸/鼠标 → 用「页面自己记的点击计数」验证真的生效 */
    private fun runStep(step: JSONObject, done: (JSONObject) -> Unit) {
        val t = step.optString("t")
        val out = JSONObject()
        when (t) {
            "click" -> clickStep(step, out, done)
            "goto" -> {
                act.loadUrl(step.optString("url"))
                out.put("ok", true).put("via", "navigate").put("url", step.optString("url"))
                done(out)
            }
            "wait" -> {
                val ms = step.optLong("ms", 1000)
                out.put("ok", true).put("via", "wait").put("ms", ms)
                main.postDelayed({ done(out) }, ms)
            }
            "waitFor" -> waitForStep(step, out, done)
            "playVideo" -> playVideoStep(step, out, done)
            "clickGroup" -> {
                // 点组（合并出来的"一步多个点"）：按顺序把每个点各点 reps 次
                val pts = step.optJSONArray("points") ?: JSONArray()
                val got = JSONArray()
                fun next(i: Int) {
                    if (i >= pts.length()) {
                        out.put("ok", got.length() == pts.length()).put("via", "click-group")
                            .put("points", got)
                        done(out)
                        return
                    }
                    val p = pts.optJSONObject(i) ?: return next(i + 1)
                    val reps = p.optInt("reps", 1).coerceIn(1, 50)
                    val anchor = p.optJSONObject("anchor") ?: step.optJSONObject("anchor") ?: JSONObject()
                    val cx = p.optDouble("cx"); val cy = p.optDouble("cy")
                    val sub = JSONObject()
                        .put("t", "click").put("mode", "coord").put("selector", "")
                        .put("url", step.optString("url")).put("title", step.optString("title"))
                        .put("box", JSONObject().put("cx", cx).put("cy", cy).put("x", cx).put("y", cy).put("w", 1).put("h", 1))
                        .put("anchor", anchor)
                    fun rep(k: Int) {
                        if (k >= reps) { next(i + 1); return }
                        runStep(sub) { r ->
                            got.put(r.put("point", i).put("rep", k))
                            main.postDelayed({ rep(k + 1) }, 180)
                        }
                    }
                    rep(0)
                }
                next(0)
            }
            "if" -> ifStep(step, out, done)
            "input" -> {
                val sel = step.optString("selector")
                val val_ = step.optString("value")
                pageCmd(JSONObject().put("op", "setValue").put("selector", sel).put("text", val_)) { r ->
                    out.put("ok", r.optBoolean("ok")).put("via", "js-setValue").put("detail", r)
                    done(out)
                }
            }
            else -> done(out.put("ok", false).put("error", "未知步骤类型 $t"))
        }
    }

    /**
     * 等待条件成立（视频播完 / 元素出现或消失 / 文字出现 / URL 变化 / 固定时间）。
     * 轮询而不是"睡一觉"：每一步都能报「等了多久、轮询几次、当时页面什么状态」，
     * 条件不成立就是超时失败，不会被当成成功。
     */
    private fun waitForStep(step: JSONObject, out: JSONObject, done: (JSONObject) -> Unit) {
        val cond = step.optJSONObject("cond") ?: JSONObject().put("type", "always")
        val timeout = step.optLong("timeoutMs", 20000)
        val interval = step.optLong("intervalMs", 300)
        val t0 = System.currentTimeMillis()
        var polls = 0
        val lastDetail = JSONObject()

        fun poll() {
            polls++
            pageCmd(JSONObject().put("op", "check").put("cond", cond)) { c ->
                if (c.has("detail")) lastDetail.put("detail", c.opt("detail"))
                val satisfied = c.optBoolean("ok") && c.optBoolean("result")
                val spent = System.currentTimeMillis() - t0
                if (satisfied) {
                    out.put("ok", true).put("via", "waitFor").put("cond", cond.optString("type"))
                        .put("waitedMs", spent).put("polls", polls).put("detail", c.opt("detail"))
                    done(out)
                } else if (spent >= timeout) {
                    out.put("ok", false).put("via", "waitFor").put("cond", cond.optString("type"))
                        .put("waitedMs", spent).put("polls", polls).put("detail", c.opt("detail"))
                        .put("error", "等条件「${cond.optString("type")}」超时（${timeout}ms）：${c.optString("error")}")
                    done(out)
                } else {
                    main.postDelayed({ poll() }, interval)
                }
            }
        }
        poll()
    }

    /** 点播放：先真实触摸点视频中心（很多播放器只认用户手势），再让页面调 play() 兜底 */
    private fun playVideoStep(step: JSONObject, out: JSONObject, done: (JSONObject) -> Unit) {
        val sel = step.optString("selector")
        pageCmd(JSONObject().put("op", "video").put("action", "play").put("selector", sel).put("muted", step.optBoolean("muted", true))) { v ->
            out.put("video", v)
            if (!v.optBoolean("ok")) { done(out.put("ok", false).put("error", v.optString("error"))); return@pageCmd }
            val rect = v.optJSONObject("rect")
            if (rect != null && !step.optBoolean("noTap")) {
                val ok = act.injectTap(rect.optDouble("cx"), rect.optDouble("cy"))
                out.put("tapped", ok).put("tapPoint", JSONObject().put("x", rect.optDouble("cx")).put("y", rect.optDouble("cy")))
            }
            main.postDelayed({
                pageCmd(JSONObject().put("op", "videoState").put("selector", sel)) { s ->
                    out.put("state", s.opt("videos"))
                    out.put("ok", true).put("via", "playVideo")
                    done(out)
                }
            }, 500)
        }
    }

    /** 顺序执行一组步骤（「如果…就…」的分支复用同一套执行器） */
    private fun runBranch(arr: JSONArray, out: JSONArray, i: Int, done: (Boolean) -> Unit) {
        if (i >= arr.length()) { done(true); return }
        val st = arr.getJSONObject(i)
        runStep(st) { r ->
            r.put("branchStep", i)
            out.put(r)
            main.postDelayed({ runBranch(arr, out, i + 1, done) }, st.optLong("pauseAfter", 300))
        }
    }

    /** 条件分支：先判断，再跑对应分支，分支里的每一步都带回来 */
    private fun ifStep(step: JSONObject, out: JSONObject, done: (JSONObject) -> Unit) {
        val cond = step.optJSONObject("cond") ?: JSONObject().put("type", "always")
        pageCmd(JSONObject().put("op", "check").put("cond", cond)) { c ->
            val result = c.optBoolean("ok") && c.optBoolean("result")
            val branch = (if (result) step.optJSONArray("then") else step.optJSONArray("else")) ?: JSONArray()
            val sub = JSONArray()
            out.put("via", "if").put("cond", cond.optString("type")).put("condResult", result)
                .put("branch", if (result) "then" else "else").put("condDetail", c.opt("detail"))
            runBranch(branch, sub, 0) { _ ->
                out.put("sub", sub).put("ok", true)
                done(out)
            }
        }
    }

    private fun clickStep(step: JSONObject, out: JSONObject, done: (JSONObject) -> Unit) {
        // 定位（DOM 指纹优先，位置锚点兜底并做相似度打分）——由页面内 agent 给出结果与所用策略
        pageCmd(JSONObject().put("op", "locate").put("step", step)) { loc ->
            out.put("locate", loc)
            if (!loc.optBoolean("ok")) {
                // 找不到就如实报失败：绝不能「反正有个位置，就点一下试试」——那会点到别的元素上，
                // 而页面点击计数照样会涨，看起来像成功（本项目实测踩过这个假成功）
                out.put("ok", false)
                out.put("error", "没定位到目标：" + loc.optString("error", "找不到"))
                done(out)
                return@pageCmd
            }
            val box = loc.optJSONObject("el")?.optJSONObject("box")
            var cx = box?.optDouble("cx") ?: -1.0
            var cy = box?.optDouble("cy") ?: -1.0
            if (!loc.optBoolean("inViewport")) {
                // ② 滚进视野后重新取坐标
                pageCmd(JSONObject().put("op", "scrollIntoView").put("step", step)) { s ->
                    val b2 = s.optJSONObject("box")
                    if (b2 != null) { cx = b2.optDouble("cx"); cy = b2.optDouble("cy") }
                    doTap(step, loc, cx, cy, out, done)
                }
            } else {
                doTap(step, loc, cx, cy, out, done)
            }
        }
    }

    /** 注入顺序：真实触摸事件（isTrusted） → JS 合成鼠标事件 → 元素自身 click() → 逐级往上找可点祖先 */
    private fun doTap(step: JSONObject, loc: JSONObject, cssX: Double, cssY: Double, out: JSONObject, done: (JSONObject) -> Unit) {
        if (cssX < 0 || cssY < 0) { done(out.put("ok", false).put("error", "坐标无效")); return }
        // 注入前先让页面把目标滚进视野并给出坐标（只有这一步会滚动页面；定位/诊断/录制都不动页面）
        val sel = loc.optJSONObject("el")?.optString("selector") ?: ""
        pageCmd(JSONObject().put("op", "pointFor").put("step", step).put("sel", sel)) { pf ->
            val x = if (pf.optBoolean("ok")) pf.optDouble("x", cssX) else cssX
            val y = if (pf.optBoolean("ok")) pf.optDouble("y", cssY) else cssY
            if (pf.optBoolean("scrolled")) out.put("scrolledIntoView", true).put("scrollY", pf.optInt("scrollY"))
            tapNow(step, loc, x, y, out, done)
        }
    }

    private fun tapNow(step: JSONObject, loc: JSONObject, cssX: Double, cssY: Double, out: JSONObject, done: (JSONObject) -> Unit) {
        pageCmd(JSONObject().put("op", "clickStats")) { before ->
            val n0 = before.optInt("clicks", 0)
            val ok = act.injectTap(cssX, cssY)
            out.put("tapPoint", JSONObject().put("x", cssX).put("y", cssY)).put("tapInjected", ok)
            main.postDelayed({
                pageCmd(JSONObject().put("op", "clickStats")) { after ->
                    val n1 = after.optInt("clicks", 0)
                    out.put("clicksBefore", n0).put("clicksAfter", n1)
                    if (n1 > n0) {
                        out.put("ok", true).put("via", "native-touch").put("trusted", after.optBoolean("lastTrusted"))
                        out.put("target", after.optJSONObject("last") ?: JSONObject.NULL)
                        done(out)
                    } else if (!ok) {
                        // 原生触摸被丢掉了（目标仍在视口外之类）：再要一次坐标，重点一次。
                        // 以前这里直接退化成 JS 合成点击 —— 看着成功，但 isTrusted=false。
                        log("原生触摸没生效，再要一次坐标后重点")
                        pageCmd(JSONObject().put("op", "pointFor").put("step", step).put("sel", loc.optJSONObject("el")?.optString("selector") ?: "")) { pf2 ->
                            if (!pf2.optBoolean("ok")) {
                                out.put("ok", false).put("error", "重新定位失败，没法真点")
                                done(out)
                                return@pageCmd
                            }
                            val ok2 = act.injectTap(pf2.optDouble("x"), pf2.optDouble("y"))
                            out.put("tapRetry", true)
                                .put("tapPoint2", JSONObject().put("x", pf2.optDouble("x")).put("y", pf2.optDouble("y")))
                            main.postDelayed({
                                pageCmd(JSONObject().put("op", "clickStats")) { after2 ->
                                    val n2 = after2.optInt("clicks", 0)
                                    if (ok2 && n2 > n0) {
                                        out.put("ok", true).put("via", "native-touch-retry")
                                            .put("trusted", after2.optBoolean("lastTrusted"))
                                            .put("clicksAfter", n2)
                                        done(out)
                                    } else {
                                        jsFallback(step, loc, out, done)
                                    }
                                }
                            }, 350)
                        }
                    } else {
                        jsFallback(step, loc, out, done)
                    }
                }
            }, 300)
        }
    }

    /** JS 合成兜底（结果里如实标 trusted=false，别把它当真实触摸） */
    private fun jsFallback(step: JSONObject, loc: JSONObject, out: JSONObject, done: (JSONObject) -> Unit) {
        pageCmd(JSONObject().put("op", "forceClick").put("step", step).put("sel", loc.optJSONObject("el")?.optString("selector"))) { f ->
            out.put("fallback", f)
            if (f.optBoolean("ok")) {
                out.put("ok", true).put("via", "js:" + f.optString("via")).put("trusted", false)
            } else {
                out.put("ok", false).put("error", "真实触摸与 JS 兜底都没能让目标产生反应")
            }
            done(out)
        }
    }
}
