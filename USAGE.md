# Sony a6000 AI Macro System — 使用与接入指南 (`USAGE.md`)

本文档面向**人类操作者**与 **AI Agent**，说明如何编译安装机内插件、连接相机，以及如何通过浏览器、HTTP API 或 Python SDK 控制 Sony a6000 完成矿石微距拍摄与自动景深堆栈。

---

## 第一部分：环境准备与安装（人类一次性操作）

### 1. 硬件前提
* **相机**：Sony a6000（ILCE-6000）。
* **镜头**：**必须使用带电子触点和自动对焦（AF）马达的 E 卡口镜头**（例如套头 + 带触点自动近摄接圈，或适马/腾龙/索尼 AF 微距头），否则焦距驱动指令无效。
* **网络预设**：先在 a6000 机身菜单中连接一次你家里的 Wi-Fi 路由器，让相机记住该 Wi-Fi 的 SSID 和密码。

### 2. 编译 APK
项目已配置 GitHub Actions（`.github/workflows/build.yml`），推送到 GitHub 后可在 Actions 页面直接下载编译好的 `a6000-ai-macro-debug-1.0.0.apk`。
如在本地编译（需安装 JDK 8 与 Android SDK `platforms;android-10`、`build-tools;25.0.2`）：
```bash
cd camera-app
./gradlew assembleDebug
# 输出位于 camera-app/app/build/outputs/apk/
```

### 3. 刷入 Sony a6000
1. 在 a6000 菜单：`设置` → `USB 连接` → 改为 **`MTP`**。
2. 用数据线将 a6000 连接到电脑，使用开源工具 `pmca-re-reloaded`（或 `Sony-PMCA-RE`）刷入 APK：
   ```bash
   # 示例：使用 pmca-console 安装编译好的 APK
   pmca-console install -f a6000-ai-macro-debug-1.0.0.apk
   ```
3. 拔掉数据线，进入相机菜单的「应用程序」即可看到 **`AI Macro Server`**。

### 4. 启动相机服务
1. 将相机模式拨盘转到 **`M`（手动曝光）** 档（或在应用启动后由软件接管）。
2. 在相机上打开 **`AI Macro Server`**。
3. 等待几秒，相机屏幕顶部会显示绿色字样：
   `READY (<你的WiFi名称>): http://192.168.x.x:8080`
   记下这个 IP 地址（例如 `192.168.1.105`）。

---

## 第二部分：浏览器交互操作（Mac / iPad）

在同一个局域网下的 Mac 或 iPad 浏览器中直接打开：`http://<相机IP>:8080`
* **实时画面**：页面上方每 ~180ms 自动刷新实时预览画面。
* **焦点微调**：点击 `◀◀◀` `◀◀` `◀ Near`（往近处调）或 `Far ▶` `▶▶` `▶▶▶`（往远处调）。
* **一键堆栈**：
  1. 用微调按钮将焦点对准矿石最前端晶尖，点击 **`Set Near`**；
  2. 将焦点调到矿石最后端，点击 **`Set Far`**；
  3. 填写 `Shots`（如 `20`），点击 **`Start Stack`**，相机将自动完成连拍。
* **曝光调整**：点击 `F -` / `F +`、`Shutter -` / `Shutter +`、`ISO 100` / `ISO 400`，或点击 **`Capture`** 拍单张测试。

---

## 第三部分：HTTP REST API 完整参考（供 AI Agent / 脚本调用）

所有接口均监听在 `http://<相机IP>:8080`，支持 CORS。

### 1. 查询当前状态
* **`GET /api/status`**
* **返回示例**：
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
* `state` 可能的值：`idle`（空闲）、`focusing`（正在跳转焦点）、`capturing`（正在拍照）、`bracketing`（正在执行景深包围）、`error`（硬件不支持）。

### 2. 获取实时预览画面
* **`GET /api/liveview.jpg`**
* **返回**：`Content-Type: image/jpeg` 实时图像字节流（若相机刚启动尚未产生第一帧，返回 `503`）。

### 3. 单步微调手动对焦
* **`POST /api/focus/drive?dir=<near|far>&speed=<1..4>`**
* **参数**：
  * `dir`：`near`（往近处移动焦点）或 `far`（往远处移动焦点）。
  * `speed`：`1`（最精细步进）到 `4`（大幅快速移动）。

### 4. 闭环跳转到指定焦点刻度
* **`POST /api/focus/goto?pos=<int>`**
* **参数**：`pos` 为目标整数刻度（范围 `0` 到 `maxFocus`）。
* **行为**：异步执行；调用后需轮询 `GET /api/status` 直到 `state == "idle"`。

### 5. 启动自动景深包围（Focus Bracketing）
* **`POST /api/bracket?min=<int>&max=<int>&shots=<int>`**
* **参数**：
  * `min`：起始焦点刻度。
  * `max`：结束焦点刻度。
  * `shots`：拍摄张数（>= 2）。
* **行为**：相机自动从 `min` 步进到 `max` 并连拍 `shots` 张照片存入 SD 卡。轮询 `GET /api/status` 可通过 `remainingShots` 查看剩余张数，直至 `state == "idle"`。

### 6. 中止景深包围
* **`POST /api/bracket/abort`**

### 7. 单张拍摄
* **`POST /api/capture`**

### 8. 调整曝光参数
* **`POST /api/exposure?aperture=<inc|dec>&shutter=<inc|dec>&iso=<int>`**
* 三个查询参数均为可选，可单独传或组合传：
  * `aperture=inc`（缩小光圈如 F5.6→F6.3）或 `dec`（开大光圈）。
  * `shutter=inc`（加快快门如 1/10→1/13）或 `dec`（减慢快门）。
  * `iso=100`（设置具体 ISO 数值）。

---

## 第四部分：AI Agent / Python SDK 使用规范

仓库 `client/a6000_client.py` 提供了零第三方依赖的 Python 封装类 `A6000MacroClient`：

```python
from client.a6000_client import A6000MacroClient

cam = A6000MacroClient("192.168.1.105")

# 1. 获取状态与实时画面
status = cam.get_status()
cam.save_liveview("preview.jpg")

# 2. 调整曝光（例如防止高反光矿石过曝）
cam.set_exposure(iso=100, shutter="inc")

# 3. 移动焦点到指定刻度并阻塞等待完成
cam.goto_focus(60, wait=True)

# 4. 执行全自动景深包围（例如从 50 到 140 拍 25 张）
cam.start_bracket(min_pos=50, max_pos=140, shots=25, wait=True)
```

### AI Agent 标准作业流程（SOP）
当用户要求 AI Agent 协助拍摄矿石微距时，Agent 应遵循以下闭环步骤：
1. **确认连接**：调用 `get_status()`，确认 `state == "idle"` 且 `maxFocus > 0`（若 `maxFocus == 0`，提示用户确认镜头已安装且为 AF 镜头）。
2. **曝光检查**：下载 `save_liveview()` 观察高光过曝与暗部细节；如有高光死白（如石英、黄铁矿反光），调用 `set_exposure(shutter="inc")` 压暗并再次取图验证。
3. **焦点边界探测**：通过 `drive_focus()` 或 `goto_focus()` 扫描样本，结合局部清晰度（或视觉模型判断），确定矿石最前端刚好清晰时的刻度 `min_pos` 和最后端刚好清晰时的刻度 `max_pos`。
4. **执行堆栈**：调用 `start_bracket(min_pos, max_pos, shots=N, wait=True)` 完成连拍。
5. **提示后处理**：完成后提示用户从 SD 卡导出该组 RAW/JPEG 照片，使用 Helicon Focus 或 Zerene Stacker 合成最终全景深图像。
