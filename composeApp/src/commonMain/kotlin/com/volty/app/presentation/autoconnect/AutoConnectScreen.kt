package com.volty.app.presentation.autoconnect

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AutoConnectScreen(component: AutoConnectComponent) {
    val state by component.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state.phase) {
            AutoConnectComponent.Phase.Counting -> {
                CountdownRing(seconds = state.countdownSec)
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "${state.vehicle?.name ?: "Battery"} found",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Auto-connecting in ${state.countdownSec} second${if (state.countdownSec == 1) "" else "s"}.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                state.vehicle?.let { VehicleCard(name = it.name, bmsType = it.bmsType.label) }
                Spacer(Modifier.height(20.dp))
                Button(onClick = component::onConnectNow) { Text("Connect now") }
                TextButton(onClick = component::onCancel) { Text("Cancel") }
            }
            AutoConnectComponent.Phase.Connecting -> {
                CircularProgressIndicator(modifier = Modifier.size(80.dp))
                Spacer(Modifier.height(20.dp))
                Text("Connecting…", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = component::onCancel) { Text("Cancel") }
            }
            AutoConnectComponent.Phase.Connected -> {
                Text("Connected", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
            }
            AutoConnectComponent.Phase.Failed -> {
                Text(
                    text = "Connection failed",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.failure ?: "Unknown error",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = component::onRetry) { Text("Try again") }
                TextButton(onClick = component::onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun CountdownRing(seconds: Int) {
    val transition = rememberInfiniteTransition(label = "ring")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(10000, easing = LinearEasing)),
        label = "rot"
    )
    val ringColor = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(modifier = Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().rotate(rotation)) {
            drawCircle(color = outline, style = Stroke(width = 4.dp.toPx()))
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx()),
                topLeft = Offset.Zero,
                size = Size(size.width, size.height)
            )
        }
        Text(
            text = "$seconds",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun VehicleCard(name: String, bmsType: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(14.dp, 22.dp, 14.dp, 22.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text("⚡", color = Color.White, fontSize = 16.sp)
        }
        Column {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(bmsType, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
