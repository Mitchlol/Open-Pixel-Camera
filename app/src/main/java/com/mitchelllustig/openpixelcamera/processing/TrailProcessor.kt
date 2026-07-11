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

        // Age existing trail pixels and clear expired ones
        // age 0 = no trail, age 1 = fresh, age > 1 = aging, age > trailLength = expired
        for (i in 0 until size) {
            val age = tAge[i].toInt()
            if (age > 0) {
                tAge[i] = (age + 1).toByte()
                if (age + 1 > trailLength) {
                    tAge[i] = 0
                    tPix[i] = Color.TRANSPARENT
                }
            }
        }

        // Write new bright pixels at age 1, replacing whatever was there
        for (i in 0 until size) {
            val pixel = pBuf[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

            if (brightness > thresholdValue) {
                tPix[i] = pixel or 0xFF000000.toInt()
                tAge[i] = 1
            }
        }

        // Update trail bitmap from pixel data
        trailBitmap!!.setPixels(tPix, 0, width, 0, 0, width, height)

        // Composite: current frame + trail overlay
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
