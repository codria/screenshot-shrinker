package com.codria.screenshotshrinker.util

import android.graphics.Bitmap

object ImageResizer {

    /**
     * Bitmapを指定の幅に縮小する。高さはアスペクト比から自動計算。
     * 拡大は行わない（targetWidth >= bitmap.width なら元のBitmapをそのまま返す）。
     */
    fun resizeToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        require(targetWidth > 0) { "targetWidthは1以上" }
        if (targetWidth >= bitmap.width) return bitmap
        val targetHeight = (bitmap.height.toLong() * targetWidth / bitmap.width)
            .toInt()
            .coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
