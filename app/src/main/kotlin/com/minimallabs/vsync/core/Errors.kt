package com.minimallabs.vsync.core

import com.minimallabs.vsync.bridge.VtopError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Port of `VtopException.failureMessageFor` and friends. */
object Failures {
    /** Replaceable in tests. */
    var internetProbe: suspend () -> Boolean = ::probeInternet

    private val ambiguous = setOf("NetworkError", "TimeoutError", "DnsError", "ConnectionRefused")

    /** The text shown to the student for any failure. */
    suspend fun messageFor(e: Throwable, fallback: String = "An unexpected error occurred. Please try again."): String =
        when (e) {
            is VtopError -> {
                val online = if (e.type in ambiguous) internetProbe() else true
                // The Flutter app passed Rust's debug text as the "message".
                failureMessageFor(e.type, e.debug, online)
            }
            is DemoModeException -> e.message ?: fallback
            is UserMessage -> e.message ?: fallback
            is java.net.UnknownHostException, is java.net.SocketException -> "No internet connection"
            else -> fallback
        }

    fun failureMessageFor(errorType: String, message: String, online: Boolean = true): String {
        if (message.contains("Number Of Maximum Fail Attempts Reached")) return message
        return when (errorType) {
            "NetworkError", "DnsError" ->
                if (online) "VTOP isn't reachable right now. Your internet is working, so VIT-AP's servers are most likely down or busy. Try again in a few minutes."
                else "No internet connection. Check your Wi-Fi or mobile data and try again."
            "TimeoutError" ->
                if (online) "VTOP is responding very slowly right now. Your internet is fine, so it's most likely overloaded. Try again in a few minutes."
                else "Your connection is too slow or unstable to reach VTOP. Check your Wi-Fi or mobile data and try again."
            "ConnectionRefused" ->
                if (online) "VTOP is refusing connections, which usually means it is down for maintenance. Your internet is fine. Try again later."
                else "No internet connection. Check your Wi-Fi or mobile data and try again."
            "VtopServerError" -> "VTOP is having problems on its side and returned an error. Nothing is wrong with your account or your phone. Try again in a few minutes."
            "MenuUnavailable" -> "VTOP is not serving this page right now. It may be switched off or under maintenance. Your saved data is still here. Try again later."
            "SslError" -> "Secure connection to VTOP failed. This is usually a problem with VTOP's certificate or a network that is intercepting the connection. Try another network, or try again later."
            "ResponseReadError" -> "The connection dropped while VTOP was replying. Please try again."
            "AuthenticationFailed", "InvalidCredentials" -> "Invalid username or password. Please check your credentials and try again."
            "SessionExpired" -> "Your session has expired. The app will automatically retry with a fresh session."
            "CaptchaRequired" -> "Captcha verification is required. Please complete the captcha and try again."
            "RegistrationParsingError" -> "Invalid registration number format. Please check your registration number."
            "ParseError", "InvalidResponse" -> "VTOP sent a page the app could not read. This can happen when VTOP changes its layout or sends an error page. Your saved data is unchanged. Please try again later."
            "InvalidSemesterId" -> "That semester could not be recognised. Please pick one from the semester list."
            "ConfigurationError" -> "App configuration error. Please restart the app and try again."
            "LoginOtpRequired" -> "OTP verification is required for login."
            "LoginOtpIncorrect" -> "Incorrect OTP entered for login. Please try again."
            "LoginOtpExpired" -> "OTP for login has expired. Please request a new OTP and try again."
            else -> message
        }
    }
}

/** An exception whose message is already written for the student. */
class UserMessage(message: String) : Exception(message)

/** "Does this phone have working internet?": any answer from a neutral 204 endpoint means yes. */
suspend fun probeInternet(): Boolean = withContext(Dispatchers.IO) {
    try {
        val c = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 4000
        c.requestMethod = "GET"
        c.responseCode
        c.disconnect()
        true
    } catch (_: Throwable) {
        false
    }
}

/** An empty refresh never overwrites saved data (VTOP answers a failed request with an empty page). */
fun <T> shouldKeepSaved(fresh: List<T>, saved: List<T>): Boolean = fresh.isEmpty() && saved.isNotEmpty()
