package dev.cdp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 内伪终端（mydoc 清单第 7 条的"伪终端界面"部分）。
 *
 * 诚实说清能力边界：**Android 应用不能 fork/exec 系统 shell**（不是没做，是系统不允许），
 * 所以这里不是 /bin/sh，而是一个**应用内命令解析器**：文件操作限制在 App 私有目录里，
 * 网络类命令走 App 自己的 HTTP 客户端。也就是说：
 *   · 能做：pwd / cd / ls / cat / head / tail / wc / mkdir / rm / mv / du / stat / find / grep /
 *           echo / date / df / md5 / sha256 / curl（下载到 files/terminal/）/
 *           net dns <host> / net tcp <host> <port> / help / clear
 *   · 不能做：apt/pip/任意可执行文件、管道重定向、后台作业、ffmpeg（**本工程没有内置 ffmpeg**，
 *           真要用得先交叉编译进来；这里会明确回"未内置"而不是假装有）
 *   · 所有路径被限制在 filesDir 之内：`cd ../../` 这类会给出拒绝并回到根。
 */
class Terminal(private val ctx: android.content.Context, private val ffmpeg: Ffmpeg? = null) {

    private val root: File = File(ctx.filesDir, "term").apply { mkdirs() }
    @Volatile private var cwd: File = root

    private fun sandbox(p: String): File? {
        val f = if (p.startsWith("/")) File(p) else File(cwd, p)
        val cf = try { f.canonicalFile } catch (e: Exception) { return null }
        val cr = try { root.canonicalFile } catch (e: Exception) { root }
        return if (cf.path == cr.path || cf.path.startsWith(cr.path + "/")) cf else null
    }

    private fun rel(f: File): String {
        val cr = root.canonicalPath
        val p = f.canonicalPath
        return if (p == cr) "~" else "~" + p.removePrefix(cr)
    }

    private fun human(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> "%.2fG".format(n / 1073741824.0)
        n >= 1024L * 1024 -> "%.2fM".format(n / 1048576.0)
        n >= 1024 -> "%.1fK".format(n / 1024.0)
        else -> "${n}B"
    }

    private fun json(ok: Boolean, out: String): JSONObject = JSONObject()
        .put("ok", ok).put("out", out).put("cwd", rel(cwd))

    /** 跑一条命令；返回值直接给界面和接口用 */
    fun run(line: String): JSONObject {
        val cmdline = line.trim()
        if (cmdline.isEmpty()) return json(true, "")
        // 极简词法：按空白切，支持用引号包住的参数
        val parts = Regex("""[^\s"]+|"([^"]*)"""")
            .findAll(cmdline)
            .map { m -> if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.value }
            .toMutableList()
        if (parts.isEmpty()) return json(true, "")
        val cmd = parts.removeAt(0)
        try {
            return when (cmd) {
                "help" -> json(
                    true,
                    """
                    这是 App 内的伪终端（不是系统 /bin/sh——Android 不允许应用 fork shell）。
                      pwd / cd <目录> / ls [-l] [目录] / cat <文件> / head|tail [-n N] <文件>
                      wc <文件> / mkdir <目录> / rm [-r] <路径> / mv <源> <目标> / du [目录]
                      stat <路径> / find <名字> / grep <文本> <文件> / echo <文本>
                      date / df / md5|sha256 <文件> / curl <url> [文件名]
                      net dns <主机> / net tcp <主机> <端口>
                      clear / help
                    不能用：apt/pip/任意可执行文件、管道重定向、后台作业。
                    ffmpeg：本工程**没有内置**（会明确回"未内置"），要用得先交叉编译进来。
                    所有路径限制在 ~ 之内（cd ../../ 会被拒绝）。
                    """.trimIndent()
                )
                "pwd" -> json(true, rel(cwd))
                "cd" -> {
                    val target = if (parts.isEmpty() || parts[0] == "~") root else sandbox(parts[0])
                    when {
                        target == null -> json(false, "拒绝：$'${parts.getOrNull(0)}' 超出 App 私有目录（~）")
                        !target.exists() -> json(false, "cd: 没有这个目录：${parts.getOrNull(0)}")
                        !target.isDirectory -> json(false, "cd: 不是目录：${parts.getOrNull(0)}")
                        else -> { cwd = target; json(true, rel(cwd)) }
                    }
                }
                "ls" -> {
                    val long = parts.remove("-l") || parts.remove("-la") || parts.remove("-al")
                    val dir = if (parts.isEmpty()) cwd else sandbox(parts[0])
                    if (dir == null || !dir.exists()) json(false, "ls: 路径不对：${parts.getOrNull(0) ?: "."}")
                    else {
                        val kids = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
                        val sb = StringBuilder()
                        for (k in kids) {
                            if (long) {
                                sb.append(if (k.isDirectory) "d" else "-")
                                    .append(" ").append(String.format(Locale.US, "%10s", human(k.length())))
                                    .append("  ").append(SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(k.lastModified())))
                                    .append("  ").append(k.name).append(if (k.isDirectory) "/" else "").append('\n')
                            } else {
                                sb.append(k.name).append(if (k.isDirectory) "/" else "").append("  ")
                            }
                        }
                        json(true, if (sb.isEmpty()) "(空目录)" else sb.toString().trimEnd())
                    }
                }
                "cat", "head", "tail" -> {
                    var n = if (cmd == "cat") 200 else 10
                    val i = parts.indexOf("-n")
                    if (i >= 0 && i + 1 < parts.size) { n = parts[i + 1].toIntOrNull() ?: n; parts.removeAt(i + 1); parts.removeAt(i) }
                    val f = parts.firstOrNull()?.let { sandbox(it) }
                    if (f == null || !f.exists() || f.isDirectory) json(false, "$cmd: 读不到：${parts.firstOrNull() ?: "(没给文件)"}")
                    else {
                        val all = f.readLines()
                        val out = when (cmd) {
                            "cat" -> all.take(n)
                            "head" -> all.take(n)
                            else -> all.takeLast(n)
                        }
                        json(true, out.joinToString("\n") + if (all.size > n && cmd != "cat") "\n…（共 ${all.size} 行，只显示 $n 行）" else "")
                    }
                }
                "wc" -> {
                    val f = parts.firstOrNull()?.let { sandbox(it) }
                    if (f == null || !f.exists()) json(false, "wc: 读不到")
                    else json(true, "${f.readLines().size} 行　${human(f.length())}")
                }
                "mkdir" -> {
                    val f = parts.firstOrNull()?.let { sandbox(it) }
                    if (f == null) json(false, "mkdir: 路径超出 ~") else json(f.mkdirs(), if (f.exists()) "已存在：${rel(f)}" else "建不了：${rel(f)}")
                }
                "rm" -> {
                    val rec = parts.remove("-r") || parts.remove("-rf") || parts.remove("-fr")
                    val f = parts.firstOrNull()?.let { sandbox(it) }
                    if (f == null) json(false, "rm: 路径超出 ~")
                    else if (!f.exists()) json(false, "rm: 没有：${parts.firstOrNull()}")
                    else if (f.isDirectory && !rec) json(false, "rm: 是目录，要加 -r")
                    else {
                        // 顶层目录本身不给删（root 就是 ~）
                        if (f.canonicalPath == root.canonicalPath) json(false, "rm: ~ 本身不给删")
                        else json(f.deleteRecursively(), "已删 ${rel(f)}")
                    }
                }
                "mv" -> {
                    val a = parts.getOrNull(0)?.let { sandbox(it) }
                    val b = parts.getOrNull(1)?.let { sandbox(it) }
                    if (a == null || b == null || !a.exists()) json(false, "mv: 参数不对")
                    else json(a.renameTo(b), "已移到 ${rel(b)}")
                }
                "du" -> {
                    val d = if (parts.isEmpty()) cwd else sandbox(parts[0])
                    if (d == null || !d.exists()) json(false, "du: 路径不对")
                    else {
                        var total = 0L
                        d.walkTopDown().forEach { if (it.isFile) total += it.length() }
                        json(true, "${human(total)}（${d.walkTopDown().count()} 个条目）")
                    }
                }
                "stat" -> {
                    val f = parts.firstOrNull()?.let { sandbox(it) }
                    if (f == null || !f.exists()) json(false, "stat: 没有")
                    else json(
                        true,
                        "路径: ${rel(f)}\n类型: ${if (f.isDirectory) "目录" else "文件"}\n大小: ${f.length()} 字节\n" +
                            "修改: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f.lastModified())) +
                            "\n可读: ${f.canRead()}　可写: ${f.canWrite()}"
                    )
                }
                "find" -> {
                    val kw = parts.getOrNull(0) ?: return json(false, "find: 给个名字片段")
                    val hits = root.walkTopDown().filter { it.name.contains(kw) }.take(50).map { rel(it) }.toList()
                    json(true, if (hits.isEmpty()) "(没找到)" else hits.joinToString("\n"))
                }
                "grep" -> {
                    val kw = parts.getOrNull(0); val f = parts.getOrNull(1)?.let { sandbox(it) }
                    if (kw == null || f == null || !f.exists()) json(false, "grep: 用法 grep <文本> <文件>")
                    else {
                        val hits = f.readLines().withIndex().filter { it.value.contains(kw) }.take(50)
                        json(true, if (hits.isEmpty()) "(没匹配)" else hits.joinToString("\n") { (i, l) -> "${i + 1}: $l" })
                    }
                }
                "echo" -> json(true, parts.joinToString(" "))
                "date" -> json(true, SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date()))
                "df" -> json(
                    true,
                    "App 私有目录: 已用 ${human(root.walkTopDown().filter { it.isFile }.sumOf { it.length() })}" +
                        "（内部存储剩余 ${human(ctx.filesDir.usableSpace)}）"
                )
                "md5", "sha256" -> {
                    val f = parts.firstOrNull()?.let { sandbox(it) }
                    if (f == null || !f.exists()) json(false, "$cmd: 读不到")
                    else {
                        val md = MessageDigest.getInstance(if (cmd == "md5") "MD5" else "SHA-256")
                        val d = md.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                        json(true, "$d  ${f.name}")
                    }
                }
                "curl" -> {
                    val url = parts.getOrNull(0) ?: return json(false, "curl: 给个地址")
                    val name = parts.getOrNull(1) ?: (url.substringAfterLast('/').ifEmpty { "download.bin" })
                    val out = File(cwd, name)
                    try {
                        val c = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 10000; readTimeout = 15000
                            setRequestProperty("User-Agent", "CDP-terminal/1.0")
                        }
                        val code = c.responseCode
                        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
                        out.writeBytes(bytes)
                        json(code in 200..299, "HTTP $code　${bytes.size} 字节 → ${rel(out)}")
                    } catch (e: Exception) {
                        json(false, "curl 失败：${e.message}")
                    }
                }
                "net" -> {
                    when (parts.getOrNull(0)) {
                        "dns" -> {
                            val h = parts.getOrNull(1) ?: return json(false, "net dns <主机>")
                            try {
                                val addrs = InetAddress.getAllByName(h).map { it.hostAddress }
                                json(true, addrResolved(h, addrs))
                            } catch (e: Exception) {
                                json(false, "解析失败：${e.message}（这台模拟器可能没外网，属正常）")
                            }
                        }
                        "tcp" -> {
                            val h = parts.getOrNull(1); val p = parts.getOrNull(2)?.toIntOrNull()
                            if (h == null || p == null) json(false, "net tcp <主机> <端口>")
                            else {
                                val t0 = System.currentTimeMillis()
                                try {
                                    Socket().use { it.connect(java.net.InetSocketAddress(h, p), 5000) }
                                    json(true, "连上了 $h:$p　耗时 ${System.currentTimeMillis() - t0} ms")
                                } catch (e: Exception) {
                                    json(false, "连不上 $h:$p：${e.message}")
                                }
                            }
                        }
                        else -> json(false, "net: 只有 dns / tcp")
                    }
                }
                "ffmpeg" -> {
                    // 真内置了（ffmpeg-kit-min）：直接在应用内跑，不需要从私有目录 exec
                    val ff = ffmpeg
                    val st = ff?.state() ?: org.json.JSONObject().put("ok", false)
                    if (ff == null || !st.optBoolean("ok")) {
                        json(false, "没有内置 ffmpeg：" + st.optString("error"))
                    } else if (parts.isEmpty()) {
                        json(true, st.optString("version") + "\n" + st.optString("note"))
                    } else {
                        val r = ff.run(parts.joinToString(" "), 180)
                        json(r.optBoolean("ok"), (r.optString("log") + "\n返回码=" + r.optString("returnCode")).takeLast(4000))
                    }
                }
                "clear" -> json(true, "")
                else -> json(false, "未实现：$cmd（输入 help 看能用的命令）")
            }
        } catch (e: Exception) {
            return json(false, "$cmd 出错：${e.message}")
        }
    }

    private fun addrResolved(h: String, addrs: List<String>): String =
        "$h → " + addrs.joinToString(", ")
}
