# Sony a6000 AI Macro System

Turn a Sony a6000 (`ILCE-6000`) into a network-connected macro photography workstation with **Wi-Fi LAN Live View, browser-based remote Manual Focus (MF) stepping, in-camera Focus Bracketing, and an AI-ready HTTP REST API**.

## Key Features

* **Direct Home Wi-Fi Connection**: Connects directly to your existing Wi-Fi router (no need to disconnect your Mac or iPad from the internet to join an ad-hoc camera hotspot) and displays its LAN IP address on the camera screen (port `8080`).
* **Built-in Web Control UI**: Open `http://<camera-ip>:8080` in Safari/Chrome on Mac or iPad to view live preview frames, step manual focus (`<<<` `<<` `<` `>` `>>` `>>>`), adjust aperture/shutter/ISO, and run automated focus bracketing stacks.
* **AI-Friendly HTTP REST API & Python SDK**: Clean JSON/JPEG endpoints (`/api/status`, `/api/liveview.jpg`, `/api/focus/drive`, `/api/focus/goto`, `/api/bracket`, `/api/exposure`, `/api/capture`) plus a zero-dependency Python client (`client/a6000_client.py`).
* **Automated GitHub Actions Build**: Automatically compiles the camera `.apk` in the cloud on every push.

## Documentation

* **System Architecture & Technical Design**: [DESIGN.md](./DESIGN.md)
* **Build, Flashing, API Reference & AI Agent Guide**: [USAGE.md](./USAGE.md)

## Repository Structure

* `camera-app/` — Android 2.3 (API 10) in-camera APK source built on `OpenMemories-Framework` and `NanoHTTPD`
* `client/` — Python client SDK (`a6000_client.py`) and automated AI macro focus-stacking script (`example_ai_macro.py`)
* `.github/workflows/build.yml` — GitHub Actions workflow to build the `.apk` artifact

## Hardware Requirement

* Remote focus stepping and automated focus bracketing require an **E-mount lens with electronic contacts and an autofocus (AF) motor** (or a Sony AF lens paired with electronic AF macro extension tubes). Fully mechanical manual lenses can still use live view, exposure control, and remote shutter release.
