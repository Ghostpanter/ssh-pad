package com.sshtab.pad.ssh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object NodeStore {
    private const val PREF = "nodes"
    private const val KEY = "json"

    fun load(ctx: Context): List<HostProfile> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HostProfile(
                    name = o.optString("name"),
                    host = o.optString("host"),
                    port = o.optInt("port", 22),
                    username = o.optString("user"),
                    password = o.optString("pass"),
                    kind = runCatching { TransportKind.valueOf(o.optString("kind", "SSH")) }
                        .getOrDefault(TransportKind.SSH),
                    auth = runCatching { AuthMethod.valueOf(o.optString("auth", "PASSWORD")) }
                        .getOrDefault(AuthMethod.PASSWORD),
                    privateKey = o.optString("key"),
                    passphrase = o.optString("phrase"),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun upsert(ctx: Context, profile: HostProfile, saveSecret: Boolean) {
        val rest = load(ctx).filterNot {
            it.host == profile.host && it.port == profile.port && it.username == profile.username
        }
        val stored = if (saveSecret) profile else profile.copy(password = "", privateKey = "", passphrase = "")
        save(ctx, rest + stored)
    }

    fun delete(ctx: Context, profile: HostProfile) {
        save(
            ctx,
            load(ctx).filterNot {
                it.host == profile.host && it.port == profile.port && it.username == profile.username
            },
        )
    }

    private fun save(ctx: Context, list: List<HostProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("host", p.host)
                    .put("port", p.port)
                    .put("user", p.username)
                    .put("pass", p.password)
                    .put("kind", p.kind.name)
                    .put("auth", p.auth.name)
                    .put("key", p.privateKey)
                    .put("phrase", p.passphrase),
            )
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

data class ServerStats(
    val load: String = "—",
    val cpuPercent: Int = -1,
    val memUsedKb: Long = 0,
    val memTotalKb: Long = 0,
    val rxBps: Long = 0,
    val txBps: Long = 0,
) {
    fun memText(): String {
        if (memTotalKb <= 0) return "—"
        fun fmt(kb: Long): String {
            val mb = kb / 1024.0
            return if (mb >= 1024) String.format("%.1fG", mb / 1024) else String.format("%.0fM", mb)
        }
        return "${fmt(memUsedKb)}/${fmt(memTotalKb)}"
    }

    fun netText(): String {
        fun fmt(bps: Long): String {
            if (bps < 0) return "—"
            val kb = bps / 1024.0
            return if (kb >= 1024) String.format("%.1fMB/s", kb / 1024) else String.format("%.0fKB/s", kb)
        }
        return "↓${fmt(rxBps)} ↑${fmt(txBps)}"
    }
}
