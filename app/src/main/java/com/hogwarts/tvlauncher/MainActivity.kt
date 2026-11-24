package com.hogwarts.tvlauncher

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var hedwigView: TextView
    private val appCards = mutableListOf<LinearLayout>()
    private var padServer: PadMirrorServer? = null

    @Volatile
    var latestPadJson: String = """{"text":"","image":"","updated":0}"""

    private val apps = listOf(
        Triple("NETFLIX", "Netflix", "com.netflix.ninja") to "#E50914",
        Triple("▶ YouTube", "YouTube", "com.google.android.youtube.tv") to "#FF2A2A",
        Triple("FANCODE", "FanCode", "com.fancode.tv") to "#FF6B00",
        Triple("JioHotstar", "JioHotstar", "in.startv.hotstar") to "#38BDF8"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0a0b10"))
        }

        // 1. Non-interactive WebView Layer (Wallpapers, Clock, Weather, Marauder's Parchment)
        webView = WebView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(TvBridge(), "AndroidBridge")
        }
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 2. Native Android TV D-Pad Dock Layer (Guaranteed Sony Bravia Focus)
        val dockContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B80C0A08"))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.parseColor("#66DAB260"))
            }
        }

        appCards.clear()
        apps.forEachIndexed { index, (info, brandHex) ->
            val (badgeText, labelText, pkg) = info
            val card = createNativeAppCard(badgeText, labelText, brandHex, pkg, index)
            appCards.add(card)
            val lp = LinearLayout.LayoutParams(dp(168), dp(104)).apply {
                if (index > 0) marginStart = dp(28)
            }
            dockContainer.addView(card, lp)
        }

        val dockParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(46)
        }
        root.addView(dockContainer, dockParams)

        // 3. Native Enchantments Button (Bottom-Right)
        val settingsBtn = TextView(this).apply {
            text = "✦ Enchantments"
            setTextColor(Color.parseColor("#E5BE6B"))
            textSize = 14f
            typeface = Typeface.SERIF
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusable = true
            isFocusableInTouchMode = true
            background = makeCardBg(false)
            setOnFocusChangeListener { v, hasFocus ->
                v.background = makeCardBg(hasFocus)
                v.animate().scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f).setDuration(200).start()
            }
            setOnClickListener { showEnchantmentDialog() }
        }
        val settingsParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            bottomMargin = dp(24)
            marginEnd = dp(30)
        }
        root.addView(settingsBtn, settingsParams)

        // 4. Native Animated Hedwig Owl Overlay
        hedwigView = TextView(this).apply {
            text = "🦉"
            textSize = 42f
            elevation = dp(16).toFloat()
            isFocusable = false
        }
        root.addView(hedwigView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        startLocalPadServer()
        webView.loadUrl("file:///android_asset/index.html")

        // Give initial focus to Netflix and perch Hedwig above it
        appCards.firstOrNull()?.post {
            appCards[0].requestFocus()
            flyHedwigTo(appCards[0], animate = false)
        }
    }

    private fun createNativeAppCard(
        badgeText: String,
        labelText: String,
        brandHex: String,
        packageName: String,
        index: Int
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            background = makeCardBg(index == 0)

            val badge = TextView(this@MainActivity).apply {
                text = badgeText
                setTextColor(Color.parseColor(brandHex))
                textSize = 21f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val label = TextView(this@MainActivity).apply {
                text = labelText
                setTextColor(Color.parseColor("#DFCBA3"))
                textSize = 13f
                typeface = Typeface.SERIF
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            addView(badge)
            addView(label)

            setOnFocusChangeListener { v, hasFocus ->
                v.background = makeCardBg(hasFocus)
                v.animate()
                    .translationY(if (hasFocus) -dp(12).toFloat() else 0f)
                    .scaleX(if (hasFocus) 1.09f else 1f)
                    .scaleY(if (hasFocus) 1.09f else 1f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
                if (hasFocus) {
                    flyHedwigTo(v, animate = true)
                }
            }

            setOnClickListener {
                launchTvApp(packageName)
            }
        }
    }

    private fun makeCardBg(focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(if (focused) "#F22E2315" else "#D91A1510"))
            cornerRadius = dp(18).toFloat()
            setStroke(
                dp(if (focused) 3 else 1),
                Color.parseColor(if (focused) "#FFD670" else "#59B9924A")
            )
        }
    }

    private fun flyHedwigTo(target: View, animate: Boolean) {
        val loc = IntArray(2)
        target.getLocationInWindow(loc)
        val targetX = loc[0] + (target.width / 2f) - (hedwigView.width / 2f)
        val targetY = loc[1] - hedwigView.height + dp(8).toFloat()

        if (!animate) {
            hedwigView.x = targetX
            hedwigView.y = targetY
            return
        }

        // Arc flight animation with wing-flap tilt
        hedwigView.animate()
            .x(targetX)
            .y(targetY - dp(22))
            .rotation(if (targetX > hedwigView.x) 14f else -14f)
            .scaleX(1.18f)
            .scaleY(1.18f)
            .setDuration(280)
            .withEndAction {
                hedwigView.animate()
                    .y(targetY)
                    .rotation(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
            .start()
    }

    private fun showEnchantmentDialog() {
        val prefs = getSharedPreferences("hogwarts_prefs", Context.MODE_PRIVATE)
        val options = arrayOf(
            "Toggle Live Weather & Day/Night Cycle",
            "Cycle Wizarding Background Scene",
            "Clear Marauder's Corner Note",
            "Open Sony Android TV Settings"
        )
        AlertDialog.Builder(this)
            .setTitle("✦ Hogwarts Enchantments ✦")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val next = !prefs.getBoolean("weather_on", true)
                        prefs.edit().putBoolean("weather_on", next).apply()
                        webView.evaluateJavascript(
                            "localStorage.setItem('hp_weather_enabled', '$next'); weatherEnabled = $next; applyWallpaperAndAtmosphere(); if($next) fetchWeather();",
                            null
                        )
                    }
                    1 -> {
                        val nextScene = (prefs.getInt("scene_idx", 0) + 1) % 7
                        prefs.edit().putInt("scene_idx", nextScene).apply()
                        webView.evaluateJavascript(
                            "localStorage.setItem('hp_manual_scene', '$nextScene'); manualSceneIndex = '$nextScene'; applyWallpaperAndAtmosphere();",
                            null
                        )
                    }
                    2 -> {
                        latestPadJson = """{"text":"","image":"","updated":${System.currentTimeMillis()}}"""
                        webView.evaluateJavascript(
                            "document.getElementById('parchmentText').textContent='Mischief managed.'; document.getElementById('parchmentImg').style.display='none';",
                            null
                        )
                    }
                    3 -> {
                        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            .show()
    }

    private fun launchTvApp(packageName: String) {
        try {
            val leanbackIntent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            val launchIntent = leanbackIntent ?: packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "App not installed: $packageName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not launch app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startLocalPadServer() {
        try {
            padServer?.stop()
            padServer = PadMirrorServer(8080)
            padServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        padServer?.stop()
        super.onDestroy()
    }

    inner class TvBridge {
        @JavascriptInterface
        fun launchApp(packageName: String) = runOnUiThread { launchTvApp(packageName) }

        @JavascriptInterface
        fun openSystemSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        @JavascriptInterface
        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            } catch (_: Exception) { }
            return "Check TV Wi-Fi IP"
        }

        @JavascriptInterface
        fun getPadState(): String = latestPadJson

        @JavascriptInterface
        fun clearPadState() {
            latestPadJson = """{"text":"","image":"","updated":${System.currentTimeMillis()}}"""
        }
    }

    inner class PadMirrorServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            if (uri == "/api/update" && session.method == Method.POST) {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val body = files["postData"] ?: "{}"
                latestPadJson = body
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""")
            }
            if (uri == "/api/state") {
                return newFixedLengthResponse(Response.Status.OK, "application/json", latestPadJson)
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html", PHONE_PAD_HTML)
        }
    }

    companion object {
        private val PHONE_PAD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<title>Marauder's TV Parchment</title>
<style>
  * { box-sizing: border-box; font-family: Georgia, serif; }
  body { margin: 0; background: #16110c; color: #f3e5c8; padding: 14px; display: flex; flex-direction: column; gap: 12px; }
  h2 { margin: 0; font-size: 1.25rem; color: #f6c96b; text-align: center; letter-spacing: 0.5px; }
  .card { background: #261d14; border: 1px solid #6b4f2a; border-radius: 12px; padding: 12px; }
  textarea { width: 100%; height: 84px; background: #f4e6c3; color: #2b1a0c; border: 2px solid #8a6432; border-radius: 8px; padding: 10px; font-size: 16px; resize: none; }
  canvas { width: 100%; height: 220px; background: #f4e6c3; border: 2px solid #8a6432; border-radius: 8px; touch-action: none; display: block; }
  .row { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
  button { flex: 1; padding: 11px; border-radius: 8px; border: 1px solid #9c743c; background: #3b2b1a; color: #f6d58e; font-size: 15px; font-weight: bold; }
  button.primary { background: #7a4b16; color: #fff6df; }
  .swatch { width: 32px; height: 32px; border-radius: 50%; border: 2px solid #fff; flex: none; }
  .status { text-align: center; font-size: 12px; color: #bfa67a; }
</style>
</head>
<body>
  <h2>✦ Marauder's TV Parchment ✦</h2>
  <div class="card">
    <div style="margin-bottom:6px;font-size:13px;color:#d8be8c;">Quick Note to TV:</div>
    <textarea id="noteText" placeholder="Write a message to appear on your TV..."></textarea>
  </div>
  <div class="card">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">
      <span style="font-size:13px;color:#d8be8c;">Live Sketchpad:</span>
      <div style="display:flex;gap:8px;">
        <button class="swatch" style="background:#2b180a;" onclick="setColor('#2b180a')"></button>
        <button class="swatch" style="background:#8b1e1e;" onclick="setColor('#8b1e1e')"></button>
        <button class="swatch" style="background:#17477a;" onclick="setColor('#17477a')"></button>
        <button class="swatch" style="background:#1e5c2b;" onclick="setColor('#1e5c2b')"></button>
      </div>
    </div>
    <canvas id="pad" width="520" height="300"></canvas>
    <div class="row">
      <button onclick="clearAll()">Mischief Managed (Clear)</button>
      <button class="primary" onclick="sendNow()">Send Now</button>
    </div>
  </div>
  <div class="status" id="status">Connected to TV • Auto-syncing live</div>
<script>
  const canvas = document.getElementById('pad');
  const ctx = canvas.getContext('2d');
  const noteEl = document.getElementById('noteText');
  let drawing = false, color = '#2b180a', syncTimer = null;

  ctx.lineWidth = 4;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = color;

  function setColor(c) { color = c; ctx.strokeStyle = c; }

  function getPos(e) {
    const r = canvas.getBoundingClientRect();
    const t = e.touches ? e.touches[0] : e;
    return {
      x: (t.clientX - r.left) * (canvas.width / r.width),
      y: (t.clientY - r.top) * (canvas.height / r.height)
    };
  }

  function start(e) { drawing = true; const p = getPos(e); ctx.beginPath(); ctx.moveTo(p.x, p.y); }
  function move(e) {
    if (!drawing) return;
    e.preventDefault();
    const p = getPos(e);
    ctx.lineTo(p.x, p.y);
    ctx.stroke();
    scheduleSync();
  }
  function end() { if (drawing) { drawing = false; scheduleSync(); } }

  canvas.addEventListener('touchstart', start, {passive:false});
  canvas.addEventListener('touchmove', move, {passive:false});
  canvas.addEventListener('touchend', end);
  canvas.addEventListener('mousedown', start);
  canvas.addEventListener('mousemove', move);
  window.addEventListener('mouseup', end);

  noteEl.addEventListener('input', scheduleSync);

  function scheduleSync() {
    clearTimeout(syncTimer);
    syncTimer = setTimeout(sendNow, 250);
  }

  function clearAll() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    noteEl.value = '';
    sendNow();
  }

  function sendNow() {
    const payload = JSON.stringify({
      text: noteEl.value,
      image: canvas.toDataURL('image/png'),
      updated: Date.now()
    });
    fetch('/api/update', { method: 'POST', body: payload })
      .then(() => { document.getElementById('status').textContent = 'Synced to TV at ' + new Date().toLocaleTimeString(); })
      .catch(() => { document.getElementById('status').textContent = 'Sync error — check TV Wi-Fi'; });
  }
</script>
</body>
</html>
""".trimIndent()
    }
}
