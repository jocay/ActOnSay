package com.voice.actonsay.vdisplay

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.system.exitProcess

/**
 * 虚拟屏宿主：不是应用进程的一部分，由 Root 通过 app_process 以本 APK 为 classpath 单独启动。
 * 它创建一块不随主屏熄灭的可信虚拟显示器并持有其画面；应用通过它的标准输入输出收发单行 JSON。
 * 标准输入关闭（应用退出）时进程结束，虚拟屏及其上的内容随之销毁。
 *
 * 这里只能使用框架类和反射，不能触碰应用内的单例（它们依赖真实的应用进程）。
 */
object DisplayHelper {
    // DisplayManager 的隐藏标志位，取值与 AOSP 一致。
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_PRESENTATION = 1 shl 1
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val FLAG_OWN_FOCUS = 1 shl 14

    private val lock = Any()
    private var latest: Bitmap? = null
    private var frames = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val width = args.getOrNull(0)?.toIntOrNull() ?: 720
            val height = args.getOrNull(1)?.toIntOrNull() ?: 1600
            val dpi = args.getOrNull(2)?.toIntOrNull() ?: 280
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            val display = create(width, height, dpi)
            emit(JSONObject().put("event", "ready").put("displayId", display.display.displayId).put("width", width).put("height", height))
            Thread({ commandLoop(display) }, "commands").start()
            Looper.loop()
        } catch (error: Throwable) {
            emit(JSONObject().put("event", "error").put("message", "${error.javaClass.simpleName}: ${error.message}"))
            exitProcess(1)
        }
    }

    @Synchronized
    private fun emit(value: JSONObject) {
        println(value.toString())
        System.out.flush()
    }

    private fun create(width: Int, height: Int, dpi: Int): VirtualDisplay {
        val thread = HandlerThread("frames").apply { start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ source ->
            // 只保留最新一帧；及时取走图像，缓冲区占满会让显示器停止出帧。
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val frame = if (padded.width == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
                synchronized(lock) { latest?.recycle(); latest = frame; frames++ }
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }, Handler(thread.looper))

        var flags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        // 独立显示组加“始终解锁”让它不跟随主屏熄灭和锁屏；可信标志允许启动任意应用并接收注入的输入。
        if (Build.VERSION.SDK_INT >= 33) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        if (Build.VERSION.SDK_INT >= 34) flags = flags or FLAG_OWN_FOCUS

        val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val manager = constructor.newInstance(ShellContext(systemContext()))
        return manager.createVirtualDisplay("actonsay-virtual", width, height, dpi, reader.surface, flags)
            ?: error("系统拒绝创建虚拟显示器")
    }

    /** 没有应用环境的进程借用一个系统 Context，做法与 scrcpy 相同。 */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun systemContext(): Context {
        val type = Class.forName("android.app.ActivityThread")
        val thread = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        type.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        runCatching { type.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true) }
        return type.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
    }

    /** 显示服务按调用方 UID 校验包名：Root 不校验，Shell 身份需要报 com.android.shell。 */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        private val name = if (Process.myUid() == Process.SHELL_UID) "com.android.shell" else "root"
        override fun getPackageName(): String = name
        override fun getOpPackageName(): String = name
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(Process.myUid()).setPackageName(name).build()
        override fun getDeviceId(): Int = 0
    }

    private fun commandLoop(display: VirtualDisplay) {
        val input = System.`in`.bufferedReader()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val reply = JSONObject()
            try {
                val command = JSONObject(line)
                reply.put("id", command.optLong("id"))
                when (command.optString("cmd")) {
                    "screenshot" -> screenshot(command.optInt("quality", 70), reply)
                    "quit" -> { emit(reply.put("ok", true)); break }
                    else -> reply.put("ok", false).put("error", "unknown command")
                }
            } catch (error: Throwable) {
                reply.put("ok", false).put("error", "${error.javaClass.simpleName}: ${error.message}")
            }
            emit(reply)
        }
        runCatching { display.release() }
        exitProcess(0)
    }

    private fun screenshot(quality: Int, reply: JSONObject) {
        val bytes = ByteArrayOutputStream()
        val count = synchronized(lock) {
            val frame = latest ?: return@synchronized -1L
            frame.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), bytes)
            frames
        }
        if (count < 0) reply.put("ok", false).put("error", "no frame yet")
        else reply.put("ok", true).put("frames", count).put("jpeg", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
    }
}
