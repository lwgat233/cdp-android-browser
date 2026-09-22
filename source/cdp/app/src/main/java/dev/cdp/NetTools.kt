package dev.cdp

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.security.cert.X509Certificate

/**
 * 网站安全 / 网络工具（离线可测，不依赖外网）：
 *  - 证书探测：连上去自己握手，读对端证书（主体 / 签发者 / 有效期 / 是否自签 / TLS 版本）
 *  - 域名解析：本地解析出的 IP（"本地 IP 对应实际地址、服务器 IP 对应地址"这两项）
 *  - 通路测试：对 host:port 建 TCP 连接并计时
 *  - 测速：从指定地址下载 N 毫秒，算实际速率（验收用 App 自己的本地测试源，不联网）
 */
object NetTools {

    fun localIps(): JSONArray {
        val out = JSONArray()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    val ip = a.hostAddress ?: continue
                    if (ip.contains(':')) continue
                    out.put(
                        JSONObject().put("iface", nif.name).put("ip", ip)
                            .put("host", try { a.canonicalHostName } catch (_: Exception) { "" })
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun resolve(host: String): JSONObject {
        return try {
            val addrs = InetAddress.getAllByName(host)
            val arr = JSONArray()
            for (a in addrs) {
                arr.put(
                    JSONObject().put("ip", a.hostAddress)
                        .put("reverse", try { a.canonicalHostName } catch (_: Exception) { "" })
                )
            }
            JSONObject().put("ok", true).put("host", host).put("list", arr)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("host", host).put("error", e.message ?: "解析失败")
        }
    }

    /** 证书探测：不校验信任链（我们要看的就是"它到底给了什么证书"） */
    fun cert(host: String, port: Int = 443, timeoutMs: Int = 6000): JSONObject {
        var s: SSLSocket? = null
        return try {
            val f = SSLSocketFactory.getDefault() as SSLSocketFactory
            s = f.createSocket() as SSLSocket
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.startHandshake()
            val sess = s.session
            val cert = sess.peerCertificates.firstOrNull() as? X509Certificate
            val out = JSONObject()
                .put("ok", true)
                .put("host", host)
                .put("port", port)
                .put("protocol", sess.protocol)
                .put("cipher", sess.cipherSuite)
            if (cert != null) {
                val now = System.currentTimeMillis()
                out.put("subject", cert.subjectDN.name)
                    .put("issuer", cert.issuerDN.name)
                    .put("notBefore", cert.notBefore.time)
                    .put("notAfter", cert.notAfter.time)
                    .put("expired", cert.notAfter.time < now)
                    .put("notYetValid", cert.notBefore.time > now)
                    .put("serial", cert.serialNumber.toString(16))
                    .put("sigAlg", cert.sigAlgName)
                    // 自签：主体==签发者（真实站点基本不会相等）
                    .put("selfSigned", cert.subjectDN.name == cert.issuerDN.name)
                    .put("daysLeft", (cert.notAfter.time - now) / 86400000L)
            } else {
                out.put("error", "对端没有给证书（可能是明文连接）")
            }
            out
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("host", host).put("error", e.message ?: "握手失败")
        } finally {
            try {
                s?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 通路测试：能不能连上、连了多久 */
    /**
     * 一个网址的 **IP + 请求头 + 响应头**（用户清单第 18 条："知道对应网址的IP、cdn请求header和接收header等等信息"）。
     * 做法：解析域名拿 IP（本地解析 + 实际连上的那个地址），发一次真实请求，
     * 把"我们发出去的头"和"服务器回来的头"都原样列出来（Server / Via / X-Cache / CF-* 这些就是 CDN 的指纹）。
     * 边界：只发一次 GET（不等正文），拿不到正文大小；重定向默认跟随（会记下跳转链）。
     */
    fun headers(url: String, ua: String = "", referer: String = "", timeoutMs: Int = 8000): JSONObject {
        if (url.isBlank()) return JSONObject().put("ok", false).put("error", "先填一个网址")
        val full = if (url.startsWith("http")) url else "https://$url"
        return try {
            val u = URL(full)
            val host = u.host
            val ips = JSONArray()
            try {
                InetAddress.getAllByName(host).forEach { ips.put(it.hostAddress ?: "") }
            } catch (_: Exception) {
            }
            val conn = (u.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                if (ua.isNotBlank()) setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "*/*")
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            }
            val code = conn.responseCode
            val serverIp = try { conn.getHeaderField("X-Server-IP") ?: "" } catch (_: Exception) { "" }
            val resp = JSONObject()
            conn.headerFields.forEach { (k, v) ->
                if (k == null) return@forEach
                resp.put(k, (v ?: emptyList<String>()).joinToString(", "))
            }
            // 请求头：把"我们确实发出去的那几个"原样列出来（HttpURLConnection 会自己补 Host/Connection 等）
            val req = JSONObject()
            req.put("User-Agent", if (ua.isNotBlank()) ua else "（WebView 默认手机 UA）")
            req.put("Accept", "*/*")
            if (referer.isNotBlank()) req.put("Referer", referer)
            req.put("Host", host)
            val chain = JSONArray()
            var cur: java.net.URL? = u
            var guard = 0
            while (cur != null && guard++ < 5) {
                chain.put(cur.toString())
                cur = try { conn.url } catch (_: Exception) { null }
                if (guard == 1 && conn.url != u) { chain.put(conn.url.toString()) } else { cur = null }
            }
            try { conn.disconnect() } catch (_: Exception) {}
            JSONObject().put("ok", true)
                .put("url", full).put("host", host)
                .put("ips", ips).put("serverIp", serverIp)
                .put("status", code)
                .put("requestHeaders", req)
                .put("responseHeaders", resp)
                .put("redirects", conn.url.toString())
                .put("note", "响应头里 Server / Via / X-Cache / CF-* 这些就是 CDN 的指纹；" +
                    "本地解析 IP 与「服务器 IP」可能不同（CDN 会按就近调度）")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", (e.javaClass.simpleName + ": " + (e.message ?: "")))
        }
    }

    fun tcpTest(host: String, port: Int, timeoutMs: Int = 5000): JSONObject {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { sk ->
                sk.connect(InetSocketAddress(host, port), timeoutMs)
                JSONObject().put("ok", true).put("host", host).put("port", port)
                    .put("ms", System.currentTimeMillis() - t0)
                    .put("localPort", sk.localPort)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("host", host).put("port", port)
                .put("ms", System.currentTimeMillis() - t0)
                .put("error", (e.javaClass.simpleName + ": " + (e.message ?: "")))
        }
    }

    /** 测速：从 url 拉 dumpMs 毫秒，报实际字节与速率（验收用本地测试源，不联网） */
    fun speedTest(url: String, dumpMs: Int = 3000): JSONObject {
        val t0 = System.currentTimeMillis()
        var bytes = 0L
        return try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 5000
            conn.readTimeout = dumpMs + 2000
            val buf = ByteArray(64 * 1024)
            conn.getInputStream().use { ins ->
                while (System.currentTimeMillis() - t0 < dumpMs) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    bytes += n
                }
            }
            val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
            JSONObject().put("ok", true).put("url", url).put("bytes", bytes).put("ms", ms)
                .put("kbps", (bytes * 8.0 / ms).toLong())
                .put("mBps", String.format("%.2f", bytes / 1048576.0 / (ms / 1000.0)).toDouble())
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("url", url)
                .put("error", (e.javaClass.simpleName + ": " + (e.message ?: "")))
        }
    }
}
