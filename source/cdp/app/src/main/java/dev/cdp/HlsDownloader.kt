package dev.cdp

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载 m3u8（HLS）：拿播放列表 → 解析分片 → 按顺序把分片存进目录。
 *
 * 明确不做的事：不转码、不合并成单个 mp4（设备上没有 ffmpeg，硬合容易半路失败）；
 * 存下来的是「列表 + 分片」，要合并在电脑上一条命令就行。
 */
class HlsDownloader(
    private val ctx: Context,
    private val cookieFor: (String) -> String?,
    private val log: (String) -> Unit
) {

    data class Result(
        val ok: Boolean,
        val name: String,
        val dir: String,
        val segments: Int,
        val bytes: Long,
        val error: String?
    )

    /** 拉文本（带 WebView 的 cookie，登录态的播放列表也能取） */
    fun fetchText(url: String): String {
        val c = conn(url)
        return try {
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            try { c.disconnect() } catch (_: Exception) {}
        }
    }

    /** 这条资源所在的页面地址（用来做 Referer；B 站这类 CDN 只认站点域名根） */
    var pageHint: String = ""

    private fun conn(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", UA)
        // 实测：B 站这类 CDN 会校验 Referer，不带就 403（现象就是"有分片没下成功"）。
        // 只带"协议://域名/"，带完整页面路径反而会被拒。
        try {
            val page = pageHint
            val u2 = java.net.URI(if (page.isNotBlank()) page else url)
            if (u2.scheme != null && u2.host != null) {
                c.setRequestProperty("Referer", u2.scheme + "://" + u2.host + "/")
            }
        } catch (e: Throwable) {}
        try {
            cookieFor(url)?.let { if (it.isNotBlank()) c.setRequestProperty("Cookie", it) }
        } catch (_: Exception) {
        }
        return c
    }

    private fun resolve(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        val b = URL(base)
        return if (rel.startsWith("/")) {
            "${b.protocol}://${b.host}${if (b.port > 0 && b.port != 80 && b.port != 443) ":${b.port}" else ""}$rel"
        } else {
            val dir = base.substringBeforeLast('/') + "/"
            val merged = dir + rel
            // 简单归一化 ../ 
            if (!merged.contains("../")) merged
            else {
                val parts = merged.split("/")
                val out = ArrayList<String>()
                for (p in parts) {
                    if (p == "..") { if (out.isNotEmpty()) out.removeAt(out.size - 1) }
                    else out.add(p)
                }
                out.joinToString("/")
            }
        }
    }

    /** 解析播放列表：返回分片地址（若遇到多码率主列表，先取其中一路） */
    fun playlistSegments(playlistUrl: String, text: String): Pair<List<String>, String?> {
        val lines = text.split("\n").map { it.trim() }
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        if (isMaster) {
            // 主列表：取第一条变体（信息里通常第一个是最高或最低码率；这里取第一条可用的）
            val idx = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            for (i in idx + 1 until lines.size) {
                val l = lines[i]
                if (l.isEmpty() || l.startsWith("#")) continue
                return playlistSegments(resolve(playlistUrl, l), fetchText(resolve(playlistUrl, l))).let {
                    (it.first) to it.second
                }
            }
            return emptyList<String>() to "主列表里没找到变体地址"
        }
        val segs = ArrayList<String>()
        for (l in lines) {
            if (l.isEmpty() || l.startsWith("#")) continue
            segs.add(resolve(playlistUrl, l))
        }
        return segs to null
    }

    fun download(playlistUrl: String, nameHint: String, onProgress: ((Int, Int, Long) -> Unit)? = null): Result {
        val name = sanitize(nameHint.ifBlank { nameFromUrl(playlistUrl) })
        val dir = File(File(ctx.filesDir, "downloads"), "$name.hls")
        return try {
            if (!dir.exists()) dir.mkdirs()
            val text = fetchText(playlistUrl)
            if (text.isBlank()) return Result(false, name, dir.absolutePath, 0, 0, "播放列表是空的")
            File(dir, "playlist.m3u8").writeText(text)
            val (segs, err) = playlistSegments(playlistUrl, text)
            if (segs.isEmpty()) return Result(false, name, dir.absolutePath, 0, 0, err ?: "列表里没有分片")
            log("m3u8 $name：共 ${segs.size} 个分片，开始下载到 ${dir.name}/")
            var done = 0
            var bytes = 0L
            for ((i, s) in segs.withIndex()) {
                val ext = s.substringBefore('?').substringAfterLast('.', "ts").take(5)
                val f = File(dir, String.format("%04d.%s", i + 1, ext))
                try {
                    val c = conn(s)
                    c.inputStream.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                    bytes += f.length()
                    done++
                    try { c.disconnect() } catch (_: Exception) {}
                } catch (e: Exception) {
                    log("分片 ${i + 1} 失败：${e.message}")
                }
                if ((i + 1) % 10 == 0) {
                    log("m3u8 $name：已完成 ${i + 1}/${segs.size}")
                    // 进度回给调用方（下载列表要能显示"170/1999 · 12.3 MB"，而不是一直 0 B）
                    try { onProgress?.invoke(i + 1, segs.size, bytes) } catch (_: Exception) {}
                }
            }
            // 记一条清单，方便后面对账
            File(dir, "MANIFEST.txt").writeText(
                buildString {
                    append("来源: $playlistUrl\n")
                    append("分片数: ${segs.size}（成功 $done）\n")
                    append("字节: $bytes\n")
                    segs.forEachIndexed { i, s -> append(String.format("%04d", i + 1)).append("  ").append(s).append('\n') }
                }
            )
            val rec = JSONObject()
                .put("name", name).put("url", playlistUrl).put("kind", "hls")
                .put("segments", segs.size).put("okSegments", done)
                .put("bytes", bytes).put("dir", dir.name)
                .put("state", if (done == segs.size) "完成" else "部分完成（$done/${segs.size}）")
                .put("ts", System.currentTimeMillis())
            log("m3u8 $name 下载结束：$done/${segs.size} 个分片，$bytes 字节 → ${dir.absolutePath}")
            Result(done > 0, name, dir.absolutePath, done, bytes, if (done == segs.size) null else "有分片没下成功")
                .also { _ -> // 把 JSON 记进下载记录由调用方处理
                    lastRecord = rec
                }
        } catch (e: Exception) {
            log("m3u8 下载失败：${e.message}")
            Result(false, name, dir.absolutePath, 0, 0, e.message)
        }
    }


    /**
     * 直链（单个文件，如 .mp4/.m4s/.flv）下载。
     * 以前这类地址也被丢进 download()（那是给 m3u8 播放列表用的），于是必然报
     * "有分片没下成功" —— 其实只要当普通文件拉下来就行。
     */
    fun downloadFile(url: String, nameHint: String): Result {
        val name = sanitize(nameHint.ifBlank { nameFromUrl(url) })
        val ext = url.substringBefore('?').substringAfterLast('.', "bin").take(5).ifBlank { "bin" }
        val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
        val f = File(dir, "$name.$ext")
        return try {
            val c = conn(url)
            val code = c.responseCode
            if (code >= 400) {
                log("直链下载失败：HTTP $code")
                return Result(false, name, f.absolutePath, 0, 0, "服务器返回 HTTP $code（多半要 Referer/UA 或链接过期）")
            }
            var n = 0L
            c.inputStream.use { input -> f.outputStream().use { out -> n = input.copyTo(out) } }
            try { c.disconnect() } catch (_: Exception) {}
            log("直链下载完成：$name.$ext → $n 字节")
            lastRecord = JSONObject()
                .put("name", name).put("url", url).put("kind", "file")
                .put("bytes", n).put("file", f.name).put("dir", "downloads")
                .put("state", if (n > 0) "完成" else "失败")
                .put("ts", System.currentTimeMillis())
            Result(n > 0, name, f.absolutePath, 1, n, if (n > 0) null else "一个字节都没下来")
        } catch (e: Exception) {
            log("直链下载出错：${e.message}")
            Result(false, name, f.absolutePath, 0, 0, e.message)
        }
    }

    /** 是不是 m3u8 播放列表（决定走哪条下载通道） */
    fun looksLikePlaylist(url: String): Boolean {
        val p = url.substringBefore('?').lowercase()
        return p.endsWith(".m3u8") || p.endsWith(".m3u") || p.endsWith(".mpd") || p.contains(".m3u8")
    }

    /** 最近一次的下载记录（给 MainActivity 落盘用） */
    @Volatile
    var lastRecord: JSONObject? = null
        private set

    private fun nameFromUrl(url: String): String {
        val seg = url.substringBefore('?').substringAfterLast('/')
        val base = seg.substringBeforeLast('.').ifBlank { "hls" }
        return base.ifBlank { "hls" }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_").take(60).ifBlank { "hls" }

    companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
