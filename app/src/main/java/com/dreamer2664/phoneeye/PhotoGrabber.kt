package com.dreamer2664.phoneeye

import android.content.Context
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
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One photo, on demand (GET /photo). No preview UI: a dummy SurfaceTexture keeps
 * camera HALs that demand a preview stream happy while AutoFocus/AutoExposure settle,
 * then a single STILL_CAPTURE lands in an ImageReader.
 *
 * Honest constraints, printed in the refusals and the docs:
 *  - the camera is exclusive: if another app holds it, this says so (503);
 *  - Android shows the privacy camera indicator and some regions play a shutter
 *    sound — phone-eye photos are never covert, by platform design;
 *  - background camera access needs the foreground service to have started while
 *    the app was in use (which is exactly phone-eye's only start path).
 */
object PhotoGrabber {

    private val lock = Object()

    fun capture(ctx: Context, front: Boolean, timeoutMs: Long = 12_000): ByteArray {
        synchronized(lock) {
            val thread = HandlerThread("phoneeye-camera")
            thread.start()
            val handler = Handler(thread.looper)

            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var reader: ImageReader? = null
            var surfaceTexture: SurfaceTexture? = null
            var previewSurface: Surface? = null

            val openLatch = CountDownLatch(1)
            val sessionLatch = CountDownLatch(1)
            val imageLatch = CountDownLatch(1)
            @Volatile var error: String? = null
            @Volatile var jpeg: ByteArray? = null

            try {
                val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val wantFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT
                                 else CameraCharacteristics.LENS_FACING_BACK
                var cameraId: String? = null
                for (id in manager.cameraIdList) {
                    val ch = manager.getCameraCharacteristics(id)
                    if (ch.get(CameraCharacteristics.LENS_FACING) == wantFacing) { cameraId = id; break }
                }
                if (cameraId == null) cameraId = manager.cameraIdList.firstOrNull()
                val id = cameraId ?: throw Refusal(503, "this phone reports no cameras at all")
                val chars = manager.getCameraCharacteristics(id)

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(ImageFormat.JPEG)
                val size = sizes
                    ?.filter { it.width <= 2048 }
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: sizes?.minByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: throw Refusal(503, "the camera offers no JPEG sizes phone-eye can use")

                reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
                reader.setOnImageAvailableListener({ r ->
                    try {
                        val image = r.acquireLatestImage()
                        if (image != null) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            jpeg = bytes
                            image.close()
                        } else if (error == null) {
                            error = "camera delivered an empty image"
                        }
                    } catch (e: Exception) {
                        if (error == null) error = "reading the captured image failed: ${e.message}"
                    } finally {
                        imageLatch.countDown()
                    }
                }, handler)

                surfaceTexture = SurfaceTexture(11)
                surfaceTexture.setDefaultBufferSize(640, 480)
                previewSurface = Surface(surfaceTexture)

                try {
                    manager.openCamera(id, object : CameraDevice.StateCallback() {
                        override fun onOpened(d: CameraDevice) { device = d; openLatch.countDown() }
                        override fun onDisconnected(d: CameraDevice) {
                            if (error == null) error = "camera disconnected mid-capture"
                            d.close()
                            openLatch.countDown()
                            sessionLatch.countDown()
                            imageLatch.countDown()
                        }
                        override fun onError(d: CameraDevice, e: Int) {
                            if (error == null) {
                                error = when (e) {
                                    ERROR_CAMERA_IN_USE -> "the camera is in use by another app"
                                    ERROR_CAMERA_DISABLED -> "the camera is disabled by device policy"
                                    ERROR_CAMERA_DEVICE, ERROR_CAMERA_SERVICE -> "the camera service hit a fatal error (code $e)"
                                    else -> "camera open failed (code $e)"
                                }
                            }
                            d.close()
                            openLatch.countDown()
                            sessionLatch.countDown()
                            imageLatch.countDown()
                        }
                    }, handler)
                } catch (e: SecurityException) {
                    throw Refusal(409, "CAMERA permission is not granted — phone-eye app → \"Grant permissions\", then restart the session so the foreground service picks it up")
                }

                if (!openLatch.await(5, TimeUnit.SECONDS) || device == null) {
                    throw Refusal(503, "camera would not open: ${error ?: "timed out after 5s (another app may be holding it)"}")
                }
                val dev = device!!

                @Suppress("DEPRECATION")
                dev.createCaptureSession(
                    listOf(previewSurface, reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { session = s; sessionLatch.countDown() }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            if (error == null) error = "camera session configuration failed on this device"
                            sessionLatch.countDown()
                            imageLatch.countDown()
                        }
                    },
                    handler
                )
                if (!sessionLatch.await(5, TimeUnit.SECONDS) || session == null) {
                    throw Refusal(503, "camera session would not start: ${error ?: "timed out after 5s"}")
                }
                val sess = session!!

                // Let AE/AF run briefly against the dummy preview; a cold STILL_CAPTURE is
                // the classic way to get a black or blurry frame and call it success.
                val previewReq = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                sess.setRepeatingRequest(previewReq.build(), null, handler)
                Thread.sleep(800)

                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val displayDeg = Device.rotationDegrees(ctx)
                val jpegOrientation = if (front) {
                    val r = (sensorOrientation + displayDeg) % 360
                    (360 - r) % 360
                } else {
                    (sensorOrientation - displayDeg + 360) % 360
                }

                val captureReq = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                    set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                }
                sess.capture(captureReq.build(), null, handler)

                if (!imageLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw Refusal(503, "photo timed out after ${timeoutMs / 1000}s — the camera never delivered a frame")
                }
                error?.let { throw Refusal(503, it) }
                return jpeg ?: throw Refusal(503, "photo finished with no image and no error — report this phone model, it is a new failure shape")
            } finally {
                try { session?.stopRepeating() } catch (e: Exception) { }
                try { session?.close() } catch (e: Exception) { }
                try { device?.close() } catch (e: Exception) { }
                try { reader?.close() } catch (e: Exception) { }
                try { previewSurface?.release() } catch (e: Exception) { }
                try { surfaceTexture?.release() } catch (e: Exception) { }
                thread.quitSafely()
            }
        }
    }
}
