package com.voicenova.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Language Support ──────────────────────────────────────────────────────────

enum class Language(
    val displayName: String,
    val bcp47Code: String,
    val unicodeRange: IntRange?   // for script-based auto-detection
) {
    // Indian languages
    HINDI("हिंदी", "hi-IN", 0x0900..0x097F),
    BENGALI("বাংলা", "bn-IN", 0x0980..0x09FF),
    TELUGU("తెలుగు", "te-IN", 0x0C00..0x0C7F),
    MARATHI("मराठी", "mr-IN", 0x0900..0x097F),   // shares Devanagari, resolved by keywords
    TAMIL("தமிழ்", "ta-IN", 0x0B80..0x0BFF),
    GUJARATI("ગુજરાતી", "gu-IN", 0x0A80..0x0AFF),
    KANNADA("ಕನ್ನಡ", "kn-IN", 0x0C80..0x0CFF),
    MALAYALAM("മലയാളം", "ml-IN", 0x0D00..0x0D7F),
    PUNJABI("ਪੰਜਾਬੀ", "pa-IN", 0x0A00..0x0A7F),
    ODIA("ଓଡ଼ିଆ", "or-IN", 0x0B00..0x0B7F),
    ASSAMESE("অসমীয়া", "as-IN", 0x0980..0x09FF),
    URDU("اردو", "ur-IN", 0x0600..0x06FF),
    KASHMIRI("कश्मीरी", "ks-IN", null),
    SINDHI("सिन्धी", "sd-IN", null),
    KONKANI("कोंकणी", "kok-IN", null),
    NEPALI("नेपाली", "ne-IN", 0x0900..0x097F),
    MAITHILI("मैथिली", "mai-IN", null),
    DOGRI("डोगरी", "doi-IN", null),
    BODO("Bodo", "brx-IN", null),
    MANIPURI("Manipuri", "mni-IN", null),
    SANTALI("Santali", "sat-IN", null),

    // International
    ENGLISH("English", "en-IN", null),
    ARABIC("العربية", "ar-SA", 0x0600..0x06FF),
    SPANISH("Español", "es-ES", null),
    FRENCH("Français", "fr-FR", null),
    GERMAN("Deutsch", "de-DE", null),
    CHINESE("中文", "zh-CN", 0x4E00..0x9FFF),
    JAPANESE("日本語", "ja-JP", 0x3040..0x309F),
    KOREAN("한국어", "ko-KR", 0xAC00..0xD7AF),
    PORTUGUESE("Português", "pt-BR", null),
    RUSSIAN("Русский", "ru-RU", 0x0400..0x04FF),
    ITALIAN("Italiano", "it-IT", null),
    DUTCH("Nederlands", "nl-NL", null),
    TURKISH("Türkçe", "tr-TR", null),
    VIETNAMESE("Tiếng Việt", "vi-VN", null),
    THAI("ภาษาไทย", "th-TH", 0x0E00..0x0E7F),
    INDONESIAN("Bahasa Indonesia", "id-ID", null),
    POLISH("Polski", "pl-PL", null),
    SWEDISH("Svenska", "sv-SE", null);

    companion object {
        fun fromBcp47(code: String): Language? =
            values().find { it.bcp47Code.equals(code, ignoreCase = true) }
    }
}

// ── Call Session ──────────────────────────────────────────────────────────────

@Serializable
data class CallSession(
    val id: String,                          // Twilio CallSid or test ID
    val from: String,
    val to: String,
    val direction: CallDirection,
    var detectedLanguage: Language = Language.HINDI,
    val history: MutableList<Message> = mutableListOf(),
    val callerData: CallerData? = null,
    val agentConfig: AgentConfig,
    var status: CallStatus = CallStatus.ACTIVE,
    val startedAt: Long = System.currentTimeMillis(),
    var endedAt: Long? = null,
    var intentHistory: MutableList<String> = mutableListOf(),
    var qualityScore: Float? = null,
    val debugEvents: MutableList<CallDebugEvent> = mutableListOf(),
    var cachedPromptBase: String? = null,
    var cachedContextDigest: String? = null,
    var hrCallType: HrCallType? = null,
    var hrInterviewMode: Boolean = true,
    var contactProfile: ContactProfile? = null,
    var hrScenario: HrCallScenario? = null,
    var hrStep: HrInterviewStep = HrInterviewStep.INTRO_OFFER,
    var hrCandidateDraft: HrCandidateProfile? = null,
    var hrIntroAttempts: Int = 0
)

@Serializable
data class Message(
    val role: String,           // "user" or "assistant"
    val content: String,
    val language: Language,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float = 1.0f
)

@Serializable
data class AgentResponse(
    val text: String,
    val language: Language,
    val action: AgentAction = AgentAction.RESPOND,
    val twiml: String? = null   // pre-built TwiML if needed
)

// ── Agent Configuration ───────────────────────────────────────────────────────

@Serializable
data class AgentConfig(
    val companyName: String = "Chain tech network ",
    val agentName: String = "Minsha",
    val agentGender: String = "female",
    val systemInstructions: String = "",
    val personality: List<String> = listOf("professional", "warm", "helpful"),
    val callRules: List<String> = emptyList(),
    val greetings: Map<String, String> = emptyMap(),  // lang -> greeting text
    val primaryLanguage: Language = Language.HINDI,
    val supportedLanguages: List<Language> = listOf(Language.HINDI, Language.ENGLISH),
    val escalationKeywords: List<String> = listOf("manager", "complaint", "consumer court"),
    val fallbackResponse: String = "Ji, main aapki jarur madad kar karunga."
) {
    companion object {
        val DEFAULT = AgentConfig(
            companyName = "VoiceNova",
            agentName = "Minsha",
            agentGender = "female",
            systemInstructions = "Be helpful, concise, and professional. Never make up information.",
            greetings = mapOf(
                "hi-IN" to "नमस्ते! मैं Minsha हूं. मैं आपकी कैसे सहायता कर सकती हूं?",
                "en-IN" to "Hello! I'm Minsha. How can I help you today?",
                "ta-IN" to "வணக்கம்! நான் Minsha. உங்களுக்கு எப்படி உதவலாம்?",
                "te-IN" to "నమస్కారం! నేను Minsha. మీకు ఎలా సహాయం చేయగలను?",
                "gu-IN" to "નમસ્તે! હું Minsha છું. હું તમારી કેવી રીતે મદદ કરી શકું?"
            )
        )
    }
}

// ── Caller Data ───────────────────────────────────────────────────────────────

@Serializable
data class CallerData(
    val name: String? = null,
    val phone: String,
    val email: String? = null,
    val plan: String? = null,
    val accountAge: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList()
)

@Serializable
data class ContactProfile(
    @SerialName("phone_number")
    val phoneNumber: String,
    val name: String? = null,
    val email: String? = null,
    val department: String? = null,
    val designation: String? = null,
    @SerialName("preferred_language")
    val preferredLanguage: String? = null,
    val company: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("last_call_summary")
    val lastCallSummary: String? = null,
    @SerialName("last_attendance_status")
    val lastAttendanceStatus: String? = null,
    @SerialName("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @SerialName("last_updated_at")
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class HrFaqItem(
    val question: String,
    val answer: String
)

@Serializable
data class HrCallScenario(
    @SerialName("call_type")
    val callType: HrCallType = HrCallType.INTERVIEW,
    @SerialName("interview_round")
    val interviewRound: String? = null,
    @SerialName("interview_details")
    val interviewDetails: String? = null,
    @SerialName("salary_credited")
    val salaryCredited: Boolean? = null,
    @SerialName("salary_amount")
    val salaryAmount: String? = null,
    @SerialName("attendance_date")
    val attendanceDate: String? = null,
    @SerialName("privacy_change_summary")
    val privacyChangeSummary: String? = null,
    @SerialName("custom_faqs")
    val customFaqs: List<HrFaqItem> = emptyList(),
    val notes: String? = null
)

@Serializable
enum class HrInterviewStep {
    INTRO_OFFER,
    ASK_NAME_CONFIRM,
    ASK_ROLE,
    ASK_SELF_INTRO,
    ASK_NAME,
    ASK_PREVIOUS_COMPANY,
    ASK_PROJECTS,
    ASK_BASIC_Q1,
    ASK_BASIC_Q2,
    ASK_EMAIL,
    ASK_CONSENT,
    COMPLETE,
    DECLINED
}

@Serializable
data class HrCandidateProfile(
    @SerialName("phone_number")
    val phoneNumber: String,
    val name: String? = null,
    @SerialName("previous_company")
    val previousCompany: String? = null,
    @SerialName("desired_role")
    val desiredRole: String? = null,
    @SerialName("self_introduction")
    val selfIntroduction: String? = null,
    @SerialName("basic_answer_1")
    val basicAnswer1: String? = null,
    @SerialName("basic_answer_2")
    val basicAnswer2: String? = null,
    val projects: String? = null,
    val email: String? = null,
    @SerialName("consent_to_store")
    val consentToStore: Boolean = false,
    @SerialName("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @SerialName("last_updated_at")
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class HrCandidateAdminResponse(
    @SerialName("phone_number")
    val phoneNumber: String,
    val name: String? = null,
    @SerialName("previous_company")
    val previousCompany: String? = null,
    @SerialName("desired_role")
    val desiredRole: String? = null,
    @SerialName("self_introduction")
    val selfIntroduction: String? = null,
    @SerialName("basic_answer_1")
    val basicAnswer1: String? = null,
    @SerialName("basic_answer_2")
    val basicAnswer2: String? = null,
    val projects: String? = null,
    val email: String? = null,
    @SerialName("consent_to_store")
    val consentToStore: Boolean = false,
    @SerialName("created_at")
    val createdAt: Long,
    @SerialName("last_updated_at")
    val lastUpdatedAt: Long
)

// ── Knowledge Base ────────────────────────────────────────────────────────────

@Serializable
data class KnowledgeChunk(
    val id: String,
    val source: String,         // filename
    val category: String,       // company/products/faq/scripts
    val content: String,
    val embedding: List<Float>? = null,
    val languages: List<String> = listOf("all"),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class LearnedFact(
    val id: String,
    val question: String,
    val answer: String,
    val language: Language,
    val callId: String,
    val approved: Boolean = false,
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class CallDirection { INBOUND, OUTBOUND }
enum class CallStatus { ACTIVE, COMPLETED, FAILED, BUSY, NO_ANSWER, TRANSFERRED }
enum class AgentAction { RESPOND, TRANSFER_TO_HUMAN, END_CALL, HOLD, CALLBACK }
enum class VoiceBackendMode { TWILIO_NATIVE, GROK_VOICE_AGENT, PLIVO }
enum class HrCallType { INTERVIEW, SALARY, ATTENDANCE, PRIVACY_POLICY_CHANGED, CUSTOMER_SUPPORT, OTHER }
enum class Intent {
    GENERAL_QUERY, PRODUCT_INQUIRY, BILLING_QUERY, TECHNICAL_SUPPORT,
    COMPLAINT, PURCHASE_INTENT, CANCELLATION, REFUND_REQUEST,
    TRANSFER_TO_HUMAN, END_CALL, APPOINTMENT, OTHER
}
enum class TtsProvider { ELEVENLABS, GOOGLE, AZURE, TWILIO_BASIC, PIPER, MACOS_SAY }
enum class SttProvider { GOOGLE, OPENAI_WHISPER, AZURE, VOSK }
enum class LlmProvider { ANTHROPIC, OPENAI, GOOGLE, OPEN_SOURCE }

// ── API Request/Response DTOs ─────────────────────────────────────────────────

@Serializable
data class StartConversationRequest(
    val language: String = "hi-IN",
    @SerialName("caller_number")
    val callerNumber: String = "+919999999999",
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
data class DetectLanguageResponse(
    val language: String,
    val name: String,
    val confidence: Double
)

@Serializable
data class ReloadKnowledgeResponse(
    val status: String,
    @SerialName("chunks_loaded")
    val chunksLoaded: Int,
    val documents: Int
)

@Serializable
data class OutboundCallRequest(
    val to: String,
    val language: String = "hi-IN",
    @SerialName("hr_call_type")
    val hrCallType: String? = null,
    @SerialName("hr_notes")
    val hrNotes: String? = null,
    @SerialName("contact_name")
    val contactName: String? = null,
    @SerialName("contact_email")
    val contactEmail: String? = null,
    @SerialName("contact_department")
    val contactDepartment: String? = null,
    @SerialName("contact_designation")
    val contactDesignation: String? = null,
    @SerialName("contact_notes")
    val contactNotes: String? = null,
    @SerialName("contact_tags")
    val contactTags: List<String> = emptyList(),
    @SerialName("scenario")
    val scenario: HrCallScenario? = null
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
data class HrBatchCallRequest(
    @SerialName("phone_numbers")
    val phoneNumbers: List<String>,
    @SerialName("call_type")
    val callType: String,
    val language: String = "en-IN",
    val notes: String? = null,
    @SerialName("scenario")
    val scenario: HrCallScenario? = null
)

@Serializable
data class ContactUpsertRequest(
    @SerialName("phone_number")
    val phoneNumber: String,
    val name: String? = null,
    val email: String? = null,
    val department: String? = null,
    val designation: String? = null,
    @SerialName("preferred_language")
    val preferredLanguage: String? = null,
    val company: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("last_call_summary")
    val lastCallSummary: String? = null,
    @SerialName("last_attendance_status")
    val lastAttendanceStatus: String? = null
)

@Serializable
data class HrBatchCallItemResponse(
    @SerialName("phone_number")
    val phoneNumber: String,
    @SerialName("call_sid")
    val callSid: String,
    val status: String
)

@Serializable
data class HrBatchCallResponse(
    @SerialName("batch_id")
    val batchId: String,
    @SerialName("call_type")
    val callType: String,
    val total: Int,
    val created: Int,
    val failed: Int,
    val calls: List<HrBatchCallItemResponse>
)

@Serializable
data class HrCallHistoryResponse(
    @SerialName("call_sid")
    val callSid: String,
    @SerialName("phone_number")
    val phoneNumber: String,
    @SerialName("call_type")
    val callType: String,
    val status: String,
    val notes: String? = null,
    @SerialName("batch_id")
    val batchId: String? = null,
    @SerialName("created_at")
    val createdAt: Long,
    @SerialName("updated_at")
    val updatedAt: Long
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
    @SerialName("personal_number_requires_forwarding")
    val personalNumberRequiresForwarding: Boolean,
    @SerialName("inbound_setup_hint")
    val inboundSetupHint: String,
    @SerialName("ai_mode")
    val aiMode: String,
    @SerialName("llm_provider")
    val llmProvider: String,
    @SerialName("llm_model")
    val llmModel: String,
    @SerialName("llm_api_url")
    val llmApiUrl: String,
    @SerialName("llm_api_format")
    val llmApiFormat: String,
    @SerialName("available_llm_providers")
    val availableLlmProviders: List<String>,
    @SerialName("llm_configured")
    val llmConfigured: Boolean,
    @SerialName("llm_ready")
    val llmReady: Boolean,
    @SerialName("llm_hint")
    val llmHint: String,
    @SerialName("auto_answer_inbound")
    val autoAnswerInbound: Boolean,
    @SerialName("call_controls_mode")
    val callControlsMode: String,
    @SerialName("selected_voice_backend")
    val selectedVoiceBackend: String,
    @SerialName("available_voice_backends")
    val availableVoiceBackends: List<String>,
    @SerialName("voice_backend_configured")
    val voiceBackendConfigured: Boolean,
    @SerialName("voice_backend_ready")
    val voiceBackendReady: Boolean,
    @SerialName("voice_backend_hint")
    val voiceBackendHint: String,
    @SerialName("voice_backend_websocket_url")
    val voiceBackendWebsocketUrl: String? = null,
    @SerialName("selected_tts_provider")
    val selectedTtsProvider: String,
    @SerialName("available_tts_providers")
    val availableTtsProviders: List<String>,
    @SerialName("selected_piper_voice")
    val selectedPiperVoice: String? = null,
    @SerialName("available_piper_voices")
    val availablePiperVoices: List<PiperVoiceOptionResponse> = emptyList(),
    @SerialName("tts_configured")
    val ttsConfigured: Boolean,
    @SerialName("tts_ready")
    val ttsReady: Boolean,
    @SerialName("tts_hint")
    val ttsHint: String
)

@Serializable
data class UpdateVoiceBackendRequest(
    @SerialName("voice_backend")
    val voiceBackend: String
)

@Serializable
data class VoiceBackendResponse(
    @SerialName("voice_backend")
    val voiceBackend: String,
    @SerialName("configured")
    val configured: Boolean,
    @SerialName("ready")
    val ready: Boolean,
    @SerialName("hint")
    val hint: String,
    @SerialName("websocket_url")
    val websocketUrl: String? = null
)

@Serializable
data class UpdateLlmSettingsRequest(
    @SerialName("llm_profile_id")
    val llmProfileId: String? = null,
    @SerialName("llm_provider")
    val llmProvider: String = "",
    @SerialName("llm_model")
    val llmModel: String = "",
    @SerialName("llm_api_url")
    val llmApiUrl: String = "",
    @SerialName("llm_api_format")
    val llmApiFormat: String = "auto",
    @SerialName("llm_api_key")
    val llmApiKey: String? = null
)

@Serializable
data class LlmLocalProfileResponse(
    val id: String,
    val label: String,
    val provider: String,
    val model: String,
    @SerialName("llm_api_url")
    val llmApiUrl: String,
    @SerialName("llm_api_format")
    val llmApiFormat: String,
    val summary: String,
    @SerialName("hardware_tier")
    val hardwareTier: String,
    @SerialName("best_for")
    val bestFor: String
)

@Serializable
data class LlmSettingsResponse(
    @SerialName("llm_provider")
    val llmProvider: String,
    @SerialName("llm_model")
    val llmModel: String,
    @SerialName("llm_api_url")
    val llmApiUrl: String,
    @SerialName("llm_api_format")
    val llmApiFormat: String,
    @SerialName("available_llm_providers")
    val availableLlmProviders: List<String>,
    @SerialName("available_local_profiles")
    val availableLocalProfiles: List<LlmLocalProfileResponse> = emptyList(),
    @SerialName("selected_local_profile_id")
    val selectedLocalProfileId: String? = null,
    @SerialName("configured")
    val configured: Boolean,
    @SerialName("ready")
    val ready: Boolean,
    @SerialName("hint")
    val hint: String
)

@Serializable
data class LlmTestRequest(
    val prompt: String,
    @SerialName("max_tokens")
    val maxTokens: Int = 120
)

@Serializable
data class LlmTestResponse(
    val reply: String,
    @SerialName("llm_provider")
    val llmProvider: String,
    @SerialName("llm_model")
    val llmModel: String,
    @SerialName("llm_api_url")
    val llmApiUrl: String
)

@Serializable
data class UpdateTtsProviderRequest(
    @SerialName("tts_provider")
    val ttsProvider: String,
    @SerialName("piper_voice")
    val piperVoice: String? = null
)

@Serializable
data class PiperVoiceOptionResponse(
    val id: String,
    val label: String,
    val gender: String
)

@Serializable
data class TtsProviderResponse(
    @SerialName("tts_provider")
    val ttsProvider: String,
    @SerialName("selected_piper_voice")
    val selectedPiperVoice: String? = null,
    @SerialName("available_piper_voices")
    val availablePiperVoices: List<PiperVoiceOptionResponse> = emptyList(),
    @SerialName("configured")
    val configured: Boolean,
    @SerialName("ready")
    val ready: Boolean,
    @SerialName("hint")
    val hint: String
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
    @SerialName("direction")
    val direction: String,
    @SerialName("language")
    val language: String,
    @SerialName("duration_seconds")
    val durationSeconds: Int,
    @SerialName("started_at")
    val startedAt: Long,
    @SerialName("ended_at")
    val endedAt: Long? = null
)

@Serializable
data class CallDebugEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val stage: String,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

@Serializable
data class CallDebugResponse(
    @SerialName("call_sid")
    val callSid: String,
    val status: String,
    val language: String,
    @SerialName("recent_transcript")
    val recentTranscript: List<String>,
    val events: List<CallDebugEvent>
)

@Serializable
data class TrainingUploadRequest(
    val category: String,
    val title: String,
    val content: String,
    val language: String = "all"
)
