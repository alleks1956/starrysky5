package com.example.tripodcamera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var intervalInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Интервал между кадрами, секунд:"
            textSize = 16f
        })
        intervalInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("5")
        }
        root.addView(intervalInput)

        root.addView(Button(this).apply {
            text = "Старт"
            setOnClickListener { onStartClicked() }
        })
        root.addView(Button(this).apply {
            text = "Стоп"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TripodService::class.java))
                status.text = "Остановлено"
            }
        })

        status = TextView(this).apply {
            text = "Фото сохраняются в галерею: Pictures/TripodCamera"
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)

        setContentView(root)
    }

    private fun onStartClicked() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startShooting() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startShooting()
        } else {
            status.text = "Нужно разрешение на камеру"
        }
    }

    private fun startShooting() {
        val interval = intervalInput.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 5L
        startForegroundService(
            Intent(this, TripodService::class.java).putExtra(TripodService.EXTRA_INTERVAL, interval)
        )
        status.text = "Съёмка запущена, интервал $interval с.\nФото: галерея, Pictures/TripodCamera"
    }
}
