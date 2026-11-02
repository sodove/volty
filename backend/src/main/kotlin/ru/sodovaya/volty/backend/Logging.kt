package ru.sodovaya.volty.backend

import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

internal fun Application.installBackendCallLogging() {
    install(CallLogging) {
        // Evaluated after the response: keep failed polling requests visible.
        filter { call ->
            !(call.request.httpMethod == HttpMethod.Get &&
                call.request.path() == "/v2/nearby/calls/incoming" &&
                call.response.status()?.value in 200..299)
        }
    }
}
