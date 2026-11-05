package com.alastor.ctmforge.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alastor.ctmforge.data.Launchers

enum class WebSite(val title: String, val home: String) {
    CURSEFORGE("CurseForge", "https://www.curseforge.com/minecraft/search?class=texture-packs&search=connected%20textures"),
    PLANET("Planet Minecraft", "https://www.planetminecraft.com/texture-packs/?keywords=connected%20textures"),
}

/**
 * Keeps one WebView per site alive while the app runs, so switching tabs doesn't lose the
 * page. The WebView sits on a MutableContextWrapper, re-pointed at the current activity.
 */
class WebHolder(val site: WebSite) {
    var webView: WebView? = null
        private set
    var progress by mutableIntStateOf(0)
    var title by mutableStateOf("")
    var url by mutableStateOf(site.home)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var onDownload: ((String, String?) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context, onDownload: (url: String, userAgent: String?) -> Unit): WebView {
        this.onDownload = onDownload
        webView?.let { existing ->
            (existing.context as? MutableContextWrapper)?.baseContext = context
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val wv = WebView(MutableContextWrapper(context))
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            // look like mobile Chrome rather than an embedded WebView
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                if (scheme == "about" || scheme == "javascript" || scheme == "data" || scheme == "blob") return false
                try {
                    val intent = if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    view.context.startActivity(intent)
                } catch (_: Exception) {
                    // no app for it: ignore, like a browser would
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                url?.let { this@WebHolder.url = it }
                error = null
                updateNav(view)
            }

            override fun onPageFinished(view: WebView, url: String?) = updateNav(view)

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                url?.let { this@WebHolder.url = it }
                updateNav(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                if (request.isForMainFrame) error = err.description?.toString() ?: "Couldn't load the page"
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t ?: "" }
        }
        wv.setDownloadListener { url, userAgent, _, _, _ -> this.onDownload?.invoke(url, userAgent) }
        wv.loadUrl(site.home)
        webView = wv
        return wv
    }

    private fun updateNav(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    fun destroy() {
        webView?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
    }
}

tailrec fun Context.findActivityContext(): Context = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> if (baseContext == null || baseContext === this) this else baseContext.findActivityContext()
    else -> this
}

@Composable
fun WebPane(holder: WebHolder, onDownload: (url: String, userAgent: String?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { holder.webView?.goBack() }, enabled = holder.canGoBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = { holder.webView?.goForward() }, enabled = holder.canGoForward) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward")
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    holder.title.ifBlank { holder.site.title },
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    holder.url.removePrefix("https://").removePrefix("www."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { holder.webView?.reload() }) { Icon(Icons.Rounded.Refresh, contentDescription = "Reload") }
            IconButton(onClick = { holder.webView?.loadUrl(holder.site.home) }) { Icon(Icons.Rounded.Home, contentDescription = "Start page") }
            IconButton(onClick = { Launchers.openUrl(context, holder.url) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open in browser")
            }
        }
        if (holder.progress in 1..99) {
            LinearProgressIndicator(progress = { holder.progress / 100f }, modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx -> holder.obtain(ctx.findActivityContext(), onDownload) },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view -> (view.parent as? ViewGroup)?.removeView(view) },
            )
            holder.error?.let { err ->
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't load ${holder.site.title}", style = MaterialTheme.typography.titleMedium)
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { holder.error = null; holder.webView?.reload() }) { Text("Try again") }
                }
            }
        }
    }
    BackHandler(enabled = holder.canGoBack) { holder.webView?.goBack() }
    DisposableEffect(holder) {
        holder.webView?.onResume()
        onDispose { holder.webView?.onPause() }
    }
}
