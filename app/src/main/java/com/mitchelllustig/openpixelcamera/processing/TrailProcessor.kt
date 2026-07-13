package com.mitchelllustig.openpixelcamera.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

class TrailProcessor {

    private var currentWidth = 0
    private var currentHeight = 0

    private var pixelBuf: IntArray? = null
    private var trailPixels: IntArray? = null
    private var trailAge: ByteArray? = null
    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private var outputBitmap: Bitmap? = null
    private var outputCanvas: Canvas? = null

    var threshold: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }

    var trailLength: Int = 3
        set(value) { field = value.coerceAtLeast(1) }

    var fadePercent: Float = 0.25f
        set(value) { field = value.coerceIn(0f, 1f) }

    fun processFrame(frame: Bitmap): Bitmap {
        val width = frame.width
        val height = frame.height

        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            val size = width * height
            pixelBuf = IntArray(size)
            trailPixels = IntArray(size)
            trailAge = ByteArray(size)
            trailBitmap?.recycle()
            trailBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            trailCanvas = Canvas(trailBitmap!!)
            outputBitmap?.recycle()
            outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outputCanvas = Canvas(outputBitmap!!)
        }

        val pBuf = pixelBuf!!
        val tPix = trailPixels!!
        val tAge = trailAge!!
        val size = width * height

        frame.getPixels(pBuf, 0, width, 0, 0, width, height)

        val thresholdValue = 255 - (threshold * 255).toInt()

        val fadeFrames = (trailLength * fadePercent).toInt().coerceAtMost(trailLength - 1)
        val fadeStart = trailLength - fadeFrames

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
                if (newAge > trailLength) {
                    tAge[i] = 0
                    tPix[i] = Color.TRANSPARENT
                } else {
                    tAge[i] = newAge.toByte()
                    if (newAge > fadeStart) {
                        val tp = tPix[i]
                        val ta = (tp shr 24) and 0xFF
                        val tr = (tp shr 16) and 0xFF
                        val tg = (tp shr 8) and 0xFF
                        val tb = tp and 0xFF
                        val remaining = trailLength - age + 1
                        val na = (ta * (remaining - 1) + remaining / 2) / remaining
                        tPix[i] = (na shl 24) or (tr shl 16) or (tg shl 8) or tb
                    }
                }
            }
        }

        trailBitmap!!.setPixels(tPix, 0, width, 0, 0, width, height)

        val canvas = outputCanvas!!
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(frame, 0f, 0f, null)
        canvas.drawBitmap(trailBitmap!!, 0f, 0f, null)

        return outputBitmap!!
    }

    fun clear() {
        trailPixels?.fill(Color.TRANSPARENT)
        trailAge?.fill(0)
        outputBitmap?.recycle()
        outputBitmap = null
        outputCanvas = null
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
        pixelBuf = null
        trailPixels = null
        trailAge = null
        currentWidth = 0
        currentHeight = 0
    }
}
