package com.bambu.nfc.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val spoolmanUrl: String = DEFAULT_SPOOLMAN_URL,
    val labelitoUrl: String = DEFAULT_LABELITO_URL,
    val labelitoToken: String = "",
) {
    companion object {
        const val DEFAULT_SPOOLMAN_URL = "https://spoolman.lab.apj.dev"
        const val DEFAULT_LABELITO_URL = "https://labelito.lab.apj.dev"
    }
}

/** All settings live in EncryptedSharedPreferences (only the labelito token is secret, but it's simpler). */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = open(context)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    val current: AppSettings get() = _settings.value

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_SPOOLMAN_URL, settings.spoolmanUrl.trim())
            .putString(KEY_LABELITO_URL, settings.labelitoUrl.trim())
            .putString(KEY_LABELITO_TOKEN, settings.labelitoToken.trim())
            .apply()
        _settings.value = load()
    }

    private fun load() = AppSettings(
        spoolmanUrl = prefs.getString(KEY_SPOOLMAN_URL, null)?.ifBlank { null } ?: AppSettings.DEFAULT_SPOOLMAN_URL,
        labelitoUrl = prefs.getString(KEY_LABELITO_URL, null)?.ifBlank { null } ?: AppSettings.DEFAULT_LABELITO_URL,
        labelitoToken = prefs.getString(KEY_LABELITO_TOKEN, null).orEmpty(),
    )

    private companion object {
        const val FILE = "spool_scanner_settings"
        const val KEY_SPOOLMAN_URL = "spoolman_url"
        const val KEY_LABELITO_URL = "labelito_url"
        const val KEY_LABELITO_TOKEN = "labelito_token"

        fun open(context: Context): SharedPreferences {
            fun create(): SharedPreferences {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    context, FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                create()
            } catch (e: Exception) {
                // A keystore reset (e.g. restored backup) makes the old file undecryptable: start over.
                Log.w("SettingsStore", "Encrypted prefs unreadable, resetting: ${e.message}")
                context.deleteSharedPreferences(FILE)
                create()
            }
        }
    }
}
