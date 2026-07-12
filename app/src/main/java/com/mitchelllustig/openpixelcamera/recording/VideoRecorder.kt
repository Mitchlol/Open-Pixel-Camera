package com.mitchelllustig.openpixelcamera.recording

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var pfd: android.os.ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    private var cachedSurface: Surface? = null

    @Volatile
    var isRecording = false
        private set

    fun start(width: Int, height: Int, fps: Int = 60): Boolean {
        if (isRecording) return false
        try {
            val contentValues = ContentValues().apply {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                put(MediaStore.Video.Media.DISPLAY_NAME, "VID_$ts.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/OpenPixelCamera")
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return false
            videoUri = uri

            pfd = resolver.openFileDescriptor(uri, "w") ?: run {
                resolver.delete(uri, null, null)
                return false
            }

            val r = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(fps)
                setVideoEncodingBitRate(width * height * 4)
                setOutputFile(pfd!!.fileDescriptor)
                prepare()
                start()
            }
            recorder = r
            cachedSurface = r.surface
            isRecording = true
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            release()
            return false
        }
    }

    fun drawFrame(bitmap: Bitmap) {
        if (!isRecording) return
        val surface = cachedSurface ?: return
        try {
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), null)
            surface.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        try { recorder?.stop() } catch (e: Exception) {
            e.printStackTrace()
            videoUri?.let { context.contentResolver.delete(it, null, null) }
        }
        release()
    }

    private fun release() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        cachedSurface = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        videoUri = null
    }
}
