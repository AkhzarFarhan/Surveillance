# Robustness, Self-Heal & Resume Plan

Target: make the RTSP → YouTube pipeline survive Wi-Fi loss, ISP/WAN loss, camera reboots, YouTube ingest drops, FFmpeg hangs, process death, OS kills, app updates and reboots — and **resume without losing footage** (SD-card backfill) — with no human intervention.

Audience: the implementing agent (Gemini). Each phase is independently shippable, builds on the previous one, and ends with acceptance criteria. Do the phases in order. Keep the existing `AppLogger.enter/step/exit/exception` logging style in all new code.

---

## 0. Current architecture (as of v1.0.14)

| File | Role |
|---|---|
| `service/CctvStreamingService.kt` | Foreground service (`specialUse`). Holds WakeLock/WifiLock, registers a **Wi-Fi-only** `NetworkCallback`, starts the rotation loop, triggers SD catch-up on Wi-Fi return. |
| `scheduler/SessionRotationScheduler.kt` | Splits the day into 3 × 8 h parts; calls `onNewSessionStarting` / `onSessionEnding` with a 15 s gap. |
| `engine/DualStreamOrchestrator.kt` | Two FFmpegKit workers (`_F`, `_R`), each in a retry loop with 5→30 s backoff; probes RTSP first; falls back to plain RTMP after a TLS error. |
| `engine/RtmpsTlsBridge.kt` | Loopback `127.0.0.1:<port>` → TLS socket to `a.rtmps.youtube.com:443` (FFmpegKit build has no OpenSSL). |
| `sync/DahuaSdCardSyncManager.kt` | After an outage, replays `/cam/playback` RTSP at `catchupSpeed`× into the **same** stream key, sequentially per lens, blocking. |
| `receiver/BootReceiver.kt` | Starts the service on `BOOT_COMPLETED`. |
| `data/StreamState.kt`, `data/AppSettings.kt` | UI state models; encrypted prefs. |

## 1. Failure modes found in the code (why it is not robust)

Numbered so later phases can reference them.

**Process / lifecycle**
- **F1** No persisted "desired state". If the OS kills the process and does not honour `START_STICKY` (common on OEM ROMs), nothing restarts streaming until a reboot.
- **F2** No `MY_PACKAGE_REPLACED` receiver → after the in-app self-update installs, streaming stays dead.
- **F3** `onStartCommand` calls `startPipeline()` every time (UI start, sticky restart, timeout relaunch) → each call tears down and restarts both streams. Not idempotent.
- **F4** If `onCreate` throws, `isRunning` stays `false` and the service lingers in `ERROR` with no retry. `serviceScope`'s `CoroutineExceptionHandler` sets `ERROR` and nothing ever recovers from it.

**Concurrency bugs in `DualStreamOrchestrator`**
- **F5** `stopRequested` is one shared `@Volatile` flag. `startDualStreaming()` calls `stopLiveStreamsGracefully()` (sets `true`) then immediately sets it `false`; old worker loops still unwinding can observe `false` and continue.
- **F6** `sessionFixed/sessionRotatable/bridgeFixed/bridgeRotatable` are shared vars. The `finally` of an **old** attempt calls `onSessionAssigned(null)` / `onBridgeAssigned(null)`, which can null out the **new** attempt's references → a later stop cannot cancel the new FFmpeg session → orphaned FFmpeg pushing to YouTube, duplicate publishers on one key.
- **F7** When the coroutine is cancelled while in `finishedDeferred.await()`, the `finally` closes the bridge but never calls `FFmpegKit.cancel(sessionId)` itself. Cancellation of the FFmpeg process depends on the outside caller having a still-valid reference (see F6).

**Hangs (no watchdog)**
- **F8** No RTSP read timeout (`-timeout`) and no output `-rw_timeout`. With `-rtsp_transport tcp`, a camera that stops sending but keeps the socket open makes FFmpeg block forever: `fps=0`, worker never exits, never reconnects.
- **F9** `RtmpsTlsBridge` sets `soTimeout = 0` after the handshake and has no write timeout. A half-dead upstream TCP connection (NAT reset, ISP drop) blocks `output.write()` until the kernel gives up (can be 15+ minutes).
- **F10** Nothing checks *progress*: frame count, bytes sent to YouTube. A "running" FFmpeg that sends nothing is treated as healthy.

**Network detection**
- **F11** `NetworkRequest` uses only `TRANSPORT_WIFI` with no `NET_CAPABILITY_INTERNET`/`VALIDATED`. Wi-Fi associated but **WAN/ISP down** is invisible → no outage recorded → that footage is never backfilled. This is the most common real outage.
- **F12** `onLost` for *any* Wi-Fi network pauses streams even if another Wi-Fi network is still up; `onAvailable` without internet triggers a catch-up that immediately fails. No debounce → a flapping AP causes a storm of pause/catch-up/restart cycles.
- **F13** Camera reachability (LAN) and YouTube reachability (WAN) are not distinguished.

**Catch-up / resume**
- **F14** In `onAvailable`, if `syncMissingFootageTillNow` throws, `handleNewSession` is skipped (it is inside the same `try`) → **live streaming never resumes**. Same if the catch-up FFmpeg hangs (`FFmpegKit.execute` is synchronous and uncancellable from the coroutine).
- **F15** Outage boundaries live only in memory (`wifiDisconnectTimestamp`). Process death during an outage loses the gap; `settings.lastOutageTimestamp` is written but never read.
- **F16** Catch-up runs Fixed then Rotatable **sequentially** while live is paused. A 2 h outage at 3× = ~40 min per lens = ~80 min with no live, and that new gap is never backfilled.
- **F17** Rotation can fire during catch-up: `handleSessionEnding` stops live only, then `handleNewSession` starts live on the **same stream key** that catch-up is still publishing to → YouTube rejects one or both.
- **F18** Gaps caused by worker failures while Wi-Fi is up (YouTube ingest down, camera reboot, TLS errors, F8/F9 hangs) are never recorded or backfilled.
- **F19** Catch-up is all-or-nothing; a failure 50 min into a 60 min backfill restarts from zero (or is dropped).

**Scheduler**
- **F20** Rotation directly drives the orchestrator via callbacks; there is no single owner of "what should be running now", so network events, rotation, and catch-up race each other (F3, F14, F17).

---

## 2. Target architecture

Introduce **one owner of truth** — a `StreamSupervisor` — that serialises every event through a single coroutine (actor pattern). Everything else becomes a dumb component that reports events and executes commands.

```
                ┌─────────────────────────────────────────────┐
 events ──────▶ │ StreamSupervisor (single-threaded actor)    │
  NetworkState  │  desired state  +  observed state           │
  WorkerExited  │  → decides commands per lens                │
  WorkerStalled │                                             │
  RotationTick  │  persists: DesiredState, GapLedger,         │
  CatchupDone   │            Heartbeat                        │
  UserStart/Stop└──────┬──────────────┬───────────────┬───────┘
                       ▼              ▼               ▼
                LensWorker(_F)  LensWorker(_R)  BackfillWorker(per lens)
                 (FFmpeg live)   (FFmpeg live)   (FFmpeg SD replay)
                       ▲              ▲               ▲
                ConnectivityMonitor (LAN probe + WAN probe + OS callback)
                RotationClock (pure function of wall time)
                Watchdogs: in-process stall watchdog, AlarmManager + WorkManager process watchdog
```

Rules:
1. Only the supervisor starts/stops workers. Components never call each other.
2. Every lens has its own `LensWorker` instance with its own job, session id, bridge, and stop flag. No shared mutable vars between lenses or between attempts.
3. For each lens, **at most one FFmpeg publisher per stream key at any time** (live XOR backfill). The supervisor enforces this.
4. Anything needed after a process restart is persisted (desired state, gap ledger, heartbeat).
5. All decisions are idempotent: re-applying the same desired state is a no-op.

New package layout (suggested):
```
engine/LensWorker.kt            (replaces runWorkerLoop/runSingleAttempt)
engine/FfmpegCommandBuilder.kt  (pure, unit-testable)
engine/StallWatchdog.kt
engine/RtmpsTlsBridge.kt        (hardened)
supervisor/StreamSupervisor.kt
supervisor/SupervisorEvent.kt
supervisor/Backoff.kt           (pure, unit-testable)
net/ConnectivityMonitor.kt
resume/GapLedger.kt             (persisted JSON)
resume/BackfillWorker.kt        (replaces DahuaSdCardSyncManager live logic)
resume/Heartbeat.kt
watchdog/ServiceWatchdogReceiver.kt   (AlarmManager)
watchdog/ServiceWatchdogWorker.kt     (WorkManager)
receiver/BootReceiver.kt        (extended)
```
`DualStreamOrchestrator` becomes a thin façade over two `LensWorker`s (or is deleted once the supervisor owns them — keep it until Phase 3 is done so the UI keeps compiling).

Inject a `Clock` interface (`fun nowMs(): Long`, `fun elapsedMs(): Long` backed by `SystemClock.elapsedRealtime()`) into supervisor, scheduler, ledger, and watchdog so logic is testable and immune to wall-clock jumps. Use **elapsed realtime** for timeouts/backoff, **wall time** only for titles, rotation boundaries and SD playback ranges.

---

## Phase 1 — Stop the bleeding (small, high-value fixes)

Goal: fix outright bugs without restructuring. Ship as v1.0.15.

1. **F14 — always resume live after catch-up.** In `CctvStreamingService.registerNetworkMonitoring().onAvailable`, move `handleNewSession(...)` into a `finally` (or after a separate `try/catch` around the sync call). Wrap the sync call in `withTimeoutOrNull(gapMs / catchupSpeed + 5 min)`.
2. **F6/F7 — per-attempt ownership.** In `DualStreamOrchestrator.runSingleAttempt`:
   - Replace `onSessionAssigned(null)` / `onBridgeAssigned(null)` in `finally` with compare-and-clear: only clear if the stored reference is still *this* attempt's session/bridge (use `AtomicReference<FFmpegSession?>` per lens and `compareAndSet(session, null)`).
   - In `finally`, if the session is not yet complete, call `FFmpegKit.cancel(session.sessionId)` yourself.
3. **F5 — per-launch generation token.** Replace the boolean `stopRequested` with `@Volatile var generation: Long`. `startDualStreaming` increments it and passes the value into each worker loop; loops exit when `myGeneration != generation`. `stopLiveStreamsGracefully` also increments it.
4. **F8 — FFmpeg I/O timeouts.** Add to the live command (verify option names against the bundled FFmpeg version by running `ffmpeg -h demuxer=rtsp` / `-h protocol=tcp` through `FFmpegKit.execute` once and logging the output; FFmpeg ≥5 uses `-timeout` in microseconds for RTSP, older uses `-stimeout`):
   - Input: `-rtsp_transport tcp -timeout 10000000` (10 s socket I/O timeout) before `-i`.
   - Input: `-fflags +genpts+discardcorrupt` and `-use_wallclock_as_timestamps 1` (camera timestamp resets after camera reboot otherwise make YouTube drop the stream).
   - Output: `-rw_timeout 15000000` on the RTMP output (applies to the loopback TCP connection).
   - Apply the same to the SD catch-up command.
5. **F9 — bridge write/read stall.** In `RtmpsTlsBridge.handleConnection`: set `ssl.keepAlive = true`; keep `soTimeout` on the **local** (FFmpeg→bridge) socket at 30 s so a silent FFmpeg kills the tunnel; add a lightweight bridge watchdog thread that closes both sockets if `bytesUp` has not increased for 20 s (FFmpeg sends continuously while live, so 20 s of zero upstream bytes is always a fault). Expose `lastUpstreamProgressElapsedMs` for Phase 2.
6. **F3 — idempotent start.** In `onStartCommand`, if the pipeline coroutine is already active and the action is `ACTION_START`/`null`, only re-promote the notification; do not call `startPipeline()` again. Track the pipeline `Job` in a field.
7. **F2 — restart after self-update.** Add `android.intent.action.MY_PACKAGE_REPLACED` to the `BootReceiver` intent filter and handle it the same as boot (respect `autoStartOnBoot` *or* the new `desiredRunning` flag from Phase 4 — for now, `autoStartOnBoot`).
8. **Reconnect jitter.** In the worker loop add ±20 % random jitter to `backoffMs` so the two lenses do not reconnect in lock-step.

**Acceptance (manual, on device):**
- Pull the camera's power for 2 min then restore → both lenses resume within 60 s without touching the app.
- Block YouTube on the router (or unplug WAN) for 2 min with Wi-Fi up → workers exit within ~20 s (no infinite hang), retry with backoff, resume after WAN returns.
- `adb shell am force-stop` is *not* expected to recover yet (Phase 4).
- After installing an update via the in-app updater, streaming restarts by itself.
- Logs show no "orphan" FFmpeg session: after any stop, `FFmpegKit.listSessions()` filtered to `RUNNING` is empty (add a debug log of this in `stopLiveStreamsGracefully`).

---

## Phase 2 — `LensWorker` + stall watchdog

Goal: replace `runWorkerLoop`/`runSingleAttempt` with a self-contained worker per lens that detects stalls and always cleans up after itself.

### 2.1 `LensWorker`
```kotlin
class LensWorker(
    val lens: Lens,                    // enum { FIXED("_F"), ROTATABLE("_R") } with channel lookup
    private val settings: AppSettings,
    private val probe: OnvifCameraClient,
    private val clock: Clock,
    private val events: SendChannel<SupervisorEvent>,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<LensStreamState>
    fun startLive(target: String, title: String)   // idempotent if same target+title already running
    fun startBackfill(range: GapRange, target: String) // used in Phase 5
    suspend fun stop(reason: String)              // cancels FFmpeg, closes bridge, awaits exit (≤5 s)
}
```
- Exactly one internal `Job`. `startLive` while running with different params = `stop()` then start.
- The attempt loop lives inside the worker; on every attempt exit it sends `SupervisorEvent.WorkerExited(lens, outcome, startedAtMs, endedAtMs)` (used for the gap ledger in Phase 5) and applies backoff from `Backoff` (pure class: base 2 s, factor 2, cap 60 s, ±20 % jitter, reset after 60 s of healthy streaming).
- Classify outcomes into an enum: `CAMERA_UNREACHABLE`, `CAMERA_AUTH_FAILED` (401 from probe → back off to 5 min and surface in UI, do not hammer the camera — Dahua/Imou locks accounts after repeated failures), `INGEST_UNREACHABLE`, `TLS_ERROR`, `STALLED`, `FFMPEG_ERROR`, `CANCELLED`. The UI shows the classified reason, not a raw log tail.
- Keep the H.265→ONVIF→H.264 logic, but cache the "camera is H.265 and refuses to switch" result for the session so each retry does not repeat the ONVIF dance.
- Plain-RTMP fallback after TLS errors: make it sticky for 10 min (counter-based) instead of one attempt, then try RTMPS again.

### 2.2 `StallWatchdog`
Per running attempt, a coroutine that checks every 5 s:
- `lastFrameProgressElapsedMs` — updated in the FFmpeg statistics callback when `videoFrameNumber` increases.
- `bridge.lastUpstreamProgressElapsedMs` — from Phase 1.5 (or the FFmpeg `size` statistic when no bridge).
- Startup grace: 25 s after FFmpeg start before the first check.
- If either has not advanced for **20 s** → log `unexpected(... "STALLED")`, `FFmpegKit.cancel(sessionId)`, close the bridge, outcome = `STALLED`.
- If FFmpeg does not report completion within 5 s of cancel → abandon the session handle (log it), close the bridge (which unblocks FFmpeg's socket), and continue the loop — never block the worker on a hung native session.

### 2.3 Remove shared state from `DualStreamOrchestrator`
Make it hold two `LensWorker`s and delegate. `fixedState`/`rotatableState` forward the workers' state flows so `CctvStreamingService.observeOrchestratorStates()` and the UI keep working unchanged.

### 2.4 Unit tests (JVM, `app/src/test`)
Add `testImplementation` for JUnit4 + `kotlinx-coroutines-test`. Test: `Backoff` (growth, cap, reset, jitter bounds), `FfmpegCommandBuilder` (timeouts present, silent audio mapping, transcode branch, tcUrl), outcome classification from sample FFmpeg log tails.

**Acceptance:**
- Simulate a stall: on the router, drop (not reject) traffic from the phone to YouTube IPs → `STALLED` within 25 s, reconnect loop continues, recovers when the rule is removed.
- Wrong camera password → UI shows "Camera auth failed", retries are ≥5 min apart.
- `./gradlew testDebugUnitTest` passes.

---

## Phase 3 — `ConnectivityMonitor` (detect WAN loss, not just Wi-Fi loss)

Fixes F11, F12, F13.

### 3.1 Inputs
1. `ConnectivityManager.registerDefaultNetworkCallback` (not a Wi-Fi-only request). Track `onCapabilitiesChanged` for `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`, and transport (Wi-Fi vs cellular — keep a setting `allowCellular` default `false`; when false and only cellular is available, treat WAN as down for streaming purposes but still consider the camera LAN unreachable).
2. **Active LAN probe** every 15 s: TCP connect to `cameraIp:rtspPort` with 3 s timeout.
3. **Active WAN probe** every 15 s: TCP connect to `a.rtmps.youtube.com:443` (and fallback `a.rtmp.youtube.com:1935`) with 4 s timeout. Resolve DNS each time (cached DNS hides outages).
4. Worker outcomes (`INGEST_UNREACHABLE`, `STALLED`) are an additional WAN-down hint.

### 3.2 Output
```kotlin
data class LinkState(val lanUp: Boolean, val wanUp: Boolean, val changedAtElapsedMs: Long)
val linkState: StateFlow<LinkState>
```
- **Debounce/hysteresis:** declare *down* after 2 consecutive failed probes (~30 s) or an OS `onLost` of the default network; declare *up* only after the OS reports `VALIDATED` **and** 2 consecutive successful probes. Prevents flap storms.
- Run probes on `Dispatchers.IO`, cancellable, all inside `ConnectivityMonitor`'s own scope.

### 3.3 Service changes
Delete the Wi-Fi-only callback in `CctvStreamingService`. Map `LinkState` to `PipelineStatus`: `wanUp=false` → `OUTAGE_RECORDING` (rename label to "Offline – camera recording to SD"), `lanUp=false` → new `CAMERA_OFFLINE`. Rebind WifiLock on network change (`wifiLock.release(); acquire()` if needed).

Also when the default network changes (Wi-Fi ↔ another Wi-Fi), proactively restart workers: existing sockets are bound to the old network and will otherwise hang until a timeout.

**Acceptance:**
- Wi-Fi up, router WAN cable pulled → status shows offline within ~30 s, workers stop retrying aggressively (supervisor holds them until WAN up — Phase 4).
- Toggle AP off/on 5 times within a minute → at most one stop and one start of the workers.

---

## Phase 4 — `StreamSupervisor`, persisted desired state, and process-level self-heal

Fixes F1, F3, F4, F15, F17, F20.

### 4.1 Persisted state (new file `supervisor/SupervisorStore.kt`)
Use plain `SharedPreferences` (`surveillance_runtime`, not encrypted — must be readable early and fast; contains no secrets):
- `desiredRunning: Boolean` — set `true` by user Start, `false` only by user Stop. Never set by errors.
- `heartbeatWallMs: Long` — written every 10 s by the supervisor while `desiredRunning`.
- `crashCount`/`lastCrashWallMs` — for restart-loop protection.

### 4.2 Supervisor
`StreamSupervisor(scope, clock, connectivity, workers, ledger, rotationClock, store)` with a `Channel<SupervisorEvent>(UNLIMITED)` consumed by one coroutine. Events:
`UserStart`, `UserStop`, `LinkChanged(LinkState)`, `RotationBoundary(part, dateStr)`, `WorkerExited(lens, outcome, ...)`, `BackfillProgress/Done(lens, ...)`, `Tick` (every 10 s).

On every event, compute the desired per-lens mode, then reconcile:
```
mode(lens) =
  if !desiredRunning                       -> OFF
  else if !lanUp || !wanUp                 -> WAITING (record gap open)
  else if rotationGap in progress          -> OFF (15 s seal gap)
  else if ledger has pending gap for lens
       && backfillPolicy allows            -> BACKFILL   (Phase 5)
  else                                     -> LIVE(currentTitle)
```
Reconcile = call `worker.startLive/startBackfill/stop` only when the mode differs from what the worker is doing (idempotent).

### 4.3 Rotation
Turn `SessionRotationScheduler` into `RotationClock`: pure functions `partFor(wallMs)`, `nextBoundaryWallMs(wallMs)` plus a ticker that emits `RotationBoundary` events. The supervisor performs the stop → 15 s gap → start sequence. This removes the direct callback coupling and guarantees backfill and live never overlap on a key during rotation (F17). Also handle wall-clock jumps (user/NTP time change): on each `Tick`, if `partFor(now)` ≠ current part, emit a boundary.

### 4.4 Service becomes thin
`CctvStreamingService`:
- `onCreate`: build components, `promoteToForeground`, locks, start supervisor. Wrap each in its own try; if supervisor construction fails, schedule a self-restart via the watchdog alarm (below) and `stopSelf()` — never sit in `ERROR`.
- `onStartCommand`: `ACTION_START`/`null` → `store.desiredRunning = true` (only if explicit start or sticky restart while desired), send `UserStart` (idempotent). `ACTION_STOP` → `desiredRunning = false`, `UserStop`, stop self.
- `CoroutineExceptionHandler` → log and send a `Tick` so the supervisor reconciles; do not set a terminal `ERROR`.
- Keep `SystemStreamState` publishing for the UI, fed from the supervisor.

### 4.5 Process-level watchdogs (the "extreme self-heal" layer)
All three restart paths call `CctvStreamingService.start()` only if `store.desiredRunning` is true and `isRunning` is false (or heartbeat is older than 60 s).
1. **AlarmManager** (`watchdog/ServiceWatchdogReceiver`): `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, +15 min)`, re-armed each time it fires and each time the service starts. (Exact alarms need `SCHEDULE_EXACT_ALARM`; inexact is enough.) Starting an FGS from an alarm receiver is allowed when the app is exempt from battery optimisation, which the app already requests — log a clear warning in the service and on the dashboard if `PowerManager.isIgnoringBatteryOptimizations()` is false.
2. **WorkManager** periodic worker (15 min, `NetworkType.NOT_REQUIRED`) doing the same check — survives cases where alarms are cleared by OEM "cleaners". Add `androidx.work:work-runtime-ktx` to `libs.versions.toml`.
3. **Boot/update** receiver: `BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED` → check `desiredRunning` (fall back to `autoStartOnBoot` for users upgrading from older versions: initialise `desiredRunning = autoStartOnBoot` on first run of the new version).
4. **Crash-loop guard**: in `SurveillanceApp.installCrashRecorder`, increment `crashCount`; if >5 crashes within 10 min, delay the next watchdog restart to 5 min instead of immediately, and surface "Crash loop detected" on the dashboard.
5. `onTaskRemoved` in the service: re-arm the alarm (some ROMs kill services on swipe-away despite `stopWithTask=false`).

### 4.6 UI
Add a "Health" card on `DashboardScreen`: link state (LAN/WAN), per-lens mode + classified last error, last heartbeat, battery-optimisation exemption status (with a button to the system settings intent), pending gap minutes per lens.

**Acceptance:**
- `adb shell am kill com.imou.youtubestreamer` (simulates OS kill) → streaming back within 15 min (usually immediately via `START_STICKY`).
- `adb shell am force-stop` then wait → *not* expected to recover (Android forbids it); document this.
- Reboot phone → streaming resumes without opening the app.
- Rotation boundary while offline → no crash; on reconnect, titles reflect the **current** part.
- Change device time manually across a part boundary → rotation happens within 10 s.

---

## Phase 5 — Gap ledger & resumable SD-card backfill

Fixes F14–F19. This is the "resume capability".

### 5.1 `GapLedger` (persisted)
File: `filesDir/gap_ledger.json`, written atomically (write to `.tmp`, `fsync`, rename). Model:
```kotlin
data class Gap(
    val id: String,          // UUID
    val lens: Lens,
    val startWallMs: Long,
    val endWallMs: Long?,    // null = still open
    val doneUpToWallMs: Long,// backfill resume point (starts == startWallMs)
    val attempts: Int,
    val status: GapStatus,   // OPEN, PENDING, IN_PROGRESS, DONE, ABANDONED
)
```
Rules:
- **Open** a gap for a lens when it leaves LIVE-healthy for any reason other than user stop/rotation seal (WAN down, camera down, worker exit, stall). Start = last time healthy frames were observed (from the watchdog's `lastFrameProgress` converted to wall time), not the time of detection.
- **Close** it when the lens has been LIVE-healthy for 30 s. Discard gaps shorter than `minGapToBackfill` (default 60 s; setting).
- On process start: if `heartbeatWallMs` is older than 30 s and `desiredRunning`, open a gap from `heartbeatWallMs` to now for both lenses (covers process death, reboot, force-stop) — fixes F15.
- Merge overlapping/adjacent gaps (≤10 s apart). Cap total retained age at the camera's SD retention (setting `sdRetentionHours`, default 72) → older gaps become `ABANDONED`.
- Expose `pendingGaps: StateFlow<List<Gap>>` for the UI.

### 5.2 `BackfillWorker` (replaces `DahuaSdCardSyncManager.syncChannelViaRtspPlayback`)
- Run inside `LensWorker.startBackfill` so the one-publisher-per-key invariant is enforced by construction.
- Process each gap in **chunks of 10 min** (`doneUpToWallMs` → `min(+10 min, endWallMs)`), each chunk a separate FFmpeg run via `executeAsync` (cancellable), with the same watchdog and I/O timeouts as live. After each successful chunk, persist `doneUpToWallMs`. A failure retries only the current chunk (max 3 tries, then mark chunk skipped and log) → fixes F19.
- Before each chunk, probe `/cam/playback` DESCRIBE; a 404/empty means the SD has no recording for that window → advance `doneUpToWallMs` and continue.
- Use `-readrate` = `catchupSpeed` and keep `-use_wallclock_as_timestamps 0` here (playback must keep source timestamps); add `-re`-free accelerated mode as today.
- Run both lenses' backfills **in parallel** (they use different keys) → fixes F16.

### 5.3 Backfill policy (supervisor)
Two configurable strategies (new setting `backfillMode`):
1. **`SEPARATE_KEY` (recommended).** New optional settings `backfillRtmpFixed` / `backfillRtmpRotatable` = a second YouTube stream key per lens bound to a separate "Backfill" broadcast. Live keeps running; backfill streams in parallel on the other key. Live is never interrupted. Supervisor mode becomes `LIVE + BACKFILL` concurrently on different keys.
2. **`SAME_KEY` (current behaviour, fallback when no backfill key is set).** Pause live, backfill, then go live. Because live is paused while backfilling, footage during the backfill is still on the SD card and becomes a new gap — the supervisor **chases**: keep backfilling until the remaining gap is < 60 s, then go live. Bound it: if the chase cannot converge (`catchupSpeed` ≤ 1.2) or the backfill would cross the next rotation boundary, stop at the boundary, go live for the new part, and leave the remainder in the ledger for the next opportunity (or for the `SEPARATE_KEY` path once configured).
- Never backfill while `wanUp=false` or `lanUp=false`.
- Rotation boundary: supervisor stops backfill first, performs the seal gap, then resumes backfill/live per policy (fixes F17).

### 5.4 Remove the old path
Delete the catch-up code from `CctvStreamingService.onAvailable` (gone with Phase 3) and the blocking `FFmpegKit.execute` path. Keep `runCgiBacklogCheck` only as an optional diagnostic button in Settings.

**Acceptance:**
- Wi-Fi off for 10 min → on return, ledger shows a 10-min gap per lens; with `SEPARATE_KEY`, live resumes within 60 s and the backfill broadcast receives ~10 min of footage in ~3–4 min.
- Kill the app mid-backfill (`am kill`) → after restart, backfill resumes from the last completed 10-min chunk, not from the start.
- Reboot phone during an outage → after boot, the gap from last heartbeat to now is in the ledger and is backfilled.
- 3 rapid outages of 2 min each → merged/ordered gaps, all backfilled, no overlapping publishers on any key (verify in logs: never two `RUNNING` FFmpeg sessions with the same target).

---

## Phase 6 — Hardening details

1. **Session-key sanity:** on start, if Fixed and Rotatable keys are identical, refuse and show an error (two publishers on one key flap forever).
2. **FFmpegKit global state:** set `FFmpegKitConfig.setSessionHistorySize(20)` so session history does not grow unbounded over days (memory leak in 24/7 use). Clear `allLogs` usage — `executeAsync` keeps logs per session; with history capped this is bounded.
3. **Log volume:** `AppLogger.ffmpeg` per-line logging for days can fill storage; ensure `StreamLog` rotates by size (check `StreamLog.kt`; add a 5 MB × 3 file cap if not present).
4. **Thermal / memory:** register `ComponentCallbacks2.onTrimMemory` in the service; on `TRIM_MEMORY_RUNNING_CRITICAL` drop transcoding (if active) to sub-stream (`subtype=1`) and log. Optional: `PowerManager.addThermalStatusListener` — at `THERMAL_STATUS_SEVERE`, switch transcoding lenses to copy-mode sub-stream.
5. **WakeLock refresh:** some ROMs revoke long-held wakelocks; re-acquire on each supervisor `Tick` if `!isHeld`.
6. **DNS:** resolve `a.rtmps.youtube.com` in the bridge per connection (already true), and on `UnknownHostException` classify as `INGEST_UNREACHABLE`, not `TLS_ERROR` (today it triggers the plain-RTMP fallback incorrectly).
7. **Bridge accept loop:** today it accepts unlimited connections and keeps only the latest `activeLocal`. Limit to one active tunnel; close the previous one when a new FFmpeg connection arrives.
8. **Notification:** show per-lens state and pending backfill minutes; add a "Restart pipeline" action (sends `UserStart` after `UserStop` without clearing `desiredRunning`).
9. **Settings validation:** validate stream keys format and camera IP on save; reject blank channel collisions (fixed == rotatable channel).

---

## Phase 7 — Verification matrix (run before each release)

Add `docs/ROBUSTNESS_TESTS.md` with this table and record pass/fail per release. Add a hidden debug setting `rotationPeriodMinutesOverride` (e.g. 10 min) so rotation can be tested without waiting 8 h.

| # | Scenario | How | Expected |
|---|---|---|---|
| 1 | Wi-Fi off 2 min | Quick-settings toggle | Gap recorded, live resumes ≤60 s after Wi-Fi back, gap backfilled |
| 2 | WAN down, Wi-Fi up | Unplug router WAN 5 min | Detected ≤30 s, gap backfilled after |
| 3 | Camera power cycle | Unplug camera 2 min | `CAMERA_OFFLINE`, resume ≤60 s after camera boots; no backfill for camera-off period (SD not recording) — ledger marks chunks empty |
| 4 | YouTube ingest silently dropped | Router DROP rule to YouTube IPs | `STALLED` ≤25 s, recovery after rule removed |
| 5 | AP flapping | Toggle AP 5× in 60 s | ≤1 restart cycle |
| 6 | Process kill | `adb shell am kill <pkg>` | Restart ≤15 min (usually immediate) |
| 7 | Reboot | `adb reboot` | Auto-start, gap from last heartbeat backfilled |
| 8 | App update | Install new APK via updater | Auto-restart |
| 9 | Kill during backfill | `am kill` mid-backfill | Resumes from last chunk |
| 10 | Rotation boundary | Debug override 10 min | Clean stop, 15 s gap, new titles, no overlap with backfill |
| 11 | Wrong camera password | Settings | Classified error, ≥5 min retry spacing |
| 12 | 72 h soak | Leave running | No memory growth >50 MB, log size bounded, no orphan FFmpeg sessions |
| 13 | Time change | Set clock forward 4 h | Rotation within 10 s |
| 14 | Doze | `adb shell dumpsys deviceidle force-idle` | Streaming continues (FGS + locks) |

---

## Implementation notes for the agent

- Build: `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`. Bump `versionCode`/`versionName` in `app/build.gradle.kts` per phase.
- Keep `PipelineStatus` / `SystemStreamState` / `LensStreamState` field names stable or update `DashboardScreen.kt`, `MainViewModel.kt` in the same change; add new fields rather than renaming where possible.
- Do not log stream keys or camera passwords; use `AppLogger.maskSecret` as existing code does. RTSP URLs must be sanitised as in `DualStreamOrchestrator.getRtspUrl`.
- Never call blocking `FFmpegKit.execute` from a coroutine you need to cancel; always `executeAsync` + `CompletableDeferred` + explicit `FFmpegKit.cancel` in `finally`.
- Every new long-running loop must (a) be cancellable, (b) have a timeout or watchdog, (c) report its exit as an event to the supervisor.
- The FFmpegKit build has no OpenSSL; all RTMPS must keep going through `RtmpsTlsBridge`.
- Phases 1–2 can be released independently; Phases 3–5 depend on the supervisor from Phase 4 being in place (do 3 and 4 together if easier; 3's monitor can be written first and wired in 4).
