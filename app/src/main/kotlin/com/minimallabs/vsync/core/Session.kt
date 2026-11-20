package com.minimallabs.vsync.core

import com.minimallabs.vsync.bridge.VtopApi
import com.minimallabs.vsync.bridge.VtopClient
import com.minimallabs.vsync.bridge.VtopError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.security.MessageDigest

/** Thrown when a live VTOP call is attempted in the demo account. */
class DemoModeException(message: String = "This feature is not available in the demo account.") : Exception(message)

/**
 * Owns the ONE authenticated VTOP session (port of `VtopClientService`).
 *
 * VTOP emails a fresh OTP for every credentials POST from an IP it does not
 * trust, so at most one login runs at a time: callers that arrive while one is
 * in flight (including while it waits for the student to type the OTP) join it.
 * The session is renewed after 14 minutes of IDLE time, not age.
 */
class VtopSession(
    private val createClient: (String, String, String) -> VtopApi = { u, p, ua -> VtopClient(u, p, ua) },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val userAgent: () -> String = { deviceUserAgent() },
    private val isDemo: () -> Boolean = { DemoMode.isOn },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val retryDelayMs: Long = 500,
) {
    private class Flight(val key: String, val deferred: Deferred<VtopApi>)

    private val lock = Any()
    private var client: VtopApi? = null
    private var initialized = false
    private var otpPending = false
    private var currentUsername: String? = null
    private var currentDigest: String? = null
    private var sessionCreatedAt: Long? = null
    private var lastActivityAt: Long? = null
    private var otpGate: CompletableDeferred<Unit>? = null
    private var flight: Flight? = null
    private var generation = 0

    private val _otpRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val _authFailure = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Emits when the login needs an OTP; the global prompt listens. */
    val onOtpRequired: SharedFlow<Unit> = _otpRequired

    /** Emits the reason when VTOP rejects the credentials. */
    val onAuthFailure: SharedFlow<String> = _authFailure

    val isOtpPending: Boolean get() = synchronized(lock) { otpPending }
    val isInitialized: Boolean get() = synchronized(lock) { initialized && client != null }
    val currentClient: VtopApi? get() = synchronized(lock) { if (initialized) client else null }

    private fun digestOf(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun nearExpiry(): Boolean {
        val last = lastActivityAt ?: sessionCreatedAt ?: return true
        return now() - last >= REFRESH_MS
    }

    private fun expired(): Boolean {
        val last = lastActivityAt ?: sessionCreatedAt ?: return true
        return now() - last >= EXPIRY_MS
    }

    private fun needsNewClient(username: String, digest: String) =
        client == null || !initialized || currentUsername != username || currentDigest != digest || nearExpiry()

    /** The session for these credentials, logging in (once) if needed. */
    suspend fun getClient(username: String, password: String): VtopApi {
        if (isDemo()) throw DemoModeException()
        val digest = digestOf(password)
        val joined: Deferred<VtopApi> = synchronized(lock) {
            if (!needsNewClient(username, digest)) {
                // Handing the session out counts as using it.
                lastActivityAt = now()
                return client!!
            }
            val key = "$username:$digest"
            val running = flight
            if (running != null && running.key == key) {
                running.deferred
            } else {
                // A login for DIFFERENT credentials is in flight: drop it.
                if (running != null) resetLocked()
                val gen = ++generation
                val deferred = scope.async { initializeClient(username, password, digest, gen) }
                flight = Flight(key, deferred)
                deferred.invokeOnCompletion {
                    synchronized(lock) { if (flight?.deferred === deferred) flight = null }
                }
                deferred
            }
        }
        return joined.await()
    }

    private suspend fun initializeClient(username: String, password: String, digest: String, gen: Int): VtopApi {
        try {
            val made = createClient(username, password, userAgent())
            synchronized(lock) { client = made }
            made.text("login")
            synchronized(lock) {
                throwIfSuperseded(gen)
                currentUsername = username
                currentDigest = digest
                sessionCreatedAt = now()
                lastActivityAt = sessionCreatedAt
                initialized = true
            }
            return made
        } catch (e: VtopError) {
            when (e.type) {
                "AuthenticationFailed" -> {
                    clearIfCurrent(gen)
                    _authFailure.tryEmit(e.detail)
                    throw e
                }
                "InvalidCredentials" -> {
                    clearIfCurrent(gen)
                    _authFailure.tryEmit("Invalid username or password.")
                    throw e
                }
                "LoginOtpRequired" -> {
                    val gate = CompletableDeferred<Unit>()
                    val pending: VtopApi = synchronized(lock) {
                        throwIfSuperseded(gen)
                        // Keep the client alive: the OTP must go to the same session.
                        currentUsername = username
                        currentDigest = digest
                        otpPending = true
                        otpGate = gate
                        client!!
                    }
                    _otpRequired.tryEmit(Unit)
                    try {
                        gate.await()
                    } catch (x: Throwable) {
                        synchronized(lock) {
                            if (clearIfCurrentLocked(gen)) {
                                otpPending = false
                                otpGate = null
                            }
                        }
                        throw x
                    }
                    return pending
                }
                else -> {
                    clearIfCurrent(gen)
                    throw e
                }
            }
        } catch (e: Throwable) {
            clearIfCurrent(gen)
            throw e
        }
    }

    private fun clearIfCurrent(gen: Int): Boolean = synchronized(lock) { clearIfCurrentLocked(gen) }

    private fun clearIfCurrentLocked(gen: Int): Boolean {
        if (generation != gen) return false
        initialized = false
        client = null
        currentUsername = null
        currentDigest = null
        sessionCreatedAt = null
        lastActivityAt = null
        return true
    }

    private fun throwIfSuperseded(gen: Int) {
        if (generation != gen) throw IllegalStateException("The session was reset while signing in. Please try again.")
    }

    /** Runs [operation] on the session, re-logging in ONCE if VTOP reports the session expired. */
    suspend fun <T> execute(
        username: String,
        password: String,
        maxRetries: Int = 2,
        operation: suspend (VtopApi) -> T,
    ): T {
        var attempts = 0
        while (true) {
            attempts++
            try {
                val c = getClient(username, password)
                return operation(c)
            } catch (e: Throwable) {
                if (attempts >= maxRetries) throw e
                // Only an expired session is worth another credentials POST.
                if (e is VtopError && e.type == "SessionExpired") {
                    // Never tear down a session someone is mid-OTP on.
                    if (!isOtpPending) invalidateSession()
                    delay(retryDelayMs)
                } else {
                    throw e
                }
            }
        }
    }

    suspend fun submitLoginOtp(code: String) {
        val c = synchronized(lock) {
            if (!otpPending || client == null) throw IllegalStateException("No OTP-pending session")
            client!!
        }
        c.text("login_otp", code)
        val gate = synchronized(lock) {
            otpPending = false
            initialized = true
            sessionCreatedAt = now()
            lastActivityAt = sessionCreatedAt
            val g = otpGate
            otpGate = null
            g
        }
        gate?.complete(Unit)
    }

    suspend fun resendLoginOtp() {
        val c = synchronized(lock) {
            if (!otpPending || client == null) throw IllegalStateException("No OTP-pending session")
            client!!
        }
        c.text("login_otp_resend")
    }

    fun cancelOtp() {
        val gate = synchronized(lock) {
            if (!otpPending || otpGate == null) return
            otpPending = false
            val g = otpGate
            otpGate = null
            g
        }
        gate?.completeExceptionally(Exception("Login verification was cancelled. Please try again."))
    }

    /** Drops the cached session without touching a login already in flight. */
    private fun invalidateSession() = synchronized(lock) {
        if (flight != null) return@synchronized
        client = null
        initialized = false
        sessionCreatedAt = null
        lastActivityAt = null
    }

    /** For logout or a credential change. */
    fun resetClient() {
        cancelOtp()
        synchronized(lock) { resetLocked() }
    }

    private fun resetLocked() {
        val gate = otpGate
        otpGate = null
        otpPending = false
        gate?.completeExceptionally(Exception("Login verification was cancelled. Please try again."))
        generation++
        client = null
        initialized = false
        currentUsername = null
        currentDigest = null
        sessionCreatedAt = null
        lastActivityAt = null
        flight = null
    }

    fun hasSessionFor(username: String, password: String): Boolean = synchronized(lock) {
        initialized && currentUsername == username && currentDigest == digestOf(password) && !expired()
    }

    companion object {
        const val EXPIRY_MS = 15 * 60_000L
        const val REFRESH_MS = 14 * 60_000L
    }
}

fun deviceUserAgent(): String = try {
    "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL}) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
} catch (_: Throwable) {
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
