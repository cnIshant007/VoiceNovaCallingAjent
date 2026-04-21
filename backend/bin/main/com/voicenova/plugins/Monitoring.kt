package com.voicenova.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import mu.KotlinLogging
import org.slf4j.event.Level

private val log = KotlinLogging.logger {}

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            // Skip health check spam from logs
            !call.request.local.uri.contains("/health")
        }
    }

    log.info("Monitoring configured.")
}
