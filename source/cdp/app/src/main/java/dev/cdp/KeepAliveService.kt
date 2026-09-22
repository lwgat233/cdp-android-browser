package dev.cdp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

/**
 * 后台前台化（清单第 15 条）：让 App 在熄屏/切到后台时**继续干活**
 * （HTTP 控制口、代理中继、定时脚本、下载）。
 *
 * Android 的规矩（老实说清）：
 *  - 必须挂一条**常驻通知**，用户看得见（这是平台的硬要求，不能偷偷跑）；
 *  - 通知要用户允许（Android 13+ 需要 POST_NOTIFICATIONS 运行时权限），
 *    用户不给权限时通知栏不显示，但服务仍在跑；
 *  - 想"熄屏也执行"就保持这个服务开着；关掉它，系统会在后台收紧 App（这是 Android 的设计）。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                bridgeOrNull()?.log("后台前台化：已停止（App 回到普通后台）")
                return START_NOT_STICKY
            }
            ACTION_MEDIA_ON -> {
                mediaMode = true
                startForegroundCompat()
                // **不申请自己的音频焦点**：实测抢焦点会把正在播的 HTML 媒体掐停
                // （日志里 Chromium 的 AudioFocusDelegate 会自己申请 USAGE_MEDIA 焦点；
                //  我们再插一脚，它会判定"被抢走"→ pause）。
                acquireWakeLock()
                bridgeOrNull()?.log("后台播放：已开（前台服务类型 mediaPlayback + 音频焦点 + 唤醒锁；熄屏继续放）")
            }
            ACTION_MEDIA_OFF -> {
                mediaMode = false
                releaseAudioFocus()
                releaseWakeLock()
                if (running) startForegroundCompat()     // 还在保活就换成普通类型继续跑
                else { stopForegroundCompat(); stopSelf() }
                bridgeOrNull()?.log("后台播放：已关（音频焦点与唤醒锁都还回去了）")
            }
            else -> {
                startForegroundCompat()
                bridgeOrNull()?.log(
                    if (mediaMode) "后台前台化：已启动（含后台播放模式）"
                    else "后台前台化：已启动（HTTP 控制口 / 代理 / 定时脚本在后头继续跑）"
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        releaseAudioFocus()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * 音频焦点：**默认不用**（保留代码是为了排查时能试）。
     *
     * 为什么默认不申请：WebView 里的 HTML 媒体由 Chromium 的 AudioFocusDelegate 自己申请焦点，
     * 我们再申请一次 GAIN，Chromium 会当成"焦点被抢"→ 直接把正在播的媒体 pause 掉。
     * 实测（本轮）：开着播放→点开"后台播放"，音频从 paused=false 立刻变 true、currentTime 停住。
     * 而熄屏/后台能不能继续放，靠的是**前台服务类型 mediaPlayback + 唤醒锁**，不是这把焦点。
     */
    @Suppress("unused")
    private fun acquireAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusReq = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (_: Throwable) {
        }
    }

    @Suppress("unused")
    private fun releaseAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusReq?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {
        }
        focusReq = null
    }

    /** 熄屏后 CPU 可能睡下去 —— 放音频时拿一把 partial 唤醒锁，关了立刻还 */
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "cdp:bgplay").apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)     // 最长 6 小时，避免忘关
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun bridgeOrNull(): Bridge? = try {
        MainActivity.INSTANCE?.bridge
    } catch (_: Exception) {
        null
    }

    private fun startForegroundCompat() {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
                .setContentTitle(if (mediaMode) "CDP 后台播放中" else "CDP 在后台运行")
                .setContentText(if (mediaMode) "熄屏也继续放（前台服务类型 mediaPlayback）；点这里回到 App"
                                else "HTTP 控制口 / 代理 / 定时脚本继续工作，点这里回到 App")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("CDP 在后台运行")
                .setContentText("HTTP 控制口 / 代理 / 定时脚本继续工作")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
        // 前台服务**类型**要跟着模式走：mediaPlayback 是系统认可的"正在放媒体"，
        // 熄屏/后台时不会被当成普通后台任务收紧（Android 14 起必须声明这个类型，否则直接抛异常）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = if (mediaMode) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIF_ID, n, types)
        } else {
            startForeground(NOTIF_ID, n)
        }
        running = true
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        running = false
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "CDP 在后台继续工作（控制口 / 代理 / 定时脚本）"
                }
            )
        }
    }

    private var focusReq: android.media.AudioFocusRequest? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL = "cdp_keepalive"
        const val NOTIF_ID = 4242
        const val ACTION_START = "dev.cdp.KEEPALIVE_START"
        const val ACTION_STOP = "dev.cdp.KEEPALIVE_STOP"
        const val ACTION_MEDIA_ON = "dev.cdp.KEEPALIVE_MEDIA_ON"
        const val ACTION_MEDIA_OFF = "dev.cdp.KEEPALIVE_MEDIA_OFF"

        /** 后台播放模式（前台服务带 mediaPlayback 类型 + 持有音频焦点 + 唤醒锁） */
        @Volatile
        var mediaMode = false

        /** 记录当前是否开着（控制台/接口问状态用） */
        @Volatile
        var running = false

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }

        fun startMedia(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java).setAction(ACTION_MEDIA_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stopMedia(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java).setAction(ACTION_MEDIA_OFF)
            try { ctx.startService(i) } catch (_: Throwable) {}
        }

        fun state(): JSONObject = JSONObject()
            .put("running", running)
            .put("media", mediaMode)
            .put("note", "常驻通知由系统要求（Android 不允许悄悄在后台跑）；通知权限被拒时服务照跑，只是看不到通知")
    }
}
