import { useRef, useState } from 'react';
import {
  type CameraClient,
  type CameraState,
  FOCUS_FAR,
  FOCUS_NEAR,
  ZOOM_TELE,
  ZOOM_WIDE,
} from '../api/cameraClient';

interface FocusControlProps {
  client: CameraClient;
  state: CameraState | null;
  connected: boolean;
  onRefresh: () => void;
  onError: (msg: string) => void;
}

export function FocusControl({
  client,
  state,
  connected,
  onRefresh,
  onError,
}: FocusControlProps) {
  const [busy, setBusy] = useState(false);
  const [startPos, setStartPos] = useState<string>('');
  const [endPos, setEndPos] = useState<string>('');
  const [steps, setSteps] = useState<number>(5);
  const [settleMs, setSettleMs] = useState<number>(400);
  const [stackStatus, setStackStatus] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const currentFocus = state?.focus?.currentPosition;
  const maxFocus = state?.focus?.maxPosition;
  const pct =
    typeof currentFocus === 'number' && typeof maxFocus === 'number' && maxFocus > 0
      ? Math.min(100, Math.max(0, (currentFocus / maxFocus) * 100))
      : 0;

  const stepFocus = async (dir: 0 | 1, speed: 1 | 2 | 3 | 4) => {
    setBusy(true);
    try {
      await client.focusStep(dir, speed);
      onRefresh();
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  const stepZoom = async (dir: 0 | 1) => {
    setBusy(true);
    try {
      await client.zoomStep(dir, 1);
      onRefresh();
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
      onRefresh();
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
            MF: {currentFocus ?? '--'} / {maxFocus ?? '--'}
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

      <div className="progress-track" title={`Focus ${currentFocus ?? 0} / ${maxFocus ?? 0}`}>
        <div className="progress-fill" style={{ width: `${pct}%` }} />
      </div>

      <div className="focus-buttons">
        <button
          type="button"
          className="btn"
          disabled={!connected || busy}
          onClick={() => stepFocus(FOCUS_NEAR, 4)}
          title="Near (Fast)"
        >
          &laquo;&laquo; Near
        </button>
        <button
          type="button"
          className="btn"
          disabled={!connected || busy}
          onClick={() => stepFocus(FOCUS_NEAR, 2)}
          title="Near (Medium)"
        >
          &laquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!connected || busy}
          onClick={() => stepFocus(FOCUS_NEAR, 1)}
          title="Near (Fine)"
        >
          &lsaquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!connected || busy}
          onClick={() => stepFocus(FOCUS_FAR, 1)}
          title="Far (Fine)"
        >
          &rsaquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!connected || busy}
          onClick={() => stepFocus(FOCUS_FAR, 2)}
          title="Far (Medium)"
        >
          &raquo;
        </button>
        <button
          type="button"
          className="btn"
          disabled={!connected || busy}
          onClick={() => stepFocus(FOCUS_FAR, 4)}
          title="Far (Fast)"
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
          {!abortRef.current ? (
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
          {stackStatus && <span className="muted-tag">{stackStatus}</span>}
        </div>
      </div>
    </section>
  );
}
