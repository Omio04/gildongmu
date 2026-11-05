package com.gildongmu.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

/**
 * Takes one still from a camera the platform lets this app use, at the moment a hazard is detected, so a report can
 * carry what the driver saw. Standard Camera2 API: the Pleos SDK has no camera API, and a real car may or may not
 * expose its exterior or dashcam cameras to third-party apps. When no camera is available, or the permission is
 * missing, [capture] simply calls back with null and the report goes without a photo. Nothing is recorded:
 * one frame, kept in memory, sent only if the driver confirms the report, dropped otherwise.
 */
class HazardCamera(private val context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** True when the platform lists a camera at all. */
    fun available(): Boolean = try { manager.cameraIdList.isNotEmpty() } catch (_: Exception) { false }

    /** Takes one frame, shrinks it (longest side 800 px, JPEG quality 70) and calls [done] with the bytes, or null. */
    @SuppressLint("MissingPermission")
    fun capture(done: (ByteArray?) -> Unit) {
        val thread = HandlerThread("hazard-camera").also { it.start() }
        val handler = Handler(thread.looper)
        var finished = false
        var device: CameraDevice? = null
        var reader: ImageReader? = null
        fun finish(bytes: ByteArray?) {
            if (finished) return
            finished = true
            try { device?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            thread.quitSafely()
            done(bytes)
        }
        try {
            val id = pickCamera() ?: return finish(null)
            val chars = manager.getCameraCharacteristics(id)
            val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)?.filter { it.width <= 1920 }?.maxByOrNull { it.width * it.height }
                ?: return finish(null)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { r ->
                r.setOnImageAvailableListener({ rd ->
                    val img = try { rd.acquireLatestImage() } catch (_: Exception) { null }
                    if (img != null) {
                        val buf = img.planes[0].buffer
                        val raw = ByteArray(buf.remaining()).also { buf.get(it) }
                        img.close()
                        finish(shrink(raw))
                    }
                }, handler)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        cam.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader!!.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    }
                                    handler.postDelayed({ try { session.capture(req.build(), null, handler) } catch (_: Exception) { finish(null) } }, 400)
                                } catch (_: Exception) { finish(null) }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { finish(null) }
                        }, handler)
                    } catch (_: Exception) { finish(null) }
                }
                override fun onDisconnected(cam: CameraDevice) { finish(null) }
                override fun onError(cam: CameraDevice, error: Int) { finish(null) }
            }, handler)
            handler.postDelayed({ finish(null) }, 4_000)   // never hold the camera for long
        } catch (_: Exception) {
            finish(null)
        }
    }

    /** A back-facing camera if there is one (it looks at the road), else the first one the platform lists. */
    private fun pickCamera(): String? = try {
        val ids = manager.cameraIdList.toList()
        ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: ids.firstOrNull()
    } catch (_: Exception) { null }

    private fun shrink(raw: ByteArray): ByteArray? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
        val scale = 800f / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    } catch (_: Exception) { null }
}
