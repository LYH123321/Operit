package com.ai.assistance.operit.core.tools.system

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.core.tools.system.shell.ShellProcess
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences

/** 向后兼容的Shell命令执行工具类 通过权限级别委托到相应的Shell执行器 */
class AndroidShellExecutor {
    companion object {
        private const val TAG = "AndroidShellExecutor"
        private var context: Context? = null
        private val preferredPermissionLevelCacheLock = Any()
        @Volatile private var hasCachedPreferredPermissionLevel = false
        @Volatile private var cachedPreferredPermissionLevel: AndroidPermissionLevel? = null

        /**
         * 设置全局上下文引用
         * @param appContext 应用上下文
         */
        fun setContext(appContext: Context) {
            context = appContext.applicationContext
        }

        fun clearPreferredPermissionLevelCache() {
            synchronized(preferredPermissionLevelCacheLock) {
                cachedPreferredPermissionLevel = null
                hasCachedPreferredPermissionLevel = false
            }
        }

        private fun getPreferredPermissionLevelCached(): AndroidPermissionLevel? {
            if (hasCachedPreferredPermissionLevel) {
                return cachedPreferredPermissionLevel
            }

            synchronized(preferredPermissionLevelCacheLock) {
                if (!hasCachedPreferredPermissionLevel) {
                    cachedPreferredPermissionLevel =
                            androidPermissionPreferences.getPreferredPermissionLevel()
                    hasCachedPreferredPermissionLevel = true
                }
                return cachedPreferredPermissionLevel
            }
        }

        private fun getPermissionLevelLabel(level: AndroidPermissionLevel): String {
            return when (level) {
                AndroidPermissionLevel.STANDARD -> "STANDARD"
                AndroidPermissionLevel.ACCESSIBILITY -> "ACCESSIBILITY"
                AndroidPermissionLevel.DEBUGGER -> "DEBUGGER"
                AndroidPermissionLevel.ADMIN -> "ADMIN"
                AndroidPermissionLevel.ROOT -> "ROOT"
            }
        }

        private fun buildStrictUnavailableReason(
            level: AndroidPermissionLevel,
            executorAvailable: Boolean,
            permStatus: ShellExecutor.PermissionStatus
        ): String {
            val reasons = mutableListOf<String>()

            if (!executorAvailable) {
                reasons += "executor unavailable"
            }
            if (!permStatus.granted) {
                reasons += permStatus.reason.trim().ifEmpty { "permission not granted" }
            }

            val reasonText = reasons.distinct().joinToString("; ").ifBlank { "unknown reason" }
            return "Current ${getPermissionLevelLabel(level)} unavailable: $reasonText"
        }

        /**
         * 封装执行命令的函数
         * @param command 要执行的命令
         * @return 命令执行结果
         */
        suspend fun executeShellCommand(command: String): CommandResult {
            return executeShellCommand(command, null)
        }

        suspend fun executeShellCommand(command: String, identityOverride: ShellIdentity?): CommandResult {
            val ctx = context ?: return CommandResult(false, "", "Context not initialized")

            // 如果调用方显式指定了身份，就直接向下传递；否则使用默认身份
            val identity = identityOverride ?: ShellIdentity.DEFAULT

            val preferredLevel = getPreferredPermissionLevelCached()
            val actualLevel = preferredLevel ?: AndroidPermissionLevel.STANDARD

            val preferredExecutor = ShellExecutorFactory.getExecutor(ctx, actualLevel)
            val permStatus = preferredExecutor.hasPermission()
            val executorAvailable = preferredExecutor.isAvailable()

            if (executorAvailable && permStatus.granted) {
                val result = preferredExecutor.executeCommand(command, identity)
                return CommandResult(result.success, result.stdout, result.stderr, result.exitCode)
            }

            val reason = buildStrictUnavailableReason(actualLevel, executorAvailable, permStatus)

            // MOD: fallback chain - degrade to any available executor instead of hard failing
            val fallbackOrder = listOf(AndroidPermissionLevel.ROOT, AndroidPermissionLevel.DEBUGGER, AndroidPermissionLevel.ACCESSIBILITY, AndroidPermissionLevel.ADMIN, AndroidPermissionLevel.STANDARD)
            for (level in fallbackOrder) {
                if (level == actualLevel) continue
                try {
                    val fe = ShellExecutorFactory.getExecutor(ctx, level)
                    if (fe.isAvailable() && fe.hasPermission().granted) {
                        val fr = fe.executeCommand(command, identity)
                        AppLogger.d(TAG, "Fallback executor used: ${'$'}{getPermissionLevelLabel(level)}")
                        return CommandResult(fr.success, fr.stdout, fr.stderr, fr.exitCode)
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Fallback ${'$'}{getPermissionLevelLabel(level)} failed: ${'$'}{e.message}")
                }
            }

            AppLogger.d(TAG, "Strict permission mode enabled. $reason")
            return CommandResult(false, "", reason, -1)
        }

        suspend fun startShellProcess(command: String): ShellProcess {
            val ctx = context ?: throw IllegalStateException("Context not initialized")

            val preferredLevel = getPreferredPermissionLevelCached()
            val actualLevel = preferredLevel ?: AndroidPermissionLevel.STANDARD
            val preferredExecutor = ShellExecutorFactory.getExecutor(ctx, actualLevel)
            val permStatus = preferredExecutor.hasPermission()
            val executorAvailable = preferredExecutor.isAvailable()

            if (executorAvailable && permStatus.granted) {
                return preferredExecutor.startProcess(command)
            }

            val reason = buildStrictUnavailableReason(actualLevel, executorAvailable, permStatus)

            AppLogger.d(TAG, "Strict permission mode enabled. $reason")
            throw SecurityException(reason)
        }
    }

    /** 命令执行结果数据类 */
    data class CommandResult(
            val success: Boolean,
            val stdout: String,
            val stderr: String = "",
            val exitCode: Int = -1
    )
}

enum class ShellIdentity {
    DEFAULT,
    APP,
    ROOT,
    SHELL
}
