import { useRef, useState } from 'react';
import {
  type CameraClient,
  type CameraState,
  ZOOM_TELE,
  ZOOM_WIDE,
} from '../api/cameraClient';

interface FocusControlProps {
  client: CameraClient;
  state: CameraState | null;
  connected: boolean;
  onError: (msg: string) => void;
}

export function FocusControl({
  client,
  state,
  connected,
  onError,
}: FocusControlProps) {
  const [busy, setBusy] = useState(false);
  const [stackRunning, setStackRunning] = useState(false);
  const [startPos, setStartPos] = useState<string>('');
  const [endPos, setEndPos] = useState<string>('');
  const [steps, setSteps] = useState<number>(5);
  const [settleMs, setSettleMs] = useState<number>(400);
  const [stackStatus, setStackStatus] = useState<string | null>(null);
  const [targetFocus, setTargetFocus] = useState<number | null>(null);
  const targetFocusRef = useRef<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const gotoAbortRef = useRef<AbortController | null>(null);

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

  const handleStartStack = async () => {
    const a = Number(startPos);
    const b = Number(endPos);
    if (Number.isNaN(a) || Number.isNaN(b) || startPos === '' || endPos === '') {
      onError('Set both Start (A) and End (B) focus positions first');
      return;
    }
    const controller = new AbortController();
    abortRef.current = controller;
    setBusy(true);
    setStackRunning(true);
    setStackStatus('Starting focus stack...');
    try {
      await client.runFocusStack(
        a,
        b,
        steps,
        settleMs,
        ({ index, total, targetPos, actualPos }) => {
          setStackStatus(`Shot ${index}/${total} (target ${targetPos}, actual ${actualPos})`);
        },
        controller.signal,
      );
      setStackStatus('Focus stack complete');
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        setStackStatus('Focus stack aborted');
      } else {
        const msg = err instanceof Error ? err.message : String(err);
        setStackStatus(`Error: ${msg}`);
        onError(msg);
      }
    } finally {
      abortRef.current = null;
      setStackRunning(false);
      setBusy(false);
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
            <button
              type="button"
              className="btn btn-sm"
              disabled={!connected || busy}
              onClick={() => stepZoom(ZOOM_WIDE)}
            >
              Zoom -
            </button>
            <button
              type="button"
              className="btn btn-sm"
              disabled={!connected || busy}
              onClick={() => stepZoom(ZOOM_TELE)}
            >
              Zoom +
            </button>
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
          title="Near (-20)"
        >
          &laquo;&laquo; Near
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(-5)}
          title="Near (-5)"
        >
          &laquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(-1)}
          title="Near (-1)"
        >
          &lsaquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(1)}
          title="Far (+1)"
        >
          &rsaquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(5)}
          title="Far (+5)"
        >
          &raquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!canDragFocus || typeof currentFocus !== 'number'}
          onClick={() => stepFocus(20)}
          title="Far (+20)"
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
            />
            <button
              type="button"
              className="btn btn-sm"
              disabled={typeof currentFocus !== 'number'}
              onClick={() => setStartPos(String(currentFocus ?? ''))}
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
            />
            <button
              type="button"
              className="btn btn-sm"
              disabled={typeof currentFocus !== 'number'}
              onClick={() => setEndPos(String(currentFocus ?? ''))}
            >
              Set
            </button>
          </div>

          <div className="field-inline">
            <label>Shots</label>
            <input
              type="number"
              min={2}
              max={100}
              className="input-sm"
              value={steps}
              onChange={(e) => setSteps(Number(e.target.value) || 2)}
            />
          </div>

          <div className="field-inline">
            <label>Settle (ms)</label>
            <input
              type="number"
              min={100}
              max={5000}
              step={50}
              className="input-sm"
              value={settleMs}
              onChange={(e) => setSettleMs(Number(e.target.value) || 300)}
            />
          </div>
        </div>

        <div className="stack-actions">
          {stackStatus && <span className="muted-tag" style={{ marginRight: 'auto' }}>{stackStatus}</span>}
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
