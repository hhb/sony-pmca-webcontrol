import { useEffect, useRef, useState } from 'react';
import type { CameraClient } from '../api/cameraClient';

interface LiveViewPanelProps {
  client: CameraClient;
  connected: boolean;
  onCapture: () => void;
  capturing: boolean;
}

function getLiveViewClientId(): string {
  const fallback = `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  try {
    const existing = window.sessionStorage.getItem('pmca_lv_cid');
    if (existing) return existing;
    window.sessionStorage.setItem('pmca_lv_cid', fallback);
    return fallback;
  } catch {
    return fallback;
  }
}

export function LiveViewPanel({
  client,
  connected,
  onCapture,
  capturing,
}: LiveViewPanelProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const histCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const loupeCanvasRef = useRef<HTMLCanvasElement | null>(null);

  const [streaming, setStreaming] = useState(true);
  const [pageVisible, setPageVisible] = useState<boolean>(() =>
    typeof document === 'undefined' ? true : !document.hidden,
  );
  const [showGrid, setShowGrid] = useState(true);
  const [peaking, setPeaking] = useState(false);
  const [showHistogram, setShowHistogram] = useState(false);
  const [showLoupe, setShowLoupe] = useState(false);
  const [loupeZoom, setLoupeZoom] = useState<2 | 3 | 4>(3);
  const [focusPoint, setFocusPoint] = useState<{ x: number; y: number } | null>(null);

  const [fps, setFps] = useState<number>(0);
  const [frameError, setFrameError] = useState<string | null>(null);

  const focusPointRef = useRef<{ x: number; y: number } | null>(null);
  focusPointRef.current = focusPoint;
  const liveViewCidRef = useRef<string>(getLiveViewClientId());
  const showLoupeRef = useRef<boolean>(false);
  showLoupeRef.current = showLoupe;
  const loupeZoomRef = useRef<number>(3);
  loupeZoomRef.current = loupeZoom;
  // Keep overlay flags in refs so toggling them doesn't reconnect the MJPEG stream
  const showGridRef = useRef(showGrid);
  showGridRef.current = showGrid;
  const peakingRef = useRef(peaking);
  peakingRef.current = peaking;
  const showHistogramRef = useRef(showHistogram);
  showHistogramRef.current = showHistogram;

  useEffect(() => {
    const onVisibilityChange = () => {
      setPageVisible(!document.hidden);
    };
    const onPageHide = () => {
      client.closeLiveView(liveViewCidRef.current);
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [client]);

  useEffect(() => {
    if (!connected || !streaming || !pageVisible) {
      setFps(0);
      return;
    }

    let cancelled = false;
    let retryTimer: number | undefined;
    let activeController: AbortController | null = null;
    let activeReader: ReadableStreamDefaultReader<Uint8Array> | null = null;
    let frameCount = 0;
    let lastFpsTime = performance.now();

    let pendingBlob: Blob | null = null;
    let isRendering = false;

    const renderLoop = async () => {
      if (isRendering || cancelled) return;
      isRendering = true;
      try {
        while (!cancelled && pendingBlob) {
          const blob = pendingBlob;
          pendingBlob = null;

          const bitmap = await createImageBitmap(blob);
          if (cancelled) {
            bitmap.close();
            break;
          }

          const canvas = canvasRef.current;
          if (canvas) {
            if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
              canvas.width = bitmap.width || 640;
              canvas.height = bitmap.height || 480;
            }
            const needPixels = peakingRef.current || showHistogramRef.current;
            const ctx = canvas.getContext('2d', { willReadFrequently: needPixels });
            if (ctx) {
              ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);

              if (needPixels) {
                const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                const data = imgData.data;

                if (showHistogramRef.current && histCanvasRef.current) {
                  drawHistogram(histCanvasRef.current, data);
                }

                if (peakingRef.current) {
                  applyFocusPeaking(data, canvas.width, canvas.height);
                  ctx.putImageData(imgData, 0, 0);
                }
              }

              // Update magnified loupe canvas before drawing overlays on main canvas
              if (showLoupeRef.current && focusPointRef.current && loupeCanvasRef.current) {
                const lCanvas = loupeCanvasRef.current;
                const lCtx = lCanvas.getContext('2d');
                if (lCtx) {
                  const z = loupeZoomRef.current;
                  const srcW = canvas.width / z;
                  const srcH = canvas.height / z;
                  const srcX = Math.max(0, Math.min(canvas.width - srcW, focusPointRef.current.x - srcW / 2));
                  const srcY = Math.max(0, Math.min(canvas.height - srcH, focusPointRef.current.y - srcH / 2));

                  lCtx.clearRect(0, 0, lCanvas.width, lCanvas.height);
                  lCtx.imageSmoothingEnabled = true;
                  lCtx.imageSmoothingQuality = 'high';
                  lCtx.drawImage(canvas, srcX, srcY, srcW, srcH, 0, 0, lCanvas.width, lCanvas.height);

                  // Draw delicate crosshair in center of loupe
                  const cx = lCanvas.width / 2;
                  const cy = lCanvas.height / 2;
                  lCtx.strokeStyle = 'rgba(126, 231, 135, 0.7)';
                  lCtx.lineWidth = 1;
                  lCtx.beginPath();
                  lCtx.moveTo(cx - 10, cy);
                  lCtx.lineTo(cx + 10, cy);
                  lCtx.moveTo(cx, cy - 10);
                  lCtx.lineTo(cx, cy + 10);
                  lCtx.stroke();
                }
              }

              // Draw target focus box on main canvas
              if (showLoupeRef.current && focusPointRef.current) {
                const z = loupeZoomRef.current;
                drawFocusBox(
                  ctx,
                  focusPointRef.current.x,
                  focusPointRef.current.y,
                  canvas.width / z,
                  canvas.height / z,
                  `${z}x`,
                );
              }

              if (showGridRef.current) {
                drawGrid(ctx, canvas.width, canvas.height);
              }
            }
          }
          bitmap.close();

          frameCount += 1;
          const now = performance.now();
          if (now - lastFpsTime >= 1000) {
            setFps(Math.round((frameCount * 1000) / (now - lastFpsTime)));
            frameCount = 0;
            lastFpsTime = now;
          }
        }
      } catch {
        // Ignore transient decode errors on partial/aborted frames
      } finally {
        isRendering = false;
      }
    };

    const startStream = async () => {
      if (cancelled) return;
      activeController = new AbortController();
      try {
        const res = await fetch(client.liveViewMjpegUrl(liveViewCidRef.current), {
          signal: activeController.signal,
          cache: 'no-store',
        });

        if (res.status === 503) {
          setFrameError('LiveView busy (too many clients)');
          setFps(0);
          setStreaming(false);
          return;
        }
        if (!res.ok || !res.body) {
          throw new Error(`HTTP ${res.status}`);
        }

        setFrameError(null);
        const reader = res.body.getReader();
        activeReader = reader;
        let buffer = new Uint8Array(0);
        const decoder = new TextDecoder('ascii');

        while (!cancelled) {
          const { value, done } = await reader.read();
          if (done || cancelled) break;
          if (!value || value.length === 0) continue;

          const merged = new Uint8Array(buffer.length + value.length);
          merged.set(buffer);
          merged.set(value, buffer.length);
          buffer = merged;

          // Parse complete multipart/x-mixed-replace frames by Content-Length
          while (true) {
            const headerEnd = findHeaderEnd(buffer);
            if (headerEnd === -1) break;

            const headerText = decoder.decode(buffer.subarray(0, headerEnd));
            const match = /Content-Length:\s*(\d+)/i.exec(headerText);
            if (!match) {
              buffer = buffer.subarray(headerEnd + 4);
              continue;
            }
            const contentLen = Number(match[1]);
            const bodyStart = headerEnd + 4;
            if (buffer.length < bodyStart + contentLen) {
              break;
            }

            // Copy frame slice & overwrite any un-decoded previous frame (zero backlog)
            const jpegCopy = buffer.slice(bodyStart, bodyStart + contentLen);
            buffer = buffer.subarray(bodyStart + contentLen);
            pendingBlob = new Blob([jpegCopy], { type: 'image/jpeg' });
            void renderLoop();
          }
        }
      } catch (err) {
        if (cancelled || (err instanceof DOMException && err.name === 'AbortError')) {
          return;
        }
        setFrameError('LiveView stream unavailable');
        setFps(0);
        retryTimer = window.setTimeout(() => void startStream(), 1500);
      }
    };

    void startStream();

    return () => {
      cancelled = true;
      void activeReader?.cancel().catch(() => {});
      activeController?.abort();
      client.closeLiveView(liveViewCidRef.current);
      if (retryTimer !== undefined) window.clearTimeout(retryTimer);
    };
  }, [client, connected, streaming, pageVisible]);

  const handleCanvasClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const imgRatio = canvas.width / canvas.height;
    const boxRatio = rect.width / rect.height;
    let renderWidth = rect.width;
    let renderHeight = rect.height;
    let offsetX = 0;
    let offsetY = 0;

    if (boxRatio > imgRatio) {
      renderWidth = rect.height * imgRatio;
      offsetX = (rect.width - renderWidth) / 2;
    } else {
      renderHeight = rect.width / imgRatio;
      offsetY = (rect.height - renderHeight) / 2;
    }

    const relX = e.clientX - rect.left - offsetX;
    const relY = e.clientY - rect.top - offsetY;

    if (relX < 0 || relX > renderWidth || relY < 0 || relY > renderHeight) {
      return;
    }

    const normX = relX / renderWidth;
    const normY = relY / renderHeight;
    const clickX = Math.round(normX * canvas.width);
    const clickY = Math.round(normY * canvas.height);

    setFocusPoint({ x: clickX, y: clickY });
    setShowLoupe(true);
  };

  return (
    <section className="panel liveview-panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>LiveView</span>
          <span className="badge">{streaming && pageVisible && connected ? `${fps} FPS` : 'PAUSED'}</span>
        </div>
        <div className="toolbar-row">
          <button
            type="button"
            className={`btn btn-sm ${streaming ? 'btn-active' : ''}`}
            onClick={() => {
              setFrameError(null);
              setStreaming((s) => !s);
            }}
          >
            {streaming ? 'Pause' : 'Resume'}
          </button>
          <button
            type="button"
            className={`btn btn-sm ${showGrid ? 'btn-active' : ''}`}
            onClick={() => setShowGrid((g) => !g)}
          >
            Grid
          </button>
          <button
            type="button"
            className={`btn btn-sm ${peaking ? 'btn-active' : ''}`}
            onClick={() => setPeaking((p) => !p)}
          >
            Peaking
          </button>
          <button
            type="button"
            className={`btn btn-sm ${showHistogram ? 'btn-active' : ''}`}
            onClick={() => setShowHistogram((h) => !h)}
          >
            Hist
          </button>
          <button
            type="button"
            className={`btn btn-sm ${showLoupe ? 'btn-active' : ''}`}
            onClick={() => {
              if (!showLoupe && !focusPoint) {
                const canvas = canvasRef.current;
                const cx = canvas ? Math.round(canvas.width / 2) : 320;
                const cy = canvas ? Math.round(canvas.height / 2) : 240;
                setFocusPoint({ x: cx, y: cy });
              }
              setShowLoupe((v) => !v);
            }}
            title="Click image to zoom in on focus area"
          >
            Loupe
          </button>
        </div>
      </div>

      <div className="liveview-stage">
        <canvas
          ref={canvasRef}
          className="liveview-canvas"
          style={{ cursor: 'crosshair' }}
          onClick={handleCanvasClick}
          title="Click to focus loupe here"
        />
        {showHistogram && connected && streaming && !frameError && (
          <canvas
            ref={histCanvasRef}
            width={160}
            height={56}
            className="histogram-overlay"
          />
        )}
        {showLoupe && focusPoint && connected && streaming && !frameError && (
          <div
            className={`loupe-window ${focusPoint.x > (canvasRef.current?.width ?? 640) / 2 ? 'loupe-left' : 'loupe-right'}`}
          >
            <div className="loupe-header">
              <span className="loupe-title">🔍 {loupeZoom}x Focus Zoom</span>
              <div className="loupe-toolbar">
                <button
                  type="button"
                  className="btn btn-xs"
                  onClick={() => setLoupeZoom((z) => (z === 2 ? 3 : z === 3 ? 4 : 2))}
                  title="Switch zoom level (2x / 3x / 4x)"
                >
                  {loupeZoom}x
                </button>
                <button
                  type="button"
                  className="btn btn-xs"
                  onClick={() => setShowLoupe(false)}
                  title="Close Loupe"
                >
                  ✕
                </button>
              </div>
            </div>
            <canvas ref={loupeCanvasRef} width={180} height={180} className="loupe-canvas" />
            <div className="loupe-hint">Click image to move focus box</div>
          </div>
        )}
        {(!connected || frameError) && (
          <div className="liveview-placeholder">
            {!connected ? 'Not connected to camera' : frameError}
          </div>
        )}
        <button
          type="button"
          className="shutter-fab"
          disabled={!connected || capturing}
          onClick={onCapture}
          title="Take Picture"
        >
          <span className="shutter-inner" />
        </button>
      </div>
    </section>
  );
}

function findHeaderEnd(buf: Uint8Array): number {
  for (let i = 0; i <= buf.length - 4; i++) {
    if (
      buf[i] === 13 &&
      buf[i + 1] === 10 &&
      buf[i + 2] === 13 &&
      buf[i + 3] === 10
    ) {
      return i;
    }
  }
  return -1;
}

function drawGrid(ctx: CanvasRenderingContext2D, w: number, h: number) {
  ctx.save();
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.28)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(w / 3, 0);
  ctx.lineTo(w / 3, h);
  ctx.moveTo((2 * w) / 3, 0);
  ctx.lineTo((2 * w) / 3, h);
  ctx.moveTo(0, h / 3);
  ctx.lineTo(w, h / 3);
  ctx.moveTo(0, (2 * h) / 3);
  ctx.lineTo(w, (2 * h) / 3);
  ctx.stroke();
  ctx.restore();
}

function drawHistogram(canvas: HTMLCanvasElement, data: Uint8ClampedArray) {
  const bins = new Uint32Array(64);
  for (let i = 0; i < data.length; i += 16) {
    const luma = (data[i] * 77 + data[i + 1] * 150 + data[i + 2] * 29) >> 8;
    bins[luma >> 2] += 1;
  }
  let max = 1;
  for (let i = 0; i < 64; i++) {
    if (bins[i] > max) max = bins[i];
  }
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const { width, height } = canvas;
  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = 'rgba(13, 17, 23, 0.68)';
  ctx.fillRect(0, 0, width, height);
  ctx.fillStyle = 'rgba(220, 235, 255, 0.85)';
  const barW = width / 64;
  for (let i = 0; i < 64; i++) {
    const barH = (bins[i] / max) * (height - 6);
    ctx.fillRect(i * barW, height - barH, Math.max(1, barW - 0.5), barH);
  }
}

function applyFocusPeaking(data: Uint8ClampedArray, w: number, h: number) {
  const threshold = 70;
  const luma = new Uint8Array(w * h);
  for (let p = 0, i = 0; p < data.length; p += 4, i++) {
    luma[i] = (data[p] * 77 + data[p + 1] * 150 + data[p + 2] * 29) >> 8;
  }
  for (let y = 1; y < h - 1; y += 2) {
    const row = y * w;
    for (let x = 1; x < w - 1; x += 2) {
      const idx = row + x;
      const gx = Math.abs(luma[idx + 1] - luma[idx - 1]);
      const gy = Math.abs(luma[idx + w] - luma[idx - w]);
      if (gx + gy > threshold) {
        const p = idx * 4;
        data[p] = 255;
        data[p + 1] = 56;
        data[p + 2] = 56;
      }
    }
  }
}

function drawFocusBox(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  boxW: number,
  boxH: number,
  zoomText: string,
) {
  ctx.save();
  const left = cx - boxW / 2;
  const top = cy - boxH / 2;

  // Box border with outer glow
  ctx.shadowColor = 'rgba(0, 0, 0, 0.85)';
  ctx.shadowBlur = 4;
  ctx.strokeStyle = '#7ee787';
  ctx.lineWidth = 1.5;
  ctx.strokeRect(left, top, boxW, boxH);

  // Corner bracket accents
  const cornerLen = Math.max(6, Math.min(boxW, boxH) * 0.22);
  ctx.lineWidth = 2.5;
  ctx.beginPath();
  // Top-left
  ctx.moveTo(left, top + cornerLen);
  ctx.lineTo(left, top);
  ctx.lineTo(left + cornerLen, top);
  // Top-right
  ctx.moveTo(left + boxW - cornerLen, top);
  ctx.lineTo(left + boxW, top);
  ctx.lineTo(left + boxW, top + cornerLen);
  // Bottom-left
  ctx.moveTo(left, top + boxH - cornerLen);
  ctx.lineTo(left, top + boxH);
  ctx.lineTo(left + cornerLen, top + boxH);
  // Bottom-right
  ctx.moveTo(left + boxW - cornerLen, top + boxH);
  ctx.lineTo(left + boxW, top + boxH);
  ctx.lineTo(left + boxW, top + boxH - cornerLen);
  ctx.stroke();

  // Center crosshair
  const chLen = 5;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(cx - chLen, cy);
  ctx.lineTo(cx + chLen, cy);
  ctx.moveTo(cx, cy - chLen);
  ctx.lineTo(cx, cy + chLen);
  ctx.stroke();

  // Badge label above box
  ctx.fillStyle = 'rgba(13, 17, 23, 0.8)';
  ctx.fillRect(left, Math.max(0, top - 16), 34, 14);
  ctx.fillStyle = '#7ee787';
  ctx.font = 'bold 10px monospace';
  ctx.fillText(zoomText, left + 4, Math.max(11, top - 5));

  ctx.restore();
}
