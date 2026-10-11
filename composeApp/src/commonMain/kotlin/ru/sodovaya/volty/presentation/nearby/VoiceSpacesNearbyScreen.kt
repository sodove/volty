package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
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
    val profileDescription=stringResource(Res.string.nearby_profile_title)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={if(state.section==NearbyShellSection.PROFILE) component.openServers() else component.back()}) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,contentDescription=stringResource(Res.string.nearby_back))
            }
            Text(stringResource(if(state.section==NearbyShellSection.PROFILE) Res.string.nearby_profile_title else Res.string.nearby_title),
                modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
            val account=state.session as? SocialSession.Authenticated
            if(account!=null) FilledTonalIconButton(onClick=component::openProfile,enabled=!state.busy,shape=CircleShape) {
                Text(account.displayName.trim().take(1).uppercase().ifEmpty { "•" },
                    modifier=Modifier.semantics {contentDescription=profileDescription},style=MaterialTheme.typography.titleSmall)
            }
        }
        if(state.session is SocialSession.Authenticated) state.error?.let {
            Text(stringResource(it.shellMessage()),Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error)
        }
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.session !is SocialSession.Authenticated) {
            NearbyAuthForm(state,component,Modifier.weight(1f))
        } else {
            if(state.section!=NearbyShellSection.PROFILE) TabRow(selectedTabIndex=if(state.section==NearbyShellSection.FRIENDS) 1 else 0) {
                Tab(selected=state.section==NearbyShellSection.SERVERS,onClick=component::openServers,
                    text={Text(stringResource(Res.string.nearby_shell_servers))})
                Tab(selected=state.section==NearbyShellSection.FRIENDS,onClick=component::openFriends,
                    text={Text(stringResource(Res.string.nearby_friends_tab))})
            }
            Crossfade(state.section, modifier=Modifier.weight(1f), animationSpec=tween(220), label="nearbyPage") { section ->
            when(section) {
                NearbyShellSection.PROFILE -> NearbyProfileForm(state,component,Modifier.fillMaxSize())
                NearbyShellSection.FRIENDS -> NearbyFriendsView(state,component,Modifier.fillMaxSize())
                NearbyShellSection.SERVERS -> {
                    val presenter=servers.presenter
                    when {
                        presenter!=null -> ServerWorkspaceScreen(presenter,Modifier.fillMaxSize())
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
}

@Composable
private fun NearbyProfileForm(state: VoiceSpacesNearbyState,component: DefaultVoiceSpacesNearbyComponent,modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {OutlinedTextField(state.profile.displayName,component::setProfileName,
            Modifier.widthIn(max=520.dp).fillMaxWidth(),label={Text(stringResource(Res.string.nearby_name))},
            singleLine=true,enabled=!state.busy)}
        item {Button(onClick=component::submitProfile,enabled=!state.busy) {
            Text(stringResource(Res.string.nearby_profile_save))
        }}
        if(state.profile.saved) item {Text(stringResource(Res.string.nearby_shell_profile_saved))}
        item {NearbyCallNotificationSettings(Modifier.widthIn(max=520.dp).fillMaxWidth())}
        item {TextButton(onClick=component::logout,enabled=!state.busy) {
            Text(stringResource(Res.string.nearby_logout),color=MaterialTheme.colorScheme.error)
        }}
    }
}

@Composable
private fun NearbyAuthForm(state: VoiceSpacesNearbyState,component: DefaultVoiceSpacesNearbyComponent,modifier: Modifier) {
    val enabled=!state.busy && state.session!=SocialSession.Authenticating
    // Keep the IME's selection/composition synchronously. The component's combined
    // StateFlow is an asynchronous text echo, not the editor's source of truth.
    // Form-scoped state also survives lazy-item disposal and login/register changes;
    // leaving authentication discards it, including the password.
    var email by remember(component) {mutableStateOf(TextFieldValue(state.auth.email,TextRange(state.auth.email.length)))}
    var password by remember(component) {mutableStateOf(TextFieldValue(state.auth.password,TextRange(state.auth.password.length)))}
    var displayName by remember(component) {mutableStateOf(TextFieldValue(state.auth.displayName,TextRange(state.auth.displayName.length)))}
    LazyColumn(modifier.fillMaxWidth().imePadding().navigationBarsPadding(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text(stringResource(Res.string.nearby_shell_description))}
        item {TabRow(selectedTabIndex=state.auth.mode.ordinal) {
            Tab(selected=state.auth.mode==NearbyAuthMode.LOGIN,onClick={component.setAuthMode(NearbyAuthMode.LOGIN)},enabled=enabled,
                text={Text(stringResource(Res.string.nearby_login))})
            Tab(selected=state.auth.mode==NearbyAuthMode.REGISTER,onClick={component.setAuthMode(NearbyAuthMode.REGISTER)},enabled=enabled,
                text={Text(stringResource(Res.string.nearby_register))})
        }}
        item {Text(stringResource(Res.string.nearby_auth_email_hint),style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item(key="email") {OutlinedTextField(email,{value -> if(enabled && value.text.length<=254) {
            val changed=value.text!=email.text; email=value; if(changed) component.setEmail(value.text)
        }},Modifier.widthIn(max=520.dp).fillMaxWidth(),
            label={Text(stringResource(Res.string.nearby_auth_email))},placeholder={Text("name@example.com")},
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,enabled=enabled)}
        item(key="password") {OutlinedTextField(password,{value -> if(enabled && value.text.length<=128) {
            val changed=value.text!=password.text; password=value; if(changed) component.setPassword(value.text)
        }},Modifier.widthIn(max=520.dp).fillMaxWidth(),
            label={Text(stringResource(Res.string.nearby_password))},visualTransformation=PasswordVisualTransformation(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,enabled=enabled)}
        if(state.auth.mode==NearbyAuthMode.REGISTER) {
            item {Text(stringResource(Res.string.nearby_password_hint))}
            item(key="displayName") {OutlinedTextField(displayName,{value -> if(enabled && value.text.length<=80) {
                val changed=value.text!=displayName.text; displayName=value; if(changed) component.setDisplayName(value.text)
            }},Modifier.widthIn(max=520.dp).fillMaxWidth(),
                label={Text(stringResource(Res.string.nearby_name))},singleLine=true,enabled=enabled)}
        }
        state.error?.let {error ->item {Text(stringResource(when {
            error==NearbyShellError.INVALID_FORM && state.auth.mode==NearbyAuthMode.LOGIN -> Res.string.nearby_auth_login_invalid
            error==NearbyShellError.CONFLICT && state.auth.mode==NearbyAuthMode.REGISTER -> Res.string.nearby_auth_email_taken
            else -> error.shellMessage()
        }),color=MaterialTheme.colorScheme.error)}}
        item {Button(onClick=component::submitAuth,enabled=enabled,modifier=Modifier.widthIn(max=520.dp).fillMaxWidth()) {
            Text(stringResource(if(state.auth.mode==NearbyAuthMode.LOGIN) Res.string.nearby_login else Res.string.nearby_register))
        }}
        item {TextButton(onClick=component::requestRecovery,enabled=enabled) {
            Text(stringResource(Res.string.nearby_recovery))
        }}
        if(state.auth.recoveryRequested) item {Text(stringResource(Res.string.nearby_shell_recovery_requested))}
        if(state.session==SocialSession.Authenticating) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
    }
}

internal fun NearbyShellError.shellMessage(): StringResource = when(this) {
    NearbyShellError.INVALID_FORM -> Res.string.nearby_shell_invalid_form
    NearbyShellError.INVALID_CREDENTIALS -> Res.string.nearby_auth_invalid_credentials
    NearbyShellError.UNAUTHORIZED -> Res.string.nearby_servers_auth_required
    NearbyShellError.FORBIDDEN -> Res.string.nearby_servers_access_denied
    NearbyShellError.CONFLICT -> Res.string.nearby_shell_conflict
    NearbyShellError.RATE_LIMITED -> Res.string.nearby_shell_rate_limited
    NearbyShellError.UNAVAILABLE -> Res.string.nearby_shell_unavailable
}
