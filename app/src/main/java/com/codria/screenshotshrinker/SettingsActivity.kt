package com.codria.screenshotshrinker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.codria.screenshotshrinker.widget.PreviewView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.codria.screenshotshrinker.util.ImageConcatenator
import com.codria.screenshotshrinker.util.toFileSizeString
import com.codria.screenshotshrinker.util.ImageLoader
import com.codria.screenshotshrinker.util.ImageMosaicker
import com.codria.screenshotshrinker.util.ImageResizer
import com.codria.screenshotshrinker.util.ImageSaver
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
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
    private var cropRects: List<Rect?> = emptyList()
    private var mosaicRegionsList: List<List<Rect>> = emptyList()
    private var mosaicCellPxList: List<Int> = emptyList()
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
    private var pendingRestoreResizePct: Int? = null

    private lateinit var concatRow: LinearLayout
    private lateinit var directionRadioGroup: RadioGroup
    private lateinit var previewImage: PreviewView
    private lateinit var resizeLabelText: TextView
    private lateinit var resizeDropdown: AutoCompleteTextView
    private lateinit var customSlider: Slider
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

        val cropArr: IntArray? = intent.getIntArrayExtra(EXTRA_CROP_RECTS)
        cropRects = sourceUris.mapIndexed { i, _ ->
            if (cropArr != null && cropArr.size >= (i + 1) * 4 && cropArr[i * 4] >= 0) {
                Rect(
                    cropArr[i * 4],
                    cropArr[i * 4 + 1],
                    cropArr[i * 4] + cropArr[i * 4 + 2],
                    cropArr[i * 4 + 1] + cropArr[i * 4 + 3],
                )
            } else null
        }

        val mosaicArr: IntArray? = intent.getIntArrayExtra(EXTRA_MOSAIC_DATA)
        val (regionsList, cellPxList) = unpackMosaicData(mosaicArr, sourceUris.size)
        mosaicRegionsList = regionsList
        mosaicCellPxList = cellPxList

        concatRow = findViewById(R.id.concatRow)
        directionRadioGroup = findViewById(R.id.directionRadioGroup)
        previewImage = findViewById(R.id.previewImage)
        resizeLabelText = findViewById(R.id.resizeLabelText)
        resizeDropdown = findViewById(R.id.resizeDropdown)
        customSlider = findViewById(R.id.customSlider)
        customResolutionText = findViewById(R.id.customResolutionText)
        qualityLabelText = findViewById(R.id.qualityLabelText)
        qualitySlider = findViewById(R.id.qualitySlider)
        qualityValueText = findViewById(R.id.qualityValueText)
        saveButton = findViewById(R.id.buttonSave)
        backButton = findViewById(R.id.buttonBackToMain)

        concatRow.visibility = if (sourceUris.size >= 2) View.VISIBLE else View.GONE

        // 永続化された前回設定を復元 (リスナー設定前に値だけ流し込む)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedQuality = prefs.getInt(KEY_QUALITY, 80).coerceIn(1, 100)
        val savedDirection = prefs.getString(KEY_DIRECTION, DIRECTION_VERTICAL) ?: DIRECTION_VERTICAL
        val savedResizePct = prefs.getInt(KEY_RESIZE_PERCENT, 100).coerceIn(1, 100)
        customSelected = prefs.getBoolean(KEY_RESIZE_CUSTOM, false)
        pendingRestoreResizePct = savedResizePct

        qualitySlider.value = savedQuality.toFloat()
        qualityValueText.text = "${savedQuality}%"
        customSlider.value = savedResizePct.toFloat()

        if (savedDirection == DIRECTION_HORIZONTAL) {
            directionRadioGroup.check(R.id.directionHorizontal)
        } else {
            directionRadioGroup.check(R.id.directionVertical)
        }

        directionRadioGroup.setOnCheckedChangeListener { _, _ ->
            recomputeOutputDimsAndRender()
            updatePreview()
            scheduleEstimate()
        }

        qualitySlider.addOnChangeListener { _, value, _ ->
            qualityValueText.text = "${value.toInt()}%"
            scheduleEstimate()
        }

        customSlider.addOnChangeListener { _, _, fromUser ->
            updateCustomResolutionText()
            if (fromUser && !customSelected) {
                customSelected = true
                selectedPreset = null
                resizeDropdown.setText(getString(R.string.resize_custom), false)
            }
            updateResizeLabel()
            if (fromUser) scheduleEstimate()
        }

        resizeDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position < presetItems.size) {
                val preset = presetItems[position]
                selectedPreset = preset
                customSelected = false
                customSlider.value = preset.percent.toFloat().coerceIn(
                    customSlider.valueFrom,
                    customSlider.valueTo,
                )
            } else {
                // 「カスタム」項目選択
                selectedPreset = null
                customSelected = true
            }
            updateResizeLabel()
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

    private fun loadBitmapsAndSetup() {
        updateQualityLabel(getString(R.string.estimate_loading))
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val totalBytes = sourceUris.sumOf { queryFileSize(it) }
                    val bitmaps = sourceUris.mapIndexed { idx, uri ->
                        val full = ImageLoader.loadBitmap(this@SettingsActivity, uri)
                        val mosaiced = applyMosaicIfAny(
                        full,
                        mosaicRegionsList.getOrNull(idx).orEmpty(),
                        mosaicCellPxList.getOrElse(idx) { MosaicActivity.DEFAULT_CELL_PX },
                    )
                        applyCropIfAny(mosaiced, cropRects.getOrNull(idx))
                    }
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

    /**
     * 与えられたBitmapに対して、指定されたモザイク領域があれば適用した新Bitmapを返す。
     * regionが空なら元Bitmapをそのまま返す。元Bitmapが置き換わる場合は recycle する。
     */
    private fun applyMosaicIfAny(full: Bitmap, regions: List<Rect>, cellPx: Int = MosaicActivity.DEFAULT_CELL_PX): Bitmap {
        if (regions.isEmpty()) return full
        val out = ImageMosaicker.applyMosaic(full, regions, cellPx)
        if (out !== full) full.recycle()
        return out
    }

    /**
     * flat IntArray (cellPx_0, count_0, x, y, w, h, ..., cellPx_1, count_1, ...) を
     * Pair<List<List<Rect>>, List<Int>> にデコード。
     */
    private fun unpackMosaicData(arr: IntArray?, expectedItems: Int): Pair<List<List<Rect>>, List<Int>> {
        val defaultCellPx = MosaicActivity.DEFAULT_CELL_PX
        if (arr == null) {
            return List(expectedItems) { emptyList<Rect>() } to List(expectedItems) { defaultCellPx }
        }
        val regions = mutableListOf<List<Rect>>()
        val cellPxList = mutableListOf<Int>()
        var pos = 0
        repeat(expectedItems) {
            if (pos >= arr.size) {
                regions.add(emptyList())
                cellPxList.add(defaultCellPx)
                return@repeat
            }
            val cellPx = arr[pos++]
            cellPxList.add(cellPx)
            if (pos >= arr.size) {
                regions.add(emptyList())
                return@repeat
            }
            val count = arr[pos++]
            val regionList = mutableListOf<Rect>()
            repeat(count) {
                if (pos + 3 >= arr.size) return@repeat
                val x = arr[pos]; val y = arr[pos + 1]; val w = arr[pos + 2]; val h = arr[pos + 3]
                if (w > 0 && h > 0) regionList.add(Rect(x, y, x + w, y + h))
                pos += 4
            }
            regions.add(regionList)
        }
        return regions to cellPxList
    }

    /**
     * 与えられたBitmapに crop が指定されていればその領域を切り出した新Bitmapを返す。
     * 元Bitmapは createBitmap が新オブジェクトを返した場合のみ recycle する。
     * crop が画像範囲外を指している場合は安全な範囲にクランプ。
     */
    private fun applyCropIfAny(full: Bitmap, crop: Rect?): Bitmap {
        if (crop == null) return full
        val safe = Rect(
            crop.left.coerceIn(0, full.width),
            crop.top.coerceIn(0, full.height),
            crop.right.coerceIn(0, full.width),
            crop.bottom.coerceIn(0, full.height),
        )
        if (safe.width() <= 0 || safe.height() <= 0) return full
        if (safe.left == 0 && safe.top == 0 && safe.width() == full.width && safe.height() == full.height) {
            return full
        }
        val cropped = Bitmap.createBitmap(full, safe.left, safe.top, safe.width(), safe.height())
        if (cropped !== full) full.recycle()
        return cropped
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
            "$base (${totalSourceBytes.toFileSizeString()} → $estimateStr)"
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
        updateResizeLabel()
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
                val target = pendingRestoreResizePct
                pendingRestoreResizePct = null
                val match = if (target != null) {
                    presetItems.firstOrNull { !it.isFixedWidth && it.percent == target }
                } else null
                val first = match ?: presetItems.firstOrNull()
                selectedPreset = first
                if (first != null) {
                    resizeDropdown.setText(formatPresetLabel(first), false)
                    customSlider.value = first.percent.toFloat().coerceIn(
                        customSlider.valueFrom,
                        customSlider.valueTo,
                    )
                }
            }
        }
    }

    private fun formatPresetLabel(p: PresetData): String =
        "${p.percent}% (${p.targetW}×${p.resultH}px)"

    private fun updateCustomResolutionText() {
        if (outputW == 0) {
            customResolutionText.text = ""
            return
        }
        val pct = customSlider.value.toInt().coerceIn(1, 100)
        customResolutionText.text = "${pct}%"
    }

    private fun updateResizeLabel() {
        val base = getString(R.string.label_resize)
        if (outputW == 0 || outputH == 0) {
            resizeLabelText.text = base
            return
        }
        val (newW, newH) = when {
            customSelected -> {
                val pct = customSlider.value.toInt().coerceIn(1, 100)
                (outputW * pct / 100).coerceAtLeast(1) to (outputH * pct / 100).coerceAtLeast(1)
            }
            selectedPreset != null -> selectedPreset!!.targetW to selectedPreset!!.resultH
            else -> {
                resizeLabelText.text = base
                return
            }
        }
        resizeLabelText.text = "$base (${newW}×${newH}px)"
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
                previewImage.setImage(newPreview)
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
                updateQualityLabel(b.toFileSizeString())
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

        persistSettings()
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
            val pct = customSlider.value.toInt().coerceIn(1, 100)
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
            val pct = customSlider.value.toInt().coerceIn(1, 100)
            return (outputW * pct / 100).coerceAtLeast(1)
        }
        return selectedPreset?.targetW
    }

    private fun persistSettings() {
        val pct = if (customSelected) {
            customSlider.value.toInt().coerceIn(1, 100)
        } else {
            selectedPreset?.percent ?: 100
        }
        val direction = if (currentDirection() == ImageConcatenator.Direction.HORIZONTAL) {
            DIRECTION_HORIZONTAL
        } else {
            DIRECTION_VERTICAL
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(KEY_QUALITY, qualitySlider.value.toInt())
            .putString(KEY_DIRECTION, direction)
            .putInt(KEY_RESIZE_PERCENT, pct)
            .putBoolean(KEY_RESIZE_CUSTOM, customSelected)
            .apply()
    }

    companion object {
        const val EXTRA_IMAGE_URIS = "image_uris"
        const val EXTRA_CROP_RECTS = "crop_rects"
        const val EXTRA_MOSAIC_DATA = "mosaic_data"
        private const val DEBOUNCE_MS = 300L

        private val PERCENT_PRESETS = listOf(100, 50, 40, 30, 20)
        private val FIXED_WIDTHS = listOf(1080, 768)

        private const val PREFS_NAME = "settings_prefs"
        private const val KEY_QUALITY = "quality"
        private const val KEY_DIRECTION = "direction"
        private const val KEY_RESIZE_PERCENT = "resize_percent"
        private const val KEY_RESIZE_CUSTOM = "resize_custom"
        private const val DIRECTION_VERTICAL = "VERTICAL"
        private const val DIRECTION_HORIZONTAL = "HORIZONTAL"
    }
}
