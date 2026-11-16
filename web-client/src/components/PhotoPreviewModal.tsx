import { useEffect, useState } from 'react';

interface PhotoPreviewModalProps {
  url: string | null;
  title: string;
  downloadName?: string;
  onClose: () => void;
}

export function PhotoPreviewModal({
  url,
  title,
  downloadName,
  onClose,
}: PhotoPreviewModalProps) {
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    setLoadFailed(false);
  }, [url]);

  useEffect(() => {
    if (!url) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [url, onClose]);

  if (!url) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <span>{title}</span>
          <div className="toolbar-row">
            {!loadFailed && (
              <a
                className="btn btn-sm"
                href={url}
                download={downloadName ?? (title.endsWith('.jpg') ? title : 'latest_capture.jpg')}
              >
                Download
              </a>
            )}
            <button type="button" className="btn btn-sm" onClick={onClose}>
              Close
            </button>
          </div>
        </div>
        {loadFailed ? (
          <div className="empty-state">No captured photo available yet</div>
        ) : (
          <img
            src={url}
            alt={title}
            className="modal-image"
            onError={() => setLoadFailed(true)}
          />
        )}
      </div>
    </div>
  );
}
