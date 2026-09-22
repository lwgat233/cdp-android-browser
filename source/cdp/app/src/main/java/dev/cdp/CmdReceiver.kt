package dev.cdp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * 显式广播命令入口，专供 adb / 测试脚本用：
 *   adb shell am broadcast -n dev.cdp/.CmdReceiver -a dev.cdp.CMD --es cmd goto --es text "https://..."
 *   adb shell am broadcast -n dev.cdp/.CmdReceiver -a dev.cdp.CMD --es cmd click --es text "下一章"
 *   adb shell am broadcast -n dev.cdp/.CmdReceiver -a dev.cdp.CMD --es cmd record --es text start
 *   adb shell am broadcast -n dev.cdp/.CmdReceiver -a dev.cdp.CMD --es cmd replay --es text <scriptId>
 *   adb shell am broadcast -n dev.cdp/.CmdReceiver -a dev.cdp.CMD --es cmd eval --es text "document.title"
 * 结果不做异步回传（adb broadcast 拿不到），结果写进 log 缓冲，用 cmd=log 读。
 */
class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val act = MainActivity.INSTANCE ?: return
        val cmd = intent.getStringExtra("cmd") ?: return
        val text = intent.getStringExtra("text") ?: ""
        val args = JSONObject().put("text", text).put("selector", text).put("name", text)
            .put("js", text).put("url", text).put("q", text).put("id", text)
            .put("lan", text == "lan")
        val op = when (cmd) {
            "goto" -> "browser.goto"
            "search" -> "browser.search"
            "click" -> "page.click"
            "query" -> "page.query"
            "diag" -> "page.diag"
            "eval" -> "page.eval"
            "state" -> "browser.state"
            "record" -> if (text == "stop") "rec.stop" else "rec.start"
            "steps" -> "rec.steps"
            "replay" -> "play.run"
            "scripts" -> "scripts.list"
            "scan" -> "scripts.scanDir"
            "http" -> if (text == "stop") "http.stop" else "http.start"
            "events" -> "events.tail"
            "log" -> "log.tail"
            else -> {
                act.bridge.log("CMD 未知命令: $cmd"); return
            }
        }
        act.runOnUiThread {
            act.bridge.dispatch(op, args) { r ->
                act.bridge.log("CMD $cmd → " + r.toString().take(600))
            }
        }
    }
}
