package com.voicenova.preview

import android.content.Intent

private var applicationContextProvider: (() -> android.content.Context)? = null

fun registerShareContextProvider(provider: () -> android.content.Context) {
    applicationContextProvider = provider
}

actual fun shareLink(title: String, url: String) {
    val context = applicationContextProvider?.invoke() ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, url)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
