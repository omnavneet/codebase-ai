import React from 'react';
import Dialog from './Dialog';
import { CopyButton } from './PanelKit';
import './Modal.css';

interface CitationModalProps {
  citation: {
    file_path?: string;
    start_line: number;
    end_line: number;
    content?: string;
  } | null;
  onClose: () => void;
  /** Jumps from this snippet to the whole file in the viewer. */
  onOpenFile?: (filePath: string) => void;
}

const CitationModal: React.FC<CitationModalProps> = ({
  citation,
  onClose,
  onOpenFile,
}) => {
  if (!citation) return null;

  const path = citation.file_path ?? '';
  const segments = path.split('/');
  const name = segments[segments.length - 1] || 'Code snippet';
  const folder = segments.length > 1 ? segments.slice(0, -1).join('/') : '';

  return (
    <Dialog labelledBy="citation-title" onClose={onClose} size="wide">
      <div className="modal-header file-preview-header">
        <div className="file-preview-heading">
          {folder && <span className="file-preview-folder">{folder}/</span>}
          <h2 className="file-preview-title" id="citation-title">
            {name}
          </h2>
          <p className="file-preview-meta">
            Cited lines {citation.start_line}–{citation.end_line}
          </p>
        </div>

        <div className="file-preview-tools">
          {citation.content && <CopyButton text={citation.content} label="Copy snippet" />}
          {path && onOpenFile && (
            <button
              type="button"
              className="ui-btn ui-btn-ghost ui-btn-sm"
              onClick={() => onOpenFile(path)}
            >
              Open full file
            </button>
          )}
        </div>
      </div>

      {citation.content ? (
        <pre className="file-preview-code">{citation.content}</pre>
      ) : (
        <p className="citation-missing">
          This snippet is no longer available in the indexed source.
        </p>
      )}
    </Dialog>
  );
};

export default CitationModal;
