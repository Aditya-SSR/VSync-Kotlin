package com.minimallabs.vsync.feature.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.DemoMode
import com.minimallabs.vsync.core.Failures
import com.minimallabs.vsync.ui.CenterLoader
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.Muted
import org.json.JSONObject

private const val BASE = "https://vtop.vitap.ac.in"
private const val OPEN_PAGE = "/vtop/open/page"
private const val CONTENT_PAGE = "/vtop/content"

/** Everything the WebView needs to carry on the scraper's session. */
private class Handoff(val cookies: String, val csrf: String?, val username: String, val userAgent: String)

private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * The real VTOP site, signed in with the scraper's session: its cookies are
 * copied into the WebView and the SAME User-Agent is sent (VTOP binds the session to it).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VtopWebViewPage() {
    val nav = LocalNav.current
    var handoff by remember { mutableStateOf<Handoff?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(attempt) {
        handoff = null; error = null
        if (DemoMode.isOn) { error = "This feature is not available in the demo account."; return@LaunchedEffect }
        try {
            if (attempt > 0) App.session.resetClient()
            handoff = App.vtop { c ->
                if (c.text("is_auth") != "true") throw com.minimallabs.vsync.core.UserMessage("Session not authenticated. Please refresh.")
                val cookies = String(c.bytes("cookies"), Charsets.UTF_8)
                if (cookies.isBlank()) throw com.minimallabs.vsync.core.UserMessage("No session cookies found. Please refresh.")
                Handoff(cookies, c.text("csrf").ifBlank { null }, c.text("username"), c.text("user_agent"))
            }
        } catch (e: Throwable) {
            error = Failures.messageFor(e)
        }
    }

    BackHandler(enabled = web?.canGoBack() == true) { web?.goBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.Filled.Close, "Close") }
            Text(url.removePrefix("https://").ifBlank { "vtop.vitap.ac.in" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (web != null) web?.reload() else attempt++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        val h = handoff
        when {
            error != null -> EmptyState("Couldn't open VTOP", error, "Try again") { attempt++ }
            h == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { CenterLoader(); Muted("Signing in to VTOP...") }
            }
            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    for (part in h.cookies.split(';')) {
                        val kv = part.trim()
                        if (kv.contains('=')) cm.setCookie(BASE, "$kv; Path=/")
                    }
                    cm.flush()
                    var csrf = h.csrf
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        if (h.userAgent.isNotBlank()) settings.userAgentString = h.userAgent
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !request.url.toString().startsWith(BASE) // never leave VTOP

                            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) { url = u }

                            override fun onPageFinished(view: WebView, u: String) {
                                url = u
                                view.evaluateJavascript("(function(){var i=document.querySelector('input[name=\"_csrf\"]');return i?i.value:'';})()") { raw ->
                                    val token = raw?.trim('"').orEmpty()
                                    if (token.isNotEmpty()) csrf = token
                                    // The open page needs a POST with the CSRF token to reach the signed-in content.
                                    if (u.contains(OPEN_PAGE) && csrf != null) {
                                        val fields = mapOf("_csrf" to csrf!!, "authorizedID" to h.username, "verifyMenu" to "true", "nocache" to "@(new Date().getTime())")
                                            .entries.joinToString("") { "<input type=\"hidden\" name=\"${esc(it.key)}\" value=\"${esc(it.value)}\">" }
                                        val js = "(function(){var f=document.createElement('form');f.method='POST';f.action=${JSONObject.quote(BASE + CONTENT_PAGE)};" +
                                            "f.innerHTML=${JSONObject.quote(fields)};document.body.appendChild(f);f.submit();})()"
                                        view.postDelayed({ view.evaluateJavascript(js, null) }, 300)
                                    } else if (u.contains("/vtop/login") || u.contains("/vtop/prelogin")) {
                                        error = "Session expired. Please go back and try again."
                                    }
                                }
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                if (request.isForMainFrame) error = "Failed to load page: ${err.description}"
                            }
                        }
                        loadUrl(BASE + OPEN_PAGE)
                        web = this
                    }
                },
                onRelease = { it.destroy(); web = null },
            )
        }
    }
}
