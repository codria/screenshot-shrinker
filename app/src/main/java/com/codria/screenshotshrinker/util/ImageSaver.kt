package com.codria.screenshotshrinker.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException

object ImageSaver {

    data class SaveResult(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
    )

    /**
     * BitmapをJPGとしてMediaStoreへ保存する（Pictures/ScreenshotShrinker/ 配下）。
     * Scoped Storage前提のためAPI 29+でのみ動作する（minSdk 26時点でフォールバックは未実装）。
     */
    fun saveJpeg(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        quality: Int = 80,
    ): SaveResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val pendingValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/ScreenshotShrinker",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, pendingValues)
            ?: throw IOException("MediaStoreへのレコード作成に失敗しました")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    throw IOException("JPEG圧縮に失敗しました")
                }
            } ?: throw IOException("OutputStreamを取得できませんでした")

            val finalValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, finalValues, null, null)
        } catch (t: Throwable) {
            // IS_PENDING=1 のまま残骸を残さないよう削除を試みる
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        return SaveResult(uri = uri, displayName = displayName, sizeBytes = size)
    }
}
