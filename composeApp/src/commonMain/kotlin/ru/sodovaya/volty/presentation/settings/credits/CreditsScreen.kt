package ru.sodovaya.volty.presentation.settings.credits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.credits_authors
import volty.composeapp.generated.resources.credits_empty_catalog
import volty.composeapp.generated.resources.credits_intro
import volty.composeapp.generated.resources.credits_licenses
import volty.composeapp.generated.resources.credits_load_error
import volty.composeapp.generated.resources.credits_loading
import volty.composeapp.generated.resources.credits_notice
import volty.composeapp.generated.resources.credits_no_results
import volty.composeapp.generated.resources.credits_open_source
import volty.composeapp.generated.resources.credits_read_license
import volty.composeapp.generated.resources.credits_retry
import volty.composeapp.generated.resources.credits_search
import volty.composeapp.generated.resources.credits_version

/** Renders the offline credits catalog; nested back decisions remain with [CreditsComponent]. */
@Composable
fun CreditsScreen(component: CreditsComponent?, modifier: Modifier = Modifier) {
    if (component == null) {
        CreditsMessage(
            modifier = modifier,
            message = stringResource(Res.string.credits_load_error),
        )
        return
    }

    val state by component.state.collectAsState()
    val selectedLicense = state.selectedLicense
    val selectedProject = state.selectedProject
    LaunchedEffect(component) { component.onLoad() }

    when {
        state.error != null -> CreditsMessage(
            modifier = modifier,
            message = stringResource(Res.string.credits_load_error),
            retryLabel = stringResource(Res.string.credits_retry),
            onRetry = component::onLoad,
        )

        state.loading || state.catalog == null -> CreditsMessage(
            modifier = modifier,
            message = stringResource(Res.string.credits_loading),
            loading = true,
        )

        selectedLicense != null -> CreditsLicenseDetails(
            license = selectedLicense,
            modifier = modifier,
        )

        selectedProject != null -> CreditsProjectDetails(
            project = selectedProject,
            catalog = state.catalog,
            onLicenseSelected = component::onLicenseSelected,
            modifier = modifier,
        )

        else -> CreditsCatalogList(
            state = state,
            component = component,
            modifier = modifier,
        )
    }
}

@Composable
private fun CreditsMessage(
    modifier: Modifier,
    message: String,
    loading: Boolean = false,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) CircularProgressIndicator()
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (retryLabel != null && onRetry != null) {
                TextButton(onClick = onRetry) { Text(retryLabel) }
            }
        }
    }
}

@Composable
private fun CreditsCatalogList(
    state: CreditsComponent.State,
    component: CreditsComponent,
    modifier: Modifier,
) {
    val projects = state.filteredProjects
    val initialIndex = state.scrollIndex.coerceIn(0, (projects.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = state.scrollOffset.coerceAtLeast(0),
    )
    var previousQuery by remember { mutableStateOf(state.query) }
    LaunchedEffect(state.query) {
        if (previousQuery != state.query) {
            listState.scrollToItem(0)
            previousQuery = state.query
        }
    }

    LaunchedEffect(component, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> component.onListPositionChanged(index, offset) }
    }
    LaunchedEffect(projects.size, listState) {
        if (projects.isNotEmpty() && listState.firstVisibleItemIndex >= projects.size) {
            listState.scrollToItem(projects.lastIndex)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.credits_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = component::onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(Res.string.credits_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        )

        when {
            projects.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (state.query.isBlank()) Res.string.credits_empty_catalog
                        else Res.string.credits_no_results,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                itemsIndexed(projects, key = { _, project -> project.id }) { index, project ->
                    CreditProjectRow(
                        project = project,
                        catalog = state.catalog!!,
                        onClick = { component.onProjectSelected(project.id) },
                    )
                    if (index < projects.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun CreditProjectRow(
    project: CreditProject,
    catalog: CreditsCatalog,
    onClick: () -> Unit,
) {
    val authors = project.authors.filter(String::isNotBlank).joinToString(", ")
    val version = project.version?.let { stringResource(Res.string.credits_version, it) }
    val licenseNames = project.licenseIds.mapNotNull { catalog.licenses[it]?.name }
    val metadata = listOfNotNull(version, licenseNames.takeIf { it.isNotEmpty() }?.joinToString(" · "))
        .joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(project.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        if (authors.isNotEmpty()) {
            Text(authors, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (metadata.isNotEmpty()) {
            Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CreditsProjectDetails(
    project: CreditProject,
    catalog: CreditsCatalog?,
    onLicenseSelected: (String) -> Unit,
    modifier: Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val authors = project.authors.filter(String::isNotBlank).joinToString(", ")
    val version = project.version?.let { stringResource(Res.string.credits_version, it) }
    val licenses = project.licenseIds.mapNotNull { catalog?.licenses?.get(it) }
    val website = project.website?.takeIf { it.startsWith("https://", ignoreCase = true) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(project.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (authors.isNotEmpty()) LabeledValue(stringResource(Res.string.credits_authors), authors)
        if (version != null) Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (website != null) {
            TextButton(onClick = { runCatching { uriHandler.openUri(website) } }) {
                Text(stringResource(Res.string.credits_open_source))
            }
        }

        if (licenses.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.credits_licenses), style = MaterialTheme.typography.titleMedium)
                licenses.forEach { license ->
                    TextButton(onClick = { onLicenseSelected(license.id) }) {
                        Text("${stringResource(Res.string.credits_read_license)}: ${license.name}")
                    }
                }
            }
        }

        project.notice?.takeIf(String::isNotBlank)?.let { notice ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.credits_notice), style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(notice, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreditsLicenseDetails(license: CreditLicense, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(license.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Text(
                text = license.content,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                softWrap = true,
            )
        }
    }
}
