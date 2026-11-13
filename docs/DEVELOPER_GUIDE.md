# Sony PMCA WebControl — Developer Guide (`DEVELOPER_GUIDE.md`)

This guide covers repository layout, local build tooling, flashing workflows, CI/CD, and how to extend the camera app or web client.

---

## 1. Repository Structure

```text
sony-pmca-webcontrol/
├── camera-app/                 # Android 2.3 (API 10) in-camera APK (OpenMemories + NanoHTTPD)
│   └── app/src/main/
│       ├── assets/index.html   # Lightweight built-in fallback web UI served at http://<ip>:8080/
│       └── java/com/sony/pmca/webcontrol/
├── web-client/                 # React + TypeScript + Vite frontend & CameraClient SDK
│   └── src/api/cameraClient.ts
├── tools/Sony-PMCA-RE/         # USB MTP flashing toolchain (pmca-console.py)
└── .github/workflows/build.yml # CI build for APK + single-file web-client HTML
```

---

## 2. Building the Camera APK (`camera-app/`)

### Prerequisites
* **JDK 8** (Android Gradle plugin `2.1.x` fails on JDK 9+)
* **Android SDK** with Platform 10 (`android-2.3.3`) and Build Tools `25.0.2`

### Build Command
```bash
cd camera-app
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 ./gradlew assembleDebug
```
Output APK: `camera-app/app/build/outputs/apk/sony-pmca-webcontrol-debug-1.0.0.apk`

---

## 3. Developing & Building the Web Client (`web-client/`)

### Prerequisites
* **Node.js 22+** (`>=22.12.0`)

### Commands
```bash
cd web-client
npm install
npm run dev       # Local HTTPS dev server (HTTPS is required by browsers for showDirectoryPicker)
npm run build     # Type-check (tsc -b) + Vite single-file build -> web-client/dist/index.html
```

To check for unused locals/parameters strictly:
```bash
npx tsc --noEmit --noUnusedLocals --noUnusedParameters
```

---

## 4. Flashing & On-Camera Testing Loop

Sony Alpha cameras do **not** expose `adb`. Always flash via USB MTP using the project's `.venv` Python environment (which includes `pycryptodomex` and `pyusb`):

```bash
# On Linux desktop, unmount GVFS MTP first if libusb reports Resource busy:
gio mount -l | grep -i gphoto2
gio mount -u gphoto2://Sony_ILCE-6000_.../

# Flash the APK:
.venv/bin/python tools/Sony-PMCA-RE/pmca-console.py install -f camera-app/app/build/outputs/apk/sony-pmca-webcontrol-debug-1.0.0.apk
```

---

## 5. Extending the System

1. **Adding New Client Features Without Reflashing**:
   * Call `GET /api/describe` on a live camera (or inspect `stubs--SNAPSHOT.jar`) to check whether the desired Sony Scalar method is already exposed on `camera`, `modifier`, `params`, or `static:*`.
   * Implement higher-level workflows (e.g., intervalometer, HDR exposure bracketing, custom focus solvers) purely in `web-client/` using `POST /api/call` + `GET /api/events`.
2. **Adding New Static/System Targets on Camera**:
   * Register additional whitelisted classes in `RpcDispatcher.java` (for example, `com.sony.scalar.sysutil.didep.Media`, `com.sony.scalar.provider.AvindexStore`, `com.sony.scalar.sysutil.ScalarInput`) following the `static:Temperature` / `static:ScalarProperties` pattern.
3. **Adding New Event Listeners**:
   * Register additional `CameraEx` listeners (such as `IntervalRecListener`, `TrackingFocusListener`, `FaceDetectionListener`, `MeteringModeInfoListener`) in `MacroServerActivity.registerCameraListeners()` and forward events via `m_eventHub.emit(type, json)`.

---

## 6. CI & Releases

[`.github/workflows/build.yml`](../.github/workflows/build.yml) builds both `sony-pmca-webcontrol-debug-1.0.0.apk` and the self-contained `web-client/dist/index.html` on every push/PR, and publishes them to **GitHub Releases** when pushing a `v*` tag.
