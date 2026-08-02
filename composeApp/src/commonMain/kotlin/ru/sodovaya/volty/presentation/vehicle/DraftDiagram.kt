package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import volty.composeapp.generated.resources.*

/** One row in the rider-facing phone -> BLE link -> source tree. */
enum class DiagramNodeKind {
    PHONE,
    BLE_LINK,
    CONTROLLER,
    BATTERY,
    BOTH,
    DERIVED_BATTERY
}

/**
 * Everything the renderer needs to walk the diagram without reconstructing the draft.
 *
 * [sourceKeys] is empty for the phone, links and derived batteries, one element for an
 * ordinary source, and contains both halves of a wheel. It gives every [ComposerIssue]
 * one stable, testable attachment point even when two draft rows render as one device.
 */
data class DiagramNode(
    val kind: DiagramNodeKind,
    val title: String,
    val address: String? = null,
    val canId: Int? = null,
    val sourceKeys: List<String> = emptyList(),
    val controllerType: ControllerType? = null,
    val bmsType: BmsType? = null,
    val issues: List<ComposerIssue> = emptyList(),
    val children: List<DiagramNode> = emptyList()
)

private data class SourceEntry(
    val bundleKey: String,
    val isDirectController: Boolean,
    val node: DiagramNode
)

/**
 * The review diagram, as a pure projection of [draft].
 *
 * The optional [issues] input lets the edit screen retain telemetry-backed advisories already
 * present in its state. The one-argument form remains the complete structural projection used by
 * the wizard. Issue ownership itself is delegated to [ComposerIssue.affectedKeys].
 */
fun draftDiagram(
    draft: VehicleDraft,
    issues: List<ComposerIssue> = validate(draft)
): DiagramNode {
    fun issuesFor(sourceKeys: List<String>): List<ComposerIssue> =
        issues.filter { issue -> issue.affectedKeys(draft).any(sourceKeys::contains) }

    // A Begode's direct controller and direct pack rows are two roles of one advertised device.
    // Requiring exactly one direct controller avoids hiding an already-ambiguous duplicate row.
    val wheelPacksByController = draft.controllers.mapNotNull { controller ->
        val packs = draft.packs.filter {
            controller.controllerType == ControllerType.BEGODE &&
                controller.canId == null &&
                it.bmsType == BmsType.BEGODE &&
                it.canId == null &&
                it.address == controller.address
        }
        val directControllers = draft.controllers.count {
            it.address == controller.address && it.canId == null
        }
        if (controller.address.isNotBlank() && packs.size == 1 && directControllers == 1) {
            controller.key to packs
        } else {
            null
        }
    }.toMap()
    val wheelPackKeys = wheelPacksByController.values.flatten().mapTo(mutableSetOf()) { it.key }

    val entries = buildList {
        for (controller in draft.controllers) {
            val wheelPacks = wheelPacksByController[controller.key]
            if (wheelPacks != null) {
                val keys = listOf(controller.key) + wheelPacks.map { it.key }
                add(
                    SourceEntry(
                        bundleKey = controller.key,
                        isDirectController = true,
                        node = DiagramNode(
                            kind = DiagramNodeKind.BOTH,
                            title = controller.label,
                            address = controller.address,
                            sourceKeys = keys,
                            controllerType = controller.controllerType,
                            bmsType = BmsType.BEGODE,
                            issues = issuesFor(keys)
                        )
                    )
                )
            } else {
                add(
                    SourceEntry(
                        bundleKey = controller.key,
                        isDirectController = controller.canId == null,
                        node = DiagramNode(
                            kind = DiagramNodeKind.CONTROLLER,
                            title = controller.label,
                            address = controller.address,
                            canId = controller.canId,
                            sourceKeys = listOf(controller.key),
                            controllerType = controller.controllerType,
                            issues = issuesFor(listOf(controller.key))
                        )
                    )
                )
                if (draft.resolvedDerivedBattery(controller)) {
                    add(
                        SourceEntry(
                            bundleKey = controller.key,
                            isDirectController = false,
                            node = DiagramNode(
                                kind = DiagramNodeKind.DERIVED_BATTERY,
                                title = controller.label,
                                address = controller.address,
                                canId = controller.canId
                            )
                        )
                    )
                }
            }
        }
        for (pack in draft.packs) {
            if (pack.key in wheelPackKeys) continue
            add(
                SourceEntry(
                    bundleKey = pack.key,
                    isDirectController = false,
                    node = DiagramNode(
                        kind = DiagramNodeKind.BATTERY,
                        title = pack.label,
                        address = pack.address,
                        canId = pack.canId,
                        sourceKeys = listOf(pack.key),
                        bmsType = pack.bmsType,
                        issues = issuesFor(listOf(pack.key))
                    )
                )
            )
        }
    }

    // A conflicting address is not one usable BLE link. Keeping one bucket per independently
    // added source bundle repeats the address visibly instead of presenting a false merge.
    val conflictingAddresses = issues.filterIsInstance<ComposerIssue.ConflictingKinds>()
        .mapTo(mutableSetOf()) { it.address }
    val bundles = entries.groupByTo(LinkedHashMap()) { it.bundleKey }
    val linkGroups = mutableListOf<MutableList<SourceEntry>>()
    val ordinaryLinkByAddress = LinkedHashMap<String, MutableList<SourceEntry>>()
    for (bundle in bundles.values) {
        val address = bundle.first().node.address.orEmpty()
        val group = if (address.isBlank() || address in conflictingAddresses) {
            mutableListOf<SourceEntry>().also(linkGroups::add)
        } else {
            ordinaryLinkByAddress.getOrPut(address) {
                mutableListOf<SourceEntry>().also(linkGroups::add)
            }
        }
        group += bundle
    }

    val links = linkGroups.map { group ->
        val canEntries = group.filter { it.node.canId != null }
        val gateways = group.filter { it.isDirectController }
        val children = if (gateways.size == 1 && canEntries.isNotEmpty()) {
            val gateway = gateways.single()
            group.mapNotNull { entry ->
                when {
                    entry.isDirectController && entry.bundleKey == gateway.bundleKey ->
                        entry.node.copy(children = canEntries.map { it.node })
                    entry.node.canId != null -> null
                    else -> entry.node
                }
            }
        } else {
            group.map { it.node }
        }
        DiagramNode(
            kind = DiagramNodeKind.BLE_LINK,
            title = "",
            address = group.first().node.address,
            children = children
        )
    }

    return DiagramNode(
        kind = DiagramNodeKind.PHONE,
        title = "",
        children = links
    )
}

/** Walks a fully-decided [DiagramNode] tree; it contains no draft or topology rules. */
@Composable
fun DraftDiagramView(root: DiagramNode, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DiagramBranch(root, depth = 0)
    }
}

@Composable
private fun DiagramBranch(node: DiagramNode, depth: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 16).dp)
                .background(
                    color = if (node.issues.isEmpty()) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(diagramGlyph(node.kind), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(diagramTitle(node), fontWeight = FontWeight.SemiBold)
                Text(
                    diagramFacts(node),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                node.issues.forEach { issue ->
                    Text(
                        composerIssueText(issue),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        node.children.forEach { child -> DiagramBranch(child, depth + 1) }
    }
}

private fun diagramGlyph(kind: DiagramNodeKind): String = when (kind) {
    DiagramNodeKind.PHONE -> "APP"
    DiagramNodeKind.BLE_LINK -> "BT"
    DiagramNodeKind.CONTROLLER -> "ESC"
    DiagramNodeKind.BATTERY -> "BMS"
    DiagramNodeKind.BOTH -> "2×"
    DiagramNodeKind.DERIVED_BATTERY -> "≈"
}

@Composable
private fun diagramTitle(node: DiagramNode): String = when (node.kind) {
    DiagramNodeKind.PHONE -> stringResource(Res.string.diagram_phone)
    DiagramNodeKind.BLE_LINK -> stringResource(Res.string.diagram_bluetooth_link)
    DiagramNodeKind.CONTROLLER -> node.title.ifBlank { node.controllerType?.label ?: "—" }
    DiagramNodeKind.BATTERY -> node.title.ifBlank { node.bmsType?.let { bmsTypeLabel(it) } ?: "—" }
    DiagramNodeKind.BOTH -> node.title.ifBlank { node.controllerType?.label ?: "—" }
    DiagramNodeKind.DERIVED_BATTERY -> stringResource(Res.string.diagram_derived_battery)
}

@Composable
private fun diagramFacts(node: DiagramNode): String {
    val role = stringResource(
        when (node.kind) {
            DiagramNodeKind.PHONE -> Res.string.diagram_role_vehicle
            DiagramNodeKind.BLE_LINK -> Res.string.diagram_role_link
            DiagramNodeKind.CONTROLLER -> Res.string.diagram_role_controller
            DiagramNodeKind.BATTERY, DiagramNodeKind.DERIVED_BATTERY -> Res.string.diagram_role_battery
            DiagramNodeKind.BOTH -> Res.string.diagram_role_both
        }
    )
    val type = when (node.kind) {
        DiagramNodeKind.PHONE -> stringResource(Res.string.diagram_type_phone)
        DiagramNodeKind.BLE_LINK -> stringResource(Res.string.diagram_type_bluetooth)
        DiagramNodeKind.CONTROLLER -> node.controllerType?.label ?: "—"
        DiagramNodeKind.BATTERY -> node.bmsType?.let { bmsTypeLabel(it) } ?: "—"
        DiagramNodeKind.BOTH -> listOfNotNull(
            node.controllerType?.label,
            node.bmsType?.let { bmsTypeLabel(it) }
        ).joinToString(" + ")
        DiagramNodeKind.DERIVED_BATTERY -> stringResource(Res.string.diagram_type_derived)
    }
    return buildList {
        add(role)
        add(type)
        node.address?.let { add(it.ifBlank { "—" }) }
        node.canId?.let { add("CAN $it") }
    }.joinToString(" · ")
}
