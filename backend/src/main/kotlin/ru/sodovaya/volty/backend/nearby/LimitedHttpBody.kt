package ru.sodovaya.volty.backend.nearby

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

internal class OversizedHttpBody : RuntimeException()

/** Bounded buffering and explicit cancellation, including cancellation before subscription. */
internal class LimitedHttpBody(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val buffer = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null
    private var terminated = false

    override fun getBody(): CompletionStage<ByteArray> = result

    @Synchronized override fun onSubscribe(value: Flow.Subscription) {
        if (terminated || subscription != null) { value.cancel(); return }
        subscription = value
        value.request(Long.MAX_VALUE)
    }

    @Synchronized override fun onNext(items: List<ByteBuffer>) {
        if (terminated) return
        for (item in items) {
            if (item.remaining() > limit - buffer.size()) {
                terminated = true
                subscription?.cancel()
                result.completeExceptionally(OversizedHttpBody())
                return
            }
            val chunk = ByteArray(item.remaining())
            item.get(chunk)
            buffer.write(chunk)
        }
    }

    @Synchronized override fun onError(failure: Throwable) {
        terminated = true
        result.completeExceptionally(failure)
    }

    @Synchronized override fun onComplete() {
        if (terminated) return
        terminated = true
        result.complete(buffer.toByteArray())
    }

    @Synchronized fun abort() {
        terminated = true
        subscription?.cancel()
        result.cancel(false)
    }
}
