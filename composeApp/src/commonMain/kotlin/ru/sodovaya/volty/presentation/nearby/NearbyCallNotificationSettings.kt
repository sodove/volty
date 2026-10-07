package ru.sodovaya.volty.presentation.nearby

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform notification settings only; never grants voice or account authority. */
@Composable
internal expect fun NearbyCallNotificationSettings(modifier: Modifier = Modifier)
