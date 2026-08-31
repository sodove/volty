package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createNavigationHttpClient(): HttpClient = HttpClient(OkHttp)
