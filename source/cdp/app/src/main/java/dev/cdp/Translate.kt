package dev.cdp

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 翻译（清单第 18 条）。
 *
 * 老实说清能力边界：**离线翻译得有模型文件**（比如 Firefox 用的 bergamot / marian 小模型，
 * 一个语言对几百 MB 到 1 GB），本项目**不分发模型**，也没有把推理引擎塞进 APK。
 * 所以这里做的是「通路 + 诚实报错」：
 *   - 如果你把 `translateEndpoint` 设成一个能接受 `{"text","from","to"}` POST、返回 `{"text"}` 的服务，
 *     整页/选中翻译就走它；
 *   - 没设就明确告诉你"没配端点、离线模型需要你自己放进 files/models/ 再由插件调用"，
 *     不假装翻译成功。
 */
object Translate {

    fun hasEndpoint(settings: SettingsStore): Boolean = settings.get("translateEndpoint").isNotBlank()

    fun state(settings: SettingsStore): JSONObject = JSONObject()
        .put("ok", true)
        .put("endpoint", settings.get("translateEndpoint"))
        .put("modelsDir", "files/models/")
        .put("modelFiles", listModels())
        .put(
            "note",
            "没配端点时不假装能翻。离线模型（如 bergamot 中英小模型）请自己放到 files/models/ 下，" +
                "再用插件调用；或把 translateEndpoint 指向你自己的翻译服务（POST {\"text\",\"from\",\"to\"} → {\"text\"}）"
        )

    private fun listModels(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        try {
            File(MainActivity.INSTANCE?.filesDir, "models").listFiles()?.forEach { arr.put(it.name) }
        } catch (_: Exception) {
        }
        return arr
    }

    /** 走配置的端点翻译一段文本（阻塞，调用方负责放后台线程） */
    fun doTranslate(settings: SettingsStore, text: String, from: String, to: String): JSONObject {
        val ep = settings.get("translateEndpoint")
        if (ep.isBlank()) {
            return JSONObject().put("ok", false)
                .put("error", "没配翻译端点（离线模型需要你自己放进 files/models/，本项目不分发模型）")
                .put("howto", "设置里把 translateEndpoint 设成能收 POST {\"text\",\"from\",\"to\"} 并回 {\"text\"} 的服务")
        }
        if (text.isBlank()) return JSONObject().put("ok", false).put("error", "要翻的文本是空的")
        return try {
            val conn = URL(ep).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("text", text).put("from", from).put("to", to).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val out = try {
                JSONObject(resp).optString("text")
            } catch (_: Exception) {
                resp
            }
            JSONObject().put("ok", code in 200..299)
                .put("status", code)
                .put("from", from).put("to", to)
                .put("text", out.take(20000))
                .put("chars", out.length)
        } catch (e: Exception) {
            JSONObject().put("ok", false)
                .put("error", (e.javaClass.simpleName + ": " + (e.message ?: "")))
        }
    }
}
