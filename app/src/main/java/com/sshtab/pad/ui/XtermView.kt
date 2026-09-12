package com.sshtab.pad.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.InputType
import android.util.Base64
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.sshtab.pad.log.SessionLog
import com.sshtab.pad.service.SessionClient
import com.sshtab.pad.ui.theme.isDarkTheme

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XtermView(modifier: Modifier = Modifier) {
    val themeMode by AppSettings.theme.collectAsState()
    val fontSize by AppSettings.fontSize.collectAsState()
    val keepOn by AppSettings.keepScreenOn.collectAsState()
    val dark = isDarkTheme(themeMode, isSystemInDarkTheme())
    val sink = remember {
        { bytes: ByteArray ->
            val holder = XtermHost.webView
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
        val cancelReset = SessionClient.onTerminalReset {
            XtermHost.webView?.post {
                XtermHost.webView?.evaluateJavascript("window.__reset && window.__reset()", null)
            }
        }
        onDispose {
            cancelReset()
            SessionClient.detachSink(sink)
            SessionLog.event("xterm disposed")
        }
    }

    LaunchedEffect(dark, fontSize) {
        val d = if (dark) "true" else "false"
        XtermHost.webView?.evaluateJavascript("window.__apply && window.__apply($d, $fontSize)", null)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalWebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.parseColor(if (dark) "#0D1117" else "#FFFFFF"))
                isFocusable = true
                isFocusableInTouchMode = true
                isSoundEffectsEnabled = false
                keepScreenOn = keepOn
                if (Build.VERSION.SDK_INT >= 26) {
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    importantForContentCapture = android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
                }
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
                        SessionClient.attachSink(sink)
                        SessionClient.resyncTerminal()
                        val d = if (dark) "true" else "false"
                        view?.evaluateJavascript("window.__apply && window.__apply($d, $fontSize)", null)
                        view?.evaluateJavascript("window.__wake && window.__wake()", null)
                        view?.requestFocus()
                    }
                }
                XtermHost.webView = this
                loadUrl("file:///android_asset/xterm/index.html")
            }
        },
        update = { view ->
            XtermHost.webView = view
            view.keepScreenOn = keepOn
            view.setBackgroundColor(Color.parseColor(if (dark) "#0D1117" else "#FFFFFF"))
        },
    )
}

object XtermHost {
    @Volatile var webView: WebView? = null

    fun pause() {
        val v = webView ?: return
        v.evaluateJavascript("window.__sleep && window.__sleep()", null)
        try { v.onPause() } catch (_: Throwable) {}
    }

    fun resume() {
        val v = webView ?: return
        try { v.onResume() } catch (_: Throwable) {}
        v.post {
            v.evaluateJavascript("window.__wake && window.__wake()", null)
            v.requestFocus()
        }
    }
}

/** 关掉联想 / 智能标点 / 全屏输入，避免切回后 `cd ..` 变成 `cd cd...`。 */
private class TerminalWebView(context: Context) : WebView(context) {
    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_NO_EXTRACT_UI
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION
        outAttrs.initialCapsMode = 0
        outAttrs.hintLocales = null
        return ic
    }
}

private class XtermBridge {
    @JavascriptInterface
    fun onData(data: String) {
        if (data.isEmpty()) return
        SessionClient.writeUtf8(data)
    }

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) {
        SessionClient.resize(cols, rows)
    }
}
