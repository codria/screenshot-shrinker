package com.codria.screenshotshrinker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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

    /**
     * URIの画像サイズ（W, H）だけを取得する。inJustDecodeBoundsで本体デコードしない。
     */
    fun getDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("画像サイズを取得できませんでした")
        }
        return bounds.outWidth to bounds.outHeight
    }

    fun getDisplayNameBase(context: Context, uri: Uri): String {
        val name = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        } ?: "image"
        return name.substringBeforeLast('.')
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / sample > maxDim || height / sample > maxDim) {
            sample *= 2
        }
        return sample
    }
}
