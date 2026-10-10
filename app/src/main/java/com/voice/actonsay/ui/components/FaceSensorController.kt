package com.voice.actonsay.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sqrt

/**
 * 汇总提供给 OuO 表情的物理传感器与设备状态。
 */
@Stable
class FaceSensorState {
    /** 手机左右倾斜归一化偏移（-1..1），用于眼神/面部跟随。 */
    var tiltX by mutableFloatStateOf(0f)
        internal set

    /** 手机前后倾斜归一化偏移（-1..1），用于眼神/面部跟随。 */
    var tiltY by mutableFloatStateOf(0f)
        internal set

    /** 环境是否极暗（光线传感器 < 3 lux）。 */
    var isDarkEnvironment by mutableStateOf(false)
        internal set

    /** 是否正在充电。 */
    var isCharging by mutableStateOf(false)
        internal set

    /** 是否处于低电量（<= 15%）。 */
    var isLowBattery by mutableStateOf(false)
        internal set
}

/**
 * 在界面处于前台 RESUMED 且 [enabled] 开启时监听传感器；离开前台立即注销，后台零功耗。
 */
@Composable
fun rememberFaceSensorState(
    enabled: Boolean,
    onShake: () -> Unit = {}
): FaceSensorState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { FaceSensorState() }
    val currentOnShake by rememberUpdatedState(onShake)

    DisposableEffect(context, lifecycleOwner, enabled) {
        if (!enabled) {
            state.tiltX = 0f
            state.tiltY = 0f
            state.isDarkEnvironment = false
            return@DisposableEffect onDispose {}
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        var lastShakeHit = 0L
        var shakeCount = 0
        var lastTriggerTime = 0L

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]

                        // 低通滤波平滑倾斜方向，用于眼神视差偏移
                        val targetX = (-x / 7.5f).coerceIn(-1f, 1f)
                        val targetY = ((y - 5.5f) / 6.5f).coerceIn(-1f, 1f)
                        state.tiltX += (targetX - state.tiltX) * 0.18f
                        state.tiltY += (targetY - state.tiltY) * 0.18f

                        // 摇晃检测：计算合成加速度与标准重力的差值
                        val magnitude = sqrt(x * x + y * y + z * z)
                        val delta = kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH)
                        if (delta > 11.5f) {
                            val now = System.currentTimeMillis()
                            if (now - lastShakeHit > 650L) shakeCount = 0
                            lastShakeHit = now
                            shakeCount++
                            if (shakeCount >= 3 && now - lastTriggerTime > 3500L) {
                                shakeCount = 0
                                lastTriggerTime = now
                                currentOnShake()
                            }
                        }
                    }

                    Sensor.TYPE_LIGHT -> {
                        val lux = event.values.firstOrNull() ?: return
                        state.isDarkEnvironment = lux < 3.0f
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                state.isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                if (level >= 0) {
                    state.isLowBattery = (level * 100 / scale) <= 15
                }
            }
        }

        var registered = false
        fun register() {
            if (registered) return
            registered = true
            accelerometer?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            lightSensor?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            runCatching {
                context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        }

        fun unregister() {
            if (!registered) return
            registered = false
            sensorManager?.unregisterListener(sensorListener)
            runCatching { context.unregisterReceiver(batteryReceiver) }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }

    return state
}
