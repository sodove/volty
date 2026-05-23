package com.volty.app.presentation.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.welcome_add_battery
import volty.composeapp.generated.resources.welcome_quick_connect
import volty.composeapp.generated.resources.welcome_subtitle
import volty.composeapp.generated.resources.welcome_title

@Composable
fun WelcomeScreen(component: WelcomeComponent) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        VoltyLogoMark()
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(Res.string.welcome_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.welcome_subtitle),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = component::onAddBattery,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
        ) {
            Text(stringResource(Res.string.welcome_add_battery))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = component::onQuickConnect,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(stringResource(Res.string.welcome_quick_connect))
        }
    }
}

@Composable
private fun VoltyLogoMark() {
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary
        )
    )
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(80.dp)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 32.dp, bottomEnd = 28.dp, bottomStart = 18.dp))
            .background(gradient),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "V",
            color = Color.White,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
