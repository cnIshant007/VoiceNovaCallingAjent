package com.voicenova.preview

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*

actual fun createPreviewHttpClient(): HttpClient = HttpClient(OkHttp, configurePreviewClient())
