# Sony a6000 AI Macro System

将 Sony a6000（ILCE-6000）改造成支持 **Wi-Fi 局域网实时取景、浏览器远程微调 MF、机内自动景深包围（Focus Bracketing）与 AI HTTP REST API 控制** 的微距摄影工作站。

## 核心功能

* **局域网直连，无需切换热点**：相机直接连入家庭/工作室 Wi-Fi，在屏幕上显示局域网 IP（端口 `8080`）。
* **内置 Web 控制面板**：Mac / iPad 浏览器打开 `http://<相机IP>:8080` 即可看实时画面、微调对焦（`<<<` `<<` `<` `>` `>>` `>>>`）、调整光圈/快门/ISO、一键执行景深包围。
* **AI 友好 HTTP REST API & Python SDK**：提供 `/api/status`、`/api/liveview.jpg`、`/api/focus/drive`、`/api/focus/goto`、`/api/bracket`、`/api/exposure`、`/api/capture` 等标准接口，及零依赖 Python 客户端。
* **GitHub Actions 自动编译**：推送后自动在云端编译生成可刷入相机的 `.apk`。

## 文档导航

* **系统架构与设计原理**：[DESIGN.md](./DESIGN.md)
* **编译、刷机、API 手册与 AI Agent 使用指南**：[USAGE.md](./USAGE.md)

## 目录结构

* `camera-app/` — 运行在 Sony a6000 机内 Android 2.3 子系统的 APK 源码（基于 `OpenMemories-Framework` 与 `NanoHTTPD`）
* `client/` — Python 控制端 SDK (`a6000_client.py`) 与自动曝光/扫焦堆栈示例 (`example_ai_macro.py`)
* `.github/workflows/build.yml` — GitHub Actions 自动编译 APK 工作流

## 硬件前提

* 远程调焦与自动景深包围需搭配**带电子触点与自动对焦（AF）马达的 E 卡口镜头**（或“索尼 AF 镜头 + 带电子触点近摄接圈”）。纯手动机械镜头仍可使用实时取景、曝光调节和远程快门功能。
