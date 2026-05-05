package com.codria.screenshotshrinker.util

import android.graphics.Bitmap
import android.graphics.Canvas

object ImageConcatenator {

    enum class Direction { VERTICAL, HORIZONTAL }

    /**
     * 複数のBitmapを指定方向で連結する。
     * - 縦連結: 各画像の幅を最も狭い幅にダウンスケール → 上から下に並べる
     * - 横連結: 各画像の高さを最も低い高さにダウンスケール → 左から右に並べる
     * - 拡大はしない（短辺合わせ）
     * - 中間で生成したスケール済みBitmapは関数内で recycle する
     * - 入力Bitmapは触らない（呼び出し側で recycle 管理）
     */
    fun concat(bitmaps: List<Bitmap>, direction: Direction): Bitmap {
        require(bitmaps.isNotEmpty()) { "bitmapsが空" }
        if (bitmaps.size == 1) return bitmaps[0]
        return when (direction) {
            Direction.VERTICAL -> concatVertical(bitmaps)
            Direction.HORIZONTAL -> concatHorizontal(bitmaps)
        }
    }

    /**
     * 連結後の出力サイズを計算する（実際の連結は行わない）。
     * UIでラベル表示や予測サイズ計算に使う。
     */
    fun computeOutputSize(
        sources: List<Pair<Int, Int>>,
        direction: Direction,
    ): Pair<Int, Int> {
        require(sources.isNotEmpty())
        if (sources.size == 1) return sources[0]
        return when (direction) {
            Direction.VERTICAL -> {
                val w = sources.minOf { it.first }
                val h = sources.sumOf { (it.second.toLong() * w / it.first).toInt().coerceAtLeast(1) }
                w to h
            }
            Direction.HORIZONTAL -> {
                val h = sources.minOf { it.second }
                val w = sources.sumOf { (it.first.toLong() * h / it.second).toInt().coerceAtLeast(1) }
                w to h
            }
        }
    }

    private fun concatVertical(bitmaps: List<Bitmap>): Bitmap {
        val targetW = bitmaps.minOf { it.width }
        val scaled = bitmaps.map { bmp ->
            if (bmp.width == targetW) bmp
            else {
                val newH = (bmp.height.toLong() * targetW / bmp.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, targetW, newH, true)
            }
        }
        val totalH = scaled.sumOf { it.height }
        val result = Bitmap.createBitmap(targetW, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        var y = 0
        for (bmp in scaled) {
            canvas.drawBitmap(bmp, 0f, y.toFloat(), null)
            y += bmp.height
        }
        scaled.forEachIndexed { i, b ->
            if (b !== bitmaps[i]) b.recycle()
        }
        return result
    }

    private fun concatHorizontal(bitmaps: List<Bitmap>): Bitmap {
        val targetH = bitmaps.minOf { it.height }
        val scaled = bitmaps.map { bmp ->
            if (bmp.height == targetH) bmp
            else {
                val newW = (bmp.width.toLong() * targetH / bmp.height).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, newW, targetH, true)
            }
        }
        val totalW = scaled.sumOf { it.width }
        val result = Bitmap.createBitmap(totalW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        var x = 0
        for (bmp in scaled) {
            canvas.drawBitmap(bmp, x.toFloat(), 0f, null)
            x += bmp.width
        }
        scaled.forEachIndexed { i, b ->
            if (b !== bitmaps[i]) b.recycle()
        }
        return result
    }
}
