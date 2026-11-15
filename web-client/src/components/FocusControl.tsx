import { useEffect, useRef, useState } from 'react';
import {
  type CameraClient,
  type CameraState,
  computeFocusStackPositions,
  ZOOM_TELE,
  ZOOM_WIDE,
} from '../api/cameraClient';

interface FocusControlProps {
  client: CameraClient;
  state: CameraState | null;
  connected: boolean;
  onError: (msg: string) => void;
  onStackSessionChange?: (baseName: string | null) => void;
  openFocusAvailable: boolean;
  openFocusReason: string;
  autoMerge: boolean;
  onAutoMergeChange: (checked: boolean) => void;
  hideAutoMerge?: boolean;
  mergeStatus?: string | null;
  measuredStep?: { step: number; seq: number } | null;
  onStackSessionStart?: (baseName: string, autoMerge: boolean) => Promise<void> | void;
  onCaptureStackShot?: (triggerShot: () => Promise<unknown>, signal?: AbortSignal) => Promise<void>;
  onStackSessionEnd?: (
    baseName: string,
    autoMerge: boolean,
    aborted: boolean,
    expectedShots: number,
  ) => Promise<void> | void;
}

function formatCaptureBaseName(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `capture_${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

export function FocusControl({
  client,
  state,
  connected,
  onError,
  onStackSessionChange,
  openFocusAvailable,
  openFocusReason,
  autoMerge,
  onAutoMergeChange,
  hideAutoMerge = false,
  mergeStatus,
  measuredStep,
  onStackSessionStart,
  onCaptureStackShot,
  onStackSessionEnd,
}: FocusControlProps) {
  const [busy, setBusy] = useState(false);
  const [stackRunning, setStackRunning] = useState(false);
  const [startPos, setStartPos] = useState<string>('');
  const [endPos, setEndPos] = useState<string>('');
  const [stepInput, setStepInput] = useState<string>('4');
  const [settleInput, setSettleInput] = useState<string>('400');
  const [stackStatus, setStackStatus] = useState<string | null>(null);

  useEffect(() => {
    if (!measuredStep) return;
    setStepInput(String(measuredStep.step));
    setStackStatus(`Measured step: ${measuredStep.step}`);
  }, [measuredStep]);
  const [targetFocus, setTargetFocus] = useState<number | null>(null);
  const targetFocusRef = useRef<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const gotoAbortRef = useRef<AbortController | null>(null);
  const prefixClearTimerRef = useRef<number | null>(null);

  const parsedA = startPos !== '' ? Number(startPos) : NaN;
  const parsedB = endPos !== '' ? Number(endPos) : NaN;
  const parsedStep = stepInput.trim() !== '' ? Number(stepInput) : NaN;
  const isStepValid = Number.isInteger(parsedStep) && parsedStep >= 1;
  const plannedShots =
    !Number.isNaN(parsedA) && !Number.isNaN(parsedB) && isStepValid
      ? computeFocusStackPositions(parsedA, parsedB, parsedStep).length
      : null;

  const currentFocus = state?.focus?.currentPosition;
  const maxFocus = state?.focus?.maxPosition;
  const canDragFocus =
    connected && !stackRunning && typeof maxFocus === 'number' && maxFocus > 0;
  const displayFocus =
    targetFocus ?? (typeof currentFocus === 'number' ? currentFocus : 0);
  const pct =
    typeof maxFocus === 'number' && maxFocus > 0
      ? Math.min(100, Math.max(0, (displayFocus / maxFocus) * 100))
      : 0;

  const moveFocusTo = async (rawTarget: number) => {
    if (!canDragFocus || typeof maxFocus !== 'number') {
      targetFocusRef.current = null;
      setTargetFocus(null);
      return;
    }
    const target = Math.max(0, Math.min(maxFocus, Math.round(rawTarget)));
    targetFocusRef.current = target;
    setTargetFocus(target);

    gotoAbortRef.current?.abort();
    const controller = new AbortController();
    gotoAbortRef.current = controller;
    try {
      await client.gotoFocus(target, 0, controller.signal);
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        return;
      }
      onError(err instanceof Error ? err.message : String(err));
    } finally {
      if (gotoAbortRef.current === controller) {
        gotoAbortRef.current = null;
        targetFocusRef.current = null;
        setTargetFocus(null);
      }
    }
  };

  const commitFocusDrag = () => {
    const target = targetFocusRef.current;
    if (target === null) return;
    void moveFocusTo(target);
  };

  const stepFocus = (delta: number) => {
    if (typeof currentFocus !== 'number' || !canDragFocus) {
      return;
    }
    const base = targetFocusRef.current ?? currentFocus;
    void moveFocusTo(base + delta);
  };

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

  const handleStartStack = async () => {
    const a = Number(startPos);
    const b = Number(endPos);
    if (Number.isNaN(a) || Number.isNaN(b) || startPos === '' || endPos === '') {
      onError('Set both Start (A) and End (B) focus positions first');
      return;
    }
    const stepVal = stepInput.trim() !== '' ? Number(stepInput) : NaN;
    if (!Number.isInteger(stepVal) || stepVal < 1) {
      onError('Step must be an integer >= 1');
      return;
    }
    const settleVal = settleInput.trim() !== '' ? Number(settleInput) : NaN;
    if (Number.isNaN(settleVal) || settleVal < 0) {
      onError('Settle (ms) must be a valid number >= 0');
      return;
    }
    if (prefixClearTimerRef.current !== null) {
      window.clearTimeout(prefixClearTimerRef.current);
      prefixClearTimerRef.current = null;
    }
    const runBaseName = formatCaptureBaseName();
    const useAutoMerge = openFocusAvailable && autoMerge;
    const expectedShots = computeFocusStackPositions(a, b, stepVal).length;
    onStackSessionChange?.(runBaseName);

    const controller = new AbortController();
    abortRef.current = controller;
    setBusy(true);
    setStackRunning(true);
    setStackStatus('Starting focus stack...');
    let aborted = false;
    try {
      await onStackSessionStart?.(runBaseName, useAutoMerge);
      await client.runFocusStack(
        a,
        b,
        stepVal,
        settleVal,
        ({ index, total }) => {
          setTargetFocus(null);
          setStackStatus(`Shot ${index}/${total}`);
        },
        controller.signal,
        (targetPos) => {
          setTargetFocus(targetPos);
        },
        onCaptureStackShot,
      );
      setStackStatus('Focus stack complete');
    } catch (err) {
      aborted = true;
      if (err instanceof DOMException && err.name === 'AbortError') {
        setStackStatus('Focus stack aborted');
      } else {
        const msg = err instanceof Error ? err.message : String(err);
        setStackStatus(`Error: ${msg}`);
        onError(msg);
      }
    } finally {
      setTargetFocus(null);
      abortRef.current = null;
      setStackRunning(false);
      setBusy(false);
      void onStackSessionEnd?.(runBaseName, useAutoMerge, aborted, expectedShots);
      prefixClearTimerRef.current = window.setTimeout(() => {
        onStackSessionChange?.(null);
        prefixClearTimerRef.current = null;
      }, 3500);
    }
  };

  const handleAbortStack = () => {
    abortRef.current?.abort();
  };

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>Focus &amp; Zoom</span>
          <span className="badge">
            MF:{' '}
            {targetFocus !== null && targetFocus !== currentFocus
              ? `${currentFocus ?? '--'} → ${targetFocus}`
              : (currentFocus ?? '--')}{' '}
            / {maxFocus ?? '--'}
          </span>
        </div>
        {state?.powerZoom && (
          <div className="toolbar-row">
            {(
              [
                { dir: ZOOM_WIDE as 0 | 1, label: 'Zoom -', hint: 'Zoom wide (tap for 1 step, hold for continuous zoom)' },
                { dir: ZOOM_TELE as 0 | 1, label: 'Zoom +', hint: 'Zoom tele (tap for 1 step, hold for continuous zoom)' },
              ]
            ).map(({ dir, label, hint }) => (
              <button
                key={label}
                type="button"
                className="btn btn-sm"
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
            ))}
          </div>
        )}
      </div>

      <div
        className="focus-slider-wrap"
        title={`Focus ${displayFocus} / ${maxFocus ?? 0} (drag or click to move)`}
      >
        <div className="progress-track">
          <div
            className="progress-fill"
            style={{
              width: `${pct}%`,
              transition: targetFocus !== null ? 'none' : undefined,
            }}
          />
        </div>
        <input
          type="range"
          className="focus-slider"
          min={0}
          max={typeof maxFocus === 'number' && maxFocus > 0 ? maxFocus : 100}
          value={displayFocus}
          disabled={!canDragFocus}
          aria-label="Manual focus position"
          onChange={(e) => {
            const next = Number(e.target.value);
            targetFocusRef.current = next;
            setTargetFocus(next);
          }}
          onPointerUp={commitFocusDrag}
          onKeyUp={(e) => {
            if (
              e.key === 'ArrowLeft' ||
              e.key === 'ArrowRight' ||
              e.key === 'ArrowUp' ||
              e.key === 'ArrowDown' ||
              e.key === 'Home' ||
              e.key === 'End'
            ) {
              commitFocusDrag();
            }
          }}
        />
      </div>

      <div className="focus-buttons">
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(-20)}
          title="Focus Near (-20)"
          aria-label="Focus Near (-20)"
        >
          &laquo;&laquo; Near
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(-5)}
          title="Focus Near (-5)"
          aria-label="Focus Near (-5)"
        >
          &laquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(-1)}
          title="Focus Near (-1)"
          aria-label="Focus Near (-1)"
        >
          &lsaquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(1)}
          title="Focus Far (+1)"
          aria-label="Focus Far (+1)"
        >
          &rsaquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(5)}
          title="Focus Far (+5)"
          aria-label="Focus Far (+5)"
        >
          &raquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(20)}
          title="Focus Far (+20)"
          aria-label="Focus Far (+20)"
        >
          Far &raquo;&raquo;
        </button>
      </div>

      <div className="subpanel">
        <div className="subpanel-title">Focus Stacking (Client-Side Closed Loop)</div>
        <div className="stack-grid">
          <div className="field-inline">
            <label>Start A</label>
            <input
              type="number"
              className="input-sm"
              value={startPos}
              onChange={(e) => setStartPos(e.target.value)}
              placeholder="pos"
              aria-label="Focus stack Start A position"
              title="Start A focus position"
            />
            <button
              type="button"
              className="btn btn-sm"
              disabled={typeof currentFocus !== 'number'}
              onClick={() => setStartPos(String(currentFocus ?? ''))}
              title="Set Start A to current focus position"
              aria-label="Set Start A to current focus position"
            >
              Set
            </button>
          </div>

          <div className="field-inline">
            <label>End B</label>
            <input
              type="number"
              className="input-sm"
              value={endPos}
              onChange={(e) => setEndPos(e.target.value)}
              placeholder="pos"
              aria-label="Focus stack End B position"
              title="End B focus position"
            />
            <button
              type="button"
              className="btn btn-sm"
              disabled={typeof currentFocus !== 'number'}
              onClick={() => setEndPos(String(currentFocus ?? ''))}
              title="Set End B to current focus position"
              aria-label="Set End B to current focus position"
            >
              Set
            </button>
          </div>

          <div className="field-inline">
            <label>Step</label>
            <input
              type="number"
              min={1}
              max={500}
              className="input-sm"
              value={stepInput}
              placeholder="4"
              onChange={(e) => setStepInput(e.target.value)}
              aria-label="Focus stack step size"
              title="Focus step size between shots"
            />
            <span className="muted-tag">
              {plannedShots !== null ? `${plannedShots} shots` : '-- shots'}
            </span>
          </div>

          <div className="field-inline">
            <label>Settle (ms)</label>
            <input
              type="number"
              min={0}
              max={5000}
              step={50}
              className="input-sm"
              value={settleInput}
              placeholder="400"
              onChange={(e) => setSettleInput(e.target.value)}
              aria-label="Focus stack settle delay in milliseconds"
              title="Wait time in milliseconds after moving focus before shooting"
            />
          </div>
        </div>

        <div className="stack-actions" style={{ flexWrap: 'wrap' }}>
          {!hideAutoMerge && (
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginRight: 'auto' }}>
              <label className={`checkbox-row ${!openFocusAvailable ? 'is-disabled' : ''}`}>
                <input
                  type="checkbox"
                  disabled={!openFocusAvailable || stackRunning}
                  checked={openFocusAvailable && autoMerge}
                  onChange={(e) => onAutoMergeChange(e.target.checked)}
                />
                <span>Auto Merge</span>
              </label>
              {!openFocusAvailable && (
                <span className="info-tooltip-wrap">
                  <span className="info-icon" tabIndex={0} aria-label={openFocusReason}>
                    i
                  </span>
                  <span className="info-tooltip-bubble">{openFocusReason}</span>
                </span>
              )}
            </div>
          )}
          {(mergeStatus || stackStatus) && (
            <span className="muted-tag">
              {mergeStatus ?? stackStatus}
            </span>
          )}
          {!stackRunning ? (
            <button
              type="button"
              className="btn btn-primary btn-sm"
              disabled={!connected || busy}
              onClick={handleStartStack}
            >
              Run Focus Stack
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-danger btn-sm"
              onClick={handleAbortStack}
            >
              Abort Stack
            </button>
          )}
        </div>
      </div>
    </section>
  );
}
