package com.forbiddenstreams.vod.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Plays YouTube trailers full screen inside the app with YouTube's official embedded
 * player, driven by the TV remote: OK = play/pause, Left/Right = skip 10s,
 * Up/Down = previous/next trailer, Back = close. Videos that can't be embedded are skipped.
 */
class TrailerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_START = "start"
        private const val ORIGIN = "https://forbiddenstreams.app"

        fun intent(context: Context, videoIds: List<String>, startIndex: Int): Intent =
            Intent(context, TrailerActivity::class.java)
                .putExtra(EXTRA_IDS, videoIds.toTypedArray())
                .putExtra(EXTRA_START, startIndex)
    }

    private val HANDLED_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )

    private var webView: WebView? = null
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ids = intent.getStringArrayExtra(EXTRA_IDS)?.toList().orEmpty()
        val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        if (ids.isEmpty()) {
            finish()
            return
        }

        val web = try {
            WebView(this)
        } catch (e: Exception) {
            // Some TV boxes ship without a working WebView.
            Toast.makeText(this, "Trailers need Android System WebView, which isn't available on this device.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        webView = web
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.isFocusable = false // the remote is handled in dispatchKeyEvent

        hint = TextView(this).apply {
            text = "OK  play / pause     ◀ ▶  skip 10s     ▲ ▼  other trailers     Back  close"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(0xB3000000.toInt())
            setPadding(32, 16, 32, 16)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                hint,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = 48 },
            )
        }
        setContentView(root)
        hideSystemBars()
        showHint()

        // Loaded with an https base URL so YouTube sees a proper origin/referrer for the embed.
        web.loadDataWithBaseURL("$ORIGIN/", playerHtml(ids, start), "text/html", "utf-8", null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return if (event.keyCode in HANDLED_KEYS) true else super.dispatchKeyEvent(event)
        }
        val js = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE,
            -> "toggle()"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play()"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause()"
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> "seek(-10)"
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "seek(10)"
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> "next()"
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous()"
            else -> return super.dispatchKeyEvent(event)
        }
        webView?.evaluateJavascript(js, null)
        showHint()
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        webView?.evaluateJavascript("pause()", null)
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun showHint() {
        if (!::hint.isInitialized) return
        hint.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ hint.visibility = View.GONE }, 4_000)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Called from the page's JavaScript. */
    private inner class Bridge {
        @JavascriptInterface
        fun finished() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun unplayable() {
            runOnUiThread {
                Toast.makeText(this@TrailerActivity, "None of these trailers can be played here.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun playerHtml(ids: List<String>, start: Int): String {
        val idArray = ids.joinToString(",") { "'$it'" }
        return """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style>
</head><body>
<div id="p"></div>
<script>
var ids=[$idArray], idx=$start, player=null, ready=false, tried=0;
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{
    width:'100%',height:'100%',videoId:ids[idx],
    playerVars:{autoplay:1,controls:1,rel:0,modestbranding:1,playsinline:1,fs:0,iv_load_policy:3,origin:'$ORIGIN'},
    events:{
      onReady:function(e){ready=true;e.target.playVideo();},
      onStateChange:function(e){ if(e.data===0){ if(idx<ids.length-1){ load(idx+1);} else { Android.finished(); } } },
      onError:function(e){ tried++; if(tried>=ids.length){ Android.unplayable(); return; } load((idx+1)%ids.length); }
    }
  });
}
function load(i){ idx=i; if(player&&ready){ player.loadVideoById(ids[idx]); } }
function toggle(){ if(!ready)return; if(player.getPlayerState()===1){player.pauseVideo();}else{player.playVideo();} }
function play(){ if(ready)player.playVideo(); }
function pause(){ if(ready)player.pauseVideo(); }
function seek(d){ if(!ready)return; player.seekTo(Math.max(0,player.getCurrentTime()+d),true); }
function next(){ if(idx<ids.length-1){ tried=0; load(idx+1);} }
function previous(){ if(idx>0){ tried=0; load(idx-1);} }
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
""".trimIndent()
    }
}
