package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import ru.sodovaya.volty.data.voice.VoiceSessionHttpPort
import kotlin.test.*

class NearbyApiUrlDelimiterTest {
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
