package com.codria.screenshotshrinker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Size
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val uriList: MutableList<Uri> = mutableListOf()
    private val thumbCache: MutableMap<Uri, Bitmap> = mutableMapOf()
    private val sizeCache: MutableMap<Uri, Long> = mutableMapOf()
    private val dimensionsCache: MutableMap<Uri, Pair<Int, Int>> = mutableMapOf()
    private val thumbJobs: MutableMap<Uri, Job> = mutableMapOf()
    private var isAnimatingSwap: Boolean = false

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        uriList.addAll(uris)
        renderList()
        updateNextButton()
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

        val restored: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            savedInstanceState?.getParcelableArrayList(STATE_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            savedInstanceState?.getParcelableArrayList(STATE_URIS)
        }
        if (restored != null) uriList.addAll(restored)

        findViewById<MaterialButton>(R.id.buttonPickImage).setOnClickListener {
            pickImages.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.buttonNext).setOnClickListener {
            if (uriList.isEmpty()) return@setOnClickListener
            val intent = Intent(this, SettingsActivity::class.java).apply {
                putParcelableArrayListExtra(
                    SettingsActivity.EXTRA_IMAGE_URIS,
                    ArrayList(uriList),
                )
            }
            startActivity(intent)
        }

        renderList()
        updateNextButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(STATE_URIS, ArrayList(uriList))
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
        uriList.forEachIndexed { index, uri ->
            val row = inflater.inflate(R.layout.item_image_row, container, false)
            val thumbView = row.findViewById<ImageView>(R.id.itemThumbnail)
            val nameView = row.findViewById<TextView>(R.id.itemFileName)
            val resolutionView = row.findViewById<TextView>(R.id.itemResolution)
            val sizeView = row.findViewById<TextView>(R.id.itemFileSize)

            val (name, size) = queryNameAndSize(uri)
            nameView.text = name
            sizeView.text = formatFileSize(size)

            val cachedDims = dimensionsCache[uri]
            if (cachedDims != null) {
                resolutionView.text = "${cachedDims.first}×${cachedDims.second}px"
            } else {
                resolutionView.text = ""
                loadDimensionsAsync(uri, resolutionView)
            }

            // サムネイル: キャッシュにあれば即時、なければ非同期ロード
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
                isEnabled = index < uriList.size - 1
                setOnClickListener { swap(index, index + 1) }
            }
            row.findViewById<MaterialButton>(R.id.itemRemove).setOnClickListener {
                uriList.removeAt(index)
                if (uri !in uriList) {
                    thumbJobs.remove(uri)?.cancel()
                    thumbCache.remove(uri)?.recycle()
                    sizeCache.remove(uri)
                    dimensionsCache.remove(uri)
                }
                renderList()
                updateNextButton()
            }
            row.findViewById<MaterialButton>(R.id.itemCrop).setOnClickListener {
                // Phase 6 で実装予定。現状はスペース確認のためのプレースホルダ
                Snackbar.make(
                    findViewById(R.id.main),
                    R.string.snackbar_crop_not_implemented,
                    Snackbar.LENGTH_SHORT,
                ).show()
            }
            container.addView(row)
        }
    }

    private fun loadDimensionsAsync(uri: Uri, target: TextView) {
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
                if (uri in uriList) {
                    target.text = "${dims.first}×${dims.second}px"
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
                // 表示時点でリスト変更されてる可能性があるので、念のためURIで判定
                if (target.tag == uri || uri in uriList) {
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
        if (i !in uriList.indices || j !in uriList.indices) return

        val container = findViewById<LinearLayout>(R.id.imageList)
        val oldViewI = container.getChildAt(i)
        val oldViewJ = container.getChildAt(j)
        if (oldViewI == null || oldViewJ == null) {
            doSwap(i, j)
            renderList()
            return
        }
        // 旧位置の差分をキャプチャ (rerender後は old views の top 情報が消える)
        val deltaY = (oldViewJ.top - oldViewI.top).toFloat()

        // 先にデータswap → rerender (新Viewはswap後の正しいenabled/click handlerを持つ)
        doSwap(i, j)
        renderList()

        val newViewAtI = container.getChildAt(i)
        val newViewAtJ = container.getChildAt(j)
        if (newViewAtI == null || newViewAtJ == null) return

        // 新Viewを「旧位置から始まる」よう translateY で逆オフセット → 0 へアニメ
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
        val tmp = uriList[i]
        uriList[i] = uriList[j]
        uriList[j] = tmp
    }

    private fun updateNextButton() {
        findViewById<MaterialButton>(R.id.buttonNext).isEnabled = uriList.isNotEmpty()
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        bytes > 0 -> "$bytes B"
        else -> ""
    }

    companion object {
        private const val STATE_URIS = "uris"
        private const val SWAP_DURATION_MS = 220L
    }
}
