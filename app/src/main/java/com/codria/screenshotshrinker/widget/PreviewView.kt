package com.codria.screenshotshrinker.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.view.GestureDetectorCompat

class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ZoomableImageView(context, attrs, defStyleAttr) {

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            viewMatrix.reset()
            invalidate()
            return true
        }
    })

    fun setImage(bmp: Bitmap?) {
        bitmap = bmp
        viewMatrix.reset()
        recomputeImageRect()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (imageRectF.isEmpty) return
        val sc = canvas.save()
        canvas.concat(viewMatrix)
        canvas.drawBitmap(bmp, null, imageRectF, bitmapPaint)
        canvas.restoreToCount(sc)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        gestureDetector.onTouchEvent(event)
        processScaleGesture(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isScaleGestureInProgress) {
                    viewMatrix.postTranslate(event.x - lastTouchX, event.y - lastTouchY)
                    constrainPan()
                    invalidate()
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
        return true
    }
}
