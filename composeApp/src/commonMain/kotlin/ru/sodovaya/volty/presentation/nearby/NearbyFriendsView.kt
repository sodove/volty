package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.social.*
import volty.composeapp.generated.resources.*

@Composable
internal fun NearbyFriendsView(state: VoiceSpacesNearbyState,component: DefaultVoiceSpacesNearbyComponent,modifier: Modifier) {
    val page=state.friends
    LazyColumn(modifier.fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {OutlinedTextField(page.query,component::setFriendQuery,Modifier.widthIn(max=520.dp).fillMaxWidth(),
            label={Text(stringResource(Res.string.nearby_friend_search_hint))},singleLine=true)}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=component::searchFriends,enabled=!page.searchLoading) {Text(stringResource(Res.string.nearby_friend_search))}
            TextButton(onClick=component::refreshFriends,enabled=!page.loading) {Text(stringResource(Res.string.nearby_refresh))}
        }}
        if(page.searchLoading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        page.searchError?.let {error ->item {Text(stringResource(error.shellMessage()),color=MaterialTheme.colorScheme.error)}}
        page.results?.let {results ->
            if(results.isEmpty()) item {Text(stringResource(Res.string.nearby_friend_search_empty))}
            items(results) {user ->Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(user.displayName)
                    if(user.state==null && user.friendshipId==null && user.userId!=(state.session as? SocialSession.Authenticated)?.userId)
                        TextButton(onClick={component.requestFriend(user.userId)},enabled=!state.busy) {Text(stringResource(Res.string.nearby_friend_add))}
                    else user.state?.let {Text(stringResource(it.friendshipMessage()))}
                }
            }}
        }
        item {Text(stringResource(Res.string.nearby_friends_tab),style=MaterialTheme.typography.titleMedium)}
        if(page.loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        page.error?.let {error ->item {Text(stringResource(error.shellMessage()),color=MaterialTheme.colorScheme.error)}}
        page.items?.let {friends ->
            if(friends.isEmpty()) item {Text(stringResource(Res.string.nearby_friends_empty))}
            items(friends) {friend ->Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(friend.displayName);Text(stringResource(friend.state.friendshipMessage()))
                    when(friend.state) {
                        FriendshipState.ACCEPTED -> Button(onClick={component.callFriend(friend.userId)},enabled=!state.busy && !page.loading && page.error==null) {
                            Text(stringResource(Res.string.nearby_shell_call))
                        }
                        FriendshipState.REQUEST_RECEIVED -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={component.respondToFriend(friend.friendshipId,true)},enabled=!state.busy && !page.loading) {Text(stringResource(Res.string.nearby_accept))}
                            TextButton(onClick={component.respondToFriend(friend.friendshipId,false)},enabled=!state.busy && !page.loading) {Text(stringResource(Res.string.nearby_reject))}
                        }
                        else -> Unit
                    }
                }
            }}
        }
    }
}

private fun FriendshipState.friendshipMessage(): StringResource = when(this) {
    FriendshipState.ACCEPTED -> Res.string.nearby_friend_accepted
    FriendshipState.REQUEST_SENT -> Res.string.nearby_friend_request_sent
    FriendshipState.REQUEST_RECEIVED -> Res.string.nearby_friend_request_received
    FriendshipState.BLOCKED -> Res.string.nearby_friend_blocked
}
