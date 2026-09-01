package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

actual fun createNavigationHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000L
        requestTimeoutMillis = 5_000L
        socketTimeoutMillis = 5_000L
    }
}
