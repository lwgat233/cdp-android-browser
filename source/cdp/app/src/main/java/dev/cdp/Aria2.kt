package dev.cdp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * aria2 外部下载（log.md 第 6 条："提供 aria2 等等下载方式"）。
 *
 * 做法：把要下载的地址通过 **aria2 的 JSON-RPC** 提交给用户自己的 aria2（可以跑在 NAS / 电脑上），
 * 由 aria2 去下；App 只读回 gid 当作凭据（不假装自己在下）。
 *
 * 请求（标准 aria2 JSON-RPC）：
 *   POST <rpc>  {"jsonrpc":"2.0","id":"cdp","method":"aria2.addUri",
 *                "params":[["token:<token>"],"<url>",{"dir":"<dir>","out":"<name>"}]}
 * 回包：{"jsonrpc":"2.0","id":"cdp","result":"<gid>"} ；出错时是 {"error":{...}}。
 *
 * 约定：没配 RPC 地址就明确报"没配"，绝不假装提交成功。
 */
object Aria2 {

    fun submit(rpc: String, token: String, url: String, dir: String, name: String): JSONObject {
        if (rpc.isBlank()) return JSONObject().put("ok", false).put("error", "还没配 aria2 的 RPC 地址（设置里填，例如 http://192.168.1.10:6800/jsonrpc）")
        if (url.isBlank()) return JSONObject().put("ok", false).put("error", "没有要下载的地址")
        val params = JSONArray().put(JSONArray().apply { if (token.isNotBlank()) put("token:$token") }).put(url)
        val opts = JSONObject()
        if (dir.isNotBlank()) opts.put("dir", dir)
        if (name.isNotBlank()) opts.put("out", name)
        if (opts.length() > 0) params.put(opts)
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", "cdp").put("method", "aria2.addUri").put("params", params)
        return try {
            val conn = (URL(rpc).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            try { conn.disconnect() } catch (_: Exception) {}
            val o = try { JSONObject(text) } catch (_: Exception) { JSONObject().put("raw", text.take(300)) }
            val gid = o.optString("result")
            if (code in 200..299 && gid.isNotBlank()) {
                JSONObject().put("ok", true).put("gid", gid).put("via", "aria2")
                    .put("note", "已交给 aria2（gid $gid），下载进度在 aria2 那边看")
            } else {
                JSONObject().put("ok", false)
                    .put("error", "aria2 没接受：" + (o.optJSONObject("error")?.optString("message") ?: text.take(160)))
                    .put("http", code)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "连不上 aria2（${e.javaClass.simpleName}: ${e.message}）")
        }
    }

    /** 试连通（"试一下"按钮用）：调 aria2.getVersion —— 只读，不改对方任何状态 */
    fun version(rpc: String, token: String): JSONObject {
        if (rpc.isBlank()) return JSONObject().put("ok", false).put("error", "还没填 RPC 地址")
        val params = JSONArray().put(JSONArray().apply { if (token.isNotBlank()) put("token:$token") })
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "cdp").put("method", "aria2.getVersion")
            .put("params", params)
        return try {
            val conn = (URL(rpc).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 6000; readTimeout = 10000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val o = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (o.optJSONObject("result") != null) {
                JSONObject().put("ok", true).put("version", o.getJSONObject("result").optString("version"))
                    .put("note", "aria2 通了（版本 " + o.getJSONObject("result").optString("version") + "）")
            } else {
                JSONObject().put("ok", false)
                    .put("error", o.optJSONObject("error")?.optString("message") ?: ("HTTP " + code + " " + text.take(120)))
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "连不上（" + e.message + "）")
        }
    }

    /** 问一下 aria2 自己：这个 gid 现在什么进度（模式：只读，不改对方状态） */
    fun tellStatus(rpc: String, token: String, gid: String): JSONObject {
        if (rpc.isBlank() || gid.isBlank()) return JSONObject().put("ok", false).put("error", "缺 RPC 地址或 gid")
        val params = JSONArray().put(JSONArray().apply { if (token.isNotBlank()) put("token:$token") }).put(gid)
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "cdp").put("method", "aria2.tellStatus")
            .put("params", params)
        return try {
            val conn = (URL(rpc).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val o = JSONObject(text)
            val r = o.optJSONObject("result")
            if (r == null) {
                JSONObject().put("ok", false).put("error", o.optJSONObject("error")?.optString("message") ?: "读不到")
            } else {
                JSONObject().put("ok", true)
                    .put("status", r.optString("status"))
                    .put("total", r.optString("totalLength"))
                    .put("done", r.optString("completedLength"))
                    .put("speed", r.optString("downloadSpeed"))
                    .put("gid", gid)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "连不上 aria2（${e.message}）")
        }
    }
}
