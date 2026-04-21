package com.voicenova.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("error", "Bad Request")
                    put("message", cause.message ?: "Invalid input")
                }.toString()
            )
        }
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception for ${call.request.httpMethod.value} ${call.request.uri}" }
            call.respond(
                HttpStatusCode.InternalServerError,
                buildJsonObject {
                    put("error", "Internal Server Error")
                    put("message", cause.message ?: "An unexpected error occurred")
                }.toString()
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                buildJsonObject {
                    put("error", "Not Found")
                    put("message", "The requested endpoint does not exist")
                }.toString()
            )
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                buildJsonObject {
                    put("error", "Unauthorized")
                    put("message", "Valid Authorization header required")
                }.toString()
            )
        }
    }
}
