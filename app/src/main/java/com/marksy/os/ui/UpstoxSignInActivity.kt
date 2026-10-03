package com.marksy.os.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.marksy.os.upstox.UpstoxOAuth
import com.marksy.os.upstox.UpstoxOAuthStore
import kotlinx.coroutines.launch
import java.io.IOException

/** Upstox's own login page in a WebView; the redirect is caught here, so no server is involved. Read-only. */
class UpstoxSignInActivity : ComponentActivity() {
    private lateinit var store: UpstoxOAuthStore
    private lateinit var credentials: UpstoxOAuth.Credentials
    private lateinit var state: String
    private var web: WebView? = null
    private var handled = false
    private var busy by mutableStateOf(false)
    private var problem by mutableStateOf<String?>(null)

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OTP and PIN stay out of recents and screen recordings.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        store = UpstoxOAuthStore(this)
        credentials = store.credentials() ?: run { finish(); return }
        state = savedInstanceState?.getString(KEY_STATE) ?: UpstoxOAuth.newState()
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = route(request.url.toString(), request.isForMainFrame)
                // POST navigations skip shouldOverrideUrlLoading, so the redirect and host checks repeat here.
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && url != "about:blank" && route(url, mainFrame = true)) view.stopLoading()
                }
            }
        }
        web = view
        view.loadUrl(UpstoxOAuth.authorizeUrl(credentials, state))
        onBackPressedDispatcher.addCallback(this) { if (problem == null && view.canGoBack()) view.goBack() else finish() }
        setContent {
            MarksyMaterialTheme {
                Box(Modifier.fillMaxSize().background(MarksyTheme.Background).systemBarsPadding().imePadding()) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    if (busy) Box(Modifier.fillMaxSize().background(MarksyTheme.Background.copy(alpha = .9f)), contentAlignment = Alignment.Center) {
                        MarksyLoader("Finishing the Upstox sign-in…")
                    }
                    problem?.let { message ->
                        val shape = MarksyShape.Panel
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(MarksySpace.Wide)
                                .background(MarksyTheme.Surface, shape).border(MarksySpace.Border, MarksyTheme.Warning, shape).padding(MarksySpace.Section),
                            verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
                        ) {
                            Text(message, color = MarksyTheme.TextPrimary, style = MarksyType.Body)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                                Pill("Try again") { retry() }
                                Pill("Close") { finish() }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE, state)
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
        super.onDestroy()
    }

    /** True when the WebView must not load [url] itself. */
    private fun route(url: String, mainFrame: Boolean): Boolean {
        if (UpstoxOAuth.isRedirect(url, credentials.redirectUri)) {
            if (!handled) { handled = true; complete(url) }
            return true
        }
        if (mainFrame && !UpstoxOAuth.isUpstoxPage(url)) {
            problem = "Upstox sent the sign-in to ${Uri.parse(url).host ?: "another site"}, which Marksy doesn't open."
            return true
        }
        return false
    }

    private fun complete(url: String) {
        when (val r = UpstoxOAuth.parseRedirect(url, state)) {
            is UpstoxOAuth.Redirect.Failed -> problem = r.message
            is UpstoxOAuth.Redirect.Code -> {
                busy = true
                lifecycleScope.launch {
                    try {
                        store.saveToken(UpstoxOAuth.exchange(r.code, credentials), System.currentTimeMillis())
                        setResult(RESULT_OK)
                        finish()
                    } catch (e: IOException) {
                        problem = e.message ?: "Couldn't finish the Upstox sign-in"
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    private fun retry() {
        problem = null
        handled = false
        state = UpstoxOAuth.newState()
        web?.loadUrl(UpstoxOAuth.authorizeUrl(credentials, state))
    }

    private companion object { const val KEY_STATE = "upstox_state" }
}
