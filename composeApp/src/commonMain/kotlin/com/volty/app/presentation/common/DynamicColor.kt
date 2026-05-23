package com.volty.app.presentation.common

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

expect fun supportsDynamicColor(): Boolean

@Composable
expect fun dynamicVoltyColors(darkTheme: Boolean): ColorScheme
