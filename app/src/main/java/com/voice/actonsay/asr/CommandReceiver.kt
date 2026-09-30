package com.voice.actonsay.asr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 调试/自动化入口：通过广播向助手注入一条文本指令，走与手动输入相同的链路。
 *
 * adb shell am broadcast -n com.voice.actonsay/.asr.CommandReceiver -a com.voice.actonsay.SEND_TEXT --es text "打开设置"
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND_TEXT) return
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        Log.i(TAG, "Injected text command: $text")
        AsrManager.init(context)
        AsrManager.sendManualMessage(text)
    }

    companion object {
        private const val TAG = "CommandReceiver"
        const val ACTION_SEND_TEXT = "com.voice.actonsay.SEND_TEXT"
        const val EXTRA_TEXT = "text"
    }
}
