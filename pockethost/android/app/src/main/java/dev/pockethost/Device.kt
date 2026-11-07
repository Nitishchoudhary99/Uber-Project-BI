package dev.pockethost

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

data class DeviceStats(
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val batteryTempC: Float? = null,
    val ramTotalMb: Long = 0,
    val ramAvailMb: Long = 0,
    val storageFreeGb: Double = 0.0,
) {
    val ramTotalGb: Double get() = ramTotalMb / 1024.0
    val hot: Boolean get() = (batteryTempC ?: 0f) >= 42f

    companion object {
        fun read(ctx: Context): DeviceStats {
            val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE

            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)

            val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            return DeviceStats(
                batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                charging = plugged != 0,
                batteryTempC = if (temp == Int.MIN_VALUE) null else temp / 10f,
                ramTotalMb = mi.totalMem / (1024 * 1024),
                ramAvailMb = mi.availMem / (1024 * 1024),
                storageFreeGb = (stat?.availableBytes ?: 0L) / 1e9,
            )
        }
    }
}

data class CatalogModel(
    val name: String,
    val title: String,
    val sizeGb: Double,
    val minRamGb: Int,
    val blurb: String,
)

/** Hand-picked models that run well on phones (CPU, q4 quantized). */
val MODEL_CATALOG = listOf(
    CatalogModel("gemma3:1b", "Gemma 3 1B", 0.8, 3, "Fastest all-rounder. Great first model."),
    CatalogModel("qwen2.5:0.5b", "Qwen 2.5 0.5B", 0.4, 2, "Tiny and instant. Good for classification in n8n."),
    CatalogModel("qwen2.5:1.5b", "Qwen 2.5 1.5B", 1.0, 4, "Strong multilingual chat incl. Hindi."),
    CatalogModel("llama3.2:1b", "Llama 3.2 1B", 1.3, 4, "Meta's small model, solid tool use."),
    CatalogModel("qwen3:1.7b", "Qwen 3 1.7B", 1.4, 4, "Reasoning model with thinking mode."),
    CatalogModel("qwen2.5-coder:1.5b", "Qwen 2.5 Coder 1.5B", 1.0, 4, "Code generation and explanation."),
    CatalogModel("llama3.2:3b", "Llama 3.2 3B", 2.0, 6, "Best quality for 6-8 GB phones."),
    CatalogModel("qwen2.5:3b", "Qwen 2.5 3B", 1.9, 6, "Great JSON output for automations."),
    CatalogModel("qwen3:4b", "Qwen 3 4B", 2.6, 8, "Smartest that still feels quick on 8 GB+."),
    CatalogModel("gemma3:4b", "Gemma 3 4B", 3.3, 8, "Understands images too (vision)."),
    CatalogModel("phi4-mini", "Phi-4 Mini 3.8B", 2.5, 8, "Microsoft's reasoning-focused small model."),
    CatalogModel("nomic-embed-text", "Nomic Embed", 0.3, 2, "Embeddings for RAG / vector search in n8n."),
)
