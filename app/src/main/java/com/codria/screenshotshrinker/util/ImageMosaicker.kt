package com.codria.screenshotshrinker.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

object ImageMosaicker {

    /** モザイクのセル(ブロック)1辺の画素数。値が大きいほど粗い。 */
    private const val DEFAULT_CELL_PX = 16

    /**
     * 与えられた領域 (画像座標) を、それぞれモザイク化した新Bitmapを返す。
     * 元Bitmapは変更しない。領域が無い/空の場合は元Bitmapをそのまま返す。
     *
     * 各領域について:
     *  1. 元画像の該当領域を `cellPx` ピクセル粒度に縮小
     *  2. 縮小したものを元のサイズに戻して描画 (FilterBitmap=falseで隣接補間)
     */
    fun applyMosaic(
        source: Bitmap,
        regions: List<Rect>,
        cellPx: Int = DEFAULT_CELL_PX,
    ): Bitmap {
        if (regions.isEmpty()) return source

        val output = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val nearestPaint = Paint().apply { isFilterBitmap = false }
        val downscalePaint = Paint().apply { isFilterBitmap = true }

        regions.forEach { region ->
            val safe = Rect(
                region.left.coerceIn(0, source.width),
                region.top.coerceIn(0, source.height),
                region.right.coerceIn(0, source.width),
                region.bottom.coerceIn(0, source.height),
            )
            if (safe.width() <= 0 || safe.height() <= 0) return@forEach

            val cellsX = (safe.width() / cellPx).coerceAtLeast(1)
            val cellsY = (safe.height() / cellPx).coerceAtLeast(1)
            val tiny = Bitmap.createBitmap(cellsX, cellsY, Bitmap.Config.ARGB_8888)
            val tinyCanvas = Canvas(tiny)
            tinyCanvas.drawBitmap(source, safe, Rect(0, 0, cellsX, cellsY), downscalePaint)
            canvas.drawBitmap(tiny, null, safe, nearestPaint)
            tiny.recycle()
        }

        return output
    }
}
