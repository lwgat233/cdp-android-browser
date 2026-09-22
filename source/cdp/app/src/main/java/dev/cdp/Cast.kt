package dev.cdp

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.regex.Pattern

/**
 * 投屏（DLNA/UPnP AVTransport）。
 *
 * 做法就是标准那三步：
 *  ① SSDP：往 239.255.255.250:1900 发 M-SEARCH（找 MediaRenderer），收响应里的 LOCATION；
 *  ② 取设备描述 XML，找出 AVTransport 服务的 controlURL；
 *  ③ SOAP POST 给 controlURL：先 SetAVTransportURI（把视频地址交给电视），再 Play。
 *
 * 只做"把地址交给电视并开始播"，不做转码推流（电视机自己去拉那个地址）。
 * 局域网里电视/盒子基本都吃这套；投屏 App 私有协议（如各家手机助手的私协议）不在范围内。
 */
class Cast(private val log: (String) -> Unit) {

    data class Device(val name: String, val controlUrl: String, val location: String)

    /** SSDP 搜索 MediaRenderer；timeoutMs 内收到的都算 */
    fun discover(timeoutMs: Int = 2500): List<Device> {
        val found = LinkedHashMap<String, Device>()
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            sock.soTimeout = 600
            sock.broadcast = true
            val msg = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
                append("\r\n")
            }.toByteArray()
            // 单播给本机也发一份：这样"设备就在本机"的离线测试也走得通
            val targets = listOf(
                InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900),
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1900)
            )
            targets.forEach { t ->
                try { sock.send(DatagramPacket(msg, msg.size, t)) } catch (_: Exception) {}
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(p)
                } catch (_: Exception) {
                    continue
                }
                val text = String(p.data, 0, p.length)
                val loc = headerOf(text, "LOCATION") ?: continue
                if (found.containsKey(loc)) continue
                val d = describe(loc) ?: continue
                found[loc] = d
            }
        } catch (e: Exception) {
            log("投屏搜索出错: ${e.message}")
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
        log("投屏：搜到 ${found.size} 个设备" + if (found.isEmpty()) "（没搜到就把电视和手机放同一个 Wi-Fi；投屏 App 的私有协议不在支持范围）" else "")
        return found.values.toList()
    }

    private fun headerOf(text: String, key: String): String? {
        for (line in text.split("\r\n")) {
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals(key, true)) return line.substring(i + 1).trim()
        }
        return null
    }

    /** 拉设备描述 XML，找 AVTransport 的 controlURL */
    fun describe(location: String): Device? {
        return try {
            val xml = httpGet(location) ?: return null
            val name = tag(xml, "friendlyName") ?: "DLNA 设备"
            // AVTransport 服务块里取 controlURL（相对地址按 base 拼绝对）
            val i = xml.indexOf("AVTransport")
            val seg = if (i >= 0) xml.substring(i, minOf(xml.length, i + 4000)) else xml
            var ctl = tag(seg, "controlURL")
            if (ctl.isNullOrBlank()) return null
            if (!ctl.startsWith("http")) {
                val base = java.net.URL(location)
                ctl = if (ctl.startsWith("/")) "${base.protocol}://${base.host}:${base.port}$ctl"
                else location.substringBeforeLast('/') + "/" + ctl
            }
            Device(name, ctl, location)
        } catch (e: Exception) {
            log("解析设备描述失败（$location）: ${e.message}")
            null
        }
    }

    private fun tag(xml: String, name: String): String? {
        val m = Pattern.compile("<$name[^>]*>(.*?)</$name>", Pattern.DOTALL).matcher(xml)
        return if (m.find()) m.group(1).trim() else null
    }

    private fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "CDP/0.1 UPnP/1.0")
        return try {
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } finally {
            try { c.disconnect() } catch (_: Exception) {}
        }
    }

    /** 把地址交给电视并开始播 */
    fun play(d: Device, videoUrl: String, title: String = ""): Pair<Boolean, String> {
        val escaped = videoUrl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val meta = if (title.isBlank()) "" else buildString {
            append("<upnp:albumArtURI></upnp:albumArtURI><dc:title>")
            append(title.replace("<", "").replace(">", ""))
            append("</dc:title>")
        }
        val setUri = soap(
            d.controlUrl, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>$escaped</CurrentURI>" +
                "<CurrentURIMetaData>&lt;DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"&gt;&lt;item&gt;$meta" +
                "&lt;res&gt;$escaped&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"
        )
        if (!setUri.first) return setUri
        val play = soap(d.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        return if (play.first) {
            log("投屏成功：${d.name} ← ${videoUrl.take(90)}")
            true to "已投到「${d.name}」"
        } else play
    }

    fun stop(d: Device): Pair<Boolean, String> =
        soap(d.controlUrl, "Stop", "<InstanceID>0</InstanceID>")

    private fun soap(controlUrl: String, action: String, body: String): Pair<Boolean, String> {
        val envelope = buildString {
            append("<?xml version=\"1.0\"?>\r\n")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
            append(body)
            append("</u:$action></s:Body></s:Envelope>")
        }
        return try {
            val u = URL(controlUrl)
            val c = u.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            c.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = try {
                (if (code in 200..299) c.inputStream else c.errorStream)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: ""
            } catch (_: Exception) { "" }
            try { c.disconnect() } catch (_: Exception) {}
            if (code in 200..299) true to "OK($code)"
            else false to "电视回绝了（HTTP $code）${text.take(120)}"
        } catch (e: Exception) {
            false to (e.message ?: "SOAP 失败")
        }
    }
}
