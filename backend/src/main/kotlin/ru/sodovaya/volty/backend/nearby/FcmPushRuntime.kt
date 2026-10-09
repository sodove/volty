package ru.sodovaya.volty.backend.nearby

import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** Application owns the HTTP transport; construction reads no database or network. */
internal class FcmPushRuntime private constructor(private val http: HttpClient, val tick: PushDeliveryTick) : AutoCloseable {
    override fun close() { http.shutdownNow() }
    companion object {
        fun create(source: DataSource, account: FcmServiceAccount, now: () -> Long): FcmPushRuntime {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()
            return try {
                val preparation = FcmCallPushPreparation(FcmOAuthProvider.create(account, http), HttpFcmSender.create(account.project, http))
                val worker = PushDeliveryWorker(JdbcPushDeliveryQueue(source), preparation, now) { UUID.randomUUID().toString() }
                FcmPushRuntime(http, PushDeliveryTick { worker.tick() })
            } catch (failure: Throwable) { http.shutdownNow(); throw failure }
        }
    }
}
