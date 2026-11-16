import { useEffect, useRef, useState } from 'react';
import {
  type CameraClient,
  type CameraState,
  formatAperture,
  formatEv,
  formatShutter,
  ZOOM_TELE,
  ZOOM_WIDE,
} from '../api/cameraClient';

interface ExposureControlProps {
  client: CameraClient;
  state: CameraState | null;
  connected: boolean;
  onError: (msg: string) => void;
}

const ISO_PRESETS = [0, 100, 200, 400, 800, 1600, 3200, 6400];
const WB_PRESETS = [
  'auto',
  'daylight',
  'shade',
  'cloudy-daylight',
  'fluorescent-coolwhite',
  'fluorescent-daywhite',
  'fluorescent-daylight',
  'warm-fluorescent',
  'incandescent',
  'flash',
  'custom',
  'color-temp',
  'underwater-auto',
];
const WB_LABELS: Record<string, string> = {
  auto: 'Auto',
  daylight: 'Daylight',
  shade: 'Shade',
  'cloudy-daylight': 'Cloudy',
  'fluorescent-coolwhite': 'Fluor: Cool White',
  'fluorescent-daywhite': 'Fluor: Day White',
  'fluorescent-daylight': 'Fluor: Daylight',
  'warm-fluorescent': 'Fluor: Warm',
  incandescent: 'Incandescent',
  flash: 'Flash',
  custom: 'Custom',
  'color-temp': 'Color Temp (K)',
  'underwater-auto': 'Underwater Auto',
};
const KELVIN_OPTIONS = Array.from({ length: 75 }, (_, i) => 2500 + i * 100);

function formatWbLabel(mode: string): string {
  return WB_LABELS[mode] ?? mode;
}

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
  const [wbMode, setWbMode] = useState<string>('');
  const [wbOptions, setWbOptions] = useState<string[]>(WB_PRESETS);
  const [colorTemp, setColorTemp] = useState<number>(5500);
  const [wbLb, setWbLb] = useState<number>(0);
  const [wbCc, setWbCc] = useState<number>(0);

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

  // Sync actual hardware EV compensation, supported ISOs, and WB on mount/reconnect
  useEffect(() => {
    if (!connected) {
      setLocalEvIndex(null);
      setLocalIso(null);
      setSceneMode(null);
      setWbMode('');
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

    client
      .getSupportedWhiteBalance()
      .then((list) => {
        if (!cancelled && Array.isArray(list) && list.length > 0) {
          setWbOptions(list);
        }
      })
      .catch(() => {});

    client
      .getWhiteBalance()
      .then((mode) => {
        if (!cancelled && typeof mode === 'string') {
          setWbMode(mode);
        }
      })
      .catch(() => {});

    client
      .getColorTemperature()
      .then((k) => {
        if (!cancelled && typeof k === 'number' && k >= 2500) {
          setColorTemp(k);
        }
      })
      .catch(() => {});

    client
      .getWhiteBalanceLightBalance()
      .then((v) => {
        if (!cancelled && typeof v === 'number') {
          setWbLb(v);
        }
      })
      .catch(() => {});

    client
      .getWhiteBalanceColorComp()
      .then((v) => {
        if (!cancelled && typeof v === 'number') {
          setWbCc(v);
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
  const hasAutoExposure = autoAperture || autoShutter || isoValue === 0;
  const evDisabled = !connected || busy || !hasAutoExposure;

  const focalLabel =
    typeof state?.focalLength === 'number' && state.focalLength > 0
      ? `${Math.round(state.focalLength / 10)}mm`
      : null;
  const hasZoomCard = Boolean(state?.powerZoom || focalLabel);

  const zoomPressRef = useRef<{
    active: boolean;
    holding: boolean;
    timerId: number | null;
  }>({ active: false, holding: false, timerId: null });

  useEffect(() => {
    return () => {
      const press = zoomPressRef.current;
      if (press.timerId !== null) {
        window.clearTimeout(press.timerId);
        press.timerId = null;
      }
      if (press.holding) {
        press.holding = false;
        void client.stopZoom().catch(() => {});
      }
      press.active = false;
    };
  }, [client]);

  const stepZoom = async (dir: 0 | 1) => {
    setBusy(true);
    try {
      await client.zoomStep(dir, 1);
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  const handleZoomPointerDown = (dir: 0 | 1, e: React.PointerEvent<HTMLButtonElement>) => {
    if (!connected || busy) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;

    const press = zoomPressRef.current;
    if (press.timerId !== null) {
      window.clearTimeout(press.timerId);
    }
    press.active = true;
    press.holding = false;
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {}

    press.timerId = window.setTimeout(() => {
      press.timerId = null;
      if (!press.active) return;
      press.holding = true;
      void client.startZoom(dir, 1).catch((err) => {
        onError(err instanceof Error ? err.message : String(err));
      });
    }, 400);
  };

  const handleZoomPointerEnd = (dir: 0 | 1) => {
    const press = zoomPressRef.current;
    if (press.timerId !== null) {
      window.clearTimeout(press.timerId);
      press.timerId = null;
    }
    if (!press.active) return;
    const wasHolding = press.holding;
    press.active = false;
    press.holding = false;

    if (wasHolding) {
      void client.stopZoom().catch((err) => {
        onError(err instanceof Error ? err.message : String(err));
      });
    } else {
      void stepZoom(dir);
    }
  };

  const renderZoomButton = (dir: 0 | 1, label: string, hint: string) => (
    <button
      key={label}
      type="button"
      className="btn"
      disabled={!connected || busy}
      title={hint}
      aria-label={hint}
      onPointerDown={(e) => handleZoomPointerDown(dir, e)}
      onPointerUp={() => handleZoomPointerEnd(dir)}
      onPointerCancel={() => handleZoomPointerEnd(dir)}
      onClick={(e) => {
        if (e.detail === 0) void stepZoom(dir);
      }}
    >
      {label}
    </button>
  );

  const changeIso = async (val: number) => {
    setLocalIso(val);
    try {
      await client.setIso(val);
    } catch (err) {
      setLocalIso(null);
      throw err;
    }
  };

  const changeWbMode = async (mode: string) => {
    const prev = wbMode;
    setWbMode(mode);
    try {
      await client.setWhiteBalance(mode);
      const [actualMode, lb, cc] = await Promise.all([
        client.getWhiteBalance().catch(() => mode),
        client.getWhiteBalanceLightBalance().catch(() => wbLb),
        client.getWhiteBalanceColorComp().catch(() => wbCc),
      ]);
      setWbMode(actualMode);
      setWbLb(lb);
      setWbCc(cc);
      if (actualMode === 'color-temp') {
        const k = await client.getColorTemperature().catch(() => colorTemp);
        setColorTemp(k);
      }
    } catch (err) {
      setWbMode(prev);
      throw err;
    }
  };

  const changeColorTemp = async (kelvin: number) => {
    const prev = colorTemp;
    setColorTemp(kelvin);
    try {
      await client.setColorTemperature(kelvin);
    } catch (err) {
      setColorTemp(prev);
      throw err;
    }
  };

  const changeWbShift = async (axis: 'lb' | 'cc', delta: number) => {
    if (axis === 'lb') {
      const target = Math.max(-7, Math.min(7, wbLb + delta));
      if (target === wbLb) return;
      const prev = wbLb;
      setWbLb(target);
      try {
        await client.setWhiteBalanceLightBalance(target);
      } catch (err) {
        setWbLb(prev);
        throw err;
      }
    } else {
      const target = Math.max(-7, Math.min(7, wbCc + delta));
      if (target === wbCc) return;
      const prev = wbCc;
      setWbCc(target);
      try {
        await client.setWhiteBalanceColorComp(target);
      } catch (err) {
        setWbCc(prev);
        throw err;
      }
    }
  };

  const resetWbShift = async () => {
    const prevLb = wbLb;
    const prevCc = wbCc;
    setWbLb(0);
    setWbCc(0);
    try {
      if (prevLb !== 0) await client.setWhiteBalanceLightBalance(0);
      if (prevCc !== 0) await client.setWhiteBalanceColorComp(0);
    } catch (err) {
      setWbLb(prevLb);
      setWbCc(prevCc);
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
        {modeBadge && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span className="badge">{modeBadge}</span>
          </div>
        )}
      </div>

      <div className="exposure-grid">
        <div className="metric-card">
          <div className="metric-header-row">
            <span className="metric-label">Aperture</span>
            <span className="metric-value metric-value-inline">{aperture}</span>
            <button
              type="button"
              className={`btn btn-sm metric-auto-btn ${autoAperture ? 'btn-active' : ''}`}
              disabled={!connected || busy}
              aria-pressed={autoAperture}
              aria-label="Auto aperture"
              title="Toggle automatic aperture"
              onClick={() =>
                void runAction(() => setExposureAutoFlags(!autoAperture, autoShutter))
              }
            >
              Auto
            </button>
          </div>
          <div className="stepper-row">
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || autoAperture}
              onClick={() => runAction(() => client.decrementAperture())}
              title="Wider aperture (lower F-number)"
              aria-label="Wider aperture (F-)"
            >
              F-
            </button>
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || autoAperture}
              onClick={() => runAction(() => client.incrementAperture())}
              title="Narrower aperture (higher F-number)"
              aria-label="Narrower aperture (F+)"
            >
              F+
            </button>
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-header-row">
            <span className="metric-label">Shutter</span>
            <span className="metric-value metric-value-inline">{shutter}</span>
            <button
              type="button"
              className={`btn btn-sm metric-auto-btn ${autoShutter ? 'btn-active' : ''}`}
              disabled={!connected || busy}
              aria-pressed={autoShutter}
              aria-label="Auto shutter speed"
              title="Toggle automatic shutter speed"
              onClick={() =>
                void runAction(() => setExposureAutoFlags(autoAperture, !autoShutter))
              }
            >
              Auto
            </button>
          </div>
          <div className="stepper-row">
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || autoShutter}
              onClick={() => runAction(() => client.decrementShutter())}
              title="Slower shutter"
              aria-label="Slower shutter (S-)"
            >
              S-
            </button>
            <button
              type="button"
              className="btn"
              disabled={!connected || busy || autoShutter}
              onClick={() => runAction(() => client.incrementShutter())}
              title="Faster shutter"
              aria-label="Faster shutter (S+)"
            >
              S+
            </button>
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-header-row">
            <span className="metric-label">ISO</span>
          </div>
          <select
            className="select-input"
            style={{ width: '100%' }}
            disabled={!connected || busy}
            value={isoValue ?? ''}
            title="Select ISO sensitivity (or AUTO)"
            aria-label="ISO sensitivity"
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
            <span className="metric-value metric-value-inline">{evDisplay}</span>
            {evIndex !== 0 && connected && (
              <button
                type="button"
                className="metric-reset-btn"
                onClick={() => runAction(resetEv)}
                title={
                  !hasAutoExposure
                    ? 'EV compensation has no effect without Auto Aperture, Shutter, or ISO'
                    : 'Reset EV to ±0.0'
                }
                aria-label="Reset exposure compensation to 0"
                disabled={evDisabled}
              >
                Reset
              </button>
            )}
          </div>
          <div className="stepper-row">
            <button
              type="button"
              className="btn"
              disabled={evDisabled || evIndex <= evMin}
              onClick={() => runAction(() => changeEv(-1))}
              title={
                !hasAutoExposure
                  ? 'EV compensation requires Aperture, Shutter, or ISO set to Auto'
                  : 'Decrease exposure compensation (-1/3 EV)'
              }
              aria-label="Decrease exposure compensation (-1/3 EV)"
            >
              -
            </button>
            <button
              type="button"
              className="btn"
              disabled={evDisabled || evIndex >= evMax}
              onClick={() => runAction(() => changeEv(1))}
              title={
                !hasAutoExposure
                  ? 'EV compensation requires Aperture, Shutter, or ISO set to Auto'
                  : 'Increase exposure compensation (+1/3 EV)'
              }
              aria-label="Increase exposure compensation (+1/3 EV)"
            >
              +
            </button>
          </div>
        </div>

        <div className={`metric-card ${hasZoomCard ? '' : 'metric-card-wide'}`}>
          <div className="metric-header-row">
            <span className="metric-label">WB</span>
            <select
              className="select-input wb-select"
              disabled={!connected || busy}
              value={wbMode}
              title="Select white balance preset"
              aria-label="White balance mode"
              onChange={(e) => {
                if (e.target.value) {
                  void runAction(() => changeWbMode(e.target.value));
                }
              }}
            >
              <option value="" disabled>
                Select WB
              </option>
              {wbOptions.map((mode) => (
                <option key={mode} value={mode}>
                  {formatWbLabel(mode)}
                </option>
              ))}
            </select>
            {wbMode === 'color-temp' && (
              <select
                className="select-input wb-kelvin-select"
                disabled={!connected || busy}
                value={colorTemp}
                title="Select color temperature (Kelvin)"
                aria-label="Color temperature (K)"
                onChange={(e) => {
                  const k = Number(e.target.value);
                  if (!Number.isNaN(k)) {
                    void runAction(() => changeColorTemp(k));
                  }
                }}
              >
                {KELVIN_OPTIONS.map((k) => (
                  <option key={k} value={k}>
                    {k}K
                  </option>
                ))}
              </select>
            )}
          </div>
          <div className="wb-shift-row">
            <div className="wb-shift-group">
              <span className="muted-tag">A/B</span>
              <button
                type="button"
                className="btn btn-sm wb-step-btn"
                disabled={!connected || busy || wbLb <= -7}
                onClick={() => void runAction(() => changeWbShift('lb', -1))}
                title="Decrease WB Light Balance (A/B)"
                aria-label="Decrease WB A/B shift"
              >
                -
              </button>
              <span className="wb-shift-val">{wbLb > 0 ? `+${wbLb}` : wbLb}</span>
              <button
                type="button"
                className="btn btn-sm wb-step-btn"
                disabled={!connected || busy || wbLb >= 7}
                onClick={() => void runAction(() => changeWbShift('lb', 1))}
                title="Increase WB Light Balance (A/B)"
                aria-label="Increase WB A/B shift"
              >
                +
              </button>
            </div>
            <div className="wb-shift-group">
              <span className="muted-tag">G/M</span>
              <button
                type="button"
                className="btn btn-sm wb-step-btn"
                disabled={!connected || busy || wbCc <= -7}
                onClick={() => void runAction(() => changeWbShift('cc', -1))}
                title="Decrease WB Color Compensation (G/M)"
                aria-label="Decrease WB G/M shift"
              >
                -
              </button>
              <span className="wb-shift-val">{wbCc > 0 ? `+${wbCc}` : wbCc}</span>
              <button
                type="button"
                className="btn btn-sm wb-step-btn"
                disabled={!connected || busy || wbCc >= 7}
                onClick={() => void runAction(() => changeWbShift('cc', 1))}
                title="Increase WB Color Compensation (G/M)"
                aria-label="Increase WB G/M shift"
              >
                +
              </button>
            </div>
            {connected && (wbLb !== 0 || wbCc !== 0) && (
              <button
                type="button"
                className="metric-reset-btn"
                disabled={busy}
                onClick={() => void runAction(resetWbShift)}
                title="Reset WB A/B and G/M shift to 0"
                aria-label="Reset WB shift to 0"
              >
                Reset
              </button>
            )}
          </div>
        </div>

        {hasZoomCard && (
          <div className="metric-card">
            <div className="metric-header-row">
              <span className="metric-label">Zoom</span>
              <span className="metric-value metric-value-inline">{focalLabel ?? '--'}</span>
            </div>
            {state?.powerZoom ? (
              <div className="stepper-row">
                {renderZoomButton(
                  ZOOM_WIDE as 0 | 1,
                  'Zoom -',
                  'Zoom wide (tap for 1 step, hold for continuous zoom)',
                )}
                {renderZoomButton(
                  ZOOM_TELE as 0 | 1,
                  'Zoom +',
                  'Zoom tele (tap for 1 step, hold for continuous zoom)',
                )}
              </div>
            ) : (
              <div className="stepper-row">
                <span className="muted-tag">Manual / Prime</span>
              </div>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
