package ru.sodovaya.volty.presentation.settings.credits

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CreditsComponentTest {
    @Test
    fun loadingIsLazyAndSuccessfulCatalogIsLoadedOnlyOnce() = runTest {
        var reads = 0
        val component = DefaultCreditsComponent(backgroundScope) {
            reads += 1
            catalogJson
        }

        assertEquals(0, reads)
        assertNull(component.state.value.catalog)

        component.onLoad()
        runCurrent()
        assertEquals(1, reads)
        assertNotNull(component.state.value.catalog)
        assertFalse(component.state.value.loading)

        component.onLoad()
        runCurrent()
        assertEquals(1, reads, "reopening credits must reuse the loaded catalog")
    }

    @Test
    fun failedLoadCanBeRetried() = runTest {
        var reads = 0
        val component = DefaultCreditsComponent(backgroundScope) {
            reads += 1
            if (reads == 1) error("catalog unavailable")
            catalogJson
        }

        component.onLoad()
        runCurrent()
        assertEquals(1, reads)
        assertNotNull(component.state.value.error)
        assertFalse(component.state.value.loading)

        component.onLoad()
        runCurrent()
        assertEquals(2, reads)
        assertNull(component.state.value.error)
        assertNotNull(component.state.value.catalog)
    }

    @Test
    fun projectsAreFilteredAcrossNamesAuthorsAndLicenseNamesInAlphabeticalOrder() = runTest {
        val component = loadedComponent()
        assertEquals(listOf("Alpha", "Beta", "Zulu"), component.state.value.filteredProjects.map { it.name })

        component.onQueryChanged("vALiN")
        assertEquals(listOf("Beta"), component.state.value.filteredProjects.map { it.name })
        component.onQueryChanged("bsd-3")
        assertEquals(listOf("Beta"), component.state.value.filteredProjects.map { it.name })
        component.onQueryChanged("alpha")
        assertEquals(listOf("Alpha"), component.state.value.filteredProjects.map { it.name })
        component.onQueryChanged("")
        assertEquals(listOf("Alpha", "Beta", "Zulu"), component.state.value.filteredProjects.map { it.name })
    }

    @Test
    fun nestedBackReturnsLicenseThenProjectThenCatalogAndKeepsSearchAndPosition() = runTest {
        val component = loadedComponent()
        component.onQueryChanged("beta")
        component.onListPositionChanged(index = 4, offset = 37)

        component.onProjectSelected("beta")
        assertEquals("beta", component.state.value.selectedProjectId)
        assertNull(component.state.value.selectedLicenseId)

        component.onLicenseSelected("bsd")
        assertEquals("BSD-3-Clause", component.state.value.selectedLicense?.name)

        assertTrue(component.onBack())
        assertEquals("beta", component.state.value.selectedProjectId)
        assertNull(component.state.value.selectedLicenseId)

        assertTrue(component.onBack())
        assertNull(component.state.value.selectedProjectId)
        assertEquals("beta", component.state.value.query)
        assertEquals(4, component.state.value.scrollIndex)
        assertEquals(37, component.state.value.scrollOffset)

        assertFalse(component.onBack(), "the settings page handles Back after the catalog level")
    }

    @Test
    fun invalidProjectsAndUnreferencedLicensesDoNotNavigate() = runTest {
        val component = loadedComponent()
        component.onProjectSelected("missing")
        assertNull(component.state.value.selectedProjectId)

        component.onProjectSelected("alpha")
        component.onLicenseSelected("bsd")
        assertEquals("alpha", component.state.value.selectedProjectId)
        assertNull(component.state.value.selectedLicenseId)
        assertNull(component.state.value.selectedLicense)
    }

    @Test
    fun emptyMetadataAndZeroAuthorsRemainEmpty() = runTest {
        val component = DefaultCreditsComponent(backgroundScope) { emptyMetadataJson }
        component.onLoad()
        runCurrent()

        val project = component.state.value.filteredProjects.single()
        assertEquals(emptyList(), project.authors)
        assertNull(project.version)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.loadedComponent(): CreditsComponent {
        val component = DefaultCreditsComponent(backgroundScope) { catalogJson }
        component.onLoad()
        runCurrent()
        return component
    }

    private val catalogJson = """
        {
          "projects": [
            {"id":"zulu","name":"Zulu","version":"2.0","authors":["Other"],"licenseIds":["mit"]},
            {"id":"beta","name":"Beta","authors":["Jean-Marc Valin"],"licenseIds":["bsd"]},
            {"id":"alpha","name":"Alpha","authors":["Writer"],"licenseIds":["mit"]}
          ],
          "licenses": {
            "mit":{"id":"mit","name":"MIT","content":"MIT full text"},
            "bsd":{"id":"bsd","name":"BSD-3-Clause","content":"BSD full text"}
          }
        }
    """.trimIndent()

    private val emptyMetadataJson = """
        {"projects":[{"id":"bare","name":"Bare","licenseIds":["mit"]}],
         "licenses":{"mit":{"id":"mit","name":"MIT","content":"text"}}}
    """.trimIndent()
}
