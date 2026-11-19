package com.example.tripodcamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Интервальная съёмка со штатива: калибровка по авто (фокус, экспозиция, WB),
 * затем жёсткая ручная фиксация всех параметров.
 *
 * Выдержка ограничена сверху значением 1/shutterDenom (см. setManualExposure),
 * чтобы движущиеся объекты не смазывались; недостающая яркость добирается ISO.
 *
 * Разрешение: берётся ТОЛЬКО из обычной карты SCALER_STREAM_CONFIGURATION_MAP
 * (режим «по умолчанию», биннинг). Режим MAXIMUM_RESOLUTION (108/200 МП) не используется.
 *
 * Нужны: разрешение CAMERA, для работы с погашенным экраном — foreground-сервис
 * с foregroundServiceType="camera" и разрешение WAKE_LOCK.
 */
class TripodIntervalCamera(
    private val context: Context,
    private val outputDir: File,
    /** Явный id камеры; null = первая задняя камера (обычно основная). */
    private val cameraIdOverride: String? = null,
    /** Ограничение разрешения в мегапикселях; 0 = максимум из обычной карты. */
    private val maxMegapixels: Double = 0.0,
    /** Поворот устройства на штативе: 0 = портрет, 90/180/270 — как в Surface.ROTATION_*. */
    private val deviceRotationDegrees: Int = 0,
    /** Частота сети: 50 (Европа) или 60 (США). Сейчас не используется, оставлено для совместимости. */
    private val mainsFlickerHz: Int = 50,
    private val calibrationTimeoutMs: Long = 5_000L,
    /** Если задан, JPEG отдаётся сюда (имя файла, байты) вместо записи в outputDir. Вызывается в потоке записи. */
    private val jpegSink: ((String, ByteArray) -> Unit)? = null,
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {
    private companion object {
        const val TAG = "TripodCamera"
        const val MIN_CALIBRATION_FRAMES = 10
        const val NS_IN_SEC = 1_000_000_000L
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var previewReader: ImageReader? = null
    private var chars: CameraCharacteristics? = null

    // Поток камеры: все колбэки Camera2 и логика калибровки идут сюда (однопоточно).
    private val backgroundThread = HandlerThread("TripodCameraThread").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    // Таймер съёмки и отдельный поток записи на диск.
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var intervalTask: ScheduledFuture<*>? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    // Параметры камеры
    private var cameraId: String = "0"
    private var minIso = 100
    private var maxIso = 100
    private var exposureRange: android.util.Range<Long>? = null
    private var sensorOrientation = 90
    private var afSupported = false
    private var intervalSeconds = 5L

    // Настройки экспозиции от пользователя
    private var manualExposure = false
    private var shutterDenom = 500
    private var manualIso = 400

    /**
     * manual = false: яркость как при автокалибровке, но выдержка не длиннее 1/shutterDenom,
     *                 недостающее добирается ISO.
     * manual = true:  выдержка 1/shutterDenom и ISO ровно как заданы.
     * Вызывать до startCamera().
     */
    fun setManualExposure(manual: Boolean, shutterDenom: Int, iso: Int) {
        this.manualExposure = manual
        this.shutterDenom = shutterDenom.coerceIn(1, 8000)
        this.manualIso = iso.coerceAtLeast(50)
    }

    // Калибровка (трогаем только из backgroundHandler)
    private var calibrationDone = false
    private var frameCounter = 0
    private var lastResult: TotalCaptureResult? = null
    private val timeoutRunnable = Runnable { finishCalibration("таймаут калибровки") }

    // Зафиксированные значения
    private var lockedExposureNs = 16_666_666L
    private var lockedIso = 100
    private var lockedFocusDistance: Float? = null
    private var lockedGains: RggbChannelVector? = null
    private var lockedTransform: ColorSpaceTransform? = null

    private var frameCounterFile = 0L

    // ---------------------------------------------------------------- 1. Выбор камеры и размера

    private fun initCameraSpecs(): Size {
        val id = cameraIdOverride ?: cameraManager.cameraIdList.firstOrNull { candidate ->
            cameraManager.getCameraCharacteristics(candidate)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"
        cameraId = id

        val c = cameraManager.getCameraCharacteristics(id)
        chars = c

        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            minIso = it.lower
            maxIso = it.upper
        }
        exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        afSupported = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)

        // Только обычная карта: getOutputSizes из SCALER_STREAM_CONFIGURATION_MAP,
        // без SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION.
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val all = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val fourByThree = all.filter { it.width * 3 == it.height * 4 || it.height * 3 == it.width * 4 }
        var candidates = fourByThree.ifEmpty { all }
        if (maxMegapixels > 0.0) {
            val limit = maxMegapixels * 1_000_000.0
            val capped = candidates.filter { it.width.toLong() * it.height <= limit }
            if (capped.isNotEmpty()) candidates = capped
        }
        val size = candidates.maxByOrNull { it.width.toLong() * it.height } ?: Size(4000, 3000)
        log("Камера $id, JPEG ${size.width}x${size.height}, ISO $minIso..$maxIso, AF=$afSupported")
        return size
    }

    private fun pickPreviewSize(): Size {
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val target = 640 * 480
        return sizes.filter { it.width * it.height >= target }
            .minByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(640, 480)
    }

    // ---------------------------------------------------------------- 2. Запуск

    @SuppressLint("MissingPermission", "WakelockTimeout")
    fun startCamera(intervalSeconds: Long = 5) {
        if (stopped.get() || !started.compareAndSet(false, true)) return
        this.intervalSeconds = intervalSeconds.coerceAtLeast(1)

        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalCamera:tripod").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            log("WakeLock недоступен: ${e.message}")
        }

        val bestSize = try {
            initCameraSpecs()
        } catch (e: Exception) {
            log("Ошибка инициализации камеры: $e")
            stop()
            return
        }

        jpegReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 4).apply {
            setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireNextImage()
                } catch (e: Exception) {
                    log("acquireNextImage: $e")
                    null
                } ?: return@setOnImageAvailableListener
                val bytes = try {
                    val buffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } finally {
                    image.close()
                }
                try {
                    ioExecutor.execute { saveJpeg(bytes) }
                } catch (_: RejectedExecutionException) {
                    // stop() уже вызван
                }
            }, backgroundHandler)
        }

        // Небольшой YUV-ридер вместо «пустого» SurfaceTexture: кадры сразу закрываются,
        // конвейер 3A на прошивках Xiaomi работает штатно.
        val previewSize = pickPreviewSize()
        previewReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                try {
                    reader.acquireLatestImage()?.close()
                } catch (_: Exception) {
                }
            }, backgroundHandler)
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (stopped.get()) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createSessionAndCalibrate()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    log("Камера отключена")
                    camera.close()
                    stop()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    log("Ошибка камеры: $error")
                    camera.close()
                    stop()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            log("openCamera: $e")
            stop()
        }
    }

    // ---------------------------------------------------------------- 3. Сессия и калибровка

    @Suppress("DEPRECATION")
    private fun createSessionAndCalibrate() {
        val device = cameraDevice ?: return
        val jpegSurface = jpegReader?.surface ?: return
        val previewSurface: Surface = previewReader?.surface ?: return

        try {
            device.createCaptureSession(
                listOf(jpegSurface, previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (stopped.get()) {
                            session.close()
                            return
                        }
                        captureSession = session
                        startCalibration(device, session, previewSurface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        log("onConfigureFailed")
                        stop()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            log("createCaptureSession: $e")
            stop()
        }
    }

    private fun startCalibration(device: CameraDevice, session: CameraCaptureSession, previewSurface: Surface) {
        try {
            val repeating = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                if (afSupported) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                }
            }

            session.setRepeatingRequest(repeating.build(), CalibrationCallback(), backgroundHandler)

            // Триггер автофокуса — ОДИН раз, отдельным capture(); в repeating-запросе его нет.
            if (afSupported) {
                val trigger = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                session.capture(trigger.build(), null, backgroundHandler)
            }

            backgroundHandler.postDelayed(timeoutRunnable, calibrationTimeoutMs)
        } catch (e: Exception) {
            log("startCalibration: $e")
            stop()
        }
    }

    private inner class CalibrationCallback : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (calibrationDone) return
            lastResult = result
            frameCounter++
            if (frameCounter < MIN_CALIBRATION_FRAMES) return

            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)

            val afOk = !afSupported ||
                afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            val aeOk = aeState == null ||
                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
            val awbOk = awbState == null ||
                awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED

            if (afOk && aeOk && awbOk) finishCalibration("калибровка завершена")
        }
    }

    private fun finishCalibration(reason: String) {
        if (calibrationDone || stopped.get()) return
        calibrationDone = true
        backgroundHandler.removeCallbacks(timeoutRunnable)

        val result = lastResult
        if (result == null) {
            log("Калибровка не дала ни одного кадра — остановка")
            stop()
            return
        }
        log("Калибровка: $reason")

        try {
            captureSession?.stopRepeating()
        } catch (e: Exception) {
            log("stopRepeating: $e")
        }

        lockedFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        lockedGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        lockedTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        lockExposure(result)

        log(
            "Зафиксировано: выдержка ${lockedExposureNs / 1_000_000.0} мс, ISO $lockedIso, " +
                "фокус $lockedFocusDistance, WB вручную=${lockedGains != null && lockedTransform != null}"
        )
        startIntervalShooting()
    }

    // ---------------------------------------------------------------- 4. Пересчёт экспозиции

    private fun lockExposure(result: CaptureResult) {
        val rangeMin = exposureRange?.lower ?: 100_000L
        val rangeMax = exposureRange?.upper ?: NS_IN_SEC
        val shutterCap = (NS_IN_SEC / shutterDenom).coerceIn(rangeMin, rangeMax)

        if (manualExposure) {
            // Ручной режим: выдержка и ISO ровно как заданы.
            lockedExposureNs = shutterCap
            lockedIso = manualIso.coerceIn(minIso, maxIso)
            return
        }

        // Авто по калибровке: яркость как у автоэкспозиции, но выдержка не длиннее лимита,
        // недостающее добирается ISO.
        val autoNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_666_666L
        val autoIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: minIso
        val total = autoNs.toDouble() * autoIso // «общая экспозиция» в нс*ISO

        val exposure = minOf(shutterCap, (total / minIso).toLong()).coerceAtLeast(rangeMin)
        lockedExposureNs = exposure
        lockedIso = (total / exposure).roundToInt().coerceIn(minIso, maxIso)
    }

    // ---------------------------------------------------------------- 5. Таймер

    private fun startIntervalShooting() {
        if (stopped.get()) return
        try {
            intervalTask = scheduler.scheduleAtFixedRate({
                // Исключение внутри scheduleAtFixedRate молча отменило бы все следующие запуски.
                try {
                    captureTripodFrame()
                } catch (e: Exception) {
                    log("Ошибка съёмки кадра: $e")
                }
            }, 500, intervalSeconds * 1000, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // stop() уже вызван
        }
    }

    // ---------------------------------------------------------------- 6. Кадр

    private fun captureTripodFrame() {
        if (stopped.get()) return
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = jpegReader?.surface ?: return
        val c = chars ?: return

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(surface)

            set(CaptureRequest.JPEG_QUALITY, 99.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation - deviceRotationDegrees + 360) % 360)

            // Резкость и шумоподавление — только если поддерживаются.
            if (c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                    ?.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY) == true
            ) {
                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            }
            if (c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                    ?.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true
            ) {
                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }

            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            // Фокус: ручной режим + сохранённая дистанция линзы.
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            lockedFocusDistance?.let { set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }

            // Экспозиция: полностью ручная.
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, lockedIso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedExposureNs)

            // Баланс белого: ручные коэффициенты из калибровки (если устройство их отдаёт).
            val gains = lockedGains
            val transform = lockedTransform
            if (gains != null && transform != null) {
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
            } else {
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }

            // Стабилизация на штативе вредна.
            if (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true
            ) {
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
            if (c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == true
            ) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
        }

        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                log("Кадр не снят, причина: ${failure.reason}")
            }
        }, backgroundHandler)
    }

    // ---------------------------------------------------------------- Запись на диск (поток ioExecutor)

    private fun saveJpeg(bytes: ByteArray) {
        try {
            val name = "TRIPOD_${System.currentTimeMillis()}_${frameCounterFile++}.jpg"
            val sink = jpegSink
            if (sink != null) {
                sink(name, bytes)
                return
            }
            if (!outputDir.exists()) outputDir.mkdirs()
            val tmp = File(outputDir, "$name.tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(File(outputDir, name))) {
                log("Не удалось переименовать $name")
            }
        } catch (e: Exception) {
            log("Ошибка записи JPEG: $e")
        }
    }

    // ---------------------------------------------------------------- Остановка (идемпотентна)

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return

        backgroundHandler.removeCallbacks(timeoutRunnable)
        try { intervalTask?.cancel(false) } catch (_: Exception) {}
        scheduler.shutdownNow()

        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { jpegReader?.close() } catch (_: Exception) {}
        jpegReader = null
        try { previewReader?.close() } catch (_: Exception) {}
        previewReader = null

        // Уже поставленные в очередь записи дописываются, новые не принимаются.
        ioExecutor.shutdown()

        backgroundThread.quitSafely()
        // join нельзя из самого потока камеры (stop() вызывается из onError/onDisconnected).
        if (Thread.currentThread() != backgroundThread) {
            try { backgroundThread.join(1000) } catch (_: InterruptedException) {}
        }

        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        log("Камера остановлена")
    }
}
