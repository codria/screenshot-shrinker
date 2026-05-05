package com.codria.screenshotshrinker

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.codria.screenshotshrinker.util.ImageLoader
import com.codria.screenshotshrinker.util.ImageResizer
import com.codria.screenshotshrinker.util.ImageSaver
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var sourceUri: Uri
    private lateinit var resizeRadioGroup: RadioGroup
    private lateinit var customInputLayout: TextInputLayout
    private lateinit var customInput: TextInputEditText
    private lateinit var qualitySlider: Slider
    private lateinit var qualityValueText: TextView
    private lateinit var saveButton: MaterialButton
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val incomingUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_IMAGE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_IMAGE_URI)
        }
        if (incomingUri == null) {
            finish()
            return
        }
        sourceUri = incomingUri

        resizeRadioGroup = findViewById(R.id.resizeRadioGroup)
        customInputLayout = findViewById(R.id.customInputLayout)
        customInput = findViewById(R.id.customInput)
        qualitySlider = findViewById(R.id.qualitySlider)
        qualityValueText = findViewById(R.id.qualityValueText)
        saveButton = findViewById(R.id.buttonSave)
        statusText = findViewById(R.id.textStatus)

        resizeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            customInputLayout.visibility =
                if (checkedId == R.id.resizeCustom) View.VISIBLE else View.GONE
        }

        qualitySlider.addOnChangeListener { _, value, _ ->
            qualityValueText.text = value.toInt().toString()
        }

        saveButton.setOnClickListener { onSaveClicked() }
    }

    private fun onSaveClicked() {
        val resizeMode = buildResizeMode() ?: return
        val quality = qualitySlider.value.toInt()

        saveButton.isEnabled = false
        statusText.setText(R.string.status_processing)

        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val srcBitmap = ImageLoader.loadBitmap(this@SettingsActivity, sourceUri)
                    val resizedBitmap = ImageResizer.resize(srcBitmap, resizeMode)
                    if (resizedBitmap !== srcBitmap) {
                        srcBitmap.recycle()
                    }
                    val baseName = ImageLoader.getDisplayNameBase(this@SettingsActivity, sourceUri)
                    val outputName = "shrunk_$baseName.jpg"
                    val saved = ImageSaver.saveJpeg(
                        this@SettingsActivity,
                        resizedBitmap,
                        outputName,
                        quality,
                    )
                    resizedBitmap.recycle()
                    saved
                }
            }

            outcome.onSuccess { saved ->
                val msg = getString(
                    R.string.snackbar_save_success,
                    saved.displayName,
                    formatFileSize(saved.sizeBytes),
                )
                statusText.text = msg
                Snackbar.make(findViewById(R.id.settingsRoot), msg, Snackbar.LENGTH_LONG).show()
            }.onFailure { t ->
                val reason = t.message ?: t.javaClass.simpleName
                val msg = getString(R.string.snackbar_save_failed, reason)
                statusText.text = msg
                Snackbar.make(findViewById(R.id.settingsRoot), msg, Snackbar.LENGTH_LONG).show()
            }

            saveButton.isEnabled = true
        }
    }

    private fun buildResizeMode(): ImageResizer.ResizeMode? = when (resizeRadioGroup.checkedRadioButtonId) {
        R.id.resize1080 -> ImageResizer.ResizeMode.LongEdge(1080)
        R.id.resize720 -> ImageResizer.ResizeMode.LongEdge(720)
        R.id.resize50 -> ImageResizer.ResizeMode.ScalePercent(50)
        R.id.resize25 -> ImageResizer.ResizeMode.ScalePercent(25)
        R.id.resizeOriginal -> ImageResizer.ResizeMode.Original
        R.id.resizeCustom -> {
            val text = customInput.text?.toString()?.trim().orEmpty()
            val px = text.toIntOrNull()
            if (px == null || px <= 0) {
                customInputLayout.error = getString(R.string.error_invalid_size)
                null
            } else {
                customInputLayout.error = null
                ImageResizer.ResizeMode.LongEdge(px)
            }
        }
        else -> {
            Snackbar.make(
                findViewById(R.id.settingsRoot),
                R.string.error_resize_not_selected,
                Snackbar.LENGTH_SHORT,
            ).show()
            null
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
    }
}
