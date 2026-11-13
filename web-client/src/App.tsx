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
          if (handledFilesRef.current.has(rawFileName)) return;
          handledFilesRef.current.add(rawFileName);

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

  // Initial state fetch + long-polling /api/events
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();

    const loop = async () => {
      await refreshState();
      while (!cancelled) {
        try {
          const res = await client.pollEvents(seqRef.current, 10, controller.signal);
          if (cancelled) break;
          setConnected(true);
          if (typeof res.seq === 'number') {
            seqRef.current = res.seq;
          }
          if (res.events && res.events.length > 0) {
            setEvents((prev) => [...res.events.slice().reverse(), ...prev].slice(0, 30));
            for (const ev of res.events) {
              if (ev.type === 'Jpeg' || ev.type === 'StoreImageComplete') {
                handleCaptureEvent(ev);
              }
            }
            const s = await client.getState();
            if (!cancelled) setState(s);
          }
        } catch (err) {
          if (cancelled || (err instanceof DOMException && err.name === 'AbortError')) {
            break;
          }
          setConnected(false);
          await new Promise((r) => setTimeout(r, 2000));
        }
      }
    };

    void loop();

    return () => {
      cancelled = true;
      controller.abort();
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
      await refreshState();
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
            />
            <div className="side-column">
              <ExposureControl
                client={client}
                state={state}
                connected={connected}
                onRefresh={refreshState}
                onError={setErrorMsg}
              />
              <FocusControl
                client={client}
                state={state}
                connected={connected}
                onRefresh={refreshState}
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
