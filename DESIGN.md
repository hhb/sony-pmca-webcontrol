# Sony PMCA WebControl — Architecture & Design (`DESIGN.md`)

## 1. Background & Stock Firmware Limitations

Sony's 2014–2017 PlayMemories-era mirrorless cameras (such as the `ILCE-6000` / a6000, a6300, a6500, and a7 II series) impose three major firmware limitations for tethered studio control, macro photography, and automated workflows:
1. **No Live View or Focus Drive over USB (`PC Remote`)**: Connecting these bodies to Sony `Imaging Edge Desktop` via USB only allows blind shutter triggering and exposure changes. It does not stream a Live View feed or expose manual focus (MF) stepping controls.
2. **No Remote MF Stepping over Wi-Fi (`Smart Remote Control`)**: Sony's official Wi-Fi JSON-RPC API only exposes `Touch AF` (autofocus at coordinates) and omits fine-grained manual focus drive commands. It also forces the host computer/tablet to join the camera's isolated Wi-Fi access point.
3. **No Native Focus Bracketing**: Shooting macro subjects (such as 1–5 cm mineral specimens) requires stacking dozens of shots due to razor-thin depth of field, which stock firmware on these bodies cannot automate.

## 2. Core Mechanism: In-Camera Android Subsystem & `CameraEx`

Internally, PlayMemories-capable Sony cameras run an embedded **Android 2.3.3 (API Level 10)** subsystem. By leveraging the open-source [OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework) and [Sony-PMCA-RE (`pmca-console`)](https://github.com/ma1co/Sony-PMCA-RE) toolchain, we can run a custom Android `.apk` directly inside the camera and invoke Sony's undocumented hardware layer (`com.sony.scalar.hardware.CameraEx`):

* **Thin Stateless Hardware Bridge**: Instead of hardcoding high-level workflows (like focus bracketing state machines) inside the camera APK, the camera exposes a **universal reflective JSON-RPC endpoint** (`POST /api/call` + `GET /api/describe`) backed by `RpcDispatcher`. Any `CameraEx`, `CameraEx.ParametersModifier`, whitelisted `Camera.Parameters`, or Scalar static/system method can be invoked without reflashing the APK.
* **Event-Driven State & Long Polling**: `CameraEventHub` subscribes to all major `CameraEx` listeners (`FocusDriveListener`, `ApertureChangeListener`, `ShutterSpeedChangeListener`, `SettingChangedListener`, `StoreImageCompleteListener`, `ZoomChangeListener`, etc.), caches the latest state snapshot (`/api/state`), and streams events over a 256-entry ring buffer (`/api/events`).
* **Hardware JPEG LiveView (`CameraSequence`)**: `LiveViewManager` uses `CameraSequence.startPreviewSequence()` (`640px` hardware-encoded JPEG frames via `DeviceBuffer`) rather than CPU-heavy software `YuvImage` compression. Frames are pushed to `multipart/x-mixed-replace` MJPEG clients (`/api/liveview.mjpeg`) with adaptive frame intervals (`33ms`/`50ms`/`85ms` for 1/2/3+ clients) as well as single-frame snapshots (`/api/liveview.jpg`).
* **LAN Web Server**: `WifiManager` connects the camera directly to a saved home/studio Wi-Fi router, while `MacroHttpServer` (`NanoHTTPD` on port `80` with fallback to `8080`, CORS enabled) serves the REST API and a built-in fallback HTML page (`assets/index.html`).

## 3. System Architecture

```text
+-----------------------------------------------------------------------+
|              Sony PMCA Camera (Android 2.3 / API 10)                  |
|                                                                       |
|  +-----------------------+      +----------------------------------+  |
|  |  MacroServerActivity  |<---->|  com.sony.scalar.CameraEx        |  |
|  |  - Lifecycle & LCD UI |      |  - FocusDriveListener            |  |
|  |  - Camera Listeners   |      |  - startOneShotFocusDrive()      |  |
|  |  - runOnMainSync()    |      |  - burstableTakePicture()        |  |
|  +-----+-----------+-----+      +----------------+-----------------+  |
|        |           |                             |                    |
|        v           v                             v                    |
|  +-----------+  +---------------+      +---------------------------+  |
|  |RpcDispatch|  |CameraEventHub |      |     LiveViewManager       |  |
|  |Reflection |  |256-ring buffer|      | CameraSequence (640px JPG)|  |
|  |/api/call  |  |/api/state     |      | Adaptive MJPEG producer   |  |
|  +-----+-----+  +-------+-------+      +-------------+-------------+  |
|        |                |                            |                |
|        +----------------+----------------------------+                |
|                         v                                             |
|  +-----------------------------------------------------------------+  |
|  |  MacroHttpServer (NanoHTTPD on :80 / :8080, CORS enabled)       |  |
|  |  - GET  /                    -> Fallback HTML5 Control Page     |  |
|  |  - POST /api/call            -> Reflective RPC (single/batch)   |  |
|  |  - GET  /api/describe        -> Method introspection            |  |
|  |  - GET  /api/state, /events  -> Snapshot & long-polling bus     |  |
|  |  - GET  /api/liveview.mjpeg  -> Hardware JPEG MJPEG stream      |  |
|  |  - GET  /api/photos*         -> PhotoStorageHelper SD browser   |  |
|  +-----------------------------------------------------------------+  |
+-----------------------------------------------------------------------+
                  |  Local Wi-Fi Network (HTTP / JSON / MJPEG)
                  v
+-----------------------------------------------------------------------+
|                  Web Client (web-client/, React + TS)                 |
|  - CameraClient SDK (src/api/cameraClient.ts)                         |
|  - LiveViewPanel (MJPEG parser, Peaking, Histogram, 2x-4x Focus Loupe)|
|  - FocusControl (Client-side closed-loop gotoFocus & focus stacking)  |
|  - ExposureControl, PhotoBrowser, AutoSavePanel, RpcInspector         |
+-----------------------------------------------------------------------+
```

## 4. Detailed Module Design

### 4.1 Threading & Lock Separation
* `NanoHTTPD` processes incoming HTTP requests on background worker threads, whereas `CameraEx` hardware mutations must run on the Android main UI thread. `MacroServerActivity.runOnMainSync()` marshals all `/api/call` invocations onto the main `Handler` with a `3000 ms` timeout (`504 Gateway Timeout` on firmware stall).
* `LiveViewManager` separates `m_captureLock` (guarding `CameraSequence.getPreviewSequenceFrames(1)` and `ByteBuffer` reads) from `m_frameLock` (guarding `m_latestJpeg`, `m_frameSeq`, and `notifyAll()`). This ensures hardware frame reads never block `onPause()`/`surfaceDestroyed()` or HTTP snapshot readers.

### 4.2 Client-Side Closed-Loop Focus Positioning
* Because `CameraEx.shiftFocusPosition()` blocks/hangs on bodies like `ILCE-6000`, `CameraClient.gotoFocus()` implements closed-loop positioning in `web-client/src/api/cameraClient.ts`:
  * Reads `focus.currentPosition` from `/api/state`, computes `diff = targetPos - current`, selects adaptive speed (`1..4`) for `startOneShotFocusDrive(direction, speed)`, and iterates until `|diff| <= tolerance`.
* `CameraClient.runFocusStack()` interpolates `N` targets between `Start A` and `End B`, drives to each target, waits `settleMs`, triggers `burstableTakePicture()`, and supports instant cancellation via `AbortController`.

### 4.3 LiveView Canvas Processing & Auto-Save
* `LiveViewPanel.tsx` reads the `/api/liveview.mjpeg` byte stream via `fetch()` + `ReadableStream`, decoding only the newest frame (zero backlog) onto `<canvas>` with optional real-time client-side **Focus Peaking**, **Luma Histogram**, **Rule-of-Thirds Grid**, and a **2x–4x Click-to-Magnify Focus Loupe**.
* `App.tsx` listens for `StoreImageComplete` (and `Jpeg` fallback when no SD card is present) on `/api/events`, deduplicating filenames and queueing downloads via the File System Access API (`showDirectoryPicker`) or standard browser downloads.

## 5. System & Hardware Requirements
1. **Supported Camera Bodies**:
   * **Full Support** (`ScalarProperties.PROP_VERSION_PLATFORM >= 2.3`): `a5100`, `a6000`, `a6300`, `a6500`, `a7 II`, `a7R II`, `a7S II`, `a7` / `a7R` / `a7S` (updated firmware), `RX100 III / IV / V`, `RX10 II / III`, `RX1R II`.
   * **Partial Support** (`PROP_VERSION_PLATFORM < 2.3`): `NEX-5R`, `NEX-5T`, `NEX-6`, `RX100 II` (Live View, exposure, and capture work; motorized focus drive is unavailable in firmware).
2. **AF Lens Requirement**: Software focus control relies on electronic commands sent to the lens focus motor. **An E-mount lens with electronic contacts and an AF motor is required** (including standard AF lenses mounted on electronic AF macro extension tubes). Purely mechanical manual lenses (e.g., Laowa 65mm 2X) cannot be focused electronically, though live view, exposure adjustments, and shutter release still work.
3. **Power Management**: The app calls `setAutoPowerOffMode(false)` while active so the camera never sleeps mid-session. Using an NP-FW50 USB dummy battery is recommended for long studio sessions.
4. **Storage Workflow**: Full-resolution `.JPG` and `.ARW` files are written to the internal SD card and can be browsed on demand (`/api/photos`) or auto-saved over Wi-Fi.
