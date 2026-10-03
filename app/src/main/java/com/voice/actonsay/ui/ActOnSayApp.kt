package com.voice.actonsay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.voice.actonsay.ui.icons.AppIcons
import com.voice.actonsay.ui.screens.AutomationScreen
import com.voice.actonsay.ui.screens.TasksScreen
import com.voice.actonsay.ui.screens.HomeScreen
import com.voice.actonsay.ui.screens.settings.AboutScreen
import com.voice.actonsay.ui.screens.settings.AiSettingsScreen
import com.voice.actonsay.ui.screens.settings.AppearanceSettingsScreen
import com.voice.actonsay.ui.screens.settings.AsrSettingsScreen
import com.voice.actonsay.ui.screens.settings.SettingsHubScreen
import com.voice.actonsay.ui.screens.settings.SettingsPage
import com.voice.actonsay.ui.screens.settings.TtsSettingsScreen
import com.voice.actonsay.ui.screens.settings.WakeWordSettingsScreen
import com.voice.actonsay.ui.screens.settings.ExecutionSettingsScreen
import com.voice.actonsay.ui.screens.settings.McpSettingsScreen
import com.voice.actonsay.ui.screens.settings.SkillsSettingsScreen
import com.voice.actonsay.ui.screens.settings.PermissionsSettingsScreen
import com.voice.actonsay.ui.screens.settings.MemorySettingsScreen
import com.voice.actonsay.ui.screens.settings.WebSettingsScreen

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("对话", AppIcons.Chat),
    TASKS("任务", AppIcons.Task),
    AUTOMATION("自动化", AppIcons.Schedule),
    SETTINGS("设置", Icons.Default.Settings)
}

/**
 * 应用根：四个标签页与设置子页，沿用状态驱动导航。
 */
@Composable
fun ActOnSayApp(openTasksRequest: Int = 0) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    LaunchedEffect(openTasksRequest) {
        if (openTasksRequest > 0) {
            tab = Tab.TASKS
            settingsPage = null
        }
    }

    fun openSettings(page: SettingsPage? = null) {
        tab = Tab.SETTINGS
        settingsPage = page
    }

    BackHandler(enabled = settingsPage != null || tab != Tab.HOME) {
        when {
            settingsPage != null -> settingsPage = null
            else -> tab = Tab.HOME
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab to settingsPage,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = (initialState.second == null && targetState.second != null)
                val backward = (initialState.second != null && targetState.second == null)
                when {
                    forward -> (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
                    backward -> (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                    else -> fadeIn() togetherWith fadeOut()
                }
            },
            label = "screens"
        ) { (currentTab, page) ->
            when (currentTab) {
                Tab.HOME -> HomeScreen(
                    onOpenSettings = { openSettings() },
                    onOpenAiSettings = { openSettings(SettingsPage.AI) },
                    onOpenAsrSettings = { openSettings(SettingsPage.ASR) },
                    onOpenTasks = { tab = Tab.TASKS }
                )
                Tab.TASKS -> TasksScreen()
                Tab.AUTOMATION -> AutomationScreen()
                Tab.SETTINGS -> when (page) {
                    null -> SettingsHubScreen(onOpen = { settingsPage = it })
                    SettingsPage.AI -> AiSettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.ASR -> AsrSettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.TTS -> TtsSettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.WAKE_WORDS -> WakeWordSettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.APPEARANCE -> AppearanceSettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.ABOUT -> AboutScreen(onBack = { settingsPage = null })
                    SettingsPage.EXECUTION -> ExecutionSettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.MCP -> McpSettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.SKILLS -> SkillsSettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.PERMISSIONS -> PermissionsSettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.MEMORY -> MemorySettingsScreen(onBack = { settingsPage = null })
                    SettingsPage.WEB -> WebSettingsScreen(onBack = { settingsPage = null })
                }
            }
        }

        // 子页内隐藏底栏，给表单更多空间
        if (settingsPage == null) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; settingsPage = null },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    }
}
