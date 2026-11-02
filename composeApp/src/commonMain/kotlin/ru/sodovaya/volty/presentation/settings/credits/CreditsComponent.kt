package ru.sodovaya.volty.presentation.settings.credits

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.ExperimentalResourceApi
import volty.composeapp.generated.resources.Res

interface CreditsComponent {
    val state: StateFlow<State>

    fun onLoad()
    fun onQueryChanged(query: String)
    fun onProjectSelected(id: String)
    fun onLicenseSelected(id: String)
    fun onListPositionChanged(index: Int, offset: Int)

    /** Returns true when a license or project detail level was closed. */
    fun onBack(): Boolean

    data class State(
        val loading: Boolean = false,
        val error: String? = null,
        val catalog: CreditsCatalog? = null,
        val query: String = "",
        val selectedProjectId: String? = null,
        val selectedLicenseId: String? = null,
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
    ) {
        val selectedProject: CreditProject?
            get() = catalog?.projects?.firstOrNull { it.id == selectedProjectId }

        val selectedLicense: CreditLicense?
            get() = selectedLicenseId?.let { catalog?.licenses?.get(it) }

        val filteredProjects: List<CreditProject>
            get() {
                val currentCatalog = catalog ?: return emptyList()
                val search = query.trim().lowercase()
                return currentCatalog.projects
                    .asSequence()
                    .filter { project ->
                        search.isEmpty() || project.name.containsIgnoreCase(search) ||
                            project.authors.any { it.containsIgnoreCase(search) } ||
                            project.licenseIds.any { licenseId ->
                                val license = currentCatalog.licenses.getValue(licenseId)
                                license.name.containsIgnoreCase(search) || license.id.containsIgnoreCase(search)
                            }
                    }
                    .sortedWith(compareBy<CreditProject> { it.name.lowercase() }.thenBy { it.name }.thenBy { it.id })
                    .toList()
            }

        private fun String.containsIgnoreCase(query: String): Boolean = lowercase().contains(query)
    }
}

class DefaultCreditsComponent(
    private val scope: CoroutineScope,
    private val loadCatalog: suspend () -> String,
) : CreditsComponent {
    private val _state = MutableStateFlow(CreditsComponent.State())
    override val state: StateFlow<CreditsComponent.State> = _state.asStateFlow()

    override fun onLoad() {
        if (_state.value.loading || _state.value.catalog != null) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val catalog = CreditsCatalog.parse(loadCatalog())
                _state.update { it.copy(loading = false, error = null, catalog = catalog) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = failure.message?.takeIf(String::isNotBlank) ?: "Unable to load credits catalog"
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    override fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query, scrollIndex = 0, scrollOffset = 0) }
    }

    override fun onProjectSelected(id: String) {
        if (_state.value.catalog?.projects?.any { it.id == id } != true) return
        _state.update { it.copy(selectedProjectId = id, selectedLicenseId = null) }
    }

    override fun onLicenseSelected(id: String) {
        val current = _state.value
        val project = current.selectedProject ?: return
        if (id !in project.licenseIds || current.catalog?.licenses?.containsKey(id) != true) return
        _state.update { it.copy(selectedLicenseId = id) }
    }

    override fun onListPositionChanged(index: Int, offset: Int) {
        _state.update { it.copy(scrollIndex = index.coerceAtLeast(0), scrollOffset = offset.coerceAtLeast(0)) }
    }

    override fun onBack(): Boolean {
        val current = _state.value
        return when {
            current.selectedLicenseId != null -> {
                _state.update { it.copy(selectedLicenseId = null) }
                true
            }
            current.selectedProjectId != null -> {
                _state.update { it.copy(selectedProjectId = null) }
                true
            }
            else -> false
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
internal suspend fun loadBundledCreditsCatalog(): String =
    Res.readBytes("files/credits/catalog.json").decodeToString()
