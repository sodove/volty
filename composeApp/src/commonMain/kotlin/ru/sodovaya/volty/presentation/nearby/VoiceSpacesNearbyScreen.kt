package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.social.*
import volty.composeapp.generated.resources.*

/** Account/friend shell around the server view. Root supplies conversation admission callbacks. */
@Composable
fun VoiceSpacesNearbyScreen(component: DefaultVoiceSpacesNearbyComponent,modifier: Modifier=Modifier) {
    val state by component.state.collectAsState()
    val servers by component.servers.collectAsState()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=component::back) {Text(stringResource(Res.string.nearby_back))}
            Text((state.session as? SocialSession.Authenticated)?.displayName ?: stringResource(Res.string.nearby_title),
                modifier=Modifier.weight(1f).padding(vertical=16.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
            if(state.session is SocialSession.Authenticated) TextButton(onClick=component::logout,enabled=!state.busy) {
                Text(stringResource(Res.string.nearby_logout))
            }
        }
        state.error?.let {Text(stringResource(it.shellMessage()),Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error)}
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.session !is SocialSession.Authenticated) {
            NearbyAuthForm(state,component,Modifier.weight(1f))
        } else {
            TabRow(selectedTabIndex=state.section.ordinal) {
                Tab(selected=state.section==NearbyShellSection.SERVERS,onClick=component::openServers,
                    text={Text(stringResource(Res.string.nearby_shell_servers))})
                Tab(selected=state.section==NearbyShellSection.FRIENDS,onClick=component::openFriends,
                    text={Text(stringResource(Res.string.nearby_friends_tab))})
            }
            when(state.section) {
                NearbyShellSection.FRIENDS -> NearbyFriendsView(state,component,Modifier.weight(1f))
                NearbyShellSection.SERVERS -> {
                    val presenter=servers.presenter
                    when {
                        presenter!=null -> ServerWorkspaceScreen(presenter,Modifier.weight(1f))
                        servers.failed -> Column(Modifier.padding(16.dp)) {
                            Text(stringResource(Res.string.nearby_shell_unavailable))
                            Button(onClick=component::retryServers) {Text(stringResource(Res.string.nearby_retry))}
                        }
                        else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyAuthForm(state: VoiceSpacesNearbyState,component: DefaultVoiceSpacesNearbyComponent,modifier: Modifier) {
    val enabled=!state.busy && state.session!=SocialSession.Authenticating
    LazyColumn(modifier.fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text(stringResource(Res.string.nearby_shell_description))}
        item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(onClick={component.setAuthMode(NearbyAuthMode.LOGIN)},enabled=enabled) {Text(stringResource(Res.string.nearby_login))}
            TextButton(onClick={component.setAuthMode(NearbyAuthMode.REGISTER)},enabled=enabled) {Text(stringResource(Res.string.nearby_register))}
        }}
        item {OutlinedTextField(state.auth.email,component::setEmail,Modifier.widthIn(max=520.dp).fillMaxWidth(),
            label={Text(stringResource(Res.string.nearby_email))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,enabled=enabled)}
        item {OutlinedTextField(state.auth.password,component::setPassword,Modifier.widthIn(max=520.dp).fillMaxWidth(),
            label={Text(stringResource(Res.string.nearby_password))},visualTransformation=PasswordVisualTransformation(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,enabled=enabled)}
        if(state.auth.mode==NearbyAuthMode.REGISTER) {
            item {Text(stringResource(Res.string.nearby_password_hint))}
            item {OutlinedTextField(state.auth.displayName,component::setDisplayName,Modifier.widthIn(max=520.dp).fillMaxWidth(),
                label={Text(stringResource(Res.string.nearby_name))},singleLine=true,enabled=enabled)}
        }
        item {Button(onClick=component::submitAuth,enabled=enabled) {
            Text(stringResource(if(state.auth.mode==NearbyAuthMode.LOGIN) Res.string.nearby_login else Res.string.nearby_register))
        }}
        if(state.session==SocialSession.Authenticating) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
    }
}

internal fun NearbyShellError.shellMessage(): StringResource = when(this) {
    NearbyShellError.INVALID_FORM -> Res.string.nearby_shell_invalid_form
    NearbyShellError.UNAUTHORIZED -> Res.string.nearby_servers_auth_required
    NearbyShellError.FORBIDDEN -> Res.string.nearby_servers_access_denied
    NearbyShellError.CONFLICT -> Res.string.nearby_shell_conflict
    NearbyShellError.RATE_LIMITED -> Res.string.nearby_shell_rate_limited
    NearbyShellError.UNAVAILABLE -> Res.string.nearby_shell_unavailable
}
