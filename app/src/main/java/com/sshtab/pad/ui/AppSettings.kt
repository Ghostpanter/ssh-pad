package com.sshtab.pad.ui

import android.content.Context
import com.sshtab.pad.SshPadApp
import java.io.File
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppSettings {
    private const val FILE = "settings.props"

    private val _theme = MutableStateFlow("dark")
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _fontSize = MutableStateFlow(15)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _extraKeys = MutableStateFlow(true)
    val extraKeys: StateFlow<Boolean> = _extraKeys.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _autoReconnect = MutableStateFlow(true)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _statusSec = MutableStateFlow(5)
    val statusSec: StateFlow<Int> = _statusSec.asStateFlow()

    private val _savePassword = MutableStateFlow(true)
    val savePassword: StateFlow<Boolean> = _savePassword.asStateFlow()

    private val _leftWidth = MutableStateFlow(280)
    val leftWidth: StateFlow<Int> = _leftWidth.asStateFlow()

    private val _onboarded = MutableStateFlow(false)
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun init(ctx: Context) {
        val p = read(ctx)
        _theme.value = p.getProperty("theme", "dark")
        _fontSize.value = p.getProperty("fontSize", "15").toIntOrNull() ?: 15
        _extraKeys.value = p.getProperty("extraKeys", "1") != "0"
        _keepScreenOn.value = p.getProperty("keepScreenOn", "1") != "0"
        _autoReconnect.value = p.getProperty("autoReconnect", "1") != "0"
        _statusSec.value = p.getProperty("statusSec", "5").toIntOrNull() ?: 5
        _savePassword.value = p.getProperty("savePassword", "1") != "0"
        _leftWidth.value = p.getProperty("leftWidth", "280").toIntOrNull() ?: 280
        _onboarded.value = p.getProperty("onboarded", "0") == "1"
    }

    fun autoReconnectNow(): Boolean = try {
        read(SshPadApp.instance).getProperty("autoReconnect", "1") != "0"
    } catch (_: Throwable) { true }

    fun statusSecNow(): Int = try {
        read(SshPadApp.instance).getProperty("statusSec", "5").toIntOrNull() ?: 5
    } catch (_: Throwable) { 5 }

    fun setTheme(v: String) { _theme.value = v; persist() }
    fun setFontSize(v: Int) { _fontSize.value = v.coerceIn(12, 22); persist() }
    fun setExtraKeys(v: Boolean) { _extraKeys.value = v; persist() }
    fun setKeepScreenOn(v: Boolean) { _keepScreenOn.value = v; persist() }
    fun setAutoReconnect(v: Boolean) { _autoReconnect.value = v; persist() }
    fun setStatusSec(v: Int) { _statusSec.value = v.coerceIn(3, 30); persist() }
    fun setSavePassword(v: Boolean) { _savePassword.value = v; persist() }
    fun setLeftWidth(v: Int) { _leftWidth.value = v.coerceIn(220, 480); persist() }
    fun setOnboarded(v: Boolean) { _onboarded.value = v; persist() }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun read(ctx: Context): Properties {
        val p = Properties()
        try {
            val f = file(ctx)
            if (f.exists()) f.inputStream().use { p.load(it) }
        } catch (_: Throwable) {
        }
        return p
    }

    private fun persist() {
        val ctx = try { SshPadApp.instance } catch (_: Throwable) { return }
        val p = Properties()
        p["theme"] = _theme.value
        p["fontSize"] = _fontSize.value.toString()
        p["extraKeys"] = if (_extraKeys.value) "1" else "0"
        p["keepScreenOn"] = if (_keepScreenOn.value) "1" else "0"
        p["autoReconnect"] = if (_autoReconnect.value) "1" else "0"
        p["statusSec"] = _statusSec.value.toString()
        p["savePassword"] = if (_savePassword.value) "1" else "0"
        p["leftWidth"] = _leftWidth.value.toString()
        p["onboarded"] = if (_onboarded.value) "1" else "0"
        try { file(ctx).outputStream().use { p.store(it, "ssh-pad") } } catch (_: Throwable) {}
    }
}
