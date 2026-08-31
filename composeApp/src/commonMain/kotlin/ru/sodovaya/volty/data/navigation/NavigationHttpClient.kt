package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient

expect fun createNavigationHttpClient(): HttpClient
