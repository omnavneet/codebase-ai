import React from 'react';
import './Modal.css';

interface CitationModalProps {
  citation: {
    file_path?: string;
    start_line: number;
    end_line: number;
    content?: string;
  } | null;
  onClose: () => void;
}

const CitationModal: React.FC<CitationModalProps> = ({ citation, onClose }) => {
  if (!citation) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2 className="modal-title">
            {citation.file_path || 'Code Snippet'}
          </h2>
          <p className="modal-subtitle">
            Lines {citation.start_line} - {citation.end_line}
          </p>
        </div>
        <pre style={{
          background: 'var(--bg-secondary)',
          padding: '16px',
          borderRadius: '6px',
          overflowX: 'auto',
          fontSize: '13px',
          lineHeight: '1.5',
        }}>
          {citation.content || 'Code content not available'}
        </pre>
        <div className="modal-actions">
          <button className="cancel-button" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default CitationModal;