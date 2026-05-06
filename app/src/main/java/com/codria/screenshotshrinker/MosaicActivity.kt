package com.codria.screenshotshrinker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.codria.screenshotshrinker.util.ImageLoader
import com.codria.screenshotshrinker.util.LongClickHelper
import com.codria.screenshotshrinker.widget.MosaicView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MosaicActivity : AppCompatActivity() {

    private lateinit var mosaicView: MosaicView
    private lateinit var countInfo: TextView
    private lateinit var buttonUndo: MaterialButton
    private lateinit var buttonRedo: MaterialButton
    private lateinit var buttonCellFiner: MaterialButton
    private lateinit var buttonCellCoarser: MaterialButton
    private lateinit var buttonAddRegion: MaterialButton
    private lateinit var buttonDeleteSelected: MaterialButton
    private lateinit var buttonDeleteAll: MaterialButton
    private lateinit var buttonSavePreset: MaterialButton
    private lateinit var slotButtons: List<MaterialButton>

    private var isSaveMode: Boolean = false
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_mosaic)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mosaicRoot)) { v, insets ->
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

        val initialRegions = readInitialRegions(intent)
        val initialCellPx = intent.getIntExtra(EXTRA_INIT_CELL_PX, DEFAULT_CELL_PX)

        mosaicView = findViewById(R.id.mosaicView)
        countInfo = findViewById(R.id.mosaicCountInfo)
        buttonUndo = findViewById(R.id.buttonUndo)
        buttonRedo = findViewById(R.id.buttonRedo)
        buttonCellFiner = findViewById(R.id.buttonCellFiner)
        buttonCellCoarser = findViewById(R.id.buttonCellCoarser)
        buttonAddRegion = findViewById(R.id.buttonAddRegion)
        buttonDeleteSelected = findViewById(R.id.buttonDeleteSelected)
        buttonDeleteAll = findViewById(R.id.buttonDeleteAll)
        buttonSavePreset = findViewById(R.id.buttonSavePreset)
        slotButtons = listOf(
            findViewById(R.id.buttonSlot1),
            findViewById(R.id.buttonSlot2),
            findViewById(R.id.buttonSlot3),
        )

        mosaicView.mosaicCellPx = initialCellPx

        mosaicView.onRegionsChange = { regions ->
            updateCountInfo(regions.size)
        }
        mosaicView.onSelectionChange = {
            updateActionButtonsEnabled()
        }
        mosaicView.onHistoryChange = {
            updateUndoRedoEnabled()
            updateActionButtonsEnabled()
        }

        buttonUndo.setOnClickListener { mosaicView.undo() }
        buttonRedo.setOnClickListener { mosaicView.redo() }

        buttonCellFiner.setOnClickListener {
            val smaller = CELL_PX_STEPS.filter { it < mosaicView.mosaicCellPx }
            if (smaller.isNotEmpty()) {
                mosaicView.mosaicCellPx = smaller.last()
                updateCellButtons()
            }
        }
        buttonCellCoarser.setOnClickListener {
            val larger = CELL_PX_STEPS.filter { it > mosaicView.mosaicCellPx }
            if (larger.isNotEmpty()) {
                mosaicView.mosaicCellPx = larger.first()
                updateCellButtons()
            }
        }

        buttonAddRegion.setOnClickListener { mosaicView.addRegion() }
        buttonDeleteSelected.setOnClickListener { mosaicView.deleteSelected() }
        buttonDeleteAll.setOnClickListener { mosaicView.deleteAll() }

        buttonSavePreset.setOnClickListener { toggleSaveMode() }
        slotButtons.forEachIndexed { idx, btn ->
            val slot = idx + 1
            LongClickHelper.attach(
                btn,
                onClick = { handleSlotClick(slot) },
                onLongClick = { clearPresetSlot(slot) },
            )
        }

        findViewById<MaterialButton>(R.id.buttonCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.buttonOk).setOnClickListener {
            val regions = mosaicView.getRegions()
            val data = Intent().apply {
                putExtra(RESULT_REGIONS, packRegions(regions))
                putExtra(RESULT_CELL_PX, mosaicView.mosaicCellPx)
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        updateCountInfo(initialRegions.size)
        updateUndoRedoEnabled()
        updateActionButtonsEnabled()
        updateCellButtons()
        refreshSlotIcons()
        loadBitmapAsync(sourceUri, initialRegions)
    }

    private fun readInitialRegions(intent: Intent): List<Rect> {
        val arr = intent.getIntArrayExtra(EXTRA_INIT_REGIONS) ?: return emptyList()
        return unpackRegions(arr)
    }

    private fun loadBitmapAsync(uri: Uri, initialRegions: List<Rect>) {
        lifecycleScope.launch {
            val bmp = runCatching {
                withContext(Dispatchers.IO) {
                    ImageLoader.loadBitmap(this@MosaicActivity, uri)
                }
            }
            bmp.onSuccess {
                mosaicView.setBitmap(it, initialRegions)
                updateCountInfo(initialRegions.size)
                updateUndoRedoEnabled()
                updateActionButtonsEnabled()
            }.onFailure { t ->
                val reason = t.message ?: t.javaClass.simpleName
                Snackbar.make(
                    findViewById(R.id.mosaicRoot),
                    getString(R.string.snackbar_save_failed, reason),
                    Snackbar.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    private fun updateCountInfo(count: Int) {
        countInfo.text = getString(R.string.mosaic_count_info, count)
    }

    private fun updateUndoRedoEnabled() {
        buttonUndo.isEnabled = mosaicView.canUndo()
        buttonRedo.isEnabled = mosaicView.canRedo()
    }

    private fun updateActionButtonsEnabled() {
        val regions = mosaicView.getRegions()
        buttonDeleteSelected.isEnabled = mosaicView.getSelectedIndex() in regions.indices
        buttonDeleteAll.isEnabled = regions.isNotEmpty()
    }

    private fun updateCellButtons() {
        buttonCellFiner.isEnabled = mosaicView.mosaicCellPx > CELL_PX_STEPS.first()
        buttonCellCoarser.isEnabled = mosaicView.mosaicCellPx < CELL_PX_STEPS.last()
    }

    // ── プリセット保存モード ─────────────────────────────────────────────

    private fun toggleSaveMode() {
        isSaveMode = !isSaveMode
        if (isSaveMode) {
            buttonSavePreset.setText(R.string.action_save_preset_active)
            showAnchoredSnackbar(R.string.snackbar_pick_save_slot)
        } else {
            buttonSavePreset.setText(R.string.action_save_preset)
        }
    }

    private fun handleSlotClick(slot: Int) {
        if (isSaveMode) {
            savePresetTo(slot)
            isSaveMode = false
            buttonSavePreset.setText(R.string.action_save_preset)
        } else {
            loadPresetFrom(slot)
        }
    }

    private fun savePresetTo(slot: Int) {
        val regions = mosaicView.getRegions()
        val encoded = regions.flatMap { listOf(it.left, it.top, it.width(), it.height()) }
            .joinToString(",")
        prefs.edit().putString(keySlot(slot), encoded).apply()
        refreshSlotIcons()
        showAnchoredSnackbar(R.string.snackbar_preset_saved)
    }

    private fun loadPresetFrom(slot: Int) {
        val encoded = prefs.getString(keySlot(slot), null) ?: run {
            showAnchoredSnackbar(R.string.snackbar_preset_empty)
            return
        }
        val regions = decodeRegions(encoded)
        mosaicView.setRegionsPreset(regions)
    }

    private fun clearPresetSlot(slot: Int) {
        if (!prefs.contains(keySlot(slot))) return
        prefs.edit().remove(keySlot(slot)).apply()
        refreshSlotIcons()
        showAnchoredSnackbarFormatted(getString(R.string.snackbar_preset_cleared, slot))
    }

    private fun decodeRegions(encoded: String): List<Rect> {
        if (encoded.isBlank()) return emptyList()
        val nums = encoded.split(",").mapNotNull { it.trim().toIntOrNull() }
        val out = mutableListOf<Rect>()
        var i = 0
        while (i + 3 < nums.size) {
            val x = nums[i]; val y = nums[i + 1]; val w = nums[i + 2]; val h = nums[i + 3]
            if (w > 0 && h > 0) out.add(Rect(x, y, x + w, y + h))
            i += 4
        }
        return out
    }

    private fun refreshSlotIcons() {
        slotButtons.forEachIndexed { idx, btn ->
            val slot = idx + 1
            btn.setIconResource(
                if (prefs.contains(keySlot(slot))) R.drawable.ic_slot_filled else R.drawable.ic_slot_empty,
            )
        }
    }

    private fun keySlot(slot: Int) = "mosaic_slot_$slot"

    // ── トースト ──────────────────────────────────────────────────────────

    private val hideTopToastRunnable = Runnable {
        val toast = findViewById<TextView>(R.id.topToast)
        toast.animate().alpha(0f).setDuration(200L).withEndAction {
            toast.visibility = android.view.View.GONE
        }.start()
    }

    private fun showAnchoredSnackbar(resId: Int) {
        val toast = findViewById<TextView>(R.id.topToast)
        toast.removeCallbacks(hideTopToastRunnable)
        toast.setText(resId)
        toast.alpha = 0f
        toast.visibility = android.view.View.VISIBLE
        toast.animate().alpha(1f).setDuration(180L).start()
        toast.postDelayed(hideTopToastRunnable, 1500L)
    }

    private fun showAnchoredSnackbarFormatted(message: String) {
        val toast = findViewById<TextView>(R.id.topToast)
        toast.removeCallbacks(hideTopToastRunnable)
        toast.text = message
        toast.alpha = 0f
        toast.visibility = android.view.View.VISIBLE
        toast.animate().alpha(1f).setDuration(180L).start()
        toast.postDelayed(hideTopToastRunnable, 1500L)
    }

    companion object {
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_INIT_REGIONS = "init_regions"
        const val EXTRA_INIT_CELL_PX = "init_cell_px"
        const val RESULT_REGIONS = "result_regions"
        const val RESULT_CELL_PX = "result_cell_px"

        const val DEFAULT_CELL_PX = 16
        val CELL_PX_STEPS = listOf(4, 8, 12, 16, 24, 32, 48, 64)

        private const val PREFS_NAME = "mosaic_presets"

        /**
         * 領域配列を flat IntArray (x, y, w, h, x, y, w, h, ...) にパック。
         */
        fun packRegions(regions: List<Rect>): IntArray {
            val arr = IntArray(regions.size * 4)
            regions.forEachIndexed { i, r ->
                arr[i * 4] = r.left
                arr[i * 4 + 1] = r.top
                arr[i * 4 + 2] = r.width()
                arr[i * 4 + 3] = r.height()
            }
            return arr
        }

        fun unpackRegions(arr: IntArray): List<Rect> {
            if (arr.size % 4 != 0) return emptyList()
            val out = mutableListOf<Rect>()
            var i = 0
            while (i + 3 < arr.size) {
                val x = arr[i]
                val y = arr[i + 1]
                val w = arr[i + 2]
                val h = arr[i + 3]
                if (w > 0 && h > 0) out.add(Rect(x, y, x + w, y + h))
                i += 4
            }
            return out
        }
    }
}
