package com.ai.assistance.operit.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.File
import java.util.zip.ZipFile

/**
 * Live2D 悬浮窗服务：透明 WebView 渲染 Q 版 Live2D 立绘，可拖动、可说话气泡
 * 启动：context.startForegroundService 不需要，普通 startService 即可（仅展示悬浮窗）
 */
class Live2DService : Service() {

    companion object {
        @Volatile var webView: WebView? = null
        @Volatile var isShowing = false
        // ===== 心情机 =====
        @Volatile var petMood: String = "normal"   // happy / normal / bored / sleepy
        @Volatile var lastSayTime: Long = 0L
        val moodLines = mapOf(
            "happy" to listOf("嘿嘿，今天真开心~", "和你在一起最好啦!", "元气满满!", "刚才聊得很开心呢", "嘿嘿，被夸了!"),
            "normal" to listOf("嗯~在待机中", "有什么想看的剧吗?", "要不要打开影视中心呀?", "我在呢~", "无聊的话可以看看小说哦"),
            "bored" to listOf("好无聊啊...理理我嘛", "你已经很久没和我说话了!", "我在这发呆好久了...", "要不要一起看个剧?"),
            "sleepy" to listOf("zzZ...", "困了...晚安", "呼...睡着了...")
        )
        fun updateMood() {
            val idle = (System.currentTimeMillis() - lastSayTime) / 60000L
            petMood = when {
                idle < 10 -> "happy"
                idle < 40 -> "normal"
                idle < 150 -> "bored"
                else -> "sleepy"
            }
        }
        fun randomLine(m: String): String {
            val l = moodLines[m] ?: moodLines["normal"]!!
            return l[kotlin.random.Random.nextInt(l.size)]
        }
        @Volatile var ttsEngine: android.speech.tts.TextToSpeech? = null
        fun ensureTts(ctx: android.content.Context) {
            if (ttsEngine != null) return
            ttsEngine = android.speech.tts.TextToSpeech(ctx.applicationContext) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    ttsEngine?.language = java.util.Locale.SIMPLIFIED_CHINESE
                }
            }
        }
        fun speak(text: String) {
            try {
                ttsEngine?.setSpeechRate(1.05f)
                ttsEngine?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "pet")
            } catch (_: Exception) {}
        }
        fun say(text: String) {
            lastSayTime = System.currentTimeMillis()
            if (petMood == "bored" || petMood == "sleepy") petMood = "happy"
            webView?.post {
                webView?.evaluateJavascript("say(" + android.text.TextUtils.htmlEncode(text).let { "'" + it.replace("'", "\\'") + "'" } + ");", null)
            }
            speak(text)
        }
    }

    private var windowManager: WindowManager? = null
    private var moodHandler: android.os.Handler? = null
    private var moodRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "show"
        when (action) {
            "hide" -> {
                moodHandler?.removeCallbacks(moodRunnable ?: Runnable {})
                hide(); stopSelf(); return START_NOT_STICKY
            }
            "say" -> { intent?.getStringExtra("text")?.let { say(it) }; return START_NOT_STICKY }
        }
        if (isShowing) return START_NOT_STICKY
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this, "需要悬浮窗权限才能显示Live2D桌宠，请在系统设置中授权", android.widget.Toast.LENGTH_LONG).show()
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + packageName))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {}
            return START_NOT_STICKY
        }
        ensureTts(this)
        // 心情循环：每 3 分钟检查，无聊/困了主动搭话（AG 主动关怀雏形）
        moodHandler?.removeCallbacks(moodRunnable ?: Runnable {})
        moodHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                try {
                    updateMood()
                    if (petMood == "bored" || petMood == "sleepy") {
                        say(randomLine(petMood))
                    }
                } catch (_: Exception) {}
                moodHandler?.postDelayed(this, 3 * 60 * 1000L)
            }
        }
        moodRunnable = runnable
        moodHandler?.postDelayed(runnable, 3 * 60 * 1000L)

        // 解压 web.zip 到 filesDir/live2d（首次）
        val dir = File(filesDir, "live2d")
        val index = File(dir, "index.html")
        if (!index.exists()) {
            try {
                dir.mkdirs()
                ZipFile(extractFromAssets()).use { z ->
                    val entries = z.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val out = File(dir, e.name)
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            z.getInputStream(e).use { input ->
                                out.outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Live2D", "unzip fail", e)
                return START_NOT_STICKY
            }
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wv.webChromeClient = WebChromeClient()
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.loadUrl("file://" + index.absolutePath)

        val dm = resources.displayMetrics
        val size = (Math.min(dm.widthPixels, dm.heightPixels) * 0.38f).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 0; y = 0
        }

        // 拖动
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        wv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y; moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                    params.x = startX - dx; params.y = startY - dy
                    windowManager?.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(wv, params)
            webView = wv
            isShowing = true
        } catch (e: Exception) {
            android.util.Log.e("Live2D", "addView fail", e)
        }
        return START_NOT_STICKY
    }

    private fun extractFromAssets(): File {
        // 从 assets 拷到 cache 再解压
        val f = File(cacheDir, "live2d_web.zip")
        if (!f.exists() || f.length() == 0L) {
            assets.open("live2d/web.zip").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        return f
    }

    private fun hide() {
        try {
            webView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        webView = null
        isShowing = false
    }

    override fun onDestroy() { hide() }
}
