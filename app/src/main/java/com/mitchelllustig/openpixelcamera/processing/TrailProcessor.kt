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
import kotlin.math.roundToInt

enum class ColorOverrideMode { OFF, FADE, RGB, COLOR, WHITE, BLACK }

class TrailProcessor(private val context: Context) {

    private var currentWidth = 0
    private var currentHeight = 0

    private var pixelBuf: IntArray? = null
    private var trailPixels: IntArray? = null
    private var trailDisplay: IntArray? = null
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

    var rotationalSymmetry: Int = 0
        set(value) { field = value.coerceAtLeast(0) }

    var shimmerEnabled: Boolean = false

    private var cachedRotSymmetry = 0
    private var cachedRotWidth = 0
    private var cachedRotHeight = 0
    private var rotCos: FloatArray? = null
    private var rotSin: FloatArray? = null
    private var rotCenterX = 0f
    private var rotCenterY = 0f

    private var rsContext: RenderScript? = null
    private var blurScript: ScriptIntrinsicBlur? = null
    private var blurInputAlloc: Allocation? = null
    private var blurOutputAlloc: Allocation? = null
    private var blurCachedWidth = 0
    private var blurCachedHeight = 0

    private var fadeStartTimeNanos: Long = 0L
    private val fadeDurationNanos = 10_000_000_000L

    private var rgbColors: IntArray? = null
    private var cachedRgbTrailLength = 0

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
        if (rgbColors == null || cachedRgbTrailLength != cachedTrailLength) {
            cachedRgbTrailLength = cachedTrailLength
            val len = cachedTrailLength
            val colors = IntArray(len + 1)
            for (age in 1..len) {
                val hue = (age - 1).toFloat() / (len - 1).coerceAtLeast(1) * 300f
                colors[age] = hsvToArgb(hue, 1f, 1f)
            }
            rgbColors = colors
        }
    }

    private fun ensureRotation() {
        if (rotationalSymmetry == cachedRotSymmetry && currentWidth == cachedRotWidth && currentHeight == cachedRotHeight) return
        cachedRotSymmetry = rotationalSymmetry
        cachedRotWidth = currentWidth
        cachedRotHeight = currentHeight
        rotCenterX = (currentWidth - 1) / 2f
        rotCenterY = (currentHeight - 1) / 2f
        val n = rotationalSymmetry
        if (n > 1) {
            val cos = FloatArray(n - 1)
            val sin = FloatArray(n - 1)
            for (k in 1 until n) {
                val a = 2.0 * Math.PI * k / n
                cos[k - 1] = Math.cos(a).toFloat()
                sin[k - 1] = Math.sin(a).toFloat()
            }
            rotCos = cos
            rotSin = sin
        } else {
            rotCos = null
            rotSin = null
        }
    }

    fun processFrame(src: ByteBuffer, width: Int, height: Int): Bitmap {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            val size = width * height
            pixelBuf = IntArray(size)
            trailPixels = IntArray(size)
            trailDisplay = IntArray(size)
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
        ensureRotation()

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
        val rotN = rotationalSymmetry

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
        val rgbMode = colorOverrideMode == ColorOverrideMode.RGB
        if (overrideActive) {
            val overrideRgb = when (colorOverrideMode) {
                ColorOverrideMode.COLOR -> solidColor
                ColorOverrideMode.WHITE -> 0xFFFFFFFF.toInt()
                ColorOverrideMode.BLACK -> 0xFF000000.toInt()
                ColorOverrideMode.RGB -> 0xFFFF0000.toInt()
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
                if (rotN > 1) {
                    val cosArr = rotCos!!
                    val sinArr = rotSin!!
                    val x = i % width
                    val y = i / width
                    val dx = x - rotCenterX
                    val dy = y - rotCenterY
                    for (k in cosArr.indices) {
                        val mx = (rotCenterX + dx * cosArr[k] - dy * sinArr[k]).roundToInt()
                        val my = (rotCenterY + dx * sinArr[k] + dy * cosArr[k]).roundToInt()
                        if (mx >= 0 && mx < width && my >= 0 && my < height) {
                            val m = my * width + mx
                            if (m != i) {
                                tAge[m] = 1
                                tPix[m] = color
                            }
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
                    if (rgbMode) {
                        val prevAlpha = (tPix[i] shr 24) and 0xFF
                        tPix[i] = rgbColors!![newAge]
                        if (newAge > fadeStart) {
                            val remaining = tLen - age + 1
                            val na = (prevAlpha * (remaining - 1) + remaining / 2) / remaining
                            tPix[i] = (na shl 24) or (tPix[i] and 0x00FFFFFF)
                        }
                    } else if (newAge > fadeStart) {
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

        val trailSrc = if (shimmerEnabled) {
            buildShimmerPixels(size)
            trailDisplay!!
        } else {
            tPix
        }

        val trailSource: Bitmap
        if (blurAmount > 0f) {
            ensureBlurAlloc(width, height)
            trailBitmap!!.setPixels(trailSrc, 0, width, 0, 0, width, height)
            blurScript!!.setRadius((blurAmount * 25f).coerceIn(0.1f, 25f))
            blurScript!!.setInput(blurInputAlloc)
            blurScript!!.forEach(blurOutputAlloc)
            blurOutputAlloc!!.copyTo(trailBitmap!!)
            trailSource = trailBitmap!!
        } else {
            trailBitmap!!.setPixels(trailSrc, 0, width, 0, 0, width, height)
            trailSource = trailBitmap!!
        }

        val idx = outputIndex
        outputIndex = (outputIndex + 1) % 2
        val canvas = outputCanvases[idx]!!
        canvas.drawBitmap(frameBitmap!!, 0f, 0f, null)
        canvas.drawBitmap(trailSource, 0f, 0f, null)

        return outputBitmaps[idx]!!
    }

    private fun buildShimmerPixels(size: Int) {
        val disp = trailDisplay!!
        val tPix = trailPixels!!
        val tAge = trailAge!!
        for (i in 0 until size) {
            disp[i] = if ((tAge[i].toInt() % 3) == 0) Color.TRANSPARENT else tPix[i]
        }
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
        trailDisplay = null
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
        cachedRotSymmetry = 0
        cachedRotWidth = 0
        cachedRotHeight = 0
        rotCos = null
        rotSin = null
        rgbColors = null
        cachedRgbTrailLength = 0
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
