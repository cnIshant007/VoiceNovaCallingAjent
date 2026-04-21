package com.voicenova.di

import com.voicenova.ai.LLMClient
import com.voicenova.ai.STTClient
import com.voicenova.ai.TTSClient
import com.voicenova.config.AppConfig
import com.voicenova.services.CallService
import com.voicenova.services.LanguageService
import com.voicenova.services.PlivoService
import com.voicenova.services.TrainingService
import com.voicenova.services.TwilioService
import com.voicenova.services.VoiceBackendService
import com.voicenova.services.HrCallService
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module

fun appModule(config: AppConfig) = module {
    single { config }

    single {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = config.llm.requestTimeoutMs
                socketTimeoutMillis = config.llm.requestTimeoutMs
                connectTimeoutMillis = 15_000
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Logging) {
                level = LogLevel.INFO
            }
        }
    }

    single { LLMClient(httpClient = get(), config = get()) }
    single { STTClient(config = get()) }
    single { TTSClient(httpClient = get(), config = get()) }
    single { LanguageService() }
    single { TrainingService(config = get(), llmClient = get()) }
    single { TwilioService(httpClient = get(), config = get()) }
    single { PlivoService(httpClient = get(), config = get()) }
    single { VoiceBackendService(config = get()) }
    single { HrCallService(config = get()) }
    single {
        CallService(
            llmClient = get(),
            ttsClient = get(),
            languageService = get(),
            trainingService = get(),
            config = get()
        )
    }
}
