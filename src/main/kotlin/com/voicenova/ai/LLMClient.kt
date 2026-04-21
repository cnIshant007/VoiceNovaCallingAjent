package com.voicenova.ai

import com.voicenova.config.AppConfig
import com.voicenova.models.LlmProvider
import com.voicenova.models.LlmLocalProfileResponse
import com.voicenova.models.Message
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

class LLMClient(
    private val httpClient: HttpClient,
    private val config: AppConfig
) {
    private val runtimeSettingsFile = File(config.storagePath, "runtime/llm-settings.json")

    data class RuntimeSettings(
        val provider: LlmProvider,
        val model: String,
        val apiUrl: String,
        val apiKey: String,
        val apiFormat: String
    )

    data class RuntimeStatus(
        val provider: String,
        val model: String,
        val apiUrl: String,
        val apiFormat: String,
        val availableProviders: List<String>,
        val availableLocalProfiles: List<LlmLocalProfileResponse>,
        val selectedLocalProfileId: String?,
        val configured: Boolean,
        val ready: Boolean,
        val hint: String
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val runningInDocker = System.getenv("RUNNING_IN_DOCKER")?.equals("true", ignoreCase = true) == true ||
        File("/.dockerenv").exists() ||
        File("/proc/1/cgroup").takeIf { it.exists() }?.readText()?.contains("docker", ignoreCase = true) == true
    private val localProfiles = buildLocalProfiles()
    private val runtimeSettings = AtomicReference(
        loadPersistedSettings() ?: RuntimeSettings(
            provider = config.llm.provider,
            model = config.llm.model,
            apiUrl = normalizeApiUrl(config.llm.provider, config.llm.apiUrl),
            apiKey = config.llm.apiKey,
            apiFormat = config.llm.apiFormat
        )
    )
    private val safeFallbackReply =
        "I can still help. Please ask your question in one short line, like pricing, refund, or password reset."

    private fun buildLocalProfiles() = listOf(
        LlmLocalProfileResponse(
            id = "qwen25_15b_ollama",
            label = "Qwen 2.5 1.5B via Ollama",
            provider = "local",
            model = "qwen2.5:1.5b",
            llmApiUrl = localOllamaApiUrl(),
            llmApiFormat = "ollama",
            summary = "Best low-cost offline default for Hindi and English support on modest hardware.",
            hardwareTier = "4-8 GB RAM/VRAM",
            bestFor = "Fastest cheap calls"
        ),
        LlmLocalProfileResponse(
            id = "gemma2_2b_ollama",
            label = "Gemma 2B via Ollama",
            provider = "local",
            model = "gemma:2b",
            llmApiUrl = localOllamaApiUrl(),
            llmApiFormat = "ollama",
            summary = "Recommended low-cost default for local support calls when you want to avoid Gemini spend.",
            hardwareTier = "4-6 GB RAM/VRAM",
            bestFor = "Lowest-cost local default"
        ),
        LlmLocalProfileResponse(
            id = "llama32_3b_ollama",
            label = "Llama 3.2 3B via Ollama",
            provider = "local",
            model = "llama3.2:3b",
            llmApiUrl = localOllamaApiUrl(),
            llmApiFormat = "ollama",
            summary = "Higher quality offline preset for bilingual phone conversations and longer prompts.",
            hardwareTier = "6-10 GB RAM/VRAM",
            bestFor = "Best quality offline"
        ),
        LlmLocalProfileResponse(
            id = "qwen25_15b_openai",
            label = "Qwen 2.5 1.5B via local OpenAI-compatible server",
            provider = "local",
            model = "Qwen/Qwen2.5-1.5B-Instruct",
            llmApiUrl = localOpenAiCompatibleApiUrl(),
            llmApiFormat = "openai",
            summary = "Use this when serving the Hugging Face checkpoint yourself through vLLM, TGI, LM Studio, or llama.cpp server.",
            hardwareTier = "4-8 GB RAM/VRAM",
            bestFor = "Local HF checkpoint"
        ),
        LlmLocalProfileResponse(
            id = "llama32_3b_openai",
            label = "Llama 3.2 3B via local OpenAI-compatible server",
            provider = "local",
            model = "meta-llama/Llama-3.2-3B-Instruct",
            llmApiUrl = localOpenAiCompatibleApiUrl(),
            llmApiFormat = "openai",
            summary = "Use this when you want the Hugging Face Llama checkpoint behind a local OpenAI-compatible endpoint.",
            hardwareTier = "6-10 GB RAM/VRAM",
            bestFor = "Best local HF quality"
        )
    )

    suspend fun chat(
        systemPrompt: String,
        history: List<Message>,
        userMessage: String,
        maxTokens: Int = 300
    ): String {
        val settings = runtimeSettings.get()
        return when (settings.provider) {
            LlmProvider.ANTHROPIC ->
                if (settings.apiKey.isBlank()) mockResponse(systemPrompt, userMessage)
                else chatAnthropic(settings, systemPrompt, history, userMessage, maxTokens)

            LlmProvider.OPENAI ->
                if (settings.apiKey.isBlank()) mockResponse(systemPrompt, userMessage)
                else chatOpenAiCompatible(settings, systemPrompt, history, userMessage, maxTokens)

            LlmProvider.OPEN_SOURCE ->
                chatOpenSource(settings, systemPrompt, history, userMessage, maxTokens)

            else ->
                if (settings.apiKey.isBlank()) mockResponse(systemPrompt, userMessage)
                else chatOpenAiCompatible(settings, systemPrompt, history, userMessage, maxTokens)
        }
    }

    suspend fun complete(prompt: String, maxTokens: Int = 500): String {
        return chat(
            systemPrompt = "You are a helpful assistant. Return only what is asked.",
            history = emptyList(),
            userMessage = prompt,
            maxTokens = maxTokens
        )
    }

    fun availableProviders(): List<String> = listOf("local", "openai", "anthropic", "google")

    fun availableLocalProfiles(): List<LlmLocalProfileResponse> = localProfiles

    fun describe(): RuntimeStatus {
        val settings = runtimeSettings.get()
        return describe(settings)
    }

    fun updateSettings(
        profileIdRaw: String? = null,
        providerRaw: String = "",
        modelRaw: String = "",
        apiUrlRaw: String = "",
        apiFormatRaw: String = "auto",
        apiKeyRaw: String? = null
    ): RuntimeStatus {
        val current = runtimeSettings.get()
        val selectedProfile = resolveLocalProfile(profileIdRaw)
        val provider = selectedProfile?.let { parseProvider(it.provider) } ?: parseProvider(providerRaw)
        val model = selectedProfile?.model ?: modelRaw.trim().ifBlank {
            throw IllegalArgumentException("LLM model is required.")
        }
        val apiFormat = selectedProfile?.llmApiFormat ?: apiFormatRaw.trim().ifBlank {
            defaultApiFormat(provider, current.apiFormat)
        }
        val apiUrl = selectedProfile?.llmApiUrl ?: apiUrlRaw.trim().ifBlank {
            if (current.provider == provider && current.apiUrl.isNotBlank()) {
                current.apiUrl
            } else {
                defaultApiUrl(provider)
            }
        }
        val apiKey = when {
            !apiKeyRaw.isNullOrBlank() -> apiKeyRaw.trim()
            selectedProfile != null && provider == LlmProvider.OPEN_SOURCE -> ""
            current.provider == provider -> current.apiKey
            else -> ""
        }

        val updated = RuntimeSettings(
            provider = provider,
            model = model,
            apiUrl = normalizeApiUrl(provider, apiUrl),
            apiKey = apiKey,
            apiFormat = apiFormat
        )
        runtimeSettings.set(updated)
        persistSettings(updated)
        log.info { "LLM runtime settings updated: provider=${wireValue(provider)} model=$model apiUrl=$apiUrl format=$apiFormat" }
        return describe(updated)
    }

    private fun mockResponse(systemPrompt: String, userMessage: String): String {
        val lower = userMessage.lowercase()
        val englishRequested = lower.contains("english")
        val romanHindiMarkers = listOf("mujhe", "aap", "hai", "karna", "chahiye", "kitna", "kaise", "madad", "bilkul", "wapas")
        val looksLikeRomanHindi = romanHindiMarkers.any { lower.contains(it) }
        val englishContext = systemPrompt.contains("CURRENT LANGUAGE: English", ignoreCase = true)
        val useEnglish = englishRequested || (englishContext && !looksLikeRomanHindi)
        return when {
            lower.contains("password") || lower.contains("reset") ->
                if (useEnglish) {
                    "Sure. To reset your password, go to the login page, click 'Forgot Password', and follow the link sent to your email."
                } else {
                    "Bilkul! Password reset karne ke liye, aap login page par 'Forgot Password' option use karein. Aapko email par link milega."
                }
            lower.contains("price") || lower.contains("plan") || lower.contains("kitna") ->
                if (useEnglish) {
                    "Our Basic Plan is ₹999 per month. The Pro Plan is ₹2,999 per month and includes unlimited features. Which plan would you like to know more about?"
                } else {
                    "Hamara Basic Plan ₹999/month hai. Pro Plan ₹2,999/month mein unlimited features ke saath aata hai. Kaunsa plan aapke liye suitable hoga?"
                }
            lower.contains("refund") || lower.contains("wapas") ->
                if (useEnglish) {
                    "Yes, we offer a full refund within 30 days, with no questions asked."
                } else {
                    "Ji bilkul, hum 30 din ke andar full refund dete hain, bina kisi sawaal ke."
                }
            lower.contains("hello") || lower.contains("hi") || lower.contains("namaste") ->
                if (useEnglish) {
                    "Hello, this is VoiceNova. How can I help you today?"
                } else {
                    "Namaste! Main VoiceNova se bol rahi hoon. Aapki kaise madad kar sakti hoon?"
                }
            lower.contains("english") ->
                "Of course. You're speaking with VoiceNova. How can I help you today?"
            else ->
                if (useEnglish) {
                    "I understand. Could you share a little more detail so I can help you properly?"
                } else {
                    "Aapki baat samajh aa gayi. Kya aap thoda aur detail mein bata sakte hain? Main aapki poori madad karna chahta hoon."
                }
        }
    }

    private suspend fun chatAnthropic(
        settings: RuntimeSettings,
        systemPrompt: String,
        history: List<Message>,
        userMessage: String,
        maxTokens: Int
    ): String {
        val messages = buildList {
            history.forEach { msg -> add(mapOf("role" to msg.role, "content" to msg.content)) }
            add(mapOf("role" to "user", "content" to userMessage))
        }
        val body = buildJsonObject {
            put("model", settings.model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("messages", Json.encodeToJsonElement(messages))
        }
        return try {
            val response = httpClient.post(settings.apiUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", settings.apiKey)
                header("anthropic-version", "2023-06-01")
                setBody(body.toString())
            }
            val responseBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                log.error { "Anthropic API error ${response.status.value}: ${responseBody.take(800)}" }
                return "Maafi kijiye, abhi response generate nahi ho pa raha."
            }

            val responseJson = Json.parseToJsonElement(responseBody)
            responseJson.jsonObject["content"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: run {
                    log.warn { "Anthropic response did not contain text content: ${responseBody.take(800)}" }
                    "Kripya thoda intezaar karein."
                }
        } catch (e: Exception) {
            log.error { "LLM error: ${e.message}" }
            "Maafi kijiye, abhi ek problem aa gayi."
        }
    }

    private suspend fun chatOpenSource(
        settings: RuntimeSettings,
        systemPrompt: String,
        history: List<Message>,
        userMessage: String,
        maxTokens: Int
    ): String {
        val format = settings.apiFormat.lowercase()
        return if (format == "ollama" || (format == "auto" && looksLikeOllamaEndpoint(settings.apiUrl))) {
            chatOllama(settings, systemPrompt, history, userMessage, maxTokens)
        } else {
            chatOpenAiCompatible(settings, systemPrompt, history, userMessage, maxTokens)
        }
    }

    private suspend fun chatOpenAiCompatible(
        settings: RuntimeSettings,
        systemPrompt: String,
        history: List<Message>,
        userMessage: String,
        maxTokens: Int
    ): String {
        val messages = buildList {
            add(mapOf("role" to "system", "content" to systemPrompt))
            history.forEach { msg -> add(mapOf("role" to msg.role, "content" to msg.content)) }
            add(mapOf("role" to "user", "content" to userMessage))
        }
        val body = buildJsonObject {
            put("model", settings.model)
            put("max_tokens", maxTokens)
            put("messages", Json.encodeToJsonElement(messages))
            put("temperature", config.llm.temperature)
        }
        return try {
            val response = httpClient.post(settings.apiUrl) {
                contentType(ContentType.Application.Json)
                if (settings.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${settings.apiKey}")
                }
                setBody(body.toString())
            }
            val responseBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                log.error { "OpenAI-compatible API error ${response.status.value}: ${responseBody.take(800)}" }
                if (response.status.value == 429 && responseBody.contains("RESOURCE_EXHAUSTED", ignoreCase = true)) {
                    return "Gemini quota is exhausted right now. Please try again later or switch to a local model."
                }
                return safeFallbackReply
            }
            extractAssistantText(responseBody)
                ?: run {
                    log.warn { "OpenAI-compatible response missing assistant text: ${responseBody.take(800)}" }
                    safeFallbackReply
                }
        } catch (e: Exception) {
            log.error { "OpenAI-compatible LLM error: ${e.message}" }
            safeFallbackReply
        }
    }

    private suspend fun chatOllama(
        settings: RuntimeSettings,
        systemPrompt: String,
        history: List<Message>,
        userMessage: String,
        maxTokens: Int
    ): String {
        val messages = buildList {
            add(mapOf("role" to "system", "content" to systemPrompt))
            history.forEach { msg -> add(mapOf("role" to msg.role, "content" to msg.content)) }
            add(mapOf("role" to "user", "content" to userMessage))
        }
        val body = buildJsonObject {
            put("model", settings.model)
            put("stream", false)
            put("messages", Json.encodeToJsonElement(messages))
            put("options", buildJsonObject {
                put("temperature", config.llm.temperature)
                put("num_predict", maxTokens)
            })
            if (systemPrompt.isNotBlank()) {
                put("system", systemPrompt)
            }
        }
        return try {
            val response = httpClient.post(settings.apiUrl) {
                contentType(ContentType.Application.Json)
                if (settings.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${settings.apiKey}")
                }
                setBody(body.toString())
            }
            val responseBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                log.error { "Local/Ollama API error ${response.status.value}: ${responseBody.take(800)}" }
                return safeFallbackReply
            }
            extractAssistantText(responseBody)
                ?: run {
                    log.warn { "Local/Ollama response missing assistant text: ${responseBody.take(800)}" }
                    safeFallbackReply
                }
        } catch (e: Exception) {
            log.error { "Local/Ollama LLM error: ${e.message}" }
            safeFallbackReply
        }
    }

    private fun looksLikeOllamaEndpoint(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("/api/chat") || normalized.contains(":11434")
    }

    private fun describe(settings: RuntimeSettings): RuntimeStatus {
        val selectedProfileId = matchProfileId(settings)
        val configured = when (settings.provider) {
            LlmProvider.OPEN_SOURCE -> settings.apiUrl.isNotBlank()
            LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.GOOGLE ->
                settings.apiUrl.isNotBlank() && settings.apiKey.isNotBlank()
        }
        val hint = when (settings.provider) {
            LlmProvider.OPEN_SOURCE -> selectedProfileId?.let { profileId ->
                val profile = localProfiles.firstOrNull { it.id == profileId }
                "${profile?.label ?: "Local model"} is active. Keep the local model server running for call responses."
            } ?: "Local/Open-source mode is active. Recommended offline presets: Gemma 2B for the cheapest default, Qwen 2.5 1.5B for multilingual balance, or Llama 3.2 3B for better quality."
            LlmProvider.OPENAI -> if (configured) {
                "OpenAI-compatible mode is ready. This is a good choice for low-latency calls."
            } else {
                "Add an API key to use OpenAI-compatible models such as gpt-4o-mini."
            }
            LlmProvider.ANTHROPIC -> if (configured) {
                "Anthropic mode is ready."
            } else {
                "Add an API key to use Anthropic models."
            }
            LlmProvider.GOOGLE -> if (configured) {
                "Google-compatible mode is configured."
            } else {
                "Add an API key to use Google-compatible mode."
            }
        }
        return RuntimeStatus(
            provider = wireValue(settings.provider),
            model = settings.model,
            apiUrl = settings.apiUrl,
            apiFormat = settings.apiFormat,
            availableProviders = availableProviders(),
            availableLocalProfiles = availableLocalProfiles(),
            selectedLocalProfileId = selectedProfileId,
            configured = configured,
            ready = configured,
            hint = hint
        )
    }

    private fun resolveLocalProfile(profileIdRaw: String?): LlmLocalProfileResponse? {
        val profileId = profileIdRaw?.trim().orEmpty()
        if (profileId.isBlank()) return null
        return localProfiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("Unknown LLM profile: $profileId")
    }

    private fun matchProfileId(settings: RuntimeSettings): String? =
        localProfiles.firstOrNull { profile ->
            profile.provider == wireValue(settings.provider) &&
                profile.model == settings.model &&
                profile.llmApiUrl == settings.apiUrl &&
                profile.llmApiFormat == settings.apiFormat
        }?.id

    private fun parseProvider(rawProvider: String?): LlmProvider {
        return when (rawProvider?.trim()?.lowercase()) {
            "openai" -> LlmProvider.OPENAI
            "anthropic" -> LlmProvider.ANTHROPIC
            "google" -> LlmProvider.GOOGLE
            "open_source", "open-source", "local", "ollama", "vllm", "lmstudio" -> LlmProvider.OPEN_SOURCE
            else -> LlmProvider.OPEN_SOURCE
        }
    }

    private fun wireValue(provider: LlmProvider): String =
        when (provider) {
            LlmProvider.OPEN_SOURCE -> "local"
            LlmProvider.OPENAI -> "openai"
            LlmProvider.ANTHROPIC -> "anthropic"
            LlmProvider.GOOGLE -> "google"
        }

    private fun defaultApiUrl(provider: LlmProvider): String =
        when (provider) {
            LlmProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
            LlmProvider.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
            LlmProvider.OPEN_SOURCE -> localOllamaApiUrl()
            LlmProvider.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        }

    private fun defaultApiFormat(provider: LlmProvider, currentFormat: String): String =
        when (provider) {
            LlmProvider.OPEN_SOURCE -> if (currentFormat == "openai") "openai" else "ollama"
            else -> "openai"
        }

    private fun loadPersistedSettings(): RuntimeSettings? {
        return runCatching {
            if (!runtimeSettingsFile.exists()) return null
            val root = json.parseToJsonElement(runtimeSettingsFile.readText()).jsonObject
            RuntimeSettings(
                provider = parseProvider(root["provider"]?.jsonPrimitive?.contentOrNull),
                model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { config.llm.model },
                apiUrl = normalizeApiUrl(
                    parseProvider(root["provider"]?.jsonPrimitive?.contentOrNull),
                    root["apiUrl"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { config.llm.apiUrl }
                ),
                apiKey = root["apiKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                apiFormat = root["apiFormat"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { config.llm.apiFormat }
            )
        }.onFailure { cause ->
            log.warn(cause) { "Could not load persisted LLM runtime settings. Falling back to startup config." }
        }.getOrNull()
    }

    private fun persistSettings(settings: RuntimeSettings) {
        runCatching {
            runtimeSettingsFile.parentFile?.mkdirs()
            val payload = buildJsonObject {
                put("provider", wireValue(settings.provider))
                put("model", settings.model)
                put("apiUrl", normalizeApiUrl(settings.provider, settings.apiUrl))
                put("apiKey", settings.apiKey)
                put("apiFormat", settings.apiFormat)
            }
            runtimeSettingsFile.writeText(json.encodeToString(JsonObject.serializer(), payload))
        }.onFailure { cause ->
            log.warn(cause) { "Could not persist LLM runtime settings." }
        }
    }

    private fun localOllamaApiUrl(): String {
        val configured = config.llm.apiUrl.trim()
        val candidate = if (
            config.llm.provider == LlmProvider.OPEN_SOURCE &&
            looksLikeOllamaEndpoint(configured)
        ) configured else "http://127.0.0.1:11434/api/chat"
        return normalizeApiUrl(LlmProvider.OPEN_SOURCE, candidate)
    }

    private fun localOpenAiCompatibleApiUrl(): String {
        val configured = config.llm.apiUrl.trim()
        val candidate = if (
            config.llm.provider == LlmProvider.OPEN_SOURCE &&
            configured.isNotBlank() &&
            !looksLikeOllamaEndpoint(configured)
        ) configured else "http://127.0.0.1:8000/v1/chat/completions"
        return normalizeApiUrl(LlmProvider.OPEN_SOURCE, candidate)
    }

    private fun normalizeApiUrl(provider: LlmProvider, rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return trimmed
        if (provider != LlmProvider.OPEN_SOURCE || !runningInDocker) return trimmed
        return runCatching {
            val uri = URI(trimmed)
            val host = uri.host ?: return trimmed
            if (host !in setOf("127.0.0.1", "localhost", "0.0.0.0")) return trimmed
            URI(
                uri.scheme,
                uri.userInfo,
                "host.docker.internal",
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toString()
        }.getOrElse {
            trimmed
                .replace("://127.0.0.1", "://host.docker.internal")
                .replace("://localhost", "://host.docker.internal")
                .replace("://0.0.0.0", "://host.docker.internal")
        }
    }

    private fun extractAssistantText(responseBody: String): String? {
        return try {
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            extractTextContent(responseJson["message"]?.jsonObject?.get("content"))
                ?: extractTextContent(
                    responseJson["choices"]?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("message")
                        ?.jsonObject
                        ?.get("content")
                )
                ?: responseJson["choices"]?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                ?: responseJson["response"]?.jsonPrimitive?.contentOrNull
                ?: extractTextContent(responseJson["generated_text"])
        } catch (e: Exception) {
            log.warn { "Could not parse LLM response body: ${e.message}" }
            null
        }
    }

    private fun extractTextContent(element: JsonElement?): String? {
        element ?: return null
        return when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.joinToString("\n") { part ->
                when (part) {
                    is JsonPrimitive -> part.contentOrNull.orEmpty()
                    else -> part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }
            }.trim().ifBlank { null }
            else -> element.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }
    }
}
