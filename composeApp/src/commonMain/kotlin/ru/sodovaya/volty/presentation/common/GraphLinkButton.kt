package ru.sodovaya.volty.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.tab_graph

/**
 * "Graph ›" text link. Graph stopped being a top-level tab when the bar
 * became Ride / Battery / Settings, so both dashboards carry this link to
 * keep the history screen one tap away.
 *
 * Per the signed-off mockup, the Ride screen's header row is two muted
 * labels — a filled, primary-tinted chip here would be the only saturated
 * element on the screen and compete with the hero gauge. So this renders as
 * a plain text label in the primary colour, no background, no border.
 *
 * The label's own ink is ~20dp tall — far under the 48dp platform minimum, and
 * it is the only route to the Graph screen. So the clickable sits on an outer
 * [Box] and comes BEFORE [minimumInteractiveComponentSize] in the chain, which
 * makes the expanded 48dp box the actual hit area rather than merely reserved
 * space around a small target (that modifier only grows the node it wraps —
 * putting it first would leave the pointer bounds on the small inner node).
 */
@Composable
fun GraphLinkButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick, role = Role.Button)
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(Res.string.tab_graph) + " ›",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
