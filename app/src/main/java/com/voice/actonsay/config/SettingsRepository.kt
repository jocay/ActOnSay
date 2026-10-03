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

/** 配置统一入口；凭据只在加密成功后移除旧明文，失败时保留可恢复的数据。 */
class SettingsRepository(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("actonsay_prefs", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("actonsay_secrets", Context.MODE_PRIVATE)

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
        check(preferences.edit().remove(name).commit()) { "旧凭据清理失败" }
    }

    @Synchronized
    fun getSecret(name: String): String {
        val packed = secrets.getString(name, null)
        if (packed == null) {
            val old = preferences.getString(name, "").orEmpty()
            if (old.isNotEmpty()) putSecret(name, old)
            return old
        }
        val parts = packed.split(":", limit = 2)
        require(parts.size == 2) { "凭据格式无效，请重新配置" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val value = String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        if (preferences.contains(name)) check(preferences.edit().remove(name).commit())
        return value
    }

    fun clearSecrets() {
        check(secrets.edit().clear().commit()) { "凭据清理失败" }
    }

    companion object { private const val ALIAS = "actonsay.credentials.v1" }
}
