package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import java.io.File

/**
 * 增强版「关于手机」工具：整机硬件/系统/传感器/电池 全量信息
 */
class PhoneInfoToolExecutor(private val context: Context) : ToolExecutor {

    override fun invoke(tool: AITool): ToolResult {
        return try {
            val sb = StringBuilder()

            sb.appendLine("== 关于本机 ==")
            prop("ro.product.marketname")?.let { sb.appendLine("机型: $it") }
            sb.appendLine("品牌: ${Build.BRAND}  厂商: ${Build.MANUFACTURER}")
            sb.appendLine("型号: ${Build.MODEL}  设备代号: ${Build.DEVICE}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            prop("ro.build.version.security_patch")?.let { sb.appendLine("安全补丁: $it") }
            prop("ro.build.display.id")?.let { sb.appendLine("系统版本: $it") }
            prop("ro.build.date.utc")?.let { 
                try { sb.appendLine("系统构建时间: ${java.util.Date(it.toLong()*1000)}") } catch (_: Exception) {}
            }
            sb.appendLine("内核: ${System.getProperty("os.version")}  架构: ${Build.SUPPORTED_ABIS.joinToString(",")}")

            sb.appendLine().appendLine("== SoC / CPU ==")
            prop("ro.board.platform")?.let { sb.appendLine("平台: $it") }
            prop("ro.soc.manufacturer")?.let { sb.appendLine("SoC厂商: $it") }
            prop("ro.soc.model")?.let { sb.appendLine("SoC型号: $it") }
            sb.appendLine("CPU核心数: ${Runtime.getRuntime().availableProcessors()}")
            // 每核实时频率
            try {
                val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
                    val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLongOrNull()
                    f?.let { "${i}:" + String.format("%dMHz", it / 1000) }
                }
                if (freqs.isNotEmpty()) sb.appendLine("实时频率: " + freqs.joinToString(" "))
                val maxF = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
                maxF?.let { sb.appendLine("最大频率: ${it / 1000}MHz") }
            } catch (_: Exception) {}
            // CPU 型号（ARM implementer 解析 xiaomi/nova 风格）
            try {
                File("/proc/cpuinfo").readText().lineSequence()
                    .firstOrNull { it.startsWith("Hardware", true) }
                    ?.let { sb.appendLine("CPU硬件: " + it.substringAfter(":").trim()) }
            } catch (_: Exception) {}

            sb.appendLine().appendLine("== GPU / 屏幕 ==")
            try {
                val dm = context.resources.displayMetrics
                sb.appendLine("分辨率: ${dm.widthPixels}x${dm.heightPixels}  密度: ${dm.densityDpi}dpi (${String.format("%.2f", dm.density)}x)")
                @Suppress("DEPRECATION")
                val refresh = context.display?.refreshRate ?: 60f
                sb.appendLine("刷新率: ${String.format("%.1f", refresh)}Hz")
                val real = context.display
                real?.let { sb.appendLine("物理尺寸: ${String.format("%.1f", it.width / dm.xdpi * 0.03937)} x ${String.format("%.1f", it.height / dm.ydpi * 0.03937)} 英寸") }
            } catch (_: Exception) {}

            sb.appendLine().appendLine("== 内存 / 存储 ==")
            try {
                val mi = android.app.ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
                sb.appendLine("内存: ${fmt(mi.totalMem)} 总计 / ${fmt(mi.availMem)} 可用")
                // ZRAM/swap
                val sw = File("/proc/meminfo").readText()
                sw.lineSequence().firstOrNull { it.startsWith("SwapTotal") }?.let {
                    val kb = it.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                    if (kb > 0) sb.appendLine("Swap/内存拓展: ${fmt(kb * 1024)}")
                }
                sb.appendLine("低内存阈值: ${fmt(mi.threshold)}")
            } catch (_: Exception) {}
            try {
                val st = android.os.StatFs(Environment2.getExternal())
                sb.appendLine("内置存储: ${fmt(st.totalBytes)} 总计 / ${fmt(st.availableBytes)} 可用")
            } catch (_: Exception) {}

            sb.appendLine().appendLine("== 电池 ==")
            try {
                val bi = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                if (bi != null) {
                    val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = level * 100 / scale
                    val status = when (bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                        else -> "未知"
                    }
                    val plugged = when (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
                        else -> "电池"
                    }
                    val volt = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                    val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    val health = when (bi.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                        BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
                        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
                        BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
                        else -> "未知"
                    }
                    val tech = bi.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
                    sb.appendLine("电量: ${pct}% ($status, $plugged)")
                    if (volt > 0) sb.appendLine("电压: ${volt / 1000.0}V")
                    if (temp > 0) sb.appendLine("温度: ${temp / 10.0}°C")
                    sb.appendLine("健康: $health  技术: $tech")
                    // 循环次数（部分厂商）
                    bi.getIntExtra("cycle_count", -1).takeIf { it > 0 }?.let { sb.appendLine("循环次数: $it") }
                }
            } catch (_: Exception) {}

            sb.appendLine().appendLine("== 传感器 (${countSensors()}) ==")
            try {
                val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val counts = sm.getSensorList(Sensor.TYPE_ALL).groupingBy { it.type }.eachCount()
                val names = mapOf(
                    Sensor.TYPE_ACCELEROMETER to "加速度", Sensor.TYPE_GYROSCOPE to "陀螺仪",
                    Sensor.TYPE_MAGNETIC_FIELD to "磁力", Sensor.TYPE_LIGHT to "光线",
                    Sensor.TYPE_PROXIMITY to "距离", Sensor.TYPE_PRESSURE to "气压",
                    Sensor.TYPE_STEP_COUNTER to "计步", Sensor.TYPE_HEART_RATE to "心率",
                    Sensor.TYPE_AMBIENT_TEMPERATURE to "环境温度", Sensor.TYPE_RELATIVE_HUMIDITY to "湿度",
                    Sensor.TYPE_GRAVITY to "重力", Sensor.TYPE_LINEAR_ACCELERATION to "线性加速度",
                    Sensor.TYPE_ROTATION_VECTOR to "旋转矢量", Sensor.TYPE_GAME_ROTATION_VECTOR to "游戏旋转"
                )
                names.forEach { (t, n) -> counts[t]?.let { sb.appendLine("$n x$it") } }
            } catch (_: Exception) {}

            sb.appendLine().appendLine("== 网络 ==")
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                sb.appendLine("类型: " + when {
                    caps == null -> "无连接"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                    else -> "其他"
                })
                caps?.linkDownstreamBandwidthKbps?.takeIf { it in 1..999999 }?.let {
                    sb.appendLine("下行带宽估计: ${it / 1000}Mbps")
                }
            } catch (_: Exception) {}
            try {
                val wifi = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val info = wifi.connectionInfo
                if (info != null && info.networkId != -1) {
                    sb.appendLine("SSID: ${info.ssid}  信号: ${info.rssi}dBm  频段: ${if (info.frequency > 5000) "5GHz" else "2.4GHz"}")
                }
            } catch (_: Exception) {}

            sb.appendLine().appendLine("== 运行状态 ==")
            val up = SystemClock.elapsedRealtime()
            sb.appendLine("开机时长: ${up / 3600000}小时${up % 3600000 / 60000}分")
            val now = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            sb.appendLine("上次开机: ${java.util.Date(now)}")
            sb.appendLine("时区: ${java.util.TimeZone.getDefault().id}")

            ToolResult(toolName = tool.name, success = true, result = StringResultData(sb.toString()))
        } catch (e: Exception) {
            ToolResult(toolName = tool.name, success = false, result = StringResultData(""),
                error = "获取手机信息失败: ${e.message}")
        }
    }

    private fun prop(key: String): String? = try {
        val p = ProcessBuilder("getprop", key).start()
        val r = p.inputStream.bufferedReader().readLine()?.trim()
        p.waitFor()
        r?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private fun countSensors(): Int = try {
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getSensorList(Sensor.TYPE_ALL).size
    } catch (_: Exception) { 0 }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        b < 1024L * 1024 * 1024 -> String.format("%.1fMB", b / 1048576.0)
        else -> String.format("%.1fGB", b / 1073741824.0)
    }

    // 存储路径小工具，避免直接依赖 Environment.getExternalStorageDirectory 弃用警告
    private object Environment2 {
        fun getExternal(): String = android.os.Environment.getExternalStorageDirectory().path
    }
}
