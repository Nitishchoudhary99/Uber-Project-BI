package dev.pockethost.server

import android.content.Context
import java.security.SecureRandom

enum class TunnelMode { QUICK, TOKEN, NONE }

/** All settings live in app-private storage. */
class AppConfig(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("pockethost", Context.MODE_PRIVATE)

    val apiKey: String
        get() = p.getString(KEY_API, null) ?: rotateApiKey()

    fun rotateApiKey(): String = newKey().also { p.edit().putString(KEY_API, it).apply() }

    var tunnelMode: TunnelMode
        get() = runCatching { TunnelMode.valueOf(p.getString("tunnel_mode", null) ?: "QUICK") }.getOrDefault(TunnelMode.QUICK)
        set(v) = p.edit().putString("tunnel_mode", v.name).apply()

    var tunnelToken: String
        get() = p.getString("tunnel_token", "").orEmpty()
        set(v) = p.edit().putString("tunnel_token", v).apply()

    /** The hostname configured in Cloudflare for a token tunnel, e.g. https://ai.example.com */
    var publicUrl: String
        get() = p.getString("public_url", "").orEmpty()
        set(v) = p.edit().putString("public_url", v.trim().trimEnd('/')).apply()

    var autoStart: Boolean
        get() = p.getBoolean("auto_start", false)
        set(v) = p.edit().putBoolean("auto_start", v).apply()

    var setupDone: Boolean
        get() = p.getBoolean("setup_done", false)
        set(v) = p.edit().putBoolean("setup_done", v).apply()

    /** How long a model stays in RAM after the last request. */
    var keepAlive: String
        get() = p.getString("keep_alive", "5m").orEmpty()
        set(v) = p.edit().putString("keep_alive", v).apply()

    /** Set when the user starts the server, cleared when they stop it; used to resume after a crash or reboot. */
    var wantRunning: Boolean
        get() = p.getBoolean("want_running", false)
        set(v) = p.edit().putBoolean("want_running", v).apply()

    private fun newKey(): String {
        val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return "ph_" + b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_API = "api_key"
    }
}
