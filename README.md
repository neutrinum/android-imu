# IMU Recorder (VS Code-friendly Android project)

A minimal Android app that records **raw accelerometer** and **raw gyroscope** samples to a local CSV file with timestamps.

## What it does

- Uses only `TYPE_ACCELEROMETER` and `TYPE_GYROSCOPE`
- Lets you choose **individual requested rates in Hz** for each sensor
- Starts and stops recording with buttons
- Saves one CSV file per session under the app's local files directory
- Logs:
  - `sensor_timestamp_ns` (from Android `SensorEvent.timestamp`)
  - `estimated_epoch_ms` (best-effort conversion to wall-clock time)
  - sensor type and sensor name
  - requested rate and requested sampling period
  - x, y, z values
  - accuracy

## File location on the phone

The app writes CSV files to:

`Android/data/com.example.imurecorder/files/recordings/`

When the app is open it also shows the full file path of the latest recording.

## Important note about sampling rates

Android treats requested sensor rates as **requests, not guarantees**. The phone may deliver samples a little faster or slower than what you ask for.

For the most reliable analysis, use the `sensor_timestamp_ns` column in the CSV to measure the real delivered sample timing.

## Project structure

This archive is a plain Gradle Android project that is comfortable to edit in **Visual Studio Code**. It does **not** include a Gradle wrapper JAR, so for pure VS Code usage you should have Gradle installed locally.

## Codespaces

This repo now includes a `.devcontainer/` setup for GitHub Codespaces.

When you open the repo in a Codespace, the container will install:

- JDK 17
- Gradle 8.7
- Android SDK command-line tools
- Android platform 34
- Android build-tools 34.0.0
- `adb`

After the Codespace finishes building, compile with:

```bash
gradle assembleDebug
```

The APK should appear at:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

Codespaces can compile the APK, but it cannot talk to your phone over local USB. The normal workflow is to build in Codespaces, download the APK, and install it on the phone from your own machine.

## Push this folder to GitHub

This directory was originally not initialized as a git repository. To push it to:

`https://github.com/neutrinum/android-imu.git`

run:

```bash
git init -b main
git remote add origin https://github.com/neutrinum/android-imu.git
git add .
git commit -m "Initial import with Codespaces support"
git push -u origin main
```

If GitHub says the remote already has commits, use:

```bash
git pull --rebase origin main
git push -u origin main
```

## Build options

### Option A: VS Code + command line

You will need:

- JDK 17+
- Android SDK command-line tools
- Android platform-tools (`adb`)
- Android SDK Platform 34
- Android Build-Tools 34.x
- Gradle 8.7+ installed on your machine, unless you use Codespaces

From the project root, build with:

```bash
gradle assembleDebug
```

Then install to your phone with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Option B: Open in Android Studio once

Even though the project is VS Code-friendly, you can also open it in Android Studio and let Android Studio handle the SDK and Gradle sync.

## Suggested Android SDK packages

Using `sdkmanager`, install at least:

```bash
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## VS Code task ideas

You can add a custom build task that runs:

```bash
gradle assembleDebug
```

and another that runs:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Notes for your use case

- The app records both sensors **simultaneously** in one CSV file.
- Each sensor can have its **own requested rate**.
- If you want the same rate for both, just enter the same number in both boxes.
- The manifest already includes `HIGH_SAMPLING_RATE_SENSORS`.
- On Android, rates above what the framework/device will deliver are silently limited by the system or hardware.

## Possible next improvements

- Add a toggle for a single shared rate
- Add a live estimate of the true delivered sample rate
- Add pause/resume
- Add a share button for the latest CSV
- Add a foreground service to keep recording reliably with the screen off
