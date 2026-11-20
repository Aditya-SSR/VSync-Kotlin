package com.minimallabs.vsync.feature.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.Failures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val OTP_WINDOW_SECONDS = 180
const val OTP_SUCCESS_HOLD_MS = 1700L

enum class OtpActivity { Waiting, Submitting, Resending }
enum class OtpAutoPhase { NotEnabled, Fetching, Verifying, Succeeded, Failed }

data class OtpChallenge(
    val isActive: Boolean = false,
    val activity: OtpActivity = OtpActivity.Waiting,
    val minimized: Boolean = false,
    val remainingSeconds: Int = 0,
    val message: String = "",
    val errorMessage: String? = null,
    val autoStatus: String? = null,
    val autoPhase: OtpAutoPhase? = null,
) {
    val isSucceeded get() = autoPhase == OtpAutoPhase.Succeeded
    val isBusy get() = activity == OtpActivity.Submitting || activity == OtpActivity.Resending
    val canResend get() = isActive && remainingSeconds <= 0 && !isBusy

    companion object {
        fun formatSeconds(total: Int) = "%02d:%02d".format(total / 60, total % 60)
    }
}

/**
 * The login OTP prompt's state (port of `LoginOtpChallenge`).
 * Automatic reading from Gmail must fail QUIETLY: typing the code always works.
 */
object OtpPrompt {
    var state by mutableStateOf(OtpChallenge())
        private set

    private var ticker: Job? = null
    private var auto: Job? = null
    private var successTimer: Job? = null

    /** Called once at startup: opens the prompt whenever the session asks for an OTP. */
    fun attach() {
        App.scope.launch { App.session.onOtpRequired.collect { requestOtp() } }
        App.logoutHooks.add { dismiss() }
    }

    fun requestOtp() {
        if (state.isActive) {
            state = state.copy(minimized = false, activity = OtpActivity.Waiting,
                message = "Additional verification required. OTP sent to your email.", errorMessage = null)
            return
        }
        state = OtpChallenge(isActive = true, remainingSeconds = OTP_WINDOW_SECONDS,
            message = "Additional verification required. OTP sent to your email.")
        startTicker()
        startAutoRead(System.currentTimeMillis())
    }

    /** Collapsing is the student's choice only (chevron / scrim tap). */
    fun minimize() {
        if (!state.isActive) return
        if (state.isSucceeded) { finish(); return }
        state = state.copy(minimized = true)
    }

    fun reopen() { if (state.isActive) state = state.copy(minimized = false) }

    fun dismiss() { if (state.isActive) finish() }

    /** Close the prompt AND abandon the pending login. */
    fun cancel() {
        dismiss()
        App.session.cancelOtp()
    }

    private fun finish() {
        successTimer?.cancel(); successTimer = null
        ticker?.cancel(); ticker = null
        auto?.cancel(); auto = null
        state = OtpChallenge()
    }

    fun submit(code: String) { App.scope.launch { submitOtp(code, automatic = false) } }

    private suspend fun submitOtp(code: String, automatic: Boolean) {
        if (!state.isActive || state.isBusy) return
        val digits = code.replace(Regex("\\D"), "")
        if (digits.length != 6) {
            state = state.copy(errorMessage = "Please enter a valid 6-digit OTP.")
            return
        }
        state = state.copy(activity = OtpActivity.Submitting, errorMessage = null)
        try {
            App.session.submitLoginOtp(digits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!state.isActive) return
            state = state.copy(activity = OtpActivity.Waiting, minimized = false,
                errorMessage = Failures.messageFor(e, "Failed to verify OTP. Please try again."))
            return
        }
        if (!state.isActive) return
        ticker?.cancel(); ticker = null
        if (automatic && !state.minimized) {
            // A code that came from Gmail shows a tick for a moment, then closes.
            state = state.copy(activity = OtpActivity.Waiting, errorMessage = null, autoStatus = null, autoPhase = OtpAutoPhase.Succeeded)
            successTimer?.cancel()
            successTimer = App.scope.launch {
                delay(OTP_SUCCESS_HOLD_MS)
                if (state.isActive) { auto = null; finish() }
            }
        } else {
            val running = auto
            auto = null
            state = OtpChallenge()
            if (!automatic) running?.cancel()
        }
    }

    fun resend() {
        if (!state.canResend) return
        state = state.copy(activity = OtpActivity.Resending, errorMessage = null)
        App.scope.launch {
            try {
                App.session.resendLoginOtp()
            } catch (e: Throwable) {
                if (state.isActive) state = state.copy(activity = OtpActivity.Waiting,
                    errorMessage = Failures.messageFor(e, "Failed to resend OTP. Please try again."))
                return@launch
            }
            if (!state.isActive) return@launch
            state = state.copy(activity = OtpActivity.Waiting, remainingSeconds = OTP_WINDOW_SECONDS,
                message = "A new OTP has been sent to your registered email.", errorMessage = null)
            startTicker()
            startAutoRead(System.currentTimeMillis())
        }
    }

    private fun startAutoRead(issuedAfter: Long) {
        auto?.cancel()
        auto = App.scope.launch {
            try { autoReadLoop(issuedAfter) } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
        }
    }

    private suspend fun autoReadLoop(issuedAfter: Long) {
        val tried = HashSet<Long>()
        val self = kotlinx.coroutines.currentCoroutineContext()[Job]
        fun stopped() = self?.isActive != true || !state.isActive
        // Never open a mailbox unless the student set it up AND left it on.
        if (!GmailOtpStore.isReady()) {
            if (!stopped()) state = state.copy(autoPhase = OtpAutoPhase.NotEnabled)
            return
        }
        while (!stopped()) {
            val found: OtpFound
            try {
                state = state.copy(autoPhase = OtpAutoPhase.Fetching, autoStatus = null)
                val account = GmailOtpStore.read()
                if (account == null || !GmailOtpStore.isEnabled()) {
                    state = state.copy(autoPhase = OtpAutoPhase.NotEnabled)
                    return
                }
                found = GmailOtpReader({ ImapOtpMailbox(account.email, account.appPassword) })
                    .waitForCode(issuedAfter, tried, isCancelled = { stopped() })
            } catch (e: OtpReaderException) {
                if (stopped() || e.problem == OtpReaderProblem.Cancelled) return
                state = state.copy(autoPhase = OtpAutoPhase.Failed, autoStatus = e.userMessage)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (stopped()) return
                state = state.copy(autoPhase = OtpAutoPhase.Failed, autoStatus = "Could not read your Gmail.")
                return
            }
            if (stopped()) return
            state = state.copy(autoPhase = OtpAutoPhase.Verifying)
            submitOtp(found.code, automatic = true)
            if (stopped() || state.isSucceeded) return // it worked, or the prompt was closed
            tried.add(found.uid)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = App.scope.launch {
            while (true) {
                delay(1000)
                if (!state.isActive) return@launch
                val next = state.remainingSeconds - 1
                if (next <= 0) {
                    state = state.copy(remainingSeconds = 0, message = "OTP expired. Tap resend to get a new one.",
                        errorMessage = "OTP expired. Please resend OTP.")
                    return@launch
                }
                state = state.copy(remainingSeconds = next)
            }
        }
    }
}
