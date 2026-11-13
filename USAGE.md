# Sony a6000 AI Macro System — Usage & Agent Guide (`USAGE.md`)

This document instructs **human operators** and **AI Agents** on building and installing the in-camera APK, connecting the Sony a6000 to Wi-Fi, and controlling the camera via a web browser, HTTP REST API, or Python SDK for macro photography and automated focus stacking.

---

## Part 1: Setup & Installation (One-Time Human Setup)

### 1. Prerequisites
* **Camera**: Sony a6000 (`ILCE-6000`).
* **Lens**: **An E-mount lens with electronic contacts and an autofocus (AF) motor** is required for remote focus stepping and focus bracketing (e.g., a native AF macro lens, or any Sony AF lens paired with electronic AF macro extension tubes).
* **Saved Wi-Fi Network**: Connect the a6000 to your home/studio Wi-Fi router at least once in the stock camera settings so the SSID and password are saved in the camera's configuration.

### 2. Building the APK
A GitHub Actions workflow (`.github/workflows/build.yml`) is included. Pushing this repository to GitHub automatically builds `a6000-ai-macro-debug-1.0.0.apk` as a downloadable workflow artifact.

To build locally (requires JDK 8 and Android SDK `platforms;android-10` + `build-tools;25.0.2`):
```bash
cd camera-app
./gradlew assembleDebug
# Output APK: camera-app/app/build/outputs/apk/
```

### 3. Flashing the APK to the Sony a6000
1. On the camera menu: `Setup` → `USB Connection` → set to **`MTP`**.
2. Connect the camera to your computer via a data-capable Micro-USB cable and install the APK using `pmca-re-reloaded` / `Sony-PMCA-RE`:
   ```bash
   pmca-console install -f a6000-ai-macro-debug-1.0.0.apk
   ```
3. Unplug the USB cable. Open the camera's `Application List` to find **`AI Macro Server`**.

### 4. Starting the In-Camera Server
1. Set the physical mode dial on the camera to **`M` (Manual Exposure)**.
2. Launch **`AI Macro Server`** from the camera's `Application List`.
3. Within a few seconds, the top bar on the camera LCD will display:
   `READY (<SSID>): http://192.168.x.x:8080`
   Note this IP address (e.g., `192.168.1.105`).

---

## Part 2: Web Browser UI (Mac / iPad)

Open `http://<camera-ip>:8080` in Safari or Chrome on any device on the same Wi-Fi network:
* **Live View**: Streams live preview frames automatically every ~180ms.
* **Manual Focus Stepping**: Use `◀◀◀`, `◀◀`, `◀ Near` (focus closer) or `Far ▶`, `▶▶`, `▶▶▶` (focus farther).
* **Automated Focus Stacking**:
  1. Step the focus to the closest tip of the mineral specimen and click **`Set Near`**.
  2. Step the focus to the farthest edge of the specimen and click **`Set Far`**.
  3. Enter the number of `Shots` (e.g., `20`) and click **`Start Stack`**.
* **Exposure & Single Shot**: Adjust `F -` / `F +`, `Shutter -` / `Shutter +`, `ISO 100` / `ISO 400`, or click **`Capture`**.

---

## Part 3: HTTP REST API Reference (For AI Agents & Scripts)

Base URL: `http://<camera-ip>:8080` (CORS enabled on all endpoints).

### 1. Get Camera Status
* **`GET /api/status`**
* **Response (`application/json`)**:
  ```json
  {
    "state": "idle",
    "currentFocus": 72,
    "maxFocus": 255,
    "remainingShots": 0,
    "aperture": 800,
    "apertureFormatted": "F8.0",
    "shutterSpeed": "1/10",
    "iso": 100,
    "wifiIp": "192.168.1.105"
  }
  ```
* `state` values:
  * `idle` — Ready for commands.
  * `focusing` — Driving lens toward a target focus position.
  * `capturing` — Taking a single photo.
  * `bracketing` — Executing an automated focus-bracket sequence (`remainingShots` indicates progress).
  * `error` — Hardware/lens focus drive unsupported.

### 2. Fetch Live View Frame
* **`GET /api/liveview.jpg`**
* **Response**: `image/jpeg` binary frame (returns HTTP `503` if the preview pipeline has not produced its first frame yet).

### 3. Single-Step Manual Focus Drive
* **`POST /api/focus/drive?dir=<near|far>&speed=<1..4>`**
* **Query Parameters**:
  * `dir`: `near` (focus closer) or `far` (focus farther).
  * `speed`: Step size from `1` (finest micro-step) to `4` (largest step).

### 4. Closed-Loop Goto Focus Position
* **`POST /api/focus/goto?pos=<int>`**
* **Query Parameters**:
  * `pos`: Target integer coordinate in `[0, maxFocus]`.
* **Behavior**: Asynchronous. Poll `GET /api/status` until `state == "idle"`.

### 5. Start Automated Focus Bracketing
* **`POST /api/bracket?min=<int>&max=<int>&shots=<int>`**
* **Query Parameters**:
  * `min`: Starting focus coordinate.
  * `max`: Ending focus coordinate.
  * `shots`: Total number of frames to capture (`>= 2`).
* **Behavior**: Steps focus evenly from `min` to `max` and saves `shots` photos to the camera SD card. Poll `GET /api/status` (`remainingShots`) until `state == "idle"`.

### 6. Abort Active Focus Bracket
* **`POST /api/bracket/abort`**

### 7. Trigger Single Capture
* **`POST /api/capture`**

### 8. Adjust Exposure Parameters
* **`POST /api/exposure?aperture=<inc|dec>&shutter=<inc|dec>&iso=<int>`**
* All query parameters are optional and can be combined:
  * `aperture=inc` (stop down, e.g., F5.6 → F6.3) or `dec` (open up).
  * `shutter=inc` (faster shutter, e.g., 1/10 → 1/13) or `dec` (slower shutter).
  * `iso=100` (set specific ISO sensitivity).

---

## Part 4: Python SDK & AI Agent Standard Operating Procedure (SOP)

The zero-dependency Python client is located at `client/a6000_client.py` (`A6000MacroClient`):

```python
from client.a6000_client import A6000MacroClient

cam = A6000MacroClient("192.168.1.105")

# 1. Inspect status and capture a live preview frame
status = cam.get_status()
cam.save_liveview("preview.jpg")

# 2. Adjust exposure (e.g. prevent blown highlights on reflective crystals)
cam.set_exposure(iso=100, shutter="inc")

# 3. Move to an exact focus coordinate and wait until idle
cam.goto_focus(60, wait=True)

# 4. Run an automated 25-shot focus bracket stack from 50 to 140
cam.start_bracket(min_pos=50, max_pos=140, shots=25, wait=True)
```

### AI Agent SOP for Mineral Macro Photography
When an AI Agent is tasked with photographing a mineral specimen using this repository:
1. **Verify Readiness**: Call `get_status()`. Confirm `state == "idle"` and `maxFocus > 0`. (If `maxFocus == 0`, inform the user that an AF-capable lens or electronic extension tube must be attached).
2. **Optimize Exposure**: Fetch a preview frame via `get_liveview_jpeg()` or `save_liveview()`. Check for specular highlight clipping on crystal faces. If highlights are clipped, step the shutter faster (`set_exposure(shutter="inc")`) or lower ISO to `100` until highlights are preserved.
3. **Locate Focus Boundaries**: Sweep focus across the specimen using `goto_focus()` or `drive_focus()`, evaluating frame sharpness (via Laplacian variance as shown in `client/example_ai_macro.py` or via vision model inspection) to identify the front-most crystal tip (`min_pos`) and back-most edge (`max_pos`).
4. **Execute Focus Stack**: Call `start_bracket(min_pos, max_pos, shots=N, wait=True)` and monitor until completion.
5. **Post-Processing Hand-off**: Notify the user that the bracketed RAW/JPEG sequence has been recorded on the camera's SD card and is ready for stacking in Helicon Focus or Zerene Stacker.
