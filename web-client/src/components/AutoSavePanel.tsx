import { isDirectoryPickerSupported } from '../api/localSave';

interface AutoSavePanelProps {
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
  dirName: string | null;
  onPickDirectory: () => void;
  backendAvailable: boolean;
  unavailableReason: string;
  savedCount: number;
  lastSavedFile: string | null;
  savingStatus: string | null;
  connected: boolean;
  getLatestPhotoUrl: (inline?: boolean) => string;
  onOpenSavedPhoto?: () => void;
}

export function AutoSavePanel({
  enabled,
  onToggle,
  dirName,
  onPickDirectory,
  backendAvailable,
  unavailableReason,
  savedCount,
  lastSavedFile,
  savingStatus,
  connected,
  getLatestPhotoUrl,
  onOpenSavedPhoto,
}: AutoSavePanelProps) {
  const pickerSupported = isDirectoryPickerSupported();
  const effectiveEnabled = backendAvailable && enabled;

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span className={!backendAvailable ? 'is-disabled' : undefined} style={!backendAvailable ? { opacity: 0.55 } : undefined}>
            Save
          </span>
          {!backendAvailable && (
            <span className="info-tooltip-wrap">
              <span className="info-icon" tabIndex={0} aria-label={unavailableReason}>
                i
              </span>
              <span className="info-tooltip-bubble">{unavailableReason}</span>
            </span>
          )}
          <button
            type="button"
            className={`btn btn-sm ${effectiveEnabled ? 'btn-active' : ''}`}
            disabled={!backendAvailable}
            onClick={() => onToggle(!enabled)}
            aria-pressed={effectiveEnabled}
            title="Toggle automatic photo download after capture"
            aria-label="Toggle automatic photo download after capture"
          >
            Auto
          </button>
          {savedCount > 0 && <span className="badge">{savedCount} saved</span>}
        </div>
        <div className="toolbar-row">
          <a
            className={`btn btn-sm ${!connected ? 'is-disabled' : ''}`}
            href={connected ? getLatestPhotoUrl(false) : '#'}
            download="photo_latest.jpg"
            title="Download the most recently captured photo from the camera"
            aria-label="Download latest photo"
            aria-disabled={!connected}
            style={!connected ? { opacity: 0.5, pointerEvents: 'none' } : undefined}
            onClick={(e) => {
              if (!connected) {
                e.preventDefault();
                return;
              }
              e.currentTarget.href = getLatestPhotoUrl(false);
            }}
          >
            Download Latest
          </a>
          <a
            className={`btn btn-sm ${!connected ? 'is-disabled' : ''}`}
            href={connected ? getLatestPhotoUrl(true) : '#'}
            target="_blank"
            rel="noopener noreferrer"
            title="View the latest photo from the camera in a new tab"
            aria-label="View latest photo"
            aria-disabled={!connected}
            style={!connected ? { opacity: 0.5, pointerEvents: 'none' } : undefined}
            onClick={(e) => {
              if (!connected) {
                e.preventDefault();
                return;
              }
              e.currentTarget.href = getLatestPhotoUrl(true);
            }}
          >
            View Latest
          </a>
        </div>
      </div>

      <div className="autosave-row">
        <div
          className={!backendAvailable ? 'is-disabled' : undefined}
          style={!backendAvailable ? { opacity: 0.55 } : undefined}
        >
          {pickerSupported ? (
            <button
              type="button"
              className={`btn btn-sm ${effectiveEnabled && !dirName ? 'btn-primary' : ''}`}
              disabled={!backendAvailable}
              onClick={onPickDirectory}
              title="Choose local folder for auto-saved photos"
            >
              {dirName ? `Folder: ${dirName}` : effectiveEnabled ? 'Select Folder to Save' : 'Choose Local Folder'}
            </button>
          ) : (
            <span className="muted-tag">
              Folder picker unavailable (HTTPS/localhost Chromium required); will use browser download
            </span>
          )}
        </div>
        {savingStatus ? (
          <span className="autosave-status" title={savingStatus}>
            {savingStatus}
          </span>
        ) : (
          lastSavedFile && (
            <span className="autosave-status" title={`Saved: ${lastSavedFile}`}>
              {'Saved: '}
              {onOpenSavedPhoto ? (
                <button
                  type="button"
                  className="autosave-link"
                  onClick={onOpenSavedPhoto}
                  title={`Open local file ${lastSavedFile} in a new tab`}
                >
                  {lastSavedFile}
                </button>
              ) : (
                lastSavedFile
              )}
            </span>
          )
        )}
      </div>
    </section>
  );
}
