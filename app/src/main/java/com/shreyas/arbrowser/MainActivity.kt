package com.shreyas.arbrowser

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

class MirrorView(c: Context) : View(c) {
    var bitmap: Bitmap? = null
    override fun onDraw(canvas: Canvas) { bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) } }
}

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var root: FrameLayout
    private lateinit var scene: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var box: LinearLayout
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var reticle: View
    private lateinit var mirror: MirrorView

    private var vr = false
    private val handler = Handler(Looper.getMainLooper())
    private var bmp: Bitmap? = null
    private var copying = false
    private val rot = FloatArray(9)
    private var haveRef = false
    private var yaw0 = 0f
    private var pitch0 = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var dwellStart = 0L
    private var cooldownUntil = 0L

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, "Camera permission chahiye", Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        buildUi()
        setContentView(root)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        root.post { applyMode() }
        camPerm.launch(Manifest.permission.CAMERA)
        handler.post(copyLoop)
    }

    private fun btn(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f; isAllCaps = false
        minWidth = 0; minimumWidth = 0
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        scene = FrameLayout(this)
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        scene.addView(preview, FrameLayout.LayoutParams(-1, -1))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { urlBox.setText(url) }
            }
            webChromeClient = WebChromeClient()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        urlBox = EditText(this).apply {
            setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            hint = "URL ya search"; textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, _, _ -> go(v.text.toString()); true }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("◀") { if (web.canGoBack()) web.goBack() })
            addView(btn("▶") { if (web.canGoForward()) web.goForward() })
            addView(btn("⟳") { web.reload() })
            addView(btn("▲") { web.evaluateJavascript("window.scrollBy({top:-500,behavior:'smooth'})", null) })
            addView(btn("▼") { web.evaluateJavascript("window.scrollBy({top:500,behavior:'smooth'})", null) })
            addView(btn("Google") { go("https://www.google.com") })
            addView(btn("YouTube") { go("https://m.youtube.com") })
            addView(btn("⌖") { haveRef = false })
            addView(btn("VR") { vr = !vr; applyMode() })
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 24, 24, 28)); cornerRadius = dp(18).toFloat(); setStroke(dp(3), Color.WHITE)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(row1, LinearLayout.LayoutParams(-1, -2))
            addView(urlBox, LinearLayout.LayoutParams(-1, -2))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        scene.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        reticle = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.argb(60, 255, 255, 255)); setStroke(dp(3), Color.parseColor("#00E5FF"))
            }
        }
        scene.addView(reticle, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))

        mirror = MirrorView(this).apply { visibility = View.GONE }
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))
        root.addView(mirror, FrameLayout.LayoutParams(0, -1, Gravity.END))
        web.loadUrl("https://www.google.com")
    }

    private fun go(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        web.loadUrl(
            when {
                s.startsWith("http://") || s.startsWith("https://") -> s
                s.contains(" ") || !s.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(s)
                else -> "https://$s"
            }
        )
    }

    private fun applyMode() {
        val w = root.width; val h = root.height
        if (w == 0) return
        val sw = if (vr) w / 2 else w
        scene.layoutParams = FrameLayout.LayoutParams(sw, h, Gravity.START)
        mirror.layoutParams = FrameLayout.LayoutParams(w - sw, h, Gravity.END)
        mirror.visibility = if (vr) View.VISIBLE else View.GONE
        reticle.visibility = if (vr) View.VISIBLE else View.GONE
        box.layoutParams = FrameLayout.LayoutParams((sw * 0.8f).toInt(), (h * 0.84f).toInt(), Gravity.CENTER)
        box.translationX = 0f; box.translationY = 0f
        haveRef = false
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv)
        }, ContextCompat.getMainExecutor(this))
    }

    // Left half ko copy karke right half me dikhata hai (dono aankhon ke liye)
    private val copyLoop = object : Runnable {
        override fun run() { if (vr) copyFrame(); handler.postDelayed(this, 33) }
    }

    private fun copyFrame() {
        val w = scene.width; val h = scene.height
        if (copying || w <= 0 || h <= 0) return
        var b = bmp
        if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp = b }
        val loc = IntArray(2); scene.getLocationInWindow(loc)
        copying = true
        try {
            PixelCopy.request(window, Rect(loc[0], loc[1], loc[0] + w, loc[1] + h), b, { r ->
                copying = false
                if (r == PixelCopy.SUCCESS) { mirror.bitmap = b; mirror.invalidate() }
            }, handler)
        } catch (e: Exception) { copying = false }
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (!vr) return
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        val fx = -rot[2]; val fy = -rot[5]; val fz = -rot[8]   // camera kis taraf dekh raha hai
        val yaw = atan2(fx, fy)
        val pitch = asin(fz.coerceIn(-1f, 1f))
        if (!haveRef) { yaw0 = yaw; pitch0 = pitch; haveRef = true }
        var dy = yaw - yaw0
        if (dy > Math.PI) dy -= (2 * Math.PI).toFloat()
        if (dy < -Math.PI) dy += (2 * Math.PI).toFloat()
        val pxPerRad = scene.width / 1.5f
        box.translationX = -dy * pxPerRad
        box.translationY = (pitch - pitch0) * pxPerRad
        updateDwell()
    }

    // Reticle (beech ka circle) kisi cheez par 1.2 sec rukne par click hota hai
    private fun updateDwell() {
        val now = SystemClock.uptimeMillis()
        val bl = IntArray(2); box.getLocationInWindow(bl)
        val sl = IntArray(2); scene.getLocationInWindow(sl)
        val gx = sl[0] + scene.width / 2f - bl[0]
        val gy = sl[1] + scene.height / 2f - bl[1]
        val inside = gx >= 0 && gy >= 0 && gx < box.width && gy < box.height
        if (!inside || now < cooldownUntil) { dwellStart = 0; reticle.scaleX = 1f; reticle.scaleY = 1f; return }
        if (dwellStart == 0L || hypot(gx - anchorX, gy - anchorY) > dp(30)) { anchorX = gx; anchorY = gy; dwellStart = now }
        val p = ((now - dwellStart) / 1200f).coerceIn(0f, 1f)
        reticle.scaleX = 1f + p * 0.9f; reticle.scaleY = 1f + p * 0.9f
        if (p >= 1f) {
            val t = SystemClock.uptimeMillis()
            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, gx, gy, 0)
            box.dispatchTouchEvent(d); d.recycle()
            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, gx, gy, 0)
            box.dispatchTouchEvent(u); u.recycle()
            dwellStart = 0; cooldownUntil = now + 900
            reticle.scaleX = 1f; reticle.scaleY = 1f
        }
    }
}
