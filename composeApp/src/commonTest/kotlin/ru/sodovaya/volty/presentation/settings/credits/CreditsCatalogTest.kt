package ru.sodovaya.volty.presentation.settings.credits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CreditsCatalogTest {
    @Test
    fun parsesOptionalMetadataAndUnknownFutureFields() {
        val catalog = CreditsCatalog.parse(
            """
            {
              "projects": [{
                "id": "core",
                "name": "Core",
                "authors": [],
                "licenseIds": ["mit"],
                "futureField": {"keptByNewerReaders": true}
              }],
              "licenses": {
                "mit": {"id": "mit", "name": "MIT", "content": "Full license text", "futureField": 1}
              },
              "futureCatalogField": "ignored"
            }
            """.trimIndent(),
        )

        assertEquals("core", catalog.projects.single().id)
        assertEquals(emptyList(), catalog.projects.single().authors)
        assertNull(catalog.projects.single().version)
        assertNull(catalog.projects.single().website)
        assertNull(catalog.projects.single().notice)
        assertEquals("Full license text", catalog.licenses.getValue("mit").content)
    }

    @Test
    fun acceptsMultipleLicenseReferences() {
        val catalog = CreditsCatalog.parse(
            """
            {
              "projects": [{"id":"p","name":"Project","authors":["A"],"licenseIds":["mit","bsd"]}],
              "licenses": {
                "mit":{"id":"mit","name":"MIT","content":"MIT text"},
                "bsd":{"id":"bsd","name":"BSD-3-Clause","content":"BSD text"}
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("mit", "bsd"), catalog.projects.single().licenseIds)
    }

    @Test
    fun rejectsMalformedJson() {
        assertFailsWith<Exception> { CreditsCatalog.parse("{not-json") }
    }

    @Test
    fun rejectsDuplicateProjectIds() {
        assertInvalid(
            """
            {"projects":[
              {"id":"same","name":"One","licenseIds":["mit"]},
              {"id":"same","name":"Two","licenseIds":["mit"]}
            ],"licenses":{"mit":{"id":"mit","name":"MIT","content":"text"}}}
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsBlankProjectNames() {
        assertInvalid(
            """
            {"projects":[{"id":"p","name":"  ","licenseIds":["mit"]}],
             "licenses":{"mit":{"id":"mit","name":"MIT","content":"text"}}}
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsProjectsWithoutLicenseReferences() {
        assertInvalid(
            """
            {"projects":[{"id":"p","name":"Project","licenseIds":[]}],"licenses":{}}
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsUnknownLicenseReferences() {
        assertInvalid(
            """
            {"projects":[{"id":"p","name":"Project","licenseIds":["missing"]}],"licenses":{}}
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsLicenseMapKeysThatDoNotMatchTheirIds() {
        assertInvalid(
            """
            {"projects":[{"id":"p","name":"Project","licenseIds":["mit"]}],
             "licenses":{"mit":{"id":"different","name":"MIT","content":"text"}}}
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsBlankLicenseNamesAndContents() {
        assertInvalid(
            """
            {"projects":[{"id":"p","name":"Project","licenseIds":["mit"]}],
             "licenses":{"mit":{"id":"mit","name":"MIT","content":"  "}}}
            """.trimIndent(),
        )
        assertInvalid(
            """
            {"projects":[{"id":"p","name":"Project","licenseIds":["mit"]}],
             "licenses":{"mit":{"id":"mit","name":"  ","content":"text"}}}
            """.trimIndent(),
        )
    }

    private fun assertInvalid(json: String) {
        assertFailsWith<IllegalArgumentException> { CreditsCatalog.parse(json) }
    }
}
