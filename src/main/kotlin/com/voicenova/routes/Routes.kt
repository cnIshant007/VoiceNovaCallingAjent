package com.voicenova.routes

import com.voicenova.ai.LLMClient
import com.voicenova.ai.TTSClient
import com.voicenova.config.AppConfig
import com.voicenova.models.*
import com.voicenova.services.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

private fun Parameters.twilioDebugDetails(): Map<String, String> {
    val interestingKeys = listOf(
        "CallSid",
        "CallStatus",
        "From",
        "To",
        "Direction",
        "ApiVersion",
        "CallerName",
        "CallDuration",
        "AnsweredBy",
        "ErrorCode",
        "ErrorMessage",
        "SipResponseCode",
        "AccountSid",
        "CallbackSource",
        "SequenceNumber",
        "LanguageCode",
        "Confidence"
    )
    return interestingKeys.mapNotNull { key ->
        this[key]?.takeIf { it.isNotBlank() }?.let { key to it.take(220) }
    }.toMap()
}

private fun twilioSafeRetryTwiml(
    prompt: String = "Sorry, there was a temporary issue. Please say that again."
): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <Response>
        <Say>$prompt</Say>
        <Gather input="speech" speechTimeout="auto" action="/webhooks/twilio/transcription" method="POST" actionOnEmptyResult="true">
            <Say>Please tell me how I can help.</Say>
        </Gather>
    </Response>
""".trimIndent()

fun Route.twilioRoutes(callService: CallService, hrCallService: HrCallService) {
    // ── Inbound call arrives ──────────────────────────────────────────────────
    post("/twilio/inbound") {
        val params = call.receiveParameters()
        val callSid = params["CallSid"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing CallSid")
            return@post
        }
        val from = params["From"] ?: ""
        val to = params["To"] ?: ""
        log.info { "Inbound call: $callSid from $from to $to details=${params.twilioDebugDetails()}" }

        try {
            val session = callService.getSession(callSid) ?: callService.createSession(
                callSid = callSid,
                from = from,
                to = to,
                direction = CallDirection.INBOUND
            )
            callService.addDebugEvent(
                callSid,
                stage = "twilio_inbound",
                message = "Inbound webhook received",
                details = params.twilioDebugDetails()
            )
            val twiml = callService.buildWelcomeTwiml(session)
            log.debug { "[$callSid] Returning welcome TwiML (${twiml.length} chars)" }
            call.respondText(twiml, ContentType.Application.Xml)
        } catch (e: Exception) {
            callService.addDebugEvent(
                callSid,
                stage = "twilio_error",
                message = "Inbound webhook failed. Returning safe fallback TwiML.",
                details = mapOf("error" to (e.message ?: e::class.simpleName.orEmpty()))
            )
            log.error(e) { "[$callSid] Inbound webhook failed. Returning safe fallback TwiML." }
            call.respondText(twilioSafeRetryTwiml(), ContentType.Application.Xml)
        }
    }

    // ── Caller spoke — Twilio sends transcription ─────────────────────────────
    post("/twilio/transcription") {
        val params = call.receiveParameters()
        val callSid = params["CallSid"] ?: run {
            log.warn { "Transcription webhook missing CallSid. Returning safe retry TwiML." }
            call.respondText(
                twilioSafeRetryTwiml("Sorry, I could not identify this call. Please say that again."),
                ContentType.Application.Xml
            )
            return@post
        }
        val speechResult = params["SpeechResult"] ?: ""
        val languageCode = params["LanguageCode"]
        log.info {
            "[$callSid] Transcription webhook speech=\"${speechResult.take(220)}\" lang=$languageCode details=${params.twilioDebugDetails()}"
        }

        val session = callService.getSession(callSid) ?: run {
            // Recover automatically instead of redirect loop or hard failure.
            val from = params["From"] ?: ""
            val to = params["To"] ?: ""
            log.warn { "[$callSid] Session missing during transcription. Recreating session and continuing call." }
            callService.createSession(
                callSid = callSid,
                from = from,
                to = to,
                direction = CallDirection.INBOUND
            )
        }

        callService.addDebugEvent(
            callSid,
            stage = "twilio_transcription",
            message = "Speech transcription received",
            details = mapOf(
                "speech" to speechResult.take(220),
                "language_code" to (languageCode ?: "")
            )
        )

        if (speechResult.isBlank()) {
            log.warn { "[$callSid] Blank speech result from Twilio. Returning no-speech TwiML." }
            call.respondText(callService.buildNoSpeechTwiml(session), ContentType.Application.Xml)
            return@post
        }

        try {
            val agentResponse = callService.processMessage(session, speechResult, languageCode)
            val twiml = callService.buildResponseTwiml(session, agentResponse)
            log.info {
                "[$callSid] Agent action=${agentResponse.action.name.lowercase()} language=${agentResponse.language.bcp47Code} reply_preview=${agentResponse.text.take(180)}"
            }
            call.respondText(twiml, ContentType.Application.Xml)
        } catch (e: Exception) {
            callService.addDebugEvent(
                callSid,
                stage = "twilio_error",
                message = "Transcription webhook failed. Returning safe fallback TwiML.",
                details = mapOf("error" to (e.message ?: e::class.simpleName.orEmpty()))
            )
            log.error(e) { "[$callSid] Transcription webhook failed. Returning safe fallback TwiML." }
            call.respondText(twilioSafeRetryTwiml(), ContentType.Application.Xml)
        }
    }

    post("/twilio/outbound") {
        val params = call.receiveParameters()
        val callSid = params["CallSid"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing CallSid")
            return@post
        }
        val from = params["From"] ?: ""
        val to = params["To"] ?: ""

        val session = callService.getSession(callSid) ?: callService.createSession(
            callSid = callSid,
            from = from,
            to = to,
            direction = CallDirection.OUTBOUND
        )

        val twiml = callService.buildWelcomeTwiml(session)
        call.respondText(twiml, ContentType.Application.Xml)
    }

    // ── Call status update (ringing, in-progress, completed, etc.) ───────────
    post("/twilio/status") {
        val params = call.receiveParameters()
        val callSid = params["CallSid"] ?: return@post
        val status = params["CallStatus"] ?: return@post
        val statusDetails = params.twilioDebugDetails()
        log.info { "[$callSid] Status callback: $status details=$statusDetails" }

        val mappedStatus = when (status.lowercase()) {
            "queued", "ringing", "in-progress" -> CallStatus.ACTIVE
            "busy" -> CallStatus.BUSY
            "no-answer" -> CallStatus.NO_ANSWER
            "failed", "canceled" -> CallStatus.FAILED
            "completed" -> CallStatus.COMPLETED
            else -> CallStatus.ACTIVE
        }
        callService.updateSessionStatus(callSid, mappedStatus)
        hrCallService.updateCallStatus(callSid, mappedStatus.name.lowercase())
        callService.addDebugEvent(
            callSid,
            stage = "twilio_status",
            message = "Twilio status callback received",
            details = statusDetails
        )

        if (mappedStatus in listOf(CallStatus.BUSY, CallStatus.NO_ANSWER, CallStatus.FAILED)) {
            log.warn { "[$callSid] Call entered terminal failure state: $mappedStatus details=$statusDetails" }
        }

        if (status in listOf("completed", "busy", "no-answer", "failed", "canceled")) {
            callService.endSession(callSid)
        }
        call.respond(HttpStatusCode.OK, "")
    }

    post("/twilio/conference/join") {
        val conferenceName = call.request.queryParameters["conferenceName"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing conferenceName")
            return@post
        }
        val participantLabel = call.request.queryParameters["participantLabel"] ?: "participant"
        val muted = call.request.queryParameters["muted"]?.toBooleanStrictOrNull() ?: false
        val announceTransfer = call.request.queryParameters["announceTransfer"]?.toBooleanStrictOrNull() ?: false
        val announcement = if (participantLabel == "customer" && announceTransfer) {
            "<Say language=\"en-IN\">Connecting you to a live expert now.</Say>"
        } else {
            ""
        }

        call.respondText(
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    $announcement
                    <Dial>
                        <Conference
                            beep="false"
                            startConferenceOnEnter="true"
                            endConferenceOnExit="false"
                            muted="$muted"
                            participantLabel="$participantLabel"
                            statusCallback="/webhooks/twilio/conference/status"
                            statusCallbackMethod="POST"
                            statusCallbackEvent="start end join leave mute hold modify speaker announcement">
                            $conferenceName
                        </Conference>
                    </Dial>
                </Response>
            """.trimIndent(),
            ContentType.Application.Xml
        )
    }

    post("/twilio/conference/status") {
        val params = call.receiveParameters()
        val conferenceName = params["FriendlyName"] ?: params["ConferenceFriendlyName"] ?: ""
        val callSid = params["CallSid"] ?: ""
        val event = params["StatusCallbackEvent"] ?: params["SequenceNumber"] ?: "conference_update"
        if (callSid.isNotBlank()) {
            callService.addDebugEvent(
                callSid,
                stage = "twilio_conference",
                message = "Twilio conference callback received",
                details = mapOf(
                    "conference" to conferenceName,
                    "event" to event,
                    "participant_label" to (params["ParticipantLabel"] ?: "")
                )
            )
        }
        call.respond(HttpStatusCode.OK, "")
    }
}

fun Route.plivoRoutes(callService: CallService, plivoService: PlivoService, hrCallService: HrCallService) {
    post("/plivo/inbound") {
        val params = call.receiveParameters()
        val callUuid = params["CallUUID"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing CallUUID")
            return@post
        }
        val requestUuid = params["RequestUUID"]
        val from = params["From"] ?: ""
        val to = params["To"] ?: ""

        plivoService.registerCallUuid(requestUuid, callUuid)
        if (!requestUuid.isNullOrBlank() && requestUuid != callUuid) {
            callService.adoptSessionId(requestUuid, callUuid)
        }

        val session = callService.getSession(callUuid) ?: callService.createSession(
            callSid = callUuid,
            from = from,
            to = to,
            direction = CallDirection.INBOUND
        )
        callService.addDebugEvent(
            callUuid,
            stage = "plivo_inbound",
            message = "Plivo inbound/answer webhook received",
            details = mapOf(
                "from" to from,
                "to" to to,
                "request_uuid" to (requestUuid ?: "")
            )
        )
        call.respondText(callService.buildPlivoWelcomeXml(session), ContentType.Application.Xml)
    }

    post("/plivo/input") {
        val params = call.receiveParameters()
        val callUuid = params["CallUUID"] ?: params["RequestUUID"] ?: return@post
        val requestUuid = params["RequestUUID"]
        val languageCode = params["Language"] ?: params["language"]
        val speechResult = params["Speech"]
            ?: params["SpeechResult"]
            ?: params["speech"]
            ?: params["Digits"]
            ?: params["digits"]
            ?: ""

        plivoService.registerCallUuid(requestUuid, params["CallUUID"])
        if (!requestUuid.isNullOrBlank() && requestUuid != callUuid) {
            callService.adoptSessionId(requestUuid, callUuid)
        }

        val session = callService.getSession(callUuid) ?: run {
            call.respondText(
                """<?xml version="1.0" encoding="UTF-8"?><Response><Redirect method="POST">/webhooks/plivo/inbound</Redirect></Response>""",
                ContentType.Application.Xml
            )
            return@post
        }

        callService.addDebugEvent(
            callUuid,
            stage = "plivo_input",
            message = "Plivo speech input received",
            details = mapOf(
                "speech" to speechResult.take(220),
                "language_code" to (languageCode ?: "")
            )
        )

        if (speechResult.isBlank()) {
            call.respondText(callService.buildPlivoNoInputXml(session), ContentType.Application.Xml)
            return@post
        }

        val agentResponse = callService.processMessage(session, speechResult, languageCode)
        call.respondText(callService.buildPlivoResponseXml(session, agentResponse), ContentType.Application.Xml)
    }

    post("/plivo/status") {
        val params = call.receiveParameters()
        val callUuid = params["CallUUID"] ?: params["RequestUUID"] ?: return@post
        val requestUuid = params["RequestUUID"]
        val status = params["CallStatus"] ?: params["Event"] ?: return@post

        plivoService.registerCallUuid(requestUuid, params["CallUUID"])
        if (!requestUuid.isNullOrBlank() && requestUuid != callUuid) {
            callService.adoptSessionId(requestUuid, callUuid)
        }

        val mappedStatus = when (status.lowercase()) {
            "queued", "ringing", "in-progress" -> CallStatus.ACTIVE
            "busy" -> CallStatus.BUSY
            "no-answer", "timeout" -> CallStatus.NO_ANSWER
            "failed", "cancel", "canceled" -> CallStatus.FAILED
            "completed" -> CallStatus.COMPLETED
            else -> CallStatus.ACTIVE
        }

        callService.updateSessionStatus(callUuid, mappedStatus)
        hrCallService.updateCallStatus(callUuid, mappedStatus.name.lowercase())
        callService.addDebugEvent(
            callUuid,
            stage = "plivo_status",
            message = "Plivo status callback received",
            details = mapOf(
                "status" to status,
                "request_uuid" to (requestUuid ?: "")
            )
        )

        if (mappedStatus in listOf(CallStatus.COMPLETED, CallStatus.BUSY, CallStatus.NO_ANSWER, CallStatus.FAILED)) {
            callService.endSession(callUuid)
        }
        call.respond(HttpStatusCode.OK, "")
    }
}

fun Route.apiRoutes(
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
    route("/api/v1") {

        // ── Health check ──────────────────────────────────────────────────────
        get("/health") {
            call.respond(mapOf("status" to "ok", "version" to "1.0.0"))
        }

        // ── TEST ENDPOINTS (no auth needed in dev) ────────────────────────────
        route("/test") {

            // Start a new test conversation (no Twilio needed)
            post("/conversation") {
                val req = call.receive<StartConversationRequest>()
                val session = callService.createSession(
                    from = req.callerNumber,
                    to = "+911800000000",
                    direction = CallDirection.INBOUND,
                    initialLanguage = Language.fromBcp47(req.language)
                )
                val greeting = callService.getGreeting(session)
                call.respond(
                    StartConversationResponse(
                        sessionId = session.id,
                        agentGreeting = greeting,
                        detectedLanguage = session.detectedLanguage.bcp47Code,
                        agentName = session.agentConfig.agentName
                    )
                )
            }

            // Send a message to an existing test session
            post("/message") {
                val req = call.receive<SendMessageRequest>()
                val session = callService.getSession(req.sessionId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, "Session not found. Start a new one at /test/conversation")

                val response = callService.processMessage(session, req.message)
                call.respond(
                    ConversationResponse(
                        sessionId = req.sessionId,
                        agentReply = response.text,
                        detectedLanguage = response.language.bcp47Code,
                        intent = session.intentHistory.lastOrNull() ?: "UNKNOWN",
                        confidence = 0.92f
                    )
                )
            }

            // End a test session
            post("/end") {
                val body = call.receive<JsonObject>()
                val sessionId = body["session_id"]?.jsonPrimitive?.content
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing session_id")
                val score = body["quality_score"]?.jsonPrimitive?.floatOrNull ?: 4.0f
                callService.endSession(sessionId, qualityScore = score)
                call.respond(mapOf("status" to "ended", "session_id" to sessionId))
            }

            // Test language detection
            post("/detect-language") {
                val body = call.receive<JsonObject>()
                val text = body["text"]?.jsonPrimitive?.content
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing 'text'")
                val detected = languageService.detect(text)
                call.respond(
                    DetectLanguageResponse(
                        language = detected.bcp47Code,
                        name = detected.displayName,
                        confidence = 0.95
                    )
                )
            }

            // Check what context was loaded
            get("/context-summary") {
                call.respond(trainingService.getContextSummary())
            }
        }

        // ── TRAINING ENDPOINTS ────────────────────────────────────────────────
        route("/training") {

            // Hot-reload knowledge base
            post("/reload") {
                trainingService.reloadKnowledgeBase()
                val summary = trainingService.getContextSummary()
                call.respond(
                    ReloadKnowledgeResponse(
                        status = "reloaded",
                        chunksLoaded = summary.totalChunks,
                        documents = summary.documentsLoaded
                    )
                )
            }

            // Add text content via API
            post("/text") {
                val req = call.receive<TrainingUploadRequest>()
                trainingService.addTextContent(
                    category = req.category,
                    title = req.title,
                    content = req.content
                )
                call.respond(mapOf("status" to "added", "title" to req.title))
            }

            // View learned facts from calls
            get("/learned-facts") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val facts = trainingService.getLearnedFacts(limit)
                call.respond(facts)
            }

            // Approve a learned fact
            patch("/facts/{factId}") {
                val factId = call.parameters["factId"] ?: return@patch
                val body = call.receive<JsonObject>()
                val approved = body["approved"]?.jsonPrimitive?.boolean ?: true
                val success = trainingService.approveLearnedFact(factId)
                call.respond(mapOf("success" to success, "fact_id" to factId, "approved" to approved))
            }
        }

        route("/hr") {
            route("/contacts") {
                get {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                    call.respond(trainingService.listContacts(limit))
                }

                post {
                    val req = call.receive<ContactUpsertRequest>()
                    if (req.phoneNumber.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing phone_number"))
                        return@post
                    }
                    val saved = trainingService.upsertContactProfile(
                        ContactProfile(
                            phoneNumber = req.phoneNumber,
                            name = req.name,
                            email = req.email,
                            department = req.department,
                            designation = req.designation,
                            preferredLanguage = req.preferredLanguage,
                            company = req.company,
                            notes = req.notes,
                            tags = req.tags,
                            lastCallSummary = req.lastCallSummary,
                            lastAttendanceStatus = req.lastAttendanceStatus
                        )
                    )
                    call.respond(saved)
                }

                delete("/{phone}") {
                    val phone = call.parameters["phone"]
                    if (phone.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing phone parameter"))
                        return@delete
                    }
                    val success = trainingService.deleteContactProfile(phone)
                    if (success) call.respond(mapOf("success" to true))
                    else call.respond(HttpStatusCode.NotFound, mapOf("error" to "Contact not found"))
                }
            }

            get("/candidates") {
                val dateFrom = call.request.queryParameters["date_from"]
                val dateTo = call.request.queryParameters["date_to"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                val rows = trainingService.listHrCandidates(dateFrom = dateFrom, dateTo = dateTo, limit = limit)
                call.respond(rows)
            }

            delete("/candidates/{phone}") {
                val phone = call.parameters["phone"]
                if (phone.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing phone parameter"))
                    return@delete
                }
                val success = trainingService.deleteCandidateProfile(phone)
                if (success) {
                    call.respond(mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Candidate not found"))
                }
            }

            get("/calls/history") {
                val callType = call.request.queryParameters["call_type"]
                val dateFrom = call.request.queryParameters["date_from"]
                val dateTo = call.request.queryParameters["date_to"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                call.respond(
                    hrCallService.history(
                        callType = callType,
                        dateFrom = dateFrom,
                        dateTo = dateTo,
                        limit = limit
                    )
                )
            }

            post("/calls/batch") {
                val req = call.receive<HrBatchCallRequest>()
                val cleanedNumbers = req.phoneNumbers.map { it.trim() }.filter { it.isNotBlank() }
                if (cleanedNumbers.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, "Provide at least one phone number in phone_numbers.")
                    return@post
                }
                val callType = hrCallService.normalizeCallType(req.callType)
                val voiceBackend = voiceBackendService.describe()
                if (voiceBackend.voiceBackend == "grok_voice_agent") {
                    call.respond(
                        HttpStatusCode.NotImplemented,
                        mapOf(
                            "error" to voiceBackend.hint,
                            "voice_backend" to voiceBackend.voiceBackend,
                            "websocket_url" to (voiceBackend.websocketUrl ?: "")
                        )
                    )
                    return@post
                }

                val webhookBase = twilioService.getWebhookBaseUrl()
                val batchId = hrCallService.createBatch(callType, req.notes)
                val calls = mutableListOf<HrBatchCallItemResponse>()
                var created = 0
                var failed = 0

                cleanedNumbers.forEach { rawNumber ->
                    try {
                        val normalizedTo = if (voiceBackend.voiceBackend == "plivo") {
                            plivoService.normalizePhoneNumber(rawNumber)
                        } else {
                            twilioService.normalizePhoneNumber(rawNumber)
                        }
                        val callSid = if (voiceBackend.voiceBackend == "plivo") {
                            plivoService.makeOutboundCall(normalizedTo, webhookBase)
                        } else {
                            twilioService.makeOutboundCall(normalizedTo, webhookBase)
                        }
                        callService.createSession(
                            callSid = callSid,
                            from = if (voiceBackend.voiceBackend == "plivo") plivoService.fromNumber() else twilioService.fromNumber(),
                            to = normalizedTo,
                            direction = CallDirection.OUTBOUND,
                            initialLanguage = Language.fromBcp47(req.language),
                            hrCallType = callType,
                            contactProfile = trainingService.getContactByPhone(normalizedTo),
                            hrScenario = req.scenario ?: HrCallScenario(callType = callType, notes = req.notes)
                        )
                        hrCallService.registerCall(
                            callSid = callSid,
                            phoneNumber = normalizedTo,
                            callType = callType,
                            notes = req.scenario?.notes ?: req.notes,
                            batchId = batchId
                        )
                        callService.addDebugEvent(
                            callSid,
                            stage = "hr_batch_call_requested",
                            message = "HR batch call requested via admin/API",
                            details = mapOf(
                                "call_type" to callType.name,
                                "batch_id" to batchId,
                                "to" to normalizedTo
                            )
                        )
                        calls.add(
                            HrBatchCallItemResponse(
                                phoneNumber = normalizedTo,
                                callSid = callSid,
                                status = "initiated"
                            )
                        )
                        created += 1
                    } catch (e: Exception) {
                        failed += 1
                        calls.add(
                            HrBatchCallItemResponse(
                                phoneNumber = rawNumber,
                                callSid = "",
                                status = "failed"
                            )
                        )
                    }
                }

                call.respond(
                    HrBatchCallResponse(
                        batchId = batchId,
                        callType = callType.name,
                        total = cleanedNumbers.size,
                        created = created,
                        failed = failed,
                        calls = calls
                    )
                )
            }
        }

        // ── STATUS ───────────────────────────────────────────────────────────
        get("/status") {
            val webhookBase = twilioService.getWebhookBaseUrl()
            val llmStatus = llmClient.describe()
            val aiMode = when {
                llmStatus.provider == "local" -> "local"
                !llmStatus.ready -> "mock"
                else -> "cloud"
            }
            val voiceBackend = voiceBackendService.describe()
            val ttsStatus = ttsClient.describe()
            val inboundWebhook = if (voiceBackend.voiceBackend == "plivo") {
                "$webhookBase/webhooks/plivo/inbound"
            } else {
                "$webhookBase/webhooks/twilio/inbound"
            }
            val inboundSetupHint = if (voiceBackend.voiceBackend == "plivo") {
                "Point your Plivo number or Plivo application Answer URL at the inbound webhook. Also set the Hangup URL to $webhookBase/webhooks/plivo/status."
            } else if (twilioService.isMockMode) {
                "Connect a public webhook and a Twilio voice number to let the agent answer live inbound calls."
            } else {
                "Point your Twilio number at the inbound webhook. If callers should reach the agent through your existing personal or business number, forward that number to the Twilio line."
            }
            call.respond(
                BackendStatusResponse(
                    twilioMockMode = twilioService.isMockMode,
                    webhookBaseUrl = webhookBase,
                    twilioInboundWebhook = inboundWebhook,
                    personalNumberRequiresForwarding = !twilioService.isMockMode,
                    inboundSetupHint = inboundSetupHint,
                    aiMode = aiMode,
                    llmProvider = llmStatus.provider,
                    llmModel = llmStatus.model,
                    llmApiUrl = llmStatus.apiUrl,
                    llmApiFormat = llmStatus.apiFormat,
                    availableLlmProviders = llmStatus.availableProviders,
                    llmConfigured = llmStatus.configured,
                    llmReady = llmStatus.ready,
                    llmHint = llmStatus.hint,
                    autoAnswerInbound = twilioService.isMockMode || config.publicBaseUrl.isNotBlank(),
                    callControlsMode = if (twilioService.isMockMode) "preview" else "pstn",
                    selectedVoiceBackend = voiceBackend.voiceBackend,
                    availableVoiceBackends = voiceBackendService.availableModes(),
                    voiceBackendConfigured = voiceBackend.configured,
                    voiceBackendReady = voiceBackend.ready,
                    voiceBackendHint = voiceBackend.hint,
                    voiceBackendWebsocketUrl = voiceBackend.websocketUrl,
                    selectedTtsProvider = ttsStatus.provider,
                    availableTtsProviders = ttsClient.availableProviders(),
                    selectedPiperVoice = ttsStatus.selectedPiperVoice,
                    availablePiperVoices = ttsStatus.availablePiperVoices.map {
                        PiperVoiceOptionResponse(id = it.id, label = it.label, gender = it.gender)
                    },
                    ttsConfigured = ttsStatus.configured,
                    ttsReady = ttsStatus.ready,
                    ttsHint = ttsStatus.hint
                )
            )
        }

        route("/system") {
            get("/llm") {
                val status = llmClient.describe()
                call.respond(
                    LlmSettingsResponse(
                        llmProvider = status.provider,
                        llmModel = status.model,
                        llmApiUrl = status.apiUrl,
                        llmApiFormat = status.apiFormat,
                        availableLlmProviders = status.availableProviders,
                        availableLocalProfiles = status.availableLocalProfiles,
                        selectedLocalProfileId = status.selectedLocalProfileId,
                        configured = status.configured,
                        ready = status.ready,
                        hint = status.hint
                    )
                )
            }

            get("/llm/profiles") {
                call.respond(llmClient.availableLocalProfiles())
            }

            post("/llm") {
                val req = call.receive<UpdateLlmSettingsRequest>()
                val status = llmClient.updateSettings(
                    profileIdRaw = req.llmProfileId,
                    providerRaw = req.llmProvider,
                    modelRaw = req.llmModel,
                    apiUrlRaw = req.llmApiUrl,
                    apiFormatRaw = req.llmApiFormat,
                    apiKeyRaw = req.llmApiKey
                )
                call.respond(
                    LlmSettingsResponse(
                        llmProvider = status.provider,
                        llmModel = status.model,
                        llmApiUrl = status.apiUrl,
                        llmApiFormat = status.apiFormat,
                        availableLlmProviders = status.availableProviders,
                        availableLocalProfiles = status.availableLocalProfiles,
                        selectedLocalProfileId = status.selectedLocalProfileId,
                        configured = status.configured,
                        ready = status.ready,
                        hint = status.hint
                    )
                )
            }

            post("/llm/test") {
                val req = call.receive<LlmTestRequest>()
                if (req.prompt.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Prompt is required.")
                    return@post
                }
                val status = llmClient.describe()
                val reply = llmClient.complete(req.prompt, maxTokens = req.maxTokens.coerceIn(32, 512))
                call.respond(
                    LlmTestResponse(
                        reply = reply,
                        llmProvider = status.provider,
                        llmModel = status.model,
                        llmApiUrl = status.apiUrl
                    )
                )
            }

            get("/voice-backend") {
                call.respond(voiceBackendService.describe())
            }

            post("/voice-backend") {
                val req = call.receive<UpdateVoiceBackendRequest>()
                call.respond(voiceBackendService.updateMode(req.voiceBackend))
            }

            get("/tts-provider") {
                val status = ttsClient.describe()
                call.respond(
                    TtsProviderResponse(
                        ttsProvider = status.provider,
                        selectedPiperVoice = status.selectedPiperVoice,
                        availablePiperVoices = status.availablePiperVoices.map {
                            PiperVoiceOptionResponse(id = it.id, label = it.label, gender = it.gender)
                        },
                        configured = status.configured,
                        ready = status.ready,
                        hint = status.hint
                    )
                )
            }

            post("/tts-provider") {
                val req = call.receive<UpdateTtsProviderRequest>()
                val status = ttsClient.updateProvider(req.ttsProvider, req.piperVoice)
                call.respond(
                    TtsProviderResponse(
                        ttsProvider = status.provider,
                        selectedPiperVoice = status.selectedPiperVoice,
                        availablePiperVoices = status.availablePiperVoices.map {
                            PiperVoiceOptionResponse(id = it.id, label = it.label, gender = it.gender)
                        },
                        configured = status.configured,
                        ready = status.ready,
                        hint = status.hint
                    )
                )
            }
        }

        get("/analytics") {
            call.respond(callService.currentAnalytics())
        }

        get("/calls/history") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            call.respond(callService.getRecentHistory(limit))
        }

        get("/calls/{callSid}/debug") {
            val callSid = call.parameters["callSid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing callSid")
            val debug = callService.getCallDebug(callSid)
                ?: return@get call.respond(HttpStatusCode.NotFound, "No debug data found for $callSid")
            call.respond(debug)
        }

        // ── OUTBOUND CALL ─────────────────────────────────────────────────────
        post("/calls/outbound") {
            val req = call.receive<OutboundCallRequest>()
            if (req.to.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing 'to' phone number")
                return@post
            }

            val voiceBackend = voiceBackendService.describe()
            if (voiceBackend.voiceBackend == "grok_voice_agent") {
                call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf(
                        "error" to voiceBackend.hint,
                        "voice_backend" to voiceBackend.voiceBackend,
                        "websocket_url" to (voiceBackend.websocketUrl ?: "")
                    )
                )
                return@post
            }

            try {
                val webhookBase = twilioService.getWebhookBaseUrl()
                val normalizedTo = if (voiceBackend.voiceBackend == "plivo") {
                    plivoService.normalizePhoneNumber(req.to)
                } else {
                    twilioService.normalizePhoneNumber(req.to)
                }
                val callSid = if (voiceBackend.voiceBackend == "plivo") {
                    plivoService.makeOutboundCall(normalizedTo, webhookBase)
                } else {
                    twilioService.makeOutboundCall(normalizedTo, webhookBase)
                }
                val hrCallType = req.hrCallType?.let { hrCallService.normalizeCallType(it) }
                val contactProfile = trainingService.upsertContactProfile(
                    ContactProfile(
                        phoneNumber = normalizedTo,
                        name = req.contactName,
                        email = req.contactEmail,
                        department = req.contactDepartment,
                        designation = req.contactDesignation,
                        preferredLanguage = req.language,
                        notes = req.contactNotes,
                        tags = req.contactTags
                    )
                )
                log.info {
                    "Outbound ${voiceBackend.voiceBackend} call requested to $normalizedTo using webhookBase=$webhookBase and sid=$callSid"
                }
                callService.createSession(
                    callSid = callSid,
                    from = if (voiceBackend.voiceBackend == "plivo") plivoService.fromNumber() else twilioService.fromNumber(),
                    to = normalizedTo,
                    direction = CallDirection.OUTBOUND,
                    initialLanguage = Language.fromBcp47(req.language),
                    hrCallType = hrCallType,
                    contactProfile = contactProfile,
                    hrScenario = req.scenario ?: hrCallType?.let { HrCallScenario(callType = it, notes = req.hrNotes) }
                )
                if (hrCallType != null) {
                    hrCallService.registerCall(
                        callSid = callSid,
                        phoneNumber = normalizedTo,
                        callType = hrCallType,
                        notes = req.scenario?.notes ?: req.hrNotes,
                        batchId = null
                    )
                }
                callService.addDebugEvent(
                    callSid,
                    stage = "outbound_requested",
                    message = "Outbound call requested via admin/API",
                    details = mapOf(
                        "provider" to if (voiceBackend.voiceBackend == "plivo") "plivo" else "twilio",
                        "to" to normalizedTo,
                        "webhook_base" to webhookBase,
                        "hr_call_type" to (hrCallType?.name ?: "")
                    )
                )

                call.respond(
                    OutboundCallResponse(
                        callSid = callSid,
                        status = if (voiceBackend.voiceBackend == "twilio_native" && twilioService.isMockMode) "mock_initiated" else "initiated",
                        to = normalizedTo,
                        from = "VoiceNova",
                        provider = if (voiceBackend.voiceBackend == "plivo") "plivo" else "twilio"
                    )
                )
            } catch (e: Exception) {
                log.error(e) { "Outbound call failed" }
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to (e.message ?: "Could not start outbound call"))
                )
            }
        }

        get("/calls/{callSid}/status") {
            val callSid = call.parameters["callSid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing callSid")
            val status = callService.getSessionStatus(callSid)?.name?.lowercase() ?: "unknown"
            call.respond(
                CallStatusResponse(
                    callSid = callSid,
                    status = status,
                    active = status == "active"
                )
            )
        }

        post("/calls/end") {
            val req = call.receive<EndCallRequest>()
            val voiceBackend = voiceBackendService.describe()
            val ended = if (voiceBackend.voiceBackend == "plivo") {
                plivoService.endCall(req.callSid)
            } else {
                twilioService.endCall(req.callSid)
            }
            if (ended) {
                callService.endSession(req.callSid)
            }
            call.respond(
                EndCallResponse(
                    callSid = req.callSid,
                    ended = ended,
                    status = if (ended) "completed" else "active"
                )
            )
        }

        post("/calls/expert-connect") {
            val req = call.receive<AssistCallRequest>()
            val voiceBackend = voiceBackendService.describe()
            if (voiceBackend.voiceBackend != "twilio_native") {
                call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf("error" to "Expert join is currently available only for the Twilio voice backend.")
                )
                return@post
            }

            val session = callService.getSession(req.callSid)
                ?: return@post call.respond(HttpStatusCode.NotFound, "No active call found for ${req.callSid}")
            val conferenceName = callService.getOrCreateConferenceName(req.callSid)
            val webhookBase = twilioService.getWebhookBaseUrl()
            val redirected = twilioService.redirectCallToConference(session.id, webhookBase, conferenceName)
            if (!redirected) {
                call.respond(HttpStatusCode.BadGateway, "Could not move the caller into the expert conference.")
                return@post
            }

            val participantSid = twilioService.addConferenceParticipant(
                to = req.phoneNumber,
                webhookBaseUrl = webhookBase,
                conferenceName = conferenceName,
                participantLabel = "expert-${System.currentTimeMillis()}",
                muted = false
            )
            callService.addDebugEvent(
                session.id,
                stage = "expert_join",
                message = "Expert participant added to live call",
                details = mapOf("phone_number" to req.phoneNumber, "conference" to conferenceName)
            )
            call.respond(
                AssistCallResponse(
                    callSid = session.id,
                    conferenceName = conferenceName,
                    participantCallSid = participantSid,
                    participantRole = "expert",
                    phoneNumber = req.phoneNumber,
                    status = "connecting"
                )
            )
        }

        post("/calls/monitor-connect") {
            val req = call.receive<AssistCallRequest>()
            val voiceBackend = voiceBackendService.describe()
            if (voiceBackend.voiceBackend != "twilio_native") {
                call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf("error" to "Listen-only monitoring is currently available only for the Twilio voice backend.")
                )
                return@post
            }

            val session = callService.getSession(req.callSid)
                ?: return@post call.respond(HttpStatusCode.NotFound, "No active call found for ${req.callSid}")
            val conferenceName = callService.getOrCreateConferenceName(req.callSid)
            val webhookBase = twilioService.getWebhookBaseUrl()
            val redirected = twilioService.redirectCallToConference(session.id, webhookBase, conferenceName)
            if (!redirected) {
                call.respond(HttpStatusCode.BadGateway, "Could not move the caller into the monitor conference.")
                return@post
            }

            val participantSid = twilioService.addConferenceParticipant(
                to = req.phoneNumber,
                webhookBaseUrl = webhookBase,
                conferenceName = conferenceName,
                participantLabel = "monitor-${System.currentTimeMillis()}",
                muted = true
            )
            callService.addDebugEvent(
                session.id,
                stage = "monitor_join",
                message = "Listen-only monitor participant added to live call",
                details = mapOf("phone_number" to req.phoneNumber, "conference" to conferenceName)
            )
            call.respond(
                AssistCallResponse(
                    callSid = session.id,
                    conferenceName = conferenceName,
                    participantCallSid = participantSid,
                    participantRole = "monitor",
                    phoneNumber = req.phoneNumber,
                    status = "connecting"
                )
            )
        }
    }
}
