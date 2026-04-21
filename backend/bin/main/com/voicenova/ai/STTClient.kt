package com.voicenova.ai

import com.voicenova.config.AppConfig
import com.voicenova.models.SttProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer

private val log = KotlinLogging.logger {}

/**
 * Speech-to-Text client.
 * In mock mode (no API key), returns empty string (Twilio handles STT via <Gather>).
 */
class STTClient(private val config: AppConfig) {
    private val json = Json { ignoreUnknownKeys = true }

    private val voskModel: Model? by lazy {
        try {
            Model(config.stt.modelPath)
        } catch (e: Exception) {
            log.error { "[STT][Vosk] Failed to load model at ${config.stt.modelPath}: ${e.message}" }
            null
        }
    }

    /**
     * Transcribe audio bytes to text.
     * In real calls, Twilio's <Gather input="speech"> does the STT for us
     * and sends the result via webhook — so this is mainly for non-Twilio pipelines.
     */
    suspend fun transcribe(audioBytes: ByteArray, languageCode: String = "hi-IN"): String {
        if (config.stt.provider == SttProvider.VOSK) {
            return transcribeVosk(audioBytes)
        }

        if (config.stt.apiKey.isBlank()) {
            log.info { "[STT MOCK] Would transcribe ${audioBytes.size} bytes in $languageCode" }
            return ""
        }

        return when (config.stt.provider) {
            SttProvider.GOOGLE -> transcribeGoogle(audioBytes, languageCode)
            SttProvider.OPENAI_WHISPER -> transcribeWhisper(audioBytes)
            else -> {
                log.warn { "[STT] Provider ${config.stt.provider} not implemented, returning empty." }
                ""
            }
        }
    }

    private fun transcribeGoogle(audioBytes: ByteArray, languageCode: String): String {
        log.debug { "[STT] Google transcribe ${audioBytes.size} bytes ($languageCode)" }
        return ""
    }

    private fun transcribeWhisper(audioBytes: ByteArray): String {
        log.debug { "[STT] Whisper transcribe ${audioBytes.size} bytes" }
        return ""
    }

    private fun transcribeVosk(audioBytes: ByteArray): String {
        if (audioBytes.isEmpty()) return ""
        val model = voskModel ?: return ""
        return try {
            Recognizer(model, config.stt.sampleRate.toFloat()).use { recognizer ->
                recognizer.acceptWaveForm(audioBytes, audioBytes.size)
                val resultJson = json.parseToJsonElement(recognizer.finalResult).jsonObject
                resultJson["text"]?.jsonPrimitive?.content.orEmpty()
            }
        } catch (e: Exception) {
            log.error { "[STT][Vosk] Transcription failed: ${e.message}" }
            ""
        }
    }
}
