package com.codria.screenshotshrinker

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class SettingsActivity : AppCompatActivity() {

    private data class PresetData(val percent: Int, val targetW: Int, val resultH: Int)

    private lateinit var sourceUri: Uri
    private var sourceW: Int = 0
    private var sourceH: Int = 0
    private var loadedBitmap: Bitmap? = null
    private var estimateJob: Job? = null

    private lateinit var resizeRadioGroup: RadioGroup
    private lateinit var customInputLayout: TextInputLayout
    private lateinit var customInput: TextInputEditText
    private lateinit var customResolutionText: TextView
    private lateinit var estimateText: TextView
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
        customResolutionText = findViewById(R.id.customResolutionText)
        estimateText = findViewById(R.id.estimateText)
        qualitySlider = findViewById(R.id.qualitySlider)
        qualityValueText = findViewById(R.id.qualityValueText)
        saveButton = findViewById(R.id.buttonSave)
        statusText = findViewById(R.id.textStatus)

        qualitySlider.addOnChangeListener { _, value, _ ->
            qualityValueText.text = value.toInt().toString()
            scheduleEstimate()
        }

        customInput.addTextChangedListener {
            customInputLayout.error = null
            updateCustomResolutionText()
            if (resizeRadioGroup.checkedRadioButtonId != R.id.resizeCustom) {
                // 入力したら自動でカスタムを選択（onCheckedChange内でscheduleEstimateが呼ばれる）
                resizeRadioGroup.check(R.id.resizeCustom)
            } else {
                scheduleEstimate()
            }
        }

        saveButton.setOnClickListener { onSaveClicked() }

        loadBitmapAndSetup()
    }

    override fun onDestroy() {
        super.onDestroy()
        estimateJob?.cancel()
        loadedBitmap?.recycle()
        loadedBitmap = null
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // カスタム入力欄の外をタップしたらフォーカスを外してキーボードを閉じる
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

    private fun loadBitmapAndSetup() {
        estimateText.setText(R.string.estimate_loading)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ImageLoader.loadBitmap(this@SettingsActivity, sourceUri)
                }
            }
            result.onSuccess { bmp ->
                loadedBitmap = bmp
                sourceW = bmp.width
                sourceH = bmp.height
                renderRadios()
                resizeRadioGroup.setOnCheckedChangeListener { _, _ ->
                    scheduleEstimate()
                }
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

    private fun renderRadios() {
        val percentRbs = PERCENT_PRESET_IDS.associate { (_, id) -> id to findViewById<RadioButton>(id) }
        val fixedWidthRbs = FIXED_WIDTH_PRESET_IDS.associate { (_, id) -> id to findViewById<RadioButton>(id) }
        val customRb = findViewById<RadioButton>(R.id.resizeCustom)

        val items = mutableListOf<Pair<RadioButton, PresetData>>()

        for ((pct, id) in PERCENT_PRESET_IDS) {
            val rb = percentRbs.getValue(id)
            val w = (sourceW * pct / 100).coerceAtLeast(1)
            val h = (sourceH * pct / 100).coerceAtLeast(1)
            val data = PresetData(pct, w, h)
            rb.text = buildPresetLabel(pct, w, h, bold = false)
            rb.tag = data
            items += rb to data
        }

        val standardResultWs = PERCENT_PRESET_IDS.map { (pct, _) -> sourceW * pct / 100 }.toSet()
        for ((px, id) in FIXED_WIDTH_PRESET_IDS) {
            val rb = fixedWidthRbs.getValue(id)
            if (px >= sourceW) continue
            if (px in standardResultWs) continue
            val pct = px * 100 / sourceW
            val resultH = (sourceH.toLong() * px / sourceW).toInt().coerceAtLeast(1)
            val data = PresetData(pct, px, resultH)
            rb.text = buildPresetLabel(pct, px, resultH, bold = true)
            rb.tag = data
            items += rb to data
        }

        items.sortByDescending { it.second.percent }

        resizeRadioGroup.removeAllViews()
        items.forEach { (rb, _) -> resizeRadioGroup.addView(rb) }
        resizeRadioGroup.addView(customRb)

        items.firstOrNull()?.let { resizeRadioGroup.check(it.first.id) }
    }

    private fun buildPresetLabel(percent: Int, w: Int, h: Int, bold: Boolean): CharSequence {
        val text = "$percent% (${w}×${h}px)"
        if (!bold) return text
        val sb = SpannableStringBuilder(text)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun updateCustomResolutionText() {
        val pct = customInput.text?.toString()?.trim()?.toIntOrNull()
        if (pct == null || pct !in 1..100) {
            customResolutionText.text = ""
            return
        }
        val w = (sourceW * pct / 100).coerceAtLeast(1)
        val h = (sourceH * pct / 100).coerceAtLeast(1)
        customResolutionText.text = "${w}×${h}px"
    }

    private fun scheduleEstimate() {
        estimateJob?.cancel()
        val src = loadedBitmap
        if (src == null) {
            estimateText.setText(R.string.estimate_loading)
            return
        }
        estimateText.setText(R.string.estimate_loading)
        estimateJob = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            val targetW = resolveTargetWidthQuiet()
            if (targetW == null) {
                estimateText.setText(R.string.estimate_unavailable)
                return@launch
            }
            val q = qualitySlider.value.toInt()
            val bytes = runCatching {
                withContext(Dispatchers.IO) {
                    val resized = ImageResizer.resizeToWidth(src, targetW)
                    val baos = ByteArrayOutputStream()
                    resized.compress(Bitmap.CompressFormat.JPEG, q, baos)
                    if (resized !== src) resized.recycle()
                    baos.size().toLong()
                }
            }
            bytes.onSuccess { b ->
                estimateText.text = getString(R.string.estimate_size, formatFileSize(b))
            }.onFailure {
                estimateText.setText(R.string.estimate_unavailable)
            }
        }
    }

    private fun onSaveClicked() {
        val src = loadedBitmap
        if (src == null) {
            Snackbar.make(
                findViewById(R.id.settingsRoot),
                R.string.estimate_loading,
                Snackbar.LENGTH_SHORT,
            ).show()
            return
        }
        val targetW = resolveTargetWidth() ?: return
        val quality = qualitySlider.value.toInt()

        saveButton.isEnabled = false
        statusText.setText(R.string.status_processing)

        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val resized = ImageResizer.resizeToWidth(src, targetW)
                    val baseName = ImageLoader.getDisplayNameBase(this@SettingsActivity, sourceUri)
                    val outputName = "shrunk_$baseName.jpg"
                    val saved = ImageSaver.saveJpeg(
                        this@SettingsActivity,
                        resized,
                        outputName,
                        quality,
                    )
                    if (resized !== src) resized.recycle()
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

    private fun resolveTargetWidth(): Int? {
        val checkedId = resizeRadioGroup.checkedRadioButtonId

        if (checkedId == R.id.resizeCustom) {
            val text = customInput.text?.toString()?.trim().orEmpty()
            val pct = text.toIntOrNull()
            if (pct == null || pct !in 1..100) {
                customInputLayout.error = getString(R.string.error_invalid_size)
                return null
            }
            customInputLayout.error = null
            return (sourceW * pct / 100).coerceAtLeast(1)
        }

        val rb = findViewById<RadioButton>(checkedId)
        val data = rb?.tag as? PresetData
        if (data == null) {
            Snackbar.make(
                findViewById(R.id.settingsRoot),
                R.string.error_resize_not_selected,
                Snackbar.LENGTH_SHORT,
            ).show()
            return null
        }
        return data.targetW
    }

    private fun resolveTargetWidthQuiet(): Int? {
        val checkedId = resizeRadioGroup.checkedRadioButtonId
        if (checkedId == R.id.resizeCustom) {
            val text = customInput.text?.toString()?.trim().orEmpty()
            val pct = text.toIntOrNull()
            if (pct == null || pct !in 1..100) return null
            return (sourceW * pct / 100).coerceAtLeast(1)
        }
        val rb = findViewById<RadioButton>(checkedId)
        val data = rb?.tag as? PresetData ?: return null
        return data.targetW
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        private const val DEBOUNCE_MS = 300L

        private val PERCENT_PRESET_IDS = listOf(
            100 to R.id.resize100,
            50 to R.id.resize50,
            40 to R.id.resize40,
            30 to R.id.resize30,
            20 to R.id.resize20,
        )

        private val FIXED_WIDTH_PRESET_IDS = listOf(
            1080 to R.id.resize1080,
            768 to R.id.resize768,
        )
    }
}
