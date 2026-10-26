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
 * 打开时把 assets/web 同步到 workspace 目录，然后经 LocalWebServer(8093) 加载
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = intent.getStringExtra(EXTRA_PAGE) ?: "video"
        val file = PAGES[page] ?: "video.html"

        // 1. 同步 assets/web -> workspace/web
        val webDir = syncAssets()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    // m3u8/ts 分片直连（不缓存），其余放行
                    return null
                }
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

        // 2. 经本地服务器加载（享受 /api/proxy CORS 桥）
        webView.loadUrl("http://127.0.0.1:$LOCAL_PORT/$file")
        title = when (page) { "novel" -> "小说中心"; "gamehall" -> "游戏中心"; else -> "影视中心" }
    }

    /** 把 assets/web 拷到 workspace（按大小+时间增量更新） */
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
                // 增量：仅当大小不同才覆盖（gamehall 2.3MB 每次全拷太慢）
                if (!out.exists() || out.length() != tmp.length()) {
                    tmp.renameTo(out)
                } else {
                    tmp.delete()
                }
            }
        }
        return targetDir
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
