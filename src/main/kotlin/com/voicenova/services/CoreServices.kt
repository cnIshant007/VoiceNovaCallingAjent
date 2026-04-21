package com.voicenova.services

import com.voicenova.ai.LLMClient
import com.voicenova.ai.TTSClient
import com.voicenova.config.AppConfig
import com.voicenova.models.*
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

class CallService(
    private val llmClient: LLMClient,
    private val ttsClient: TTSClient,
    private val languageService: LanguageService,
    private val trainingService: TrainingService,
    private val config: AppConfig
) {
    private val telephonyTtsBudgetMs = 1_500L

    // In-memory active sessions (use Redis for multi-instance)
    private val sessions = ConcurrentHashMap<String, CallSession>()
    private val sessionStatuses = ConcurrentHashMap<String, CallStatus>()
    private val sessionAliases = ConcurrentHashMap<String, String>()
    private val conferenceNames = ConcurrentHashMap<String, String>()
    private val archivedDebug = ConcurrentHashMap<String, CallDebugResponse>()
    private val callHistory = mutableListOf<CallHistoryItem>()
    private var completedCallsCount: Int = 0
    private var messagesProcessedCount: Int = 0
    private var totalCallDurationSeconds: Int = 0

    fun createSession(
        callSid: String? = null,
        from: String,
        to: String,
        direction: CallDirection,
        agentConfig: AgentConfig? = null,
        initialLanguage: Language? = null,
        hrCallType: HrCallType? = null,
        contactProfile: ContactProfile? = null,
        hrScenario: HrCallScenario? = null
    ): CallSession {
        val id = callSid ?: "sess_${UUID.randomUUID().toString().take(8)}"
        val baseConfig = agentConfig ?: trainingService.getAgentConfig().copy()
        val isOwnerSupportCall = direction == CallDirection.OUTBOUND && (hrCallType == null || hrCallType == HrCallType.CUSTOMER_SUPPORT)
        val config = if (isOwnerSupportCall) {
            baseConfig.copy(
                agentName = "Rehan Tumbi",
                agentGender = "male",
                personality = listOf("confident", "warm", "clear", "owner-like"),
                systemInstructions = (baseConfig.systemInstructions + "\nYou are Rehan Tumbi, a male company owner or senior operator speaking directly to the customer. Sound natural, direct, and human.").trim()
            )
        } else {
            baseConfig
        }
        val existingCandidate = trainingService.getCandidateProfileByPhone(from)
        val existingContact = contactProfile
            ?: trainingService.getContactByPhone(from)
            ?: trainingService.getContactByPhone(to)
        val session = CallSession(
            id = id,
            from = from,
            to = to,
            direction = direction,
            detectedLanguage = initialLanguage ?: guessLanguageFromPhone(from),
            agentConfig = config,
            hrCallType = hrCallType,
            hrInterviewMode = hrCallType == HrCallType.INTERVIEW,
            contactProfile = existingContact,
            hrScenario = hrScenario,
            hrStep = HrInterviewStep.INTRO_OFFER,
            hrCandidateDraft = existingCandidate
        )
        sessions[id] = session
        sessionStatuses[id] = session.status
        addDebugEvent(
            id,
            stage = "session_created",
            message = "Call session created",
            details = mapOf(
                "direction" to direction.name.lowercase(),
                "from" to from,
                "to" to to,
                "language" to session.detectedLanguage.bcp47Code,
                "hr_recall" to if (existingCandidate != null) "existing_profile_found" else "new_profile",
                "contact_recall" to if (existingContact != null) "existing_contact_found" else "new_contact",
                "hr_call_type" to (hrCallType?.name ?: "")
            )
        )
        log.info { "Session created: $id (${direction.name} from $from)" }
        return session
    }

    fun getSession(id: String): CallSession? = sessions[resolveSessionId(id)]
    fun getSessionStatus(id: String): CallStatus? {
        val resolvedId = resolveSessionId(id)
        return sessions[resolvedId]?.status ?: sessionStatuses[resolvedId] ?: sessionStatuses[id]
    }
    fun getCallDebug(id: String): CallDebugResponse? {
        val resolvedId = resolveSessionId(id)
        val active = sessions[resolvedId]
        if (active != null) {
            return CallDebugResponse(
                callSid = active.id,
                status = active.status.name.lowercase(),
                language = active.detectedLanguage.bcp47Code,
                recentTranscript = active.history.takeLast(12).map { "${it.role}: ${it.content}" },
                events = active.debugEvents.toList()
            )
        }
        return archivedDebug[resolvedId] ?: archivedDebug[id]
    }

    fun addDebugEvent(id: String, stage: String, message: String, details: Map<String, String> = emptyMap()) {
        sessions[resolveSessionId(id)]?.debugEvents?.add(
            CallDebugEvent(
                stage = stage,
                message = message,
                details = details
            )
        )
    }

    // Generate greeting based on caller's likely language (from phone number prefix)
    fun getGreeting(session: CallSession): String {
        val contactName = session.contactProfile?.name?.takeIf { it.isNotBlank() }
        if (session.hrInterviewMode) {
            return when (session.detectedLanguage) {
                Language.HINDI, Language.NEPALI, Language.MARATHI ->
                    if (contactName != null) "Namaste $contactName ji, main ${session.agentConfig.agentName} bol rahi hoon."
                    else "Namaste, main ${session.agentConfig.agentName} bol rahi hoon."
                else ->
                    if (contactName != null) "Hello $contactName, this is ${session.agentConfig.agentName} from HR."
                    else "Hello, this is ${session.agentConfig.agentName} from HR."
            }
        }
        if (session.hrCallType != null) {
            return buildScenarioGreeting(session)
        }
        val language = session.detectedLanguage
        session.detectedLanguage = language
        val greetingMap = session.agentConfig.greetings
        val greeting = greetingMap[language.bcp47Code]
            ?: greetingMap[session.agentConfig.primaryLanguage.bcp47Code]
            ?: greetingMap["en-IN"]

        val baseGreeting = greeting
            ?.replace(AgentConfig.DEFAULT.agentName, session.agentConfig.agentName)
            ?: defaultGreeting(language, session.agentConfig.agentName)
        return if (contactName != null) {
            when (language) {
                Language.HINDI, Language.NEPALI, Language.MARATHI -> "Namaste $contactName ji. $baseGreeting"
                else -> "Hello $contactName. $baseGreeting"
            }
        } else {
            baseGreeting
        }
    }

    suspend fun processMessage(
        session: CallSession,
        userMessage: String,
        languageHint: String? = null
    ): AgentResponse {
        val telephonyMaxTokens = config.llm.maxTokens.coerceIn(80, 180)

        // Detect language
        val hintedLanguage = languageHint?.let { Language.fromBcp47(it) }
        val detected = hintedLanguage ?: languageService.detect(userMessage)

        // If detected as English but session was in an Indian language, check if it's likely Roman script
        // We keep the previous language if the current detection is English and previous wasn't
        var language = if (hintedLanguage != null) {
            hintedLanguage
        } else if (detected == Language.ENGLISH && session.detectedLanguage != Language.ENGLISH) {
            val lower = userMessage.lowercase()
            val wantsEnglish = lower.contains("english") || lower.contains("angrezi")
            if (wantsEnglish) Language.ENGLISH else session.detectedLanguage
        } else {
            detected
        }
        val lowerUser = userMessage.lowercase()
        val containsDevanagari = userMessage.any { it.code in 0x0900..0x097F }
        val explicitEnglishSwitch = lowerUser.contains("english") || lowerUser.contains("angrezi")
        if ((session.detectedLanguage == Language.HINDI || containsDevanagari) && !explicitEnglishSwitch) {
            language = Language.HINDI
        }
        session.detectedLanguage = language
        if (session.hrInterviewMode) {
            val hrResponse = handleHrInterviewTurn(session, userMessage, language)
            if (hrResponse != null) {
                session.history.add(Message(role = "assistant", content = hrResponse.text, language = language))
                messagesProcessedCount += 1
                return hrResponse
            }
        }
        if (session.hrCallType != null && !session.hrInterviewMode) {
            val scenarioResponse = handleStructuredHrTurn(session, userMessage, language)
            if (scenarioResponse != null) {
                session.history.add(Message(role = "assistant", content = scenarioResponse.text, language = language))
                messagesProcessedCount += 1
                return scenarioResponse
            }
        }
        addDebugEvent(
            session.id,
            stage = "language_detected",
            message = "Caller language resolved",
            details = mapOf(
                "hint" to (languageHint ?: ""),
                "detected" to detected.bcp47Code,
                "resolved" to language.bcp47Code
            )
        )
        addDebugEvent(
            session.id,
            stage = "caller_transcript",
            message = "Caller speech received",
            details = mapOf(
                "text" to userMessage,
                "language_hint" to (languageHint ?: ""),
                "resolved_language" to language.bcp47Code
            )
        )

        // Check for escalation triggers
        val lowerMsg = userMessage.lowercase()
        if (session.agentConfig.escalationKeywords.any { lowerMsg.contains(it.lowercase()) }) {
            log.info { "Escalation triggered for session ${session.id}" }
            session.status = CallStatus.TRANSFERRED
            addDebugEvent(
                session.id,
                stage = "escalation",
                message = "Escalation keyword matched",
                details = mapOf("user_message" to userMessage.take(220))
            )
            return AgentResponse(
                text = session.agentConfig.greetings["escalation"]
                    ?: "Main aapko apne senior agent se connect karta hoon. Ek minute rukiye.",
                language = language,
                action = AgentAction.TRANSFER_TO_HUMAN
            )
        }

        // Add to history
        session.history.add(Message(role = "user", content = userMessage, language = language))
        messagesProcessedCount += 1

        // Detect intent (quick keyword check)
        val intent = detectIntent(userMessage, language)
        session.intentHistory.add(intent.name)

        trainingService.fastAnswer(userMessage, language)?.let { fastResponse ->
            addDebugEvent(
                session.id,
                stage = "fast_answer",
                message = "Served response from cached business knowledge without LLM round-trip",
                details = mapOf(
                    "intent" to intent.name.lowercase(),
                    "response" to fastResponse
                )
            )
            session.history.add(Message(role = "assistant", content = fastResponse, language = language))
            messagesProcessedCount += 1
            return AgentResponse(
                text = fastResponse,
                language = language,
                action = if (intent == Intent.END_CALL) AgentAction.END_CALL else AgentAction.RESPOND
            )
        }

        // Build system prompt with context from knowledge base
        val promptArtifacts = trainingService.buildPromptArtifacts(session, userMessage)
        val previousAssistant = session.history.asReversed().firstOrNull { it.role == "assistant" }?.content.orEmpty()
        addDebugEvent(
            session.id,
            stage = "prompt_built",
            message = "Prompt prepared for LLM",
            details = mapOf(
                "intent" to intent.name.lowercase(),
                "user_message" to userMessage,
                "context_sources" to promptArtifacts.contextSources.joinToString(", "),
                "context_preview" to promptArtifacts.contextSnippet,
                "previous_assistant" to previousAssistant,
                "system_prompt" to promptArtifacts.systemPrompt
            )
        )
        addDebugEvent(
            session.id,
            stage = "llm_request",
            message = "Sending request to LLM",
            details = mapOf(
                "provider" to llmClient.describe().provider,
                "model" to llmClient.describe().model,
                "api_format" to llmClient.describe().apiFormat,
                "user_message" to userMessage,
                "history_excerpt" to session.history.takeLast(historyWindowForModel()).joinToString("\n") { "${it.role}: ${it.content}" },
                "max_tokens" to telephonyMaxTokens.toString()
            )
        )

        // Keep telephony turns within Twilio's webhook budget.
        val llmStartedAt = System.currentTimeMillis()
        var responseText = try {
            withTimeoutOrNull(config.llm.callResponseTimeoutMs) {
                llmClient.chat(
                    systemPrompt = promptArtifacts.systemPrompt,
                    history = session.history.takeLast(historyWindowForModel()),
                    userMessage = userMessage,
                    maxTokens = telephonyMaxTokens
                )
            }
        } catch (cause: Throwable) {
            log.error(cause) { "[${session.id}] LLM request failed" }
            addDebugEvent(
                session.id,
                stage = "llm_error",
                message = "Primary LLM request failed",
                details = mapOf(
                    "error" to (cause.message ?: cause::class.simpleName.orEmpty())
                )
            )
            null
        }
        val llmDurationMs = System.currentTimeMillis() - llmStartedAt

        if (responseText == null) {
            log.warn { "[${session.id}] LLM response exceeded call budget (${llmDurationMs}ms). Using fallback." }
            addDebugEvent(
                session.id,
                stage = "llm_timeout",
                message = "Primary LLM response missed the telephony budget",
                details = mapOf(
                    "duration_ms" to llmDurationMs.toString(),
                    "budget_ms" to config.llm.callResponseTimeoutMs.toString()
                )
            )
            responseText = stabilizeResponse(
                text = "",
                userMessage = userMessage,
                intent = intent,
                language = language,
                fallbackResponse = session.agentConfig.fallbackResponse
            )
        } else {
            addDebugEvent(
                session.id,
                stage = "llm_response",
                message = "Primary LLM response received",
                details = mapOf(
                    "duration_ms" to llmDurationMs.toString(),
                    "response" to responseText
                )
            )
        }

        if (isRepetitiveResponse(responseText, previousAssistant)) {
            addDebugEvent(
                session.id,
                stage = "repeat_guard",
                message = "Detected repetitive response, requesting a rewrite",
                details = mapOf(
                    "previous" to previousAssistant,
                    "current" to responseText
                )
            )
            val retryBudgetMs = (config.llm.callResponseTimeoutMs / 3).coerceAtLeast(2_000L)
            val retryStartedAt = System.currentTimeMillis()
            val retryResponse = try {
                withTimeoutOrNull(retryBudgetMs) {
                    llmClient.chat(
                        systemPrompt = promptArtifacts.systemPrompt + "\n\nREPETITION GUARD:\n→ Your last draft sounded repetitive.\n→ Reply with different wording.\n→ Move the conversation forward with one useful answer or one specific clarifying question.\n→ Do not reuse the same greeting or closing.",
                        history = session.history.takeLast(historyWindowForModel()),
                        userMessage = userMessage,
                        maxTokens = (telephonyMaxTokens / 2).coerceIn(64, 120)
                    )
                }
            } catch (cause: Throwable) {
                log.warn(cause) { "[${session.id}] Retry LLM request failed" }
                null
            }
            val retryDurationMs = System.currentTimeMillis() - retryStartedAt

            if (retryResponse != null) {
                responseText = retryResponse
                addDebugEvent(
                    session.id,
                    stage = "llm_response_retry",
                    message = "Retry response received after repetition guard",
                    details = mapOf(
                        "duration_ms" to retryDurationMs.toString(),
                        "response" to responseText
                    )
                )
            } else {
                addDebugEvent(
                    session.id,
                    stage = "llm_response_retry_skipped",
                    message = "Retry response missed the telephony budget",
                    details = mapOf("duration_ms" to retryDurationMs.toString())
                )
            }
        }

        responseText = humanizeResponse(responseText, language)
        responseText = enforceLanguageLock(responseText, userMessage, language, intent)
        responseText = stabilizeResponse(
            text = responseText,
            userMessage = userMessage,
            intent = intent,
            language = language,
            fallbackResponse = session.agentConfig.fallbackResponse
        )
        addDebugEvent(
            session.id,
            stage = "assistant_response",
            message = "Final assistant response prepared for caller",
            details = mapOf(
                "text" to responseText,
                "language" to language.bcp47Code,
                "intent" to intent.name.lowercase()
            )
        )

        // Add agent response to history
        session.history.add(Message(role = "assistant", content = responseText, language = language))
        messagesProcessedCount += 1

        log.debug { "[${session.id}] User (${language.displayName}): $userMessage" }
        log.debug { "[${session.id}] Agent: $responseText" }

        return AgentResponse(
            text = responseText,
            language = language,
            action = if (intent == Intent.END_CALL) AgentAction.END_CALL else AgentAction.RESPOND
        )
    }

    suspend fun endSession(id: String, qualityScore: Float? = null) {
        val resolvedId = resolveSessionId(id)
        val session = sessions[resolvedId] ?: return
        session.endedAt = System.currentTimeMillis()
        session.status = CallStatus.COMPLETED
        sessionStatuses[resolvedId] = CallStatus.COMPLETED
        session.qualityScore = qualityScore
        completedCallsCount += 1
        totalCallDurationSeconds += ((session.endedAt!! - session.startedAt) / 1000).toInt()
        recordHistory(session)
        archivedDebug[resolvedId] = CallDebugResponse(
            callSid = session.id,
            status = session.status.name.lowercase(),
            language = session.detectedLanguage.bcp47Code,
            recentTranscript = session.history.takeLast(12).map { "${it.role}: ${it.content}" },
            events = session.debugEvents.toList()
        )
        persistHrDraftIfAvailable(session)
        persistContactMemory(session)

        // Trigger self-learning
        trainingService.learnFromCall(session)
        sessions.remove(resolvedId)
        log.info { "Session ended: $resolvedId (duration: ${(session.endedAt!! - session.startedAt) / 1000}s)" }
    }

    fun activeSessionsCount(): Int = sessions.size

    fun updateSessionStatus(id: String, status: CallStatus) {
        val resolvedId = resolveSessionId(id)
        sessions[resolvedId]?.status = status
        sessionStatuses[resolvedId] = status
        addDebugEvent(
            resolvedId,
            stage = "status_update",
            message = "Call status changed",
            details = mapOf("status" to status.name.lowercase())
        )
    }

    fun getOrCreateConferenceName(id: String): String {
        val resolvedId = resolveSessionId(id)
        return conferenceNames.computeIfAbsent(resolvedId) {
            "voicenova-${resolvedId.takeLast(12).replace(Regex("[^A-Za-z0-9]"), "").lowercase()}"
        }
    }

    fun conferenceName(id: String): String? = conferenceNames[resolveSessionId(id)]

    fun adoptSessionId(previousId: String, newId: String): CallSession? {
        if (previousId == newId) return sessions[newId] ?: sessions[previousId]

        val existing = sessions[newId]
        if (existing != null) {
            sessionAliases[previousId] = newId
            sessionStatuses[previousId]?.let { sessionStatuses[newId] = it }
            return existing
        }

        val session = sessions.remove(previousId) ?: return sessions[newId]
        val migrated = session.copy(id = newId)
        sessions[newId] = migrated
        sessionStatuses[previousId]?.let { sessionStatuses[newId] = it }
        sessionAliases[previousId] = newId
        conferenceNames[previousId]?.let { conferenceNames[newId] = it }
        return migrated
    }

    fun getRecentHistory(limit: Int = 20): List<CallHistoryItem> {
        val activeItems = sessions.values
            .sortedByDescending { it.startedAt }
            .take(limit)
            .map { session ->
                CallHistoryItem(
                    callSid = session.id,
                    from = session.from,
                    to = session.to,
                    status = session.status.name.lowercase(),
                    direction = session.direction.name.lowercase(),
                    language = session.detectedLanguage.bcp47Code,
                    durationSeconds = ((System.currentTimeMillis() - session.startedAt) / 1000).toInt(),
                    startedAt = session.startedAt,
                    endedAt = session.endedAt
                )
            }

        return (activeItems + callHistory)
            .sortedByDescending { it.startedAt }
            .take(limit)
    }

    fun currentAnalytics(): AnalyticsResponse {
        val creditsLimit = 5000
        val creditsUsed = (completedCallsCount * 20) + messagesProcessedCount
        val creditsRemaining = (creditsLimit - creditsUsed).coerceAtLeast(0)
        val intentCounts = sessions.values
            .flatMap { it.intentHistory }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(4)
            .map { "${it.key.lowercase().replace('_', ' ')} (${it.value})" }

        val languageCounts = sessions.values
            .groupingBy { it.detectedLanguage.displayName }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(4)
            .map { LanguageMetric(label = it.key, value = it.value) }

        val avgCallSeconds = if (completedCallsCount == 0) 0 else totalCallDurationSeconds / completedCallsCount
        val totalCalls = completedCallsCount + activeSessionsCount()
        val successRate = if (totalCalls == 0) 100 else ((completedCallsCount * 100f) / totalCalls).toInt()

        return AnalyticsResponse(
            activeCalls = activeSessionsCount(),
            completedCalls = completedCallsCount,
            messagesProcessed = messagesProcessedCount,
            creditsUsed = creditsUsed,
            creditsRemaining = creditsRemaining,
            creditsLimit = creditsLimit,
            successRate = successRate,
            avgCallSeconds = avgCallSeconds,
            topIntents = intentCounts.ifEmpty { listOf("general query (0)") },
            languageMix = languageCounts.ifEmpty { listOf(LanguageMetric(label = "Hindi", value = 0)) }
        )
    }

    // ── TwiML Generation for Twilio ───────────────────────────────────────────

    suspend fun buildWelcomeTwiml(session: CallSession): String {
        val greetingNode = speechNode(getGreeting(session), session.detectedLanguage, session.id)
        val openerNode = speechNode(openingIntro(session), session.detectedLanguage, session.id)
        val repromptNode = speechNode(firstQuestionPrompt(session.detectedLanguage, session), session.detectedLanguage, session.id)
        val twiml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response>
                $greetingNode
                $openerNode
                <Gather input="speech" speechTimeout="auto" action="/webhooks/twilio/transcription" method="POST" actionOnEmptyResult="true"
                        language="${session.detectedLanguage.bcp47Code}"
                        hints="haan,nahi,theek hai,dhanyavaad,bye">
                    $repromptNode
                </Gather>
            </Response>
        """.trimIndent()
        addDebugEvent(
            session.id,
            stage = "twiml_welcome",
            message = "Welcome TwiML prepared",
            details = mapOf(
                "language" to session.detectedLanguage.bcp47Code,
                "twiml" to twiml
            )
        )
        return twiml
    }

    suspend fun buildNoSpeechTwiml(session: CallSession): String {
        val repromptNode = speechNode(noSpeechPrompt(session.detectedLanguage), session.detectedLanguage, session.id)
        val gatherNode = speechNode(repromptText(session.detectedLanguage), session.detectedLanguage, session.id)
        val twiml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response>
                $repromptNode
                <Gather input="speech" speechTimeout="auto" action="/webhooks/twilio/transcription" method="POST" actionOnEmptyResult="true"
                        language="${session.detectedLanguage.bcp47Code}">
                    $gatherNode
                </Gather>
            </Response>
        """.trimIndent()
        addDebugEvent(
            session.id,
            stage = "twiml_no_speech",
            message = "No-speech retry TwiML prepared",
            details = mapOf("twiml" to twiml)
        )
        return twiml
    }

    suspend fun buildResponseTwiml(session: CallSession, agentResponse: AgentResponse): String {
        val finalText = if (agentResponse.action == AgentAction.END_CALL && !agentResponse.text.contains("thank", ignoreCase = true)) {
            "${agentResponse.text.trim()} Thank you for your time. Goodbye."
        } else {
            agentResponse.text
        }
        val responseNode = speechNode(finalText, agentResponse.language, session.id)
        val followUpNode = speechNode(followUpPrompt(session, agentResponse.language), agentResponse.language, session.id)

        val twiml = when (agentResponse.action) {
            AgentAction.TRANSFER_TO_HUMAN -> """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    $responseNode
                    <Dial>${config.twilio.escalationPhoneNumber}</Dial>
                </Response>
            """.trimIndent().trimStart()
            AgentAction.END_CALL -> """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    $responseNode
                    <Hangup/>
                </Response>
            """.trimIndent().trimStart()
            else -> """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    $responseNode
                    <Gather input="speech" speechTimeout="auto" action="/webhooks/twilio/transcription" method="POST" actionOnEmptyResult="true"
                            language="${agentResponse.language.bcp47Code}">
                        $followUpNode
                    </Gather>
                </Response>
            """.trimIndent().trimStart()
        }
        addDebugEvent(
            session.id,
            stage = "twiml_response",
            message = "Response TwiML prepared for caller",
            details = mapOf(
                "action" to agentResponse.action.name.lowercase(),
                "language" to agentResponse.language.bcp47Code,
                "text" to agentResponse.text,
                "twiml" to twiml
            )
        )
        return twiml
    }

    suspend fun buildPlivoWelcomeXml(session: CallSession): String {
        val greetingNode = plivoSpeechNode(getGreeting(session), session.detectedLanguage, session.id)
        val openerNode = plivoSpeechNode(openingIntro(session), session.detectedLanguage, session.id)
        val repromptNode = plivoSpeechNode(firstQuestionPrompt(session.detectedLanguage, session), session.detectedLanguage, session.id)
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response>
                $greetingNode
                $openerNode
                <GetInput inputType="speech" action="/webhooks/plivo/input" method="POST" language="${plivoSpeechLanguage(session.detectedLanguage)}" speechTimeout="auto" hints="haan,nahi,theek hai,dhanyavaad,bye">
                    $repromptNode
                </GetInput>
            </Response>
        """.trimIndent()
    }

    suspend fun buildPlivoNoInputXml(session: CallSession): String {
        val noInputNode = plivoSpeechNode(noSpeechPrompt(session.detectedLanguage), session.detectedLanguage, session.id)
        val repromptNode = plivoSpeechNode(repromptText(session.detectedLanguage), session.detectedLanguage, session.id)
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response>
                $noInputNode
                <GetInput inputType="speech" action="/webhooks/plivo/input" method="POST" language="${plivoSpeechLanguage(session.detectedLanguage)}" speechTimeout="auto">
                    $repromptNode
                </GetInput>
            </Response>
        """.trimIndent()
    }

    suspend fun buildPlivoResponseXml(session: CallSession, agentResponse: AgentResponse): String {
        val responseNode = plivoSpeechNode(agentResponse.text, agentResponse.language, session.id)
        val followUpNode = plivoSpeechNode(followUpPrompt(session, agentResponse.language), agentResponse.language, session.id)

        return when (agentResponse.action) {
            AgentAction.TRANSFER_TO_HUMAN -> """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    $responseNode
                    <Dial>
                        <Number>${escapeXml(config.twilio.escalationPhoneNumber)}</Number>
                    </Dial>
                </Response>
            """.trimIndent().trimStart()
            AgentAction.END_CALL -> """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    $responseNode
                    <Hangup/>
                </Response>
            """.trimIndent().trimStart()
            else -> """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    $responseNode
                    <GetInput inputType="speech" action="/webhooks/plivo/input" method="POST" language="${plivoSpeechLanguage(agentResponse.language)}" speechTimeout="auto">
                        $followUpNode
                    </GetInput>
                </Response>
            """.trimIndent().trimStart()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun guessLanguageFromPhone(phone: String): Language {
        // Detect likely language from phone number country/region code
        return when {
            phone.startsWith("+91") || phone.startsWith("0") -> Language.HINDI  // India, default Hindi
            phone.startsWith("+971") || phone.startsWith("+966") -> Language.ARABIC
            phone.startsWith("+1") -> Language.ENGLISH
            phone.startsWith("+44") -> Language.ENGLISH
            else -> Language.ENGLISH
        }
    }

    private fun detectIntent(text: String, language: Language): Intent {
        val lower = text.lowercase()
        return when {
            lower.contains("price") || lower.contains("cost") ||
            lower.contains("kitna") || lower.contains("rate") ||
            lower.contains("pricing") || lower.contains("plan") -> Intent.PRODUCT_INQUIRY
            lower.contains("bill") || lower.contains("payment") ||
            lower.contains("paisa") || lower.contains("invoice") -> Intent.BILLING_QUERY
            lower.contains("password") || lower.contains("reset") ||
            lower.contains("login") || lower.contains("account") ||
            lower.contains("access") -> Intent.TECHNICAL_SUPPORT
            lower.contains("problem") || lower.contains("issue") ||
            lower.contains("kaam nahi") || lower.contains("error") -> Intent.TECHNICAL_SUPPORT
            lower.contains("complaint") || lower.contains("unhappy") ||
            lower.contains("shikayat") -> Intent.COMPLAINT
            lower.contains("refund") || lower.contains("wapas") ||
            lower.contains("money back") -> Intent.REFUND_REQUEST
            lower.contains("bye") || lower.contains("dhanyavaad") ||
            lower.contains("thank") -> Intent.END_CALL
            else -> Intent.GENERAL_QUERY
        }
    }

    private fun historyWindowForModel(): Int {
        val runtime = llmClient.describe()
        val model = runtime.model.lowercase()
        val isSmallLocalModel = runtime.provider == "local" && (
            model.contains("gemma") ||
                model.contains("qwen") ||
                model.contains("phi") ||
                model.contains("llama3.2")
            )
        return if (isSmallLocalModel) 3 else 10
    }

    private fun getVoiceName(language: Language, gender: String = "female"): String {
        val male = gender.equals("male", ignoreCase = true)
        return when (language) {
            Language.HINDI -> if (male) "Google.hi-IN-Standard-B" else "Google.hi-IN-Standard-D"
            Language.NEPALI -> if (male) "Google.hi-IN-Standard-B" else "Google.hi-IN-Standard-D"
            Language.TAMIL -> if (male) "Google.ta-IN-Standard-B" else "Google.ta-IN-Standard-A"
            Language.TELUGU -> if (male) "Google.te-IN-Standard-B" else "Google.te-IN-Standard-A"
            Language.BENGALI -> if (male) "Google.bn-IN-Standard-A" else "Google.bn-IN-Standard-B"
            Language.MARATHI -> if (male) "Google.mr-IN-Standard-B" else "Google.mr-IN-Standard-A"
            Language.GUJARATI -> if (male) "Google.gu-IN-Standard-A" else "Google.gu-IN-Standard-B"
            Language.KANNADA -> if (male) "Google.kn-IN-Standard-B" else "Google.kn-IN-Standard-A"
            Language.MALAYALAM -> if (male) "Google.ml-IN-Standard-B" else "Google.ml-IN-Standard-A"
            Language.PUNJABI -> if (male) "Google.pa-IN-Standard-B" else "Google.pa-IN-Standard-A"
            Language.ENGLISH -> if (male) "Google.en-IN-Standard-B" else "Google.en-IN-Standard-C"
            Language.ARABIC -> if (male) "Google.ar-XA-Standard-B" else "Google.ar-XA-Standard-C"
            else -> if (male) "Google.en-US-Standard-D" else "Google.en-US-Standard-C"
        }
    }

    private fun twilioSayNode(text: String, language: Language): String {
        return """<Say language="${language.bcp47Code}">${escapeXml(text)}</Say>"""
    }

    private suspend fun speechNode(text: String, language: Language, callSid: String? = null): String {
        val audio = telephonyAudioNode(text, language, callSid)
        if (audio != null) {
            if (callSid != null) {
                addDebugEvent(
                    callSid,
                    stage = "tts_output",
                    message = "Generated audio prepared for caller playback",
                    details = mapOf(
                        "provider" to ttsClient.currentProvider(),
                        "language" to language.bcp47Code,
                        "spoken_text" to text,
                        "public_url" to audio.publicUrl,
                        "file_name" to audio.fileName
                    )
                )
            }
            return "<Play>${escapeXml(audio.publicUrl)}</Play>"
        }

        if (ttsClient.currentProvider() == "twilio_basic") {
            if (callSid != null) {
                addDebugEvent(
                    callSid,
                    stage = "tts_output",
                    message = "Twilio built-in speech selected for caller playback",
                    details = mapOf(
                        "provider" to ttsClient.currentProvider(),
                        "language" to language.bcp47Code,
                        "spoken_text" to text
                    )
                )
            }
            return twilioSayNode(text, language)
        }

        val gender = callSid?.let { sessions[resolveSessionId(it)]?.agentConfig?.agentGender } ?: "female"
        val voiceName = getVoiceName(language, gender)
        if (callSid != null) {
            addDebugEvent(
                callSid,
                stage = "tts_output",
                message = "Provider voice selected for caller playback",
                details = mapOf(
                    "provider" to ttsClient.currentProvider(),
                    "language" to language.bcp47Code,
                    "voice_name" to voiceName,
                    "spoken_text" to text
                )
            )
        }
        return """<Say voice="$voiceName" language="${language.bcp47Code}">${escapeXml(text)}</Say>"""
    }

    private suspend fun plivoSpeechNode(text: String, language: Language, callSid: String? = null): String {
        val audio = telephonyAudioNode(text, language, callSid)
        if (audio != null) {
            return "<Play>${escapeXml(audio.publicUrl)}</Play>"
        }

        val gender = callSid?.let { sessions[resolveSessionId(it)]?.agentConfig?.agentGender } ?: "female"
        val voice = if (gender.equals("male", ignoreCase = true)) "MAN" else "WOMAN"
        return """<Speak language="${plivoSpeechLanguage(language)}" voice="$voice">${escapeXml(text)}</Speak>"""
    }

    private suspend fun telephonyAudioNode(
        text: String,
        language: Language,
        callSid: String?
    ): TTSClient.GeneratedAudio? {
        val provider = ttsClient.currentProvider()
        val generatedAudioProvider = provider == "elevenlabs" || provider == "piper" || provider == "macos_say"
        var completed = false

        if (callSid != null) {
            addDebugEvent(
                callSid,
                stage = "tts_request",
                message = "Preparing caller audio output",
                details = mapOf(
                    "provider" to provider,
                    "language" to language.bcp47Code,
                    "text" to text
                )
            )
        }

        val audio = try {
            withTimeoutOrNull(telephonyTtsBudgetMs) {
                ttsClient.synthesizeToPublicUrl(text, language.bcp47Code).also {
                    completed = true
                }
            }
        } catch (e: Exception) {
            if (callSid != null && generatedAudioProvider) {
                addDebugEvent(
                    callSid,
                    stage = "tts_fallback",
                    message = "Generated-audio TTS failed during live call. Falling back to provider voice.",
                    details = mapOf("provider" to provider, "error" to (e.message ?: e::class.simpleName.orEmpty()))
                )
            }
            log.warn(e) { "[${callSid ?: "telephony"}] TTS generation failed. Falling back to provider voice." }
            null
        }

        if (audio != null) {
            return audio
        }

        if (!completed && callSid != null && generatedAudioProvider) {
            addDebugEvent(
                callSid,
                stage = "tts_fallback",
                message = "Generated-audio TTS exceeded the telephony budget. Falling back to provider voice.",
                details = mapOf(
                    "provider" to provider,
                    "budget_ms" to telephonyTtsBudgetMs.toString()
                )
            )
            log.warn { "[$callSid] TTS generation exceeded ${telephonyTtsBudgetMs}ms. Using provider voice instead." }
        }

        return null
    }

    private fun plivoSpeechLanguage(language: Language): String {
        return when (language) {
            Language.ENGLISH -> "en-IN"
            Language.ARABIC -> "ar-SA"
            else -> "en-IN"
        }
    }

    private fun resolveSessionId(id: String): String {
        var current = id
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val next = sessionAliases[current] ?: break
            current = next
        }
        return current
    }

    private fun repromptText(language: Language): String {
        return when (language) {
            Language.HINDI -> "Bataiye."
            Language.NEPALI -> "Bataiye."
            Language.MARATHI -> "Bataiye."
            Language.TAMIL -> "Solliunga."
            Language.TELUGU -> "Cheppandi."
            Language.BENGALI -> "Bolun."
            Language.ARABIC -> "Tafaddal."
            else -> "Please go ahead."
        }
    }

    private fun openingIntro(session: CallSession): String {
        val company = session.agentConfig.companyName
        val roles = "Android Developer, iOS Developer, Backend Engineer, QA Engineer, and Product Support"
        if (session.hrCallType == HrCallType.CUSTOMER_SUPPORT || session.hrCallType == null) {
            return when (session.detectedLanguage) {
                Language.HINDI, Language.NEPALI, Language.MARATHI ->
                    "Main ${session.agentConfig.agentName} bol raha hoon. Aap jo bhi sawal poochna chahte hain, main usmein madad karunga."
                else ->
                    "This is ${session.agentConfig.agentName}. You can ask me anything and I will help."
            }
        }
        return when (session.detectedLanguage) {
            Language.HINDI, Language.NEPALI, Language.MARATHI ->
                "Main $company HR team se bol rahi hoon. Hum abhi $roles roles ke liye hiring kar rahe hain."
            Language.TAMIL ->
                "Naan $company HR team lirundhu pesuren. Naanga ippodhu $roles platforms-ku hiring panrom."
            Language.TELUGU ->
                "Nenu $company HR team nundi maatladutunnanu. Memu ippudu $roles platforms kosam hiring chestunnam."
            Language.BENGALI ->
                "Ami $company HR team theke bolchi. Amra ekhon $roles platforms-er jonno hiring korchi."
            else ->
                "This is ${session.agentConfig.agentName} from $company HR. We are hiring for $roles platforms."
        }
    }

    private fun firstQuestionPrompt(language: Language, session: CallSession? = null): String {
        if (session?.hrCallType == HrCallType.CUSTOMER_SUPPORT || session?.hrCallType == null) {
            return when (language) {
                Language.HINDI, Language.NEPALI, Language.MARATHI -> "Aap apna sawal batayiye."
                else -> "Please tell me how I can help you."
            }
        }
        return when (language) {
            Language.HINDI, Language.NEPALI, Language.MARATHI ->
                "Kya aap interested hain? Agar haan, to main short interview start karti hoon."
            Language.TAMIL ->
                "Neenga interested-aa? Haan-na short interview ippo start pannalaam."
            Language.TELUGU ->
                "Meeru interested unte, nenu ippude short interview start chestanu."
            Language.BENGALI ->
                "Apni interested hole ami ekhon short interview suru korte pari."
            else ->
                "Are you interested in this opportunity? Please say yes or no."
        }
    }

    private fun noSpeechPrompt(language: Language): String {
        return when (language) {
            Language.HINDI -> "Maaf kijiye, mujhe aawaz clear nahi mili. Kripya dobara batayiye."
            Language.NEPALI -> "Maaf kijiye, mujhe aawaz clear nahi mili. Kripya dobara batayiye."
            Language.MARATHI -> "Maaf kijiye, mujhe aawaz clear nahi mili. Kripya dobara batayiye."
            Language.TAMIL -> "Mannikkavum, ungal kural thelivaga ketkavillai. Dhayavu seydhu meendum sollunga."
            Language.TELUGU -> "Kshaminchandi, mee mata spashtanga vinapadaledu. Dayachesi malli cheppandi."
            Language.BENGALI -> "Dukkhito, apnar kotha spasto shona jayni. Doya kore abar bolun."
            Language.ARABIC -> "Aasif, lam asma sawtak bidooh. Min fadlak aaid al-kalam."
            else -> "Sorry, I could not hear you clearly. Please say that again."
        }
    }

    private fun followUpPrompt(session: CallSession, language: Language): String {
        if (session.hrInterviewMode) {
            return when (language) {
                Language.HINDI, Language.NEPALI, Language.MARATHI -> "Kripya apna jawab boliye."
                else -> "Please continue with your answer."
            }
        }
        if (session.hrCallType == HrCallType.CUSTOMER_SUPPORT || session.hrCallType == null) {
            return when (language) {
                Language.HINDI, Language.NEPALI, Language.MARATHI -> "Ji, aap apna agla sawal batayiye."
                else -> "Please go ahead with your next question."
            }
        }
        val assistantTurns = session.history.count { it.role == "assistant" }
        val prompts = when (language) {
            Language.HINDI -> listOf(
                "जी, अब आप अपना अगला सवाल बताइए।",
                "ठीक है, अगर और कुछ पूछना हो तो बताइए।",
                "मैं सुन रही हूँ, कृपया अगला सवाल बोलिए।",
                "समझ गई, अब आगे क्या जानना चाहेंगे?"
            )
            Language.NEPALI -> listOf(
                "Kripya apna agla sawal batayiye.",
                "Main sun rahi hoon, agla sawal boliye.",
                "Agar aur sawal hai to batayiye."
            )
            Language.MARATHI -> listOf(
                "Kripya apna agla sawal batayiye.",
                "Main sun rahi hoon, agla sawal boliye.",
                "Agar aur sawal hai to batayiye."
            )
            Language.TAMIL -> listOf(
                "Thevai irundhaal naan innum help pannalaam.",
                "Idha patri innum detail venumna sollunga.",
                "Vera yedhavathu kelvi irundhaal ketkalaam."
            )
            Language.TELUGU -> listOf(
                "Meeru korithe inka sahayam chestanu.",
                "Dini gurinchi inka vivaram kavali ante cheppandi.",
                "Inkemaina prasna unte adagandi."
            )
            Language.BENGALI -> listOf(
                "Chaile ami aro sahajjo korte pari.",
                "Ei bishoye aro bistarito bolte pari.",
                "Ar kono proshno thakle bolun."
            )
            Language.ARABIC -> listOf(
                "Iza ahbabt, yumkinuni musaeadatak akthar.",
                "Yumkinuni tawdih hadha bishakl akthar.",
                "Idha kana ladayka sual akhar faqol li."
            )
            else -> listOf(
                "Please ask your next question.",
                "I am listening, please continue.",
                "If you have another question, please go ahead."
            )
        }
        val recentAssistant = session.history.asReversed()
            .firstOrNull { it.role == "assistant" }
            ?.content
            ?.lowercase()
            .orEmpty()
        val start = assistantTurns % prompts.size
        val ordered = prompts.drop(start) + prompts.take(start)
        return ordered.firstOrNull { candidate ->
            !recentAssistant.contains(candidate.lowercase())
        } ?: ordered.first()
    }

    private fun isRepetitiveResponse(current: String, previous: String): Boolean {
        if (current.isBlank() || previous.isBlank()) return false
        val normalizedCurrent = normalizeForComparison(current)
        val normalizedPrevious = normalizeForComparison(previous)
        if (normalizedCurrent == normalizedPrevious) return true

        val currentWords = normalizedCurrent.split(" ").filter { it.length > 2 }.toSet()
        val previousWords = normalizedPrevious.split(" ").filter { it.length > 2 }.toSet()
        if (currentWords.isEmpty() || previousWords.isEmpty()) return false

        val overlap = currentWords.intersect(previousWords).size.toDouble() /
            currentWords.union(previousWords).size.toDouble()
        return overlap >= 0.78
    }

    private fun normalizeForComparison(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun humanizeResponse(text: String, language: Language): String {
        val cleaned = text
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")

        if (cleaned.isBlank()) {
            return when (language) {
                Language.HINDI -> "Ji, main ismein aapki madad karti hoon. Kripya ek baar aur batayiye."
                Language.NEPALI -> "Ji, main ismein aapki madad karti hoon. Kripya ek baar aur batayiye."
                Language.MARATHI -> "Ji, main ismein aapki madad karti hoon. Kripya ek baar aur batayiye."
                else -> "Sure, I can help with that. Please tell me once more."
            }
        }

        val softened = cleaned
            .replace(Regex("(?i)^hello\\s+[^.!?]{0,40},\\s*thank you for calling\\s+[^.?!]+[.?!]\\s*"), "")
            .replace(Regex("(?i)^thank you for calling\\s+[^.?!]+[.?!]\\s*"), "")
            .replace(Regex("(?i)\\bi am happy to help\\b"), "I can help with that")
            .replace(Regex("(?i)\\bi would be happy to help\\b"), "I can help with that")
            .replace(Regex("(?i)\\bplease note that\\b"), "")
            .replace(Regex("(?i)\\bas per our knowledge base\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        val sentences = softened
            .split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }

        val capped = when {
            sentences.size <= 2 -> softened
            else -> sentences.take(2).joinToString(" ").trim()
        }

        return capped
    }

    private fun enforceLanguageLock(
        text: String,
        userMessage: String,
        language: Language,
        intent: Intent
    ): String {
        if (language != Language.HINDI) return text

        val lower = text.lowercase()
        val devanagariCount = text.count { it.code in 0x0900..0x097F }
        val englishHeavy = devanagariCount < 8 && Regex("[a-zA-Z]{4,}").containsMatchIn(text)
        if (!englishHeavy) return text

        // Force a stable Hindi fallback when model drifts to English on Hindi turns.
        return stabilizeResponse(
            text = "",
            userMessage = userMessage,
            intent = intent,
            language = Language.HINDI,
            fallbackResponse = "कृपया अपना सवाल एक लाइन में बताइए, मैं स्पष्ट उत्तर दूँगी।"
        ).replace("Bilkul", "बिलकुल")
            .replace("Chaintech mein", "Chaintech में")
            .replace("roles mention hain", "roles खुले हैं")
    }

    private fun stabilizeResponse(
        text: String,
        userMessage: String,
        intent: Intent,
        language: Language,
        fallbackResponse: String
    ): String {
        if (!looksUnusable(text, language)) return text

        val lower = userMessage.lowercase()
        return when {
            lower.contains("password") || lower.contains("reset") -> when (language) {
                Language.HINDI ->
                    "Bilkul. Password reset karne ke liye login page par 'Forgot Password' par click kariye. Aapke email par reset link aa jayega."
                Language.NEPALI ->
                    "Bilkul. Password reset karne ke liye login page par 'Forgot Password' par click kariye. Aapke email par reset link aa jayega."
                Language.MARATHI ->
                    "Bilkul. Password reset karne ke liye login page par 'Forgot Password' par click kariye. Aapke email par reset link aa jayega."
                else ->
                    "Sure. Go to the login page, click 'Forgot Password', and use the reset link sent to your email."
            }

            lower.contains("job") || lower.contains("jobs") || lower.contains("hiring") || lower.contains("career") ||
                lower.contains("android developer") || lower.contains("ios developer") ||
                lower.contains("जॉब") || lower.contains("जॉब्स") || lower.contains("नौकरी") || lower.contains("भर्ती") || lower.contains("करियर") ->
                when (language) {
                    Language.HINDI, Language.NEPALI, Language.MARATHI ->
                        "Chaintech mein abhi Android Developer aur iOS Developer roles mention hain. Agar aap chahen to main aapki details note karke hiring team se callback arrange karwa sakti hoon."
                    else ->
                        "Chaintech is currently highlighting Android Developer and iOS Developer roles. I can also note your details for a hiring follow-up."
                }

            lower.contains("ixfi") || lower.contains("token") || lower.contains("टोकन") -> when (language) {
                Language.HINDI, Language.NEPALI, Language.MARATHI ->
                    "IXFI TOKEN Chaintech ecosystem ka native token hai. Isse fee benefits aur ecosystem perks milte hain."
                else ->
                    "IXFI TOKEN is Chaintech's native ecosystem token, with fee benefits and ecosystem perks."
            }

            lower.contains("fee") || lower.contains("fees") || lower.contains("pricing") || lower.contains("price") ||
                lower.contains("कितना") || lower.contains("फीस") || lower.contains("शुल्क") ->
                when (language) {
                    Language.HINDI, Language.NEPALI, Language.MARATHI ->
                        "Chaintech comparatively low transaction fees ke liye jaana jata hai. Agar aap chahein to main trading services ka quick breakdown bhi de sakti hoon."
                    else ->
                        "Chaintech is known for comparatively low transaction fees. I can also give you a quick trading-services breakdown."
                }

            lower.contains("safu") || lower.contains("safe") || lower.contains("safety") || lower.contains("security") ||
                lower.contains("सुरक्षा") || lower.contains("सेफ") ->
                when (language) {
                    Language.HINDI, Language.NEPALI, Language.MARATHI ->
                        "Chaintech SAFU jaise security measures use karta hai, jo user assets ke protection ke liye reserve fund model hai."
                    else ->
                        "Chaintech uses security measures such as SAFU, a reserve-fund model intended to protect user assets."
                }

            intent == Intent.PRODUCT_INQUIRY || intent == Intent.BILLING_QUERY -> when (language) {
                Language.HINDI ->
                    "Hamara Basic Plan ₹999 per month hai. Pro Plan ₹2,999 per month hai aur ismein unlimited features milte hain."
                Language.NEPALI ->
                    "Hamara Basic Plan ₹999 per month hai. Pro Plan ₹2,999 per month hai aur ismein unlimited features milte hain."
                Language.MARATHI ->
                    "Hamara Basic Plan ₹999 per month hai. Pro Plan ₹2,999 per month hai aur ismein unlimited features milte hain."
                else ->
                    "Our Basic Plan is ₹999 per month. The Pro Plan is ₹2,999 per month and includes unlimited features."
            }

            intent == Intent.REFUND_REQUEST || lower.contains("refund") || lower.contains("wapas") -> when (language) {
                Language.HINDI ->
                    "Ji bilkul. Hum 30 din ke andar full refund offer karte hain. Agar aap chahen to main process bhi bata sakti hoon."
                Language.NEPALI ->
                    "Ji bilkul. Hum 30 din ke andar full refund offer karte hain. Agar aap chahen to main process bhi bata sakti hoon."
                Language.MARATHI ->
                    "Ji bilkul. Hum 30 din ke andar full refund offer karte hain. Agar aap chahen to main process bhi bata sakti hoon."
                else ->
                    "Yes. We offer a full refund within 30 days. If you want, I can also explain the process."
            }

            language == Language.HINDI || language == Language.NEPALI || language == Language.MARATHI ->
                fallbackResponse.ifBlank { "Ji, main madad kar sakti hoon. Kripya apni problem ek line mein batayiye, jaise password reset, plan price, ya refund." }

            else ->
                "I can help with that. Please ask once more and I will answer briefly."
        }
    }

    private suspend fun handleHrInterviewTurn(
        session: CallSession,
        userMessage: String,
        language: Language
    ): AgentResponse? {
        val normalized = normalizeForYesNo(userMessage)
        val draft = session.hrCandidateDraft ?: HrCandidateProfile(
            phoneNumber = session.from,
            consentToStore = false
        )
        session.hrCandidateDraft = draft

        fun response(text: String, action: AgentAction = AgentAction.RESPOND): AgentResponse =
            AgentResponse(text = text, language = language, action = action)

        return when (session.hrStep) {
            HrInterviewStep.INTRO_OFFER -> {
                val affirmative = isAffirmativeRobustly(normalized)
                val negative = isNegativeRobustly(normalized)
                val roleFound = extractRole(userMessage) != null
                
                when {
                    affirmative || roleFound -> {
                        session.hrStep = HrInterviewStep.ASK_NAME_CONFIRM
                        val recall = draft.name?.let { "Welcome back $it. I will update your profile details now. " } ?: ""
                        response("${recall}Please confirm your full name to start the interview.")
                    }
                    negative -> {
                        session.hrStep = HrInterviewStep.DECLINED
                        response("Thank you for your time. We appreciate your response. Goodbye.", AgentAction.END_CALL)
                    }
                    else -> {
                        session.hrIntroAttempts += 1
                        if (session.hrIntroAttempts >= 2) {
                            session.hrStep = HrInterviewStep.ASK_NAME_CONFIRM
                            response("No problem, I will continue. Please confirm your full name to start the interview.")
                        } else {
                            response("Please say yes to continue the interview, or no to exit.")
                        }
                    }
                }
            }
            HrInterviewStep.ASK_NAME_CONFIRM, HrInterviewStep.ASK_NAME -> {
                val nameRaw = extractName(userMessage) ?: userMessage.trim()
                val nameEn = transliterateToEnglish(nameRaw, language)
                session.hrCandidateDraft = draft.copy(name = nameEn)
                session.hrStep = HrInterviewStep.ASK_ROLE
                response("Thank you. Which role do you want to join: Android Developer, iOS Developer, or another role?")
            }
            HrInterviewStep.ASK_ROLE -> {
                val roleRaw = extractRole(userMessage) ?: userMessage.trim()
                val roleEn = transliterateToEnglish(roleRaw, language)
                session.hrCandidateDraft = draft.copy(desiredRole = roleEn)
                session.hrStep = HrInterviewStep.ASK_SELF_INTRO
                response("Great. Please give a short self-introduction in 3 to 4 lines.")
            }
            HrInterviewStep.ASK_SELF_INTRO -> {
                val introEn = transliterateToEnglish(userMessage.trim(), language)
                session.hrCandidateDraft = draft.copy(selfIntroduction = introEn)
                session.hrStep = HrInterviewStep.ASK_PREVIOUS_COMPANY
                response("Please share your previous company details.")
            }
            HrInterviewStep.ASK_PREVIOUS_COMPANY -> {
                val compEn = transliterateToEnglish(userMessage.trim(), language)
                session.hrCandidateDraft = draft.copy(previousCompany = compEn)
                session.hrStep = HrInterviewStep.ASK_PROJECTS
                response("Great. Please describe your recent projects briefly.")
            }
            HrInterviewStep.ASK_PROJECTS -> {
                val projEn = transliterateToEnglish(userMessage.trim(), language)
                session.hrCandidateDraft = draft.copy(projects = projEn)
                session.hrStep = HrInterviewStep.ASK_BASIC_Q1
                response(hrQuestion1(draft.desiredRole))
            }
            HrInterviewStep.ASK_BASIC_Q1 -> {
                val a1En = transliterateToEnglish(userMessage.trim(), language)
                session.hrCandidateDraft = draft.copy(basicAnswer1 = a1En)
                session.hrStep = HrInterviewStep.ASK_BASIC_Q2
                response(hrQuestion2(draft.desiredRole))
            }
            HrInterviewStep.ASK_BASIC_Q2 -> {
                val a2En = transliterateToEnglish(userMessage.trim(), language)
                session.hrCandidateDraft = draft.copy(basicAnswer2 = a2En)
                session.hrStep = HrInterviewStep.ASK_EMAIL
                response("Please share your email address for next-round updates.")
            }
            HrInterviewStep.ASK_EMAIL -> {
                val email = extractEmailRobustly(userMessage)
                if (email == null) {
                    response("I could not capture a valid email. Please say your email again slowly.")
                } else {
                    session.hrCandidateDraft = draft.copy(email = email)
                    session.hrStep = HrInterviewStep.ASK_CONSENT
                    response("Do you agree that we can save your details for future updates? Please say yes or no.")
                }
            }
            HrInterviewStep.ASK_CONSENT -> {
                val affirmative = isAffirmativeRobustly(normalized)
                val negative = isNegativeRobustly(normalized)
                
                when {
                    affirmative -> {
                        val saved = trainingService.upsertCandidateProfile((session.hrCandidateDraft ?: draft).copy(consentToStore = true))
                        session.hrCandidateDraft = saved
                        trainingService.sendHrThankYouEmail(saved)
                        session.hrStep = HrInterviewStep.COMPLETE
                        response("Thank you so much. Your profile is saved and our team will connect for the next round soon. Thanks, goodbye.", AgentAction.END_CALL)
                    }
                    negative -> {
                        session.hrStep = HrInterviewStep.COMPLETE
                        response("Thank you for your time. We will not store your details. Goodbye.", AgentAction.END_CALL)
                    }
                    else -> response("Please answer yes or no for consent to save your profile.")
                }
            }
            HrInterviewStep.COMPLETE, HrInterviewStep.DECLINED -> response(
                "Thank you. This call is now complete.",
                AgentAction.END_CALL
            )
        }
    }

    private suspend fun isAffirmativeRobustly(text: String): Boolean {
        if (isAffirmative(text)) return true
        val prompt = "Analyze the following user response and determine if it is an affirmative 'YES' (consent, agreement, willingness to continue). Return 'YES' if it is affirmative, otherwise return 'NO'.\n\nRESPONSE: $text\n\nReturn ONLY 'YES' or 'NO'."
        val result = evaluateWithLlm(prompt).uppercase()
        return result.contains("YES")
    }

    private suspend fun isNegativeRobustly(text: String): Boolean {
        if (isNegative(text)) return true
        val prompt = "Analyze the following user response and determine if it is a negative 'NO' (declining, refusal, not interested). Return 'YES' if it is negative (meaning they said NO), otherwise return 'NO'.\n\nRESPONSE: $text\n\nReturn ONLY 'YES' if they said NO/REJECTED, or 'NO' if they did not reject."
        val result = evaluateWithLlm(prompt).uppercase()
        return result.contains("YES")
    }

    private fun isAffirmative(text: String): Boolean =
        yesSignals.any { containsWholeWordOrPhrase(text, it) }

    private fun isNegative(text: String): Boolean =
        noSignals.any { containsWholeWordOrPhrase(text, it) }

    private fun normalizeForYesNo(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9@._+\\-\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun containsWholeWordOrPhrase(text: String, phrase: String): Boolean {
        if (phrase.contains(" ")) {
            return text.contains(phrase)
        }
        val regex = Regex("(^|\\s)${Regex.escape(phrase)}(\\s|$)")
        return regex.containsMatchIn(text)
    }

    private fun persistHrDraftIfAvailable(session: CallSession) {
        if (!session.hrInterviewMode) return
        val draft = session.hrCandidateDraft ?: return
        
        // Save more aggressively: if they provided name, role, or even started self intro
        val hasAnyProgress = listOf(
            draft.name,
            draft.desiredRole,
            draft.selfIntroduction,
            draft.email
        ).any { !it.isNullOrBlank() }
        
        if (!hasAnyProgress) return
        
        log.info { "Auto-persisting HR draft for call ${session.id} because call ended." }
        trainingService.upsertCandidateProfile(draft)
    }

    private fun persistContactMemory(session: CallSession) {
        val existing = session.contactProfile ?: trainingService.getContactByPhone(session.to) ?: ContactProfile(phoneNumber = session.to)
        val lastUserMessage = session.history.asReversed().firstOrNull { it.role == "user" }?.content?.take(220)
        val updated = trainingService.upsertContactProfile(
            existing.copy(
                phoneNumber = session.to,
                preferredLanguage = session.detectedLanguage.bcp47Code,
                lastCallSummary = lastUserMessage ?: existing.lastCallSummary
            )
        )
        session.contactProfile = updated
    }

    private fun buildScenarioGreeting(session: CallSession): String {
        val name = session.contactProfile?.name?.takeIf { it.isNotBlank() }
        val scenario = session.hrScenario
        val prefix = when (session.detectedLanguage) {
            Language.HINDI, Language.NEPALI, Language.MARATHI ->
                if (name != null) "Namaste $name ji." else "Namaste."
            else ->
                if (name != null) "Hello $name." else "Hello."
        }
        return when (session.hrCallType) {
            HrCallType.SALARY -> {
                val amount = scenario?.salaryAmount?.takeIf { it.isNotBlank() }
                when (scenario?.salaryCredited) {
                    true -> if (amount != null) "$prefix Your salary of $amount has been credited to your account. Thank you."
                    else "$prefix Your salary has been credited to your account. Thank you."
                    false -> "$prefix This is an update that your salary has not been credited yet. Our team will share the next update shortly."
                    null -> "$prefix This is a salary update call from HR."
                }
            }
            HrCallType.ATTENDANCE -> "$prefix This is HR checking today's attendance. Please say present or absent."
            HrCallType.PRIVACY_POLICY_CHANGED -> {
                val summary = scenario?.privacyChangeSummary?.takeIf { it.isNotBlank() }
                if (summary != null) "$prefix Our privacy policy has changed. $summary"
                else "$prefix Our privacy policy has changed. I will explain the update briefly."
            }
            HrCallType.CUSTOMER_SUPPORT -> "$prefix This is Rehan Tumbi from ${session.agentConfig.companyName}. Please tell me how I can help you today."
            HrCallType.OTHER -> "$prefix This is an HR information call. You can ask your question and I will help."
            HrCallType.INTERVIEW, null -> prefix
        }
    }

    private suspend fun handleStructuredHrTurn(
        session: CallSession,
        userMessage: String,
        language: Language
    ): AgentResponse? {
        val lower = userMessage.lowercase()
        maybeLearnNameFromConversation(session, userMessage, language)
        return when (session.hrCallType) {
            HrCallType.ATTENDANCE -> {
                when {
                    lower.contains("present") || lower.contains("haan") || lower.contains("yes") -> {
                        val updated = trainingService.upsertContactProfile(
                            (session.contactProfile ?: ContactProfile(phoneNumber = session.to)).copy(
                                phoneNumber = session.to,
                                name = session.contactProfile?.name,
                                preferredLanguage = language.bcp47Code,
                                lastAttendanceStatus = "present",
                                lastCallSummary = "Attendance marked present"
                            )
                        )
                        session.contactProfile = updated
                        AgentResponse("Thank you. Your attendance is marked present for today. Goodbye.", language, AgentAction.END_CALL)
                    }
                    lower.contains("absent") || lower.contains("leave") -> {
                        val updated = trainingService.upsertContactProfile(
                            (session.contactProfile ?: ContactProfile(phoneNumber = session.to)).copy(
                                phoneNumber = session.to,
                                preferredLanguage = language.bcp47Code,
                                lastAttendanceStatus = "absent",
                                lastCallSummary = "Attendance marked absent"
                            )
                        )
                        session.contactProfile = updated
                        AgentResponse("Thank you. I have marked your attendance update. Goodbye.", language, AgentAction.END_CALL)
                    }
                    else -> AgentResponse("Please say present or absent for today's attendance.", language)
                }
            }
            HrCallType.SALARY -> {
                val scenario = session.hrScenario
                val amount = scenario?.salaryAmount?.takeIf { it.isNotBlank() }
                val salaryDone = listOf("ok", "okay", "thank", "thanks", "bye", "cut", "disconnect").any { lower.contains(it) }
                val unrelated = listOf("interview", "attendance", "policy", "support", "problem", "issue", "product").any { lower.contains(it) }
                val reply = when {
                    unrelated -> "This call is only for a salary update. For other help, please contact support or ask for a separate callback."
                    scenario?.salaryCredited == true && amount != null -> "Your salary of $amount has been credited to your account."
                    scenario?.salaryCredited == true -> "Your salary has been credited to your account."
                    scenario?.salaryCredited == false -> "Your salary is not credited yet. HR will share the next update soon."
                    else -> "This call is only for a salary update from HR."
                }
                AgentResponse(reply, language, if (salaryDone) AgentAction.END_CALL else AgentAction.RESPOND)
            }
            HrCallType.PRIVACY_POLICY_CHANGED -> {
                val summary = session.hrScenario?.privacyChangeSummary?.takeIf { it.isNotBlank() }
                    ?: session.hrScenario?.notes?.takeIf { it.isNotBlank() }
                    ?: "We have updated how personal information is handled and shared."
                AgentResponse(summary, language, if (lower.contains("ok") || lower.contains("thank")) AgentAction.END_CALL else AgentAction.RESPOND)
            }
            HrCallType.CUSTOMER_SUPPORT -> null
            HrCallType.OTHER -> {
                val faqs = session.hrScenario?.customFaqs.orEmpty()
                val match = faqs.firstOrNull { faq ->
                    val q = faq.question.lowercase()
                    lower.contains(q) || q.split(" ").count { it.length > 3 && lower.contains(it) } >= 2
                }
                if (match != null) {
                    AgentResponse(match.answer, language)
                } else if (faqs.isNotEmpty()) {
                    AgentResponse(faqs.first().answer, language)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private suspend fun maybeLearnNameFromConversation(session: CallSession, userMessage: String, language: Language) {
        if (!session.contactProfile?.name.isNullOrBlank()) return
        val extracted = extractName(userMessage)?.takeIf {
            it.length in 2..60 && !it.contains("present", ignoreCase = true) && !it.contains("absent", ignoreCase = true)
        } ?: return
        val saved = trainingService.upsertContactProfile(
            ContactProfile(
                phoneNumber = session.to,
                name = transliterateToEnglish(extracted, language).take(80),
                preferredLanguage = language.bcp47Code,
                lastCallSummary = "Name learned during call"
            )
        )
        session.contactProfile = saved
    }

    private val yesSignals = listOf(
        "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "interested", "done",
        "interest", "intrested", "intrest", "interested in this", "i am interested",
        "i'm interested", "im interested", "yes i am interested", "i am intrested",
        "interseed", "intersted",
        "agree", "i agree", "consent", "i consent", "allow", "you can save",
        "haan", "han", "haa", "ha", "haanji", "ji", "bilkul", "thik hai", "theek hai"
    )

    private val noSignals = listOf(
        "no", "nope", "not interested", "do not", "don't", "dont", "not now",
        "nahi", "nahin", "na", "mat", "mana hai"
    )

    private fun extractEmail(text: String): String? {
        val normalized = text.lowercase()
            .replace(",", " ")
            .replace(";", " ")
            .replace(":", " ")
            .replace(" at the rate ", " @ ")
            .replace(" at rate ", " @ ")
            .replace(" at ", " @ ")
            .replace(" dot ", " . ")
            .replace(" underscore ", " _ ")
            .replace(" hyphen ", " - ")
            .replace(" dash ", " - ")
            .replace(" plus ", " + ")
            .replace(" space ", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(" ", "")
        val regex = Regex("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")
        return regex.find(normalized)?.value
    }

    private suspend fun evaluateWithLlm(prompt: String): String {
        return runCatching {
            kotlinx.coroutines.withTimeoutOrNull(3500L) {
                llmClient.complete(prompt, maxTokens = 150).trim()
            } ?: ""
        }.getOrDefault("").replace("\"", "")
    }

    private suspend fun transliterateToEnglish(text: String, language: Language): String {
        if (language == Language.ENGLISH) return text
        if (text.isBlank()) return text
        val prompt = "Transliterate the following text into the English alphabet (Latin/Roman script). Do NOT translate the meaning, just write the exact spoken words using English letters. For example, if the text is in Hindi script, write it in Hinglish. Return ONLY the transliterated text, no extra words, no quotes, or markdown. Under no circumstances should you apologize or say you cannot translate.\n\nTEXT: $text"
        val transl = evaluateWithLlm(prompt)
        return transl.ifBlank { text }
    }

    private suspend fun extractEmailRobustly(text: String): String? {
        val basic = extractEmail(text)
        if (basic != null) return basic
        if (text.length < 5) return null
        
        val prompt = "Extract the email address from the following text. The user may have spoken it slowly with spaces or words like 'dot' or 'at'. For example, 'i s h a n a t g m a i l d o t c o m' -> ishan@gmail.com. If no email is found, return the word NONE.\n\nTEXT: $text\n\nReturn EXACTLY the email address in standard format, or NONE."
        val result = evaluateWithLlm(prompt).lowercase()
        if (result.contains("none") || !result.contains("@")) return null
        val tokens = result.split(Regex("\\s+"))
        return tokens.firstOrNull { it.contains("@") && it.contains(".") }
    }

    private fun extractName(text: String): String? {
        val cleaned = text.trim()
            .replace(Regex("(?i)^(my name is|i am|this is|main|mera naam)\\s+"), "")
            .trim()
        return cleaned.takeIf { it.length >= 2 }
    }

    private fun extractRole(text: String): String? {
        val lower = text.lowercase()
        return when {
            lower.contains("android") -> "Android Developer"
            lower.contains("ios") || lower.contains("iphone") -> "iOS Developer"
            lower.contains("backend") || lower.contains("server") -> "Backend Engineer"
            lower.contains("qa") || lower.contains("testing") || lower.contains("tester") -> "QA Engineer"
            else -> null
        }
    }

    private fun hrQuestion1(role: String?): String {
        val normalized = role?.lowercase().orEmpty()
        return when {
            normalized.contains("android") ->
                "Basic question 1: In Android, how do you manage UI state across configuration changes?"
            normalized.contains("ios") ->
                "Basic question 1: In iOS, what is the difference between UIKit lifecycle and SwiftUI state handling?"
            normalized.contains("backend") ->
                "Basic question 1: How do you design a reliable REST API with proper error handling?"
            normalized.contains("qa") ->
                "Basic question 1: How do you build a balanced test strategy for functional and regression testing?"
            else ->
                "Basic question 1: What skills make you suitable for this role?"
        }
    }

    private fun hrQuestion2(role: String?): String {
        val normalized = role?.lowercase().orEmpty()
        return when {
            normalized.contains("android") ->
                "Basic question 2: Explain your experience with Kotlin, Jetpack Compose, and API integration."
            normalized.contains("ios") ->
                "Basic question 2: Explain your experience with Swift, architecture patterns, and API integration."
            normalized.contains("backend") ->
                "Basic question 2: How do you handle database performance and scalability in production?"
            normalized.contains("qa") ->
                "Basic question 2: Which automation tools have you used and how did they improve release quality?"
            else ->
                "Basic question 2: Describe one project where you solved a difficult technical problem."
        }
    }

    private fun looksUnusable(text: String, language: Language): Boolean {
        if (text.isBlank()) return true
        if (text.length > 420) return true

        val normalized = text.lowercase()
        val suspiciousPatterns = listOf(
            "## step",
            "step 1:",
            "percentage =",
            "decimal =",
            "to convert",
            "price =",
            "<think",
            "</think>",
            "sorry, there was an issue",
            "please try again",
            "main madad kar sakti hoon",
            "i can help with that"
        )

        if (suspiciousPatterns.any { normalized.contains(it) }) return true
        if (normalized.contains("[plan name]") || normalized.contains("[price]") || normalized.contains("[key benefit]")) return true
        if (normalized.contains("{{") || normalized.contains("}}")) return true
        if (Regex("\\[[a-z][^\\]]{1,30}\\]", RegexOption.IGNORE_CASE).containsMatchIn(text)) return true
        if (text.startsWith(".") || text.startsWith("#")) return true

        val devanagariCount = text.count { it.code in 0x0900..0x097F }
        if (language == Language.HINDI && devanagariCount == 0 && normalized.contains("step")) return true

        return false
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun defaultGreeting(language: Language, agentName: String): String {
        return when (language) {
            Language.HINDI -> "नमस्ते! मैं $agentName हूं। मैं आपकी कैसे मदद कर सकता हूं?"
            Language.TAMIL -> "வணக்கம்! நான் $agentName. உங்களுக்கு எப்படி உதவலாம்?"
            Language.TELUGU -> "నమస్కారం! నేను $agentName. మీకు ఎలా సహాయం చేయగలను?"
            Language.GUJARATI -> "નમસ્તે! હું $agentName છું. હું તમારી કેવી રીતે મદદ કરી શકું?"
            else -> "Hello! This is $agentName. How can I help you today?"
        }
    }

    private fun recordHistory(session: CallSession) {
        callHistory.removeAll { it.callSid == session.id }
        callHistory.add(
            0,
            CallHistoryItem(
                callSid = session.id,
                from = session.from,
                to = session.to,
                status = session.status.name.lowercase(),
                direction = session.direction.name.lowercase(),
                language = session.detectedLanguage.bcp47Code,
                durationSeconds = ((session.endedAt ?: System.currentTimeMillis()) - session.startedAt).div(1000).toInt(),
                startedAt = session.startedAt,
                endedAt = session.endedAt
            )
        )
        if (callHistory.size > 50) {
            callHistory.removeAt(callHistory.lastIndex)
        }
    }
}
