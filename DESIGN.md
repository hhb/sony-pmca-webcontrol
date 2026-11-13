# Sony PMCA WebControl — Architecture & Design (`DESIGN.md`)

## 1. Background & Stock Firmware Limitations

Sony's 2014–2017 PlayMemories-era mirrorless cameras (such as the `ILCE-6000` / a6000, a6300, a6500, and a7 II series) impose three major firmware limitations for tethered studio control, macro photography, and automated workflows:
1. **No Live View or Focus Drive over USB (`PC Remote`)**: Connecting these bodies to Sony `Imaging Edge Desktop` via USB only allows blind shutter triggering and exposure changes. It does not stream a Live View feed or expose manual focus (MF) stepping controls.
2. **No Remote MF Stepping over Wi-Fi (`Smart Remote Control`)**: Sony's official Wi-Fi JSON-RPC API only exposes `Touch AF` (autofocus at coordinates) and omits fine-grained manual focus drive commands. It also forces the host computer/tablet to join the camera's isolated Wi-Fi access point.
3. **No Native Focus Bracketing**: Shooting macro subjects (such as 1–5 cm mineral specimens) requires stacking dozens of shots due to razor-thin depth of field, which stock firmware on these bodies cannot automate.

## 2. Core Mechanism: In-Camera Android Subsystem & `CameraEx`

Internally, PlayMemories-capable Sony cameras run an embedded **Android 2.3.3 (API Level 10)** subsystem. By leveraging the open-source [OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework) and [Sony-PMCA-RE (`pmca-console`)](https://github.com/ma1co/Sony-PMCA-RE) toolchain, we can run a custom Android `.apk` directly inside the camera and invoke Sony's undocumented hardware layer (`com.sony.scalar.hardware.CameraEx`):

* **Motorized Focus Stepping**: `CameraEx.startOneShotFocusDrive(direction, speed)` drives the lens focus motor in `NEAR` or `FAR` directions across 4 speed levels (`1..4`).
* **Absolute Focus Scale Readback**: `CameraEx.FocusDriveListener` reports `FocusPosition(currentPosition, maxPosition)`, enabling closed-loop positioning to any integer focus coordinate.
* **Exposure & Shutter Control**: `CameraEx.incrementAperture()` / `decrementAperture()`, `incrementShutterSpeed()` / `decrementShutterSpeed()`, `ParametersModifier.setISOSensitivity()`, and `burstableTakePicture()`.
* **Live View Capture**: Standard Android `Camera.PreviewCallback` captures raw YUV (`NV21`) preview frames, which are compressed on-demand into JPEG streams via `android.graphics.YuvImage` when requested over HTTP.
* **LAN Web Server**: `WifiManager` connects the camera directly to a saved home/studio Wi-Fi router, while an embedded `NanoHTTPD` server (port `8080`) exposes a RESTful HTTP API and a responsive HTML5 control panel.

## 3. System Architecture

```text
+-------------------------------------------------------------------+
|            Sony PMCA Camera (Android 2.3 / API 10)                |
|                                                                   |
|  +-----------------------+      +------------------------------+  |
|  |  MacroServerActivity  |<---->|  com.sony.scalar.CameraEx    |  |
|  |  - State Machine      |      |  - FocusDriveListener        |  |
|  |  - Focus Queue        |      |  - startOneShotFocusDrive()  |  |
|  |  - YUV Frame Buffer   |      |  - burstableTakePicture()    |  |
|  +-----------+-----------+      |  - PreviewCallback (YUV)     |  |
|              ^                  +------------------------------+  |
|              | UI Handler / Thread-Safe Dispatch                  |
|              v                                                    |
|  +-----------------------+                                        |
|  |    MacroHttpServer    |  (NanoHTTPD on :8080, CORS enabled)    |
|  |  - GET  /             | -> Built-in HTML5 Web UI (Mac/iPad)    |
|  |  - GET  /api/status   | -> JSON state & focus coordinates      |
|  |  - GET  /api/liveview.jpg -> On-demand YUV-to-JPEG frame       |
|  |  - POST /api/focus/*  | -> Single step / closed-loop goto      |
|  |  - POST /api/bracket  | -> Automated focus bracketing loop     |
|  |  - POST /api/exposure | -> Aperture / Shutter / ISO control    |
|  |  - POST /api/capture  | -> Single shutter release              |
|  +-----------+-----------+                                        |
+--------------|----------------------------------------------------+
               |  Local Wi-Fi Network (HTTP / JSON / JPEG)
               v
+-------------------------------------------------------------------+
|       Controller: Mac / iPad Browser  or  Python / AI Agent       |
|  - client/pmca_client.py         (Zero-dependency HTTP SDK)       |
|  - client/example_macro_stack.py (Highlight check + sweep + stack)|
+-------------------------------------------------------------------+
```

## 4. Detailed Module Design

### 4.1 Threading Model & On-Demand JPEG Compression
* `NanoHTTPD` processes incoming HTTP requests on background worker threads, whereas `CameraEx` hardware mutations and UI updates must run on the Android main UI thread. `MacroServerActivity` marshals all camera commands onto the main thread via `Handler.post()`.
* `onPreviewFrame(byte[] data, Camera camera)` fires continuously at the camera's preview frame rate. To prevent CPU overheating on the camera's processor, the callback only copies the raw YUV byte array under a mutex lock (`m_frameLock`). Actual `YuvImage.compressToJpeg(..., 75, baos)` encoding is executed lazily only when a client requests `GET /api/liveview.jpg`.

### 4.2 Closed-Loop Focus Positioning & Stall Recovery
* On Sony Scalar firmware, `startOneShotFocusDrive()` occasionally fails to fire `FocusDriveListener` for tiny steps. Every drive command schedules a `60ms` watchdog runnable (`m_checkFocusRunnable`). If the position has not changed, it retries up to 8 times before safely declaring a physical lens limit/stall and returning to `idle` (or advancing the bracket queue), preventing deadlocks.

### 4.3 Focus Bracketing State Machine
* States: `idle`, `focusing`, `capturing`, `bracketing`, `error`.
* Upon receiving `POST /api/bracket?min=A&max=B&shots=N`, the activity interpolates `N` deduplicated integer focus coordinates between `min` and `max` into `m_focusQueue`.
* Execution loop: `driveTowardTarget()` moves to the head of `m_focusQueue` → triggers `burstableTakePicture()` → `onShutter()` callback confirms capture completion → `cancelTakePicture()` releases the shutter lock and pops the queue → advances to the next focus target until `m_focusQueue` is empty.

## 5. System & Hardware Requirements
1. **Supported Camera Bodies**:
   * **Full Support** (`ScalarProperties.PROP_VERSION_PLATFORM >= 2.3`): `a5100`, `a6000`, `a6300`, `a6500`, `a7 II`, `a7R II`, `a7S II`, `a7` / `a7R` / `a7S` (updated firmware), `RX100 III / IV / V`, `RX10 II / III`, `RX1R II`.
   * **Partial Support** (`PROP_VERSION_PLATFORM < 2.3`): `NEX-5R`, `NEX-5T`, `NEX-6`, `RX100 II` (Live View, exposure, and capture work; motorized focus drive is unavailable in firmware).
2. **AF Lens Requirement**: Software focus control relies on electronic commands sent to the lens focus motor. **An E-mount lens with electronic contacts and an AF motor is required** (including standard AF lenses mounted on electronic AF macro extension tubes). Purely mechanical manual lenses (e.g., Laowa 65mm 2X) cannot be focused electronically, though live view, exposure adjustments, and shutter release still work.
3. **Power Management**: The app calls `setAutoPowerOffMode(false)` while active so the camera never sleeps mid-session. Using an NP-FW50 USB dummy battery is recommended for long studio sessions.
4. **Storage Workflow**: Because 24MP RAW (`.ARW`) files are ~24MB each and PlayMemories cameras use 2.4GHz Wi-Fi, bracketed sequences are written directly to the internal SD card at full speed for later stacking in Helicon Focus or Zerene Stacker.
