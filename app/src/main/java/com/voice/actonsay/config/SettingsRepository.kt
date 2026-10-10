package com.voice.actonsay.config

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 配置统一入口；凭据只在加密成功后移除旧明文，失败时保留可恢复的数据。 */
class SettingsRepository(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("actonsay_prefs", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("actonsay_secrets", Context.MODE_PRIVATE)
    private val savedValues = ConcurrentHashMap<String, String>()
    private val desiredValues = ConcurrentHashMap<String, String>()
    private val pendingLock = Any()
    private val epoch = AtomicLong()
    private val pending = mutableMapOf<String, Pair<Long, String>>()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val _saveError = MutableStateFlow("")
    val saveError = _saveError.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (ignored in changes) {
                delay(400)
                val batch = synchronized(pendingLock) { pending.toMap().also { pending.clear() } }
                for ((name, entry) in batch) {
                    synchronized(this@SettingsRepository) {
                        if (entry.first == epoch.get() && desiredValues[name] == entry.second) {
                            runCatching { putSecret(name, entry.second) }
                                .onFailure { _saveError.value = "凭据保存失败，本次修改尚未持久化，请重新输入后重试。" }
                        }
                    }
                }
            }
        }
    }

    /** 界面即时生效，凭据写盘在 IO 线程合并处理；迁移仍使用同步保存保证顺序。 */
    fun queueSecret(name: String, value: String) {
        synchronized(pendingLock) {
            if (pending[name]?.second == value) return
            desiredValues[name] = value
            pending[name] = epoch.get() to value
        }
        _saveError.value = ""
        changes.trySend(Unit)
    }

    /** 配对等必须确认已落盘的流程在 IO 线程调用；较早的输入快照不能覆盖它。 */
    @Synchronized
    fun persistSecret(name: String, value: String) {
        synchronized(pendingLock) { desiredValues[name] = value; pending.remove(name) }
        putSecret(name, value)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized
    fun putSecret(name: String, value: String) {
        if (savedValues[name] == value && !preferences.contains(name)) return
        if (value.isBlank()) {
            check(secrets.edit().remove(name).commit()) { "凭据清理失败" }
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(payload, Base64.NO_WRAP)
            check(secrets.edit().putString(name, packed).commit()) { "凭据保存失败" }
        }
        if (preferences.contains(name)) check(preferences.edit().remove(name).commit()) { "旧凭据清理失败" }
        savedValues[name] = value
    }

    @Synchronized
    fun getSecret(name: String): String {
        val packed = secrets.getString(name, null)
        if (packed == null) {
            val old = preferences.getString(name, "").orEmpty()
            if (old.isNotEmpty()) putSecret(name, old)
            savedValues[name] = old
            return old
        }
        val parts = packed.split(":", limit = 2)
        require(parts.size == 2) { "凭据格式无效，请重新配置" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val value = String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        if (preferences.contains(name)) check(preferences.edit().remove(name).commit())
        savedValues[name] = value
        return value
    }

    @Synchronized
    fun clearSecrets() {
        epoch.incrementAndGet()
        synchronized(pendingLock) { pending.clear() }
        check(secrets.edit().clear().commit()) { "凭据清理失败" }
        savedValues.clear()
        desiredValues.clear()
    }

    companion object { private const val ALIAS = "actonsay.credentials.v1" }
}
