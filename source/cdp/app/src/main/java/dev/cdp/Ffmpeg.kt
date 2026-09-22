package dev.cdp

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 内置 ffmpeg（mydoc 清单第 7/8 条：内置 ffmpeg、用 ffmpeg 强制下载视频）。
 *
 * 用的是预编译的 **ffmpeg-kit-min（LGPL 版，6.0-2）**：
 *  · 能力：读媒体信息（探测流/时长/码率）、用 `-c copy` 把 HLS/m3u8 拉成一个 MP4（"强制下载"就是这条）、
 *    以及跑任意 ffmpeg 参数（界面里会给常用例子）。
 *  · 取舍（如实写）：**min 变体不含 x264/x265 这类 GPL 编码器**，所以能做"转封装/复制流"，
 *    **不能做重编码**（要重编码得换 ffmpeg-kit 的 gpl 变体，体积和许可都会变）。
 *  · 全部在本进程内跑（不需要从应用私有目录 exec 二进制——Android 10+ 本来也禁这个）。
 *  · 长任务放后台线程，界面/接口拿的是"会话日志 + 返回码"，不假装同步完成。
 */
class Ffmpeg(private val ctx: android.content.Context) {

    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"
    private val outDir: File = File(ctx.filesDir, "ffmpeg").apply { mkdirs() }

    fun available(): Boolean = try {
        Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
        true
    } catch (e: Throwable) {
        false
    }

    /** 版本信息 + 能力边界（给界面和接口看的） */
    fun state(): JSONObject {
        if (!available()) {
            return JSONObject().put("ok", false).put("error", "没有内置 ffmpeg（构建里没带 ffmpeg-kit）")
        }
        val ver = runSync(listOf("-version"), 20)
        val first = ver.optString("log").lineSequence().firstOrNull { it.contains("ffmpeg version") } ?: ""
        return JSONObject()
            .put("ok", true)
            .put("variant", "ffmpeg-kit-https 6.0-2（LGPL；min + HTTPS/TLS，无 x264/x265 等 GPL 编码器）")
            .put("version", first.trim())
            .put("canRemux", true)
            .put("canReencode", false)
            .put("outDir", outDir.absolutePath)
            .put("note", "能做：读媒体信息、用 -c copy 把 m3u8/HLS 拉成 MP4（强制下载）、跑常用参数；" +
                "不能做：重编码（不带 x264/x265），要重编码得换 gpl 变体。能直接拉 https 流（B 站这类 CDN 都是 https）。")
    }

    private fun runSync(args: List<String>, timeoutSec: Long): JSONObject {
        val latch = CountDownLatch(1)
        var sessionId = ""
        var rc = ""
        val logSb = StringBuilder()
        try {
            val cmd = args.joinToString(" ")
            val session = FFmpegKit.executeAsync(cmd) { s ->
                sessionId = s.sessionId.toString()
                rc = s.returnCode?.toString() ?: ""
                logSb.append(s.allLogsAsString ?: "")
                latch.countDown()
            }
            latch.await(timeoutSec, TimeUnit.SECONDS)
            val ok = ReturnCode.isSuccess(com.arthenica.ffmpegkit.ReturnCode(rc.toIntOrNull() ?: -1))
            return JSONObject()
                .put("ok", ok)
                .put("sessionId", sessionId)
                .put("returnCode", rc)
                .put("log", logSb.toString().takeLast(8000))
        } catch (e: Throwable) {
            return JSONObject().put("ok", false).put("error", "跑 ffmpeg 出错：" + (e.message ?: e.toString()))
        }
    }

    /** 媒体信息：优先用 FFprobe（有就更好），没有就退回 `ffmpeg -i` 的日志 */
    fun info(url: String): JSONObject {
        if (!available()) return JSONObject().put("ok", false).put("error", "没有内置 ffmpeg")
        if (url.isBlank()) return JSONObject().put("ok", false).put("error", "没给地址")
        return try {
            val latch = CountDownLatch(1)
            var out = JSONObject().put("ok", false).put("error", "探测没回结果")
            FFprobeKit.getMediaInformationAsync(url) { session ->
                try {
                    val mi = session.mediaInformation
                    if (mi == null) {
                        out = JSONObject().put("ok", false)
                            .put("error", "读不到媒体信息（可能是加密流/分片/需要鉴权）")
                            .put("log", (session.allLogsAsString ?: "").takeLast(1500))
                    } else {
                        val streams = JSONArray()
                        mi.streams?.forEach { st ->
                            streams.put(
                                JSONObject().put("type", st.type ?: "")
                                    .put("codec", st.codec ?: "")
                                    .put("width", st.width ?: 0).put("height", st.height ?: 0)
                                    .put("bitrate", st.bitrate ?: "").put("sampleRate", st.sampleRate ?: "")
                            )
                        }
                        out = JSONObject().put("ok", true)
                            .put("engine", "FFprobe（ffmpeg-kit-min 内置）")
                            .put("format", mi.format ?: "")
                            .put("duration", mi.duration ?: "")
                            .put("size", mi.size ?: "")
                            .put("bitrate", mi.bitrate ?: "")
                            .put("streams", streams)
                    }
                } catch (e: Throwable) {
                    out = JSONObject().put("ok", false).put("error", "解析探测结果出错：" + (e.message ?: ""))
                }
                latch.countDown()
            }
            latch.await(45, TimeUnit.SECONDS)
            out
        } catch (e: Throwable) {
            JSONObject().put("ok", false).put("error", "探测出错：" + (e.message ?: e.toString()))
        }
    }

    /**
     * 把地址（HLS/m3u8、mp4、flv 都行）**拉成一个文件**（清单第 8 条"强制下载视频"）。
     * 默认 `-c copy` 只转封装、不重编码：快、无损、CPU 友好；min 变体也只能这样。
     */
    fun remux(
        url: String,
        name: String,
        extraArgs: List<String> = emptyList(),
        ext: String = "mp4",
        referer: String = "",
        cookies: String = ""
    ): JSONObject {
        if (!available()) return JSONObject().put("ok", false).put("error", "没有内置 ffmpeg")
        if (url.isBlank()) return JSONObject().put("ok", false).put("error", "没给地址")
        val safe = name.ifBlank { "video" }.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_").take(40)
        val e = if (ext.matches(Regex("[A-Za-z0-9]{2,5}"))) ext.lowercase() else "mp4"
        val out = File(outDir, "$safe-${System.currentTimeMillis() % 100000}.$e")
        // 很多 CDN（B 站这类）会校验 Referer / UA，不带就 403。这里默认把"当前页面的地址"
        // 当作 Referer、用浏览器的 UA（都能从应用侧拿到），调用方也可以自己指定。
        val hdr = StringBuilder()
        if (referer.isNotBlank()) hdr.append("Referer: ").append(referer).append("\r\n")
        val head: MutableList<String> = mutableListOf("-y")
        // 注意：UA 本身带空格，**必须加引号**——否则解析时只会吃掉 "Mozilla/5.0"，
        // 后面的 -headers 会被当成杂散参数丢掉，表现就是"带了头还是 403"。
        if (hdr.isNotEmpty()) head += listOf("-user_agent", "\"" + UA + "\"", "-headers", "\"" + hdr + "\"")
        if (cookies.isNotBlank()) head += listOf("-cookies", "\"" + cookies + "\"")
        val args: List<String> = if (extraArgs.isNotEmpty()) {
            extraArgs + out.absolutePath          // 调用方给全参数，输出路径由我们补在最后
        } else {
            head + listOf("-i", url, "-c", "copy", "-bsf:a", "aac_adtstoasc", out.absolutePath)
        }
        android.util.Log.i("cdp", "ffmpeg 拉流用了 Referer=" + if (referer.isBlank()) "(空)" else referer)
        val r = runSync(args, 180)
        r.put("referer", referer)          // 实际用的 Referer（排障时一眼能看出取没取到）
        r.put("out", out.absolutePath)
        r.put("bytes", if (out.exists()) out.length() else 0)
        r.put("args", args.joinToString(" "))
        if (r.optBoolean("ok") && out.exists() && out.length() > 0) {
            // 拿 ffprobe 复核一下产物（时长/流），让"成功"有据可查
            val chk = info(out.absolutePath)
            r.put("verify", chk)
        }
        return r
    }

    /** 通用入口：给界面/接口跑任意参数（注意：重编码能力受 min 变体限制） */
    fun run(args: String, timeoutSec: Long = 120): JSONObject {
        if (!available()) return JSONObject().put("ok", false).put("error", "没有内置 ffmpeg")
        if (args.isBlank()) return JSONObject().put("ok", false).put("error", "没给参数")
        val r = runSync(args.split(" ").filter { it.isNotBlank() }, timeoutSec)
        r.put("args", args)
        return r
    }
}
