package com.codria.screenshotshrinker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.codria.screenshotshrinker.util.ImageLoader
import com.codria.screenshotshrinker.util.toFileSizeString
import com.codria.screenshotshrinker.widget.PreviewView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResultActivity : AppCompatActivity() {

    private var resultBitmap: Bitmap? = null

    override fun onDestroy() {
        super.onDestroy()
        resultBitmap?.recycle()
        resultBitmap = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_result)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.resultRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_URI)
        }
        if (uri == null) {
            finish()
            return
        }

        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
        val sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)
        val width = intent.getIntExtra(EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 0)

        val previewView = findViewById<PreviewView>(R.id.resultImage)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { ImageLoader.loadBitmap(this@ResultActivity, uri) }.getOrNull()
            }
            if (bmp != null) {
                resultBitmap = bmp
                previewView.setImage(bmp)
            }
        }
        findViewById<TextView>(R.id.resultFileName).text = displayName
        findViewById<TextView>(R.id.resultMeta).text =
            "${width}×${height}px ・ ${sizeBytes.toFileSizeString()}"

        findViewById<MaterialButton>(R.id.buttonOpenInGallery).setOnClickListener {
            openInGallery(uri)
        }
        findViewById<MaterialButton>(R.id.buttonShare).setOnClickListener {
            share(uri)
        }
        findViewById<MaterialButton>(R.id.buttonBack).setOnClickListener {
            confirmDiscard(uri, displayName)
        }
        findViewById<MaterialButton>(R.id.buttonStartOver).setOnClickListener {
            // 最初から: MainActivity まで戻る (Settings/Result はスタックから除去)
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun confirmDiscard(uri: Uri, displayName: String) {
        val nameForMessage = displayName.ifEmpty { uri.lastPathSegment ?: "?" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_discard_title)
            .setMessage(getString(R.string.dialog_discard_message, nameForMessage))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                performDiscard(uri, displayName)
            }
            .show()
    }

    /**
     * 削除前に対象URIが本アプリが直前に保存した画像であることを厳重に検証する。
     * 検証OKならContentResolver経由で1件削除し、画面を閉じる。
     * 違うレコードを誤って消さないため、検証失敗時は何もしない。
     */
    private fun performDiscard(uri: Uri, expectedDisplayName: String) {
        if (!isOwnSavedImage(uri, expectedDisplayName)) {
            Snackbar.make(
                findViewById(R.id.resultRoot),
                R.string.snackbar_discard_unverified,
                Snackbar.LENGTH_LONG,
            ).show()
            return
        }
        val rows = runCatching { contentResolver.delete(uri, null, null) }.getOrDefault(0)
        if (rows >= 1) {
            finish()
        } else {
            Snackbar.make(
                findViewById(R.id.resultRoot),
                R.string.snackbar_discard_failed,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * 与えられたURIが本アプリ保存の Pictures/ScreenshotShrinker/ 配下のJPEGで、
     * かつ display_name が想定と一致するかを確認する。
     * 1つでも条件を満たさなければ false を返す。
     */
    private fun isOwnSavedImage(uri: Uri, expectedDisplayName: String): Boolean {
        if (uri.scheme != "content") return false
        if (uri.authority?.startsWith("media") != true) return false
        if (expectedDisplayName.isBlank()) return false

        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.RELATIVE_PATH,
                ),
                null,
                null,
                null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use false
                val name = c.getString(0) ?: return@use false
                val mime = c.getString(1) ?: return@use false
                val path = c.getString(2) ?: ""
                name == expectedDisplayName &&
                    mime == "image/jpeg" &&
                    path.contains("ScreenshotShrinker")
            } ?: false
        }.getOrDefault(false)
    }

    private fun openInGallery(uri: Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(viewIntent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(
                findViewById(R.id.resultRoot),
                R.string.error_no_handler,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun share(uri: Uri) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(sendIntent, getString(R.string.action_share)))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(
                findViewById(R.id.resultRoot),
                R.string.error_no_handler,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    companion object {
        const val EXTRA_RESULT_URI = "result_uri"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_SIZE_BYTES = "size_bytes"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
    }
}
