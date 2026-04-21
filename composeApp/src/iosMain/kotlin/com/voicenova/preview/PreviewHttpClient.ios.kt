package com.voicenova.preview

import io.ktor.client.*
import io.ktor.client.engine.darwin.*

actual fun createPreviewHttpClient(): HttpClient = HttpClient(Darwin, configurePreviewClient())
