package com.ai.assistance.operit.ui.features.chat.webview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Scroller
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 嗅探浏览器：用户在里面打开任意视频网站（腾讯/爱奇艺官网等），
 * shouldInterceptRequest 拦截 .m3u8/.mp4 请求，抓到真实播放地址。
 * 顶部地址栏 + 底部嗅探结果列表，点击复制，可一键送影视中心。
 */
class SniffActivity : AppCompatActivity() {

    companion object {
        // 最近一次嗅探到的地址（静态，供 open_media_center 之类读取）
        @Volatile
        var lastSniffedUrl: String? = null
        @Volatile
        var sniffHistory: MutableList<String> = mutableListOf()
    }

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var sniffBox: TextView
    private var sniffCount = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 顶栏：返回 + 地址栏 + GO
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        val btnBack = Button(this).apply { text = "◀" }
        urlBar = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            hint = "输入视频网址，如 v.qq.com"
            setText(intent.getStringExtra("url") ?: "https://v.qq.com")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            textSize = 13f
        }
        val btnGo = Button(this).apply { text = "GO" }
        top.addView(btnBack)
        top.addView(urlBar)
        top.addView(btnGo)
        root.addView(top)

        // WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.let { urlBar.setText(it.toString()) }
                    return false
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    // 嗅探规则：m3u8 / mp4 / 常见视频接口
                    if (u.contains(".m3u8") || u.contains(".mp4") ||
                        (u.contains(".flv")) || (u.contains("video") && u.contains(".ts")) ||
                        (u.contains("getvinfo") || u.contains("playurl"))) {
                        runOnUiThread { onSniffed(u) }
                    }
                    return null  // 不拦截，正常放行
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(urlBar.text.toString())
        }
        root.addView(webView)

        // 底部嗅探结果
        sniffBox = TextView(this).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            textSize = 11f
            text = "嗅探中…播放视频后这里会出现真实地址\n(点地址=复制，点[影视中心]=本地播放)"
            setOnClickListener {
                lastSniffedUrl?.let {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("sniff", it))
                    Toast.makeText(this@SniffActivity, "已复制视频地址", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnCenter = Button(this).apply {
            text = "影视中心"
            textSize = 12f
            setOnClickListener {
                startActivity(android.content.Intent(this@SniffActivity, MediaWebActivity::class.java).putExtra("page", "video"))
            }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(sniffBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(btnCenter)
        root.addView(bottom)

        setContentView(root)

        btnGo.setOnClickListener { go() }
        urlBar.setOnEditorActionListener { _, _, _ -> go(); true }
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        title = "嗅探浏览器"
    }

    private fun go() {
        var u = urlBar.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        urlBar.setText(u)
        webView.loadUrl(u)
    }

    private fun onSniffed(url: String) {
        if (sniffHistory.contains(url)) return
        sniffHistory.add(url)
        if (sniffHistory.size > 20) sniffHistory.removeAt(0)
        lastSniffedUrl = url
        sniffCount++
        val host = android.net.Uri.parse(url).host ?: "?"
        sniffBox.text = "[$sniffCount] $host\n${url.take(120)}${if (url.length>120)"…" else ""}"
        Toast.makeText(this, "嗅探到视频地址！($host)", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}