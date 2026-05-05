package com.codria.screenshotshrinker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.codria.screenshotshrinker.util.ImageConcatenator
import com.codria.screenshotshrinker.util.ImageLoader
import com.codria.screenshotshrinker.util.ImageResizer
import com.codria.screenshotshrinker.util.ImageSaver
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private data class PresetData(
        val percent: Int,
        val targetW: Int,
        val resultH: Int,
        val isFixedWidth: Boolean,
    )

    private lateinit var sourceUris: List<Uri>
    private var loadedBitmaps: List<Bitmap>? = null
    private var previewBitmap: Bitmap? = null
    private var totalSourceBytes: Long = 0L
    private var outputW: Int = 0
    private var outputH: Int = 0
    private var estimateJob: Job? = null
    private var previewJob: Job? = null

    private var presetItems: List<PresetData> = emptyList()
    private var selectedPreset: PresetData? = null
    private var customSelected: Boolean = false

    private lateinit var concatRow: LinearLayout
    private lateinit var directionRadioGroup: RadioGroup
    private lateinit var previewImage: ImageView
    private lateinit var resizeDropdown: AutoCompleteTextView
    private lateinit var customInputLayout: TextInputLayout
    private lateinit var customInput: TextInputEditText
    private lateinit var customResolutionText: TextView
    private lateinit var qualityLabelText: TextView
    private lateinit var qualitySlider: Slider
    private lateinit var qualityValueText: TextView
    private lateinit var saveButton: MaterialButton
    private lateinit var backButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val incomingUris: ArrayList<Uri>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(EXTRA_IMAGE_URIS, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(EXTRA_IMAGE_URIS)
            }
        if (incomingUris.isNullOrEmpty()) {
            finish()
            return
        }
        sourceUris = incomingUris.toList()

        concatRow = findViewById(R.id.concatRow)
        directionRadioGroup = findViewById(R.id.directionRadioGroup)
        previewImage = findViewById(R.id.previewImage)
        resizeDropdown = findViewById(R.id.resizeDropdown)
        customInputLayout = findViewById(R.id.customInputLayout)
        customInput = findViewById(R.id.customInput)
        customResolutionText = findViewById(R.id.customResolutionText)
        qualityLabelText = findViewById(R.id.qualityLabelText)
        qualitySlider = findViewById(R.id.qualitySlider)
        qualityValueText = findViewById(R.id.qualityValueText)
        saveButton = findViewById(R.id.buttonSave)
        backButton = findViewById(R.id.buttonBackToMain)

        concatRow.visibility = if (sourceUris.size >= 2) View.VISIBLE else View.GONE

        directionRadioGroup.setOnCheckedChangeListener { _, _ ->
            recomputeOutputDimsAndRender()
            updatePreview()
            scheduleEstimate()
        }

        qualitySlider.addOnChangeListener { _, value, _ ->
            qualityValueText.text = "${value.toInt()}%"
            scheduleEstimate()
        }

        customInput.addTextChangedListener {
            customInputLayout.error = null
            updateCustomResolutionText()
            if (!customSelected) {
                customSelected = true
                selectedPreset = null
                resizeDropdown.setText(getString(R.string.resize_custom), false)
            }
            scheduleEstimate()
        }

        resizeDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position < presetItems.size) {
                selectedPreset = presetItems[position]
                customSelected = false
                customInputLayout.error = null
            } else {
                // 「カスタム」項目選択
                selectedPreset = null
                customSelected = true
                // フォーカスとIMEを起動して入力を促す
                customInput.requestFocus()
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(customInput, InputMethodManager.SHOW_IMPLICIT)
            }
            scheduleEstimate()
        }

        saveButton.setOnClickListener { onSaveClicked() }
        backButton.setOnClickListener { finish() }

        loadBitmapsAndSetup()
    }

    override fun onDestroy() {
        super.onDestroy()
        estimateJob?.cancel()
        previewJob?.cancel()
        recyclePreview()
        loadedBitmaps?.forEach { it.recycle() }
        loadedBitmaps = null
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && customInput.hasFocus()) {
            val rect = Rect()
            customInputLayout.getGlobalVisibleRect(rect)
            if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                customInput.clearFocus()
                getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(customInput.windowToken, 0)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun loadBitmapsAndSetup() {
        updateQualityLabel(getString(R.string.estimate_loading))
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val totalBytes = sourceUris.sumOf { queryFileSize(it) }
                    val bitmaps = sourceUris.map { ImageLoader.loadBitmap(this@SettingsActivity, it) }
                    bitmaps to totalBytes
                }
            }
            result.onSuccess { (bitmaps, totalBytes) ->
                loadedBitmaps = bitmaps
                totalSourceBytes = totalBytes
                recomputeOutputDimsAndRender()
                updatePreview()
                scheduleEstimate()
            }.onFailure { t ->
                val reason = t.message ?: t.javaClass.simpleName
                Snackbar.make(
                    findViewById(R.id.settingsRoot),
                    getString(R.string.snackbar_save_failed, reason),
                    Snackbar.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    private fun queryFileSize(uri: Uri): Long {
        return contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) cursor.getLong(idx) else 0L
            } else 0L
        } ?: 0L
    }

    private fun updateQualityLabel(estimateStr: String) {
        val base = getString(R.string.label_quality)
        qualityLabelText.text = if (totalSourceBytes > 0) {
            "$base (${formatFileSize(totalSourceBytes)} → $estimateStr)"
        } else {
            base
        }
    }

    private fun currentDirection(): ImageConcatenator.Direction =
        if (directionRadioGroup.checkedRadioButtonId == R.id.directionHorizontal) {
            ImageConcatenator.Direction.HORIZONTAL
        } else {
            ImageConcatenator.Direction.VERTICAL
        }

    private fun recomputeOutputDimsAndRender() {
        val bitmaps = loadedBitmaps ?: return
        val (w, h) = ImageConcatenator.computeOutputSize(
            bitmaps.map { it.width to it.height },
            currentDirection(),
        )
        outputW = w
        outputH = h
        rebuildPresetList()
        renderResizeDropdown()
        updateCustomResolutionText()
    }

    private fun rebuildPresetList() {
        val items = mutableListOf<PresetData>()
        for (pct in PERCENT_PRESETS) {
            val w = (outputW * pct / 100).coerceAtLeast(1)
            val h = (outputH * pct / 100).coerceAtLeast(1)
            items += PresetData(pct, w, h, isFixedWidth = false)
        }
        val standardResultWs = PERCENT_PRESETS.map { outputW * it / 100 }.toSet()
        for (px in FIXED_WIDTHS) {
            if (px >= outputW) continue
            if (px in standardResultWs) continue
            val pct = px * 100 / outputW
            val resultH = (outputH.toLong() * px / outputW).toInt().coerceAtLeast(1)
            items += PresetData(pct, px, resultH, isFixedWidth = true)
        }
        items.sortByDescending { it.percent }
        presetItems = items
    }

    private fun renderResizeDropdown() {
        val labels = presetItems.map { formatPresetLabel(it) } + getString(R.string.resize_custom)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        resizeDropdown.setAdapter(adapter)

        // 既存選択を維持。一致するものがなければ先頭(100%)を選択
        when {
            customSelected -> {
                resizeDropdown.setText(getString(R.string.resize_custom), false)
            }
            selectedPreset != null -> {
                val match = presetItems.firstOrNull { it.percent == selectedPreset!!.percent && it.isFixedWidth == selectedPreset!!.isFixedWidth }
                if (match != null) {
                    selectedPreset = match
                    resizeDropdown.setText(formatPresetLabel(match), false)
                } else {
                    val first = presetItems.firstOrNull()
                    selectedPreset = first
                    if (first != null) resizeDropdown.setText(formatPresetLabel(first), false)
                }
            }
            else -> {
                val first = presetItems.firstOrNull()
                selectedPreset = first
                if (first != null) resizeDropdown.setText(formatPresetLabel(first), false)
            }
        }
    }

    private fun formatPresetLabel(p: PresetData): String =
        "${p.percent}% (${p.targetW}×${p.resultH}px)"

    private fun updateCustomResolutionText() {
        val pct = customInput.text?.toString()?.trim()?.toIntOrNull()
        if (pct == null || pct !in 1..100 || outputW == 0) {
            customResolutionText.text = ""
            return
        }
        val w = (outputW * pct / 100).coerceAtLeast(1)
        val h = (outputH * pct / 100).coerceAtLeast(1)
        customResolutionText.text = "${w}×${h}px"
    }

    private fun updatePreview() {
        val sources = loadedBitmaps ?: return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            val newPreview = withContext(Dispatchers.IO) {
                runCatching {
                    val combined = ImageConcatenator.concat(sources, currentDirection())
                    val previewMaxW = 800
                    val resized = if (combined.width > previewMaxW) {
                        ImageResizer.resizeToWidth(combined, previewMaxW)
                    } else {
                        combined
                    }
                    if (resized !== combined && combined !in sources) combined.recycle()
                    resized
                }.getOrNull()
            }
            if (newPreview != null) {
                val old = previewBitmap
                previewBitmap = newPreview
                previewImage.setImageBitmap(newPreview)
                if (old != null && old !== newPreview && old !in sources) {
                    old.recycle()
                }
            }
        }
    }

    private fun recyclePreview() {
        val sources = loadedBitmaps ?: emptyList()
        val pb = previewBitmap
        if (pb != null && pb !in sources) pb.recycle()
        previewBitmap = null
    }

    private fun scheduleEstimate() {
        estimateJob?.cancel()
        val sources = loadedBitmaps
        if (sources == null) {
            updateQualityLabel(getString(R.string.estimate_loading))
            return
        }
        updateQualityLabel(getString(R.string.estimate_loading))
        estimateJob = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            val targetW = resolveTargetWidthQuiet()
            if (targetW == null) {
                updateQualityLabel(getString(R.string.estimate_unavailable))
                return@launch
            }
            val q = qualitySlider.value.toInt()
            val direction = currentDirection()
            val bytes = runCatching {
                withContext(Dispatchers.IO) {
                    val combined = ImageConcatenator.concat(sources, direction)
                    val resized = ImageResizer.resizeToWidth(combined, targetW)
                    val baos = ByteArrayOutputStream()
                    resized.compress(Bitmap.CompressFormat.JPEG, q, baos)
                    if (resized !== combined) resized.recycle()
                    if (combined !in sources) combined.recycle()
                    baos.size().toLong()
                }
            }
            bytes.onSuccess { b ->
                updateQualityLabel(formatFileSize(b))
            }.onFailure {
                updateQualityLabel(getString(R.string.estimate_unavailable))
            }
        }
    }

    private fun onSaveClicked() {
        val sources = loadedBitmaps
        if (sources == null) {
            Snackbar.make(
                findViewById(R.id.settingsRoot),
                R.string.estimate_loading,
                Snackbar.LENGTH_SHORT,
            ).show()
            return
        }
        val targetW = resolveTargetWidth() ?: return
        val quality = qualitySlider.value.toInt()
        val direction = currentDirection()
        val isMulti = sources.size >= 2

        saveButton.isEnabled = false

        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val combined = ImageConcatenator.concat(sources, direction)
                    val resized = ImageResizer.resizeToWidth(combined, targetW)
                    val timestamp = SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
                    val outputName = if (isMulti) {
                        "concat_$timestamp.jpg"
                    } else {
                        "shrunk_$timestamp.jpg"
                    }
                    val saved = ImageSaver.saveJpeg(
                        this@SettingsActivity,
                        resized,
                        outputName,
                        quality,
                    )
                    if (resized !== combined) resized.recycle()
                    if (combined !in sources) combined.recycle()
                    saved
                }
            }

            outcome.onSuccess { saved ->
                val intent = Intent(this@SettingsActivity, ResultActivity::class.java).apply {
                    putExtra(ResultActivity.EXTRA_RESULT_URI, saved.uri)
                    putExtra(ResultActivity.EXTRA_DISPLAY_NAME, saved.displayName)
                    putExtra(ResultActivity.EXTRA_SIZE_BYTES, saved.sizeBytes)
                    putExtra(ResultActivity.EXTRA_WIDTH, saved.width)
                    putExtra(ResultActivity.EXTRA_HEIGHT, saved.height)
                }
                startActivity(intent)
            }.onFailure { t ->
                val reason = t.message ?: t.javaClass.simpleName
                val msg = getString(R.string.snackbar_save_failed, reason)
                Snackbar.make(findViewById(R.id.settingsRoot), msg, Snackbar.LENGTH_LONG).show()
            }

            saveButton.isEnabled = true
        }
    }

    private fun resolveTargetWidth(): Int? {
        if (customSelected) {
            val text = customInput.text?.toString()?.trim().orEmpty()
            val pct = text.toIntOrNull()
            if (pct == null || pct !in 1..100) {
                customInputLayout.error = getString(R.string.error_invalid_size)
                return null
            }
            customInputLayout.error = null
            return (outputW * pct / 100).coerceAtLeast(1)
        }
        val preset = selectedPreset
        if (preset == null) {
            Snackbar.make(
                findViewById(R.id.settingsRoot),
                R.string.error_resize_not_selected,
                Snackbar.LENGTH_SHORT,
            ).show()
            return null
        }
        return preset.targetW
    }

    private fun resolveTargetWidthQuiet(): Int? {
        if (customSelected) {
            val text = customInput.text?.toString()?.trim().orEmpty()
            val pct = text.toIntOrNull()
            if (pct == null || pct !in 1..100) return null
            return (outputW * pct / 100).coerceAtLeast(1)
        }
        return selectedPreset?.targetW
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    companion object {
        const val EXTRA_IMAGE_URIS = "image_uris"
        private const val DEBOUNCE_MS = 300L

        private val PERCENT_PRESETS = listOf(100, 50, 40, 30, 20)
        private val FIXED_WIDTHS = listOf(1080, 768)
    }
}
