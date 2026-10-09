package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.social.FriendshipState
import ru.sodovaya.volty.domain.social.SocialSession
import volty.composeapp.generated.resources.*

@Composable
internal fun NearbyFriendsView(
    state: VoiceSpacesNearbyState,
    component: DefaultVoiceSpacesNearbyComponent,
    modifier: Modifier,
) {
    val page = state.friends

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = page.query,
                    onValueChange = component::setFriendQuery,
                    modifier = Modifier.weight(1f).widthIn(max = 520.dp),
                    label = { Text(stringResource(Res.string.nearby_friend_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { if (!page.searchLoading) component.searchFriends() },
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = component::searchFriends,
                            enabled = !page.searchLoading,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(Res.string.nearby_friend_search),
                            )
                        }
                    },
                )
                IconButton(
                    onClick = component::refreshFriends,
                    enabled = !page.loading,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(Res.string.nearby_refresh),
                    )
                }
            }
        }

        if (page.searchLoading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        page.searchError?.let { error ->
            item {
                Text(
                    text = stringResource(error.shellMessage()),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        page.results?.let { results ->
            if (results.isEmpty()) {
                item { Text(stringResource(Res.string.nearby_friend_search_empty)) }
            }
            items(results) { user ->
                val currentUserId = (state.session as? SocialSession.Authenticated)?.userId
                val canRequest = user.state == null && user.friendshipId == null && user.userId != currentUserId
                FriendRow(
                    displayName = user.displayName,
                    subtitle = user.state?.let { stringResource(it.friendshipMessage()) },
                    trailing = {
                        if (canRequest) {
                            TextButton(
                                onClick = { component.requestFriend(user.userId) },
                                enabled = !state.busy,
                            ) {
                                Text(stringResource(Res.string.nearby_friend_add))
                            }
                        }
                    },
                )
            }
        }

        if (page.items == null || page.items.isEmpty()) {
            item { FriendsSectionHeader(Res.string.nearby_friends_tab) }
        }
        if (page.loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        page.error?.let { error ->
            item {
                Text(
                    text = stringResource(error.shellMessage()),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        page.items?.let { friends ->
            if (friends.isEmpty()) {
                item { Text(stringResource(Res.string.nearby_friends_empty)) }
            } else {
                val incoming = friends.filter { it.state == FriendshipState.REQUEST_RECEIVED }
                val accepted = friends.filter { it.state == FriendshipState.ACCEPTED }
                val outgoing = friends.filter { it.state == FriendshipState.REQUEST_SENT }
                val blocked = friends.filter { it.state == FriendshipState.BLOCKED }

                if (incoming.isNotEmpty()) {
                    item { FriendsSectionHeader(Res.string.nearby_friend_incoming) }
                    items(incoming) { friend ->
                        FriendRow(
                            displayName = friend.displayName,
                            subtitle = stringResource(friend.state.friendshipMessage()),
                            trailing = {
                                TextButton(
                                    onClick = { component.respondToFriend(friend.friendshipId, true) },
                                    enabled = !state.busy && !page.loading,
                                ) {
                                    Text(stringResource(Res.string.nearby_accept))
                                }
                                TextButton(
                                    onClick = { component.respondToFriend(friend.friendshipId, false) },
                                    enabled = !state.busy && !page.loading,
                                ) {
                                    Text(stringResource(Res.string.nearby_reject))
                                }
                            },
                        )
                    }
                }

                if (accepted.isNotEmpty()) {
                    item { FriendsSectionHeader(Res.string.nearby_friends_tab) }
                    items(accepted) { friend ->
                        FriendRow(
                            displayName = friend.displayName,
                            trailing = {
                                FilledTonalIconButton(
                                    onClick = { component.callFriend(friend.userId) },
                                    enabled = !state.busy && !page.loading && page.error == null,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Call,
                                        contentDescription = stringResource(Res.string.nearby_shell_call),
                                    )
                                }
                            },
                        )
                    }
                }

                if (outgoing.isNotEmpty()) {
                    item { FriendsSectionHeader(Res.string.nearby_friend_outgoing) }
                    items(outgoing) { friend ->
                        FriendRow(
                            displayName = friend.displayName,
                            subtitle = stringResource(friend.state.friendshipMessage()),
                        )
                    }
                }

                if (blocked.isNotEmpty()) {
                    item { FriendsSectionHeader(Res.string.nearby_friend_blocked) }
                    items(blocked) { friend ->
                        FriendRow(
                            displayName = friend.displayName,
                            subtitle = stringResource(friend.state.friendshipMessage()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendsSectionHeader(label: StringResource) {
    Text(
        text = stringResource(label),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FriendRow(
    displayName: String,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FriendAvatar(displayName)
            Column(Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun FriendAvatar(displayName: String) {
    val initial = displayName.trim().firstOrNull()?.uppercase() ?: "?"
    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun FriendshipState.friendshipMessage(): StringResource = when (this) {
    FriendshipState.ACCEPTED -> Res.string.nearby_friend_accepted
    FriendshipState.REQUEST_SENT -> Res.string.nearby_friend_request_sent
    FriendshipState.REQUEST_RECEIVED -> Res.string.nearby_friend_request_received
    FriendshipState.BLOCKED -> Res.string.nearby_friend_blocked
}
