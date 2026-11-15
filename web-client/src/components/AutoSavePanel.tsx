import { isDirectoryPickerSupported } from '../api/localSave';

interface AutoSavePanelProps {
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
  dirName: string | null;
  onPickDirectory: () => void;
  backendAvailable: boolean;
  backendSaveDir?: string;
  savedCount: number;
  lastSavedFile: string | null;
  savingStatus: string | null;
}

export function AutoSavePanel({
  enabled,
  onToggle,
  dirName,
  onPickDirectory,
  backendAvailable,
  backendSaveDir,
  savedCount,
  lastSavedFile,
  savingStatus,
}: AutoSavePanelProps) {
  const pickerSupported = isDirectoryPickerSupported();

  return (
    <section className="panel">
      <div className="panel-header">
        <div className="panel-title">
          <span>Auto-Save</span>
          {savedCount > 0 && <span className="badge">{savedCount} saved</span>}
        </div>
        <button
          type="button"
          className={`btn btn-sm ${enabled ? 'btn-active' : ''}`}
          onClick={() => onToggle(!enabled)}
        >
          {enabled ? 'ON' : 'OFF'}
        </button>
      </div>

      <div className="autosave-row">
        {dirName ? (
          <button
            type="button"
            className="btn btn-sm"
            onClick={onPickDirectory}
          >
            {`Folder: ${dirName}`}
          </button>
        ) : backendAvailable ? (
          <>
            <span className="muted-tag" title={backendSaveDir}>
              {`Backend folder: ${backendSaveDir || './captures'}`}
            </span>
            {pickerSupported && (
              <button
                type="button"
                className="btn btn-sm"
                onClick={onPickDirectory}
              >
                Use Browser Folder
              </button>
            )}
          </>
        ) : pickerSupported ? (
          <button
            type="button"
            className={`btn btn-sm ${enabled ? 'btn-primary' : ''}`}
            onClick={onPickDirectory}
          >
            {enabled ? 'Select Folder to Save' : 'Choose Local Folder'}
          </button>
        ) : (
          <span className="muted-tag">
            Folder picker unavailable (HTTPS/localhost Chromium required); will use browser download
          </span>
        )}
      </div>

      {(savingStatus || lastSavedFile) && (
        <div className="autosave-status">
          {savingStatus ?? `Last saved: ${lastSavedFile}`}
        </div>
      )}
    </section>
  );
}
