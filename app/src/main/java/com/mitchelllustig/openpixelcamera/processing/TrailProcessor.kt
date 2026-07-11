package com.mitchelllustig.openpixelcamera.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

class TrailProcessor {

    private val trailBuffer = ArrayDeque<IntArray>()
    private var currentWidth = 0
    private var currentHeight = 0

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
        }

        val brightPixels = extractBrightPixels(frame, threshold)

        trailBuffer.addLast(brightPixels)
        while (trailBuffer.size > trailLength) {
            trailBuffer.removeFirst()
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        canvas.drawBitmap(frame, 0f, 0f, null)

        for (i in 0 until trailBuffer.size - 1) {
            val trail = trailBuffer[i]
            val trailBitmap = pixelsToBitmap(trail, width, height)
            canvas.drawBitmap(trailBitmap, 0f, 0f, null)
            trailBitmap.recycle()
        }

        return output
    }

    private fun extractBrightPixels(frame: Bitmap, threshold: Float): IntArray {
        val width = frame.width
        val height = frame.height
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)

        val thresholdValue = (threshold * 255).toInt()
        val mask = IntArray(width * height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

            if (brightness > thresholdValue) {
                mask[i] = pixel or 0xFF000000.toInt()
            } else {
                mask[i] = Color.TRANSPARENT
            }
        }

        return mask
    }

    private fun pixelsToBitmap(pixels: IntArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun clear() {
        trailBuffer.clear()
    }
}
