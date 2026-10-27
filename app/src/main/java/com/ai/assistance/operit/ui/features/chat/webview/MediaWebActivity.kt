package com.ai.assistance.operit.ui.features.chat.webview
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * 媒体中心容器：小说阅读 / 影视中心 / 游戏中心
 * 支持后台播放：退到后台时 WebView 不暂停（继续出声），由 MediaPlaybackService 前台服务保活
 */
class MediaWebActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PAGE = "page"
        const val LOCAL_PORT = 8093
        private val PAGES = mapOf(
            "novel" to "novel.html",
            "video" to "video.html",
            "gamehall" to "gamehall.html"
        )
    }
    private lateinit var webView: WebView
    private var fgStarted = false
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE) ?: "video"
        val file = PAGES[page] ?: "video.html"
        val webDir = syncAssets()
        try {
            com.ai.assistance.operit.ui.features.chat.webview.LocalWebServer
                .getInstance(applicationContext, com.ai.assistance.operit.ui.features.chat.webview.LocalWebServer.ServerType.WORKSPACE)
                .start()
        } catch (e: Exception) {
            android.util.Log.w("MediaWebActivity", "local server start failed: " + e.message)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                    progress.progress = newProgress
                }
            }
        }
        root.addView(progress)
        root.addView(webView)
        setContentView(root)
        webView.loadUrl("http://127.0.0.1:$LOCAL_PORT/$file")
        title = when (page) { "novel" -> "小说中心"; "gamehall" -> "游戏中心"; else -> "影视中心" }
    }
    /** 后台播放：不调用 onPause 默认暂停逻辑 */
    override fun onPause() {
        // 保留 WebView 继续解码音频（视频会继续有声）
        try {
            val m = WebView::class.java.getMethod("onPause")
            // 不调用 super/webView.onPause —— 让媒体继续
        } catch (_: Exception) {}
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        try { webView.getClass().getMethod("onResume").invoke(webView) } catch (_: Exception) {}
    }
    /** 用户开始播放后由 JS 桥调用：启动前台服务保活 */
    fun startForegroundKeepAlive() {
        if (fgStarted) return
        fgStarted = true
        try {
            val svc = android.content.Intent(this, com.ai.assistance.operit.services.MediaPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (e: Exception) {
            android.util.Log.w("MediaWebActivity", "fg service failed: " + e.message)
        }
    }
    fun stopForegroundKeepAlive() {
        if (!fgStarted) return
        fgStarted = false
        try {
            stopService(android.content.Intent(this, com.ai.assistance.operit.services.MediaPlaybackService::class.java))
        } catch (_: Exception) {}
    }
    private fun syncAssets(): File {
        val targetDir = File(
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ), "Operit/workspace"
            ), ""
        )
        targetDir.mkdirs()
        val am = assets
        val names = am.list("web") ?: emptyArray()
        for (name in names) {
            val out = File(targetDir, name)
            am.open("web/$name").use { input ->
                val tmp = File(targetDir, "$name.tmp")
                FileOutputStream(tmp).use { input.copyTo(it) }
                if (!out.exists() || out.length() != tmp.length()) tmp.renameTo(out) else tmp.delete()
            }
        }
        return targetDir
    }
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        stopForegroundKeepAlive()
        webView.destroy()
        super.onDestroy()
    }
}
