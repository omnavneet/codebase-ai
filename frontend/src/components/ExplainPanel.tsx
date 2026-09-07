import React, { useState, useEffect } from 'react';
import apiClient from '../services/apiClient';
import {
  renderAnswerWithCitations,
  type AgentInvestigation,
} from './citationUtils';
import './Agent.css';

interface ExplainPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
}

const ExplainPanel: React.FC<ExplainPanelProps> = ({ projectId, onCitationClick }) => {
  const [files, setFiles] = useState<string[]>([]);
  const [selectedFile, setSelectedFile] = useState('');
  const [symbol, setSymbol] = useState('');
  const [result, setResult] = useState<AgentInvestigation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    fetchFiles();
  }, [projectId]);

  const fetchFiles = async () => {
    try {
      const response = await apiClient.get(`/projects/${projectId}/files`);
      // Flatten file tree to a plain list
      const fileList: string[] = [];
      const flattenTree = (tree: any[]) => {
        tree.forEach(item => {
          if (item.type === 'file') {
            fileList.push(item.path);
          } else if (item.children) {
            flattenTree(item.children);
          }
        });
      };
      flattenTree(response.data);
      setFiles(fileList);
    } catch (error) {
      console.error('Failed to fetch files:', error);
    }
  };

  const handleExplain = async () => {
    if (!selectedFile || loading) return;

    setLoading(true);
    setError('');
    setResult(null);

    try {
      const response = await apiClient.post(`/projects/${projectId}/agent/explain-code`, {
        filePath: selectedFile,
        symbol: symbol || null,
      });
      setResult(response.data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to explain code');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="explain-panel">
      <div className="explain-controls">
        <select
          className="file-select"
          value={selectedFile}
          onChange={(e) => setSelectedFile(e.target.value)}
        >
          <option value="">Select a file...</option>
          {files.map((file, index) => (
            <option key={index} value={file}>{file}</option>
          ))}
        </select>

        <input
          className="symbol-input"
          type="text"
          value={symbol}
          onChange={(e) => setSymbol(e.target.value)}
          placeholder="Function/class name (optional)"
        />

        <button
          className="explain-button"
          onClick={handleExplain}
          disabled={!selectedFile || loading}
        >
          {loading ? 'Analyzing...' : 'Explain Code'}
        </button>
      </div>

      {error && <div className="error-message">{error}</div>}

      {loading && (
        <div className="agent-loading">
          <div className="loading-spinner" />
          <span>Analyzing code... this can take a minute</span>
        </div>
      )}

      {result && (
        <div className="explain-result">
          <div className="agent-trace">
            <h4>Analysis Trace</h4>
            {result.trace.map((step, index) => (
              <div key={index} className="trace-step">✓ {step}</div>
            ))}
          </div>

          <div className="explain-answer">
            <h4>Explanation</h4>
            <div className="answer-content">
              {renderAnswerWithCitations(result.answer, onCitationClick)}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ExplainPanel;
