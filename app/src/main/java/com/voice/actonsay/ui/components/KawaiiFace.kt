package com.voice.actonsay.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voice.actonsay.asr.AsrState
import com.voice.actonsay.asr.EmotionAnalyzer
import com.voice.actonsay.asr.EngineState
import com.voice.actonsay.asr.FaceMood
import com.voice.actonsay.media.MusicPlayer
import com.voice.actonsay.tasks.TaskRecord
import com.voice.actonsay.ui.icons.AppIcons
import com.voice.actonsay.ui.theme.engineStateColor
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * OuO 风格互动表情组件：支持根据传感器、触摸手势、语音状态与对话情绪展示不同可爱表情。
 */
@Composable
fun KawaiiFace(
    state: AsrState,
    audioLevel: Float,
    modifier: Modifier = Modifier,
    width: Dp = 236.dp,
    height: Dp = 168.dp,
    fullScreen: Boolean = false,
    activeTask: TaskRecord? = null,
    onCenterTap: (() -> Unit)? = null
) {
    val musicPlaying by MusicPlayer.isPlaying.collectAsState()

    // 瞬时交互表情（摸头、戳眼、摇晃等）及到期恢复计时
    var transientMood by remember { mutableStateOf<FaceMood?>(null) }
    var transientToken by remember { mutableIntStateOf(0) }
    var winkLeft by remember { mutableStateOf(false) }
    var winkRight by remember { mutableStateOf(false) }
    var pokeCount by remember { mutableIntStateOf(0) }
    var lastPokeTime by remember { mutableStateOf(0L) }
    var rubProgress by remember { mutableFloatStateOf(0f) }

    fun triggerTransient(mood: FaceMood, durationMs: Long = 2600L, leftWink: Boolean = false, rightWink: Boolean = false) {
        transientMood = mood
        winkLeft = leftWink
        winkRight = rightWink
        transientToken++
    }

    LaunchedEffect(transientToken) {
        if (transientToken == 0) return@LaunchedEffect
        val waitMs = if (transientMood == FaceMood.DIZZY) 3200L else 2400L
        delay(waitMs)
        transientMood = null
        winkLeft = false
        winkRight = false
        rubProgress = 0f
    }

    val sensorState = rememberFaceSensorState(
        enabled = state.ouoSensorEnabled,
        onShake = { triggerTransient(FaceMood.DIZZY, durationMs = 3200L) }
    )

    val mood = EmotionAnalyzer.resolveMood(
        state = state,
        musicPlaying = musicPlaying,
        transientMood = transientMood,
        isDarkEnvironment = sensorState.isDarkEnvironment,
        isCharging = sensorState.isCharging,
        isLowBattery = sensorState.isLowBattery,
        activeTask = activeTask
    )

    // 自然眨眼与闲置视线微动
    var blinking by remember { mutableStateOf(false) }
    var idleGazeX by remember { mutableFloatStateOf(0f) }
    var idleGazeY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(mood) {
        if (mood == FaceMood.SLEEPY || mood == FaceMood.DIZZY) {
            blinking = false
            idleGazeX = 0f
            idleGazeY = 0f
            return@LaunchedEffect
        }
        while (true) {
            delay(Random.nextLong(2200L, 4600L))
            blinking = true
            delay(115L)
            blinking = false
            // 偶发双眨眼
            if (Random.nextFloat() < 0.22f) {
                delay(110L)
                blinking = true
                delay(100L)
                blinking = false
            }
            // 待机时眼神偶尔轻轻左右张望
            if (mood == FaceMood.NORMAL || mood == FaceMood.LISTENING || mood == FaceMood.MUSIC) {
                if (Random.nextFloat() < 0.45f) {
                    idleGazeX = (Random.nextFloat() - 0.5f) * 0.7f
                    idleGazeY = (Random.nextFloat() - 0.5f) * 0.4f
                } else {
                    idleGazeX = 0f
                    idleGazeY = 0f
                }
            } else if (mood == FaceMood.THINKING) {
                idleGazeX = 0.45f
                idleGazeY = -0.55f
            } else {
                idleGazeX = 0f
                idleGazeY = 0f
            }
        }
    }

    // 动画状态
    val targetEyeOpen = when {
        blinking -> 0.08f
        mood == FaceMood.SLEEPY -> 0.10f
        mood == FaceMood.LISTENING || mood == FaceMood.SURPRISED -> 1.16f
        else -> 1.0f
    }
    val eyeOpenState = animateFloatAsState(
        targetValue = targetEyeOpen,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "eyeOpen"
    )
    val lookXState = animateFloatAsState(
        targetValue = (sensorState.tiltX * 0.75f + idleGazeX).coerceIn(-1f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "lookX"
    )
    val lookYState = animateFloatAsState(
        targetValue = (sensorState.tiltY * 0.75f + idleGazeY).coerceIn(-1f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "lookY"
    )
    val blushAlphaState = animateFloatAsState(
        targetValue = when (mood) {
            FaceMood.SHY, FaceMood.ENJOY -> 0.78f
            FaceMood.HAPPY, FaceMood.PROUD, FaceMood.MUSIC -> 0.48f
            FaceMood.LISTENING -> 0.28f
            else -> 0.14f
        },
        animationSpec = tween(350),
        label = "blushAlpha"
    )
    val voiceBounceState = animateFloatAsState(
        targetValue = if (state.engineState == EngineState.ACTIVE_LISTENING) audioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(stiffness = 650f, dampingRatio = 0.65f),
        label = "voiceBounce"
    )

    val speaking = state.engineState == EngineState.SPEAKING_TTS || mood == FaceMood.SPEAKING
    val continuousAnimNeeded = speaking || mood == FaceMood.DIZZY || mood == FaceMood.MUSIC || mood == FaceMood.THINKING || mood == FaceMood.SLEEPY
    val loopTransition = if (continuousAnimNeeded) rememberInfiniteTransition(label = "faceLoop") else null
    val wavePhaseState = loopTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase"
    )
    val talkPhaseState = if (loopTransition != null && speaking) {
        loopTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Reverse),
            label = "talkPhase"
        )
    } else null

    val accent by animateColorAsState(
        targetValue = if (state.engineState == EngineState.IDLE) MaterialTheme.colorScheme.primary else engineStateColor(state.engineState),
        animationSpec = tween(400),
        label = "faceAccent"
    )
    val bgColor by animateColorAsState(
        targetValue = if (fullScreen) {
            Color(0xFF10131A)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(400),
        label = "faceBg"
    )
    val strokeColor = if (fullScreen) Color(0xFFF5F7FF) else MaterialTheme.colorScheme.onSurface
    val cheekColor = Color(0xFFFF7B9C)

    val containerModifier = if (fullScreen) {
        modifier.fillMaxSize().background(bgColor)
    } else {
        modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(36.dp))
            .background(bgColor)
    }

    Box(
        modifier = containerModifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val nx = offset.x / w
                        val ny = offset.y / h
                        val now = System.currentTimeMillis()
                        if (now - lastPokeTime > 2000L) pokeCount = 0
                        lastPokeTime = now

                        when {
                            // 点击左眼区域
                            nx in 0.14f..0.44f && ny in 0.22f..0.60f -> {
                                pokeCount++
                                if (pokeCount >= 3) {
                                    pokeCount = 0
                                    triggerTransient(FaceMood.ANGRY)
                                } else {
                                    triggerTransient(FaceMood.NORMAL, durationMs = 1200L, leftWink = true)
                                }
                            }
                            // 点击右眼区域
                            nx in 0.56f..0.86f && ny in 0.22f..0.60f -> {
                                pokeCount++
                                if (pokeCount >= 3) {
                                    pokeCount = 0
                                    triggerTransient(FaceMood.ANGRY)
                                } else {
                                    triggerTransient(FaceMood.NORMAL, durationMs = 1200L, rightWink = true)
                                }
                            }
                            // 点击脸颊两侧
                            ny in 0.45f..0.78f && (nx < 0.28f || nx > 0.72f) -> {
                                triggerTransient(FaceMood.SHY)
                            }
                            // 点击嘴巴/鼻子或中央
                            else -> {
                                if (onCenterTap != null && !fullScreen) {
                                    onCenterTap()
                                } else {
                                    val pick = if (Random.nextBoolean()) FaceMood.HAPPY else FaceMood.SURPRISED
                                    triggerTransient(pick, durationMs = 1800L)
                                }
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // 摸头 / 抚摸脸部：累积滑动距离触发享受表情
                        rubProgress = (rubProgress + (abs(dragAmount.x) + abs(dragAmount.y)) / 260f).coerceAtMost(2f)
                        if (rubProgress >= 0.65f) {
                            triggerTransient(FaceMood.ENJOY, durationMs = 2400L)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val unit = minOf(w, h)
            val lookX = lookXState.value
            val lookY = lookYState.value
            val eyeOpen = eyeOpenState.value
            val blushAlpha = blushAlphaState.value
            val voiceBounce = voiceBounceState.value
            val wavePhase = wavePhaseState?.value ?: 0f
            val talkOpen = talkPhaseState?.value ?: 0f

            // 外围柔和状态光晕（非全屏或倾听/思考时呼应主题色）
            val glowAlpha = if (state.engineState == EngineState.IDLE) 0.05f else 0.14f + voiceBounce * 0.16f
            drawCircle(
                color = accent.copy(alpha = glowAlpha.coerceIn(0f, 0.35f)),
                radius = unit * (0.48f + voiceBounce * 0.08f),
                center = Offset(w * 0.5f, h * 0.5f)
            )

            // 听歌时轻微上下律动
            val rhythmOffsetY = if (mood == FaceMood.MUSIC) sin(wavePhase * 2f * PI).toFloat() * unit * 0.025f else 0f
            val centerX = w * 0.5f + lookX * unit * 0.06f
            val centerY = h * 0.48f + lookY * unit * 0.05f - voiceBounce * unit * 0.02f + rhythmOffsetY

            val strokeWidth = (unit * 0.055f).coerceIn(6f, 22f)
            val eyeSpacing = unit * 0.24f
            val leftEyeCenter = Offset(centerX - eyeSpacing, centerY - unit * 0.04f)
            val rightEyeCenter = Offset(centerX + eyeSpacing, centerY - unit * 0.04f)
            val eyeRadiusX = unit * 0.095f
            val eyeRadiusY = unit * 0.125f

            // 1. 腮红
            val cheekY = centerY + unit * 0.11f
            val cheekOffsetX = eyeSpacing * 1.22f
            val cheekWidth = unit * 0.13f
            val cheekHeight = unit * 0.065f
            drawOval(
                color = cheekColor.copy(alpha = blushAlpha),
                topLeft = Offset(centerX - cheekOffsetX - cheekWidth / 2f, cheekY - cheekHeight / 2f),
                size = Size(cheekWidth, cheekHeight)
            )
            drawOval(
                color = cheekColor.copy(alpha = blushAlpha),
                topLeft = Offset(centerX + cheekOffsetX - cheekWidth / 2f, cheekY - cheekHeight / 2f),
                size = Size(cheekWidth, cheekHeight)
            )
            // 害羞斜线 (// //)
            if (mood == FaceMood.SHY || mood == FaceMood.ENJOY) {
                val slashStroke = Stroke(width = strokeWidth * 0.38f, cap = StrokeCap.Round)
                for (side in listOf(-1f, 1f)) {
                    val baseCx = centerX + side * cheekOffsetX
                    for (i in -1..1) {
                        val sx = baseCx + i * unit * 0.032f
                        drawLine(
                            color = cheekColor.copy(alpha = 0.85f),
                            start = Offset(sx + unit * 0.012f, cheekY - unit * 0.022f),
                            end = Offset(sx - unit * 0.012f, cheekY + unit * 0.022f),
                            strokeWidth = slashStroke.width,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // 2. 双眼绘制
            drawOuoEye(
                center = leftEyeCenter,
                radiusX = eyeRadiusX,
                radiusY = eyeRadiusY,
                openness = if (winkLeft) 0.08f else eyeOpen,
                isLeft = true,
                mood = mood,
                lookX = lookX,
                lookY = lookY,
                wavePhase = wavePhase,
                color = strokeColor,
                bgColor = bgColor,
                strokeWidth = strokeWidth
            )
            drawOuoEye(
                center = rightEyeCenter,
                radiusX = eyeRadiusX,
                radiusY = eyeRadiusY,
                openness = if (winkRight) 0.08f else eyeOpen,
                isLeft = false,
                mood = mood,
                lookX = lookX,
                lookY = lookY,
                wavePhase = wavePhase,
                color = strokeColor,
                bgColor = bgColor,
                strokeWidth = strokeWidth
            )

            // 3. 嘴巴绘制
            val mouthCenter = Offset(centerX, centerY + unit * 0.145f)
            drawOuoMouth(
                center = mouthCenter,
                unit = unit,
                mood = mood,
                speaking = speaking,
                talkOpen = talkOpen,
                audioLevel = voiceBounce,
                color = strokeColor,
                accentColor = cheekColor,
                strokeWidth = strokeWidth * 0.82f
            )

            // 4. 情绪装饰符号（爱心、音符、睡眠气泡、思考点）
            drawMoodAccessory(
                center = Offset(centerX, centerY),
                unit = unit,
                mood = mood,
                wavePhase = wavePhase,
                accent = accent,
                cheekColor = cheekColor
            )
        }

        // 非全屏模式下右上角提示当前表情标签
        if (!fullScreen) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = "${mood.kaomoji} · ${mood.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun DrawScope.drawOuoEye(
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    openness: Float,
    isLeft: Boolean,
    mood: FaceMood,
    lookX: Float,
    lookY: Float,
    wavePhase: Float,
    color: Color,
    bgColor: Color,
    strokeWidth: Float
) {
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // 眯眼或开心弧线眼 (^ ^ / ˘ ˘)
    if (openness < 0.22f || mood == FaceMood.HAPPY || mood == FaceMood.ENJOY || mood == FaceMood.SLEEPY) {
        val path = Path().apply {
            val arch = when {
                mood == FaceMood.SLEEPY || openness < 0.22f -> 0f
                mood == FaceMood.ENJOY -> -radiusY * 0.55f
                else -> -radiusY * 0.85f
            }
            moveTo(center.x - radiusX, center.y)
            quadraticTo(center.x, center.y + arch, center.x + radiusX, center.y)
        }
        drawPath(path = path, color = color, style = stroke)
        return
    }

    when (mood) {
        FaceMood.PROUD, FaceMood.ANGRY -> {
            // > < 眼神
            val path = Path().apply {
                if (isLeft) {
                    moveTo(center.x - radiusX * 0.85f, center.y - radiusY * 0.55f)
                    lineTo(center.x + radiusX * 0.75f, center.y)
                    lineTo(center.x - radiusX * 0.85f, center.y + radiusY * 0.55f)
                } else {
                    moveTo(center.x + radiusX * 0.85f, center.y - radiusY * 0.55f)
                    lineTo(center.x - radiusX * 0.75f, center.y)
                    lineTo(center.x + radiusX * 0.85f, center.y + radiusY * 0.55f)
                }
            }
            drawPath(path = path, color = color, style = stroke)
            if (mood == FaceMood.ANGRY) {
                // 生气上挑眉
                val dir = if (isLeft) 1f else -1f
                drawLine(
                    color = color,
                    start = Offset(center.x - dir * radiusX * 0.9f, center.y - radiusY * 1.15f),
                    end = Offset(center.x + dir * radiusX * 0.8f, center.y - radiusY * 0.75f),
                    strokeWidth = strokeWidth * 0.85f,
                    cap = StrokeCap.Round
                )
            }
        }

        FaceMood.SAD -> {
            // TwT 委屈下垂眼 + 泪滴
            drawLine(
                color = color,
                start = Offset(center.x - radiusX * 0.95f, center.y - radiusY * 0.25f),
                end = Offset(center.x + radiusX * 0.95f, center.y - radiusY * 0.25f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFF64B5F6),
                start = Offset(center.x, center.y - radiusY * 0.05f),
                end = Offset(center.x, center.y + radiusY * 0.95f),
                strokeWidth = strokeWidth * 0.75f,
                cap = StrokeCap.Round
            )
        }

        FaceMood.DIZZY -> {
            // @_@ 旋转蚊香圈眼
            val angleOffset = wavePhase * 2f * PI.toFloat() * (if (isLeft) 1f else -1f)
            val spiral = Path()
            val steps = 38
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val angle = t * 3.8f * PI.toFloat() + angleOffset
                val r = t * radiusX * 1.05f
                val px = center.x + cos(angle) * r
                val py = center.y + sin(angle) * r
                if (i == 0) spiral.moveTo(px, py) else spiral.lineTo(px, py)
            }
            drawPath(path = spiral, color = color, style = Stroke(width = strokeWidth * 0.65f, cap = StrokeCap.Round))
        }

        else -> {
            // 经典 OuO 圆润大眼睛（带眼神跟随高光）
            val scaleY = openness.coerceIn(0.15f, 1.25f)
            val rx = radiusX * if (mood == FaceMood.SURPRISED) 1.12f else 1.0f
            val ry = radiusY * scaleY
            drawRoundRect(
                color = color,
                topLeft = Offset(center.x - rx, center.y - ry),
                size = Size(rx * 2f, ry * 2f),
                cornerRadius = CornerRadius(rx, rx)
            )
            // 瞳孔内部高光随视线偏移
            val highlightOffsetX = lookX * rx * 0.28f - rx * 0.22f
            val highlightOffsetY = lookY * ry * 0.28f - ry * 0.26f
            drawCircle(
                color = bgColor,
                radius = rx * 0.30f,
                center = Offset(center.x + highlightOffsetX, center.y + highlightOffsetY)
            )
            drawCircle(
                color = bgColor.copy(alpha = 0.75f),
                radius = rx * 0.14f,
                center = Offset(center.x + highlightOffsetX + rx * 0.42f, center.y + highlightOffsetY + ry * 0.46f)
            )
        }
    }
}

private fun DrawScope.drawOuoMouth(
    center: Offset,
    unit: Float,
    mood: FaceMood,
    speaking: Boolean,
    talkOpen: Float,
    audioLevel: Float,
    color: Color,
    accentColor: Color,
    strokeWidth: Float
) {
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val mouthW = unit * 0.15f
    val mouthH = unit * 0.065f

    if (speaking) {
        // 播报时嘴巴张合动画 (0o0)
        val openRatio = (0.35f + talkOpen * 0.65f).coerceIn(0.25f, 1f)
        val rx = mouthW * 0.62f
        val ry = mouthH * (0.55f + openRatio * 0.95f)
        drawOval(
            color = accentColor.copy(alpha = 0.85f),
            topLeft = Offset(center.x - rx, center.y - ry),
            size = Size(rx * 2f, ry * 2f)
        )
        drawOval(
            color = color,
            topLeft = Offset(center.x - rx, center.y - ry),
            size = Size(rx * 2f, ry * 2f),
            style = stroke
        )
        return
    }

    when (mood) {
        FaceMood.SURPRISED, FaceMood.DIZZY -> {
            val r = unit * 0.052f
            drawCircle(color = color, radius = r, center = center, style = stroke)
        }

        FaceMood.SAD, FaceMood.ANGRY -> {
            // 委屈/不高兴下撇嘴 (n / ^)
            val path = Path().apply {
                moveTo(center.x - mouthW * 0.75f, center.y + mouthH * 0.35f)
                quadraticTo(center.x, center.y - mouthH * 0.65f, center.x + mouthW * 0.75f, center.y + mouthH * 0.35f)
            }
            drawPath(path = path, color = color, style = stroke)
        }

        FaceMood.SLEEPY -> {
            drawLine(
                color = color,
                start = Offset(center.x - mouthW * 0.45f, center.y),
                end = Offset(center.x + mouthW * 0.45f, center.y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }

        FaceMood.HAPPY, FaceMood.ENJOY, FaceMood.PROUD, FaceMood.SHY, FaceMood.MUSIC -> {
            // 经典猫咪 w 嘴 (^w^)
            val path = Path().apply {
                moveTo(center.x - mouthW, center.y - mouthH * 0.15f)
                quadraticTo(center.x - mouthW * 0.5f, center.y + mouthH * 0.95f, center.x, center.y - mouthH * 0.05f)
                quadraticTo(center.x + mouthW * 0.5f, center.y + mouthH * 0.95f, center.x + mouthW, center.y - mouthH * 0.15f)
            }
            drawPath(path = path, color = color, style = stroke)
        }

        else -> {
            // 经典 u 微笑嘴 (0u0)，倾听时随音量微张
            val extraDepth = mouthH * (0.85f + audioLevel * 0.45f)
            val path = Path().apply {
                moveTo(center.x - mouthW * 0.72f, center.y - mouthH * 0.2f)
                quadraticTo(center.x, center.y + extraDepth, center.x + mouthW * 0.72f, center.y - mouthH * 0.2f)
            }
            drawPath(path = path, color = color, style = stroke)
        }
    }
}

private fun DrawScope.drawMoodAccessory(
    center: Offset,
    unit: Float,
    mood: FaceMood,
    wavePhase: Float,
    accent: Color,
    cheekColor: Color
) {
    when (mood) {
        FaceMood.ENJOY, FaceMood.HAPPY -> {
            // 右上角漂浮小爱心
            val bob = sin(wavePhase * 2f * PI).toFloat() * unit * 0.02f
            val hx = center.x + unit * 0.34f
            val hy = center.y - unit * 0.26f + bob
            val s = unit * 0.048f
            val heart = Path().apply {
                moveTo(hx, hy + s * 0.65f)
                cubicTo(hx - s * 1.2f, hy - s * 0.1f, hx - s * 0.6f, hy - s * 1.05f, hx, hy - s * 0.35f)
                cubicTo(hx + s * 0.6f, hy - s * 1.05f, hx + s * 1.2f, hy - s * 0.1f, hx, hy + s * 0.65f)
                close()
            }
            drawPath(path = heart, color = cheekColor)
        }

        FaceMood.THINKING -> {
            // 右上角三个思考气泡圆点
            for (i in 0..2) {
                val pulse = sin((wavePhase + i * 0.22f) * 2f * PI).toFloat() * 0.5f + 0.5f
                drawCircle(
                    color = accent.copy(alpha = 0.45f + pulse * 0.45f),
                    radius = unit * (0.018f + i * 0.008f),
                    center = Offset(
                        center.x + unit * (0.28f + i * 0.055f),
                        center.y - unit * (0.22f + i * 0.055f)
                    )
                )
            }
        }

        FaceMood.SLEEPY -> {
            // 睡眠呼吸小气泡
            val r = unit * (0.025f + sin(wavePhase * 2f * PI).toFloat() * 0.008f).coerceAtLeast(0.01f)
            drawCircle(
                color = accent.copy(alpha = 0.5f),
                radius = r,
                center = Offset(center.x + unit * 0.32f, center.y - unit * 0.24f),
                style = Stroke(width = unit * 0.012f)
            )
        }

        else -> Unit
    }
}

/**
 * 全屏 OuO 桌宠互动模式：黑底全屏显示巨型可爱表情，支持摸头、戳眼、摇晃、语音唤醒和实时字幕。
 */
@Composable
fun OuoFullScreenDialog(
    state: AsrState,
    audioLevel: Float,
    activeTask: TaskRecord?,
    onMicTap: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF10131A))) {
            KawaiiFace(
                state = state,
                audioLevel = audioLevel,
                fullScreen = true,
                activeTask = activeTask,
                modifier = Modifier.fillMaxSize()
            )

            // 顶部退出栏与操作提示
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 28.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.10f),
                    shape = CircleShape
                ) {
                    Text(
                        text = "摸摸额头 · 戳戳眼睛 · 摇晃手机 · 直接呼叫唤醒词",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "退出全屏表情",
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            // 底部实时对话字幕与快捷麦克风按钮
            val lastAssistant = state.messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
            val subtitle = when {
                state.engineState == EngineState.ACTIVE_LISTENING && state.currentText.isNotBlank() -> state.currentText
                state.engineState == EngineState.ACTIVE_LISTENING -> "我在听，请说话…"
                state.engineState == EngineState.PROCESSING_FINAL -> "正在识别…"
                state.engineState == EngineState.THINKING_AI -> activeTask?.progress?.ifBlank { "正在思考…" } ?: "正在思考…"
                state.engineState == EngineState.SPEAKING_TTS && lastAssistant != null ->
                    lastAssistant.content.lineSequence().firstOrNull().orEmpty().take(80)
                else -> ""
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (subtitle.isNotBlank()) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.padding(bottom = 14.dp)
                    ) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = onMicTap,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = when (state.engineState) {
                                EngineState.IDLE -> AppIcons.Power
                                EngineState.SPEAKING_TTS -> AppIcons.Stop
                                else -> AppIcons.Mic
                            },
                            contentDescription = "语音交互"
                        )
                    }
                    Text(
                        text = when (state.engineState) {
                            EngineState.IDLE -> "点击开启待机"
                            EngineState.WAITING_WAKEWORD -> "待机中 · 可随时说话"
                            EngineState.ACTIVE_LISTENING -> "倾听中 · 点击结束"
                            EngineState.SPEAKING_TTS -> "播报中 · 点击停止"
                            else -> "处理中…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
