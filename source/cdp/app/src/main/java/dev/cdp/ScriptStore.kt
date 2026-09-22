package dev.cdp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 脚本库（落盘在 App 私有目录 files/scripts.json）。
 *
 * 两类脚本分开存：
 *  - kind = "userscript"：油猴式脚本（.user.js 原文），按 @match/@include 在对应站点自动注入；
 *  - kind = "recording"：录制出来的自动化脚本（steps 数组，每步含位置锚点 + DOM 指纹）。
 *
 * 场景：学习通那种网页里「不是 <a>、点不动」的按钮 —— 录一遍存下来，下次一条命令回放。
 */
class ScriptStore(ctx: Context) {

    private val ctxRef = ctx
    private var file: File = Spaces.file(ctx, "scripts.json")

    /** 外部导入目录（adb push / 文件管理器放的 .user.js、.cdpscript.json 都从这儿扫） */
    val importDir: File = File(ctx.getExternalFilesDir(null), "scripts")

    @Volatile private var items = JSONArray()

    init {
        load()
        if (!importDir.exists()) importDir.mkdirs()
        // 配置隔离：切空间就换文件重新加载（清单第 12 条）
        Spaces.onSwitch {
            file = Spaces.file(ctxRef, "scripts.json")
            items = JSONArray()
            load()
        }
    }

    private fun load() {
        items = try {
            if (file.exists()) JSONArray(file.readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun persist() {
        try {
            file.writeText(items.toString(2))
        } catch (_: Exception) {}
    }

    fun all(): JSONArray = items

    fun byKind(kind: String): JSONArray {
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optString("kind") == kind) out.put(o)
        }
        return out
    }

    fun get(id: String): JSONObject? {
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o
        }
        return null
    }

    /** 同 id 覆盖，同 name 也覆盖（避免重复导入堆一坨） */
    fun save(o: JSONObject): JSONObject {
        if (!o.has("id") || o.optString("id").isEmpty()) o.put("id", "s" + System.currentTimeMillis())
        o.put("updated", System.currentTimeMillis())
        var replaced = false
        for (i in 0 until items.length()) {
            val cur = items.optJSONObject(i) ?: continue
            if (cur.optString("id") == o.optString("id")) {
                items.put(i, o); replaced = true; break
            }
        }
        // 同名的也覆盖：注释里一直这么写，但代码只按 id 去重，于是别的入口
        //（录制以外的保存、队列临时脚本、测试）能造出重名，列表里看着像复制了一遍。
        if (!replaced) {
            val nm = o.optString("name").trim()
            if (nm.isNotEmpty()) {
                for (i in 0 until items.length()) {
                    val cur = items.optJSONObject(i) ?: continue
                    if (cur.optString("name").trim() == nm) {
                        o.put("id", cur.optString("id"))   // 沿用老 id，界面上的引用不失效
                        items.put(i, o); replaced = true; break
                    }
                }
            }
        }
        if (!replaced) items.put(o)
        persist()
        return o
    }

    fun delete(id: String): Boolean {
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("id") == id) {
                items.remove(i); persist(); return true
            }
        }
        return false
    }

    /** 只清"录制类"（recording / queue）：一键清空录制脚本用，用户脚本（内置/粘贴）不动 */
    fun clearRecordings(): Int {
        var n = 0
        for (i in items.length() - 1 downTo 0) {
            val k = items.optJSONObject(i)?.optString("kind") ?: continue
            if (k == "recording" || k == "queue") {
                items.remove(i)
                n++
            }
        }
        if (n > 0) persist()
        return n
    }

    fun clearAll(): Int {
        val n = items.length()
        items = JSONArray()
        persist()
        return n
    }

    private fun md5(s: String): String = try {
        val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        d.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        s.length.toString()
    }

    /**
     * 内置插件（assets 里 scripts 目录下的 .user.js）：首次启动自动装进来，用户删了就不再自动装。
     *
     * 2026-09-20 补：**内置插件要能跟着构件升级**。以前只按名字"缺了就补"，
     * 于是 assets 里的内置脚本改了、设备上永远还是旧的那份（实测：阅读时间插件改好了也到不了设备）。
     * 现在记两个哈希：
     *   `srcHash` = 当初装进来的那份内置源码的哈希；`codeHash` = 当前存着的代码哈希。
     *   两者相等（说明用户没动过）且内置源码变了 → 覆盖为新版；不相等（用户改过）→ 不动用户的版本。
     */
    fun seedBundled(ctx: android.content.Context): Int {
        // 每次启动都按名字对齐一遍：**缺了就补**（别用"装过就不再管"的标记——
        // 内置插件被删/被测试清掉之后，那样就再也回不来了，实测踩过）。
        // 名字已存在的跳过，这样用户在界面上改过的内置脚本不会被覆盖。
        var n = 0
        try {
            val names = ctx.assets.list("scripts") ?: emptyArray()
            for (f in names) {
                if (!f.endsWith(".user.js")) continue
                val code = ctx.assets.open("scripts/$f").readBytes().toString(Charsets.UTF_8)
                val name = parseMeta(code, "name") ?: f.removeSuffix(".user.js")
                val h = md5(code)
                var hit: org.json.JSONObject? = null
                for (i in 0 until items.length()) {
                    val it2 = items.optJSONObject(i) ?: continue
                    if (it2.optString("name") == name) { hit = it2; break }
                }
                if (hit == null) {
                    save(
                        org.json.JSONObject()
                            .put("id", "bundled_" + f.removeSuffix(".user.js"))
                            .put("kind", "userscript")
                            .put("name", name)
                            .put("match", parseMeta(code, "match") ?: "*://*/*")
                            .put("runAt", parseMeta(code, "run-at") ?: "document-idle")
                            .put("code", code)
                            .put("bundled", true)
                            .put("enabled", true)
                            .put("srcHash", h).put("codeHash", h)
                            .put("ts", System.currentTimeMillis())
                    )
                    n++
                } else if (hit.optBoolean("bundled") && hit.optString("srcHash") == hit.optString("codeHash") &&
                    hit.optString("srcHash") != h
                ) {
                    // 用户没改过这份内置脚本 → 用新版覆盖（并保留它自己的"启用/停用"选择）
                    hit.put("code", code)
                    hit.put("match", parseMeta(code, "match") ?: "*://*/*")
                        .put("runAt", parseMeta(code, "run-at") ?: "document-idle")
                    hit.put("srcHash", h).put("codeHash", h).put("ts", System.currentTimeMillis())
                    save(hit)
                    n++
                }
            }
            // "退休"：assets 里已经没有这个内置脚本、而且用户没改过它 → 从设备上移除。
            // 为什么需要：用户 2026-09-20 要求"不要往页面里注入 JS"（阅读时间改成 App 自己计时、
            // 助手面板不再注入页面）。只删 assets 删不掉设备上那份 —— 不退休的话它还会在每个页面里跑。
            val assetNames = (ctx.assets.list("scripts") ?: emptyArray())
                .filter { it.endsWith(".user.js") }
                .map { it.removeSuffix(".user.js") }
                .toSet()
            for (i in items.length() - 1 downTo 0) {
                val it2 = items.optJSONObject(i) ?: continue
                if (!it2.optBoolean("bundled")) continue
                val base = it2.optString("id").removePrefix("bundled_")
                if (assetNames.contains(base)) continue
                if (it2.optString("srcHash") != it2.optString("codeHash")) continue   // 用户改过 → 留着
                lastRetired.add(it2.optString("name"))
                items.remove(i)
            }
            if (lastRetired.isNotEmpty()) persist()
        } catch (e: Exception) {
            // 装不上不算致命：日志里能看到
        }
        return n
    }

    fun count(): Int = items.length()

    /** 最近一次启动时"退休"掉的内置插件名（assets 里已删除、用户又没改过的那几个） */
    val lastRetired = mutableListOf<String>()

    /** 列表用的精简视图（不把整段代码塞给界面） */
    fun listLight(): JSONArray {
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val l = JSONObject()
            l.put("id", o.optString("id"))
            l.put("name", o.optString("name"))
            l.put("kind", o.optString("kind"))
            l.put("source", o.optString("source"))
            l.put("updated", o.optLong("updated"))
            l.put("steps", o.optJSONArray("steps")?.length() ?: 0)
            l.put("codeLen", o.optString("code").length)
            l.put("matches", o.optString("match"))
            // 运行计数：跑了多少次、成功多少次、每步触发次数（控制台直接显示）
            val stats = o.optJSONObject("stats")
            if (stats != null) {
                l.put("runs", stats.optInt("runs"))
                l.put("okRuns", stats.optInt("okRuns"))
                l.put("lastMs", stats.optLong("lastMs"))
                l.put("lastOk", stats.optBoolean("lastOk"))
                l.put("stepFires", stats.optJSONArray("stepFires") ?: JSONArray())
            } else {
                l.put("runs", 0).put("okRuns", 0)
            }
            out.put(l)
        }
        return out
    }

    /** 扫外部导入目录：.user.js → 油猴脚本；.cdpscript.json / .json → 录制脚本 */
    fun scanImportDir(): JSONArray {
        val added = JSONArray()
        val fs = importDir.listFiles() ?: return added
        for (f in fs) {
            if (!f.isFile) continue
            val name = f.name
            try {
                if (name.endsWith(".user.js")) {
                    val code = f.readText()
                    val o = JSONObject()
                        .put("name", parseMeta(code, "name") ?: name.removeSuffix(".user.js"))
                        .put("kind", "userscript")
                        .put("source", "文件导入")
                        .put("code", code)
                        .put("match", parseMeta(code, "match") ?: parseMeta(code, "include") ?: "*")
                    val saved = save(o)
                    added.put(JSONObject().put("name", saved.optString("name")).put("kind", "userscript"))
                } else if (name.endsWith(".cdpscript.json") || (name.endsWith(".json") && f.readText().contains("\"steps\""))) {
                    val o = JSONObject(f.readText())
                    o.put("kind", "recording")
                    if (!o.has("name")) o.put("name", name.removeSuffix(".json"))
                    o.put("source", "文件导入")
                    val saved = save(o)
                    added.put(
                        JSONObject().put("name", saved.optString("name")).put("kind", "recording")
                            .put("steps", saved.optJSONArray("steps")?.length() ?: 0)
                    )
                }
            } catch (_: Exception) {
            }
        }
        return added
    }

    companion object {
        val META_RE = Regex("""//\s*@(\S+)\s+(.*)""")

        fun parseMeta(code: String, key: String): String? {
            for (line in code.lineSequence().take(40)) {
                val m = META_RE.find(line) ?: continue
                if (m.groupValues[1] == key) return m.groupValues[2].trim()
            }
            return null
        }

        /** @match / @include 命中判断。支持 * 通配与 *:// 前缀写法，够用且好解释。 */
        fun matchesUrl(pattern: String, url: String): Boolean {
            if (pattern.isBlank() || pattern == "*") return true
            for (raw in pattern.split(",")) {
                val p = raw.trim()
                if (p.isEmpty()) continue
                if (p == "*") return true
                val re = StringBuilder("^")
                for (ch in p) {
                    when (ch) {
                        '*' -> re.append(".*")
                        '.', '?', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> re.append("\\").append(ch)
                        else -> re.append(ch)
                    }
                }
                re.append("$")
                try {
                    if (Regex(re.toString(), RegexOption.IGNORE_CASE).containsMatchIn(url)) return true
                } catch (_: Exception) {}
            }
            return false
        }
    }
}
