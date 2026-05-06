package com.codria.screenshotshrinker.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent

/**
 * 画像表示 + 矩形クロップ領域指定の自前View。
 *
 * - ピンチで拡大 (0.3〜5倍)、ドラッグで移動。拡大状態は ACTION_UP 後も維持。
 * - 4角ハンドル + 4辺ハンドル(アスペクト比フリー時のみ有効)
 * - 内部ドラッグで全体移動
 * - 画像端へのスナップ (アスペクト比フリー時)
 * - アスペクト比ロック中の角ドラッグは反対角を支点にratio保持
 * - Undo/Redo (drag開始 / aspect変更 / プリセット読込 で履歴push)
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ZoomableImageView(context, attrs, defStyleAttr) {

    private val cropRectImage = RectF()
    private var aspectRatio: Float = 0f

    private val overlayPaint: Paint by lazy {
        val cellPx = (8f * resources.displayMetrics.density).toInt().coerceAtLeast(8)
        val full = cellPx * 2
        val tile = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        val pDark = Paint().apply { color = 0x80555555.toInt() }
        val pLight = Paint().apply { color = 0x80999999.toInt() }
        c.drawRect(0f, 0f, cellPx.toFloat(), cellPx.toFloat(), pDark)
        c.drawRect(cellPx.toFloat(), cellPx.toFloat(), full.toFloat(), full.toFloat(), pDark)
        c.drawRect(cellPx.toFloat(), 0f, full.toFloat(), cellPx.toFloat(), pLight)
        c.drawRect(0f, cellPx.toFloat(), cellPx.toFloat(), full.toFloat(), pLight)
        Paint().apply {
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
    private val insideTintPaint = Paint().apply { color = 0x14FFFFFF.toInt() }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val activeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val snapThresholdView: Float get() = 8f * resources.displayMetrics.density

    private enum class Handle { NONE, TL, TR, BL, BR, T, B, L, R, INTERIOR, PAN }
    private var activeHandle: Handle = Handle.NONE
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f

    private val undoStack: ArrayDeque<Rect> = ArrayDeque()
    private val redoStack: ArrayDeque<Rect> = ArrayDeque()
    private val maxHistory = 30

    var onCropChange: ((Rect) -> Unit)? = null
    var onHistoryChange: (() -> Unit)? = null

    fun setBitmap(bmp: Bitmap, initialCrop: Rect? = null) {
        bitmap = bmp
        cropRectImage.set(
            (initialCrop?.left ?: 0).toFloat(),
            (initialCrop?.top ?: 0).toFloat(),
            (initialCrop?.right ?: bmp.width).toFloat(),
            (initialCrop?.bottom ?: bmp.height).toFloat(),
        )
        undoStack.clear()
        redoStack.clear()
        viewMatrix.reset()
        recomputeImageRect()
        invalidate()
        notifyChange()
        onHistoryChange?.invoke()
    }

    fun setAspectRatio(ratio: Float) {
        if (ratio > 0f) pushUndoFromCurrent()
        aspectRatio = ratio
        if (ratio > 0) applyAspectRatioToCurrent()
        invalidate()
        notifyChange()
    }

    fun setCropRectFromValues(x: Int, y: Int, w: Int, h: Int, autoCommit: Boolean = false) {
        val bmp = bitmap ?: return
        val nx = x.coerceIn(0, bmp.width - minSizePx)
        val ny = y.coerceIn(0, bmp.height - minSizePx)
        val nw = w.coerceIn(minSizePx, bmp.width - nx)
        val nh = h.coerceIn(minSizePx, bmp.height - ny)
        val newRect = RectF(nx.toFloat(), ny.toFloat(), (nx + nw).toFloat(), (ny + nh).toFloat())
        if (newRect == cropRectImage) return
        if (autoCommit) pushUndoFromCurrent()
        cropRectImage.set(newRect)
        invalidate()
        notifyChange()
    }

    fun getCropRect(): Rect = Rect(
        cropRectImage.left.toInt(),
        cropRectImage.top.toInt(),
        cropRectImage.right.toInt(),
        cropRectImage.bottom.toInt(),
    )

    fun pushUndoFromCurrent() {
        undoStack.addLast(getCropRect())
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        onHistoryChange?.invoke()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = getCropRect()
        redoStack.addLast(current)
        if (redoStack.size > maxHistory) redoStack.removeFirst()
        val prev = undoStack.removeLast()
        cropRectImage.set(prev)
        invalidate()
        notifyChange()
        onHistoryChange?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = getCropRect()
        undoStack.addLast(current)
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        val next = redoStack.removeLast()
        cropRectImage.set(next)
        invalidate()
        notifyChange()
        onHistoryChange?.invoke()
    }

    private fun applyAspectRatioToCurrent() {
        val bmp = bitmap ?: return
        val ratio = aspectRatio
        if (ratio <= 0) return
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        val (targetW, targetH) = if (bmpW / bmpH > ratio) {
            (bmpH * ratio) to bmpH
        } else {
            bmpW to (bmpW / ratio)
        }
        val left = (bmpW - targetW) / 2
        val top = (bmpH - targetH) / 2
        cropRectImage.set(left, top, left + targetW, top + targetH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (imageRectF.isEmpty) return

        val sc = canvas.save()
        canvas.concat(viewMatrix)

        canvas.drawBitmap(bmp, null, imageRectF, bitmapPaint)
        val cvPre = imageToViewCoords(cropRectImage)

        canvas.drawRect(imageRectF.left, imageRectF.top, imageRectF.right, cvPre.top, overlayPaint)
        canvas.drawRect(imageRectF.left, cvPre.bottom, imageRectF.right, imageRectF.bottom, overlayPaint)
        canvas.drawRect(imageRectF.left, cvPre.top, cvPre.left, cvPre.bottom, overlayPaint)
        canvas.drawRect(cvPre.right, cvPre.top, imageRectF.right, cvPre.bottom, overlayPaint)

        canvas.restoreToCount(sc)

        val cvScreen = imageToScreenCoords(cropRectImage)
        canvas.drawRect(cvScreen, insideTintPaint)
        drawGrid(canvas, cvScreen)
        drawHandles(canvas, cvScreen)
    }

    private fun drawGrid(canvas: Canvas, cv: RectF) {
        val w = cv.width()
        val h = cv.height()
        canvas.drawLine(cv.left + w / 3, cv.top, cv.left + w / 3, cv.bottom, gridPaint)
        canvas.drawLine(cv.left + 2 * w / 3, cv.top, cv.left + 2 * w / 3, cv.bottom, gridPaint)
        canvas.drawLine(cv.left, cv.top + h / 3, cv.right, cv.top + h / 3, gridPaint)
        canvas.drawLine(cv.left, cv.top + 2 * h / 3, cv.right, cv.top + 2 * h / 3, gridPaint)
    }

    private fun drawHandles(canvas: Canvas, cv: RectF) {
        val r = handleRadius
        fun paintFor(h: Handle): Paint = if (isHandleAffected(h)) activeHandlePaint else handlePaint
        canvas.drawCircle(cv.left, cv.top, r, paintFor(Handle.TL))
        canvas.drawCircle(cv.right, cv.top, r, paintFor(Handle.TR))
        canvas.drawCircle(cv.left, cv.bottom, r, paintFor(Handle.BL))
        canvas.drawCircle(cv.right, cv.bottom, r, paintFor(Handle.BR))
        if (aspectRatio <= 0f) {
            canvas.drawCircle((cv.left + cv.right) / 2, cv.top, r, paintFor(Handle.T))
            canvas.drawCircle((cv.left + cv.right) / 2, cv.bottom, r, paintFor(Handle.B))
            canvas.drawCircle(cv.left, (cv.top + cv.bottom) / 2, r, paintFor(Handle.L))
            canvas.drawCircle(cv.right, (cv.top + cv.bottom) / 2, r, paintFor(Handle.R))
        }
    }

    private fun isHandleAffected(h: Handle): Boolean = when (activeHandle) {
        Handle.TL -> h in setOf(Handle.TL, Handle.T, Handle.TR, Handle.L, Handle.BL)
        Handle.TR -> h in setOf(Handle.TR, Handle.T, Handle.TL, Handle.R, Handle.BR)
        Handle.BL -> h in setOf(Handle.BL, Handle.B, Handle.BR, Handle.L, Handle.TL)
        Handle.BR -> h in setOf(Handle.BR, Handle.B, Handle.BL, Handle.R, Handle.TR)
        Handle.T -> h in setOf(Handle.T, Handle.TL, Handle.TR)
        Handle.B -> h in setOf(Handle.B, Handle.BL, Handle.BR)
        Handle.L -> h in setOf(Handle.L, Handle.TL, Handle.BL)
        Handle.R -> h in setOf(Handle.R, Handle.TR, Handle.BR)
        else -> false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        processScaleGesture(event)
        if (isScaleGestureInProgress) {
            activeHandle = Handle.NONE
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cvScreen = imageToScreenCoords(cropRectImage)
                val hit = detectHandle(event.x, event.y, cvScreen)
                activeHandle = when {
                    hit != Handle.NONE -> {
                        if (hit == Handle.INTERIOR) startRectDrag(event.x, event.y, cropRectImage)
                        val before = getCropRect()
                        scheduleDragUndo {
                            if (before != getCropRect()) {
                                undoStack.addLast(before)
                                if (undoStack.size > maxHistory) undoStack.removeFirst()
                                redoStack.clear()
                                onHistoryChange?.invoke()
                            }
                        }
                        hit
                    }
                    else -> Handle.PAN
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle != Handle.NONE) {
                    if (activeHandle == Handle.PAN) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        viewMatrix.postTranslate(dx, dy)
                        constrainPan()
                        invalidate()
                    } else {
                        handleDrag(event.x, event.y)
                        invalidate()
                        notifyChange()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                commitDragUndo()
                activeHandle = Handle.NONE
                invalidate()
            }
        }
        return true
    }

    private fun detectHandle(x: Float, y: Float, cv: RectF): Handle {
        val r = handleHitRadius
        if (dist(x, y, cv.left, cv.top) < r) return Handle.TL
        if (dist(x, y, cv.right, cv.top) < r) return Handle.TR
        if (dist(x, y, cv.left, cv.bottom) < r) return Handle.BL
        if (dist(x, y, cv.right, cv.bottom) < r) return Handle.BR
        if (aspectRatio <= 0f) {
            val cx = (cv.left + cv.right) / 2
            val cy = (cv.top + cv.bottom) / 2
            if (dist(x, y, cx, cv.top) < r) return Handle.T
            if (dist(x, y, cx, cv.bottom) < r) return Handle.B
            if (dist(x, y, cv.left, cy) < r) return Handle.L
            if (dist(x, y, cv.right, cy) < r) return Handle.R
        }
        if (cv.contains(x, y)) return Handle.INTERIOR
        return Handle.NONE
    }

    private fun handleDrag(viewX: Float, viewY: Float) {
        val bmp = bitmap ?: return
        val imgX = viewToImageX(viewX)
        val imgY = viewToImageY(viewY)
        val r = cropRectImage

        if (activeHandle == Handle.INTERIOR) {
            applyRectDrag(viewX, viewY, r)
            return
        }

        if (aspectRatio > 0f && activeHandle in setOf(Handle.TL, Handle.TR, Handle.BL, Handle.BR)) {
            applyAspectLockedCornerDrag(imgX, imgY, bmp)
            return
        }

        when (activeHandle) {
            Handle.TL -> { r.left = imgX.coerceAtMost(r.right - minSizePx); r.top = imgY.coerceAtMost(r.bottom - minSizePx) }
            Handle.TR -> { r.right = imgX.coerceAtLeast(r.left + minSizePx); r.top = imgY.coerceAtMost(r.bottom - minSizePx) }
            Handle.BL -> { r.left = imgX.coerceAtMost(r.right - minSizePx); r.bottom = imgY.coerceAtLeast(r.top + minSizePx) }
            Handle.BR -> { r.right = imgX.coerceAtLeast(r.left + minSizePx); r.bottom = imgY.coerceAtLeast(r.top + minSizePx) }
            Handle.T -> r.top = imgY.coerceAtMost(r.bottom - minSizePx)
            Handle.B -> r.bottom = imgY.coerceAtLeast(r.top + minSizePx)
            Handle.L -> r.left = imgX.coerceAtMost(r.right - minSizePx)
            Handle.R -> r.right = imgX.coerceAtLeast(r.left + minSizePx)
            else -> return
        }

        applySnap(r, bmp)

        r.left = r.left.coerceIn(0f, bmp.width.toFloat() - minSizePx)
        r.top = r.top.coerceIn(0f, bmp.height.toFloat() - minSizePx)
        r.right = r.right.coerceIn(minSizePx.toFloat(), bmp.width.toFloat())
        r.bottom = r.bottom.coerceIn(minSizePx.toFloat(), bmp.height.toFloat())
    }

    private fun applyAspectLockedCornerDrag(imgX: Float, imgY: Float, bmp: Bitmap) {
        val ratio = aspectRatio
        val r = cropRectImage
        val anchorX: Float; val anchorY: Float; val growLeft: Boolean; val growUp: Boolean
        when (activeHandle) {
            Handle.TL -> { anchorX = r.right; anchorY = r.bottom; growLeft = true; growUp = true }
            Handle.TR -> { anchorX = r.left; anchorY = r.bottom; growLeft = false; growUp = true }
            Handle.BL -> { anchorX = r.right; anchorY = r.top; growLeft = true; growUp = false }
            Handle.BR -> { anchorX = r.left; anchorY = r.top; growLeft = false; growUp = false }
            else -> return
        }
        val dragW = (if (growLeft) anchorX - imgX else imgX - anchorX).coerceAtLeast(0f)
        val dragH = (if (growUp) anchorY - imgY else imgY - anchorY).coerceAtLeast(0f)
        val maxW = if (growLeft) anchorX else (bmp.width.toFloat() - anchorX)
        val maxH = if (growUp) anchorY else (bmp.height.toFloat() - anchorY)
        val minW = maxOf(minSizePx.toFloat(), minSizePx.toFloat() * ratio)

        var w = minOf(dragW, dragH * ratio).coerceIn(minW, maxW)
        var h = w / ratio
        if (h > maxH) {
            h = maxH
            w = (h * ratio).coerceIn(minW, maxW)
            h = w / ratio
        }
        if (h < minSizePx) {
            h = minSizePx.toFloat()
            w = (h * ratio).coerceAtMost(maxW)
            h = w / ratio
        }

        if (growLeft) { r.left = anchorX - w; r.right = anchorX } else { r.left = anchorX; r.right = anchorX + w }
        if (growUp) { r.top = anchorY - h; r.bottom = anchorY } else { r.top = anchorY; r.bottom = anchorY + h }
    }

    private fun applySnap(r: RectF, bmp: Bitmap) {
        val snapImgX = (snapThresholdView / currentScale()) * bmp.width / imageRectF.width()
        val snapImgY = (snapThresholdView / currentScale()) * bmp.height / imageRectF.height()
        if (r.left < snapImgX) r.left = 0f
        if (r.top < snapImgY) r.top = 0f
        if (bmp.width - r.right < snapImgX) r.right = bmp.width.toFloat()
        if (bmp.height - r.bottom < snapImgY) r.bottom = bmp.height.toFloat()
    }

    private fun notifyChange() {
        onCropChange?.invoke(getCropRect())
    }
}
