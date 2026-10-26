package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.runBlocking

/**
 * MOD v2: AG awareness tools - clipboard / app usage stats / notifications
 */
open class AwarenessTools(private val context: Context) {

    companion object {
        private const val TAG = "AwarenessTools"
    }

    // ---------- Clipboard ----------
    class ReadClipboardExecutor(private val context: Context) : ToolExecutor {
        override fun invoke(tool: AITool): ToolResult {
            return try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                var text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                if (text.isBlank()) {
                    // fallback: try via shell (works with Shizuku/root)
                    try {
                        val r = runBlocking {
                            com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
                                .executeShellCommand("dumpsys clipboard | grep -m1 text=")
                        }
                        if (r.success) text = r.stdout.trim()
                    } catch (e: Exception) { AppLogger.w(TAG, "clipboard shell fallback failed: ${e.message}") }
                }
                if (text.isBlank()) {
                    ToolResult(tool.name, false, StringResultData(""),
                        "Clipboard is empty or no focus permission (Android 10+ restricts background clipboard)")
                } else {
                    ToolResult(tool.name, true, StringResultData(text.take(5000)))
                }
            } catch (e: Exception) {
                ToolResult(tool.name, false, StringResultData(""), "Read clipboard failed: ${e.message}")
            }
        }
    }

    class WriteClipboardExecutor(private val context: Context) : ToolExecutor {
        override fun invoke(tool: AITool): ToolResult {
            val text = tool.parameters.find { it.name == "text" }?.value ?: ""
            if (text.isBlank()) {
                return ToolResult(tool.name, false, StringResultData(""), "Missing required param: text")
            }
            return try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("operit", text))
                ToolResult(tool.name, true, StringResultData("Clipboard set (${text.length} chars)"))
            } catch (e: Exception) {
                ToolResult(tool.name, false, StringResultData(""), "Write clipboard failed: ${e.message}")
            }
        }
    }

    // ---------- App Usage Stats ----------
    class AppUsageExecutor(private val context: Context) : ToolExecutor {
        override fun invoke(tool: AITool): ToolResult {
            return try {
                if (!hasUsagePermission()) {
                    return ToolResult(tool.name, false, StringResultData(""),
                        "No PACKAGE_USAGE_STATS permission. Guide user: Settings - Privacy - Special app access - Usage access, grant to Operit Mod.")
                }
                val hours = (tool.parameters.find { it.name == "hours" }?.value ?: "24").toIntOrNull() ?: 24
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val stats = usm.queryAndAggregateUsageStats(now - hours * 3600_000L, now)
                val sb = StringBuilder("App usage last ${hours}h (top 20):\n")
                stats.values.sortedByDescending { it.totalTimeInForeground }
                    .take(20).forEach { s ->
                        val pkg = try {
                            context.packageManager.getApplicationLabel(
                                context.packageManager.getApplicationInfo(s.packageName, 0)).toString()
                        } catch (e: Exception) { s.packageName }
                        val mins = s.totalTimeInForeground / 60000
                        if (mins > 0) sb.append("$pkg ($s.packageName): ${mins} min, last used: ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(s.lastTimeUsed))}\n")
                    }
                ToolResult(tool.name, true, StringResultData(sb.toString()))
            } catch (e: Exception) {
                ToolResult(tool.name, false, StringResultData(""), "Query usage failed: ${e.message}")
            }
        }

        private fun hasUsagePermission(): Boolean {
            return try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(context.packageName, 0)
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, info.uid, context.packageName)
                mode == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) { false }
        }
    }

    // ---------- Notifications (via listener snapshot) ----------
    class ReadNotificationsExecutor : ToolExecutor {
        override fun invoke(tool: AITool): ToolResult {
            val snap = AwareNotificationListener.snapshot()
            return if (snap.isEmpty()) {
                ToolResult(tool.name, false, StringResultData(""),
                    if (AwareNotificationListener.isConnected())
                        "No active notifications"
                    else
                        "Notification listener not connected. Guide user: Settings - Notification access, enable Operit Mod.")
            } else {
                ToolResult(tool.name, true, StringResultData(snap.joinToString("\n").take(8000)))
            }
        }
    }
}
