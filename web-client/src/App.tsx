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
import { AutoSavePanel } from './components/AutoSavePanel';
import { ExposureControl } from './components/ExposureControl';
import { FocusControl } from './components/FocusControl';
import { LiveViewPanel } from './components/LiveViewPanel';
import { PhotoBrowser } from './components/PhotoBrowser';
import { RpcInspector } from './components/RpcInspector';

type TabKey = 'control' | 'photos' | 'rpc';

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
  const [events, setEvents] = useState<CameraEvent[]>([]);
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

  const seqRef = useRef<number>(0);
  const dirHandleRef = useRef<FileSystemDirectoryHandle | null>(null);
  const autoSaveRef = useRef<boolean>(false);
  autoSaveRef.current = autoSaveEnabled;

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
  const pendingJpegTimerRef = useRef<number | null>(null);
  const saveQueueRef = useRef<Array<{ name: string; url: string }>>([]);
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
      }
    }
    setSavingStatus(null);
    isProcessingQueueRef.current = false;
  }, []);

  const queueSavePhoto = useCallback(
    (task: { name: string; url: string }) => {
      saveQueueRef.current.push(task);
      void processSaveQueue();
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

  const handleCaptureEvent = useCallback(
    (ev: CameraEvent) => {
      if (!autoSaveRef.current) return;

      if (ev.type === 'StoreImageComplete') {
        // SD card write completed - cancel fallback timer if any
        if (pendingJpegTimerRef.current !== null) {
          window.clearTimeout(pendingJpegTimerRef.current);
          pendingJpegTimerRef.current = null;
        }

        const dirName = typeof ev.data?.DirectoryName === 'string' ? ev.data.DirectoryName : '';
        const rawFileName = typeof ev.data?.FileName === 'string' ? ev.data.FileName : '';
        if (rawFileName) {
          const dedupKey = dirName ? `${dirName}/${rawFileName}` : rawFileName;
          if (handledFilesRef.current.has(dedupKey)) return;
          if (handledFilesRef.current.size > 500) {
            handledFilesRef.current.clear();
          }
          handledFilesRef.current.add(dedupKey);

          // Direct path avoids slow SD directory scan (which stalls on cards with thousands of files)
          const filePath = dirName ? `/mnt/sdcard/DCIM/${dirName}/${rawFileName}` : rawFileName;
          queueSavePhoto({
            name: rawFileName,
            url: client.photoDownloadUrl(filePath),
          });
        } else {
          // StoreImageComplete without filename fallback
          const stamp = new Date().toISOString().replace(/[-:T.]/g, '').slice(0, 14);
          const name = `DSC_${stamp}_${ev.seq}.jpg`;
          if (handledFilesRef.current.has(name)) return;
          handledFilesRef.current.add(name);
          queueSavePhoto({
            name,
            url: client.latestPhotoUrl(Date.now()),
          });
        }
      } else if (ev.type === 'Jpeg') {
        // Memory capture event arrives before StoreImageComplete.
        // Buffer for 1.2s: if StoreImageComplete arrives, it cancels this timer and saves the high-res file.
        // If not (e.g. shooting without SD card), saves the in-memory JPEG frame.
        if (pendingJpegTimerRef.current !== null) {
          window.clearTimeout(pendingJpegTimerRef.current);
        }
        pendingJpegTimerRef.current = window.setTimeout(() => {
          pendingJpegTimerRef.current = null;
          const stamp = new Date().toISOString().replace(/[-:T.]/g, '').slice(0, 14);
          const name = `capture_${stamp}_${ev.seq}.jpg`;
          if (handledFilesRef.current.has(name)) return;
          handledFilesRef.current.add(name);
          queueSavePhoto({
            name,
            url: client.latestPhotoUrl(Date.now()),
          });
        }, 1200);
      }
    },
    [client, queueSavePhoto],
  );

  const eventsCidRef = useRef<string>(getEventsClientId());
  const [pageVisible, setPageVisible] = useState<boolean>(() =>
    typeof document === 'undefined' ? true : !document.hidden,
  );

  useEffect(() => {
    const onVisibilityChange = () => {
      setPageVisible(!document.hidden);
    };
    const onPageHide = () => {
      client.closeEventsStream(eventsCidRef.current);
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [client]);

  // Initial state fetch + SSE /api/events/stream (disconnects when tab is inactive, resyncs on reconnect)
  useEffect(() => {
    if (!pageVisible) {
      client.closeEventsStream(eventsCidRef.current);
      return;
    }

    let cancelled = false;
    let eventSource: EventSource | null = null;
    let needsResync = false;

    const handleIncomingEvents = async (incoming: CameraEvent[]) => {
      if (cancelled || incoming.length === 0) return;
      setEvents((prev) => [...incoming.slice().reverse(), ...prev].slice(0, 30));
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
  }, [client, handleCaptureEvent, pageVisible, refreshState]);

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

        <form className="connect-form" onSubmit={handleConnectSubmit}>
          <input
            type="text"
            className="input-text url-input"
            value={urlInput}
            onChange={(e) => setUrlInput(e.target.value)}
            placeholder="http://192.168.0.250:8080"
          />
          <button type="submit" className="btn btn-primary btn-sm">
            Connect
          </button>
        </form>
      </header>

      {errorMsg && (
        <div className="error-banner">
          <span>{errorMsg}</span>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => setErrorMsg(null)}
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
              />
              <AutoSavePanel
                enabled={autoSaveEnabled}
                onToggle={(next) => {
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
                savedCount={savedCount}
                lastSavedFile={lastSavedFile}
                savingStatus={savingStatus}
              />
              {events.length > 0 && (
                <section className="panel">
                  <div className="panel-header">
                    <span className="panel-title">Recent Events</span>
                  </div>
                  <div className="event-log">
                    {events.slice(0, 8).map((ev) => (
                      <div key={ev.seq} className="event-row">
                        <span className="badge">#{ev.seq}</span>
                        <span className="event-type">{ev.type}</span>
                      </div>
                    ))}
                  </div>
                </section>
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

        {tab === 'rpc' && <RpcInspector client={client} connected={connected} />}
      </main>
    </div>
  );
}
