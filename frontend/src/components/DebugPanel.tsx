import React, { useState } from 'react';
import apiClient from '../services/apiClient';
import {
  renderAnswerWithCitations,
  type AgentInvestigation,
} from './citationUtils';
import { type Finding, sortFindings } from './findings';
import { getApiErrorMessage } from '../utils/apiError';
import './Agent.css';
import './Modal.css';

interface StackFrame {
  file?: string | null;
  line?: number | null;
  symbol?: string | null;
  language?: string | null;
  raw?: string | null;
  project_path?: string;
}

interface DebugResult extends AgentInvestigation {
  findings?: Finding[];
  frames?: StackFrame[];
  project_frames?: StackFrame[];
  stack_trace_language?: string | null;
  stack_trace_parsed?: boolean;
  stack_trace_notes?: string[];
}

interface DebugPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
}

const DebugPanel: React.FC<DebugPanelProps> = ({ projectId, onCitationClick }) => {
  const [description, setDescription] = useState('');
  const [stackTrace, setStackTrace] = useState('');
  const [filePath, setFilePath] = useState('');
  const [result, setResult] = useState<DebugResult | null>(null);
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
    } catch (err) {
      setError(getApiErrorMessage(err, 'Debugging failed'));
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
          {result.frames && result.frames.length > 0 && (
            <div className="agent-trace">
              <h4>Parsed Stack Trace{result.stack_trace_language ? ` (${result.stack_trace_language})` : ''}</h4>
              {result.frames.slice(0, 6).map((frame, index) => {
                const inProject = result.project_frames?.some(
                  (candidate) => candidate.raw === frame.raw,
                );
                return (
                  <div key={index} className="trace-step">
                    {inProject ? '★' : '·'} {frame.symbol || '(unknown)'} — {frame.file}
                    {frame.line ? `:${frame.line}` : ''}
                    {inProject ? ' (in project)' : ''}
                  </div>
                );
              })}
              {result.stack_trace_notes?.map((note, index) => (
                <div key={`note-${index}`} className="trace-step">ℹ {note}</div>
              ))}
            </div>
          )}

          <div className="agent-trace">
            <h4>Debug Trace</h4>
            {result.trace.map((step, index) => (
              <div key={index} className="trace-step">✓ {step}</div>
            ))}
          </div>

          {result.findings && result.findings.length > 0 && (
            <div className="agent-result">
              <h4>Findings &amp; Suggested Fix</h4>
              {sortFindings(result.findings).map((finding, index) => (
                <div key={index} className="finding-card">
                  <div className="finding-header">
                    <span className={`finding-badge ${finding.severity}`}>{finding.severity}</span>
                    <span className="finding-badge category">{finding.category}</span>
                    <span className="finding-title">{finding.title}</span>
                    <span className="finding-lines">L{finding.lines}</span>
                  </div>
                  {finding.description && (
                    <div className="finding-text">{finding.description}</div>
                  )}
                  {finding.suggestion && (
                    <div className="finding-text suggestion">{finding.suggestion}</div>
                  )}
                  {finding.code_before && (
                    <div className="diff-block diff-before">
                      <div className="diff-label">Current</div>
                      <pre>{finding.code_before}</pre>
                    </div>
                  )}
                  {finding.code_after && (
                    <div className="diff-block diff-after">
                      <div className="diff-label">Suggested</div>
                      <pre>{finding.code_after}</pre>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

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
