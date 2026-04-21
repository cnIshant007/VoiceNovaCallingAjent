package com.voicenova.services

import com.voicenova.config.AppConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * TwilioService — wraps the Twilio REST API.
 * Used for making outbound calls and sending SMS.
 * When credentials are missing, operates in mock mode (logs only).
 */
class TwilioService(
    private val httpClient: HttpClient,
    private val config: AppConfig
) {
    private val baseUrl = "https://api.twilio.com/2010-04-01"

    val isMockMode: Boolean
        get() = config.twilio.accountSid.isBlank() || config.twilio.accountSid.startsWith("AC00") ||
                config.twilio.authToken.isBlank()

    /**
     * Initiate an outbound call via Twilio.
     * Returns the Twilio CallSid or a mock ID in mock mode.
     */
    suspend fun makeOutboundCall(
        to: String,
        webhookBaseUrl: String,
        statusCallbackUrl: String? = null
    ): String {
        val normalizedTo = normalizePhoneNumber(to)
        val effectiveBase = webhookBaseUrl.trimEnd('/')
        val twimlUrl = "$effectiveBase/webhooks/twilio/inbound"
        val statusUrl = statusCallbackUrl ?: "$effectiveBase/webhooks/twilio/status"

        if (isMockMode) {
            val mockSid = "CA_MOCK_${System.currentTimeMillis()}"
            log.info { "[TWILIO MOCK] Would call $normalizedTo → TwiML: $twimlUrl  (MockSid: $mockSid)" }
            return mockSid
        }

        return try {
            log.info {
                "Preparing Twilio outbound call to $normalizedTo using twimlUrl=$twimlUrl statusCallback=$statusUrl"
            }
            val credentials = Base64.getEncoder().encodeToString(
                "${config.twilio.accountSid}:${config.twilio.authToken}".toByteArray()
            )

            val response = httpClient.post(
                "$baseUrl/Accounts/${config.twilio.accountSid}/Calls.json"
            ) {
                header(HttpHeaders.Authorization, "Basic $credentials")
                setBody(FormDataContent(Parameters.build {
                    append("To", normalizedTo)
                    append("From", config.twilio.phoneNumber)
                    append("Url", twimlUrl)
                    append("StatusCallback", statusUrl)
                    append("StatusCallbackMethod", "POST")
                    append("StatusCallbackEvent", "initiated ringing answered completed")
                }))
            }

            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val json = Json.parseToJsonElement(body)
                val sid = json.jsonObject["sid"]?.jsonPrimitive?.content ?: "UNKNOWN"
                val initialStatus = json.jsonObject["status"]?.jsonPrimitive?.content ?: "unknown"
                log.info { "Outbound call initiated. SID: $sid → $normalizedTo (twilio_status=$initialStatus)" }
                sid
            } else {
                log.error { "Twilio API error: $body" }
                throw IllegalStateException("Twilio call failed: $body")
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to make outbound call to $normalizedTo via $twimlUrl" }
            throw e
        }
    }

    suspend fun redirectCallToConference(
        callSid: String,
        webhookBaseUrl: String,
        conferenceName: String,
        announceTransfer: Boolean = true
    ): Boolean {
        if (isMockMode) {
            log.info { "[TWILIO MOCK] Would redirect $callSid into conference $conferenceName" }
            return true
        }

        val joinUrl = "${
            webhookBaseUrl.trimEnd('/')
        }/webhooks/twilio/conference/join?conferenceName=${conferenceName.urlEncode()}&participantLabel=customer&announceTransfer=$announceTransfer"

        return try {
            val credentials = Base64.getEncoder().encodeToString(
                "${config.twilio.accountSid}:${config.twilio.authToken}".toByteArray()
            )
            val response = httpClient.post("$baseUrl/Accounts/${config.twilio.accountSid}/Calls/$callSid.json") {
                header(HttpHeaders.Authorization, "Basic $credentials")
                setBody(FormDataContent(Parameters.build {
                    append("Url", joinUrl)
                    append("Method", "POST")
                }))
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            log.error(e) { "Failed to redirect call $callSid to conference $conferenceName" }
            false
        }
    }

    suspend fun addConferenceParticipant(
        to: String,
        webhookBaseUrl: String,
        conferenceName: String,
        participantLabel: String,
        muted: Boolean
    ): String {
        val normalizedTo = normalizePhoneNumber(to)
        val effectiveBase = webhookBaseUrl.trimEnd('/')
        val joinUrl = "$effectiveBase/webhooks/twilio/conference/join" +
            "?conferenceName=${conferenceName.urlEncode()}" +
            "&participantLabel=${participantLabel.urlEncode()}" +
            "&muted=$muted"
        val statusUrl = "$effectiveBase/webhooks/twilio/status"

        if (isMockMode) {
            val mockSid = "CA_CONF_${System.currentTimeMillis()}"
            log.info { "[TWILIO MOCK] Would add $participantLabel participant $normalizedTo to conference $conferenceName" }
            return mockSid
        }

        return try {
            val credentials = Base64.getEncoder().encodeToString(
                "${config.twilio.accountSid}:${config.twilio.authToken}".toByteArray()
            )

            val response = httpClient.post(
                "$baseUrl/Accounts/${config.twilio.accountSid}/Calls.json"
            ) {
                header(HttpHeaders.Authorization, "Basic $credentials")
                setBody(FormDataContent(Parameters.build {
                    append("To", normalizedTo)
                    append("From", config.twilio.phoneNumber)
                    append("Url", joinUrl)
                    append("Method", "POST")
                    append("StatusCallback", statusUrl)
                    append("StatusCallbackMethod", "POST")
                }))
            }

            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val json = Json.parseToJsonElement(body)
                val sid = json.jsonObject["sid"]?.jsonPrimitive?.content ?: "UNKNOWN"
                log.info { "Conference participant $participantLabel initiated. SID: $sid → $normalizedTo" }
                sid
            } else {
                log.error { "Twilio conference participant error: $body" }
                throw IllegalStateException("Twilio conference participant failed: $body")
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to add conference participant $participantLabel" }
            throw e
        }
    }

    /**
     * Send an SMS via Twilio.
     */
    suspend fun sendSms(to: String, message: String): Boolean {
        val normalizedTo = normalizePhoneNumber(to)
        if (isMockMode) {
            log.info { "[TWILIO MOCK] Would SMS $normalizedTo: $message" }
            return true
        }

        return try {
            val credentials = Base64.getEncoder().encodeToString(
                "${config.twilio.accountSid}:${config.twilio.authToken}".toByteArray()
            )
            val response = httpClient.post(
                "$baseUrl/Accounts/${config.twilio.accountSid}/Messages.json"
            ) {
                header(HttpHeaders.Authorization, "Basic $credentials")
                setBody(FormDataContent(Parameters.build {
                    append("To", normalizedTo)
                    append("From", config.twilio.phoneNumber)
                    append("Body", message)
                }))
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            log.error { "SMS failed: ${e.message}" }
            false
        }
    }

    /**
     * Get the public webhook base URL (ngrok or configured URL).
     */
    fun getWebhookBaseUrl(): String {
        return config.publicBaseUrl.ifBlank {
            "http://localhost:${config.server.port}"
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

    fun fromNumber(): String = config.twilio.phoneNumber.ifBlank { "VoiceNova" }

    suspend fun endCall(callSid: String): Boolean {
        if (isMockMode) {
            log.info { "[TWILIO MOCK] Would end call $callSid" }
            return true
        }

        return try {
            val credentials = Base64.getEncoder().encodeToString(
                "${config.twilio.accountSid}:${config.twilio.authToken}".toByteArray()
            )
            val response = httpClient.post(
                "$baseUrl/Accounts/${config.twilio.accountSid}/Calls/$callSid.json"
            ) {
                header(HttpHeaders.Authorization, "Basic $credentials")
                setBody(FormDataContent(Parameters.build {
                    append("Status", "completed")
                }))
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            log.error { "Failed to end call $callSid: ${e.message}" }
            false
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
}
