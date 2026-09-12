package com.sshtab.pad.log

import android.util.Log
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory + on-disk session transcript. Always records, even when the
 * terminal WebView is destroyed in the background.
 */
object SessionLog {
    private const val TAG = "SessionLog"
    private const val MAX_CHARS = 400_000
    private val lock = Any()
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileSdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private val buf = StringBuilder()
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    @Volatile private var file: File? = null
    @Volatile var lastDisconnectReason: String = ""
        private set

    fun init(dir: File) {
        try {
            dir.mkdirs()
            val f = File(dir, "session-${fileSdf.format(Date())}.log")
            if (!f.exists()) f.writeText("")
            file = f
            event("log file ${f.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "init", t)
        }
    }

    fun event(msg: String) {
        append("*** ${ts()} $msg\n")
    }

    fun disconnect(reason: String) {
        lastDisconnectReason = reason
        event("DISCONNECT $reason")
    }

    fun incoming(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        append(String(bytes, StandardCharsets.UTF_8))
    }

    fun outgoing(text: String) {
        // raw keystrokes are already echoed by PTY; mark locally for debug
        if (text.isEmpty()) return
        append("") // keep transcript server-authored; events cover control
    }

    fun snapshot(): String = synchronized(lock) { buf.toString() }

    fun saveTo(out: OutputStream) {
        out.write(snapshot().toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    fun clear() {
        synchronized(lock) {
            buf.setLength(0)
            _text.value = ""
        }
        event("log cleared")
    }

    fun currentFile(): File? = file

    private fun ts(): String = sdf.format(Date())

    private fun append(chunk: String) {
        if (chunk.isEmpty()) return
        val shown: String
        synchronized(lock) {
            buf.append(chunk)
            if (buf.length > MAX_CHARS) {
                buf.delete(0, buf.length - MAX_CHARS + MAX_CHARS / 4)
            }
            shown = buf.toString()
        }
        _text.value = shown
        try {
            file?.appendText(chunk, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "append file", t)
        }
    }
}
