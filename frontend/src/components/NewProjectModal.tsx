import React, { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import apiClient from '../services/apiClient';
import { getApiErrorMessage } from '../utils/apiError';
import Dialog from './Dialog';
import './Modal.css';

interface NewProjectModalProps {
  onClose: () => void;
}

interface JobStatus {
  id: string;
  projectId: string;
  status: 'queued' | 'processing' | 'ready' | 'error' | 'cancelled';
  progressPercent: number;
  attempts: number;
  errorMessage: string | null;
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
 * progress feedback for a two-step operation — create, then upload.
 */
const NewProjectModal: React.FC<NewProjectModalProps> = ({ onClose }) => {
  const [name, setName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [uploadProgress, setUploadProgress] = useState('');
  const [jobStatus, setJobStatus] = useState<JobStatus | null>(null);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [cancelRequested, setCancelRequested] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();

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
      setUploadProgress('Creating project…');
      const createResponse = await apiClient.post('/projects', { name });
      const pid = createResponse.data.id;
      setProjectId(pid);

      // Upload ZIP — backend now returns 202 Accepted with job info
      setUploadProgress('Uploading archive…');
      const formData = new FormData();
      formData.append('file', file);

      const uploadResponse = await apiClient.post(`/projects/${pid}/upload`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });

      // Start polling job status
      const job: JobStatus = uploadResponse.data;
      setJobStatus(job);
      setUploadProgress(`Queued (${job.status})`);
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to create project'));
      setUploadProgress('');
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = async () => {
    if (!jobStatus || !projectId) return;
    setCancelRequested(true);
    try {
      await apiClient.post(`/projects/${projectId}/jobs/${jobStatus.id}/cancel`);
      setUploadProgress('Cancelling…');
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to cancel indexing job'));
      setCancelRequested(false);
    }
  };

  // Poll job status while job is active
  useEffect(() => {
    if (!jobStatus || !jobStatus.id || !projectId) return;
    if (jobStatus.status === 'ready' || jobStatus.status === 'error' || jobStatus.status === 'cancelled') {
      return;
    }

    const intervalId = window.setInterval(async () => {
      try {
        const response = await apiClient.get(`/projects/${projectId}/jobs/${jobStatus.id}`);
        const updated: JobStatus = response.data;
        setJobStatus(updated);

        if (updated.status === 'queued') {
          setUploadProgress(`Queued… (${updated.attempts > 0 ? `retry ${updated.attempts}` : ''})`);
        } else if (updated.status === 'processing') {
          setUploadProgress(`Indexing… ${updated.progressPercent}%`);
        } else if (updated.status === 'ready') {
          setUploadProgress('Indexing complete!');
          // Navigate to project after short delay
          setTimeout(() => {
            onClose();
            navigate(`/projects/${projectId}`);
          }, 800);
        } else if (updated.status === 'error') {
          setError(updated.errorMessage || 'Indexing failed');
          setUploadProgress('');
        } else if (updated.status === 'cancelled') {
          setUploadProgress('Cancelled');
        }
      } catch (err) {
        console.error('Failed to fetch job status:', err);
      }
    }, 2000);

    return () => window.clearInterval(intervalId);
  }, [jobStatus, projectId, navigate, onClose]);

  const renderProgress = () => {
    if (!jobStatus) return null;
    return (
      <div className="form-group" role="status" aria-live="polite">
        <span className="form-label progress-label">{uploadProgress}</span>
        <div className="progress-bar-container">
          <div
            className="progress-bar-fill"
            style={{ width: `${jobStatus.progressPercent}%` }}
          />
        </div>
      </div>
    );
  };

  const renderCancelButton = () => {
    if (!jobStatus) return null;
    if (jobStatus.status === 'ready' || jobStatus.status === 'error' || jobStatus.status === 'cancelled') {
      return null;
    }
    return (
      <button
        type="button"
        className="ui-btn ui-btn-neutral"
        onClick={handleCancel}
        disabled={loading || cancelRequested}
      >
        {cancelRequested ? 'Cancelling…' : 'Cancel indexing'}
      </button>
    );
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
            disabled={loading || !!jobStatus}
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
            disabled={loading || !!jobStatus}
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

        {uploadProgress && renderProgress()}

        <div className="modal-actions">
          <button
            type="button"
            className="ui-btn ui-btn-neutral"
            onClick={onClose}
            disabled={loading || !!jobStatus}
          >
            {jobStatus ? 'Back to dashboard' : 'Cancel'}
          </button>
          {jobStatus ? (
            renderCancelButton()
          ) : (
            <button type="submit" className="ui-btn ui-btn-primary" disabled={loading}>
              {loading ? (
                <>
                  <span className="spinner spinner-xs" aria-hidden="true" />
                  Working…
                </>
              ) : (
                'Create project'
              )}
            </button>
          )}
        </div>
      </form>
    </Dialog>
  );
};

export default NewProjectModal;
