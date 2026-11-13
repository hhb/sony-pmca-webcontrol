# Sony PMCA WebControl — Usage & API Guide (`USAGE.md`)

This document instructs **human operators** and **AI Agents** on building and installing the in-camera APK, connecting a compatible Sony PlayMemories camera to Wi-Fi, and controlling the camera via a web browser, HTTP REST API, or Python SDK.

---

## Part 1: Requirements & Installation (One-Time Setup)

### 1. Hardware & Software Requirements
* **Camera**: Any PlayMemories-compatible Sony camera with Scalar Platform $\ge 2.3$:
  * **APS-C**: `a5100`, `a6000`, `a6300`, `a6500`
  * **Full-Frame**: `a7 II`, `a7R II`, `a7S II` (plus `a7` / `a7R` / `a7S` on latest firmware)
  * **Cyber-shot**: `RX100 III / IV / V`, `RX10 II / III`, `RX1R II`
* **Lens**: **An E-mount lens with electronic contacts and an autofocus (AF) motor** is required for remote focus stepping and focus bracketing (e.g., a native AF macro lens, any Sony AF lens paired with electronic AF macro extension tubes, or built-in motorized lenses on RX cameras).
* **Saved Wi-Fi Network**: Connect the camera to your home/studio Wi-Fi router at least once in the stock camera settings (`Wireless` → `Access Point Set.`) so the SSID and password are saved in the camera's configuration.
* **Host Environment**:
  * Python 3.9+ inside a virtual environment (`.venv`).
  * For compilation: JDK 8 (`OpenJDK 8`) and Android SDK (`platforms;android-10`, `build-tools;25.0.2`), or GitHub Actions.
  * For flashing: Linux (recommended) or Windows with [Sony-PMCA-RE (`pmca-console`)](https://github.com/ma1co/Sony-PMCA-RE).

### 2. Building the APK
A GitHub Actions workflow (`.github/workflows/build.yml`) is included. Pushing this repository to GitHub automatically builds `sony-pmca-webcontrol-debug-1.0.0.apk` as a downloadable workflow artifact.

To build locally:
```bash
cd camera-app
./gradlew assembleDebug
# Output APK: camera-app/app/build/outputs/apk/sony-pmca-webcontrol-debug-1.0.0.apk
```

### 3. Flashing the APK to the Camera
Flashing works best on **Linux** (via native `libusb`, no custom drivers needed) or **Windows** (using the prebuilt GUI/CLI release).

* **Official Tool (`pmca-console` / `pmca-gui`)**: [ma1co/Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) (Prebuilt binaries: [Releases](https://github.com/ma1co/Sony-PMCA-RE/releases))
* **Community Rust Alternative**: [masterflai/pmca-re-reloaded](https://github.com/masterflai/pmca-re-reloaded)

Steps to flash on Linux using a Python `venv`:
1. On the camera menu: `Setup` → `USB Connection` → set to **`Mass Storage`** or **`MTP`**.
2. Connect the camera to your computer via a data-capable Micro-USB cable and run:
   ```bash
   python3 -m venv .venv
   ./.venv/bin/pip install -r tools/Sony-PMCA-RE/requirements.txt
   sudo ./.venv/bin/python tools/Sony-PMCA-RE/pmca-console.py install -f camera-app/app/build/outputs/apk/sony-pmca-webcontrol-debug-1.0.0.apk
   ```
3. Unplug the USB cable. Open the camera's `Application List` to find **`PMCA WebControl`**.

### 4. Starting the In-Camera Server
1. Set the physical mode dial on the camera to **`M` (Manual Exposure)**.
2. Launch **`PMCA WebControl`** from the camera's `Application List`.
3. Within a few seconds, the top bar on the camera LCD will display:
   `READY (<SSID>): http://192.168.x.x:8080`
   Note this IP address (e.g., `192.168.1.105`).

---

## Part 2: Web Browser UI (Mac / iPad)

Open `http://<camera-ip>:8080` in Safari or Chrome on any device on the same Wi-Fi network:
* **Live View**: Streams live preview frames automatically every ~180ms.
* **Manual Focus Stepping**: Use `◀◀◀`, `◀◀`, `◀ Near` (focus closer) or `Far ▶`, `▶▶`, `▶▶▶` (focus farther).
* **Automated Focus Stacking**:
  1. Step the focus to the closest tip of the subject and click **`Set Near`**.
  2. Step the focus to the farthest edge of the subject and click **`Set Far`**.
  3. Enter the number of `Shots` (e.g., `20`) and click **`Start Stack`**.
* **Exposure & Single Shot**: Adjust `F -` / `F +`, `Shutter -` / `Shutter +`, `ISO 100` / `ISO 400`, or click **`Capture`**.

---

## Part 3: HTTP REST API Reference (For Scripts & AI Agents)

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
* **Response**: `image/jpeg` binary frame (returns HTTP `500` if the preview pipeline has not produced its first frame yet).

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

## Part 4: Python SDK & Automation / AI Agent SOP

The zero-dependency Python client is located at `client/pmca_client.py` (`SonyPmcaClient`):

```python
from client.pmca_client import SonyPmcaClient

cam = SonyPmcaClient("192.168.1.105")

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

### Automation / AI Agent SOP for Macro Focus Stacking
When an automated script or AI Agent is tasked with photographing a macro subject using this repository:
1. **Verify Readiness**: Call `get_status()`. Confirm `state == "idle"` and `maxFocus > 0`. (If `maxFocus == 0`, inform the user that an AF-capable lens or electronic extension tube must be attached).
2. **Optimize Exposure**: Fetch a preview frame via `get_liveview_jpeg()` or `save_liveview()`. Check for specular highlight clipping on crystal faces. If highlights are clipped, step the shutter faster (`set_exposure(shutter="inc")`) or lower ISO to `100` until highlights are preserved.
3. **Locate Focus Boundaries**: Sweep focus across the subject using `goto_focus()` or `drive_focus()`, evaluating frame sharpness (via Laplacian variance as shown in `client/example_macro_stack.py` or via vision model inspection) to identify the front-most point (`min_pos`) and back-most edge (`max_pos`).
4. **Execute Focus Stack**: Call `start_bracket(min_pos, max_pos, shots=N, wait=True)` and monitor until completion.
5. **Post-Processing Hand-off**: Notify the user that the bracketed RAW/JPEG sequence has been recorded on the camera's SD card and is ready for stacking in Helicon Focus or Zerene Stacker.
