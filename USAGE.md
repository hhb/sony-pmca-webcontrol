# Sony PMCA WebControl — Usage & API Reference (`USAGE.md`)

This document is the complete reference for building, flashing, and developing clients or AI Agents against **Sony PMCA WebControl** (`com.sony.pmca.webcontrol`).

The camera-side application is a **universal, stateless hardware bridge** exposing Sony's underlying `CameraEx`, `ParametersModifier`, `Camera.Parameters`, and whitelisted Sony Scalar static/system classes over a single JSON RPC endpoint (`POST /api/call`) plus real-time state/event streaming (`GET /api/state`, `GET /api/events`). Higher-level workflows (closed-loop focus positioning, target aperture/shutter stepping, and automated focus bracketing) live entirely in client SDKs (`client/pmca_client.py`).

---

## Part 1: Build & Flashing

### 1. Build the Camera APK

Requires JDK 8, Android SDK Platform 10 (`android-2.3.3`), and Build Tools `25.0.2`:

```bash
cd camera-app
./gradlew assembleDebug
```

Output APK: `camera-app/app/build/outputs/apk/sony-pmca-webcontrol-debug-1.0.0.apk`

### 2. Flash to Camera via USB (`pmca-console`)

Connect the camera via USB (MTP / Mass Storage mode) and run:

```bash
python tools/Sony-PMCA-RE/pmca-console.py install -f camera-app/app/build/outputs/apk/sony-pmca-webcontrol-debug-1.0.0.apk
```

### 3. Launch on Camera

1. Disconnect USB.
2. On the camera: **Menu → Application → Application List → PMCA WebControl**.
3. Wait for the camera to join your configured Wi-Fi network. The LAN URL (e.g., `http://192.168.0.250:8080`) appears at the bottom of the camera screen.

---

## Part 2: HTTP REST API Specification

All endpoints support CORS (`Access-Control-Allow-Origin: *`).
All RPC calls run on the Android main thread with a **3000 ms timeout** (returning HTTP `504 Gateway Timeout` if the camera firmware blocks). Errors return `{"ok": false, "error": "..."}`.

### Summary of Endpoints
* `POST /api/call` — Universal reflective RPC (single or batched calls)
* `GET  /api/describe` — Introspect all callable targets, method names, and signatures
* `GET  /api/state` — Latest cached snapshots from all `CameraEx` listeners + `seq`
* `GET  /api/events` — Long-polling callback event stream
* `GET  /api/liveview.jpg` — 640px JPEG live preview frame
* `GET  /api/photos`, `/api/photos/latest`, `/api/photos/download` — SD card listing & downloads
* `GET  /api/log` — Plain-text camera debug/crash log

---

### 1. Universal Reflective RPC (`POST /api/call`)

#### Single Call Format
```json
{
  "target": "camera",
  "method": "adjustAperture",
  "args": [2]
}
```

#### Batch Call Format
Executes sequentially on the camera main thread in a single HTTP round-trip. **Consecutive `modifier` `set*` calls are automatically coalesced into a single `setParameters()` IPC call:**
```json
{
  "calls": [
    { "target": "modifier", "method": "setISOSensitivity", "args": [100] },
    { "target": "modifier", "method": "setDROMode",        "args": ["off"] },
    { "target": "camera",   "method": "adjustShutterSpeed", "args": [-2] }
  ]
}
```

#### Supported Targets
| `target` | Underlying Object | Behavior & Notes |
|---|---|---|
| `"camera"` | `com.sony.scalar.hardware.CameraEx` | Calls instance methods directly (excluding lifecycle/listener methods). Also supports synthetic `"capabilities"` returning `LensInfo` + all `ParametersModifier.is*Supported()` flags. |
| `"modifier"` | `CameraEx.ParametersModifier` | `get*`/`is*` automatically invoke on `getParameters()`. `set*` automatically invoke on `createEmptyParameters()` + `setParameters()`. |
| `"params"` | `android.hardware.Camera.Parameters` | Whitelisted `get`, `getInt`, `flatten`, `set`, `get/setExposureCompensation`, `get/setFocusMode`, `get/setWhiteBalance`, `get/setSceneMode`. |
| `"static:ScalarProperties"` | `com.sony.scalar.sysutil.ScalarProperties` | Read-only static getters (`getFirmwareVersion`, `getString`, `getInt`, `getIntArray`, `getByteArray`, `getSupportedPictureSizes`). |
| `"static:Settings"` | `com.sony.scalar.sysutil.didep.Settings` | `getTargetMediaId`, `getUsingMedia`, `isLiveAndRec`. |
| `"static:Temperature"` | `com.sony.scalar.sysutil.didep.Temperature` | `getStatus` — instantiates, reads `Temperature.Status` public fields, and releases safely. |
| `"static:System"` | Android System / `BatteryManager` | `getBattery` — returns `{ level, scale, percent, status, plugged }`. |

#### Automatic JSON ↔ Java Conversion
* **Arguments**:
  * Primitives & strings (`int`, `long`, `float`, `double`, `boolean`, `String`) convert automatically.
  * `int[]` / `byte[]` / `String[]`: pass a JSON array (e.g., `[1, 2, 3]`).
  * `android.util.Pair<Integer, Integer>`: pass a 2-element JSON array (e.g., `[0, 0]`).
  * Sony POJO structs (`SelectedColor`, `ExifInfo`, etc.): pass a JSON object matching public field names.
* **Overload Resolution**: Matched by method name, argument count, and JSON value type. Unmatched calls return candidate signatures in the error message.
* **Return Values**: Primitives, `Pair` → `[first, second]`, `List`/arrays → JSON array, and Sony structs (`LensInfo`, `WideTele`, `Temperature.Status`, etc.) → JSON object of all public non-static fields.

#### Security Blacklist
To prevent destabilizing the camera session or overwriting event hooks, the following are blocked:
* `open`, `reopen`, `release`, `getNormalCamera`, `create*`, `withSupportedParameters`, `tryIgnoreInhibit`, `shiftFocusPosition` (blocks/hangs on ILCE-6000 main thread)
* All `set*Listener`, `set*Callback`, and methods taking interface/callback arguments (callbacks are handled centrally and streamed via `/api/events`).

> **Caution (Preview Pipeline Rebuild):** Setting structural parameters such as `setImageSize`, `setImageAspectRatio`, `setRawRecMode`, `setPictureStorageFormat`, or `setDriveMode` may cause the camera firmware to rebuild its preview pipeline, briefly interrupting `/api/liveview.jpg`.

---

### 2. Self-Documenting Introspection (`GET /api/describe`)

Lists all callable targets, method names, parameter types, and return types discovered via reflection.

* **`GET /api/describe`**: Returns all targets and methods.
* **`GET /api/describe?target=camera&q=Focus`**: Filters by target and case-insensitive substring on method name.

---

### 3. State & Real-Time Event Bus

#### `GET /api/state`
Returns the latest cached snapshots from all registered `CameraEx` listeners plus the monotonic event sequence number (`seq`).

**Response Example:**
```json
{
  "ok": true,
  "seq": 1042,
  "aperture": { "currentAperture": 560, "currentAvailableMin": 350, "currentAvailableMax": 2200 },
  "shutter": {
    "currentShutterSpeed_n": 1, "currentShutterSpeed_d": 125,
    "currentAvailableMin_n": 1, "currentAvailableMin_d": 4000,
    "currentAvailableMax_n": 30, "currentAvailableMax_d": 1
  },
  "focus": { "currentPosition": 312, "maxPosition": 847 },
  "zoom": { "opticalPosition": 0, "opticalMagnification": 100, "digitalPosition": 0, "digitalMagnification": 100, "stopped": true },
  "autoIso": 400,
  "focalLength": 50,
  "powerZoom": 0,
  "previewMagnification": { "enabled": false, "ratio": 0 },
  "lastStoreImage": { "status": 0, "DirectoryName": "100MSDCF", "FileName": "DSC01234.JPG" },
  "lastShutterStatus": 0,
  "capturing": false,
  "lens": { "LensName": "FE 90mm F2.8 Macro G OSS", "LensType": "E-mount", "FixedAperture": false },
  "wifi": { "ip": "192.168.0.250", "port": 8080 }
}
```

> **Raw Units:**
> * `Aperture`: `F-number * 100` (e.g., `560` = `F5.6`, `280` = `F2.8`).
> * `Shutter`: fraction `n / d` seconds (e.g., `1/125`s or `30/1`s).
> * `Zoom magnification`: `100` = `1.0x`, `140` = `1.4x`, `200` = `2.0x`.
> * `Focus direction`: `0` = `NEAR`, `1` = `FAR`.
> * `Zoom direction`: `0` = `TELE` (zoom in), `1` = `WIDE` (zoom out).

#### `GET /api/events?since=<seq>&timeout=<sec>`
Long-polling endpoint backed by a 256-entry ring buffer. Blocks until at least one event with `seq > since` arrives, or until `timeout` seconds elapse (`0..30`, default `10`).

**Emitted Event Types:**
* `ApertureChange` — `{ currentAperture, currentAvailableMin, currentAvailableMax }`
* `ShutterSpeedChange` — `{ currentShutterSpeed_n, currentShutterSpeed_d, currentAvailableMin_*, currentAvailableMax_* }`
* `AutoISOSensitivity` — `{ iso }`
* `FocusDrive` — `{ currentPosition, maxPosition }`
* `AutoFocusStart` / `AutoFocusDone` — `{ status, focusAreaIds }`
* `ZoomChange` — `{ opticalPosition, opticalMagnification, digitalPosition, digitalMagnification, digitalZoomType, stopped }`
* `FocalLengthChange` — `{ focalLength }`
* `PowerZoom` — `{ status }`
* `PreviewMagnification` / `PreviewMagnificationInfo` — `{ enabled, ratio, pos }`
* `Shutter` — `{ status }` (`0` = OK, `1` = CANCELED, `2` = ERROR)
* `CaptureStart` / `CaptureEnd` — `{ capturing, reason }`
* `Jpeg` — `{ size }`
* `StoreImageComplete` — `{ status, DirectoryName, FileName, DirectoryNo, FileNo, MediaId }`
* `SettingChanged` — `{ types: [...] }`
* `EquipmentChange` — `{ deviceType, status }`
* `Error` — `{ error }`

---

### 4. LiveView & Photo Downloads

* **`GET /api/liveview.jpg`**: Latest 640px hardware JPEG frame from `CameraSequence`.
* **`GET /api/photos?dir=<relPath>&limit=100`**: Non-recursive directory listing under `DCIM` (sorted newest/name descending). Omit `dir` to list `DCIM` folders (e.g. `100MSDCF`, `101MSDCF`); pass `?dir=100MSDCF` to list photos in that folder. Returns `{ dir, totalPhotosInDir, dirs: [...], photos: [...] }`.
* **`GET /api/photos/latest`**: Downloads the latest captured JPEG (from in-memory capture buffer or SD card).
* **`GET /api/photos/download?path=<absolutePath>`**: Streams a specific SD card file (JPG or ARW).
* **`GET /api/log`**: Returns plain-text crash/debug logs from the camera.

---

## Part 3: Python Client SDK (`client/pmca_client.py`)

`SonyPmcaClient` wraps `/api/call`, `/api/describe`, `/api/state`, `/api/events`, and implements client-side closed-loop workflows:

```python
from client.pmca_client import SonyPmcaClient

cam = SonyPmcaClient("192.168.0.250", 8080)

# 1. Discover available methods & hardware capabilities
methods = cam.describe(target="camera", query="Focus")
caps = cam.capabilities()
battery = cam.get_battery()
temp = cam.get_temperature()

# 2. Direct single or batched RPC calls
cam.camera("incrementAperture")
cam.modifier("setISOSensitivity", 400)
cam.batch_call([
    {"target": "modifier", "method": "setISOSensitivity", "args": [100]},
    {"target": "modifier", "method": "setDROMode", "args": ["off"]},
    {"target": "params", "method": "set", "args": ["exposure-compensation", "-3"]},
])

# 3. Client-side closed-loop focus positioning
cam.goto_focus(350)

# 4. Capture, wait for StoreImageComplete event, and download exact file
cam.capture_and_download("shot_001.jpg")

# 5. Client-side automated focus bracketing stack with local download
cam.focus_stack(min_pos=120, max_pos=420, shots=25, save_dir="./stack_output")
```

---

## Part 4: Guide for Future Agents Extending the System

1. **Adding New Client Features Without Reflashing**:
   * First call `GET /api/describe` on the live camera (or inspect `stubs--SNAPSHOT.jar`) to verify if the desired Sony method already exists on `camera`, `modifier`, `params`, or `static:*`.
   * If it exists, implement the higher-level logic (e.g., target-F-number solver, HDR exposure bracketing, intervalometer, auto-exposure highlight protection) purely in the client SDK using `POST /api/call` + `GET /api/events`.
2. **Adding New Static/System Targets on Camera**:
   * In `RpcDispatcher.java`, register additional whitelisted classes (e.g., `com.sony.scalar.sysutil.didep.Media`, `com.sony.scalar.provider.AvindexStore`, `com.sony.scalar.sysutil.ScalarInput`) following the `static:Temperature` / `static:ScalarProperties` pattern.
3. **Adding New Event Listeners**:
   * Register additional `CameraEx` listeners (such as `IntervalRecListener`, `TrackingFocusListener`, `FaceDetectionListener`, `MeteringModeInfoListener`) in `MacroServerActivity.registerCameraListeners()` and forward them via `m_eventHub.emit(type, json)`.
4. **Streaming Upgrade**:
   * Replace `/api/liveview.jpg` HTTP polling and `/api/events` long-polling with `multipart/x-mixed-replace` MJPEG or WebSocket streaming if sub-30ms latency is needed.
