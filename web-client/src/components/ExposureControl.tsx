import { useEffect, useState } from 'react';
import {
  type CameraClient,
  type CameraState,
  type ExposureCompensationInfo,
  formatAperture,
  formatEv,
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
  const [localEv, setLocalEv] = useState<ExposureCompensationInfo>({
    index: 0,
    min: -9,
    max: 9,
    step: 1 / 3,
  });

  // Keep in sync with state if state provides exposureCompensation
  const stateEvIndex =
    typeof state?.exposureCompensation === 'number'
      ? state.exposureCompensation
      : typeof state?.exposureCompensation?.index === 'number'
        ? state.exposureCompensation.index
        : undefined;

  const evIndex = stateEvIndex ?? localEv.index;
  const evMin =
    typeof state?.exposureCompensation === 'object' &&
    typeof state?.exposureCompensation?.min === 'number'
      ? state.exposureCompensation.min
      : localEv.min;
  const evMax =
    typeof state?.exposureCompensation === 'object' &&
    typeof state?.exposureCompensation?.max === 'number'
      ? state.exposureCompensation.max
      : localEv.max;
  const evStep =
    typeof state?.exposureCompensation === 'object' &&
    typeof state?.exposureCompensation?.step === 'number'
      ? state.exposureCompensation.step
      : localEv.step;

  useEffect(() => {
    if (!connected) return;
    let active = true;
    client
      .getExposureCompensationInfo()
      .then((info) => {
        if (active) setLocalEv(info);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [client, connected]);

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

  const changeEv = async (delta: number) => {
    const target = Math.max(evMin, Math.min(evMax, evIndex + delta));
    if (target === evIndex) return;
    await client.setExposureCompensation(target);
    setLocalEv((prev) => ({ ...prev, index: target }));
  };

  const resetEv = async () => {
    if (evIndex === 0) return;
    await client.setExposureCompensation(0);
    setLocalEv((prev) => ({ ...prev, index: 0 }));
  };

  const aperture = formatAperture(state?.aperture?.currentAperture);
  const shutter = formatShutter(
    state?.shutter?.currentShutterSpeed_n,
    state?.shutter?.currentShutterSpeed_d,
  );
  const isoValue = state?.autoIso;
  const evDisplay = connected ? formatEv(evIndex, evStep) : '--';

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

        <div className="metric-card">
          <div className="metric-header-row">
            <span className="metric-label">EV Comp</span>
            {evIndex !== 0 && connected && (
              <button
                type="button"
                className="metric-reset-btn"
                onClick={() => runAction(resetEv)}
                title="Reset EV to ±0.0"
                disabled={busy}
              >
                Reset
              </button>
            )}
          </div>
          <div className="metric-value">{evDisplay}</div>
          <div className="stepper-row">
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || evIndex <= evMin}
              onClick={() => runAction(() => changeEv(-1))}
              title="Decrease exposure compensation (-1/3 EV)"
            >
              -
            </button>
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || evIndex >= evMax}
              onClick={() => runAction(() => changeEv(1))}
              title="Increase exposure compensation (+1/3 EV)"
            >
              +
            </button>
          </div>
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
