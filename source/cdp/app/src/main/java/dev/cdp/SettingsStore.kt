package dev.cdp

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 设置（浏览器模式 / 搜索引擎 / 隐身 / 代理 / 直连白名单）。
 *
 * 落盘在 files/settings.json，重启后还在。字段都用字符串存，读的时候给默认值 ——
 * 这样以后加字段不用做迁移。
 */
class SettingsStore(private val ctx: Context) {

    private val ctxRef = ctx
    private var file: File = Spaces.file(ctx, "settings.json")
    private val lock = Any()
    private var data = JSONObject()

    init {
        load()
        // 配置隔离：切空间就换文件重新加载（清单第 12 条）
        Spaces.onSwitch {
            file = Spaces.file(ctxRef, "settings.json")
            load()
        }
    }

    private fun load() {
        synchronized(lock) {
            data = try {
                if (file.exists()) JSONObject(file.readText()) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
        }
    }

    private fun persist() {
        try {
            file.writeText(data.toString())
        } catch (_: Exception) {
        }
    }

    fun get(key: String, def: String = ""): String = synchronized(lock) { data.optString(key, def) }

    fun getBool(key: String, def: Boolean = false): Boolean = synchronized(lock) { data.optBoolean(key, def) }

    /** 下载方式：app（应用内自己下，默认；log.md 第 6 条要求）| system（系统下载器，test.md #20 要的选项） */
    fun downloadViaSystem(): Boolean = get("downloadVia", "app") == "system"

    /** 下载方式：app（自己下）/ system（系统下载器）/ aria2（交给外面的 aria2，第 6 条） */
    fun downloadVia(): String = get("downloadVia", "app")

    /** aria2 的 JSON-RPC 地址（例如 http://192.168.1.10:6800/jsonrpc） */
    fun aria2Rpc(): String = get("aria2Rpc", "")

    /** aria2 的 RPC secret（空 = 对方没设） */
    fun aria2Token(): String = get("aria2Token", "")

    /** 交给 aria2 时用的目录（空 = 用 aria2 默认） */
    fun aria2Dir(): String = get("aria2Dir", "")

    fun set(key: String, value: String) {
        synchronized(lock) { data.put(key, value) }
        persist()
    }

    fun set(key: String, value: Boolean) {
        synchronized(lock) { data.put(key, value) }
        persist()
    }

    /** 一次性更新多个字段（控制台/接口都走这个，避免写半截） */
    fun update(o: JSONObject): JSONObject {
        synchronized(lock) {
            for (k in o.keys()) data.put(k, o.get(k))
        }
        persist()
        return json()
    }

    fun json(): JSONObject = synchronized(lock) { JSONObject(data.toString()) }

    // ---------------------------------------------------------------- 派生值

    /** 当前生效的 UA（null = 用 WebView 默认） */
    fun userAgent(): String? = when (get("uaMode", "phone")) {
        "desktop" -> DESKTOP_UA
        "custom" -> get("customUa").ifBlank { null }
        else -> null
    }

    /** 当前搜索引擎的 URL 模板（含 %s） */
    fun searchTemplate(): String {
        val id = get("search", "bing")
        if (id == "custom") {
            val t = get("customSearch")
            if (t.contains("%s")) return t
        }
        return ENGINES[id] ?: ENGINES["bing"]!!
    }

    fun searchUrl(q: String): String =
        searchTemplate().replace("%s", java.net.URLEncoder.encode(q, "UTF-8"))

    fun incognito(): Boolean = getBool("incognito", false)

    /** 自代理（用户第 16 条）：不用上游代理，也让浏览器流量过本地中继，这样统计口径更全 */
    fun selfProxy(): Boolean {
        // 注意：从控制口传进来的是**字符串**（?selfProxy=1），optBoolean 对字符串会返回 false——
        // 这个坑踩过一次（开关点开没生效）。所以这里按文本判。
        val v = get("selfProxy", "0").lowercase()
        return v == "1" || v == "true" || v == "on" || v == "yes"
    }

    fun proxyType(): String = get("proxyType", "none")          // none | http | https | socks5
    fun proxyHost(): String = get("proxyHost")
    fun proxyPort(): Int = get("proxyPort").toIntOrNull() ?: 0
    fun proxyUser(): String = get("proxyUser")
    fun proxyPass(): String = get("proxyPass")

    /** 给界面看的：把"当前生效值"也算出来（省得界面自己拼） */
    fun jsonForUi(localRelayPort: Int = 0): JSONObject {
        val o = json()
        val mode = get("uaMode", "phone")
        o.put(
            "uaLabel",
            when (mode) {
                "desktop" -> "电脑模式"
                "custom" -> "自定义 UA"
                else -> "手机模式"
            }
        )
        o.put("uaNow", userAgent() ?: "（WebView 默认手机 UA）")
        o.put("selfProxy", selfProxy())      // 第 16 条：界面要能把勾选状态读回来
        val id = get("search", "bing")
        o.put("searchLabel", ENGINE_NAMES[id] ?: id)
        o.put("searchTemplateNow", searchTemplate())
        val pt = proxyType()
        o.put(
            "proxySummary",
            if (pt == "none" || proxyHost().isBlank()) "不用代理（直连）"
            else "$pt ${proxyHost()}:${proxyPort()}" +
                (if (localRelayPort > 0) "　本地中继 127.0.0.1:$localRelayPort" else "　（中继未启动）")
        )
        return o
    }

    /** 直连白名单（走代理但不代理这些；支持 * 通配），逗号/换行分隔 */
    fun bypassRules(): List<String> =
        get("bypass").split(',', '\n', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

        val ENGINES: Map<String, String> = linkedMapOf(
            "bing" to "https://cn.bing.com/search?q=%s",
            "baidu" to "https://www.baidu.com/s?wd=%s",
            "google" to "https://www.google.com/search?q=%s",
            "sogou" to "https://www.sogou.com/web?query=%s",
            "so360" to "https://www.so.com/s?q=%s",
            "ddg" to "https://duckduckgo.com/?q=%s",
            "yandex" to "https://yandex.com/search/?text=%s",
            "ecosia" to "https://www.ecosia.org/search?q=%s",
            "brave" to "https://search.brave.com/search?q=%s",
            "startpage" to "https://www.startpage.com/sp/search?query=%s",
            "zhihu" to "https://www.zhihu.com/search?type=content&q=%s",
            "bilibili" to "https://search.bilibili.com/all?keyword=%s",
            "gh" to "https://github.com/search?q=%s"
        )

        val ENGINE_NAMES: Map<String, String> = linkedMapOf(
            "bing" to "必应", "baidu" to "百度", "google" to "谷歌", "sogou" to "搜狗", "so360" to "360",
            "ddg" to "DuckDuckGo", "yandex" to "Yandex", "ecosia" to "Ecosia", "brave" to "Brave",
            "startpage" to "Startpage", "zhihu" to "知乎", "bilibili" to "B站", "gh" to "GitHub",
            "custom" to "自定义"
        )
    }
}
