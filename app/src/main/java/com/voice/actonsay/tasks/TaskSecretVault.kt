package com.voice.actonsay.tasks

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.content.Context
import java.io.File
import java.util.UUID

/** 任务配置快照的凭据也须加密，不能通过检查点留下明文密钥。 */
internal object TaskSecretVault {
    private const val ALIAS = "actonsay_task_snapshots"
    private lateinit var directory: File
    fun init(context: Context) { directory = File(context.filesDir, "task_payloads").apply { mkdirs() } }
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        if (encrypted.length <= 256 * 1024) return encrypted
        val id = UUID.randomUUID().toString()
        val target = File(directory, id)
        val temporary = File(directory, "$id.partial")
        temporary.writeText(encrypted, Charsets.UTF_8)
        check(temporary.renameTo(target)) { "无法保存加密任务记录" }
        return "vault-file:$id"
    }
    fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        val encoded = if (value.startsWith("vault-file:")) {
            val id = value.removePrefix("vault-file:")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val source = File(directory, id).canonicalFile
            require(source.path.startsWith(directory.canonicalPath + File.separator) && source.length() <= 32L * 1024 * 1024)
            source.readText(Charsets.UTF_8)
        } else value
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    /** 仅在用户清理历史时回收旧的无引用大载荷；新写入但还未提交的文件不触碰。 */
    fun pruneUnreferenced(references: List<String>, before: Long) {
        val retained = references.filter { it.startsWith("vault-file:") }.map { it.removePrefix("vault-file:") }.toSet()
        val root = directory.canonicalFile
        root.listFiles()?.forEach { file ->
            if (file.name !in retained && file.lastModified() < before && file.canonicalFile == file.absoluteFile && file.isFile) file.delete()
        }
    }
}
