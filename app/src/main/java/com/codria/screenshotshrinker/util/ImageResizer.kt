package com.codria.screenshotshrinker.util

import android.graphics.Bitmap
import kotlin.math.roundToInt

object ImageResizer {

    sealed interface ResizeMode {
        /** リサイズしない（そのまま）。 */
        data object Original : ResizeMode

        /** 長辺を指定px以下に縮小（拡大はしない）。 */
        data class LongEdge(val maxPx: Int) : ResizeMode

        /** 倍率指定（1〜100%）。100は等倍 = リサイズなし。 */
        data class ScalePercent(val percent: Int) : ResizeMode
    }

    /**
     * 指定モードでBitmapを縮小して返す。拡大は行わない（元サイズより大きい指定なら元のまま）。
     */
    fun resize(bitmap: Bitmap, mode: ResizeMode): Bitmap = when (mode) {
        ResizeMode.Original -> bitmap
        is ResizeMode.LongEdge -> resizeByLongEdge(bitmap, mode.maxPx)
        is ResizeMode.ScalePercent -> resizeByScale(bitmap, mode.percent)
    }

    private fun resizeByLongEdge(src: Bitmap, maxPx: Int): Bitmap {
        require(maxPx > 0) { "maxPxは1以上" }
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxPx) return src
        val scale = maxPx.toDouble() / longEdge
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun resizeByScale(src: Bitmap, percent: Int): Bitmap {
        require(percent in 1..100) { "percentは1〜100" }
        if (percent == 100) return src
        val w = (src.width * percent / 100).coerceAtLeast(1)
        val h = (src.height * percent / 100).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
