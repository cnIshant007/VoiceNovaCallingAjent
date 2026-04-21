package com.voicenova.services

import com.voicenova.config.AppConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mu.KotlinLogging
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

class PlivoService(
    private val httpClient: HttpClient,
    private val config: AppConfig
) {
    private val baseUrl = "https://api.plivo.com/v1/Account/${config.plivo.authId}"
    private val requestToCallUuid = ConcurrentHashMap<String, String>()

    val isConfigured: Boolean
        get() = config.plivo.authId.isNotBlank() &&
            config.plivo.authToken.isNotBlank() &&
            config.plivo.phoneNumber.isNotBlank()

    suspend fun makeOutboundCall(to: String, webhookBaseUrl: String): String {
        check(isConfigured) { "Plivo credentials are missing." }

        val normalizedTo = normalizePhoneNumber(to)
        val effectiveBase = webhookBaseUrl.trimEnd('/')
        val answerUrl = "$effectiveBase/webhooks/plivo/inbound"
        val hangupUrl = "$effectiveBase/webhooks/plivo/status"
        val ringUrl = "$effectiveBase/webhooks/plivo/status"

        val response = httpClient.post("$baseUrl/Call/") {
            header(HttpHeaders.Authorization, basicAuthHeader())
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("from", config.plivo.phoneNumber)
                    put("to", normalizedTo)
                    put("answer_url", answerUrl)
                    put("answer_method", "POST")
                    put("hangup_url", hangupUrl)
                    put("hangup_method", "POST")
                    put("ring_url", ringUrl)
                    put("ring_method", "POST")
                    put("caller_name", "VoiceNova")
                }
            )
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.error { "Plivo API error: $body" }
            throw IllegalStateException("Plivo call failed: $body")
        }

        val json = Json.parseToJsonElement(body).jsonObject
        val requestUuid = json["request_uuid"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Plivo call started but request_uuid was missing.")
        log.info { "Plivo outbound call initiated. request_uuid=$requestUuid to $normalizedTo" }
        return requestUuid
    }

    suspend fun endCall(callIdOrRequestId: String): Boolean {
        check(isConfigured) { "Plivo credentials are missing." }
        val callUuid = resolveCallUuid(callIdOrRequestId) ?: callIdOrRequestId

        return try {
            val response = httpClient.delete("$baseUrl/Call/$callUuid/") {
                header(HttpHeaders.Authorization, basicAuthHeader())
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            log.error(e) { "Failed to end Plivo call $callIdOrRequestId" }
            false
        }
    }

    fun normalizePhoneNumber(phone: String): String {
        val trimmed = phone.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.startsWith("+")) return trimmed

        val digits = trimmed.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+91$digits"
            digits.length == 11 && digits.startsWith("0") -> "+91${digits.drop(1)}"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            else -> if (digits.isNotBlank()) "+$digits" else trimmed
        }
    }

    fun fromNumber(): String = config.plivo.phoneNumber.ifBlank { "VoiceNova" }

    fun registerCallUuid(requestUuid: String?, callUuid: String?) {
        if (requestUuid.isNullOrBlank() || callUuid.isNullOrBlank()) return
        requestToCallUuid[requestUuid] = callUuid
    }

    fun resolveCallUuid(callIdOrRequestId: String?): String? {
        if (callIdOrRequestId.isNullOrBlank()) return null
        return requestToCallUuid[callIdOrRequestId] ?: callIdOrRequestId
    }

    private fun basicAuthHeader(): String {
        val raw = "${config.plivo.authId}:${config.plivo.authToken}"
        return "Basic ${Base64.getEncoder().encodeToString(raw.toByteArray())}"
    }
}
