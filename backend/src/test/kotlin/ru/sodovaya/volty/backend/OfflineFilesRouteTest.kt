package ru.sodovaya.volty.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineFilesRouteTest {
    @Test
    fun serves_offline_catalog_from_the_configured_read_only_root() = testApplication {
        val root = Files.createTempDirectory("volty-offline-test")
        try {
            Files.writeString(root.resolve("catalog.json"), "{\"schemaVersion\":2}")
            application {
                module(
                    AppDependencies(
                        config = AppConfig.forTests().copy(offlineFilesRoot = root.toString()),
                        store = object : BackendStore {},
                        testMode = true,
                    ),
                )
            }

            val response = client.get("/offline/catalog.json")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"schemaVersion\":2"))
        } finally {
            Files.deleteIfExists(root.resolve("catalog.json"))
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun resolves_static_catalog_without_a_manager_service() = testApplication {
        val root = Files.createTempDirectory("volty-offline-resolve-test")
        try {
            val release = root.resolve("regions/ekb/v1")
            Files.createDirectories(release)
            Files.writeString(release.resolve(".ready.json"), "{}")
            Files.writeString(
                root.resolve("catalog.json"),
                """
                {"schemaVersion":2,"regions":[{"region":{"regionId":"ekb","displayName":"EKB","bounds":{"south":56.0,"west":60.0,"north":57.0,"east":61.0}},"latestRelease":{"regionId":"ekb","releaseVersion":"v1"}}]}
                """.trimIndent(),
            )
            application {
                module(
                    AppDependencies(
                        config = AppConfig.forTests().copy(offlineFilesRoot = root.toString()),
                        store = object : BackendStore {},
                        testMode = true,
                    ),
                )
            }

            val response = client.get("/offline/resolve?lat=56.8&lon=60.6")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"status\":\"ready\""))
            assertTrue(response.bodyAsText().contains("\"regionId\":\"ekb\""))
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
