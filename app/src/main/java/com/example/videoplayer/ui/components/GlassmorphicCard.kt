package com.example.videoplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.videoplayer.ui.theme.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit) = composed {
    if (!enabled) return@composed this
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 720f),
        label = "bounceScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                },
                onTap = { onClick() }
            )
        }
}

@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.dp,
    shadowElevation: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                clip = false,
                ambientColor = PrimaryNeonPurple.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.8f)
            )
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF191C25).copy(alpha = 0.94f),
                        CarbonCard.copy(alpha = 0.82f)
                    )
                )
            )
            .border(
                width = borderWidth,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.14f),
                        SecondaryNeonCyan.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.035f)
                    )
                ),
                shape = shape
            ),
        content = content
    )
}

@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D0F16), CarbonBg, Color(0xFF08090C))
                )
            ),
        content = content
    )
}
