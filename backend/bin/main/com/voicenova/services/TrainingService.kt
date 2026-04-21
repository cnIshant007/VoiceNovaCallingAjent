package com.voicenova.services

import com.voicenova.ai.LLMClient
import com.voicenova.config.AppConfig
import com.voicenova.models.*
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * TrainingService — loads context from files and enables self-learning from calls.
 *
 * How it works:
 * 1. On startup, reads all files in /knowledge folder
 * 2. Splits them into chunks (~500 words each)
 * 3. Stores chunks in memory (+ optionally PostgreSQL for persistence)
 * 4. At query time, does keyword search to find relevant chunks
 * 5. After each call, optionally extracts Q&A pairs for future use
 */
class TrainingService(
    private val config: AppConfig,
    private val llmClient: LLMClient
) {
    private val knowledgeChunks = mutableListOf<KnowledgeChunk>()
    private val learnedFacts = mutableListOf<LearnedFact>()
    private var agentConfig = AgentConfig.DEFAULT
    private val knowledgePath = File(config.knowledgePath)
    private val storagePath = File(config.storagePath)
    private val runtimePath = File(storagePath, "runtime")
    private val hrCandidatesFile = File(runtimePath, "hr-candidates.json")
    private val contactsFile = File(runtimePath, "contacts.json")
    private val hrOutboxPath = File(storagePath, "outbox")
    private val hrCandidates = mutableMapOf<String, HrCandidateProfile>()
    private val contacts = mutableMapOf<String, ContactProfile>()
    private val jsonCodec = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init {
        runtimePath.mkdirs()
        hrOutboxPath.mkdirs()
        loadHrCandidates()
        loadContacts()
        ensureHrCandidatesTable()
        ensureContactsTable()
    }

    // ── Startup: Load all context files ──────────────────────────────────────

    fun loadKnowledgeBase() {
        log.info { "Loading knowledge base from: ${knowledgePath.absolutePath}" }
        knowledgeChunks.clear()

        if (!knowledgePath.exists()) {
            log.warn { "Knowledge folder not found. Creating at: ${knowledgePath.absolutePath}" }
            knowledgePath.mkdirs()
            createDefaultKnowledge()
        }

        // Load agent-config.yaml if present
        val agentConfigFile = File(knowledgePath, "agent-config.yaml")
        if (agentConfigFile.exists()) {
            loadAgentConfig(agentConfigFile)
        }

        // Recursively load all knowledge files
        knowledgePath.walkTopDown()
            .filter { it.isFile && it.extension in listOf("md", "txt", "pdf") }
            .forEach { file ->
                try {
                    val category = file.parentFile.name
                    val content = readFile(file)
                    val chunks = chunkText(content, file.nameWithoutExtension, category)
                    knowledgeChunks.addAll(chunks)
                    log.info { "Loaded: ${file.path} → ${chunks.size} chunks" }
                } catch (e: Exception) {
                    log.warn { "Could not load ${file.path}: ${e.message}" }
                }
            }

        hrCandidates.values.forEach { profile ->
            learnCandidateProfile(profile)
        }
        contacts.values.forEach { profile ->
            learnContactProfile(profile)
        }

        log.info { "Knowledge base ready: ${knowledgeChunks.size} total chunks from ${knowledgePath.walkTopDown().count { it.isFile }} files" }
    }

    // ── Query: Find relevant context for a user message ──────────────────────

    fun retrieveContext(query: String, language: Language, maxChunks: Int = 5): String =
        selectRelevantChunks(query, language, maxChunks)
            .joinToString("\n\n---\n\n") { chunk ->
                "[${chunk.category.uppercase()} — ${chunk.source}]\n${chunk.content}"
            }

    fun buildPromptArtifacts(session: CallSession, userQuery: String): PromptArtifacts {
        val runtimeLlm = llmClient.describe()
        val modelLower = runtimeLlm.model.lowercase()
        val isSmallLocalModel = runtimeLlm.provider == "local" && (
            modelLower.contains("gemma") ||
                modelLower.contains("qwen") ||
                modelLower.contains("phi") ||
                modelLower.contains("llama3.2")
            )
        val relevantChunks = selectRelevantChunks(
            query = userQuery,
            language = session.detectedLanguage,
            maxChunks = if (isSmallLocalModel) 2 else if (session.history.size <= 2) 4 else 2
        )
            .filterNot { chunk ->
                // Long style-guide chunks can overwhelm very small local models.
                isSmallLocalModel && chunk.source.equals("human-interaction", ignoreCase = true)
            }
        val styleGuideChunks = knowledgeChunks
            .filter { it.source.equals("human-interaction", ignoreCase = true) }
            .take(1)
        val relevantContext = relevantChunks.joinToString("\n\n---\n\n") { chunk ->
            val chunkText = if (isSmallLocalModel) chunk.content.take(900) else chunk.content
            "[${chunk.category.uppercase()} — ${chunk.source}]\n$chunkText"
        }
        val styleContext = styleGuideChunks.joinToString("\n\n---\n\n") { chunk ->
            "[STYLE GUIDE — ${chunk.source}]\n${chunk.content}"
        }
        val callerInfo = session.callerData?.let {
            buildString {
                append("CALLER INFO:\n")
                it.name?.let { name -> append("- Name: $name\n") }
                it.plan?.let { plan -> append("- Plan: $plan\n") }
                it.notes?.let { notes -> append("- Notes: $notes\n") }
            }
        } ?: ""
        val knownContactInfo = session.contactProfile?.let {
            buildString {
                append("KNOWN CONTACT PROFILE:\n")
                it.name?.let { value -> append("- Name: $value\n") }
                it.department?.let { value -> append("- Department: $value\n") }
                it.designation?.let { value -> append("- Designation: $value\n") }
                it.notes?.let { value -> append("- Notes: $value\n") }
                if (it.tags.isNotEmpty()) append("- Tags: ${it.tags.joinToString(", ")}\n")
            }
        } ?: ""
        val scenarioInstructions = buildString {
            when (session.hrCallType) {
                HrCallType.CUSTOMER_SUPPORT -> {
                    append("CALL MODE: CUSTOMER SUPPORT\n")
                    append("→ You are Rehan Tumbi, a male company owner or senior operator.\n")
                    append("→ Talk naturally like a real person on a business call.\n")
                    append("→ The caller may ask any business or support question.\n")
                    append("→ Answer dynamically from the knowledge base and known context.\n")
                    append("→ If information is missing, say you will arrange a callback.\n\n")
                }
                HrCallType.SALARY -> {
                    append("CALL MODE: SALARY UPDATE ONLY\n")
                    append("→ Talk only about salary status.\n")
                    append("→ Do not start interview, attendance, policy, or support topics.\n")
                    append("→ If the caller asks unrelated questions, politely say this call is only for salary update.\n\n")
                }
                HrCallType.INTERVIEW -> {
                    append("CALL MODE: INTERVIEW\n")
                    append("→ Stay in interview flow only.\n")
                    append("→ Collect and ask interview details step by step.\n\n")
                }
                HrCallType.ATTENDANCE -> {
                    append("CALL MODE: ATTENDANCE ONLY\n")
                    append("→ Ask only for today's attendance and end after the status is captured.\n\n")
                }
                HrCallType.PRIVACY_POLICY_CHANGED -> {
                    append("CALL MODE: PRIVACY POLICY UPDATE\n")
                    append("→ Explain only the privacy-policy change and answer related follow-ups briefly.\n\n")
                }
                HrCallType.OTHER, null -> {
                    if (session.direction == CallDirection.OUTBOUND) {
                        append("CALL MODE: OWNER OUTBOUND\n")
                        append("→ You are Rehan Tumbi, a male company owner or senior operator.\n")
                        append("→ Sound natural, direct, and conversational.\n")
                        append("→ Answer dynamically based on what the user asks.\n\n")
                    }
                }
            }
        }

        val rules = if (session.agentConfig.callRules.isNotEmpty()) {
            "RULES:\n" + session.agentConfig.callRules.joinToString("\n") { "- $it" }
        } else ""

        val promptBase = session.cachedPromptBase ?: buildString {
            append("You are ${session.agentConfig.agentName}, ")
            append("a ${session.agentConfig.agentGender} AI voice assistant for ${session.agentConfig.companyName}.\n\n")

            if (session.agentConfig.personality.isNotEmpty()) {
                append("PERSONALITY: ${session.agentConfig.personality.joinToString(", ")}\n\n")
            }

            if (session.agentConfig.systemInstructions.isNotEmpty()) {
                append("INSTRUCTIONS:\n${session.agentConfig.systemInstructions}\n\n")
            }

            if (rules.isNotEmpty()) append("$rules\n\n")

            if (callerInfo.isNotEmpty()) append("$callerInfo\n")
            if (knownContactInfo.isNotEmpty()) append("$knownContactInfo\n")
            if (scenarioInstructions.isNotEmpty()) append("$scenarioInstructions")

            append("CURRENT LANGUAGE: ${session.detectedLanguage.displayName} (${session.detectedLanguage.bcp47Code})\n")
            append("→ Respond in the same language and style as the caller.\n")
            append("→ Prioritize the latest caller utterance first.\n")
            append("→ Use earlier turns only if the caller explicitly refers to previous context.\n")
            append("→ If the caller uses Hinglish, reply in natural Hinglish.\n")
            append("→ If the caller uses clear English, reply in simple natural English.\n")
            append("→ Do not switch languages unless the caller switches first.\n")
            append("→ Keep responses short and natural for voice — ideally 1-2 sentences, maximum 3.\n")
            append("→ Never mention that you are an AI unless directly asked.\n\n")
            if (isSmallLocalModel) {
                append("CONVERSATION STYLE (COMPACT):\n")
                append("→ Give one direct answer from snippets.\n")
                append("→ Keep it to 1-2 short sentences.\n")
                append("→ Do not give generic lines like 'main madad kar sakti hoon' without details.\n")
                append("→ Ask at most one follow-up question only if information is missing.\n")
                append("→ No bullets, no markdown, no long explanation.\n\n")
            } else {
                append("CONVERSATION STYLE:\n")
                append("→ Sound like a calm, helpful human phone agent, not a bot.\n")
                append("→ Do not repeat the same greeting, closing, or sentence pattern across turns.\n")
                append("→ Answer directly first, then ask at most one helpful follow-up question only if needed.\n")
                append("→ Use light empathy when appropriate, but avoid scripted phrases and avoid sounding salesy.\n")
                append("→ Do not echo the caller's full question back to them unless needed for confirmation.\n")
                append("→ Avoid stiff phrases like 'thank you for calling' unless it is the very first greeting.\n")
                append("→ Do not sound like a brochure, policy page, or FAQ document.\n")
                append("→ No bullet points, numbering, or markdown in the spoken answer.\n")
                append("→ Prefer warm spoken wording over formal written wording.\n\n")
            }

            if (!isSmallLocalModel && styleContext.isNotEmpty()) {
                append("VOICE STYLE GUIDE:\n")
                append(styleContext)
                append("\n\n")
            }

            append("→ Only use information from the knowledge base snippets provided with each turn. If not covered, say you'll check and offer a callback.\n")
        }.also { session.cachedPromptBase = it }

        val contextDigest = session.cachedContextDigest ?: knowledgeChunks
            .take(12)
            .joinToString(" | ") { "${it.category}/${it.source}" }
            .also { session.cachedContextDigest = it }

        return buildString {
            append(promptBase)

            if (relevantContext.isNotEmpty()) {
                append("\nKNOWLEDGE BASE SNIPPETS FOR THIS TURN:\n")
                append(relevantContext)
                append("\n")
            }

            append("\nSESSION CONTEXT DIGEST:\n")
            append(contextDigest)

            // Add recent learned facts
            val relevantFacts = learnedFacts
                .filter { it.approved || config.autoApproveLearning }
                .filter { fact -> userQuery.lowercase().let { q ->
                    fact.question.lowercase().split(" ").any { w -> q.contains(w) }
                }}
                .take(3)

            if (relevantFacts.isNotEmpty()) {
                append("\nRECENT SUCCESSFUL ANSWERS (from past calls):\n")
                relevantFacts.forEach { fact ->
                    append("Q: ${fact.question}\nA: ${fact.answer}\n\n")
                }
            }
        }.let { prompt ->
            PromptArtifacts(
                systemPrompt = prompt,
                contextSources = relevantChunks.map { "${it.category}/${it.source}" }.distinct(),
                contextSnippet = relevantContext.take(700)
            )
        }
    }

    // Build the full system prompt with context injected
    fun buildSystemPrompt(session: CallSession, userQuery: String): String =
        buildPromptArtifacts(session, userQuery).systemPrompt

    fun fastAnswer(userQuery: String, language: Language): String? {
        val lower = userQuery.lowercase()
        fun containsAny(vararg terms: String): Boolean = terms.any { lower.contains(it) }
        return when {
            containsAny("price", "pricing", "fees", "cost", "rate", "kitna", "कितना", "फीस", "चार्ज", "charges", "शुल्क") ->
                when (language) {
                    Language.HINDI, Language.NEPALI, Language.MARATHI ->
                        "Chaintech comparatively low transaction fees ke liye jaana jata hai. Agar aap chahen, main trading services ya IXFI token ke benefits bhi bata sakti hoon."
                    else ->
                        "Chaintech is known for comparatively low transaction fees. If you want, I can also explain its trading services or IXFI token benefits."
                }
            containsAny("ixfi", "token", "टोकन") ->
                when (language) {
                    Language.HINDI, Language.NEPALI, Language.MARATHI ->
                        "IXFI TOKEN Chaintech ecosystem ka native token hai. Iska main benefit reduced fees aur extra ecosystem benefits hai."
                    else ->
                        "IXFI TOKEN is Chaintech's native ecosystem token. Its main benefit is reduced fees and added ecosystem benefits."
                }
            containsAny("safe", "safety", "safu", "secure", "security", "सुरक्षा", "सेफ", "सेफ्टी") ->
                when (language) {
                    Language.HINDI, Language.NEPALI, Language.MARATHI ->
                        "Chaintech SAFU jaise safety measures use karta hai, jo user assets ko protect karne ke liye reserve fund hai."
                    else ->
                        "Chaintech uses safety measures such as SAFU, a reserve fund intended to help protect user assets."
                }
            containsAny(
                "job", "jobs", "hiring", "career", "android developer", "ios developer",
                "जॉब", "जॉब्स", "नौकरी", "भर्ती", "करियर", "रिक्रूट", "रिक्रूटमेंट", "hiring role"
            ) ->
                when (language) {
                    Language.HINDI, Language.NEPALI, Language.MARATHI ->
                        "Chaintech abhi Android Developer aur iOS Developer roles ke liye hiring mention karta hai. Agar aap chahen, main aapki interest note karke callback arrange kar sakti hoon."
                    else ->
                        "Chaintech is currently highlighting Android Developer and iOS Developer roles. If you want, I can note your interest for follow-up."
                }
            else -> null
        }
    }

    // ── Self-learning: Extract facts from completed calls ─────────────────────

    suspend fun learnFromCall(session: CallSession) {
        if (!config.enableSelfLearning) return
        val score = session.qualityScore ?: return
        if (score < config.minCallScoreForLearning) return

        log.info { "Learning from call ${session.id} (score: $score)" }

        val transcript = session.history.joinToString("\n") {
            "${it.role.uppercase()}: ${it.content}"
        }

        // Ask Claude to extract Q&A pairs from the transcript
        val extractionPrompt = """
            Extract 1-3 useful Q&A pairs from this call transcript that could help train a customer service agent.
            Only extract pairs where the agent gave a GOOD, CORRECT answer.
            Skip greetings, hold requests, and transfers.
            
            Format as JSON array:
            [{"question": "...", "answer": "...", "language": "hi-IN"}]
            
            Return ONLY the JSON, nothing else.
            
            TRANSCRIPT:
            $transcript
        """.trimIndent()

        try {
            val response = llmClient.complete(extractionPrompt, maxTokens = 500)
            val facts = Json.decodeFromString<List<Map<String, String>>>(response)

            facts.forEach { fact ->
                val langCode = fact["language"] ?: session.detectedLanguage.bcp47Code
                val language = Language.fromBcp47(langCode) ?: session.detectedLanguage
                learnedFacts.add(
                    LearnedFact(
                        id = "fact_${UUID.randomUUID().toString().take(8)}",
                        question = fact["question"] ?: return@forEach,
                        answer = fact["answer"] ?: return@forEach,
                        language = language,
                        callId = session.id,
                        approved = config.autoApproveLearning
                    )
                )
            }
            log.info { "Extracted ${facts.size} new facts from call ${session.id}" }
        } catch (e: Exception) {
            log.warn { "Could not extract facts from call: ${e.message}" }
        }
    }

    // ── Hot-reload: Add new content without restart ───────────────────────────

    fun reloadKnowledgeBase() {
        log.info { "Hot-reloading knowledge base..." }
        loadKnowledgeBase()
    }

    fun addTextContent(category: String, title: String, content: String) {
        val chunks = chunkText(content, title, category)
        knowledgeChunks.addAll(chunks)
        log.info { "Added $title to knowledge base (${chunks.size} chunks)" }
    }

    fun approveLearnedFact(factId: String): Boolean {
        val fact = learnedFacts.find { it.id == factId } ?: return false
        learnedFacts.remove(fact)
        learnedFacts.add(fact.copy(approved = true))
        return true
    }

    // ── Context summary for testing ───────────────────────────────────────────

    fun getContextSummary(): ContextSummaryResponse {
        val topics = knowledgeChunks
            .flatMap { chunk -> chunk.content.lines().take(3) }
            .filter { it.startsWith("#") || it.startsWith("Q:") }
            .map { it.trimStart('#', ' ', 'Q', ':', ' ').take(50) }
            .distinct()
            .take(20)

        val languages = agentConfig.supportedLanguages.map { it.bcp47Code }

        return ContextSummaryResponse(
            agentName = agentConfig.agentName,
            company = agentConfig.companyName,
            documentsLoaded = knowledgePath.walkTopDown().count { it.isFile && it.extension in listOf("md", "txt", "pdf") },
            totalChunks = knowledgeChunks.size,
            topicsCovered = topics,
            languagesConfigured = languages
        )
    }

    fun getLearnedFacts(limit: Int = 20) = learnedFacts.takeLast(limit)
    fun getAgentConfig() = agentConfig
    fun getCandidateProfileByPhone(phoneNumber: String): HrCandidateProfile? =
        findCandidateInDb(normalizePhone(phoneNumber)) ?: hrCandidates[normalizePhone(phoneNumber)]
    fun getContactByPhone(phoneNumber: String): ContactProfile? =
        findContactInDb(normalizePhone(phoneNumber)) ?: contacts[normalizePhone(phoneNumber)]

    fun upsertContactProfile(profile: ContactProfile): ContactProfile {
        val key = normalizePhone(profile.phoneNumber)
        val existing = getContactByPhone(key)
        val merged = ContactProfile(
            phoneNumber = key,
            name = profile.name?.takeIf { it.isNotBlank() } ?: existing?.name,
            email = profile.email?.takeIf { it.isNotBlank() } ?: existing?.email,
            department = profile.department?.takeIf { it.isNotBlank() } ?: existing?.department,
            designation = profile.designation?.takeIf { it.isNotBlank() } ?: existing?.designation,
            preferredLanguage = profile.preferredLanguage?.takeIf { it.isNotBlank() } ?: existing?.preferredLanguage,
            company = profile.company?.takeIf { it.isNotBlank() } ?: existing?.company,
            notes = profile.notes?.takeIf { it.isNotBlank() } ?: existing?.notes,
            tags = (existing?.tags.orEmpty() + profile.tags).map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            lastCallSummary = profile.lastCallSummary?.takeIf { it.isNotBlank() } ?: existing?.lastCallSummary,
            lastAttendanceStatus = profile.lastAttendanceStatus?.takeIf { it.isNotBlank() } ?: existing?.lastAttendanceStatus,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis()
        )
        contacts[key] = merged
        upsertContactInDb(merged)
        persistContacts()
        learnContactProfile(merged)
        return merged
    }

    fun deleteContactProfile(phoneNumber: String): Boolean {
        val key = normalizePhone(phoneNumber)
        val memDeleted = contacts.remove(key) != null
        if (memDeleted) persistContacts()
        val dbDeleted = deleteContactFromDb(key)
        return memDeleted || dbDeleted
    }

    fun listContacts(limit: Int = 200): List<ContactProfile> {
        return runCatching {
            dbConnection().use { conn ->
                conn.prepareStatement(
                    """
                    SELECT phone_number, name, email, department, designation, preferred_language, company, notes, tags, last_call_summary, last_attendance_status, created_at_ms, last_updated_at_ms
                    FROM contacts
                    ORDER BY last_updated_at_ms DESC
                    LIMIT ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setInt(1, limit.coerceIn(1, 1000))
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    ContactProfile(
                                        phoneNumber = rs.getString("phone_number"),
                                        name = rs.getString("name"),
                                        email = rs.getString("email"),
                                        department = rs.getString("department"),
                                        designation = rs.getString("designation"),
                                        preferredLanguage = rs.getString("preferred_language"),
                                        company = rs.getString("company"),
                                        notes = rs.getString("notes"),
                                        tags = rs.getString("tags")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                                        lastCallSummary = rs.getString("last_call_summary"),
                                        lastAttendanceStatus = rs.getString("last_attendance_status"),
                                        createdAt = rs.getLong("created_at_ms"),
                                        lastUpdatedAt = rs.getLong("last_updated_at_ms")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }.getOrElse {
            contacts.values.sortedByDescending { it.lastUpdatedAt }.take(limit.coerceIn(1, 1000))
        }
    }

    fun upsertCandidateProfile(profile: HrCandidateProfile): HrCandidateProfile {
        val key = normalizePhone(profile.phoneNumber)
        val existing = hrCandidates[key]
        val merged = HrCandidateProfile(
            phoneNumber = key,
            name = profile.name?.takeIf { it.isNotBlank() } ?: existing?.name,
            previousCompany = profile.previousCompany?.takeIf { it.isNotBlank() } ?: existing?.previousCompany,
            desiredRole = profile.desiredRole?.takeIf { it.isNotBlank() } ?: existing?.desiredRole,
            selfIntroduction = profile.selfIntroduction?.takeIf { it.isNotBlank() } ?: existing?.selfIntroduction,
            basicAnswer1 = profile.basicAnswer1?.takeIf { it.isNotBlank() } ?: existing?.basicAnswer1,
            basicAnswer2 = profile.basicAnswer2?.takeIf { it.isNotBlank() } ?: existing?.basicAnswer2,
            projects = profile.projects?.takeIf { it.isNotBlank() } ?: existing?.projects,
            email = profile.email?.takeIf { it.isNotBlank() } ?: existing?.email,
            consentToStore = profile.consentToStore,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis()
        )
        hrCandidates[key] = merged
        upsertCandidateInDb(merged)
        persistHrCandidates()
        learnCandidateProfile(merged)
        return merged
    }

    fun deleteCandidateProfile(phoneNumber: String): Boolean {
        val key = normalizePhone(phoneNumber)
        var memDeleted = false
        if (hrCandidates.containsKey(key)) {
            hrCandidates.remove(key)
            persistHrCandidates()
            memDeleted = true
        }
        val dbDeleted = deleteCandidateFromDb(key)
        return memDeleted || dbDeleted
    }

    fun listHrCandidates(
        dateFrom: String?,
        dateTo: String?,
        limit: Int = 200
    ): List<HrCandidateAdminResponse> {
        val from = dateFrom?.trim().orEmpty()
        val to = dateTo?.trim().orEmpty()
        val sql = buildString {
            append(
                """
                SELECT phone_number, name, previous_company, desired_role, self_introduction, basic_answer_1, basic_answer_2, projects, email, consent_to_store, created_at_ms, last_updated_at_ms
                FROM hr_candidates
                WHERE 1=1
                """.trimIndent()
            )
            if (from.isNotBlank()) append(" AND created_at::date >= ?::date")
            if (to.isNotBlank()) append(" AND created_at::date <= ?::date")
            append(" ORDER BY created_at DESC LIMIT ?")
        }
        return runCatching {
            dbConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    var idx = 1
                    if (from.isNotBlank()) stmt.setString(idx++, from)
                    if (to.isNotBlank()) stmt.setString(idx++, to)
                    stmt.setInt(idx, limit.coerceIn(1, 1000))
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    HrCandidateAdminResponse(
                                        phoneNumber = rs.getString("phone_number"),
                                        name = rs.getString("name"),
                                        previousCompany = rs.getString("previous_company"),
                                        desiredRole = rs.getString("desired_role"),
                                        selfIntroduction = rs.getString("self_introduction"),
                                        basicAnswer1 = rs.getString("basic_answer_1"),
                                        basicAnswer2 = rs.getString("basic_answer_2"),
                                        projects = rs.getString("projects"),
                                        email = rs.getString("email"),
                                        consentToStore = rs.getBoolean("consent_to_store"),
                                        createdAt = rs.getLong("created_at_ms"),
                                        lastUpdatedAt = rs.getLong("last_updated_at_ms")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }.getOrElse {
            hrCandidates.values
                .filter { profile ->
                    val createdDate = java.time.Instant.ofEpochMilli(profile.createdAt)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                    val fromOk = if (from.isBlank()) true else createdDate >= java.time.LocalDate.parse(from)
                    val toOk = if (to.isBlank()) true else createdDate <= java.time.LocalDate.parse(to)
                    fromOk && toOk
                }
                .sortedByDescending { it.createdAt }
                .take(limit.coerceIn(1, 1000))
                .map {
                    HrCandidateAdminResponse(
                        phoneNumber = it.phoneNumber,
                        name = it.name,
                        previousCompany = it.previousCompany,
                        desiredRole = it.desiredRole,
                        selfIntroduction = it.selfIntroduction,
                        basicAnswer1 = it.basicAnswer1,
                        basicAnswer2 = it.basicAnswer2,
                        projects = it.projects,
                        email = it.email,
                        consentToStore = it.consentToStore,
                        createdAt = it.createdAt,
                        lastUpdatedAt = it.lastUpdatedAt
                    )
                }
        }
    }

    fun sendHrThankYouEmail(profile: HrCandidateProfile): Boolean {
        val targetEmail = profile.email?.trim().orEmpty()
        if (targetEmail.isBlank()) return false

        val subject = "Thank you for your interview with ${agentConfig.companyName}"
        val html = """
            <html>
            <body style="font-family: Arial, sans-serif; color: #1f2937;">
              <h2>Thank you, ${profile.name ?: "Candidate"}.</h2>
              <p>Thank you so much for speaking with our HR team at <b>${agentConfig.companyName}</b>.</p>
              <p>We have received your profile and will connect for the next round soon.</p>
              <p style="margin-top:24px;">Regards,<br/>${agentConfig.companyName} HR Team</p>
            </body>
            </html>
        """.trimIndent()

        val savedOutbox = saveOutboxCopy(profile, subject, html)
        return sendSmtpHtmlEmail(targetEmail, subject, html) || savedOutbox
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun selectRelevantChunks(query: String, language: Language, maxChunks: Int = 5): List<KnowledgeChunk> {
        if (knowledgeChunks.isEmpty()) return emptyList()

        val queryWords = query.lowercase().split(" ", "?", "।", "،", "。", ",", ".", "!")
            .filter { it.length > 2 }
            .toSet()

        return knowledgeChunks
            .map { chunk ->
                val chunkWords = chunk.content.lowercase()
                val keywordScore = queryWords.count { word -> chunkWords.contains(word) }
                val categoryBoost = when (chunk.category.lowercase()) {
                    "faq" -> 2
                    "products" -> 1
                    else -> 0
                }
                val languageBoost = if (chunk.languages.contains("all") || chunk.languages.contains(language.bcp47Code)) 1 else 0
                chunk to (keywordScore * 3 + categoryBoost + languageBoost)
            }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { it.first }
            .take(maxChunks)
    }

    private fun readFile(file: File): String {
        return when (file.extension.lowercase()) {
            "pdf" -> {
                Loader.loadPDF(file).use { doc ->
                    PDFTextStripper().getText(doc)
                }
            }
            else -> file.readText(Charsets.UTF_8)
        }
    }

    private fun chunkText(text: String, source: String, category: String): List<KnowledgeChunk> {
        val words = text.split(Regex("\\s+"))
        val chunkSize = 400  // words per chunk
        val overlap = 50

        return words.windowed(chunkSize, chunkSize - overlap, partialWindows = true)
            .mapIndexed { index, chunk ->
                KnowledgeChunk(
                    id = "${source}_chunk_$index",
                    source = source,
                    category = category,
                    content = chunk.joinToString(" ")
                )
            }
    }

    private fun loadAgentConfig(file: File) {
        // Simple YAML parsing for agent config
        // In production use a proper YAML library (hoplite)
        val lines = file.readLines()
        var name = agentConfig.agentName
        var company = agentConfig.companyName
        val personality = mutableListOf<String>()
        val rules = mutableListOf<String>()

        lines.forEach { line ->
            when {
                line.trimStart().startsWith("name:") ->
                    name = line.substringAfter("name:").trim().trim('"')
                line.trimStart().startsWith("company:") ->
                    company = line.substringAfter("company:").trim().trim('"')
                line.trimStart().startsWith("- ") && line.contains("professional") ||
                line.trimStart().startsWith("- ") && line.contains("friendly") ->
                    personality.add(line.trimStart().removePrefix("- ").trim())
            }
        }

        agentConfig = agentConfig.copy(
            agentName = name,
            companyName = company,
            personality = personality.ifEmpty { agentConfig.personality }
        )
        log.info { "Agent config loaded: name=$name, company=$company" }
    }

    private fun createDefaultKnowledge() {
        // Create example knowledge files when none exist
        File(knowledgePath, "company").mkdirs()
        File(knowledgePath, "company/about.md").writeText("""
# Company Information

## Company Name
My Company

## Agent Name
Nova

## What We Do
We provide excellent products and services to our customers.

## Business Hours
Monday to Saturday, 9 AM to 6 PM.
We are closed on Sundays and national holidays.

## Contact
Email: support@mycompany.com
Phone: 1800-XXX-XXXX (Toll Free)
        """.trimIndent())

        File(knowledgePath, "faq").mkdirs()
        File(knowledgePath, "faq/general.md").writeText("""
# General FAQ

Q: How do I contact support?
A: You can call us at 1800-XXX-XXXX or email support@mycompany.com

Q: What are your business hours?
A: We are open Monday to Saturday, 9 AM to 6 PM IST.

Q: How do I get a refund?
A: Please contact our support team with your order details. We process refunds within 7 business days.
        """.trimIndent())

        log.info { "Created default knowledge files in ${knowledgePath.absolutePath}" }
        log.info { "→ Please edit these files to customise your agent!" }
    }

    private fun loadHrCandidates() {
        if (!hrCandidatesFile.exists()) return
        try {
            val decoded = jsonCodec.decodeFromString<List<HrCandidateProfile>>(hrCandidatesFile.readText())
            decoded.forEach { profile ->
                hrCandidates[normalizePhone(profile.phoneNumber)] = profile
            }
            log.info { "Loaded ${decoded.size} HR candidate profiles from ${hrCandidatesFile.absolutePath}" }
        } catch (e: Exception) {
            log.warn { "Could not load HR candidates file: ${e.message}" }
        }
    }

    private fun loadContacts() {
        if (!contactsFile.exists()) return
        try {
            val decoded = jsonCodec.decodeFromString<List<ContactProfile>>(contactsFile.readText())
            decoded.forEach { profile ->
                contacts[normalizePhone(profile.phoneNumber)] = profile
            }
            log.info { "Loaded ${decoded.size} contacts from ${contactsFile.absolutePath}" }
        } catch (e: Exception) {
            log.warn { "Could not load contacts file: ${e.message}" }
        }
    }

    private fun persistHrCandidates() {
        try {
            hrCandidatesFile.parentFile?.mkdirs()
            val ordered = hrCandidates.values.sortedByDescending { it.lastUpdatedAt }
            hrCandidatesFile.writeText(jsonCodec.encodeToString(ordered))
        } catch (e: Exception) {
            log.warn { "Could not persist HR candidates: ${e.message}" }
        }
    }

    private fun persistContacts() {
        try {
            contactsFile.parentFile?.mkdirs()
            val ordered = contacts.values.sortedByDescending { it.lastUpdatedAt }
            contactsFile.writeText(jsonCodec.encodeToString(ordered))
        } catch (e: Exception) {
            log.warn { "Could not persist contacts: ${e.message}" }
        }
    }

    private fun ensureHrCandidatesTable() {
        runCatching {
            dbConnection().use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS hr_candidates (
                            phone_number TEXT PRIMARY KEY,
                            name TEXT,
                            previous_company TEXT,
                            desired_role TEXT,
                            self_introduction TEXT,
                            basic_answer_1 TEXT,
                            basic_answer_2 TEXT,
                            projects TEXT,
                            email TEXT,
                            consent_to_store BOOLEAN NOT NULL DEFAULT FALSE,
                            created_at_ms BIGINT NOT NULL,
                            last_updated_at_ms BIGINT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                        );
                        """.trimIndent()
                    )
                    st.execute("""ALTER TABLE hr_candidates ADD COLUMN IF NOT EXISTS desired_role TEXT;""")
                    st.execute("""ALTER TABLE hr_candidates ADD COLUMN IF NOT EXISTS self_introduction TEXT;""")
                    st.execute("""ALTER TABLE hr_candidates ADD COLUMN IF NOT EXISTS basic_answer_1 TEXT;""")
                    st.execute("""ALTER TABLE hr_candidates ADD COLUMN IF NOT EXISTS basic_answer_2 TEXT;""")
                }
            }
        }.onFailure {
            log.warn { "Could not ensure hr_candidates table: ${it.message}" }
        }
    }

    private fun ensureContactsTable() {
        runCatching {
            dbConnection().use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS contacts (
                            phone_number TEXT PRIMARY KEY,
                            name TEXT,
                            email TEXT,
                            department TEXT,
                            designation TEXT,
                            preferred_language TEXT,
                            company TEXT,
                            notes TEXT,
                            tags TEXT,
                            last_call_summary TEXT,
                            last_attendance_status TEXT,
                            created_at_ms BIGINT NOT NULL,
                            last_updated_at_ms BIGINT NOT NULL
                        );
                        """.trimIndent()
                    )
                }
            }
        }.onFailure {
            log.warn { "Could not ensure contacts table: ${it.message}" }
        }
    }

    private fun deleteCandidateFromDb(phoneNumber: String): Boolean {
        return runCatching {
            dbConnection().use { conn ->
                conn.prepareStatement("DELETE FROM hr_candidates WHERE phone_number = ?").use { stmt ->
                    stmt.setString(1, phoneNumber)
                    stmt.executeUpdate() > 0
                }
            }
        }.onFailure {
            log.error(it) { "Failed to delete candidate '$phoneNumber' from database" }
        }.getOrDefault(false)
    }

    private fun deleteContactFromDb(phoneNumber: String): Boolean {
        return runCatching {
            dbConnection().use { conn ->
                conn.prepareStatement("DELETE FROM contacts WHERE phone_number = ?").use { stmt ->
                    stmt.setString(1, phoneNumber)
                    stmt.executeUpdate() > 0
                }
            }
        }.getOrDefault(false)
    }

    private fun upsertCandidateInDb(profile: HrCandidateProfile) {
        runCatching {
            dbConnection().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO hr_candidates (
                        phone_number, name, previous_company, desired_role, self_introduction, basic_answer_1, basic_answer_2, projects, email, consent_to_store, created_at_ms, last_updated_at_ms, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                    ON CONFLICT (phone_number) DO UPDATE SET
                        name = EXCLUDED.name,
                        previous_company = EXCLUDED.previous_company,
                        desired_role = EXCLUDED.desired_role,
                        self_introduction = EXCLUDED.self_introduction,
                        basic_answer_1 = EXCLUDED.basic_answer_1,
                        basic_answer_2 = EXCLUDED.basic_answer_2,
                        projects = EXCLUDED.projects,
                        email = EXCLUDED.email,
                        consent_to_store = EXCLUDED.consent_to_store,
                        created_at_ms = LEAST(hr_candidates.created_at_ms, EXCLUDED.created_at_ms),
                        last_updated_at_ms = EXCLUDED.last_updated_at_ms,
                        updated_at = NOW();
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, profile.phoneNumber)
                    stmt.setString(2, profile.name)
                    stmt.setString(3, profile.previousCompany)
                    stmt.setString(4, profile.desiredRole)
                    stmt.setString(5, profile.selfIntroduction)
                    stmt.setString(6, profile.basicAnswer1)
                    stmt.setString(7, profile.basicAnswer2)
                    stmt.setString(8, profile.projects)
                    stmt.setString(9, profile.email)
                    stmt.setBoolean(10, profile.consentToStore)
                    stmt.setLong(11, profile.createdAt)
                    stmt.setLong(12, profile.lastUpdatedAt)
                    stmt.executeUpdate()
                }
            }
        }.onFailure {
            log.warn { "Could not upsert hr candidate in DB: ${it.message}" }
        }
    }

    private fun upsertContactInDb(profile: ContactProfile) {
        runCatching {
            dbConnection().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO contacts (
                        phone_number, name, email, department, designation, preferred_language, company, notes, tags, last_call_summary, last_attendance_status, created_at_ms, last_updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (phone_number) DO UPDATE SET
                        name = EXCLUDED.name,
                        email = EXCLUDED.email,
                        department = EXCLUDED.department,
                        designation = EXCLUDED.designation,
                        preferred_language = EXCLUDED.preferred_language,
                        company = EXCLUDED.company,
                        notes = EXCLUDED.notes,
                        tags = EXCLUDED.tags,
                        last_call_summary = EXCLUDED.last_call_summary,
                        last_attendance_status = EXCLUDED.last_attendance_status,
                        last_updated_at_ms = EXCLUDED.last_updated_at_ms;
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, profile.phoneNumber)
                    stmt.setString(2, profile.name)
                    stmt.setString(3, profile.email)
                    stmt.setString(4, profile.department)
                    stmt.setString(5, profile.designation)
                    stmt.setString(6, profile.preferredLanguage)
                    stmt.setString(7, profile.company)
                    stmt.setString(8, profile.notes)
                    stmt.setString(9, profile.tags.joinToString(","))
                    stmt.setString(10, profile.lastCallSummary)
                    stmt.setString(11, profile.lastAttendanceStatus)
                    stmt.setLong(12, profile.createdAt)
                    stmt.setLong(13, profile.lastUpdatedAt)
                    stmt.executeUpdate()
                }
            }
        }.onFailure {
            log.warn { "Could not upsert contact in DB: ${it.message}" }
        }
    }

    private fun findCandidateInDb(phoneNumber: String): HrCandidateProfile? {
        return runCatching {
            dbConnection().use { conn ->
                conn.prepareStatement(
                    """
                    SELECT phone_number, name, previous_company, desired_role, self_introduction, basic_answer_1, basic_answer_2, projects, email, consent_to_store, created_at_ms, last_updated_at_ms
                    FROM hr_candidates
                    WHERE phone_number = ?
                    LIMIT 1
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, phoneNumber)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        HrCandidateProfile(
                            phoneNumber = rs.getString("phone_number"),
                            name = rs.getString("name"),
                            previousCompany = rs.getString("previous_company"),
                            desiredRole = rs.getString("desired_role"),
                            selfIntroduction = rs.getString("self_introduction"),
                            basicAnswer1 = rs.getString("basic_answer_1"),
                            basicAnswer2 = rs.getString("basic_answer_2"),
                            projects = rs.getString("projects"),
                            email = rs.getString("email"),
                            consentToStore = rs.getBoolean("consent_to_store"),
                            createdAt = rs.getLong("created_at_ms"),
                            lastUpdatedAt = rs.getLong("last_updated_at_ms")
                        )
                    }
                }
            }
        }.getOrNull()
    }

    private fun findContactInDb(phoneNumber: String): ContactProfile? {
        return runCatching {
            dbConnection().use { conn ->
                conn.prepareStatement(
                    """
                    SELECT phone_number, name, email, department, designation, preferred_language, company, notes, tags, last_call_summary, last_attendance_status, created_at_ms, last_updated_at_ms
                    FROM contacts
                    WHERE phone_number = ?
                    LIMIT 1
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, phoneNumber)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        ContactProfile(
                            phoneNumber = rs.getString("phone_number"),
                            name = rs.getString("name"),
                            email = rs.getString("email"),
                            department = rs.getString("department"),
                            designation = rs.getString("designation"),
                            preferredLanguage = rs.getString("preferred_language"),
                            company = rs.getString("company"),
                            notes = rs.getString("notes"),
                            tags = rs.getString("tags")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                            lastCallSummary = rs.getString("last_call_summary"),
                            lastAttendanceStatus = rs.getString("last_attendance_status"),
                            createdAt = rs.getLong("created_at_ms"),
                            lastUpdatedAt = rs.getLong("last_updated_at_ms")
                        )
                    }
                }
            }
        }.getOrNull()
    }

    private fun dbConnection() = DriverManager.getConnection(
        config.database.url,
        config.database.user,
        config.database.password
    )

    private fun learnCandidateProfile(profile: HrCandidateProfile) {
        val source = "candidate_${normalizePhone(profile.phoneNumber).replace("+", "")}"
        knowledgeChunks.removeAll { it.category.equals("hr-candidates", ignoreCase = true) && it.source == source }
        val summary = buildString {
            append("Candidate profile for hiring follow-up.\n")
            append("Phone: ${profile.phoneNumber}\n")
            profile.name?.let { append("Name: $it\n") }
            profile.previousCompany?.let { append("Previous Company: $it\n") }
            profile.desiredRole?.let { append("Desired Role: $it\n") }
            profile.selfIntroduction?.let { append("Self Introduction: $it\n") }
            profile.basicAnswer1?.let { append("Basic Answer 1: $it\n") }
            profile.basicAnswer2?.let { append("Basic Answer 2: $it\n") }
            profile.projects?.let { append("Projects: $it\n") }
            profile.email?.let { append("Email: $it\n") }
            append("Consent: ${if (profile.consentToStore) "yes" else "no"}\n")
            append("Updated At: ${profile.lastUpdatedAt}\n")
        }
        knowledgeChunks.addAll(chunkText(summary, source, "hr-candidates"))
    }

    private fun learnContactProfile(profile: ContactProfile) {
        val source = "contact_${normalizePhone(profile.phoneNumber).replace("+", "")}"
        knowledgeChunks.removeAll { it.category.equals("contacts", ignoreCase = true) && it.source == source }
        val summary = buildString {
            append("Known contact profile for repeat outbound and inbound conversations.\n")
            append("Phone: ${profile.phoneNumber}\n")
            profile.name?.let { append("Name: $it\n") }
            profile.department?.let { append("Department: $it\n") }
            profile.designation?.let { append("Designation: $it\n") }
            profile.preferredLanguage?.let { append("Preferred Language: $it\n") }
            profile.company?.let { append("Company: $it\n") }
            profile.notes?.let { append("Notes: $it\n") }
            if (profile.tags.isNotEmpty()) append("Tags: ${profile.tags.joinToString(", ")}\n")
            profile.lastCallSummary?.let { append("Last Call Summary: $it\n") }
            profile.lastAttendanceStatus?.let { append("Last Attendance Status: $it\n") }
        }
        knowledgeChunks.addAll(chunkText(summary, source, "contacts"))
    }

    private fun saveOutboxCopy(profile: HrCandidateProfile, subject: String, html: String): Boolean {
        return try {
            val ts = System.currentTimeMillis()
            val file = File(hrOutboxPath, "${normalizePhone(profile.phoneNumber).replace("+", "")}_$ts.html")
            file.writeText(
                """
                Subject: $subject
                To: ${profile.email ?: ""}

                $html
                """.trimIndent()
            )
            true
        } catch (e: Exception) {
            log.warn { "Could not write HR email outbox copy: ${e.message}" }
            false
        }
    }

    private fun sendSmtpHtmlEmail(to: String, subject: String, html: String): Boolean {
        val emailConfig = config.email
        if (
            emailConfig.host.isBlank() ||
            emailConfig.username.isBlank() ||
            emailConfig.password.isBlank() ||
            emailConfig.fromAddress.isBlank()
        ) {
            return false
        }

        return try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", emailConfig.useTls.toString())
                put("mail.smtp.host", emailConfig.host)
                put("mail.smtp.port", emailConfig.port.toString())
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(emailConfig.username, emailConfig.password)
            })
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(emailConfig.fromAddress, emailConfig.fromName))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject)
                setContent(html, "text/html; charset=utf-8")
            }
            Transport.send(message)
            true
        } catch (e: Exception) {
            log.warn { "SMTP send failed: ${e.message}" }
            false
        }
    }

    private fun normalizePhone(raw: String): String =
        raw.trim().replace(Regex("[^+\\d]"), "")

}

data class PromptArtifacts(
    val systemPrompt: String,
    val contextSources: List<String>,
    val contextSnippet: String
)
