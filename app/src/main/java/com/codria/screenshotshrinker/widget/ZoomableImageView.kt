package com.codria.screenshotshrinker.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

abstract class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    protected var bitmap: Bitmap? = null
    protected val imageRectF = RectF()
    protected val viewMatrix = Matrix()
    private val invertedMatrix = Matrix()
    protected val tmpPts = FloatArray(2)

    protected val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    protected val handleRadius: Float get() = 7f * resources.displayMetrics.density
    protected val handleHitRadius: Float get() = 28f * resources.displayMetrics.density
    protected val minSizePx: Int = 32

    private val minScale: Float = 0.3f
    private val maxScale: Float = 5f

    protected var dragOffsetImgX: Float = 0f
    protected var dragOffsetImgY: Float = 0f

    protected fun startRectDrag(viewX: Float, viewY: Float, rect: RectF) {
        dragOffsetImgX = viewToImageX(viewX) - rect.left
        dragOffsetImgY = viewToImageY(viewY) - rect.top
    }

    protected fun applyRectDrag(viewX: Float, viewY: Float, rect: RectF) {
        val bmp = bitmap ?: return
        val imgX = viewToImageX(viewX)
        val imgY = viewToImageY(viewY)
        val curW = rect.width()
        val curH = rect.height()
        val newLeft = (imgX - dragOffsetImgX).coerceIn(0f, bmp.width - curW)
        val newTop = (imgY - dragOffsetImgY).coerceIn(0f, bmp.height - curH)
        rect.set(newLeft, newTop, newLeft + curW, newTop + curH)
    }

    private var pendingDragCommit: (() -> Unit)? = null

    protected fun scheduleDragUndo(commit: () -> Unit) {
        pendingDragCommit = commit
    }

    protected fun commitDragUndo() {
        pendingDragCommit?.invoke()
        pendingDragCommit = null
    }

    private var pinchId0: Int = MotionEvent.INVALID_POINTER_ID
    private var pinchId1: Int = MotionEvent.INVALID_POINTER_ID
    private var pinchLastSpan: Float = 0f
    private var _isScaleGestureInProgress: Boolean = false

    protected val isScaleGestureInProgress: Boolean get() = _isScaleGestureInProgress

    protected fun processScaleGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinchId0 = event.getPointerId(0)
                pinchId1 = MotionEvent.INVALID_POINTER_ID
                _isScaleGestureInProgress = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pinchId1 == MotionEvent.INVALID_POINTER_ID) {
                    pinchId1 = event.getPointerId(event.actionIndex)
                    pinchLastSpan = pinchSpan(event)
                    _isScaleGestureInProgress = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinchId1 != MotionEvent.INVALID_POINTER_ID) {
                    val span = pinchSpan(event)
                    if (pinchLastSpan > 0f && span > 0f) {
                        val (fx, fy) = pinchFocus(event)
                        val current = currentScale()
                        val target = (current * span / pinchLastSpan).coerceIn(minScale, maxScale)
                        val effective = target / current
                        viewMatrix.postScale(effective, effective, fx, fy)
                        constrainPan()
                        invalidate()
                    }
                    pinchLastSpan = span
                    _isScaleGestureInProgress = true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upId = event.getPointerId(event.actionIndex)
                if (upId == pinchId0 || upId == pinchId1) {
                    pinchId0 = MotionEvent.INVALID_POINTER_ID
                    pinchId1 = MotionEvent.INVALID_POINTER_ID
                    pinchLastSpan = 0f
                    _isScaleGestureInProgress = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinchId0 = MotionEvent.INVALID_POINTER_ID
                pinchId1 = MotionEvent.INVALID_POINTER_ID
                pinchLastSpan = 0f
                _isScaleGestureInProgress = false
            }
        }
    }

    private fun pinchSpan(event: MotionEvent): Float {
        val i0 = event.findPointerIndex(pinchId0)
        val i1 = event.findPointerIndex(pinchId1)
        if (i0 < 0 || i1 < 0) return pinchLastSpan
        val dx = event.getX(i0) - event.getX(i1)
        val dy = event.getY(i0) - event.getY(i1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun pinchFocus(event: MotionEvent): Pair<Float, Float> {
        val i0 = event.findPointerIndex(pinchId0)
        val i1 = event.findPointerIndex(pinchId1)
        if (i0 < 0 || i1 < 0) return 0f to 0f
        return (event.getX(i0) + event.getX(i1)) / 2f to (event.getY(i0) + event.getY(i1)) / 2f
    }

    protected fun currentScale(): Float {
        val pts = FloatArray(9)
        viewMatrix.getValues(pts)
        return pts[Matrix.MSCALE_X]
    }

    protected fun constrainPan() {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        val rect = RectF(imageRectF)
        viewMatrix.mapRect(rect)
        val visibleMinX = minOf(viewW, rect.width()) * VISIBLE_MIN_FRACTION
        val visibleMinY = minOf(viewH, rect.height()) * VISIBLE_MIN_FRACTION
        var dx = 0f
        if (rect.right < visibleMinX) dx = visibleMinX - rect.right
        else if (rect.left > viewW - visibleMinX) dx = (viewW - visibleMinX) - rect.left
        var dy = 0f
        if (rect.bottom < visibleMinY) dy = visibleMinY - rect.bottom
        else if (rect.top > viewH - visibleMinY) dy = (viewH - visibleMinY) - rect.top
        if (dx != 0f || dy != 0f) viewMatrix.postTranslate(dx, dy)
    }

    protected fun recomputeImageRect() {
        val bmp = bitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        val scale = minOf(viewW / bmp.width, viewH / bmp.height)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val left = (viewW - drawW) / 2
        val top = (viewH - drawH) / 2
        imageRectF.set(left, top, left + drawW, top + drawH)
    }

    protected fun viewToImageX(screenX: Float): Float {
        val bmp = bitmap ?: return 0f
        val scale = imageRectF.width() / bmp.width
        viewMatrix.invert(invertedMatrix)
        tmpPts[0] = screenX
        tmpPts[1] = 0f
        invertedMatrix.mapPoints(tmpPts)
        return ((tmpPts[0] - imageRectF.left) / scale).coerceIn(0f, bmp.width.toFloat())
    }

    protected fun viewToImageY(screenY: Float): Float {
        val bmp = bitmap ?: return 0f
        val scale = imageRectF.height() / bmp.height
        viewMatrix.invert(invertedMatrix)
        tmpPts[0] = 0f
        tmpPts[1] = screenY
        invertedMatrix.mapPoints(tmpPts)
        return ((tmpPts[1] - imageRectF.top) / scale).coerceIn(0f, bmp.height.toFloat())
    }

    /** 画像座標系 → viewMatrix 適用前のビュー座標系 */
    protected fun imageToViewCoords(src: RectF): RectF {
        val bmp = bitmap ?: return RectF()
        val scale = imageRectF.width() / bmp.width
        return RectF(
            imageRectF.left + src.left * scale,
            imageRectF.top + src.top * scale,
            imageRectF.left + src.right * scale,
            imageRectF.top + src.bottom * scale,
        )
    }

    /** 画像座標系 → viewMatrix 適用後の画面座標系 */
    protected fun imageToScreenCoords(src: RectF): RectF {
        val cv = imageToViewCoords(src)
        val arr = floatArrayOf(cv.left, cv.top, cv.right, cv.bottom)
        viewMatrix.mapPoints(arr)
        return RectF(arr[0], arr[1], arr[2], arr[3])
    }

    protected fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeImageRect()
    }

    companion object {
        const val VISIBLE_MIN_FRACTION: Float = 0.25f
    }
}
