package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import ru.sodovaya.volty.domain.nearby.community.*

/**
 * Owns invitation commands for one authenticated lifetime. Retire before exposing another
 * account. An already-issued command can commit remotely; its durable draft is preserved.
 * This is a factory boundary, not automatic wiring into the legacy social repository.
 */
class InvitationAccountSession(
    credentials: NearbyAccountCredentials,
    store: InvitationDraftStore,
    generator: InvitationSecretGenerator,
    client: HttpClient,
    baseUrl: String,
) {
    val actorId = credentials.actorId.also { require(store.actorId == it) }
    private val binding = NearbyAccountBinding(credentials)
    private val port = InvitationHttpPort(client, baseUrl) { binding.bearer() }
    val owner = InvitationCreationDraftOwner(actorId, store, generator, port)

    fun refresh(credentials: NearbyAccountCredentials) = binding.refresh(credentials)

    fun retire() {
        binding.retire()
        owner.retire()
        port.close()
    }
}
