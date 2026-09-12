package com.sshtab.pad.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Base64
import com.sshtab.pad.log.SessionLog
import com.sshtab.pad.ssh.SshSessionManager

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XtermView(modifier: Modifier = Modifier) {
    val sink = remember {
        { bytes: ByteArray ->
            val holder = XtermHolder.webView
            if (holder != null && bytes.isNotEmpty()) {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                holder.post {
                    holder.evaluateJavascript("window.__writeB64 && window.__writeB64('$b64')", null)
                }
            }
            Unit
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            SshSessionManager.detachSink(sink)
            SessionLog.event("xterm disposed")
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.parseColor("#0B1220"))
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.useWideViewPort = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.textZoom = 100
                if (Build.VERSION.SDK_INT >= 26) {
                    settings.safeBrowsingEnabled = false
                }
                addJavascriptInterface(XtermBridge(), "SshPad")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        SshSessionManager.attachSink(sink)
                        view?.evaluateJavascript("window.__fit && window.__fit()", null)
                        view?.requestFocus()
                    }
                }
                XtermHolder.webView = this
                loadUrl("file:///android_asset/xterm/index.html")
            }
        },
        update = { view ->
            XtermHolder.webView = view
            view.post {
                view.evaluateJavascript("window.__fit && window.__fit()", null)
            }
        },
    )
}

private object XtermHolder {
    @Volatile var webView: WebView? = null
}

private class XtermBridge {
    @JavascriptInterface
    fun onData(data: String) {
        SshSessionManager.writeUtf8(data)
    }

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) {
        SshSessionManager.resize(cols, rows)
    }
}
