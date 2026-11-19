package com.example.tripodcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import java.io.File

class TripodService : Service() {

    private var camera: TripodIntervalCamera? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("tripod", "Съёмка", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, "tripod")
            .setContentTitle("Интервальная съёмка идёт")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)

        val interval = intent?.getLongExtra(EXTRA_INTERVAL, 5L) ?: 5L
        val manual = intent?.getBooleanExtra(MainActivity.EXTRA_MANUAL, false) ?: false
        val denom = intent?.getIntExtra(MainActivity.EXTRA_SHUTTER_DENOM, 500) ?: 500
        val iso = intent?.getIntExtra(MainActivity.EXTRA_ISO, 400) ?: 400

        camera?.stop()
        camera = TripodIntervalCamera(
            context = this,
            outputDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "tripod"),
            jpegSink = { name, bytes -> saveToGallery(name, bytes) }
        ).also {
            it.setManualExposure(manual, denom, iso)
            it.startCamera(interval)
        }

        return START_NOT_STICKY
    }

    /** Сохраняет JPEG в Pictures/TripodCamera, видно в галерее. Разрешение на память не нужно. */
    private fun saveToGallery(name: String, bytes: ByteArray) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TripodCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e("TripodService", "Ошибка сохранения: $e")
        }
    }

    override fun onDestroy() {
        camera?.stop()
        camera = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_INTERVAL = "interval"
    }
}
