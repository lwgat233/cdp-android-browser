package dev.cdp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 接口目录与「二级接口」（清单第 23 条：为 CDP 的 CDP 提供大量接口，并基于基础接口给二级接口）。
 *
 * 三类：
 *  1. 基础接口（140 个，`/api/status` 的 endpoints 里列全）——一个接口一件事；
 *  2. **组合接口**：把常用流程包成一步（`/api/summary` 一屏状态、`/api/grab` 抓正文、`/api/act` 操作）；
 *  3. **二级接口**：`/api/batch?ops=a|b|c` 一次跑多个基础接口（顺序执行，共用一次 space 切换），
 *     外部程序/LLM 不必来回建连接。
 *
 * 这里只维护「给人看的说明」，实际路由在 HttpControl / Bridge 里。
 */
object ApiCatalog {

    /** 常用接口的参数说明（外发接口的自述，别让人去读源码） */
    fun common(): JSONArray {
        val a = JSONArray()
        fun add(path: String, params: String, desc: String) {
            a.put(JSONObject().put("path", path).put("params", params).put("desc", desc))
        }
        // 一、看状态
        add("/api/status", "", "App 版本、页面状态、当前 space、接口总数等一屏信息")
        add("/api/state", "", "当前页面 URL / 标题 / 视口 / 是否在录制")
        add("/api/summary", "space=", "组合接口：状态 + 书签数 + 历史数 + 脚本数 + 嗅探数（一次拿全）")
        // 二、导航与页面
        add("/api/nav/open", "url=", "打开一个地址")
        add("/api/search", "q=", "按设置里的搜索引擎搜索")
        add("/api/eval", "js=", "在页面里执行一段 JS 并取回结果")
        add("/api/query", "selector=|text=", "按选择器/文字查元素（返回候选与几何）")
        add("/api/diag", "selector=|text=", "这个元素为什么点不动：诊断信息")
        add("/api/find", "q=&dir=1", "页内查找：高亮并滚到命中处")
        // 三、操作
        add("/api/click", "selector=|text=|x=&y=", "点元素（真实触摸优先）")
        add("/api/picker/arm", "", "进入拾取模式：用户点哪个抓哪个")
        add("/api/ai/act", "do=click|type|goto|search", "给 AI 用的动作入口（与上面共用同一条链路）")
        // 四、抓内容（给 AI / 别的软件）
        add("/api/ai/context", "space=", "当前页正文/链接/表单字段/按钮（默认不带输入值与 cookie）")
        add("/api/grab", "selector=|max=", "抓一段文本或某个元素的正文")
        // 五、录制与脚本
        add("/api/record", "action=start|stop", "开始/停止页面录制")
        add("/api/recording", "", "录制状态与已录步骤")
        add("/api/replay", "script=", "回放一个脚本（含等待/条件步骤）")
        add("/api/scripts", "", "脚本清单")
        add("/api/scripts/save", "name=&code=&match=", "保存一个用户脚本")
        // 六、数据与设置
        add("/api/bookmarks", "", "书签清单")
        add("/api/history", "", "历史清单")
        add("/api/settings/set", "uaMode=|search=|proxyType=|incognito=", "改设置")
        add("/api/space/use", "name=", "切配置空间（历史/书签/脚本/设置各一套）")
        add("/api/export/bundle", "parts=history,bookmarks,scripts,settings,sniff,adblock", "打包导出到系统下载目录")
        // 七、网络与安全
        add("/api/net", "filter=&kind=&limit=", "请求时间线")
        add("/api/net/stats", "", "请求统计（含应用自搬运字节）")
        add("/api/net/tool", "action=ip|resolve|cert|tcp|speed&host=&port=&url=", "网络工具：本机 IP / 解析 / 证书 / 通路 / 测速")
        add("/api/security", "", "控制口安全状态（绑定范围 / 是否要令牌 / 敏感接口开关 / 被拒次数）")
        add("/api/security/token", "", "取访问令牌（**只在本机可用**；局域网调用要带 ?t= 或 X-CDP-Token 头）")
        add("/api/security/sensitive", "on=0|1", "局域网下是否允许敏感接口（默认关闭）")
        add("/api/security/page", "", "当前页安全结论（明文 / 混合内容 / 警告名单）")
        add("/api/_test/audio/sample.mp3", "", "离线音频测试源（打包自带的 MP3，用来对账下载字节）")
        add("/api/keepalive/media", "on=0|1", "后台播放：熄屏继续放（前台服务 mediaPlayback + 音频焦点 + 唤醒锁）")
        add("/api/record/dots", "on=0|1", "页面上显示坐标小点（画在页面里；N1 起不再用无障碍覆盖层）")
        add("/api/record/dots/status", "", "小点状态：开没开 / 几个点")
        add("/api/record/dots/play", "n=", "点一下第 n 个点 = 让它自己点那一步")
        add("/api/record/dots/move", "n=&x=&y=", "挪第 n 个点到页面坐标 (x,y)")
        add("/api/record/merge", "index=", "把第 index 步并进前一个点（同处→×N；不同处→合成点组）")
        add("/api/sniff/scan", "", "从页面里再扫一遍媒体地址（performance 资源 + video/audio 标签）")
        add("/api/sniff/stats", "", "嗅探清单按归类数一遍（播放列表/视频/音频/分片/字幕）")
        add("/api/sniff/pick", "on=0|1", "指定哪一块是视频：on=1 进入选取（然后点页面上视频那一块），on=0 取消")
        add("/api/sniff/pickAt", "x=&y=", "直接给页面 CSS 坐标做同一件事（真手指那条路走的是原生触摸，结果一样）")
        add("/api/adblock/warn", "", "站点警告名单（和广告规则同一份规则库，命中只挂警告条）")
        add("/api/adblock/warn/add", "host=", "加一条警告名单")
        add("/api/security/block/add", "host=", "把域名加进基础屏蔽名单")
        // 八、后台与窗口
        add("/api/keepalive", "on=1|0", "后台前台化：熄屏继续跑")
        add("/api/win", "", "窗口清单（多窗口；每项带 active/kept：active=当前，kept=false 表示被回收、切回去要重载）")
        add("/api/win/recent", "", "最近关闭的窗口（最多 10 条，可恢复）")
        add("/api/win/menu", "", "打开工具栏 ▤ 那个窗口菜单（看点图标看到的东西；验收也用它读 ● ○ ⟳）")
        add("/api/win/reopen", "i=", "把最近关闭里第 i 条重新开成窗口")
        add("/api/win/new", "url=", "开一个新窗口")
        add("/api/win/switch", "id=", "切到某个窗口")
        add("/api/help", "", "就是本接口：接口说明与二级接口用法")
        add("/api/batch", "ops=a|b|c", "二级接口：一次按顺序跑多个基础接口")
        return a
    }

    fun help(): JSONObject = JSONObject()
        .put("ok", true)
        .put("api", "CDP 对外控制口")
        .put("base", "http://127.0.0.1:8848")
        .put("note", "全部路由见 /api/status 的 endpoints；下表是常用接口的参数与用途说明")
        .put("common", common())
        .put(
            "batch",
            "二级接口：GET /api/batch?ops=" + "api%2Fstate%7Capi%2Fbookmarks（用 | 分隔多个接口，" +
                "每个可以带自己的查询串）。返回 {\"results\":[…]}，按顺序给出每个接口的返回。"
        )
        .put(
            "space",
            "任何接口都可以带 ?space=名字（或头 X-CDP-Space），那一次调用就落在该配置空间里（历史/书签/脚本/设置独立）"
        )
        .put(
            "privacy",
            "默认不吐隐私：/api/ai/context 会剔除表单控件的值；cookie 要显式用 /api/cookies/domain?domain= 查，且日志会记一笔"
        )
}
