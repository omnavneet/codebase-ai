import React, { useMemo } from 'react';
import Dialog from './Dialog';
import { CopyButton } from './PanelKit';
import './Modal.css';

interface FilePreviewProps {
  file: { path: string; content: string } | null;
  /**
   * True while the content request is in flight. The panel opens immediately
   * with the path it already knows and a skeleton, so clicking a file always
   * produces a visible reaction.
   */
  loading?: boolean;
  /** Set when the content request failed; keeps the panel from going blank. */
  error?: string;
  onRetry?: () => void;
  onClose: () => void;
}

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

const closeIcon = (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

const FilePreview: React.FC<FilePreviewProps> = ({
  file,
  loading = false,
  error = "",
  onRetry,
  onClose,
}) => {
  const stats = useMemo(() => {
    if (!file || !file.content) return null;
    const bytes = new TextEncoder().encode(file.content).length;
    return {
      lines: file.content.split('\n').length,
      size: formatBytes(bytes),
    };
  }, [file]);

  if (!file) return null;

  const segments = file.path.split('/');
  const name = segments[segments.length - 1] ?? file.path;
  const folder = segments.length > 1 ? segments.slice(0, -1).join('/') : '';

  return (
    <Dialog labelledBy="file-preview-title" onClose={onClose} size="wide">
      <div className="modal-header file-preview-header">
        <div className="file-preview-heading">
          {folder && <span className="file-preview-folder">{folder}/</span>}
          <h2 className="file-preview-title" id="file-preview-title">
            {name}
          </h2>
          <p className="file-preview-meta">
            {error
              ? 'Could not load this file'
              : loading || !stats
                ? 'Loading…'
                : `${stats.lines.toLocaleString()} lines · ${stats.size}`}
          </p>
        </div>

        <div className="file-preview-tools">
          {!loading && file.content && <CopyButton text={file.content} label="Copy file" />}
          <button
            type="button"
            className="ui-icon-btn"
            onClick={onClose}
            aria-label="Close file"
            title="Close (Esc)"
          >
            {closeIcon}
          </button>
        </div>
      </div>

      {loading ? (
        <div className="file-preview-skeleton" aria-hidden="true">
          {Array.from({ length: 12 }, (_, index) => (
            <span key={index} style={{ width: `${28 + ((index * 37) % 62)}%` }} />
          ))}
        </div>
      ) : error ? (
        <div className="file-preview-error" role="alert">
          <p>{error}</p>
          {onRetry && (
            <button type="button" className="ui-btn ui-btn-neutral ui-btn-sm" onClick={onRetry}>
              Try again
            </button>
          )}
        </div>
      ) : (
        <pre className="file-preview-code">{file.content}</pre>
      )}
    </Dialog>
  );
};

export default FilePreview;
