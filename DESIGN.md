# Sony a6000 AI Macro System — 系统设计文档 (`DESIGN.md`)

## 1. 背景与核心问题

Sony a6000（ILCE-6000）原厂固件在微距自动化与 AI 联机工作流中存在三个官方限制：
1. **USB 联机（PC Remote）无实时画面，也无焦点驱动接口**：`Imaging Edge Desktop` 连接 a6000 仅支持改曝光参数、按快门和传图，不传输 Live View 视频流，也不提供手动对焦（MF）步进按钮。
2. **Wi-Fi 遥控（Smart Remote Control）无远程 MF 接口**：官方 Wi-Fi JSON-RPC 接口只开放了 `Touch AF`（点击自动对焦），没有开放驱动镜头马达微调步进的接口；且必须让控制端断开外网去连相机热点。
3. **无原生机内景深包围（Focus Bracketing）**：拍 1–5cm 矿石微距时景深极浅，原厂固件无法自动从前到后步进拍多张照片用于景深合成（Focus Stacking）。

## 2. 突破原理：机内 Android 子系统与 `CameraEx`

Sony a6000 机身内部运行着一个基于 **Android 2.3.3（API Level 10）** 的子系统，用于承载 PlayMemories 机内应用。通过开源社区的 `OpenMemories-Framework` 和 `PMCA-RE` 工具链，我们可以将自定义 `.apk` 直接安装到相机内部，从而绕过官方外部协议的阉割，直接调用索尼未公开的底层硬件类 `com.sony.scalar.hardware.CameraEx`：

* **电动对焦步进控制**：`CameraEx.startOneShotFocusDrive(direction, speed)`（支持 `NEAR` / `FAR` 两个方向与 `1..4` 四档速度）。
* **绝对焦点刻度读取**：`CameraEx.FocusDriveListener` 回调返回 `FocusPosition(currentPosition, maxPosition)`，可实现闭环定位到任意整数刻度。
* **曝光与快门控制**：`CameraEx.incrementAperture()` / `decrementAperture()`、`incrementShutterSpeed()` / `decrementShutterSpeed()`、`ParametersModifier.setISOSensitivity()`、`burstableTakePicture()`。
* **实时画面采集**：通过标准 Android `Camera.PreviewCallback` 捕获 YUV（NV21）预览帧，在有 HTTP 请求时通过 `android.graphics.YuvImage` 按需压缩为 JPEG 字节流。
* **局域网接入**：调用 `WifiManager` 连接相机中已保存的家庭路由器 Wi-Fi，使相机成为局域网内的独立节点，并在机内嵌入 `NanoHTTPD`（端口 `8080`）对外暴露 RESTful HTTP 接口与内置 Web 控制面板。

## 3. 系统架构

```text
+-------------------------------------------------------------------+
|                   Sony a6000 (Android 2.3 / API 10)               |
|                                                                   |
|  +-----------------------+      +------------------------------+  |
|  |  MacroServerActivity  |<---->|  com.sony.scalar.CameraEx    |  |
|  |  - 状态机 (State)      |      |  - FocusDriveListener        |  |
|  |  - 对焦队列 (Queue)    |      |  - startOneShotFocusDrive()  |  |
|  |  - YUV 帧缓冲 (Lock)   |      |  - burstableTakePicture()    |  |
|  +-----------+-----------+      |  - PreviewCallback (YUV)     |  |
|              ^                  +------------------------------+  |
|              | UI Handler / 线程安全调用                           |
|              v                                                    |
|  +-----------------------+                                        |
|  |    MacroHttpServer    |  (NanoHTTPD on :8080, CORS enabled)    |
|  |  - GET  /             | -> 内置 HTML5 Web UI (Mac/iPad Safari) |
|  |  - GET  /api/status   | -> JSON 状态与焦点刻度                  |
|  |  - GET  /api/liveview.jpg -> 实时预览帧 (YuvImage -> JPEG)     |
|  |  - POST /api/focus/*  | -> 步进微调 / 闭环跳转到指定刻度         |
|  |  - POST /api/bracket  | -> 机内全自动景深包围连拍               |
|  |  - POST /api/exposure | -> 光圈 / 快门 / ISO 调整               |
|  |  - POST /api/capture  | -> 单张拍摄                            |
|  +-----------+-----------+                                        |
+--------------|----------------------------------------------------+
               |  家庭 Wi-Fi 局域网 (HTTP / JSON / JPEG)
               v
+-------------------------------------------------------------------+
|            控制端：Mac / iPad 浏览器  或  AI Agent (Python)         |
|  - client/a6000_client.py      (零第三方依赖 HTTP SDK)             |
|  - client/example_ai_macro.py  (高光过曝检测 + 清晰度扫边 + 自动堆栈)|
+-------------------------------------------------------------------+
```

## 4. 核心模块设计

### 4.1 线程模型与按需压缩
* `NanoHTTPD` 在后台工作线程处理 HTTP 请求，而 `CameraEx` 与 UI 控件必须在 Android 主线程（UI Thread）操作。`MacroServerActivity` 通过 `Handler.post()` 将所有硬件指令派发回主线程执行。
* 相机以较高帧率触发 `onPreviewFrame(byte[] data, Camera camera)`。为避免 CPU 过热和无效开销，回调中仅在互斥锁 `m_frameLock` 下执行一次极轻量的内存拷贝保存最新 YUV 原始字节；只有当外部真正请求 `GET /api/liveview.jpg` 时，才执行 `YuvImage.compressToJpeg(..., 75, baos)` 编码输出。

### 4.2 焦点闭环定位与卡顿兜底
* `FocusBracket` 实测经验表明，调用 `startOneShotFocusDrive()` 后偶尔不会触发 `FocusDriveListener`。因此在每次驱动后挂载一个 `60ms` 延迟检查任务 `m_checkFocusRunnable`；若焦点未变化则重试，连续 8 次无变化则判定为到达镜头物理极限或卡顿，安全回退到 `idle`（或继续拍摄），防止死锁。

### 4.3 景深包围（Focus Bracketing）状态机
* 状态枚举：`idle` → `focusing` / `capturing` / `bracketing` / `error`。
* 接收到 `POST /api/bracket?min=A&max=B&shots=N` 后，在 `[min, max]` 之间线性插值生成去重后的目标焦点队列 `m_focusQueue`。
* 循环流程：`driveTowardTarget()` 驱动到队首刻度 → 触发 `burstableTakePicture()` → `onShutter()` 回调确认拍摄成功 → `cancelTakePicture()` 释放快门锁并弹出队首 → 驱动到下一个焦点，直至队列清空。

## 5. 物理与硬件前提
1. **镜头要求**：远程调焦和自动景深包围依赖相机通过卡口触点向镜头内部马达发送指令，因此**必须使用带有电子触点与自动对焦（AF）马达的镜头**（或“索尼 AF 镜头 + 带电子触点近摄接圈”）。纯手动机械镜头（如老蛙 65mm 2X）无法响应马达指令，但仍可使用本系统的实时画面、曝光调整和远程快门功能。
2. **机身状态**：运行时通过 `setAutoPowerOffMode(false)` 禁用自动关机，建议搭配 NP-FW50 USB 假电池供电。
3. **文件存储**：RAW（ARW）单张约 24MB，受限于 a6000 的 2.4GHz Wi-Fi 带宽，堆栈连拍默认高速写入机身 SD 卡，拍完后在电脑端进行合成（如 Helicon Focus / Zerene Stacker）。
