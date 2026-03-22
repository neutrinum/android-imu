package com.example.imurecorder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SensorEventListener {

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

    private var isRecording = false
    private var sessionStartElapsedNs: Long = 0L
    private var sessionStartWallMs: Long = 0L
    private var accelRequestedHz: Double = 0.0
    private var gyroRequestedHz: Double = 0.0
    private var accelRequestedUs: Int = 0
    private var gyroRequestedUs: Int = 0
    private var accelCount: Long = 0
    private var gyroCount: Long = 0
    private var lastUiUpdateRealtimeMs: Long = 0L
    private var currentOutputLabel: String = "No recording yet"
    private var currentRecordingFile: File? = null
    private var pendingExportSourceFile: File? = null
    private val currentAccuracy = mutableMapOf<Int, Int>()
    private val csvRecorder = CsvRecorder()

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val sourceFile = pendingExportSourceFile
            pendingExportSourceFile = null

            if (sourceFile == null) {
                return@registerForActivityResult
            }

            if (uri == null) {
                currentOutputLabel = sourceFile.absolutePath
                filePathText.text = currentOutputLabel
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
        stopButton.setOnClickListener { stopRecording(promptForSave = true) }

        updateSensorHelperText()
        filePathText.text = currentOutputLabel
        showIdleStatus()
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) {
            stopRecording(promptForSave = false)
        }
    }

    override fun onDestroy() {
        stopRecording(promptForSave = false)
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording) return

        val sensorType = event.sensor.type
        if (sensorType != Sensor.TYPE_ACCELEROMETER && sensorType != Sensor.TYPE_GYROSCOPE) return

        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            accelCount++
        } else if (sensorType == Sensor.TYPE_GYROSCOPE) {
            gyroCount++
        }

        val estimatedEpochMs = sessionStartWallMs + ((event.timestamp - sessionStartElapsedNs) / 1_000_000L)
        val requestedHz = if (sensorType == Sensor.TYPE_ACCELEROMETER) accelRequestedHz else gyroRequestedHz
        val requestedUs = if (sensorType == Sensor.TYPE_ACCELEROMETER) accelRequestedUs else gyroRequestedUs
        val sensorLabel = if (sensorType == Sensor.TYPE_ACCELEROMETER) "accelerometer" else "gyroscope"
        val accuracy = currentAccuracy[sensorType] ?: SensorManager.SENSOR_STATUS_UNRELIABLE

        csvRecorder.enqueue(
            listOf(
                event.timestamp.toString(),
                estimatedEpochMs.toString(),
                sensorLabel,
                csvEscape(event.sensor.name),
                requestedHz.toString(),
                requestedUs.toString(),
                event.values.getOrElse(0) { 0f }.toString(),
                event.values.getOrElse(1) { 0f }.toString(),
                event.values.getOrElse(2) { 0f }.toString(),
                accuracy.toString()
            ).joinToString(",")
        )

        maybeRefreshStatusUi()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensor?.let { currentAccuracy[it.type] = accuracy }
    }

    private fun startRecording() {
        if (isRecording) return

        val accel = accelerometer
        val gyro = gyroscope
        if (accel == null || gyro == null) {
            Toast.makeText(this, "Accelerometer or gyroscope is not available on this phone.", Toast.LENGTH_LONG).show()
            return
        }

        val parsedAccelHz = parsePositiveHz(accelRateEdit.text?.toString(), "accelerometer") ?: return
        val parsedGyroHz = parsePositiveHz(gyroRateEdit.text?.toString(), "gyroscope") ?: return

        val outputFile = createOutputFile() ?: run {
            Toast.makeText(this, "Could not create output file.", Toast.LENGTH_LONG).show()
            return
        }

        beginRecording(parsedAccelHz, parsedGyroHz, accel, gyro, outputFile)
    }

    private fun beginRecording(
        parsedAccelHz: Double,
        parsedGyroHz: Double,
        accel: Sensor,
        gyro: Sensor,
        outputFile: File
    ) {
        val outputStream = try {
            outputFile.outputStream()
        } catch (e: IOException) {
            Toast.makeText(this, "Could not open output file: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        accelRequestedHz = parsedAccelHz
        gyroRequestedHz = parsedGyroHz
        accelRequestedUs = hzToSamplingPeriodUs(parsedAccelHz)
        gyroRequestedUs = hzToSamplingPeriodUs(parsedGyroHz)
        currentRecordingFile = outputFile
        currentOutputLabel = outputFile.absolutePath

        sessionStartWallMs = System.currentTimeMillis()
        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()
        accelCount = 0
        gyroCount = 0
        lastUiUpdateRealtimeMs = 0L

        csvRecorder.start(outputStream)
        csvRecorder.enqueue(
            "sensor_timestamp_ns,estimated_epoch_ms,sensor_type,sensor_name,requested_rate_hz,requested_period_us,x,y,z,accuracy"
        )

        try {
            sensorManager.registerListener(this, accel, accelRequestedUs, 0)
            sensorManager.registerListener(this, gyro, gyroRequestedUs, 0)
        } catch (e: Exception) {
            csvRecorder.stop()
            Toast.makeText(this, "Could not register sensors: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        isRecording = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        accelRateEdit.isEnabled = false
        gyroRateEdit.isEnabled = false
        filePathText.text = currentOutputLabel
        maybeRefreshStatusUi(force = true)
    }

    private fun stopRecording(promptForSave: Boolean) {
        if (!isRecording && !csvRecorder.isRunning()) return

        sensorManager.unregisterListener(this)
        isRecording = false
        csvRecorder.stop()
        startButton.isEnabled = true
        stopButton.isEnabled = false
        accelRateEdit.isEnabled = true
        gyroRateEdit.isEnabled = true
        showIdleStatus()

        val recordedFile = currentRecordingFile
        if (recordedFile == null || !recordedFile.exists()) {
            filePathText.text = currentOutputLabel
            return
        }

        currentOutputLabel = recordedFile.absolutePath
        filePathText.text = currentOutputLabel

        if (promptForSave) {
            pendingExportSourceFile = recordedFile
            createDocumentLauncher.launch(recordedFile.name)
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
            currentOutputLabel = destinationUri.toString()
            filePathText.text = currentOutputLabel
            Toast.makeText(this, "Recording exported.", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            currentOutputLabel = sourceFile.absolutePath
            filePathText.text = currentOutputLabel
            Toast.makeText(this, "Export failed. Local copy kept at ${sourceFile.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showIdleStatus() {
        val accelInfo = accelerometer?.let { "${it.name} (minDelay=${it.minDelay} us)" } ?: "missing"
        val gyroInfo = gyroscope?.let { "${it.name} (minDelay=${it.minDelay} us)" } ?: "missing"
        statusText.text = buildString {
            appendLine("Idle")
            appendLine("Accelerometer: $accelInfo")
            appendLine("Gyroscope: $gyroInfo")
            append("Last counts - accel: $accelCount, gyro: $gyroCount")
        }
    }

    private fun maybeRefreshStatusUi(force: Boolean = false) {
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - lastUiUpdateRealtimeMs < 250L) return
        lastUiUpdateRealtimeMs = nowMs

        statusText.text = buildString {
            appendLine(if (isRecording) "Recording" else "Idle")
            appendLine("Requested accel: ${formatHz(accelRequestedHz)} Hz (${accelRequestedUs} us)")
            appendLine("Requested gyro: ${formatHz(gyroRequestedHz)} Hz (${gyroRequestedUs} us)")
            appendLine("Samples so far - accel: $accelCount, gyro: $gyroCount")
            append("CSV queue depth: ${csvRecorder.queueSize()}")
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

    private fun createOutputFile(): File? {
        val recordingsDir = File(getExternalFilesDir(null), "recordings")
        if (!recordingsDir.exists() && !recordingsDir.mkdirs()) {
            return null
        }
        return File(recordingsDir, buildSuggestedFileName())
    }

    private fun buildSuggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "imu_recording_$stamp.csv"
    }

    private fun hzToSamplingPeriodUs(hz: Double): Int {
        return (1_000_000.0 / hz).roundToInt().coerceAtLeast(1)
    }

    private fun periodUsToHzText(periodUs: Int): String {
        if (periodUs <= 0) return "unknown"
        val hz = 1_000_000.0 / periodUs.toDouble()
        return formatHz(hz) + " Hz"
    }

    private fun formatHz(hz: Double): String {
        return if (hz == 0.0) "0" else String.format(Locale.US, "%.2f", hz)
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private class CsvRecorder {
        private val stopToken = "__CSV_STOP__"
        private val queue = LinkedBlockingQueue<String>()
        private var writerThread: Thread? = null

        @Volatile
        private var running = false

        @Volatile
        private var writer: BufferedWriter? = null

        fun start(outputStream: OutputStream) {
            stop()
            queue.clear()
            writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
            running = true
            writerThread = thread(start = true, name = "csv-writer") {
                try {
                    while (true) {
                        val line = queue.take()
                        if (line == stopToken) break
                        writer?.append(line)
                        writer?.newLine()
                    }
                    writer?.flush()
                } finally {
                    writer?.close()
                    writer = null
                }
            }
        }

        fun enqueue(line: String) {
            if (running) {
                queue.offer(line)
            }
        }

        fun stop() {
            if (!running && writerThread == null) {
                return
            }
            running = false
            queue.offer(stopToken)
            writerThread?.join(3000)
            writerThread = null
            queue.clear()
        }

        fun queueSize(): Int = queue.size.coerceAtLeast(0)

        fun isRunning(): Boolean = running || writerThread != null
    }
}
