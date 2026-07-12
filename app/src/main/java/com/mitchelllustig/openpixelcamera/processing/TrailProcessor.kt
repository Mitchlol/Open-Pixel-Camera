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
    private var rotatedBitmap: Bitmap? = null
    private var rotatedCanvas: Canvas? = null
    private var sensorOrientation: Int = 0

    var threshold: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }

    var trailLength: Int = 3
        set(value) { field = value.coerceAtLeast(1) }

    var fadePercent: Float = 0.25f
        set(value) { field = value.coerceIn(0f, 1f) }

    fun processFrame(frame: Bitmap, orientation: Int = 0): Bitmap {
        val width = frame.width
        val height = frame.height
        sensorOrientation = orientation

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
            rotatedBitmap?.recycle()
            val isRotated = orientation == 90 || orientation == 270
            val rw = if (isRotated) height else width
            val rh = if (isRotated) width else height
            rotatedBitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            rotatedCanvas = Canvas(rotatedBitmap!!)
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
            val age = tAge[i].toInt()
            if (age > 0) {
                val newAge = age + 1
                if (newAge > trailLength) {
                    tAge[i] = 0
                    tPix[i] = Color.TRANSPARENT
                } else {
                    tAge[i] = newAge.toByte()
                    if (newAge > fadeStart) {
                        val pixel = tPix[i]
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        val alpha = Color.alpha(pixel)
                        val remaining = trailLength - age + 1
                        val remainingNew = remaining - 1
                        val na = (alpha * remainingNew + remaining / 2) / remaining
                        tPix[i] = Color.argb(na, r, g, b)
                    }
                }
            }
        }

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

        trailBitmap!!.setPixels(tPix, 0, width, 0, 0, width, height)

        val canvas = outputCanvas!!
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(frame, 0f, 0f, null)
        canvas.drawBitmap(trailBitmap!!, 0f, 0f, null)

        val rCanvas = rotatedCanvas!!
        rCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val matrix = android.graphics.Matrix()
        matrix.setRotate(sensorOrientation.toFloat(), outputBitmap!!.width / 2f, outputBitmap!!.height / 2f)
        val srcRect = android.graphics.RectF(0f, 0f, outputBitmap!!.width.toFloat(), outputBitmap!!.height.toFloat())
        matrix.mapRect(srcRect)
        val dx = (rotatedBitmap!!.width - srcRect.width()) / 2f - srcRect.left
        val dy = (rotatedBitmap!!.height - srcRect.height()) / 2f - srcRect.top
        matrix.postTranslate(dx, dy)
        rCanvas.drawBitmap(outputBitmap!!, matrix, null)

        return rotatedBitmap!!
    }

    fun clear() {
        trailPixels?.fill(Color.TRANSPARENT)
        trailAge?.fill(0)
        outputBitmap?.recycle()
        outputBitmap = null
        outputCanvas = null
        rotatedBitmap?.recycle()
        rotatedBitmap = null
        rotatedCanvas = null
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
