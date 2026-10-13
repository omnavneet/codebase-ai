import React, { useState, useRef } from 'react';
import apiClient from '../services/apiClient';
import { getApiErrorMessage } from '../utils/apiError';
import Dialog from './Dialog';
import { ProgressBar } from './PanelKit';
import './Modal.css';

interface NewProjectModalProps {
  onClose: () => void;
  /** Called after the upload is accepted (202) so the dashboard can refresh. */
  onUploaded?: () => void;
}

const MAX_ZIP_BYTES = 50 * 1024 * 1024;

const uploadIcon = (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <polyline points="17 8 12 3 7 8" />
    <line x1="12" y1="3" x2="12" y2="15" />
  </svg>
);

/**
 * Creates a project and uploads its archive.
 *
 * The dialog shell (Escape to dismiss, focus trapping, scroll lock, focus
 * return) is delegated to `Dialog`; everything here is the form and the
 * upload feedback for a two-step operation — create, then upload. As soon as
 * the backend accepts the upload (202) the dialog closes: indexing runs in
 * the background and the dashboard card shows its progress.
 */
const NewProjectModal: React.FC<NewProjectModalProps> = ({ onClose, onUploaded }) => {
  const [name, setName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  /** Validation lives in one place so picking and dropping behave identically. */
  const acceptFile = (selectedFile: File | undefined | null) => {
    if (!selectedFile) return;
    if (!selectedFile.name.toLowerCase().endsWith('.zip')) {
      setError('Only ZIP archives can be indexed.');
      return;
    }
    if (selectedFile.size > MAX_ZIP_BYTES) {
      setError('That archive is larger than 50 MB.');
      return;
    }
    setFile(selectedFile);
    setError('');
  };

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    acceptFile(event.target.files?.[0]);
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (loading) return;

    if (!name.trim()) {
      setError('Project name is required');
      return;
    }

    if (!file) {
      setError('Please select a ZIP file');
      return;
    }

    setLoading(true);
    setError('');

    try {
      // Create project
      const createResponse = await apiClient.post('/projects', { name });
      const pid = createResponse.data.id;

      // Upload ZIP — the backend answers 202 Accepted as soon as the job is
      // queued. Indexing continues in the background: close immediately and
      // let the dashboard card report progress (no navigation to chat).
      const formData = new FormData();
      formData.append('file', file);

      await apiClient.post(`/projects/${pid}/upload`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });

      onUploaded?.();
      onClose();
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to create project'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog labelledBy="new-project-title" onClose={onClose}>
      <div className="modal-header">
        <h2 className="modal-title" id="new-project-title">
          New project
        </h2>
        <p className="modal-subtitle">
          Upload a ZIP of the codebase. Indexing starts immediately after upload.
        </p>
      </div>

      <form className="modal-form" onSubmit={handleSubmit}>
        <div className="form-group">
          <label className="form-label" htmlFor="projectName">
            Project name
          </label>
          <input
            id="projectName"
            type="text"
            className="form-input"
            value={name}
            onChange={(event) => {
              setName(event.target.value);
              if (error) setError('');
            }}
            placeholder="payments-service"
            required
            autoFocus
            disabled={loading}
          />
        </div>

        <div className="form-group">
          <span className="form-label" id="zip-label">
            Codebase archive
          </span>
          <input
            ref={fileInputRef}
            type="file"
            accept=".zip"
            onChange={handleFileSelect}
            className="sr-only"
            aria-labelledby="zip-label"
          />
          <button
            type="button"
            className={`upload-area ${dragActive ? 'dragging' : ''}`}
            onClick={() => fileInputRef.current?.click()}
            onDragOver={(event) => {
              event.preventDefault();
              setDragActive(true);
            }}
            onDragLeave={() => setDragActive(false)}
            onDrop={(event) => {
              event.preventDefault();
              setDragActive(false);
              acceptFile(event.dataTransfer.files?.[0]);
            }}
            disabled={loading}
          >
            <span className="upload-icon" aria-hidden="true">
              {uploadIcon}
            </span>
            <span className="upload-text">{file ? file.name : 'Choose a ZIP, or drop one here'}</span>
            <span className="upload-hint">
              {file
                ? `${(file.size / 1024 / 1024).toFixed(1)} MB · ready to upload`
                : 'ZIP only · up to 50 MB'}
            </span>
          </button>
        </div>

        {error && (
          <p className="ui-field-error" role="alert">
            {error}
          </p>
        )}

        {loading && (
          <div className="form-group" role="status" aria-live="polite">
            <ProgressBar stage="uploading" loading showCancel={false} />
          </div>
        )}

        <div className="modal-actions">
          <button
            type="button"
            className="ui-btn ui-btn-neutral"
            onClick={onClose}
            disabled={loading}
          >
            Cancel
          </button>
          <button type="submit" className="ui-btn ui-btn-primary" disabled={loading}>
            {loading ? 'Uploading…' : 'Create project'}
          </button>
        </div>
      </form>
    </Dialog>
  );
};

export default NewProjectModal;
