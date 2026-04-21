package com.voicenova

import com.voicenova.ai.LLMClient
import com.voicenova.ai.TTSClient
import com.voicenova.config.AppConfig
import com.voicenova.di.appModule
import com.voicenova.plugins.*
import com.voicenova.services.CallService
import com.voicenova.services.LanguageService
import com.voicenova.services.PlivoService
import com.voicenova.services.TrainingService
import com.voicenova.services.TwilioService
import com.voicenova.services.VoiceBackendService
import com.voicenova.services.HrCallService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import mu.KotlinLogging
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

private val log = KotlinLogging.logger {}

fun main() {
    val config = AppConfig.load()
    log.info { "Starting VoiceNova AI Agent v1.0 on port ${config.server.port}" }

    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host
    ) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig.load()) {
    install(Koin) {
        slf4jLogger()
        modules(appModule(config))
    }
    val callService: CallService by inject()
    val languageService: LanguageService by inject()
    val trainingService: TrainingService by inject()
    val twilioService: TwilioService by inject()
    val plivoService: PlivoService by inject()
    val voiceBackendService: VoiceBackendService by inject()
    val hrCallService: HrCallService by inject()
    val ttsClient: TTSClient by inject()
    val llmClient: LLMClient by inject()

    configureSecurity(config)
    configureSerialization()
    configureCORS()
    configureStatusPages()
    configureMonitoring()
    configureRouting(config, callService, languageService, trainingService, twilioService, plivoService, voiceBackendService, hrCallService, ttsClient, llmClient)

    // Load knowledge base on startup via Koin injection
    environment.monitor.subscribe(ApplicationStarted) {
        trainingService.loadKnowledgeBase()
        log.info("Knowledge base loaded. Agent is ready.")
    }
}
