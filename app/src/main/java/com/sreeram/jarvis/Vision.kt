package com.sreeram.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Lets the live conversation see: about one frame a second from the camera, or from the screen, sent to the model while
// you talk. It shows a notification the whole time, can be stopped from it, and stops by itself after 5 minutes.
object Vision {
    @Volatile var mode: String? = null // "camera", "screen" or null
    private var camera: CameraStream? = null
    private var screenThread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private var autoStop: Runnable? = null
    const val ACTION_STOP = "com.sreeram.jarvis.STOP_SHARING"
    private const val MAX_MS = 5 * 60_000L

    // Small and compressed: the model needs the gist of a picture, not a photograph.
    fun encode(bmp: Bitmap): ByteArray {
        val scale = 768f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
    }

    fun startCamera(c: Context, front: Boolean): String {
        val sink = Session.frameSink ?: return "Camera view needs a live conversation. Say Hey buddy first."
        stop(c)
        val cam = CameraStream(c, front) { sink(it) }
        if (!cam.start()) return "I couldn't open the camera. Another app may be using it."
        camera = cam; mode = "camera"
        begin(c, "Buddy can see your ${if (front) "front" else "back"} camera")
        return "The ${if (front) "front" else "back"} camera is on and I can see through it now. Point it at what you want me to look at."
    }

    fun startScreen(c: Context): String {
        val sink = Session.frameSink ?: return "Screen sharing needs a live conversation. Say Hey buddy first."
        val svc = JarvisAccessibilityService.instance ?: return "Screen sharing needs accessibility to be on."
        stop(c)
        mode = "screen"
        Session.moveToBack?.invoke() // Buddy's own screen would otherwise be what gets shared
        screenThread = Thread {
            Thread.sleep(1200)
            while (mode == "screen") {
                try { svc.screenshotJpeg()?.let { sink(it) } } catch (e: Exception) { Log.w("Jarvis", "screen frame failed", e) }
                Thread.sleep(1500)
            }
        }.also { it.start() }
        begin(c, "Buddy can see your screen")
        return "Screen sharing is on and I can see your screen now."
    }

    private fun begin(c: Context, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sharing", "Buddy is looking", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(c, 9, Intent(c, SharingStopReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(41, NotificationCompat.Builder(c, "sharing").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(text)
            .setContentText("Tap Stop to turn it off. It also stops after 5 minutes.").setOngoing(true).addAction(0, "Stop", stop).build())
        autoStop = Runnable { stop(c) }.also { main.postDelayed(it, MAX_MS) }
    }

    fun stop(c: Context): String {
        val was = mode
        mode = null
        camera?.stop(); camera = null
        screenThread = null
        autoStop?.let { main.removeCallbacks(it) }; autoStop = null
        c.getSystemService(NotificationManager::class.java).cancel(41)
        return if (was == null) "I wasn't looking at anything." else "I've stopped looking."
    }
}

class SharingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Vision.stop(context) }
}

// Keeps the camera open and takes a small still about every second.
class CameraStream(private val context: Context, private val front: Boolean, private val onFrame: (ByteArray) -> Unit) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var texture: SurfaceTexture? = null
    private var preview: Surface? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        val cm = context.getSystemService(CameraManager::class.java)
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted } ?: return false
        val chars = cm.getCameraCharacteristics(id)
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG)
        val size = sizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height } ?: sizes.last()
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val t = HandlerThread("buddy-vision").also { it.start() }.also { thread = it }
        val h = Handler(t.looper).also { handler = it }
        val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { reader = it }
        rd.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { img ->
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { onFrame(Vision.encode(it)) }
            }
        }, h)
        val tex = SurfaceTexture(0).also { it.setDefaultBufferSize(640, 480); texture = it }
        val prev = Surface(tex).also { preview = it }
        val opened = CountDownLatch(1)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
                override fun onDisconnected(d: CameraDevice) { d.close(); opened.countDown() }
                override fun onError(d: CameraDevice, e: Int) { d.close(); opened.countDown() }
            }, h)
            opened.await(5, TimeUnit.SECONDS)
            val dev = device ?: return fail()
            val configured = CountDownLatch(1)
            @Suppress("DEPRECATION")
            dev.createCaptureSession(listOf(prev, rd.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
            }, h)
            configured.await(5, TimeUnit.SECONDS)
            val s = session ?: return fail()
            s.setRepeatingRequest(dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(prev); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build(), null, h)
            running = true
            val tick = object : Runnable {
                override fun run() {
                    if (!running) return
                    try {
                        s.capture(dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(rd.surface); set(CaptureRequest.JPEG_ORIENTATION, orientation)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build(), null, h)
                    } catch (e: Exception) { Log.w("Jarvis", "camera frame failed", e); return }
                    h.postDelayed(this, 1000)
                }
            }
            h.postDelayed(tick, 1200) // let focus and exposure settle first
            return true
        } catch (e: Exception) { Log.e("Jarvis", "camera stream failed", e); return fail() }
    }

    private fun fail(): Boolean { stop(); return false }

    fun stop() {
        running = false
        try { session?.close() } catch (e: Exception) { }
        try { device?.close() } catch (e: Exception) { }
        reader?.close(); preview?.release(); texture?.release(); thread?.quitSafely()
        session = null; device = null; reader = null; thread = null
    }
}
