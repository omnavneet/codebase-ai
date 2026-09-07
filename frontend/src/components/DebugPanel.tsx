import React, { useState } from 'react';
import apiClient from '../services/apiClient';
import {
  renderAnswerWithCitations,
  type AgentInvestigation,
} from './citationUtils';
import './Agent.css';
import './Modal.css';

interface DebugPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
}

const DebugPanel: React.FC<DebugPanelProps> = ({ projectId, onCitationClick }) => {
  const [description, setDescription] = useState('');
  const [stackTrace, setStackTrace] = useState('');
  const [filePath, setFilePath] = useState('');
  const [result, setResult] = useState<AgentInvestigation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleDebug = async () => {
    if (!description.trim() || loading) return;

    setLoading(true);
    setError('');
    setResult(null);

    try {
      const response = await apiClient.post(`/projects/${projectId}/agent/debug`, {
        issueDescription: description.trim(),
        stackTrace: stackTrace.trim() || null,
        filePath: filePath.trim() || null,
      });
      setResult(response.data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Debugging failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="debug-panel">
      <div className="debug-controls">
        <div className="form-group">
          <label className="form-label">Issue Description</label>
          <textarea
            className="debug-textarea"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Describe the issue... e.g., 'Login fails with 500 error when using special characters in password'"
            rows={3}
          />
        </div>

        <div className="form-group">
          <label className="form-label">Stack Trace (optional)</label>
          <textarea
            className="debug-textarea"
            value={stackTrace}
            onChange={(e) => setStackTrace(e.target.value)}
            placeholder="Paste any error/stack trace..."
            rows={5}
          />
        </div>

        <div className="form-group">
          <label className="form-label">Suspected File (optional)</label>
          <input
            className="debug-input"
            type="text"
            value={filePath}
            onChange={(e) => setFilePath(e.target.value)}
            placeholder="e.g., src/auth/AuthService.java"
          />
        </div>

        <button
          className="debug-button"
          onClick={handleDebug}
          disabled={!description.trim() || loading}
        >
          {loading ? 'Debugging...' : 'Debug Issue'}
        </button>
      </div>

      {error && <div className="error-message">{error}</div>}

      {loading && (
        <div className="agent-loading">
          <div className="loading-spinner" />
          <span>Investigating issue... this can take a minute</span>
        </div>
      )}

      {result && (
        <div className="debug-result">
          <div className="agent-trace">
            <h4>Debug Trace</h4>
            {result.trace.map((step, index) => (
              <div key={index} className="trace-step">✓ {step}</div>
            ))}
          </div>

          <div className="debug-answer">
            <h4>Root Cause &amp; Fix</h4>
            <div className="answer-content">
              {renderAnswerWithCitations(result.answer, onCitationClick)}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default DebugPanel;
