package com.voicenova.plugins

import com.voicenova.ai.LLMClient
import com.voicenova.ai.TTSClient
import com.voicenova.config.AppConfig
import com.voicenova.routes.apiRoutes
import com.voicenova.routes.plivoRoutes
import com.voicenova.routes.twilioRoutes
import com.voicenova.services.CallService
import com.voicenova.services.LanguageService
import com.voicenova.services.PlivoService
import com.voicenova.services.TrainingService
import com.voicenova.services.TwilioService
import com.voicenova.services.VoiceBackendService
import com.voicenova.services.HrCallService
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.io.File

fun Application.configureRouting(
    config: AppConfig,
    callService: CallService,
    languageService: LanguageService,
    trainingService: TrainingService,
    twilioService: TwilioService,
    plivoService: PlivoService,
    voiceBackendService: VoiceBackendService,
    hrCallService: HrCallService,
    ttsClient: TTSClient,
    llmClient: LLMClient
) {
    routing {
        // Root health check
        get("/health") {
            call.respond(mapOf(
                "status" to "ok",
                "service" to "VoiceNova AI Agent",
                "version" to "1.0.0",
                "mockMode" to twilioService.isMockMode.toString()
            ))
        }

        // Twilio webhooks
        route("/webhooks") {
            twilioRoutes(callService, hrCallService)
            plivoRoutes(callService, plivoService, hrCallService)
        }

        // All API routes
        apiRoutes(config, callService, languageService, trainingService, twilioService, plivoService, voiceBackendService, hrCallService, ttsClient, llmClient)

        get("/") {
            call.respondRedirect("/site/index.html", permanent = false)
        }

        get("/site") {
            call.respondRedirect("/site/index.html", permanent = false)
        }

        get("/admin") {
            call.respondRedirect("/admin/index.html", permanent = false)
        }

        get("/dashboard") {
            call.respondRedirect("/admin/index.html", permanent = false)
        }

        staticResources("/site", "site")

        staticResources("/admin", "admin")
        staticFiles("/audio", File(config.storagePath, "tts"))
    }
}
