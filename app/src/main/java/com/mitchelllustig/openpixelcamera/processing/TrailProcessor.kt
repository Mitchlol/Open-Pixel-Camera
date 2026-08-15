package com.mitchelllustig.openpixelcamera.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import java.nio.ByteBuffer
import kotlin.math.floor

enum class ColorOverrideMode { OFF, COLOR, WHITE, FADE }

class TrailProcessor(private val context: Context) {

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
    private var cachedFadePct = -1f
    private var cachedSolidR = 0
    private var cachedSolidG = 0
    private var cachedSolidB = 0
    private var cachedSrcBuf: ByteBuffer? = null
    private var cachedIntView: java.nio.IntBuffer? = null

    var threshold: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }

    var trailLength: Int = 3
        set(value) { field = value.coerceAtLeast(1) }

    var fadePercent: Float = 0.25f
        set(value) { field = value.coerceIn(0f, 1f) }

    var colorOverrideMode: ColorOverrideMode = ColorOverrideMode.OFF
    var solidColor: Int = 0xFFFFFFFF.toInt()

    var blurAmount: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    var mirrorHorizontal: Boolean = false
    var mirrorVertical: Boolean = false

    private var rsContext: RenderScript? = null
    private var blurScript: ScriptIntrinsicBlur? = null
    private var blurInputAlloc: Allocation? = null
    private var blurOutputAlloc: Allocation? = null
    private var blurCachedWidth = 0
    private var blurCachedHeight = 0

    private var fadeStartTimeNanos: Long = 0L
    private val fadeDurationNanos = 10_000_000_000L

    private fun ensureDerived() {
        if (threshold == cachedThreshold && trailLength == cachedTrailLength && fadePercent == cachedFadePct) return
        cachedThreshold = threshold
        cachedTrailLength = trailLength
        cachedFadePct = fadePercent
        cachedThresholdValue = 255 - (threshold * 255).toInt()
        val fadeFrames = (trailLength * fadePercent).toInt().coerceAtMost(trailLength - 1)
        cachedFadeStart = trailLength - fadeFrames
        cachedSolidR = (solidColor shr 16) and 0xFF
        cachedSolidG = (solidColor shr 8) and 0xFF
        cachedSolidB = solidColor and 0xFF
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
        val mirrorH = mirrorHorizontal
        val mirrorV = mirrorVertical
        val lastCol = width - 1
        val lastRow = height - 1

        src.position(0)
        if (src !== cachedSrcBuf) {
            cachedSrcBuf = src
            cachedIntView = src.asIntBuffer()
        }
        cachedIntView!!.position(0)
        cachedIntView!!.get(pBuf)

        var overrideR = 0
        var overrideG = 0
        var overrideB = 0
        val overrideActive = colorOverrideMode != ColorOverrideMode.OFF
        if (overrideActive) {
            val overrideRgb = when (colorOverrideMode) {
                ColorOverrideMode.COLOR -> solidColor
                ColorOverrideMode.WHITE -> 0xFFFFFFFF.toInt()
                ColorOverrideMode.FADE -> {
                    if (fadeStartTimeNanos == 0L) fadeStartTimeNanos = System.nanoTime()
                    val elapsed = System.nanoTime() - fadeStartTimeNanos
                    val hue = (elapsed.toFloat() / fadeDurationNanos * 360f) % 360f
                    hsvToArgb(hue, 1f, 1f)
                }
                else -> 0
            }
            overrideR = (overrideRgb shr 16) and 0xFF
            overrideG = (overrideRgb shr 8) and 0xFF
            overrideB = overrideRgb and 0xFF
        } else {
            fadeStartTimeNanos = 0L
        }

        for (i in 0 until size) {
            val pixel = pBuf[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            if (r > thresholdValue || g > thresholdValue || b > thresholdValue) {
                val color = if (overrideActive) {
                    (0xFF shl 24) or (overrideR shl 16) or (overrideG shl 8) or overrideB
                } else {
                    pixel or 0xFF000000.toInt()
                }
                tAge[i] = 1
                tPix[i] = color
                if (mirrorH || mirrorV) {
                    val col = i % width
                    val row = i / width
                    if (mirrorH) {
                        val m = row * width + (lastCol - col)
                        if (m != i) {
                            tAge[m] = 1
                            tPix[m] = color
                        }
                    }
                    if (mirrorV) {
                        val m = (lastRow - row) * width + col
                        if (m != i) {
                            tAge[m] = 1
                            tPix[m] = color
                        }
                    }
                    if (mirrorH && mirrorV) {
                        val m = (lastRow - row) * width + (lastCol - col)
                        if (m != i) {
                            tAge[m] = 1
                            tPix[m] = color
                        }
                    }
                }
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
                        val remaining = tLen - age + 1
                        val na = (ta * (remaining - 1) + remaining / 2) / remaining
                        tPix[i] = (na shl 24) or (tp and 0x00FFFFFF)
                    }
                }
            }
        }

        frameBitmap!!.setPixels(pBuf, 0, width, 0, 0, width, height)

        val trailSource: Bitmap
        if (blurAmount > 0f) {
            ensureBlurAlloc(width, height)
            trailBitmap!!.setPixels(tPix, 0, width, 0, 0, width, height)
            blurScript!!.setRadius((blurAmount * 25f).coerceIn(0.1f, 25f))
            blurScript!!.setInput(blurInputAlloc)
            blurScript!!.forEach(blurOutputAlloc)
            blurOutputAlloc!!.copyTo(trailBitmap!!)
            trailSource = trailBitmap!!
        } else {
            trailBitmap!!.setPixels(tPix, 0, width, 0, 0, width, height)
            trailSource = trailBitmap!!
        }

        val idx = outputIndex
        outputIndex = (outputIndex + 1) % 2
        val canvas = outputCanvases[idx]!!
        canvas.drawBitmap(frameBitmap!!, 0f, 0f, null)
        canvas.drawBitmap(trailSource, 0f, 0f, null)

        return outputBitmaps[idx]!!
    }

    private fun ensureBlurAlloc(width: Int, height: Int) {
        if (rsContext == null) {
            rsContext = RenderScript.create(context)
            blurScript = ScriptIntrinsicBlur.create(rsContext, Element.U8_4(rsContext))
        }
        if (width != blurCachedWidth || height != blurCachedHeight) {
            blurInputAlloc?.destroy()
            blurOutputAlloc?.destroy()
            blurInputAlloc = Allocation.createFromBitmap(rsContext, trailBitmap!!)
            blurOutputAlloc = Allocation.createTyped(rsContext, blurInputAlloc!!.type)
            blurCachedWidth = width
            blurCachedHeight = height
        }
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
        fadeStartTimeNanos = 0L
        blurInputAlloc?.destroy()
        blurInputAlloc = null
        blurOutputAlloc?.destroy()
        blurOutputAlloc = null
        blurScript?.destroy()
        blurScript = null
        rsContext?.destroy()
        rsContext = null
        blurCachedWidth = 0
        blurCachedHeight = 0
    }

    companion object {
        private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
            val h = ((hue / 60f) % 6f).coerceIn(0f, 6f)
            val f = h - floor(h)
            val p = (value * (1f - saturation) * 255f).toInt()
            val q = (value * (1f - f * saturation) * 255f).toInt()
            val t = (value * (1f - (1f - f) * saturation) * 255f).toInt()
            val v = (value * 255f).toInt()
            val (r, g, b) = when (h.toInt()) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
