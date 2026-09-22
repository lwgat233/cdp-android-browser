package dev.cdp

import android.content.Context
import java.io.File

/**
 * 配置隔离（清单第 12 条）：不同的调用方/用途各存一套数据，互不污染。
 *
 * 现在隔离的是这三样（其余如嗅探清单、拦截规则是全局的，界面上会说明）：
 *   - 历史 + 书签（nav.json）
 *   - 脚本（scripts.json）
 *   - 设置（settings.json：UA / 搜索引擎 / 代理 / 隐身…）
 *
 * 用法：HTTP 控制口的请求带上 `space=<名字>`（或头 `X-CDP-Space`），
 * 就会临时切到那个命名空间处理这一批调用；`space=default` 回到主空间。
 * 数据落在 `files/spaces/<名字>/` 下，主空间仍在 `files/` 根（沿用老数据，不迁移）。
 */
object Spaces {

    const val DEFAULT = "default"

    @Volatile
    var current: String = DEFAULT
        private set

    private val listeners = java.util.Collections.synchronizedList(mutableListOf<() -> Unit>())

    /** 名字只留小写字母/数字/下划线/短横，避免路径穿越 */
    fun sanitize(name: String): String {
        val n = name.trim().lowercase().replace(Regex("[^a-z0-9_\\-]"), "")
        return if (n.isEmpty()) DEFAULT else n.take(24)
    }

    fun file(ctx: Context, base: String): File {
        val sp = current
        return if (sp == DEFAULT) File(ctx.filesDir, base)
        else File(File(ctx.filesDir, "spaces/$sp").apply { mkdirs() }, base)
    }

    /** 各 store 在切换时重新加载自己的文件 */
    fun onSwitch(fn: () -> Unit) {
        listeners.add(fn)
    }

    fun use(name: String): String {
        current = sanitize(name)
        synchronized(listeners) {
            listeners.forEach {
                try {
                    it()
                } catch (_: Exception) {
                }
            }
        }
        return current
    }

    fun list(ctx: Context): List<String> {
        val out = mutableListOf(DEFAULT)
        try {
            File(ctx.filesDir, "spaces").listFiles()?.filter { it.isDirectory }?.forEach { out.add(it.name) }
        } catch (_: Exception) {
        }
        return out.sorted()
    }
}
