package ru.sodovaya.volty.presentation.root.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.*

/** Renders the original process offer; composition and screen disposal never decline a call. */
@Composable
internal fun NearbyIncomingCallScreen(view:NearbyIncomingCallView?,actions:NearbyIncomingCallActions) {
    if(view==null) return
    val title=stringResource(Res.string.nearby_incoming_call_title)
    Surface(Modifier.fillMaxWidth(),tonalElevation=3.dp) {
        Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall)
            view.callerName?.let {Text(it,maxLines=1,overflow=TextOverflow.Ellipsis)}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier=Modifier.weight(1f),onClick={actions.decline(view)}) {
                    Text(stringResource(Res.string.nearby_incoming_call_decline))
                }
                Button(modifier=Modifier.weight(1f),onClick={actions.accept(view,title)}) {
                    Text(stringResource(Res.string.nearby_incoming_call_accept))
                }
            }
        }
    }
}
