import React, { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import apiClient from '../services/apiClient';
import './Modal.css';

interface NewProjectModalProps {
  onClose: () => void;
}

const NewProjectModal: React.FC<NewProjectModalProps> = ({ onClose }) => {
  const [name, setName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [uploadProgress, setUploadProgress] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFile = e.target.files?.[0];
    if (selectedFile) {
      if (!selectedFile.name.endsWith('.zip')) {
        setError('Only ZIP files are allowed');
        return;
      }
      if (selectedFile.size > 50 * 1024 * 1024) {
        setError('File size must be less than 50MB');
        return;
      }
      setFile(selectedFile);
      setError('');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
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
      setUploadProgress('Creating project...');
      const createResponse = await apiClient.post('/projects', { name });
      const projectId = createResponse.data.id;

      // Upload ZIP
      setUploadProgress('Uploading file...');
      const formData = new FormData();
      formData.append('file', file);

      await apiClient.post(`/projects/${projectId}/upload`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });

      // Navigate to project
      navigate(`/projects/${projectId}`);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to create project');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2 className="modal-title">New Project</h2>
          <p className="modal-subtitle">Upload your codebase to start chatting</p>
        </div>

        <form className="modal-form" onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="projectName">
              Project Name
            </label>
            <input
              id="projectName"
              type="text"
              className="form-input"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="My Awesome Project"
              required
              autoFocus
            />
          </div>

          <div className="form-group">
            <label className="form-label">Codebase ZIP</label>
            <input
              ref={fileInputRef}
              type="file"
              accept=".zip"
              onChange={handleFileSelect}
              style={{ display: 'none' }}
            />
            <div 
              className="upload-area"
              onClick={() => fileInputRef.current?.click()}
            >
              <div className="upload-icon">📁</div>
              <div className="upload-text">
                {file ? file.name : 'Click to select ZIP file'}
              </div>
            </div>
          </div>

          {error && <div className="error-message">{error}</div>}
          
          {uploadProgress && (
            <div className="form-group">
              <div className="form-label" style={{ color: 'var(--m3-primary)' }}>
                {uploadProgress}
              </div>
              <div className="progress-bar-container">
                <div className="progress-bar-fill"></div>
              </div>
            </div>
          )}

          <div className="modal-actions">
            <button type="button" className="btn-outlined" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn-primary" disabled={loading}>
              {loading ? 'Creating...' : 'Create Project'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default NewProjectModal;