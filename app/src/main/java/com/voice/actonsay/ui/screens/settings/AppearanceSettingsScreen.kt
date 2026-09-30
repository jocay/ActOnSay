package com.voice.actonsay.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.asr.ThemeMode
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint
import com.voice.actonsay.ui.components.SettingSwitchRow
import com.voice.actonsay.ui.icons.AppIcons

@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()

    SubPageScaffold(title = "外观与行为", onBack = onBack) {
        SectionCard(title = "主题") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                val modes = listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色")
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { AsrManager.setAppearance(mode, state.dynamicColor) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        label = { Text(label) }
                    )
                }
            }
            val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            SettingSwitchRow(
                title = "动态取色",
                subtitle = if (supportsDynamic) "跟随系统壁纸生成配色（Material You）" else "需要 Android 12 及以上",
                checked = state.dynamicColor && supportsDynamic,
                enabled = supportsDynamic,
                onCheckedChange = { AsrManager.setAppearance(state.themeMode, it) },
                icon = AppIcons.Palette
            )
        }

        SectionCard(title = "行为") {
            SettingSwitchRow(
                title = "打开应用即开始待机",
                subtitle = "启动时自动开启语音待机（需已授予麦克风权限）",
                checked = state.autoStartOnLaunch,
                onCheckedChange = { AsrManager.setBehavior(it, state.keepScreenOn) },
                icon = AppIcons.Power
            )
            SettingSwitchRow(
                title = "待机时屏幕常亮",
                subtitle = "语音待机运行期间阻止屏幕自动熄灭",
                checked = state.keepScreenOn,
                onCheckedChange = { AsrManager.setBehavior(state.autoStartOnLaunch, it) },
                icon = AppIcons.DarkMode
            )
            SettingHint("待机由前台服务承载，即使切到后台或锁屏也会继续监听，可在通知栏随时停止。")
        }
    }
}
