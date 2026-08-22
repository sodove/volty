package ru.sodovaya.volty.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun startServer() {
    val dependencies = AppDependencies.create()
    embeddedServer(Netty, host = "0.0.0.0", port = System.getenv("PORT")?.toIntOrNull() ?: 8080) { module(dependencies) }.start(wait = true)
}
