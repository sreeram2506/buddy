package com.sreeram.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Takes one photo without showing a preview. Needs Buddy's screen in the foreground and the CAMERA permission.
object CameraCapture {
    @SuppressLint("MissingPermission")
    fun capture(context: Context, front: Boolean): Bitmap? {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return null
        val cm = context.getSystemService(CameraManager::class.java)
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted } ?: return null
        val chars = cm.getCameraCharacteristics(id)
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG)
        val size = sizes.filter { it.width <= 2048 }.maxByOrNull { it.width * it.height } ?: sizes.first()
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val thread = HandlerThread("buddy-camera").also { it.start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        val texture = SurfaceTexture(0).also { it.setDefaultBufferSize(640, 480) }
        val preview = Surface(texture)
        var bytes: ByteArray? = null
        val gotImage = CountDownLatch(1)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { img ->
                val buf = img.planes[0].buffer
                bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            }
            gotImage.countDown()
        }, handler)

        var device: CameraDevice? = null
        val opened = CountDownLatch(1)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
                override fun onDisconnected(d: CameraDevice) { d.close(); opened.countDown() }
                override fun onError(d: CameraDevice, e: Int) { d.close(); opened.countDown() }
            }, handler)
            opened.await(5, TimeUnit.SECONDS)
            val dev = device ?: return null

            var session: CameraCaptureSession? = null
            val configured = CountDownLatch(1)
            @Suppress("DEPRECATION")
            dev.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
            }, handler)
            configured.await(5, TimeUnit.SECONDS)
            val s = session ?: return null

            // Let auto-exposure and focus settle on a few preview frames before the real shot.
            s.setRepeatingRequest(dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build(), null, handler)
            Thread.sleep(1200)
            s.capture(dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build(), null, handler)
            gotImage.await(5, TimeUnit.SECONDS)
            s.close()
            dev.close()
            return bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }.also { Log.d("Jarvis", "camera photo: ${it?.width}x${it?.height}") }
        } catch (e: CameraAccessException) {
            Log.e("Jarvis", "camera failed", e)
            return null
        } finally {
            device?.close(); reader.close(); preview.release(); texture.release(); thread.quitSafely()
        }
    }
}
