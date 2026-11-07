package dev.pockethost

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat

/** Talks to Termux through its official RUN_COMMAND API. */
object Termux {
    const val PACKAGE = "com.termux"
    const val BOOT_PACKAGE = "com.termux.boot"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val CLI = "$PREFIX/bin/pockethost"

    const val DOWNLOAD_URL = "https://f-droid.org/packages/com.termux/"
    const val BOOT_DOWNLOAD_URL = "https://f-droid.org/packages/com.termux.boot/"

    /** The one line a user pastes into Termux so this app may send it commands. */
    const val ALLOW_COMMAND =
        "mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings"

    fun isInstalled(ctx: Context, pkg: String = PACKAGE): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Runs [script] with bash inside Termux.
     * [background] = false opens a visible Termux session so the user can watch progress.
     */
    fun run(ctx: Context, script: String, background: Boolean = true, label: String = "PocketHost"): Result<Unit> =
        runCatching {
            if (!isInstalled(ctx)) error("Termux is not installed")
            if (!hasPermission(ctx)) error("Allow PocketHost to run commands in Termux first")
            val intent = Intent().apply {
                setClassName(PACKAGE, "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
            Unit
        }

    /** Runs `pockethost <args>` in the background. */
    fun cli(ctx: Context, vararg args: String): Result<Unit> =
        run(ctx, (listOf(CLI) + args.map(::shellQuote)).joinToString(" "), true, "pockethost ${args.firstOrNull() ?: ""}")

    /** Opens a visible Termux session running `pockethost <args>`. */
    fun cliVisible(ctx: Context, vararg args: String): Result<Unit> {
        val cmd = (listOf(CLI) + args.map(::shellQuote)).joinToString(" ")
        return run(ctx, "$cmd; echo; read -n 1 -s -r -p 'Press any key to close'", false, "pockethost").onSuccess { open(ctx) }
    }

    /**
     * Builds an install command that carries the bundled scripts inside it,
     * so nothing has to be downloaded from GitHub.
     */
    fun installScript(ctx: Context, autostart: Boolean, skipN8n: Boolean): String {
        val files = listOf("install.sh", "pockethost.sh", "gateway.js")
        val sb = StringBuilder()
        sb.append("set -e\nD=\"\$HOME/.pockethost/src\"\nmkdir -p \"\$D\"\n")
        for (f in files) {
            val b64 = Base64.encodeToString(ctx.assets.open(f).use { it.readBytes() }, Base64.NO_WRAP)
            sb.append("printf '%s' '").append(b64).append("' | base64 -d > \"\$D/").append(f).append("\"\n")
        }
        sb.append("PH_AUTOSTART=").append(if (autostart) 1 else 0)
            .append(" SKIP_N8N=").append(if (skipN8n) 1 else 0)
            .append(" bash \"\$D/install.sh\" || echo 'Install failed - scroll up to see why.'\n")
        sb.append("echo; read -n 1 -s -r -p 'Press any key to close'\n")
        return sb.toString()
    }

    fun open(ctx: Context) {
        ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        }
    }

    fun openAppSettings(ctx: Context, pkg: String) {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) { }
    }

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
