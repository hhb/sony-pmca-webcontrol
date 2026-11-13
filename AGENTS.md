# AGENTS.md — AI Coding Agent Guide

Project: **Sony PMCA WebControl** — Android 2.3 (API 10) in-camera HTTP/RPC bridge (`camera-app/`) + React/TypeScript web frontend (`web-client/`).

## Build & Flashing Commands

* **Build camera APK (`camera-app/`)**:
  * Must use **JDK 8** (Android Gradle plugin `2.1.x` fails on JDK 9+):
    ```bash
    cd camera-app && JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 ./gradlew assembleDebug
    ```
  * Output: `camera-app/app/build/outputs/apk/sony-pmca-webcontrol-debug-1.0.0.apk`

* **Flash APK to camera over USB**:
  * **Do NOT use `adb`** — Sony Alpha cameras do not expose standard Android `adb` debugging.
  * Use `Sony-PMCA-RE` via the repo's `.venv` Python:
    ```bash
    .venv/bin/python tools/Sony-PMCA-RE/pmca-console.py install -f camera-app/app/build/outputs/apk/sony-pmca-webcontrol-debug-1.0.0.apk
    ```
  * **Linux desktop GVFS note**: If `pmca-console.py` fails with `USBError: [Errno 16] Resource busy`, unmount the auto-mounted MTP volume first:
    ```bash
    gio mount -l | grep -i gphoto2
    gio mount -u gphoto2://Sony_ILCE-6000_.../
    ```

* **Build & type-check web client (`web-client/`)**:
  ```bash
  cd web-client && npx tsc --noEmit --noUnusedLocals --noUnusedParameters && npm run build
  ```
  * Output: single self-contained `web-client/dist/index.html`.

## Key Documentation
* [DESIGN.md](./DESIGN.md) — Overall architecture, threading/lock separation, closed-loop control, and LiveView slot design
* [API.md](./API.md) — HTTP/RPC endpoints, `highlevel` targets, `/api/events`, and `/api/liveview*`
* [USER_GUIDE.md](./USER_GUIDE.md) — Camera compatibility, flashing, and user operation
* [DEVELOPER_GUIDE.md](./DEVELOPER_GUIDE.md) — Build setup, testing loop, and extension points
