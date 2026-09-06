import React, { useState, useEffect } from 'react';
import apiClient from '../services/apiClient';

interface DocsPanelProps {
  projectId: string;
}

const DocsPanel: React.FC<DocsPanelProps> = ({ projectId }) => {
  const [files, setFiles] = useState<string[]>([]);
  const [selectedFile, setSelectedFile] = useState<string>('');
  const [selectedSymbol, setSelectedSymbol] = useState<string>('');
  const [documentation, setDocumentation] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [readmeLoading, setReadmeLoading] = useState(false);
  const [readme, setReadme] = useState<string>('');

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

  const generateDocs = async () => {
    if (!selectedFile || loading) return;

    setLoading(true);
    setError('');
    setDocumentation('');

    try {
      const response = await apiClient.post(`/projects/${projectId}/agent/generate-docs`, {
        filePath: selectedFile,
        symbol: selectedSymbol || null,
      });
      setDocumentation(response.data.documentation);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to generate documentation');
    } finally {
      setLoading(false);
    }
  };

  const generateReadme = async () => {
    if (readmeLoading) return;

    setReadmeLoading(true);
    setError('');
    setReadme('');

    try {
      const response = await apiClient.post(`/projects/${projectId}/agent/generate-readme`, {});
      setReadme(response.data.readme);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to generate README');
    } finally {
      setReadmeLoading(false);
    }
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
  };

  return (
    <div className="docs-panel">
      <div className="docs-section">
        <h3>Generate Documentation</h3>

        <div className="docs-controls">
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
            value={selectedSymbol}
            onChange={(e) => setSelectedSymbol(e.target.value)}
            placeholder="Function/class name (optional)"
          />

          <button
            className="generate-button"
            onClick={generateDocs}
            disabled={!selectedFile || loading}
          >
            {loading ? 'Generating...' : 'Generate Docs'}
          </button>
        </div>

        {error && <div className="error-message">{error}</div>}

        {documentation && (
          <div className="documentation-result">
            <h4>Generated Documentation</h4>
            <pre className="docs-content">{documentation}</pre>
            <button className="copy-button" onClick={() => copyToClipboard(documentation)}>
              Copy to Clipboard
            </button>
          </div>
        )}
      </div>

      <div className="docs-section">
        <h3>Generate README</h3>
        <button
          className="generate-button"
          onClick={generateReadme}
          disabled={readmeLoading}
        >
          {readmeLoading ? 'Generating README... this can take a minute' : 'Generate Project README'}
        </button>

        {readme && (
          <div className="readme-result">
            <h4>Generated README</h4>
            <pre className="readme-content">{readme}</pre>
            <button className="copy-button" onClick={() => copyToClipboard(readme)}>
              Copy to Clipboard
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default DocsPanel;
