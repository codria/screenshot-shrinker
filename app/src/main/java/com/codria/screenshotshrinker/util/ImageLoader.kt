package com.codria.screenshotshrinker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.IOException

object ImageLoader {

    /**
     * URIから画像を読み込む。inJustDecodeBoundsで先にサイズを計測し、
     * maxDim を超える場合は inSampleSize を 2 倍ずつ増やしてダウンサンプル → OOM回避。
     */
    fun loadBitmap(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("画像サイズを取得できませんでした")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IOException("Bitmapデコードに失敗しました")
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / sample > maxDim || height / sample > maxDim) {
            sample *= 2
        }
        return sample
    }
}
