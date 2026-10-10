package com.voice.actonsay.asr

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class VolcengineStreamAsrClient {

    companion object {
        private const val TAG = "VolcengineAsrClient"
        private const val DEFAULT_WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"
        const val RESOURCE_ID_SEED_2 = "volc.seedasr.sauc.duration"
        const val RESOURCE_ID_BIGASR_1 = "volc.bigasr.sauc.duration"

        // Protocol Header Constants
        private const val PROTOCOL_VERSION: Byte = 0b0001
        private const val DEFAULT_HEADER_SIZE: Byte = 0b0001 // 1 * 4 = 4 bytes

        // Message Types
        private const val MSG_TYPE_FULL_CLIENT_REQ: Byte = 0b0001
        private const val MSG_TYPE_AUDIO_ONLY_REQ: Byte = 0b0010
        private const val MSG_TYPE_FULL_SERVER_RESP: Byte = 0b1001
        private const val MSG_TYPE_SERVER_ACK: Byte = 0b1011
        private const val MSG_TYPE_SERVER_ERROR_RESP: Byte = 0b1111

        // Message Type Specific Flags
        private const val FLAG_NO_SEQUENCE: Byte = 0b0000
        private const val FLAG_POS_SEQUENCE: Byte = 0b0001
        private const val FLAG_NEG_WITH_SEQUENCE: Byte = 0b0011

        // Serialization
        private const val SERIAL_NONE: Byte = 0b0000
        private const val SERIAL_JSON: Byte = 0b0001

        // Compression
        private const val COMPRESS_NONE: Byte = 0b0000
        private const val COMPRESS_GZIP: Byte = 0b0001
    }

    interface Listener {
        fun onConnected()
        fun onTranscriptionUpdate(text: String, isFinal: Boolean)
        fun onError(code: Int, message: String)
        fun onClosed()
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var listener: Listener? = null
    private val sequence = AtomicInteger(1)
    private val isConnected = AtomicBoolean(false)
    private val isHandshakeFinished = AtomicBoolean(false)
    private val isFinished = AtomicBoolean(false)
    /** 每次结束会话都使旧连接回调失效，校验与状态更新共用对象锁。 */
    private var sessionGeneration = 0L

    // Pending audio queue while handshake is in progress
    private val pendingAudioQueue = ConcurrentLinkedQueue<Pair<ByteArray, Boolean>>()

    @Synchronized
    fun startSession(
        apiKey: String,
        resourceId: String = RESOURCE_ID_BIGASR_1,
        listener: Listener,
        wsUrl: String = DEFAULT_WS_URL
    ) {
        cancel()
        val generation = sessionGeneration

        this.listener = listener
        this.sequence.set(1)
        this.isConnected.set(false)
        this.isHandshakeFinished.set(false)
        this.isFinished.set(false)
        this.pendingAudioQueue.clear()

        val connectId = UUID.randomUUID().toString()
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("X-Api-Key", apiKey.trim())
            .addHeader("X-Api-Resource-Id", resourceId.trim())
            .addHeader("X-Api-Connect-Id", connectId)
            .addHeader("X-Api-Sequence", "-1")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(this@VolcengineStreamAsrClient) {
                    if (generation != sessionGeneration) return
                    Log.i(TAG, "WebSocket connected, sending FULL_CLIENT_REQUEST. connectId=$connectId")
                    isConnected.set(true)
                    sendFullClientRequest(webSocket, connectId)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                synchronized(this@VolcengineStreamAsrClient) {
                    if (generation != sessionGeneration) return
                    handleServerMessage(webSocket, bytes.toByteArray())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@VolcengineStreamAsrClient) {
                    if (generation != sessionGeneration) return
                    val errorBody = try { response?.body?.string() } catch (e: Exception) { null }
                    val code = response?.code ?: -1
                    val baseMsg = t.message ?: "网络连接异常"
                    val finalMsg = if (!errorBody.isNullOrEmpty()) "$baseMsg ($errorBody)" else baseMsg
                    Log.e(TAG, "WebSocket failure: code=$code, msg=$finalMsg", t)
                    listener.onError(code, finalMsg)
                    cleanup()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@VolcengineStreamAsrClient) {
                    if (generation != sessionGeneration) return
                    Log.i(TAG, "WebSocket closing: $code / $reason")
                    webSocket.close(code, reason)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@VolcengineStreamAsrClient) {
                    if (generation != sessionGeneration) return
                    Log.i(TAG, "WebSocket closed: $code / $reason")
                    listener.onClosed()
                    cleanup()
                }
            }
        })
    }

    private fun sendFullClientRequest(ws: WebSocket, connectId: String) {
        try {
            val root = JSONObject().apply {
                put("user", JSONObject().apply {
                    put("uid", connectId)
                })
                put("audio", JSONObject().apply {
                    put("format", "pcm")
                    put("rate", 16000)
                    put("bits", 16)
                    put("channel", 1)
                    put("codec", "raw")
                })
                put("request", JSONObject().apply {
                    put("model_name", "bigmodel")
                    put("enable_itn", true)
                    put("result_type", "full")
                })
            }

            val jsonBytes = root.toString().toByteArray(Charsets.UTF_8)
            val compressedPayload = gzipCompress(jsonBytes)
            val seq = sequence.getAndIncrement() // 1

            val frame = buildFrame(
                msgType = MSG_TYPE_FULL_CLIENT_REQ,
                flags = FLAG_POS_SEQUENCE,
                serialMethod = SERIAL_JSON,
                compression = COMPRESS_GZIP,
                sequence = seq,
                payload = compressedPayload
            )

            ws.send(frame.toByteString())
            Log.d(TAG, "Sent FULL_CLIENT_REQUEST frame: size=${frame.size}, seq=$seq")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send FULL_CLIENT_REQUEST", e)
            listener?.onError(-1, "初始化请求发送失败: ${e.message}")
        }
    }

    private fun handleServerMessage(ws: WebSocket, raw: ByteArray) {
        if (raw.size < 4) {
            Log.w(TAG, "Received message too short (${raw.size} bytes)")
            return
        }

        try {
            val protocolVersion = (raw[0].toInt() shr 4) and 0x0F
            val headerSize = (raw[0].toInt() and 0x0F) * 4
            val msgType = (raw[1].toInt() shr 4) and 0x0F
            val flags = raw[1].toInt() and 0x0F
            val serialMethod = (raw[2].toInt() shr 4) and 0x0F
            val compression = raw[2].toInt() and 0x0F

            var offset = headerSize
            var respSeq = 0
            if ((flags and 0x01) != 0 && raw.size >= offset + 4) {
                respSeq = ByteBuffer.wrap(raw, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                offset += 4
            }

            val isLastPackage = (flags and 0x02) != 0

            var payloadBytes = ByteArray(0)
            if (raw.size >= offset + 4) {
                val payloadSize = ByteBuffer.wrap(raw, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                offset += 4
                if (payloadSize > 0 && raw.size >= offset + payloadSize) {
                    payloadBytes = raw.copyOfRange(offset, offset + payloadSize)
                }
            }

            // Decompress if needed
            val decompressedBytes = if (compression == COMPRESS_GZIP.toInt() && payloadBytes.isNotEmpty()) {
                gzipDecompress(payloadBytes)
            } else {
                payloadBytes
            }

            val jsonString = if (serialMethod == SERIAL_JSON.toInt() && decompressedBytes.isNotEmpty()) {
                String(decompressedBytes, Charsets.UTF_8)
            } else ""

            when (msgType.toByte()) {
                MSG_TYPE_SERVER_ACK -> {
                    Log.d(TAG, "Received SERVER_ACK: seq=$respSeq")
                    if (!isHandshakeFinished.get()) {
                        isHandshakeFinished.set(true)
                        listener?.onConnected()
                        drainPendingAudio(ws)
                    }
                }
                MSG_TYPE_FULL_SERVER_RESP -> {
                    if (!isHandshakeFinished.get()) {
                        isHandshakeFinished.set(true)
                        listener?.onConnected()
                        drainPendingAudio(ws)
                    }
                    if (jsonString.isNotEmpty()) {
                        val json = JSONObject(jsonString)
                        val text = json.optJSONObject("result")?.optString("text") ?: ""
                        Log.i(TAG, "ASR result: text='$text', isLast=$isLastPackage")
                        listener?.onTranscriptionUpdate(text, isLastPackage)
                    }
                    if (isLastPackage) {
                        Log.i(TAG, "ASR session completed by server")
                        ws.close(1000, "Normal Closure")
                    }
                }
                MSG_TYPE_SERVER_ERROR_RESP -> {
                    val errorCode = if (decompressedBytes.size >= 4) {
                        ByteBuffer.wrap(decompressedBytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                    } else -1
                    Log.e(TAG, "Server error response: code=$errorCode, payload=$jsonString")
                    listener?.onError(errorCode, "火山引擎服务报错: $jsonString")
                }
                else -> {
                    Log.d(TAG, "Received unknown msgType=$msgType, payloadLen=${payloadBytes.size}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling server message", e)
        }
    }

    @Synchronized
    fun sendAudio(pcm: ByteArray, isLast: Boolean = false) {
        if (isFinished.get()) return

        val ws = webSocket
        if (ws == null || !isHandshakeFinished.get()) {
            // Queue audio until handshake completes
            pendingAudioQueue.offer(Pair(pcm, isLast))
            if (isLast) {
                isFinished.set(true)
            }
            return
        }

        sendAudioFrame(ws, pcm, isLast)
    }

    private fun drainPendingAudio(ws: WebSocket) {
        while (!pendingAudioQueue.isEmpty()) {
            val item = pendingAudioQueue.poll() ?: break
            sendAudioFrame(ws, item.first, item.second)
        }
    }

    private fun sendAudioFrame(ws: WebSocket, pcm: ByteArray, isLast: Boolean) {
        try {
            val compressed = gzipCompress(pcm)
            val currentSeq = sequence.getAndIncrement()
            val seqToSend = if (isLast) -currentSeq else currentSeq
            val flagToSend = if (isLast) FLAG_NEG_WITH_SEQUENCE else FLAG_POS_SEQUENCE

            val frame = buildFrame(
                msgType = MSG_TYPE_AUDIO_ONLY_REQ,
                flags = flagToSend,
                serialMethod = SERIAL_NONE,
                compression = COMPRESS_GZIP,
                sequence = seqToSend,
                payload = compressed
            )

            ws.send(frame.toByteString())
            Log.d(TAG, "Sent audio frame: seq=$seqToSend, pcmSize=${pcm.size}, compressedSize=${compressed.size}, isLast=$isLast")
            if (isLast) {
                isFinished.set(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio frame", e)
            listener?.onError(-1, "音频分片发送失败: ${e.message}")
        }
    }

    @Synchronized
    fun finish() {
        if (isFinished.get()) return
        sendAudio(ByteArray(0), isLast = true)
    }

    @Synchronized
    fun cancel() {
        sessionGeneration++
        isFinished.set(true)
        webSocket?.cancel()
        cleanup()
    }

    private fun cleanup() {
        sessionGeneration++
        webSocket = null
        listener = null
        isConnected.set(false)
        isHandshakeFinished.set(false)
        pendingAudioQueue.clear()
    }

    private fun buildFrame(
        msgType: Byte,
        flags: Byte,
        serialMethod: Byte,
        compression: Byte,
        sequence: Int,
        payload: ByteArray
    ): ByteArray {
        val totalSize = 4 + 4 + 4 + payload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Byte 0: version (4 bits) + header_size (4 bits)
        buffer.put(((PROTOCOL_VERSION.toInt() shl 4) or (DEFAULT_HEADER_SIZE.toInt() and 0x0F)).toByte())
        // Byte 1: message_type (4 bits) + flags (4 bits)
        buffer.put(((msgType.toInt() shl 4) or (flags.toInt() and 0x0F)).toByte())
        // Byte 2: serialization_method (4 bits) + compression (4 bits)
        buffer.put(((serialMethod.toInt() shl 4) or (compression.toInt() and 0x0F)).toByte())
        // Byte 3: reserved
        buffer.put(0x00.toByte())

        // Sequence (4 bytes big-endian)
        buffer.putInt(sequence)

        // Payload size (4 bytes big-endian)
        buffer.putInt(payload.size)

        // Payload
        buffer.put(payload)

        return buffer.array()
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gzipDecompress(compressed: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(compressed)
        GZIPInputStream(bis).use { return it.readBytes() }
    }
}
