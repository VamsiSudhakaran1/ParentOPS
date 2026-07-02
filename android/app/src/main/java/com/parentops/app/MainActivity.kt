package com.parentops.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * ParentOps Android shell: renders the household's ParentOps server in a
 * full-screen WebView. The server address is asked once on first launch and
 * changeable from the menu. Login (PIN) cookies persist across restarts.
 *
 * Google account linking is intentionally opened in the phone's browser:
 * Google blocks OAuth inside WebViews ("disallowed_useragent").
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout

    private val prefs by lazy { getSharedPreferences("parentops", MODE_PRIVATE) }
    private var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v.trimEnd('/')).apply()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        swipe = SwipeRefreshLayout(this)
        webView = WebView(this)
        swipe.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(swipe)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val home = Uri.parse(baseUrl)
                // OAuth linking and any external site open in the real browser.
                val isLink = url.path?.startsWith("/link") == true
                val sameHost = url.host == home.host && url.port == home.port
                if (!sameHost || isLink) {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipe.isRefreshing = false
                CookieManager.getInstance().flush()
            }
        }

        swipe.setOnRefreshListener { webView.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (baseUrl.isEmpty()) askForServer(first = true) else webView.loadUrl(baseUrl)
    }

    private fun askForServer(first: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "http://192.168.1.5:8000"
            setText(baseUrl)
        }
        AlertDialog.Builder(this)
            .setTitle(if (first) "Welcome to ParentOps" else "Change server")
            .setMessage(
                "Enter your ParentOps server address.\n\n" +
                "Same Wi-Fi: your computer's IP shown when run.bat starts " +
                "(e.g. http://192.168.1.5:8000). Deployed: your https:// address."
            )
            .setView(input)
            .setCancelable(!first)
            .setPositiveButton("Save") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http")) url = "http://$url"
                    baseUrl = url
                    webView.loadUrl(baseUrl)
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Refresh")
        menu.add(0, 2, 1, "Change server")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> webView.reload()
            2 -> askForServer(first = false)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }
}
