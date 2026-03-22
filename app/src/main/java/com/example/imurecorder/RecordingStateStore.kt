package com.example.imurecorder

data class RecordingSnapshot(
    val isRecording: Boolean = false,
    val accelRequestedHz: Double = 0.0,
    val gyroRequestedHz: Double = 0.0,
    val accelRequestedUs: Int = 0,
    val gyroRequestedUs: Int = 0,
    val accelCount: Long = 0L,
    val gyroCount: Long = 0L,
    val queueDepth: Int = 0,
    val currentOutputPath: String = "No recording yet",
    val lastCompletedFilePath: String? = null
)

object RecordingStateStore {
    @Volatile
    var snapshot: RecordingSnapshot = RecordingSnapshot()
        private set

    fun update(transform: (RecordingSnapshot) -> RecordingSnapshot) {
        synchronized(this) {
            snapshot = transform(snapshot)
        }
    }
}
