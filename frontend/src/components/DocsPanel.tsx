import React, { useState, useEffect } from 'react';
import apiClient from '../services/apiClient';
import { flattenFileTree } from '../utils/fileTree';
import { getApiErrorMessage } from '../utils/apiError';

interface GeneratedFile {
  name: string;
  sizeBytes: number;
}

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
  const [savedPath, setSavedPath] = useState<string>('');
  const [saving, setSaving] = useState(false);
  const [generatedFiles, setGeneratedFiles] = useState<GeneratedFile[]>([]);
  const [generatedContent, setGeneratedContent] = useState<{ name: string; content: string } | null>(null);

  useEffect(() => {
    const fetchFiles = async () => {
      try {
        const response = await apiClient.get(`/projects/${projectId}/files`);
        setFiles(flattenFileTree(response.data));
      } catch (error) {
        console.error('Failed to fetch files:', error);
      }
    };
    const fetchGenerated = async () => {
      try {
        const response = await apiClient.get(`/projects/${projectId}/generated`);
        setGeneratedFiles(response.data);
      } catch (error) {
        console.error('Failed to fetch generated files:', error);
      }
    };
    fetchFiles();
    fetchGenerated();
  }, [projectId]);

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
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to generate documentation'));
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
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to generate README'));
    } finally {
      setReadmeLoading(false);
    }
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
  };

  /** Writes the generated documentation to a NEW file; the source is never touched. */
  const saveAsNewFile = async () => {
    if (!documentation || !selectedFile || saving) return;
    setSaving(true);
    setError('');
    try {
      const response = await apiClient.post(`/projects/${projectId}/generated`, {
        filePath: selectedFile,
        symbol: selectedSymbol || null,
        content: documentation,
      });
      setSavedPath(response.data.path);
      const list = await apiClient.get(`/projects/${projectId}/generated`);
      setGeneratedFiles(list.data);
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to save generated document'));
    } finally {
      setSaving(false);
    }
  };

  const viewGenerated = async (name: string) => {
    try {
      const response = await apiClient.get(`/projects/${projectId}/generated/${name}`);
      setGeneratedContent({ name, content: response.data.content });
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to open generated file'));
    }
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
            <button
              className="copy-button"
              onClick={saveAsNewFile}
              disabled={saving || !selectedFile}
              style={{ marginLeft: '8px' }}
            >
              {saving ? 'Saving...' : 'Save as new file'}
            </button>
            <p className="docs-note">
              Saving writes a new file under <code>__generated__/</code>; your source file is never modified.
            </p>
            {savedPath && (
              <div className="docs-note">Saved to <code>{savedPath}</code></div>
            )}
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
      <div className="docs-section">
        <h3>Generated files</h3>
        {generatedFiles.length === 0 ? (
          <p className="docs-note">Nothing saved yet. Generated docs stay in __generated__/ and are not indexed.</p>
        ) : (
          generatedFiles.map((file) => (
            <div key={file.name} className="generated-file-row">
              <button className="copy-button" onClick={() => viewGenerated(file.name)}>
                {file.name}
              </button>
            </div>
          ))
        )}
        {generatedContent && (
          <div className="documentation-result">
            <h4>{generatedContent.name}</h4>
            <pre className="docs-content">{generatedContent.content}</pre>
            <button className="copy-button" onClick={() => copyToClipboard(generatedContent.content)}>
              Copy to Clipboard
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default DocsPanel;
