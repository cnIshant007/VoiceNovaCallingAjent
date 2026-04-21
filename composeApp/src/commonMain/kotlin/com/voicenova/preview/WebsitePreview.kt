package com.voicenova.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun WebsitePreview(
    url: String,
    modifier: Modifier = Modifier
)

expect fun defaultBackendBaseUrl(): String

fun siteUrlFor(baseUrl: String): String = "${baseUrl.trimEnd('/')}/site/index.html"
