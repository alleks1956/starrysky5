package com.example.tripodcamera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_SHUTTER_DENOM = "shutter_denom"
        const val EXTRA_ISO = "iso"
    }

    private lateinit var intervalInput: EditText
    private lateinit var manualCheck: CheckBox
    private lateinit var shutterInput: EditText
    private lateinit var isoInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f }
        fun numberField(default: String) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(default)
        }

        root.addView(label("Интервал между кадрами, секунд:"))
        intervalInput = numberField("5")
        root.addView(intervalInput)

        root.addView(label("Выдержка не длиннее 1/N с (N = 500 значит 1/500):"))
        shutterInput = numberField("500")
        root.addView(shutterInput)

        manualCheck = CheckBox(this).apply {
            text = "Ручной режим (выдержка и ISO ровно как заданы; иначе авто с лимитом выдержки)"
            isChecked = false
        }
        root.addView(manualCheck)

        root.addView(label("ISO (только для ручного режима):"))
        isoInput = numberField("400")
        root.addView(isoInput)

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
        val manual = manualCheck.isChecked
        val denom = shutterInput.text.toString().toIntOrNull()?.coerceIn(1, 8000) ?: 500
        val iso = isoInput.text.toString().toIntOrNull()?.coerceAtLeast(50) ?: 400

        startForegroundService(
            Intent(this, TripodService::class.java)
                .putExtra(TripodService.EXTRA_INTERVAL, interval)
                .putExtra(EXTRA_MANUAL, manual)
                .putExtra(EXTRA_SHUTTER_DENOM, denom)
                .putExtra(EXTRA_ISO, iso)
        )
        val mode = if (manual) "ручной, 1/$denom с, ISO $iso" else "авто, выдержка не длиннее 1/$denom с"
        status.text = "Съёмка запущена, интервал $interval с\nЭкспозиция: $mode\nФото: Pictures/TripodCamera"
    }
}
