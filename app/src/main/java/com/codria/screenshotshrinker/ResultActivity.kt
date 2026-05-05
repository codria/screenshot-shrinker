package com.codria.screenshotshrinker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

class ResultActivity : AppCompatActivity() {

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

        findViewById<ImageView>(R.id.resultImage).setImageURI(uri)
        findViewById<TextView>(R.id.resultFileName).text = displayName
        findViewById<TextView>(R.id.resultMeta).text =
            "${width}×${height}px ・ ${formatFileSize(sizeBytes)}"

        findViewById<MaterialButton>(R.id.buttonOpenInGallery).setOnClickListener {
            openInGallery(uri)
        }
        findViewById<MaterialButton>(R.id.buttonShare).setOnClickListener {
            share(uri)
        }
        findViewById<MaterialButton>(R.id.buttonBack).setOnClickListener {
            // 戻る: 同じ元画像で SettingsActivity に戻る (バックスタックを1つpop)
            finish()
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

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    companion object {
        const val EXTRA_RESULT_URI = "result_uri"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_SIZE_BYTES = "size_bytes"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
    }
}
