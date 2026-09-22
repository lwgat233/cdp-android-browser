package dev.cdp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 广告/跟踪拦截：域名后缀规则 + 关键字规则，用在 shouldInterceptRequest 里直接掐掉。
 *
 * 说明（老实说清能力边界）：
 *  - 这是**基于规则的拦截**（黑名单后缀 + 关键字），不是"智能"识别；拦不到的广告很正常。
 *  - 拦下来的请求返回 204 空响应，不消耗流量；计数与最近拦下的地址给控制台看。
 *  - 默认规则是内置的一份常见广告/统计域名清单，用户可增删、可一键关。
 */
class AdBlock(private val ctx: Context) {

    private val lock = Any()
    private val file = File(ctx.filesDir, "adblock.json")

    private var enabled = true
    private var rules: MutableList<String> = mutableListOf()
    private var blockKeywords: MutableList<String> = mutableListOf("adserver", "/advert", "doubleclick", "umeng", "cnzz")

    // ---- "屏蔽不渲染"（清单第 3 条的重点）：**不阻断请求**，只把广告元素藏起来，
    //      免得某些站的 JS 探测到资源被拦后不给内容。mode: block（老行为，回空）/ hide（只隐藏）/ both
    private var mode = "both"
    private var cosmetic: MutableList<String> = mutableListOf()

    // 站点警告名单（原来是 settings 里的一条逗号串 + 另一套匹配代码；本轮并进同一份规则库，
    // 于是"拦截"和"警告"共用一个存储、一个匹配器、一套 op —— 不再两份逻辑各写一遍）
    private var warnHosts: MutableList<String> = mutableListOf()

    @Volatile
    private var blockedCount = 0

    private val recent = ArrayDeque<String>()

    init {
        load()
    }

    private fun load() {
        synchronized(lock) {
            if (file.exists()) {
                try {
                    val o = JSONObject(file.readText())
                    enabled = o.optBoolean("enabled", true)
                    rules = jsonToList(o.optJSONArray("rules"))
                    blockKeywords = jsonToList(o.optJSONArray("keywords"))
                    mode = o.optString("mode").ifBlank { "both" }
                    cosmetic = jsonToList(o.optJSONArray("cosmetic"))
                    warnHosts = jsonToList(o.optJSONArray("warn"))
                } catch (_: Exception) {
                }
            }
            if (rules.isEmpty()) rules = defaultRules().toMutableList()
            if (cosmetic.isEmpty()) cosmetic = defaultCosmetic().toMutableList()
        }
    }

    private fun jsonToList(a: JSONArray?): MutableList<String> {
        val out = mutableListOf<String>()
        if (a != null) for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    private fun persist() {
        synchronized(lock) {
            try {
                file.writeText(
                    JSONObject()
                        .put("enabled", enabled)
                        .put("rules", JSONArray(rules.toList()))
                        .put("keywords", JSONArray(blockKeywords.toList()))
                        .put("mode", mode)
                        .put("cosmetic", JSONArray(cosmetic.toList()))
                        .put("warn", JSONArray(warnHosts.toList()))
                        .toString()
                )
            } catch (_: Exception) {
            }
        }
    }

    /** "屏蔽不渲染"的默认选择器（保守：只藏典型的广告位/浮层，不乱藏正文） */
    private fun defaultCosmetic(): List<String> = listOf(
        "ins.adsbygoogle",
        "[id^=div-gpt-ad]",
        "[id^=google_ads]",
        "[class*=\"ad-banner\"]",
        "[class*=\"adsbygoogle\"]",
        "[class*=\"advert\"]",
        "[class~=\"ad\"]",
        "[id*=\"ad-container\"]",
        "[class*=\"sponsor\"]"
    )

    /** 内置的常见广告/统计域名（保守清单：只放确实是广告/统计服务的域名） */
    private fun defaultRules(): List<String> = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "ads.yahoo.com", "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "scorecardresearch.com", "quantserve.com", "moatads.com", "adcolony.com", "applovin.com",
        "unityads.unity3d.com", "bytedance.com/pangle", "pangolin-sdk-toutiao.com", "gromore",
        "ad.qq.com", "adsmind.gdtimg.com", "gdt.qq.com", "e.qq.com", "pingjs.qq.com",
        "hm.baidu.com", "pos.baidu.com", "cpro.baidu.com", "union.baidu.com", "mobads.baidu.com",
        "admaster.com.cn", "miaozhen.com", "talkingdata.com", "umeng.com", "umengcloud.com",
        "cnzz.com", "51.la", "vamaker.com", "gtags.net", "adservice.google.cn",
        "tanx.com", "alimama.cn", "mmstat.com", "simba.taobao.com"
    )

    fun isEnabled(): Boolean = enabled

    fun setEnabled(on: Boolean) {
        enabled = on
        persist()
    }

    fun ruleList(): List<String> = synchronized(lock) { rules.toList() }

    fun addRule(r: String): Boolean {
        val v = r.trim().lowercase()
        if (v.isEmpty()) return false
        return synchronized(lock) {
            if (rules.contains(v)) false else {
                rules.add(v); persist(); true
            }
        }
    }

    fun removeRule(r: String): Boolean = synchronized(lock) {
        val ok = rules.remove(r.trim().lowercase()); if (ok) persist(); ok
    }

    fun clearRules(): Int = synchronized(lock) {
        val n = rules.size; rules.clear(); persist(); n
    }

    fun resetDefault(): Int = synchronized(lock) {
        rules = defaultRules().toMutableList(); persist(); rules.size
    }

    fun mode(): String = mode
    fun setMode(m: String): String {
        mode = if (m in listOf("block", "hide", "both")) m else "both"
        persist(); return mode
    }
    fun cosmeticList(): List<String> = synchronized(lock) { cosmetic.toList() }
    fun addCosmetic(sel: String): Boolean = synchronized(lock) {
        val s2 = sel.trim()
        if (s2.isEmpty() || cosmetic.contains(s2)) return false
        cosmetic.add(s2); persist(); true
    }
    fun removeCosmetic(sel: String): Boolean = synchronized(lock) {
        val hit = cosmetic.remove(sel.trim()); if (hit) persist(); hit
    }
    fun resetCosmetic(): Int = synchronized(lock) {
        cosmetic = defaultCosmetic().toMutableList(); persist(); cosmetic.size
    }

    // ---- 站点警告名单：命中只挂警告条（不阻断加载）。跟广告规则共用同一份文件。 ----
    fun warnList(): List<String> = synchronized(lock) { warnHosts.toList() }

    fun addWarn(host: String): Boolean {
        val v = host.trim().lowercase()
        if (v.isEmpty()) return false
        return synchronized(lock) {
            if (warnHosts.contains(v)) false else { warnHosts.add(v); persist(); true }
        }
    }

    fun removeWarn(host: String): Boolean = synchronized(lock) {
        val ok = warnHosts.remove(host.trim().lowercase()); if (ok) persist(); ok
    }

    fun clearWarn(): Int = synchronized(lock) {
        val n = warnHosts.size; warnHosts.clear(); persist(); n
    }

    /** 这个地址命中警告名单吗？命中返回命中的那条（给警告条和日志看） */
    fun matchWarn(url: String): String? {
        val u = url.trim().lowercase()
        if (u.isEmpty()) return null
        val host = try {
            android.net.Uri.parse(if (u.startsWith("http")) u else "https://$u").host ?: ""
        } catch (_: Exception) {
            ""
        }
        if (host.isEmpty()) return null
        return synchronized(lock) { warnHosts.toList() }.firstOrNull { host == it || host.endsWith(".$it") }
    }

    /** 这个地址要不要"真阻断"？mode=hide 时一律不阻断（只靠隐藏） */
    fun shouldBlock(url: String): String? {
        if (mode == "hide") return null
        return match(url)
    }

    fun stats(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("mode", mode)
        .put("cosmetic", JSONArray(cosmeticList()))
        .put("blockedNote", if (mode == "hide") "当前是「只隐藏不阻断」：命中也不回空响应，靠注入 CSS 藏元素" else "命中就回空响应（204 语义）")
        .put("rules", rules.size)
        .put("keywords", blockKeywords.size)
        .put("blocked", blockedCount)
        .put("recent", JSONArray(synchronized(lock) { recent.toList() }))
        .put("ruleList", JSONArray(ruleList()))
        .put("warn", JSONArray(warnList()))

    /** 这个地址要不要拦？命中返回命中的规则（给日志看） */
    fun match(url: String): String? {
        if (!enabled) return null
        val u = url.lowercase()
        if (!u.startsWith("http")) return null
        val host = try {
            android.net.Uri.parse(u).host ?: ""
        } catch (_: Exception) {
            ""
        }
        for (r in synchronized(lock) { rules.toList() }) {
            if (r.isEmpty()) continue
            if (r.contains('/')) {
                if (u.contains(r)) return r
            } else if (host == r || host.endsWith(".$r")) {
                return r
            }
        }
        for (k in synchronized(lock) { blockKeywords.toList() }) {
            if (k.isNotEmpty() && u.contains(k)) return "关键字:$k"
        }
        return null
    }

    fun noteBlocked(url: String, rule: String) {
        blockedCount++
        synchronized(lock) {
            recent.addFirst("[$rule] ${url.take(120)}")
            while (recent.size > 30) recent.removeLast()
        }
    }
}
