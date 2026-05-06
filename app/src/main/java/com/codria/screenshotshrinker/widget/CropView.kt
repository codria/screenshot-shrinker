package com.codria.screenshotshrinker.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.sqrt

/**
 * 画像表示 + 矩形クロップ領域指定の自前View。
 *
 * - ピンチで拡大 (1〜5倍)、ドラッグで移動 (拡大時のみ)。拡大状態は ACTION_UP 後も維持。
 * - 4角ハンドル + 4辺ハンドル(アスペクト比フリー時のみ有効)
 * - 内部ドラッグで全体移動
 * - 画像端へのスナップ (アスペクト比フリー時)
 * - アスペクト比ロック中の角ドラッグは反対角を支点にratio保持
 * - Undo/Redo (drag開始 / aspect変更 / プリセット読込 で履歴push)
 *
 * 座標系:
 * - cropRectImage: 元画像の座標系で保持 (zoom/panに影響されない)
 * - imageRectF: 画像の "natural" 表示位置 (fitCenter、zoom無し時の bitmap が描画される矩形)
 * - viewMatrix: pinch/pan による追加変換。drawBitmap時に concat、handle描画時には mapPoints で位置のみ変換
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private val imageRectF = RectF()
    private val cropRectImage = RectF()
    private var aspectRatio: Float = 0f

    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val tmpPts = FloatArray(2)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    /**
     * 外側マスク: グレー2色 (#555555 / #999999) の市松模様。
     * 中央値 (#777777) から上下に等距離なので主張が穏やか。
     * 黒画像でも白画像でも必ずタイルとのコントラストが付く。
     */
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
        color = 0xFFFFC107.toInt() // Material amber 500
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val handleRadius: Float get() = 7f * resources.displayMetrics.density
    private val handleHitRadius: Float get() = 28f * resources.displayMetrics.density
    private val snapThresholdView: Float get() = 8f * resources.displayMetrics.density
    private val minSizePx: Int = 32
    private val minScale: Float = 0.9f
    private val maxScale: Float = 5f

    private enum class Handle { NONE, TL, TR, BL, BR, T, B, L, R, INTERIOR, PAN }
    private var activeHandle: Handle = Handle.NONE
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f

    private val undoStack: ArrayDeque<Rect> = ArrayDeque()
    private val redoStack: ArrayDeque<Rect> = ArrayDeque()
    private val maxHistory = 30

    var onCropChange: ((Rect) -> Unit)? = null
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

    /**
     * パン後の位置をクランプ。等倍/拡大に関わらず統一ルール:
     * 軸ごとに「viewと画像の小さい方の 25%」が必ず重なって残るようにする。
     * - 等倍 余白あり軸: 画像幅の 25% が view 内に残る (= 画像の 75% を端から外せる)
     * - 等倍 ピッタリ軸 / 拡大時: view 幅の 25% が画像で覆われている (= view の 75% を空ける)
     */
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
        /** 等倍パン時の画像残存最小比率 (= 1 - 「ずらせる比率」) */
        const val VISIBLE_MIN_FRACTION: Float = 0.25f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (imageRectF.isEmpty) return

        // bitmap と外側マスク (fill) は transform 内で描画 (zoom時に画像と一緒に拡大される)
        val sc = canvas.save()
        canvas.concat(viewMatrix)

        canvas.drawBitmap(bmp, null, imageRectF, bitmapPaint)
        val cvPre = imageRectToView(cropRectImage)

        canvas.drawRect(imageRectF.left, imageRectF.top, imageRectF.right, cvPre.top, overlayPaint)
        canvas.drawRect(imageRectF.left, cvPre.bottom, imageRectF.right, imageRectF.bottom, overlayPaint)
        canvas.drawRect(imageRectF.left, cvPre.top, cvPre.left, cvPre.bottom, overlayPaint)
        canvas.drawRect(cvPre.right, cvPre.top, imageRectF.right, cvPre.bottom, overlayPaint)

        canvas.restoreToCount(sc)

        // grid/handles/inside-tint は画面座標で描画 (zoomしても太さが変わらない)
        // 境界線は外側マスク(80%黒)と内側tint(8%白)のコントラストで表現するため省略
        val cvScreen = cropRectInScreen()
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

    /**
     * activeHandle が動かす辺の上に存在するハンドル全て(対角の辺端ハンドル含む)を判定。
     * 例: TL drag中 → 上辺 (TL,T,TR) + 左辺 (TL,L,BL) = TL,T,TR,L,BL を highlight。
     *     T drag中 → 上辺 (TL,T,TR) のみ highlight。
     */
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

    private fun imageRectToView(src: RectF): RectF {
        val bmp = bitmap ?: return RectF()
        val scale = imageRectF.width() / bmp.width
        return RectF(
            imageRectF.left + src.left * scale,
            imageRectF.top + src.top * scale,
            imageRectF.left + src.right * scale,
            imageRectF.top + src.bottom * scale,
        )
    }

    /**
     * 画面座標 → 元画像座標。viewMatrix の inverse を経由。
     */
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

    /**
     * cv (pre-transform座標) を viewMatrix で変換した RectF を返す。ハンドル検出に使用。
     */
    private fun cropRectInScreen(): RectF {
        val cv = imageRectToView(cropRectImage)
        val arr = floatArrayOf(cv.left, cv.top, cv.right, cv.bottom)
        viewMatrix.mapPoints(arr)
        return RectF(arr[0], arr[1], arr[2], arr[3])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        // ピンチを先に処理 (常に通す: 空エリアでのピンチも検出するため)
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            activeHandle = Handle.NONE
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cvScreen = cropRectInScreen()
                val hit = detectHandle(event.x, event.y, cvScreen)
                activeHandle = when {
                    hit != Handle.NONE -> {
                        pushUndoFromCurrent()
                        hit
                    }
                    // ハンドル外タップ: 等倍でもパン可能 (constrainPanで適切な範囲に制約される)
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
                activeHandle = Handle.NONE
                invalidate()
            }
        }
        // 常にtrue: 後続イベント (POINTER_DOWN/MOVE 等) を受け続けるため、空エリアでのpinchも有効
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

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun handleDrag(viewX: Float, viewY: Float) {
        val bmp = bitmap ?: return
        val imgX = viewToImageX(viewX)
        val imgY = viewToImageY(viewY)
        val r = cropRectImage

        if (activeHandle == Handle.INTERIOR) {
            val dxImg = imgX - viewToImageX(lastTouchX)
            val dyImg = imgY - viewToImageY(lastTouchY)
            val curW = r.width()
            val curH = r.height()
            val newLeft = (r.left + dxImg).coerceIn(0f, bmp.width - curW)
            val newTop = (r.top + dyImg).coerceIn(0f, bmp.height - curH)
            r.set(newLeft, newTop, newLeft + curW, newTop + curH)
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
        // スナップ閾値を 画面ピクセル × inverse zoom で算出
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
