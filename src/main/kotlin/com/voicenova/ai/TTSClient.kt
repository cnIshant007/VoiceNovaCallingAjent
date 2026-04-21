package com.voicenova.ai

import com.voicenova.config.AppConfig
import com.voicenova.models.TtsProvider
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

class TTSClient(
    private val httpClient: HttpClient,
    private val config: AppConfig
) {
    private val runtimeProviderFile = File(config.storagePath, "runtime/tts-provider.json")

    data class PiperVoiceOption(
        val id: String,
        val label: String,
        val gender: String,
        val modelPath: String
    )

    data class TtsProviderStatus(
        val provider: String,
        val selectedPiperVoice: String? = null,
        val availablePiperVoices: List<PiperVoiceOption> = emptyList(),
        val configured: Boolean,
        val ready: Boolean,
        val hint: String
    )

    data class GeneratedAudio(
        val fileName: String,
        val publicUrl: String
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val piperVoices = listOf(
        PiperVoiceOption(
            id = "amy",
            label = "Amy",
            gender = "female",
            modelPath = "./models/piper/en_US-amy-medium.onnx"
        ),
        PiperVoiceOption(
            id = "lessac",
            label = "Lessac",
            gender = "male",
            modelPath = "./models/piper/en_US-lessac-medium.onnx"
        )
    )
    private val selectedProvider = AtomicReference(loadPersistedProvider() ?: config.tts.provider)
    private val selectedPiperVoice = AtomicReference(loadPersistedPiperVoice() ?: inferPiperVoiceId(config.tts.piperModelPath))

    suspend fun synthesize(text: String, languageCode: String = "hi-IN"): ByteArray {
        return when (selectedProvider.get()) {
            TtsProvider.ELEVENLABS -> {
                if (config.tts.apiKey.isBlank()) {
                    log.info { "[TTS MOCK] Would speak (${languageCode}) with ElevenLabs: \"$text\"" }
                    ByteArray(0)
                } else {
                    synthesizeElevenLabs(text)
                }
            }
            TtsProvider.GOOGLE -> synthesizeGoogle(text, languageCode)
            TtsProvider.PIPER -> synthesizePiper(text)
            TtsProvider.MACOS_SAY -> synthesizeMacOsSay(text, languageCode)
            else -> {
                log.info { "[TTS] Provider ${selectedProvider.get()} uses built-in <Say>. No synthesis needed." }
                ByteArray(0)
            }
        }
    }

    fun availableProviders(): List<String> = listOf("piper", "macos_say", "elevenlabs", "twilio_basic")

    fun availablePiperVoices(): List<PiperVoiceOption> = piperVoices

    fun currentProvider(): String = selectedProvider.get().wireValue()

    fun updateProvider(rawProvider: String, rawPiperVoice: String? = null): TtsProviderStatus {
        val provider = parseProvider(rawProvider)
        val piperVoiceId = resolvePiperVoiceId(rawPiperVoice)
        selectedProvider.set(provider)
        selectedPiperVoice.set(piperVoiceId)
        persistRuntimeSettings(provider, piperVoiceId)
        log.info { "TTS runtime settings updated: provider=${provider.wireValue()} piperVoice=$piperVoiceId" }
        return describe(provider)
    }

    fun describe(): TtsProviderStatus = describe(selectedProvider.get())

    suspend fun synthesizeToPublicUrl(text: String, languageCode: String = "hi-IN"): GeneratedAudio? {
        val provider = selectedProvider.get()
        if (provider != TtsProvider.ELEVENLABS && provider != TtsProvider.PIPER && provider != TtsProvider.MACOS_SAY) return null
        if (config.publicBaseUrl.isBlank()) {
            log.warn { "[TTS] ${provider.wireValue()} selected but PUBLIC_BASE_URL is missing. Phone playback needs a public URL." }
            return null
        }

        val storageDir = File(config.storagePath, "tts").apply { mkdirs() }
        return when (provider) {
            TtsProvider.ELEVENLABS -> {
                if (config.tts.apiKey.isBlank()) {
                    log.warn { "[TTS] ElevenLabs selected but ELEVENLABS_API_KEY is missing." }
                    null
                } else {
                    val audioBytes = synthesizeElevenLabs(text)
                    if (audioBytes.isEmpty()) {
                        null
                    } else {
                        val fileName = "tts_${UUID.randomUUID().toString().take(12)}.mp3"
                        File(storageDir, fileName).writeBytes(audioBytes)
                        GeneratedAudio(
                            fileName = fileName,
                            publicUrl = "${config.publicBaseUrl.trimEnd('/')}/audio/$fileName"
                        )
                    }
                }
            }
            TtsProvider.PIPER -> {
                val fileName = "tts_${UUID.randomUUID().toString().take(12)}.wav"
                val outputFile = File(storageDir, fileName)
                if (!synthesizePiperToFile(text, outputFile)) {
                    null
                } else {
                    GeneratedAudio(
                        fileName = fileName,
                        publicUrl = "${config.publicBaseUrl.trimEnd('/')}/audio/$fileName"
                    )
                }
            }
            TtsProvider.MACOS_SAY -> {
                val fileName = "tts_${UUID.randomUUID().toString().take(12)}.wav"
                val outputFile = File(storageDir, fileName)
                if (!synthesizeMacOsSayToFile(text, languageCode, outputFile)) {
                    null
                } else {
                    GeneratedAudio(
                        fileName = fileName,
                        publicUrl = "${config.publicBaseUrl.trimEnd('/')}/audio/$fileName"
                    )
                }
            }
        }
    }

    private suspend fun synthesizeElevenLabs(text: String): ByteArray {
        log.debug { "[TTS] ElevenLabs synthesize: ${text.take(50)}" }

        val response: HttpResponse = httpClient.post("https://api.elevenlabs.io/v1/text-to-speech/${config.tts.voiceId}") {
            header("xi-api-key", config.tts.apiKey)
            header(HttpHeaders.Accept, "audio/mpeg")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("text", text)
                    put("model_id", config.tts.modelId)
                    put("output_format", config.tts.outputFormat)
                }
            )
        }

        val contentType = response.contentType()
        if (!response.status.isSuccess() || contentType?.contentType != "audio") {
            val errorBody = response.bodyAsText()
            log.error {
                "[TTS] ElevenLabs error status=${response.status.value} contentType=${contentType ?: "unknown"} body=${errorBody.take(400)}"
            }
            return ByteArray(0)
        }

        val audioBytes: ByteArray = response.body()
        if (audioBytes.startsWithJsonObject()) {
            log.error { "[TTS] ElevenLabs returned JSON payload instead of audio. Falling back to provider voice." }
            return ByteArray(0)
        }
        return audioBytes
    }

    private fun synthesizeGoogle(text: String, languageCode: String): ByteArray {
        log.debug { "[TTS] Google synthesize ($languageCode): ${text.take(50)}" }
        return ByteArray(0)
    }

    private fun loadPersistedProvider(): TtsProvider? {
        return runCatching {
            if (!runtimeProviderFile.exists()) return null
            val root = json.parseToJsonElement(runtimeProviderFile.readText()).jsonObject
            parseProvider(root.stringOrNull("provider"))
        }.onFailure { cause ->
            log.warn(cause) { "Could not load persisted TTS provider. Falling back to startup config." }
        }.getOrNull()
    }

    private fun loadPersistedPiperVoice(): String? {
        return runCatching {
            if (!runtimeProviderFile.exists()) return null
            val root = json.parseToJsonElement(runtimeProviderFile.readText()).jsonObject
            resolvePiperVoiceId(root.stringOrNull("piper_voice"))
        }.onFailure { cause ->
            log.warn(cause) { "Could not load persisted Piper voice. Falling back to startup config." }
        }.getOrNull()
    }

    private fun persistRuntimeSettings(provider: TtsProvider, piperVoiceId: String) {
        runCatching {
            runtimeProviderFile.parentFile?.mkdirs()
            val payload = buildJsonObject {
                put("provider", provider.wireValue())
                put("piper_voice", piperVoiceId)
            }
            runtimeProviderFile.writeText(json.encodeToString(JsonObject.serializer(), payload))
        }.onFailure { cause ->
            log.warn(cause) { "Could not persist TTS runtime settings." }
        }
    }

    private fun describe(provider: TtsProvider): TtsProviderStatus {
        val voices = availablePiperVoices()
        val selectedVoice = currentPiperVoice()
        return when (provider) {
            TtsProvider.ELEVENLABS -> {
                val configured = config.tts.apiKey.isNotBlank()
                val ready = configured && config.publicBaseUrl.isNotBlank()
                val hint = when {
                    !configured -> "Add ELEVENLABS_API_KEY to use ElevenLabs voice."
                    config.publicBaseUrl.isBlank() -> "Set PUBLIC_BASE_URL so calls can play generated ElevenLabs audio."
                    else -> "ElevenLabs is ready. Calls will use generated audio via <Play>."
                }
                TtsProviderStatus(
                    provider = provider.wireValue(),
                    selectedPiperVoice = selectedVoice.id,
                    availablePiperVoices = voices,
                    configured = configured,
                    ready = ready,
                    hint = hint
                )
            }
            TtsProvider.TWILIO_BASIC -> TtsProviderStatus(
                provider = provider.wireValue(),
                selectedPiperVoice = selectedVoice.id,
                availablePiperVoices = voices,
                configured = true,
                ready = true,
                hint = "Twilio built-in <Say> voices are active. Use Piper when you want offline local synthesis."
            )
            TtsProvider.MACOS_SAY -> {
                val sayExists = hasMacOsSayBinary()
                val convertExists = hasAfconvertBinary()
                val configured = sayExists && convertExists
                val ready = configured && config.publicBaseUrl.isNotBlank()
                val hint = when {
                    !sayExists -> "macOS 'say' was not found on this machine."
                    !convertExists -> "macOS 'afconvert' was not found, so VoiceNova cannot export call audio files."
                    config.publicBaseUrl.isBlank() -> "macOS local speech is installed. Set PUBLIC_BASE_URL so callers can play generated audio."
                    else -> "macOS local speech is ready and can generate offline call audio on this Mac."
                }
                TtsProviderStatus(
                    provider = provider.wireValue(),
                    selectedPiperVoice = selectedVoice.id,
                    availablePiperVoices = voices,
                    configured = configured,
                    ready = ready,
                    hint = hint
                )
            }
            TtsProvider.PIPER -> {
                val binExists = hasPiperBinary()
                val modelExists = File(selectedVoice.modelPath).exists()
                val configured = binExists && modelExists
                val ready = configured && config.publicBaseUrl.isNotBlank()
                val hint = when {
                    !binExists -> "Install the piper binary and set PIPER_BIN."
                    !modelExists -> "Download the selected Piper voice model: ${selectedVoice.label}."
                    config.publicBaseUrl.isBlank() -> "Piper is installed locally. Set PUBLIC_BASE_URL so callers can play generated audio."
                    else -> "Piper is ready and using the ${selectedVoice.label} voice on local CPU."
                }
                TtsProviderStatus(
                    provider = provider.wireValue(),
                    selectedPiperVoice = selectedVoice.id,
                    availablePiperVoices = voices,
                    configured = configured,
                    ready = ready,
                    hint = hint
                )
            }
            TtsProvider.GOOGLE -> TtsProviderStatus(
                provider = provider.wireValue(),
                selectedPiperVoice = selectedVoice.id,
                availablePiperVoices = voices,
                configured = false,
                ready = false,
                hint = "Google TTS is not wired into the live call flow yet."
            )
            TtsProvider.AZURE -> TtsProviderStatus(
                provider = provider.wireValue(),
                selectedPiperVoice = selectedVoice.id,
                availablePiperVoices = voices,
                configured = false,
                ready = false,
                hint = "Azure TTS is not wired into the live call flow yet."
            )
        }
    }

    private fun parseProvider(rawProvider: String?): TtsProvider {
        return when (rawProvider?.trim()?.lowercase()) {
            "twilio_basic", "twilio-basic", "twilio" -> TtsProvider.TWILIO_BASIC
            "piper" -> TtsProvider.PIPER
            "macos_say", "say", "macos" -> TtsProvider.MACOS_SAY
            else -> TtsProvider.ELEVENLABS
        }
    }

    private fun TtsProvider.wireValue(): String =
        when (this) {
            TtsProvider.ELEVENLABS -> "elevenlabs"
            TtsProvider.TWILIO_BASIC -> "twilio_basic"
            TtsProvider.PIPER -> "piper"
            TtsProvider.MACOS_SAY -> "macos_say"
            TtsProvider.GOOGLE -> "google"
            TtsProvider.AZURE -> "azure"
        }

    private fun ByteArray.startsWithJsonObject(): Boolean = firstOrNull()?.toInt()?.toChar() == '{'

    private fun currentPiperVoice(): PiperVoiceOption {
        return piperVoices.firstOrNull { it.id == selectedPiperVoice.get() } ?: piperVoices.first()
    }

    private fun inferPiperVoiceId(modelPath: String): String {
        val normalized = modelPath.lowercase()
        return piperVoices.firstOrNull { normalized.contains(it.id) }?.id ?: piperVoices.first().id
    }

    private fun resolvePiperVoiceId(rawPiperVoice: String?): String {
        val value = rawPiperVoice?.trim()?.lowercase()
        return piperVoices.firstOrNull { it.id == value }?.id ?: selectedPiperVoice.get() ?: inferPiperVoiceId(config.tts.piperModelPath)
    }

    private fun hasPiperBinary(): Boolean {
        return runCatching {
            val process = ProcessBuilder(config.tts.piperBin, "--help")
                .redirectErrorStream(true)
                .start()
            process.inputStream.close()
            process.waitFor()
            true
        }.getOrDefault(false)
    }

    private fun hasMacOsSayBinary(): Boolean = File("/usr/bin/say").canExecute()

    private fun hasAfconvertBinary(): Boolean = File("/usr/bin/afconvert").canExecute()

    private fun JsonObject.stringOrNull(key: String): String? {
        return this[key]?.toString()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
    }

    private fun synthesizePiper(text: String): ByteArray {
        val tempDir = File(config.storagePath, "tts").apply { mkdirs() }
        val tempFile = File(tempDir, "piper_${UUID.randomUUID().toString().take(12)}.wav")
        return try {
            if (!synthesizePiperToFile(text, tempFile)) {
                ByteArray(0)
            } else {
                tempFile.readBytes()
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun synthesizePiperToFile(text: String, outputFile: File): Boolean {
        val voice = currentPiperVoice()
        if (!File(voice.modelPath).exists()) {
            log.warn { "[TTS][Piper] Model not found at ${voice.modelPath}" }
            return false
        }

        return try {
            outputFile.parentFile?.mkdirs()
            val process = ProcessBuilder(
                config.tts.piperBin,
                "--model",
                voice.modelPath,
                "--output_file",
                outputFile.absolutePath
            )
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use {
                it.write(text)
                it.newLine()
            }
            val logs = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit != 0 || !outputFile.exists() || outputFile.length() == 0L) {
                log.error { "[TTS][Piper] exited with $exit. logs=${logs.take(400)}" }
                false
            } else {
                true
            }
        } catch (cause: Exception) {
            log.error(cause) { "[TTS][Piper] Failed to synthesize local audio." }
            false
        }
    }

    private fun synthesizeMacOsSay(text: String, languageCode: String): ByteArray {
        val tempDir = File(config.storagePath, "tts").apply { mkdirs() }
        val tempFile = File(tempDir, "say_${UUID.randomUUID().toString().take(12)}.wav")
        return try {
            if (!synthesizeMacOsSayToFile(text, languageCode, tempFile)) {
                ByteArray(0)
            } else {
                tempFile.readBytes()
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun synthesizeMacOsSayToFile(text: String, languageCode: String, outputFile: File): Boolean {
        if (!hasMacOsSayBinary() || !hasAfconvertBinary()) {
            log.warn { "[TTS][macOS say] Required binaries are missing." }
            return false
        }

        val tempAiff = File(outputFile.parentFile, "say_${UUID.randomUUID().toString().take(12)}.aiff")
        val voice = macOsVoiceFor(languageCode)
        return try {
            outputFile.parentFile?.mkdirs()
            val sayProcess = ProcessBuilder(
                "/usr/bin/say",
                "-v",
                voice,
                "-o",
                tempAiff.absolutePath,
                text
            )
                .redirectErrorStream(true)
                .start()
            val sayLogs = sayProcess.inputStream.bufferedReader().use { it.readText() }
            val sayExit = sayProcess.waitFor()
            if (sayExit != 0 || !tempAiff.exists() || tempAiff.length() == 0L) {
                log.error { "[TTS][macOS say] exited with $sayExit. logs=${sayLogs.take(400)}" }
                return false
            }

            val convertProcess = ProcessBuilder(
                "/usr/bin/afconvert",
                "-f",
                "WAVE",
                "-d",
                "LEI16@16000",
                tempAiff.absolutePath,
                outputFile.absolutePath
            )
                .redirectErrorStream(true)
                .start()
            val convertLogs = convertProcess.inputStream.bufferedReader().use { it.readText() }
            val convertExit = convertProcess.waitFor()
            if (convertExit != 0 || !outputFile.exists() || outputFile.length() == 0L) {
                log.error { "[TTS][macOS say] afconvert exited with $convertExit. logs=${convertLogs.take(400)}" }
                false
            } else {
                true
            }
        } catch (cause: Exception) {
            log.error(cause) { "[TTS][macOS say] Failed to synthesize local audio." }
            false
        } finally {
            tempAiff.delete()
        }
    }

    private fun macOsVoiceFor(languageCode: String): String {
        return when (languageCode.lowercase()) {
            "hi-in", "hi" -> "Lekha"
            "ta-in", "ta" -> "Vani"
            "te-in", "te" -> "Geeta"
            "bn-in", "bn" -> "Piya"
            "kn-in", "kn" -> "Soumya"
            "en-in" -> "Aman"
            else -> "Aman"
        }
    }
}
