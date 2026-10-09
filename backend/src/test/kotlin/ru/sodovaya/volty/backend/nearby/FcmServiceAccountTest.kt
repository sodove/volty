package ru.sodovaya.volty.backend.nearby

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.*

class FcmServiceAccountTest {
    private val project = "test-project"
    @Test fun loadsTypedKeyWithOnlyMessagingScopeAndRedactsWrapper() = withKey(key()) { path ->
        val loaded = FcmServiceAccount.load(path, project)
        assertEquals(project, loaded.project)
        assertEquals(project, loaded.credentials.projectId)
        assertEquals(setOf("https://www.googleapis.com/auth/firebase.messaging"), loaded.credentials.scopes.toSet())
        assertEquals("https://oauth2.googleapis.com/token", loaded.credentials.tokenServerUri.toString())
        assertEquals("FcmServiceAccount(<redacted>)", loaded.toString())
        assertNotNull(loaded.credentials.privateKey)
        Unit
    }

    @Test fun rejectsOtherProjectAndExternalCredentialKindsWithoutPrivateErrorChain() {
        for (replacement in listOf("project_id" to "another-project", "type" to "authorized_user", "type" to "external_account")) {
            withKey(key(replacement)) { assertPrivateFailure(it) }
        }
    }

    @Test fun rejectsCredentialSuppliedEndpointsAndAlternateUniverse() {
        for (replacement in listOf("token_uri" to "http://127.0.0.1:9/private", "token_uri" to "https://oauth2.googleapis.com/token?x=private",
            "universe_domain" to "private.invalid")) {
            withKey(key(replacement)) { assertPrivateFailure(it) }
        }
    }

    @Test fun rejectsMalformedPrivateKeyAndOversizedFileWithoutExposingContent() {
        withKey(key("private_key" to "private-sensitive-invalid-key")) { assertPrivateFailure(it) }
        withKey("private-sensitive".repeat(5000)) { assertPrivateFailure(it) }
        withKey("{\"private_key\":\"private-sensitive\"}") { assertPrivateFailure(it) }
    }

    @Test fun missingFileAndDirectoryHaveTheSamePrivateFailure() {
        val directory = Files.createTempDirectory("volty-fcm-test-")
        try { assertPrivateFailure(directory); assertPrivateFailure(directory.resolve("private-sensitive-missing.json")) }
        finally { Files.delete(directory) }
    }

    @Test fun malformedUtf8InIgnoredFieldStillRejectsOtherwiseValidKey() {
        val path = Files.createTempFile("volty-fcm-test-", ".json")
        try {
            val prefix = (key().dropLast(1) + ",\"ignored\":\"").toByteArray(Charsets.UTF_8)
            Files.write(path, prefix + byteArrayOf(0x80.toByte()) + "\"}".toByteArray(Charsets.UTF_8))
            assertPrivateFailure(path)
        } finally { Files.delete(path) }
    }

    private fun assertPrivateFailure(path: Path) {
        val failure = assertFailsWith<IllegalArgumentException> { FcmServiceAccount.load(path, project) }
        assertEquals("Invalid Firebase service-account configuration", failure.message)
        assertNull(failure.cause); assertTrue(failure.suppressed.isEmpty())
    }
    private fun key(replacement: Pair<String, String>? = null) = buildJsonObject {
        val fields = mapOf("type" to "service_account", "project_id" to project, "client_id" to "123456789",
            "private_key_id" to "a".repeat(40), "private_key" to pem, "client_email" to "sender@$project.iam.gserviceaccount.com",
            "token_uri" to "https://oauth2.googleapis.com/token", "universe_domain" to "googleapis.com")
        fields.forEach { (name, value) -> put(name, if (replacement?.first == name) replacement.second else value) }
    }.toString()
    private fun <T> withKey(contents: String, work: (Path) -> T): T {
        val path = Files.createTempFile("volty-fcm-test-", ".json")
        return try { Files.writeString(path, contents); work(path) } finally { Files.delete(path) }
    }
    companion object {
        // Ephemeral test-only key; no project credentials or Google network requests.
        private val pem by lazy {
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
            "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(key.encoded) + "\n-----END PRIVATE KEY-----\n"
        }
    }
}
