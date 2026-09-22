package dev.cdp

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密码存储（mydoc 清单第 4 条）。
 *
 * 做法与边界（都如实写在返回里）：
 *  · 落盘是 **AES/GCM 加密**（密钥在 Android Keystore 里，不在代码里、不在文件里），
 *    文件是 files/vault.enc（salt+IV+密文）；直接读文件看不到明文密码。
 *  · **看/改都要先过锁屏验证**（KeyguardManager 的设备凭据，REQ=9201）；
 *    没解锁时 list/get/add/del 一律拒绝，只回 needUnlock=true。
 *  · 解锁只在内存里生效 N 分钟（默认 5 分钟），`vault.lock` 立刻锁上。
 *  · 随机密码：长度 + 大写/小写/数字/符号 四类各自可开关（第 4 条要求"用户可自定义哪几类"）。
 *  · 页面登录时"要不要保存"由 App 弹一条提示条，用户点了才存（`vault.capture`）。
 *  没做/做不到的也写清：不自动填充到所有站点（要用户主动点"填这一页"）；密码不会进日志。
 */
class PassVault(private val act: Activity) {
    private val file = File(act.filesDir, "vault.enc")
    @Volatile private var unlockedUntil = 0L
    @Volatile private var pendingCapture: JSONObject? = null

    private fun unlocked() = System.currentTimeMillis() < unlockedUntil

    // ---------------------------------------------------------------- 加解密
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun readAll(): JSONArray {
        if (!file.exists()) return JSONArray()
        return try {
            val raw = file.readBytes()
            val ivLen = raw[0].toInt()
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val body = raw.copyOfRange(1 + ivLen, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            JSONArray(String(c.doFinal(body), Charsets.UTF_8))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeAll(arr: JSONArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val body = c.doFinal(arr.toString().toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + body.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(body, 0, out, 1 + iv.size, body.size)
        file.writeBytes(out)
    }

    // ---------------------------------------------------------------- 解锁
    fun state(): JSONObject = JSONObject()
        .put("ok", true)
        .put("unlocked", unlocked())
        .put("secondsLeft", if (unlocked()) ((unlockedUntil - System.currentTimeMillis()) / 1000) else 0)
        .put("count", if (unlocked()) readAll().length() else -1)
        .put("file", file.absolutePath)
        .put("encrypted", true)
        .put(
            "note",
            "密码是 AES/GCM 加密存的（密钥在 Android Keystore）；看和改都要先过锁屏验证。" +
                "没解锁时只回 needUnlock=true，不会吐任何条目。"
        )

    fun requestUnlock(): JSONObject {
        return try {
            val km = act.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardSecure) {
                return JSONObject().put("ok", false).put("needUnlock", true)
                    .put("error", "这台设备没设锁屏（PIN/图案/密码），按第 4 条的要求就不能查看和修改密码。")
                    .put("howto", "先在系统设置里设一个锁屏密码，再回来点解锁。")
            }
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("查看密码库", "验证锁屏后 5 分钟内可查看/修改")
            if (i == null) {
                JSONObject().put("ok", false).put("error", "系统没给出锁屏验证界面")
            } else {
                act.startActivityForResult(i, REQ)
                JSONObject().put("ok", true).put("needUnlock", true).put("prompted", true)
                    .put("note", "已经叫出锁屏验证，验证通过后 5 分钟内可查看/修改")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "叫不起锁屏验证：" + (e.message ?: ""))
        }
    }

    /** Activity 的 onActivityResult 回调（REQ=9201） */
    fun onUnlockResult(resultCode: Int): Boolean {
        if (resultCode == Activity.RESULT_OK) {
            unlockedUntil = System.currentTimeMillis() + 5 * 60_000L
            pendingCapture?.let { rec ->
                try {
                    val arr = readAll()
                    arr.put(rec)
                    writeAll(arr)
                } catch (e: Exception) {
                }
                pendingCapture = null
            }
            return true
        }
        return false
    }

    fun lock(): JSONObject {
        unlockedUntil = 0
        return JSONObject().put("ok", true).put("unlocked", false)
    }

    private fun needUnlock() = JSONObject().put("ok", false).put("needUnlock", true)
        .put("error", "要先过锁屏验证（点 vault.unlock），这是第 4 条的要求")

    // ---------------------------------------------------------------- 条目
    fun list(): JSONObject {
        if (!unlocked()) return needUnlock()
        val arr = readAll()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.put(
                JSONObject()
                    .put("id", o.optString("id"))
                    .put("site", o.optString("site"))
                    .put("user", o.optString("user"))
                    .put("ts", o.optLong("ts"))
                    // 列表里默认不回密码，要看单条得显式 get（同样要已解锁）
                    .put("hasPass", o.optString("pass").isNotEmpty())
            )
        }
        return JSONObject().put("ok", true).put("list", out).put("count", out.length())
    }

    fun get(id: String): JSONObject {
        if (!unlocked()) return needUnlock()
        val arr = readAll()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                return JSONObject().put("ok", true).put("item", o)
            }
        }
        return JSONObject().put("ok", false).put("error", "没这一条：$id")
    }

    /** 从页面抓到的一条登录信息，先挂起；用户点了"保存"且解锁后才落盘 */
    fun capture(rec: JSONObject): JSONObject {
        if (!unlocked()) {
            pendingCapture = rec
            val r = requestUnlock()
            return JSONObject().put("ok", true).put("pending", true)
                .put("note", "已经挂起这条（站点 " + rec.optString("site") + "），先过锁屏验证再落盘")
                .put("unlock", r)
        }
        return save(rec)
    }

    fun save(rec: JSONObject): JSONObject {
        if (!unlocked()) {
            pendingCapture = rec
            return JSONObject().put("ok", false).put("needUnlock", true)
                .put("error", "要先过锁屏验证才能存")
        }
        return try {
            val arr = readAll()
            val id = "p" + System.currentTimeMillis()
            val item = JSONObject()
                .put("id", id)
                .put("site", rec.optString("site"))
                .put("user", rec.optString("user"))
                .put("pass", rec.optString("pass"))
                .put("ts", System.currentTimeMillis())
                .put("note", rec.optString("note"))
            arr.put(item)
            writeAll(arr)
            JSONObject().put("ok", true).put("id", id).put("count", arr.length())
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "存失败：" + (e.message ?: ""))
        }
    }

    fun del(id: String): JSONObject {
        if (!unlocked()) return needUnlock()
        val arr = readAll()
        val out = JSONArray()
        var hit = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                hit = true
            } else {
                out.put(o)
            }
        }
        if (hit) {
            try { writeAll(out) } catch (e: Exception) {
                return JSONObject().put("ok", false).put("error", "写失败：" + (e.message ?: ""))
            }
        }
        return JSONObject().put("ok", hit).put("count", out.length())
            .put("error", if (hit) "" else "没这一条：$id")
    }

    /** 随机密码：四类字符各自可开关（第 4 条要求） */
    fun gen(len: Int, upper: Boolean, lower: Boolean, digit: Boolean, sym: Boolean): JSONObject {
        val u = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val l = "abcdefghijkmnopqrstuvwxyz"
        val d = "23456789"
        val s = "!@#$%^&*()-_=+[]{};:,.?"
        val pools = ArrayList<String>()
        if (upper) pools.add(u)
        if (lower) pools.add(l)
        if (digit) pools.add(d)
        if (sym) pools.add(s)
        if (pools.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "四类字符全关了，没字符可用")
        }
        val n = if (len < 4) 12 else if (len > 128) 128 else len
        val rnd = SecureRandom()
        val sb = StringBuilder()
        // 每一类至少来一个，剩下的从全池随机（这样"关掉的类"一定不会出现）
        for (p in pools) sb.append(p[rnd.nextInt(p.length)])
        val all = pools.joinToString("")
        while (sb.length < n) sb.append(all[rnd.nextInt(all.length)])
        // 打乱
        val chars = sb.toString().toCharArray()
        for (i in chars.indices.reversed()) {
            val j = rnd.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        val pass = String(chars)
        return JSONObject().put("ok", true).put("password", pass).put("len", pass.length)
            .put("pools", JSONObject().put("upper", upper).put("lower", lower).put("digit", digit).put("sym", sym))
    }

    companion object {
        const val REQ = 9201
        private const val ALIAS = "cdp_vault_key"
    }
}
