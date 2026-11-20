package com.minimallabs.vsync.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Same switches as the Flutter `UserPreferences` entity, same defaults. */
@Serializable
data class Prefs(
    val isTimetableNotificationsEnabled: Boolean = true,
    val isExamScheduleNotificationEnabled: Boolean = true,
    val timetableNotificationDelay: Int = 10,
    val examScheduleNotificationDelay: Int = 60,
    val isPrivacyEnabled: Boolean = true,
    val isDarkModeEnabled: Boolean = true,
    val hasUserChosenTheme: Boolean = false,
    val isAmoledEnabled: Boolean = false,
    val bypassWeekendOutingRestriction: Boolean = false,
    val appTheme: String = "blue",
    val fontScale: Double = 1.2,
    val lastSync: Long? = null,
    val attendanceLastSync: Long? = null,
    val marksLastSync: Long? = null,
    val examScheduleLastSync: Long? = null,
    val isFirstLaunch: Boolean = true,
    val hideGrades: Boolean = false,
    val hideDigitalAssignments: Boolean = false,
    val hideOuting: Boolean = false,
    val hideFacultyInfo: Boolean = false,
    val hideOpenVtop: Boolean = false,
    val hideAcademicCalendar: Boolean = false,
    val hideBiometric: Boolean = false,
    val academicsListView: Boolean = false,
    val hideCalendarCountdowns: Boolean = false,
    val liquidGlassNavbar: Boolean = true,
    val classStackEnabled: Boolean = false,
    /** Liquid glass throughout the app (Appearance). */
    val liquidGlass: Boolean = true,
    val followSystemTheme: Boolean = false,
    val onboardingSeen: Boolean = false,
    val appIcon: String = "default",
)

@Serializable
private data class CacheRow(val payload: String, val updatedAt: Long)

/**
 * On-device storage. Plain JSON files replace ObjectBox:
 *   user.json        the student's saved data
 *   semesters.json   the semester list + which one is selected
 *   cache/<key>      keyed payloads with a timestamp (calendar, capstone, ...)
 * Preferences live in SharedPreferences; credentials are AES-GCM encrypted
 * with a key held in the Android Keystore.
 */
class Store(private val context: Context) {
    private val dir: File = context.filesDir
    private val cacheDir: File = File(dir, "cache").apply { mkdirs() }
    val sp: SharedPreferences = context.getSharedPreferences("vsync", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = context.getSharedPreferences("vsync_secure", Context.MODE_PRIVATE)

    // ---- user ----
    fun loadUser(): UserData? = readFile("user.json")?.let { runCatching { AppJson.decodeFromString<UserData>(it) }.getOrNull() }
    fun saveUser(user: UserData) = writeFile("user.json", AppJson.encodeToString(user))
    fun clearUser() { File(dir, "user.json").delete() }

    // ---- semesters ----
    fun loadSemesters(): List<SemesterCache> =
        readFile("semesters.json")?.let { runCatching { AppJson.decodeFromString<List<SemesterCache>>(it) }.getOrNull() } ?: emptyList()
    fun saveSemesterRows(rows: List<SemesterCache>) = writeFile("semesters.json", AppJson.encodeToString(rows))
    fun clearSemesters() { File(dir, "semesters.json").delete() }

    // ---- keyed cache ----
    private fun cacheFile(key: String) = File(cacheDir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
    fun cachePut(key: String, payload: String, now: Long = System.currentTimeMillis()) {
        cacheFile(key).writeText(AppJson.encodeToString(CacheRow(payload, now)))
    }
    fun cacheGet(key: String): Pair<String, Long>? = runCatching {
        val f = cacheFile(key)
        if (!f.exists()) null else AppJson.decodeFromString<CacheRow>(f.readText()).let { it.payload to it.updatedAt }
    }.getOrNull()
    fun cacheRemove(key: String) { cacheFile(key).delete() }
    fun cacheClear() { cacheDir.listFiles()?.forEach { it.delete() } }

    // ---- preferences ----
    fun loadPrefs(): Prefs = sp.getString("prefs", null)?.let { runCatching { AppJson.decodeFromString<Prefs>(it) }.getOrNull() } ?: Prefs()
    fun savePrefs(p: Prefs) = sp.edit().putString("prefs", AppJson.encodeToString(p)).apply()

    // ---- secure strings ----
    fun secureWrite(key: String, value: String?) {
        if (value == null) { secure.edit().remove(key).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(value.toByteArray())
        secure.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun secureRead(key: String): String? = runCatching {
        val raw = Base64.decode(secure.getString(key, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun loadCredentials(): Credentials? =
        secureRead("stu_credentials")?.let { runCatching { AppJson.decodeFromString<Credentials>(it) }.getOrNull() }
    fun saveCredentials(c: Credentials?) = secureWrite("stu_credentials", c?.let { AppJson.encodeToString(it) })

    // ---- files ----
    fun readFile(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()
    fun writeFile(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
    }
    fun deleteFile(name: String) { File(dir, name).delete() }
    fun file(name: String) = File(dir, name)

    private companion object { const val KEY_ALIAS = "vsync_secure_v1" }
}
