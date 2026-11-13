# Sony PMCA WebControl (`sony-pmca-webcontrol`)

Turn any PlayMemories-capable Sony Alpha / Cyber-shot camera (such as **a6000, a6300, a6500, a5100, a7 II, a7R II, a7S II, RX100 III–V, RX10 II–III**) into a network-connected workstation with **Wi-Fi LAN MJPEG Live View, browser-based remote Manual Focus (MF) stepping, client-side closed-loop Focus Bracketing, and a reflective HTTP JSON-RPC API**.

## Key Features

* **Direct Home Wi-Fi Connection**: Connects directly to your existing Wi-Fi router (no need to disconnect your Mac or iPad from the internet to join an ad-hoc camera hotspot) and displays its LAN IP address on the camera screen (port `80` or `8080`).
* **Universal Reflective RPC (`POST /api/call` & `GET /api/describe`)**: Exposes Sony's underlying `CameraEx`, `ParametersModifier`, `Camera.Parameters`, and whitelisted Scalar static/system classes over HTTP with automatic JSON ↔ Java conversion and batching.
* **Real-Time Event Stream & MJPEG Live View**: Hardware-accelerated 640px JPEG preview stream (`/api/liveview.mjpeg` and `/api/liveview.jpg`) plus long-polling `CameraEx` listener events (`/api/events`) and state snapshots (`/api/state`).
* **Modern React Web Client (`web-client/`)**: Single-file React + TypeScript app featuring live Focus Peaking, rule-of-thirds grid, luma histogram, 2x–4x click-to-magnify Focus Loupe, exposure & EV compensation controls, automated closed-loop focus stacking, SD card photo browser, auto-save to local directory, and an interactive RPC Inspector.
* **Built-in Fallback Web UI**: Opening `http://<camera-ip>:8080` directly in any browser serves a lightweight built-in control page (`camera-app/app/src/main/assets/index.html`) for quick testing without running `web-client/`.
* **Automated GitHub Actions Build**: Automatically compiles the camera `.apk` in the cloud on every push.

## Requirements

### 1. Compatible Sony Cameras
* **Full Support** (Live View + Remote MF Stepping + Automated Focus Bracketing + Exposure Control; requires Scalar Android Platform $\ge 2.3$):
  * **APS-C E-mount**: `ILCE-5100` (a5100), `ILCE-6000` (a6000), `ILCE-6300` (a6300), `ILCE-6500` (a6500)
  * **Full-Frame E-mount**: `ILCE-7M2` (a7 II), `ILCE-7RM2` (a7R II), `ILCE-7SM2` (a7S II), and `ILCE-7` / `ILCE-7R` / `ILCE-7S` updated to the latest firmware
  * **Fixed-Lens Cyber-shot**: `DSC-RX100M3` (RX100 III), `DSC-RX100M4` (RX100 IV), `DSC-RX100M5` (RX100 V), `DSC-RX10M2` (RX10 II), `DSC-RX10M3` (RX10 III), `DSC-RX1RM2` (RX1R II)
* **Partial Support** (Live View + Exposure + Capture only; Platform $< 2.3$ lacks `startOneShotFocusDrive`):
  * `NEX-5R`, `NEX-5T`, `NEX-6`, `DSC-RX100M2`
* **Unsupported**:
  * Post-2018 Sony bodies that removed the PlayMemories Android subsystem (`a6100`, `a6400`, `a6600`, `a6700`, `ZV-E10`, `a7 III`, `a7 IV`, etc.).

### 2. Lens Requirements
* Remote focus stepping and automated focus bracketing require an **E-mount lens with electronic contacts and an autofocus (AF) motor** (or a Sony AF lens paired with electronic AF macro extension tubes, or a built-in motorized lens on RX-series cameras).
* Fully mechanical manual lenses (e.g., Laowa 65mm f/2.8 2X) cannot be driven by software focus commands, though live view, exposure control, and remote shutter release remain functional.

### 3. Host / Flashing Requirements
* **Flashing (One-Time)**: Linux (recommended, via `libusb`) or Windows, Python 3.9+ in a `venv`, and [Sony-PMCA-RE (`pmca-console`)](https://github.com/ma1co/Sony-PMCA-RE).
* **Daily Operation / Web Client**: Any OS (macOS, iPadOS, Linux, Windows) on the same Wi-Fi LAN. Node.js 18+ to run or build `web-client/` (or open `http://<camera-ip>:8080` directly for the built-in fallback UI).

## Documentation

* **System Architecture & Technical Design**: [DESIGN.md](./DESIGN.md)
* **Build, Flashing, API Reference & Web Client Guide**: [USAGE.md](./USAGE.md)

## Repository Structure

* `camera-app/` — Android 2.3 (API 10) in-camera APK source built on `OpenMemories-Framework` and `NanoHTTPD`
* `web-client/` — React + TypeScript + Vite web control UI and `CameraClient` SDK (`src/api/cameraClient.ts`)
* `.github/workflows/build.yml` — GitHub Actions workflow to build the `.apk` artifact
