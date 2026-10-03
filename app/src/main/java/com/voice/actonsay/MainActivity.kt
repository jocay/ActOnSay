package com.voice.actonsay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.asr.AsrService
import com.voice.actonsay.tasks.TaskRuntime
import com.voice.actonsay.ui.ActOnSayApp
import com.voice.actonsay.ui.theme.ActOnSayTheme

class MainActivity : ComponentActivity() {
    private var openTasksRequested by mutableIntStateOf(0)

    // 任务前台服务与完成通知依赖通知权限；系统对话框结束后再请求电池优化豁免，避免两个系统界面叠加。
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestBatteryOptimizationExemption()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_tasks", false)) openTasksRequested++
    }

    override fun onStart() {
        super.onStart()
        TaskRuntime.startFromForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("open_tasks", false)) openTasksRequested++
        AsrManager.init(this)
        enableEdgeToEdge()

        // 首次创建时按设置自动开启待机
        if (savedInstanceState == null) {
            val st = AsrManager.state.value
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (st.autoStartOnLaunch && granted && !st.isListening) {
                AsrService.start(this)
            }
        }

        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission && savedInstanceState == null) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestBatteryOptimizationExemption()
        }

        setContent {
            val state by AsrManager.state.collectAsState()

            LaunchedEffect(state.keepScreenOn, state.isListening) {
                if (state.keepScreenOn && state.isListening) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            ActOnSayTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
                ActOnSayApp(openTasksRequest = openTasksRequested)
            }
        }
    }

    /** 无 root 时的退路：请求系统忽略电池优化，减少后台被断网的概率。 */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
            )
        }
    }
}
