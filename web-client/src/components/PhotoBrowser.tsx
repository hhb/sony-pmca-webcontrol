import { useCallback, useEffect, useState } from 'react';
import {
  type CameraClient,
  type DirEntry,
  type PhotoEntry,
  formatBytes,
} from '../api/cameraClient';
import { PhotoPreviewModal } from './PhotoPreviewModal';

interface PhotoBrowserProps {
  client: CameraClient;
  connected: boolean;
  onError: (msg: string) => void;
}

export function PhotoBrowser({ client, connected, onError }: PhotoBrowserProps) {
  const [currentDir, setCurrentDir] = useState<string>('');
  const [dirs, setDirs] = useState<DirEntry[]>([]);
  const [photos, setPhotos] = useState<PhotoEntry[]>([]);
  const [totalInDir, setTotalInDir] = useState<number>(0);
  const [loading, setLoading] = useState(false);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewName, setPreviewName] = useState<string>('');

  const loadDir = useCallback(
    async (dirPath: string) => {
      if (!connected) return;
      setLoading(true);
      try {
        const res = await client.listPhotos(dirPath, 100);
        setCurrentDir(res.dir ?? dirPath);
        setDirs(res.dirs ?? []);
        setPhotos(res.photos ?? []);
        setTotalInDir(res.totalPhotosInDir ?? res.photos?.length ?? 0);
      } catch (err) {
        onError(err instanceof Error ? err.message : String(err));
      } finally {
        setLoading(false);
      }
    },
    [client, connected, onError],
  );

  useEffect(() => {
    if (connected) {
      void loadDir(currentDir);
    }
  }, [connected]); // eslint-disable-line react-hooks/exhaustive-deps

  const goUp = () => {
    if (!currentDir) return;
    const parts = currentDir.split('/').filter(Boolean);
    parts.pop();
    void loadDir(parts.join('/'));
  };

  const breadcrumbs = currentDir.split('/').filter(Boolean);

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>Photos (Directory Browser)</span>
          <span className="badge">{totalInDir} files</span>
        </div>
        <div className="toolbar-row">
          <button
            type="button"
            className="btn btn-sm"
            disabled={!connected}
            onClick={() => {
              setPreviewName('Latest Capture');
              setPreviewUrl(client.latestPhotoUrl(Date.now()));
            }}
            title="Preview the most recently captured photo"
          >
            Latest Photo
          </button>
          <button
            type="button"
            className="btn btn-sm"
            disabled={!connected || loading}
            onClick={() => void loadDir(currentDir)}
            title="Refresh current directory"
          >
            {loading ? 'Loading...' : 'Refresh'}
          </button>
        </div>
      </div>

      <div className="breadcrumb-bar">
        <button
          type="button"
          className="btn btn-sm"
          disabled={!connected || !currentDir}
          onClick={goUp}
          title="Go to parent directory"
          aria-label="Go to parent directory"
        >
          Up
        </button>
        <button
          type="button"
          className="crumb-btn"
          disabled={!connected}
          onClick={() => void loadDir('')}
        >
          DCIM
        </button>
        {breadcrumbs.map((part, idx) => {
          const path = breadcrumbs.slice(0, idx + 1).join('/');
          return (
            <span key={path} className="crumb-sep">
              /
              <button
                type="button"
                className="crumb-btn"
                disabled={!connected}
                onClick={() => void loadDir(path)}
              >
                {part}
              </button>
            </span>
          );
        })}
      </div>

      <div className="photo-list">
        {dirs.map((d) => (
          <button
            key={d.path}
            type="button"
            className="list-row list-row-dir"
            onClick={() => void loadDir(d.path)}
          >
            <span className="row-name">[DIR] {d.name}</span>
            <span className="row-meta">Open &rarr;</span>
          </button>
        ))}

        {photos.map((p) => {
          const dlUrl = client.photoDownloadUrl(p.path);
          return (
            <div key={p.path} className="list-row">
              <span className="row-name">{p.name}</span>
              <span className="row-meta">{formatBytes(p.size)}</span>
              <div className="row-actions">
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => {
                    setPreviewName(p.name);
                    setPreviewUrl(dlUrl);
                  }}
                >
                  View
                </button>
                <a className="btn btn-sm" href={dlUrl} download={p.name}>
                  Download
                </a>
              </div>
            </div>
          );
        })}

        {connected && !loading && dirs.length === 0 && photos.length === 0 && (
          <div className="empty-state">Empty directory</div>
        )}
        {!connected && <div className="empty-state">Connect to browse SD card</div>}
      </div>

      <PhotoPreviewModal
        url={previewUrl}
        title={previewName}
        onClose={() => setPreviewUrl(null)}
      />
    </section>
  );
}
