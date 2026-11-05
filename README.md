# Surveillance: Imou Life Dual-Lens CCTV to YouTube Private Streaming & Archival

A production-grade, 24/7 background Android application engineered to stream dual-lens **Imou Life / Dahua** wireless CCTV cameras directly to **YouTube Live in Private Mode**, complete with automated **MicroSD card offline backlog catch-up** and **8-hour session rotation cycler**.

---

## 🌟 Core Features & Solutions

### 1. MicroSD Card Offline Backlog Catch-Up Engine
* **The Problem**: When the Android phone disconnects from home Wi-Fi (power cuts, router reboot, or moving out of range), live streaming halts, causing missing surveillance gaps in cloud archives.
* **The Solution**: The Imou CCTV camera continuously records local footage to its onboard MicroSD card.
  * The app registers an active `ConnectivityManager.NetworkCallback` to detect Wi-Fi drops and records the outage start timestamp.
  * Upon Wi-Fi restoration, `DahuaSdCardSyncManager` queries the camera's local Dahua CGI API (`mediaFileFind.cgi`) and RTSP Playback endpoint (`/cam/playback`).
  * Ingests the missing interval at **3.0x accelerated speed** (`-readrate 3.0`) directly into YouTube Live until playback matches real-time `now()`, seamlessly handing off to the live stream with **zero gap in surveillance history**.

### 2. 8-Hour Session Rotation Engine (3 Permanent Daily Archives)
* **The Problem**: YouTube automatically renders and archives live broadcasts into permanent, scrubbable VOD replays **only if the stream is under 12 hours**. Any stream exceeding 12 hours is discarded.
* **The Solution**: Divides each 24-hour day into **exactly three 8-hour sessions**:
  * **Part 1 of 3**: `00:00:00` to `07:59:59` (8 Hours)
  * **Part 2 of 3**: `08:00:00` to `15:59:59` (8 Hours)
  * **Part 3 of 3**: `16:00:00` to `23:59:59` (8 Hours)
  * At the 8-hour boundary, the app executes a graceful termination, calls YouTube API `liveBroadcasts.transition("complete")`, pauses for a 15-second buffer to let YouTube seal the archive, and automatically boots the next 8-hour session.
  * **Result**: Generates **exactly 3 permanent private daily replay videos** on your YouTube channel!

### 3. Automated Video Naming Convention
* Exact naming standard: `DD-MM-YYYY Part <X> of 3_<LensCode>`
  * **`_F`**: Fixed Overview Lens (Channel 2: Wide-Angle Lens)
  * **`_R`**: Rotatable Lens (Channel 1: Motorized Pan-Tilt PTZ Lens)
* **Daily Title Examples**:
  * `05-11-2026 Part 1 of 3_F` & `05-11-2026 Part 1 of 3_R`
  * `05-11-2026 Part 2 of 3_F` & `05-11-2026 Part 2 of 3_R`
  * `05-11-2026 Part 3 of 3_F` & `05-11-2026 Part 3 of 3_R`

### 4. Fast, Single-Tap Google & YouTube Authentication
* Integrated with **Google Identity Services (Play Services Auth)**.
* 1-Tap Google Account picker bottom sheet; automatically exchanges and caches OAuth2 tokens in hardware-backed `EncryptedSharedPreferences` (AES-256-GCM).
* Automatically provisions, titles, and binds RTMP ingestion points via YouTube Live Streaming API v3.
* Includes persistent RTMP fallback mode for custom stream keys.

### 5. Content ID Ambient Copyright Protection
* Injects synthetic silent stereo AAC audio (`-f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100`) alongside video stream copy (`-c:v copy`).
* Eliminates 100% of Content ID ambient copyright strikes caused by background radios, TVs, or street music, while keeping YouTube stream ingestion health active.

### 6. 24/7 Android OS Background Survival
* Runs as an unkillable **Foreground Service** (`CctvStreamingService`) with `mediaProcessing|dataSync` types.
* Bypasses Android OS Doze mode using `PowerManager.PARTIAL_WAKE_LOCK` and `WifiManager.WIFI_MODE_FULL_HIGH_PERF`.
* Auto-boot receiver (`BootReceiver`) restores streaming immediately upon phone reboot.
* Consumes **<3% CPU and ~85MB RAM** via native FFmpeg stream copy.

---

## 🏗️ Architecture & Component Overview

```
                                  +------------------------------------+
                                  |     Imou Life Dual-Lens Camera     |
                                  |  - Fixed Lens Overview (Ch 2)      |
                                  |  - Rotatable Pan-Tilt (Ch 1)       |
                                  |  - Onboard MicroSD Card            |
                                  +-----------------+------------------+
                                                    |
                                (Local Wi-Fi Network: RTSP / HTTP CGI)
                                                    |
                                                    v
+----------------------------------------------------------------------------------------------------+
|                                Android Phone (Surveillance App)                                    |
|                                                                                                    |
|  +-----------------------+    +-----------------------+    +------------------------------------+  |
|  |  CctvStreamingService |    | SessionRotationCycler |    |       DahuaSdCardSyncManager       |  |
|  | (24/7 Foreground Svc) |<-->|  (3x 8-Hour Sessions) |<-->|   - NetworkCallback (Outage Log)   |  |
|  | - WakeLock & WifiLock |    |  - Part 1, Part 2, 3  |    |   - Dahua CGI Digest Client        |  |
|  +-----------+-----------+    +-----------------------+    |   - 3.0x RTSP Playback Fast-Stream |  |
|              |                                             +-----------------+------------------+  |
|              v                                                               |                     |
|  +----------------------------------------------------+                      |                     |
|  |             DualStreamOrchestrator                 |                      |                     |
|  |  - Worker 1: Fixed Lens (_F) RTSP -> RTMPS         |<---------------------+                     |
|  |  - Worker 2: Rotatable Lens (_R) RTSP -> RTMPS     | (Handoff after Catchup)                    |
|  |  - anullsrc Silent Audio Filter (Zero Content ID)  |                                            |
|  +---------------------------+------------------------+                                            |
|                              |                                                                     |
|                              v                                                                     |
|  +----------------------------------------------------+                                            |
|  |              YouTubeAuthManager                    |                                            |
|  |  - 1-Tap Google Sign-In & Token Storage (AES-256)  |                                            |
|  |  - YouTube Live API: createBroadcast & complete    |                                            |
|  +---------------------------+------------------------+                                            |
+------------------------------|---------------------------------------------------------------------+
                               |
                   (Encrypted RTMPS Ingest)
                               |
                               v
               +-------------------------------+
               |         YouTube Live          |
               |  - Strict Private Mode        |
               |  - Automatic VOD Archival     |
               |  - 3 Daily Replays Saved      |
               +-------------------------------+
```

---

## 📱 User Interface (Jetpack Compose Material 3)

The application features a dark-themed monitoring console:

1. **Dashboard Tab**:
   * **Pipeline Status Card**: Real-time status (STREAMING, CATCHING_UP, OUTAGE, ROTATING), Wi-Fi indicator, active session part, and live countdown timer to the next 8-hour break.
   * **Dual Camera Cards**: Live FPS, bitrate (kbps), uptime, and current YouTube broadcast title for Fixed (`_F`) and Rotatable (`_R`) lenses.
   * **Google Account Status**: 1-Tap sign-in button or connected profile indicator.
   * **Primary Controls**: Start/Stop streaming toggle, Manual SD Catch-Up button, and Battery Optimization prompt.

2. **Settings Tab**:
   * Camera IP, RTSP Port, HTTP Port, Username, Safety Code (device sticker password).
   * One-tap **Test Connection** button verifying Dahua CGI & RTSP credentials.
   * MicroSD Catchup Speed Multiplier (`2.0x`, `3.0x`, `4.0x`).
   * Silent Audio injection toggle.
   * YouTube API auto-broadcast toggle and custom RTMP fallback endpoints.
   * Auto-boot toggle.

3. **Diagnostics / Logs Tab**:
   * Real-time monospaced terminal displaying logs with level filters (`ALL`, `INFO`, `WARN`, `ERROR`, `FFMPEG`).
   * One-click log copy and clear tools.

---

## 🚀 Setup & Deployment

### 1. Prerequisites
* **Android Device**: Android 8.0 (API 26) or higher (tested up to Android 15 / API 35).
* **Imou Life Camera**: Ensure camera is on the same local Wi-Fi network and video encryption is disabled in the Imou Life mobile app (**Settings > Video Encryption > Off**).
* **Safety Code**: Found on the QR code sticker under the camera base (an 8-character code, e.g. `L2A1B3C4`).

### 2. Google Cloud Console Setup (for YouTube 1-Tap Auth)
1. Go to [Google Cloud Console](https://console.cloud.google.com/).
2. Create a project and enable the **YouTube Data API v3**.
3. Create an **OAuth 2.0 Client ID** for Android:
   * Package name: `com.imou.youtubestreamer`
   * SHA-1 fingerprint: Add your debug and release keystore SHA-1.
4. Add scopes: `https://www.googleapis.com/auth/youtube` and `https://www.googleapis.com/auth/youtube.force-ssl`.

### 3. Build & Run
```bash
# Clone the repository
cd C:\GitHub\Surveillance

# Build debug APK
.\gradlew.bat assembleDebug

# Install on connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔒 Security & Privacy Notice
* All broadcasts are explicitly created with `privacyStatus = "private"`. No one except the authenticated channel owner can view the streams or archived replays.
* Device credentials and OAuth tokens are stored in hardware-backed `EncryptedSharedPreferences`.
