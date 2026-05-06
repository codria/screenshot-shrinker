package com.codria.screenshotshrinker.util

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Android デフォルト (~500ms) より長い長押し判定時間を持つカスタムタッチハンドラ。
 * 既定の View.setOnLongClickListener は ViewConfiguration.getLongPressTimeout() を使うが、
 * View 個別に変える手段がないため自前で計測する。
 *
 * 誤タップで破壊的操作 (削除/リセット) が発火しないよう、長めの timeout (デフォルト1000ms) を使う。
 */
object LongClickHelper {

    const val DEFAULT_LONG_PRESS_MS = 1000L

    @Suppress("ClickableViewAccessibility")
    fun attach(
        view: View,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        longPressMs: Long = DEFAULT_LONG_PRESS_MS,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        var startX = 0f
        var startY = 0f
        var triggered = false
        var pending: Runnable? = null
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    triggered = false
                    v.isPressed = true
                    val task = Runnable {
                        triggered = true
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongClick()
                    }
                    pending = task
                    handler.postDelayed(task, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        pending?.let { handler.removeCallbacks(it) }
                        pending = null
                        v.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pending?.let { handler.removeCallbacks(it) }
                    pending = null
                    v.isPressed = false
                    if (!triggered) onClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pending?.let { handler.removeCallbacks(it) }
                    pending = null
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }
}
