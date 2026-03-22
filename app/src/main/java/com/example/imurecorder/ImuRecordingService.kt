package com.example.imurecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
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

class ImuRecordingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isRecording = false
    private var sessionStartElapsedNs: Long = 0L
    private var sessionStartWallMs: Long = 0L
    private var accelRequestedHz: Double = 0.0
    private var gyroRequestedHz: Double = 0.0
    private var accelRequestedUs: Int = 0
    private var gyroRequestedUs: Int = 0
    private var accelCount: Long = 0
    private var gyroCount: Long = 0
    private var currentFile: File? = null
    private val currentAccuracy = mutableMapOf<Int, Int>()
    private val csvRecorder = CsvRecorder()

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val accelHz = intent.getDoubleExtra(EXTRA_ACCEL_HZ, 0.0)
                val gyroHz = intent.getDoubleExtra(EXTRA_GYRO_HZ, 0.0)
                startRecording(accelHz, gyroHz)
            }

            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording) return

        val sensorType = event.sensor.type
        if (sensorType != Sensor.TYPE_ACCELEROMETER && sensorType != Sensor.TYPE_GYROSCOPE) return

        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            accelCount++
        } else {
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

        if ((accelCount + gyroCount) % 50L == 0L) {
            publishSnapshot()
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensor?.let { currentAccuracy[it.type] = accuracy }
    }

    private fun startRecording(parsedAccelHz: Double, parsedGyroHz: Double) {
        if (isRecording) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
            return
        }

        val accel = accelerometer
        val gyro = gyroscope
        if (accel == null || gyro == null || parsedAccelHz <= 0.0 || parsedGyroHz <= 0.0) {
            stopSelf()
            return
        }

        val outputFile = createOutputFile() ?: run {
            stopSelf()
            return
        }

        val outputStream = try {
            outputFile.outputStream()
        } catch (_: IOException) {
            stopSelf()
            return
        }

        accelRequestedHz = parsedAccelHz
        gyroRequestedHz = parsedGyroHz
        accelRequestedUs = hzToSamplingPeriodUs(parsedAccelHz)
        gyroRequestedUs = hzToSamplingPeriodUs(parsedGyroHz)
        currentFile = outputFile
        sessionStartWallMs = System.currentTimeMillis()
        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()
        accelCount = 0L
        gyroCount = 0L
        currentAccuracy.clear()

        csvRecorder.start(outputStream)
        csvRecorder.enqueue(
            "sensor_timestamp_ns,estimated_epoch_ms,sensor_type,sensor_name,requested_rate_hz,requested_period_us,x,y,z,accuracy"
        )

        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            sensorManager.registerListener(this, accel, accelRequestedUs, 0)
            sensorManager.registerListener(this, gyro, gyroRequestedUs, 0)
        } catch (_: Exception) {
            csvRecorder.stop()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        isRecording = true
        publishSnapshot()
    }

    private fun stopRecording() {
        if (!isRecording && !csvRecorder.isRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        sensorManager.unregisterListener(this)
        isRecording = false
        csvRecorder.stop()
        releaseWakeLock()

        val completedPath = currentFile?.absolutePath
        RecordingStateStore.update {
            it.copy(
                isRecording = false,
                accelCount = accelCount,
                gyroCount = gyroCount,
                queueDepth = csvRecorder.queueSize(),
                currentOutputPath = completedPath ?: it.currentOutputPath,
                lastCompletedFilePath = completedPath
            )
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishSnapshot() {
        RecordingStateStore.update {
            it.copy(
                isRecording = isRecording,
                accelRequestedHz = accelRequestedHz,
                gyroRequestedHz = gyroRequestedHz,
                accelRequestedUs = accelRequestedUs,
                gyroRequestedUs = gyroRequestedUs,
                accelCount = accelCount,
                gyroCount = gyroCount,
                queueDepth = csvRecorder.queueSize(),
                currentOutputPath = currentFile?.absolutePath ?: it.currentOutputPath,
                lastCompletedFilePath = if (isRecording) null else currentFile?.absolutePath
            )
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ImuRecordingService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (isRecording) {
            "Accel $accelCount samples, gyro $gyroCount samples"
        } else {
            "Preparing recording"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("IMU Recorder")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(isRecording)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IMU recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps long-running IMU recordings active while the screen is off."
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:imu-recording"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createOutputFile(): File? {
        val recordingsDir = File(getExternalFilesDir(null), "recordings")
        if (!recordingsDir.exists() && !recordingsDir.mkdirs()) {
            return null
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir, "imu_recording_$stamp.csv")
    }

    private fun hzToSamplingPeriodUs(hz: Double): Int {
        return (1_000_000.0 / hz).roundToInt().coerceAtLeast(1)
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

    companion object {
        const val ACTION_START = "com.example.imurecorder.action.START"
        const val ACTION_STOP = "com.example.imurecorder.action.STOP"
        const val EXTRA_ACCEL_HZ = "extra_accel_hz"
        const val EXTRA_GYRO_HZ = "extra_gyro_hz"

        private const val CHANNEL_ID = "imu_recording"
        private const val NOTIFICATION_ID = 1001
    }
}
