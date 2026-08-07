package com.copperbeech.pulsetones

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var service: ToneService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ToneService.LocalBinder).service
            bound = true
            service?.onStopped = {
                runOnUiThread {
                    webView.evaluateJavascript("window.__pulseNativeStopped && window.__pulseNativeStopped();", null)
                }
            }
            syncUiToService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen awake while the app is open so the visual pulse stays visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            setBackgroundColor(0xFF0D1024.toInt())
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            addJavascriptInterface(NativeBridge(), "PulseNative")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)

        // Ask for notification permission (Android 13+) so the playback
        // notification is visible. Playback works either way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ToneService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) {
            service?.onStopped = null
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** Push current playback state into the WebView (e.g. after returning to a playing session). */
    private fun syncUiToService() {
        val playing = service?.isPlaying() ?: false
        webView.evaluateJavascript("window.__pulseSyncState && window.__pulseSyncState($playing);", null)
    }

    /** Methods exposed to the page as window.PulseNative.* */
    inner class NativeBridge {

        @JavascriptInterface
        fun startTone(pitch: Double, rate: Double, volume: Double) {
            val intent = Intent(this@MainActivity, ToneService::class.java)
                .setAction(ToneService.ACTION_START)
                .putExtra(ToneService.EXTRA_PITCH, pitch.toFloat())
                .putExtra(ToneService.EXTRA_RATE, rate.toFloat())
                .putExtra(ToneService.EXTRA_VOLUME, volume.toFloat())
            ContextCompat.startForegroundService(this@MainActivity, intent)
        }

        @JavascriptInterface
        fun stopTone() {
            service?.stopTone()
        }

        @JavascriptInterface
        fun setPitch(pitch: Double) { service?.pitchHz = pitch.toFloat() }

        @JavascriptInterface
        fun setRate(rate: Double) { service?.rateHz = rate.toFloat() }

        @JavascriptInterface
        fun setVolume(volume: Double) { service?.volume = volume.toFloat() }

        @JavascriptInterface
        fun isPlaying(): Boolean = service?.isPlaying() ?: false
    }
}
