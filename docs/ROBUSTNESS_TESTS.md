# Surveillance Robustness & Self-Heal Verification Matrix

Verification checklist for `v1.0.15` implementing Phases 1–7 of [`ROBUSTNESS_PLAN.md`](file:///C:/GitHub/Surveillance/docs/ROBUSTNESS_PLAN.md).

## Preconditions & Notes
- Enable **Settings $\rightarrow$ Debug: Rotation Period Override** (e.g. `10` min) when testing #10 so rotation boundaries can be verified without waiting 8 hours. Reset to `0` afterwards.
- Verify **Battery Optimization** is set to *Exempt / Unrestricted* (`PowerManager.isIgnoringBatteryOptimizations`) before running background self-heal tests.
- **OS Limitation (`am force-stop`)**: Per Android OS rules, `adb shell am force-stop com.imou.youtubestreamer` puts the package into the *stopped* state where alarms, WorkManager jobs, and `BOOT_COMPLETED` are inhibited until the user manually opens the app again. Recovery from `am force-stop` itself is forbidden by Android; recovery from `am kill`, OOM kills, crashes, updates (`MY_PACKAGE_REPLACED`), and device reboots is verified below.

## Verification Matrix

| # | Scenario | How to Test | Expected Behaviour | v1.0.15 Result |
|---|---|---|---|---|
| 1 | Wi-Fi off 2 min | Toggle phone Wi-Fi off for 2 min, then back on | `CAMERA_OFFLINE` shown; gap recorded in `gap_ledger.json`; live resumes $\le 60\text{ s}$ after return; 2-min gap backfilled automatically | _Pending device run_ |
| 2 | WAN down, Wi-Fi up | Unplug router WAN/fiber cable for 5 min while phone stays on router Wi-Fi | Detected within $\le 30\text{ s}$ (2 failed 15 s probes) $\rightarrow$ `Offline – camera recording to SD`; 5-min gap backfilled on return | _Pending device run_ |
| 3 | Camera power cycle | Unplug Imou camera power for 2 min, then plug back in | `CAMERA_OFFLINE` shown; resumes $\le 60\text{ s}$ after camera boots; playback DESCRIBE returns 404 for power-off chunks and marks them skipped cleanly | _Pending device run_ |
| 4 | YouTube ingest silently dropped | Router `DROP` firewall rule for traffic to `*.youtube.com` | `StallWatchdog` / `RtmpsTlsBridge` classifies `STALLED` within $\le 25\text{ s}$; triggers immediate probe; recovers after rule removed | _Pending device run_ |
| 5 | AP flapping | Toggle router AP off/on 5$\times$ within 60 s | `LinkDebouncer` (2-success up hysteresis + OS validation) causes $\le 1$ stop and $\le 1$ restart cycle | _Pending device run_ |
| 6 | Process kill | `adb shell am kill com.imou.youtubestreamer` | `START_STICKY`, AlarmManager (`ServiceWatchdogReceiver`), or WorkManager (`ServiceWatchdogWorker`) restarts streaming $\le 15\text{ min}$ (usually immediately) | _Pending device run_ |
| 7 | Device reboot | `adb reboot` | `BootReceiver` sees `desiredRunning=true`, restarts FGS automatically, backfills downtime since last heartbeat | _Pending device run_ |
| 8 | App update | Install new APK via in-app updater or `adb install -r` | `MY_PACKAGE_REPLACED` in `BootReceiver` restarts streaming automatically without opening UI | _Pending device run_ |
| 9 | Kill mid-backfill | Run `adb shell am kill com.imou.youtubestreamer` during an active multi-chunk backfill | Resumes from the last persisted 10-min chunk (`doneUpToWallMs` in `gap_ledger.json`), not from start | _Pending device run_ |
| 10 | Rotation boundary | Set `Rotation Period Override = 10` in Settings | Clean stop on all live + backfill workers, 15 s seal break, new `Part X of N` titles, zero dual-publisher key overlap | _Pending device run_ |
| 11 | Wrong camera password | Enter incorrect password in Settings | Classified error `Camera auth failed (check username/password)`, retry spacing $\ge 5\text{ min}$ (`Backoff`) | _Pending device run_ |
| 12 | 72 h soak | Leave running continuously on charger for 72 hours | No memory growth $> 50\text{ MB}$ (`setSessionHistorySize(20)`), log size bounded $\le 15\text{ MB}$ (`5 MB x 3`), `FFmpegKit.listSessions()` `RUNNING` count $\le 2$ (live) / $\le 4$ (concurrent backfill) | _Pending device run_ |
| 13 | System time change | Advance device clock forward 4 h in Android Settings | `StreamSupervisor` `Tick` detects `slotFor(now)` change within $\le 10\text{ s}$ and rotates cleanly | _Pending device run_ |
| 14 | Doze mode | `adb shell dumpsys deviceidle force-idle` | `specialUse` FGS + WakeLock/WifiLock keep live streaming uninterrupted | _Pending device run_ |

## Unit Test Coverage (Automated JVM Gate)
Run on every release before pushing:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```
Covers:
- `BackoffTest` (exponential curve, 60 s cap, $\pm 20\%$ jitter bounds, 60 s healthy reset, 5-min `CAMERA_AUTH_FAILED` lockout guard)
- `FfmpegCommandBuilderTest` (RTSP `-timeout`, wallclock timestamps, `-rw_timeout`, `-shortest` backfill, outcome classification for 401/404/DNS/TLS/stall/cancel)
- `RotationClockTest` (8 h slots, 20 s pre-boundary margin, wall-clock jump detection, 10-min debug override)
- `GapLedgerTest` (atomic disk reload, `< minGap` discard, $\le 10\text{ s}$ merge preserving earliest incomplete cursor, chunk resume, `sdRetentionHours` expiry)
- `StallWatchdogTest` (25 s startup grace, advancing throughput, frozen frame stall, frozen upstream byte stall)
- `LinkDebouncerTest` (2-sample up/down hysteresis, flap storm immunity, WAN loss with LAN up, cellular blocked when `allowCellular=false`)
- `SettingsValidatorTest` (duplicate Fixed/Rotatable keys refused, backfill/live collisions refused, channel collision refused, invalid IP/port refused)
