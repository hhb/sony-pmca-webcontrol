# Sony PMCA WebControl — Usage & API Reference (`USAGE.md`)

This document is the complete reference for building, flashing, and developing clients or AI Agents against **Sony PMCA WebControl** (`com.sony.pmca.webcontrol`).

The camera-side application acts as a **thin, stateless hardware bridge** exposing Sony's underlying `CameraEx`, `ParametersModifier`, `Camera.Parameters`, and `CameraSequence` APIs over HTTP/JSON. Higher-level workflows (such as closed-loop focus positioning, target aperture/shutter stepping, and automated focus bracketing) are implemented in client SDKs (see `client/pmca_client.py`).

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
Camera calls are marshaled to the Android main thread with a **3000 ms timeout** (returning HTTP `504 Gateway Timeout` if the camera firmware blocks). Errors return `{"ok": false, "error": "..."}`.

---

### 1. State & Real-Time Event Bus

#### `GET /api/state`
Returns the latest cached snapshots from all registered `CameraEx` listeners plus the current monotonic event sequence number (`seq`).

**Response Example:**
```json
{
  "ok": true,
  "seq": 1042,
  "aperture": {
    "currentAperture": 560,
    "currentAvailableMin": 350,
    "currentAvailableMax": 2200
  },
  "shutter": {
    "currentShutterSpeed_n": 1,
    "currentShutterSpeed_d": 125,
    "currentAvailableMin_n": 1,
    "currentAvailableMin_d": 4000,
    "currentAvailableMax_n": 30,
    "currentAvailableMax_d": 1
  },
  "focus": {
    "currentPosition": 312,
    "maxPosition": 847
  },
  "zoom": {
    "opticalPosition": 0,
    "opticalMagnification": 100,
    "digitalPosition": 0,
    "digitalMagnification": 100,
    "digitalZoomType": "precision",
    "stopped": true
  },
  "autoIso": 400,
  "focalLength": 50,
  "powerZoom": 0,
  "previewMagnification": {
    "enabled": false,
    "ratio": 0
  },
  "lastStoreImage": {
    "status": 0,
    "DirectoryName": "100MSDCF",
    "FileName": "DSC01234.JPG",
    "DirectoryNo": 100,
    "FileNo": 1234,
    "MediaId": 1
  },
  "lastShutterStatus": 0,
  "capturing": false,
  "lens": {
    "LensName": "FE 90mm F2.8 Macro G OSS",
    "LensType": "E-mount",
    "FixedAperture": false,
    "FocalLength": { "wide": 90, "tele": 90 },
    "MinFValue": { "wide": 280, "tele": 280 },
    "MaxFValue": { "wide": 2200, "tele": 2200 }
  },
  "wifi": {
    "ip": "192.168.0.250",
    "port": 8080
  }
}
```

> **Raw Units:**
> * `Aperture`: `F-number * 100` (e.g., `560` = `F5.6`, `280` = `F2.8`).
> * `Shutter`: fraction `n / d` seconds (e.g., `1/125`s or `30/1`s).
> * `Zoom magnification`: `100` = `1.0x`, `140` = `1.4x`, `200` = `2.0x`.

---

#### `GET /api/events?since=<seq>&timeout=<sec>`
Long-polling endpoint backed by a 256-entry ring buffer. Blocks until at least one event with `seq > since` occurs, or until `timeout` seconds elapse (`0..30`, default `10`).

**Response:**
```json
{
  "ok": true,
  "seq": 1044,
  "events": [
    {
      "seq": 1043,
      "t": 1731475200123,
      "type": "FocusDrive",
      "data": { "currentPosition": 315, "maxPosition": 847 }
    },
    {
      "seq": 1044,
      "t": 1731475200890,
      "type": "StoreImageComplete",
      "data": { "status": 0, "DirectoryName": "100MSDCF", "FileName": "DSC01235.JPG" }
    }
  ]
}
```

**Emitted Event Types:**
* `ApertureChange` — `{ currentAperture, currentAvailableMin, currentAvailableMax }`
* `ShutterSpeedChange` — `{ currentShutterSpeed_n, currentShutterSpeed_d, currentAvailableMin_*, currentAvailableMax_* }`
* `AutoISOSensitivity` — `{ iso }` (effective ISO chosen when ISO is `0` / AUTO)
* `FocusDrive` — `{ currentPosition, maxPosition }`
* `AutoFocusStart` — `{}`
* `AutoFocusDone` — `{ status, focusAreaIds }`
* `ZoomChange` — `{ opticalPosition, opticalMagnification, digitalPosition, digitalMagnification, digitalZoomType, stopped }`
* `FocalLengthChange` — `{ focalLength }`
* `PowerZoom` — `{ status }`
* `PreviewMagnification` — `{ enabled, ratio, pos }`
* `PreviewMagnificationInfo` — `{ enabled, pos }`
* `Shutter` — `{ status }` (`0` = OK, `1` = CANCELED, `2` = ERROR)
* `CaptureStart` / `CaptureEnd` — `{ capturing, reason }`
* `Jpeg` — `{ size }` (in-memory review JPEG captured)
* `StoreImageComplete` — `{ status, DirectoryName, FileName, DirectoryNo, FileNo, MediaId }`
* `SettingChanged` — `{ types: [...] }` (triggered by physical camera dials/menus or parameter writes)
* `EquipmentChange` — `{ deviceType, status }` (lens/flash attach/detach)
* `Error` — `{ error }`

---

### 2. Direct `CameraEx` Commands (`/api/camera/<action>`)

Whitelisted 1-to-1 wrappers for `com.sony.scalar.hardware.CameraEx` methods. Most command methods return asynchronously via `/api/events`.

#### Aperture & Shutter (Dial Stepping)
| Endpoint | Method | Parameters | Underlying `CameraEx` Call |
|---|---|---|---|
| `/api/camera/incrementAperture` | POST | — | `incrementAperture()` |
| `/api/camera/decrementAperture` | POST | — | `decrementAperture()` |
| `/api/camera/adjustAperture` | POST | `n` (or `steps`, int) | `adjustAperture(n)` |
| `/api/camera/incrementShutterSpeed` | POST | — | `incrementShutterSpeed()` |
| `/api/camera/decrementShutterSpeed` | POST | — | `decrementShutterSpeed()` |
| `/api/camera/adjustShutterSpeed` | POST | `n` (or `steps`, int) | `adjustShutterSpeed(n)` |
| `/api/camera/adjustProgramLine` | POST | `n` (or `steps`, int) | `adjustProgramLine(n)` |

#### Focus Motor & Autofocus
| Endpoint | Method | Parameters | Underlying `CameraEx` Call |
|---|---|---|---|
| `/api/camera/startOneShotFocusDrive` | POST | `dir` (`0`=NEAR, `1`=FAR), `speed` (`1..4`) | `startOneShotFocusDrive(dir, speed)` |
| `/api/camera/shiftFocusPosition` | POST | `n` (int delta) | `shiftFocusPosition(n)` → returns `{"result": boolean}` |
| `/api/camera/startFocusHold` | POST | — | `startFocusHold()` |
| `/api/camera/stopFocusHold` | POST | — | `stopFocusHold()` |
| `/api/camera/executeAutoFocusStartTrigger` | POST | `halfPress` (`true`/`false`), `mode` (optional) | `executeAutoFocusStartTrigger(halfPress, mode)` |

#### Zoom & MF Assist Magnification
| Endpoint | Method | Parameters | Underlying `CameraEx` Call |
|---|---|---|---|
| `/api/camera/startZoom` | POST | `dir` (`0`=TELE/In, `1`=WIDE/Out), `speed` (int) | `startZoom(dir, speed)` |
| `/api/camera/stopZoom` | POST | — | `stopZoom()` |
| `/api/camera/setDigitalZoom` | POST | `v` (int, e.g. `140`, `200`, `400`) | `setDigitalZoom(v)` |
| `/api/camera/resetDigitalZoom` | POST | — | `resetDigitalZoom()` |
| `/api/camera/setPreviewMagnification` | POST | `ratio` (int, e.g. `590`, `1170`), `x`, `y` | `setPreviewMagnification(ratio, Pair(x,y))` |
| `/api/camera/stopPreviewMagnification` | POST | — | `stopPreviewMagnification()` |

#### Capture & Hardware Queries
| Endpoint | Method | Parameters | Underlying `CameraEx` Call |
|---|---|---|---|
| `/api/camera/burstableTakePicture` | POST | — | `burstableTakePicture()` |
| `/api/camera/cancelTakePicture` | POST | — | `cancelTakePicture()` |
| `/api/camera/cancelExposure` | POST | — | `cancelExposure()` (ends BULB exposure) |
| `/api/camera/getLensInfo` | GET | — | `getLensInfo()` |
| `/api/camera/getInhibitionInfo` | GET | — | `getInhibitionInfo()` |
| `/api/camera/capabilities` | GET | — | `getLensInfo()` + all `ParametersModifier.is*Supported()` flags |

---

### 3. `ParametersModifier` Reflection Proxy (`/api/modifier`)

Exposes all ~340 `is*`, `get*`, `getSupported*`, `getMin*`, `getMax*`, and `set*` methods of `CameraEx.ParametersModifier` with zero hardcoded business logic (`tryIgnoreInhibit` is excluded). Method objects are cached at startup.

* **`GET /api/modifier`**: Self-documenting introspection endpoint returning every callable method name, parameter types, and return type.
* **`GET /api/modifier/<methodName>`**: Invokes a getter/query on `getParameters()`. Optional positional arguments: `?arg0=...&arg1=...` or `?v=...`.
  * `GET /api/modifier/getSupportedISOSensitivities` → `{"ok":true,"method":"getSupportedISOSensitivities","result":[0,100,125,160,...]}`
  * `GET /api/modifier/getISOSensitivity` → `{"ok":true,"method":"getISOSensitivity","result":400}`
  * `GET /api/modifier/getMaxColorTemperatureForWhiteBalance`
* **`GET /api/modifier?get=Method1,Method2,...`**: **Batch read** executing all parameterless getters against a single `getParameters()` IPC call. You can omit the `get`/`is` prefix (e.g., `?get=ISOSensitivity,DROMode`).
* **`POST /api/modifier/<methodName>?v=<val>`** (or JSON body `{"v": ...}` / `{"args": [...]}`): Invokes a setter via `createEmptyParameters()` + `setParameters()`.
  * `POST /api/modifier/setISOSensitivity?v=800`
  * `POST /api/modifier/setColorTemperatureForWhiteBalance?v=5600`
* **`POST /api/modifier`** with JSON body: **Batch write** applying multiple setters in a single `setParameters()` call. Keys may include or omit the `set` prefix:
  ```json
  {
    "ISOSensitivity": 800,
    "DROMode": "off",
    "ColorTemperatureForWhiteBalance": 5500
  }
  ```

> **Caution (Preview Pipeline Rebuild):** Changing structural image parameters such as `setImageSize`, `setImageAspectRatio`, `setRawRecMode`, `setPictureStorageFormat`, or `setDriveMode` may cause the camera firmware to temporarily tear down and restart its preview pipeline, briefly interrupting `/api/liveview.jpg`.

---

### 4. Raw `android.hardware.Camera.Parameters` (`/api/params`)

Thin key-value bridge for standard Android camera parameters that live outside `ParametersModifier` (such as `exposure-compensation`, `min-exposure-compensation`, `max-exposure-compensation`, `exposure-compensation-step`, `focus-mode`, `whitebalance`, `scene-mode`).

* **`GET /api/params`**: Returns all flattened `Camera.Parameters` key-value pairs.
* **`GET /api/params/<key>`**: Returns a single parameter string.
* **`POST /api/params`**: Applies key-value updates via `createEmptyParameters()` + `setParameters()`. Accepts JSON body (e.g., `{"exposure-compensation": "-3"}`) or query parameters.

---

### 5. LiveView & Photo Downloads

* **`GET /api/liveview.jpg`**: Latest 640px hardware JPEG frame from `CameraSequence`.
* **`GET /api/photos?limit=50`**: Lists `.JPG` / `.ARW` files on SD card sorted newest-first.
* **`GET /api/photos/latest`**: Downloads the latest captured JPEG (from in-memory capture buffer or SD card).
* **`GET /api/photos/download?path=<absolutePath>`**: Streams a specific SD card file (JPG or ARW).
* **`GET /api/log`**: Returns plain-text crash/debug logs from the camera.

---

## Part 3: Python Client SDK (`client/pmca_client.py`)

`SonyPmcaClient` provides both 1-to-1 wrappers and client-side closed-loop workflows:

```python
from client.pmca_client import SonyPmcaClient

cam = SonyPmcaClient("192.168.0.250", 8080)

# 1. Inspect capabilities & state
caps = cam.capabilities()
state = cam.get_state()

# 2. Direct ParametersModifier batch read/write
info = cam.modifier_batch_get("ISOSensitivity", "SupportedISOSensitivities", "DROMode")
cam.modifier_batch_set(ISOSensitivity=100, DROMode="off")

# 3. Direct CameraEx commands
cam.camera("incrementAperture")
cam.camera("decrementShutterSpeed")

# 4. Client-side closed-loop focus positioning (uses shiftFocusPosition + FocusDrive events)
cam.goto_focus(350)

# 5. Capture, wait for StoreImageComplete event, and download exact file
cam.capture_and_download("shot_001.jpg")

# 6. Client-side automated focus bracketing stack with local download
cam.focus_stack(min_pos=120, max_pos=420, shots=25, save_dir="./stack_output")
```

---

## Part 4: Extensible Future Sony APIs (Roadmap for Agents)

The `stubs--SNAPSHOT.jar` in OpenMemories-Framework exposes additional Sony Scalar classes that are **not yet wired** into the HTTP server, should future client applications need them:

1. **Camera Dial & Hardware Input (`ScalarInput`, `KeyStatus`)**:
   * Listening for physical mode dial changes (`M`/`A`/`S`/`P` via `SettingChangedListener` `TYPE_SCENE_MODE`) or reading custom buttons / rotary dials.
2. **Additional `CameraEx` Subsystems**:
   * **Interval Recording**: `startIntervalRec()` / `stopIntervalRec()` + `IntervalRecListener`.
   * **Tracking & Face Focus**: `startTrackingFocus(x, y)` / `stopTrackingFocus()` + `TrackingFocusListener`, `startFaceDetection()` + `FaceDetectionListener`.
   * **Custom White Balance Capture**: `captureCustomWhiteBalance()` / `saveCustomWhiteBalance(channel)`.
   * **Metering & Exposure Evaluation**: `getProperExposureLevel()` + `ProperExposureLevelCallback`, `MeteringModeInfoListener`.
   * **Extended Gamma Curves**: `createGammaTable()` / `setExtendedGammaTable()`.
   * **Multi-Media Slot Selection**: `setRecordingMedia(mediaId, callback)`.
3. **System Telemetry (`com.sony.scalar.sysutil.didep.*`)**:
   * `Temperature`: Read sensor/body thermal state during long focus stacks.
   * `Power`: Query battery level percentage and external power status.
   * `Media` & `AvindexStore`: Query SD card free space and fast indexed media DB queries instead of recursive filesystem scans.
4. **WebSocket / MJPEG Stream**:
   * Replace `/api/liveview.jpg` HTTP polling and `/api/events` long-polling with a persistent WebSocket or `multipart/x-mixed-replace` MJPEG stream for lower latency live view.
