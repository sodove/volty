package ru.sodovaya.volty.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.tab_graph

/**
 * Compact "Graph ›" chip. Graph stopped being a top-level tab when the bar
 * became Ride / Battery / Settings, so both dashboards carry this button to
 * keep the history screen one tap away.
 *
 * The chip's own ink is ~20dp tall — far under the 48dp platform minimum, and
 * it is the only route to the Graph screen. So the clickable sits on an outer
 * [Box] and comes BEFORE [minimumInteractiveComponentSize] in the chain, which
 * makes the expanded 48dp box the actual hit area rather than merely reserved
 * space around a small target (that modifier only grows the node it wraps —
 * putting it first would leave the pointer bounds on the small inner node).
 * The visible chip — clip, background, padding — is unchanged and stays on the
 * [Text] itself, so the rendering is byte-for-byte what it was.
 */
@Composable
fun GraphLinkButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            // Clip first so the press ripple follows the expanded box's corners
            // instead of painting a bare rectangle across the card header.
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(Res.string.tab_graph) + " ›",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
