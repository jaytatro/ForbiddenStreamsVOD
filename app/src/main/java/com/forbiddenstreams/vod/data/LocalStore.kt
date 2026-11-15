package com.forbiddenstreams.vod.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private val Context.vodDataStore by preferencesDataStore(name = "vod_store")

/**
 * On-device storage for the signed-in account, favorites and resume positions.
 * Favorites and progress are kept per account, so switching providers doesn't mix them.
 */
class LocalStore(context: Context) {

    private val dataStore = context.applicationContext.vodDataStore
    private val json = Json { ignoreUnknownKeys = true }

    private val accountKey = stringPreferencesKey("account")
    private val pinKey = stringPreferencesKey("parental_pin_sha256")
    private fun favoritesKey(accountId: String) = stringPreferencesKey("favorites_$accountId")
    private fun resumeKey(accountId: String) = stringPreferencesKey("resume_$accountId")

    // ---- Account ----

    val account: Flow<Account?> = dataStore.data.map { prefs ->
        prefs[accountKey]?.let { runCatching { json.decodeFromString<Account>(it) }.getOrNull() }
    }

    suspend fun saveAccount(account: Account) {
        dataStore.edit { it[accountKey] = json.encodeToString(account) }
    }

    suspend fun clearAccount() {
        dataStore.edit { it.remove(accountKey) }
    }

    // ---- Parental controls ----

    /** True while a parental PIN is set; 18+ content is hidden. */
    val parentalEnabled: Flow<Boolean> = dataStore.data.map { it[pinKey] != null }

    suspend fun setParentalPin(pin: String) {
        dataStore.edit { it[pinKey] = hashPin(pin) }
    }

    suspend fun clearParentalPin() {
        dataStore.edit { it.remove(pinKey) }
    }

    suspend fun checkParentalPin(pin: String): Boolean =
        dataStore.data.first()[pinKey] == hashPin(pin)

    private fun hashPin(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("forbidden-streams-vod:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---- Playback ----

    private val forceHdr10Key = booleanPreferencesKey("force_hdr10")

    val forceHdr10: Flow<Boolean> = dataStore.data.map { it[forceHdr10Key] ?: false }

    suspend fun setForceHdr10(value: Boolean) {
        dataStore.edit { it[forceHdr10Key] = value }
    }

    private val matchFrameRateKey = booleanPreferencesKey("match_frame_rate")

    val matchFrameRate: Flow<Boolean> = dataStore.data.map { it[matchFrameRateKey] ?: true }

    suspend fun setMatchFrameRate(value: Boolean) {
        dataStore.edit { it[matchFrameRateKey] = value }
    }

    // ---- Catalog sizes (for the download percentage) ----

    private fun catalogSizeKey(accountId: String, kind: String) = intPreferencesKey("catalog_size_${accountId}_$kind")

    suspend fun lastCatalogSize(accountId: String, kind: String): Int? =
        dataStore.data.first()[catalogSizeKey(accountId, kind)]

    suspend fun saveCatalogSize(accountId: String, kind: String, size: Int) {
        dataStore.edit { it[catalogSizeKey(accountId, kind)] = size }
    }

    // ---- Favorites ----

    fun favorites(accountId: String): Flow<List<FavoriteItem>> =
        dataStore.data.map { decodeFavorites(it, accountId) }

    suspend fun toggleFavorite(accountId: String, item: FavoriteItem) {
        dataStore.edit { prefs ->
            val current = decodeFavorites(prefs, accountId)
            val next = if (current.any { it.key == item.key }) {
                current.filterNot { it.key == item.key }
            } else {
                listOf(item) + current
            }
            prefs[favoritesKey(accountId)] = json.encodeToString(next)
        }
    }

    // ---- Resume / continue watching ----

    /** Most recently watched first. */
    fun resume(accountId: String): Flow<List<ResumeEntry>> =
        dataStore.data.map { prefs -> decodeResume(prefs, accountId).sortedByDescending { it.updatedAt } }

    suspend fun saveResume(accountId: String, entry: ResumeEntry) {
        dataStore.edit { prefs ->
            val rest = decodeResume(prefs, accountId).filterNot { it.key == entry.key }
            val next = (listOf(entry) + rest).sortedByDescending { it.updatedAt }.take(MAX_RESUME)
            prefs[resumeKey(accountId)] = json.encodeToString(next)
        }
    }

    suspend fun removeResume(accountId: String, key: String) {
        dataStore.edit { prefs ->
            val next = decodeResume(prefs, accountId).filterNot { it.key == key }
            prefs[resumeKey(accountId)] = json.encodeToString(next)
        }
    }

    private fun decodeFavorites(prefs: Preferences, accountId: String): List<FavoriteItem> =
        prefs[favoritesKey(accountId)]
            ?.let { runCatching { json.decodeFromString<List<FavoriteItem>>(it) }.getOrNull() }
            ?: emptyList()

    private fun decodeResume(prefs: Preferences, accountId: String): List<ResumeEntry> =
        prefs[resumeKey(accountId)]
            ?.let { runCatching { json.decodeFromString<List<ResumeEntry>>(it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        const val MAX_RESUME = 60
    }
}
