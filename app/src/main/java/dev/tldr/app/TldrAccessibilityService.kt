package dev.tldr.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import dev.tldr.summarizer.ContentPrep
import kotlin.math.abs

/**
 * Hosts the floating bubble (an accessibility overlay, so no "draw over other apps" permission is needed)
 * and reads the screen's text when the bubble is tapped.
 */
class TldrAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        if (SettingsStore(this).bubbleEnabled) showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hideBubble()
        instance = null
        super.onDestroy()
    }

    fun showBubble() {
        if (bubble != null) return
        val sizePx = dp(52)
        val view = TextView(this).apply {
            text = "TL"
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_background)
            elevation = dp(6).toFloat()
            contentDescription = getString(R.string.action_summarize)
        }
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - sizePx - dp(8)
            y = resources.displayMetrics.heightPixels / 3
        }
        attachTouchHandling(view, params)
        windowManager.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    fun hideBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchHandling(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            SettingsStore(this).bubbleEnabled = false
            hideBubble()
            Toast.makeText(this, R.string.bubble_hidden, Toast.LENGTH_SHORT).show()
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    handler.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging && bubble != null) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) snapToEdge(view, params)
                    else if (!longPressed) onBubbleTapped()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val screenWidth = resources.displayMetrics.widthPixels
        val center = params.x + view.width / 2
        params.x = if (center < screenWidth / 2) dp(8) else screenWidth - view.width - dp(8)
        if (bubble != null) windowManager.updateViewLayout(view, params)
    }

    private fun onBubbleTapped() {
        val capture = ScreenTextExtractor.capture(this)
        if (capture.text.length < ContentPrep.MIN_CHARS && capture.url == null) {
            Toast.makeText(this, R.string.no_text, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, SummaryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(SummaryActivity.EXTRA_TEXT, capture.text)
            putExtra(SummaryActivity.EXTRA_SOURCE_APP, capture.appLabel ?: capture.packageName)
            putExtra(SummaryActivity.EXTRA_URL, capture.url)
        }
        startActivity(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        var instance: TldrAccessibilityService? = null
            private set
    }
}
