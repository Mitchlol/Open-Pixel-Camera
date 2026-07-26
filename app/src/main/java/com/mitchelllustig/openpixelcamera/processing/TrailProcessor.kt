package com.mitchelllustig.openpixelcamera.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.ByteBuffer

class TrailProcessor {

    private var currentWidth = 0
    private var currentHeight = 0

    private var pixelBuf: IntArray? = null
    private var trailPixels: IntArray? = null
    private var trailAge: ShortArray? = null
    private var trailBitmap: Bitmap? = null
    private var frameBitmap: Bitmap? = null
    private var outputBitmaps = arrayOfNulls<Bitmap>(2)
    private var outputCanvases = arrayOfNulls<Canvas>(2)
    private var outputIndex = 0

    private var cachedThresholdValue = 0
    private var cachedFadeStart = 0
    private var cachedTrailLength = 0
    private var cachedThreshold = -1f
    private var cachedTrailLen = -1
    private var cachedFadePct = -1f
    private var cachedSrcBuf: ByteBuffer? = null
    private var cachedIntView: java.nio.IntBuffer? = null

    var threshold: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }

    var trailLength: Int = 3
        set(value) { field = value.coerceAtLeast(1) }

    var fadePercent: Float = 0.25f
        set(value) { field = value.coerceIn(0f, 1f) }

    private fun ensureDerived() {
        if (threshold == cachedThreshold && trailLength == cachedTrailLen && fadePercent == cachedFadePct) return
        cachedThreshold = threshold
        cachedTrailLen = trailLength
        cachedFadePct = fadePercent
        cachedThresholdValue = 255 - (threshold * 255).toInt()
        cachedTrailLength = trailLength
        val fadeFrames = (trailLength * fadePercent).toInt().coerceAtMost(trailLength - 1)
        cachedFadeStart = trailLength - fadeFrames
    }

    fun processFrame(src: ByteBuffer, width: Int, height: Int): Bitmap {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            val size = width * height
            pixelBuf = IntArray(size)
            trailPixels = IntArray(size)
            trailAge = ShortArray(size)
            trailBitmap?.recycle()
            trailBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (i in 0..1) {
                outputBitmaps[i]?.recycle()
                outputBitmaps[i] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                outputCanvases[i] = Canvas(outputBitmaps[i]!!)
            }
        }

        ensureDerived()

        val pBuf = pixelBuf!!
        val tPix = trailPixels!!
        val tAge = trailAge!!
        val size = width * height
        val thresholdValue = cachedThresholdValue
        val tLen = cachedTrailLength
        val fadeStart = cachedFadeStart

        src.position(0)
        if (src !== cachedSrcBuf) {
            cachedSrcBuf = src
            cachedIntView = src.asIntBuffer()
        }
        cachedIntView!!.position(0)
        cachedIntView!!.get(pBuf)

        for (i in 0 until size) {
            val pixel = pBuf[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            if (r > thresholdValue || g > thresholdValue || b > thresholdValue) {
                tPix[i] = pixel or 0xFF000000.toInt()
                tAge[i] = 1
            } else if (tAge[i] > 0) {
                val age = tAge[i].toInt()
                val newAge = age + 1
                if (newAge > tLen) {
                    tAge[i] = 0
                    tPix[i] = Color.TRANSPARENT
                } else {
                    tAge[i] = newAge.toShort()
                    if (newAge > fadeStart) {
                        val tp = tPix[i]
                        val ta = (tp shr 24) and 0xFF
                        val tr = (tp shr 16) and 0xFF
                        val tg = (tp shr 8) and 0xFF
                        val tb = tp and 0xFF
                        val remaining = tLen - age + 1
                        val na = (ta * (remaining - 1) + remaining / 2) / remaining
                        tPix[i] = (na shl 24) or (tr shl 16) or (tg shl 8) or tb
                    }
                }
            }
        }

        trailBitmap!!.setPixels(tPix, 0, width, 0, 0, width, height)
        frameBitmap!!.setPixels(pBuf, 0, width, 0, 0, width, height)

        val idx = outputIndex
        outputIndex = (outputIndex + 1) % 2
        val canvas = outputCanvases[idx]!!
        canvas.drawBitmap(frameBitmap!!, 0f, 0f, null)
        canvas.drawBitmap(trailBitmap!!, 0f, 0f, null)

        return outputBitmaps[idx]!!
    }

    fun clear() {
        trailPixels?.fill(Color.TRANSPARENT)
        trailAge?.fill(0)
        for (i in 0..1) {
            outputBitmaps[i]?.recycle()
            outputBitmaps[i] = null
            outputCanvases[i] = null
        }
        trailBitmap?.recycle()
        trailBitmap = null
        frameBitmap?.recycle()
        frameBitmap = null
        pixelBuf = null
        trailPixels = null
        trailAge = null
        cachedSrcBuf = null
        cachedIntView = null
        currentWidth = 0
        currentHeight = 0
    }
}
