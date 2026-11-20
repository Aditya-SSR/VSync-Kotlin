package com.minimallabs.vsync.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The raw JNI surface of `libvsync_bridge.so` (see rust-bridge/src/lib.rs). */
internal object Native {
    init {
        System.loadLibrary("vsync_bridge")
    }

    @JvmStatic external fun newClient(username: String, password: String, userAgent: String): Long
    @JvmStatic external fun freeClient(handle: Long)
    @JvmStatic external fun call(handle: Long, method: String, argsJson: String, payload: ByteArray?): ByteArray?
}

/** An error reported by the Rust scraper. [type] is the name the Flutter app matched on. */
class VtopError(
    val type: String,
    val detail: String,
    val rustMessage: String,
    val debug: String,
) : Exception("$type: $debug")

/** What every bridge call needs; an interface so the session logic can be tested without the .so. */
interface VtopApi {
    suspend fun text(method: String, vararg args: String): String
    suspend fun bytes(method: String, vararg args: String): ByteArray
    suspend fun upload(method: String, payload: ByteArray, vararg args: String): String
    fun close() {}
}

/** One VTOP session living in Rust. All calls are blocking in Rust, so they run on IO. */
class VtopClient(username: String, password: String, userAgent: String) : VtopApi {
    private val handle: Long = Native.newClient(username, password, userAgent)

    private suspend fun raw(method: String, args: Array<out String>, payload: ByteArray?): ByteArray =
        withContext(Dispatchers.IO) {
            val argsJson = JsonArray(args.map { JsonPrimitive(it) }).toString()
            val reply = Native.call(handle, method, argsJson, payload)
                ?: throw VtopError("ParseError", "", "Unable to process server response. Please try again.", "null reply")
            if (reply.isEmpty()) {
                throw VtopError("ParseError", "", "Unable to process server response. Please try again.", "empty reply")
            }
            if (reply[0].toInt() == 2) {
                val obj = Json.parseToJsonElement(String(reply, 1, reply.size - 1, Charsets.UTF_8)).jsonObject
                fun s(k: String) = obj[k]?.jsonPrimitive?.content ?: ""
                throw VtopError(s("type"), s("detail"), s("message"), s("debug"))
            }
            reply
        }

    override suspend fun text(method: String, vararg args: String): String {
        val r = raw(method, args, null)
        return String(r, 1, r.size - 1, Charsets.UTF_8)
    }

    override suspend fun bytes(method: String, vararg args: String): ByteArray {
        val r = raw(method, args, null)
        return r.copyOfRange(1, r.size)
    }

    override suspend fun upload(method: String, payload: ByteArray, vararg args: String): String {
        val r = raw(method, args, payload)
        return String(r, 1, r.size - 1, Charsets.UTF_8)
    }

    override fun close() = Native.freeClient(handle)
}
