package dev.cdp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 给"别的软件 / AI"用的接口层（清单第 11、12 条）：
 *
 *  - **不需要交谈**，只给端口：外部程序拿页面内容、做搜索、点东西、抓一段文本，
 *    全走 HTTP 控制口，App 不当聊天窗。
 *  - **默认不给隐私**：正文提取会**去掉**输入框的值、cookie、localStorage；
 *    要 cookie 得显式传 include=private，而且日志里会记一笔。
 *  - **配置隔离**：每个调用方可以指定自己的 space（命名空间），
 *    历史/书签/脚本/设置/嗅探/拦截规则各存一套文件，互不污染。
 */
object AiApi {

    /** 正文/可交互元素提取（在页面里跑，返回结构化结果） */
    val EXTRACT_JS: String = """
(function(){
  function norm(s){ return String(s == null ? '' : s).replace(/\s+/g,' ').trim(); }
  var main = null, best = 0;
  var cands = document.querySelectorAll('article, main, [role=main], .article, .content, #content, .post, .entry');
  for (var i=0;i<cands.length;i++){
    var t = cands[i].innerText || '';
    if (t.length > best) { best = t.length; main = cands[i]; }
  }
  var root = (main && best > 400) ? main : document.body;
  // 正文：去掉脚本/样式/表单控件（表单值属于隐私，默认不带出去）
  var clone = root ? root.cloneNode(true) : null;
  if (clone) {
    var kill = clone.querySelectorAll('script,style,noscript,input,textarea,select,button');
    for (var k=0;k<kill.length;k++){ try { kill[k].parentNode.removeChild(kill[k]); } catch(e){} }
  }
  var text = norm(clone ? clone.innerText : '');
  var links = [];
  var as = (root || document).querySelectorAll('a[href]');
  for (var j=0;j<as.length && links.length<80;j++){
    var href = as[j].href || '';
    if (href.indexOf('javascript:') === 0) continue;
    links.push({ text: norm(as[j].innerText).slice(0,60), href: href.slice(0,300) });
  }
  var forms = [];
  var fs = document.querySelectorAll('form');
  for (var f=0; f<fs.length && forms.length<10; f++){
    var ins = fs[f].querySelectorAll('input,select,textarea');
    var fields = [];
    for (var m=0;m<ins.length && fields.length<20;m++){
      var el = ins[m];
      var ty = el.getAttribute('type') || el.tagName.toLowerCase();
      // 只报"有哪些字段"，**不报值**（值可能是密码/手机号）
      if (ty === 'password' || ty === 'hidden' || ty === 'submit' || ty === 'button') continue;
      fields.push({ name: el.getAttribute('name') || el.id || '', type: ty });
    }
    if (fields.length) forms.push({ action: (fs[f].getAttribute('action')||'').slice(0,200), fields: fields });
  }
  var btns = [];
  var bs = document.querySelectorAll('button,[role=button],a.btn,.btn');
  for (var b=0; b<bs.length && btns.length<60; b++){
    var tx = norm(bs[b].innerText).slice(0,40);
    if (!tx) continue;
    btns.push({ text: tx });
  }
  return {
    ok: true,
    url: location.href,
    title: document.title,
    text: text.slice(0, 60000),
    textLen: text.length,
    links: links,
    forms: forms,
    buttons: btns,
    privacy: '正文里已剔除表单控件的值（只报字段名与类型）；cookie / localStorage 不在里面'
  };
})()
""".trimIndent()

    /** 兼容旧名（早先调用过这个） */
    fun contextJs(): String = EXTRACT_JS

    fun result(o: JSONObject, op: String, space: String): JSONObject = o
        .put("op", op)
        .put("space", space)
        .put("api", "CDP AI 接口（不需要交谈，给端口就行）")
        .put("hint", "要点元素用 /api/picker/arm+click，要跑脚本用 /api/replay，要搜索用 /api/search?q=")
}
