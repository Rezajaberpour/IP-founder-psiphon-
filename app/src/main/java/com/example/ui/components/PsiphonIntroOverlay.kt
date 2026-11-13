package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PsiphonCrimson
import com.example.ui.theme.PsiphonWarmTop
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PsiphonIntroOverlay(
    visible: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logoScale = remember { Animatable(0.35f) }
    val textAlpha = remember { Animatable(0f) }
    val textSlideY = remember { Animatable(24f) }

    val infiniteTransition = rememberInfiniteTransition(label = "psiphon_intro_pulse")
    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_1"
    )
    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_2"
    )
    val sweepRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_rotation"
    )

    LaunchedEffect(visible) {
        if (visible) {
            launch {
                logoScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            delay(220)
            launch {
                textAlpha.animateTo(1f, animationSpec = tween(480))
            }
            launch {
                textSlideY.animateTo(0f, animationSpec = tween(480, easing = FastOutSlowInEasing))
            }
            delay(1750)
            onFinished()
        }
    }

    AnimatedVisibility(
        visible = visible,
        exit = fadeOut(animationSpec = tween(350)) + scaleOut(targetScale = 1.06f, animationSpec = tween(350)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            PsiphonWarmTop,
                            ObsidianBg,
                            Color(0xFF09090B)
                        )
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFinished
                )
                .testTag("psiphon_intro_overlay"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Box(
                    modifier = Modifier.size(196.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Expanding Psiphon radar & tunnel waves
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxRadius = size.minDimension / 2f

                        // Ambient crimson glow
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    PsiphonCrimson.copy(alpha = 0.28f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = maxRadius
                            ),
                            radius = maxRadius,
                            center = center
                        )

                        // Pulse Ring 1 (Psiphon Crimson)
                        drawCircle(
                            color = PsiphonCrimson.copy(alpha = (1f - pulse1) * 0.65f),
                            radius = maxRadius * (0.38f + pulse1 * 0.60f),
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )

                        // Pulse Ring 2 (Electric Blue)
                        drawCircle(
                            color = ElectricCyan.copy(alpha = (1f - pulse2) * 0.50f),
                            radius = maxRadius * (0.38f + pulse2 * 0.60f),
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // Rotating orbital scanner arc
                        val arcDiameter = maxRadius * 1.18f
                        drawArc(
                            color = ElectricCyan.copy(alpha = 0.85f),
                            startAngle = sweepRotation,
                            sweepAngle = 72f,
                            useCenter = false,
                            topLeft = Offset(center.x - arcDiameter / 2f, center.y - arcDiameter / 2f),
                            size = Size(arcDiameter, arcDiameter),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = PsiphonCrimson.copy(alpha = 0.85f),
                            startAngle = sweepRotation + 180f,
                            sweepAngle = 72f,
                            useCenter = false,
                            topLeft = Offset(center.x - arcDiameter / 2f, center.y - arcDiameter / 2f),
                            size = Size(arcDiameter, arcDiameter),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // Central Psiphon-inspired Crimson Shield & Stylized "P" Tunnel Emblem
                    Canvas(
                        modifier = Modifier
                            .size(92.dp)
                            .graphicsLayer(
                                scaleX = logoScale.value,
                                scaleY = logoScale.value
                            )
                    ) {
                        val r = size.minDimension / 2f
                        val c = Offset(size.width / 2f, size.height / 2f)

                        drawCircle(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFFFF4D43), PsiphonCrimson)
                            ),
                            radius = r,
                            center = c
                        )

                        // Stylized geometric P-tunnel glyph
                        val stemX = c.x - r * 0.24f
                        val topY = c.y - r * 0.42f
                        val bottomY = c.y + r * 0.42f
                        val midY = c.y + r * 0.04f

                        drawLine(
                            color = Color.White,
                            start = Offset(stemX, bottomY),
                            end = Offset(stemX, topY),
                            strokeWidth = r * 0.22f,
                            cap = StrokeCap.Round
                        )

                        val loopPath = Path().apply {
                            moveTo(stemX, topY)
                            lineTo(c.x + r * 0.08f, topY)
                            cubicTo(
                                c.x + r * 0.48f, topY,
                                c.x + r * 0.48f, midY,
                                c.x + r * 0.08f, midY
                            )
                            lineTo(stemX, midY)
                        }
                        drawPath(
                            path = loopPath,
                            color = Color.White,
                            style = Stroke(width = r * 0.20f, cap = StrokeCap.Round)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .alpha(textAlpha.value)
                        .graphicsLayer(translationY = textSlideY.value)
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimaryDark
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.splash_subtitle),
                        style = MaterialTheme.typography.titleSmall,
                        color = ElectricCyan
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    LinearProgressIndicator(
                        color = PsiphonCrimson,
                        trackColor = Color(0xFF28282C),
                        modifier = Modifier
                            .width(150.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.splash_tap_skip),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondaryDark.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
