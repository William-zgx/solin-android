package com.bytedance.zgx.solin.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedSecretStore(context: Context) : SecretStore {
    private val appContext = context.applicationContext

    override fun loadString(name: String): Result<String> =
        runCatching {
            encryptedPrefs().getString(name, "").orEmpty()
        }

    override fun saveString(name: String, value: String): Result<Unit> =
        runCatching {
            encryptedPrefs().edit().apply {
                if (value.isBlank()) {
                    remove(name)
                } else {
                    putString(name, value)
                }
            }.commit().also { saved ->
                check(saved) { "无法保存加密密钥" }
            }
        }

    private fun encryptedPrefs() =
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun masterKey(): MasterKey =
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private companion object {
        const val PREFS_NAME = "solin_secrets"
    }
}
