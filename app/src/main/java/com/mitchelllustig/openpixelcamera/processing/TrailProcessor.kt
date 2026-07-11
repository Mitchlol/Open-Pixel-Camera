package com.mitchelllustig.openpixelcamera.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

class TrailProcessor {

    private val trailBuffer = ArrayDeque<IntArray>()
    private var currentWidth = 0
    private var currentHeight = 0

    private var cachedPixelBuffer: IntArray? = null
    private var cachedMaskBuffer: IntArray? = null
    private var cachedTrailBitmaps = mutableListOf<Bitmap>()
    private var cachedOutput: Bitmap? = null
    private var cachedOutputCanvas: Canvas? = null

    var threshold: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }

    var trailLength: Int = 3
        set(value) { field = value.coerceAtLeast(1) }

    fun processFrame(frame: Bitmap): Bitmap {
        val width = frame.width
        val height = frame.height

        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            trailBuffer.clear()
            cachedPixelBuffer = null
            cachedMaskBuffer = null
            cachedOutput = null
            cachedOutputCanvas = null
            cachedTrailBitmaps.forEach { it.recycle() }
            cachedTrailBitmaps.clear()
        }

        val size = width * height

        var pixelBuf = cachedPixelBuffer
        if (pixelBuf == null || pixelBuf.size != size) {
            pixelBuf = IntArray(size)
            cachedPixelBuffer = pixelBuf
        }

        var maskBuf = cachedMaskBuffer
        if (maskBuf == null || maskBuf.size != size) {
            maskBuf = IntArray(size)
            cachedMaskBuffer = maskBuf
        }

        extractBrightPixels(frame, pixelBuf, maskBuf, width, height)

        // Copy mask into a stable array for the trail buffer (mask is reused next frame)
        val trailMask = IntArray(size)
        maskBuf.copyInto(trailMask)

        trailBuffer.addLast(trailMask)
        while (trailBuffer.size > trailLength) {
            trailBuffer.removeFirst()
        }

        var output = cachedOutput
        if (output == null || output.isRecycled || output.width != width || output.height != height) {
            output?.recycle()
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            cachedOutput = output
            cachedOutputCanvas = Canvas(output)
        }

        val canvas = cachedOutputCanvas!!
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(frame, 0f, 0f, null)

        // Ensure we have enough cached trail bitmaps
        while (cachedTrailBitmaps.size < trailBuffer.size - 1) {
            cachedTrailBitmaps.add(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        }

        for (i in 0 until trailBuffer.size - 1) {
            val trail = trailBuffer[i]
            val trailBitmap = cachedTrailBitmaps[i]
            trailBitmap.setPixels(trail, 0, width, 0, 0, width, height)
            canvas.drawBitmap(trailBitmap, 0f, 0f, null)
        }

        return output
    }

    private fun extractBrightPixels(frame: Bitmap, pixelBuf: IntArray, maskBuf: IntArray, width: Int, height: Int) {
        frame.getPixels(pixelBuf, 0, width, 0, 0, width, height)

        val thresholdValue = 255 - (threshold * 255).toInt()

        for (i in pixelBuf.indices) {
            val pixel = pixelBuf[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

            if (brightness > thresholdValue) {
                maskBuf[i] = pixel or 0xFF000000.toInt()
            } else {
                maskBuf[i] = Color.TRANSPARENT
            }
        }
    }

    fun clear() {
        trailBuffer.clear()
        cachedOutput?.recycle()
        cachedOutput = null
        cachedOutputCanvas = null
        cachedTrailBitmaps.forEach { it.recycle() }
        cachedTrailBitmaps.clear()
        cachedPixelBuffer = null
        cachedMaskBuffer = null
    }
}
