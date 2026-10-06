package my.kang.carvideo

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback

/**
 * Phone side. This is where you sign in.
 *
 * Both WebViews live in the same app process and therefore share one
 * CookieManager, so a Google session established here is already present in the
 * car WebView - no typing on the car screen, no keyboard gymnastics.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fullscreenHost: FrameLayout
    private var chromeClient: FullscreenWebChromeClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        webView = findViewById(R.id.web)
        fullscreenHost = findViewById(R.id.web_host)

        WebKit.configure(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = WebKit.shouldBlockNavigation(request)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                WebKit.flushCookies()
                refreshStatus()
            }
        }
        chromeClient = FullscreenWebChromeClient(
            host = fullscreenHost,
            webView = { webView }
        ).also { webView.webChromeClient = it }

        findViewById<Button>(R.id.btn_home).setOnClickListener {
            webView.loadUrl(Config.START_URL)
        }
        findViewById<Button>(R.id.btn_signin).setOnClickListener {
            webView.loadUrl(SIGN_IN_URL)
        }
        findViewById<Button>(R.id.btn_signout).setOnClickListener { confirmSignOut() }

        if (savedInstanceState == null) webView.loadUrl(Config.START_URL)
        // Works on every API level, including predictive back on 33+.
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        // Make sure the car side sees the session even if this process is killed.
        WebKit.flushCookies()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    private fun handleBack() {
        when {
            chromeClient?.exit() == true -> Unit
            webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    private fun refreshStatus() {
        val signedIn = WebKit.isSignedIn()
        status.setText(if (signedIn) R.string.status_signed_in else R.string.status_signed_out)
        status.setTextColor(if (signedIn) COLOR_OK else COLOR_WARN)
        findViewById<Button>(R.id.btn_signout).isEnabled = signedIn
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sign_out)
            .setMessage(R.string.sign_out_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sign_out) { _, _ ->
                WebKit.signOut()
                webView.loadUrl(Config.START_URL)
                refreshStatus()
                Toast.makeText(this, R.string.signed_out, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private companion object {
        const val SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://m.youtube.com/"
        const val COLOR_OK = 0xFF1B7F3B.toInt()
        const val COLOR_WARN = 0xFFB3261E.toInt()
    }
}
