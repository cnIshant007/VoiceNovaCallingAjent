package com.voicenova.preview

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun WebsitePreview(url: String, modifier: Modifier) {
    val ngrokHeaders = mapOf("ngrok-skip-browser-warning" to "true")
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webChromeClient = WebChromeClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                loadUrl(url, ngrokHeaders)
            }
        },
        update = { webView ->
            if (webView.url != url) {
                webView.loadUrl(url, ngrokHeaders)
            }
        }
    )
}

actual fun defaultBackendBaseUrl(): String = "https://semiotic-unprocessional-misha.ngrok-free.dev"
