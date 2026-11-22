package com.tabletagent.local.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ProviderKeySummary(
    val openAiConfigured: Boolean = false,
    val openAiHint: String = "",
    val anthropicConfigured: Boolean = false,
    val anthropicHint: String = "",
    val geminiConfigured: Boolean = false,
    val geminiHint: String = ""
)

/**
 * Stores provider API keys encrypted with Android KeyStore AES-256-GCM.
 * Keys are never written to logs, URLs, or HTTP endpoints; they are injected
 * solely into the isolated PRoot bridge process environment.
 */
object SecureSecrets {
    const val KEY_OPENAI = "OPENAI_API_KEY"
    const val KEY_ANTHROPIC = "ANTHROPIC_API_KEY"
    const val KEY_GEMINI = "GEMINI_API_KEY"

    private const val ALIAS = "tablet_agent_provider_secrets_key"
    private const val STORE = "tablet_agent_provider_secrets"

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun saveKey(context: Context, envName: String, value: String) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove("${envName}_iv").remove("${envName}_ct").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("${envName}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("${envName}_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    fun getKey(context: Context, envName: String): String {
        return try {
            val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val ivB64 = prefs.getString("${envName}_iv", null) ?: return ""
            val ctB64 = prefs.getString("${envName}_ct", null) ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun hasKey(context: Context, envName: String): Boolean {
        return getKey(context, envName).isNotBlank()
    }

    fun maskedHint(context: Context, envName: String): String {
        val raw = getKey(context, envName)
        if (raw.isBlank()) return ""
        val prefix = if (raw.length >= 6) raw.take(4) else ""
        return if (prefix.isNotEmpty()) "$prefix••••••••" else "••••••••"
    }

    fun getSummary(context: Context): ProviderKeySummary {
        return ProviderKeySummary(
            openAiConfigured = hasKey(context, KEY_OPENAI),
            openAiHint = maskedHint(context, KEY_OPENAI),
            anthropicConfigured = hasKey(context, KEY_ANTHROPIC),
            anthropicHint = maskedHint(context, KEY_ANTHROPIC),
            geminiConfigured = hasKey(context, KEY_GEMINI),
            geminiHint = maskedHint(context, KEY_GEMINI)
        )
    }

    fun asEnvMap(context: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (k in listOf(KEY_OPENAI, KEY_ANTHROPIC, KEY_GEMINI)) {
            val v = getKey(context, k)
            if (v.isNotBlank()) out[k] = v
        }
        return out
    }
}
