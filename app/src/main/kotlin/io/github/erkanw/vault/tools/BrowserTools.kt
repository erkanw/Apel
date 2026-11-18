package io.github.erkanw.vault.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.erkanw.vault.provider.ProviderEndpoints
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * The experimental browser (PLAN §7.4, off by default): one hidden WebView the agent drives. It opens http and https
 * pages only, never this phone or the home network (checked on every page and every request the page makes, by name),
 * has no file access, and keeps its cookies apart from the phone's browser. A page is read as its title, address,
 * the elements it can act on (each with a ref like e3) and its text; the agent clicks and types by ref. Typing into a
 * password field is refused: that is left to the user.
 */
object BrowserSession {
    private const val WIDTH_DP = 412
    private const val HEIGHT_DP = 900
    private const val MAX_TEXT = 12_000

    private val REF = Regex("e[0-9]{1,4}")
    private var view: WebView? = null
    private var pageLoaded: CompletableDeferred<Unit>? = null
    @Volatile private var blocked: String? = null

    /** Whether [url] may be opened: http or https, not this phone or the home network. Null when it may; else why not. */
    fun refusal(url: String): String? {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return "That isn’t a web address." }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Only http and https pages."
        val host = uri.host ?: return "That isn’t a web address."
        if (ProviderEndpoints.isPrivateHost(host)) return "Pages on this phone or the home network can’t be opened."
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensure(context: Context): WebView {
        view?.let { return it }
        val density = context.resources.displayMetrics.density
        val w = (WIDTH_DP * density).toInt()
        val h = (HEIGHT_DP * density).toInt()
        val v = WebView(context.applicationContext)
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
        }
        v.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val why = refusal(request.url.toString()) ?: return false
                if (request.isForMainFrame) blocked = why
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // A page may not reach into the home network from its scripts or images either.
                val host = request.url.host ?: return null
                val scheme = request.url.scheme?.lowercase()
                if ((scheme == "http" || scheme == "https") && ProviderEndpoints.isPrivateHost(host)) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), "".byteInputStream())
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoaded?.complete(Unit)
            }
        }
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        view = v
        return v
    }

    private suspend fun js(v: WebView, script: String): String = suspendCancellableCoroutine { c ->
        v.evaluateJavascript(script) { raw -> if (c.isActive) c.resume(raw ?: "null") }
    }

    /** A script's string result, unquoted. */
    private fun unquote(raw: String): String = try { JSONArray("[$raw]").optString(0) } catch (e: Exception) { raw }

    /** Waits for the page to finish loading (at most [ms]), then a moment for its scripts. */
    private suspend fun settle(v: WebView, ms: Long) {
        val done = pageLoaded
        if (done != null && v.progress < 100) withTimeoutOrNull(ms) { done.await() }
        delay(600)
    }

    suspend fun open(context: Context, url: String): String = withContext(Dispatchers.Main) {
        refusal(url)?.let { throw ToolException(it) }
        val v = ensure(context)
        blocked = null
        pageLoaded = CompletableDeferred()
        v.loadUrl(url)
        val ok = withTimeoutOrNull(20_000) { pageLoaded!!.await() } != null
        blocked?.let { throw ToolException(it) }
        if (!ok) throw ToolException("The page took longer than 20 seconds to load.")
        delay(600)
        readLocked(v)
    }

    suspend fun read(context: Context): String = withContext(Dispatchers.Main) {
        val v = view ?: throw ToolException("No page is open. Open one with browser_open first.")
        readLocked(v)
    }

    private suspend fun readLocked(v: WebView): String {
        val json = try { JSONObject(unquote(js(v, READ_JS.replace("MAX_TEXT", MAX_TEXT.toString())))) } catch (e: Exception) {
            throw ToolException("The page couldn’t be read.")
        }
        val refs = json.optJSONArray("refs") ?: JSONArray()
        return buildString {
            append("Title: ").append(json.optString("title")).append('\n')
            append("URL: ").append(json.optString("url")).append("\n\n")
            if (refs.length() > 0) {
                append("Elements (act on them by ref):\n")
                for (i in 0 until refs.length()) append(refs.optString(i)).append('\n')
                append('\n')
            }
            append("Text:\n").append(json.optString("text"))
        }
    }

    suspend fun click(context: Context, ref: String): String = withContext(Dispatchers.Main) {
        if (!REF.matches(ref)) throw ToolException("A ref looks like e3: use one from the page as last read.")
        val v = view ?: throw ToolException("No page is open. Open one with browser_open first.")
        blocked = null
        pageLoaded = CompletableDeferred()
        when (unquote(js(v, CLICK_JS.replace("REF", JSONObject.quote(ref))))) {
            "ok" -> Unit
            else -> throw ToolException("There is no element $ref on this page now. Read the page again for fresh refs.")
        }
        delay(900)
        settle(v, 15_000)
        blocked?.let { throw ToolException("The click led to a page that can’t be opened: $it") }
        "Clicked $ref.\n\n" + readLocked(v)
    }

    suspend fun type(context: Context, ref: String, text: String, submit: Boolean): String = withContext(Dispatchers.Main) {
        if (!REF.matches(ref)) throw ToolException("A ref looks like e3: use one from the page as last read.")
        val v = view ?: throw ToolException("No page is open. Open one with browser_open first.")
        blocked = null
        pageLoaded = CompletableDeferred()
        // The typed text goes in last, so nothing in it is taken for a placeholder.
        val script = TYPE_JS.replace("REF", JSONObject.quote(ref)).replace("SUBMIT", submit.toString()).replace("TEXT", JSONObject.quote(text))
        when (unquote(js(v, script))) {
            "ok" -> Unit
            "password" -> throw ToolException("That is a password field. Passwords are left to the user: ask them to sign in themselves.")
            "notfield" -> throw ToolException("$ref isn’t a field you can type into.")
            else -> throw ToolException("There is no element $ref on this page now. Read the page again for fresh refs.")
        }
        if (submit) {
            delay(900)
            settle(v, 15_000)
        }
        blocked?.let { throw ToolException("That led to a page that can’t be opened: $it") }
        "Typed into $ref" + (if (submit) " and sent it" else "") + ".\n\n" + readLocked(v)
    }

    /** Draws the page into a PNG in the workspace (`browser/`); its path. */
    suspend fun screenshot(context: Context): String = withContext(Dispatchers.Main) {
        val v = view ?: throw ToolException("No page is open. Open one with browser_open first.")
        val bitmap = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        val dir = File(context.filesDir, "workspace/browser").apply { mkdirs() }
        val file = File(dir, "page-${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        bitmap.recycle()
        "Saved a picture of the page as browser/${file.name} in the files. The model can’t see it; the user can open it in Files."
    }

    /** Closes the page and forgets every cookie and stored item of the browser. */
    suspend fun clear() = withContext(Dispatchers.Main) {
        view?.let { it.stopLoading(); it.clearHistory(); it.clearCache(true); it.destroy() }
        view = null
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    }

    private val READ_JS = """
(function(){
  var max=MAX_TEXT, refs=[], n=0;
  document.querySelectorAll('[data-apel-ref]').forEach(function(e){e.removeAttribute('data-apel-ref');});
  var sel='a[href],button,input,select,textarea,[role=button],[role=link],[role=checkbox],[role=tab],[role=menuitem],[onclick],summary';
  var els=document.querySelectorAll(sel);
  for (var i=0;i<els.length && n<150;i++){
    var e=els[i], r=e.getBoundingClientRect(), st=getComputedStyle(e);
    if (st.display=='none'||st.visibility=='hidden'||(r.width==0&&r.height==0)||e.disabled) continue;
    if (e.tagName=='INPUT'&&e.type=='hidden') continue;
    n++; var id='e'+n; e.setAttribute('data-apel-ref',id);
    var tag=e.tagName.toLowerCase();
    var kind=tag=='a'?'link':tag=='input'?('field '+(e.type||'text')):tag=='select'?'choice':tag=='textarea'?'field textarea':(e.getAttribute('role')||'button');
    var label=(e.getAttribute('aria-label')||e.innerText||(tag=='input'&&(e.type=='submit'||e.type=='button')?e.value:'')||e.placeholder||e.title||e.name||e.alt||'').trim().replace(/\s+/g,' ').slice(0,80);
    var extra='';
    if (tag=='a') extra=' -> '+String(e.getAttribute('href')).slice(0,100);
    if (tag=='input'||tag=='textarea'){
      if (e.type=='password') extra=' (password: left to the user)';
      else if (e.type=='checkbox'||e.type=='radio') extra=e.checked?' (checked)':' (not checked)';
      else extra=' value="'+String(e.value||'').slice(0,60)+'"';
    }
    if (tag=='select') extra=' options: '+Array.prototype.slice.call(e.options,0,12).map(function(o){return o.text.trim();}).join(' | ');
    refs.push('['+id+'] '+kind+' "'+label+'"'+extra);
  }
  var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n');
  return JSON.stringify({title:document.title,url:location.href,refs:refs,text:text.slice(0,max)});
})()
""".trimIndent()

    private val CLICK_JS = """
(function(){
  var e=document.querySelector('[data-apel-ref='+JSON.stringify(REF)+']');
  if(!e) return 'missing';
  e.scrollIntoView({block:'center'});
  e.click();
  return 'ok';
})()
""".trimIndent()

    private val TYPE_JS = """
(function(){
  var e=document.querySelector('[data-apel-ref='+JSON.stringify(REF)+']');
  if(!e) return 'missing';
  if(e.type=='password') return 'password';
  var tag=e.tagName;
  if(tag=='SELECT'){
    var t=TEXT.toLowerCase(), hit=null;
    for(var i=0;i<e.options.length;i++){ if(e.options[i].text.trim().toLowerCase()==t||e.options[i].value.toLowerCase()==t){hit=e.options[i];break;} }
    if(!hit) return 'missing';
    e.value=hit.value;
  } else if(tag=='INPUT'||tag=='TEXTAREA'){
    e.focus();
    var proto=tag=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto,'value').set.call(e,TEXT);
  } else if(e.isContentEditable){
    e.focus(); e.innerText=TEXT;
  } else return 'notfield';
  e.dispatchEvent(new Event('input',{bubbles:true}));
  e.dispatchEvent(new Event('change',{bubbles:true}));
  if(SUBMIT){
    if(e.form){ if(e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); }
    else { e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true})); }
  }
  return 'ok';
})()
""".trimIndent()
}

/** The browser tools (PLAN §7.4, "Experimental", Off until turned on in Tools). */
object BrowserTools {
    private const val EXPERIMENTAL = "Experimental. "

    object Open : Tool {
        override val name = "browser_open"
        override val title = "Open a page"
        override val description = EXPERIMENTAL + "Opens a page in a hidden browser on this phone, with its own cookies. Never your home network."
        override val instructions = "Open a web page in a real browser (runs its scripts), to then read it, click and type. Returns the page: its title, address, the elements you can act on with their refs (like e3), and its text. Use web_fetch instead when you only need to read a simple page."
        override val schema = params("url" to str("The page's full address (http or https)."))
        override val defaultLevel = ToolLevel.Off
        override fun hint(args: JSONObject) = "Opening " + (Uri.parse(args.optString("url")).host?.removePrefix("www.") ?: "a page")
        override fun step(args: JSONObject) = "browser_open " + args.optString("url")
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            ctx.readWeb = true
            val raw = args.need("url").trim().let { if ("://" in it) it else "https://$it" }
            return BrowserSession.open(ctx.app, raw)
        }
    }

    object Read : Tool {
        override val name = "browser_read"
        override val title = "Read the open page"
        override val description = EXPERIMENTAL + "Reads the page open in the hidden browser again, with fresh element refs."
        override val instructions = "Read the page that is open in the browser again (after it changed), with fresh element refs."
        override val schema = params()
        override val defaultLevel = ToolLevel.Off
        override fun hint(args: JSONObject) = "Reading the page"
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            ctx.readWeb = true
            return BrowserSession.read(ctx.app)
        }
    }

    object Click : Tool {
        override val name = "browser_click"
        override val title = "Click on a page"
        override val description = EXPERIMENTAL + "Clicks a link or button on the open page."
        override val instructions = "Click an element of the open page by its ref (from browser_open or browser_read). Returns the page after the click."
        override val schema = params("ref" to str("The element's ref, like e3."))
        override val defaultLevel = ToolLevel.Off
        override fun hint(args: JSONObject) = "Clicking"
        override fun step(args: JSONObject) = "browser_click " + args.optString("ref")
        override fun ask(args: JSONObject) = ApprovalAsk("Click ${args.optString("ref")} on the open page?", args.optString("ref"))
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            ctx.readWeb = true
            return BrowserSession.click(ctx.app, args.need("ref").trim())
        }
    }

    object Type : Tool {
        override val name = "browser_type"
        override val title = "Type on a page"
        override val description = EXPERIMENTAL + "Types into a field on the open page, and can send the form. Never into a password field."
        override val instructions = "Type text into a field of the open page by its ref, or pick an option of a choice by its text. With submit true, the form is sent (like pressing Enter). Password fields are refused: ask the user to sign in themselves."
        override val schema = params(
            "ref" to str("The field's ref, like e3."),
            "text" to str("What to type, or the option to pick."),
            "submit" to JSONObject().put("type", "boolean").put("description", "Send the form after typing."),
            required = listOf("ref", "text"),
        )
        override val defaultLevel = ToolLevel.Off
        override fun hint(args: JSONObject) = "Typing"
        override fun step(args: JSONObject) = "browser_type " + args.optString("ref") + " “" + args.optString("text").take(60) + "”"
        override fun ask(args: JSONObject) = ApprovalAsk(
            if (args.optBoolean("submit")) "Type into ${args.optString("ref")} and send it?" else "Type into ${args.optString("ref")}?",
            args.optString("text").take(500),
        )
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            ctx.readWeb = true
            return BrowserSession.type(ctx.app, args.need("ref").trim(), args.optString("text"), args.optBoolean("submit", false))
        }
    }

    object Screenshot : Tool {
        override val name = "browser_screenshot"
        override val title = "Picture of a page"
        override val description = EXPERIMENTAL + "Saves a picture of the open page in Files."
        override val instructions = "Save a picture of the open page to the files (browser/…png) for the user. You can't see it yourself."
        override val schema = params()
        override val defaultLevel = ToolLevel.Off
        override fun hint(args: JSONObject) = "Taking a picture of the page"
        override suspend fun run(args: JSONObject, ctx: ToolContext): String = BrowserSession.screenshot(ctx.app)
    }

    val all: List<Tool> = listOf(Open, Read, Click, Type, Screenshot)
}
