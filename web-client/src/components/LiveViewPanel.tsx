import { useEffect, useRef, useState } from 'react';
import type { CameraClient } from '../api/cameraClient';

interface LiveViewPanelProps {
  client: CameraClient;
  connected: boolean;
  onCapture: () => void;
  capturing: boolean;
}

export function LiveViewPanel({
  client,
  connected,
  onCapture,
  capturing,
}: LiveViewPanelProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const histCanvasRef = useRef<HTMLCanvasElement | null>(null);

  const [streaming, setStreaming] = useState(true);
  const [showGrid, setShowGrid] = useState(true);
  const [peaking, setPeaking] = useState(false);
  const [showHistogram, setShowHistogram] = useState(true);
  const [fps, setFps] = useState<number>(0);
  const [frameError, setFrameError] = useState<string | null>(null);

  useEffect(() => {
    if (!connected || !streaming) {
      setFps(0);
      return;
    }

    let cancelled = false;
    let timerId: number | undefined;
    let frameCount = 0;
    let lastFpsTime = performance.now();

    const fetchNextFrame = () => {
      if (cancelled) return;
      const img = new Image();
      img.crossOrigin = 'anonymous';

      img.onload = () => {
        if (cancelled) return;
        setFrameError(null);

        const canvas = canvasRef.current;
        if (canvas) {
          if (canvas.width !== img.naturalWidth || canvas.height !== img.naturalHeight) {
            canvas.width = img.naturalWidth || 640;
            canvas.height = img.naturalHeight || 480;
          }
          const ctx = canvas.getContext('2d', { willReadFrequently: peaking || showHistogram });
          if (ctx) {
            ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

            if (peaking || showHistogram) {
              try {
                const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                const data = imgData.data;

                if (showHistogram && histCanvasRef.current) {
                  drawHistogram(histCanvasRef.current, data);
                }

                if (peaking) {
                  applyFocusPeaking(data, canvas.width, canvas.height);
                  ctx.putImageData(imgData, 0, 0);
                }
              } catch {
                // Canvas tainted if CORS missing; ignore overlays gracefully
              }
            }

            if (showGrid) {
              drawGrid(ctx, canvas.width, canvas.height);
            }
          }
        }

        frameCount += 1;
        const now = performance.now();
        if (now - lastFpsTime >= 1000) {
          setFps(Math.round((frameCount * 1000) / (now - lastFpsTime)));
          frameCount = 0;
          lastFpsTime = now;
        }

        timerId = window.setTimeout(fetchNextFrame, 30);
      };

      img.onerror = () => {
        if (cancelled) return;
        setFrameError('LiveView stream unavailable');
        setFps(0);
        timerId = window.setTimeout(fetchNextFrame, 1000);
      };

      img.src = client.liveViewUrl(Date.now());
    };

    fetchNextFrame();

    return () => {
      cancelled = true;
      if (timerId !== undefined) window.clearTimeout(timerId);
    };
  }, [client, connected, streaming, showGrid, peaking, showHistogram]);

  return (
    <section className="panel liveview-panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>LiveView</span>
          <span className="badge">{streaming && connected ? `${fps} FPS` : 'PAUSED'}</span>
        </div>
        <div className="toolbar-row">
          <button
            type="button"
            className={`btn btn-sm ${streaming ? 'btn-active' : ''}`}
            onClick={() => setStreaming((s) => !s)}
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
        </div>
      </div>

      <div className="liveview-stage">
        <canvas ref={canvasRef} className="liveview-canvas" />
        {showHistogram && connected && streaming && !frameError && (
          <canvas
            ref={histCanvasRef}
            width={160}
            height={56}
            className="histogram-overlay"
          />
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
  // Compute fast horizontal + vertical gradient on luminance
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
