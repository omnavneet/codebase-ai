import React, { useCallback, useState } from 'react';
import apiClient from '../services/apiClient';
import {
  renderAnswerWithCitations,
  type AgentInvestigation,
} from './citationUtils';
import { useProjectFiles } from '../hooks/useProjectFiles';
import { getApiErrorMessage } from '../utils/apiError';
import { CopyButton, FilePicker, PanelEmpty, PanelError, PanelRun } from './PanelKit';

interface ExplainPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
}

const explainIcon = (
  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="10" />
    <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
    <line x1="12" y1="17" x2="12.01" y2="17" />
  </svg>
);

/**
 * Explain one file — or one symbol inside it. The form is the whole
 * interaction, so it leads; the answer is a trace plus an explanation, both of
 * which can cite the source they came from.
 */
const ExplainPanel: React.FC<ExplainPanelProps> = ({ projectId, onCitationClick }) => {
  const { files, loading: filesLoading, error: filesError, reload } = useProjectFiles(projectId);
  const [selectedFile, setSelectedFile] = useState('');
  const [symbol, setSymbol] = useState('');
  const [result, setResult] = useState<AgentInvestigation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const runExplain = useCallback(async () => {
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
    } catch (err) {
      setError(getApiErrorMessage(err, 'This file could not be explained.'));
    } finally {
      setLoading(false);
    }
  }, [loading, projectId, selectedFile, symbol]);

  return (
    <div className="tool-panel">
      <div className="panel-toolbar">
        <FilePicker
          id="explain-file"
          label="File"
          value={selectedFile}
          onChange={setSelectedFile}
          files={files}
          loading={filesLoading}
          error={filesError}
          onReload={() => void reload()}
        />

        <div className="panel-field panel-field-fixed">
          <label className="panel-field-label" htmlFor="explain-symbol">
            Symbol (optional)
          </label>
          <input
            id="explain-symbol"
            className="ui-input"
            type="text"
            value={symbol}
            onChange={(event) => setSymbol(event.target.value)}
            placeholder="Function or class"
          />
        </div>

        <button
          type="button"
          className="ui-btn ui-btn-primary"
          onClick={() => void runExplain()}
          disabled={!selectedFile || loading}
        >
          {loading ? (
            <>
              <span className="spinner spinner-xs" aria-hidden="true" />
              Explaining…
            </>
          ) : (
            'Explain'
          )}
        </button>
      </div>

      {error && <PanelError message={error} onRetry={() => void runExplain()} />}

      {loading && (
        <PanelRun
          label="Reading the file in context"
          hint="Following the calls it makes before summarising — this can take a minute."
        />
      )}

      {!loading && !error && !result && (
        <PanelEmpty
          icon={explainIcon}
          title="Walk through a file"
          hint="Pick a file to get a grounded summary of what it does, how it is called, and what it depends on."
        />
      )}

      {result && (
        <div className="panel-result">
          <section className="panel-card" aria-label="Analysis trace">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Analysis trace</h3>
              <span className="panel-meta">
                {result.filesRead.length} files read · {result.searchesPerformed.length} searches
              </span>
            </div>
            <ol className="panel-trace">
              {result.trace.map((step, index) => (
                <li key={index} className="panel-trace-step">
                  {step}
                </li>
              ))}
            </ol>
          </section>

          <section className="panel-card" aria-label="Explanation">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Explanation</h3>
              <CopyButton text={result.answer} label="Copy explanation" />
            </div>
            <div className="panel-answer">
              {renderAnswerWithCitations(result.answer, onCitationClick)}
            </div>
          </section>
        </div>
      )}
    </div>
  );
};

export default ExplainPanel;
