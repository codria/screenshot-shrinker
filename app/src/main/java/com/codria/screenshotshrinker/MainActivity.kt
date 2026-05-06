package com.codria.screenshotshrinker

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Size
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.codria.screenshotshrinker.util.LongClickHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private data class ImageItem(
        val uri: Uri,
        val cropRect: Rect? = null,
        val mosaicRegions: List<Rect> = emptyList(),
        val mosaicCellPx: Int = MosaicActivity.DEFAULT_CELL_PX,
    )

    private val items: MutableList<ImageItem> = mutableListOf()
    private val thumbCache: MutableMap<Uri, Bitmap> = mutableMapOf()
    private val sizeCache: MutableMap<Uri, Long> = mutableMapOf()
    private val dimensionsCache: MutableMap<Uri, Pair<Int, Int>> = mutableMapOf()
    private val thumbJobs: MutableMap<Uri, Job> = mutableMapOf()
    private var isAnimatingSwap: Boolean = false
    private var pendingCropIndex: Int = -1
    private var pendingMosaicIndex: Int = -1

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        items.addAll(uris.map { ImageItem(it) })
        renderList()
        updateNextButton()
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val idx = pendingCropIndex
        pendingCropIndex = -1
        if (result.resultCode != Activity.RESULT_OK || idx !in items.indices) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val newCrop: Rect? = if (data.getBooleanExtra(CropActivity.RESULT_RESET, false)) {
            null
        } else {
            val x = data.getIntExtra(CropActivity.RESULT_X, -1)
            val y = data.getIntExtra(CropActivity.RESULT_Y, -1)
            val w = data.getIntExtra(CropActivity.RESULT_W, -1)
            val h = data.getIntExtra(CropActivity.RESULT_H, -1)
            if (x < 0 || y < 0 || w <= 0 || h <= 0) null else Rect(x, y, x + w, y + h)
        }
        items[idx] = items[idx].copy(cropRect = newCrop)
        renderList()
    }

    private val mosaicLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val idx = pendingMosaicIndex
        pendingMosaicIndex = -1
        if (result.resultCode != Activity.RESULT_OK || idx !in items.indices) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val arr = data.getIntArrayExtra(MosaicActivity.RESULT_REGIONS) ?: IntArray(0)
        val regions = MosaicActivity.unpackRegions(arr)
        val cellPx = data.getIntExtra(MosaicActivity.RESULT_CELL_PX, MosaicActivity.DEFAULT_CELL_PX)
        items[idx] = items[idx].copy(mosaicRegions = regions, mosaicCellPx = cellPx)
        renderList()
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

        restoreState(savedInstanceState)

        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }

        findViewById<MaterialButton>(R.id.buttonPickImage).setOnClickListener {
            pickImages.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.buttonNext).setOnClickListener {
            if (items.isEmpty()) return@setOnClickListener
            val intent = Intent(this, SettingsActivity::class.java).apply {
                putParcelableArrayListExtra(
                    SettingsActivity.EXTRA_IMAGE_URIS,
                    ArrayList(items.map { it.uri }),
                )
                putExtra(SettingsActivity.EXTRA_CROP_RECTS, packCropRects())
                putExtra(SettingsActivity.EXTRA_MOSAIC_DATA, packMosaicData())
            }
            startActivity(intent)
        }

        renderList()
        updateNextButton()
    }

    private fun packCropRects(): IntArray {
        val arr = IntArray(items.size * 4)
        items.forEachIndexed { i, item ->
            val r = item.cropRect
            if (r != null) {
                arr[i * 4] = r.left
                arr[i * 4 + 1] = r.top
                arr[i * 4 + 2] = r.width()
                arr[i * 4 + 3] = r.height()
            } else {
                arr[i * 4] = -1
            }
        }
        return arr
    }

    /**
     * 全画像のモザイク情報を flat IntArray にエンコード:
     * [cellPx_0, count_0, x, y, w, h, ..., cellPx_1, count_1, ...]
     */
    private fun packMosaicData(): IntArray {
        val totalSize = items.sumOf { 2 + it.mosaicRegions.size * 4 }
        val arr = IntArray(totalSize)
        var pos = 0
        items.forEach { item ->
            val regions = item.mosaicRegions
            arr[pos++] = item.mosaicCellPx
            arr[pos++] = regions.size
            regions.forEach { r ->
                arr[pos++] = r.left
                arr[pos++] = r.top
                arr[pos++] = r.width()
                arr[pos++] = r.height()
            }
        }
        return arr
    }

    private fun unpackMosaicData(arr: IntArray, expectedItems: Int): List<Pair<List<Rect>, Int>> {
        val out = mutableListOf<Pair<List<Rect>, Int>>()
        var pos = 0
        repeat(expectedItems) {
            if (pos >= arr.size) {
                out.add(emptyList<Rect>() to MosaicActivity.DEFAULT_CELL_PX)
                return@repeat
            }
            val cellPx = arr[pos++]
            if (pos >= arr.size) {
                out.add(emptyList<Rect>() to cellPx)
                return@repeat
            }
            val count = arr[pos++]
            val regions = mutableListOf<Rect>()
            repeat(count) {
                if (pos + 3 >= arr.size) return@repeat
                val x = arr[pos]; val y = arr[pos + 1]; val w = arr[pos + 2]; val h = arr[pos + 3]
                if (w > 0 && h > 0) regions.add(Rect(x, y, x + w, y + h))
                pos += 4
            }
            out.add(regions to cellPx)
        }
        return out
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        val uris: ArrayList<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            savedInstanceState.getParcelableArrayList(STATE_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            savedInstanceState.getParcelableArrayList(STATE_URIS)
        } ?: return
        val crops = savedInstanceState.getIntArray(STATE_CROPS)
        val mosaicData = savedInstanceState.getIntArray(STATE_MOSAIC_DATA)
        val mosaicItems = mosaicData?.let { unpackMosaicData(it, uris.size) } ?: emptyList()
        uris.forEachIndexed { i, uri ->
            val rect = crops?.takeIf { it.size >= (i + 1) * 4 && it[i * 4] >= 0 }?.let {
                Rect(it[i * 4], it[i * 4 + 1], it[i * 4] + it[i * 4 + 2], it[i * 4 + 1] + it[i * 4 + 3])
            }
            val (regions, cellPx) = mosaicItems.getOrNull(i) ?: (emptyList<Rect>() to MosaicActivity.DEFAULT_CELL_PX)
            items.add(ImageItem(uri, rect, regions, cellPx))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleShareIntent(intent)) {
            renderList()
            updateNextButton()
        }
    }

    private fun handleShareIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false
        val type = intent.type ?: return false
        if (!type.startsWith("image/")) return false

        val sharedUris: List<Uri> = when (action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list?.toList() ?: emptyList()
            }
            else -> emptyList()
        }
        if (sharedUris.isEmpty()) return false
        items.addAll(sharedUris.map { ImageItem(it) })
        // 同じIntentで再処理しないようクリア
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(STATE_URIS, ArrayList(items.map { it.uri }))
        outState.putIntArray(STATE_CROPS, packCropRects())
        outState.putIntArray(STATE_MOSAIC_DATA, packMosaicData())
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbJobs.values.forEach { it.cancel() }
        thumbJobs.clear()
        thumbCache.values.forEach { it.recycle() }
        thumbCache.clear()
    }

    private fun renderList() {
        val container = findViewById<LinearLayout>(R.id.imageList)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        items.forEachIndexed { index, item ->
            val uri = item.uri
            val row = inflater.inflate(R.layout.item_image_row, container, false)
            val thumbView = row.findViewById<ImageView>(R.id.itemThumbnail)
            val nameView = row.findViewById<TextView>(R.id.itemFileName)
            val resolutionView = row.findViewById<TextView>(R.id.itemResolution)
            val sizeView = row.findViewById<TextView>(R.id.itemFileSize)

            val (name, size) = queryNameAndSize(uri)
            nameView.text = name
            sizeView.text = formatFileSize(size)

            val cachedDims = dimensionsCache[uri]
            val cropSuffix = item.cropRect?.let { "  (${it.width()}×${it.height()})" }.orEmpty()
            if (cachedDims != null) {
                resolutionView.text = "${cachedDims.first}×${cachedDims.second}px$cropSuffix"
            } else {
                resolutionView.text = cropSuffix
                loadDimensionsAsync(uri, resolutionView, item.cropRect)
            }

            val cached = thumbCache[uri]
            if (cached != null && !cached.isRecycled) {
                thumbView.setImageBitmap(cached)
            } else {
                thumbView.setImageDrawable(null)
                loadThumbnailAsync(uri, thumbView)
            }

            row.findViewById<MaterialButton>(R.id.itemMoveUp).apply {
                isEnabled = index > 0
                setOnClickListener { swap(index, index - 1) }
            }
            row.findViewById<MaterialButton>(R.id.itemMoveDown).apply {
                isEnabled = index < items.size - 1
                setOnClickListener { swap(index, index + 1) }
            }
            row.findViewById<MaterialButton>(R.id.itemRemove).setOnClickListener {
                items.removeAt(index)
                if (items.none { it.uri == uri }) {
                    thumbJobs.remove(uri)?.cancel()
                    thumbCache.remove(uri)?.recycle()
                    sizeCache.remove(uri)
                    dimensionsCache.remove(uri)
                }
                renderList()
                updateNextButton()
            }
            val cropButton = row.findViewById<MaterialButton>(R.id.itemCrop)
            applyAppliedTint(cropButton, item.cropRect != null)
            LongClickHelper.attach(
                cropButton,
                onClick = {
                    pendingCropIndex = index
                    val intent = Intent(this@MainActivity, CropActivity::class.java).apply {
                        putExtra(CropActivity.EXTRA_SOURCE_URI, uri)
                        item.cropRect?.let {
                            putExtra(CropActivity.EXTRA_INIT_X, it.left)
                            putExtra(CropActivity.EXTRA_INIT_Y, it.top)
                            putExtra(CropActivity.EXTRA_INIT_W, it.width())
                            putExtra(CropActivity.EXTRA_INIT_H, it.height())
                        }
                    }
                    cropLauncher.launch(intent)
                },
                onLongClick = {
                    if (item.cropRect != null) {
                        items[index] = items[index].copy(cropRect = null)
                        renderList()
                    }
                },
            )

            val mosaicButton = row.findViewById<MaterialButton>(R.id.itemMosaic)
            applyAppliedTint(mosaicButton, item.mosaicRegions.isNotEmpty())
            LongClickHelper.attach(
                mosaicButton,
                onClick = {
                    pendingMosaicIndex = index
                    val intent = Intent(this@MainActivity, MosaicActivity::class.java).apply {
                        putExtra(MosaicActivity.EXTRA_SOURCE_URI, uri)
                        putExtra(MosaicActivity.EXTRA_INIT_CELL_PX, item.mosaicCellPx)
                        if (item.mosaicRegions.isNotEmpty()) {
                            putExtra(
                                MosaicActivity.EXTRA_INIT_REGIONS,
                                MosaicActivity.packRegions(item.mosaicRegions),
                            )
                        }
                    }
                    mosaicLauncher.launch(intent)
                },
                onLongClick = {
                    if (item.mosaicRegions.isNotEmpty()) {
                        items[index] = items[index].copy(mosaicRegions = emptyList())
                        renderList()
                    }
                },
            )
            container.addView(row)
        }
    }

    /**
     * 適用済み(Crop/Mosaic領域あり)の場合は背景を colorPrimary で塗りつぶし、アイコンを白で反転表示。
     * 未適用はインフレート時のデフォルト (Outlined) のまま。
     */
    private fun applyAppliedTint(button: MaterialButton, applied: Boolean) {
        if (!applied) return
        val primary = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary) ?: return
        button.backgroundTintList = ColorStateList.valueOf(primary)
        button.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        button.strokeWidth = 0
    }

    private fun resolveThemeColor(attr: Int): Int? {
        val tv = TypedValue()
        if (!theme.resolveAttribute(attr, tv, true)) return null
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
        } else {
            tv.data
        }
    }

    private fun loadDimensionsAsync(uri: Uri, target: TextView, cropRect: Rect?) {
        lifecycleScope.launch {
            val dims = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri).use { stream ->
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        opts.outWidth to opts.outHeight
                    } else null
                }.getOrNull()
            }
            if (dims != null) {
                dimensionsCache[uri] = dims
                if (items.any { it.uri == uri }) {
                    val cropSuffix = cropRect?.let { "  (${it.width()}×${it.height()})" }.orEmpty()
                    target.text = "${dims.first}×${dims.second}px$cropSuffix"
                }
            }
        }
    }

    private fun loadThumbnailAsync(uri: Uri, target: ImageView) {
        thumbJobs[uri]?.cancel()
        thumbJobs[uri] = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(uri, Size(240, 240), null)
                    } else {
                        null
                    }
                }.getOrNull()
            }
            if (bmp != null) {
                thumbCache[uri] = bmp
                if (items.any { it.uri == uri }) {
                    target.setImageBitmap(bmp)
                }
            }
            thumbJobs.remove(uri)
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        val cachedSize = sizeCache[uri]
        val cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
        var name: String? = null
        var size: Long = cachedSize ?: 0L
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = c.getString(nameIdx)
                if (sizeIdx >= 0 && cachedSize == null) {
                    size = c.getLong(sizeIdx)
                    sizeCache[uri] = size
                }
            }
        }
        return (name ?: uri.lastPathSegment ?: "image") to size
    }

    private fun swap(i: Int, j: Int) {
        if (isAnimatingSwap) return
        if (i !in items.indices || j !in items.indices) return

        val container = findViewById<LinearLayout>(R.id.imageList)
        val oldViewI = container.getChildAt(i)
        val oldViewJ = container.getChildAt(j)
        if (oldViewI == null || oldViewJ == null) {
            doSwap(i, j)
            renderList()
            return
        }
        val deltaY = (oldViewJ.top - oldViewI.top).toFloat()

        doSwap(i, j)
        renderList()

        val newViewAtI = container.getChildAt(i)
        val newViewAtJ = container.getChildAt(j)
        if (newViewAtI == null || newViewAtJ == null) return

        newViewAtI.translationY = deltaY
        newViewAtJ.translationY = -deltaY

        isAnimatingSwap = true
        newViewAtI.animate()
            .translationY(0f)
            .setDuration(SWAP_DURATION_MS)
            .start()
        newViewAtJ.animate()
            .translationY(0f)
            .setDuration(SWAP_DURATION_MS)
            .withEndAction { isAnimatingSwap = false }
            .start()
    }

    private fun doSwap(i: Int, j: Int) {
        val tmp = items[i]
        items[i] = items[j]
        items[j] = tmp
    }

    private fun updateNextButton() {
        findViewById<MaterialButton>(R.id.buttonNext).isEnabled = items.isNotEmpty()
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        bytes > 0 -> "$bytes B"
        else -> ""
    }

    companion object {
        private const val STATE_URIS = "uris"
        private const val STATE_CROPS = "crops"
        private const val STATE_MOSAIC_DATA = "mosaic_data"
        private const val SWAP_DURATION_MS = 220L
    }
}
