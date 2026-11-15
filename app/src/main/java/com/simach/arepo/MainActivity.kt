package com.simach.arepo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.huawei.hiar.ARConfigBase
import com.huawei.hiar.AREnginesApk
import com.huawei.hiar.ARSession
import com.huawei.hiar.ARWorldTrackingConfig
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var web: WebView
    private var session: ARSession? = null
    private lateinit var game: Game
    private lateinit var renderer: GameRenderer
    @Volatile private var boot = "checking"
    @Volatile private var pageReady = false
    private var lastError = 0L

    companion object { private const val REQ_CAM = 7 }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

        val root = FrameLayout(this)
        game = Game(this)
        glView = GLSurfaceView(this)
        glView.preserveEGLContextOnPause = true
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        renderer = GameRenderer(this, game)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "Native")
        web.webViewClient = WebViewClient()
        web.loadUrl("file:///android_asset/ui/index.html")

        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(glView, FrameLayout.LayoutParams(mp, mp))
        root.addView(web, FrameLayout.LayoutParams(mp, mp))
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            boot = "nocamera"
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
            return
        }
        startAr()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startAr()
            else boot = "nocamera"
        }
    }

    private fun startAr() {
        val ready = try { AREnginesApk.isAREngineApkReady(this) } catch (e: Throwable) { false }
        if (!ready) { boot = "noengine"; return }
        if (session == null) {
            try {
                val s = ARSession(applicationContext)
                val cfg = ARWorldTrackingConfig(s)
                cfg.focusMode = ARConfigBase.FocusMode.AUTO_FOCUS
                cfg.setPlaneFindingMode(ARConfigBase.PlaneFindingMode.ENABLE)
                try { cfg.semanticMode = ARWorldTrackingConfig.SEMANTIC_PLANE } catch (e: Throwable) { }
                try { cfg.lightingMode = ARConfigBase.LIGHT_MODE_AMBIENT_INTENSITY } catch (e: Throwable) { }
                try {
                    s.configure(cfg)
                } catch (e: Throwable) {
                    // повторяем без семантики, если устройство её не поддерживает
                    try { cfg.semanticMode = ARWorldTrackingConfig.SEMANTIC_NONE } catch (x: Throwable) { }
                    s.configure(cfg)
                }
                session = s
            } catch (e: Throwable) {
                val n = e.javaClass.simpleName
                boot = if (n.contains("NotInstalled") || n.contains("TooOld")) "noengine" else "error:$n ${e.message ?: ""}"
                return
            }
        }
        try {
            session!!.resume()
        } catch (e: Throwable) {
            boot = "error:Камера занята или недоступна (${e.javaClass.simpleName})"
            return
        }
        renderer.session = session
        renderer.geometryDirty = true
        glView.onResume()
        boot = "ok"
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        try { session?.pause() } catch (e: Throwable) { }
    }

    override fun onDestroy() {
        try { session?.stop() } catch (e: Throwable) { }
        session = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        js("window.onBack && onBack()")
    }

    fun js(code: String) {
        if (!pageReady) return
        runOnUiThread { try { web.evaluateJavascript(code, null) } catch (e: Throwable) { } }
    }

    fun reportError(e: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastError < 3000) return
        lastError = now
        val st = e.stackTrace.take(3).joinToString(" | ") { "${it.fileName}:${it.lineNumber}" }
        js("E(" + JSONObject().put("t", "msg").put("m", "Ошибка: ${e.javaClass.simpleName} ${e.message ?: ""} @ $st") + ")")
    }

    private fun vibrate(ms: Long) {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Throwable) { }
    }

    private fun openAppGallery() {
        try {
            val i = Intent("com.huawei.appmarket.intent.action.AppDetail")
            i.putExtra("APP_PACKAGENAME", "com.huawei.arengine.service")
            i.setPackage("com.huawei.appmarket")
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(i)
        } catch (e: Throwable) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("appmarket://details?id=com.huawei.arengine.service")))
            } catch (x: Throwable) {
                try { AREnginesApk.requestInstall(this, true) } catch (y: Throwable) { }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun ready() { pageReady = true }

        @JavascriptInterface
        fun boot(): String = boot

        @JavascriptInterface
        fun cmd(json: String) {
            try { game.cmds.add(JSONObject(json)) } catch (e: Throwable) { }
        }

        @JavascriptInterface
        fun vibrate(ms: Int) = this@MainActivity.vibrate(ms.toLong())

        @JavascriptInterface
        fun installEngine() = runOnUiThread { openAppGallery() }

        @JavascriptInterface
        fun retry() = runOnUiThread {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
            } else startAr()
        }

        @JavascriptInterface
        fun openSettings() = runOnUiThread {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Throwable) { }
        }
    }
}
