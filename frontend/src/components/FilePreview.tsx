import React from 'react';
import './Modal.css';

interface FilePreviewProps {
  file: { path: string; content: string } | null;
  onClose: () => void;
}

const FilePreview: React.FC<FilePreviewProps> = ({ file, onClose }) => {
  if (!file) return null;
  
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div 
        className="modal-content" 
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: '800px', maxHeight: '80vh', overflow: 'auto' }}
      >
        <div className="modal-header">
          <h2 className="modal-title">{file.path}</h2>
        </div>
        <pre style={{
          background: 'var(--bg-secondary)',
          padding: '16px',
          borderRadius: '6px',
          overflowX: 'auto',
          fontSize: '13px',
          lineHeight: '1.5',
          maxHeight: '60vh',
          overflowY: 'auto',
        }}>
          {file.content}
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

export default FilePreview;