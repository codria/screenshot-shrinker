package com.codria.screenshotshrinker

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.codria.screenshotshrinker.util.ImageLoader
import com.codria.screenshotshrinker.util.ImageSaver
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var pickButton: Button

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        processSelectedImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusText = findViewById(R.id.textStatus)
        pickButton = findViewById(R.id.buttonPickImage)
        pickButton.setOnClickListener { pickImage.launch("image/*") }
    }

    private fun processSelectedImage(uri: Uri) {
        pickButton.isEnabled = false
        statusText.setText(R.string.status_processing)

        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val bitmap = ImageLoader.loadBitmap(this@MainActivity, uri)
                    val baseName = ImageLoader.getDisplayNameBase(this@MainActivity, uri)
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(Date())
                    val outputName = "shrunk_${baseName}_$timestamp.jpg"
                    ImageSaver.saveJpeg(this@MainActivity, bitmap, outputName)
                }
            }

            outcome.onSuccess { saved ->
                val msg = getString(
                    R.string.snackbar_save_success,
                    saved.displayName,
                    formatFileSize(saved.sizeBytes),
                )
                statusText.text = msg
                Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_LONG).show()
            }.onFailure { t ->
                val reason = t.message ?: t.javaClass.simpleName
                val msg = getString(R.string.snackbar_save_failed, reason)
                statusText.text = msg
                Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_LONG).show()
            }

            pickButton.isEnabled = true
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
