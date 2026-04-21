// ============================================================
// AppConfig.kt
// ============================================================
package com.voicenova.config

import com.voicenova.models.LlmProvider
import com.voicenova.models.SttProvider
import com.voicenova.models.TtsProvider
import java.io.File

data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val publicBaseUrl: String = "",
    val llm: LlmConfig = LlmConfig(),
    val stt: SttConfig = SttConfig(),
    val tts: TtsConfig = TtsConfig(),
    val xai: XAiConfig = XAiConfig(),
    val voice: VoiceConfig = VoiceConfig(),
    val plivo: PlivoConfig = PlivoConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val redis: RedisConfig = RedisConfig(),
    val twilio: TwilioConfig = TwilioConfig(),
    val email: EmailConfig = EmailConfig(),
    val knowledgePath: String = "./knowledge",
    val storagePath: String = "./storage",
    val enableSelfLearning: Boolean = true,
    val minCallScoreForLearning: Float = 4.0f,
    val autoApproveLearning: Boolean = false,
    val mockTelephony: Boolean = true,
    val defaultLanguage: String = "hi-IN",
    val autoDetectLanguage: Boolean = true,
    val apiSecretKey: String = "dev_secret"
) {
    companion object {
        fun load(): AppConfig {
            val dotenv = loadDotEnv()

            fun env(key: String, default: String = "") =
                System.getenv(key) ?: dotenv[key] ?: default

            fun envBool(key: String, default: Boolean) =
                (System.getenv(key) ?: dotenv[key])?.lowercase()?.let { it == "true" } ?: default

            fun envFloat(key: String, default: Float) =
                (System.getenv(key) ?: dotenv[key])?.toFloatOrNull() ?: default

            val llmProvider = when (env("LLM_PROVIDER", "local").lowercase()) {
                "openai" -> LlmProvider.OPENAI
                "google" -> LlmProvider.GOOGLE
                "open-source", "opensource", "local", "ollama", "lmstudio", "vllm", "llamacpp" -> LlmProvider.OPEN_SOURCE
                else -> LlmProvider.ANTHROPIC
            }

            val defaultLlmApiUrl = when (llmProvider) {
                LlmProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
                LlmProvider.OPEN_SOURCE -> "http://127.0.0.1:11434/api/chat"
                else -> "https://api.anthropic.com/v1/messages"
            }

            val defaultLlmModel = when (llmProvider) {
                LlmProvider.OPEN_SOURCE -> "gemma:2b"
                LlmProvider.OPENAI -> "gpt-4o-mini"
                else -> "claude-sonnet-4-20250514"
            }

            val llmApiKey = env("LLM_API_KEY").ifEmpty {
                when (llmProvider) {
                    LlmProvider.ANTHROPIC -> env("ANTHROPIC_API_KEY")
                    LlmProvider.OPENAI, LlmProvider.GOOGLE -> env("OPENAI_API_KEY")
                    LlmProvider.OPEN_SOURCE -> ""
                }
            }

            return AppConfig(
                server = ServerConfig(
                    port = env("SERVER_PORT", "8080").toInt(),
                    host = env("SERVER_HOST", "0.0.0.0")
                ),
                publicBaseUrl = env("PUBLIC_BASE_URL", ""),
                llm = LlmConfig(
                    provider = llmProvider,
                    apiKey = llmApiKey,
                    apiUrl = env("LLM_API_URL", defaultLlmApiUrl),
                    model = env("LLM_MODEL", defaultLlmModel),
                    maxTokens = env("LLM_MAX_TOKENS", "300").toInt(),
                    temperature = env("LLM_TEMPERATURE", "0.7").toDouble(),
                    apiFormat = env("LLM_API_FORMAT", "auto"),
                    requestTimeoutMs = env("LLM_REQUEST_TIMEOUT_MS", "120000").toLong(),
                    callResponseTimeoutMs = env("LLM_CALL_RESPONSE_TIMEOUT_MS", "10000").toLong()
                ),
                stt = SttConfig(
                    provider = when (env("STT_PROVIDER", "vosk")) {
                        "openai_whisper" -> SttProvider.OPENAI_WHISPER
                        "azure" -> SttProvider.AZURE
                        "vosk" -> SttProvider.VOSK
                        else -> SttProvider.GOOGLE
                    },
                    apiKey = env("GOOGLE_SPEECH_KEY").ifEmpty { env("OPENAI_API_KEY") },
                    modelPath = env("VOSK_MODEL_PATH", "./models/vosk"),
                    sampleRate = env("VOSK_SAMPLE_RATE", "16000").toInt()
                ),
                tts = TtsConfig(
                    provider = when (env("TTS_PROVIDER", "piper")) {
                        "google" -> TtsProvider.GOOGLE
                        "azure" -> TtsProvider.AZURE
                        "twilio_basic" -> TtsProvider.TWILIO_BASIC
                        "piper" -> TtsProvider.PIPER
                        "macos_say", "say", "macos" -> TtsProvider.MACOS_SAY
                        else -> TtsProvider.ELEVENLABS
                    },
                    apiKey = env("ELEVENLABS_API_KEY").ifEmpty { env("GOOGLE_TTS_KEY") },
                    voiceId = env("ELEVENLABS_VOICE_ID", "JBFqnCBsd6RMkjVDRZzb"),
                    modelId = env("ELEVENLABS_MODEL_ID", "eleven_multilingual_v2"),
                    outputFormat = env("ELEVENLABS_OUTPUT_FORMAT", "mp3_44100_128"),
                    piperBin = env("PIPER_BIN", "piper"),
                    piperModelPath = env("PIPER_MODEL_PATH", "./models/piper/en_US-amy-medium.onnx"),
                    piperSampleRate = env("PIPER_SAMPLE_RATE", "16000").toInt()
                ),
                xai = XAiConfig(
                    apiKey = env("XAI_API_KEY"),
                    realtimeUrl = env("XAI_REALTIME_URL", "wss://api.x.ai/v1/realtime")
                ),
                voice = VoiceConfig(
                    defaultBackend = env("VOICE_BACKEND_MODE", "twilio_native")
                ),
                plivo = PlivoConfig(
                    authId = env("PLIVO_AUTH_ID"),
                    authToken = env("PLIVO_AUTH_TOKEN"),
                    phoneNumber = env("PLIVO_PHONE_NUMBER")
                ),
                database = DatabaseConfig(
                    url = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/voicenova"),
                    user = env("DATABASE_USER", env("DB_USER", "postgres")),
                    password = env("DATABASE_PASSWORD", env("DB_PASS", "password"))
                ),
                redis = RedisConfig(url = env("REDIS_URL", "redis://localhost:6379")),
                twilio = TwilioConfig(
                    accountSid = env("TWILIO_ACCOUNT_SID"),
                    authToken = env("TWILIO_AUTH_TOKEN"),
                    phoneNumber = env("TWILIO_PHONE_NUMBER"),
                    escalationPhoneNumber = env("TWILIO_ESCALATION_NUMBER", "+91XXXXXXXXXX")
                ),
                email = EmailConfig(
                    host = env("SMTP_HOST"),
                    port = env("SMTP_PORT", "587").toIntOrNull() ?: 587,
                    username = env("SMTP_USERNAME"),
                    password = env("SMTP_PASSWORD"),
                    fromAddress = env("SMTP_FROM_ADDRESS"),
                    fromName = env("SMTP_FROM_NAME", "HR Team"),
                    useTls = envBool("SMTP_USE_TLS", true)
                ),
                knowledgePath = env("KNOWLEDGE_PATH", "./knowledge"),
                storagePath = env("STORAGE_PATH", "./storage"),
                enableSelfLearning = envBool("ENABLE_SELF_LEARNING", true),
                minCallScoreForLearning = envFloat("MIN_CALL_SCORE_FOR_LEARNING", 4.0f),
                autoApproveLearning = envBool("AUTO_APPROVE_LEARNING", false),
                mockTelephony = envBool("MOCK_TELEPHONY", true),
                defaultLanguage = env("DEFAULT_LANGUAGE", "hi-IN"),
                autoDetectLanguage = envBool("AUTO_DETECT_LANGUAGE", true),
                apiSecretKey = env("API_SECRET_KEY", "dev_secret_change_me")
            )
        }

        private fun loadDotEnv(): Map<String, String> {
            val file = File(".env")
            if (!file.exists()) return emptyMap()

            return file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
                .associate { line ->
                    val (key, rawValue) = line.split("=", limit = 2)
                    key.trim() to parseEnvValue(rawValue)
                }
        }

        private fun parseEnvValue(rawValue: String): String {
            val trimmed = rawValue.trim()
            if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
                return trimmed.removeSurrounding("\"").removeSurrounding("'")
            }

            val commentIndex = trimmed.indexOf(" #")
            val withoutComment = if (commentIndex >= 0) trimmed.substring(0, commentIndex) else trimmed
            return withoutComment.trim()
        }
    }
}

data class ServerConfig(val port: Int = 8080, val host: String = "0.0.0.0")
data class LlmConfig(
    val provider: LlmProvider = LlmProvider.OPEN_SOURCE,
    val apiKey: String = "",
    val apiUrl: String = "",
    val model: String = "gemma:2b",
    val maxTokens: Int = 300,
    val temperature: Double = 0.7,
    val apiFormat: String = "auto",
    val requestTimeoutMs: Long = 120_000,
    val callResponseTimeoutMs: Long = 10_000
)
data class SttConfig(
    val provider: SttProvider = SttProvider.VOSK,
    val apiKey: String = "",
    val modelPath: String = "./models/vosk",
    val sampleRate: Int = 16000
)
data class TtsConfig(
    val provider: TtsProvider = TtsProvider.PIPER,
    val apiKey: String = "",
    val voiceId: String = "JBFqnCBsd6RMkjVDRZzb",
    val modelId: String = "eleven_multilingual_v2",
    val outputFormat: String = "mp3_44100_128",
    val piperBin: String = "piper",
    val piperModelPath: String = "./models/piper/en_US-amy-medium.onnx",
    val piperSampleRate: Int = 16000
)
data class XAiConfig(
    val apiKey: String = "",
    val realtimeUrl: String = "wss://api.x.ai/v1/realtime"
)
data class VoiceConfig(
    val defaultBackend: String = "twilio_native"
)
data class PlivoConfig(
    val authId: String = "",
    val authToken: String = "",
    val phoneNumber: String = ""
)
data class DatabaseConfig(
    val url: String = "jdbc:postgresql://localhost:5432/voicenova",
    val user: String = "vnova",
    val password: String = "password",
    val poolSize: Int = 10
)
data class RedisConfig(val url: String = "redis://localhost:6379")
data class TwilioConfig(
    val accountSid: String = "",
    val authToken: String = "",
    val phoneNumber: String = "",
    val escalationPhoneNumber: String = "+91XXXXXXXXXX"
)
data class EmailConfig(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    val password: String = "",
    val fromAddress: String = "",
    val fromName: String = "HR Team",
    val useTls: Boolean = true
)
