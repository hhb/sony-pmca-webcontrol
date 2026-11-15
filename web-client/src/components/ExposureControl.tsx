import { useEffect, useRef, useState } from 'react';
import {
  type CameraClient,
  type CameraState,
  formatAperture,
  formatEv,
  formatShutter,
} from '../api/cameraClient';

interface ExposureControlProps {
  client: CameraClient;
  state: CameraState | null;
  connected: boolean;
  onError: (msg: string) => void;
}

const ISO_PRESETS = [0, 100, 200, 400, 800, 1600, 3200, 6400];

export function ExposureControl({
  client,
  state,
  connected,
  onError,
}: ExposureControlProps) {
  const [busy, setBusy] = useState(false);
  const [localEvIndex, setLocalEvIndex] = useState<number | null>(null);
  const [localIso, setLocalIso] = useState<number | null>(null);
  const [isoOptions, setIsoOptions] = useState<number[]>(ISO_PRESETS);
  const [sceneMode, setSceneMode] = useState<string | null>(null);
  const lastManualIsoRef = useRef<number>(100);

  const ec = typeof state?.exposureCompensation === 'object' ? state.exposureCompensation : undefined;
  const stateEvIndex =
    typeof state?.exposureCompensation === 'number'
      ? state.exposureCompensation
      : typeof ec?.index === 'number'
        ? ec.index
        : 0;
  const evIndex = localEvIndex !== null ? localEvIndex : stateEvIndex;
  const evMin = typeof ec?.min === 'number' ? ec.min : -15;
  const evMax = typeof ec?.max === 'number' ? ec.max : 15;
  const evStep = typeof ec?.step === 'number' && ec.step > 0 ? ec.step : 1 / 3;

  // Sync actual hardware EV compensation and supported ISOs on mount/reconnect
  useEffect(() => {
    if (!connected) {
      setLocalEvIndex(null);
      setLocalIso(null);
      setSceneMode(null);
      return;
    }
    let cancelled = false;
    client
      .getSceneMode()
      .then((mode) => {
        if (!cancelled && typeof mode === 'string') {
          setSceneMode(mode);
        }
      })
      .catch(() => {});
    client
      .getExposureCompensation()
      .then((val) => {
        if (!cancelled && typeof val === 'number') {
          setLocalEvIndex(val);
        }
      })
      .catch(() => {});

    client
      .getSupportedISOSensitivities()
      .then((list) => {
        if (!cancelled && Array.isArray(list) && list.length > 0) {
          setIsoOptions(list);
        }
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, [client, connected]);

  useEffect(() => {
    if (state?.autoIso !== undefined && state.autoIso === localIso) {
      setLocalIso(null);
    }
  }, [state?.autoIso, localIso]);

  const runAction = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await fn();
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  const changeEv = async (delta: number) => {
    const target = Math.max(evMin, Math.min(evMax, evIndex + delta));
    if (target === evIndex) return;
    const prev = evIndex;
    setLocalEvIndex(target);
    try {
      await client.setExposureCompensation(target);
    } catch (err) {
      setLocalEvIndex(prev);
      throw err;
    }
  };

  const resetEv = async () => {
    if (evIndex === 0) return;
    const prev = evIndex;
    setLocalEvIndex(0);
    try {
      await client.setExposureCompensation(0);
    } catch (err) {
      setLocalEvIndex(prev);
      throw err;
    }
  };

  const autoAperture =
    sceneMode === 'shutter-speed' || sceneMode === 'program-auto' || sceneMode === 'auto';
  const autoShutter =
    sceneMode === 'aperture-priority' || sceneMode === 'program-auto' || sceneMode === 'auto';

  const modeBadge =
    sceneMode === 'manual-exposure'
      ? 'M'
      : sceneMode === 'aperture-priority'
        ? 'A'
        : sceneMode === 'shutter-speed'
          ? 'S'
          : sceneMode === 'program-auto'
            ? 'P'
            : sceneMode;

  const setExposureAutoFlags = async (nextAutoAperture: boolean, nextAutoShutter: boolean) => {
    const targetMode = nextAutoAperture
      ? nextAutoShutter
        ? 'program-auto'
        : 'shutter-speed'
      : nextAutoShutter
        ? 'aperture-priority'
        : 'manual-exposure';
    const prev = sceneMode;
    setSceneMode(targetMode);
    try {
      await client.setSceneMode(targetMode);
      const actual = await client.getSceneMode();
      setSceneMode(actual);
    } catch (err) {
      setSceneMode(prev);
      throw err;
    }
  };

  const aperture = formatAperture(state?.aperture?.currentAperture);
  const shutter = formatShutter(
    state?.shutter?.currentShutterSpeed_n,
    state?.shutter?.currentShutterSpeed_d,
  );
  const isoValue = localIso !== null ? localIso : state?.autoIso;
  const autoIso = isoValue === 0;
  if (typeof isoValue === 'number' && isoValue > 0) {
    lastManualIsoRef.current = isoValue;
  }

  const changeIso = async (val: number) => {
    setLocalIso(val);
    try {
      await client.setIso(val);
    } catch (err) {
      setLocalIso(null);
      throw err;
    }
  };
  const evDisplay = connected ? formatEv(evIndex, evStep) : '--';

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>Exposure</span>
          {state?.lens?.LensName && (
            <span className="muted-tag">{state.lens.LensName}</span>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {modeBadge && <span className="badge">{modeBadge}</span>}
          {typeof state?.focalLength === 'number' && state.focalLength > 0 && (
            <span className="badge">{Math.round(state.focalLength / 10)}mm</span>
          )}
        </div>
      </div>

      <div className="exposure-grid">
        <div className="metric-card">
          <div className="metric-header-row">
            <span className="metric-label">Aperture</span>
            <label className="metric-auto-toggle">
              <input
                type="checkbox"
                disabled={!connected || busy}
                checked={autoAperture}
                onChange={(e) =>
                  void runAction(() => setExposureAutoFlags(e.target.checked, autoShutter))
                }
              />
              <span>Auto</span>
            </label>
          </div>
          <div className="metric-value">{aperture}</div>
          <div className="stepper-row">
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || autoAperture}
              onClick={() => runAction(() => client.decrementAperture())}
              title="Wider aperture (lower F-number)"
            >
              F-
            </button>
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || autoAperture}
              onClick={() => runAction(() => client.incrementAperture())}
              title="Narrower aperture (higher F-number)"
            >
              F+
            </button>
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-header-row">
            <span className="metric-label">Shutter</span>
            <label className="metric-auto-toggle">
              <input
                type="checkbox"
                disabled={!connected || busy}
                checked={autoShutter}
                onChange={(e) =>
                  void runAction(() => setExposureAutoFlags(autoAperture, e.target.checked))
                }
              />
              <span>Auto</span>
            </label>
          </div>
          <div className="metric-value">{shutter}</div>
          <div className="stepper-row">
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || autoShutter}
              onClick={() => runAction(() => client.decrementShutter())}
              title="Slower shutter"
            >
              S-
            </button>
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || autoShutter}
              onClick={() => runAction(() => client.incrementShutter())}
              title="Faster shutter"
            >
              S+
            </button>
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-header-row">
            <span className="metric-label">ISO</span>
            <label className="metric-auto-toggle">
              <input
                type="checkbox"
                disabled={!connected || busy}
                checked={autoIso}
                onChange={(e) => {
                  const target = e.target.checked ? 0 : lastManualIsoRef.current;
                  void runAction(() => changeIso(target));
                }}
              />
              <span>Auto</span>
            </label>
          </div>
          <div className="metric-value">
            {isoValue === undefined ? '--' : isoValue === 0 ? 'AUTO' : isoValue}
          </div>
          <select
            className="select-input"
            style={{ width: '100%' }}
            disabled={!connected || busy || autoIso}
            value={isoValue ?? ''}
            onChange={(e) => {
              const val = Number(e.target.value);
              if (!Number.isNaN(val)) {
                void runAction(() => changeIso(val));
              }
            }}
          >
            <option value="" disabled>
              Select ISO
            </option>
            {isoOptions.map((iso) => (
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
    </section>
  );
}
