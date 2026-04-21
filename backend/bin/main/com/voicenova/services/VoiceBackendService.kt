package com.voicenova.services

import com.voicenova.config.AppConfig
import com.voicenova.models.VoiceBackendMode
import com.voicenova.models.VoiceBackendResponse
import java.util.concurrent.atomic.AtomicReference

class VoiceBackendService(
    private val config: AppConfig
) {
    private val selectedMode = AtomicReference(parseMode(config.voice.defaultBackend))

    fun currentMode(): VoiceBackendMode = selectedMode.get()

    fun updateMode(rawMode: String): VoiceBackendResponse {
        val mode = parseMode(rawMode)
        selectedMode.set(mode)
        return describe(mode)
    }

    fun describe(): VoiceBackendResponse = describe(currentMode())

    fun availableModes(): List<String> = VoiceBackendMode.entries.map { it.wireValue() }

    private fun describe(mode: VoiceBackendMode): VoiceBackendResponse {
        val xaiConfigured = config.xai.apiKey.isNotBlank()
        val plivoConfigured = config.plivo.authId.isNotBlank() &&
            config.plivo.authToken.isNotBlank() &&
            config.plivo.phoneNumber.isNotBlank()
        val publicUrlConfigured = config.publicBaseUrl.isNotBlank()
        return when (mode) {
            VoiceBackendMode.TWILIO_NATIVE -> VoiceBackendResponse(
                voiceBackend = mode.wireValue(),
                configured = true,
                ready = true,
                hint = "Current built-in Twilio TwiML flow is active. Calls use the existing webhook, Gather, and local/cloud LLM loop."
            )
            VoiceBackendMode.GROK_VOICE_AGENT -> VoiceBackendResponse(
                voiceBackend = mode.wireValue(),
                configured = xaiConfigured,
                ready = false,
                hint = if (xaiConfigured) {
                    "xAI is configured, but PSTN calls still need a Twilio Media Streams to xAI realtime bridge before Grok Voice Agent can handle live phone audio."
                } else {
                    "Add XAI_API_KEY first. After that, Grok Voice Agent still needs a Twilio Media Streams to xAI realtime bridge before it can handle live phone audio."
                },
                websocketUrl = config.xai.realtimeUrl
            )
            VoiceBackendMode.PLIVO -> VoiceBackendResponse(
                voiceBackend = mode.wireValue(),
                configured = plivoConfigured,
                ready = plivoConfigured && publicUrlConfigured,
                hint = if (!plivoConfigured) {
                    "Add PLIVO_AUTH_ID, PLIVO_AUTH_TOKEN, and PLIVO_PHONE_NUMBER first."
                } else if (!publicUrlConfigured) {
                    "Set PUBLIC_BASE_URL so Plivo can reach /webhooks/plivo/inbound and /webhooks/plivo/status."
                } else {
                    "Plivo is ready. Point your Plivo number or application Answer URL to ${config.publicBaseUrl.trimEnd('/')}/webhooks/plivo/inbound and Hangup URL to ${config.publicBaseUrl.trimEnd('/')}/webhooks/plivo/status."
                }
            )
        }
    }

    private fun parseMode(rawMode: String?): VoiceBackendMode {
        return when (rawMode?.trim()?.lowercase()) {
            "grok_voice_agent", "grok-voice-agent", "grok" -> VoiceBackendMode.GROK_VOICE_AGENT
            "plivo" -> VoiceBackendMode.PLIVO
            else -> VoiceBackendMode.TWILIO_NATIVE
        }
    }

    private fun VoiceBackendMode.wireValue(): String =
        when (this) {
            VoiceBackendMode.TWILIO_NATIVE -> "twilio_native"
            VoiceBackendMode.GROK_VOICE_AGENT -> "grok_voice_agent"
            VoiceBackendMode.PLIVO -> "plivo"
        }
}
