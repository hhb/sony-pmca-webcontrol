# Sony PMCA WebControl — HTTP & RPC API Reference (`API.md`)

The camera runs an embedded HTTP server (`MacroHttpServer`, `NanoHTTPD` on port `80` with fallback to `8080`).

* **CORS**: All endpoints return `Access-Control-Allow-Origin: *` and support `OPTIONS` preflight requests.
* **Main-Thread Timeout**: Reflective RPC calls execute on the Android main thread with a **3000 ms timeout** (returning HTTP `504 Gateway Timeout` if camera firmware blocks).
* **Error Format**: Failed API calls return `{"ok": false, "error": "..."}`.

---

## Summary of Endpoints

| Method & Path | Description |
|---|---|
| `POST /api/call` | Universal reflective RPC (single or batched calls) |
| `GET  /api/describe` | Introspect all callable targets, method names, and signatures |
| `GET  /api/state` | Latest cached snapshots from all `CameraEx` listeners + `seq` |
| `GET  /api/events` | Long-polling callback event stream |
| `GET  /api/liveview.mjpeg` | Adaptive `multipart/x-mixed-replace` 640px JPEG stream (up to 3 concurrent clients) |
| `GET/POST /api/liveview/close` | Explicitly close an active MJPEG client by `cid` |
| `GET  /api/liveview.jpg` | Single latest 640px JPEG live preview frame |
| `GET  /api/photos` | Non-recursive `DCIM` folder and photo listing |
| `GET  /api/photos/latest` | Download the latest captured JPEG |
| `GET  /api/photos/download` | Download a specific SD card file (`.JPG` or `.ARW`) |
| `GET  /api/log` | Plain-text camera debug/crash log |

---

## 1. Universal Reflective RPC (`POST /api/call`)

### Single Call Format
```json
{
  "target": "camera",
  "method": "adjustAperture",
  "args": [2]
}
```

### Batch Call Format
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

### Supported Targets
| `target` | Underlying Object | Behavior & Notes |
|---|---|---|
| `"camera"` | `com.sony.scalar.hardware.CameraEx` | Calls instance methods directly (excluding lifecycle/listener methods). Also supports synthetic `"capabilities"` returning `LensInfo` + all `ParametersModifier.is*Supported()` flags. |
| `"modifier"` | `CameraEx.ParametersModifier` | `get*`/`is*` automatically invoke on `getParameters()`. `set*` automatically invoke on `createEmptyParameters()` + `setParameters()`. |
| `"params"` | `android.hardware.Camera.Parameters` | Whitelisted `get`, `getInt`, `flatten`, `set`, `get/setExposureCompensation`, `get/setFocusMode`, `get/setWhiteBalance`, `get/setSceneMode`. |
| `"static:ScalarProperties"` | `com.sony.scalar.sysutil.ScalarProperties` | Read-only static getters (`getFirmwareVersion`, `getString`, `getInt`, `getIntArray`, `getByteArray`, `getSupportedPictureSizes`). |
| `"static:Settings"` | `com.sony.scalar.sysutil.didep.Settings` | `getTargetMediaId`, `getUsingMedia`, `isLiveAndRec`. |
| `"static:Temperature"` | `com.sony.scalar.sysutil.didep.Temperature` | `getStatus` — instantiates, reads `Temperature.Status` public fields, and releases safely. |
| `"static:System"` | Android System / `BatteryManager` | `getBattery` — returns `{ level, scale, percent, status, plugged }`. |
| `"highlevel"` | `CameraGoalController` | Device-side event-driven closed-loop setters (`setFocusPosition`, `setAperture`, `setShutterSpeed`, `setZoomPosition`). Runs on HTTP worker thread without blocking camera UI. |

### High-Level Closed-Loop APIs (`target: "highlevel"`)
Sony `CameraEx` does not expose absolute setters for focus, aperture, shutter speed, or optical zoom — only relative stepping methods. `CameraGoalController` drives these in a tight local closed loop using hardware listener callbacks:

* **`setFocusPosition(targetPos[, tolerance])`**: Drives MF position using `startOneShotFocusDrive` until within `tolerance` (default `2`).
* **`setAperture(fValue100)`**: Steps `incrementAperture` / `decrementAperture` until reaching `fValue100` (e.g., `800` for `F8.0`, clamped to `currentAvailableMin`..`currentAvailableMax`).
* **`setShutterSpeed(numerator, denominator)`**: Steps `incrementShutterSpeed` / `decrementShutterSpeed` until reaching `numerator / denominator` seconds (e.g., `[1, 125]` for `1/125s`).
* **`setZoomPosition(opticalPosition[, tolerance])`**: Steps `startZoom` / `stopZoom` until reaching `opticalPosition` (default tolerance `1`).

**Concurrency & Preemption:**
* **Cross-parameter parallel**: Focus, aperture, shutter, and zoom each have independent state slots and can run concurrently.
* **Same-parameter latest-wins**: Sending a new target for the same parameter immediately supersedes any in-flight call (`superseded: true`) and steers directly to the new target.

```json
// POST /api/call
{ "target": "highlevel", "method": "setFocusPosition", "args": [350, 2] }

// Response
{
  "ok": true,
  "target": "highlevel",
  "method": "setFocusPosition",
  "result": { "converged": true, "superseded": false, "stalled": false, "position": 350, "target": 350, "steps": 6 }
}
```

### Automatic JSON ↔ Java Conversion
* **Arguments**:
  * Primitives & strings (`int`, `long`, `float`, `double`, `boolean`, `String`) convert automatically.
  * `int[]` / `byte[]` / `String[]`: pass a JSON array (e.g., `[1, 2, 3]`).
  * `android.util.Pair<Integer, Integer>`: pass a 2-element JSON array (e.g., `[0, 0]`).
  * Sony POJO structs (`SelectedColor`, `ExifInfo`, etc.): pass a JSON object matching public field names.
* **Overload Resolution**: Matched by method name, argument count, and JSON value type. Unmatched calls return candidate signatures in the error message.
* **Return Values**: Primitives, `Pair` → `[first, second]`, `List`/arrays → JSON array, and Sony structs (`LensInfo`, `WideTele`, `Temperature.Status`, etc.) → JSON object of all public non-static fields.

### Security Blacklist
To prevent destabilizing the camera session or overwriting event hooks, the following are blocked:
* `open`, `reopen`, `release`, `getNormalCamera`, `create*`, `withSupportedParameters`, `tryIgnoreInhibit`, `shiftFocusPosition` (blocks/hangs on `ILCE-6000` main thread)
* All `set*Listener`, `set*Callback`, and methods taking interface/callback arguments (callbacks are handled centrally and streamed via `/api/events`).

> **Caution (Preview Pipeline Rebuild):** Setting structural parameters such as `setImageSize`, `setImageAspectRatio`, `setRawRecMode`, `setPictureStorageFormat`, or `setDriveMode` may cause the camera firmware to rebuild its preview pipeline, briefly interrupting LiveView.

---

## 2. Self-Documenting Introspection (`GET /api/describe`)

Lists all callable targets, method names, parameter types, and return types discovered via reflection.

* **`GET /api/describe`**: Returns all targets and methods.
* **`GET /api/describe?target=camera&q=Focus`**: Filters by target and case-insensitive substring on method name.

---

## 3. State & Real-Time Event Bus

### `GET /api/state`
Returns the latest cached snapshots from all registered `CameraEx` listeners plus the monotonic event sequence number (`seq`).

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

### `GET /api/events?since=<seq>&timeout=<sec>`
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

## 4. LiveView & Photo Downloads

* **`GET /api/liveview.mjpeg?cid=<clientId>`**: Continuous `multipart/x-mixed-replace` 640px hardware JPEG stream from `CameraSequence` (up to 3 concurrent clients; if full, the oldest client is evicted; if the same `cid` reconnects, its previous stream is replaced immediately).
* **`GET|POST /api/liveview/close?cid=<clientId>`**: Immediately closes and releases the MJPEG stream matching `cid` (designed for `navigator.sendBeacon` / `fetch(..., { keepalive: true })` on tab hide, pause, or page unload). Returns `{"ok": true, "closed": true|false}`.
* **`GET /api/liveview.jpg`**: Latest 640px hardware JPEG frame from `CameraSequence`.
* **`GET /api/photos?dir=<relPath>&limit=100`**: Non-recursive directory listing under `DCIM` (sorted descending). Omit `dir` to list `DCIM` folders (e.g., `100MSDCF`, `101MSDCF`); pass `?dir=100MSDCF` to list photos in that folder. Returns `{ dir, totalPhotosInDir, dirs: [...], photos: [...] }`.
* **`GET /api/photos/latest`**: Downloads the latest captured JPEG (from in-memory capture buffer or SD card).
* **`GET /api/photos/download?path=<absolutePath>`**: Streams a specific SD card file (JPG or ARW).
* **`GET /api/log`**: Returns plain-text crash/debug logs from the camera.
