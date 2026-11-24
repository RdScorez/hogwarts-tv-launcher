package com.hogwarts.tvlauncher

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var padServer: PadMirrorServer? = null

    @Volatile
    var latestPadJson: String = """{"text":"","image":"","updated":0}"""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(webView)
        webView.requestFocus()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowUniversalAccessFromFileURLs = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.requestFocus()
            }
        }
        webView.addJavascriptInterface(TvBridge(), "AndroidBridge")

        startLocalPadServer()
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.requestFocus()
        }
    }

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

    private fun sendKeyToJs(action: String) {
        runOnUiThread {
            webView.evaluateJavascript("if(window.onRemoteKey){window.onRemoteKey('$action');}", null)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { sendKeyToJs("LEFT"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { sendKeyToJs("RIGHT"); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { sendKeyToJs("UP"); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { sendKeyToJs("DOWN"); return true }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> { sendKeyToJs("ENTER"); return true }
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO -> { sendKeyToJs("MENU"); return true }
                KeyEvent.KEYCODE_BACK -> {
                    runOnUiThread {
                        webView.evaluateJavascript("if(window.handleBackKey){window.handleBackKey();}", null)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    inner class TvBridge {
        @JavascriptInterface
        fun launchApp(packageName: String) {
            runOnUiThread {
                try {
                    val leanbackIntent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                    val launchIntent = leanbackIntent ?: packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    } else {
                        Toast.makeText(this@MainActivity, "App not installed: $packageName", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Could not launch app", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun openSystemSettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
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
