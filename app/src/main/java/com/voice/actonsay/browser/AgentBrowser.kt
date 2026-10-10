package com.voice.actonsay.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 给 AI 用的浏览器：应用内一个常驻的 WebView。读取的是页面文字和可交互元素列表（带编号），
 * 点击、输入都按编号用脚本完成，不依赖截图，中文输入也没有问题。用户可以在任务页打开同一个 WebView 查看。
 */
object AgentBrowser {
    private const val WIDTH = 1080
    private const val HEIGHT = 2200

    private var webView: WebView? = null
    @Volatile private var loading = false
    /** 页面版本：每次导航或重新编号都会递增，旧编号随之失效。 */
    private val version = java.util.concurrent.atomic.AtomicLong(0)
    val pageVersion: Long get() = version.get()
    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /** 返回唯一的 WebView；必须在主线程调用。显示在界面时由调用方挂到自己的容器上。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context): WebView = webView ?: WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading = true; version.incrementAndGet(); _url.value = url }
            override fun onPageFinished(view: WebView, url: String) { loading = false; _url.value = url; _title.value = view.title.orEmpty() }
            // 只在本浏览器内打开 http(s)，其他协议（拉起应用等）一律不跳转。
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme !in setOf("http", "https")
        }
        // 没有挂到窗口上时也要有尺寸，页面才会按手机宽度排版。
        measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
        layout(0, 0, WIDTH, HEIGHT)
        webView = this
    }

    fun detachFromParent() {
        (webView?.parent as? ViewGroup)?.removeView(webView)
    }

    private suspend fun waitForLoad(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            delay(300)
            while (loading) delay(200)
        }
        // 留一点时间给页面脚本渲染动态内容。
        delay(500)
    }

    private suspend fun script(code: String): String = withContext(Dispatchers.Main) {
        val view = webView ?: error("浏览器还没有打开网页")
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(code) { raw ->
                val value = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
                continuation.resume(if (value is String) value else raw.orEmpty())
            }
        }
    }

    suspend fun open(context: Context, address: String) {
        withContext(Dispatchers.Main) {
            loading = true
            view(context).loadUrl(address)
        }
        waitForLoad(20_000)
    }

    /** 页面标题、地址、正文和带编号的可交互元素；返回的 version 是这批编号对应的页面版本。 */
    suspend fun read(maxChars: Int): JSONObject {
        val raw = script(READ_JS.replace("__MAX__", maxChars.toString()))
        val page = runCatching { JSONObject(raw) }.getOrElse { error("页面脚本没有返回内容，可能还在加载") }
        // 脚本重新分配了编号，之前读到的编号不再可靠。
        return page.put("version", version.incrementAndGet())
    }

    suspend fun click(index: Int): String {
        val result = script("(function(i){var e=document.querySelector('[data-actonsay=\"'+i+'\"]');if(!e)return 'missing';" +
            "e.scrollIntoView({block:'center'});e.click();return 'ok';})($index)")
        waitForLoad(10_000)
        return result
    }

    suspend fun type(index: Int, text: String, submit: Boolean): String {
        val result = script(TYPE_JS.replace("__INDEX__", index.toString()).replace("__TEXT__", JSONObject.quote(text)).replace("__SUBMIT__", submit.toString()))
        waitForLoad(if (submit) 10_000 else 0)
        return result
    }

    suspend fun scroll(down: Boolean) {
        script("window.scrollBy(0, ${if (down) "" else "-"}Math.round(window.innerHeight*0.8))")
        delay(400)
    }

    suspend fun back(): Boolean {
        val moved = withContext(Dispatchers.Main) { webView?.takeIf { it.canGoBack() }?.also { loading = true; it.goBack() } != null }
        if (moved) waitForLoad(10_000)
        return moved
    }

    fun close() {
        version.incrementAndGet()
        webView?.apply { detachFromParent(); stopLoading(); loadUrl("about:blank"); clearHistory() }
        _url.value = ""
        _title.value = ""
    }

    private const val READ_JS = """(function(maxChars){
var sel='a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[role=tab],[onclick],summary,label[for]';
var items=[],n=0;
document.querySelectorAll('[data-actonsay]').forEach(function(e){e.removeAttribute('data-actonsay');});
var els=document.querySelectorAll(sel);
for(var k=0;k<els.length&&n<150;k++){var e=els[k];var r=e.getBoundingClientRect();var st=window.getComputedStyle(e);
if(r.width<2||r.height<2||st.visibility=='hidden'||st.display=='none')continue;
n++;e.setAttribute('data-actonsay',n);
// 输入内容不作为元素标签回传，避免密码、验证码或用户手动填写的私密内容进入模型与日志。
var editable=/^(INPUT|TEXTAREA|SELECT)$/.test(e.tagName)||e.isContentEditable||e.getAttribute('role')=='textbox';
var label=((editable?'':e.innerText)||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||'').trim().replace(/\s+/g,' ').slice(0,80);
var tag=e.tagName.toLowerCase();if(tag=='input')tag+='['+(e.type||'text')+']';
var href=e.getAttribute('href');
items.push(n+'|'+tag+'|'+label+(href&&href.indexOf('javascript')!=0?'|'+e.href.slice(0,150):''));}
// 正文同样跳过可编辑区域，不从 textarea 默认值或富文本编辑器旁路回传输入内容。
var chunks=[],length=0,visited=0;
if(document.body){var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),node;
while((node=walker.nextNode())&&length<=maxChars&&visited++<30000){var p=node.parentElement;
if(!p||p.closest('input,textarea,select,[contenteditable],[role="textbox"],script,style,noscript')||!p.getClientRects().length)continue;
var css=window.getComputedStyle(p);if(css.visibility=='hidden'||css.display=='none')continue;
var value=node.nodeValue.replace(/\s+/g,' ').trim();if(value){value=value.slice(0,maxChars+1-length);chunks.push(value);length+=value.length+1;}}}
var text=chunks.join('\n');if(visited>=30000&&text.length<=maxChars)length=maxChars+1;
return JSON.stringify({title:document.title,url:location.href,text:text.slice(0,maxChars),text_length:Math.max(length,text.length),elements:items});
})(__MAX__)"""

    private const val TYPE_JS = """(function(i,t,submit){var e=document.querySelector('[data-actonsay="'+i+'"]');if(!e)return 'missing';
e.scrollIntoView({block:'center'});e.focus();
var proto=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
var d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set){d.set.call(e,t);}else{e.value=t;}
e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
if(submit){['keydown','keypress','keyup'].forEach(function(type){e.dispatchEvent(new KeyboardEvent(type,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});
var f=e.form;if(f){if(f.requestSubmit){try{f.requestSubmit();}catch(x){f.submit();}}else{f.submit();}}}
return 'ok';})(__INDEX__,__TEXT__,__SUBMIT__)"""
}
