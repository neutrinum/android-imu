package com.example.imurecorder

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private lateinit var accelRateEdit: TextInputEditText
    private lateinit var gyroRateEdit: TextInputEditText
    private lateinit var accelHelperText: TextView
    private lateinit var gyroHelperText: TextView
    private lateinit var statusText: TextView
    private lateinit var filePathText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingExportAfterStop = false
    private var pendingExportSourceFile: File? = null
    private var lastExportPromptedPath: String? = null

    private val statusPoller = object : Runnable {
        override fun run() {
            syncUiFromState()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val sourceFile = pendingExportSourceFile
            pendingExportSourceFile = null

            if (sourceFile == null) {
                return@registerForActivityResult
            }

            if (uri == null) {
                RecordingStateStore.update {
                    it.copy(currentOutputPath = sourceFile.absolutePath)
                }
                filePathText.text = sourceFile.absolutePath
                Toast.makeText(
                    this,
                    "Save cancelled. Recording kept locally at ${sourceFile.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }

            exportRecordingToDestination(sourceFile, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accelRateEdit = findViewById(R.id.accelRateEdit)
        gyroRateEdit = findViewById(R.id.gyroRateEdit)
        accelHelperText = findViewById(R.id.accelHelperText)
        gyroHelperText = findViewById(R.id.gyroHelperText)
        statusText = findViewById(R.id.statusText)
        filePathText = findViewById(R.id.filePathText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener { startRecording() }
        stopButton.setOnClickListener { stopRecording() }

        updateSensorHelperText()
        syncUiFromState()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(statusPoller)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(statusPoller)
        super.onPause()
    }

    private fun startRecording() {
        if (RecordingStateStore.snapshot.isRecording) return

        if (accelerometer == null || gyroscope == null) {
            Toast.makeText(this, "Accelerometer or gyroscope is not available on this phone.", Toast.LENGTH_LONG).show()
            return
        }

        val parsedAccelHz = parsePositiveHz(accelRateEdit.text?.toString(), "accelerometer") ?: return
        val parsedGyroHz = parsePositiveHz(gyroRateEdit.text?.toString(), "gyroscope") ?: return

        pendingExportAfterStop = false
        pendingExportSourceFile = null
        lastExportPromptedPath = null

        val serviceIntent = Intent(this, ImuRecordingService::class.java).apply {
            action = ImuRecordingService.ACTION_START
            putExtra(ImuRecordingService.EXTRA_ACCEL_HZ, parsedAccelHz)
            putExtra(ImuRecordingService.EXTRA_GYRO_HZ, parsedGyroHz)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopRecording() {
        if (!RecordingStateStore.snapshot.isRecording) return

        pendingExportAfterStop = true
        val serviceIntent = Intent(this, ImuRecordingService::class.java).apply {
            action = ImuRecordingService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun syncUiFromState() {
        val snapshot = RecordingStateStore.snapshot

        filePathText.text = snapshot.currentOutputPath

        if (snapshot.isRecording) {
            startButton.isEnabled = false
            stopButton.isEnabled = true
            accelRateEdit.isEnabled = false
            gyroRateEdit.isEnabled = false
            statusText.text = buildString {
                appendLine("Recording")
                appendLine("Requested accel: ${formatHz(snapshot.accelRequestedHz)} Hz (${snapshot.accelRequestedUs} us)")
                appendLine("Requested gyro: ${formatHz(snapshot.gyroRequestedHz)} Hz (${snapshot.gyroRequestedUs} us)")
                appendLine("Samples so far - accel: ${snapshot.accelCount}, gyro: ${snapshot.gyroCount}")
                append("CSV queue depth: ${snapshot.queueDepth}")
            }
            return
        }

        startButton.isEnabled = true
        stopButton.isEnabled = false
        accelRateEdit.isEnabled = true
        gyroRateEdit.isEnabled = true
        showIdleStatus(snapshot)

        if (pendingExportAfterStop) {
            val completedPath = snapshot.lastCompletedFilePath
            if (!completedPath.isNullOrBlank() && completedPath != lastExportPromptedPath) {
                val sourceFile = File(completedPath)
                if (sourceFile.exists()) {
                    pendingExportAfterStop = false
                    pendingExportSourceFile = sourceFile
                    lastExportPromptedPath = completedPath
                    createDocumentLauncher.launch(sourceFile.name)
                }
            }
        }
    }

    private fun exportRecordingToDestination(sourceFile: File, destinationUri: Uri) {
        try {
            sourceFile.inputStream().use { input ->
                val output = contentResolver.openOutputStream(destinationUri, "w")
                    ?: throw IOException("Could not open selected destination.")
                output.use {
                    input.copyTo(it)
                }
            }
            RecordingStateStore.update {
                it.copy(currentOutputPath = destinationUri.toString())
            }
            filePathText.text = destinationUri.toString()
            Toast.makeText(this, "Recording exported.", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            RecordingStateStore.update {
                it.copy(currentOutputPath = sourceFile.absolutePath)
            }
            filePathText.text = sourceFile.absolutePath
            Toast.makeText(this, "Export failed. Local copy kept at ${sourceFile.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showIdleStatus(snapshot: RecordingSnapshot) {
        val accelInfo = accelerometer?.let { "${it.name} (minDelay=${it.minDelay} us)" } ?: "missing"
        val gyroInfo = gyroscope?.let { "${it.name} (minDelay=${it.minDelay} us)" } ?: "missing"
        statusText.text = buildString {
            appendLine("Idle")
            appendLine("Accelerometer: $accelInfo")
            appendLine("Gyroscope: $gyroInfo")
            append("Last counts - accel: ${snapshot.accelCount}, gyro: ${snapshot.gyroCount}")
        }
    }

    private fun updateSensorHelperText() {
        accelHelperText.text = accelerometer?.let {
            "Sensor: ${it.name} - minDelay=${it.minDelay} us - fastest theoretical rate about ${periodUsToHzText(it.minDelay)}"
        } ?: "Accelerometer not available"

        gyroHelperText.text = gyroscope?.let {
            "Sensor: ${it.name} - minDelay=${it.minDelay} us - fastest theoretical rate about ${periodUsToHzText(it.minDelay)}"
        } ?: "Gyroscope not available"
    }

    private fun parsePositiveHz(raw: String?, sensorLabel: String): Double? {
        val value = raw?.trim()?.toDoubleOrNull()
        if (value == null || value <= 0.0) {
            Toast.makeText(this, "Enter a positive rate in Hz for the $sensorLabel.", Toast.LENGTH_LONG).show()
            return null
        }
        return value
    }

    private fun periodUsToHzText(periodUs: Int): String {
        if (periodUs <= 0) return "unknown"
        val hz = 1_000_000.0 / periodUs.toDouble()
        return formatHz(hz) + " Hz"
    }

    private fun formatHz(hz: Double): String {
        return if (hz == 0.0) "0" else String.format(Locale.US, "%.2f", hz)
    }
}
