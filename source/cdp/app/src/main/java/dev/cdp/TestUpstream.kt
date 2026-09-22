package dev.cdp

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 只用来验证的「上游代理」：同一端口上既会说 SOCKS5、也会说 HTTP CONNECT，
 * 可以要求用户名密码认证。
 *
 * 为什么需要它：模拟器在容器里，宿主机上的代理服务它访问不到；而验证代理功能必须
 * 有一个真实的、可认证的上游。于是把上游做进 debug 包，链路变成
 * 「浏览器 → 本地中继 → 这个上游 → 目标」，全程离线可复现。
 *
 * 只在 debug 构件里注册入口。
 */
class TestUpstream(private val log: (String) -> Unit) {

    private var server: ServerSocket? = null
    private var pool = Executors.newCachedThreadPool()

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var requireAuth = false

    @Volatile
    var user = ""

    @Volatile
    var pass = ""

    val conns = AtomicInteger()
    val ok = AtomicInteger()
    val fail = AtomicInteger()
    val authFails = AtomicInteger()

    @Volatile
    var lastTarget = ""

    /** 最近见过的目标（含类型）。Chromium 自己也会走代理（autofill/generate_204 之类），
     *  只留 lastTarget 会被这些旁路流量冲掉 —— 断言要能在历史里找，而不是比"最后一个"。 */
    val targets = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** 成功完成的目标（断言用这个：旁路流量失败也不该让我们的断言变红） */
    val okTargets = java.util.concurrent.ConcurrentLinkedDeque<String>()

    @Volatile
    var lastMode = ""

    @Volatile
    var lastKind = ""

    val isRunning: Boolean get() = server != null

    fun start(port: Int, user: String?, pass: String?): Int {
        stop()
        return try {
            val ss = ServerSocket(port, 32, java.net.InetAddress.getByName("127.0.0.1"))
            server = ss
            this.port = ss.localPort
            this.user = user ?: ""
            this.pass = pass ?: ""
            this.requireAuth = !this.user.isBlank()
            conns.set(0); ok.set(0); fail.set(0); authFails.set(0)
            pool = Executors.newCachedThreadPool()
            Thread {
                while (server != null) {
                    try {
                        val c = ss.accept()
                        pool.execute { handleQuiet(c) }
                    } catch (_: Exception) {
                        if (server == null) break
                    }
                }
            }.apply { name = "cdp-testup"; isDaemon = true; start() }
            log("测试上游代理已启动 127.0.0.1:${this.port}（认证=${if (requireAuth) "要" else "不要"} ${this.user}）")
            this.port
        } catch (e: Exception) {
            log("测试上游起不来: ${e.message}")
            0
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        port = 0
    }

    /** 从绝对 URI 里取 host:port（记成功目标用） */
    private fun hpOf(target: String): String = try {
        val u = java.net.URI(target)
        val p = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        "${u.host}:$p"
    } catch (_: Exception) {
        target.take(60)
    }

    private fun markOk(kind: String, target: String) {
        okTargets.addFirst("$kind $target")
        while (okTargets.size > 30) okTargets.removeLast()
    }

    private fun record(kind: String, target: String) {
        targets.addFirst("$kind $target")
        while (targets.size > 30) targets.removeLast()
    }

    fun statsJson(): JSONObject = JSONObject()
        .put("running", isRunning)
        .put("port", port)
        .put("requireAuth", requireAuth)
        .put("conns", conns.get())
        .put("ok", ok.get())
        .put("fail", fail.get())
        .put("authFails", authFails.get())
        .put("lastTarget", lastTarget)
        .put("targets", org.json.JSONArray(targets.toList()))
        .put("okTargets", org.json.JSONArray(okTargets.toList()))
        .put("lastMode", lastMode)
        .put("lastKind", lastKind)

    private fun handleQuiet(c: Socket) {
        try {
            handle(c)
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 20000
        conns.incrementAndGet()
        val ins = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        ins.mark(8)
        val b0 = ins.read()
        ins.reset()
        if (b0 == 0x05) socks5(c, ins, out) else httpProxy(c, ins, out)
    }

    // ------------------------------------------------------------------ SOCKS5

    private fun socks5(c: Socket, ins: InputStream, out: OutputStream) {
        lastMode = "socks5"
        val hello = ByteArray(2)
        readFully(ins, hello)
        val n = hello[1].toInt()
        val methods = ByteArray(n)
        readFully(ins, methods)
        if (requireAuth) {
            out.write(byteArrayOf(0x05, 0x02)); out.flush()
            val h = ByteArray(2); readFully(ins, h)
            if (h[0].toInt() != 0x01) { authFails.incrementAndGet(); fail.incrementAndGet(); return }
            val ub = ByteArray(h[1].toInt()); readFully(ins, ub)
            val pl = ByteArray(1); readFully(ins, pl)
            val pb = ByteArray(pl[0].toInt()); readFully(ins, pb)
            val u = String(ub); val p = String(pb)
            if (u != user || p != pass) {
                authFails.incrementAndGet()
                fail.incrementAndGet()
                out.write(byteArrayOf(0x01, 0x01)); out.flush()
                return
            }
            out.write(byteArrayOf(0x01, 0x00)); out.flush()
        } else {
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
        }
        val req = ByteArray(4)
        readFully(ins, req)
        if (req[1].toInt() != 0x01) { fail.incrementAndGet(); return }
        val host: String
        when (req[3].toInt()) {
            0x01 -> { val a = ByteArray(4); readFully(ins, a); host = a.joinToString(".") { (it.toInt() and 0xff).toString() } }
            0x03 -> { val l = ByteArray(1); readFully(ins, l); val h = ByteArray(l[0].toInt()); readFully(ins, h); host = String(h) }
            0x04 -> { val a = ByteArray(16); readFully(ins, a); host = "ipv6" }
            else -> { fail.incrementAndGet(); return }
        }
        val pb = ByteArray(2); readFully(ins, pb)
        val targetPort = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)
        lastTarget = "$host:$targetPort"
        record("socks5", lastTarget)
        val up = try {
            Socket().apply { connect(InetSocketAddress(host, targetPort), 8000) }
        } catch (e: Exception) {
            fail.incrementAndGet()
            out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
            return
        }
        ok.incrementAndGet()
        markOk("socks5", lastTarget)
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
        relay(ins, out, up)
    }

    // ------------------------------------------------------------------ HTTP CONNECT

    private fun httpProxy(c: Socket, ins: InputStream, out: OutputStream) {
        lastMode = "http"
        val head = readHead(ins) ?: return
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val parts = lines[0].split(" ")
        if (parts.size < 2) { fail.incrementAndGet(); return }

        // 认证（两种形态都要过）
        if (requireAuth && !authOk(lines)) {
            authFails.incrementAndGet()
            fail.incrementAndGet()
            out.write(
                ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"cdp-test\"\r\nContent-Length: 0\r\n\r\n").toByteArray()
            )
            out.flush()
            return
        }

        if (parts[0].equals("CONNECT", true)) {
            val hp = parts[1]
            val host = hp.substringBeforeLast(':')
            val targetPort = hp.substringAfterLast(':').toIntOrNull() ?: 443
            lastKind = "connect"
            lastTarget = hp
            record("http-connect", hp)
            val up = try {
                Socket().apply { connect(InetSocketAddress(host, targetPort), 8000) }
            } catch (e: Exception) {
                fail.incrementAndGet()
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            ok.incrementAndGet()
            markOk("http-connect", hp)
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            relay(ins, out, up)
            return
        }

        // 普通代理请求（绝对 URI 形态，纯 HTTP 走这条）——把它换成 origin-form 转给目标
        val target = parts[1]
        if (!target.startsWith("http://")) {
            out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            fail.incrementAndGet()
            return
        }
        val hp = hostPortOf(target)
        lastKind = "absolute"
        lastTarget = hp
        val host = hp.substringBeforeLast(':')
        val targetPort = hp.substringAfterLast(':').toIntOrNull() ?: 80
        val up = try {
            Socket().apply { connect(InetSocketAddress(host, targetPort), 8000) }
        } catch (e: Exception) {
            fail.incrementAndGet()
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        ok.incrementAndGet()
        markOk("http-absolute", hpOf(target))
        val u = java.net.URI(target)
        val origin = (if (u.rawPath.isNullOrEmpty()) "/" else u.rawPath) +
            (if (u.rawQuery != null) "?" + u.rawQuery else "")
        val version = if (parts.size > 2) parts[2] else "HTTP/1.1"
        val newLines = listOf("${parts[0]} $origin $version") +
            lines.drop(1).filterNot {
                it.startsWith("Proxy-Connection:", true) || it.startsWith("Proxy-Authorization:", true)
            }
        val uo = up.getOutputStream()
        uo.write((newLines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        uo.flush()
        relay(ins, out, up)
    }

    private fun hostPortOf(url: String): String {
        val noScheme = url.removePrefix("http://")
        val slash = noScheme.indexOf('/')
        val hp = if (slash >= 0) noScheme.substring(0, slash) else noScheme
        return if (hp.contains(':')) hp else "$hp:80"
    }

    private fun authOk(lines: List<String>): Boolean {
        val auth = lines.firstOrNull { it.startsWith("Proxy-Authorization:", true) } ?: return false
        val expected = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        val got = auth.substringAfter(':').trim()
        return got.equals("Basic $expected", false)
    }

    private fun relay(ins: InputStream, out: OutputStream, up: Socket) {
        try {
            val t = Thread { pipe(up.getInputStream(), out) }
            t.isDaemon = true; t.start()
            pipe(ins, up.getOutputStream())
            t.join(400)
        } finally {
            try { up.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(ins: InputStream, outs: OutputStream) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                outs.write(buf, 0, n); outs.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readHead(ins: InputStream): String? {
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

    private fun readFully(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.IOException("提前结束")
            off += n
        }
    }
}
