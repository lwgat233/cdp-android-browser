package dev.cdp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocketFactory

/**
 * 本地中继代理：浏览器（WebView）只认 127.0.0.1 上的这个 HTTP 代理，
 * 由它按设置去连真正的上游（HTTP / HTTPS / SOCKS5，支持用户名密码）。
 *
 * 为什么要自己写而不是直接用系统的 ProxyController：
 *  - ProxyController 只能设 HTTP/HTTPS 代理，**不支持 SOCKS5**；
 *  - 它也没有地方填代理账号密码（代理需要认证时直接 407）。
 * 自己起一个本地中继，这两件事就都解决了，而且能按白名单直连。
 *
 * 只监听 127.0.0.1，别的 App 不受影响。
 */
class ProxyRelay(private val settings: SettingsStore, private val log: (String) -> Unit) {

    /**
     * 字节回调：`(下行, 上行, 目标主机, 开始时刻)`。
     * 由 MainActivity 接到 `NetLog.addBytes(...)` 上——这样"网络流量统计"里的字节
     * 是**中继真实转发过的**（开着代理时≈整机浏览流量），而不是估算。
     */
    var onBytes: ((Long, Long, String, Long) -> Unit)? = null

    private var server: ServerSocket? = null
    private var acceptor: Thread? = null
    private var pool: ExecutorService? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** 起中继；返回本地端口（0 = 失败） */
    fun start(): Int {
        if (running && port > 0) return port
        return try {
            val ss = ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"))
            server = ss
            port = ss.localPort
            pool = Executors.newCachedThreadPool()
            running = true
            acceptor = Thread {
                while (running) {
                    try {
                        val c = ss.accept()
                        pool?.execute { handleQuiet(c) }
                    } catch (_: Exception) {
                        if (running) Thread.sleep(60) else break
                    }
                }
            }.apply { name = "cdp-proxy-accept"; isDaemon = true; start() }
            log("本地中继已启动：127.0.0.1:$port（上游 ${settings.proxyType()} ${settings.proxyHost()}:${settings.proxyPort()}）")
            port
        } catch (e: Exception) {
            log("本地中继启动失败: ${e.message}")
            0
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { pool?.shutdownNow() } catch (_: Exception) {}
        server = null
        pool = null
        port = 0
        log("本地中继已停止")
    }

    // ------------------------------------------------------------------ 连接处理

    private fun handleQuiet(client: Socket) {
        try {
            handle(client)
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 25000
        val cin = BufferedInputStream(client.getInputStream())
        val cout = BufferedOutputStream(client.getOutputStream())
        val head = readHead(cin) ?: return
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val parts = lines[0].split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val tConn = System.currentTimeMillis()      // 这次连接的开始时刻（字节事件要它来分桶）

        if (method == "CONNECT") {
            // 隧道（HTTPS 全走这里）
            val up = connectUpstream(target, lines.drop(1))
            if (up == null) {
                cout.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                cout.flush()
                return
            }
            cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            cout.flush()
            try {
                val upBytes = LongArray(1)
                // 请求头也是我们发出去的上行字节（以前只数隧道里的 body → 上行恒为 0，用户说"统计不对"就是它）
                upBytes[0] = head.length.toLong()
                val downBytes = LongArray(1)
                val t1 = Thread { pipe(cin, up.getOutputStream(), upBytes) }
                t1.isDaemon = true; t1.start()
                pipe(up.getInputStream(), cout, downBytes)
                t1.join(800)          // 上行那条可能还在跑（keep-alive）：等久一点再读计数
                // 一次连接结束就记一条字节事件（带目标主机 + 起止时间）→ 流量统计用它分桶/画饼图
                if (downBytes[0] > 0 || upBytes[0] > 0) {
                    try { onBytes?.invoke(downBytes[0], upBytes[0], target.substringBefore(':'), tConn) } catch (_: Exception) {}
                }
            } finally {
                try { up.close() } catch (_: Exception) {}
            }
            return
        }

        // 明文 HTTP：上游是 SOCKS5 时我们直接隧道到目标，请求行必须换成 origin-form
        // （绝对 URI 只有 HTTP 代理才认；以前这里是原样转发，走 SOCKS5 时目标直接解析不了 →
        //  现象是"代理连上了但页面空白"，第八组抓出来的）。
        if (target.startsWith("http://")) {
            val hostPort = hostPortOf(target)
            val up = connectUpstream(hostPort, lines.drop(1))
            if (up == null) {
                cout.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                cout.flush()
                return
            }
            // 请求行一律换成 origin-form：connectUpstream 无论哪种上游类型都是先建隧道
            // （HTTP 代理走 CONNECT、SOCKS5 走握手），隧道对面的目标只认 origin-form；
            // 绝对 URI 是"不建隧道、直接把请求丢给代理"那一套，这里用不上（踩过：
            // 走 HTTP 上游时目标收到绝对 URI 解析不了 → 代理连上了但页面空白）。
            val u = java.net.URI(target)
            val origin = (if (u.rawPath.isNullOrEmpty()) "/" else u.rawPath) +
                (if (u.rawQuery != null) "?" + u.rawQuery else "")
            val outLines = listOf("$method $origin HTTP/1.1") +
                lines.drop(1).filterNot {
                    it.startsWith("Proxy-Connection:", true) || it.startsWith("Proxy-Authorization:", true)
                }
            val uo = BufferedOutputStream(up.getOutputStream())
            val headSent = (outLines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            uo.write(headSent)
            uo.flush()
            try {
                val upBytes = LongArray(1)
                // 请求头也是上行字节（以前是直接写出去的，没进计数 → 上行恒 0）
                upBytes[0] = headSent.size.toLong()
                val downBytes = LongArray(1)
                val t1 = Thread { pipe(cin, uo, upBytes) }
                t1.isDaemon = true; t1.start()
                pipe(up.getInputStream(), cout, downBytes)
                t1.join(800)          // 上行那条可能还在跑（keep-alive）：等久一点再读计数
                if (downBytes[0] > 0 || upBytes[0] > 0) {
                    try { onBytes?.invoke(downBytes[0], upBytes[0], java.net.URI(target).host ?: "", tConn) } catch (_: Exception) {}
                }
            } finally {
                try { up.close() } catch (_: Exception) {}
            }
            return
        }

        cout.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
        cout.flush()
    }

    private fun readHead(ins: InputStream): String? {
        val sb = StringBuilder()
        val buf = ByteArray(1)
        while (sb.length < 65536) {
            val n = ins.read(buf)
            if (n < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(buf[0].toInt().toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    private fun hostPortOf(url: String): String {
        val noScheme = url.removePrefix("http://")
        val slash = noScheme.indexOf('/')
        val hp = if (slash >= 0) noScheme.substring(0, slash) else noScheme
        return if (hp.contains(':')) hp else "$hp:80"
    }

    private fun pipe(ins: InputStream, outs: OutputStream, counter: LongArray? = null) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                outs.write(buf, 0, n)
                outs.flush()
                if (counter != null) counter[0] += n.toLong()      // 数一下真过了多少字节
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ 连上游

    /** @return 已连好的上游 socket；失败返回 null */
    private fun connectUpstream(hostPort: String, headers: List<String>): Socket? {
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        return try {
            when (settings.proxyType()) {
                "socks5" -> socks5Connect(host, port)
                "https" -> httpProxyConnect(host, port, tls = true)
                "http" -> httpProxyConnect(host, port, tls = false)
                // 自代理模式（用户第 16 条）：没有上游代理，但还是让浏览器走本地中继 ——
                // 这样页面真实流量都会过我们这一层，统计口径就完整了（相当于自己当自己的代理）。
                else -> if (settings.selfProxy()) directConnect(host, port) else null
            }
        } catch (e: Exception) {
            log("连上游失败 ${settings.proxyType()} ${settings.proxyHost()}:${settings.proxyPort()} → $hostPort：${e.message}")
            null
        }
    }

    /** 直连（自代理模式用）：连目标主机本身 */
    private fun directConnect(host: String, port: Int): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 15000)
        s.soTimeout = 25000
        return s
    }

    /** 通过 HTTP(S) 代理建隧道 */
    private fun httpProxyConnect(host: String, port: Int, tls: Boolean): Socket {
        val ph = settings.proxyHost()
        val pp = settings.proxyPort()
        val raw = Socket()
        raw.connect(InetSocketAddress(ph, pp), 15000)
        raw.soTimeout = 25000
        val sock: Socket = if (tls) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, ph, pp, true)
        } else raw
        val out = sock.getOutputStream()
        val auth = basicAuthHeader()
        val req = buildString {
            append("CONNECT $host:$port HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Proxy-Connection: keep-alive\r\n")
            if (auth != null) append("Proxy-Authorization: $auth\r\n")
            append("\r\n")
        }
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        val status = readStatusLine(sock.getInputStream())
        if (status == null || !status.contains(" 200")) {
            try { sock.close() } catch (_: Exception) {}
            throw java.io.IOException("上游代理拒绝: ${status ?: "无响应"}")
        }
        return sock
    }

    private fun readStatusLine(ins: InputStream): String? {
        val sb = StringBuilder()
        var b = ByteArray(1)
        var count = 0
        // 读到首个 \r\n（状态行），然后继续读到空行
        while (count < 8192) {
            val n = ins.read(b)
            if (n < 0) break
            count++
            sb.append(b[0].toInt().toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        val text = sb.toString()
        return text.split("\r\n").firstOrNull { it.isNotBlank() }
    }

    private fun basicAuthHeader(): String? {
        val u = settings.proxyUser()
        if (u.isBlank()) return null
        val raw = "$u:${settings.proxyPass()}"
        return "Basic " + android.util.Base64.encodeToString(raw.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** SOCKS5 握手 + CONNECT */
    private fun socks5Connect(host: String, port: Int): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(settings.proxyHost(), settings.proxyPort()), 15000)
        s.soTimeout = 20000
        val ins = s.getInputStream()
        val out = s.getOutputStream()
        val user = settings.proxyUser()
        val pass = settings.proxyPass()

        // 问候：支持无认证 + 用户名密码
        val methods = if (user.isNotBlank()) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00)
        out.write(methods); out.flush()
        val resp = ByteArray(2)
        readFully(ins, resp)
        if (resp[0].toInt() != 0x05) throw java.io.IOException("不是 SOCKS5 应答")
        when (resp[1].toInt()) {
            0x00 -> { /* 免认证 */ }
            0x02 -> {
                val ub = user.toByteArray(Charsets.UTF_8)
                val pb = pass.toByteArray(Charsets.UTF_8)
                val buf = ByteArray(3 + ub.size + pb.size)
                buf[0] = 0x01; buf[1] = ub.size.toByte(); System.arraycopy(ub, 0, buf, 2, ub.size)
                buf[2 + ub.size] = pb.size.toByte(); System.arraycopy(pb, 0, buf, 3 + ub.size, pb.size)
                out.write(buf); out.flush()
                val ar = ByteArray(2)
                readFully(ins, ar)
                if (ar[1].toInt() != 0x00) throw java.io.IOException("SOCKS5 认证被拒")
            }
            else -> throw java.io.IOException("代理不接受我们的认证方式(${resp[1]})")
        }

        // CONNECT 请求
        val hb = host.toByteArray(Charsets.UTF_8)
        val req = ByteArray(7 + hb.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = hb.size.toByte(); System.arraycopy(hb, 0, req, 5, hb.size)
        req[5 + hb.size] = ((port shr 8) and 0xff).toByte()
        req[6 + hb.size] = (port and 0xff).toByte()
        out.write(req); out.flush()
        val head = ByteArray(4)
        readFully(ins, head)
        if (head[1].toInt() != 0x00) throw java.io.IOException("SOCKS5 连接被拒(代码 ${head[1]})")
        // 吃掉绑定地址
        val skip = when (head[3].toInt()) {
            0x01 -> 4 + 2
            0x04 -> 16 + 2
            0x03 -> {
                val l = ByteArray(1); readFully(ins, l); l[0].toInt() + 2
            }
            else -> 0
        }
        if (skip > 0) readFully(ins, ByteArray(skip))
        return s
    }

    private fun readFully(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.IOException("连接提前结束")
            off += n
        }
    }
}
