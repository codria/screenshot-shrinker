package com.codria.screenshotshrinker.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.sqrt

/**
 * 画像表示 + 複数のモザイク領域指定の自前View。
 *
 * - 各領域は独立した矩形 (画像座標) で保持。
 * - 1つの領域を選択中: 4角+4辺ハンドルでリサイズ、内側ドラッグで移動。
 * - 非選択領域はタップで選択切替。
 * - 何も無い場所のドラッグは画像のパン。
 * - ピンチで拡大、領域追加/削除/Undo/Redoは外部から呼び出し。
 *
 * 領域内には実際にモザイク化したプレビューを描画する。
 */
class MosaicView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private val imageRectF = RectF()
    private val regions: MutableList<RectF> = mutableListOf()
    private var selectedIndex: Int = -1

    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val tmpPts = FloatArray(2)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val nearestPaint = Paint().apply { isFilterBitmap = false }
    private val downscalePaint = Paint().apply { isFilterBitmap = true }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val selectedOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt() // amber 500
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val activeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
        style = Paint.Style.FILL
    }

    private val handleRadius: Float get() = 7f * resources.displayMetrics.density
    private val handleHitRadius: Float get() = 28f * resources.displayMetrics.density
    private val minSizePx: Int = 32
    private val minScale: Float = 0.9f
    private val maxScale: Float = 5f
    var mosaicCellPx: Int = 16
        set(value) {
            field = value.coerceIn(MIN_CELL_PX, MAX_CELL_PX)
            invalidate()
        }

    private enum class Handle { NONE, TL, TR, BL, BR, T, B, L, R, INTERIOR, PAN }
    private var activeHandle: Handle = Handle.NONE
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var dragOffsetImgX: Float = 0f
    private var dragOffsetImgY: Float = 0f
    private var pendingUndoPushed: Boolean = false

    private val undoStack: ArrayDeque<List<Rect>> = ArrayDeque()
    private val redoStack: ArrayDeque<List<Rect>> = ArrayDeque()
    private val maxHistory = 30

    var onRegionsChange: ((List<Rect>) -> Unit)? = null
    var onSelectionChange: ((Int) -> Unit)? = null
    var onHistoryChange: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = currentScale()
            val raw = current * detector.scaleFactor
            val target = raw.coerceIn(minScale, maxScale)
            val effective = target / current
            viewMatrix.postScale(effective, effective, detector.focusX, detector.focusY)
            constrainPan()
            invalidate()
            return true
        }
    })

    fun setBitmap(bmp: Bitmap, initialRegions: List<Rect>) {
        bitmap = bmp
        regions.clear()
        regions.addAll(initialRegions.map {
            RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        })
        selectedIndex = if (regions.isEmpty()) -1 else regions.size - 1
        undoStack.clear()
        redoStack.clear()
        viewMatrix.reset()
        recomputeImageRect()
        invalidate()
        notifyRegionsChange()
        onSelectionChange?.invoke(selectedIndex)
        onHistoryChange?.invoke()
    }

    fun getRegions(): List<Rect> = regions.map {
        Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
    }

    fun getSelectedIndex(): Int = selectedIndex

    /**
     * 領域を新規追加する。サイズは画像短辺の30% (最小値以上)、位置は現在の表示中心。
     * 追加直後の領域を選択状態にする。
     */
    fun addRegion() {
        val bmp = bitmap ?: return
        val baseSize = (minOf(bmp.width, bmp.height) * 0.3f).coerceAtLeast(minSizePx.toFloat())
        val half = baseSize / 2
        val cx = viewToImageX(width / 2f).coerceIn(half, bmp.width - half)
        val cy = viewToImageY(height / 2f).coerceIn(half, bmp.height - half)
        pushUndo()
        regions.add(RectF(cx - half, cy - half, cx + half, cy + half))
        selectedIndex = regions.size - 1
        invalidate()
        notifyRegionsChange()
        onSelectionChange?.invoke(selectedIndex)
    }

    fun deleteSelected() {
        val idx = selectedIndex
        if (idx !in regions.indices) return
        pushUndo()
        regions.removeAt(idx)
        selectedIndex = if (regions.isEmpty()) -1 else (idx - 1).coerceAtLeast(0)
        invalidate()
        notifyRegionsChange()
        onSelectionChange?.invoke(selectedIndex)
    }

    fun deleteAll() {
        if (regions.isEmpty()) return
        pushUndo()
        regions.clear()
        selectedIndex = -1
        invalidate()
        notifyRegionsChange()
        onSelectionChange?.invoke(selectedIndex)
    }

    /**
     * 現在状態を Undo に積んで領域一覧を差し替える。プリセット読み込みに使用。
     */
    fun setRegionsPreset(newRegions: List<Rect>) {
        pushUndo()
        regions.clear()
        regions.addAll(newRegions.map {
            RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        })
        selectedIndex = if (regions.isEmpty()) -1 else regions.size - 1
        invalidate()
        notifyRegionsChange()
        onSelectionChange?.invoke(selectedIndex)
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(getRegions())
        if (redoStack.size > maxHistory) redoStack.removeFirst()
        val prev = undoStack.removeLast()
        applySnapshot(prev)
        onHistoryChange?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(getRegions())
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        val next = redoStack.removeLast()
        applySnapshot(next)
        onHistoryChange?.invoke()
    }

    private fun applySnapshot(snapshot: List<Rect>) {
        regions.clear()
        regions.addAll(snapshot.map {
            RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        })
        selectedIndex = if (regions.isEmpty()) -1 else selectedIndex.coerceIn(0, regions.size - 1)
        invalidate()
        notifyRegionsChange()
        onSelectionChange?.invoke(selectedIndex)
    }

    private fun pushUndo() {
        undoStack.addLast(getRegions())
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        onHistoryChange?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeImageRect()
    }

    private fun recomputeImageRect() {
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

    private fun currentScale(): Float {
        val pts = FloatArray(9)
        viewMatrix.getValues(pts)
        return pts[Matrix.MSCALE_X]
    }

    private fun constrainPan() {
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

    private companion object {
        const val VISIBLE_MIN_FRACTION: Float = 0.25f
        const val MIN_CELL_PX: Int = 4
        const val MAX_CELL_PX: Int = 64
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (imageRectF.isEmpty) return

        // bitmap と各領域のモザイクプレビューは viewMatrix 内で描画 (zoom時に画像と一緒に拡大)
        val sc = canvas.save()
        canvas.concat(viewMatrix)
        canvas.drawBitmap(bmp, null, imageRectF, bitmapPaint)
        regions.forEach { drawMosaicPreview(canvas, bmp, it) }
        canvas.restoreToCount(sc)

        // 枠線とハンドルは画面座標で描画 (zoomしても太さが変わらない)
        regions.forEachIndexed { idx, regionImage ->
            val cv = imageRectToScreen(regionImage)
            if (idx == selectedIndex) {
                canvas.drawRect(cv, selectedOutlinePaint)
                drawHandles(canvas, cv)
            } else {
                canvas.drawRect(cv, outlinePaint)
            }
        }
    }

    /**
     * 指定領域 (画像座標) にモザイク化したピクセルを描画する。
     * `canvas.concat(viewMatrix)` 済み前提なので、imageRectF と同じスケールで dst を計算。
     */
    private fun drawMosaicPreview(canvas: Canvas, bmp: Bitmap, regionImage: RectF) {
        val scale = imageRectF.width() / bmp.width
        val safeLeft = regionImage.left.coerceIn(0f, bmp.width.toFloat()).toInt()
        val safeTop = regionImage.top.coerceIn(0f, bmp.height.toFloat()).toInt()
        val safeRight = regionImage.right.coerceIn(0f, bmp.width.toFloat()).toInt()
        val safeBottom = regionImage.bottom.coerceIn(0f, bmp.height.toFloat()).toInt()
        val srcW = safeRight - safeLeft
        val srcH = safeBottom - safeTop
        if (srcW <= 0 || srcH <= 0) return

        val cellsX = (srcW / mosaicCellPx).coerceAtLeast(1)
        val cellsY = (srcH / mosaicCellPx).coerceAtLeast(1)
        val tiny = Bitmap.createBitmap(cellsX, cellsY, Bitmap.Config.ARGB_8888)
        Canvas(tiny).drawBitmap(
            bmp,
            Rect(safeLeft, safeTop, safeRight, safeBottom),
            Rect(0, 0, cellsX, cellsY),
            downscalePaint,
        )

        val dst = RectF(
            imageRectF.left + safeLeft * scale,
            imageRectF.top + safeTop * scale,
            imageRectF.left + safeRight * scale,
            imageRectF.top + safeBottom * scale,
        )
        canvas.drawBitmap(tiny, null, dst, nearestPaint)
        tiny.recycle()
    }

    private fun drawHandles(canvas: Canvas, cv: RectF) {
        val r = handleRadius
        fun paintFor(h: Handle): Paint = if (isHandleAffected(h)) activeHandlePaint else handlePaint
        canvas.drawCircle(cv.left, cv.top, r, paintFor(Handle.TL))
        canvas.drawCircle(cv.right, cv.top, r, paintFor(Handle.TR))
        canvas.drawCircle(cv.left, cv.bottom, r, paintFor(Handle.BL))
        canvas.drawCircle(cv.right, cv.bottom, r, paintFor(Handle.BR))
        canvas.drawCircle((cv.left + cv.right) / 2, cv.top, r, paintFor(Handle.T))
        canvas.drawCircle((cv.left + cv.right) / 2, cv.bottom, r, paintFor(Handle.B))
        canvas.drawCircle(cv.left, (cv.top + cv.bottom) / 2, r, paintFor(Handle.L))
        canvas.drawCircle(cv.right, (cv.top + cv.bottom) / 2, r, paintFor(Handle.R))
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

    private fun imageRectToScreen(src: RectF): RectF {
        val bmp = bitmap ?: return RectF()
        val scale = imageRectF.width() / bmp.width
        val cv = RectF(
            imageRectF.left + src.left * scale,
            imageRectF.top + src.top * scale,
            imageRectF.left + src.right * scale,
            imageRectF.top + src.bottom * scale,
        )
        val arr = floatArrayOf(cv.left, cv.top, cv.right, cv.bottom)
        viewMatrix.mapPoints(arr)
        return RectF(arr[0], arr[1], arr[2], arr[3])
    }

    private fun viewToImageX(screenX: Float): Float {
        val bmp = bitmap ?: return 0f
        val scale = imageRectF.width() / bmp.width
        viewMatrix.invert(inverseMatrix)
        tmpPts[0] = screenX
        tmpPts[1] = 0f
        inverseMatrix.mapPoints(tmpPts)
        return ((tmpPts[0] - imageRectF.left) / scale).coerceIn(0f, bmp.width.toFloat())
    }

    private fun viewToImageY(screenY: Float): Float {
        val bmp = bitmap ?: return 0f
        val scale = imageRectF.height() / bmp.height
        viewMatrix.invert(inverseMatrix)
        tmpPts[0] = 0f
        tmpPts[1] = screenY
        inverseMatrix.mapPoints(tmpPts)
        return ((tmpPts[1] - imageRectF.top) / scale).coerceIn(0f, bmp.height.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            activeHandle = Handle.NONE
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                pendingUndoPushed = false
                handleDown(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                handleMove(event.x, event.y)
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = Handle.NONE
                invalidate()
            }
        }
        return true
    }

    private fun handleDown(x: Float, y: Float) {
        // 1. 選択中の領域のハンドル/内部にヒットするか
        if (selectedIndex in regions.indices) {
            val cv = imageRectToScreen(regions[selectedIndex])
            val hit = detectHandle(x, y, cv)
            if (hit != Handle.NONE) {
                if (hit == Handle.INTERIOR) {
                    activeHandle = Handle.INTERIOR
                    val r = regions[selectedIndex]
                    dragOffsetImgX = viewToImageX(x) - r.left
                    dragOffsetImgY = viewToImageY(y) - r.top
                } else {
                    activeHandle = hit
                }
                pushUndoOnce()
                return
            }
        }
        // 2. 他の領域 (上から下へ、後から追加されたものを優先) の内側にヒットするか
        for (i in regions.indices.reversed()) {
            if (i == selectedIndex) continue
            val cv = imageRectToScreen(regions[i])
            if (cv.contains(x, y)) {
                selectedIndex = i
                onSelectionChange?.invoke(selectedIndex)
                activeHandle = Handle.INTERIOR
                pushUndoOnce()
                val r = regions[i]
                dragOffsetImgX = viewToImageX(x) - r.left
                dragOffsetImgY = viewToImageY(y) - r.top
                invalidate()
                return
            }
        }
        // 3. どの領域にも当たらず → パン
        activeHandle = Handle.PAN
    }

    private fun pushUndoOnce() {
        if (!pendingUndoPushed) {
            pushUndo()
            pendingUndoPushed = true
        }
    }

    private fun handleMove(x: Float, y: Float) {
        when (activeHandle) {
            Handle.PAN -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                viewMatrix.postTranslate(dx, dy)
                constrainPan()
                invalidate()
            }
            Handle.NONE -> { /* nothing */ }
            else -> {
                if (selectedIndex !in regions.indices) return
                val r = regions[selectedIndex]
                val bmp = bitmap ?: return
                val imgX = viewToImageX(x)
                val imgY = viewToImageY(y)
                if (activeHandle == Handle.INTERIOR) {
                    val curW = r.width()
                    val curH = r.height()
                    val newLeft = (imgX - dragOffsetImgX).coerceIn(0f, bmp.width - curW)
                    val newTop = (imgY - dragOffsetImgY).coerceIn(0f, bmp.height - curH)
                    r.set(newLeft, newTop, newLeft + curW, newTop + curH)
                } else {
                    // Apply handle movement (cross-over allowed at this stage)
                    when (activeHandle) {
                        Handle.TL -> { r.left = imgX; r.top = imgY }
                        Handle.TR -> { r.right = imgX; r.top = imgY }
                        Handle.BL -> { r.left = imgX; r.bottom = imgY }
                        Handle.BR -> { r.right = imgX; r.bottom = imgY }
                        Handle.T  -> r.top = imgY
                        Handle.B  -> r.bottom = imgY
                        Handle.L  -> r.left = imgX
                        Handle.R  -> r.right = imgX
                        else -> return
                    }
                    // Cross-over: normalize inverted rect, then switch to the new handle
                    val crossX = r.left > r.right
                    val crossY = r.top > r.bottom
                    if (crossX) { val tmp = r.left; r.left = r.right; r.right = tmp }
                    if (crossY) { val tmp = r.top; r.top = r.bottom; r.bottom = tmp }
                    if (crossX || crossY) {
                        activeHandle = when (activeHandle) {
                            Handle.TL -> if (crossX && crossY) Handle.BR else if (crossX) Handle.TR else Handle.BL
                            Handle.TR -> if (crossX && crossY) Handle.BL else if (crossX) Handle.TL else Handle.BR
                            Handle.BL -> if (crossX && crossY) Handle.TR else if (crossX) Handle.BR else Handle.TL
                            Handle.BR -> if (crossX && crossY) Handle.TL else if (crossX) Handle.BL else Handle.TR
                            Handle.T  -> Handle.B
                            Handle.B  -> Handle.T
                            Handle.L  -> Handle.R
                            Handle.R  -> Handle.L
                            else      -> activeHandle
                        }
                    }
                    // Clamp to image bounds (tiny rects are allowed during drag)
                    r.left   = r.left.coerceIn(0f, bmp.width.toFloat())
                    r.top    = r.top.coerceIn(0f, bmp.height.toFloat())
                    r.right  = r.right.coerceIn(0f, bmp.width.toFloat())
                    r.bottom = r.bottom.coerceIn(0f, bmp.height.toFloat())
                }
                invalidate()
                notifyRegionsChange()
            }
        }
    }

    private fun detectHandle(x: Float, y: Float, cv: RectF): Handle {
        val r = handleHitRadius
        if (dist(x, y, cv.left, cv.top) < r) return Handle.TL
        if (dist(x, y, cv.right, cv.top) < r) return Handle.TR
        if (dist(x, y, cv.left, cv.bottom) < r) return Handle.BL
        if (dist(x, y, cv.right, cv.bottom) < r) return Handle.BR
        val cx = (cv.left + cv.right) / 2
        val cy = (cv.top + cv.bottom) / 2
        if (dist(x, y, cx, cv.top) < r) return Handle.T
        if (dist(x, y, cx, cv.bottom) < r) return Handle.B
        if (dist(x, y, cv.left, cy) < r) return Handle.L
        if (dist(x, y, cv.right, cy) < r) return Handle.R
        if (cv.contains(x, y)) return Handle.INTERIOR
        return Handle.NONE
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun notifyRegionsChange() {
        onRegionsChange?.invoke(getRegions())
    }
}
