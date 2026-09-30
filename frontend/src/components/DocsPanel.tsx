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

interface DocsPanelProps {
  projectId: string;
}

const docsIcon = (
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
 * Documentation mode: doc comments for one file, or a README for the project.
 * Anything saved lands in `__generated__/` and is listed below, so the mode
 * has memory — a generated doc can be reopened without regenerating it.
 */
const DocsPanel: React.FC<DocsPanelProps> = ({ projectId }) => {
  const { files, loading: filesLoading, error: filesError, reload } = useProjectFiles(projectId);
  const { toast } = useToast();

  const [selectedFile, setSelectedFile] = useState('');
  const [selectedSymbol, setSelectedSymbol] = useState('');
  const [documentation, setDocumentation] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [readmeLoading, setReadmeLoading] = useState(false);
  const [readme, setReadme] = useState('');
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

  const generateDocs = async () => {
    if (!selectedFile || loading) return;

    setLoading(true);
    setError('');

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
  };

  const generateReadme = async () => {
    if (readmeLoading) return;

    setReadmeLoading(true);
    setError('');

    try {
      const response = await apiClient.post(`/projects/${projectId}/agent/generate-readme`, {});
      setReadme(response.data.readme);
      toast({ title: 'README generated', tone: 'success' });
    } catch (err) {
      setError(getApiErrorMessage(err, 'The README could not be generated.'));
    } finally {
      setReadmeLoading(false);
    }
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
  };

  const viewGenerated = async (name: string) => {
    try {
      const response = await apiClient.get(`/projects/${projectId}/generated/${name}`);
      setGeneratedContent({ name, content: response.data.content });
    } catch (err) {
      setError(getApiErrorMessage(err, 'That generated file could not be opened.'));
    }
  };

  return (
    <div className="tool-panel">
      <section className="panel-card" aria-label="Generate documentation for a file">
        <div className="panel-card-header">
          <h3 className="panel-section-title">Document a file</h3>
        </div>

        <div className="panel-toolbar">
          <FilePicker
            id="docs-file"
            label="File"
            value={selectedFile}
            onChange={setSelectedFile}
            files={files}
            loading={filesLoading}
            error={filesError}
            onReload={() => void reload()}
          />
          <div className="panel-field panel-field-fixed">
            <label className="panel-field-label" htmlFor="docs-symbol">
              Symbol (optional)
            </label>
            <input
              id="docs-symbol"
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
              'Generate docs'
            )}
          </button>
        </div>
      </section>

      {error && <PanelError message={error} />}

      {loading && (
        <PanelRun label="Writing documentation" hint="Reading the file and the places it is used." />
      )}

      {!loading && !documentation && (
        <PanelEmpty
          icon={docsIcon}
          title="Generate documentation for this codebase"
          hint="Pick a file for doc comments, or generate a README for the whole project. Nothing overwrites your source — saved docs go to a new file under __generated__/."
        />
      )}

      {documentation && (
        <div className="panel-result">
          <section className="panel-card" aria-label="Generated documentation">
            <div className="panel-card-header">
              <h3 className="panel-section-title">
                {selectedSymbol ? `Doc · ${selectedSymbol}` : 'Generated documentation'}
              </h3>
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

      <section className="panel-card" aria-label="Project README">
        <div className="panel-card-header">
          <h3 className="panel-section-title">Project README</h3>
          <button
            type="button"
            className="ui-btn ui-btn-neutral ui-btn-sm"
            onClick={() => void generateReadme()}
            disabled={readmeLoading}
          >
            {readmeLoading ? (
              <>
                <span className="spinner spinner-xs" aria-hidden="true" />
                Generating…
              </>
            ) : readme ? (
              'Regenerate'
            ) : (
              'Generate README'
            )}
          </button>
        </div>

        {readmeLoading ? (
          <PanelRun
            label="Reading the whole project"
            hint="Summarising structure, entry points and setup — this can take a minute."
          />
        ) : readme ? (
          <>
            <pre className="panel-code">{readme}</pre>
            <div className="docs-actions">
              <CopyButton text={readme} label="Copy README" />
            </div>
          </>
        ) : (
          <p className="docs-note">
            Builds a README from the indexed project: what it is, how it is laid out, and how to
            run it.
          </p>
        )}
      </section>

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

export default DocsPanel;
