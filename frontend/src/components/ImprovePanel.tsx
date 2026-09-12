import React, { useState, useEffect } from 'react';
import apiClient from '../services/apiClient';
import './Agent.css';

interface ImprovementFinding {
  severity: string;
  category: string;
  title: string;
  lines: string;
  description: string;
  suggestion: string;
  code_before?: string | null;
  code_after?: string | null;
}

interface ImproveCodeResult {
  file_path: string;
  summary: string;
  findings: ImprovementFinding[];
}

interface ImprovePanelProps {
  projectId: string;
}

const SEVERITY_ORDER: Record<string, number> = { high: 0, medium: 1, low: 2 };

const ImprovePanel: React.FC<ImprovePanelProps> = ({ projectId }) => {
  const [files, setFiles] = useState<string[]>([]);
  const [selectedFile, setSelectedFile] = useState('');
  const [result, setResult] = useState<ImproveCodeResult | null>(null);
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

  const handleImprove = async () => {
    if (!selectedFile || loading) return;

    setLoading(true);
    setError('');
    setResult(null);

    try {
      const response = await apiClient.post(`/projects/${projectId}/agent/improve-code`, {
        filePath: selectedFile,
      });
      setResult(response.data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to review file');
    } finally {
      setLoading(false);
    }
  };

  const sortedFindings = result
    ? [...result.findings].sort(
        (a, b) =>
          (SEVERITY_ORDER[a.severity] ?? 99) - (SEVERITY_ORDER[b.severity] ?? 99),
      )
    : [];

  return (
    <div className="improve-panel">
      <div className="improve-controls">
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

        <button
          className="improve-button"
          onClick={handleImprove}
          disabled={!selectedFile || loading}
        >
          {loading ? 'Reviewing...' : 'Review File'}
        </button>
      </div>

      {error && <div className="error-message">{error}</div>}

      {loading && (
        <div className="agent-loading">
          <div className="loading-spinner" />
          <span>Reviewing file... this can take a moment</span>
        </div>
      )}

      {result && (
        <div className="improve-result">
          <div className="improve-summary">
            <h4>Review Summary</h4>
            <div className="summary-content">{result.summary}</div>
          </div>

          {sortedFindings.length === 0 ? (
            <div className="improve-clean">No significant issues found in this file.</div>
          ) : (
            sortedFindings.map((finding, index) => (
              <div key={index} className="finding-card">
                <div className="finding-header">
                  <span className={`finding-badge ${finding.severity}`}>
                    {finding.severity}
                  </span>
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
            ))
          )}
        </div>
      )}
    </div>
  );
};

export default ImprovePanel;
