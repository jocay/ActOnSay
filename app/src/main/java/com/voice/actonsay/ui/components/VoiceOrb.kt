package com.voice.actonsay.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.EngineState
import com.voice.actonsay.ui.icons.AppIcons
import com.voice.actonsay.ui.theme.engineStateColor
import kotlin.math.PI
import kotlin.math.sin

/**
 * 语音助手的核心视觉：一个随状态变色、随音量呼吸的光球。
 *
 * @param audioLevel 麦克风电平 0..1，仅在倾听状态下影响外圈半径
 */
@Composable
fun VoiceOrb(
    state: EngineState,
    audioLevel: Float,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    icon: ImageVector? = null
) {
    val accent by animateColorAsState(engineStateColor(state), tween(400), label = "orbColor")
    val active = state != EngineState.IDLE
    val spinning = state == EngineState.THINKING_AI || state == EngineState.PROCESSING_FINAL
    val levelState = animateFloatAsState(
        targetValue = if (state == EngineState.ACTIVE_LISTENING) audioLevel else 0f,
        animationSpec = spring(stiffness = 600f, dampingRatio = 0.6f),
        label = "orbLevel"
    )

    val transition = if (active) rememberInfiniteTransition(label = "orbBreath") else null
    val breathState = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "breath"
    )
    val spinState = if (transition != null && spinning) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
            label = "spin"
        )
    } else null
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val iconTint = if (state == EngineState.IDLE) MaterialTheme.colorScheme.onSurfaceVariant else Color.White

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val base = this.size.minDimension / 2f
            val coreRadius = base * 0.42f
            val level = levelState.value

            // 外层：三道相位不同的呼吸环
            if (active) {
                val breath = breathState?.value ?: 0f
                val breathAmp = when (state) {
                    EngineState.WAITING_WAKEWORD -> 0.06f
                    EngineState.THINKING_AI -> 0.10f
                    EngineState.SPEAKING_TTS -> 0.12f
                    else -> 0.08f
                }
                for (i in 0 until 3) {
                    val phase = ((breath + i / 3f) % 1f)
                    val wave = sin(phase * 2f * PI).toFloat() * 0.5f + 0.5f
                    val radius = coreRadius + base * (0.18f + 0.14f * i) * (0.75f + wave * 0.25f + level * 0.9f) + base * breathAmp * wave
                    val alpha = (0.22f - 0.06f * i) * (0.6f + 0.4f * wave)
                    drawCircle(
                        color = accent.copy(alpha = alpha.coerceIn(0f, 1f)),
                        radius = radius.coerceAtMost(base),
                        center = center
                    )
                }
            } else {
                drawCircle(color = surfaceColor, radius = coreRadius + base * 0.2f, center = center)
            }

            // 思考态：旋转的弧线
            if (spinning) {
                val spin = spinState?.value ?: 0f
                val arcRadius = coreRadius + base * 0.16f
                drawArc(
                    brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0f), accent), center),
                    startAngle = spin,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = androidx.compose.ui.geometry.Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = base * 0.05f, cap = StrokeCap.Round)
                )
            }

            // 核心球体：径向渐变
            val coreBrush = if (active) {
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.95f), accent, accent.copy(alpha = 0.85f)),
                    center = Offset(center.x - coreRadius * 0.3f, center.y - coreRadius * 0.3f),
                    radius = coreRadius * 1.4f
                )
            } else {
                Brush.radialGradient(
                    colors = listOf(surfaceColor, surfaceColor.copy(alpha = 0.7f)),
                    center = center,
                    radius = coreRadius * 1.2f
                )
            }
            drawCircle(brush = coreBrush, radius = coreRadius * (1f + level * 0.08f), center = center)
            // 高光
            if (active) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = coreRadius * 0.35f,
                    center = Offset(center.x - coreRadius * 0.35f, center.y - coreRadius * 0.4f)
                )
            }
        }

        Icon(
            imageVector = icon ?: iconForState(state),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(size * 0.28f)
        )
    }
}

fun iconForState(state: EngineState): ImageVector = when (state) {
    EngineState.IDLE -> AppIcons.MicOff
    EngineState.WAITING_WAKEWORD -> AppIcons.Hearing
    EngineState.ACTIVE_LISTENING -> AppIcons.Mic
    EngineState.PROCESSING_FINAL -> AppIcons.GraphicEq
    EngineState.THINKING_AI -> AppIcons.Sparkle
    EngineState.SPEAKING_TTS -> AppIcons.VolumeUp
}

/** 倾听时的实时音量柱，用于状态条内的小型可视化。 */
@Composable
fun LevelBars(
    level: Float,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 5
) {
    val phaseState = if (active) {
        val transition = rememberInfiniteTransition(label = "bars")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "barsPhase"
        )
    } else null
    val smoothState = animateFloatAsState(if (active) level else 0f, spring(stiffness = 800f), label = "barsLevel")

    Canvas(modifier = modifier) {
        val t = phaseState?.value ?: 0f
        val smooth = smoothState.value
        val gap = size.width * 0.12f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val centerBias = 1f - kotlin.math.abs(i - (barCount - 1) / 2f) / ((barCount - 1) / 2f) * 0.5f
            val wobble = sin((t + i * 0.17f) * 2f * PI).toFloat() * 0.5f + 0.5f
            val h = if (active) {
                size.height * (0.2f + 0.8f * (smooth * centerBias * 0.9f + wobble * 0.15f).coerceIn(0f, 1f))
            } else {
                size.height * 0.2f
            }
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
            )
        }
    }
}
