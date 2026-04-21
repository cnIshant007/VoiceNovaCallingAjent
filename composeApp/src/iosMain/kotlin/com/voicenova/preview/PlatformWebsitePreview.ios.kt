package com.voicenova.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.WebKit.WKWebView

@Composable
actual fun WebsitePreview(url: String, modifier: Modifier) {
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView().apply {
                loadRequest(ngrokAwareRequest(url))
            }
        },
        update = { webView ->
            webView.loadRequest(ngrokAwareRequest(url))
        }
    )
}

actual fun defaultBackendBaseUrl(): String = "https://semiotic-unprocessional-misha.ngrok-free.dev"

private fun ngrokAwareRequest(url: String): NSMutableURLRequest {
    val request = NSMutableURLRequest.requestWithURL(NSURL(string = url)!!)
    request.setValue("true", forHTTPHeaderField = "ngrok-skip-browser-warning")
    return request
}
