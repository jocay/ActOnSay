package com.voice.actonsay.agent

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 通知监听：只在内存中保留最近的通知供 read_notifications 读取，不落盘、不上传。 */
class NotificationReader : NotificationListenerService() {
    data class Entry(val key: String, val packageName: String, val app: String, val title: String, val text: String, val time: Long)

    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications?.forEach { record(it) } }
    }

    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) = record(sbn)

    private fun record(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        // 进行中的系统通知（下载、媒体、前台服务）没有阅读价值。
        if ((title.isBlank() && text.isBlank()) || sbn.isOngoing) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        synchronized(recent) {
            recent.removeAll { it.key == sbn.key }
            recent.addLast(Entry(sbn.key, sbn.packageName, app, title.take(200), text.take(1000), sbn.postTime))
            while (recent.size > 100) recent.removeFirst()
        }
    }

    companion object {
        @Volatile var connected = false
            private set
        private val recent = ArrayDeque<Entry>()
        fun snapshot(): List<Entry> = synchronized(recent) { recent.toList().sortedByDescending { it.time } }
    }
}
