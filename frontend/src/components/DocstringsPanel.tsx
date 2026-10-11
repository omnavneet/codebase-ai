import React, { useCallback, useEffect, useState } from 'react';
import apiClient from '../services/apiClient';
import { useProjectFiles } from '../hooks/useProjectFiles';
import { getApiErrorMessage } from '../utils/apiError';
import { useToast } from '../context/ToastContext';
import { CopyButton, FilePicker, PanelEmpty, PanelError, PanelRun } from './PanelKit';

interface GeneratedFile {
  name: string;
  sizeBytes: number;
}

interface DocstringsPanelProps {
  projectId: string;
}

const docstringsIcon = (
  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <polyline points="14 2 14 8 20 8" />
    <line x1="16" y1="13" x2="8" y2="13" />
    <line x1="16" y1="17" x2="8" y2="17" />
  </svg>
);

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

/**
 * Per-file docstring generator. The result can be copied or saved to a NEW
 * file under `__generated__/` — the source is never modified, and saved docs
 * are listed below for reopening. No README generator: that whole-project
 * scan was expensive and rarely re-opened.
 */
const DocstringsPanel: React.FC<DocstringsPanelProps> = ({ projectId }) => {
  const { files, loading: filesLoading, error: filesError, reload } = useProjectFiles(projectId);
  const { toast } = useToast();

  const [selectedFile, setSelectedFile] = useState('');
  const [selectedSymbol, setSelectedSymbol] = useState('');
  const [documentation, setDocumentation] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [savedPath, setSavedPath] = useState('');
  const [saving, setSaving] = useState(false);
  const [generatedFiles, setGeneratedFiles] = useState<GeneratedFile[]>([]);
  const [generatedContent, setGeneratedContent] = useState<{ name: string; content: string } | null>(
    null,
  );

  const loadGenerated = useCallback(async () => {
    try {
      const response = await apiClient.get(`/projects/${projectId}/generated`);
      setGeneratedFiles(response.data);
    } catch (err) {
      console.error('Failed to fetch generated files:', err);
    }
  }, [projectId]);

  useEffect(() => {
    // Async fetch — every setState runs after the request resolves.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadGenerated();
  }, [loadGenerated]);

  const generateDocs = useCallback(async () => {
    if (!selectedFile || loading) return;

    setLoading(true);
    setError('');
    setDocumentation('');
    setSavedPath('');

    try {
      const response = await apiClient.post(`/projects/${projectId}/agent/generate-docs`, {
        filePath: selectedFile,
        symbol: selectedSymbol || null,
      });
      setDocumentation(response.data.documentation);
    } catch (err) {
      setError(getApiErrorMessage(err, 'Documentation could not be generated for this file.'));
    } finally {
      setLoading(false);
    }
  }, [loading, projectId, selectedFile, selectedSymbol]);

  /** Writes the generated documentation to a NEW file; the source is never touched. */
  const saveAsNewFile = useCallback(async () => {
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
      await loadGenerated();
      toast({
        title: 'Saved to __generated__',
        description: response.data.path,
        tone: 'success',
      });
    } catch (err) {
      setError(getApiErrorMessage(err, 'The document could not be saved.'));
    } finally {
      setSaving(false);
    }
  }, [documentation, loadGenerated, projectId, saving, selectedFile, selectedSymbol, toast]);

  const viewGenerated = useCallback(
    async (name: string) => {
      try {
        const response = await apiClient.get(`/projects/${projectId}/generated/${name}`);
        setGeneratedContent({ name, content: response.data.content });
      } catch (err) {
        setError(getApiErrorMessage(err, 'That generated file could not be opened.'));
      }
    },
    [projectId],
  );

  return (
    <div className="tool-panel">
      <div className="panel-toolbar">
        <FilePicker
          id="docstrings-file"
          label="File"
          value={selectedFile}
          onChange={setSelectedFile}
          files={files}
          loading={filesLoading}
          error={filesError}
          onReload={() => void reload()}
        />

        <div className="panel-field panel-field-fixed">
          <label className="panel-field-label" htmlFor="docstrings-symbol">
            Symbol (optional)
          </label>
          <input
            id="docstrings-symbol"
            className="ui-input"
            type="text"
            value={selectedSymbol}
            onChange={(event) => setSelectedSymbol(event.target.value)}
            placeholder="Function or class"
          />
        </div>

        <button
          type="button"
          className="ui-btn ui-btn-primary"
          onClick={() => void generateDocs()}
          disabled={!selectedFile || loading}
        >
          {loading ? (
            <>
              <span className="spinner spinner-xs" aria-hidden="true" />
              Generating…
            </>
          ) : (
            'Generate'
          )}
        </button>
      </div>

      {error && <PanelError message={error} onRetry={() => void generateDocs()} />}

      {loading && (
        <PanelRun
          label="Writing documentation"
          hint="Reading the file and drafting doc comments — this can take a minute."
        />
      )}

      {!loading && !error && !documentation && (
        <PanelEmpty
          icon={docstringsIcon}
          title="Generate docstrings for a file"
          hint="Pick a file to draft documentation comments. Nothing overwrites your source — saved docs go to a new file under __generated__/."
        />
      )}

      {documentation && (
        <div className="panel-result">
          <section className="panel-card" aria-label="Generated documentation">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Documentation</h3>
              <span className="panel-meta">{selectedFile}</span>
            </div>
            <pre className="panel-code">{documentation}</pre>
            <div className="docs-actions">
              <CopyButton text={documentation} label="Copy" />
              <button
                type="button"
                className="ui-btn ui-btn-neutral"
                onClick={() => void saveAsNewFile()}
                disabled={saving || !selectedFile}
              >
                {saving ? (
                  <>
                    <span className="spinner spinner-xs" aria-hidden="true" />
                    Saving…
                  </>
                ) : (
                  'Save as new file'
                )}
              </button>
              <span className="docs-note">
                Saved under <code>__generated__/</code>; the source file is never modified.
              </span>
            </div>
            {savedPath && (
              <p className="docs-note" role="status">
                Saved to <code>{savedPath}</code>
              </p>
            )}
          </section>
        </div>
      )}

      <section className="panel-card" aria-label="Saved documents">
        <div className="panel-card-header">
          <h3 className="panel-section-title">Saved documents</h3>
          {generatedFiles.length > 0 && (
            <span className="panel-meta">
              {generatedFiles.length} {generatedFiles.length === 1 ? 'file' : 'files'}
            </span>
          )}
        </div>

        {generatedFiles.length === 0 ? (
          <p className="docs-note">
            Nothing saved yet. Generated docs stay in <code>__generated__/</code> and are not
            indexed.
          </p>
        ) : (
          generatedFiles.map((file) => (
            <div key={file.name} className="generated-file-row">
              <button
                type="button"
                className="ui-btn ui-btn-ghost ui-btn-sm"
                onClick={() => void viewGenerated(file.name)}
                title={`Open ${file.name}`}
              >
                {file.name}
              </button>
              <span className="panel-meta">{formatBytes(file.sizeBytes)}</span>
            </div>
          ))
        )}

        {generatedContent && (
          <div className="docs-generated-preview">
            <div className="panel-card-header">
              <h3 className="panel-section-title">{generatedContent.name}</h3>
              <CopyButton text={generatedContent.content} label="Copy" />
            </div>
            <pre className="panel-code">{generatedContent.content}</pre>
          </div>
        )}
      </section>
    </div>
  );
};

export default DocstringsPanel;
