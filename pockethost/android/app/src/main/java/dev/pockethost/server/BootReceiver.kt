package dev.pockethost.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the server after a reboot (or app update) if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val cfg = AppConfig(ctx)
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        val updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if ((boot && cfg.autoStart) || (updated && cfg.wantRunning)) ServerService.start(ctx)
    }
}
