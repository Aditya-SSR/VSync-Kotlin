package com.minimallabs.vsync.feature.auth

import android.util.Base64
import com.minimallabs.vsync.core.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.Calendar
import java.util.TimeZone
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** What a VTOP sign-in code email looks like. */
object VtopOtpEmail {
    const val SENDER = "noreply.sdc@vitap.ac.in"

    fun isFromVtop(address: String?): Boolean = (address ?: "").trim().lowercase() == SENDER

    /** The first standalone 6 digits, or six digits separated by spaces. */
    fun extractCode(body: String): String? {
        val text = toText(body)
        Regex("(?<!\\d)\\d{6}(?!\\d)").find(text)?.let { return it.value }
        Regex("(?<!\\d)(\\d)(?:[ \\t\\u00A0]+(\\d)){5}(?!\\d)").find(text)?.let { return it.value.replace(Regex("[^\\d]"), "") }
        return null
    }

    fun toText(html: String): String {
        var text = html
        text = text.replace(Regex("<(style|script)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        text = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        text = text.replace(Regex("<[^>]*>"), " ")
        return text.replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    }
}

data class GmailOtpAccount(val email: String, val appPassword: String)

/** Gmail address + app password, only ever in the encrypted store on the phone. */
object GmailOtpStore {
    private const val EMAIL = "gmail_otp_email_v1"
    private const val PASSWORD = "gmail_otp_app_password_v1"
    private const val ENABLED = "gmail_otp_enabled_v1"

    fun read(): GmailOtpAccount? {
        val email = App.store.secureRead(EMAIL)
        val password = App.store.secureRead(PASSWORD)
        if (email.isNullOrEmpty() || password.isNullOrEmpty()) return null
        return GmailOtpAccount(email, password)
    }

    fun save(account: GmailOtpAccount) {
        App.store.secureWrite(EMAIL, account.email)
        App.store.secureWrite(PASSWORD, account.appPassword)
        App.store.secureWrite(ENABLED, "true")
    }

    fun clear() {
        App.store.secureWrite(EMAIL, null)
        App.store.secureWrite(PASSWORD, null)
        App.store.secureWrite(ENABLED, null)
    }

    fun isEnabled(): Boolean = App.store.secureRead(ENABLED) == "true"
    fun setEnabled(value: Boolean) = App.store.secureWrite(ENABLED, if (value) "true" else "false")
    fun isReady(): Boolean = read() != null && isEnabled()

    /** A Google app password is 16 letters, usually shown in groups of four. */
    fun normalisePassword(raw: String): String? {
        val letters = raw.replace(Regex("\\s+"), "").lowercase()
        return if (Regex("^[a-z]{16}$").matches(letters)) letters else null
    }

    fun isValidEmail(raw: String) = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(raw.trim())
}

enum class OtpReaderProblem { NotConfigured, InvalidCredentials, AccessBlocked, Network, TimedOut, Cancelled, Unexpected }

class OtpReaderException(val problem: OtpReaderProblem, val detail: String? = null) : Exception(detail) {
    val userMessage: String
        get() = when (problem) {
            OtpReaderProblem.NotConfigured -> "Gmail is not connected."
            OtpReaderProblem.InvalidCredentials -> "Gmail did not accept the address or app password."
            OtpReaderProblem.AccessBlocked -> "This Google account does not allow app passwords or IMAP. Your college may have turned them off."
            OtpReaderProblem.Network -> "Could not reach Gmail. Check your connection."
            OtpReaderProblem.TimedOut -> "No code arrived in Gmail. Type it in, or tap Resend."
            OtpReaderProblem.Cancelled -> "Stopped."
            OtpReaderProblem.Unexpected -> "Could not read your Gmail."
        }
}

class MailMessage(val uid: Long, val received: Long?, val from: String?, val body: String)
class MailboxSummary(val count: Int, val latest: Long?)
class OtpFound(val code: String, val uid: Long, val received: Long?)

interface OtpMailbox {
    fun open()
    fun fromVtop(sinceMs: Long): List<MailMessage>
    fun summary(sinceMs: Long): MailboxSummary
    fun close()
}

/** Polls the mailbox until a fresh VTOP code shows up (or 45 s pass). */
class GmailOtpReader(
    private val openMailbox: () -> OtpMailbox,
    private val pollEveryMs: Long = 3_000,
    private val timeoutMs: Long = 45_000,
    private val slackMs: Long = 120_000,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun waitForCode(issuedAfterMs: Long, ignoreUids: Set<Long> = emptySet(), isCancelled: () -> Boolean = { false }): OtpFound =
        withContext(Dispatchers.IO) {
            val deadline = now() + timeoutMs
            val since = issuedAfterMs - slackMs
            var mailbox: OtpMailbox? = null
            var reconnects = 0
            var lastTrouble: OtpReaderException? = null
            try {
                while (true) {
                    if (isCancelled()) throw OtpReaderException(OtpReaderProblem.Cancelled)
                    try {
                        if (mailbox == null) {
                            val m = openMailbox()
                            try { m.open() } catch (e: Throwable) { runCatching { m.close() }; throw e }
                            mailbox = m
                        }
                        val mails = mailbox.fromVtop(since)
                        lastTrouble = null
                        newestCode(mails, since, ignoreUids)?.let { return@withContext it }
                    } catch (e: OtpReaderException) {
                        if (e.problem != OtpReaderProblem.Network || ++reconnects > 2) throw e
                        lastTrouble = e
                        runCatching { mailbox?.close() }
                        mailbox = null
                    }
                    if (now() + pollEveryMs >= deadline) throw lastTrouble ?: OtpReaderException(OtpReaderProblem.TimedOut)
                    sleep(pollEveryMs)
                }
                @Suppress("UNREACHABLE_CODE")
                throw OtpReaderException(OtpReaderProblem.Unexpected)
            } finally {
                runCatching { mailbox?.close() }
            }
        }

    /** Used by the "Test connection" button: how many VTOP mails in the last [days]. */
    suspend fun check(days: Int = 30): MailboxSummary = withContext(Dispatchers.IO) {
        val mailbox = openMailbox()
        try {
            mailbox.open()
            mailbox.summary(now() - days * 86_400_000L)
        } finally {
            runCatching { mailbox.close() }
        }
    }

    private fun newestCode(mails: List<MailMessage>, since: Long, ignore: Set<Long>): OtpFound? {
        val candidates = mails.filter {
            VtopOtpEmail.isFromVtop(it.from) && it.uid !in ignore && (it.received == null || it.received >= since)
        }.sortedByDescending { it.uid }
        for (m in candidates) {
            val code = VtopOtpEmail.extractCode(m.body)
            if (code != null) return OtpFound(code, m.uid, m.received)
        }
        return null
    }
}

/** A minimal read-only IMAP client: LOGIN, EXAMINE INBOX, UID SEARCH, UID FETCH. */
class ImapOtpMailbox(
    private val email: String,
    private val appPassword: String,
    private val host: String = "imap.gmail.com",
    private val port: Int = 993,
    private val secure: Boolean = true,
    private val timeoutMs: Int = 15_000,
) : OtpMailbox {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var tag = 0
    private var loggedIn = false

    /** One server response line; literals ({n} blocks) are kept as bytes. */
    private class Response(val text: String, val literals: List<ByteArray>)

    override fun open() {
        try {
            val s: Socket = if (secure) {
                val plain = Socket()
                plain.connect(InetSocketAddress(host, port), timeoutMs)
                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true).also {
                    (it as SSLSocket).startHandshake()
                }
            } else {
                Socket().also { it.connect(InetSocketAddress(host, port), timeoutMs) }
            }
            s.soTimeout = timeoutMs
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = s.getOutputStream()
            readResponse() // greeting
            command("LOGIN ${quote(email)} ${quote(appPassword)}")
            loggedIn = true
            command("EXAMINE INBOX")
        } catch (e: Throwable) {
            throw classify(e)
        }
    }

    private fun search(sinceMs: Long): List<Long> {
        val lines = command("UID SEARCH FROM \"${VtopOtpEmail.SENDER}\" SINCE ${imapDate(sinceMs)}")
        return lines.filter { it.text.startsWith("* SEARCH") }
            .flatMap { it.text.removePrefix("* SEARCH").trim().split(' ').mapNotNull { n -> n.toLongOrNull() } }
            .sortedDescending()
    }

    override fun fromVtop(sinceMs: Long): List<MailMessage> {
        if (socket == null) throw OtpReaderException(OtpReaderProblem.Unexpected, "not open")
        try {
            val newest = search(sinceMs).take(5)
            if (newest.isEmpty()) return emptyList()
            val lines = command("UID FETCH ${newest.joinToString(",")} (INTERNALDATE BODY.PEEK[])")
            return lines.mapNotNull { r ->
                if (!r.text.contains("FETCH")) return@mapNotNull null
                val uid = Regex("UID (\\d+)").find(r.text)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
                val raw = r.literals.firstOrNull() ?: ByteArray(0)
                val mime = Mime.parse(raw)
                MailMessage(uid, internalDate(r.text), mime.fromAddress, mime.text)
            }
        } catch (e: Throwable) {
            throw classify(e)
        }
    }

    override fun summary(sinceMs: Long): MailboxSummary {
        if (socket == null) throw OtpReaderException(OtpReaderProblem.Unexpected, "not open")
        try {
            val newest = search(sinceMs).take(300)
            if (newest.isEmpty()) return MailboxSummary(0, null)
            val lines = command("UID FETCH ${newest.joinToString(",")} (INTERNALDATE)")
            var count = 0
            var latest: Long? = null
            for (r in lines) {
                if (!r.text.contains("FETCH")) continue
                val at = internalDate(r.text)
                if (at != null && at < sinceMs) continue
                count++
                if (at != null && (latest == null || at > latest)) latest = at
            }
            return MailboxSummary(count, latest)
        } catch (e: Throwable) {
            throw classify(e)
        }
    }

    override fun close() {
        val s = socket ?: return
        socket = null
        try {
            if (loggedIn) {
                s.soTimeout = 3000
                command("LOGOUT")
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { s.close() }
        }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Sends one command and returns the untagged responses; throws on NO/BAD. */
    private fun command(text: String): List<Response> {
        val t = "A${++tag}"
        output!!.write("$t $text\r\n".toByteArray())
        output!!.flush()
        val out = ArrayList<Response>()
        while (true) {
            val r = readResponse()
            if (r.text.startsWith("$t ")) {
                val status = r.text.substring(t.length + 1)
                if (!status.startsWith("OK")) throw ImapError(status)
                return out
            }
            if (r.text.startsWith("* BYE") && !text.startsWith("LOGOUT")) throw ImapError(r.text)
            out.add(r)
        }
    }

    private class ImapError(message: String) : Exception(message)

    private fun readResponse(): Response {
        val text = StringBuilder()
        val literals = ArrayList<ByteArray>()
        while (true) {
            val line = readLine()
            text.append(line)
            val m = Regex("\\{(\\d+)\\}$").find(line)
            if (m == null) break
            val n = m.groupValues[1].toInt()
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input!!.read(buf, got, n - got)
                if (r < 0) throw java.io.EOFException("connection lost")
                got += r
            }
            literals.add(buf)
        }
        return Response(text.toString(), literals)
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input!!.read()
            if (b < 0) throw java.io.EOFException("connection lost")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

    companion object {
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** IMAP SINCE is day-granular and timezone-vague, so ask from a day earlier. */
        fun imapDate(whenMs: Long): String {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.timeInMillis = whenMs - 86_400_000L
            return "${c.get(Calendar.DAY_OF_MONTH)}-${MONTHS[c.get(Calendar.MONTH)]}-${c.get(Calendar.YEAR)}"
        }

        /** `13-Nov-2026 11:22:05 +0530` -> epoch millis. */
        fun internalDate(raw: String?): Long? {
            if (raw == null) return null
            val m = Regex("(\\d{1,2})-([A-Za-z]{3})-(\\d{4})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\s*([+-])(\\d{2})(\\d{2})").find(raw) ?: return null
            val g = m.groupValues
            val month = MONTHS.indexOfFirst { it.equals(g[2], ignoreCase = true) }
            if (month < 0) return null
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(g[3].toInt(), month, g[1].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            val offset = (g[8].toInt() * 60 + g[9].toInt()) * 60_000L
            return if (g[7] == "+") c.timeInMillis - offset else c.timeInMillis + offset
        }

        fun classify(error: Throwable): OtpReaderException {
            if (error is OtpReaderException) return error
            if (error is java.net.SocketTimeoutException || error is java.net.SocketException ||
                error is java.net.UnknownHostException || error is javax.net.ssl.SSLException || error is java.io.EOFException
            ) return OtpReaderException(OtpReaderProblem.Network, "$error")
            val text = (error.message ?: "$error").lowercase()
            if (text.contains("authenticationfailed") || text.contains("invalid credentials") ||
                text.contains("username and password not accepted") || text.contains("authentication failed")
            ) return OtpReaderException(OtpReaderProblem.InvalidCredentials, text)
            if (text.contains("application-specific password") || text.contains("imap access is disabled") ||
                text.contains("imap is disabled") || text.contains("web login required") || text.contains("[alert]") ||
                text.contains("not allowed") || text.contains("disabled by")
            ) return OtpReaderException(OtpReaderProblem.AccessBlocked, text)
            if (text.contains("connection lost") || text.contains("connection error") ||
                text.contains("client disconnected") || text.contains("socket")
            ) return OtpReaderException(OtpReaderProblem.Network, text)
            return OtpReaderException(OtpReaderProblem.Unexpected, text)
        }
    }
}

/** Just enough MIME to get the sender and the readable text out of a raw email. */
class Mime(val fromAddress: String?, val text: String) {
    companion object {
        fun parse(raw: ByteArray): Mime {
            val all = String(raw, Charsets.ISO_8859_1)
            val (headers, _) = split(all)
            val from = headers["from"]?.let { v ->
                Regex("<([^>]+)>").find(v)?.groupValues?.get(1) ?: Regex("[^\\s<>\"]+@[^\\s<>\"]+").find(v)?.value
            }
            val out = StringBuilder()
            collect(all, out, 0)
            return Mime(from?.trim(), out.toString())
        }

        private fun split(part: String): Pair<Map<String, String>, String> {
            val normal = part.replace("\r\n", "\n")
            val idx = normal.indexOf("\n\n")
            val head = if (idx < 0) normal else normal.substring(0, idx)
            val body = if (idx < 0) "" else normal.substring(idx + 2)
            val headers = HashMap<String, String>()
            // Unfold continuation lines.
            val unfolded = head.replace(Regex("\n[ \t]+"), " ")
            for (line in unfolded.split('\n')) {
                val c = line.indexOf(':')
                if (c > 0) headers.putIfAbsent(line.substring(0, c).trim().lowercase(), line.substring(c + 1).trim())
            }
            return headers to body
        }

        private fun collect(part: String, out: StringBuilder, depth: Int) {
            if (depth > 6) return
            val (headers, body) = split(part)
            val type = (headers["content-type"] ?: "text/plain").lowercase()
            if (type.startsWith("multipart/")) {
                val boundary = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                    .find(headers["content-type"] ?: "")?.groupValues?.get(1) ?: return
                val pieces = body.split("--$boundary")
                for (i in 1 until pieces.size) {
                    val p = pieces[i]
                    if (p.startsWith("--")) break
                    collect(p.trimStart('\n'), out, depth + 1)
                }
                return
            }
            if (!type.startsWith("text/")) return
            val charset = Regex("charset=\"?([^\";\\s]+)\"?", RegexOption.IGNORE_CASE).find(headers["content-type"] ?: "")
                ?.groupValues?.get(1)?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            val bytes: ByteArray = when ((headers["content-transfer-encoding"] ?: "").lowercase().trim()) {
                "base64" -> runCatching { Base64.decode(body.replace(Regex("\\s"), ""), Base64.DEFAULT) }.getOrDefault(ByteArray(0))
                "quoted-printable" -> quotedPrintable(body)
                else -> body.toByteArray(Charsets.ISO_8859_1)
            }
            out.append(String(bytes, charset)).append('\n')
        }

        private fun quotedPrintable(s: String): ByteArray {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '=') {
                    if (i + 1 < s.length && s[i + 1] == '\n') { i += 2; continue }
                    if (i + 2 < s.length) {
                        val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                        if (hex != null) { out.write(hex); i += 3; continue }
                    }
                }
                out.write(c.code and 0xFF)
                i++
            }
            return out.toByteArray()
        }
    }
}
