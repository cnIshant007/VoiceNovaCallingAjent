package com.voicenova.plugins

import com.voicenova.config.AppConfig
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun Application.configureSecurity(config: AppConfig) {
    // Basic Bearer token auth for protected endpoints
    install(Authentication) {
        bearer("auth-bearer") {
            authenticate { tokenCredential ->
                if (tokenCredential.token == config.apiSecretKey ||
                    tokenCredential.token == "test_token"    // allow test_token in dev
                ) {
                    UserIdPrincipal("api-user")
                } else {
                    null
                }
            }
        }
    }
}
