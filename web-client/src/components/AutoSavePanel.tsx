import { isDirectoryPickerSupported } from '../api/localSave';

interface AutoSavePanelProps {
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
  dirName: string | null;
  onPickDirectory: () => void;
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
  savedCount,
  lastSavedFile,
  savingStatus,
  connected,
  getLatestPhotoUrl,
  onOpenSavedPhoto,
}: AutoSavePanelProps) {
  const pickerSupported = isDirectoryPickerSupported();

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>Save</span>
          <button
            type="button"
            className={`btn btn-sm ${enabled ? 'btn-active' : ''}`}
            onClick={() => onToggle(!enabled)}
            aria-pressed={enabled}
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
        <div>
          {pickerSupported ? (
            <button
              type="button"
              className={`btn btn-sm ${enabled && !dirName ? 'btn-primary' : ''}`}
              onClick={onPickDirectory}
              title="Choose local folder for auto-saved photos"
            >
              {dirName ? `Folder: ${dirName}` : enabled ? 'Select Folder to Save' : 'Choose Local Folder'}
            </button>
          ) : (
            <span className="muted-tag">
              Browser download mode (HTTPS/localhost required for folder picker)
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
