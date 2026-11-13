import { useState } from 'react';
import {
  type CameraClient,
  type CameraState,
  formatAperture,
  formatShutter,
} from '../api/cameraClient';

interface ExposureControlProps {
  client: CameraClient;
  state: CameraState | null;
  connected: boolean;
  onRefresh: () => void;
  onError: (msg: string) => void;
}

const ISO_PRESETS = [0, 100, 200, 400, 800, 1600, 3200, 6400];

export function ExposureControl({
  client,
  state,
  connected,
  onRefresh,
  onError,
}: ExposureControlProps) {
  const [busy, setBusy] = useState(false);

  const runAction = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await fn();
      onRefresh();
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  const aperture = formatAperture(state?.aperture?.currentAperture);
  const shutter = formatShutter(
    state?.shutter?.currentShutterSpeed_n,
    state?.shutter?.currentShutterSpeed_d,
  );
  const isoValue = state?.autoIso;

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>Exposure &amp; Capture</span>
          {state?.lens?.LensName && (
            <span className="muted-tag">{state.lens.LensName}</span>
          )}
        </div>
        {typeof state?.focalLength === 'number' && state.focalLength > 0 && (
          <span className="badge">{Math.round(state.focalLength / 10)}mm</span>
        )}
      </div>

      <div className="exposure-grid">
        <div className="metric-card">
          <div className="metric-label">Aperture</div>
          <div className="metric-value">{aperture}</div>
          <div className="stepper-row">
            <button
              type="button"
              className="btn"
              disabled={!connected || busy}
              onClick={() => runAction(() => client.decrementAperture())}
              title="Wider aperture (lower F-number)"
            >
              F-
            </button>
            <button
              type="button"
              className="btn"
              disabled={!connected || busy}
              onClick={() => runAction(() => client.incrementAperture())}
              title="Narrower aperture (higher F-number)"
            >
              F+
            </button>
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Shutter</div>
          <div className="metric-value">{shutter}</div>
          <div className="stepper-row">
            <button
              type="button"
              className="btn"
              disabled={!connected || busy}
              onClick={() => runAction(() => client.decrementShutter())}
              title="Slower shutter"
            >
              Slower
            </button>
            <button
              type="button"
              className="btn"
              disabled={!connected || busy}
              onClick={() => runAction(() => client.incrementShutter())}
              title="Faster shutter"
            >
              Faster
            </button>
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">ISO</div>
          <div className="metric-value">
            {isoValue === undefined ? '--' : isoValue === 0 ? 'AUTO' : isoValue}
          </div>
          <select
            className="select-input"
            disabled={!connected || busy}
            value={isoValue ?? ''}
            onChange={(e) => {
              const val = Number(e.target.value);
              if (!Number.isNaN(val)) {
                void runAction(() => client.setIso(val));
              }
            }}
          >
            <option value="" disabled>
              Select ISO
            </option>
            {ISO_PRESETS.map((iso) => (
              <option key={iso} value={iso}>
                {iso === 0 ? 'AUTO' : `ISO ${iso}`}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="capture-row">
        <button
          type="button"
          className="btn btn-primary btn-capture"
          disabled={!connected || busy || state?.capturing}
          onClick={() => runAction(() => client.takePicture())}
        >
          {state?.capturing ? 'Capturing...' : 'Take Picture'}
        </button>
      </div>
    </section>
  );
}
