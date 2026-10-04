package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import ru.sodovaya.volty.data.voice.VoiceSessionHttpPort
import ru.sodovaya.volty.data.voice.VoiceRosterHttpPort
import kotlin.test.*

class NearbyApiUrlDelimiterTest {
    private fun rejectsMissingHosts(create: (HttpClient, String, suspend () -> String?) -> (() -> Unit)) {
        var authReads = 0
        val client = HttpClient(MockEngine) { engine { addHandler { error("No request allowed") } } }
        try {
            for (base in listOf("https://", "https:///v2", "https://@/v2", "https://:443/v2")) {
                var close: (() -> Unit)? = null
                try {
                    assertFailsWith<IllegalArgumentException>(base) {
                        close = create(client, base) { authReads++; "secret" }
                    }
                } finally { close?.invoke() }
            }
            for (base in listOf("https://[::1]:8443/v2", "https://localhost:8443/v2", "https://example.test/v2/%3F%23")) {
                create(client, base) { authReads++; "secret" }.invoke()
            }
            assertEquals(0, authReads)
        } finally { client.close() }
    }

    @Test fun communityRejectsMissingRawHosts() = rejectsMissingHosts { client, base, bearer ->
        val port = CommunityHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun removalRejectsMissingRawHosts() = rejectsMissingHosts { client, base, bearer ->
        val port = CommunityRemovalHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun channelsRejectMissingRawHosts() = rejectsMissingHosts { client, base, bearer ->
        val port = ChannelCommandsHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun invitationsRejectMissingRawHosts() = rejectsMissingHosts { client, base, bearer ->
        val port = InvitationHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun listingRejectsMissingRawHosts() = rejectsMissingHosts { client, base, bearer ->
        val port = InvitationListingHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun voiceSessionRejectsMissingRawHosts() = rejectsMissingHosts { client, base, bearer ->
        val port = VoiceSessionHttpPort(client, base, bearer, {100}); { port.close() }
    }
    @Test fun voiceRosterRejectsMissingRawHosts() = rejectsMissingHosts { client, base, bearer ->
        val port = VoiceRosterHttpPort(client, base, bearer, {100}); { port.close() }
    }
    private fun rejectsDelimiters(create: (HttpClient, String, suspend () -> String?) -> (() -> Unit)) {
        var authReads = 0
        val client = HttpClient(MockEngine) { engine { addHandler { error("No request allowed") } } }
        try {
            for (suffix in listOf("?", "#", "?q=x", "#x")) {
                var close: (() -> Unit)? = null
                try {
                    assertFailsWith<IllegalArgumentException> {
                        close = create(client, "https://example.test/v2/nearby$suffix") { authReads++; "secret" }
                    }
                } finally { close?.invoke() }
            }
            create(client, "https://example.test/v2/%3F%23/nearby") { authReads++; "secret" }.invoke()
            assertEquals(0, authReads)
        } finally { client.close() }
    }

    @Test fun communityReadsRejectPathRedirectingDelimiters() = rejectsDelimiters { client, base, bearer ->
        val port = CommunityHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun removalRejectsPathRedirectingDelimiters() = rejectsDelimiters { client, base, bearer ->
        val port = CommunityRemovalHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun channelCommandsRejectPathRedirectingDelimiters() = rejectsDelimiters { client, base, bearer ->
        val port = ChannelCommandsHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun invitationCommandsRejectPathRedirectingDelimiters() = rejectsDelimiters { client, base, bearer ->
        val port = InvitationHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun invitationListingRejectsPathRedirectingDelimiters() = rejectsDelimiters { client, base, bearer ->
        val port = InvitationListingHttpPort(client, base, bearer); { port.close() }
    }
    @Test fun voiceSessionsRejectPathRedirectingDelimiters() = rejectsDelimiters { client, base, bearer ->
        val port = VoiceSessionHttpPort(client, base, bearer, {100}); { port.close() }
    }
}
