package dev.cdp

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 只用来验证投屏链路的「假电视」：本机上开一个
 *  ① UDP :1900 应答 SSDP M-SEARCH（回 LOCATION 指向本机 HTTP）；
 *  ② HTTP 服务提供设备描述 XML 与 AVTransport 控制端点（记录收到的 SetAVTransportURI/Play）。
 * 这样"搜索 → 解析 → 下发地址 → 播放"整条链路不用真电视也能验。
 */
class TestRenderer(private val log: (String) -> Unit) {

    private var udp: DatagramSocket? = null
    private var http: ServerSocket? = null
    private var httpPort = 0
    private var udpPort = 0

    @Volatile
    var lastUri = ""

    @Volatile
    var lastAction = ""

    @Volatile
    var playCount = 0

    val calls = JSONArray()

    val isRunning: Boolean get() = udp != null

    fun start(udpPort: Int = 1900): Boolean {
        stop()
        return try {
            httpPort = 0
            val hs = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            http = hs
            httpPort = hs.localPort
            Thread {
                while (http != null) {
                    try {
                        val c = hs.accept()
                        Thread { serveHttp(c) }.apply { isDaemon = true; start() }
                    } catch (_: Exception) {
                        if (http == null) break
                    }
                }
            }.apply { name = "cdp-faketv-http"; isDaemon = true; start() }

            val us = DatagramSocket(udpPort, InetAddress.getByName("127.0.0.1"))
            udp = us
            this.udpPort = udpPort
            Thread {
                val buf = ByteArray(4096)
                while (udp != null) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        us.receive(p)
                        val req = String(p.data, 0, p.length)
                        if (req.contains("M-SEARCH", true)) {
                            val loc = "http://127.0.0.1:$httpPort/desc.xml"
                            val resp = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("CACHE-CONTROL: max-age=1800\r\n")
                                append("EXT:\r\n")
                                append("LOCATION: $loc\r\n")
                                append("SERVER: CDP-FakeTV/1.0 UPnP/1.0\r\n")
                                append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
                                append("USN: uuid:cdp-fake-tv::urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
                                append("\r\n")
                            }.toByteArray()
                            us.send(DatagramPacket(resp, resp.size, p.address, p.port))
                            log("假电视：应答了一次 SSDP 搜索（LOCATION=$loc）")
                        }
                    } catch (_: Exception) {
                        if (udp == null) break
                    }
                }
            }.apply { name = "cdp-faketv-ssdp"; isDaemon = true; start() }
            log("假电视已启动：SSDP :$udpPort，HTTP :$httpPort")
            true
        } catch (e: Exception) {
            log("假电视起不来: ${e.message}")
            false
        }
    }

    fun stop() {
        try { udp?.close() } catch (_: Exception) {}
        try { http?.close() } catch (_: Exception) {}
        udp = null
        http = null
    }

    fun statsJson(): JSONObject = JSONObject()
        .put("running", isRunning)
        .put("httpPort", httpPort)
        .put("udpPort", udpPort)
        .put("lastAction", lastAction)
        .put("lastUri", lastUri)
        .put("playCount", playCount)
        .put("calls", synchronized(calls) { JSONArray(calls.toString()) })

    private fun serveHttp(c: Socket) {
        try {
            val ins = BufferedInputStream(c.getInputStream())
            val head = readHead(ins) ?: return
            val lines = head.split("\r\n").filter { it.isNotEmpty() }
            val first = lines.firstOrNull() ?: return
            val path = first.split(" ").getOrNull(1) ?: "/"
            val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = if (len > 0) {
                val b = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = ins.read(b, off, len - off)
                    if (n < 0) break
                    off += n
                }
                String(b, 0, off, Charsets.UTF_8)
            } else ""

            val out = c.getOutputStream()
            if (path.startsWith("/desc.xml")) {
                val xml = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>CDP 假电视</friendlyName>
    <manufacturer>CDP</manufacturer>
    <modelName>FakeTV</modelName>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/avt/control</controlURL>
        <eventSubURL>/avt/event</eventSubURL>
        <SCPDURL>/avt/scpd.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""
                val bytes = xml.toByteArray(Charsets.UTF_8)
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.write(bytes)
            } else if (path.startsWith("/avt/control")) {
                val action = Regex("""<(?:u:)?([A-Za-z]+)\s+xmlns""").find(body)?.groupValues?.get(1)
                    ?: Regex("""</u:([A-Za-z]+)>""").find(body)?.groupValues?.get(1) ?: "?"
                val uri = Regex("""<CurrentURI>(.*?)</CurrentURI>""").find(body)?.groupValues?.get(1) ?: ""
                lastAction = action
                if (uri.isNotBlank()) lastUri = uri
                if (action == "Play") playCount++
                synchronized(calls) {
                    calls.put(JSONObject().put("action", action).put("uri", uri).put("ts", System.currentTimeMillis()))
                    while (calls.length() > 50) {
                        val arr = JSONArray()
                        for (i in 1 until calls.length()) arr.put(calls.optJSONObject(i))
                        while (calls.length() > 0) calls.remove(0)
                        for (i in 0 until arr.length()) calls.put(arr.optJSONObject(i))
                    }
                }
                log("假电视收到 SOAP：$action ${uri.take(60)}")
                val xml = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:$action""".trimIndent() +
                    """Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"></u:""" +
                    "${action}Response></s:Body></s:Envelope>"
                val bytes = xml.toByteArray(Charsets.UTF_8)
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.write(bytes)
            } else {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun readHead(ins: BufferedInputStream): String? {
        val sb = StringBuilder()
        val b = ByteArray(1)
        while (sb.length < 16384) {
            val n = ins.read(b)
            if (n < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b[0].toInt().toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }
}
