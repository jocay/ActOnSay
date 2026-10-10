package com.voice.actonsay.agent

import com.voice.actonsay.tasks.TaskRuntime
import kotlinx.coroutines.delay

/**
 * 浏览器、虚拟屏这类单实例资源按任务独占：同一时间只有一个任务能连续操作，
 * 避免任务 A 读取的元素编号或截图坐标被任务 B 的操作改掉。
 * 持有者结束运行（完成、失败、等待授权）时由 [TaskRuntime] 释放；持有者已不在运行或长时间未使用时可被接管。
 */
class ResourceLease(val label: String) {
    private var owner: String? = null
    private var lastUsed = 0L

    /** 等待至多 [timeoutMs] 取得资源；超时返回 false，由调用方如实告诉模型资源被占用。 */
    suspend fun acquire(taskId: String, timeoutMs: Long = WAIT_MS): Boolean {
        val key = taskId.ifBlank { INTERACTIVE }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (tryAcquire(key)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(500)
        }
    }

    @Synchronized private fun tryAcquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        val current = owner
        val stale = current == null || current == key || current == INTERACTIVE ||
            !TaskRuntime.isRunning(current) || now - lastUsed > IDLE_TAKEOVER_MS
        if (!stale) return false
        owner = key
        lastUsed = now
        return true
    }

    @Synchronized fun release(taskId: String) {
        if (owner == taskId.ifBlank { INTERACTIVE }) owner = null
    }

    /** 当前持有者（任务 ID），界面展示用。 */
    @Synchronized fun holder(): String? = owner

    companion object {
        private const val INTERACTIVE = "interactive"
        private const val WAIT_MS = 60_000L
        /** 持有者仍在运行但这么久没用过资源时允许接管，防止一个卡住的任务长期占着。 */
        private const val IDLE_TAKEOVER_MS = 5 * 60_000L
    }
}

object ResourceLeases {
    val browser = ResourceLease("浏览器")
    val virtualScreen = ResourceLease("虚拟屏")
    fun releaseAll(taskId: String) {
        browser.release(taskId)
        virtualScreen.release(taskId)
    }
}
