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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voice.actonsay.agent.SystemPermissions
import com.voice.actonsay.asr.AsrManager
import kotlinx.coroutines.launch
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

    // 工具首次用到通讯录、日历、电话或短信时才申请；被永久拒绝后不再自动弹窗，只提示去“手机权限”设置。
    private val toolPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val denied = results.filterValues { !it }.keys
        val permanent = denied.filter { !shouldShowRequestPermissionRationale(it) }
        if (permanent.isNotEmpty()) {
            AsrManager.showError("${permanent.joinToString("、") { SystemPermissions.labelOf(it) }}权限已被拒绝，可在「设置 › 手机权限」中重新开启。")
        } else if (denied.isNotEmpty()) {
            AsrManager.showError("未授予${denied.joinToString("、") { SystemPermissions.labelOf(it) }}权限，相关操作无法完成。")
        }
    }

    private fun requestToolPermissions(requested: List<String>) {
        if (requested.isEmpty()) return
        val prefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
        val asked = prefs.getStringSet("asked", emptySet()).orEmpty()
        // 申请过且系统不再显示说明时，说明用户选择了“不再询问”，弹窗不会出现。
        val blocked = requested.filter { it in asked && !shouldShowRequestPermissionRationale(it) }
        val askable = requested - blocked.toSet()
        if (blocked.isNotEmpty()) {
            AsrManager.showError("需要${blocked.joinToString("、") { SystemPermissions.labelOf(it) }}权限，请在「设置 › 手机权限」中打开系统设置授权。")
        }
        if (askable.isEmpty()) return
        prefs.edit().putStringSet("asked", asked + askable).apply()
        toolPermissions.launch(askable.toTypedArray())
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SystemPermissions.pending.collect { pending ->
                    if (pending.isNotEmpty()) requestToolPermissions(SystemPermissions.takePending(this@MainActivity))
                }
            }
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

    companion object {
        /** 与手机权限设置页共用，记录哪些权限已经申请过。 */
        const val PERMISSION_PREFS = "actonsay_permissions"
    }
}
