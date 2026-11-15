import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  CameraClient,
  type CameraEvent,
  type CameraState,
  getDefaultCameraUrl,
} from './api/cameraClient';
import {
  isDirectoryPickerSupported,
  pickSaveDirectory,
  saveBlobToDirectory,
  triggerBrowserDownload,
} from './api/localSave';
import {
  backendStackResultUrl,
  createBackendStackSession,
  deleteBackendStackSession,
  fetchHostCapabilities,
  getBackendStackStatus,
  grabLatestToBackendStack,
  type HostCapabilities,
  triggerBackendStackMerge,
} from './api/hostBackend';
import { AutoSavePanel } from './components/AutoSavePanel';
import { EventsPanel, type LoggedCameraEvent } from './components/EventsPanel';
import { ExposureControl } from './components/ExposureControl';
import { FocusControl } from './components/FocusControl';
import { LiveViewPanel } from './components/LiveViewPanel';
import { PhotoBrowser } from './components/PhotoBrowser';
import { RpcInspector } from './components/RpcInspector';

type TabKey = 'control' | 'photos' | 'events' | 'rpc';

function applyEventsToState(
  prev: CameraState | null,
  events: CameraEvent[],
): { next: CameraState | null; needFullRefresh: boolean } {
  let needFullRefresh = false;
  if (!prev) {
    return { next: prev, needFullRefresh: true };
  }
  const next: CameraState = { ...prev };
  for (const ev of events) {
    const d = ev.data ?? {};
    switch (ev.type) {
      case 'FocusDrive':
        next.focus = d as CameraState['focus'];
        break;
      case 'ApertureChange':
        next.aperture = d as CameraState['aperture'];
        break;
      case 'ShutterSpeedChange':
        next.shutter = d as CameraState['shutter'];
        break;
      case 'AutoISOSensitivity':
        if (typeof d.iso === 'number') next.autoIso = d.iso;
        break;
      case 'ZoomChange':
        next.zoom = d as CameraState['zoom'];
        break;
      case 'FocalLengthChange':
        if (typeof d.focalLength === 'number') next.focalLength = d.focalLength;
        break;
      case 'PowerZoom':
        if (typeof d.status === 'number') next.powerZoom = d.status;
        break;
      case 'PreviewMagnification':
        next.previewMagnification = d as CameraState['previewMagnification'];
        break;
      case 'CaptureStart':
      case 'CaptureEnd':
        if (typeof d.capturing === 'boolean') next.capturing = d.capturing;
        break;
      case 'Shutter':
        if (typeof d.status === 'number') next.lastShutterStatus = d.status;
        break;
      case 'StoreImageComplete':
        next.lastStoreImage = d as CameraState['lastStoreImage'];
        break;
      case 'SettingChanged':
      case 'EquipmentChange':
        needFullRefresh = true;
        break;
    }
    next.seq = Math.max(next.seq ?? 0, ev.seq);
  }
  return { next, needFullRefresh };
}

function getEventsClientId(): string {
  const fallback = `ev-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  try {
    const existing = window.sessionStorage.getItem('pmca_ev_cid');
    if (existing) return existing;
    window.sessionStorage.setItem('pmca_ev_cid', fallback);
    return fallback;
  } catch {
    return fallback;
  }
}

export function App() {
  const [urlInput, setUrlInput] = useState<string>(() => getDefaultCameraUrl());
  const [activeUrl, setActiveUrl] = useState<string>(() => getDefaultCameraUrl());
  const client = useMemo(() => new CameraClient(activeUrl), [activeUrl]);

  const [connected, setConnected] = useState(false);
  const [state, setState] = useState<CameraState | null>(null);
  const [events, setEvents] = useState<LoggedCameraEvent[]>([]);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [tab, setTab] = useState<TabKey>('control');

  const [autoSaveEnabled, setAutoSaveEnabled] = useState<boolean>(() => {
    try {
      return localStorage.getItem('pmca_autosave_enabled') === 'true';
    } catch {
      return false;
    }
  });
  const [saveDirName, setSaveDirName] = useState<string | null>(null);
  const [savedCount, setSavedCount] = useState<number>(0);
  const [lastSavedFile, setLastSavedFile] = useState<string | null>(null);
  const [savingStatus, setSavingStatus] = useState<string | null>(null);
  const [hostCaps, setHostCaps] = useState<HostCapabilities>({
    backend: false,
    openfocus: {
      available: false,
      reason: 'Checking host backend...',
    },
  });
  const [autoMerge, setAutoMerge] = useState<boolean>(() => {
    try {
      return localStorage.getItem('pmca_automerge_enabled') === 'true';
    } catch {
      return false;
    }
  });
  const [mergeStatus, setMergeStatus] = useState<string | null>(null);
  const [measuredStep, setMeasuredStep] = useState<{ step: number; seq: number } | null>(null);

  const seqRef = useRef<number>(0);
  const dirHandleRef = useRef<FileSystemDirectoryHandle | null>(null);
  const activeUrlRef = useRef<string>(activeUrl);
  activeUrlRef.current = activeUrl;

  useEffect(() => {
    if (__CAMERA_EMBEDDED__) return;
    void fetchHostCapabilities().then(setHostCaps);
  }, []);
  const autoSaveRef = useRef<boolean>(false);
  autoSaveRef.current = !__CAMERA_EMBEDDED__ && hostCaps.backend && autoSaveEnabled;

  const refreshState = useCallback(async () => {
    try {
      const s = await client.getState();
      setState(s);
      seqRef.current = Math.max(seqRef.current, s.seq ?? 0);
      setConnected(true);
      setErrorMsg(null);
    } catch (err) {
      setConnected(false);
      setErrorMsg(err instanceof Error ? err.message : String(err));
    }
  }, [client]);

  const handledFilesRef = useRef<Set<string>>(new Set());
  const stackSessionRef = useRef<{ baseName: string; nextIndex: number } | null>(null);
  const backendMergeSessionRef = useRef<{ sessionId: string; pendingGrabs: Set<Promise<void>> } | null>(null);
  const stackShotWaitersRef = useRef<Array<() => void>>([]);
  const saveQueueRef = useRef<Array<{ name: string; url: string; resolve?: () => void }>>([]);
  const isProcessingQueueRef = useRef<boolean>(false);

  const processSaveQueue = useCallback(async () => {
    if (isProcessingQueueRef.current) return;
    isProcessingQueueRef.current = true;
    while (saveQueueRef.current.length > 0) {
      const task = saveQueueRef.current.shift();
      if (!task) break;
      setSavingStatus(`Saving ${task.name}...`);
      try {
        const res = await fetch(task.url);
        if (!res.ok) throw new Error(`Download failed: HTTP ${res.status}`);
        const blob = await res.blob();
        if (dirHandleRef.current) {
          await saveBlobToDirectory(dirHandleRef.current, task.name, blob);
        } else {
          triggerBrowserDownload(task.name, blob);
        }
        setSavedCount((c) => c + 1);
        setLastSavedFile(task.name);
      } catch (err) {
        setErrorMsg(err instanceof Error ? err.message : String(err));
      } finally {
        task.resolve?.();
      }
    }
    setSavingStatus(null);
    isProcessingQueueRef.current = false;
  }, []);

  const queueSavePhoto = useCallback(
    (task: { name: string; url: string }): Promise<void> => {
      return new Promise<void>((resolve) => {
        saveQueueRef.current.push({ ...task, resolve });
        void processSaveQueue();
      });
    },
    [processSaveQueue],
  );

  const handlePickDirectory = useCallback(async () => {
    try {
      const handle = await pickSaveDirectory();
      dirHandleRef.current = handle;
      setSaveDirName(handle.name);
      setAutoSaveEnabled(true);
      try {
        localStorage.setItem('pmca_autosave_enabled', 'true');
      } catch {}
      setErrorMsg(null);
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      setErrorMsg(err instanceof Error ? err.message : String(err));
    }
  }, []);

  const nextCaptureFileName = useCallback(() => {
    const stack = stackSessionRef.current;
    if (stack) {
      stack.nextIndex += 1;
      return `${stack.baseName}_${String(stack.nextIndex).padStart(3, '0')}.jpg`;
    }
    const d = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    return `capture_${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}.jpg`;
  }, []);

  const handleCaptureEvent = useCallback(
    (ev: CameraEvent) => {
      if (__CAMERA_EMBEDDED__) return;
      if (ev.type !== 'Jpeg') return;

      const dedupKey = `jpeg_${ev.seq}`;
      if (handledFilesRef.current.has(dedupKey)) return;
      if (handledFilesRef.current.size > 500) {
        handledFilesRef.current.clear();
      }
      handledFilesRef.current.add(dedupKey);

      const waiter = stackShotWaitersRef.current.shift();

      const mergeSession = backendMergeSessionRef.current;
      if (mergeSession) {
        const grabTask = grabLatestToBackendStack(mergeSession.sessionId, activeUrlRef.current)
          .then(() => undefined)
          .catch((err) => {
            setErrorMsg(err instanceof Error ? err.message : String(err));
          });
        mergeSession.pendingGrabs.add(grabTask);
        void grabTask.finally(() => {
          mergeSession.pendingGrabs.delete(grabTask);
          waiter?.();
        });
        return;
      }

      if (!autoSaveRef.current) {
        waiter?.();
        return;
      }

      const saveTask = queueSavePhoto({
        name: nextCaptureFileName(),
        url: client.latestPhotoUrl(Date.now()),
      });
      if (waiter) {
        void saveTask.finally(() => {
          waiter();
        });
      }
    },
    [client, nextCaptureFileName, queueSavePhoto],
  );

  const captureStackShot = useCallback(
    async (triggerShot: () => Promise<unknown>, signal?: AbortSignal) => {
      if (signal?.aborted) {
        throw new DOMException('Aborted', 'AbortError');
      }

      let done = false;
      let timerId: number | null = null;
      let finishWaiter: () => void = () => {};
      let cancelWaiter: (err: unknown) => void = () => {};

      const waitForJpeg = new Promise<void>((resolve, reject) => {
        const onAbort = () => {
          if (done) return;
          done = true;
          cleanup();
          reject(new DOMException('Aborted', 'AbortError'));
        };
        const cleanup = () => {
          if (timerId !== null) {
            window.clearTimeout(timerId);
            timerId = null;
          }
          const idx = stackShotWaitersRef.current.indexOf(finishWaiter);
          if (idx >= 0) {
            stackShotWaitersRef.current.splice(idx, 1);
          }
          if (signal) {
            signal.removeEventListener('abort', onAbort);
          }
        };
        finishWaiter = () => {
          if (done) return;
          done = true;
          cleanup();
          resolve();
        };
        cancelWaiter = (err: unknown) => {
          if (done) return;
          done = true;
          cleanup();
          reject(err);
        };

        stackShotWaitersRef.current.push(finishWaiter);
        timerId = window.setTimeout(() => {
          finishWaiter();
        }, 20000);
        if (signal) {
          signal.addEventListener('abort', onAbort, { once: true });
        }
      });

      try {
        await triggerShot();
      } catch (err) {
        cancelWaiter(err);
      }

      await waitForJpeg;
    },
    [],
  );

  const handleStackSessionStart = useCallback(
    async (baseName: string, useAutoMerge: boolean) => {
      if (!useAutoMerge) return;
      await createBackendStackSession(baseName, activeUrlRef.current);
      backendMergeSessionRef.current = {
        sessionId: baseName,
        pendingGrabs: new Set(),
      };
      setMergeStatus(null);
    },
    [],
  );

  const handleStackSessionEnd = useCallback(
    async (baseName: string, useAutoMerge: boolean, aborted: boolean, expectedShots: number) => {
      if (!useAutoMerge || !backendMergeSessionRef.current) return;
      const session = backendMergeSessionRef.current;

      if (aborted) {
        if (session.pendingGrabs.size > 0) {
          await Promise.allSettled(Array.from(session.pendingGrabs));
        }
        if (backendMergeSessionRef.current === session) {
          backendMergeSessionRef.current = null;
        }
        await deleteBackendStackSession(baseName);
        setMergeStatus(null);
        return;
      }

      const mergedFileName = `${baseName}.jpg`;
      try {
        setMergeStatus(`Verifying frames (0/${expectedShots})...`);
        setSavingStatus(`Verifying frames...`);

        // Wait up to 10s for final Jpeg SSE events and in-flight backend grabs to finish
        const deadline = Date.now() + 10000;
        let currentFrames = 0;
        while (true) {
          if (session.pendingGrabs.size > 0) {
            await Promise.allSettled(Array.from(session.pendingGrabs));
          }
          const stCheck = await getBackendStackStatus(baseName);
          currentFrames = stCheck.frameCount;
          setMergeStatus(`Verifying frames (${currentFrames}/${expectedShots})...`);
          if (currentFrames >= expectedShots && session.pendingGrabs.size === 0) {
            break;
          }
          if (Date.now() >= deadline) {
            break;
          }
          await new Promise((r) => window.setTimeout(r, 300));
        }

        if (backendMergeSessionRef.current === session) {
          backendMergeSessionRef.current = null;
        }

        if (currentFrames < expectedShots) {
          throw new Error(
            `Incomplete stack: expected ${expectedShots} photos, got ${currentFrames}. Skipping merge.`,
          );
        }

        setMergeStatus(`Merging ${currentFrames} frames with OpenFocus...`);
        setSavingStatus('Merging with OpenFocus...');
        await triggerBackendStackMerge(baseName, expectedShots);

        while (true) {
          await new Promise((r) => window.setTimeout(r, 1000));
          const st = await getBackendStackStatus(baseName);
          if (st.status === 'done') {
            await queueSavePhoto({
              name: mergedFileName,
              url: backendStackResultUrl(baseName),
            });
            setMergeStatus(`Merged ${currentFrames} frames (1 photo saved)`);
            break;
          }
          if (st.status === 'error') {
            throw new Error(st.error || 'OpenFocus merge failed');
          }
        }
      } catch (err) {
        if (backendMergeSessionRef.current === session) {
          backendMergeSessionRef.current = null;
        }
        const msg = err instanceof Error ? err.message : String(err);
        setErrorMsg(msg);
        setMergeStatus(`Merge error: ${msg}`);
        setSavingStatus(null);
      } finally {
        await deleteBackendStackSession(baseName);
      }
    },
    [queueSavePhoto],
  );

  const eventsCidRef = useRef<string>(getEventsClientId());

  useEffect(() => {
    const onPageHide = () => {
      client.closeEventsStream(eventsCidRef.current);
    };
    window.addEventListener('pagehide', onPageHide);
    return () => {
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [client]);

  // Initial state fetch + SSE /api/events/stream (kept open across tab switches so captures are never missed)
  useEffect(() => {
    let cancelled = false;
    let eventSource: EventSource | null = null;
    let needsResync = false;

    const handleIncomingEvents = async (incoming: CameraEvent[]) => {
      if (cancelled || incoming.length === 0) return;
      const now = Date.now();
      const logged: LoggedCameraEvent[] = incoming.map((ev) => ({
        ...ev,
        receivedAt: now,
      }));
      setEvents((prev) => [...logged.slice().reverse(), ...prev].slice(0, 200));
      let needFullRefresh = false;
      setState((prev) => {
        const applied = applyEventsToState(prev, incoming);
        needFullRefresh = applied.needFullRefresh;
        return applied.next;
      });
      for (const ev of incoming) {
        if (ev.type === 'Jpeg' || ev.type === 'StoreImageComplete') {
          handleCaptureEvent(ev);
        }
      }
      if (needFullRefresh) {
        await refreshState();
      }
    };

    const start = async () => {
      await refreshState();
      if (cancelled) return;

      const es = new EventSource(client.eventsStreamUrl(seqRef.current, eventsCidRef.current));
      eventSource = es;

      es.onopen = () => {
        if (cancelled) return;
        setConnected(true);
        if (needsResync) {
          needsResync = false;
          void refreshState();
        }
      };

      es.onmessage = (msg) => {
        if (cancelled || !msg.data) return;
        try {
          const ev = JSON.parse(msg.data) as CameraEvent;
          if (typeof ev.seq === 'number' && ev.seq > seqRef.current) {
            seqRef.current = ev.seq;
          }
          setConnected(true);
          void handleIncomingEvents([ev]);
        } catch {
          // Ignore malformed event payloads
        }
      };

      es.onerror = () => {
        if (cancelled) return;
        setConnected(false);
        needsResync = true;
      };
    };

    void start();

    return () => {
      cancelled = true;
      if (eventSource) {
        eventSource.close();
      }
      client.closeEventsStream(eventsCidRef.current);
    };
  }, [client, handleCaptureEvent, refreshState]);

  const handleConnectSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const cleaned = urlInput.trim().replace(/\/+$/, '');
    if (!cleaned) return;
    client.setBaseUrl(cleaned);
    setActiveUrl(cleaned);
    seqRef.current = 0;
  };

  const handleCapture = async () => {
    try {
      await client.takePicture();
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err));
    }
  };

  const wifiLabel =
    typeof state?.wifi === 'string'
      ? state.wifi
      : state?.wifi?.ip
        ? `${state.wifi.ip}:${state.wifi.port ?? 8080}`
        : null;

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className={`status-dot ${connected ? 'dot-ok' : 'dot-off'}`} />
          <span className="brand-title">Sony PMCA WebControl</span>
          {wifiLabel && <span className="muted-tag">{wifiLabel}</span>}
        </div>

        {!__CAMERA_EMBEDDED__ && (
          <form className="connect-form" onSubmit={handleConnectSubmit}>
            <input
              type="text"
              className="input-text url-input"
              value={urlInput}
              onChange={(e) => setUrlInput(e.target.value)}
              placeholder="http://192.168.0.250:8080"
              aria-label="Camera base URL"
              title="Camera HTTP server URL"
            />
            <button type="submit" className="btn btn-primary btn-sm" title="Connect to camera">
              Connect
            </button>
          </form>
        )}
      </header>

      {errorMsg && (
        <div className="error-banner">
          <span>{errorMsg}</span>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => setErrorMsg(null)}
            aria-label="Dismiss error"
          >
            Dismiss
          </button>
        </div>
      )}

      <nav className="tabs">
        <button
          type="button"
          className={`tab-btn ${tab === 'control' ? 'tab-active' : ''}`}
          onClick={() => setTab('control')}
        >
          Camera Control
        </button>
        <button
          type="button"
          className={`tab-btn ${tab === 'photos' ? 'tab-active' : ''}`}
          onClick={() => setTab('photos')}
        >
          Photos
        </button>
        <button
          type="button"
          className={`tab-btn ${tab === 'events' ? 'tab-active' : ''}`}
          onClick={() => setTab('events')}
        >
          Events
        </button>
        <button
          type="button"
          className={`tab-btn ${tab === 'rpc' ? 'tab-active' : ''}`}
          onClick={() => setTab('rpc')}
        >
          RPC Inspector
        </button>
      </nav>

      <main className="main-content">
        {tab === 'control' && (
          <div className="control-layout">
            <LiveViewPanel
              client={client}
              connected={connected}
              onCapture={handleCapture}
              capturing={Boolean(state?.capturing)}
              previewMagnification={state?.previewMagnification}
              currentFocus={state?.focus?.currentPosition}
              maxFocus={state?.focus?.maxPosition}
              onMeasuredStep={(step) => setMeasuredStep({ step, seq: Date.now() })}
              onError={setErrorMsg}
            />
            <div className="side-column">
              <ExposureControl
                client={client}
                state={state}
                connected={connected}
                onError={setErrorMsg}
              />
              <FocusControl
                client={client}
                state={state}
                connected={connected}
                onError={setErrorMsg}
                onStackSessionChange={(baseName) => {
                  stackSessionRef.current = baseName ? { baseName, nextIndex: 0 } : null;
                }}
                openFocusAvailable={!__CAMERA_EMBEDDED__ && hostCaps.openfocus.available}
                openFocusReason={hostCaps.openfocus.reason}
                autoMerge={autoMerge}
                hideAutoMerge={__CAMERA_EMBEDDED__}
                onAutoMergeChange={(checked) => {
                  setAutoMerge(checked);
                  try {
                    localStorage.setItem('pmca_automerge_enabled', String(checked));
                  } catch {}
                }}
                mergeStatus={mergeStatus}
                measuredStep={measuredStep}
                onStackSessionStart={__CAMERA_EMBEDDED__ ? undefined : handleStackSessionStart}
                onCaptureStackShot={__CAMERA_EMBEDDED__ ? undefined : captureStackShot}
                onStackSessionEnd={__CAMERA_EMBEDDED__ ? undefined : handleStackSessionEnd}
              />
              {!__CAMERA_EMBEDDED__ && (
                <AutoSavePanel
                  enabled={autoSaveEnabled}
                  onToggle={(next) => {
                    if (!hostCaps.backend) return;
                    if (next && isDirectoryPickerSupported() && !dirHandleRef.current) {
                      void handlePickDirectory();
                    } else {
                      setAutoSaveEnabled(next);
                      try {
                        localStorage.setItem('pmca_autosave_enabled', String(next));
                      } catch {}
                    }
                  }}
                  dirName={saveDirName}
                  onPickDirectory={() => void handlePickDirectory()}
                  backendAvailable={hostCaps.backend}
                  unavailableReason="Host backend not detected. Browser local Auto-Save requires running the UI via host-server/server.py due to browser filesystem permissions."
                  savedCount={savedCount}
                  lastSavedFile={lastSavedFile}
                  savingStatus={savingStatus}
                  connected={connected}
                  getLatestPhotoUrl={() => client.latestPhotoUrl()}
                />
              )}
            </div>
          </div>
        )}

        {tab === 'photos' && (
          <PhotoBrowser
            client={client}
            connected={connected}
            onError={setErrorMsg}
          />
        )}

        {tab === 'events' && (
          <EventsPanel events={events} onClear={() => setEvents([])} />
        )}

        {tab === 'rpc' && <RpcInspector client={client} connected={connected} />}
      </main>
    </div>
  );
}
