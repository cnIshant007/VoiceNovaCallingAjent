package com.voicenova.preview

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BackendApi(
    private val client: HttpClient
) {
    suspend fun fetchHealth(baseUrl: String): HealthResponse =
        client.get("${baseUrl.trimEnd('/')}/api/v1/health").body()

    suspend fun fetchContextSummary(baseUrl: String): ContextSummaryResponse =
        client.get("${baseUrl.trimEnd('/')}/api/v1/test/context-summary").body()

    suspend fun fetchBackendStatus(baseUrl: String): BackendStatusResponse =
        client.get("${baseUrl.trimEnd('/')}/api/v1/status").body()

    suspend fun fetchAnalytics(baseUrl: String): AnalyticsResponse =
        client.get("${baseUrl.trimEnd('/')}/api/v1/analytics").body()

    suspend fun fetchCallHistory(baseUrl: String, limit: Int = 20): List<CallHistoryItem> =
        client.get("${baseUrl.trimEnd('/')}/api/v1/calls/history") {
            parameter("limit", limit)
        }.body()

    suspend fun startConversation(baseUrl: String, language: String): StartConversationResponse =
        client.post("${baseUrl.trimEnd('/')}/api/v1/test/conversation") {
            contentType(ContentType.Application.Json)
            setBody(StartConversationRequest(language = language))
        }.body()

    suspend fun sendMessage(baseUrl: String, sessionId: String, message: String): ConversationResponse =
        client.post("${baseUrl.trimEnd('/')}/api/v1/test/message") {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(sessionId = sessionId, message = message))
        }.body()

    suspend fun startOutboundCall(baseUrl: String, phoneNumber: String): OutboundCallResponse =
        startOutboundCall(baseUrl, phoneNumber, "hi-IN")

    suspend fun startOutboundCall(baseUrl: String, phoneNumber: String, language: String): OutboundCallResponse =
        client.post("${baseUrl.trimEnd('/')}/api/v1/calls/outbound") {
            contentType(ContentType.Application.Json)
            setBody(OutboundCallRequest(to = phoneNumber, language = language))
        }.body()

    suspend fun endOutboundCall(baseUrl: String, callSid: String): EndCallResponse =
        client.post("${baseUrl.trimEnd('/')}/api/v1/calls/end") {
            contentType(ContentType.Application.Json)
            setBody(EndCallRequest(callSid = callSid))
        }.body()

    suspend fun fetchCallStatus(baseUrl: String, callSid: String): CallStatusResponse =
        client.get("${baseUrl.trimEnd('/')}/api/v1/calls/$callSid/status").body()

    suspend fun connectExpert(baseUrl: String, callSid: String, phoneNumber: String): AssistCallResponse =
        client.post("${baseUrl.trimEnd('/')}/api/v1/calls/expert-connect") {
            contentType(ContentType.Application.Json)
            setBody(AssistCallRequest(callSid = callSid, phoneNumber = phoneNumber))
        }.body()

    suspend fun connectMonitor(baseUrl: String, callSid: String, phoneNumber: String): AssistCallResponse =
        client.post("${baseUrl.trimEnd('/')}/api/v1/calls/monitor-connect") {
            contentType(ContentType.Application.Json)
            setBody(AssistCallRequest(callSid = callSid, phoneNumber = phoneNumber))
        }.body()

    companion object {
        fun create(client: HttpClient) = BackendApi(client)
    }
}

expect fun createPreviewHttpClient(): HttpClient

fun defaultJson() = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun configurePreviewClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClientConfig<*>.() -> Unit = {
    defaultRequest {
        header("ngrok-skip-browser-warning", "true")
    }
    install(ContentNegotiation) {
        json(defaultJson())
    }
    block()
}

@Serializable
data class HealthResponse(
    val status: String,
    val version: String? = null
)

@Serializable
data class ContextSummaryResponse(
    val agentName: String,
    val company: String,
    val documentsLoaded: Int,
    val totalChunks: Int,
    val topicsCovered: List<String>,
    val languagesConfigured: List<String>
)

@Serializable
data class BackendStatusResponse(
    @SerialName("twilio_mock_mode")
    val twilioMockMode: Boolean,
    @SerialName("webhook_base_url")
    val webhookBaseUrl: String,
    @SerialName("twilio_inbound_webhook")
    val twilioInboundWebhook: String,
    @SerialName("ai_mode")
    val aiMode: String,
    @SerialName("llm_provider")
    val llmProvider: String,
    @SerialName("llm_model")
    val llmModel: String,
    @SerialName("auto_answer_inbound")
    val autoAnswerInbound: Boolean,
    @SerialName("call_controls_mode")
    val callControlsMode: String,
    @SerialName("selected_voice_backend")
    val selectedVoiceBackend: String = "twilio_native"
)

@Serializable
data class AnalyticsResponse(
    @SerialName("active_calls")
    val activeCalls: Int,
    @SerialName("completed_calls")
    val completedCalls: Int,
    @SerialName("messages_processed")
    val messagesProcessed: Int,
    @SerialName("credits_used")
    val creditsUsed: Int,
    @SerialName("credits_remaining")
    val creditsRemaining: Int,
    @SerialName("credits_limit")
    val creditsLimit: Int,
    @SerialName("success_rate")
    val successRate: Int,
    @SerialName("avg_call_seconds")
    val avgCallSeconds: Int,
    @SerialName("top_intents")
    val topIntents: List<String>,
    @SerialName("language_mix")
    val languageMix: List<LanguageMetric>
)

@Serializable
data class LanguageMetric(
    val label: String,
    val value: Int
)

@Serializable
data class StartConversationRequest(
    val language: String = "hi-IN",
    @SerialName("caller_number")
    val callerNumber: String = "+919876543210",
    val purpose: String = "support"
)

@Serializable
data class StartConversationResponse(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("agent_greeting")
    val agentGreeting: String,
    @SerialName("detected_language")
    val detectedLanguage: String,
    @SerialName("agent_name")
    val agentName: String
)

@Serializable
data class SendMessageRequest(
    @SerialName("session_id")
    val sessionId: String,
    val message: String
)

@Serializable
data class ConversationResponse(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("agent_reply")
    val agentReply: String,
    @SerialName("detected_language")
    val detectedLanguage: String,
    val intent: String,
    val confidence: Float
)

@Serializable
data class OutboundCallRequest(
    val to: String,
    val language: String = "hi-IN"
)

@Serializable
data class OutboundCallResponse(
    @SerialName("call_sid")
    val callSid: String,
    val status: String,
    val to: String,
    val from: String,
    val provider: String = "twilio"
)

@Serializable
data class EndCallRequest(
    @SerialName("call_sid")
    val callSid: String
)

@Serializable
data class EndCallResponse(
    @SerialName("call_sid")
    val callSid: String,
    val ended: Boolean,
    val status: String
)

@Serializable
data class AssistCallRequest(
    @SerialName("call_sid")
    val callSid: String,
    @SerialName("phone_number")
    val phoneNumber: String
)

@Serializable
data class AssistCallResponse(
    @SerialName("call_sid")
    val callSid: String,
    @SerialName("conference_name")
    val conferenceName: String,
    @SerialName("participant_call_sid")
    val participantCallSid: String,
    @SerialName("participant_role")
    val participantRole: String,
    @SerialName("phone_number")
    val phoneNumber: String,
    val status: String
)

@Serializable
data class CallStatusResponse(
    @SerialName("call_sid")
    val callSid: String,
    val status: String,
    val active: Boolean
)

@Serializable
data class CallHistoryItem(
    @SerialName("call_sid")
    val callSid: String,
    val from: String,
    val to: String,
    val status: String,
    val direction: String,
    val language: String,
    @SerialName("duration_seconds")
    val durationSeconds: Int,
    @SerialName("started_at")
    val startedAt: Long,
    @SerialName("ended_at")
    val endedAt: Long? = null
)
