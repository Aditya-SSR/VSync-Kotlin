package com.minimallabs.vsync.core

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minimallabs.vsync.bridge.VtopApi
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.Credentials
import com.minimallabs.vsync.data.Prefs
import com.minimallabs.vsync.data.SemesterCache
import com.minimallabs.vsync.data.SemesterInfo
import com.minimallabs.vsync.data.Store
import com.minimallabs.vsync.data.UserData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

class VSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        App.init(this)
    }
}

/** The demo account never contacts VTOP: everything is served from assets/demo. */
object DemoMode {
    const val REG_NO = "25VSYNC"
    const val PASSWORD = "dgklynx"
    const val SEMESTER = "DEMOSEM2526"

    @Volatile var isOn: Boolean = false
        private set
    private var cache: JsonObject? = null

    fun init() { isOn = App.store.sp.getBoolean("is_demo_mode", false) }

    fun set(value: Boolean) {
        isOn = value
        App.store.sp.edit().putBoolean("is_demo_mode", value).apply()
        if (!value) cache = null
    }

    fun isDemoCredentials(username: String, password: String) =
        username.trim().uppercase() == REG_NO && password.trim() == PASSWORD

    val credentials get() = Credentials(REG_NO, PASSWORD, SEMESTER)

    fun dataset(): JsonObject = cache ?: AppJson.parseToJsonElement(
        App.context.assets.open("demo/demo_dataset.json").bufferedReader().use { it.readText() }
    ).jsonObject.also { cache = it }

    fun sectionJson(key: String): JsonElement? = dataset()[key]

    inline fun <reified T> section(key: String): T = AppJson.decodeFromJsonElement(sectionJson(key)!!)

    fun user(): UserData = section("user")
}

/** Process-wide state. Compose reads the `mutableStateOf` fields directly. */
object App {
    lateinit var context: Context
        private set
    lateinit var store: Store
        private set
    val session = VtopSession()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var user by mutableStateOf<UserData?>(null)
        private set
    var prefs by mutableStateOf(Prefs())
        private set
    var credentials by mutableStateOf<Credentials?>(null)
        private set

    /** One-line messages for the snackbar host in the shell. */
    val toasts = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun toast(message: String) { toasts.tryEmit(message) }

    fun init(app: Application) {
        context = app
        store = Store(app)
        DemoMode.init()
        prefs = store.loadPrefs()
        user = store.loadUser()
        credentials = runCatching { store.loadCredentials() }.getOrNull()
        Semesters.healSelection(credentials?.semSubId)
    }

    fun updatePrefs(change: (Prefs) -> Prefs) {
        prefs = change(prefs)
        store.savePrefs(prefs)
    }

    fun login(newUser: UserData, newCredentials: Credentials) {
        user = newUser
        store.saveUser(newUser)
        credentials = newCredentials
        store.saveCredentials(newCredentials)
    }

    fun updateUser(updated: UserData) {
        user = updated
        store.saveUser(updated)
    }

    fun updateCredentials(c: Credentials) {
        credentials = c
        store.saveCredentials(c)
    }

    val logoutHooks = mutableListOf<() -> Unit>()

    fun logout() {
        user = null
        store.clearUser()
        store.clearSemesters()
        store.cacheClear()
        DemoMode.set(false)
        credentials = null
        store.saveCredentials(null)
        session.resetClient()
        logoutHooks.forEach { runCatching { it() } }
    }

    /** Runs a VTOP call with the saved login, re-logging in once on an expired session. */
    suspend fun <T> vtop(maxRetries: Int = 2, operation: suspend (VtopApi) -> T): T {
        if (DemoMode.isOn) throw DemoModeException()
        val c = credentials
            ?: throw UserMessage("Your saved login could not be read. Please log out and log in again.")
        return session.execute(c.registrationNumber, c.password, maxRetries, operation)
    }

    val semesterId: String get() = credentials?.semSubId ?: ""
}

/** The saved semester list and the selected one (port of `AuthLocalRepository`). */
object Semesters {
    fun all(): List<SemesterInfo> = App.store.loadSemesters().map { SemesterInfo(it.semesterId, it.semesterName) }

    fun rowsFor(semesters: List<SemesterInfo>, selectedId: String?, updatedAt: Long) =
        semesters.map { SemesterCache(it.id, it.name, it.id == selectedId, updatedAt) }

    /** A list refresh must carry the selection over (it used to be wiped). */
    fun save(semesters: List<SemesterInfo>) {
        val selectedId = App.store.loadSemesters().firstOrNull { it.isSelected }?.semesterId
        App.store.saveSemesterRows(rowsFor(semesters, selectedId, System.currentTimeMillis()))
    }

    fun select(semesterId: String) {
        App.store.saveSemesterRows(App.store.loadSemesters().map { it.copy(isSelected = it.semesterId == semesterId) })
    }

    fun selected(): SemesterInfo? =
        App.store.loadSemesters().firstOrNull { it.isSelected }?.let { SemesterInfo(it.semesterId, it.semesterName) }
            ?: healSelection(App.credentials?.semSubId)

    /** Restores a lost selection from the login's semester id. */
    fun healSelection(semesterId: String?): SemesterInfo? {
        val rows = App.store.loadSemesters()
        rows.firstOrNull { it.isSelected }?.let { return SemesterInfo(it.semesterId, it.semesterName) }
        if (semesterId.isNullOrEmpty()) return null
        val match = rows.firstOrNull { it.semesterId == semesterId } ?: return null
        App.store.saveSemesterRows(rows.map { it.copy(isSelected = it.semesterId == semesterId) })
        return SemesterInfo(match.semesterId, match.semesterName)
    }
}
