package com.codria.screenshotshrinker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.codria.screenshotshrinker.util.ImageLoader
import com.codria.screenshotshrinker.util.PresetSlotManager
import com.codria.screenshotshrinker.util.TopToast
import com.codria.screenshotshrinker.widget.CropView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CropActivity : AppCompatActivity() {

    private lateinit var cropView: CropView
    private lateinit var sizeInfo: TextView
    private lateinit var aspectChipGroup: ChipGroup
    private lateinit var editX: EditText
    private lateinit var editY: EditText
    private lateinit var editW: EditText
    private lateinit var editH: EditText
    private lateinit var buttonUndo: MaterialButton
    private lateinit var buttonRedo: MaterialButton
    private lateinit var buttonSavePreset: MaterialButton
    private lateinit var slotButtons: List<MaterialButton>

    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    private var programmaticUpdate: Boolean = false

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val topToast: TopToast by lazy { TopToast(findViewById(R.id.topToast)) }
    private val presetSlotManager: PresetSlotManager by lazy {
        PresetSlotManager(
            saveButton = buttonSavePreset,
            slotButtons = slotButtons,
            topToast = topToast,
            isSlotFilled = { slot -> prefs.contains(keyX(slot)) },
            onSaveToSlot = { slot -> savePresetTo(slot) },
            onLoadFromSlot = { slot -> loadPresetFrom(slot) },
            onClearSlot = { slot -> clearPresetSlot(slot) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_crop)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cropRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val sourceUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_SOURCE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_SOURCE_URI)
        }
        if (sourceUri == null) {
            finish()
            return
        }

        val initialCrop = readInitialCrop(intent)

        cropView = findViewById(R.id.cropView)
        sizeInfo = findViewById(R.id.cropSizeInfo)
        aspectChipGroup = findViewById(R.id.aspectChipGroup)
        editX = findViewById(R.id.editX)
        editY = findViewById(R.id.editY)
        editW = findViewById(R.id.editW)
        editH = findViewById(R.id.editH)
        buttonUndo = findViewById(R.id.buttonUndo)
        buttonRedo = findViewById(R.id.buttonRedo)
        buttonSavePreset = findViewById(R.id.buttonSavePreset)
        slotButtons = listOf(
            findViewById(R.id.buttonSlot1),
            findViewById(R.id.buttonSlot2),
            findViewById(R.id.buttonSlot3),
        )

        cropView.onCropChange = { rect ->
            updateInfoText(rect)
            updateSpinnersFromRect(rect)
        }
        cropView.onHistoryChange = { updateUndoRedoEnabled() }

        aspectChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            if (checkedId == R.id.chipReset) {
                cropView.pushUndoFromCurrent()
                cropView.setCropRectFromValues(0, 0, sourceWidth, sourceHeight, autoCommit = false)
                cropView.setAspectRatio(0f)
                aspectChipGroup.check(R.id.chipFree)
                topToast.show(R.string.snackbar_crop_reset)
                return@setOnCheckedStateChangeListener
            }
            val ratio = when (checkedId) {
                R.id.chip1to1 -> 1f
                R.id.chip4to3 -> 4f / 3f
                R.id.chip16to9 -> 16f / 9f
                else -> 0f
            }
            cropView.setAspectRatio(ratio)
        }

        attachSpinnerListeners()

        buttonUndo.setOnClickListener { cropView.undo() }
        buttonRedo.setOnClickListener { cropView.redo() }

        presetSlotManager.attachListeners()

        findViewById<MaterialButton>(R.id.buttonCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.buttonOk).setOnClickListener {
            val rect = cropView.getCropRect()
            val isFullCrop = rect.left == 0 && rect.top == 0 &&
                rect.width() == sourceWidth && rect.height() == sourceHeight
            val data = Intent().apply {
                if (isFullCrop) {
                    putExtra(RESULT_RESET, true)
                } else {
                    putExtra(RESULT_X, rect.left)
                    putExtra(RESULT_Y, rect.top)
                    putExtra(RESULT_W, rect.width())
                    putExtra(RESULT_H, rect.height())
                }
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        updateUndoRedoEnabled()
        presetSlotManager.refreshIcons()
        loadBitmapAsync(sourceUri, initialCrop)
    }

    private fun readInitialCrop(intent: Intent): Rect? {
        val x = intent.getIntExtra(EXTRA_INIT_X, -1)
        if (x < 0) return null
        val y = intent.getIntExtra(EXTRA_INIT_Y, 0)
        val w = intent.getIntExtra(EXTRA_INIT_W, 0)
        val h = intent.getIntExtra(EXTRA_INIT_H, 0)
        if (w <= 0 || h <= 0) return null
        return Rect(x, y, x + w, y + h)
    }

    private fun loadBitmapAsync(uri: Uri, initialCrop: Rect?) {
        lifecycleScope.launch {
            val bmp = runCatching {
                withContext(Dispatchers.IO) {
                    ImageLoader.loadBitmap(this@CropActivity, uri)
                }
            }
            bmp.onSuccess {
                sourceWidth = it.width
                sourceHeight = it.height
                cropView.setBitmap(it, initialCrop)
                val rect = initialCrop ?: Rect(0, 0, sourceWidth, sourceHeight)
                updateInfoText(rect)
                updateSpinnersFromRect(rect)
                updateUndoRedoEnabled()
            }.onFailure { t ->
                val reason = t.message ?: t.javaClass.simpleName
                Snackbar.make(
                    findViewById(R.id.cropRoot),
                    getString(R.string.snackbar_save_failed, reason),
                    Snackbar.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    private fun updateInfoText(rect: Rect) {
        sizeInfo.text = getString(
            R.string.crop_size_info,
            sourceWidth, sourceHeight,
            rect.width(), rect.height(),
        )
    }

    private fun updateSpinnersFromRect(rect: Rect) {
        programmaticUpdate = true
        editX.setText(rect.left.toString())
        editY.setText(rect.top.toString())
        editW.setText(rect.width().toString())
        editH.setText(rect.height().toString())
        programmaticUpdate = false
    }

    private fun attachSpinnerListeners() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (programmaticUpdate) return
                val x = editX.text?.toString()?.toIntOrNull() ?: return
                val y = editY.text?.toString()?.toIntOrNull() ?: return
                val w = editW.text?.toString()?.toIntOrNull() ?: return
                val h = editH.text?.toString()?.toIntOrNull() ?: return
                cropView.setCropRectFromValues(x, y, w, h, autoCommit = false)
            }
        }
        editX.addTextChangedListener(watcher)
        editY.addTextChangedListener(watcher)
        editW.addTextChangedListener(watcher)
        editH.addTextChangedListener(watcher)
    }

    private fun updateUndoRedoEnabled() {
        buttonUndo.isEnabled = cropView.canUndo()
        buttonRedo.isEnabled = cropView.canRedo()
    }

    private fun savePresetTo(slot: Int) {
        val rect = cropView.getCropRect()
        prefs.edit()
            .putInt(keyX(slot), rect.left)
            .putInt(keyY(slot), rect.top)
            .putInt(keyW(slot), rect.width())
            .putInt(keyH(slot), rect.height())
            .apply()
        presetSlotManager.refreshIcons()
        topToast.show(R.string.snackbar_preset_saved)
    }

    private fun loadPresetFrom(slot: Int) {
        if (!prefs.contains(keyX(slot))) {
            topToast.show(R.string.snackbar_preset_empty)
            return
        }
        val x = prefs.getInt(keyX(slot), 0)
        val y = prefs.getInt(keyY(slot), 0)
        val w = prefs.getInt(keyW(slot), 100)
        val h = prefs.getInt(keyH(slot), 100)
        cropView.setCropRectFromValues(x, y, w, h, autoCommit = true)
    }

    private fun clearPresetSlot(slot: Int) {
        if (!prefs.contains(keyX(slot))) return
        prefs.edit()
            .remove(keyX(slot))
            .remove(keyY(slot))
            .remove(keyW(slot))
            .remove(keyH(slot))
            .apply()
        presetSlotManager.refreshIcons()
        topToast.show(getString(R.string.snackbar_preset_cleared, slot))
    }

    private fun keyX(slot: Int) = "preset_${slot}_x"
    private fun keyY(slot: Int) = "preset_${slot}_y"
    private fun keyW(slot: Int) = "preset_${slot}_w"
    private fun keyH(slot: Int) = "preset_${slot}_h"

    companion object {
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_INIT_X = "init_x"
        const val EXTRA_INIT_Y = "init_y"
        const val EXTRA_INIT_W = "init_w"
        const val EXTRA_INIT_H = "init_h"

        const val RESULT_X = "result_x"
        const val RESULT_Y = "result_y"
        const val RESULT_W = "result_w"
        const val RESULT_H = "result_h"
        const val RESULT_RESET = "result_reset"

        private const val PREFS_NAME = "crop_presets"
    }
}
